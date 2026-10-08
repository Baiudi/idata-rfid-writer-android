package com.example.rfidwriter.util;

import android.util.Log;

import com.example.rfidwriter.db.WriteResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 格式工具：ASCII<->Hex、CSV 解析、结果 JSON 序列化。
 */
public class FormatUtil {

    /** ASCII 字符串 -> 十六进制（大写，无空格） */
    public static String asciiToHex(String s) {
        if (s == null) return "";
        byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    /** 十六进制 -> ASCII 字符串（非 ASCII 字节忽略）。用于 EPC 区（资产编号，纯 ASCII）回读校验 */
    public static String hexToAscii(String hex) {
        if (hex == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < hex.length(); i += 2) {
            try {
                int v = Integer.parseInt(hex.substring(i, i + 2), 16);
                if (v >= 32 && v < 127) sb.append((char) v);
                else sb.append(' ');
            } catch (NumberFormatException e) {
                sb.append(' ');
            }
        }
        return sb.toString().trim();
    }

    /** 字符串 -> 十六进制（UTF-8 编码，支持中文）。用于 USER 区（存放位置/部门/使用人）写入 */
    public static String utf8ToHex(String s) {
        if (s == null) return "";
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    /** 十六进制 -> 字符串（UTF-8 解码，支持中文）。用于 USER 区回读校验 */
    public static String hexToUtf8(String hex) {
        byte[] bytes = hexToBytes(hex);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null) return new byte[0];
        int len = hex.length();
        if ((len & 1) != 0) len--; // 奇数长度丢弃最后半个字节
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            try {
                out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
            } catch (NumberFormatException e) {
                out[i / 2] = 0;
            }
        }
        return out;
    }

    /**
     * 解析台账 CSV。预期表头含：asset_no,dept,model,remark（顺序不强制，按表头列名匹配）。
     * 简单实现：支持双引号包裹字段、字段内逗号。
     */
    public static List<WriteResult> parseLedgerCsv(String content) {
        List<WriteResult> out = new ArrayList<>();
        if (content == null || content.isEmpty()) return out;
        String[] lines = content.split("\\r?\\n");
        if (lines.length < 2) return out;

        String[] header = splitCsvLine(lines[0]);
        if (header.length > 0) header[0] = stripBom(header[0]); // 去掉 Excel 导出的 UTF-8 BOM
        int iAsset = indexOf(header, "asset_no", "资产编号", "资产编码");
        int iDept = indexOf(header, "dept", "部门");
        int iLocation = indexOf(header, "location", "存放位置", "位置");
        int iOwner = indexOf(header, "owner", "使用人", "责任人");
        int iModel = indexOf(header, "model", "型号");
        int iRemark = indexOf(header, "remark", "备注");

        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) continue;
            String[] cols = splitCsvLine(lines[i]);
            WriteResult r = new WriteResult();
            r.assetNo = col(cols, iAsset);
            r.dept = col(cols, iDept);
            r.location = col(cols, iLocation);
            r.owner = col(cols, iOwner);
            r.model = col(cols, iModel);
            r.remark = col(cols, iRemark);
            if (r.assetNo != null && !r.assetNo.isEmpty()) out.add(r);
        }
        return out;
    }

    /** 把写标结果序列化为 JSON 数组（供 PC 端 sync_to_dws.py 消费） */
    public static String resultsToJson(List<WriteResult> list) {
        JSONArray arr = new JSONArray();
        for (WriteResult r : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("asset_no", nz(r.assetNo));
                o.put("dept", nz(r.dept));
                o.put("location", nz(r.location));
                o.put("owner", nz(r.owner));
                o.put("model", nz(r.model));
                o.put("remark", nz(r.remark));
                o.put("tid", nz(r.tid));
                o.put("epc", nz(r.epc));
                o.put("written_data", nz(r.writtenData));
                o.put("status", nz(r.status));
                o.put("error", nz(r.error));
                o.put("written_at", nz(r.writtenAt));
                arr.put(o);
            } catch (Exception e) {
                Log.w("FormatUtil", "serialize row fail: " + e.getMessage());
            }
        }
        try {
            return arr.toString(2);
        } catch (Exception e) {
            Log.w("FormatUtil", "json serialize fail: " + e.getMessage());
            return arr.toString();
        }
    }

    // ----------------- 内部辅助 -----------------

    private static String nz(String s) { return s == null ? "" : s; }

    private static String stripBom(String s) {
        if (s != null && s.startsWith("\uFEFF")) return s.substring(1);
        return s;
    }

    private static String col(String[] cols, int idx) {
        if (idx < 0 || idx >= cols.length) return "";
        return cols[idx].trim();
    }

    private static int indexOf(String[] header, String... names) {
        for (String name : names) {
            for (int i = 0; i < header.length; i++) {
                if (header[i].trim().equalsIgnoreCase(name)) return i;
            }
        }
        return -1;
    }

    /** 按 RFC4180 简易切分：双引号内逗号保留 */
    private static String[] splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"'); i++;
                    } else inQuotes = false;
                } else cur.append(c);
            } else {
                if (c == '"') inQuotes = true;
                else if (c == ',') { fields.add(cur.toString()); cur.setLength(0); }
                else cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields.toArray(new String[0]);
    }
}
