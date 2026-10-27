package com.example.rfidwriter.rfid;

import android.content.Context;
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
 * iData T1 UHF 原生 SDK 适配层（真实可用版 · 对齐手持机预装 iUHF 软件）。
 *
 * ############################################################################
 * 根因与修正（参考手持机预装 iUHF 软件逆向结论）
 * ############################################################################
 * 早期版本把 com.idata.UHFManager 当普通类 new 出来用（DexClassLoader newInstance），
 * 得到的是【没有绑定硬件的空壳对象】—— powerOn() 能返回 true，但 startInventoryTag()
 * 永远不回传标签，readTag() 永远返回空。这就是“读无反应 / 无法正常使用”的真正根因。
 *
 * iUHF（手持机预装，普通非系统 App）的正确路径是：
 *   com.idata.uhfservicesdk.UHFInterface 构造函数：
 *       this.uhfManager = (com.idata.UHFManager) context.getSystemService("idata_uhf");
 * 即：通过系统 UHF 服务拿到【已经绑定硬件的活实例】。本适配层完全复刻这一路径：
 *   路径 A（首选）：mContext.getSystemService("idata_uhf") 取到活实例（零类加载器问题）；
 *   路径 B（兜底）：DexClassLoader 加载 UHFInterface，取其内部 uhfManager 字段（同样是活实例）；
 *   路径 C（最后）：newInstance（旧逻辑，可能无硬件连接，仅作保底）。
 *
 * 关于 isAllowedCallerPackage()：iUHF 的 UHFInterface 确有此私有方法，但它【从未被
 * read/write 方法调用】—— readFullEpcTag / writeWholeEpc 直接调用 uhfManager.readTag/
 * writeTag，没有任何调用方包名校验。故第三方 App（含本 App）可正常使用该 SDK。
 *
 * ############################################################################
 * 盘点/读取“无反应”的第二个根因
 * ############################################################################
 * iUHF 的群读是通过轮询 inventoryDataGet()（readTagFromBuffer）拿标签的，而本 App 早期
 * 只依赖 registerUHFCallback 回调。若回调代理未能正确绑定（接口类加载器不匹配等），回调
 * 不触发，detectSingleTag / 盘点就永远收不到标签 → “没有反应”。
 * 本版改为：startInventoryTag 后【额外启动一个轮询线程】，每 ~150ms 调一次 inventoryDataGet()
 * 解析并转发标签，彻底摆脱对回调机制的依赖（回调仍会注册，作为双保险）。
 *
 * ############################################################################
 * 已逆向确认的类与方法（来自 iUHF.apk / uhf_sdk.dex）
 * ############################################################################
 *   com.idata.UHFManager（活实例，经 getSystemService("idata_uhf") 取得）
 *     boolean powerOn()                        // 上电
 *     boolean powerOff()
 *     boolean startInventoryTag()             // 开始群读
 *     boolean stopInventory()
 *     boolean inventoryModeSet(int mode, boolean flag)  // flag=true 才真正开启连续盘点
 *     String[] inventoryDataGet()             // 读取缓存标签数组（iUHF 群读主路径）
 *     void    registerUHFCallback(android.os.IUHFResultListener)
 *     void    unregisterUHFCallback(android.os.IUHFResultListener)
 *     String  readTag(String pwd,int bank,int ptr,int cnt,String filterEpc,int fBank,int fPtr,int fCnt)
 *     boolean writeTag(String pwd,int bank,int ptr,int cnt,String dataHex,int fBank,int fStartBit,int fLenBits,String fEpc)
 *     boolean powerSet(int dbm)
 *   android.os.IUHFResultListener.onUHFCallBack(String[] tags)  // 回调，每元素一条标签信息
 *
 * 约定（与 iData UHF SDK / 插件一致）：
 *   - Bank 编码 0=RESERVED 1=EPC 2=TID 3=USER
 *   - 过滤地址 fStartBlock 为【bit】，EPC 默认从 32 开始
 *   - 写入/读取地址为【Word】，1 Word = 4 个 hex 字符 = 2 字节
 *   - 默认访问密码 00000000
 */
public class IdataUhfAdapter implements RfidSdk {

    private static final String TAG = "IdataUhfAdapter";
    private static final String MANAGER_CLASS = "com.idata.UHFManager";
    private static final String LISTENER_CLASS = "android.os.IUHFResultListener";
    private static final String UHFINTERFACE_CLASS = "com.idata.uhfservicesdk.UHFInterface";
    private static final String SYSTEM_SERVICE_NAME = "idata_uhf";
    private static final String DEX_ASSET = "uhf_sdk.dex";
    // 与 assets/uhf_sdk.dex 实际大小一致；不一致则重新拷贝（用于覆盖升级）
    private static final long EXPECTED_DEX_SIZE = 7323736L;

    private Context mContext;                       // application context
    private DexClassLoader mLoader;
    private Class<?> mUhfClass;                     // 活实例的运行时类（用于反射，杜绝类加载器不一致）
    private Object mUhf;                            // com.idata.UHFManager 活实例
    private Class<?> mListenerIface;
    private Object mListener;                       // IUHFResultListener 的 Proxy
    private InventoryCallback mInvCb;

    private final AtomicBoolean mConnected = new AtomicBoolean(false);
    private final AtomicBoolean mPolling = new AtomicBoolean(false);
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private String mBindPath = "none";             // 诊断：实际用了哪条绑定路径
    private int mModuleType = 0;

    // 轮询去重：避免每 150ms 把同一批累积缓存反复转发
    private final Set<String> mPollSeen = new HashSet<>();
    private int mPollCount = 0;

    /** 必须在任意 SDK 调用前（如 MainActivity.onCreate 中）调用，提供 Context 以访问 assets 与创建 DexClassLoader */
    public void attach(Context ctx) {
        mContext = ctx.getApplicationContext();
        // 提前把 dex 拷出来，确保后面回退路径 / 回调接口类一定能拿到
        try {
            File dexFile = new File(mContext.getCacheDir(), DEX_ASSET);
            if (!dexFile.exists() || dexFile.length() != EXPECTED_DEX_SIZE) {
                copyAsset(dexFile);
            }
            dexFile.setReadOnly();
            File optDir = mContext.getCodeCacheDir();
            mLoader = new DexClassLoader(dexFile.getAbsolutePath(), optDir.getAbsolutePath(), null, mContext.getClassLoader());
            // 触发 UHFManager 静态初始化：若其负责向系统注册 "idata_uhf" 服务，则 getSystemService 即可返回活实例
            try { mLoader.loadClass(MANAGER_CLASS); } catch (Throwable ignore) { }
        } catch (Throwable e) {
            Log.w(TAG, "attach: 预加载 dex 失败（将仅尝试系统服务路径）", e);
        }
    }

    // ------------------------------------------------------------------
    // 获取 UHF 管理器活实例（路径 A > B > C）
    // ------------------------------------------------------------------

    private synchronized void ensureSdk() throws Exception {
        if (mUhf != null && mUhfClass != null) return;
        if (mContext == null) throw new IllegalStateException("SDK 未 attach(Context)，无法加载");

        // 路径 A（推荐，对齐 iUHF）：从系统 UHF 服务取已经绑定硬件的活实例
        Object svc = null;
        try {
            svc = mContext.getSystemService(SYSTEM_SERVICE_NAME);
        } catch (Throwable e) {
            Log.w(TAG, "getSystemService(\"idata_uhf\") 抛异常", e);
        }
        if (svc != null) {
            mUhf = svc;
            mUhfClass = svc.getClass();          // 用运行时类做反射，杜绝类加载器不一致
            mBindPath = "getSystemService";
            Log.i(TAG, "ensureSdk: 路径A 通过系统服务获取 UHFManager，class=" + mUhfClass.getName());
            return;
        }

        // 路径 B（兜底）：iUHF 的 UHFInterface 包装类内部也走 getSystemService，取其内部的 uhfManager 字段
        if (mLoader != null) {
            try {
                Class<?> iface = mLoader.loadClass(UHFINTERFACE_CLASS);
                Constructor<?> c = iface.getConstructor(Context.class);
                Object ui = c.newInstance(mContext);
                Field f = iface.getDeclaredField("uhfManager");
                f.setAccessible(true);
                Object mgr = f.get(ui);
                if (mgr != null) {
                    mUhf = mgr;
                    mUhfClass = mgr.getClass();
                    mBindPath = "UHFInterface";
                    Log.i(TAG, "ensureSdk: 路径B 通过 UHFInterface.uhfManager 获取，class=" + mUhfClass.getName());
                    return;
                }
            } catch (Throwable e) {
                Log.w(TAG, "ensureSdk: 路径B 失败，回退路径C", e);
            }
        }

        // 路径 C（旧逻辑，可能无硬件连接）：DexClassLoader 加载 dex 后实例化
        Log.w(TAG, "ensureSdk: 系统服务与 UHFInterface 均取不到，回退到 DexClassLoader newInstance（旧路径，可能无硬件连接）");
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
        // 优先用框架类（android.os.IUHFResultListener 通常随系统服务一起可见）；否则从 dex 取
        try {
            mListenerIface = Class.forName(LISTENER_CLASS);
        } catch (ClassNotFoundException e) {
            if (mLoader == null) throw e;
            mListenerIface = mLoader.loadClass(LISTENER_CLASS);
        }
        ClassLoader cl = (mUhf != null) ? mUhf.getClass().getClassLoader() : mListenerIface.getClassLoader();
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
            TagInfo finalT = t;
            mMain.post(() -> mInvCb.onTag(finalT));
        }
    }

    /**
     * 解析单条标签信息串。iData UHF 回调/缓存通常返回 EPC 的 hex 串；
     * 部分模式下会用 '|' / ',' / '#' / 空白 追加 TID/RSSI。
     *
     * 关键修复：回调里常夹杂纯 RSSI 数值（如 "-40"）。旧逻辑会把整串或首段直接当 EPC，
     * 导致 RSSI 被误判成“另一张标签”，单张检测 seen 集合出现幻影条目、seen.size()>1 而判定“多张”放弃读取。
     * 这里只把【偶长度、纯 0-9A-F 的 hex 串】认作 EPC，纯数字串认作 RSSI，其余忽略，确保单张检测准确。
     */
    private TagInfo parseTag(String raw) {
        TagInfo t = new TagInfo();
        if (raw == null) return t;
        String s = raw.trim();
        if (s.isEmpty()) return t;
        String[] parts = s.split("[|,#\\s]+");
        for (String p : parts) {
            p = p.trim();
            if (p.isEmpty()) continue;
            if (t.epc == null && isHexEpc(p)) {
                t.epc = p;
            } else if (t.rssi == null && p.matches("-?\\d+(\\.\\d+)?")) {
                t.rssi = p;
            }
        }
        return t;
    }

    /** 判断是否为合法 EPC hex：偶长度、仅 0-9A-Fa-f、至少 4 字符（2 字节） */
    private static boolean isHexEpc(String p) {
        if (p.length() < 4 || (p.length() % 2) != 0) return false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    /** 在“活实例”的运行时类上查找方法（避免类加载器不一致导致 invoke 失败） */
    private Method sdkMethod(String name, Class<?>... paramTypes) throws NoSuchMethodException {
        return mUhfClass.getMethod(name, paramTypes);
    }

    private Object invokeSdk(String name, Class<?>[] paramTypes, Object... args) throws Exception {
        return sdkMethod(name, paramTypes).invoke(mUhf, args);
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
                Object r = sdkMethod("powerOn").invoke(mUhf);
                ok = r instanceof Boolean && (Boolean) r;
                msg = ok ? ("UHF 已连接（绑定路径：" + mBindPath + "）")
                        : "UHF 初始化失败（powerOn 返回 false，请确认设备 UHF 模块正常）";
                Log.i(TAG, "init: powerOn=" + ok + " moduleType=" + mModuleType + " bindPath=" + mBindPath);
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
            if (mUhf != null) {
                sdkMethod("stopInventory").invoke(mUhf);
                sdkMethod("powerOff").invoke(mUhf);
            }
        } catch (Throwable ignore) { /* best effort */ }
        mConnected.set(false);
    }

    @Override
    public void setPower(int dbm) {
        if (!mConnected.get() || mUhf == null) return;
        try {
            sdkMethod("powerSet", int.class).invoke(mUhf, dbm);
        } catch (Throwable ignore) { /* best effort */ }
    }

    @Override
    public void setInventoryMode(int mode) {
        if (!mConnected.get() || mUhf == null) return;
        try {
            // iData UHFManager.inventoryModeSet(int mode, boolean flag)：
            // flag=true 才真正开启盘点模式（连续盘点才会回传标签）。
            // mode≠0 即开启，避免“读无反应”。
            sdkMethod("inventoryModeSet", int.class, boolean.class)
                    .invoke(mUhf, mode, mode != 0);
            Log.i(TAG, "setInventoryMode: mode=" + mode + " flag=" + (mode != 0));
        } catch (Throwable ignore) { /* best effort */ }
    }

    @Override
    public void startInventory(InventoryCallback cb) {
        if (!mConnected.get() || mUhf == null || cb == null) return;
        mInvCb = cb;
        try {
            // 回调作为双保险注册（即便代理未正确绑定，下面轮询仍会给出标签）
            ensureListener();
            try {
                sdkMethod("registerUHFCallback", mListenerIface).invoke(mUhf, mListener);
            } catch (Throwable e) {
                Log.w(TAG, "registerUHFCallback 绑定失败（忽略，走轮询路径）", e);
            }
            sdkMethod("startInventoryTag").invoke(mUhf);
        } catch (Throwable e) {
            Log.e(TAG, "startInventory failed", e);
        }
        // 轮询 inventoryDataGet()：iUHF 群读主路径，不依赖回调是否触发
        mPollSeen.clear();
        mPollCount = 0;
        mPolling.set(true);
        new Thread(() -> {
            while (mPolling.get()) {
                try {
                    Object o = sdkMethod("inventoryDataGet").invoke(mUhf);
                    if (o instanceof String[]) {
                        String[] arr = (String[]) o;
                        Set<String> cur = new HashSet<>();
                        for (String raw : arr) {
                            if (raw == null || raw.isEmpty()) continue;
                            TagInfo t = parseTag(raw);
                            if (t.epc != null && !t.epc.isEmpty()) cur.add(t.epc);
                        }
                        // 每 ~2s 清空一次去重集，使“再次读到同一标签”也能被重新计数
                        synchronized (mPollSeen) {
                            if ((++mPollCount % 14) == 0) mPollSeen.clear();
                            for (String epc : cur) {
                                if (!mPollSeen.contains(epc)) {
                                    mPollSeen.add(epc);
                                    TagInfo t = new TagInfo();
                                    t.epc = epc;
                                    final TagInfo ft = t;
                                    if (mInvCb != null) mMain.post(() -> mInvCb.onTag(ft));
                                }
                            }
                        }
                    }
                } catch (Throwable ignore) { /* 轮询异常忽略，继续下一轮 */ }
                try { Thread.sleep(150); } catch (InterruptedException e) { break; }
            }
        }, "uhf-inventory-poll").start();
    }

    @Override
    public void stopInventory() {
        mPolling.set(false);
        if (mUhf == null) return;
        try {
            sdkMethod("stopInventory").invoke(mUhf);
        } catch (Throwable ignore) { /* best effort */ }
    }

    @Override
    public void readTag(int bank, int wordAddr, int wordLen, String pwd, ReadCallback cb) {
        if (!mConnected.get() || mUhf == null) {
            if (cb != null) mMain.post(() -> cb.onResult(false, null, "未连接"));
            return;
        }
        new Thread(() -> {
            boolean ok = false;
            String data = null;
            String msg = "";
            try {
                Object r = sdkMethod("readTag",
                                String.class, int.class, int.class, int.class,
                                String.class, int.class, int.class, int.class)
                        .invoke(mUhf,
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
    public void writeTagWithFilter(int fBank, int fStartBit, int fLenBits, String fData,
                                   int writeBank, int startWord, String writeData,
                                   String pwd, WriteCallback cb) {
        if (!mConnected.get() || mUhf == null) {
            if (cb != null) mMain.post(() -> cb.onResult(false, "未连接"));
            return;
        }
        new Thread(() -> {
            boolean ok = false;
            String msg = "";
            try {
                // 业务层传入的是明文(ASCII/UTF-8)，SDK writeTag 的 data 入参为 hex 字符串，这里转换。
                byte[] b = writeData.getBytes(StandardCharsets.UTF_8);
                if (b.length % 2 != 0) {                  // 凑成整字节
                    byte[] p = new byte[b.length + 1];
                    System.arraycopy(b, 0, p, 0, b.length);
                    b = p;
                }
                String hex = toHex(b);
                int cnt = b.length / 2;                  // 字数 = 字节数 / 2
                Object r = sdkMethod("writeTag",
                                String.class, int.class, int.class, int.class, String.class,
                                int.class, int.class, int.class, String.class)
                        .invoke(mUhf,
                                pwd == null ? "" : pwd, writeBank, startWord, cnt, hex,
                                fBank, fStartBit, fLenBits, fData == null ? "" : fData);
                ok = r instanceof Boolean && (Boolean) r;
                msg = ok ? "写入成功" : "写入失败（writeTag 返回 false，请确认仅一张标签在天线范围内）";
            } catch (Throwable e) {
                msg = "写入异常：" + e.getClass().getSimpleName() + " " + e.getMessage();
                Log.e(TAG, "writeTagWithFilter failed", e);
            }
            final boolean fok = ok;
            final String fmsg = msg;
            if (cb != null) mMain.post(() -> cb.onResult(fok, fmsg));
        }).start();
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
