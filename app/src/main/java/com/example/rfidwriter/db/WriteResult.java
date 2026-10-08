package com.example.rfidwriter.db;

/**
 * 一条写标记录：导入的台账信息 + 写标执行结果。
 * 同时用于导出 JSON（供 PC 端 dws 同步脚本消费）。
 */
public class WriteResult {
    public String assetNo;     // 资产编码（唯一主键，为空时自动生成，固定写入 EPC 区）
    public String assetName;   // 资产名称（必填）
    public String dept;        // 使用部门（写入 USER 区）
    public String location;    // 存放地点（写入 USER 区）
    public String owner;       // 使用人员（写入 USER 区）
    public String model;       // 规格型号
    public String category;    // 资产分类
    public String purchaseDate;    // 购置日期
    public String purchasePrice;   // 购置价格
    public String remark;      // 备注
    public String tid;         // 标签 TID（出厂唯一，物理身份证）
    public String epc;         // 写标后的 EPC
    public String writtenData; // 实际写入 EPC/USER 区的内容
    public String status;      // "success" / "failed" / "pending"
    public String error;       // 失败原因
    public String writtenAt;   // 写标时间 ISO8601

    public WriteResult() { this.status = "pending"; }

    public WriteResult(String assetNo, String dept, String model, String remark) {
        this();
        this.assetNo = assetNo;
        this.dept = dept;
        this.model = model;
        this.remark = remark;
    }
}
