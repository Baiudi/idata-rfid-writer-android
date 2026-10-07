package com.example.rfidwriter;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.rfidwriter.db.ResultDbHelper;
import com.example.rfidwriter.db.WriteResult;
import com.example.rfidwriter.rfid.IdataUhfAdapter;
import com.example.rfidwriter.rfid.RfidSdk;
import com.example.rfidwriter.rfid.SimulatedRfidAdapter;
import com.example.rfidwriter.rfid.TagInfo;
import com.example.rfidwriter.sync.SyncManager;
import com.example.rfidwriter.util.FormatUtil;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQ_PICK_CSV = 1001;
    private static final String PWD = "00000000";          // 默认访问密码
    private static final long INVENTORY_WINDOW_MS = 1500;  // 单标签检测窗口
    private static final long WRITE_TIMEOUT_MS = 5000;
    private static final long READ_TIMEOUT_MS = 3000;

    private RfidSdk sdk = new IdataUhfAdapter();
    private ResultDbHelper db;
    private final List<WriteResult> ledger = new ArrayList<>();
    private ResultAdapter adapter;
    private android.widget.CheckBox chkSimulate;

    private EditText etPower, etModule, etBank, etStartWord;
    private TextView tvStatus, tvProgress;
    private Button btnStartWrite, btnSync;

    private volatile boolean writing = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        db = new ResultDbHelper(this);

        etPower = findViewById(R.id.etPower);
        etModule = findViewById(R.id.etModule);
        etBank = findViewById(R.id.etBank);
        etStartWord = findViewById(R.id.etStartWord);
        tvStatus = findViewById(R.id.tvStatus);
        tvProgress = findViewById(R.id.tvProgress);
        btnStartWrite = findViewById(R.id.btnStartWrite);
        btnSync = findViewById(R.id.btnSync);
        chkSimulate = findViewById(R.id.chkSimulate);

        RecyclerView rv = findViewById(R.id.rvResults);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ResultAdapter(ledger);
        rv.setAdapter(adapter);

        // 根据「模拟模式」开关选择适配器（默认勾选 -> 模拟适配器，无硬件即可跑全流程）
        applyAdapter(chkSimulate.isChecked());
        chkSimulate.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (writing) {
                Toast.makeText(this, "写标进行中，暂不能切换模式", Toast.LENGTH_SHORT).show();
                buttonView.setChecked(!isChecked);
                return;
            }
            sdk.release();
            applyAdapter(isChecked);
            setStatus(isChecked ? "已切换为模拟模式" : "已切换为真实 iData 模式（需已放入 SDK 并补全 TODO-SDK）");
        });

        findViewById(R.id.btnConnect).setOnClickListener(v -> connect());
        findViewById(R.id.btnDisconnect).setOnClickListener(v -> {
            sdk.release();
            setStatus("已断开");
        });
        findViewById(R.id.btnImport).setOnClickListener(v -> pickCsv());
        btnStartWrite.setOnClickListener(v -> startBatchWrite());
        findViewById(R.id.btnExport).setOnClickListener(v -> exportJson());
        btnSync.setOnClickListener(v -> syncToDws());

        // 恢复上次未完成的记录
        ledger.addAll(db.getAll());
        adapter.notifyDataSetChanged();
        refreshProgress();
    }

    // ----------------- 连接 / 参数 -----------------

    /** 按「模拟模式」开关切换底层适配器 */
    private void applyAdapter(boolean simulate) {
        sdk = simulate ? new SimulatedRfidAdapter() : new IdataUhfAdapter();
        setStatus(simulate ? "模拟模式就绪（未连接）" : "真实 iData 模式就绪（未连接）");
    }

    private void connect() {
        int module = parseInt(etModule, 0);
        sdk.init(module, (ok, msg) -> runOnUiThread(() -> {
            setStatus(msg);
            if (ok) {
                sdk.setPower(parseInt(etPower, 15));
                sdk.setInventoryMode(0);
            }
        }));
    }

    // ----------------- 导入台账 CSV -----------------

    private void pickCsv() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/*");
        startActivityForResult(i, REQ_PICK_CSV);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_CSV && res == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    getContentResolver().openInputStream(uri), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append("\n");
                List<WriteResult> parsed = FormatUtil.parseLedgerCsv(sb.toString());
                ledger.clear();
                ledger.addAll(parsed);
                for (WriteResult w : ledger) db.upsert(w);
                adapter.notifyDataSetChanged();
                refreshProgress();
                Toast.makeText(this, "导入 " + parsed.size() + " 条台账", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "读取 CSV 失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    // ----------------- 批量写标 -----------------

    private void startBatchWrite() {
        if (writing) return;
        if (!sdk.isConnected()) { Toast.makeText(this, "请先连接 UHF", Toast.LENGTH_SHORT).show(); return; }
        if (ledger.isEmpty()) { Toast.makeText(this, "请先导入台账", Toast.LENGTH_SHORT).show(); return; }

        int power = parseInt(etPower, 15);
        int bank = parseInt(etBank, 1);
        int startWord = parseInt(etStartWord, 2);
        sdk.setPower(power); // 写单张前调低功率，避免误写邻近标签

        writing = true;
        btnStartWrite.setEnabled(false);
        new Thread(() -> runBatchWrite(bank, startWord)).start();
    }

    private void runBatchWrite(int bank, int startWord) {
        int total = ledger.size();
        int idx = 0;
        for (WriteResult item : ledger) {
            if (!writing) break;
            idx++;
            final int cur = idx, tot = total;
            runOnUiThread(() -> tvProgress.setText("进度：" + cur + " / " + tot + "  正在处理 " + item.assetNo));

            try {
                if ("success".equals(item.status)) continue; // 已成功跳过

                // 1) 检测单张标签（低功率下仅一张靠近天线）
                TagInfo tag = detectSingleTag(INVENTORY_WINDOW_MS);
                if (tag == null) {
                    fail(item, "未检测到单张标签（请确保天线范围内仅一张空白标签）");
                    persistAndRefresh(item, cur, tot);
                    continue;
                }

                // 2) 尽量读取 TID 作为更精确的过滤条件（只读、出厂唯一）
                if (tag.tid == null || tag.tid.isEmpty()) {
                    String tidHex = sdk.readTagSync(2, 0, 6, PWD, READ_TIMEOUT_MS);
                    if (tidHex != null) tag.tid = tidHex;
                }

                // 3) 构造过滤条件
                int fBank, fStartBit, fLenBits;
                String fData;
                if (tag.tid != null && !tag.tid.isEmpty()) {
                    fBank = 2; fStartBit = 0; fLenBits = tag.tid.length() * 4; fData = tag.tid;
                } else {
                    String e = tag.epc.length() >= 8 ? tag.epc.substring(0, 8) : tag.epc;
                    fBank = 1; fStartBit = 32; fLenBits = e.length() * 4; fData = e;
                }

                // 4) 过滤写入资产编号（默认按 ASCII 直写；若 SDK 要求 Hex，改用 FormatUtil.asciiToHex）
                boolean ok = sdk.writeTagWithFilterSync(
                        fBank, fStartBit, fLenBits, fData,
                        bank, startWord, item.assetNo, PWD, WRITE_TIMEOUT_MS);
                if (!ok) { fail(item, "写入失败"); persistAndRefresh(item, cur, tot); continue; }

                // 5) 回读校验
                int wordLen = (item.assetNo.length() + 3) / 4;
                String read = sdk.readTagSync(bank, startWord, wordLen, PWD, READ_TIMEOUT_MS);
                boolean verified = read != null && FormatUtil.hexToAscii(read).trim().equals(item.assetNo);
                if (verified) {
                    item.status = "success";
                    item.tid = tag.tid == null ? "" : tag.tid;
                    item.epc = tag.epc == null ? "" : tag.epc;
                    item.writtenData = item.assetNo;
                    item.writtenAt = nowIso();
                    item.error = "";
                } else {
                    fail(item, "回读校验不一致 read=" + read);
                }
                persistAndRefresh(item, cur, tot);
            } catch (Exception e) {
                fail(item, "异常:" + e.getMessage());
                persistAndRefresh(item, cur, tot);
            }
        }

        writing = false;
        runOnUiThread(() -> {
            btnStartWrite.setEnabled(true);
            int succ = db.countByStatus("success");
            setStatus("批量写标完成：成功 " + succ + " / " + total + "（失败项可重新点“开始批量写标”补写）");
        });
    }

    /** 低功率短窗口盘点，收集到的【唯一】标签；多于/少于一张返回 null */
    private TagInfo detectSingleTag(long windowMs) {
        Map<String, Integer> seen = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(1);
        sdk.startInventory(new RfidSdk.InventoryCallback() {
            @Override public void onTag(TagInfo t) {
                if (t.epc != null && !t.epc.isEmpty()) seen.merge(t.epc, 1, Integer::sum);
            }
            @Override public void onEnd() { latch.countDown(); }
        });
        try { latch.await(windowMs, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        sdk.stopInventory();
        if (seen.size() == 1) {
            TagInfo r = new TagInfo();
            r.epc = seen.keySet().iterator().next();
            return r;
        }
        return null;
    }

    // ----------------- 导出 / 同步 -----------------

    private void exportJson() {
        try {
            List<WriteResult> all = db.getAll();
            java.io.File f = SyncManager.exportResultsJson(this, all);
            Toast.makeText(this, "已导出:\n" + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败:" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void syncToDws() {
        try {
            List<WriteResult> all = db.getAll();
            java.io.File f = SyncManager.exportResultsJson(this, all);
            // dws 是桌面 CLI，无法在 Android 运行；给出明确下一步指引。
            String msg = "结果已导出到:\n" + f.getAbsolutePath()
                    + "\n\n请在 PC 上执行：\npython sync_to_dws.py --results \""
                    + f.getAbsolutePath() + "\" --node <钉钉表格nodeId>";
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            Log.i(TAG, msg);
        } catch (Exception e) {
            Toast.makeText(this, "同步准备失败:" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ----------------- 辅助 -----------------

    private void fail(WriteResult item, String reason) {
        item.status = "failed";
        item.error = reason;
        item.writtenAt = nowIso();
    }

    private void persistAndRefresh(WriteResult item, int cur, int tot) {
        db.upsert(item);
        runOnUiThread(() -> {
            adapter.notifyDataSetChanged();
            refreshProgress();
        });
    }

    private void refreshProgress() {
        int succ = db.countByStatus("success");
        int fail = db.countByStatus("failed");
        tvProgress.setText("进度：成功 " + succ + " / 失败 " + fail + " / 共 " + ledger.size());
    }

    private void setStatus(String s) { tvStatus.setText("状态：" + s); }

    private int parseInt(EditText et, int def) {
        try { return Integer.parseInt(et.getText().toString().trim()); }
        catch (Exception e) { return def; }
    }

    private String nowIso() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.CHINA).format(new Date());
    }

    // ----------------- 结果列表适配器 -----------------

    static class ResultAdapter extends RecyclerView.Adapter<ResultAdapter.VH> {
        private final List<WriteResult> data;
        ResultAdapter(List<WriteResult> d) { this.data = d; }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            View v = LayoutInflater.from(p.getContext()).inflate(android.R.layout.simple_list_item_2, p, false);
            return new VH(v);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int i) {
            WriteResult r = data.get(i);
            h.title.setText(r.assetNo + "  [" + r.status + "]");
            h.sub.setText("部门:" + r.dept + " 型号:" + r.model + " | TID:" + r.tid + " | " + r.error);
            int color = r.status.equals("success") ? 0xFF2E7D32 :
                        r.status.equals("failed") ? 0xFFC62828 : 0xFFF9A825;
            h.title.setTextColor(color);
        }
        @Override public int getItemCount() { return data.size(); }
        static class VH extends RecyclerView.ViewHolder {
            TextView title, sub;
            VH(View v) { super(v); title = v.findViewById(android.R.id.text1); sub = v.findViewById(android.R.id.text2); }
        }
    }
}
