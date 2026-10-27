package com.example.rfidwriter.rfid;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import dalvik.system.DexClassLoader;

/**
 * iData T1 UHF 原生 SDK 适配层（真实可用版 · 对齐 iUHF 内置软件）。
 *
 * 关键修正（参考手持机预装 iUHF 软件逆向结论）：
 *   iUHF 并不是把 com.idata.UHFManager 当普通类 new 出来用，而是通过系统 UHF 服务获取
 *   已经绑定硬件的“活”实例：
 *       com.idata.uhfservicesdk.UHFInterface 的构造函数：
 *           this.uhfManager = (com.idata.UHFManager) context.getSystemService("idata_uhf");
 *   iUHF 自身是普通（非系统）App，却能用 getSystemService("idata_uhf") 拿到 UHF 管理器，
 *   说明该服务对第三方 App 全局可用。本适配层完全复刻这一路径：
 *     1) 优先 mContext.getSystemService("idata_uhf") 取到活实例（最稳，零类加载器问题）；
 *     2) 仅在取不到时，回退到 DexClassLoader 加载 assets/uhf_sdk.dex 后 newInstance（旧逻辑）。
 *   注意：之前“读无反应”的根因正是 newInstance 出来的是没有绑定硬件服务的空壳对象，
 *   即使 powerOn() 返回 true，startInventoryTag() 也不会回传任何标签。
 *
 * 设备侧已验证的类与方法（来自 iUHF.apk / uhf_sdk.dex 的逆向）：
 *   com.idata.UHFManager
 *     boolean powerOn()
 *     boolean powerOff()
 *     boolean startInventoryTag()
 *     boolean stopInventory()
 *     boolean inventoryModeSet(int mode, boolean flag)   // flag=true 才真正开启连续盘点
 *     String[] inventoryDataGet()                        // 读取缓存的标签数组
 *     void    registerUHFCallback(android.os.IUHFResultListener)
 *     void    unregisterUHFCallback(android.os.IUHFResultListener)
 *     String  readTag(String pwd,int bank,int ptr,int cnt,String filterEpc,int fBank,int fPtr,int fCnt)
 *     boolean writeTag(String pwd,int bank,int ptr,int cnt,String dataHex,int fBank,int fPtr,int fCnt,String fEpc)
 *     boolean powerSet(int dbm)
 *   android.os.IUHFResultListener
 *     void onUHFCallBack(String[] tags)   // 每个元素是一条标签信息（EPC hex，可能含 TID/RSSI）
 *
 * 约定（与 iData UHF SDK / 插件一致）：
 *   - Bank 编码 0=RESERVED 1=EPC 2=TID 3=USER
 *   - 过滤地址 fStartBlock 为【bit】，EPC 默认从 32 开始
 *   - 写入/读取地址为【Word】，1 Word = 4 个 hex 字符 = 2 字节
 *   - 默认访问密码 00000000
 *   - readTag 返回【hex 字符串】（MainActivity 侧用 hex 解析）
 *   - writeTag 的 data 入参为【hex 字符串】，本适配层把业务层传入明文转 hex 再写
 */
public class IdataUhfAdapter implements RfidSdk {

    private static final String TAG = "IdataUhfAdapter";
    private static final String SDK_CLASS = "com.idata.UHFManager";
    private static final String LISTENER_CLASS = "android.os.IUHFResultListener";
    private static final String SYSTEM_SERVICE_NAME = "idata_uhf";
    private static final String DEX_ASSET = "uhf_sdk.dex";
    // 与 assets/uhf_sdk.dex 实际大小一致；不一致则重新拷贝（用于覆盖升级）
    private static final long EXPECTED_DEX_SIZE = 7323736L;

    private Context mContext;                       // application context
    private DexClassLoader mLoader;
    private Class<?> mUhfClass;
    private Object mUhf;                            // com.idata.UHFManager 实例（活实例）
    private Class<?> mListenerIface;
    private Object mListener;                       // IUHFResultListener 的 Proxy
    private InventoryCallback mInvCb;

    private final AtomicBoolean mConnected = new AtomicBoolean(false);
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private int mModuleType = 0;
    private boolean mFromSystemService = false;

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
        } catch (Throwable e) {
            Log.w(TAG, "attach: 预加载 dex 失败（将仅尝试系统服务路径）", e);
        }
    }

    // ------------------------------------------------------------------
    // 获取 UHF 管理器实例（活实例优先走系统服务）
    // ------------------------------------------------------------------

    private synchronized void ensureSdk() throws Exception {
        if (mUhf != null && mUhfClass != null) return;
        if (mContext == null) throw new IllegalStateException("SDK 未 attach(Context)，无法加载");

        // 路径 1（推荐，对齐 iUHF）：从系统 UHF 服务取已经绑定硬件的活实例
        Object svc = null;
        try {
            svc = mContext.getSystemService(SYSTEM_SERVICE_NAME);
        } catch (Throwable e) {
            Log.w(TAG, "getSystemService(\"idata_uhf\") 抛异常", e);
        }
        if (svc != null) {
            mUhf = svc;
            mUhfClass = svc.getClass();          // 用运行时类做反射，杜绝类加载器不一致
            mFromSystemService = true;
            Log.i(TAG, "ensureSdk: 通过系统服务获取 UHFManager，class=" + mUhfClass.getName());
            return;
        }

        // 路径 2（回退）：DexClassLoader 加载 dex 后实例化
        Log.w(TAG, "ensureSdk: 系统服务取不到，回退到 DexClassLoader newInstance（旧路径，可能无硬件连接）");
        if (mLoader == null) throw new IllegalStateException("无系统服务且 dex 未就绪");
        mUhfClass = mLoader.loadClass(SDK_CLASS);
        try {
            mUhf = mUhfClass.getConstructor().newInstance();
        } catch (NoSuchMethodException e) {
            mUhf = mUhfClass.getConstructor(Context.class).newInstance(mContext);
        }
        mFromSystemService = false;
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

    /** SDK 回调回来的标签串数组 -> 解析为 TagInfo -> 转发给业务层 InventoryCallback */
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
     * 解析单条标签信息串。iData UHF 回调通常返回 EPC 的 hex 串；
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
                msg = ok ? "UHF 已连接" : "UHF 初始化失败（powerOn 返回 false，请确认设备 UHF 模块正常）";
                Log.i(TAG, "init: powerOn=" + ok + " moduleType=" + mModuleType
                        + " fromSystemService=" + mFromSystemService);
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
            // flag=true 才真正开启盘点模式（连续盘点才会回传标签）；
            // 之前一直传 false 导致 mode=1 也被当成“关闭”，于是 startInventoryTag 不再流标签，
            // 单张检测始终超时返回 null（“读取无反应”的根因）。这里把 flag 与 mode 绑定：mode≠0 即开启。
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
            ensureListener();
            sdkMethod("registerUHFCallback", mListenerIface).invoke(mUhf, mListener);
            sdkMethod("startInventoryTag").invoke(mUhf);
        } catch (Throwable e) {
            Log.e(TAG, "startInventory failed", e);
        }
    }

    @Override
    public void stopInventory() {
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
