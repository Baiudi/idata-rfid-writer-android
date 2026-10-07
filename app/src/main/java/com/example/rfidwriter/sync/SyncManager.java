package com.example.rfidwriter.sync;

import android.content.Context;
import android.util.Log;

import com.example.rfidwriter.db.WriteResult;
import com.example.rfidwriter.util.FormatUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Scanner;

/**
 * 写后同步管理。
 *
 * 设计边界：
 *   - Android 端无法直接运行 dws（dws 是桌面 CLI）。因此本类负责把写标结果
 *     ① 导出为 JSON 落到设备存储（默认 app 外部目录，可被 adb pull 或手动拷贝到 PC）；
 *     ② 可选：POST 到你们自己的后端接口（若后端再调用 dws 写钉钉台账）。
 *   - 真正把结果写回钉钉在线电子表格资产台账，由 PC 端脚本 {@code sync_to_dws.py} 用 dws 完成。
 *
 * 想“写后自动同步到资产台账”，最简闭环：本 App 导出 JSON → 在 PC 上跑
 *   python sync_to_dws.py --results <json> --node <钉钉表格nodeId>
 */
public class SyncManager {

    private static final String TAG = "SyncManager";
    public static final String RESULT_FILE_NAME = "rfid_write_results.json";

    /** 导出结果 JSON 到 app 外部存储目录（无需运行时存储权限，adb pull 或文件管理器可取到） */
    public static File exportResultsJson(Context ctx, List<WriteResult> list) throws Exception {
        File dir = ctx.getExternalFilesDir(null);
        File out = new File(dir, RESULT_FILE_NAME);
        String json = FormatUtil.resultsToJson(list);
        try (FileOutputStream fos = new FileOutputStream(out);
             OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            w.write(json);
        }
        return out;
    }

    /**
     * 可选：把结果 JSON 上报到自有的 HTTP 接口（该接口可再调用 dws 写钉钉台账）。
     * endpoint 为空则返回 false（表示未配置，跳过）。采用原生 HttpURLConnection，无额外依赖。
     */
    public static boolean postResultsToEndpoint(File jsonFile, String endpoint) {
        if (endpoint == null || endpoint.isEmpty()) return false;
        try {
            StringBuilder sb = new StringBuilder();
            try (java.io.FileInputStream fis = new java.io.FileInputStream(jsonFile);
                 Scanner sc = new Scanner(fis, "UTF-8")) {
                while (sc.hasNextLine()) sb.append(sc.nextLine());
            }
            String body = sb.toString();
            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            boolean ok = code >= 200 && code < 300;
            Log.i(TAG, "post endpoint=" + endpoint + " code=" + code + " ok=" + ok);
            conn.disconnect();
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "post fail: " + e.getMessage());
            return false;
        }
    }
}
