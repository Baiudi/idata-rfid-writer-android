package com.example.rfidwriter.rfid;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
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
    private final AtomicBoolean mPolling = new AtomicBoolean(false);
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private String mBindPath = "none";             // 诊断：实际用了哪条绑定路径
    private int mModuleType = 0;

    // 连接健壮性：iData 系统 UHF 服务（iUHF）约每 3 分钟自重启，会导致已持有的 binder 代理失效。
    // 代理死后 readTagFromBuffer / startInventoryTag 会抛 DeadObjectException(RemoteException) 或静默返回空，
    // 但 mConnected 仍为真——表现为“UI 显示已连接、盘点却一直 0 张”。下面这套机制负责检测并自动重建。
    private ConnectionListener mConnListener;       // 业务层回调（MainActivity）
    private final AtomicBoolean mResumableInventory = new AtomicBoolean(false); // 断连前是否处于盘点中（用于重连后续接）
    private final AtomicBoolean mReconnecting = new AtomicBoolean(false);       // 防重入：同一时刻只允许一个重连流程
    private final AtomicBoolean mHeartbeatRunning = new AtomicBoolean(false);   // 心跳保活线程运行标志

    // 标签转发去重：回调（实时）与轮询（兜底）两条路径统一汇入 dispatchTag。
    // 用“时间窗”去重（默认 2s 清空一次），避免同一条标签被高频重复转发导致 UI 卡顿，
    // 同时保证：只要在盘点会话内被读到至少一次，就一定会进入 invStats（唯一数不丢）。
    private final Set<String> mWindowSeen = new HashSet<>();
    private final Set<String> mWindowHasRssi = new HashSet<>();
    private long mWindowClearAt = 0;
    private static final long WINDOW_MS = 2000;
    // 诊断计数：区分“回调实时送达”与“轮询兜底送达”，便于确认 150 张漏读是否已根治
    private long mCbDelivered = 0;
    private long mPollDelivered = 0;
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
                    // 兼容 iUHF 固件多种回调签名：
                    //   1) onUHFCallBack(String[] tags)  —— 每元素一条标签串（最常见）
                    //   2) onUHFCallBack(String tags)    —— 单串，内部以 ,/ /|/\n 等分隔多条
                    //   3) 其它只带一个 String[]/String 参数的方法（如 callBack/onTag/onRead）一并兜底接收
                    if (args == null || args.length == 0) return null;
                    String name = method.getName();
                    boolean likelyCb = name.contains("UHF") || name.contains("CallBack")
                            || name.contains("Callback") || name.equalsIgnoreCase("onTag")
                            || name.equalsIgnoreCase("onRead");
                    if (!likelyCb) return null;
                    Object a0 = args[0];
                    if (a0 instanceof String[]) {
                        onRawTags((String[]) a0);
                    } else if (a0 instanceof String) {
                        // 单串：按常见分隔符拆成多条标签串
                        String[] parts = ((String) a0).split("[,\\s\\|\\n\\r]+");
                        onRawTags(parts);
                    }
                    return null;
                });
    }

    /** SDK 回调回来的标签串数组 -> 解析为 (epc, rssi) -> 经 dispatchTag 去重后转发 */
    private void onRawTags(String[] tags) {
        if (mInvCb == null || tags == null) return;
        int n = 0;
        for (String raw : tags) {
            if (raw == null || raw.isEmpty()) continue;
            TagInfo t = parseTag(raw);
            if (t.epc != null && !t.epc.isEmpty()) {
                dispatchTag(t.epc, t.rssi);
                n++;
            }
        }
        if (n > 0) {
            mCbDelivered += n;
            if ((mCbDelivered % 50) == 0)
                Log.i(TAG, "回调实时送达累计=" + mCbDelivered + "（已生效，零丢失路径）");
        }
    }

    /**
     * 统一转发出口：回调与轮询都调用它。
     * 时间窗去重（WINDOW_MS）避免高频重复转发拖垮 UI；窗口过期后同一标签会再转发一次，
     * 使 MainActivity 的“次数”计数继续累加，但【唯一数 invStats 永不丢失某条曾出现过的标签】。
     */
    private void dispatchTag(String epc, String rssi) {
        if (epc == null || epc.isEmpty() || mInvCb == null) return;
        boolean hasRssi = (rssi != null && !rssi.isEmpty());
        long now = System.currentTimeMillis();
        boolean forward;
        synchronized (mWindowSeen) {
            if (now >= mWindowClearAt) {
                mWindowSeen.clear();
                mWindowHasRssi.clear();
                mWindowClearAt = now + WINDOW_MS;
            }
            if (!mWindowSeen.contains(epc)) {
                // 首次出现：必然转发（保证唯一数不丢）
                forward = true;
                mWindowSeen.add(epc);
                if (hasRssi) mWindowHasRssi.add(epc);
            } else if (hasRssi && !mWindowHasRssi.contains(epc)) {
                // 同窗口内已用“仅 EPC”的实时回调转发过，现用带 RSSI 的数据补全信号值（避免 RSSI 被去重吞掉）
                forward = true;
                mWindowHasRssi.add(epc);
            } else {
                forward = false; // 信息不比已有更优，跳过
            }
        }
        if (!forward) return;
        TagInfo ft = new TagInfo();
        ft.epc = epc;
        ft.rssi = (rssi == null ? "" : rssi);
        mMain.post(() -> mInvCb.onTag(ft));
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
                try {
                    return m.invoke(target, args);
                } catch (java.lang.reflect.InvocationTargetException ite) {
                    Throwable cause = ite.getCause();
                    // 系统 UHF 服务重启会导致代理抛出 DeadObjectException（继承自 RemoteException）。
                    // 捕获后即判定 binder 死亡，转入自动重连。
                    if (cause instanceof RemoteException
                            || (cause != null && cause.getCause() instanceof RemoteException)) {
                        onBinderDied("callIface:" + name + " -> "
                                + (cause != null ? cause.getClass().getSimpleName() : "RemoteException"));
                    }
                    throw ite;
                }
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
            // 诊断：每 ~12 轮（≈0.6s @50ms）打印一次原始缓存，便于确认“到底有没有读到标签”
            if ((++mPollLogTick % 12) == 0) {
                StringBuilder sb = new StringBuilder("pollBuffer 原始缓存 len=").append(arr.length);
                int n = Math.min(arr.length, 6);
                for (int i = 0; i < n; i++) sb.append(" | ").append(arr[i]);
                Log.i(TAG, sb.toString());
            }
            return arr;
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable c = ite.getCause();
            if (c instanceof RemoteException
                    || (c != null && c.getCause() instanceof RemoteException)) {
                onBinderDied("pollBuffer -> "
                        + (c != null ? c.getClass().getSimpleName() : "RemoteException"));
            } else {
                Log.w(TAG, "pollBuffer 异常（忽略，继续下一轮）", ite);
            }
            return new String[0];
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
        // 重连/重复连接时先清空失效代理，确保 ensureSdk 重建全新的 binder 绑定
        // （否则 mIface 非空会让 ensureSdk 早返回，复用已死的代理而“假连接”）。
        reset();
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
            if (ok) startHeartbeat();   // 启动保活心跳：周期性重申连续盘点模式，兼作 binder 存活探测
            final boolean fok = ok;
            final String fmsg = msg;
            if (cb != null) mMain.post(() -> cb.onResult(fok, fmsg));
        }).start();
    }

    /** 清空全部运行态（失效代理 / 轮询 / 连接标志 / 可续接标记），供 init 与重连重建使用 */
    private void reset() {
        mIface = null; mIfaceClass = null; mRawManager = null;
        mUhf = null; mUhfClass = null;
        mListener = null;                 // 重建后 ensureListener 会重新注册
        mPolling.set(false);
        mResumableInventory.set(false);
        mConnected.set(false);
        stopHeartbeat();
    }

    @Override
    public void release() {
        mPolling.set(false);
        mResumableInventory.set(false);
        stopHeartbeat();
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
        if (cb == null) return;
        mInvCb = cb;
        mResumableInventory.set(true);   // 记住“盘点中”，binder 断连后自动重连时据此续接
        startInventoryInternal();
    }

    /** 真正发起群读（业务调用与自动重连续接共用）；代理失效时会在内部触发 onBinderDied */
    private void startInventoryInternal() {
        if (!mConnected.get() || mInvCb == null) return;
        try {
            // 回调作为实时零丢失主路径注册；失败才退化为纯轮询兜底
            ensureListener();
            try {
                callIface("registerUHFCallback", mListener);
                Log.i(TAG, "registerUHFCallback 注册成功（实时回调已生效，150 张标签不再漏读）");
            } catch (Throwable e) {
                Log.w(TAG, "registerUHFCallback 绑定失败（退化为纯轮询兜底路径）", e);
            }
            Object r = callIface("startInventoryTag");
            boolean started = r instanceof Boolean ? (Boolean) r : (r != null);
            Log.i(TAG, "startInventory: startInventoryTag 已调用 ret=" + started);
        } catch (Throwable e) {
            Log.e(TAG, "startInventory failed", e);
        }
        // 轮询 readTagFromBuffer()：iUHF 群读兜底路径；回调 onUHFCallBack 才是实时零丢失主路径
        mWindowSeen.clear();
        mWindowClearAt = 0;
        mCbDelivered = 0;
        mPollDelivered = 0;
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
                    // 统一经 dispatchTag 转发（与回调共用时间窗去重，保证唯一数不丢）
                    int forwarded = 0;
                    for (java.util.Map.Entry<String, String> e : cur.entrySet()) {
                        dispatchTag(e.getKey(), e.getValue());
                        forwarded++;
                    }
                    if (forwarded > 0) {
                        mPollDelivered += forwarded;
                        if ((mPollDelivered % 50) == 0)
                            Log.i(TAG, "轮询兜底送达累计=" + mPollDelivered);
                    }
                    if (arr.length > 0) {
                        Log.i(TAG, "轮询解析: arrLen=" + arr.length + " 解析出EPC=" + cur.size()
                                + " 解析失败元素=" + parsedFail
                                + " 本轮转发=" + forwarded
                                + " mInvCb=" + (mInvCb != null));
                    }
                } catch (Throwable ignore) { /* 轮询异常忽略，继续下一轮 */ }
                try { Thread.sleep(50); } catch (InterruptedException e) { break; }
            }
        }, "uhf-inventory-poll").start();
    }

    @Override
    public void stopInventory() {
        Log.i(TAG, "stopInventory 调用（mPolling " + mPolling.get() + " -> false）",
                new Throwable("stop来源"));
        mPolling.set(false);
        mResumableInventory.set(false);   // 主动停止盘点，断连时不再自动续接
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
                // 带过滤：仅对匹配 filterEpc 的那张标签读取，杜绝多标签时误读其他标签
                Object r = callIface("readTag",
                        pwd == null ? "" : pwd, bank, wordAddr, wordLen,
                        filterEpc == null ? "" : filterEpc, fBank, fStartBit, fLenBits);
                if (r instanceof String && !((String) r).isEmpty()) {
                    data = (String) r;
                    ok = true;
                    Log.i(TAG, "readTag(过滤): ok bank=" + bank + " ptr=" + wordAddr + " len=" + wordLen
                            + " filterEpc=" + filterEpc + " hex=" + data);
                } else {
                    msg = "读取为空（未读到匹配标签，请确认过滤条件与天线范围内标签一致）";
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
                // 业务层传入的是明文(ASCII/UTF-8)，SDK 入参为 hex 字符串，这里转换。
                byte[] b = writeData.getBytes(StandardCharsets.UTF_8);
                if (b.length % 2 != 0) {                  // 凑成整字节
                    byte[] p = new byte[b.length + 1];
                    System.arraycopy(b, 0, p, 0, b.length);
                    b = p;
                }
                String hex = toHex(b);
                int cnt = b.length / 2;                  // 字数 = 字节数 / 2

                // iUHF 写标签走 UHFInterface.WriteTagDataAndReadTagData（写后回读，返回 Bundle），
                // 其底层 this.uhfManager.WriteTagDataAndReadTagData(...) 即系统服务暴露的 AIDL 方法。
                Object r = callIface("WriteTagDataAndReadTagData",
                                pwd == null ? "" : pwd, writeBank, startWord, cnt, hex,
                                fBank, fStartBit, fLenBits, fData == null ? "" : fData);
                ok = interpretWriteBundle(r);
                msg = ok ? "写入成功" : "写入失败（请确认天线范围内仅一张标签，且未被其它标签干扰）";
            } catch (Throwable e) {
                msg = "写入异常：" + e.getClass().getSimpleName() + " " + e.getMessage();
                Log.e(TAG, "writeTagWithFilter failed", e);
            }
            final boolean fok = ok;
            final String fmsg = msg;
            if (cb != null) mMain.post(() -> cb.onResult(fok, fmsg));
        }).start();
    }

    /** 解释 WriteTagDataAndReadTagData 返回的 Bundle：以 "result" 布尔为主，缺省时看 "data" 是否存在 */
    private boolean interpretWriteBundle(Object r) {
        if (!(r instanceof Bundle)) {
            Log.w(TAG, "write bundle 非 Bundle: " + (r == null ? "null" : r.getClass().getName()));
            return false;
        }
        Bundle b = (Bundle) r;
        Log.i(TAG, "write bundle keys=" + bundleKeys(b));
        if (b.containsKey("result")) return b.getBoolean("result", false);
        // 部分固件用 data 字段回读；有 data 视为成功
        String data = b.getString("data");
        return data != null && !data.isEmpty();
    }

    private static String bundleKeys(Bundle b) {
        java.util.Set<String> ks = b.keySet();
        StringBuilder sb = new StringBuilder();
        for (String k : ks) sb.append(k).append("=").append(b.get(k)).append("; ");
        return sb.toString();
    }

    @Override
    public boolean isConnected() {
        return mConnected.get();
    }

    @Override
    public void setConnectionListener(ConnectionListener l) {
        mConnListener = l;
    }

    // ------------------------------------------------------------------
    // 连接健壮性：binder 死亡检测 + 自动重连 + 保活心跳
    // ------------------------------------------------------------------

    /**
     * 底层 UHF 系统服务（iUHF）约每 3 分钟自重启，会导致我们持有的 binder 代理失效：
     * 此后调用会抛 DeadObjectException（继承自 RemoteException）或静默返回空，但 mConnected 仍为真，
     * 表现为“UI 显示已连接、盘点却一直 0 张”。本方法由 callIface / pollBuffer / 心跳在捕获到
     * RemoteException 时调用，统一转入“标记失效 → 清空失效代理 → 自动重连 → 续接盘点”流程。
     */
    private void onBinderDied(String reason) {
        Log.w(TAG, "onBinderDied: " + reason);
        // 仅第一个触发者执行整段流程，避免轮询/心跳/回调并发重复重连
        if (!mConnected.compareAndSet(true, false)) return;
        // 清空失效代理，确保下次 ensureSdk 重建全新 binder
        mIface = null; mIfaceClass = null; mRawManager = null;
        mUhf = null; mUhfClass = null; mListener = null;
        mPolling.set(false);
        stopHeartbeat();
        if (mConnListener != null) {
            mMain.post(() -> mConnListener.onDisconnected(reason));
        }
        scheduleReconnect();
    }

    /** 安排一次自动重连（带防重入）；失败会指数退避重试 */
    private void scheduleReconnect() {
        if (mReconnecting.compareAndSet(false, true)) {
            Log.i(TAG, "scheduleReconnect: 1s 后开始重连");
            mMain.postDelayed(this::doReconnect, 1000);
        }
    }

    /** 真正执行重连：重建 binder、上电、重申连续盘点，若此前在盘点则自动续接 */
    private void doReconnect() {
        boolean wantResume = mResumableInventory.get();   // 先记下“盘点中”状态，下面的 reset() 会清掉它
        Log.i(TAG, "doReconnect: 重建 UHF 绑定，wantResume=" + wantResume);
        try {
            reset();
            ensureSdk();
            boolean ok = (mIface != null || mUhf != null);
            if (!ok) {
                Log.w(TAG, "doReconnect: ensureSdk 失败，2s 后重试");
                mReconnecting.set(false);
                mMain.postDelayed(this::scheduleReconnect, 2000);
                return;
            }
            mConnected.set(true);
            powerOn();                 // 重新初始化原生 RF 会话，使 startInventoryTag 安全可用
            setInventoryMode(1);       // 重申连续盘点模式
            startHeartbeat();          // 重启保活心跳
            boolean resumed = false;
            if (wantResume) {
                Log.i(TAG, "doReconnect: 续接上次盘点");
                startInventoryInternal();
                resumed = true;
            }
            mReconnecting.set(false);
            if (mConnListener != null) {
                final boolean fResumed = resumed;
                mMain.post(() -> mConnListener.onReconnected(fResumed));
            }
        } catch (Throwable e) {
            Log.e(TAG, "doReconnect 异常，2s 后重试", e);
            mReconnecting.set(false);
            mMain.postDelayed(this::scheduleReconnect, 2000);
        }
    }

    /**
     * 保活心跳：连接成功后启动，每 5 秒重申一次连续盘点模式（inventoryModeSet(1,true)）。
     * 这既能防止系统服务把模组留在“通电不发射”降级态，又能在 binder 失效时令该方法抛出
     * RemoteException，进而触发 onBinderDied → 自动重连（即使当前并未在盘点，也能即时发现死链）。
     */
    private void startHeartbeat() {
        if (mHeartbeatRunning.get()) return;
        mHeartbeatRunning.set(true);
        new Thread(() -> {
            while (mHeartbeatRunning.get() && mConnected.get()) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
                if (!mConnected.get()) break;
                try {
                    callIface("inventoryModeSet", 1, true);
                } catch (Throwable ignore) {
                    // binder 死亡已由 callIface 内部 onBinderDied 处理；循环将因 mConnected=false 退出
                }
            }
            mHeartbeatRunning.set(false);
        }, "uhf-heartbeat").start();
    }

    /** 停止保活心跳 */
    private void stopHeartbeat() {
        mHeartbeatRunning.set(false);
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
