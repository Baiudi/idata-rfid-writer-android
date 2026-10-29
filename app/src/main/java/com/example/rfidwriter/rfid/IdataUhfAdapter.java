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
 * writeTag / inventoryModeSet / powerOn …）。这些方法是 dex 内已编译的
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
 *                                        int fBank,int fStartBit,int fLenBits,String fEpc)  // 写后回读（本机固件未暴露，已改走 writeTag）
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
    /** RSSI 数值段（可带负号与小数） */
    private static final java.util.regex.Pattern NUM_RUN =
            java.util.regex.Pattern.compile("-?\\d+(\\.\\d+)?");

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
        String best = null; int bestEnd = -1, bestStart = -1;
        while (m.find()) {
            String c = m.group();
            if (best == null || c.length() > best.length()) {
                best = c; bestEnd = m.end(); bestStart = m.start();
            }
        }
        if (best != null && best.length() >= 8 && (best.length() % 2) == 0) {
            t.epc = best.toUpperCase(java.util.Locale.US);
            // RSSI 可能跟在 EPC 后，也可能排在 EPC 前；只看后面会漏掉大半（实测同标签时而拿到 -30 时而为空）
            String rssi = null;
            java.util.regex.Matcher rm = NUM_RUN.matcher(s.substring(bestEnd));
            if (rm.find()) rssi = rm.group();
            if (rssi == null && bestStart > 0) {
                java.util.regex.Matcher pm = NUM_RUN.matcher(s.substring(0, bestStart));
                String last = null;
                while (pm.find()) last = pm.group();
                rssi = last;
            }
            t.rssi = rssi == null ? "" : rssi;
        }
        return t;
    }

    /**
     * 在 UHFInterface（优先）或 mUhf 运行时类上按【方法名 + 参数个数】定位并调用。
     * 这样无需手写 Class<?>[] 形参数组，也规避了对 AIDL 代理类反射找不到方法的问题——
     * 因为这里定位的是 UHFInterface 上已编译好的方法（readTag/readTagFromBuffer/startInventoryTag/
     * WriteTagDataAndReadTagData/inventoryModeSet/powerOn …），它们内部已正确落到活实例。
     */
    private static Class<?> box(Class<?> c) {
        if (c == int.class) return Integer.class;
        if (c == boolean.class) return Boolean.class;
        if (c == long.class) return Long.class;
        if (c == double.class) return Double.class;
        if (c == float.class) return Float.class;
        if (c == byte.class) return Byte.class;
        if (c == short.class) return Short.class;
        if (c == char.class) return Character.class;
        return c;
    }

    /**
     * 反射调用 UHFInterface / UHFManager 上的方法。
     * 关键修复：getMethods() 原始实现只按「方法名 + 参数个数」匹配，若 SDK 内存在多个同名重载
     * （例如 readTag 有 8 参但参数类型不同），可能匹配到类型不兼容的那个，m.invoke 抛
     * IllegalArgumentException 被上层吞掉，导致读/写永远无声失败。
     * 现改为：在「同名 + 同参数个数」的候选中，挑【参数类型可赋值兼容】的；若 invoke 因访问权限失败则继续尝试下一个。
     */
    private Object callIface(String name, Object... args) throws Exception {
        Class<?> clazz = (mIface != null) ? mIfaceClass : mUhfClass;
        Object target = (mIface != null) ? mIface : mUhf;
        if (clazz == null || target == null) throw new IllegalStateException("SDK 未初始化");
        Exception lastAccess = null;
        for (Method m : clazz.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) continue;
            Class<?>[] pts = m.getParameterTypes();
            boolean ok = true;
            for (int i = 0; i < pts.length; i++) {
                if (args[i] == null) continue;
                if (!box(pts[i]).isAssignableFrom(args[i].getClass())) { ok = false; break; }
            }
            if (!ok) continue;
            try {
                return m.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException ite) {
                Throwable cause = ite.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw new RuntimeException(cause);
            } catch (IllegalAccessException iae) {
                lastAccess = iae;   // 权限问题，尝试下一个兼容重载
            }
        }
        if (lastAccess != null) throw lastAccess;
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
                Object r = callIface("powerOn");
                ok = r instanceof Boolean && (Boolean) r;
                Log.i(TAG, "init: powerOn=" + ok + " moduleType=" + mModuleType + " bindPath=" + mBindPath);
                // 关键修复：iData 模组把 SELECT 过滤/会话/盘存算法等状态持久化在固件里，
                // 本 App 之前做定位/读写设过的 EPC 过滤会残留，导致「不过滤盘点」永远 0 读；
                // resetDefaultSetting() 恢复到出厂默认（清掉一切残留），与 iUHF 每次启动能读到一致。
                try {
                    resetModule();
                    Log.i(TAG, "init: resetDefaultSetting 已调用，已清掉残留 SELECT/会话/算法");
                } catch (Throwable e2) {
                    Log.w(TAG, "init: resetDefaultSetting 失败（忽略，继续）", e2);
                }
                // reset 可能把上电状态也复位，确保仍上电
                try { callIface("powerOn"); } catch (Throwable ignore) { /* best effort */ }
                msg = ok ? ("UHF 已连接（绑定路径：" + mBindPath + "）")
                        : "UHF 初始化失败（powerOn 返回 false，请确认设备 UHF 模块正常）";
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
                callIface("powerOff");
            }
        } catch (Throwable ignore) { /* best effort */ }
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
    public void setInventoryMode(int mode) {
        if (!mConnected.get()) return;
        try {
            // iData UHFManager.inventoryModeSet(int mode, boolean flag)：
            // flag=true 才真正开启盘点模式（连续盘点才会回传标签）。
            callIface("inventoryModeSet", mode, mode != 0);
            Log.i(TAG, "setInventoryMode: mode=" + mode + " flag=" + (mode != 0));
        } catch (Throwable ignore) { /* best effort */ }
    }

    /**
     * 把 UHF 模组恢复到出厂默认设置（resetDefaultSetting）。
     * iData 模组会把 SELECT 过滤 / 会话(Session) / 盘存算法(Q) 等状态持久化在固件里，
     * 普通 filterSet(...,on=false) 往往清不掉残留的 SELECT —— 导致后续「不过滤盘点」仍被旧的
     * EPC 过滤挡成 0 读（本 App 做定位/读写时设过 SELECT，最易踩坑）。
     * resetDefaultSetting() 一次性清掉所有残留，与 iUHF 每次启动都能读到标签的行为一致。
     *
     * 关键：resetDefaultSetting() 会把射频载波（上电状态）一并关掉，模组回到断电态，
     * 若调用方随后只 setPower（仅设功率等级、不开载波）就盘点，模组根本不发射 → 0 读。
     * 这正是「iUHF 能读、本 App 盘点读不到」的根因。因此这里复位后【必然重新 powerOn】，
     * 一处修复即可同时覆盖 init() / startInventorySession() / doPureTest() 三条路径。
     */
    public void resetModule() throws Exception {
        boolean did = false;
        if (mRawManager != null) {
            for (java.lang.reflect.Method m : mRawManager.getClass().getMethods()) {
                if (m.getName().equals("resetDefaultSetting") && m.getParameterTypes().length == 0) {
                    m.invoke(mRawManager);
                    Log.i(TAG, "resetModule: 已对 mRawManager 调用 resetDefaultSetting");
                    did = true;
                    break;
                }
            }
        }
        if (!did && mUhf != null) {
            for (java.lang.reflect.Method m : mUhfClass.getMethods()) {
                if (m.getName().equals("resetDefaultSetting") && m.getParameterTypes().length == 0) {
                    m.invoke(mUhf);
                    Log.i(TAG, "resetModule: 已对 mUhf 调用 resetDefaultSetting");
                    did = true;
                    break;
                }
            }
        }
        if (!did) throw new NoSuchMethodException("resetDefaultSetting");
        // 复位后射频载波被关闭，必须重新上电，否则模组断电、盘点 0 读。
        try {
            Object r = callIface("powerOn");
            Log.i(TAG, "resetModule: 复位后重新 powerOn ret=" + r);
        } catch (Throwable e) {
            Log.w(TAG, "resetModule: 复位后 powerOn 失败（忽略）", e);
        }
    }

    /**
     * 设置盘存会话(Session, S0~S3)。EPC Gen2 标签被盘到一次后会翻成「静默」，
     * 在某会话里只要不离开射频场就不回应；iUHF 能读而本 App 盘点 0 读，
     * 极可能是标签被其它 App/上一次盘点静默在了某个会话里。
     * 切换到另一个会话(S0 标签离场即复位，最快「复活」)可让静默标签重新回应。
     */
    public void setSession(int s) throws Exception {
        if (mRawManager != null) {
            for (java.lang.reflect.Method m : mRawManager.getClass().getMethods()) {
                if (m.getName().equals("sessionModeSet") && m.getParameterTypes().length == 1) {
                    m.invoke(mRawManager, s);
                    Log.i(TAG, "setSession: 已对 mRawManager 设 session=" + s);
                    return;
                }
            }
        }
        if (mUhf != null) {
            for (java.lang.reflect.Method m : mUhfClass.getMethods()) {
                if (m.getName().equals("sessionModeSet") && m.getParameterTypes().length == 1) {
                    m.invoke(mUhf, s);
                    Log.i(TAG, "setSession: 已对 mUhf 设 session=" + s);
                    return;
                }
            }
        }
        if (mIface != null) { callIface("sessionModeSet", s); Log.i(TAG, "setSession: 已对 mIface 设 session=" + s); return; }
        throw new NoSuchMethodException("sessionModeSet");
    }

    /** 清除 SELECT 过滤：先试 inventoryFilterData（疑似真正控制硬件 SELECT 的 API），回退 filterSet */
    public void clearSelect() {
        boolean ok = false;
        if (mRawManager != null) {
            for (java.lang.reflect.Method m : mRawManager.getClass().getMethods()) {
                if (m.getName().equals("inventoryFilterData") && m.getParameterTypes().length == 5) {
                    try { m.invoke(mRawManager, 0, 0, 0, "", false); ok = true; } catch (Throwable ignore) {}
                    break;
                }
            }
        }
        if (!ok && mIface != null) {
            try { callIface("inventoryFilterData", 0, 0, 0, "", false); ok = true; } catch (Throwable ignore) {}
        }
        if (!ok) {
            try { setEpcFilter("", 0, 0, false); ok = true; } catch (Throwable ignore) {}
        }
        Log.i(TAG, "clearSelect: inventoryFilterData(0,0,0,\"\",false) ok=" + ok);
    }

    public int getFreqMode() { try { Object o = callIface("frequencyModeGet"); return o instanceof Number ? ((Number) o).intValue() : -999; } catch (Throwable e) { return -999; } }
    public int getSessionMode() { try { Object o = callIface("sessionModeGet"); return o instanceof Number ? ((Number) o).intValue() : -999; } catch (Throwable e) { return -999; } }

    /** 设置频率区间(freqMode)。iData 模组 frequencyModeSet(int)：不同 int 对应不同法规频段；\n     * 设备默认 freqMode=1 可能并非中国 920-925MHz，导致盘点 0 读；iUHF 能读说明它设了正确频段。 */
    public void setFreqMode(int m) throws Exception {
        if (mRawManager != null) {
            for (java.lang.reflect.Method me : mRawManager.getClass().getMethods()) {
                if (me.getName().equals("frequencyModeSet") && me.getParameterTypes().length == 1) {
                    me.invoke(mRawManager, m);
                    Log.i(TAG, "setFreqMode: 已对 mRawManager 设 freqMode=" + m);
                    return;
                }
            }
        }
        if (mUhf != null) {
            for (java.lang.reflect.Method me : mUhfClass.getMethods()) {
                if (me.getName().equals("frequencyModeSet") && me.getParameterTypes().length == 1) {
                    me.invoke(mUhf, m);
                    Log.i(TAG, "setFreqMode: 已对 mUhf 设 freqMode=" + m);
                    return;
                }
            }
        }
        if (mIface != null) { callIface("frequencyModeSet", m); Log.i(TAG, "setFreqMode: 已对 mIface 设 freqMode=" + m); return; }
        throw new NoSuchMethodException("frequencyModeSet");
    }

    /**
     * 设置 EPC 过滤（SELECT）：开启后模组只回传匹配该 EPC 的标签。
     * 该模组群读缓冲区只保留「最近读到的约 2 张」，多标签场景下目标标签常不在窗口内
     * （实测盘点能看到 4 张，定位时缓存里却是另外 2 张），导致雷达定位抓不到目标。
     * 开启过滤后缓冲区内只会有目标标签，定位即可稳定命中。
     *
     * @param epcHex  目标 EPC 的 hex 串（关闭时传 ""）
     * @param lenBits EPC 位长（= hex 字符数 * 4）
     * @param on      true 开启过滤 / false 关闭
     */
    public boolean setEpcFilter(String epcHex, int startBit, int lenBits, boolean on) {
        if (!mConnected.get()) return false;
        // UHFInterface.filterSet(int bank,int startBit,int lenBits,String data,int on)
        try {
            Object r = callIface("filterSet", 1, startBit, lenBits, epcHex, on ? 1 : 0);
            boolean ret = r instanceof Boolean ? (Boolean) r : (r != null);
            Log.i(TAG, "setEpcFilter(iface): epc=" + epcHex + " startBit=" + startBit
                    + " lenBits=" + lenBits + " on=" + on + " ret=" + ret);
            return ret;
        } catch (Throwable e) {
            Log.w(TAG, "setEpcFilter iface 路径失败（末参 int），回退 UHFManager（末参 boolean）", e);
        }
        // 回退：UHFManager.filterSet(int,int,int,String,boolean)
        try {
            Object raw = mRawManager;
            if (raw == null) return false;
            for (java.lang.reflect.Method m : raw.getClass().getMethods()) {
                if (!m.getName().equals("filterSet") || m.getParameterTypes().length != 5) continue;
                if (m.getParameterTypes()[4] != boolean.class) continue;
                Object r = m.invoke(raw, 1, startBit, lenBits, epcHex, on);
                boolean ret = r instanceof Boolean ? (Boolean) r : (r != null);
                Log.i(TAG, "setEpcFilter(mgr): epc=" + epcHex + " startBit=" + startBit
                        + " lenBits=" + lenBits + " on=" + on + " ret=" + ret);
                return ret;
            }
        } catch (Throwable e) {
            Log.w(TAG, "setEpcFilter 全部路径失败", e);
        }
        return false;
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
                    // 模组群读缓存的真实排列是三元组 [EPC, TID/DATA, RSSI]（实测：
                    // [0]=EPC(128b) [1]=TID(96b) [2]=-38 …），也可能退化为二元组 [EPC, RSSI]。
                    // 旧逻辑只取 i±1 邻居，导致真实 EPC 的 RSSI（在 i+2）永远读不到，
                    // 反而把 TID 误当成一张 EPC 上报（雷达据此做 EPC 过滤必然 0 命中）。
                    // 改为按「结构」配对：EPC 之后若紧跟一段 hex(TID) 再跟数字 → RSSI 在 i+2 且跳过 TID；
                    // 若紧跟数字 → RSSI 在 i+1。这样真实 EPC 拿到 RSSI，TID 不再污染盘点列表。
                    int i = 0;
                    while (i < arr.length) {
                        String raw = arr[i];
                        if (raw == null || raw.trim().isEmpty()) { i++; continue; }
                        String epc = extractEpc(raw.trim());
                        if (epc == null) { parsedFail++; i++; continue; }
                        String rssi = null;
                        int j = i + 1;
                        if (j < arr.length && arr[j] != null) {
                            String a = arr[j].trim();
                            if (a.matches("-?\\d+(\\.\\d+)?")) {
                                rssi = a;                       // [EPC, RSSI]
                                if (!cur.containsKey(epc)) cur.put(epc, rssi);
                                i = j + 1;
                                continue;
                            } else if (j + 1 < arr.length && arr[j + 1] != null) {
                                String b = arr[j + 1].trim();
                                if (b.matches("-?\\d+(\\.\\d+)?")) {
                                    rssi = b;                   // [EPC, TID, RSSI]：跳过 TID
                                    if (!cur.containsKey(epc)) cur.put(epc, rssi);
                                    i = j + 2;
                                    continue;
                                }
                            }
                        }
                        // 没有数字邻居：EPC 单独出现（无 RSSI），仍上报但 rssi 留空
                        if (!cur.containsKey(epc)) cur.put(epc, null);
                        i++;
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
                        // 低频打印原始缓存元素，确认模组是否真的回传了 RSSI 数值（雷达测距依赖它）
                        if ((mPollCount % 14) == 1) {
                            StringBuilder sb = new StringBuilder("轮询原始:");
                            for (int k = 0; k < Math.min(arr.length, 8); k++) {
                                sb.append(" [").append(k).append("]='").append(arr[k]).append("'");
                            }
                            Log.i(TAG, sb.toString());
                        }
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
                    data = normalizeHex((String) r);
                    ok = true;
                    Log.i(TAG, "readTag: ok bank=" + bank + " ptr=" + wordAddr + " len=" + wordLen + " hex=" + data);
                } else {
                    Log.w(TAG, "readTag: 返回非预期 r=" + r + " class=" + (r == null ? "null" : r.getClass().getName()));
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
                    data = normalizeHex((String) r);
                    ok = true;
                    Log.i(TAG, "readTag(过滤): ok bank=" + bank + " ptr=" + wordAddr + " len=" + wordLen
                            + " filterEpc=" + filterEpc + " hex=" + data);
                } else {
                    // 主路径 readTag 返回空：按 iUHF 实测可用路径兜底
                    if (bank == 1) {
                        // EPC 区：改走 readFullEpcTag（设备预装 iUHF 的稳妥读 EPC 路径，内部三次重试+过滤）
                        data = readEpcViaFull(pwd, bank, wordAddr, wordLen, filterEpc);
                        ok = data != null;
                        if (ok) Log.i(TAG, "readTag(过滤): EPC 改走 readFullEpcTag 成功 hex=" + data);
                    } else if (filterEpc != null && !filterEpc.isEmpty()) {
                        // 非 EPC 区(TID/USER)：再试 iUHF 风格过滤(fBank=1,fPtr=1,fCnt=1，按整字偏移)
                        try {
                            Object r2 = callIface("readTag",
                                    pwd == null ? "" : pwd, bank, wordAddr, wordLen,
                                    filterEpc, 1, 1, 1);
                            if (r2 instanceof String && !((String) r2).isEmpty()) {
                                data = normalizeHex((String) r2);
                                ok = true;
                                Log.i(TAG, "readTag(过滤): TID/USER 改走 iUHF 风格过滤(1,1,1) 成功 hex=" + data);
                            }
                        } catch (Throwable e2) { Log.w(TAG, "readTag 兜底(1,1,1) 异常", e2); }
                    }
                    if (!ok) {
                        Log.w(TAG, "readTag(过滤): 返回非预期 r=" + r + " class=" + (r == null ? "null" : r.getClass().getName())
                                + " filterEpc=" + filterEpc + " fBank=" + fBank + " fStartBit=" + fStartBit + " fLenBits=" + fLenBits);
                        msg = "读取为空（未读到匹配标签，请确认过滤条件与天线范围内标签一致）";
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
                // 业务层传入的是明文(ASCII/UTF-8)，SDK 入参为 hex 字符串，这里转换。
                byte[] b = writeData.getBytes(StandardCharsets.UTF_8);
                if (b.length % 2 != 0) {                  // 凑成整字节
                    byte[] p = new byte[b.length + 1];
                    System.arraycopy(b, 0, p, 0, b.length);
                    b = p;
                }
                String hex = toHex(b);
                int cnt = b.length / 2;                  // 字数 = 字节数 / 2

                // 本机固件（system UHFManager 活实例）未暴露 WriteTagDataAndReadTagData，
                // 改走真实存在且可用的 writeTag(...)（见 writeTagOnSystem，返回 boolean）。
                ok = writeTagOnSystem(pwd == null ? "" : pwd, writeBank, startWord, cnt, hex,
                                fBank, fStartBit, fLenBits, fData == null ? "" : fData);
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

    /**
     * 直接打到系统真实存在的写方法 writeTag(String,int,int,int,String,int,int,int,String)->boolean。
     * 本机固件（system UHFManager 活实例）并未暴露 WriteTagDataAndReadTagData，
     * 因此写标签改走此路径。优先目标为 mRawManager（UHFInterface 内部持有的真实硬件活实例），
     * 回退到 mUhf（getSystemService 直取），再回退到 mIface（UHFInterface 本身）。
     */
    private boolean writeTagOnSystem(String pwd, int bank, int ptr, int cnt, String dataHex,
                                     int fBank, int fStartBit, int fLenBits, String fData) {
        try {
            ensureSdk();
            Object target = null;
            Class<?> clazz = null;
            if (mRawManager != null) { target = mRawManager; clazz = mRawManager.getClass(); }
            else if (mUhf != null)  { target = mUhf;      clazz = mUhfClass; }
            else if (mIface != null) { target = mIface;    clazz = mIfaceClass; }
            if (target == null || clazz == null) {
                Log.w(TAG, "writeTagOnSystem: 无可用写目标");
                return false;
            }
            Log.i(TAG, "writeTagOnSystem args: pwd='" + pwd + "' bank=" + bank + " ptr=" + ptr + " cnt=" + cnt
                    + " dataHex=" + dataHex + " fBank=" + fBank + " fStartBit=" + fStartBit
                    + " fLenBits=" + fLenBits + " fData=" + fData + " target=" + clazz.getName());
            Object[] args = new Object[]{ pwd, bank, ptr, cnt, dataHex, fBank, fStartBit, fLenBits, fData };
            for (Method m : clazz.getMethods()) {
                if (!m.getName().equals("writeTag") || m.getParameterTypes().length != 9) continue;
                Class<?>[] pts = m.getParameterTypes();
                boolean compatible = true;
                for (int i = 0; i < 9; i++) {
                    if (!box(pts[i]).isAssignableFrom(args[i].getClass())) { compatible = false; break; }
                }
                if (!compatible) continue;
                try {
                    Object r = m.invoke(target, args);
                    boolean ok = r instanceof Boolean && (Boolean) r;
                    Log.i(TAG, "writeTagOnSystem: 调用 writeTag 成功 ret=" + ok
                            + " target=" + clazz.getName());
                    return ok;
                } catch (java.lang.reflect.InvocationTargetException ite) {
                    Throwable cause = ite.getCause();
                    Log.e(TAG, "writeTagOnSystem: writeTag 调用抛异常", cause);
                    return false;
                } catch (IllegalAccessException iae) {
                    Log.w(TAG, "writeTagOnSystem: writeTag 权限受限，尝试下一重载", iae);
                }
            }
            Log.w(TAG, "writeTagOnSystem: 未找到兼容的 writeTag 方法（target=" + clazz.getName() + "）");
        } catch (Throwable e) {
            Log.e(TAG, "writeTagOnSystem 异常", e);
        }
        return false;
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

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 诊断/读取辅助：归一化 hex、readFullEpcTag 桥接、raw 读（供 READ_TEST 干净探针）
    // ------------------------------------------------------------------

    /** 归一化 hex：去空白、去 0x 前缀、转大写、剔除非 hex 字符；空则返回 null */
    private static String normalizeHex(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.toLowerCase(java.util.Locale.US).startsWith("0x")) t = t.substring(2);
        t = t.toUpperCase(java.util.Locale.US).replaceAll("[^0-9A-Fa-f]", "");
        return t.isEmpty() ? null : t;
    }

    /**
     * EPC 区读取兜底：调用设备预装 iUHF 的 readFullEpcTag（返回 Bundle: fullEpc/epc/pc/epcWordCount）。
     * 这是 iUHF 软件读 EPC 的稳妥路径——内部对匹配 filterEpc 的标签做 SELECT 后读取，并自带三次重试。
     */
    private String readEpcViaFull(String pwd, int bank, int ptr, int cnt, String filterEpc) {
        try {
            Object r = callIface("readFullEpcTag",
                    pwd == null ? "" : pwd, bank, ptr, cnt, filterEpc == null ? "" : filterEpc);
            if (r instanceof android.os.Bundle) {
                android.os.Bundle b = (android.os.Bundle) r;
                Log.i(TAG, "readEpcViaFull bundle=" + bundleKeys(b));
                String epc = b.getString("epc");
                if (epc == null || epc.isEmpty()) epc = b.getString("fullEpc");
                return normalizeHex(epc);
            }
            Log.w(TAG, "readEpcViaFull 返回非 Bundle: " + (r == null ? "null" : r.getClass().getName()));
        } catch (Throwable e) {
            Log.w(TAG, "readEpcViaFull 异常", e);
        }
        return null;
    }

    /** 诊断用：直接调用设备 readFullEpcTag，返回原始 Bundle（含 fullEpc/epc/pc/epcWordCount），不做任何兜底 */
    public android.os.Bundle readFullEpcTagRaw(String pwd, int bank, int ptr, int cnt, String filterEpc) {
        try {
            ensureSdk();
            Object r = callIface("readFullEpcTag",
                    pwd == null ? "" : pwd, bank, ptr, cnt, filterEpc == null ? "" : filterEpc);
            if (r instanceof android.os.Bundle) return (android.os.Bundle) r;
            Log.w(TAG, "readFullEpcTagRaw 返回非 Bundle: " + (r == null ? "null" : r.getClass().getName()));
        } catch (Throwable e) {
            Log.w(TAG, "readFullEpcTagRaw 异常", e);
        }
        return null;
    }

    /** 诊断用：原始 readTag（无兜底），返回归一化 hex 或 null；供 READ_TEST 干净探针区分各参数组合是否可用 */
    public String readTagRaw(String pwd, int bank, int ptr, int cnt,
                             String filterEpc, int fBank, int fPtr, int fCnt) {
        try {
            Object r = callIface("readTag",
                    pwd == null ? "" : pwd, bank, ptr, cnt,
                    filterEpc == null ? "" : filterEpc, fBank, fPtr, fCnt);
            if (r instanceof String && !((String) r).isEmpty()) return normalizeHex((String) r);
            Log.w(TAG, "readTagRaw 返回非预期 r=" + r + " class=" + (r == null ? "null" : r.getClass().getName()));
        } catch (Throwable e) {
            Log.w(TAG, "readTagRaw 异常", e);
        }
        return null;
    }

    /** 诊断用：设置盘点返回的 bank（readTagModeSet 内部即 inventoryTagBankSet），flag 默认 true */
    public boolean setInventoryBankRaw(int bank, int ptr, int cnt) {
        try {
            ensureSdk();
            Object r = callIface("inventoryTagBankSet", bank, ptr, cnt, true);
            Log.i(TAG, "setInventoryBankRaw bank=" + bank + " ptr=" + ptr + " cnt=" + cnt + " -> " + r);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable e) { Log.w(TAG, "setInventoryBankRaw 异常", e); }
        return false;
    }

    /** 诊断用：直接取盘点缓存原始数组（readTagFromBuffer） */
    public String[] readBufferRaw() {
        try {
            ensureSdk();
            Object o = callIface("readTagFromBuffer");
            if (o instanceof String[]) return (String[]) o;
            Log.w(TAG, "readBufferRaw 返回非 String[]: " + (o == null ? "null" : o.getClass().getName()));
        } catch (Throwable e) { Log.w(TAG, "readBufferRaw 异常", e); }
        return new String[0];
    }

    /** 诊断用：readTagModeSet(I,I,I,I) */
    public boolean readModeSetRaw(int a, int b, int c, int d) {
        try { ensureSdk(); Object r = callIface("readTagModeSet", a, b, c, d);
              Log.i(TAG, "readModeSetRaw " + a + "," + b + "," + c + "," + d + " -> " + r);
              return r instanceof Boolean && (Boolean) r; }
        catch (Throwable e) { Log.w(TAG, "readModeSetRaw 异常", e); }
        return false;
    }

    /** 诊断用：filterSet(I,I,I,String,I) -> inventoryFilterData（按 EPC/TID 选中单张） */
    public boolean filterSetRaw(int bank, int ptr, int cnt, String data, int flag) {
        try { ensureSdk(); Object r = callIface("filterSet", bank, ptr, cnt, data == null ? "" : data, flag);
              Log.i(TAG, "filterSetRaw bank=" + bank + " ptr=" + ptr + " cnt=" + cnt + " flag=" + flag + " -> " + r);
              return r instanceof Boolean && (Boolean) r; }
        catch (Throwable e) { Log.w(TAG, "filterSetRaw 异常", e); }
        return false;
    }

    /** 诊断用：直接调用 WriteTagDataAndReadTagData（写后回读，返回 Bundle），同步返回原始 Bundle */
    public android.os.Bundle writeTagDataAndReadRaw(String pwd, int bank, int ptr, int cnt,
                                                    String dataHex, int fBank, int fStartBit,
                                                    int fLenBits, String fData) {
        try {
            ensureSdk();
            Object r = callIface("WriteTagDataAndReadTagData",
                    pwd == null ? "" : pwd, bank, ptr, cnt,
                    dataHex == null ? "" : dataHex,
                    fBank, fStartBit, fLenBits, fData == null ? "" : fData);
            if (r instanceof android.os.Bundle) return (android.os.Bundle) r;
            Log.w(TAG, "writeTagDataAndReadRaw 返回非 Bundle: " + (r == null ? "null" : r.getClass().getName()));
        } catch (Throwable e) {
            Log.w(TAG, "writeTagDataAndReadRaw 异常", e);
        }
        return null;
    }

    /** 诊断用：setInventoryExMode(int) */
    public boolean setInventoryExModeRaw(int mode) {
        try { ensureSdk(); Object r = callIface("setInventoryExMode", mode);
              Log.i(TAG, "setInventoryExModeRaw " + mode + " -> " + r);
              return r instanceof Boolean && (Boolean) r; }
        catch (Throwable e) { Log.w(TAG, "setInventoryExModeRaw 异常", e); }
        return false;
    }

    /** 诊断用：setFasTidMode(int) */
    public boolean setFasTidModeRaw(int mode) {
        try { ensureSdk(); Object r = callIface("setFasTidMode", mode);
              Log.i(TAG, "setFasTidModeRaw " + mode + " -> " + r);
              return r instanceof Boolean && (Boolean) r; }
        catch (Throwable e) { Log.w(TAG, "setFasTidModeRaw 异常", e); }
        return false;
    }

    /** 诊断用：setFastID（盘点时一并回传 TID） */
    public boolean setFastIDRaw(boolean on) {
        try { ensureSdk(); Object r = callIface("setFastID", on);
              Log.i(TAG, "setFastIDRaw " + on + " -> " + r);
              return r instanceof Boolean && (Boolean) r; }
        catch (Throwable e) { Log.w(TAG, "setFastIDRaw 异常", e); }
        return false;
    }

    /**
     * 诊断用：枚举系统 UHFManager（活实例）与 UHFInterface 上【全部】公开方法签名，
     * 用于确认本固件到底暴露了哪个真实写方法（WriteTagDataAndReadTagData 在本机固件不存在）。
     */
    /** 诊断用：设置写模式 setWriteMode(int) */
    public boolean setWriteModeRaw(int mode) {
        try { ensureSdk(); Object r = callIface("setWriteMode", mode);
              Log.i(TAG, "setWriteModeRaw " + mode + " -> " + r);
              return r instanceof Boolean && (Boolean) r; }
        catch (Throwable e) { Log.w(TAG, "setWriteModeRaw 异常", e); }
        return false;
    }

    /** 诊断用：写 EPC（无过滤）writeDataToEpc(pwd,ptr,cnt,dataHex) */
    public boolean writeDataToEpcRaw(String pwd, int ptr, int cnt, String data) {
        try {
            ensureSdk();
            byte[] b = data.getBytes(StandardCharsets.UTF_8);
            if (b.length % 2 != 0) { byte[] p = new byte[b.length + 1]; System.arraycopy(b, 0, p, 0, b.length); b = p; }
            String hex = toHex(b);
            int c = b.length / 2;
            Object r = callIface("writeDataToEpc", pwd == null ? "" : pwd, ptr, c, hex);
            boolean ok = r instanceof Boolean && (Boolean) r;
            Log.i(TAG, "writeDataToEpcRaw ptr=" + ptr + " cnt=" + c + " -> " + ok);
            return ok;
        } catch (Throwable e) { Log.w(TAG, "writeDataToEpcRaw 异常", e); }
        return false;
    }

    /** 诊断用：读取当前发射功率(dBm) */
    public int powerGetRaw() {
        try { ensureSdk(); Object r = callIface("powerGet"); return r instanceof Integer ? (Integer) r : -1; }
        catch (Throwable e) { Log.w(TAG, "powerGetRaw 异常", e); return -1; }
    }
    /** 诊断用：设置发射功率(dBm)，返回是否成功 */
    public boolean powerSetRaw(int dbm) {
        try { ensureSdk(); Object r = callIface("powerSet", dbm); return r instanceof Boolean && (Boolean) r; }
        catch (Throwable e) { Log.w(TAG, "powerSetRaw 异常", e); return false; }
    }
    /** 诊断用：显式上电。用于 stopInventory 关电源被 SELinux 拒绝后尝试恢复模组状态 */
    public boolean powerOnRaw() {
        try { ensureSdk(); Object r = callIface("powerOn"); return r instanceof Boolean && (Boolean) r; }
        catch (Throwable e) { Log.w(TAG, "powerOnRaw 异常", e); return false; }
    }
    /** 诊断用：显式下电（注意：SELinux Enforcing 下可能返回 false 并把模组状态搞乱） */
    public boolean powerOffRaw() {
        try { ensureSdk(); Object r = callIface("powerOff"); return r instanceof Boolean && (Boolean) r; }
        catch (Throwable e) { Log.w(TAG, "powerOffRaw 异常", e); return false; }
    }
    /** 诊断用：模组当前是否上电 */
    public boolean isPowerOnRaw() {
        try { ensureSdk(); Object r = callIface("isPowerOn"); return r instanceof Boolean && (Boolean) r; }
        catch (Throwable e) { Log.w(TAG, "isPowerOnRaw 异常", e); return false; }
    }

    public void logWriteApi() {
        try {
            ensureSdk();
            Class<?> rm = (mRawManager != null) ? mRawManager.getClass()
                    : (mUhf != null ? mUhf.getClass() : null);
            if (rm != null) {
                java.lang.reflect.Method[] ms = rm.getMethods();
                Log.i(TAG, "ALL_API systemUHFManager(" + rm.getName() + ") 共 " + ms.length + " 个方法");
                for (int i = 0; i < ms.length; i += 12) {
                    StringBuilder sb = new StringBuilder("ALL_API_MGR[" + i + "]:");
                    for (int j = i; j < ms.length && j < i + 12; j++)
                        sb.append("\n  ").append(ms[j].toString());
                    Log.i(TAG, sb.toString());
                }
            }
            if (mIfaceClass != null) {
                java.lang.reflect.Method[] ms2 = mIfaceClass.getMethods();
                Log.i(TAG, "ALL_API UHFInterface 共 " + ms2.length + " 个方法");
                for (int i = 0; i < ms2.length; i += 12) {
                    StringBuilder sb = new StringBuilder("ALL_API_IF[" + i + "]:");
                    for (int j = i; j < ms2.length && j < i + 12; j++)
                        sb.append("\n  ").append(ms2[j].toString());
                    Log.i(TAG, sb.toString());
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "logWriteApi 异常", e);
        }
    }
}
