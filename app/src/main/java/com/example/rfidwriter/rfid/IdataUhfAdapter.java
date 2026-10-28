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

    // 轮询去重：避免每轮把同一批累积缓存反复转发
    private final Set<String> mPollSeen = new HashSet<>();
    private int mPollCount = 0;

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

    /**
     * 解析单条标签信息串。iData UHF 回调/缓存通常返回 EPC 的 hex 串；
     * 部分模式下会用 '|' / ',' / '#' / 空白 追加 TID/RSSI。
     * 只把【偶长度、纯 0-9A-F 的 hex 串】认作 EPC，纯数字串认作 RSSI，其余忽略。
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

    /** 在 UHFInterface 类上调用方法（优先）；若 UHFInterface 不可用，回退到 mUhf 运行时类反射 */
    private Object callIface(String name, Class<?>[] paramTypes, Object... args) throws Exception {
        if (mIface != null) {
            return mIfaceClass.getMethod(name, paramTypes).invoke(mIface, args);
        }
        if (mUhf != null) {
            return mUhfClass.getMethod(name, paramTypes).invoke(mUhf, args);
        }
        throw new IllegalStateException("SDK 未初始化");
    }

    /** 群读缓存读取：UHFInterface 路径用 readTagFromBuffer()，兜底路径用 inventoryDataGet() */
    private String[] pollBuffer() {
        try {
            if (mIface != null) {
                Object o = mIfaceClass.getMethod("readTagFromBuffer").invoke(mIface);
                return (o instanceof String[]) ? (String[]) o : new String[0];
            }
            if (mUhf != null) {
                Object o = mUhfClass.getMethod("inventoryDataGet").invoke(mUhf);
                return (o instanceof String[]) ? (String[]) o : new String[0];
            }
        } catch (Throwable e) {
            Log.w(TAG, "pollBuffer 异常（忽略，继续下一轮）", e);
        }
        return new String[0];
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
            callIface("powerSet", int.class, dbm);
        } catch (Throwable ignore) { /* best effort */ }
    }

    @Override
    public void setInventoryMode(int mode) {
        if (!mConnected.get()) return;
        try {
            // iData UHFManager.inventoryModeSet(int mode, boolean flag)：
            // flag=true 才真正开启盘点模式（连续盘点才会回传标签）。
            callIface("inventoryModeSet", int.class, boolean.class, mode, mode != 0);
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
                callIface("registerUHFCallback", mListenerIface, mListener);
            } catch (Throwable e) {
                Log.w(TAG, "registerUHFCallback 绑定失败（忽略，走轮询路径）", e);
            }
            callIface("startInventoryTag");
            Log.i(TAG, "startInventory: startInventoryTag 已调用");
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
                                final TagInfo ft = new TagInfo();
                                ft.epc = epc;
                                if (mInvCb != null) mMain.post(() -> mInvCb.onTag(ft));
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
                                String.class, int.class, int.class, int.class,
                                String.class, int.class, int.class, int.class,
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
                                String.class, int.class, int.class, int.class, String.class,
                                int.class, int.class, int.class, String.class,
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

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }
}
