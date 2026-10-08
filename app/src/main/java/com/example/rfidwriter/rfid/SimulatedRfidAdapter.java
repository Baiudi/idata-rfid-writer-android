package com.example.rfidwriter.rfid;

import android.os.Handler;
import android.os.Looper;

import com.example.rfidwriter.util.FormatUtil;

import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模拟 RFID 适配器（无硬件版本）。
 *
 * 用途：在【没有 iData UHF SDK】或【没有实体标签】的环境下，仍可跑通
 * 连接 → 检测单张标签 → 写入资产编号 → 回读校验 → 落库 的完整流程，
 * 用于 UI 联调、流程验证、以及 CI 云构建出包。
 *
 * 行为：
 *   - init 直接成功（模拟已连接）
 *   - startInventory 在 ~300ms 后上报一张随机 EPC/TID 的“虚拟标签”，~600ms 后 onEnd
 *   - writeTagWithFilter 直接成功，并把写入内容记下来
 *   - readTag 对 TID 区返回虚拟 TID；对写过的 Bank/Word 返回该内容的 ASCII-Hex，保证回读校验通过
 *
 * ⚠️ 这是模拟，不会产生真实射频信号。接入真实设备时请改用 {@link IdataUhfAdapter}
 *    （放入厂商 jar 并补全 TODO-SDK），并在界面取消勾选「模拟模式」。
 */
public class SimulatedRfidAdapter implements RfidSdk {

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final AtomicBoolean mConnected = new AtomicBoolean(false);

    // 当前虚拟标签状态
    private final AtomicReference<String> mEpc = new AtomicReference<>("");
    private final AtomicReference<String> mTid = new AtomicReference<>("");

    // 最近一次写入的内容（按 “bank:startWord” 记录），供 readTag 回读校验
    private final AtomicReference<String> mLastWriteKey = new AtomicReference<>("");
    private final AtomicReference<String> mLastWriteVal = new AtomicReference<>("");

    private volatile boolean mInventorying = false;

    private static String randHex(int nibbles) {
        StringBuilder sb = new StringBuilder();
        Random r = new Random();
        for (int i = 0; i < nibbles; i++) sb.append("0123456789ABCDEF".charAt(r.nextInt(16)));
        return sb.toString();
    }

    @Override
    public void init(int moduleType, InitCallback cb) {
        mConnected.set(true);
        if (cb != null) mMain.post(() -> cb.onResult(true, "模拟模式已连接（无真实硬件）"));
    }

    @Override
    public void release() {
        mConnected.set(false);
        mInventorying = false;
    }

    @Override
    public void setPower(int dbm) { /* 模拟：忽略 */ }

    @Override
    public void setInventoryMode(int mode) { /* 模拟：忽略 */ }

    @Override
    public void startInventory(InventoryCallback cb) {
        if (!mConnected.get() || cb == null) return;
        mInventorying = true;
        // 每次盘点生成一张全新的虚拟标签（模拟“把一张空白标签靠近天线”）
        mEpc.set(randHex(8));
        mTid.set(randHex(12));

        final String epc = mEpc.get();
        final String tid = mTid.get();

        mMain.postDelayed(() -> {
            if (!mInventorying) return;
            TagInfo t = new TagInfo();
            t.epc = epc;
            t.tid = tid;
            t.rssi = "-45";
            cb.onTag(t);
        }, 300);

        mMain.postDelayed(() -> {
            if (!mInventorying) return;
            mInventorying = false;
            cb.onEnd();
        }, 600);
    }

    @Override
    public void stopInventory() {
        mInventorying = false;
    }

    @Override
    public void readTag(int bank, int wordAddr, int wordLen, String pwd, ReadCallback cb) {
        if (!mConnected.get()) {
            if (cb != null) mMain.post(() -> cb.onResult(false, null, "未连接"));
            return;
        }
        String data;
        if (bank == 2) {
            // TID 区：返回虚拟 TID（已是 Hex）
            data = mTid.get();
        } else if ((bank + ":" + wordAddr).equals(mLastWriteKey.get())) {
            // 回读校验：返回写入内容的 UTF-8 Hex（兼容 ASCII 资产编号与中文 USER 信息）
            data = FormatUtil.utf8ToHex(mLastWriteVal.get());
        } else {
            data = randHex(Math.max(4, wordLen * 4));
        }
        final String fData = data;
        mMain.postDelayed(() -> {
            if (cb != null) cb.onResult(true, fData, "模拟读取");
        }, 150);
    }

    @Override
    public void writeTagWithFilter(int fBank, int fStartBit, int fLenBits, String fData,
                                  int writeBank, int startWord, String writeData,
                                  String pwd, WriteCallback cb) {
        if (!mConnected.get()) {
            if (cb != null) mMain.post(() -> cb.onResult(false, "未连接"));
            return;
        }
        // 记录写入，供后续 readTag 回读校验通过
        mLastWriteKey.set(writeBank + ":" + startWord);
        mLastWriteVal.set(writeData);

        mMain.postDelayed(() -> {
            if (cb != null) cb.onResult(true, "模拟写入成功");
        }, 200);
    }

    @Override
    public boolean isConnected() {
        return mConnected.get();
    }
}
