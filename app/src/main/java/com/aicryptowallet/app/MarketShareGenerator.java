package com.aicryptowallet.app;

import android.content.Context;
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
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * AI 行情分析分享图生成器（高端版）
 * 设计语言：深空黑底、霓虹渐变、玻璃态卡片、精致排版
 */
public class MarketShareGenerator {

    private static int WIDTH_PX = 1440;
    private static final int PADDING_DP = 36;
    private static int PADDING = 54;
    private static final int QR_SIZE_DP = 110;
    private static int QR_SIZE = 165;
    private static final String DEFAULT_DOWNLOAD_URL = "https://github.com/openxcn/AI-Crypto-Wallet/releases/latest/download/AICryptoWallet-latest-release.apk";

    // 高端深色配色
    private static final int COLOR_BG_TOP = 0xFF0B0E14;
    private static final int COLOR_BG_BOTTOM = 0xFF111827;
    private static final int COLOR_CARD_BG = 0xFF151B26;
    private static final int COLOR_CARD_BG_LIGHT = 0xFF1C2333;
    private static final int COLOR_BORDER = 0xFF2D3548;
    private static final int COLOR_TEXT_PRIMARY = 0xFFF8FAFC;
    private static final int COLOR_TEXT_SECONDARY = 0xFF94A3B8;
    private static final int COLOR_TEXT_MUTED = 0xFF64748B;
    private static final int COLOR_ACCENT = 0xFF8B5CF6;
    private static final int COLOR_ACCENT_2 = 0xFF06B6D4;
    private static final int COLOR_GREEN = 0xFF22C55E;
    private static final int COLOR_RED = 0xFFEF4444;
    private static final int COLOR_GOLD = 0xFFF59E0B;

    private static void initWidth(Context ctx) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int screenWidth = ctx.getResources().getDisplayMetrics().widthPixels;
        // 以屏幕像素宽度为画布宽度，最小 1080，避免过小
        WIDTH_PX = Math.max(screenWidth, 1080);
        PADDING = (int) (PADDING_DP * density + 0.5f);
        QR_SIZE = (int) (QR_SIZE_DP * density + 0.5f);
    }

    public static Bitmap generate(Context ctx, String symbol, String name,
                                   double price, double changePercent, String report) {
        try {
            initWidth(ctx);
            int height = measureHeight(ctx, report);
            Bitmap bitmap = Bitmap.createBitmap(WIDTH_PX, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            // 背景渐变
            drawBackground(canvas, height);

            int y = PADDING;

            // === 顶部品牌区 ===
            y = drawBrandHeader(canvas, ctx, y);
            y += dpToPx(ctx, 28);

            // === 币种信息卡片 ===
            y = drawTokenCard(canvas, ctx, symbol, name, price, changePercent, y);
            y += dpToPx(ctx, 28);

            // === AI 分析结论卡片 ===
            y = drawReportCard(canvas, ctx, report, y);
            y += dpToPx(ctx, 28);

            // === 底部品牌条 ===
            drawFooter(canvas, ctx, height);

            return bitmap;
        } catch (Exception e) {
            Logger.error(null, "MarketShare", "生成分享图失败: " + e.getMessage(), e);
            return null;
        }
    }

    /** 绘制高端渐变背景 + 光晕装饰 */
    private static void drawBackground(Canvas canvas, int height) {
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        LinearGradient gradient = new LinearGradient(
            0, 0, 0, height, COLOR_BG_TOP, COLOR_BG_BOTTOM, Shader.TileMode.CLAMP);
        bg.setShader(gradient);
        canvas.drawRect(0, 0, WIDTH_PX, height, bg);

        // 顶部紫色光晕
        Paint glowTop = new Paint(Paint.ANTI_ALIAS_FLAG);
        RadialGradient rgTop = new RadialGradient(
            WIDTH_PX * 0.8f, dpToPx(null, 120), dpToPx(null, 320),
            new int[]{0x448B5CF6, 0x008B5CF6}, null, Shader.TileMode.CLAMP);
        glowTop.setShader(rgTop);
        canvas.drawRect(0, 0, WIDTH_PX, height, glowTop);

        // 底部青色光晕
        Paint glowBottom = new Paint(Paint.ANTI_ALIAS_FLAG);
        RadialGradient rgBottom = new RadialGradient(
            WIDTH_PX * 0.2f, height - dpToPx(null, 200), dpToPx(null, 360),
            new int[]{0x3306B6D4, 0x0006B6D4}, null, Shader.TileMode.CLAMP);
        glowBottom.setShader(rgBottom);
        canvas.drawRect(0, 0, WIDTH_PX, height, glowBottom);

        // 细网格纹理
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

    /** 顶部品牌区 */
    private static int drawBrandHeader(Canvas canvas, Context ctx, int startY) {
        int y = startY;

        // Logo 圆形渐变
        int logoSize = dpToPx(ctx, 52);
        Paint logoBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        LinearGradient logoGradient = new LinearGradient(
            PADDING, y, PADDING + logoSize, y + logoSize,
            0xFF8B5CF6, 0xFF06B6D4, Shader.TileMode.CLAMP);
        logoBg.setShader(logoGradient);
        canvas.drawCircle(PADDING + logoSize / 2, y + logoSize / 2, logoSize / 2, logoBg);

        Paint logoText = new Paint(Paint.ANTI_ALIAS_FLAG);
        logoText.setColor(COLOR_TEXT_PRIMARY);
        logoText.setTextSize(dpToPx(ctx, 22));
        logoText.setTypeface(Typeface.DEFAULT_BOLD);
        logoText.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("AI", PADDING + logoSize / 2, y + logoSize / 2 + dpToPx(ctx, 8), logoText);

        // 标题
        Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
        title.setColor(COLOR_TEXT_PRIMARY);
        title.setTextSize(dpToPx(ctx, 28));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setFakeBoldText(true);
        canvas.drawText("AI Crypto Wallet", PADDING + logoSize + dpToPx(ctx, 18), y + dpToPx(ctx, 24), title);

        // 副标题
        Paint subtitle = new Paint(Paint.ANTI_ALIAS_FLAG);
        subtitle.setColor(COLOR_TEXT_SECONDARY);
        subtitle.setTextSize(dpToPx(ctx, 16));
        canvas.drawText("智能行情分析", PADDING + logoSize + dpToPx(ctx, 18), y + dpToPx(ctx, 44), subtitle);

        return startY + logoSize;
    }

    /** 币种信息卡片（紧凑版） */
    private static int drawTokenCard(Canvas canvas, Context ctx, String symbol, String name,
                                      double price, double changePercent, int startY) {
        int cardHeight = dpToPx(ctx, 170);
        int cardTop = startY;
        int cardBottom = cardTop + cardHeight;
        float corner = dpToPx(ctx, 20);
        RectF cardRect = new RectF(PADDING, cardTop, WIDTH_PX - PADDING, cardBottom);

        // 卡片背景
        Paint cardBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardBg.setColor(COLOR_CARD_BG);
        canvas.drawRoundRect(cardRect, corner, corner, cardBg);

        // 渐变边框
        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dpToPx(ctx, 2));
        LinearGradient borderGradient = new LinearGradient(
            PADDING, cardTop, WIDTH_PX - PADDING, cardBottom,
            new int[]{0xFF8B5CF6, 0xFF06B6D4, 0xFF8B5CF6},
            new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        borderPaint.setShader(borderGradient);
        canvas.drawRoundRect(
            new RectF(PADDING + dpToPx(ctx, 1), cardTop + dpToPx(ctx, 1),
                WIDTH_PX - PADDING - dpToPx(ctx, 1), cardBottom - dpToPx(ctx, 1)),
            corner, corner, borderPaint);

        int leftX = PADDING + dpToPx(ctx, 28);
        int rightX = WIDTH_PX - PADDING - dpToPx(ctx, 28);
        int topY = cardTop + dpToPx(ctx, 36);

        // 左侧：代币符号
        Paint symbolPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        symbolPaint.setColor(COLOR_TEXT_PRIMARY);
        symbolPaint.setTextSize(dpToPx(ctx, 30));
        symbolPaint.setTypeface(Typeface.DEFAULT_BOLD);
        symbolPaint.setFakeBoldText(true);
        canvas.drawText(symbol, leftX, topY + dpToPx(ctx, 10), symbolPaint);

        // 左侧：代币名称
        Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        namePaint.setColor(COLOR_TEXT_SECONDARY);
        namePaint.setTextSize(dpToPx(ctx, 16));
        canvas.drawText(name, leftX, topY + dpToPx(ctx, 38), namePaint);

        // 右侧：价格
        String priceStr = formatPrice(ctx, price);
        Paint pricePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pricePaint.setColor(COLOR_TEXT_PRIMARY);
        pricePaint.setTextSize(dpToPx(ctx, 28));
        pricePaint.setTypeface(Typeface.DEFAULT_BOLD);
        pricePaint.setFakeBoldText(true);
        pricePaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(priceStr, rightX, topY + dpToPx(ctx, 10), pricePaint);

        // 右侧：涨跌幅标签
        String changeStr = (changePercent >= 0 ? "+" : "") + String.format(Locale.getDefault(), "%.2f", changePercent) + "%";
        boolean isUp = changePercent >= 0;
        int tagColor = isUp ? COLOR_GREEN : COLOR_RED;

        Paint tagBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tagBgPaint.setColor(isUp ? 0x2622C55E : 0x26EF4444);
        float tagTextSize = dpToPx(ctx, 18);
        Paint tagTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tagTextPaint.setColor(tagColor);
        tagTextPaint.setTextSize(tagTextSize);
        tagTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        tagTextPaint.setTextAlign(Paint.Align.RIGHT);

        float tagW = tagTextPaint.measureText(changeStr) + dpToPx(ctx, 20);
        float tagH = tagTextSize + dpToPx(ctx, 9);
        float tagX = rightX - tagW;
        float tagY = topY + dpToPx(ctx, 26);
        RectF tagRect = new RectF(tagX, tagY, tagX + tagW, tagY + tagH);
        canvas.drawRoundRect(tagRect, tagH / 2, tagH / 2, tagBgPaint);
        canvas.drawText(changeStr, rightX - dpToPx(ctx, 10), tagY + tagH - dpToPx(ctx, 6), tagTextPaint);

        // 底部提示文字
        Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hintPaint.setColor(COLOR_TEXT_MUTED);
        hintPaint.setTextSize(dpToPx(ctx, 12));
        hintPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("数据由 AI 智能聚合，仅供参考，不构成投资建议",
            WIDTH_PX / 2f, cardBottom - dpToPx(ctx, 18), hintPaint);

        return cardBottom;
    }

    /** AI 分析结论卡片（紧凑版） */
    private static int drawReportCard(Canvas canvas, Context ctx, String report, int startY) {
        int cardTop = startY;
        int reportHeight = measureReportHeight(ctx, report);
        int cardBottom = cardTop + reportHeight + dpToPx(ctx, 56);
        float corner = dpToPx(ctx, 20);
        RectF cardRect = new RectF(PADDING, cardTop, WIDTH_PX - PADDING, cardBottom);

        // 卡片背景
        Paint cardBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardBg.setColor(COLOR_CARD_BG);
        canvas.drawRoundRect(cardRect, corner, corner, cardBg);

        // 卡片边框
        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dpToPx(ctx, 1));
        borderPaint.setColor(COLOR_BORDER);
        canvas.drawRoundRect(cardRect, corner, corner, borderPaint);

        int ry = cardTop + dpToPx(ctx, 30);

        // 报告标题
        Paint sectionTitle = new Paint(Paint.ANTI_ALIAS_FLAG);
        sectionTitle.setColor(COLOR_TEXT_PRIMARY);
        sectionTitle.setTextSize(dpToPx(ctx, 20));
        sectionTitle.setTypeface(Typeface.DEFAULT_BOLD);
        sectionTitle.setFakeBoldText(true);
        canvas.drawText("AI 分析结论", PADDING + dpToPx(ctx, 28), ry, sectionTitle);

        // 标题下渐变装饰线
        Paint titleLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        LinearGradient lineGradient = new LinearGradient(
            PADDING + dpToPx(ctx, 28), ry + dpToPx(ctx, 8),
            PADDING + dpToPx(ctx, 120), ry + dpToPx(ctx, 8),
            COLOR_ACCENT, COLOR_ACCENT_2, Shader.TileMode.CLAMP);
        titleLinePaint.setShader(lineGradient);
        titleLinePaint.setStrokeWidth(dpToPx(ctx, 2));
        titleLinePaint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(PADDING + dpToPx(ctx, 28), ry + dpToPx(ctx, 8),
            PADDING + dpToPx(ctx, 120), ry + dpToPx(ctx, 8), titleLinePaint);

        ry += dpToPx(ctx, 32);

        // 报告正文
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int maxWidth = WIDTH_PX - PADDING * 2 - dpToPx(ctx, 56);
        int lineHeight = dpToPx(ctx, 27);

        String[] lines = report.split("\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                ry += dpToPx(ctx, 8);
                continue;
            }

            // 小标题：趋势 / 建议 / 支撑/阻力 / 新闻 / 项目 / 声明
            boolean isHeading = line.startsWith("趋势：") || line.startsWith("支撑/阻力：")
                || line.startsWith("建议：") || line.startsWith("新闻：")
                || line.startsWith("项目：") || line.startsWith("声明：");
            // 行情摘要行
            boolean isSummary = line.contains(" / ") || line.startsWith("24h 高：") || line.startsWith("24h 成交额：");

            if (isHeading) {
                textPaint.setColor(COLOR_ACCENT_2);
                textPaint.setTypeface(Typeface.DEFAULT_BOLD);
                textPaint.setFakeBoldText(true);
                textPaint.setTextSize(dpToPx(ctx, 16));
            } else if (isSummary) {
                textPaint.setColor(COLOR_TEXT_SECONDARY);
                textPaint.setTypeface(Typeface.DEFAULT);
                textPaint.setFakeBoldText(false);
                textPaint.setTextSize(dpToPx(ctx, 14));
            } else {
                textPaint.setColor(COLOR_TEXT_PRIMARY);
                textPaint.setTypeface(Typeface.DEFAULT);
                textPaint.setFakeBoldText(false);
                textPaint.setTextSize(dpToPx(ctx, 15));
            }

            while (line.length() > 0) {
                int cut = line.length();
                while (cut > 0 && textPaint.measureText(line.substring(0, cut)) > maxWidth) {
                    cut--;
                }
                if (cut <= 0) cut = 1;
                canvas.drawText(line.substring(0, cut), PADDING + dpToPx(ctx, 28), ry, textPaint);
                ry += lineHeight;
                line = line.substring(cut).trim();
            }
        }

        return cardBottom;
    }

    /** 底部品牌条（带二维码） */
    private static void drawFooter(Canvas canvas, Context ctx, int height) {
        int barHeight = dpToPx(ctx, 250);
        int barTop = height - barHeight;

        // 卡片背景
        float corner = dpToPx(ctx, 20);
        RectF footerRect = new RectF(PADDING, barTop, WIDTH_PX - PADDING, height - dpToPx(ctx, 16));
        Paint footerBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        footerBg.setColor(COLOR_CARD_BG);
        canvas.drawRoundRect(footerRect, corner, corner, footerBg);

        // 边框
        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dpToPx(ctx, 1));
        borderPaint.setColor(COLOR_BORDER);
        canvas.drawRoundRect(footerRect, corner, corner, borderPaint);

        int y = barTop + dpToPx(ctx, 22);

        // 下载引导
        Paint ctaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ctaPaint.setColor(COLOR_ACCENT_2);
        ctaPaint.setTextSize(dpToPx(ctx, 18));
        ctaPaint.setTypeface(Typeface.DEFAULT_BOLD);
        ctaPaint.setFakeBoldText(true);
        ctaPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("扫码下载最强AI炒币智能体", WIDTH_PX / 2f, y, ctaPaint);

        y += dpToPx(ctx, 26);

        // 二维码
        try {
            String downloadUrl = getDownloadUrl(ctx);
            Bitmap qrBitmap = generateQrCode(ctx, downloadUrl);
            if (qrBitmap != null) {
                int qrLeft = (WIDTH_PX - QR_SIZE) / 2;
                int qrBgPadding = dpToPx(ctx, 10);

                Paint qrBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                qrBgPaint.setColor(0xFFFFFFFF);
                RectF qrBgRect = new RectF(
                    qrLeft - qrBgPadding, y - qrBgPadding,
                    qrLeft + QR_SIZE + qrBgPadding, y + QR_SIZE + qrBgPadding);
                canvas.drawRoundRect(qrBgRect, dpToPx(ctx, 12), dpToPx(ctx, 12), qrBgPaint);

                Paint qrBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                qrBorderPaint.setStyle(Paint.Style.STROKE);
                qrBorderPaint.setStrokeWidth(dpToPx(ctx, 2));
                qrBorderPaint.setColor(COLOR_ACCENT);
                canvas.drawRoundRect(qrBgRect, dpToPx(ctx, 12), dpToPx(ctx, 12), qrBorderPaint);

                canvas.drawBitmap(qrBitmap, qrLeft, y, null);
                y += QR_SIZE + qrBgPadding * 2 + dpToPx(ctx, 14);
            }
        } catch (Exception e) {
            Logger.warning(null, "行情分享", "二维码生成失败: " + e.getMessage());
        }

        // 底部品牌
        Paint brand = new Paint(Paint.ANTI_ALIAS_FLAG);
        brand.setColor(COLOR_TEXT_MUTED);
        brand.setTextSize(dpToPx(ctx, 12));
        brand.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("让 AI 帮你做决策 · 智能 · 安全 · 免费",
            WIDTH_PX / 2f, height - dpToPx(ctx, 22), brand);
    }

    private static String getDownloadUrl(Context ctx) {
        // 统一使用 GitHub 动态 latest 链接，强制忽略历史 SharedPreferences 中残留的固定版本死链，
        // 确保每次生成的下载二维码都指向仓库最新 release 包。
        // 经 DownloadLink 重写为 dl.redmagic.pro 加速链接，扫码即走 Cloudflare 反代加速。
        return DownloadLink.accelerate(DEFAULT_DOWNLOAD_URL);
    }

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
            Logger.error(null, "行情分享", "二维码生成失败: " + e.getMessage(), e);
            return null;
        }
    }

    private static int measureHeight(Context ctx, String report) {
        int h = PADDING;
        h += dpToPx(ctx, 52); // header
        h += dpToPx(ctx, 28); // gap
        h += dpToPx(ctx, 170); // token card
        h += dpToPx(ctx, 28); // gap
        h += measureReportHeight(ctx, report) + dpToPx(ctx, 56); // report card
        h += dpToPx(ctx, 28); // gap
        h += dpToPx(ctx, 250); // footer with qr
        return h;
    }

    private static int measureReportHeight(Context ctx, String report) {
        Paint p = new Paint();
        p.setTextSize(dpToPx(ctx, 15));
        int maxWidth = WIDTH_PX - PADDING * 2 - dpToPx(ctx, 56);
        int lineHeight = dpToPx(ctx, 27);
        int h = dpToPx(ctx, 30);
        h += dpToPx(ctx, 32);

        String[] lines = report.split("\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                h += dpToPx(ctx, 8);
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
        if (ctx == null) {
            return (int) (dp * 2.75f + 0.5f);
        }
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
