package com.aicryptowallet.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * AI 合约安全分析报告分享图生成器（高端版 v3）
 * 设计语言：深空渐变背景、霓虹光晕、玻璃态卡片、星级悬浮徽章、精致二维码
 */
public class ReportShareGenerator {

    private static int WIDTH_PX = 1440;
    private static final int PADDING_DP = 36;
    private static int PADDING = 54;
    private static final int QR_SIZE_DP = 110;
    private static int QR_SIZE = 165;
    private static final String DEFAULT_DOWNLOAD_URL = "https://github.com/openxcn/AI-Crypto-Wallet/releases/latest/download/AICryptoWallet-latest-release.apk";
    private static final String DEFAULT_AI_MODEL = "DeepSeek AI";

    // 高端深色配色
    private static final int COLOR_BG_TOP = 0xFF0B0E14;
    private static final int COLOR_BG_BOTTOM = 0xFF111827;
    private static final int COLOR_CARD_BG = 0xFF151B26;
    private static final int COLOR_CARD_BG_LIGHT = 0xFF1C2333;
    private static final int COLOR_BORDER = 0xFF2D3548;
    private static final int COLOR_TEXT_PRIMARY = 0xFFF8FAFC;
    private static final int COLOR_TEXT_SECONDARY = 0xFF94A3B8;
    private static final int COLOR_TEXT_MUTED = 0xFF64748B;
    private static final int COLOR_ACCENT_VIOLET = 0xFF8B5CF6;
    private static final int COLOR_ACCENT_CYAN = 0xFF06B6D4;
    private static final int COLOR_ACCENT_PINK = 0xFFA855F7;
    private static final int COLOR_GREEN = 0xFF22C55E;
    private static final int COLOR_GREEN_DARK = 0xFF15803D;
    private static final int COLOR_RED = 0xFFEF4444;
    private static final int COLOR_YELLOW = 0xFFF59E0B;
    private static final int COLOR_ORANGE = 0xFFF97316;

    /**
     * 一键分享 AI 报告为图片
     */
    public static void shareReport(Context ctx, TokenRiskAnalyzer.RiskResult result,
                                    String symbol, String contract, String chain) {
        try {
            Bitmap bitmap = generateShareBitmap(ctx, result, symbol, contract, chain);
            if (bitmap == null) {
                Toast.makeText(ctx, ctx.getString(R.string.toast_failed_to_generate_share_2), Toast.LENGTH_SHORT).show();
                return;
            }

            File cacheDir = new File(ctx.getCacheDir(), "share");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            String fileName = "AI分析_" + symbol + "_" + System.currentTimeMillis() + ".png";
            File file = new File(cacheDir, fileName);
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();

            Uri uri = FileProvider.getUriForFile(ctx,
                ctx.getPackageName() + ".fileprovider", file);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("image/png");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(Intent.createChooser(shareIntent, ctx.getString(R.string.str_share)));

            Logger.info(null, "报告分享", "分享图片已生成: " + file.getAbsolutePath());
        } catch (Exception e) {
            Logger.error(null, "报告分享", "分享失败: " + e.getMessage(), e);
            Toast.makeText(ctx, ctx.getString(R.string.toast_failed_to_share, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 生成分享 Bitmap
     */
    private static void initWidth(Context ctx) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int screenWidth = ctx.getResources().getDisplayMetrics().widthPixels;
        WIDTH_PX = Math.max(screenWidth, 1080);
        PADDING = (int) (PADDING_DP * density + 0.5f);
        QR_SIZE = (int) (QR_SIZE_DP * density + 0.5f);
    }

    private static Bitmap generateShareBitmap(Context ctx, TokenRiskAnalyzer.RiskResult result,
                                               String symbol, String contract, String chain) {
        initWidth(ctx);
        String aiModel = getAiModelName(ctx);
        String reportText = buildCompactReport(result, symbol, contract, chain);
        int height = measureTotalHeight(ctx, result, symbol, reportText);

        Bitmap bitmap = Bitmap.createBitmap(WIDTH_PX, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // 背景
        drawBackground(canvas, height);

        int y = 0;

        // === 1. 头部 ===
        y = drawHeader(canvas, ctx, aiModel, result, symbol, y);

        // === 2. 星级徽章 ===
        y = drawStarBadge(canvas, ctx, result, y);

        // === 3. 评分概览 ===
        y = drawScoreOverview(canvas, ctx, result, y);

        // === 4. 报告内容卡片 ===
        y = drawReportCard(canvas, ctx, reportText, y);

        // === 5. 底部：二维码 + 下载引导 ===
        drawFooter(canvas, ctx, height);

        return bitmap;
    }

    /** 绘制高端渐变背景 + 光晕 + 网格 */
    private static void drawBackground(Canvas canvas, int height) {
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        LinearGradient gradient = new LinearGradient(
            0, 0, 0, height, COLOR_BG_TOP, COLOR_BG_BOTTOM, Shader.TileMode.CLAMP);
        bg.setShader(gradient);
        canvas.drawRect(0, 0, WIDTH_PX, height, bg);

        // 顶部紫色光晕
        Paint glowTop = new Paint(Paint.ANTI_ALIAS_FLAG);
        RadialGradient rgTop = new RadialGradient(
            WIDTH_PX * 0.75f, dpToPx(null, 140), dpToPx(null, 360),
            new int[]{0x4D8B5CF6, 0x008B5CF6}, null, Shader.TileMode.CLAMP);
        glowTop.setShader(rgTop);
        canvas.drawRect(0, 0, WIDTH_PX, height, glowTop);

        // 底部青色光晕
        Paint glowBottom = new Paint(Paint.ANTI_ALIAS_FLAG);
        RadialGradient rgBottom = new RadialGradient(
            WIDTH_PX * 0.25f, height - dpToPx(null, 250), dpToPx(null, 400),
            new int[]{0x3306B6D4, 0x0006B6D4}, null, Shader.TileMode.CLAMP);
        glowBottom.setShader(rgBottom);
        canvas.drawRect(0, 0, WIDTH_PX, height, glowBottom);

        // 细网格
        Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(0x08FFFFFF);
        gridPaint.setStrokeWidth(1);
        int step = dpToPx(null, 40);
        for (int x = 0; x < WIDTH_PX; x += step) {
            canvas.drawLine(x, 0, x, height, gridPaint);
        }
        for (int y = 0; y < height; y += step) {
            canvas.drawLine(0, y, WIDTH_PX, y, gridPaint);
        }
    }

    /** 绘制头部（渐变 + 装饰圆 + 标题） */
    private static int drawHeader(Canvas canvas, Context ctx, String aiModel,
                                   TokenRiskAnalyzer.RiskResult result, String symbol, int startY) {
        int headerHeight = dpToPx(ctx, 200);

        // 头部渐变背景
        Paint headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        LinearGradient gradient = new LinearGradient(
            0, 0, WIDTH_PX, headerHeight,
            new int[]{0xFF1E1B4B, 0xFF312E81, 0xFF1E1B4B},
            new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        headerPaint.setShader(gradient);
        canvas.drawRect(0, startY, WIDTH_PX, startY + headerHeight, headerPaint);

        // 装饰光晕
        Paint decoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        decoPaint.setColor(0x22FFFFFF);
        canvas.drawCircle(WIDTH_PX - dpToPx(ctx, 70), startY + dpToPx(ctx, 50), dpToPx(ctx, 90), decoPaint);
        decoPaint.setColor(0x11FFFFFF);
        canvas.drawCircle(dpToPx(ctx, 50), startY + dpToPx(ctx, 220), dpToPx(ctx, 110), decoPaint);

        // 顶部渐变线
        Paint topLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        LinearGradient lineGradient = new LinearGradient(
            0, startY, WIDTH_PX, startY,
            new int[]{0x008B5CF6, 0xFF8B5CF6, 0xFF06B6D4, 0x0006B6D4},
            new float[]{0f, 0.3f, 0.7f, 1f}, Shader.TileMode.CLAMP);
        topLine.setShader(lineGradient);
        topLine.setStrokeWidth(dpToPx(ctx, 3));
        canvas.drawLine(PADDING, startY + dpToPx(ctx, 2), WIDTH_PX - PADDING, startY + dpToPx(ctx, 2), topLine);

        int y = startY + dpToPx(ctx, 40);

        // 主标题
        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(COLOR_TEXT_PRIMARY);
        titlePaint.setTextSize(dpToPx(ctx, 28));
        titlePaint.setTypeface(Typeface.DEFAULT_BOLD);
        titlePaint.setFakeBoldText(true);
        String title = "AI 合约安全分析报告";
        float titleWidth = titlePaint.measureText(title);
        canvas.drawText(title, (WIDTH_PX - titleWidth) / 2, y, titlePaint);

        y += dpToPx(ctx, 34);

        // 副标题
        Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        subPaint.setColor(0xBBFFFFFF);
        subPaint.setTextSize(dpToPx(ctx, 16));
        String subTitle = "智能分析代币合约 · 风险一目了然";
        float subWidth = subPaint.measureText(subTitle);
        canvas.drawText(subTitle, (WIDTH_PX - subWidth) / 2, y, subPaint);

        y += dpToPx(ctx, 34);

        // 代币名称行
        Paint tokenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tokenPaint.setColor(COLOR_ACCENT_CYAN);
        tokenPaint.setTextSize(dpToPx(ctx, 24));
        tokenPaint.setTypeface(Typeface.DEFAULT_BOLD);
        tokenPaint.setFakeBoldText(true);
        String tokenText = "分析代币: " + symbol;
        float tokenWidth = tokenPaint.measureText(tokenText);
        canvas.drawText(tokenText, (WIDTH_PX - tokenWidth) / 2, y, tokenPaint);

        return startY + headerHeight;
    }

    /** 绘制星级徽章（悬浮在头部和卡片之间） */
    private static int drawStarBadge(Canvas canvas, Context ctx,
                                      TokenRiskAnalyzer.RiskResult result, int startY) {
        int badgeSize = dpToPx(ctx, 90);
        int cx = WIDTH_PX / 2;
        int cy = startY;

        // 徽章外发光
        Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int badgeColor = getBadgeColor(result.stars);
        RadialGradient badgeGlow = new RadialGradient(
            cx, cy, badgeSize,
            new int[]{adjustAlpha(badgeColor, 0.4f), adjustAlpha(badgeColor, 0f)},
            null, Shader.TileMode.CLAMP);
        glowPaint.setShader(badgeGlow);
        canvas.drawCircle(cx, cy, badgeSize, glowPaint);

        // 徽章背景圆
        Paint badgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        LinearGradient badgeGradient = new LinearGradient(
            cx - badgeSize / 2, cy - badgeSize / 2,
            cx + badgeSize / 2, cy + badgeSize / 2,
            badgeColor, adjustBrightness(badgeColor, 0.8f), Shader.TileMode.CLAMP);
        badgeBgPaint.setShader(badgeGradient);
        badgeBgPaint.setShadowLayer(dpToPx(ctx, 12), 0, dpToPx(ctx, 6), 0x44000000);
        canvas.drawCircle(cx, cy, badgeSize / 2, badgeBgPaint);

        // 徽章边框
        Paint badgeBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        badgeBorderPaint.setStyle(Paint.Style.STROKE);
        badgeBorderPaint.setStrokeWidth(dpToPx(ctx, 3));
        badgeBorderPaint.setColor(0xCCFFFFFF);
        canvas.drawCircle(cx, cy, badgeSize / 2 - dpToPx(ctx, 3), badgeBorderPaint);

        // 星级数字
        Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        starPaint.setColor(0xFFFFFFFF);
        starPaint.setTextSize(dpToPx(ctx, 32));
        starPaint.setTypeface(Typeface.DEFAULT_BOLD);
        starPaint.setFakeBoldText(true);
        starPaint.setTextAlign(Paint.Align.CENTER);
        String starText = String.valueOf(result.stars);
        canvas.drawText(starText, cx, cy + dpToPx(ctx, 6), starPaint);

        // 星级星星
        Paint starIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        starIconPaint.setColor(0xFFFFFFFF);
        starIconPaint.setTextSize(dpToPx(ctx, 16));
        starIconPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("★", cx, cy + dpToPx(ctx, 26), starIconPaint);

        // 风险等级文字
        Paint levelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        levelPaint.setColor(0xCCFFFFFF);
        levelPaint.setTextSize(dpToPx(ctx, 15));
        levelPaint.setTextAlign(Paint.Align.CENTER);
        String levelText = TokenRiskAnalyzer.getRiskLevel(result.stars);
        canvas.drawText(levelText, cx, cy + dpToPx(ctx, 42), levelPaint);

        return startY + badgeSize / 2 + dpToPx(ctx, 16);
    }

    /** 评分概览卡片 */
    private static int drawScoreOverview(Canvas canvas, Context ctx,
                                          TokenRiskAnalyzer.RiskResult result, int startY) {
        int cardHeight = dpToPx(ctx, 110);
        int cardTop = startY;
        int cardBottom = cardTop + cardHeight;
        float corner = dpToPx(ctx, 20);
        RectF cardRect = new RectF(PADDING, cardTop, WIDTH_PX - PADDING, cardBottom);

        // 背景
        Paint cardBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardBg.setColor(COLOR_CARD_BG);
        cardBg.setShadowLayer(dpToPx(ctx, 10), 0, dpToPx(ctx, 5), 0x22000000);
        canvas.drawRoundRect(cardRect, corner, corner, cardBg);

        // 边框
        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dpToPx(ctx, 1));
        borderPaint.setColor(COLOR_BORDER);
        canvas.drawRoundRect(cardRect, corner, corner, borderPaint);

        // 三项指标
        int itemWidth = (WIDTH_PX - PADDING * 2) / 3;
        String[] labels = {"安全评分", "合约验证", "风险等级"};
        String[] values = {
            result.score + "/100",
            result.isVerified ? "已开源" : "未开源",
            TokenRiskAnalyzer.getRiskLevel(result.stars)
        };
        int[] valueColors = {
            getScoreColor(result.score),
            result.isVerified ? COLOR_GREEN : COLOR_RED,
            getBadgeColor(result.stars)
        };

        for (int i = 0; i < 3; i++) {
            int cx = PADDING + itemWidth * i + itemWidth / 2;

            // 分隔线
            if (i > 0) {
                Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                dividerPaint.setColor(COLOR_BORDER);
                dividerPaint.setStrokeWidth(dpToPx(ctx, 1));
                int divX = PADDING + itemWidth * i;
                canvas.drawLine(divX, cardTop + dpToPx(ctx, 32), divX, cardBottom - dpToPx(ctx, 32), dividerPaint);
            }

            Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            valuePaint.setColor(valueColors[i]);
            valuePaint.setTextSize(dpToPx(ctx, 24));
            valuePaint.setTypeface(Typeface.DEFAULT_BOLD);
            valuePaint.setFakeBoldText(true);
            valuePaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(values[i], cx, cardTop + dpToPx(ctx, 50), valuePaint);

            Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            labelPaint.setColor(COLOR_TEXT_SECONDARY);
            labelPaint.setTextSize(dpToPx(ctx, 16));
            labelPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(labels[i], cx, cardTop + dpToPx(ctx, 78), labelPaint);
        }

        return cardBottom + dpToPx(ctx, 24);
    }

    /** 绘制报告内容卡片 */
    private static int drawReportCard(Canvas canvas, Context ctx, String reportText, int startY) {
        int cardPadding = dpToPx(ctx, 28);
        int cardLeft = PADDING;
        int cardRight = WIDTH_PX - PADDING;

        int textAreaHeight = calculateTextAreaHeight(ctx, reportText, cardPadding);
        int cardTop = startY;
        int cardBottom = startY + cardPadding + textAreaHeight + cardPadding;
        float cornerRadius = dpToPx(ctx, 20);

        // 卡片背景
        Paint cardBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        LinearGradient cardGradient = new LinearGradient(
            cardLeft, cardTop, cardRight, cardBottom,
            COLOR_CARD_BG, COLOR_CARD_BG_LIGHT, Shader.TileMode.CLAMP);
        cardBgPaint.setShader(cardGradient);
        cardBgPaint.setShadowLayer(dpToPx(ctx, 12), 0, dpToPx(ctx, 6), 0x22000000);
        RectF cardRect = new RectF(cardLeft, cardTop, cardRight, cardBottom);
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, cardBgPaint);

        // 卡片边框
        Paint cardBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardBorderPaint.setStyle(Paint.Style.STROKE);
        cardBorderPaint.setStrokeWidth(dpToPx(ctx, 1));
        cardBorderPaint.setColor(COLOR_BORDER);
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, cardBorderPaint);

        // 顶部渐变装饰条
        Paint decoBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        LinearGradient decoGradient = new LinearGradient(
            cardLeft + cornerRadius, cardTop + dpToPx(ctx, 3),
            cardRight - cornerRadius, cardTop + dpToPx(ctx, 3),
            COLOR_ACCENT_VIOLET, COLOR_ACCENT_CYAN, Shader.TileMode.CLAMP);
        decoBarPaint.setShader(decoGradient);
        decoBarPaint.setStrokeWidth(dpToPx(ctx, 4));
        decoBarPaint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(cardLeft + cornerRadius, cardTop + dpToPx(ctx, 3),
            cardRight - cornerRadius, cardTop + dpToPx(ctx, 3), decoBarPaint);

        int y = cardTop + cardPadding + dpToPx(ctx, 14);

        // 报告标题
        Paint sectionTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        sectionTitlePaint.setColor(COLOR_TEXT_PRIMARY);
        sectionTitlePaint.setTextSize(dpToPx(ctx, 20));
        sectionTitlePaint.setTypeface(Typeface.DEFAULT_BOLD);
        sectionTitlePaint.setFakeBoldText(true);
        canvas.drawText("AI 合约安全分析结论", cardLeft + cardPadding, y, sectionTitlePaint);

        y += dpToPx(ctx, 10);

        // 标题下渐变装饰线
        Paint titleLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        LinearGradient titleLineGradient = new LinearGradient(
            cardLeft + cardPadding, y,
            cardLeft + cardPadding + dpToPx(ctx, 120), y,
            COLOR_ACCENT_VIOLET, COLOR_ACCENT_CYAN, Shader.TileMode.CLAMP);
        titleLinePaint.setShader(titleLineGradient);
        titleLinePaint.setStrokeWidth(dpToPx(ctx, 2));
        titleLinePaint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(cardLeft + cardPadding, y,
            cardLeft + cardPadding + dpToPx(ctx, 120), y, titleLinePaint);

        y += dpToPx(ctx, 28);

        // 报告正文
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int textLineHeight = dpToPx(ctx, 27);
        int maxTextWidth = WIDTH_PX - PADDING * 2 - cardPadding * 2;

        String[] lines = reportText.split("\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                y += dpToPx(ctx, 7);
                continue;
            }

            if (line.startsWith("【")) {
                textPaint.setColor(COLOR_YELLOW);
                textPaint.setTypeface(Typeface.DEFAULT_BOLD);
                textPaint.setFakeBoldText(true);
                textPaint.setTextSize(dpToPx(ctx, 16));
            } else if (line.startsWith("──")) {
                textPaint.setColor(COLOR_ACCENT_VIOLET);
                textPaint.setTypeface(Typeface.DEFAULT_BOLD);
                textPaint.setFakeBoldText(true);
                textPaint.setTextSize(dpToPx(ctx, 15));
            } else if (line.contains("✅")) {
                textPaint.setColor(COLOR_GREEN);
                textPaint.setTypeface(Typeface.DEFAULT);
                textPaint.setFakeBoldText(false);
                textPaint.setTextSize(dpToPx(ctx, 15));
            } else if (line.contains("⚠️") || line.contains("❌")) {
                textPaint.setColor(COLOR_RED);
                textPaint.setTypeface(Typeface.DEFAULT);
                textPaint.setFakeBoldText(false);
                textPaint.setTextSize(dpToPx(ctx, 15));
            } else if (line.contains("★") || line.contains("☆")) {
                textPaint.setColor(COLOR_YELLOW);
                textPaint.setTypeface(Typeface.DEFAULT_BOLD);
                textPaint.setFakeBoldText(true);
                textPaint.setTextSize(dpToPx(ctx, 15));
            } else {
                textPaint.setColor(COLOR_TEXT_PRIMARY);
                textPaint.setTypeface(Typeface.DEFAULT);
                textPaint.setFakeBoldText(false);
                textPaint.setTextSize(dpToPx(ctx, 15));
            }

            String wrappedLine = line;
            while (textPaint.measureText(wrappedLine) > maxTextWidth && wrappedLine.length() > 1) {
                int cut = wrappedLine.length() - 1;
                while (cut > 0 && textPaint.measureText(wrappedLine.substring(0, cut)) > maxTextWidth) {
                    cut--;
                }
                if (cut <= 0) cut = wrappedLine.length();
                canvas.drawText(wrappedLine.substring(0, cut), cardLeft + cardPadding, y, textPaint);
                y += textLineHeight;
                wrappedLine = wrappedLine.substring(cut);
            }
            canvas.drawText(wrappedLine, cardLeft + cardPadding, y, textPaint);
            y += textLineHeight;
        }

        return cardBottom + dpToPx(ctx, 20);
    }

    /** 绘制底部：二维码 + 下载引导 */
    private static void drawFooter(Canvas canvas, Context ctx, int height) {
        int footerHeight = dpToPx(ctx, 250);
        int footerTop = height - footerHeight;
        float cornerRadius = dpToPx(ctx, 20);

        RectF footerRect = new RectF(PADDING, footerTop, WIDTH_PX - PADDING, height - dpToPx(ctx, 20));

        // 背景
        Paint footerBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        LinearGradient footerGradient = new LinearGradient(
            0, footerTop, 0, height - dpToPx(ctx, 20),
            0xFF1E293B, 0xFF0F172A, Shader.TileMode.CLAMP);
        footerBgPaint.setShader(footerGradient);
        footerBgPaint.setShadowLayer(dpToPx(ctx, 12), 0, dpToPx(ctx, 6), 0x22000000);
        canvas.drawRoundRect(footerRect, cornerRadius, cornerRadius, footerBgPaint);

        // 边框
        Paint footerBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        footerBorderPaint.setStyle(Paint.Style.STROKE);
        footerBorderPaint.setStrokeWidth(dpToPx(ctx, 1));
        footerBorderPaint.setColor(0xFF334155);
        canvas.drawRoundRect(footerRect, cornerRadius, cornerRadius, footerBorderPaint);

        int y = footerTop + dpToPx(ctx, 22);

        // 下载标题
        Paint ctaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ctaPaint.setColor(COLOR_ACCENT_CYAN);
        ctaPaint.setTextSize(dpToPx(ctx, 18));
        ctaPaint.setTypeface(Typeface.DEFAULT_BOLD);
        ctaPaint.setFakeBoldText(true);
        ctaPaint.setTextAlign(Paint.Align.CENTER);
        String ctaText = "扫码下载最强AI炒币智能体";
        canvas.drawText(ctaText, WIDTH_PX / 2, y, ctaPaint);

        y += dpToPx(ctx, 26);

        // 生成并绘制二维码
        try {
            String downloadUrl = getDownloadUrl(ctx);
            Bitmap qrBitmap = generateQrCode(ctx, downloadUrl);
            if (qrBitmap != null) {
                int qrLeft = (WIDTH_PX - QR_SIZE) / 2;
                int qrBgPadding = dpToPx(ctx, 10);

                // 二维码白色背景 + 圆角
                Paint qrBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                qrBgPaint.setColor(0xFFFFFFFF);
                RectF qrBgRect = new RectF(
                    qrLeft - qrBgPadding, y - qrBgPadding,
                    qrLeft + QR_SIZE + qrBgPadding, y + QR_SIZE + qrBgPadding);
                canvas.drawRoundRect(qrBgRect, dpToPx(ctx, 12), dpToPx(ctx, 12), qrBgPaint);

                // 边框
                Paint qrBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                qrBorderPaint.setStyle(Paint.Style.STROKE);
                qrBorderPaint.setStrokeWidth(dpToPx(ctx, 2));
                qrBorderPaint.setColor(COLOR_ACCENT_VIOLET);
                canvas.drawRoundRect(qrBgRect, dpToPx(ctx, 12), dpToPx(ctx, 12), qrBorderPaint);

                canvas.drawBitmap(qrBitmap, qrLeft, y, null);
                y += QR_SIZE + qrBgPadding * 2 + dpToPx(ctx, 14);
            }
        } catch (Exception e) {
            Logger.warning(null, "报告分享", "二维码生成失败: " + e.getMessage());
        }

        // 底部提示
        Paint tipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tipPaint.setColor(COLOR_TEXT_MUTED);
        tipPaint.setTextSize(dpToPx(ctx, 12));
        tipPaint.setTextAlign(Paint.Align.CENTER);
        String tipText = "让 AI 帮你做决策 · 智能 · 安全 · 免费";
        canvas.drawText(tipText, WIDTH_PX / 2, height - dpToPx(ctx, 22), tipPaint);
    }

    /** 获取徽章颜色 */
    private static int getBadgeColor(int stars) {
        if (stars >= 4) return COLOR_GREEN_DARK;
        if (stars >= 3) return 0xFFCA8A04;
        if (stars >= 2) return 0xFFEA580C;
        return 0xFFB91C1C;
    }

    /** 根据分数获取颜色 */
    private static int getScoreColor(int score) {
        if (score >= 80) return COLOR_GREEN;
        if (score >= 60) return COLOR_YELLOW;
        if (score >= 40) return COLOR_ORANGE;
        return COLOR_RED;
    }

    /** 调整颜色透明度 */
    private static int adjustAlpha(int color, float factor) {
        int alpha = Math.round(Color.alpha(color) * factor);
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);
        return Color.argb(alpha, red, green, blue);
    }

    /** 调整颜色亮度 */
    private static int adjustBrightness(int color, float factor) {
        int red = Math.min(255, (int) (Color.red(color) * factor));
        int green = Math.min(255, (int) (Color.green(color) * factor));
        int blue = Math.min(255, (int) (Color.blue(color) * factor));
        return Color.argb(Color.alpha(color), red, green, blue);
    }

    /** 生成精简版报告文本 */
    private static String buildCompactReport(TokenRiskAnalyzer.RiskResult result,
                                              String symbol, String contract, String chain) {
        StringBuilder sb = new StringBuilder();

        sb.append("【代币】").append(symbol);
        if (!result.contractSymbol.isEmpty() && !result.contractSymbol.equals(symbol))
            sb.append(" (").append(result.contractSymbol).append(")");
        sb.append("\n");

        sb.append("【链】").append(Logger.getChainChineseName(chain)).append("\n");
        sb.append("【评分】").append(result.score).append("/100 分\n\n");

        sb.append("── 基本信息 ──\n");
        sb.append("合约验证：").append(result.isVerified ? "✅ 已开源" : "❌ 未开源").append("\n");
        sb.append("合约年龄：").append(result.contractAge).append("\n");
        sb.append("持有者数：").append(result.holderCount).append("\n");
        sb.append("Owner：").append(result.ownerAddress).append("\n");
        if (!result.burnPercent.equals("未知")) sb.append("黑洞占比：").append(result.burnPercent).append("\n");
        if (!result.top10Percent.equals("未知")) sb.append("Top10 占比：").append(result.top10Percent).append("\n");
        sb.append("\n");

        sb.append("── LP 流动性 ──\n");
        sb.append(result.lpInfo).append("\n");
        if (!result.lpLockedPercent.equals("未知")) sb.append("LP 锁仓率：").append(result.lpLockedPercent).append("\n");
        sb.append("池子深度：").append(result.poolDepth).append("\n\n");

        if (!result.riskFactors.isEmpty()) {
            sb.append("── 风险因素 ──\n");
            for (String rf : result.riskFactors) {
                if (sb.length() < 3000) sb.append("❌ ").append(rf).append("\n");
            }
            sb.append("\n");
        }

        if (!result.safeFactors.isEmpty()) {
            sb.append("── 安全因素 ──\n");
            for (String sf : result.safeFactors) {
                if (sb.length() < 3500) sb.append("✅ ").append(sf).append("\n");
            }
            sb.append("\n");
        }

        sb.append("── 综合结论 ──\n");
        sb.append("安全评分：").append(result.score).append("/100 分\n");
        sb.append("星级评定：").append(TokenRiskAnalyzer.getStarDisplay(result.stars)).append("\n");
        if (result.isHighRisk) sb.append("⚠️ 高风险代币，AI 建议谨慎操作！\n");
        else sb.append("✅ 风险较低，可正常使用。\n");

        return sb.toString();
    }

    /** 计算文本区域高度 */
    private static int calculateTextAreaHeight(Context ctx, String reportText, int cardPadding) {
        int y = 0;
        y += dpToPx(ctx, 34); // 标题
        y += dpToPx(ctx, 10); // 装饰线
        y += dpToPx(ctx, 28); // 间距
        String[] lines = reportText.split("\n");
        int textLineHeight = dpToPx(ctx, 27);
        int maxTextWidth = WIDTH_PX - PADDING * 2 - cardPadding * 2;
        Paint measurePaint = new Paint();
        measurePaint.setTextSize(dpToPx(ctx, 15));

        for (String line : lines) {
            if (line.trim().isEmpty()) {
                y += dpToPx(ctx, 7);
                continue;
            }
            float w = measurePaint.measureText(line);
            if (w > maxTextWidth) {
                int extraLines = (int) Math.ceil(w / maxTextWidth);
                y += extraLines * textLineHeight;
            } else {
                y += textLineHeight;
            }
        }
        return y;
    }

    /** 测量总高度 */
    private static int measureTotalHeight(Context ctx, TokenRiskAnalyzer.RiskResult result,
                                          String symbol, String reportText) {
        int y = 0;
        y += dpToPx(ctx, 200); // header
        y += dpToPx(ctx, 45); // 徽章
        y += dpToPx(ctx, 16); // 徽章下方
        y += dpToPx(ctx, 110); // 评分概览
        y += dpToPx(ctx, 24); // gap
        // 报告卡片
        int cardPadding = dpToPx(ctx, 28);
        y += cardPadding + calculateTextAreaHeight(ctx, reportText, cardPadding) + cardPadding;
        y += dpToPx(ctx, 20); // gap
        y += dpToPx(ctx, 250); // footer card
        y += dpToPx(ctx, 16); // bottom padding
        return y;
    }

    /** 生成二维码 Bitmap */
    private static Bitmap generateQrCode(Context ctx, String content) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
            hints.put(EncodeHintType.MARGIN, 1);

            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints);
            Bitmap qr = Bitmap.createBitmap(QR_SIZE, QR_SIZE, Bitmap.Config.RGB_565);
            for (int x = 0; x < QR_SIZE; x++) {
                for (int y = 0; y < QR_SIZE; y++) {
                    qr.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return qr;
        } catch (WriterException e) {
            Logger.error(null, "报告分享", "二维码生成失败: " + e.getMessage(), e);
            return null;
        }
    }

    /** 获取配置的 AI 模型名称 */
    public static String getAiModelName(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        return prefs.getString("ai_model_name", DEFAULT_AI_MODEL);
    }

    /** 设置 AI 模型名称 */
    public static void setAiModelName(Context ctx, String name) {
        ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().putString("ai_model_name", name).apply();
    }

    /** 获取下载 URL */
    private static String getDownloadUrl(Context ctx) {
        // 统一使用 GitHub 动态 latest 链接，强制忽略历史 SharedPreferences 中残留的固定版本死链，
        // 确保每次生成的下载二维码都指向仓库最新 release 包。
        // 经 DownloadLink 重写为 dl.redmagic.pro 加速链接，扫码即走 Cloudflare 反代加速。
        return DownloadLink.accelerate(DEFAULT_DOWNLOAD_URL);
    }

    private static int dpToPx(Context ctx, int dp) {
        if (ctx == null) {
            return (int) (dp * 2.75f + 0.5f);
        }
        float density = ctx.getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
