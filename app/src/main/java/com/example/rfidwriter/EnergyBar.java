package com.example.rfidwriter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * 信号能量条（横向，Canvas 自绘，零依赖）。
 *
 * 设计：
 *  - 深色圆角轨道 + 红→琥珀→薄荷绿三段渐变填充：信号越强，能量越高、颜色越偏绿；
 *  - 前缘带“辉光火花”（外圈淡、内圈亮），强化“实时跳动”观感；
 *  - 25/50/75% 分段刻度，呈现仪表感；
 *  - 数值变化平滑插值（靠近→能量条迅速涨起，远离→平滑回落），不突兀。
 */
public class EnergyBar extends View {
    private static final int C_RED = 0xFFEF5350;
    private static final int C_AMBER = 0xFFFFCA28;
    private static final int C_GREEN = 0xFF69F0AE;

    private float mTarget = 0f;   // 目标能量 0..1
    private float mLevel = 0f;    // 当前显示（动画插值）

    private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBorder = new Paint(Paint.ANTI_ALIAS_FLAG);

    private LinearGradient mGrad = null;

    public EnergyBar(Context c) {
        super(c);
        mTrack.setColor(0x261B2B24);
        mFill.setStyle(Paint.Style.FILL);
        mGlow.setStyle(Paint.Style.FILL);
        mTick.setColor(0x20000000);
        mTick.setStrokeWidth(2f);
        mBorder.setColor(0x4D7CFC5C);
        mBorder.setStyle(Paint.Style.STROKE);
        mBorder.setStrokeWidth(1.5f);
    }

    /** 设置能量等级（0..1，1 = 信号最强） */
    public void setLevel(float level) {
        float v = Math.max(0f, Math.min(1f, level));
        if (Math.abs(v - mTarget) > 0.001f) {
            mTarget = v;
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        mGrad = new LinearGradient(0, 0, w, 0,
                new int[]{C_RED, C_AMBER, C_GREEN},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        mFill.setShader(mGrad);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // 平滑插值
        mLevel += (mTarget - mLevel) * 0.2f;
        if (Math.abs(mTarget - mLevel) < 0.004f) mLevel = mTarget;

        int w = getWidth(), h = getHeight();
        float r = h / 2f;
        RectF track = new RectF(0, 0, w, h);

        // 轨道 + 边框
        canvas.drawRoundRect(track, r, r, mTrack);
        canvas.drawRoundRect(track, r, r, mBorder);

        // 填充（按 level 裁剪渐变）
        if (mLevel > 0.002f) {
            canvas.save();
            canvas.clipRect(0, 0, w * mLevel, h);
            canvas.drawRoundRect(track, r, r, mFill);
            canvas.restore();

            // 前缘辉光（外淡内亮）
            float lx = w * mLevel;
            int strong = (mLevel > 0.66f) ? C_GREEN : (mLevel > 0.33f) ? C_AMBER : C_RED;
            mGlow.setColor(strong);
            mGlow.setAlpha((int) (55 + 70 * mLevel));
            canvas.drawCircle(lx, h / 2f, h * 0.45f, mGlow);
            mGlow.setAlpha((int) (140 + 100 * mLevel));
            canvas.drawCircle(lx, h / 2f, h * 0.22f, mGlow);
        }

        // 分段刻度（25/50/75%）
        mTick.setAlpha(130);
        for (int i = 1; i <= 3; i++) {
            float x = w * i / 4f;
            canvas.drawLine(x, h * 0.24f, x, h * 0.76f, mTick);
        }

        if (mLevel < 0.999f && Math.abs(mTarget - mLevel) > 0.004f) postInvalidateDelayed(16);
    }
}
