package com.example.rfidwriter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * 标签定位雷达图。
 * 同心圆 + 十字线 + 旋转扫描线（青色）+ 红色目标点。
 * 信号越强（RSSI 越接近 0），红点越靠近圆心；信号越弱点越靠外。
 * 零依赖，纯 Canvas 绘制。
 */
public class RadarView extends View {

    private float mStrength = 0f;   // 0..1，1 = 信号最强（标签就在眼前）
    private float mSweepDeg = 0f;   // 扫描线当前角度

    private final Paint mCircle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSweep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);

    public RadarView(Context c) {
        super(c);
        mCircle.setStyle(Paint.Style.STROKE);
        mCircle.setStrokeWidth(3f);
        mCircle.setColor(0xFF4CAF50);           // 绿色同心圆
        mLine.setStrokeWidth(2f);
        mLine.setColor(0xFF4CAF50);             // 十字线
        mSweep.setStrokeWidth(6f);
        mSweep.setColor(0xFF00BCD4);            // 青色扫描线
        mDot.setStyle(Paint.Style.FILL);
        mDot.setColor(0xFFE53935);              // 红色目标点
    }

    /** 用 dBm 设置信号强度（-90..-30 映射到 0..1） */
    public void setRssiDbm(float dbm) {
        float s = (dbm + 90f) / 60f;
        mStrength = Math.max(0f, Math.min(1f, s));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float R = Math.min(cx, cy) - 8f;
        if (R <= 0) return;

        // 5 圈同心圆 + 十字线
        for (int i = 1; i <= 5; i++) canvas.drawCircle(cx, cy, R * i / 5f, mCircle);
        canvas.drawLine(cx - R, cy, cx + R, cy, mLine);
        canvas.drawLine(cx, cy - R, cx, cy + R, mLine);

        // 旋转扫描线
        mSweepDeg += 4f;
        if (mSweepDeg >= 360f) mSweepDeg -= 360f;
        double rad = Math.toRadians(mSweepDeg);
        canvas.drawLine(cx, cy,
                cx + (float) Math.cos(rad) * R,
                cy + (float) Math.sin(rad) * R, mSweep);

        // 目标点：信号越强越靠圆心
        float dr = (1f - mStrength) * R;
        canvas.drawCircle(cx + (float) Math.cos(rad) * dr,
                cy + (float) Math.sin(rad) * dr, 10f, mDot);

        postInvalidateDelayed(30); // 持续动画
    }
}
