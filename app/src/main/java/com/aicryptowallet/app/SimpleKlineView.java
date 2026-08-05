package com.aicryptowallet.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量级 K 线图自绘 View
 *
 * 设计目标：
 *  - 不引入 MPAndroidChart 等第三方库，避免依赖冲突和仓库配置复杂化
 *  - 同时支持"蜡烛图"和"折线图"两种模式
 *  - 自动根据最高/最低价缩放，价格刻度居右
 *  - 涨绿跌红（与 AVE 一致，国际惯例相反）
 *
 * 数据模型：每根 K 线包含 [time, open, high, low, close, volume]
 */
public class SimpleKlineView extends View {

    /** 单根 K 线数据：time(ms), open, high, low, close, volume */
    public static class Kline {
        public final long time;
        public final double open, high, low, close, volume;
        public Kline(long t, double o, double h, double l, double c, double v) {
            time = t; open = o; high = h; low = l; close = c; volume = v;
        }
    }

    private final List<Kline> data = new ArrayList<>();
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint candlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();

    private double minPrice = 0, maxPrice = 0;
    private boolean showAsLine = true; // 默认折线模式（更简洁，类似 AVE）
    private int paddingTop = 20, paddingBottom = 30, paddingLeft = 10, paddingRight = 70;

    public SimpleKlineView(Context ctx) { super(ctx); init(); }
    public SimpleKlineView(Context ctx, AttributeSet attrs) { super(ctx, attrs); init(); }
    public SimpleKlineView(Context ctx, AttributeSet attrs, int defStyle) { super(ctx, attrs, defStyle); init(); }

    private void init() {
        linePaint.setColor(0xFF2997F4);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(3f);

        candlePaint.setStyle(Paint.Style.STROKE);
        candlePaint.setStrokeWidth(1.5f);

        gridPaint.setColor(0xFF2A2A35);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(0.5f);

        textPaint.setColor(0xFF888899);
        textPaint.setTextSize(22f);
        textPaint.setTextAlign(Paint.Align.LEFT);

        fillPaint.setStyle(Paint.Style.FILL);
    }

    /** 设置 K 线数据并触发重绘 */
    public void setData(List<Kline> klines, boolean asLine) {
        data.clear();
        if (klines != null) data.addAll(klines);
        showAsLine = asLine;
        computeRange();
        invalidate();
    }

    public void clear() {
        data.clear();
        minPrice = maxPrice = 0;
        invalidate();
    }

    private void computeRange() {
        if (data.isEmpty()) {
            minPrice = maxPrice = 0;
            return;
        }
        minPrice = Double.MAX_VALUE;
        maxPrice = Double.MIN_VALUE;
        for (Kline k : data) {
            if (k.low < minPrice) minPrice = k.low;
            if (k.high > maxPrice) maxPrice = k.high;
        }
        // 增加 5% 上下边距，避免 K 线贴边
        double margin = (maxPrice - minPrice) * 0.08;
        if (margin < maxPrice * 0.001) margin = maxPrice * 0.001; // 防止全平时为 0
        minPrice -= margin;
        maxPrice += margin;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() == 0 || getHeight() == 0) return;

        // 深色背景（与 App 主题一致）
        canvas.drawColor(0xFF131320);

        if (data.isEmpty()) {
            textPaint.setColor(0xFF666677);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("暂无 K 线数据", getWidth() / 2f, getHeight() / 2f, textPaint);
            textPaint.setTextAlign(Paint.Align.LEFT);
            return;
        }

        int chartW = getWidth() - paddingLeft - paddingRight;
        int chartH = getHeight() - paddingTop - paddingBottom;

        // 画 4 条横线网格 + 价格刻度
        textPaint.setColor(0xFF666677);
        textPaint.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i <= 4; i++) {
            float y = paddingTop + chartH * i / 4f;
            canvas.drawLine(paddingLeft, y, getWidth() - paddingRight, y, gridPaint);
            double price = maxPrice - (maxPrice - minPrice) * i / 4.0;
            canvas.drawText(formatPrice(price), getWidth() - paddingRight + 4, y + 5, textPaint);
        }

        if (showAsLine) {
            drawLineChart(canvas, chartW, chartH);
        } else {
            drawCandleChart(canvas, chartW, chartH);
        }
    }

    private void drawLineChart(Canvas canvas, int chartW, int chartH) {
        if (data.isEmpty()) return;
        float stepX = chartW / (float) Math.max(1, data.size() - 1);
        linePath.reset();

        // 构造价格曲线 path
        for (int i = 0; i < data.size(); i++) {
            float x = paddingLeft + stepX * i;
            float y = (float) (paddingTop + chartH * (1 - priceRatio(data.get(i).close)));
            if (i == 0) linePath.moveTo(x, y);
            else linePath.lineTo(x, y);
        }

        // 渐变填充区域（曲线下方到底部）
        float lastX = paddingLeft + stepX * (data.size() - 1);
        linePath.lineTo(lastX, paddingTop + chartH);
        linePath.lineTo(paddingLeft, paddingTop + chartH);
        linePath.close();
        LinearGradient gradient = new LinearGradient(
            0, paddingTop, 0, paddingTop + chartH,
            new int[]{0x552997F4, 0x002997F4},
            null, android.graphics.Shader.TileMode.CLAMP);
        fillPaint.setShader(gradient);
        canvas.drawPath(linePath, fillPaint);
        fillPaint.setShader(null);

        // 重画曲线（填充会盖住，再画一次）
        linePath.reset();
        for (int i = 0; i < data.size(); i++) {
            float x = paddingLeft + stepX * i;
            float y = (float) (paddingTop + chartH * (1 - priceRatio(data.get(i).close)));
            if (i == 0) linePath.moveTo(x, y);
            else linePath.lineTo(x, y);
        }
        canvas.drawPath(linePath, linePaint);

        // 末点高亮
        if (data.size() > 0) {
            float x = paddingLeft + stepX * (data.size() - 1);
            float y = (float) (paddingTop + chartH * (1 - priceRatio(data.get(data.size() - 1).close)));
            canvas.drawCircle(x, y, 6f, linePaint);
            canvas.drawCircle(x, y, 3f, fillPaint);
            fillPaint.setColor(0xFFFFFFFF);
            canvas.drawCircle(x, y, 2f, fillPaint);
        }
    }

    private void drawCandleChart(Canvas canvas, int chartW, int chartH) {
        if (data.isEmpty()) return;
        float candleSpacing = chartW / (float) data.size();
        float candleWidth = Math.max(3f, candleSpacing * 0.65f);

        for (int i = 0; i < data.size(); i++) {
            Kline k = data.get(i);
            float centerX = paddingLeft + candleSpacing * (i + 0.5f);
            float yHigh = (float) (paddingTop + chartH * (1 - priceRatio(k.high)));
            float yLow = (float) (paddingTop + chartH * (1 - priceRatio(k.low)));
            float yOpen = (float) (paddingTop + chartH * (1 - priceRatio(k.open)));
            float yClose = (float) (paddingTop + chartH * (1 - priceRatio(k.close)));

            boolean up = k.close >= k.open;
            int color = up ? 0xFF22C55E : 0xFFEF4444;
            candlePaint.setColor(color);

            // 上下影线
            canvas.drawLine(centerX, yHigh, centerX, yLow, candlePaint);

            // 实体
            float bodyTop = Math.min(yOpen, yClose);
            float bodyBottom = Math.max(yOpen, yClose);
            float bodyH = Math.max(1f, bodyBottom - bodyTop);
            RectF body = new RectF(centerX - candleWidth / 2, bodyTop, centerX + candleWidth / 2, bodyBottom);
            if (up) {
                candlePaint.setStyle(Paint.Style.FILL);
            } else {
                candlePaint.setStyle(Paint.Style.STROKE);
            }
            canvas.drawRect(body, candlePaint);
            candlePaint.setStyle(Paint.Style.STROKE);
        }
    }

    /** 价格 → [0,1] 归一化比例 */
    private double priceRatio(double price) {
        if (maxPrice <= minPrice) return 0.5;
        return (price - minPrice) / (maxPrice - minPrice);
    }

    private String formatPrice(double p) {
        if (p >= 1000) return String.format("%.0f", p);
        if (p >= 1) return String.format("%.2f", p);
        if (p >= 0.01) return String.format("%.4f", p);
        return String.format("%.6f", p);
    }
}
