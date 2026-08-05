package com.aicryptowallet.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import java.util.Locale;

public class MarketShareGenerator {

    private static final int WIDTH_PX = 1080;
    private static final int PADDING = 60;

    private static final int COLOR_BG = 0xFF0D0D0F;
    private static final int COLOR_CARD = 0xFF1A1A2E;
    private static final int COLOR_TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int COLOR_TEXT_SECONDARY = 0xFF9B9BA7;
    private static final int COLOR_ACCENT = 0xFF667eea;
    private static final int COLOR_GREEN = 0xFF34C759;
    private static final int COLOR_RED = 0xFFFF453A;
    private static final int COLOR_GOLD = 0xFFF5A623;

    public static Bitmap generate(Context ctx, String symbol, String name,
                                   double price, double changePercent, String report) {
        try {
            int height = measureHeight(ctx, report);
            Bitmap bitmap = Bitmap.createBitmap(WIDTH_PX, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            // 背景
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(COLOR_BG);
            canvas.drawRect(0, 0, WIDTH_PX, height, bg);

            int y = PADDING;

            // 顶部装饰线
            Paint accentLine = new Paint(Paint.ANTI_ALIAS_FLAG);
            accentLine.setStrokeWidth(dpToPx(ctx, 4));
            accentLine.setStrokeCap(Paint.Cap.ROUND);
            accentLine.setColor(COLOR_ACCENT);
            canvas.drawLine(PADDING, y, WIDTH_PX - PADDING, y, accentLine);
            y += dpToPx(ctx, 50);

            // Logo 区域：红色 AI 圆形
            int logoSize = dpToPx(ctx, 80);
            Paint logoBg = new Paint(Paint.ANTI_ALIAS_FLAG);
            logoBg.setColor(0xFFdc2626);
            int logoCx = PADDING + logoSize / 2;
            int logoCy = y + logoSize / 2;
            canvas.drawCircle(logoCx, logoCy, logoSize / 2, logoBg);

            Paint logoText = new Paint(Paint.ANTI_ALIAS_FLAG);
            logoText.setColor(COLOR_TEXT_PRIMARY);
            logoText.setTextSize(dpToPx(ctx, 32));
            logoText.setTypeface(Typeface.DEFAULT_BOLD);
            logoText.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("AI", logoCx, logoCy + dpToPx(ctx, 12), logoText);

            // 标题
            Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
            title.setColor(COLOR_TEXT_PRIMARY);
            title.setTextSize(dpToPx(ctx, 40));
            title.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText("AI Crypto Wallet", PADDING + logoSize + dpToPx(ctx, 24), y + dpToPx(ctx, 36), title);

            Paint subtitle = new Paint(Paint.ANTI_ALIAS_FLAG);
            subtitle.setColor(COLOR_TEXT_SECONDARY);
            subtitle.setTextSize(dpToPx(ctx, 22));
            canvas.drawText("智能行情分析", PADDING + logoSize + dpToPx(ctx, 24), y + dpToPx(ctx, 64), subtitle);

            y += logoSize + dpToPx(ctx, 50);

            // 币种信息卡片
            int cardTop = y;
            int cardBottom = y + dpToPx(ctx, 220);
            Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            cardPaint.setColor(COLOR_CARD);
            RectF cardRect = new RectF(PADDING, cardTop, WIDTH_PX - PADDING, cardBottom);
            canvas.drawRoundRect(cardRect, dpToPx(ctx, 24), dpToPx(ctx, 24), cardPaint);

            // 卡片顶部渐变条
            Paint topBar = new Paint(Paint.ANTI_ALIAS_FLAG);
            LinearGradient barGradient = new LinearGradient(
                PADDING, cardTop, WIDTH_PX - PADDING, cardTop,
                COLOR_ACCENT, 0xFF8B5CF6, Shader.TileMode.CLAMP);
            topBar.setShader(barGradient);
            canvas.drawRoundRect(cardRect, dpToPx(ctx, 24), dpToPx(ctx, 24), topBar);
            // 修正为顶部一小条
            Paint cardBg2 = new Paint(Paint.ANTI_ALIAS_FLAG);
            cardBg2.setColor(COLOR_CARD);
            RectF innerRect = new RectF(PADDING, cardTop + dpToPx(ctx, 6), WIDTH_PX - PADDING, cardBottom);
            canvas.drawRoundRect(innerRect, dpToPx(ctx, 20), dpToPx(ctx, 20), cardBg2);

            int cy = cardTop + dpToPx(ctx, 60);
            Paint symbolPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            symbolPaint.setColor(COLOR_TEXT_PRIMARY);
            symbolPaint.setTextSize(dpToPx(ctx, 52));
            symbolPaint.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText(symbol, PADDING + dpToPx(ctx, 32), cy, symbolPaint);

            Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            namePaint.setColor(COLOR_TEXT_SECONDARY);
            namePaint.setTextSize(dpToPx(ctx, 24));
            canvas.drawText(name, PADDING + dpToPx(ctx, 32), cy + dpToPx(ctx, 38), namePaint);

            String priceStr = formatPrice(ctx, price);
            Paint pricePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            pricePaint.setColor(COLOR_TEXT_PRIMARY);
            pricePaint.setTextSize(dpToPx(ctx, 44));
            pricePaint.setTypeface(Typeface.DEFAULT_BOLD);
            pricePaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(priceStr, WIDTH_PX - PADDING - dpToPx(ctx, 32), cy, pricePaint);

            String changeStr = (changePercent >= 0 ? "+" : "") + String.format(Locale.getDefault(), "%.2f", changePercent) + "%";
            Paint changePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            changePaint.setColor(changePercent >= 0 ? COLOR_GREEN : COLOR_RED);
            changePaint.setTextSize(dpToPx(ctx, 28));
            changePaint.setTypeface(Typeface.DEFAULT_BOLD);
            changePaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(changeStr, WIDTH_PX - PADDING - dpToPx(ctx, 32), cy + dpToPx(ctx, 42), changePaint);

            y = cardBottom + dpToPx(ctx, 40);

            // 报告内容卡片
            int reportCardTop = y;
            Paint reportCardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            reportCardPaint.setColor(COLOR_CARD);
            int reportCardBottom = y + measureReportHeight(ctx, report) + dpToPx(ctx, 60);
            RectF reportRect = new RectF(PADDING, reportCardTop, WIDTH_PX - PADDING, reportCardBottom);
            canvas.drawRoundRect(reportRect, dpToPx(ctx, 24), dpToPx(ctx, 24), reportCardPaint);

            int ry = reportCardTop + dpToPx(ctx, 36);
            Paint sectionTitle = new Paint(Paint.ANTI_ALIAS_FLAG);
            sectionTitle.setColor(COLOR_ACCENT);
            sectionTitle.setTextSize(dpToPx(ctx, 28));
            sectionTitle.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText("AI 分析结论", PADDING + dpToPx(ctx, 32), ry, sectionTitle);

            ry += dpToPx(ctx, 20);

            Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            linePaint.setColor(COLOR_ACCENT);
            linePaint.setStrokeWidth(dpToPx(ctx, 3));
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(PADDING + dpToPx(ctx, 32), ry, PADDING + dpToPx(ctx, 120), ry, linePaint);
            ry += dpToPx(ctx, 36);

            Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setTextSize(dpToPx(ctx, 24));
            textPaint.setColor(COLOR_TEXT_PRIMARY);
            int maxWidth = WIDTH_PX - PADDING * 2 - dpToPx(ctx, 64);
            int lineHeight = dpToPx(ctx, 42);

            String[] lines = report.split("\n");
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty()) {
                    ry += dpToPx(ctx, 16);
                    continue;
                }
                if (line.startsWith("【")) {
                    textPaint.setColor(COLOR_GOLD);
                    textPaint.setTypeface(Typeface.DEFAULT_BOLD);
                    textPaint.setTextSize(dpToPx(ctx, 26));
                } else if (line.startsWith("──")) {
                    textPaint.setColor(COLOR_ACCENT);
                    textPaint.setTypeface(Typeface.DEFAULT_BOLD);
                    textPaint.setTextSize(dpToPx(ctx, 24));
                } else {
                    textPaint.setColor(COLOR_TEXT_PRIMARY);
                    textPaint.setTypeface(Typeface.DEFAULT);
                    textPaint.setTextSize(dpToPx(ctx, 24));
                }

                // 绘制并自动换行
                while (line.length() > 0) {
                    int cut = line.length();
                    while (cut > 0 && textPaint.measureText(line.substring(0, cut)) > maxWidth) {
                        cut--;
                    }
                    if (cut <= 0) cut = 1;
                    canvas.drawText(line.substring(0, cut), PADDING + dpToPx(ctx, 32), ry, textPaint);
                    ry += lineHeight;
                    line = line.substring(cut).trim();
                }
            }

            // 底部品牌条
            Paint bottomBar = new Paint(Paint.ANTI_ALIAS_FLAG);
            bottomBar.setColor(COLOR_ACCENT);
            canvas.drawRect(0, height - dpToPx(ctx, 80), WIDTH_PX, height, bottomBar);

            Paint brand = new Paint(Paint.ANTI_ALIAS_FLAG);
            brand.setColor(COLOR_TEXT_PRIMARY);
            brand.setTextSize(dpToPx(ctx, 28));
            brand.setTypeface(Typeface.DEFAULT_BOLD);
            brand.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("AI Crypto Wallet · 让 AI 帮你做决策", WIDTH_PX / 2, height - dpToPx(ctx, 32), brand);

            return bitmap;
        } catch (Exception e) {
            Logger.error(null, "MarketShare", "生成分享图失败: " + e.getMessage(), e);
            return null;
        }
    }

    private static int measureHeight(Context ctx, String report) {
        int h = PADDING;
        h += dpToPx(ctx, 4);
        h += dpToPx(ctx, 50);
        h += dpToPx(ctx, 80);
        h += dpToPx(ctx, 50);
        h += dpToPx(ctx, 220);
        h += dpToPx(ctx, 40);
        h += measureReportHeight(ctx, report) + dpToPx(ctx, 60);
        h += dpToPx(ctx, 80);
        return h;
    }

    private static int measureReportHeight(Context ctx, String report) {
        Paint p = new Paint();
        p.setTextSize(dpToPx(ctx, 24));
        int maxWidth = WIDTH_PX - PADDING * 2 - dpToPx(ctx, 64);
        int lineHeight = dpToPx(ctx, 42);
        int h = dpToPx(ctx, 36);
        h += dpToPx(ctx, 20);
        h += dpToPx(ctx, 36);

        String[] lines = report.split("\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                h += dpToPx(ctx, 16);
                continue;
            }
            int linesCount = 0;
            while (line.length() > 0) {
                int cut = line.length();
                while (cut > 0 && p.measureText(line.substring(0, cut)) > maxWidth) {
                    cut--;
                }
                if (cut <= 0) cut = 1;
                linesCount++;
                line = line.substring(cut).trim();
            }
            h += linesCount * lineHeight;
        }
        return h;
    }

    private static String formatPrice(Context ctx, double price) {
        String symbol = CurrencyManager.getCurrencySymbol(CurrencyManager.getSelectedCurrency(ctx));
        if (price >= 1) return symbol + String.format(Locale.getDefault(), "%,.2f", price);
        if (price >= 0.01) return symbol + String.format(Locale.getDefault(), "%.4f", price);
        return symbol + String.format(Locale.getDefault(), "%.6f", price);
    }

    private static int dpToPx(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
