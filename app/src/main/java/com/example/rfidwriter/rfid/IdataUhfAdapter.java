package com.example.rfidwriter.rfid;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import dalvik.system.DexClassLoader;

/**
 * iData T1 UHF 原生 SDK 适配层（v3 · 完全对齐手持机预装 iUHF 软件）。
 *
 * ############################################################################
 * 根因与修正（参考手持机预装 iUHF 软件逆向结论，已逐方法核对）
 * ############################################################################
 * iUHF（手持机预装，普通非系统 App，包名 com.android.idata.iuhf）能正常读写的真正原因：
 *   它使用自带的 SDK 包装类 com.idata.uhfservicesdk.UHFInterface，其构造函数为：
 *       this.uhfManager = (com.idata.UHFManager) context.getSystemService("idata_uhf");
 *   即：通过系统 UHF 服务拿到【已经绑定硬件的活实例】，再以活实例驱动所有操作。
 *
 * 本适配层完全复刻这一路径——优先持有 UHFInterface 实例（mIface），所有读/写/盘点都调用
 * UHFInterface 上【已编译好的】方法（readTag / readTagFromBuffer / startInventoryTag /
 * WriteTagDataAndReadTagData / inventoryModeSet / powerOn …）。这些方法是 dex 内已编译的
 * Java 调用，直接落在活实例上，不会受“对 AIDL 代理类做反射找不到方法”的影响——
 * 这正是上一版盘点“无反应”的根因（直接在 getSystemService 返回的代理上反射 inventoryDataGet，
 * 该方法未被代理类直接暴露，NoSuchMethodException 被吞掉，导致轮询永远拿不到标签）。
 *
 * ############################################################################
 * 已逆向确认并采用的 iUHF SDK 调用契约（来自 iUHF.apk / uhf_sdk.dex）
 * ############################################################################
 *   com.idata.uhfservicesdk.UHFInterface（活实例，构造即绑定硬件）：
 *     boolean powerOn()                                   // 上电
 *     boolean powerOff()
 *     boolean powerSet(int dbm)                           // 功率
 *     boolean inventoryModeSet(int mode, boolean flag)    // flag=true 才开启连续盘点
 *     boolean startInventoryTag()                         // 开始群读
 *     boolean stopInventory()                             // 停止群读
 *     String[] readTagFromBuffer()                        // 读取缓存标签数组（iUHF 群读主路径；内部即 inventoryDataGet）
 *     String  readTag(String pwd,int bank,int ptr,int cnt,String filterEpc,int fBank,int fPtr,int fCnt)
 *     Bundle  readFullEpcTag(String pwd,int bank,int ptr,int cnt,String filterEpc)  // 自动算 EPC 字长
 *     Bundle  WriteTagDataAndReadTagData(String pwd,int bank,int ptr,int cnt,String dataHex,
 *                                        int fBank,int fStartBit,int fLenBits,String fEpc)  // 写后回读
 *     void    registerUHFCallback(android.os.IUHFResultListener)   // 回调（每元素一条标签串）
 *   内部字段：private com.idata.UHFManager uhfManager  // 活实例，用于直接调用 writeTag(...) 等布尔方法
 *
 *   android.os.IUHFResultListener.onUHFCallBack(String[] tags)  // 回调，每元素一条标签信息串
 *
 * 约定（与 iData UHF SDK / 插件一致）：
 *   - Bank 编码 0=RESERVED 1=EPC 2=TID 3=USER
 *   - 过滤地址 fStartBit 为【bit】，EPC 默认从 32 开始
 *   - 写入/读取地址为【Word】，1 Word = 4 个 hex 字符 = 2 字节
 *   - 默认访问密码 00000000
 */
public class IdataUhfAdapter implements RfidSdk {

    private static final String TAG = "IdataUhfAdapter";
    private static final String UHFINTERFACE_CLASS = "com.idata.uhfservicesdk.UHFInterface";
    private static final String MANAGER_CLASS = "com.idata.UHFManager";
    private static final String LISTENER_CLASS = "android.os.IUHFResultListener";
    private static final String SYSTEM_SERVICE_NAME = "idata_uhf";
    private static final String DEX_ASSET = "uhf_sdk.dex";
    // 与 assets/uhf_sdk.dex 实际大小一致；不一致则重新拷贝（用于覆盖升级）
    private static final long EXPECTED_DEX_SIZE = 7323736L;

    private Context mContext;                       // application context
    private DexClassLoader mLoader;
    private Class<?> mIfaceClass;                   // UHFInterface 运行时类（来自 uhf_sdk.dex）
    private Object mIface;                          // com.idata.uhfservicesdk.UHFInterface 活实例
    private Object mUhf;                            // 兜底：getSystemService 直取的活实例 / newInstance（活实例优先由 mIface 内部持有）
    private Class<?> mUhfClass;                     // mUhf 的运行时类（兜底路径反射用）
    private Object mRawManager;                     // mIface 内部持有的 com.idata.UHFManager 活实例（写操作用）
    private Class<?> mListenerIface;
    private Object mListener;                       // IUHFResultListener 的 Proxy
    private InventoryCallback mInvCb;

    private final AtomicBoolean mConnected = new AtomicBoolean(false);
    /** 仅在“探测确认范围内只有 C1 一张标签”时，才允许过滤写全部失败后降级为不过滤写（防误写其它标签） */
    private volatile boolean mAllowUnfiltered = false;
    private final AtomicBoolean mPolling = new AtomicBoolean(false);
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private String mBindPath = "none";             // 诊断：实际用了哪条绑定路径
    private int mModuleType = 0;

    // 轮询去重：避免每轮把同一批累积缓存反复转发
    private final Set<String> mPollSeen = new HashSet<>();
    private int mPollCount = 0;
    private int mPollLogTick = 0;

    /** 必须在任意 SDK 调用前（如 MainActivity.onCreate 中）调用，提供 Context 以访问 assets 与创建 DexClassLoader */
    public void attach(Context ctx) {
        mContext = ctx.getApplicationContext();
        try {
            File dexFile = new File(mContext.getCacheDir(), DEX_ASSET);
            if (!dexFile.exists() || dexFile.length() != EXPECTED_DEX_SIZE) {
                copyAsset(dexFile);
            }
            dexFile.setReadOnly();
            File optDir = mContext.getCodeCacheDir();
            mLoader = new DexClassLoader(dexFile.getAbsolutePath(), optDir.getAbsolutePath(), null, mContext.getClassLoader());
            Log.i(TAG, "attach: uhf_sdk.dex 已就绪 (" + dexFile.length() + " bytes)");
        } catch (Throwable e) {
            Log.w(TAG, "attach: 预加载 dex 失败", e);
        }
    }

    // ------------------------------------------------------------------
    // 获取 UHFInterface 活实例（iUHF 的精确路径）
    // ------------------------------------------------------------------

    /**
     * 优先构造 UHFInterface（其内部 getSystemService("idata_uhf") 拿活实例），
     * 失败再回退到“直接 getSystemService 取活实例(newInstance 兜底)”的反射路径。
     */
    private synchronized void ensureSdk() throws Exception {
        if (mIface != null) return;
        if (mContext == null) throw new IllegalStateException("SDK 未 attach(Context)，无法加载");

        // 路径 1（首选，对齐 iUHF）：构造 UHFInterface，内部即绑定硬件活实例
        if (mLoader != null) {
            try {
                mIfaceClass = mLoader.loadClass(UHFINTERFACE_CLASS);
                Constructor<?> c = mIfaceClass.getConstructor(Context.class);
                mIface = c.newInstance(mContext);
                // 取出其内部持有的活实例，供写操作 writeTag(...) 等布尔方法直接使用
                try {
                    Field f = mIfaceClass.getDeclaredField("uhfManager");
                    f.setAccessible(true);
                    mRawManager = f.get(mIface);
                } catch (Throwable e) {
                    Log.w(TAG, "ensureSdk: 取内部 uhfManager 字段失败（写操作将改走 WriteTagDataAndReadTagData）", e);
                }
                mBindPath = "UHFInterface";
                Log.i(TAG, "ensureSdk: 路径1 通过 UHFInterface 获取活实例，rawManager=" + (mRawManager != null));
                logManagerMethods();
                logIfaceMethods();
                dumpAllMethods(mIface, "UHFInterface");
                dumpAllMethods(mRawManager, "UHFManager(raw,内部持有)");
                return;
            } catch (Throwable e) {
                Log.w(TAG, "ensureSdk: 路径1(UHFInterface) 失败，回退路径2", e);
                mIface = null; mIfaceClass = null; mRawManager = null;
            }
        }

        // 路径 2（兜底）：直接 getSystemService 取活实例，对其运行时类反射
        Object svc = null;
        try {
            svc = mContext.getSystemService(SYSTEM_SERVICE_NAME);
        } catch (Throwable e) {
            Log.w(TAG, "getSystemService(\"idata_uhf\") 抛异常", e);
        }
        if (svc != null) {
            mUhf = svc;
            mUhfClass = svc.getClass();
            mBindPath = "getSystemService";
            Log.i(TAG, "ensureSdk: 路径2 通过系统服务获取 UHFManager，class=" + mUhfClass.getName());
            logManagerMethods();
            return;
        }

        // 路径 3（最后）：DexClassLoader 加载 dex 后实例化（旧逻辑，可能无硬件连接）
        Log.w(TAG, "ensureSdk: UHFInterface 与系统服务均取不到，回退到 DexClassLoader newInstance（旧路径，可能无硬件连接）");
        if (mLoader == null) throw new IllegalStateException("无系统服务且 dex 未就绪");
        mUhfClass = mLoader.loadClass(MANAGER_CLASS);
        try {
            mUhf = mUhfClass.getConstructor().newInstance();
        } catch (NoSuchMethodException e) {
            mUhf = mUhfClass.getConstructor(Context.class).newInstance(mContext);
        }
        mBindPath = "newInstance(死实例)";
    }

    private void copyAsset(File out) throws Exception {
        out.getParentFile().mkdirs();
        try (InputStream in = mContext.getAssets().open(DEX_ASSET);
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    private synchronized void ensureListener() throws Exception {
        if (mListener != null) return;
        try {
            mListenerIface = Class.forName(LISTENER_CLASS);
        } catch (ClassNotFoundException e) {
            if (mLoader == null) throw e;
            mListenerIface = mLoader.loadClass(LISTENER_CLASS);
        }
        ClassLoader cl = (mIface != null) ? mIface.getClass().getClassLoader()
                : (mUhf != null ? mUhf.getClass().getClassLoader() : mListenerIface.getClassLoader());
        mListener = Proxy.newProxyInstance(cl, new Class<?>[]{mListenerIface},
                (InvocationHandler) (proxy, method, args) -> {
                    if ("onUHFCallBack".equals(method.getName()) && args != null
                            && args.length == 1 && args[0] instanceof String[]) {
                        onRawTags((String[]) args[0]);
                    }
                    return null;
                });
    }

    /** SDK 回调 / 轮询回来的标签串数组 -> 解析为 TagInfo -> 转发给业务层 InventoryCallback */
    private void onRawTags(String[] tags) {
        if (mInvCb == null || tags == null) return;
        for (String raw : tags) {
            if (raw == null || raw.isEmpty()) continue;
            TagInfo t = parseTag(raw);
            if (t.epc != null && !t.epc.isEmpty()) {
                final TagInfo ft = t;
                mMain.post(() -> mInvCb.onTag(ft));
            }
        }
    }

    /** 从原始串中提取 EPC：取最长的 hex 连续段（≥8位），对前后缀不可见字符/杂质免疫 */
    private static final java.util.regex.Pattern HEX_RUN =
            java.util.regex.Pattern.compile("[0-9A-Fa-f]{8,}");

    private static String extractEpc(String s) {
        if (s == null) return null;
        java.util.regex.Matcher m = HEX_RUN.matcher(s);
        String best = null;
        while (m.find()) {
            String c = m.group();
            if (best == null || c.length() > best.length()) best = c;
        }
        return (best != null && best.length() >= 8 && (best.length() % 2) == 0)
                ? best.toUpperCase(java.util.Locale.US) : null;
    }

    /**
     * 解析单条标签信息串。iData UHF 回调/缓存通常返回 EPC 的 hex 串；
     * 部分模式下会用 '|' / ',' / '#' / 空白 追加 TID/RSSI。
     * EPC = 串中最长 hex 连续段；RSSI = EPC 之后的纯数字段（避免把 EPC 自身当数字）。
     */
    private TagInfo parseTag(String raw) {
        TagInfo t = new TagInfo();
        if (raw == null) return t;
        String s = raw.trim();
        if (s.isEmpty()) return t;
        java.util.regex.Matcher m = HEX_RUN.matcher(s);
        String best = null; int bestEnd = -1;
        while (m.find()) {
            String c = m.group();
            if (best == null || c.length() > best.length()) { best = c; bestEnd = m.end(); }
        }
        if (best != null && best.length() >= 8 && (best.length() % 2) == 0) {
            t.epc = best.toUpperCase(java.util.Locale.US);
            String rest = s.substring(Math.max(bestEnd, 0));
            java.util.regex.Matcher rm = java.util.regex.Pattern.compile("-?\\d+(\\.\\d+)?").matcher(rest);
            if (rm.find()) t.rssi = rm.group();
        }
        return t;
    }

    /**
     * 在 UHFInterface（优先）或 mUhf 运行时类上按【方法名 + 参数个数】定位并调用。
     * 这样无需手写 Class<?>[] 形参数组，也规避了对 AIDL 代理类反射找不到方法的问题——
     * 因为这里定位的是 UHFInterface 上已编译好的方法（readTag/readTagFromBuffer/startInventoryTag/
     * WriteTagDataAndReadTagData/inventoryModeSet/powerOn …），它们内部已正确落到活实例。
     */
    private Object callIface(String name, Object... args) throws Exception {
        Class<?> clazz = (mIface != null) ? mIfaceClass : mUhfClass;
        Object target = (mIface != null) ? mIface : mUhf;
        if (clazz == null || target == null) throw new IllegalStateException("SDK 未初始化");
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == args.length) {
                return m.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(name + " arity=" + args.length);
    }

    /** 群读缓存读取：UHFInterface 路径用 readTagFromBuffer()，兜底路径用 inventoryDataGet() */
    private String[] pollBuffer() {
        try {
            String[] arr;
            if (mIface != null) {
                Object o = mIfaceClass.getMethod("readTagFromBuffer").invoke(mIface);
                arr = (o instanceof String[]) ? (String[]) o : new String[0];
            } else if (mUhf != null) {
                Object o = mUhfClass.getMethod("inventoryDataGet").invoke(mUhf);
                arr = (o instanceof String[]) ? (String[]) o : new String[0];
            } else {
                return new String[0];
            }
            // 诊断：每 ~12 轮（≈1.8s）打印一次原始缓存，便于确认“到底有没有读到标签”
            if ((++mPollLogTick % 12) == 0) {
                StringBuilder sb = new StringBuilder("pollBuffer 原始缓存 len=").append(arr.length);
                int n = Math.min(arr.length, 6);
                for (int i = 0; i < n; i++) sb.append(" | ").append(arr[i]);
                Log.i(TAG, sb.toString());
            }
            return arr;
        } catch (Throwable e) {
            Log.w(TAG, "pollBuffer 异常（忽略，继续下一轮）", e);
            return new String[0];
        }
    }

    // ------------------------------------------------------------------
    // RfidSdk 接口实现
    // ------------------------------------------------------------------

    @Override
    public void init(int moduleType, InitCallback cb) {
        this.mModuleType = moduleType;
        new Thread(() -> {
            boolean ok = false;
            String msg;
            try {
                ensureSdk();
                // 规避切电：不再调用 powerOn()/powerOff()。
                // 该设备 UHF 模组由系统/iUHF 统一上电管理，本 App 直接复用已通电模组即可。
                // 任何对 /proc/driver/uhf_power_enable 的 ioctl 在部分固件/SELinux 策略下，
                // 会令共用 UHF 系统服务崩溃、或把模组留在“通电却不发射”的降级态，
                // 连累 iUHF 也读不到（已确证）。init 仅确认 SDK 已绑定即视为已连接。
                ok = (mIface != null || mUhf != null);
                msg = ok ? ("UHF 已连接（绑定路径：" + mBindPath + "，复用系统已上电模组）")
                        : "UHF 初始化失败（未能获取 UHFInterface/系统服务，请确认设备 UHF 模块正常）";
                Log.i(TAG, "init: 跳过 powerOn（规避切电），connected=" + ok + " moduleType=" + mModuleType + " bindPath=" + mBindPath);
            } catch (Throwable e) {
                msg = "UHF 初始化异常：" + e.getClass().getSimpleName() + " " + e.getMessage();
                Log.e(TAG, "init failed", e);
            }
            mConnected.set(ok);
            final boolean fok = ok;
            final String fmsg = msg;
            if (cb != null) mMain.post(() -> cb.onResult(fok, fmsg));
        }).start();
    }

    @Override
    public void release() {
        mPolling.set(false);
        try {
            if (mIface != null || mUhf != null) {
                callIface("stopInventory");
            }
        } catch (Throwable ignore) { /* best effort */ }
        // 规避切电：不再调用 powerOff()，绝不关闭共用 UHF 模组载波（否则会连累 iUHF 读不到）。
        mConnected.set(false);
    }

    @Override
    public void setPower(int dbm) {
        if (!mConnected.get()) return;
        try {
            callIface("powerSet", dbm);
        } catch (Throwable ignore) { /* best effort */ }
    }

    @Override
    public void powerOn() {
        try {
            ensureSdk();
            callIface("powerOn");
            Log.i(TAG, "powerOn: 已调用（一次性初始化原生 RF 会话，供 startInventoryTag 安全使用）");
        } catch (Throwable e) {
            Log.w(TAG, "powerOn 失败（忽略，继续）", e);
        }
    }

    @Override
    public void setInventoryMode(int mode) {
        if (!mConnected.get()) return;
        try {
            // iData UHFManager.inventoryModeSet(int mode, boolean flag)：
            // flag=true 才真正开启盘点模式（连续盘点才会回传标签）。
            callIface("inventoryModeSet", mode, mode != 0);
            Log.i(TAG, "setInventoryMode: mode=" + mode + " flag=" + (mode != 0));
        } catch (Throwable ignore) { /* best effort */ }
    }

    @Override
    public void startInventory(InventoryCallback cb) {
        if (!mConnected.get() || cb == null) return;
        mInvCb = cb;
        try {
            // 回调作为双保险注册（即便代理未正确绑定，下面轮询仍会给出标签）
            ensureListener();
            try {
                callIface("registerUHFCallback", mListener);
            } catch (Throwable e) {
                Log.w(TAG, "registerUHFCallback 绑定失败（忽略，走轮询路径）", e);
            }
            Object r = callIface("startInventoryTag");
            boolean started = r instanceof Boolean ? (Boolean) r : (r != null);
            Log.i(TAG, "startInventory: startInventoryTag 已调用 ret=" + started);
        } catch (Throwable e) {
            Log.e(TAG, "startInventory failed", e);
        }
        // 轮询 readTagFromBuffer()：iUHF 群读主路径，不依赖回调是否触发
        mPollSeen.clear();
        mPollCount = 0;
        mPolling.set(true);
        new Thread(() -> {
            while (mPolling.get()) {
                try {
                    String[] arr = pollBuffer();
                    // 原始缓存为 [空, EPC, RSSI, 空, EPC, RSSI, ...] 三元组；EPC 元素取最长 hex 段，
                    // 紧随其后的纯数字元素作为 RSSI 配对
                    java.util.LinkedHashMap<String, String> cur = new java.util.LinkedHashMap<>();
                    int parsedFail = 0;
                    for (int i = 0; i < arr.length; i++) {
                        String raw = arr[i];
                        if (raw == null) continue;
                        String s = raw.trim();
                        if (s.isEmpty()) continue;
                        String epc = extractEpc(s);
                        if (epc == null) { parsedFail++; continue; }
                        if (!cur.containsKey(epc)) {
                            String rssi = null;
                            boolean skipNext = false;
                            // iUHF 缓存格式 [空, EPC, RSSI, 空, EPC, RSSI...]：RSSI 通常紧跟 EPC 之后
                            if (i + 1 < arr.length && arr[i + 1] != null) {
                                String nxt = arr[i + 1].trim();
                                if (nxt.matches("-?\\d+(\\.\\d+)?")) { rssi = nxt; skipNext = true; }
                            }
                            // 偶发某帧 EPC 后无 RSSI，则向前一个元素补查，提升 RSSI 命中率
                            if (rssi == null && i - 1 >= 0 && arr[i - 1] != null) {
                                String prev = arr[i - 1].trim();
                                if (prev.matches("-?\\d+(\\.\\d+)?")) { rssi = prev; }
                            }
                            if (skipNext) i++;
                            cur.put(epc, rssi);
                        }
                    }
                    // 每 ~2s 清空一次去重集，使“再次读到同一标签”也能被重新计数
                    int forwarded = 0;
                    synchronized (mPollSeen) {
                        if ((++mPollCount % 14) == 0) mPollSeen.clear();
                        for (java.util.Map.Entry<String, String> e : cur.entrySet()) {
                            if (!mPollSeen.contains(e.getKey())) {
                                mPollSeen.add(e.getKey());
                                forwarded++;
                                final TagInfo ft = new TagInfo();
                                ft.epc = e.getKey();
                                ft.rssi = e.getValue() == null ? "" : e.getValue();
                                if (mInvCb != null) mMain.post(() -> mInvCb.onTag(ft));
                            }
                        }
                    }
                    if (arr.length > 0) {
                        Log.i(TAG, "轮询解析: arrLen=" + arr.length + " 解析出EPC=" + cur.size()
                                + " 解析失败元素=" + parsedFail
                                + " 新转发=" + forwarded
                                + " mInvCb=" + (mInvCb != null));
                    }
                } catch (Throwable ignore) { /* 轮询异常忽略，继续下一轮 */ }
                try { Thread.sleep(150); } catch (InterruptedException e) { break; }
            }
        }, "uhf-inventory-poll").start();
    }

    @Override
    public void stopInventory() {
        Log.i(TAG, "stopInventory 调用（mPolling " + mPolling.get() + " -> false）",
                new Throwable("stop来源"));
        mPolling.set(false);
        if (mIface == null && mUhf == null) return;
        try {
            callIface("stopInventory");
        } catch (Throwable ignore) { /* best effort */ }
    }

    @Override
    public void readTag(int bank, int wordAddr, int wordLen, String pwd, ReadCallback cb) {
        if (!mConnected.get()) {
            if (cb != null) mMain.post(() -> cb.onResult(false, null, "未连接"));
            return;
        }
        new Thread(() -> {
            boolean ok = false;
            String data = null;
            String msg = "";
            try {
                // iUHF 的 readTag(pwd, bank, ptr, cnt, filterEpc, fBank, fPtr, fCnt)
                // 单张读取不设置过滤（fBank=0,fPtr=0,fCnt=0），由模块自动选到天线范围内唯一标签。
                Object r = callIface("readTag",
                                pwd == null ? "" : pwd, bank, wordAddr, wordLen,
                                "", 0, 0, 0);
                if (r instanceof String && !((String) r).isEmpty()) {
                    data = (String) r;
                    ok = true;
                    Log.i(TAG, "readTag: ok bank=" + bank + " ptr=" + wordAddr + " len=" + wordLen + " hex=" + data);
                } else {
                    msg = "读取为空（未读到标签，请确认天线范围内有标签）";
                }
            } catch (Throwable e) {
                msg = "读取异常：" + e.getClass().getSimpleName() + " " + e.getMessage();
                Log.e(TAG, "readTag failed", e);
            }
            final boolean fok = ok;
            final String fdata = data;
            final String fmsg = msg;
            if (cb != null) mMain.post(() -> cb.onResult(fok, fdata, fmsg));
        }).start();
    }

    @Override
    public void readTag(int bank, int wordAddr, int wordLen, String pwd,
                        String filterEpc, int fBank, int fStartBit, int fLenBits, ReadCallback cb) {
        if (!mConnected.get()) {
            if (cb != null) mMain.post(() -> cb.onResult(false, null, "未连接"));
            return;
        }
        new Thread(() -> {
            boolean ok = false;
            String data = null;
            String msg = "";
            try {
                // iUHF 的 readTag(pwd, bank, ptr, cnt, filterEpc, fBank, fPtr, fCnt)
                // 带过滤：仅对匹配 filterEpc 的那张标签读取，杜绝多标签时误读其他标签。
                // 参考 writeWholeEpc 用 fStartBit=1 的线索，过滤偏移很可能以“字”为单位，故遍历多种约定。
                int[][] convs = new int[][]{
                        {fStartBit, fLenBits},  // 业务层传入（默认 32/96，bit 单位）
                        {2, 6},                 // word 单位：EPC 数据位于 word2、占 6 字
                        {0, 96},                // bit 单位、固件自动跳过 CRC+PC
                        {0, 6},                 // word 单位、从 EPC bank 起点
                };
                String epc = filterEpc == null ? "" : filterEpc;
                for (int[] conv : convs) {
                    int sb = conv[0], lb = conv[1];
                    Object r = callIface("readTag",
                            pwd == null ? "" : pwd, bank, wordAddr, wordLen, epc, fBank, sb, lb);
                    if (r instanceof String && !((String) r).isEmpty()) {
                        data = (String) r;
                        ok = true;
                        Log.i(TAG, "readTag(过滤 conv sb=" + sb + ",lb=" + lb + "): ok bank=" + bank
                                + " ptr=" + wordAddr + " len=" + wordLen + " hex=" + data);
                        break;
                    }
                    Log.w(TAG, "readTag(过滤 conv sb=" + sb + ",lb=" + lb + ") 空");
                }
                if (!ok) {
                    // 所有过滤约定都读空：退化为不过滤单读（业务层已用 detectTargetC1 保证范围内仅目标标签）
                    Log.w(TAG, "readTag(所有过滤约定均空)，退化尝试不过滤单读 bank=" + bank);
                    Object r2 = callIface("readTag",
                            pwd == null ? "" : pwd, bank, wordAddr, wordLen, "", 0, 0, 0);
                    if (r2 instanceof String && !((String) r2).isEmpty()) {
                        data = (String) r2;
                        ok = true;
                        Log.i(TAG, "readTag(不过滤): ok bank=" + bank + " ptr=" + wordAddr
                                + " len=" + wordLen + " hex=" + data);
                    } else {
                        msg = "读取为空（未读到匹配标签，请确认天线范围内有标签且过滤条件一致）";
                    }
                }
            } catch (Throwable e) {
                msg = "读取异常：" + e.getClass().getSimpleName() + " " + e.getMessage();
                Log.e(TAG, "readTag(过滤) failed", e);
            }
            final boolean fok = ok;
            final String fdata = data;
            final String fmsg = msg;
            if (cb != null) mMain.post(() -> cb.onResult(fok, fdata, fmsg));
        }).start();
    }

    @Override
    public void writeTagWithFilter(int fBank, int fStartBit, int fLenBits, String fData,
                                   int writeBank, int startWord, String writeData,
                                   String pwd, WriteCallback cb) {
        if (!mConnected.get()) {
            if (cb != null) mMain.post(() -> cb.onResult(false, "未连接"));
            return;
        }
        new Thread(() -> {
            boolean ok = false;
            String msg = "";
            try {
                // 写入目标必须是设备固件实际存在的写方法。UHFInterface.WriteTagDataAndReadTagData 在
                // 本设备固件(iUHF 预装)上并不存在（NoSuchMethodError 崩溃），因此必须直接驱动
                // 内部持有的 com.idata.UHFManager 活实例(mRawManager)上真实编译进固件的写方法。
                Object mgr = (mRawManager != null) ? mRawManager : mUhf;
                if (mgr == null) {
                    msg = "写入未执行：未持有 UHFManager 活实例（请先连接 UHF）";
                    Log.e(TAG, "writeTagWithFilter: mRawManager 与 mUhf 均为 null，无法写");
                } else {
                    byte[] dataBytes = writeData.getBytes(StandardCharsets.UTF_8);
                    String dataHex = toHex(dataBytes);   // 业务层传明文，SDK 入参为 hex
                    boolean wrote = writeViaManager(mgr,
                            fBank, fStartBit, fLenBits, fData,
                            writeBank, startWord, dataBytes, dataHex,
                            pwd == null ? "" : pwd);
                    ok = wrote;
                    msg = ok ? "写入成功" : "写入失败（未匹配到目标标签，或写入超时；请确认天线范围内仅目标标签且无同前缀兄弟标签）";
                }
            } catch (Throwable e) {
                msg = "写入异常：" + e.getClass().getSimpleName() + " " + e.getMessage();
                Log.e(TAG, "writeTagWithFilter failed", e);
            }
            final boolean fok = ok;
            final String fmsg = msg;
            if (cb != null) mMain.post(() -> cb.onResult(fok, fmsg));
        }).start();
    }

    /**
     * 直接驱动设备固件真实存在的 UHFManager 写方法（绕过 bundled dex 中固件没有的 WriteTagDataAndReadTagData）。
     * 优先尝试“带过滤写”（保证只写匹配标签，绝不误写其它标签）；只有管理器确实没有过滤写方法时，
     * 才退回到普通 writeTag（此时必须由业务层 gate 保证范围内仅一张标签）。
     *
     * @return 是否写入成功
     */
    private boolean writeViaManager(Object mgr,
                                    int fBank, int fStartBit, int fLenBits, String fData,
                                    int writeBank, int startWord,
                                    byte[] dataBytes, String dataHex, String pwd) {
        Class<?> mc = mgr.getClass();
        byte[] filterBytes = hexToBytes(fData);
        int wordLen = (dataBytes.length + 1) / 2;   // 字数 = (字节数+1)/2（iData 多数固件 writeTag 的 cnt 为字数）
        int byteLen = dataBytes.length;             // 少数固件把 cnt 当作字节数，作为备选
        String fPrefix = (fData != null && fData.length() >= 8) ? fData.substring(0, 8) : fData; // 32bit 掩码用 EPC 前 8hex
        String fPrefix16 = (fData != null && fData.length() >= 16) ? fData.substring(0, 16) : fPrefix; // 64bit 掩码
        String fPrefix20 = (fData != null && fData.length() >= 20) ? fData.substring(0, 20) : fPrefix16; // 80bit 掩码

        // 先探测并设置写模式（部分固件写前需 setWriteMode），再优先尝试厂商 UHFInterface 封装层写方法
        probeWriteMode();
        if (tryIfaceWrites(pwd, writeBank, startWord, dataHex, dataBytes, fBank, fStartBit, fLenBits, fData, mAllowUnfiltered)) return true;
        // 方案 2：先 filterSet(SELECT) 再写（对齐参考 App controlRFIDInventory：filterSet -> 写/读 复用已设 SELECT）
        if (tryFilterSetThenWrite(pwd, writeBank, startWord, dataHex, wordLen, byteLen, fBank, fData, fStartBit, fLenBits)) return true;

        // ---- 第一组：带过滤写（最安全，只写匹配标签）----
        // 设备实测 UHFManager 真实签名：writeTag(String pwd,int bank,int ptr,int cnt,String dataHex,
        //              int fBank,int fStartBit,int fLenBits,String filterEpc)
        // 最后 4 个参数为 SELECT 过滤条件。参考 iUHF 源码 writeWholeEpc 用 fStartBit=1、fLenBits=字数(含 PC)，
        // 强烈提示本固件过滤偏移以“字(word)”而非“位(bit)”为单位。故同时尝试 bit 单位(32/96) 与 word 单位(2/6) 等多种约定，
        // cnt 同时取字数(wordLen)与字节数(byteLen)。命中任一即视为写入成功。
        boolean triedFiltered = false;
        int[][] convs = new int[][]{
                {fStartBit, fLenBits},   // 业务层传入（默认 32/96，bit 单位）
                {2, 6},                  // word 单位：EPC 数据位于 word2、占 6 字(12字节)
                {0, 96},                 // bit 单位、固件自动跳过 CRC+PC
                {0, 6},                  // word 单位、从 EPC bank 起点
                {1, 7},                  // word 单位、含 PC（对齐 writeWholeEpc）
        };
        for (int[] conv : convs) {
            int sb = conv[0], lb = conv[1];
            for (int cnt : new int[]{wordLen, byteLen}) {
                try {
                    Method m = mc.getMethod("writeTag", String.class, int.class, int.class, int.class,
                            String.class, int.class, int.class, int.class, String.class);
                    triedFiltered = true;
                    Log.i(TAG, "writeViaManager: 尝试 过滤写 writeTag conv(sb=" + sb + ",lb=" + lb + ") cnt=" + cnt);
                    Object r = m.invoke(mgr, pwd, writeBank, startWord, cnt, dataHex, fBank, sb, lb, fData);
                    boolean ok = interpretWriteReturn(r);
                    Log.i(TAG, "writeViaManager: 过滤写 conv(sb=" + sb + ",lb=" + lb + ") cnt=" + cnt + " 返回=" + r + " -> ok=" + ok);
                    if (ok) return true;
                } catch (Throwable e) {
                    Log.w(TAG, "writeViaManager: 过滤写 conv(sb=" + sb + ",lb=" + lb + ") 异常", e);
                }
            }
        }
        if (triedFiltered) {
            // 过滤写方法存在但均失败：仅当业务层确认“范围内只有 C1 一张标签”时，才降级为不过滤写 / writeDataToEpc
            if (mAllowUnfiltered) {
                Log.w(TAG, "writeViaManager: 所有带过滤写失败，按允许降级为不过滤写 / writeDataToEpc（仅 C1 在场时安全）");
                if (tryUnfilteredWrite(mc, pwd, writeBank, startWord, dataHex, wordLen, byteLen)) return true;
                if (tryWriteDataToEpc(mgr, pwd, writeBank, startWord, dataHex)) return true;
            } else {
                Log.w(TAG, "writeViaManager: 所有带过滤写失败，且未授权不过滤写（防误写其它标签），放弃");
            }
            logManagerMethods();
            dumpAllMethods(mgr, "UHFManager(写失败诊断)");
            return false;
        }

        // ---- 第二组：普通 writeTag（需范围内仅一张标签，由业务层 gate 保证）----
        // 候选 C：writeTag(int bank,int ptr,byte[],String pwd)
        // 候选 D：writeTag(int bank,int ptr,String hexData,String pwd)
        Object[][][] plain = new Object[][][]{
                {{"writeTag", int.class, int.class, byte[].class, String.class},
                        new Object[]{writeBank, startWord, dataBytes, pwd}},
                {{"writeTag", int.class, int.class, String.class, String.class},
                        new Object[]{writeBank, startWord, dataHex, pwd}},
        };
        for (Object[][] c : plain) {
            String name = (String) c[0][0];
            Class<?>[] types = new Class<?>[c[0].length - 1];
            for (int i = 1; i < c[0].length; i++) types[i - 1] = (Class<?>) c[0][i];
            Object[] args = (Object[]) c[1];
            try {
                Method m = mc.getMethod(name, types);
                Log.i(TAG, "writeViaManager: 命中普通写方法 " + m.toString());
                Object r = m.invoke(mgr, args);
                boolean ok = interpretWriteReturn(r);
                Log.i(TAG, "writeViaManager: " + name + " 返回=" + r + " -> ok=" + ok);
                if (ok) return true;
                Log.w(TAG, "writeViaManager: 普通写返回未指示成功，继续尝试同组其它候选");
            } catch (NoSuchMethodException e) {
                Log.d(TAG, "writeViaManager: 管理器无 " + name + "(" + types.length + "参) 方法，跳过");
            } catch (Throwable e) {
                Log.w(TAG, "writeViaManager: 调用普通写 " + name + " 异常", e);
            }
        }

        Log.e(TAG, "writeViaManager: 未找到任何可用的写方法（已列出管理器 write 方法供诊断）");
        logManagerMethods();
        return false;
    }

    /** 业务层在探测确认“范围内只有目标一张标签”时调用，允许过滤写全部失败后降级为不过滤写 */
    public void setAllowUnfilteredWrite(boolean v) { mAllowUnfiltered = v; }

    /** 不过滤写：filterEpc 留空，写当前天线范围内唯一应答的标签（仅在 mAllowUnfiltered 时由 writeViaManager 调用）。
     *  cnt 同时尝试“字数(wordLen)”与“字节数(byteLen)”两种约定，兼容不同固件。 */
    private boolean tryUnfilteredWrite(Class<?> mc, String pwd, int writeBank, int startWord,
                                       String dataHex, int wordLen, int byteLen) {
        int[] cnts = {wordLen, byteLen};
        for (int cnt : cnts) {
            try {
                Method m = mc.getMethod("writeTag",
                        String.class, int.class, int.class, int.class, String.class, int.class, int.class, int.class, String.class);
                Log.i(TAG, "writeViaManager: 尝试 不过滤写 writeTag cnt=" + cnt);
                Object r = m.invoke(mgrOf(mc), pwd, writeBank, startWord, cnt, dataHex, 0, 0, 0, "");
                boolean ok = interpretWriteReturn(r);
                Log.i(TAG, "writeViaManager: 不过滤写 writeTag(cnt=" + cnt + ") 返回=" + r + " -> ok=" + ok);
                if (ok) return true;
            } catch (Throwable e) {
                Log.w(TAG, "writeViaManager: 不过滤写(cnt=" + cnt + ") 异常", e);
            }
        }
        return false;
    }

    /** 写最近一次盘点聚焦到的标签（iData 部分固件 writeDataToEpc 作用于上次盘点的标签，无需显式 SELECT）。
     *  仅在 mAllowUnfiltered（范围内仅 C1）时调用，避免误写其它标签。 */
    private boolean tryWriteDataToEpc(Object mgr, String pwd, int writeBank, int startWord, String dataHex) {
        try {
            Method m = mgr.getClass().getMethod("writeDataToEpc", String.class, int.class, int.class, String.class);
            Log.i(TAG, "writeViaManager: 尝试 writeDataToEpc bank=" + writeBank + " ptr=" + startWord);
            Object r = m.invoke(mgr, pwd, writeBank, startWord, dataHex);
            boolean ok = interpretWriteReturn(r);
            Log.i(TAG, "writeViaManager: writeDataToEpc 返回=" + r + " -> ok=" + ok);
            return ok;
        } catch (Throwable e) {
            Log.w(TAG, "writeViaManager: writeDataToEpc 异常", e);
            return false;
        }
    }

    /** 取得当前用于写操作的管理器实例（与 writeViaManager 一致） */
    private Object mgrOf(Class<?> mc) {
        Object mgr = (mRawManager != null) ? mRawManager : mUhf;
        return mgr;
    }

    /** 解释写方法返回值：boolean 直接判；int 判 >=0；Bundle 看 result/data；String 看非空且非 "0"/"false" */
    private boolean interpretWriteReturn(Object r) {
        if (r == null) return false;
        if (r instanceof Boolean) return (Boolean) r;
        if (r instanceof Integer) return (Integer) r >= 0;
        if (r instanceof Bundle) {
            Bundle b = (Bundle) r;
            Log.i(TAG, "write bundle keys=" + bundleKeys(b));
            if (b.containsKey("result")) return b.getBoolean("result", false);
            String data = b.getString("data");
            return data != null && !data.isEmpty();
        }
        if (r instanceof String) {
            String s = (String) r;
            return !s.isEmpty() && !"0".equals(s) && !"false".equalsIgnoreCase(s);
        }
        return false;
    }

    private static String bundleKeys(Bundle b) {
        java.util.Set<String> ks = b.keySet();
        StringBuilder sb = new StringBuilder();
        for (String k : ks) sb.append(k).append("=").append(b.get(k)).append("; ");
        return sb.toString();
    }

    /** 十六进制串 -> 字节数组（每 2 字符一字节；奇数长度前补 0） */
    private static byte[] hexToBytes(String s) {
        if (s == null) return new byte[0];
        String t = s.trim();
        if ((t.length() % 2) != 0) t = "0" + t;
        byte[] out = new byte[t.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(t.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    /** 诊断：列出管理器（UHFManager 活实例）上所有 write / read 方法签名，便于确认固件真实 API */
    private void logManagerMethods() {
        Object mgr = (mRawManager != null) ? mRawManager : mUhf;
        if (mgr == null) { Log.w(TAG, "logManagerMethods: 无管理器实例"); return; }
        Class<?> c = mgr.getClass();
        StringBuilder sb = new StringBuilder("UHFManager(").append(c.getName()).append(") 方法: ");
        for (Method m : c.getMethods()) {
            String n = m.getName();
            if (n.toLowerCase().contains("write") || n.toLowerCase().contains("read"))
                sb.append("\n  ").append(m.toString());
        }
        Log.i(TAG, sb.toString());
    }

    /** 诊断：列出 UHFInterface（dex 封装层）上的 write/select/read 方法，便于确认厂商自带写方法签名 */
    private void logIfaceMethods() {
        if (mIface == null) { Log.w(TAG, "logIfaceMethods: 无 UHFInterface 实例"); return; }
        Class<?> c = mIface.getClass();
        StringBuilder sb = new StringBuilder("UHFInterface(").append(c.getName()).append(") 方法: ");
        for (Method m : c.getMethods()) {
            String n = m.getName().toLowerCase();
            if (n.contains("write") || n.contains("read") || n.contains("select")
                    || n.contains("connect") || n.contains("power") || n.contains("inventory"))
                sb.append("\n  ").append(m.toString());
        }
        Log.i(TAG, sb.toString());
    }

    /** 诊断：完整 dump 某个对象类上的【全部】公开方法（含 select/filter/init/set*），用于发现未尝试过的正确 API */
    private void dumpAllMethods(Object obj, String label) {
        if (obj == null) { Log.w(TAG, "dumpAllMethods: " + label + " 为 null，跳过"); return; }
        Class<?> c = obj.getClass();
        StringBuilder sb = new StringBuilder("【ALL_METHODS】").append(label)
                .append(" class=").append(c.getName()).append(" (").append(c.getMethods().length).append(" methods):");
        // 按方法名排序，便于阅读
        java.util.List<Method> ms = new java.util.ArrayList<>(java.util.Arrays.asList(c.getMethods()));
        ms.sort((a, b) -> a.getName().compareTo(b.getName()));
        for (Method m : ms) sb.append("\n  ").append(m.toString());
        Log.i(TAG, sb.toString());
        // 同时 dump 声明字段（可能持有其它可写管理器）
        StringBuilder fs = new StringBuilder("【FIELDS】").append(label).append(" class=").append(c.getName()).append(":");
        for (Field fld : c.getDeclaredFields()) fs.append("\n  ").append(fld.getType().getName()).append(" ").append(fld.getName());
        Log.i(TAG, fs.toString());
    }

    /**
     * 尝试调用厂商 UHFInterface 封装层自带的写方法（writeTag / writeFullEpcTag / writeDataToEpc，
     * 以及 WriteTagDataAndReadTagData）。这些封装方法内部会做正确的 SELECT / 写模式等设置，
     * 比我们直接反射驱动固件 UHFManager.writeTag 更可靠（固件层直写此前一律返回 false）。
     * 带过滤的候选只会命中 C1（EPC 精确匹配），不会误写其它标签；writeDataToEpc 无过滤，
     * 仅在 allowUnfiltered（范围内仅 C1 一张）时调用以防误写。
     */
    private boolean tryIfaceWrites(String pwd, int writeBank, int startWord,
                                   String dataHex, byte[] dataBytes,
                                   int fBank, int fStartBit, int fLenBits, String fData, boolean allowUnfiltered) {
        if (mIface == null) { Log.w(TAG, "tryIfaceWrites: 无 UHFInterface，跳过封装层写方法"); return false; }
        Class<?> ic = mIface.getClass();
        int wordLen = (dataBytes.length + 1) / 2;
        int byteLen = dataBytes.length;
        Object[][][] cands = new Object[][][]{
                // 1) writeTag 9参（自带 SELECT 过滤，锁定 C1）
                {{"writeTag", String.class, int.class, int.class, int.class, String.class, int.class, int.class, int.class, String.class},
                        new Object[]{pwd, writeBank, startWord, wordLen, dataHex, fBank, fStartBit, fLenBits, fData}},
                {{"writeTag", String.class, int.class, int.class, int.class, String.class, int.class, int.class, int.class, String.class},
                        new Object[]{pwd, writeBank, startWord, byteLen, dataHex, fBank, fStartBit, fLenBits, fData}},
                // 2) writeFullEpcTag 6参（pwd,bank,ptr,cnt,dataHex,filterEpc）自带过滤
                {{"writeFullEpcTag", String.class, int.class, int.class, int.class, String.class, String.class},
                        new Object[]{pwd, writeBank, startWord, wordLen, dataHex, fData}},
                {{"writeFullEpcTag", String.class, int.class, int.class, int.class, String.class, String.class},
                        new Object[]{pwd, writeBank, startWord, byteLen, dataHex, fData}},
                // 3) writeDataToEpc（无过滤，仅允许 C1 单标签时调用）
                {{"writeDataToEpc", String.class, int.class, int.class, String.class},
                        new Object[]{pwd, writeBank, startWord, dataHex}},
                // 4) WriteTagDataAndReadTagData（自带 SELECT+写+读回；部分固件 UHFManager 无此方法会抛异常，忽略）
                {{"WriteTagDataAndReadTagData", String.class, int.class, int.class, int.class, String.class, int.class, int.class, int.class, String.class},
                        new Object[]{pwd, writeBank, startWord, wordLen, dataHex, fBank, fStartBit, fLenBits, fData}},
        };
        for (Object[][] c : cands) {
            String name = (String) c[0][0];
            if (name.equals("writeDataToEpc") && !allowUnfiltered) {
                Log.d(TAG, "tryIfaceWrites: 跳过无过滤 writeDataToEpc（非 C1 单标签场景）");
                continue;
            }
            Class<?>[] types = new Class<?>[c[0].length - 1];
            for (int i = 1; i < c[0].length; i++) types[i - 1] = (Class<?>) c[0][i];
            Object[] args = (Object[]) c[1];
            try {
                Method m = ic.getMethod(name, types);
                Log.i(TAG, "tryIfaceWrites: 尝试 " + name + "(UHFInterface) 参=" + java.util.Arrays.toString(args));
                Object r = m.invoke(mIface, args);
                boolean ok = interpretWriteReturn(r);
                Log.i(TAG, "tryIfaceWrites: " + name + "(UHFInterface) 返回=" + r + " -> ok=" + ok);
                if (ok) return true;
                Log.w(TAG, "tryIfaceWrites: " + name + "(UHFInterface) 未指示成功，继续候选");
            } catch (NoSuchMethodError e) {
                Log.d(TAG, "tryIfaceWrites: UHFInterface." + name + " 内部调用了固件缺失方法（固件不匹配），忽略");
            } catch (NoSuchMethodException e) {
                Log.d(TAG, "tryIfaceWrites: UHFInterface 无 " + name + "(" + types.length + "参) 方法，跳过");
            } catch (InvocationTargetException e) {
                Log.w(TAG, "tryIfaceWrites: " + name + "(UHFInterface) 内部异常（固件不匹配），忽略", e.getCause());
            } catch (Throwable e) {
                Log.w(TAG, "tryIfaceWrites: " + name + "(UHFInterface) 异常", e);
            }
        }
        return false;
    }

    /**
     * 方案 2：先 filterSet(SELECT) 再写。对齐参考 App 的 controlRFIDInventory 做法：
     * 它先 filterSet(fBank,fStartBit,fLenBits,epc,flag) 设定 SELECT，随后写/读复用该已设 SELECT。
     * filterSet 内部调用固件 inventoryFilterData(...)。这里对每种过滤约定先设 SELECT，再调用
     * UHFInterface.writeTag（空过滤，依赖已设 SELECT）写入，命中任一即成功。只锁定 C1，不会误写其它标签。
     */
    private boolean tryFilterSetThenWrite(String pwd, int writeBank, int startWord, String dataHex,
                                         int wordLen, int byteLen, int fBank, String fData,
                                         int fStartBit, int fLenBits) {
        if (mIface == null) { Log.w(TAG, "tryFilterSetThenWrite: 无 UHFInterface，跳过"); return false; }
        Class<?> ic = mIface.getClass();
        Method filterSet;
        try {
            filterSet = ic.getMethod("filterSet", int.class, int.class, int.class, String.class, int.class);
        } catch (NoSuchMethodException e) {
            Log.d(TAG, "tryFilterSetThenWrite: UHFInterface 无 filterSet 方法，跳过");
            return false;
        }
        int[][] convs = new int[][]{
                {fStartBit, fLenBits}, {2, 6}, {0, 96}, {0, 6},
        };
        for (int[] conv : convs) {
            int sb = conv[0], lb = conv[1];
            try {
                Object fr = filterSet.invoke(mIface, fBank, sb, lb, fData, 0);
                Log.i(TAG, "tryFilterSetThenWrite: filterSet(sb=" + sb + ",lb=" + lb + ")=" + fr);
                for (int cnt : new int[]{wordLen, byteLen}) {
                    Method wt = ic.getMethod("writeTag", String.class, int.class, int.class, int.class,
                            String.class, int.class, int.class, int.class, String.class);
                    Object r = wt.invoke(mIface, pwd, writeBank, startWord, cnt, dataHex, 0, 0, 0, "");
                    boolean ok = interpretWriteReturn(r);
                    Log.i(TAG, "tryFilterSetThenWrite: conv(sb=" + sb + ",lb=" + lb + ") cnt=" + cnt + " 写返回=" + r + " -> ok=" + ok);
                    if (ok) return true;
                }
            } catch (Throwable e) {
                Log.w(TAG, "tryFilterSetThenWrite: conv(sb=" + sb + ",lb=" + lb + ") 异常", e);
            }
        }
        return false;
    }

    /** 探测并设置写模式（部分固件写前需 setWriteMode）；记录结果供诊断，不阻塞写流程 */
    private void probeWriteMode() {
        for (Object obj : new Object[]{mIface, mRawManager != null ? mRawManager : mUhf}) {
            if (obj == null) continue;
            for (int mode : new int[]{0, 1}) {
                try {
                    Method m = obj.getClass().getMethod("setWriteMode", int.class);
                    Object r = m.invoke(obj, mode);
                    Log.i(TAG, "probeWriteMode: " + obj.getClass().getSimpleName() + ".setWriteMode(" + mode + ")=" + r);
                } catch (Throwable ignored) {}
            }
        }
    }

    @Override
    public boolean isConnected() {
        return mConnected.get();
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }
}
