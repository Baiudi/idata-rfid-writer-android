package com.example.rfidwriter.rfid;

/**
 * UHF RFID 操作抽象接口。
 *
 * 业务代码（MainActivity / 批量写标逻辑）只依赖本接口，不直接碰 iData SDK，
 * 因此更换/升级 SDK 时只需改 {@link IdataUhfAdapter}，不动业务层。
 *
 * 存储区 Bank 约定（与 iData UHF SDK / 插件一致）：
 *   0 = RESERVED（访问/销毁密码）
 *   1 = EPC
 *   2 = TID（只读，出厂唯一）
 *   3 = USER
 *
 * 地址说明：
 *   - 过滤(fBank/fStartBlock/fLen) 中 fStartBlock 为【bit】类型，4 个 bit 对应 1 个字符；
 *     EPC 通常从第 32 bit（即 word 2）开始。
 *   - 写入(startBlock) 与读取(wordAddr) 为【Word】类型，1 Word = 4 个字符(16bit)。
 *   - 默认访问密码：00000000（标签改过密码需改代码）。
 */
public interface RfidSdk {

    interface InitCallback {
        void onResult(boolean ok, String msg);
    }

    /** 盘点回调：每张标签触发一次 onTag，停止时触发 onEnd */
    interface InventoryCallback {
        void onTag(TagInfo tag);
        void onEnd();
    }

    interface WriteCallback {
        void onResult(boolean ok, String msg);
    }

    interface ReadCallback {
        void onResult(boolean ok, String dataHex, String msg);
    }

    void init(int moduleType, InitCallback cb);
    void release();
    void setPower(int dbm);
    void setInventoryMode(int mode);
    void startInventory(InventoryCallback cb);
    void stopInventory();

    /** 按【Word】地址读取某 Bank 的数据，返回十六进制字符串 */
    void readTag(int bank, int wordAddr, int wordLen, String pwd, ReadCallback cb);

    /**
     * 带过滤条件的单张标签写入（iData 官方推荐单张写标方式）。
     * 仅对单张标签生效 —— 建议先把功率调低，确保天线范围内只有一张目标标签。
     *
     * @param fBank     过滤区 0/1/2/3
     * @param fStartBit 过滤起始地址（bit）
     * @param fLenBits  过滤长度（bit）
     * @param fData     过滤数据（字符串）
     * @param writeBank 写入区 0/1/2/3
     * @param startWord 写入起始地址（Word）
     * @param writeData 写入数据（字符串，按 SDK 要求 ASCII 或 Hex）
     * @param pwd       访问密码
     */
    void writeTagWithFilter(int fBank, int fStartBit, int fLenBits, String fData,
                           int writeBank, int startWord, String writeData,
                           String pwd, WriteCallback cb);

    boolean isConnected();
}
