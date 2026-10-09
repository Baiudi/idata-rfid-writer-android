package com.example.rfidwriter.rfid;

import android.os.Handler;
import android.os.Looper;

import com.example.rfidwriter.util.FormatUtil;

import java.util.ArrayList;
import java.util.List;
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
    private volatile int mInvMode = 0;          // 0=单标签模式（写标用） 1=多标签盘点池
    private RfidSdk.InventoryCallback mInvCb;   // 当前盘点回调（流式上报直到 stop）
    private final List<String> mPoolEpcs = new ArrayList<>();   // 盘点池：虚拟标签群
    private final List<String> mPoolTids = new ArrayList<>();
    private final float[] mLastRssi = {-45f};   // 定位演示：RSSI 随机游走

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
    public void setInventoryMode(int mode) { mInvMode = mode; }

    /** 流式盘点：每 ~250ms 上报一批标签，直到 stopInventory */
    private final Runnable mStreamTask = new Runnable() {
        @Override public void run() {
            if (!mInventorying || mInvCb == null) return;
            if (mInvMode == 0) {
                // 单标签模式：同一条虚拟标签反复上报（保证「检测唯一标签」与定位流程成立）
                mLastRssi[0] = walk(mLastRssi[0]);
                TagInfo t = new TagInfo();
                t.epc = mEpc.get();
                t.tid = mTid.get();
                t.rssi = String.valueOf(Math.round(mLastRssi[0]));
                mInvCb.onTag(t);
            } else {
                // 多标签盘点池：随机上报 1~2 张
                int n = 1 + new Random().nextInt(2);
                for (int i = 0; i < n && !mPoolEpcs.isEmpty(); i++) {
                    int idx = new Random().nextInt(mPoolEpcs.size());
                    TagInfo t = new TagInfo();
                    t.epc = mPoolEpcs.get(idx);
                    t.tid = mPoolTids.get(idx);
                    t.rssi = String.valueOf(-35 - new Random().nextInt(36)); // -35..-70
                    mInvCb.onTag(t);
                }
            }
            mMain.postDelayed(this, 250);
        }
    };

    private static float walk(float last) {
        float next = last + (new Random().nextInt(17) - 8f); // ±8 dBm 随机游走
        return Math.max(-70f, Math.min(-30f, next));
    }

    @Override
    public void startInventory(InventoryCallback cb) {
        if (!mConnected.get() || cb == null) return;
        mInventorying = true;
        mInvCb = cb;
        // 每次盘点生成一张全新的虚拟标签（模拟“把一张空白标签靠近天线”）
        mEpc.set(randHex(8));
        mTid.set(randHex(12));
        mLastRssi[0] = -45f;

        if (mInvMode == 1 && mPoolEpcs.isEmpty()) {
            // 懒初始化 8 张池内虚拟标签（模拟货架上的一批标签）
            for (int i = 0; i < 8; i++) {
                mPoolEpcs.add(randHex(8));
                mPoolTids.add(randHex(12));
            }
        }
        mMain.post(mStreamTask);
        // 单标签模式：600ms 后补发 onEnd（写标检测不用等满检测窗口，流仍继续直到 stop）
        if (mInvMode == 0) {
            mMain.postDelayed(() -> { if (mInventorying && cb != null) cb.onEnd(); }, 600);
        }
    }

    @Override
    public void stopInventory() {
        mInventorying = false;
        mMain.removeCallbacks(mStreamTask);
        final RfidSdk.InventoryCallback cb = mInvCb;
        mInvCb = null;
        if (cb != null) mMain.post(cb::onEnd);
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
