package com.example.rfidwriter;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
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
    private static final int REQ_PICK_LEDGER = 1002;
    private static final String PWD = "00000000";          // 默认访问密码
    private static final long INVENTORY_WINDOW_MS = 1500;  // 单标签检测窗口
    private static final long WRITE_TIMEOUT_MS = 5000;
    private static final long READ_TIMEOUT_MS = 3000;
    private static final int ASSET_BANK = 1;               // 资产编号固定写入 EPC 区
    private static final int ASSET_START = 2;              // EPC 区前 2 字为 CRC/PC
    private static final String INFO_MAGIC = "R2";         // USER 区结构化信息前缀（v2：八个自定义字段）

    private RfidSdk sdk = new IdataUhfAdapter();
    private ResultDbHelper db;
    private final List<WriteResult> ledger = new ArrayList<>();
    private ResultAdapter adapter;
    private android.widget.CheckBox chkSimulate;

    private EditText etPower, etModule, etBank, etStartWord, etSingleAsset, etLocation, etDept, etOwner;
    private TextView tvStatus, tvProgress, tvDetected, tvSingleResult;
    private Button btnStartWrite, btnSync, btnWriteSingle, btnInventory;
    private TagInfo lastDetectedTag;

    private volatile boolean writing = false;
    private volatile boolean inventorying = false;
    private volatile boolean invPaused = false;
    private volatile boolean locating = false;

    // 盘点统计：epc -> [次数, 最大RSSI(dBm)]
    private final Map<String, int[]> invStats = new ConcurrentHashMap<>();
    private final List<String> invRows = new ArrayList<>();   // 盘点对话框展示行
    private final List<String> invEpcs = new ArrayList<>();   // 与 invRows 对应的 EPC
    private android.app.AlertDialog invDialog;
    private android.widget.ArrayAdapter<String> invAdapter;
    private Button dlgBtnPause;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean invRefreshQueued;
    private final Runnable invRefreshTask = () -> { invRefreshQueued = false; refreshInventoryViews(); };

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
        etSingleAsset = findViewById(R.id.etSingleAsset);
        etLocation = findViewById(R.id.etLocation);
        etDept = findViewById(R.id.etDept);
        etOwner = findViewById(R.id.etOwner);
        tvDetected = findViewById(R.id.tvDetected);
        tvSingleResult = findViewById(R.id.tvSingleResult);
        btnWriteSingle = findViewById(R.id.btnWriteSingle);

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
        findViewById(R.id.btnImport).setOnClickListener(v -> pickLedger());
        findViewById(R.id.btnTemplate).setOnClickListener(v -> exportTemplate());
        findViewById(R.id.btnAddSingle).setOnClickListener(v -> showAddDialog());
        btnStartWrite.setOnClickListener(v -> startBatchWrite());
        findViewById(R.id.btnExport).setOnClickListener(v -> exportJson());
        btnSync.setOnClickListener(v -> syncToDws());
        findViewById(R.id.btnDetectSingle).setOnClickListener(v -> detectSingle());
        btnWriteSingle.setOnClickListener(v -> writeSingle());
        btnInventory = findViewById(R.id.btnInventory);
        btnInventory.setOnClickListener(v -> toggleInventory());
        findViewById(R.id.btnLocate).setOnClickListener(v -> {
            if (lastDetectedTag != null && !nz(lastDetectedTag.epc).isEmpty()) {
                locateTag(lastDetectedTag.epc);
            } else {
                Toast.makeText(this, "请先「检测单张标签」或从盘点结果中点击要定位的标签", Toast.LENGTH_LONG).show();
            }
        });

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

    // ----------------- 导入台账（Excel/CSV）与模板 -----------------

    private void pickLedger() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_PICK_LEDGER);
    }

    /** 导出批量导入 Excel 模板（.xlsx）到应用外部文件目录 */
    private void exportTemplate() {
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            java.io.File f = new java.io.File(dir, "asset-import-template.xlsx");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(FormatUtil.buildTemplateXlsx());
            fos.close();
            Toast.makeText(this, "模板已导出:\n" + f.getAbsolutePath()
                    + "\n用电脑 Excel 填写后点「导入Excel/CSV」导入", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出模板失败:" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String queryDisplayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {}
        return null;
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
                importParsed(parsed);
            } catch (Exception e) {
                Toast.makeText(this, "读取 CSV 失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (req == REQ_PICK_LEDGER && res == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            String name = queryDisplayName(uri);
            boolean isXlsx = name != null && name.toLowerCase(Locale.ROOT).endsWith(".xlsx");
            try {
                List<WriteResult> parsed;
                if (isXlsx) {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                        int n;
                        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    }
                    parsed = FormatUtil.parseLedgerXlsx(bos.toByteArray());
                } else {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(
                            getContentResolver().openInputStream(uri), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line).append("\n");
                    }
                    parsed = FormatUtil.parseLedgerCsv(sb.toString());
                }
                importParsed(parsed);
            } catch (Exception e) {
                Toast.makeText(this, "导入失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 统一入库：编码为空自动生成，落库并刷新列表 */
    private void importParsed(List<WriteResult> parsed) {
        if (parsed == null || parsed.isEmpty()) {
            Toast.makeText(this, "未解析到有效数据（表头需含：资产名称/资产编码等列）", Toast.LENGTH_LONG).show();
            return;
        }
        for (WriteResult w : parsed) {
            if (w.assetNo == null || w.assetNo.isEmpty()) w.assetNo = genAssetNo();
            ledger.add(w);
            db.upsert(w);
        }
        adapter.notifyDataSetChanged();
        refreshProgress();
        Toast.makeText(this, "导入 " + parsed.size() + " 条台账（编码为空的已自动生成）", Toast.LENGTH_SHORT).show();
    }

    /** 自动生成资产编码：yyyyMMddHHmmss + 3 位随机，查重后返回 */
    private String genAssetNo() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss", Locale.CHINA);
        java.util.Random rnd = new java.util.Random();
        for (int i = 0; i < 10; i++) {
            String code = f.format(new Date()) + String.format(Locale.ROOT, "%03d", rnd.nextInt(1000));
            if (!db.exists(code)) return code;
        }
        return f.format(new Date()) + System.currentTimeMillis() % 1000;
    }

    // ----------------- 批量写标 -----------------

    private void startBatchWrite() {
        if (writing) return;
        if (!sdk.isConnected()) { Toast.makeText(this, "请先连接 UHF", Toast.LENGTH_SHORT).show(); return; }
        if (ledger.isEmpty()) { Toast.makeText(this, "请先导入台账", Toast.LENGTH_SHORT).show(); return; }

        int power = parseInt(etPower, 15);
        int infoBank = parseInt(etBank, 3);      // 信息写入区：默认 USER(3)
        int infoStart = parseInt(etStartWord, 0); // 信息起始字地址：默认 0
        sdk.setPower(power); // 写单张前调低功率，避免误写邻近标签

        writing = true;
        btnStartWrite.setEnabled(false);
        new Thread(() -> runBatchWrite(infoBank, infoStart)).start();
    }

    private void runBatchWrite(int infoBank, int infoStart) {
        int total = ledger.size();
        int idx = 0;
        for (WriteResult item : ledger) {
            if (!writing) break;
            idx++;
            final int cur = idx, tot = total;
            runOnUiThread(() -> tvProgress.setText("进度：" + cur + " / " + tot + "  正在处理 " + item.assetNo));

            try {
                if ("success".equals(item.status)) continue; // 已成功跳过

                TagInfo tag = detectSingleTag(INVENTORY_WINDOW_MS);
                if (tag == null) {
                    fail(item, "未检测到单张标签（请确保天线范围内仅一张空白标签）");
                    persistAndRefresh(item, cur, tot);
                    continue;
                }
                if (tag.tid == null || tag.tid.isEmpty()) {
                    String tidHex = sdk.readTagSync(2, 0, 6, PWD, READ_TIMEOUT_MS);
                    if (tidHex != null) tag.tid = tidHex;
                }
                writeOneTagDual(tag, item, infoBank, infoStart);
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

    /**
     * 把一条记录的【资产编号】写入 EPC 区(固定 bank1/word2)，并把【存放位置|部门|使用人】
     * 按 UTF-8 写入 USER 区(infoBank/infoStart)，两步均做回读校验。成功后写 item 状态。
     */
    private void writeOneTagDual(TagInfo tag, WriteResult item, int infoBank, int infoStart) {
        // 过滤条件（基于 TID 或 EPC 前缀），保证只写天线范围内那一张
        String[] f = buildFilter(tag);
        int fBank = Integer.parseInt(f[0]), fStartBit = Integer.parseInt(f[1]), fLenBits = Integer.parseInt(f[2]);
        String fData = f[3];

        // 1) EPC 区：资产编号（ASCII）
        boolean epcOk = sdk.writeTagWithFilterSync(
                fBank, fStartBit, fLenBits, fData,
                ASSET_BANK, ASSET_START, item.assetNo, PWD, WRITE_TIMEOUT_MS);
        if (!epcOk) { fail(item, "EPC(资产编号)写入失败"); return; }
        int assetWordLen = (item.assetNo.length() + 1) / 2;
        String rEpc = sdk.readTagSync(ASSET_BANK, ASSET_START, assetWordLen, PWD, READ_TIMEOUT_MS);
        boolean epcV = rEpc != null && FormatUtil.hexToAscii(rEpc).trim().equals(item.assetNo);
        if (!epcV) { fail(item, "EPC 回读不一致 read=" + rEpc); return; }

        // 2) USER 区：结构化信息（UTF-8，支持中文），v2 格式：R2|名称|规格型号|分类|购置日期|购置价格|部门|使用人|存放地点|备注
        String info = INFO_MAGIC
                + "|" + nz(item.assetName) + "|" + nz(item.model) + "|" + nz(item.category)
                + "|" + nz(item.purchaseDate) + "|" + nz(item.purchasePrice)
                + "|" + nz(item.dept) + "|" + nz(item.owner) + "|" + nz(item.location) + "|" + nz(item.remark);
        boolean userOk = sdk.writeTagWithFilterSync(
                fBank, fStartBit, fLenBits, fData,
                infoBank, infoStart, info, PWD, WRITE_TIMEOUT_MS);
        if (!userOk) { fail(item, "USER(存放位置/部门/使用人)写入失败"); return; }
        byte[] infoBytes = info.getBytes(StandardCharsets.UTF_8);
        int infoWordLen = (infoBytes.length + 1) / 2;
        String rUser = sdk.readTagSync(infoBank, infoStart, infoWordLen, PWD, READ_TIMEOUT_MS);
        boolean userV = rUser != null && FormatUtil.hexToUtf8(rUser).equals(info);
        if (!userV) { fail(item, "USER 回读不一致 read=" + rUser); return; }

        // 全部通过
        item.status = "success";
        item.tid = tag.tid == null ? "" : tag.tid;
        item.epc = tag.epc == null ? "" : tag.epc;
        item.writtenData = item.assetNo + " | " + info;
        item.writtenAt = nowIso();
        item.error = "";
    }

    /** 基于检测到的标签构造过滤条件（TID 优先，回退到 EPC 前缀） */
    private String[] buildFilter(TagInfo tag) {
        int fBank, fStartBit, fLenBits;
        String fData;
        if (tag.tid != null && !tag.tid.isEmpty()) {
            fBank = 2; fStartBit = 0; fLenBits = tag.tid.length() * 4; fData = tag.tid;
        } else {
            String e = tag.epc != null && tag.epc.length() >= 8 ? tag.epc.substring(0, 8) : tag.epc;
            fBank = 1; fStartBit = 32; fLenBits = e.length() * 4; fData = e;
        }
        return new String[]{String.valueOf(fBank), String.valueOf(fStartBit), String.valueOf(fLenBits), fData};
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

    // ----------------- 单张标签写入 -----------------

    /** 检测天线范围内【唯一】一张标签，并在界面显示其 TID/EPC（不写入） */
    private void detectSingle() {
        if (!sdk.isConnected()) { Toast.makeText(this, "请先连接 UHF", Toast.LENGTH_SHORT).show(); return; }
        sdk.setPower(parseInt(etPower, 15));
        new Thread(() -> {
            TagInfo tag = detectSingleTag(INVENTORY_WINDOW_MS);
            lastDetectedTag = tag;
            runOnUiThread(() -> {
                if (tag == null) {
                    tvDetected.setText("检测到的标签：无（请确保天线范围内仅一张标签）");
                } else {
                    tvDetected.setText("检测到的标签：EPC=" + nz(tag.epc)
                            + (tag.tid != null && !tag.tid.isEmpty() ? "  TID=" + tag.tid : ""));
                }
            });
        }).start();
    }

    /** 单张写标共享流程：检测唯一标签 -> EPC 写编码 -> USER 写全部信息 -> 落库刷新 */
    private void writeOneInBackground(WriteResult wr) {
        if (writing) return;
        writing = true;
        btnWriteSingle.setEnabled(false);
        new Thread(() -> {
            String resultMsg;
            try {
                int infoBank = parseInt(etBank, 3);
                int infoStart = parseInt(etStartWord, 0);
                sdk.setPower(parseInt(etPower, 15)); // 写单张前调低功率，避免误写邻近标签

                TagInfo tag = detectSingleTag(INVENTORY_WINDOW_MS);
                if (tag == null) {
                    resultMsg = "失败：未检测到单张标签（请确保仅一张标签靠近天线）";
                } else {
                    if (tag.tid == null || tag.tid.isEmpty()) {
                        String tidHex = sdk.readTagSync(2, 0, 6, PWD, READ_TIMEOUT_MS);
                        if (tidHex != null) tag.tid = tidHex;
                    }
                    wr.tid = nz(tag.tid);
                    wr.epc = nz(tag.epc);
                    writeOneTagDual(tag, wr, infoBank, infoStart);
                    resultMsg = "success".equals(wr.status)
                            ? "成功：编码已写 EPC，信息已写 USER，回读校验通过"
                            : "失败：" + wr.error;
                }
            } catch (Exception e) {
                resultMsg = "异常：" + e.getMessage();
            }
            db.upsert(wr);
            if (!ledger.contains(wr)) ledger.add(wr);
            final String msg = resultMsg;
            writing = false;
            runOnUiThread(() -> {
                btnWriteSingle.setEnabled(true);
                adapter.notifyDataSetChanged();
                refreshProgress();
                tvSingleResult.setText("单张写入结果：" + msg);
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    /** 把单张写入区的输入写入天线范围内【唯一】一张标签 */
    private void writeSingle() {
        if (writing) return;
        if (!sdk.isConnected()) { Toast.makeText(this, "请先连接 UHF", Toast.LENGTH_SHORT).show(); return; }
        final String asset = etSingleAsset.getText().toString().trim();
        if (asset.isEmpty()) { Toast.makeText(this, "请先输入资产编码", Toast.LENGTH_SHORT).show(); return; }

        WriteResult wr = new WriteResult();
        wr.assetNo = asset;
        wr.assetName = "";
        wr.location = etLocation.getText().toString().trim();
        wr.dept = etDept.getText().toString().trim();
        wr.owner = etOwner.getText().toString().trim();
        wr.remark = "单张写入";
        writeOneInBackground(wr);
    }

    // ----------------- 盘点（持续群读）与标签定位 -----------------

    /** 开始/停止持续盘点（主界面按钮：开始会话 <-> 结束会话） */
    private void toggleInventory() {
        if (locating) { Toast.makeText(this, "定位进行中，请先停止定位", Toast.LENGTH_SHORT).show(); return; }
        if (!inventorying) {
            startInventorySession();
        } else {
            endInventory();
        }
    }

    /** 开始一次盘点会话：清空旧统计并启动群读 */
    private void startInventorySession() {
        if (!sdk.isConnected()) { Toast.makeText(this, "请先连接 UHF", Toast.LENGTH_SHORT).show(); return; }
        invStats.clear();
        invRows.clear();
        invEpcs.clear();
        invPaused = false;
        inventorying = true;
        sdk.setInventoryMode(1);          // 多标签盘点模式
        sdk.startInventory(makeInventoryCallback());
        showInventoryDialog();
        updateInventoryButton();
    }

    /** 结束盘点会话：停止群读，统计结果保留在弹窗内（可保存/导出） */
    private void endInventory() {
        inventorying = false;
        invPaused = false;
        sdk.stopInventory();
        sdk.setInventoryMode(0);
        updateInventoryButton();
        refreshInventoryViews();
    }

    /** 暂停盘点：停止群读，但保留已统计结果，稍后可继续盘点 */
    private void pauseInventory() {
        if (!inventorying || invPaused) return;
        invPaused = true;
        sdk.stopInventory();
        refreshInventoryViews();          // 标题刷新为「已暂停」
    }

    /** 继续盘点：在暂停基础上恢复群读，统计结果累加而非清零 */
    private void resumeInventory() {
        if (!inventorying || !invPaused) return;
        invPaused = false;
        sdk.setInventoryMode(1);
        sdk.startInventory(makeInventoryCallback());
        refreshInventoryViews();          // 标题刷新为「盘点中」
    }

    /** 统一创建盘点群读回调（开始与继续复用同一份） */
    private RfidSdk.InventoryCallback makeInventoryCallback() {
        return new RfidSdk.InventoryCallback() {
            @Override public void onTag(TagInfo t) {
                if (!inventorying || invPaused || t.epc == null || t.epc.isEmpty()) return;
                int[] s = invStats.computeIfAbsent(t.epc, k -> new int[]{0, -120});
                s[0]++;
                int r = Math.round(parseRssi(t.rssi));
                if (r > s[1]) s[1] = r;
                queueInventoryRefresh();
            }
            @Override public void onEnd() { }
        };
    }

    private void updateInventoryButton() {
        if (btnInventory != null) btnInventory.setText(inventorying ? "停止盘点" : "开始盘点");
    }

    private void queueInventoryRefresh() {
        if (!invRefreshQueued) {
            invRefreshQueued = true;
            uiHandler.postDelayed(invRefreshTask, 400);
        }
    }

    /** 盘点结果弹窗：实时列表 + 暂停/继续盘点 + 保存Excel + 结束；点击条目进入定位 */
    private void showInventoryDialog() {
        android.widget.ListView lv = new android.widget.ListView(this);
        invAdapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1, invRows);
        lv.setAdapter(invAdapter);
        lv.setOnItemClickListener((p, v, pos, id) -> {
            String epc = invEpcs.get(pos);
            endInventory();
            if (invDialog != null) invDialog.dismiss();
            locateTag(epc);
        });

        TextView tip = new TextView(this);
        tip.setText("点击任意标签 → 雷达定位；同一 EPC 多次上报自动合并计数；可暂停盘点后再继续");
        tip.setTextSize(12);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        tip.setPadding(pad, pad, pad, 4);

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.addView(tip);
        box.addView(lv);

        dlgBtnPause = new Button(this);
        dlgBtnPause.setText(invPaused ? "继续盘点" : "暂停盘点");
        dlgBtnPause.setOnClickListener(v -> {
            if (invPaused) resumeInventory();
            else pauseInventory();
        });
        box.addView(dlgBtnPause);

        Button btnSave = new Button(this);
        btnSave.setText("保存盘点Excel");
        btnSave.setOnClickListener(v -> saveInventoryExcel());
        box.addView(btnSave);

        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this)
                .setTitle("盘点中：0 张标签")
                .setView(box)
                .setNegativeButton("结束盘点", null)
                .create();
        invDialog = d;
        d.setOnDismissListener(di -> { endInventory(); dlgBtnPause = null; });
        d.show();
        // 覆盖按钮行为：不自动关窗
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setVisibility(View.GONE);
        d.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setVisibility(View.GONE);
    }

    /** 重建盘点列表并刷新弹窗（节流后由 uiHandler 调用） */
    private void refreshInventoryViews() {
        invRows.clear();
        invEpcs.clear();
        invStats.entrySet().stream()
                .sorted((a, b) -> b.getValue()[0] - a.getValue()[0])
                .forEach(e -> {
                    boolean registered = false;
                    for (WriteResult w : ledger) {
                        if (e.getKey().equals(w.assetNo)) { registered = true; break; }
                    }
                    invEpcs.add(e.getKey());
                    invRows.add(e.getKey()
                            + "  次数:" + e.getValue()[0]
                            + "  RSSI:" + (e.getValue()[1] <= -120 ? "--" : String.valueOf(e.getValue()[1]))
                            + (registered ? "  [已登记]" : "  [未登记]"));
                });
        if (invDialog != null && invDialog.isShowing()) {
            invDialog.setTitle((invPaused ? "已暂停：" : (inventorying ? "盘点中：" : "盘点结束："))
                    + invStats.size() + " 张标签");
            if (dlgBtnPause != null) {
                dlgBtnPause.setText(invPaused ? "继续盘点" : "暂停盘点");
            }
            invAdapter.notifyDataSetChanged();
        }
    }

    /** 保存盘点结果 Excel（.xlsx，已登记条目附带资产名称） */
    private void saveInventoryExcel() {
        if (invStats.isEmpty()) { Toast.makeText(this, "暂无盘点数据", Toast.LENGTH_SHORT).show(); return; }
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            java.io.File f = new java.io.File(dir, "inventory-"
                    + new SimpleDateFormat("yyyyMMddHHmmss", Locale.CHINA).format(new Date()) + ".xlsx");
            String[] headers = {"EPC/资产编码", "资产名称", "盘点次数", "最大RSSI(dBm)", "登记状态"};
            List<String[]> rows = new ArrayList<>();
            for (int i = 0; i < invEpcs.size(); i++) {
                String epc = invEpcs.get(i);
                int[] s = invStats.get(epc);
                String name = "";
                boolean registered = false;
                for (WriteResult w : ledger) {
                    if (epc.equals(w.assetNo)) {
                        registered = true;
                        name = nz(w.assetName);
                        break;
                    }
                }
                rows.add(new String[]{epc, name, String.valueOf(s[0]),
                        s[1] <= -120 ? "" : String.valueOf(s[1]),
                        registered ? "已登记" : "未登记"});
            }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(FormatUtil.buildTableXlsx("盘点结果", headers, rows));
            fos.close();
            Toast.makeText(this, "盘点结果已保存:\n" + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败:" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 标签定位：雷达图 + 信号值(0-254) + LED 提示，循环短盘点读取目标 RSSI */
    private void locateTag(String epc) {
        if (locating) { Toast.makeText(this, "已在定位中", Toast.LENGTH_SHORT).show(); return; }
        if (!sdk.isConnected()) { Toast.makeText(this, "请先连接 UHF", Toast.LENGTH_SHORT).show(); return; }
        if (inventorying) endInventory();

        locating = true;
        sdk.setInventoryMode(0);
        final float txPower = parseInt(etPower, 30); // 定位用高功率，读得远
        sdk.setPower((int) txPower);

        float density = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * density);
        root.setPadding(pad, pad, pad, 0);

        TextView tvInfo = new TextView(this);
        tvInfo.setText("EPC: " + epc);
        tvInfo.setTextSize(14);

        TextView tvRssi = new TextView(this);
        tvRssi.setText("信号值：--");
        tvRssi.setTextSize(14);

        TextView tvDist = new TextView(this);
        tvDist.setText("估算距离：--");
        tvDist.setTextSize(16);

        RadarView radar = new RadarView(this);
        radar.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(300 * density)));

        android.widget.CheckBox chkLed = new android.widget.CheckBox(this);
        chkLed.setText("LED 闪烁提示（真机定位时标签灯闪）");
        chkLed.setOnCheckedChangeListener((b, on) -> sdk.setLedBlink(on));

        Button btnStop = new Button(this);
        btnStop.setText("停止定位");
        btnStop.setOnClickListener(v -> { locating = false; btnStop.setEnabled(false); btnStop.setText("已停止"); });

        root.addView(tvInfo);
        root.addView(tvRssi);
        root.addView(tvDist);
        root.addView(radar);
        root.addView(chkLed);
        root.addView(btnStop);

        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this)
                .setTitle("标签定位")
                .setView(root)
                .setNegativeButton("关闭", null)
                .create();
        d.setOnDismissListener(di -> {
            locating = false;
            sdk.setLedBlink(false);
            sdk.stopInventory();
            sdk.setInventoryMode(0);
        });
        d.show();

        new Thread(() -> {
            while (locating) {
                final float[] best = {-120f};
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                sdk.startInventory(new RfidSdk.InventoryCallback() {
                    @Override public void onTag(TagInfo t) {
                        if (epc.equals(t.epc)) {
                            float r = parseRssi(t.rssi);
                            if (r > best[0]) best[0] = r;
                        }
                    }
                    @Override public void onEnd() { latch.countDown(); }
                });
                try { latch.await(400, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
                sdk.stopInventory();
                final float rssi = best[0];
                runOnUiThread(() -> {
                    if (!locating) return;
                    if (rssi <= -120) {
                        radar.setRssiDbm(rssi, Float.NaN);
                        tvRssi.setText("信号值：未捕捉到目标标签（请移动设备靠近）");
                        tvDist.setText("估算距离：--");
                    } else {
                        float dist = FormatUtil.estimateDistanceMeters(rssi, txPower);
                        radar.setRssiDbm(rssi, dist);
                        int sig = Math.max(0, Math.min(254, Math.round((rssi + 90f) * 254f / 60f)));
                        tvRssi.setText("信号值：" + sig + " / 254（" + Math.round(rssi) + " dBm）"
                                + (sig >= 220 ? "  → 就在附近！" : ""));
                        tvDist.setText("估算距离：" + FormatUtil.formatDistance(dist));
                    }
                });
                try { Thread.sleep(80); } catch (InterruptedException e) { break; }
            }
        }).start();
    }

    private static float parseRssi(String s) {
        try { return Float.parseFloat(s); } catch (Exception e) { return -120f; }
    }

    // ----------------- 单个新增（名称必填，编码自动生成） -----------------

    /** 弹出新增资产对话框：资产名称必填，编码自动生成，8 个自定义字段选填 */
    private void showAddDialog() {
        android.widget.LinearLayout form = new android.widget.LinearLayout(this);
        form.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad, pad, 0);

        android.widget.EditText etName = addFormField(form, "资产名称 *（必填）");
        android.widget.EditText etModel = addFormField(form, "规格型号");
        android.widget.EditText etCategory = addFormField(form, "资产分类");
        android.widget.EditText etDate = addFormField(form, "购置日期（如 2026-03-01）");
        android.widget.EditText etPrice = addFormField(form, "购置价格");
        android.widget.EditText etDeptF = addFormField(form, "使用部门");
        android.widget.EditText etOwnerF = addFormField(form, "使用人员");
        android.widget.EditText etLocF = addFormField(form, "存放地点");
        android.widget.EditText etRemark = addFormField(form, "备注");

        TextView tip = new TextView(this);
        tip.setText("资产编码将自动生成（写入 EPC 区）");
        tip.setTextSize(12);
        form.addView(tip);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);

        new android.app.AlertDialog.Builder(this)
                .setTitle("新增资产")
                .setView(scroll)
                .setPositiveButton("保存并写标", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "资产名称必填", Toast.LENGTH_LONG).show();
                        return;
                    }
                    WriteResult wr = new WriteResult();
                    wr.assetName = name;
                    wr.assetNo = genAssetNo();
                    wr.model = etModel.getText().toString().trim();
                    wr.category = etCategory.getText().toString().trim();
                    wr.purchaseDate = etDate.getText().toString().trim();
                    wr.purchasePrice = etPrice.getText().toString().trim();
                    wr.dept = etDeptF.getText().toString().trim();
                    wr.owner = etOwnerF.getText().toString().trim();
                    wr.location = etLocF.getText().toString().trim();
                    wr.remark = etRemark.getText().toString().trim();

                    if (!sdk.isConnected()) {
                        // 未连接：先保存为待写记录，连接后批量写
                        ledger.add(wr);
                        db.upsert(wr);
                        adapter.notifyDataSetChanged();
                        refreshProgress();
                        Toast.makeText(this, "已保存（编码 " + wr.assetNo
                                + "），未连接 UHF；连接后可点「开始批量写标」写入", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "编码 " + wr.assetNo + "，请将一张标签靠近天线…", Toast.LENGTH_SHORT).show();
                        writeOneInBackground(wr);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private android.widget.EditText addFormField(android.widget.LinearLayout parent, String hint) {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setHint(hint);
        et.setSingleLine(true);
        parent.addView(et);
        return et;
    }

    private static String nz(String s) { return s == null ? "" : s; }

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
            String title = (r.assetNo == null || r.assetNo.isEmpty() ? "(无编码)" : r.assetNo)
                    + (r.assetName != null && !r.assetName.isEmpty() ? " " + r.assetName : "")
                    + "  [" + r.status + "]";
            h.title.setText(title);
            h.sub.setText("部门:" + nz(r.dept) + " 使用人:" + nz(r.owner) + " 位置:" + nz(r.location)
                    + " 分类:" + nz(r.category) + " | TID:" + r.tid + " | " + r.error);
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
