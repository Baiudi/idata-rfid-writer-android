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
 * iData T1 UHF 原生 SDK 适配层（真实可用版）。
 *
 * 由于本机无法获取厂商 SDK 的 aar/jar（且二进制下载被代理拦截），这里改为：
 *   - 把真机预装 UHF Demo（/system/app/iUHF/iUHF.apk）里的 classes.dex 作为 assets/uhf_sdk.dex 打入 APK；
 *   - 运行时用 DexClassLoader 加载该 dex，得到真实的 com.idata.UHFManager；
 *   - 通过反射调用其真实方法（init/powerOn/startInventoryTag/registerUHFCallback/readTag/writeTag 等）。
 * 这样业务层（MainActivity / 批量写标逻辑）只依赖 {@link RfidSdk} 接口，完全不感知反射细节。
 *
 * 设备侧已验证的类与方法（来自 iUHF.apk 的 dex）：
 *   com.idata.UHFManager
 *     boolean powerOn()
 *     boolean powerOff()
 *     boolean startInventoryTag()
 *     void    stopInventory()
 *     void    registerUHFCallback(android.os.IUHFResultListener)
 *     void    unregisterUHFCallback(android.os.IUHFResultListener)
 *     String  readTag(String pwd, int bank, int ptr, int cnt, String filterEpc, int filterBank, int filterPtr, int filterCnt)
 *     boolean writeTag(String pwd, int bank, int ptr, int cnt, String data, int filterBank, int filterPtr, int filterCnt, String filterEpc)
 *     boolean powerSet(int dbm)
 *     boolean inventoryModeSet(int mode, boolean flag)
 *   android.os.IUHFResultListener
 *     void onUHFCallBack(String[] tags)   // 每个元素是一条标签信息（通常为 EPC hex，可能含 TID/RSSI）
 *
 * 约定（与 iData UHF SDK / 插件一致）：
 *   - Bank 编码 0=RESERVED 1=EPC 2=TID 3=USER
 *   - 过滤地址 fStartBlock 为【bit】，EPC 默认从 32 开始
 *   - 写入/读取地址为【Word】，1 Word = 4 个 hex 字符 = 2 字节
 *   - 默认访问密码 00000000
 *   - readTag 返回【hex 字符串】（MainActivity 侧用 hexToAscii/hexToUtf8 解析）
 *   - writeTag 的 data 入参为【hex 字符串】，因此本适配层把业务层传入的明文(ASCII/UTF-8)转成 hex 再写
 */
public class IdataUhfAdapter implements RfidSdk {

    private static final String TAG = "IdataUhfAdapter";
    private static final String SDK_CLASS = "com.idata.UHFManager";
    private static final String LISTENER_CLASS = "android.os.IUHFResultListener";
    private static final String DEX_ASSET = "uhf_sdk.dex";
    // 与 assets/uhf_sdk.dex 实际大小一致；不一致则重新拷贝（用于覆盖升级）
    private static final long EXPECTED_DEX_SIZE = 7323736L;

    private Context mContext;                       // application context
    private DexClassLoader mLoader;
    private Class<?> mUhfClass;
    private Object mUhf;                            // com.idata.UHFManager 实例
    private Class<?> mListenerIface;
    private Object mListener;                       // IUHFResultListener 的 Proxy
    private InventoryCallback mInvCb;

    private final AtomicBoolean mConnected = new AtomicBoolean(false);
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private int mModuleType = 0;

    /** 必须在任意 SDK 调用前（如 MainActivity.onCreate 中）调用，提供 Context 以访问 assets 与创建 DexClassLoader */
    public void attach(Context ctx) {
        mContext = ctx.getApplicationContext();
    }

    // ------------------------------------------------------------------
    // 运行时加载 SDK dex
    // ------------------------------------------------------------------

    private synchronized void ensureSdk() throws Exception {
        if (mLoader != null && mUhfClass != null) return;
        if (mContext == null) throw new IllegalStateException("SDK 未 attach(Context)，无法加载 dex");

        File dexFile = new File(mContext.getCacheDir(), DEX_ASSET);
        if (!dexFile.exists() || dexFile.length() != EXPECTED_DEX_SIZE) {
            copyAsset(dexFile);
        }
        // Android 9+ 拒绝加载“可写”的 dex 文件，必须先设为只读（否则 SecurityException:
        // Writable dex file ... is not allowed）
        dexFile.setReadOnly();
        File optDir = mContext.getCodeCacheDir();   // DexClassLoader 的 odex 优化目录（API21+，minSdk24 满足）
        mLoader = new DexClassLoader(dexFile.getAbsolutePath(), optDir.getAbsolutePath(), null, mContext.getClassLoader());
        mUhfClass = mLoader.loadClass(SDK_CLASS);
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
        mListenerIface = mLoader.loadClass(LISTENER_CLASS);
        mListener = Proxy.newProxyInstance(mLoader, new Class<?>[]{mListenerIface},
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
                if (mUhf == null) {
                    try {
                        mUhf = mUhfClass.getConstructor().newInstance();
                    } catch (NoSuchMethodException e) {
                        // 个别 SDK 变体用 Context 构造
                        mUhf = mUhfClass.getConstructor(Context.class).newInstance(mContext);
                    }
                }
                Object r = mUhfClass.getMethod("powerOn").invoke(mUhf);
                ok = r instanceof Boolean && (Boolean) r;
                msg = ok ? "UHF 已连接" : "UHF 初始化失败（powerOn 返回 false，请确认设备 UHF 模块正常）";
                Log.i(TAG, "init: powerOn=" + ok + " moduleType=" + mModuleType);
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
                mUhfClass.getMethod("stopInventory").invoke(mUhf);
                mUhfClass.getMethod("powerOff").invoke(mUhf);
            }
        } catch (Throwable ignore) { /* best effort */ }
        mConnected.set(false);
    }

    @Override
    public void setPower(int dbm) {
        if (!mConnected.get() || mUhf == null) return;
        try {
            mUhfClass.getMethod("powerSet", int.class).invoke(mUhf, dbm);
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
            mUhfClass.getMethod("inventoryModeSet", int.class, boolean.class)
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
            mUhfClass.getMethod("registerUHFCallback", mListenerIface).invoke(mUhf, mListener);
            mUhfClass.getMethod("startInventoryTag").invoke(mUhf);
        } catch (Throwable e) {
            Log.e(TAG, "startInventory failed", e);
        }
    }

    @Override
    public void stopInventory() {
        if (mUhf == null) return;
        try {
            mUhfClass.getMethod("stopInventory").invoke(mUhf);
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
                Object r = mUhfClass.getMethod("readTag",
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
                Object r = mUhfClass.getMethod("writeTag",
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
