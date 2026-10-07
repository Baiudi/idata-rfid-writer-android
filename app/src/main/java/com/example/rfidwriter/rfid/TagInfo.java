package com.example.rfidwriter.rfid;

/**
 * 一张被读到的标签的简要信息。
 * TID 为出厂唯一、只读，作为物理身份证；EPC 默认可写。
 */
public class TagInfo {
    public String epc = "";
    public String tid = "";   // 部分模组/盘点模式需要单独 read 才能取到
    public String rssi = "";
}
