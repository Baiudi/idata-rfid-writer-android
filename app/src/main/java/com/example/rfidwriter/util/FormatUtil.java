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
     * 解析台账表格（CSV 行 / xlsx 工作表通用）。
     * 表头支持：资产编码/asset_no、资产名称/asset_name、规格型号/model、资产分类/category、
     * 购置日期/purchase_date、购置价格/purchase_price、使用部门/dept、使用人员/owner、
     * 存放地点/存放位置/location、备注/remark（顺序不强制，按表头列名匹配）。
     * 编码列为空时由调用方自动生成；名称与编码均空时跳过该行。
     */
    public static List<WriteResult> parseTable(List<List<String>> rows) {
        List<WriteResult> out = new ArrayList<>();
        if (rows == null || rows.size() < 2) return out;

        List<String> headRow = rows.get(0);
        String[] header = new String[headRow.size()];
        for (int i = 0; i < headRow.size(); i++) header[i] = stripBom(nz(headRow.get(i)).trim());
        int iAsset = indexOf(header, "asset_no", "资产编号", "资产编码");
        int iName = indexOf(header, "asset_name", "资产名称", "名称");
        int iDept = indexOf(header, "dept", "使用部门", "部门");
        int iLocation = indexOf(header, "location", "存放位置", "存放地点", "位置");
        int iOwner = indexOf(header, "owner", "使用人员", "使用人", "责任人");
        int iModel = indexOf(header, "model", "规格型号", "型号");
        int iCategory = indexOf(header, "category", "资产分类", "分类");
        int iDate = indexOf(header, "purchase_date", "购置日期");
        int iPrice = indexOf(header, "purchase_price", "购置价格");
        int iRemark = indexOf(header, "remark", "备注");

        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row == null || row.isEmpty() || isRowEmpty(row)) continue;
            String[] cols = row.toArray(new String[0]);
            WriteResult r = new WriteResult();
            r.assetNo = col(cols, iAsset);
            r.assetName = col(cols, iName);
            r.dept = col(cols, iDept);
            r.location = col(cols, iLocation);
            r.owner = col(cols, iOwner);
            r.model = col(cols, iModel);
            r.category = col(cols, iCategory);
            r.purchaseDate = col(cols, iDate);
            r.purchasePrice = col(cols, iPrice);
            r.remark = col(cols, iRemark);
            boolean noCode = r.assetNo == null || r.assetNo.isEmpty();
            boolean noName = r.assetName == null || r.assetName.isEmpty();
            if (noCode && noName) continue; // 名称与编码都空：无效行
            out.add(r);
        }
        return out;
    }

    /** 解析台账 CSV（兼容旧格式，内部转通用表格解析） */
    public static List<WriteResult> parseLedgerCsv(String content) {
        List<List<String>> rows = new ArrayList<>();
        if (content == null || content.isEmpty()) return new ArrayList<>();
        String[] lines = content.split("\\r?\\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            rows.add(java.util.Arrays.asList(splitCsvLine(line)));
        }
        return parseTable(rows);
    }

    // ----------------- 极简 xlsx 读取 -----------------

    /**
     * 读取 .xlsx（Office Open XML）为二维表格。
     * 无第三方依赖：直接解压 zip，解析 sharedStrings 与 sheet1。
     * 支持共享字符串(t="s")、内联字符串(t="inlineStr")、普通值。
     */
    public static List<WriteResult> parseLedgerXlsx(byte[] data) throws Exception {
        if (data == null || data.length == 0) throw new IllegalArgumentException("文件为空");
        java.util.Map<String, byte[]> parts = unzipToMap(data);
        List<String> shared = parseSharedStrings(parts.get("xl/sharedStrings.xml"));
        byte[] sheet = parts.get("xl/worksheets/sheet1.xml");
        if (sheet == null) {
            for (java.util.Map.Entry<String, byte[]> e : parts.entrySet()) {
                if (e.getKey().startsWith("xl/worksheets/") && e.getKey().endsWith(".xml")) {
                    sheet = e.getValue(); break;
                }
            }
        }
        if (sheet == null) throw new IllegalStateException("xlsx 中未找到工作表");
        return parseTable(parseSheetXml(sheet, shared));
    }

    private static java.util.Map<String, byte[]> unzipToMap(byte[] data) throws Exception {
        java.util.Map<String, byte[]> parts = new java.util.HashMap<>();
        java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(data));
        java.util.zip.ZipEntry e;
        byte[] buf = new byte[8192];
        while ((e = zin.getNextEntry()) != null) {
            if (e.isDirectory()) continue;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = zin.read(buf)) > 0) bos.write(buf, 0, n);
            parts.put(e.getName(), bos.toByteArray());
        }
        zin.close();
        return parts;
    }

    /** 解析 sharedStrings.xml：按顺序取每个 <si> 内全部 <t> 文本拼接 */
    private static List<String> parseSharedStrings(byte[] xml) throws Exception {
        List<String> out = new ArrayList<>();
        if (xml == null) return out;
        org.xmlpull.v1.XmlPullParser p = android.util.Xml.newPullParser();
        p.setInput(new java.io.ByteArrayInputStream(xml), StandardCharsets.UTF_8.name());
        StringBuilder cur = null;
        boolean inT = false;
        int ev = p.getEventType();
        while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) {
                String n = p.getName();
                if ("si".equals(n)) cur = new StringBuilder();
                else if ("t".equals(n) && cur != null) inT = true;
            } else if (ev == org.xmlpull.v1.XmlPullParser.TEXT) {
                if (inT && cur != null) cur.append(p.getText());
            } else if (ev == org.xmlpull.v1.XmlPullParser.END_TAG) {
                String n = p.getName();
                if ("t".equals(n)) inT = false;
                else if ("si".equals(n) && cur != null) { out.add(cur.toString()); cur = null; }
            }
            ev = p.next();
        }
        return out;
    }

    /** 解析 sheet XML 为二维表格（按单元格 r="C5" 定位列号） */
    private static List<List<String>> parseSheetXml(byte[] xml, List<String> shared) throws Exception {
        org.xmlpull.v1.XmlPullParser p = android.util.Xml.newPullParser();
        p.setInput(new java.io.ByteArrayInputStream(xml), StandardCharsets.UTF_8.name());
        List<List<String>> rows = new ArrayList<>();
        List<String> cur = null;
        String cellType = null;
        int cellCol = -1;
        StringBuilder text = null;      // 正在收集的 v/t 文本
        StringBuilder cellRaw = new StringBuilder(); // 单元格最终原始文本
        int ev = p.getEventType();
        while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) {
                String n = p.getName();
                if ("row".equals(n)) cur = new ArrayList<>();
                else if ("c".equals(n)) {
                    cellType = attrOf(p, "t");
                    cellCol = colLetterToIndex(attrOf(p, "r"));
                    cellRaw.setLength(0);
                    text = null;
                } else if ("v".equals(n) || "t".equals(n)) {
                    text = new StringBuilder();
                }
            } else if (ev == org.xmlpull.v1.XmlPullParser.TEXT) {
                if (text != null) text.append(p.getText());
            } else if (ev == org.xmlpull.v1.XmlPullParser.END_TAG) {
                String n = p.getName();
                if ("v".equals(n) || "t".equals(n)) {
                    if (text != null) cellRaw.append(text);
                    text = null;
                } else if ("c".equals(n)) {
                    String val = cellRaw.toString();
                    if ("s".equals(cellType)) {
                        try {
                            int i = Integer.parseInt(val.trim());
                            val = (i >= 0 && i < shared.size()) ? shared.get(i) : "";
                        } catch (NumberFormatException ignored) {}
                    }
                    if (cur != null) {
                        if (cellCol >= 0) {
                            while (cur.size() < cellCol) cur.add("");
                            if (cellCol < cur.size()) cur.set(cellCol, val); else cur.add(val);
                        } else cur.add(val);
                    }
                    cellType = null;
                } else if ("row".equals(n)) {
                    if (cur != null) rows.add(cur);
                    cur = null;
                }
            }
            ev = p.next();
        }
        return rows;
    }

    private static String attrOf(org.xmlpull.v1.XmlPullParser p, String name) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            if (name.equals(p.getAttributeName(i))) return p.getAttributeValue(i);
        }
        return null;
    }

    /** Excel 列字母 -> 0 起始索引，如 "C5" -> 2；无字母返回 -1 */
    private static int colLetterToIndex(String ref) {
        if (ref == null || ref.isEmpty()) return -1;
        int idx = 0; boolean any = false;
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch >= 'A' && ch <= 'Z') { idx = idx * 26 + (ch - 'A' + 1); any = true; }
            else if (ch >= 'a' && ch <= 'z') { idx = idx * 26 + (ch - 'a' + 1); any = true; }
            else break;
        }
        return any ? idx - 1 : -1;
    }

    // ----------------- 极简 xlsx 模板生成 -----------------

    /** 生成批量导入 Excel 模板（真正的 .xlsx，inline string，无第三方依赖） */
    public static byte[] buildTemplateXlsx() throws Exception {
        String[] headers = {"资产编码(可留空自动生成)", "资产名称(必填)", "规格型号", "资产分类",
                "购置日期", "购置价格", "使用部门", "使用人员", "存放地点", "备注"};
        String[][] samples = {
                {"", "202600101", "Dell OptiPlex 7080", "台式机", "2026-03-01", "4500", "IT部", "王凯明", "A栋3楼-01", "示例行，导入前可删除"},
                {"", "202600102", "HP LaserJet M454", "打印机", "2025-11-15", "3200", "财务部", "李会计", "B栋1楼-03", ""},
        };
        StringBuilder sheetData = new StringBuilder();
        sheetData.append("<row r=\"1\">");
        for (int i = 0; i < headers.length; i++) {
            sheetData.append("<c r=\"").append(colIndexToName(i)).append("1\" t=\"inlineStr\"><is><t>")
                    .append(xmlEscape(headers[i])).append("</t></is></c>");
        }
        sheetData.append("</row>");
        for (int r = 0; r < samples.length; r++) {
            sheetData.append("<row r=\"").append(r + 2).append("\">");
            for (int c = 0; c < samples[r].length; c++) {
                String v = samples[r][c];
                if (v == null || v.isEmpty()) continue;
                sheetData.append("<c r=\"").append(colIndexToName(c)).append(r + 2)
                        .append("\" t=\"inlineStr\"><is><t>").append(xmlEscape(v)).append("</t></is></c>");
            }
            sheetData.append("</row>");
        }
        String sheet = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
                + sheetData + "</sheetData></worksheet>";
        String workbook = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"资产台账\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>";
        String wbRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\""
                + " Target=\"worksheets/sheet1.xml\"/></Relationships>";
        String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
                + " Target=\"xl/workbook.xml\"/></Relationships>";
        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "</Types>";

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos);
        putZipEntry(zos, "[Content_Types].xml", contentTypes);
        putZipEntry(zos, "_rels/.rels", rels);
        putZipEntry(zos, "xl/workbook.xml", workbook);
        putZipEntry(zos, "xl/_rels/workbook.xml.rels", wbRels);
        putZipEntry(zos, "xl/worksheets/sheet1.xml", sheet);
        zos.close();
        return bos.toByteArray();
    }

    private static void putZipEntry(java.util.zip.ZipOutputStream zos, String name, String content) throws Exception {
        zos.putNextEntry(new java.util.zip.ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static String colIndexToName(int idx) {
        StringBuilder sb = new StringBuilder();
        int i = idx + 1;
        while (i > 0) { i--; sb.insert(0, (char) ('A' + i % 26)); i /= 26; }
        return sb.toString();
    }

    private static String xmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static boolean isRowEmpty(List<String> row) {
        for (String s : row) if (s != null && !s.trim().isEmpty()) return false;
        return true;
    }

    /** 把写标结果序列化为 JSON 数组（供 PC 端 sync_to_dws.py 消费） */
    public static String resultsToJson(List<WriteResult> list) {
        JSONArray arr = new JSONArray();
        for (WriteResult r : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("asset_no", nz(r.assetNo));
                o.put("asset_name", nz(r.assetName));
                o.put("dept", nz(r.dept));
                o.put("location", nz(r.location));
                o.put("owner", nz(r.owner));
                o.put("model", nz(r.model));
                o.put("category", nz(r.category));
                o.put("purchase_date", nz(r.purchaseDate));
                o.put("purchase_price", nz(r.purchasePrice));
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

    // ----------------- 距离估算（UHF 定位） -----------------

    /**
     * 由 RSSI(dBm) 与发射功率(dBm) 估算标签到读卡器的距离（米）。
     *
     * 采用自由空间路径损耗(FSPL)近似：
     *   d(m) = 10^((Pt - RSSI - CAL)/20)
     * 其中 CAL 为标定常数，吸收天线增益、线缆/系统损耗等因素。
     *
     * ⚠️ 这是粗估，受环境多径、人体遮挡、标签朝向影响很大；真机需现场标定 CAL。
     *    标定方法：站在 1m、3m、5m 处读 RSSI，反解 CAL = Pt - RSSI - 20*log10(d)。
     *
     * @param rssiDbm    接收信号强度，范围约 -90..-30 dBm
     * @param txPowerDbm 读卡器发射功率（定位时通常用高功率，如 30 dBm）
     * @return 估算距离（米），无信号时返回 Float.NaN
     */
    public static float estimateDistanceMeters(float rssiDbm, float txPowerDbm) {
        if (rssiDbm <= -120f) return Float.NaN;
        final float CAL = 84f; // 标定常数（默认按模拟/典型手持调校；真机请现场标定）
        float exponent = (txPowerDbm - rssiDbm - CAL) / 20f;
        float d = (float) Math.pow(10f, exponent);
        if (d < 0.05f) d = 0.05f;
        if (d > 20f) d = 20f;
        return d;
    }

    /** 把距离格式化为易读字符串（≥1000m 用 km，否则用 m，保留 1 位小数） */
    public static String formatDistance(float meters) {
        if (Float.isNaN(meters)) return "--";
        if (meters >= 1000f) return String.format(java.util.Locale.ROOT, "%.2f km", meters / 1000f);
        return String.format(java.util.Locale.ROOT, "%.2f 米", meters);
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
