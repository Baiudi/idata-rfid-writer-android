package com.example.rfidwriter.rfid;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * iData T1 UHF 原生 SDK 适配层。
 *
 * 本类是唯一直接调用 iData UHF SDK（jar + so）的地方。其余业务代码只依赖 {@link RfidSdk} 接口。
 *
 * ⚠️ TODO-SDK（上线前必须处理）：
 *   下面方法体里的【类名 / 方法名 / 回调形态】是基于 iData UHF SDK 通用形态写的参考实现，
 *   请按你拿到的 SDK jar 中的【实际】类名/方法签名替换。
 *   已知且已核对无误的部分（不要改）：
 *     - Bank 编码 0=RESERVED/1=EPC/2=TID/3=USER
 *     - 过滤地址 fStartBlock 为【bit】，EPC 默认从 32 开始
 *     - 写入/读取起始地址为【Word】
 *     - 默认访问密码 00000000
 *     - writeTagWithFilter 的形参顺序：(fBank, fStartBit, fLenBits, fData, writeBank, startWord, writeData, pwd, callback)
 *
 * 若你的 SDK 是旧版「GetRFIDThread + MyApp.getIdataLib()」形态（iData 95W 等老机型），
 * 请改用 legacy 形态实现同样的 {@link RfidSdk} 接口即可，业务层无需改动。
 */
public class IdataUhfAdapter implements RfidSdk {

    // TODO-SDK: 替换为 SDK jar 中的实际类名。常见形态之一为 com.idata.uhf.UhfManager。
    // private UhfManager mUhf;

    private final AtomicBoolean mConnected = new AtomicBoolean(false);
    private final Handler mMain = new Handler(Looper.getMainLooper());

    // 模块类型：0=UM 1=SLR 2=GX（init 时若失败可依次尝试）
    private int mModuleType = 0;

    @Override
    public void init(int moduleType, InitCallback cb) {
        this.mModuleType = moduleType;
        // TODO-SDK: 替换为实际初始化代码，例如：
        //   mUhf = UhfManager.getInstance(context);
        //   boolean ok = mUhf.init(moduleType);   // 或 mUhf.powerOn();
        // 下面为占位，请按真实返回值判断。
        boolean ok = false; // placeholder
        mConnected.set(ok);
        if (cb != null) {
            final boolean f = ok;
            mMain.post(() -> cb.onResult(f, f ? "UHF 已连接" : "UHF 初始化失败，请检查 SDK 类名/模块类型"));
        }
    }

    @Override
    public void release() {
        // TODO-SDK: mUhf.stopInventory(); mUhf.release(); 或 mUhf.powerOff();
        mConnected.set(false);
    }

    @Override
    public void setPower(int dbm) {
        if (!mConnected.get()) return;
        // TODO-SDK: mUhf.setPower(dbm);   // 合法范围 5~33 dBm
    }

    @Override
    public void setInventoryMode(int mode) {
        if (!mConnected.get()) return;
        // TODO-SDK: mUhf.setInventoryMode(mode);
        // 0=UM 多标签(近距读全) 1=快盘(远距快读) 2=低功耗(SLR) 3=新快速(E) 4=普通
    }

    @Override
    public void startInventory(InventoryCallback cb) {
        if (!mConnected.get() || cb == null) return;
        // TODO-SDK: 注册盘点监听并把每帧结果转成 TagInfo 回调。
        // 典型形态：
        //   mUhf.setOnInventoryListener(tag -> {
        //       TagInfo t = new TagInfo();
        //       t.epc = tag.getEpc();          // 盘点默认返回 EPC
        //       t.tid = tag.getTid();          // 部分机型需在回调里额外 read TID
        //       t.rssi = tag.getRssi();
        //       mMain.post(() -> cb.onTag(t));
        //   });
        //   mUhf.startInventory();
    }

    @Override
    public void stopInventory() {
        // TODO-SDK: mUhf.stopInventory();
    }

    @Override
    public void readTag(int bank, int wordAddr, int wordLen, String pwd, ReadCallback cb) {
        if (!mConnected.get()) {
            if (cb != null) mMain.post(() -> cb.onResult(false, null, "未连接"));
            return;
        }
        // TODO-SDK: 典型形态 mUhf.readTag(bank, wordAddr, wordLen, pwd, (ok, hex) -> {...});
        // 返回值为该 Bank/地址区间的十六进制字符串。
        if (cb != null) mMain.post(() -> cb.onResult(false, null, "readTag 待接入 SDK"));
    }

    @Override
    public void writeTagWithFilter(int fBank, int fStartBit, int fLenBits, String fData,
                                  int writeBank, int startWord, String writeData,
                                  String pwd, WriteCallback cb) {
        if (!mConnected.get()) {
            if (cb != null) mMain.post(() -> cb.onResult(false, "未连接"));
            return;
        }
        // TODO-SDK: 形参顺序已与官方一致：
        //   mUhf.writeTagWithFilter(fBank, fStartBit, fLenBits, fData,
        //                           writeBank, startWord, writeData, pwd,
        //       (ret) -> {
        //           boolean ok = ret != null && "success".equals(ret.optString("code"));
        //           mMain.post(() -> cb.onResult(ok, ok ? "写入成功" : "写入失败:" + ret));
        //       });
        if (cb != null) mMain.post(() -> cb.onResult(false, "writeTagWithFilter 待接入 SDK"));
    }

    @Override
    public boolean isConnected() {
        return mConnected.get();
    }

    // ------------------------------------------------------------------
    // 业务层可直接复用的「同步」封装：把回调式 SDK 包成阻塞调用，方便批量写标串行化。
    // 注意：需在子线程调用，避免阻塞 UI。
    // ------------------------------------------------------------------

    /** 阻塞式读取，超时返回 null */
    public String readTagSync(int bank, int wordAddr, int wordLen, String pwd, long timeoutMs) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> out = new AtomicReference<>();
        AtomicReference<Boolean> okRef = new AtomicReference<>(false);
        readTag(bank, wordAddr, wordLen, pwd, (ok, data, msg) -> {
            okRef.set(ok);
            out.set(data);
            latch.countDown();
        });
        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        return okRef.get() ? out.get() : null;
    }

    /** 阻塞式带过滤写入，返回是否成功 */
    public boolean writeTagWithFilterSync(int fBank, int fStartBit, int fLenBits, String fData,
                                         int writeBank, int startWord, String writeData,
                                         String pwd, long timeoutMs) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Boolean> okRef = new AtomicReference<>(false);
        writeTagWithFilter(fBank, fStartBit, fLenBits, fData, writeBank, startWord, writeData, pwd,
                (ok, msg) -> {
                    okRef.set(ok);
                    latch.countDown();
                });
        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        return okRef.get();
    }
}
