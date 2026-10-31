package com.example.rfidwriter;

import android.app.Activity;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.ToneGenerator;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.rfidwriter.db.ResultDbHelper;
import com.example.rfidwriter.db.WriteResult;
import com.example.rfidwriter.rfid.IdataUhfAdapter;
import com.example.rfidwriter.rfid.RfidSdk;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQ_PICK_CSV = 1001;
    private static final int REQ_PICK_LEDGER = 1002;
    private static final String PWD = "00000000";          // 默认访问密码
    private static final long INVENTORY_WINDOW_MS = 3000;  // 单标签检测窗口（拉长，确保首张标签稳定出现）
    private static final long WRITE_TIMEOUT_MS = 5000;
    private static final long READ_TIMEOUT_MS = 3000;
    private static final int ASSET_BANK = 1;               // 资产编号固定写入 EPC 区
    private static final int ASSET_START = 2;              // EPC 区前 2 字为 CRC/PC
    private static final String INFO_MAGIC = "R2";         // USER 区结构化信息前缀（v2：八个自定义字段）

    // 屏幕路由：单一 Activity 内多级页面切换（首页→设置 / 读写标签 / 盘点；读写标签→读取 / 写入）
    private static final int SCREEN_HOME = 0;
    private static final int SCREEN_SETTINGS = 1;
    private static final int SCREEN_RW = 2;
    private static final int SCREEN_READ = 3;
    private static final int SCREEN_WRITE = 4;
    private static final int SCREEN_INVENTORY = 5;
    private static final String PARAMS = "rfid_params";   // 参数持久化（与当前页面解耦）

    private RfidSdk sdk = new IdataUhfAdapter();
    /** 远程/自动化测试钩子：收到该 action 广播即触发「连接 UHF」（等价于手动点“连接UHF”按钮） */
    public static final String ACTION_CONNECT = "com.example.rfidwriter.action.CONNECT";
    /** 远程只读测试钩子：am broadcast -a ...READ_TEST 触发「连(若未连)→检测单张→读TID/EPC/USER」，日志前缀 RFID_TEST */
    public static final String ACTION_READ_TEST = "com.example.rfidwriter.action.READ_TEST";
    /** 远程写测试钩子：am broadcast -a ...WRITE_TEST 触发「连(若未连)→检测单张→往USER区写测试串→回读校验」，日志前缀 RFID_TEST */
    public static final String ACTION_WRITE_TEST = "com.example.rfidwriter.action.WRITE_TEST";
    private BroadcastReceiver mTestReceiver;
    private ResultDbHelper db;
    private final List<WriteResult> ledger = new ArrayList<>();
    private ResultAdapter adapter;

    private EditText etPower, etModule, etBank, etStartWord, etSingleAsset, etLocation, etDept, etOwner;
    private TextView tvStatus, tvProgress, tvDetected, tvSingleResult, tvTitle, tvReadData;
    private TextView tvBadge, tvInvCount, tvLastEpc, tvEpc, tvTid, tvUser;
    private Button btnStartWrite, btnSync, btnWriteSingle, btnInventory, btnBack;
    private FrameLayout flContent;
    private int currentScreen = SCREEN_HOME;
    private android.content.SharedPreferences prefs;
    private Map<String, String> lastReadInfo;   // 读取标签页：最近一次读取结果（供导出）
    private TagInfo lastDetectedTag;
    private String detectFailReason = "";   // detectSingleTag 返回 null 时的原因，供 UI 展示

    private volatile boolean writing = false;
    private volatile boolean inventorying = false;
    private volatile boolean invPaused = false;
    private volatile boolean locating = false;
    private volatile boolean reading = false;       // 读取中（防止手柄连按并发读取）
    private boolean locateFromInventory = false;   // 本次定位是否由盘点弹窗发起（关闭后自动回盘点）

    // 标签定位：指南针 + 邻近声音提示所需状态
    private SensorManager mSensorManager;
    private SensorEventListener mLocateSensorListener;
    private final float[] mGravity = new float[3];
    private final float[] mGeomag = new float[3];
    private final float[] mRotMat = new float[9];
    private final float[] mInclMat = new float[9];
    private volatile float mDeviceAzimuthDeg = 0f;   // 设备当前朝向（罗盘，0=北，顺时针+）
    private volatile float mPeakRssi = -120f;        // 定位以来观测到的最强 RSSI
    private volatile float mTagBearingDeg = Float.NaN; // 锚定标签方位（信号最强时的设备朝向）
    private ToneGenerator mToneGen;                  // 邻近提示音发生器
    private volatile float mBearingCalAz = Float.NaN;   // 正前基准（罗盘角，0=北）。NaN=未校准，回退到实时设备朝向
    private long mLastAutoCalMs = 0;                    // 自动校准节流时间戳
    private long mLastDiagMs = 0;                      // 基准正确性诊断日志节流时间戳

    // 4 方位标定标签：用于校准/确认罗盘正前基准。EPC 大写；angle=标签相对【设备正前】的方位角(度)
    // A1=前(0)  B1=后(180)  C1=右(90)  D1=左(-90)，与实物摆放一致（用户提供）。
    private static final java.util.Map<String, String> CAL_TAG_NAME = new java.util.HashMap<>();
    private static final java.util.Map<String, Float> CAL_TAG_ANGLE = new java.util.HashMap<>();
    static {
        CAL_TAG_NAME.put("E2801191A5040079AE927637", "A1"); CAL_TAG_ANGLE.put("E2801191A5040079AE927637", 0f);
        CAL_TAG_NAME.put("E2801191A5040079AE927627", "B1"); CAL_TAG_ANGLE.put("E2801191A5040079AE927627", 180f);
        CAL_TAG_NAME.put("E2801191A5040079AE927647", "C1"); CAL_TAG_ANGLE.put("E2801191A5040079AE927647", 90f);
        CAL_TAG_NAME.put("E2801191A5040079AE927617", "D1"); CAL_TAG_ANGLE.put("E2801191A5040079AE927617", -90f);
    }

    // ----------------- 手柄按键（设备扳机/扫描键）映射 -----------------
    // 在「读取标签/盘点」页第一次按下任意【非系统】键时，自动记住该 keyCode 作为“手柄触发键”（持久化），
    // 之后按该键即触发对应操作。这样不依赖具体机型把扳机映射成哪个 keyCode（F1/手柄键/相机键…都行）。
    // 系统键（返回/Home/电源/音量等）不参与学习，避免误绑。
    private static final Set<Integer> SYSTEM_KEYS_NO_LEARN = new HashSet<>(Arrays.asList(
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_ENDCALL, KeyEvent.KEYCODE_CALL));
    private int mHandleKey = 0;   // 0 表示尚未学习到；从偏好读取

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
        installCrashHandler();   // 必须在最前：捕获后续任何启动期崩溃并落盘
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        db = new ResultDbHelper(this);
        // 把 Context 交给 RFID 适配器：它需要用 assets 里的 uhf_sdk.dex 在运行时构造 DexClassLoader。
        // （iData UHF SDK 无法直接拿到 aar，改为把真机预装 UHF Demo 的 classes.dex 作为资源打入 APK。）
        ((IdataUhfAdapter) sdk).attach(this);

        // 注册测试钩子：shell 广播即可触发（无需脆弱的 UI 坐标点击）
        // Android 13+ 需用 RECEIVER_EXPORTED，否则 shell 发送的广播收不到（enqueued receivers=0）
        mTestReceiver = new BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, Intent i) {
                String a = i.getAction();
                if (ACTION_CONNECT.equals(a)) connect();
                else if (ACTION_READ_TEST.equals(a)) runReadTest();
                else if (ACTION_WRITE_TEST.equals(a)) runWriteTest();
            }
        };
        android.content.IntentFilter testFilter = new android.content.IntentFilter();
        testFilter.addAction(ACTION_CONNECT);
        testFilter.addAction(ACTION_READ_TEST);
        testFilter.addAction(ACTION_WRITE_TEST);
        registerReceiver(mTestReceiver, testFilter, android.content.Context.RECEIVER_EXPORTED);

        // Android 9 及以下写公共「下载」目录需要运行时权限；10+ 用 MediaStore 免权限
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2001);
        }

        // 外壳视图（顶部栏 + 持久状态 + 内容容器）一次性绑定
        prefs = getSharedPreferences(PARAMS, MODE_PRIVATE);
        mHandleKey = prefs.getInt("handleKey", 0);   // 恢复已学习的手柄触发键
        tvStatus = findViewById(R.id.tvStatus);
        tvProgress = findViewById(R.id.tvProgress);
        tvBadge = findViewById(R.id.tvBadge);
        tvTitle = findViewById(R.id.tvTitle);
        btnBack = findViewById(R.id.btnBack);
        flContent = findViewById(R.id.flContent);
        findViewById(R.id.btnConnect).setOnClickListener(v -> connect());
        findViewById(R.id.btnDisconnect).setOnClickListener(v -> {
            sdk.release();
            setStatus("已断开");
        });
        btnBack.setOnClickListener(v -> {
            int p = parentOf(currentScreen);
            if (p >= 0) showScreen(p);
        });

        // 进入首页（其它页面通过顶部“返回”或页面内按钮进入）
        showScreen(SCREEN_HOME);

        // 恢复上次未完成的记录
        ledger.addAll(db.getAll());
        refreshProgress();

        // 若上次崩溃已记录，弹窗展示堆栈（截图发我即可定位）
        maybeShowLastCrash();
    }

    // ----------------- 屏幕路由（首页→设置 / 读写标签 / 盘点；读写标签→读取 / 写入） -----------------

    /** 进入指定屏幕：把对应布局 inflate 进 flContent，并绑定该页控件与按钮 */
    private void showScreen(int screen) {
        currentScreen = screen;
        int layout;
        switch (screen) {
            case SCREEN_SETTINGS:   layout = R.layout.screen_settings;   break;
            case SCREEN_RW:         layout = R.layout.screen_rw;         break;
            case SCREEN_READ:       layout = R.layout.screen_read;       break;
            case SCREEN_WRITE:      layout = R.layout.screen_write;      break;
            case SCREEN_INVENTORY:  layout = R.layout.screen_inventory;  break;
            default:                layout = R.layout.screen_home;        break;
        }
        flContent.removeAllViews();
        View root = LayoutInflater.from(this).inflate(layout, flContent, false);
        flContent.addView(root);

        btnBack.setVisibility(screen == SCREEN_HOME ? View.GONE : View.VISIBLE);
        if (tvTitle != null) tvTitle.setText(screenTitle(screen));

        switch (screen) {
            case SCREEN_HOME:      bindHome(root);      break;
            case SCREEN_SETTINGS:   bindSettings(root);  break;
            case SCREEN_RW:         bindRw(root);        break;
            case SCREEN_READ:       bindRead(root);      break;
            case SCREEN_WRITE:      bindWrite(root);     break;
            case SCREEN_INVENTORY:  bindInventory(root); break;
        }
    }

    private int parentOf(int screen) {
        switch (screen) {
            case SCREEN_SETTINGS:
            case SCREEN_RW:
            case SCREEN_INVENTORY:  return SCREEN_HOME;
            case SCREEN_READ:
            case SCREEN_WRITE:      return SCREEN_RW;
            default:                return -1;
        }
    }

    private String screenTitle(int screen) {
        switch (screen) {
            case SCREEN_SETTINGS:   return getString(R.string.title_settings);
            case SCREEN_RW:         return getString(R.string.title_rw);
            case SCREEN_READ:       return getString(R.string.title_read);
            case SCREEN_WRITE:      return getString(R.string.title_write);
            case SCREEN_INVENTORY:  return getString(R.string.title_inventory);
            default:                return getString(R.string.app_name);
        }
    }

    /** 各页控件按当前布局重新绑定（控件只在对应页面存在，按需 setOnClickListener） */
    private void bindHome(View r) {
        r.findViewById(R.id.btnSettings).setOnClickListener(v -> showScreen(SCREEN_SETTINGS));
        r.findViewById(R.id.btnRw).setOnClickListener(v -> showScreen(SCREEN_RW));
        r.findViewById(R.id.btnInventoryHome).setOnClickListener(v -> showScreen(SCREEN_INVENTORY));
        r.findViewById(R.id.btnExport).setOnClickListener(v -> exportJson());
        r.findViewById(R.id.btnSync).setOnClickListener(v -> syncToDws());
    }

    private void bindSettings(View r) {
        etPower = r.findViewById(R.id.etPower);
        etModule = r.findViewById(R.id.etModule);
        etBank = r.findViewById(R.id.etBank);
        etStartWord = r.findViewById(R.id.etStartWord);
        // 从持久化参数回填输入框
        etPower.setText(String.valueOf(getParam("power", 30)));
        etModule.setText(String.valueOf(getParam("module", 0)));
        etBank.setText(String.valueOf(getParam("bank", 3)));
        etStartWord.setText(String.valueOf(getParam("startWord", 0)));
        r.findViewById(R.id.btnSaveSettings).setOnClickListener(v -> saveParams());
    }

    private void bindRw(View r) {
        r.findViewById(R.id.btnGoRead).setOnClickListener(v -> showScreen(SCREEN_READ));
        r.findViewById(R.id.btnGoWrite).setOnClickListener(v -> showScreen(SCREEN_WRITE));
    }

    private void bindRead(View r) {
        tvDetected = r.findViewById(R.id.tvDetected);
        tvReadData = r.findViewById(R.id.tvReadData);
        tvEpc = r.findViewById(R.id.tvEpc);
        tvTid = r.findViewById(R.id.tvTid);
        tvUser = r.findViewById(R.id.tvUser);
        r.findViewById(R.id.btnDetectSingle).setOnClickListener(v -> readFullTag());
        r.findViewById(R.id.btnExportRead).setOnClickListener(v -> exportRead());
        r.findViewById(R.id.btnGoWriteFromRead).setOnClickListener(v -> showScreen(SCREEN_WRITE));
    }

    private void bindWrite(View r) {
        etSingleAsset = r.findViewById(R.id.etSingleAsset);
        etLocation = r.findViewById(R.id.etLocation);
        etDept = r.findViewById(R.id.etDept);
        etOwner = r.findViewById(R.id.etOwner);
        tvSingleResult = r.findViewById(R.id.tvSingleResult);
        btnWriteSingle = r.findViewById(R.id.btnWriteSingle);
        btnStartWrite = r.findViewById(R.id.btnStartWrite);
        r.findViewById(R.id.btnImportData).setOnClickListener(v -> pickLedger());
        r.findViewById(R.id.btnTemplate).setOnClickListener(v -> exportTemplate());
        btnWriteSingle.setOnClickListener(v -> writeSingle());
        btnStartWrite.setOnClickListener(v -> startBatchWrite());
        r.findViewById(R.id.btnAdd).setOnClickListener(v -> showAddDialog());
    }

    private void bindInventory(View r) {
        btnInventory = r.findViewById(R.id.btnInventory);
        tvInvCount = r.findViewById(R.id.tvInvCount);
        tvLastEpc = r.findViewById(R.id.tvLastEpc);
        btnInventory.setOnClickListener(v -> toggleInventory());
        // 注：主界面不再放置「定位标签」按钮；标签定位统一从盘点结果列表中点击具体标签进入。
        RecyclerView rv = r.findViewById(R.id.rvResults);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ResultAdapter(ledger);
        rv.setAdapter(adapter);
        notifyList();
        updateInventoryButton();
    }

    // ----------------- 参数持久化（与当前显示页面解耦，连接/写/读均从这里取） -----------------

    private int getParam(String name, int def) {
        return prefs.getInt(name, def);
    }

    /** 保存设置页参数为持久化值（下次连接自动应用） */
    private void saveParams() {
        prefs.edit()
                .putInt("power", parseInt(etPower, 30))
                .putInt("module", parseInt(etModule, 0))
                .putInt("bank", parseInt(etBank, 3))
                .putInt("startWord", parseInt(etStartWord, 0))
                .apply();
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
    }

    // ----------------- 崩溃自诊断（远程排查用） -----------------

    /** 安装全局未捕获异常处理器：崩溃时把堆栈写入文件，下次启动弹窗展示 */
    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                String ts = new SimpleDateFormat("yyyyMMddHHmmss", Locale.CHINA).format(new Date());
                java.io.File dir = new java.io.File(getExternalFilesDir(null), "crash");
                dir.mkdirs();
                java.io.File f = new java.io.File(dir, "crash-" + ts + ".txt");
                try (java.io.PrintWriter w = new java.io.PrintWriter(f)) {
                    w.write("Time: " + ts + "\n");
                    w.write("Thread: " + t.getName() + "\n");
                    e.printStackTrace(w);
                }
                android.content.SharedPreferences sp = getSharedPreferences("crash", MODE_PRIVATE);
                sp.edit().putString("last_crash", f.getAbsolutePath()).apply();
            } catch (Throwable ignore) { }
            if (def != null) def.uncaughtException(t, e);
        });
    }

    /** 若上次崩溃已记录，弹窗展示堆栈，方便截图反馈；展示后清除记录 */
    private void maybeShowLastCrash() {
        try {
            android.content.SharedPreferences sp = getSharedPreferences("crash", MODE_PRIVATE);
            String path = sp.getString("last_crash", null);
            if (path == null) return;
            sp.edit().remove("last_crash").apply();
            java.io.File f = new java.io.File(path);
            if (!f.exists()) return;
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
                String line;
                int lines = 0;
                while ((line = r.readLine()) != null && lines < 200) {
                    sb.append(line).append("\n");
                    lines++;
                }
            }
            String text = sb.toString();
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            android.widget.TextView tv = new android.widget.TextView(this);
            tv.setText(text);
            tv.setTextSize(11);
            int p = (int) (8 * getResources().getDisplayMetrics().density);
            tv.setPadding(p, p, p, p);
            sv.addView(tv);
            new android.app.AlertDialog.Builder(this)
                    .setTitle("上次启动崩溃报告（截图发我即可定位）")
                    .setView(sv)
                    .setPositiveButton("复制", (d, w) -> {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", text));
                        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (Throwable ignore) { }
    }

    // ----------------- 连接 / 参数 -----------------

    private void connect() {
        int module = getParam("module", 0);
        sdk.init(module, (ok, msg) -> {
            if (!ok) {
                // UHF 模块偶发首连失败（多为上一次未干净释放），自动重试一次，避免用户看到假失败
                Log.i(TAG, "connect: 首连失败，800ms 后自动重试一次");
                runOnUiThread(() -> setStatus(msg + "（自动重试中…）"));
                uiHandler.postDelayed(() -> sdk.init(module, (ok2, msg2) -> runOnUiThread(() -> {
                    setStatus(msg2);
                    if (ok2) {
                        // 一次性上电并初始化原生 RF 会话：iData 固件要求 startInventoryTag 之前必须
                        // powerOn，否则共享的 iUHF 系统服务里会话对象为 NULL，startInventoryTag 触发
                        // 原生 SIGSEGV（fault addr 0x0）。上电后保持连续盘点模式（绝不再 powerOff、
                        // 绝不切到 setInventoryMode(0) 的“通电不发射”降级态），模组始终通电且发射，iUHF 仍可共存读取。
                        sdk.powerOn();
                        sdk.setInventoryMode(1);
                    }
                })), 800);
                return;
            }
            runOnUiThread(() -> {
                setStatus(msg);
                // 一次性上电并初始化原生 RF 会话：powerOn 后保持连续盘点模式，模组始终通电且发射，
                // 既让 startInventoryTag 安全可用，也避免把 iUHF 留在“通电不发射”降级态。
                sdk.powerOn();
                sdk.setInventoryMode(1);
            });
        });
    }

    // ----------------- 远程读写测试钩子（自动化验证用） -----------------

    /** 远程只读测试：若未连接先连，3s 后检测唯一标签并读 TID/EPC/USER，全部以 RFID_TEST 前缀落 logcat */
    private void runReadTest() {
        if (sdk.isConnected()) doReadTest();
        else { Log.i(TAG, "READ_TEST: 尚未连接，先触发连接…"); connect(); uiHandler.postDelayed(() -> doReadTest(), 3000); }
    }

    /** 远程写测试：若未连接先连，3s 后检测唯一标签，往 USER 区(bank3/word0)写固定测试串，再回读校验 */
    private void runWriteTest() {
        if (sdk.isConnected()) doWriteTest();
        else { Log.i(TAG, "WRITE_TEST: 尚未连接，先触发连接…"); connect(); uiHandler.postDelayed(() -> doWriteTest(), 3000); }
    }

    /** 只读测试主体：检测天线范围内唯一标签，读 TID/EPC/USER 三区并打 hex + 解析日志；无唯一标签则快速盘点诊断 */
    private void doReadTest() {
        if (!sdk.isConnected()) { Log.w(TAG, "READ_TEST: 仍未连接 UHF，跳过"); return; }
        sdk.setPower(getParam("power", 30));
        new Thread(() -> {
            TagInfo tag = detectSingleTag(INVENTORY_WINDOW_MS);
            if (tag == null) {
                // 没检测到“唯一”一张：做一次快速盘点，确认天线到底有没有看到标签
                java.util.Set<String> all = java.util.Collections.newSetFromMap(
                        new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
                CountDownLatch latch = new CountDownLatch(1);
                sdk.startInventory(new RfidSdk.InventoryCallback() {
                    @Override public void onTag(TagInfo t) { if (t.epc != null && !t.epc.isEmpty()) all.add(t.epc); }
                    @Override public void onEnd() { latch.countDown(); }
                });
                try { latch.await(1200, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
                sdk.stopInventory();
                Log.i(TAG, "READ_TEST: 未检测到唯一单张标签；快速盘点诊断共看到 " + all.size() + " 个唯一 EPC：" + all);
                return;
            }
            Log.i(TAG, "READ_TEST: 检测到标签 EPC=" + nz(tag.epc) + " TID=" + nz(tag.tid));
            // TID（bank2, word0, 6 字）——出厂唯一，作物理身份证
            String tidHex = sdk.readTagSync(2, 0, 6, PWD, READ_TIMEOUT_MS);
            Log.i(TAG, "READ_TEST: TID  hex=" + tidHex + "  ascii=" + (tidHex != null ? FormatUtil.hexToAscii(tidHex) : ""));
            // EPC（bank1, word2, 8 字）——跳过前 2 字(CRC/PC)
            String epcHex = sdk.readTagSync(1, 2, 8, PWD, READ_TIMEOUT_MS);
            Log.i(TAG, "READ_TEST: EPC  hex=" + epcHex + "  ascii=" + (epcHex != null ? FormatUtil.hexToAscii(epcHex) : ""));
            // USER（bank3, word0, 16 字）
            String userHex = sdk.readTagSync(3, 0, 16, PWD, READ_TIMEOUT_MS);
            Log.i(TAG, "READ_TEST: USER hex=" + userHex + "  utf8=" + (userHex != null ? FormatUtil.hexToUtf8(userHex) : ""));
        }).start();
    }

    /** 写测试主体：检测唯一标签，按 TID/EPC 构造过滤条件，往 USER 区写测试串后回读校验 */
    private void doWriteTest() {
        if (!sdk.isConnected()) { Log.w(TAG, "WRITE_TEST: 仍未连接 UHF，跳过"); return; }
        sdk.setPower(Math.min(getParam("power", 30), 20)); // 写单张前调低功率，避免误写邻近标签
        new Thread(() -> {
            TagInfo tag = detectSingleTag(INVENTORY_WINDOW_MS);
            if (tag == null) {
                Log.w(TAG, "WRITE_TEST: 未检测到单张标签，跳过");
                return;
            }
            String[] f = buildFilter(tag);
            int fBank = Integer.parseInt(f[0]), fStartBit = Integer.parseInt(f[1]), fLenBits = Integer.parseInt(f[2]);
            String fData = f[3];
            // 固定前缀 + 6 位零填零时间戳，保证偶数字节、每次不同便于回读校验
            String testStr = String.format(java.util.Locale.ROOT, "RFIDTEST%06d", System.currentTimeMillis() % 1000000);
            boolean ok = sdk.writeTagWithFilterSync(
                    fBank, fStartBit, fLenBits, fData,
                    3, 0, testStr, PWD, WRITE_TIMEOUT_MS);
            Log.i(TAG, "WRITE_TEST: 写入 ok=" + ok + "  测试串=" + testStr);
            if (ok) {
                byte[] bytes = testStr.getBytes(StandardCharsets.UTF_8);
                int wlen = (bytes.length + 1) / 2;
                String rHex = sdk.readTagSync(3, 0, wlen, PWD, READ_TIMEOUT_MS);
                String rStr = rHex != null ? FormatUtil.hexToUtf8(rHex) : "";
                boolean match = rStr.startsWith(testStr);
                Log.i(TAG, "WRITE_TEST: 回读 hex=" + rHex + "  回读utf8=" + rStr + "  校验通过=" + match);
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        if (mTestReceiver != null) {
            try { unregisterReceiver(mTestReceiver); } catch (Throwable ignore) { }
            mTestReceiver = null;
        }
        super.onDestroy();
    }

    /** 物理返回键：逐级返回（首页再按一次退出） */
    @Override
    public void onBackPressed() {
        int p = parentOf(currentScreen);
        if (p >= 0) showScreen(p);
        else super.onBackPressed();
    }

    /**
     * 设备手柄按键（扳机/扫描键）路由：
     *  - 仅在「读取标签」页：按一下 = 读取一次单张标签；
     *  - 仅在「盘点」页：按一下 = 开始读取；再按 = 暂停；再按 = 继续（start/pause 切换）；
     *  - 首次在这两个页面按下候选键之一时，自动记住该 keyCode 作为手柄触发键（持久化），
     *    之后只认该键，避免误触其它按键。未知键会在 logcat 打印 keyCode 便于排查。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if ((currentScreen == SCREEN_READ || currentScreen == SCREEN_INVENTORY)
                && handleHandleKey(e)) {
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /**
     * 手柄触发键统一处理：学习键码 + 触发对应页动作。
     * 被 Activity.dispatchKeyEvent 与盘点弹窗的 OnKeyListener 共用，
     * 以解决「弹窗打开后按键被 Dialog 截获、导致第二次按键无法暂停盘点」的问题。
     * 返回 true 表示已消费该按键。
     */
    private boolean handleHandleKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        int code = e.getKeyCode();
        Log.i(TAG, "KEY keyCode=" + code + " action=" + e.getAction() + " screen=" + currentScreen);
        // 尚未学习到手柄键：按下任意非系统键即记住它（兼容各机型扳机映射）
        if (mHandleKey == 0 && !SYSTEM_KEYS_NO_LEARN.contains(code)) {
            mHandleKey = code;
            prefs.edit().putInt("handleKey", code).apply();
            Log.i(TAG, "HANDLE_KEY learned=" + code);
            Toast.makeText(this, "已记住手柄按键（keyCode=" + code + "）", Toast.LENGTH_SHORT).show();
        }
        if (code != mHandleKey) return false;
        if (currentScreen == SCREEN_READ) {
            readFullTag();
        } else {
            toggleInventoryFromHandle();
        }
        return true;   // 消费，避免系统重复处理（如 F1 默认行为）
    }

    /** 盘点页手柄键逻辑：未开始→开始；进行中→暂停/继续切换 */
    private void toggleInventoryFromHandle() {
        if (locating) { Toast.makeText(this, "定位进行中，请先停止定位", Toast.LENGTH_SHORT).show(); return; }
        if (!inventorying) {
            startInventorySession();
        } else if (invPaused) {
            resumeInventory();
        } else {
            pauseInventory();
        }
    }

    // ----------------- 导入台账（Excel/CSV）与模板 -----------------

    private void pickLedger() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_PICK_LEDGER);
    }

    /** 导出批量导入 Excel 模板（.xlsx）到公共「下载/RFID盘点」文件夹 */
    private void exportTemplate() {
        try {
            byte[] xlsx = FormatUtil.buildTemplateXlsx();
            String where = saveToDownloads("asset-import-template.xlsx", xlsx);
            if (where != null) {
                Toast.makeText(this, "模板已保存到手机「下载/RFID盘点」文件夹：\nasset-import-template.xlsx"
                        + "\n传到电脑 Excel 填写后，再点「导入Excel/CSV」选择导入", Toast.LENGTH_LONG).show();
            } else {
                java.io.File dir = getExternalFilesDir(null);
                if (dir == null) dir = getFilesDir();
                java.io.File f = new java.io.File(dir, "asset-import-template.xlsx");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) { fos.write(xlsx); }
                Toast.makeText(this, "模板已导出:\n" + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
            }
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
        notifyList();
        refreshProgress();
        Toast.makeText(this, "导入 " + parsed.size() + " 条台账（编码为空的已自动生成）", Toast.LENGTH_SHORT).show();
    }

    private void notifyList() { if (adapter != null) adapter.notifyDataSetChanged(); }

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

        int power = getParam("power", 30);
        int infoBank = getParam("bank", 3);      // 信息写入区：默认 USER(3)
        int infoStart = getParam("startWord", 0); // 信息起始字地址：默认 0
        sdk.setPower(Math.min(power, 20)); // 写单张前调低功率，避免误写邻近标签

        writing = true;
        if (btnStartWrite != null) btnStartWrite.setEnabled(false);
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
            if (btnStartWrite != null) btnStartWrite.setEnabled(true);
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

    /** 低功率短窗口盘点，收集天线范围内的【唯一】标签；返回该标签，否则返回 null（原因见 detectFailReason） */
    private TagInfo detectSingleTag(long windowMs) {
        // 盘点会话进行中绝不允许单读/写入抢占：detectSingleTag 会覆盖共享回调并停止群读，
        // 导致盘点计数卡死（回调被换成单读回调，标签进来也被静默丢弃）
        if (inventorying) {
            detectFailReason = "盘点进行中：请先点「结束盘点」，再使用读取/写入功能";
            runOnUiThread(() -> Toast.makeText(this, detectFailReason, Toast.LENGTH_LONG).show());
            return null;
        }
        Map<String, Integer> seen = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(1);
        sdk.setInventoryMode(1);   // 确保开启连续盘点模式（否则 startInventoryTag 不回传标签）
        sdk.startInventory(new RfidSdk.InventoryCallback() {
            @Override public void onTag(TagInfo t) {
                if (t.epc != null && !t.epc.isEmpty()) seen.merge(t.epc, 1, Integer::sum);
            }
            @Override public void onEnd() { latch.countDown(); }
        });
        try { latch.await(windowMs, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        sdk.stopInventory();
        Log.i(TAG, "detectSingleTag: 窗口=" + windowMs + "ms 共看到唯一EPC=" + seen.size() + " " + seen.keySet());
        if (seen.isEmpty()) {
            detectFailReason = "未检测到标签（天线范围内没有可读标签，请确认已点「连接UHF」且标签在天线范围内）";
            return null;
        }
        // 取读取次数最多的那张作为锁定目标：单标签正常；多标签时也直接锁定一张继续读取/写入，
        // 不再整体放弃（满足“多个标签也能读出其中一张”的实际使用场景）
        String bestEpc = null; int bestCnt = -1;
        for (java.util.Map.Entry<String, Integer> e : seen.entrySet()) {
            if (e.getValue() > bestCnt) { bestCnt = e.getValue(); bestEpc = e.getKey(); }
        }
        TagInfo r = new TagInfo();
        r.epc = bestEpc;
        if (seen.size() > 1) {
            detectFailReason = "附近检测到 " + seen.size() + " 张标签，已自动锁定读取次数最多的一张（EPC=" + bestEpc
                    + "）；如只想要这一张，请把其他标签移开天线范围";
        } else {
            detectFailReason = "";
        }
        return r;
    }

    // ----------------- 单张标签写入 -----------------

    /** 检测天线范围内【唯一】一张标签，并在界面显示其 TID/EPC（不写入） */
    private void detectSingle() {
        if (!sdk.isConnected()) { Toast.makeText(this, "请先连接 UHF", Toast.LENGTH_SHORT).show(); return; }
        sdk.setPower(getParam("power", 30));
        new Thread(() -> {
            TagInfo tag = detectSingleTag(INVENTORY_WINDOW_MS);
            lastDetectedTag = tag;
            runOnUiThread(() -> {
                if (tag == null) {
                    tvDetected.setText(detectFailReason.isEmpty()
                            ? "检测到的标签：无（请确保天线范围内仅一张标签）"
                            : "检测到的标签：无（" + detectFailReason + "）");
                } else {
                    tvDetected.setText("检测到的标签：EPC=" + nz(tag.epc)
                            + (tag.tid != null && !tag.tid.isEmpty() ? "  TID=" + tag.tid : "")
                            + (detectFailReason.isEmpty() ? "" : "\n(" + detectFailReason + ")"));
                }
            });
        }).start();
    }

    /**
     * 读取标签页：检测天线范围内【唯一】一张标签，并读取 TID/EPC/USER 三区，
     * 在界面展示解析结果，同时缓存到 lastReadInfo 供「导出读取数据」使用。
     */
    private void readFullTag() {
        if (reading) return;                       // 防止手柄连按并发读取
        if (!sdk.isConnected()) { Toast.makeText(this, "请先连接 UHF", Toast.LENGTH_SHORT).show(); return; }
        reading = true;
        setStatus("正在读取标签…");
        sdk.setPower(getParam("power", 30));
        new Thread(() -> {
            try {
                TagInfo tag = detectSingleTag(INVENTORY_WINDOW_MS);
                lastDetectedTag = tag;
                if (tag == null) {
                    runOnUiThread(() -> {
                        if (tvDetected != null)
                            tvDetected.setText("检测到的标签：无（请确认已点「连接UHF」，且天线范围内【仅一张】标签；\n多张会判定为多标签而放弃读取）");
                        if (tvEpc != null) tvEpc.setText("—");
                        if (tvTid != null) tvTid.setText("—");
                        if (tvUser != null) tvUser.setText("—");
                        setStatus("读取完成：" + (detectFailReason.isEmpty() ? "未检测到唯一单张标签" : detectFailReason));
                    });
                    return;
                }
                // 用检测到的标签构造过滤条件，锁定只读取这一张，避免多标签时误读其他标签
                String[] f = buildFilter(tag);
                int fBank = Integer.parseInt(f[0]);
                int fStartBit = Integer.parseInt(f[1]);
                int fLenBits = Integer.parseInt(f[2]);
                String fData = f[3];
                String tid = sdk.readTagSync(2, 0, 6, PWD, READ_TIMEOUT_MS, fData, fBank, fStartBit, fLenBits);
                String epc = sdk.readTagSync(1, 2, 8, PWD, READ_TIMEOUT_MS, fData, fBank, fStartBit, fLenBits);
                String user = sdk.readTagSync(3, 0, 16, PWD, READ_TIMEOUT_MS, fData, fBank, fStartBit, fLenBits);
                String userUtf8 = user != null ? FormatUtil.hexToUtf8(user) : "";
                lastReadInfo = new java.util.HashMap<>();
                lastReadInfo.put("epc", nz(tag.epc));
                lastReadInfo.put("tid", nz(tid));
                lastReadInfo.put("userHex", nz(user));
                lastReadInfo.put("userUtf8", nz(userUtf8));
                lastReadInfo.put("readAt", nowIso());
                String disp = "EPC  : " + nz(tag.epc)
                        + "\nTID  : " + nz(tid)
                        + "\nUSER : " + nz(user)
                        + (userUtf8.isEmpty() ? "" : "\nUSER(解析): " + userUtf8);
                runOnUiThread(() -> {
                    if (tvDetected != null)
                        tvDetected.setText("检测到的标签：EPC=" + nz(tag.epc)
                                + (nz(tid).isEmpty() ? "" : "  TID=" + tid)
                                + (detectFailReason.isEmpty() ? "" : "\n(" + detectFailReason + ")"));
                    if (tvReadData != null) tvReadData.setText(disp);
                    if (tvEpc != null) tvEpc.setText(nz(tag.epc));
                    if (tvTid != null) tvTid.setText(nz(tid));
                    if (tvUser != null) tvUser.setText(nz(user) + (userUtf8.isEmpty() ? "" : "\nUSER(解析): " + userUtf8));
                    setStatus("读取完成（TID/EPC/USER 已读，可点「导出读取数据」）");
                });
            } finally {
                reading = false;
            }
        }).start();
    }

    /** 导出最近一次读取结果（TID/EPC/USER）为 JSON 文件 */
    private void exportRead() {
        if (lastReadInfo == null) {
            Toast.makeText(this, "请先「检测并读取标签」", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject(lastReadInfo);
            byte[] data = o.toString(2).getBytes(StandardCharsets.UTF_8);
            String name = "rfid-read-" + new SimpleDateFormat("yyyyMMddHHmmss", Locale.CHINA).format(new Date()) + ".json";
            String where = saveToDownloads(name, data);
            Toast.makeText(this, where != null ? ("已导出:\n" + where) : ("已导出:\n" + name), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败:" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 单张写标共享流程：检测唯一标签 -> EPC 写编码 -> USER 写全部信息 -> 落库刷新 */
    private void writeOneInBackground(WriteResult wr) {
        if (writing) return;
        writing = true;
        if (btnWriteSingle != null) btnWriteSingle.setEnabled(false);
        new Thread(() -> {
            String resultMsg;
            try {
            int infoBank = getParam("bank", 3);
            int infoStart = getParam("startWord", 0);
            sdk.setPower(Math.min(getParam("power", 30), 20)); // 写单张前调低功率，避免误写邻近标签

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
                if (btnWriteSingle != null) btnWriteSingle.setEnabled(true);
                notifyList();
                refreshProgress();
                if (tvSingleResult != null) tvSingleResult.setText("单张写入结果：" + msg);
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
        if (tvInvCount != null) tvInvCount.setText("0 个");
        if (tvLastEpc != null) tvLastEpc.setText("—");
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
        // 规避切电：不再调用 setInventoryMode(0)，保留连续盘点模式，避免把共用模组留在降级态。
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
                if (!inventorying || invPaused || t.epc == null || t.epc.isEmpty()) {
                    Log.i(TAG, "onTag 丢弃: epc=" + t.epc + " inventorying=" + inventorying + " invPaused=" + invPaused);
                    return;
                }
                int[] s = invStats.computeIfAbsent(t.epc, k -> new int[]{0, -120});
                s[0]++;
                int r = Math.round(parseRssi(t.rssi));
                if (r > s[1]) s[1] = r;
                if (tvInvCount != null) tvInvCount.setText(invStats.size() + " 个");
                if (tvLastEpc != null) tvLastEpc.setText(t.epc);
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
        // 占据弹窗内剩余高度，保证列表可滚动且下方按钮始终可见
        lv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        lv.setOnItemClickListener((p, v, pos, id) -> {
            String epc = invEpcs.get(pos);
            // 暂停盘点读取（保留会话与统计），打开定位；定位关闭后会自动继续盘点
            if (inventorying && !invPaused) {
                invPaused = true;
                sdk.stopInventory();
                refreshInventoryViews();
            }
            locateFromInventory = true;
            locateTag(epc);
        });

        TextView tip = new TextView(this);
        tip.setText("点击任意标签 → 雷达定位（定位关闭后自动回到本盘点继续累加）；同一 EPC 自动合并计数；也可先暂停盘点再去别处");
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
                .setNegativeButton("结束盘点", (dlg, w) -> endInventory())
                .create();
        invDialog = d;
        // 弹窗打开后，物理扳机键事件会被 Dialog 截获（不再经过 Activity.dispatchKeyEvent），
        // 因此这里单独监听弹窗按键，复用 handleHandleKey，恢复「第2下暂停 / 第3下继续」。
        d.setOnKeyListener((dialog, keyCode, event) -> handleHandleKey(event));
        d.setOnDismissListener(di -> {
            uiHandler.removeCallbacks(invRefreshTask);
            endInventory();
            dlgBtnPause = null;
        });
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
        if (invDialog != null && invDialog.isShowing()
                && invDialog.getWindow() != null
                && invDialog.getWindow().getDecorView().isAttachedToWindow()) {
            try {
                invDialog.setTitle((invPaused ? "已暂停：" : (inventorying ? "盘点中：" : "盘点结束："))
                        + invStats.size() + " 张标签");
                if (dlgBtnPause != null) {
                    dlgBtnPause.setText(invPaused ? "继续盘点" : "暂停盘点");
                }
                invAdapter.notifyDataSetChanged();
            } catch (IllegalArgumentException ex) {
                Log.w(TAG, "refreshInventoryViews skip (decor detached): " + ex.getMessage());
            }
        }
    }

    /** 保存盘点结果 Excel（.xlsx，已登记条目附带资产名称）——直接存入公共「下载/RFID盘点」文件夹 */
    private void saveInventoryExcel() {
        if (invStats.isEmpty()) { Toast.makeText(this, "暂无盘点数据", Toast.LENGTH_SHORT).show(); return; }
        try {
            String fileName = "inventory-"
                    + new SimpleDateFormat("yyyyMMddHHmmss", Locale.CHINA).format(new Date()) + ".xlsx";
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
            byte[] xlsx = FormatUtil.buildTableXlsx("盘点结果", headers, rows);
            String where = saveToDownloads(fileName, xlsx);
            if (where == null) {
                // 公共目录不可写（老系统未授权等），退回应用目录并提示
                java.io.File dir = getExternalFilesDir(null);
                if (dir == null) dir = getFilesDir();
                java.io.File f = new java.io.File(dir, fileName);
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) { fos.write(xlsx); }
                Toast.makeText(this, "已保存到应用目录:\n" + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "已保存到手机「下载/RFID盘点」文件夹：\n" + fileName
                        + "\n文件管理 或 微信聊天→文件 里都能直接找到发送", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "保存失败:" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 标签定位：雷达图 + 信号值(0-254) + LED 提示，循环短盘点读取目标 RSSI */
    private void locateTag(String epc) {
        if (locating) { Toast.makeText(this, "已在定位中", Toast.LENGTH_SHORT).show(); locateFromInventory = false; return; }
        if (!sdk.isConnected()) { Toast.makeText(this, "请先连接 UHF", Toast.LENGTH_SHORT).show(); locateFromInventory = false; return; }
        // 若本次定位由盘点发起：仅暂停盘点读取（不结束会话），定位关闭后自动回盘点继续
        if (inventorying && !locateFromInventory) endInventory();

        locating = true;
        // 关键修复：定位必须开启连续盘点模式（inventoryModeSet(mode, flag=true) 才会回传标签）。
        // 之前误用 setInventoryMode(0)（=flag=false），导致 startInventoryTag 不回传标签，
        // 雷达永远停在“未捕捉到目标标签”。与正常盘点/单读保持一致，用 mode=1。
        sdk.setInventoryMode(1);
        final float txPower = getParam("power", 30); // 距离估算用的假设发射功率（不再调 setPower，避免打崩 iUHF 服务）

        // —— 标签定位方向支持：启用指南针（加速度计+磁力计），用“信号最强时的设备朝向”锚定标签方位 ——
        mPeakRssi = -120f;
        mTagBearingDeg = Float.NaN;
        mBearingCalAz = loadBearingCal();   // 加载上次校准的正前基准（若有）
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (mSensorManager != null) {
            Sensor accel = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            Sensor mag = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (accel != null && mag != null) {
                mLocateSensorListener = new SensorEventListener() {
                    @Override public void onSensorChanged(SensorEvent e) {
                        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                            System.arraycopy(e.values, 0, mGravity, 0, 3);
                        } else if (e.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
                            System.arraycopy(e.values, 0, mGeomag, 0, 3);
                        }
                        if (SensorManager.getRotationMatrix(mRotMat, mInclMat, mGravity, mGeomag)) {
                            float[] o = new float[3];
                            SensorManager.getOrientation(mRotMat, o);
                            float newAz = (float) Math.toDegrees(o[0]); // 0=北，顺时针为正
                            // 低通滤波平滑罗盘抖动（按最短弧插值，避免 ±180° 跳变）
                            float diff = normalizeDeg(newAz - mDeviceAzimuthDeg);
                            mDeviceAzimuthDeg = normalizeDeg(mDeviceAzimuthDeg + diff * 0.25f);
                        }
                    }
                    @Override public void onAccuracyChanged(Sensor s, int a) {}
                };
                mSensorManager.registerListener(mLocateSensorListener, accel, SensorManager.SENSOR_DELAY_UI);
                mSensorManager.registerListener(mLocateSensorListener, mag, SensorManager.SENSOR_DELAY_UI);
            }
        }

        float density = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * density);
        root.setPadding(pad, pad, pad, 0);

        String calKey = epc.trim().toUpperCase();
        String calName = CAL_TAG_NAME.get(calKey);
        TextView tvInfo = new TextView(this);
        tvInfo.setText("EPC: " + epc + (calName != null ? "   · " + calName + "(标定:" + dirOf(CAL_TAG_ANGLE.get(calKey)) + ")" : ""));
        tvInfo.setTextSize(14);

        // 实时状态行：让“搜不到目标”不再表现为空白雷达，而是明确提示当前状态（诊断用）
        TextView tvLocStatus = new TextView(this);
        tvLocStatus.setId(android.view.View.generateViewId());
        tvLocStatus.setText("正在搜索目标标签…");
        tvLocStatus.setTextSize(13);
        tvLocStatus.setTextColor(0xFFFFEB3B);   // 黄：搜索中
        tvLocStatus.setPadding(0, (int) (4 * density), 0, 0);

        // 校准提示：显示目标是否为标定标签，以及正前基准是否已校准
        final TextView tvCalHint = new TextView(this);
        if (calName != null) {
            String dir = dirOf(CAL_TAG_ANGLE.get(calKey));
            tvCalHint.setText("检测到标定标签 " + calName + "（应位于设备" + dir + "）。"
                    + (Float.isNaN(mBearingCalAz) ? "请点【以此方向为正前校准】，或正对该标签近场自动校准。" : "正前基准已校准 ✓"));
        } else {
            tvCalHint.setText(Float.isNaN(mBearingCalAz)
                    ? "未校准正前基准：请把设备正前对准目标，点【以此方向为正前校准】；或定位 A1/B1/C1/D1 近场自动校准。"
                    : "正前基准已校准 ✓（可在远离时稳定指向标签）");
        }
        tvCalHint.setTextSize(12);
        tvCalHint.setTextColor(0xFF80D8FF);
        tvCalHint.setPadding(0, (int) (6 * density), 0, 0);

        // 手动校准按钮（蓝色）：把“当前设备朝向”锁定为正前基准并持久化
        final Button btnCalibrate = new Button(this);
        btnCalibrate.setText("以此方向为正前校准");
        btnCalibrate.setBackgroundColor(0xFF1976D2);
        btnCalibrate.setTextColor(0xFFFFFFFF);
        btnCalibrate.setOnClickListener(v -> {
            mBearingCalAz = mDeviceAzimuthDeg;
            saveBearingCal(mBearingCalAz);
            Toast.makeText(this, "已校准：当前方向设为正前基准", Toast.LENGTH_SHORT).show();
            tvCalHint.setText("正前基准已校准 ✓（可在远离时稳定指向标签）");
        });

        // 信号能量条（替代原“信号值”数字显示，蓝框需求：以能量条呈现信号强弱）
        android.widget.LinearLayout sigRow = new android.widget.LinearLayout(this);
        sigRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        sigRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int sigPad = (int) (6 * density);
        sigRow.setPadding(0, sigPad, 0, sigPad);

        TextView tvSigLabel = new TextView(this);
        tvSigLabel.setText("信号");
        tvSigLabel.setTextSize(13);
        tvSigLabel.setTextColor(0xFFB0BEC5);

        EnergyBar energyBar = new EnergyBar(this);
        int barH = (int) (20 * density);
        energyBar.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, barH, 1f));
        energyBar.setMinimumHeight(barH);

        TextView tvDbm = new TextView(this);
        tvDbm.setText("--");
        tvDbm.setTextSize(13);
        tvDbm.setTextColor(0xFFB0BEC5);
        tvDbm.setGravity(android.view.Gravity.END);
        tvDbm.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                (int) (82 * density), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        sigRow.addView(tvSigLabel);
        sigRow.addView(energyBar);
        sigRow.addView(tvDbm);

        RadarView radar = new RadarView(this);
        radar.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(300 * density)));

        // 停止定位（红钮）：停止本次定位循环，弹窗保持以便查看最终状态
        Button btnStop = new Button(this);
        btnStop.setText("停止定位");
        btnStop.setTextSize(14);
        btnStop.setBackgroundColor(0xFFD32F2F);
        btnStop.setTextColor(0xFFFFFFFF);
        btnStop.setOnClickListener(v -> { locating = false; btnStop.setEnabled(false); btnStop.setText("已停止"); });

        // “以此方向为正前校准”改为小按钮（不再通栏大蓝钮），与停止定位并排
        btnCalibrate.setTextSize(12);
        btnCalibrate.setPadding((int)(10 * density), (int)(8 * density), (int)(10 * density), (int)(8 * density));

        // 关闭（文字钮）
        TextView btnClose = new TextView(this);
        btnClose.setText("关闭");
        btnClose.setTextSize(14);
        btnClose.setTextColor(0xFF1976D2);
        btnClose.setGravity(android.view.Gravity.CENTER);
        btnClose.setPadding((int)(18 * density), (int)(8 * density), (int)(18 * density), (int)(8 * density));

        // 底部按钮行：校准(小) + 停止定位(红) + 关闭，固定可见、必可按到
        android.widget.LinearLayout btnRow = new android.widget.LinearLayout(this);
        btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, (int)(8 * density), 0, 0);
        btnRow.addView(btnCalibrate, new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnRow.addView(btnStop, new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnRow.addView(btnClose, new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        // 可滚动内容区（信息+雷达），与底部按钮行分离，保证按钮始终可见可按
        android.widget.LinearLayout contentInner = new android.widget.LinearLayout(this);
        contentInner.setOrientation(android.widget.LinearLayout.VERTICAL);
        contentInner.addView(tvInfo);
        contentInner.addView(tvLocStatus);
        contentInner.addView(tvCalHint);
        contentInner.addView(sigRow);
        contentInner.addView(radar);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(contentInner);
        scroll.setLayoutParams(new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(scroll);
        root.addView(btnRow);
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this)
                .setTitle("标签定位")
                .setView(root)
                .create();
        d.setOnDismissListener(di -> {
            locating = false;
            sdk.setLedBlink(false);
            sdk.stopInventory();
            // 反注册指南针传感器，释放邻近提示音资源
            if (mSensorManager != null && mLocateSensorListener != null) {
                mSensorManager.unregisterListener(mLocateSensorListener);
                mLocateSensorListener = null;
            }
            if (mToneGen != null) { mToneGen.release(); mToneGen = null; }
            // 规避切电：不再调用 setInventoryMode(0)，保留连续盘点模式。
            // 定位是由盘点发起的：关闭后自动回到盘点会话继续（保留统计、累加读取）
            if (locateFromInventory) {
                locateFromInventory = false;
                if (inventorying && invPaused) {
                    invPaused = false;
                    sdk.setInventoryMode(1);
                    sdk.startInventory(makeInventoryCallback());
                    refreshInventoryViews();
                }
            }
        });
        d.show();
        btnClose.setOnClickListener(v -> d.dismiss());
        // 弹窗占较大比例屏幕，确保雷达与底部按钮都完整可见（避免“停止定位”被挤出/按不到）
        try {
            android.view.Display disp = getWindowManager().getDefaultDisplay();
            android.graphics.Point psz = new android.graphics.Point();
            disp.getSize(psz);
            d.getWindow().setLayout((int)(psz.x * 0.94), (int)(psz.y * 0.88));
        } catch (Exception ignore) {}

        // 单次开启连续盘点，回调里【只】更新 lastRssi / lastSeen 时间戳（绝不触碰电源、不切电、不调 stopInventory）；
        // 由 UI Handler 驱动重绘：距上次读到 < 2000ms 就显示 lastRssi（红点稳定停留），
        // 超过 2 秒静默才回退“未捕捉到目标标签”——实现信号保持 + 优雅缓降，远离时红点平滑淡出。
        final float[] lastRssi = {-120f};
        final java.util.concurrent.atomic.AtomicLong lastSeen = new java.util.concurrent.atomic.AtomicLong(0);
        final long LOCATE_HOLD_MS = 2000L;                   // 信号保持窗口：2 秒无新读数才判定丢失
        final boolean[] framePending = {false};             // 防止 onTag 高频重绘排队
        final boolean[] matchedOnce = {false};              // 是否已命中目标标签（用于状态提示）
        final int[] mLocSeenCount = {0};                   // 定位期间收到的标签回调计数（诊断用）

        // 每帧重绘：根据最近读数刷新雷达/能量条/信号文本，并把“标签方位−设备朝向”换算成红点相对角度
        final Runnable applyLocateFrame = new Runnable() {
            @Override public void run() {
                if (!locating) return;
                long now = System.currentTimeMillis();
                long since = (lastSeen.get() == 0) ? Long.MAX_VALUE : (now - lastSeen.get());
                final float fr = (since >= LOCATE_HOLD_MS) ? -120f : lastRssi[0];
                if (fr <= -120f) {
                    radar.setRssiDbm(fr, Float.NaN);
                    energyBar.setLevel(0f);
                    tvDbm.setText("--");
                    tvDbm.setTextColor(0xFFB0BEC5);
                    tvLocStatus.setText("搜索中… 已读到 " + mLocSeenCount[0] + " 个标签，未命中目标");
                    tvLocStatus.setTextColor(0xFFFFEB3B);
                } else {
                    float dist = FormatUtil.estimateDistanceMeters(fr, txPower);
                    radar.setRssiDbm(fr, dist);
                    int sig = Math.max(0, Math.min(254, Math.round((fr + 90f) * 254f / 60f)));
                    energyBar.setLevel(sig / 254f);
                    if (sig >= 220) {
                        tvDbm.setText(Math.round(fr) + " dBm · 近");
                        tvDbm.setTextColor(0xFF69F0AE);
                    } else {
                        tvDbm.setText(Math.round(fr) + " dBm");
                        tvDbm.setTextColor(0xFFB0BEC5);
                    }
                    // 方位：标签锚定方位 − 设备当前朝向 → 相对角度（红点绕圈，转到正上方即正对标签）
                    if (!Float.isNaN(mTagBearingDeg)) {
                        // 相对【校准后的正前基准】而非实时朝向换算，转动设备时红点稳定指向标签
                        float base = Float.isNaN(mBearingCalAz) ? mDeviceAzimuthDeg : mBearingCalAz;
                        float rel = -90f + normalizeDeg(mTagBearingDeg - base);
                        radar.setBearing(rel);

                        // —— 基准正确性诊断（节流 400ms）：打印换算全链路数值，验证设备转动时红点是否指向真实相对方向 ——
                        long dnow = System.currentTimeMillis();
                        if (dnow - mLastDiagMs > 400) {
                            mLastDiagMs = dnow;
                            float raw = normalizeDeg(mTagBearingDeg - base);   // 指南针式相对角：0=前,90=右,180=后,-90=左
                            Log.d("BASELINE", String.format(java.util.Locale.US,
                                "tag=%s calAz=%.1f tagB=%.1f devAz=%.1f base=%.1f rawRel=%.1f radarRel=%.1f computedDir=%s",
                                (calName != null ? calName : epc), mBearingCalAz, mTagBearingDeg,
                                mDeviceAzimuthDeg, base, raw, rel, dirOf(raw)));
                        }
                    }
                    if (matchedOnce[0]) {
                        tvLocStatus.setText("已锁定 ✓  RSSI " + Math.round(fr) + " dBm · 距 " + FormatUtil.formatDistance(dist));
                        tvLocStatus.setTextColor(0xFF69F0AE);   // 绿：已锁定
                    }
                }
            }
        };

        final Runnable locateTick = new Runnable() {
            @Override public void run() {
                if (!locating) return;                       // 已停止定位（关闭/停止按钮）→ 停止重绘，不再续排
                applyLocateFrame.run();
                uiHandler.postDelayed(this, 50);             // 50ms 节流重绘（20fps），红点停留明显变长且跟手
            }
        };

        // 邻近声音提示：越近（RSSI 越大）提示音越急促，越远（RSSI 越小）越平缓
        try {
            mToneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 85);
        } catch (RuntimeException ex) {
            Log.w(TAG, "ToneGenerator init failed, 邻近提示音不可用: " + ex.getMessage());
            mToneGen = null;
        }
        final Runnable audioTick = new Runnable() {
            @Override public void run() {
                if (!locating || mToneGen == null) return;
                float r = lastRssi[0];
                if (r > -120f) {
                    try {
                        mToneGen.startTone(ToneGenerator.TONE_CDMA_PIP, 55);
                    } catch (RuntimeException ignore) { /* 音频瞬间不可用，忽略 */ }
                    uiHandler.postDelayed(this, rssiToBeepInterval(r));
                } else {
                    uiHandler.postDelayed(this, 1200);       // 未捕捉：极慢静默节拍
                }
            }
        };

        // 定位读取回调（抽成可复用变量，供 600ms 自愈重试复用，避免重复实现）
        final RfidSdk.InventoryCallback locateCb = new RfidSdk.InventoryCallback() {
            @Override public void onTag(TagInfo t) {
                mLocSeenCount[0]++;
                // 大小写/空格不敏感匹配，避免 EPC 格式差异（hex 大小写、前后空格）导致“永远匹配不上”
                boolean match = epcEq(epc, t.epc);
                if (match) {
                    matchedOnce[0] = true;
                    float r = parseRssi(t.rssi);
                    lastSeen.set(System.currentTimeMillis());      // 命中目标标签：刷新“最后见到”时间，红点保持不灭
                    if (r > -120f) {
                        lastRssi[0] = r;                          // 有效 RSSI：更新读数（驱动距离估算 + 红点实时跳动）
                        // 自动校准：目标为标定标签且处于近场(>-80dBm，与方位锚定同门限)时，依其已知方位反推正前基准并持久化。
                        // 注：20CM 等近距离下 RSSI 因天线主瓣方向性差异可达 -50~-70，若阈值过严(如-55)会导致多数标定标签永不触发自动校准，
                        // 从而 base 回退到实时朝向、所有标签都被算成“正前”，丧失 4 方位区分能力。故与锚定门限保持一致用 -80。
                        String aCalKey = (t.epc == null) ? "" : t.epc.trim().toUpperCase();
                        if (CAL_TAG_ANGLE.containsKey(aCalKey) && r > -80f) {
                            long acNow = System.currentTimeMillis();
                            if (acNow - mLastAutoCalMs > 1000) {   // 节流：1 秒内最多校准一次
                                mLastAutoCalMs = acNow;
                                mBearingCalAz = normalizeDeg(mDeviceAzimuthDeg - CAL_TAG_ANGLE.get(aCalKey));
                                saveBearingCal(mBearingCalAz);
                                Log.d("BASELINE", String.format(java.util.Locale.US,
                                    "AUTOCAL tag=%s defAngle=%.0f devAz=%.1f -> calAz=%.1f",
                                    CAL_TAG_NAME.get(aCalKey), CAL_TAG_ANGLE.get(aCalKey), mDeviceAzimuthDeg, mBearingCalAz));
                                uiHandler.post(() -> {
                                    if (tvCalHint != null)
                                        tvCalHint.setText("已自动校准正前基准 ✓（依据 " + CAL_TAG_NAME.get(aCalKey) + " 方位）");
                                });
                            }
                        }
                        // 信号增强则把“当前设备朝向”重新锚定为标签方位（红点随之绕圈指向标签）
                        if (r > -80f && r > mPeakRssi) {   // 仅近场(>-80dBm)才重新锚定方位，避免远场误锁
                            mPeakRssi = r;
                            mTagBearingDeg = mDeviceAzimuthDeg;
                        }
                    } else if (lastRssi[0] <= -120f) {
                        // 本帧 RSSI 缺失（iUHF 缓存偶发“EPC 不带 RSSI”），但标签确已命中：
                        // 给一个保守近场默认值，立即显示红点，并锚定当前朝向
                        lastRssi[0] = -55f;
                        if (Float.isNaN(mTagBearingDeg)) mTagBearingDeg = mDeviceAzimuthDeg;
                    }
                    // 已有有效读数时本帧缺失不覆盖，红点保持稳定
                    // 即时刷新（不等 50ms tick），消除信号读取延迟感
                    if (!framePending[0]) {
                        framePending[0] = true;
                        uiHandler.post(() -> { framePending[0] = false; applyLocateFrame.run(); });
                    }
                } else if (mLocSeenCount[0] <= 5) {
                    // 诊断：前几次非命中样本打印，便于排查 EPC 格式不一致
                    Log.i(TAG, "locate 收到非目标标签 epc=" + t.epc + " (目标=" + epc + ")");
                }
            }
            @Override public void onEnd() {}
        };

        sdk.startInventory(locateCb);

        // 自愈：iUHF 的 startInventoryTag 在“从盘点切到定位”时偶发不生效（原生会话未真正重启），
        // 表现为轮询线程在跑却收不到任何标签回调。若 600ms 内一条回调都没有，自动重启一次定位盘点。
        uiHandler.postDelayed(() -> {
            if (locating && mLocSeenCount[0] == 0) {
                Log.w(TAG, "locate 自愈：600ms 内无标签回调，重启定位盘点");
                try { sdk.stopInventory(); } catch (Throwable ignore) {}
                sdk.startInventory(locateCb);
            }
        }, 600);

        uiHandler.post(locateTick);
        uiHandler.post(audioTick);

        // 诊断：记录定位启动（目标 EPC + 是否由盘点发起），便于用 logcat 排查“搜不到标签”
        Log.i(TAG, "locateTag 启动: epc=" + epc + " fromInventory=" + locateFromInventory
                + " inventorying=" + inventorying + " invPaused=" + invPaused);

        // 3 秒仍未命中目标：给出明确提示（而非空白雷达），引导用户靠近 / 确认标签在范围内
        uiHandler.postDelayed(() -> {
            if (locating && !matchedOnce[0]) {
                tvLocStatus.setText("⚠ 3 秒内未检测到该标签：请靠近标签，或确认它仍在天线范围内");
                tvLocStatus.setTextColor(0xFFFF5252);   // 红：告警
            }
        }, 3000);
    }

    private static float parseRssi(String s) {
        try { return Float.parseFloat(s); } catch (Exception e) { return -120f; }
    }

    /** 大小写 / 空格不敏感的 EPC 比较（hex 大小写、前后空格差异不应导致匹配失败） */
    private static boolean epcEq(String a, String b) {
        if (a == null || b == null) return false;
        return a.trim().equalsIgnoreCase(b.trim());
    }

    /** 把角度归一化到 [-180, 180) */
    private static float normalizeDeg(float deg) {
        float d = deg % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    /** 邻近提示音间隔(ms)：信号越强(越近)间隔越短→越急促；-40dBm≈130ms，-85dBm≈1200ms */
    private static long rssiToBeepInterval(float rssi) {
        float close = Math.max(0f, Math.min(1f, (rssi - (-85f)) / ((-40f) - (-85f))));
        long ms = Math.round(1200f - close * (1200f - 130f));
        return Math.max(130L, ms);
    }

    // 正前基准（罗盘角）持久化：跨会话保留校准结果，下次打开定位直接可用
    private void saveBearingCal(float az) {
        try {
            SharedPreferences sp = getPreferences(android.content.Context.MODE_PRIVATE);
            sp.edit().putFloat("bearing_cal_az", az).apply();
        } catch (Throwable ignore) {}
    }
    private float loadBearingCal() {
        try {
            SharedPreferences sp = getPreferences(android.content.Context.MODE_PRIVATE);
            return sp.getFloat("bearing_cal_az", Float.NaN);
        } catch (Throwable ignore) { return Float.NaN; }
    }
    /** 把方位角(度)换算成中文 8 方位（前/前右/右/后右/后/后左/左/前左），用于标定标签提示 */
    private static String dirOf(float a) {
        float n = normalizeDeg(a); // [-180,180)
        if (n >= -22.5f && n < 22.5f) return "前";
        if (n >= 22.5f && n < 67.5f) return "前右";
        if (n >= 67.5f && n < 112.5f) return "右";
        if (n >= 112.5f && n < 157.5f) return "后右";
        if (n >= 157.5f || n < -157.5f) return "后";
        if (n >= -157.5f && n < -112.5f) return "后左";
        if (n >= -112.5f && n < -67.5f) return "左";
        return "前左";
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
                        notifyList();
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
            byte[] data;
            try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
                data = bos.toByteArray();
            }
            String where = saveToDownloads(f.getName(), data);
            if (where != null) {
                Toast.makeText(this, "已保存到手机「下载/RFID盘点」文件夹：\n" + f.getName(), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "已导出:\n" + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
            }
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
            notifyList();
            refreshProgress();
        });
    }

    private void refreshProgress() {
        int succ = db.countByStatus("success");
        int fail = db.countByStatus("failed");
        tvProgress.setText("进度：成功 " + succ + " / 失败 " + fail + " / 共 " + ledger.size());
    }

    /**
     * 把导出文件直接写入公共「下载/RFID盘点」文件夹（所有文件管理器/微信都能看到）。
     * 成功返回给用户展示的路径；失败返回 null（调用方退回应用专属目录）。
     */
    private String saveToDownloads(String fileName, byte[] data) {
        try {
            String downPath;
            if (Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName);
                cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, guessMime(fileName));
                cv.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/RFID盘点");
                cv.put(android.provider.MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return null;
                try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(data);
                }
                cv.clear();
                cv.put(android.provider.MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(uri, cv, null, null);
                downPath = "下载/RFID盘点/" + fileName;
            } else {
                java.io.File dir = new java.io.File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "RFID盘点");
                if (!dir.exists() && !dir.mkdirs()) return null;
                java.io.File f = new java.io.File(dir, fileName);
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) { fos.write(data); }
                downPath = f.getAbsolutePath();
            }
            // 顺便复制一份到模拟器/设备的「共享文件夹」（雷电 LDPlayer 的 /mnt/shared），
            // 这样 PC 端共享目录能直接看到，无需再进安卓文件管理。真机无此目录则静默跳过。
            String shared = copyToShared(fileName, data);
            if (shared != null) {
                return downPath + "\n（已同步到雷电共享文件夹，电脑端可直接取用）";
            }
            return downPath;
        } catch (Exception e) {
            Log.w(TAG, "saveToDownloads failed: " + e);
            return null;
        }
    }

    /** 尝试把文件复制到 /mnt/shared（雷电等模拟器的 PC 共享目录）；不存在则跳过 */
    private String copyToShared(String fileName, byte[] data) {
        String[] roots = {"/mnt/shared/Download", "/mnt/shared"};
        for (String r : roots) {
            java.io.File d = new java.io.File(r);
            if (d.isDirectory()) {
                try {
                    java.io.File f = new java.io.File(d, fileName);
                    try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(data); }
                    return f.getAbsolutePath();
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static String guessMime(String name) {
        if (name.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".csv")) return "text/csv";
        return "application/octet-stream";
    }

    private void setStatus(String s) {
        if (tvStatus != null) tvStatus.setText("状态：" + s);
        if (tvBadge == null) return;
        boolean connected = s != null && s.contains("已连接");
        boolean disconnected = s != null && (s.contains("未连接") || s.contains("断开"));
        int bg = connected ? R.color.badge_connected_bg : (disconnected ? R.color.badge_disconnected_bg : R.color.pending);
        int fg = connected ? R.color.badge_connected_fg : (disconnected ? R.color.badge_disconnected_fg : R.color.text_primary);
        tvBadge.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(bg)));
        tvBadge.setTextColor(getColor(fg));
        tvBadge.setText(connected ? "● 已连接 UHF" : (disconnected ? "● 未连接" : "● " + s));
    }

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
