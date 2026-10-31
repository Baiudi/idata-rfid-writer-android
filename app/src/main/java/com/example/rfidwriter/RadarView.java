package com.example.rfidwriter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import com.example.rfidwriter.util.FormatUtil;

/**
 * 标签定位雷达图（Canvas 自绘，零依赖）。
 *
 * 设计要点（对照需求）：
 *  - 扫描线【持续旋转】，作为“正在扫描”的视觉提示；
 *  - 红色目标点【固定在表盘上的真实位置】，绝不随扫描线转圈：
 *      方位角 = 由指南针换算的“相对方位”（默认正前方 -90°，即 12 点方向）；
 *      半径   = 由实时 RSSI 估算的距离（信号越强/越近 → 越靠近圆心）。
 *    说明：iData UHF 模组只回传 RSSI（距离），不回传方向，因此“位置”用
 *          （相对方位 + 实时半径）表达。相对方位由 MainActivity 依据
 *          “标签锚定方位 − 设备当前朝向”传入（setBearing），转动设备时红点会绕圈，
 *          转到正上方（12 点）即表示【正对标签】，雷达同时显示中文方位提示（前/后/左/右）。
 *  - 顶部青色小三角表示【设备正前方】；红点与圆心连线指示标签相对方向；
 *  - 每次收到有效读数触发一次“脉冲”扩散环，强化“实时”观感；
 *  - 未捕捉到目标时红点淡出、表盘边缘显示空心搜索环，区分“搜素中/已锁定”。
 */
public class RadarView extends View {

    private float mBearingDeg = -90f; // 目标相对方位角（度）：-90=正前方(12点),0=正右,90=正后；由指南针动态设置
    private String mBearingHint = "前";
    private static final int TRAIL = 12;               // 扫描拖尾段数
    private static final long PING_MS = 650;           // 脉冲扩散时长

    private float mStrength = 0f;     // 0..1，1 = 信号最强（标签就在眼前）
    private float mSweepDeg = 0f;    // 扫描线当前角度（仅用于旋转动画）
    private String mDistance = "--";  // 估算距离文本（米）
    private boolean mHasTarget = false;
    private long mPingStart = 0;     // 上次有效读数的时间戳，用于脉冲动画

    private final Paint mPanel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPanelBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSweep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSweepTrail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDotGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDotCore = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSearch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFront = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);

    public RadarView(Context c) {
        super(c);

        mPanelBorder.setStyle(Paint.Style.STROKE);
        mPanelBorder.setStrokeWidth(3f);
        mPanelBorder.setColor(0x4D7CFC5C);              // 雷达外圈描边：半透明绿

        mRing.setStyle(Paint.Style.STROKE);
        mRing.setStrokeWidth(2f);
        mRing.setColor(0x4D7CFC5C);                     // 同心圆/十字线：半透明绿

        mSweep.setStrokeWidth(4f);
        mSweep.setColor(0xFF00E5FF);                    // 扫描线头：青色

        mSweepTrail.setStrokeWidth(3f);
        mSweepTrail.setColor(0x8000E5FF);               // 扫描拖尾：半透明青

        mDot.setStyle(Paint.Style.FILL);
        mDot.setColor(0xFFFF1744);                      // 目标红点
        mDotGlow.setStyle(Paint.Style.FILL);
        mDotGlow.setColor(0xFFFF1744);                  // 红点辉光（动态 alpha）
        mDotCore.setStyle(Paint.Style.FILL);
        mDotCore.setColor(0xFFFFFFFF);                  // 红点白芯

        mPing.setStyle(Paint.Style.STROKE);
        mPing.setStrokeWidth(3f);
        mPing.setColor(0xFFFF1744);                     // 脉冲扩散环

        mSearch.setStyle(Paint.Style.STROKE);
        mSearch.setStrokeWidth(2f);
        mSearch.setColor(0x556CFF6B);                   // 搜索空心环

        mText.setColor(0xFFB9F6CA);                     // 距离/方位文本：浅绿
        mText.setTextAlign(Paint.Align.CENTER);

        mFront.setStyle(Paint.Style.FILL);
        mFront.setColor(0xAA00E5FF);                    // 设备正前方指示：青

        mLine.setStyle(Paint.Style.STROKE);
        mLine.setStrokeWidth(2f);
        mLine.setColor(0x80FF1744);                     // 红点指向线：半透明红
    }

    /** 用 dBm 设置信号强度（-90..-30 映射到 0..1） */
    public void setRssiDbm(float dbm) {
        float s = (dbm + 90f) / 60f;
        mStrength = Math.max(0f, Math.min(1f, s));
        invalidate();
    }

    /** 设置信号强度并显示估算距离（米），distance 传 NaN 表示未捕捉到信号 */
    public void setRssiDbm(float dbm, float distanceMeters) {
        setRssiDbm(dbm);
        mHasTarget = !Float.isNaN(distanceMeters);
        mDistance = FormatUtil.formatDistance(distanceMeters);
        if (mHasTarget) mPingStart = System.currentTimeMillis(); // 触发脉冲
        invalidate();
    }

    /**
     * 设置目标相对方位角（度）：-90=正前方(12点),0=正右,90=正后；
     * 由 MainActivity 依据指南针（标签锚定方位 − 设备当前朝向）传入。
     * 同时换算出中文方位提示（前/后/左/右）叠加显示。
     */
    public void setBearing(float deg) {
        mBearingDeg = deg;
        double a = Math.toRadians(deg + 90f);   // 0 = 正前
        double x = Math.sin(a), y = Math.cos(a); // x:右+, y:前+
        StringBuilder sb = new StringBuilder();
        if (y >= 0) sb.append("前"); else sb.append("后");
        if (x > 0.35) sb.append("右"); else if (x < -0.35) sb.append("左");
        mBearingHint = sb.toString();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float cx = w / 2f, cy = h / 2f;
        float R = Math.min(cx, cy) - 12f;
        if (R <= 0) return;
        // 圆形表盘径向渐变（中心亮、边缘暗），去掉方块底色
        android.graphics.RadialGradient g = new android.graphics.RadialGradient(
                cx, cy, R, 0xFF0C1A12, 0xFF050C08, android.graphics.Shader.TileMode.CLAMP);
        mPanel.setShader(g);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float R = Math.min(cx, cy) - 12f;
        if (R <= 0) return;

        // 圆形表盘面板（不再是方块）
        canvas.drawCircle(cx, cy, R, mPanel);
        canvas.drawCircle(cx, cy, R, mPanelBorder);

        // 5 圈同心圆 + 十字线
        for (int i = 1; i <= 5; i++) canvas.drawCircle(cx, cy, R * i / 5f, mRing);
        canvas.drawLine(cx - R, cy, cx + R, cy, mRing);
        canvas.drawLine(cx, cy - R, cx, cy + R, mRing);

        // 设备正前方指示（顶部青色小三角）：红点相对此方向的夹角即标签方位
        Path front = new Path();
        front.moveTo(cx, cy - R + 6f);
        front.lineTo(cx - 9f, cy - R + 22f);
        front.lineTo(cx + 9f, cy - R + 22f);
        front.close();
        canvas.drawPath(front, mFront);

        // 旋转扫描线（带拖尾彗星效果）
        mSweepDeg = (mSweepDeg + 3.5f) % 360f;
        for (int i = 0; i < TRAIL; i++) {
            float a = mSweepDeg - i * 5f;
            double r = Math.toRadians(a);
            int al = (int) (150 * (1f - i / (float) TRAIL));
            mSweepTrail.setAlpha(al);
            canvas.drawLine(cx, cy,
                    cx + (float) Math.cos(r) * R,
                    cy + (float) Math.sin(r) * R, mSweepTrail);
        }
        double sr = Math.toRadians(mSweepDeg);
        canvas.drawLine(cx, cy,
                cx + (float) Math.cos(sr) * R,
                cy + (float) Math.sin(sr) * R, mSweep);

        // 目标点：相对方位角 + 实时半径（绝不随扫描旋转）
        double dotRad = Math.toRadians(mBearingDeg);
        if (mHasTarget && mStrength > 0.02f) {
            float dotR = (1f - mStrength) * R;          // 越近越靠近圆心
            float dx = cx + (float) Math.cos(dotRad) * dotR;
            float dy = cy + (float) Math.sin(dotRad) * dotR;

            // 红点指向线（圆心→红点，强调方向）
            mLine.setAlpha(160);
            canvas.drawLine(cx, cy, dx, dy, mLine);

            // 辉光
            mDotGlow.setAlpha((int) (110 + 90 * mStrength));
            canvas.drawCircle(dx, dy, 16f + 12f * (1f - mStrength), mDotGlow);

            // 脉冲扩散环（每次有效读数触发）
            long t = System.currentTimeMillis() - mPingStart;
            if (t >= 0 && t < PING_MS) {
                float p = t / (float) PING_MS;
                mPing.setAlpha((int) (210 * (1f - p)));
                canvas.drawCircle(dx, dy, 10f + p * R * 0.45f, mPing);
            }

            // 实心红点 + 白芯
            mDot.setAlpha(255);
            canvas.drawCircle(dx, dy, 10f, mDot);
            canvas.drawCircle(dx, dy, 4f, mDotCore);
        } else {
            // 搜索中：边缘空心环提示“尚未锁定”
            canvas.drawCircle(
                    cx + (float) Math.cos(dotRad) * R,
                    cy + (float) Math.sin(dotRad) * R, 8f, mSearch);
        }

        // 估算距离（圆形表盘内底部）
        mText.setTextSize(22f);
        canvas.drawText(mDistance, cx, cy + R * 0.72f, mText);
        // 方位提示（距离下方）
        mText.setTextSize(20f);
        canvas.drawText("方位 " + mBearingHint, cx, cy + R * 0.72f + 26f, mText);

        postInvalidateDelayed(30); // 持续动画
    }
}
