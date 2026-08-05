package com.aicryptowallet.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
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
 * AI 报告分享图片生成器 v2
 * 精美卡片式设计：渐变头部 + 星级徽章 + 圆角卡片 + 二维码下载入口
 */
public class ReportShareGenerator {

    private static final int WIDTH_PX = 1080;
    private static final int PADDING = 48;
    private static final int QR_SIZE = 260;
    private static final String DEFAULT_DOWNLOAD_URL = "https://github.com/user/AICryptoWallet/releases";
    private static final String DEFAULT_AI_MODEL = "DeepSeek AI";

    // 精致配色方案
    private static final int COLOR_BG = 0xFF0D1117;
    private static final int COLOR_BG_GRADIENT_END = 0xFF161B28;
    private static final int COLOR_CARD_BG = 0xFF1C2333;
    private static final int COLOR_CARD_BORDER = 0xFF2D3548;
    private static final int COLOR_HEADER_START = 0xFF6366F1;
    private static final int COLOR_HEADER_MID = 0xFF8B5CF6;
    private static final int COLOR_HEADER_END = 0xFFA855F7;
    private static final int COLOR_TEXT_PRIMARY = 0xFFF1F5F9;
    private static final int COLOR_TEXT_SECONDARY = 0xFF94A3B8;
    private static final int COLOR_TEXT_DIM = 0xFF64748B;
    private static final int COLOR_ACCENT_GREEN = 0xFF22C55E;
    private static final int COLOR_ACCENT_GREEN_DARK = 0xFF15803D;
    private static final int COLOR_ACCENT_RED = 0xFFEF4444;
    private static final int COLOR_ACCENT_YELLOW = 0xFFFBBF24;
    private static final int COLOR_ACCENT_BLUE = 0xFF3B82F6;
    private static final int COLOR_ACCENT_CYAN = 0xFF06B6D4;
    private static final int COLOR_QR_BG = 0xFFFFFFFF;

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
    private static Bitmap generateShareBitmap(Context ctx, TokenRiskAnalyzer.RiskResult result,
                                               String symbol, String contract, String chain) {
        String aiModel = getAiModelName(ctx);
        String reportText = buildCompactReport(result, symbol, contract, chain);
        int height = measureTotalHeight(ctx, result, symbol, reportText);

        Bitmap bitmap = Bitmap.createBitmap(WIDTH_PX, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // 绘制渐变背景
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        LinearGradient bgGradient = new LinearGradient(0, 0, 0, height,
            COLOR_BG, COLOR_BG_GRADIENT_END, Shader.TileMode.CLAMP);
        bgPaint.setShader(bgGradient);
        canvas.drawRect(0, 0, WIDTH_PX, height, bgPaint);

        int y = 0;

        // === 1. 头部 ===
        y = drawHeader(canvas, ctx, aiModel, result, symbol, y);

        // === 2. 星级徽章 ===
        y = drawStarBadge(canvas, ctx, result, y);

        // === 3. 报告内容卡片 ===
        y = drawReportCard(canvas, ctx, reportText, result, y);

        // === 4. 底部：二维码 + 下载引导 ===
        drawFooter(canvas, ctx, y);

        return bitmap;
    }

    /** 绘制头部（渐变 + 装饰圆 + 标题） */
    private static int drawHeader(Canvas canvas, Context ctx, String aiModel,
                                   TokenRiskAnalyzer.RiskResult result, String symbol, int startY) {
        int headerHeight = dpToPx(ctx, 260);

        // 多段渐变背景
        Paint headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        LinearGradient gradient = new LinearGradient(0, 0, WIDTH_PX, headerHeight,
            new int[]{COLOR_HEADER_START, COLOR_HEADER_MID, COLOR_HEADER_END},
            new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        headerPaint.setShader(gradient);
        canvas.drawRect(0, startY, WIDTH_PX, startY + headerHeight, headerPaint);

        // 装饰圆（半透明，营造光晕效果）
        Paint decoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        decoPaint.setColor(0x22FFFFFF);
        canvas.drawCircle(WIDTH_PX - dpToPx(ctx, 60), startY + dpToPx(ctx, 40), dpToPx(ctx, 80), decoPaint);
        decoPaint.setColor(0x11FFFFFF);
        canvas.drawCircle(dpToPx(ctx, 40), startY + dpToPx(ctx, 200), dpToPx(ctx, 100), decoPaint);

        int y = startY + dpToPx(ctx, 45);

        // AI 模型标签（小药丸）
        Paint tagPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tagPaint.setColor(0x33FFFFFF);
        float tagTextSize = dpToPx(ctx, 18);
        Paint tagTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tagTextPaint.setColor(0xCCFFFFFF);
        tagTextPaint.setTextSize(tagTextSize);
        tagTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        String tagText = "  " + aiModel + "  ";
        float tagW = tagTextPaint.measureText(tagText);
        float tagH = tagTextSize + dpToPx(ctx, 12);
        float tagX = (WIDTH_PX - tagW) / 2;
        RectF tagRect = new RectF(tagX, y - tagH + dpToPx(ctx, 4), tagX + tagW, y + dpToPx(ctx, 4));
        canvas.drawRoundRect(tagRect, tagH / 2, tagH / 2, tagPaint);
        canvas.drawText(tagText, tagX, y, tagTextPaint);

        y += dpToPx(ctx, 45);

        // 主标题（大号 + 粗体 + 阴影）
        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(COLOR_TEXT_PRIMARY);
        titlePaint.setTextSize(dpToPx(ctx, 44));
        titlePaint.setTypeface(Typeface.DEFAULT_BOLD);
        titlePaint.setFakeBoldText(true);
        titlePaint.setShadowLayer(dpToPx(ctx, 4), 0, dpToPx(ctx, 2), 0x88000000);
        String title = "让 AI 帮我炒币赚钱";
        float titleWidth = titlePaint.measureText(title);
        canvas.drawText(title, (WIDTH_PX - titleWidth) / 2, y, titlePaint);

        y += dpToPx(ctx, 55);

        // 副标题
        Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        subPaint.setColor(0xBBFFFFFF);
        subPaint.setTextSize(dpToPx(ctx, 26));
        subPaint.setShadowLayer(dpToPx(ctx, 2), 0, dpToPx(ctx, 1), 0x44000000);
        String subTitle = "智能分析代币合约 · 风险一目了然";
        float subWidth = subPaint.measureText(subTitle);
        canvas.drawText(subTitle, (WIDTH_PX - subWidth) / 2, y, subPaint);

        y += dpToPx(ctx, 40);

        // 代币名称行
        Paint tokenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tokenPaint.setColor(COLOR_ACCENT_GREEN);
        tokenPaint.setTextSize(dpToPx(ctx, 28));
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
        int badgeSize = dpToPx(ctx, 100);
        int cx = WIDTH_PX / 2;
        int cy = startY + badgeSize / 2;

        // 徽章背景圆
        Paint badgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int badgeColor = getBadgeColor(result.stars);
        badgeBgPaint.setColor(badgeColor);
        badgeBgPaint.setShadowLayer(dpToPx(ctx, 8), 0, dpToPx(ctx, 4), 0x44000000);
        canvas.drawCircle(cx, cy, badgeSize / 2, badgeBgPaint);

        // 徽章边框
        Paint badgeBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        badgeBorderPaint.setStyle(Paint.Style.STROKE);
        badgeBorderPaint.setStrokeWidth(dpToPx(ctx, 3));
        badgeBorderPaint.setColor(0xFFFFFFFF);
        canvas.drawCircle(cx, cy, badgeSize / 2 - dpToPx(ctx, 2), badgeBorderPaint);

        // 星级文字
        Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        starPaint.setColor(0xFFFFFFFF);
        starPaint.setTextSize(dpToPx(ctx, 28));
        starPaint.setTypeface(Typeface.DEFAULT_BOLD);
        starPaint.setFakeBoldText(true);
        starPaint.setTextAlign(Paint.Align.CENTER);
        String starText = result.stars + "★";
        canvas.drawText(starText, cx, cy + dpToPx(ctx, 10), starPaint);

        // 风险等级文字
        Paint levelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        levelPaint.setColor(0xCCFFFFFF);
        levelPaint.setTextSize(dpToPx(ctx, 16));
        levelPaint.setTextAlign(Paint.Align.CENTER);
        String levelText = TokenRiskAnalyzer.getRiskLevel(result.stars);
        canvas.drawText(levelText, cx, cy + dpToPx(ctx, 35), levelPaint);

        return startY + badgeSize + dpToPx(ctx, 16);
    }

    /** 绘制报告内容卡片（圆角 + 边框） */
    private static int drawReportCard(Canvas canvas, Context ctx, String reportText,
                                       TokenRiskAnalyzer.RiskResult result, int startY) {
        int cardPadding = dpToPx(ctx, 28);
        int cardLeft = PADDING;
        int cardRight = WIDTH_PX - PADDING;

        // 计算文本高度
        int textAreaHeight = calculateTextAreaHeight(ctx, reportText, cardPadding);
        int cardTop = startY;
        int cardBottom = startY + cardPadding + textAreaHeight + cardPadding;
        float cornerRadius = dpToPx(ctx, 20);

        // 卡片背景
        Paint cardBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardBgPaint.setColor(COLOR_CARD_BG);
        cardBgPaint.setShadowLayer(dpToPx(ctx, 6), 0, dpToPx(ctx, 3), 0x33000000);
        RectF cardRect = new RectF(cardLeft, cardTop, cardRight, cardBottom);
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, cardBgPaint);

        // 卡片边框
        Paint cardBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardBorderPaint.setStyle(Paint.Style.STROKE);
        cardBorderPaint.setStrokeWidth(dpToPx(ctx, 1));
        cardBorderPaint.setColor(COLOR_CARD_BORDER);
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, cardBorderPaint);

        // 卡片顶部装饰条
        Paint decoBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        LinearGradient decoGradient = new LinearGradient(
            cardLeft + cornerRadius, cardTop, cardRight - cornerRadius, cardTop,
            COLOR_ACCENT_CYAN, COLOR_HEADER_END, Shader.TileMode.CLAMP);
        decoBarPaint.setShader(decoGradient);
        decoBarPaint.setStrokeWidth(dpToPx(ctx, 4));
        decoBarPaint.setStyle(Paint.Style.STROKE);
        decoBarPaint.setStrokeCap(Paint.Cap.ROUND);
        // 顶部装饰线（圆角矩形内部）
        float decoY = cardTop + dpToPx(ctx, 2);
        canvas.drawLine(cardLeft + cornerRadius, decoY, cardRight - cornerRadius, decoY, decoBarPaint);

        int y = cardTop + cardPadding + dpToPx(ctx, 20);

        // 报告标题
        Paint sectionTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        sectionTitlePaint.setColor(COLOR_ACCENT_CYAN);
        sectionTitlePaint.setTextSize(dpToPx(ctx, 30));
        sectionTitlePaint.setTypeface(Typeface.DEFAULT_BOLD);
        sectionTitlePaint.setFakeBoldText(true);
        canvas.drawText("AI 合约安全分析报告", cardLeft + cardPadding, y, sectionTitlePaint);

        y += dpToPx(ctx, 12);

        // 标题下装饰线
        Paint titleLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titleLinePaint.setColor(COLOR_HEADER_MID);
        titleLinePaint.setStrokeWidth(dpToPx(ctx, 2));
        titleLinePaint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(cardLeft + cardPadding, y,
            cardLeft + cardPadding + dpToPx(ctx, 80), y, titleLinePaint);

        y += dpToPx(ctx, 30);

        // 报告正文
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int textLineHeight = dpToPx(ctx, 34);
        int maxTextWidth = WIDTH_PX - PADDING * 2 - cardPadding * 2;

        String[] lines = reportText.split("\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                y += dpToPx(ctx, 12);
                continue;
            }

            // 根据行内容调整样式
            if (line.startsWith("【")) {
                textPaint.setColor(COLOR_TEXT_PRIMARY);
                textPaint.setTypeface(Typeface.DEFAULT_BOLD);
                textPaint.setFakeBoldText(true);
                textPaint.setTextSize(dpToPx(ctx, 24));
            } else if (line.startsWith("──")) {
                // 分隔线
                textPaint.setColor(COLOR_HEADER_MID);
                textPaint.setTypeface(Typeface.DEFAULT_BOLD);
                textPaint.setFakeBoldText(true);
                textPaint.setTextSize(dpToPx(ctx, 20));
            } else if (line.contains("✅")) {
                textPaint.setColor(COLOR_ACCENT_GREEN);
                textPaint.setTypeface(Typeface.DEFAULT);
                textPaint.setFakeBoldText(false);
                textPaint.setTextSize(dpToPx(ctx, 21));
            } else if (line.contains("⚠️") || line.contains("❌")) {
                textPaint.setColor(COLOR_ACCENT_RED);
                textPaint.setTypeface(Typeface.DEFAULT);
                textPaint.setFakeBoldText(false);
                textPaint.setTextSize(dpToPx(ctx, 21));
            } else if (line.contains("★") || line.contains("☆")) {
                textPaint.setColor(COLOR_ACCENT_YELLOW);
                textPaint.setTypeface(Typeface.DEFAULT_BOLD);
                textPaint.setFakeBoldText(true);
                textPaint.setTextSize(dpToPx(ctx, 23));
            } else {
                textPaint.setColor(COLOR_TEXT_SECONDARY);
                textPaint.setTypeface(Typeface.DEFAULT);
                textPaint.setFakeBoldText(false);
                textPaint.setTextSize(dpToPx(ctx, 21));
            }

            // 文字换行
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
    private static void drawFooter(Canvas canvas, Context ctx, int startY) {
        int y = startY + dpToPx(ctx, 10);

        // 下载引导卡片背景
        int footerCardTop = y;
        int footerCardBottom = y + dpToPx(ctx, 420);
        float cornerRadius = dpToPx(ctx, 20);

        Paint footerBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        LinearGradient footerGradient = new LinearGradient(
            0, footerCardTop, 0, footerCardBottom,
            0xFF1E293B, 0xFF0F172A, Shader.TileMode.CLAMP);
        footerBgPaint.setShader(footerGradient);
        footerBgPaint.setShadowLayer(dpToPx(ctx, 6), 0, dpToPx(ctx, 3), 0x33000000);
        RectF footerRect = new RectF(PADDING, footerCardTop, WIDTH_PX - PADDING, footerCardBottom);
        canvas.drawRoundRect(footerRect, cornerRadius, cornerRadius, footerBgPaint);

        // 边框
        Paint footerBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        footerBorderPaint.setStyle(Paint.Style.STROKE);
        footerBorderPaint.setStrokeWidth(dpToPx(ctx, 1));
        footerBorderPaint.setColor(0xFF334155);
        canvas.drawRoundRect(footerRect, cornerRadius, cornerRadius, footerBorderPaint);

        y += dpToPx(ctx, 40);

        // 醒目下载标题
        Paint ctaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        LinearGradient ctaGradient = new LinearGradient(
            0, y, WIDTH_PX, y,
            COLOR_ACCENT_GREEN, COLOR_ACCENT_CYAN, Shader.TileMode.CLAMP);
        ctaPaint.setShader(ctaGradient);
        ctaPaint.setTextSize(dpToPx(ctx, 34));
        ctaPaint.setTypeface(Typeface.DEFAULT_BOLD);
        ctaPaint.setFakeBoldText(true);
        ctaPaint.setShadowLayer(dpToPx(ctx, 3), 0, dpToPx(ctx, 2), 0x6622C55E);
        ctaPaint.setTextAlign(Paint.Align.CENTER);
        String ctaText = "扫码下载，让 AI 帮你炒币赚钱";
        canvas.drawText(ctaText, WIDTH_PX / 2, y, ctaPaint);

        y += dpToPx(ctx, 50);

        // 副标语
        Paint subCtaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        subCtaPaint.setColor(COLOR_TEXT_SECONDARY);
        subCtaPaint.setTextSize(dpToPx(ctx, 22));
        subCtaPaint.setTextAlign(Paint.Align.CENTER);
        String subCtaText = "AI 合约分析 · 智能风控 · 完全免费";
        canvas.drawText(subCtaText, WIDTH_PX / 2, y, subCtaPaint);

        y += dpToPx(ctx, 35);

        // 生成并绘制二维码
        try {
            String downloadUrl = getDownloadUrl(ctx);
            Bitmap qrBitmap = generateQrCode(ctx, downloadUrl);
            if (qrBitmap != null) {
                int qrLeft = (WIDTH_PX - QR_SIZE) / 2;
                int qrBgPadding = dpToPx(ctx, 14);

                // 二维码白色背景 + 圆角
                Paint qrBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                qrBgPaint.setColor(COLOR_QR_BG);
                qrBgPaint.setShadowLayer(dpToPx(ctx, 4), 0, dpToPx(ctx, 2), 0x44000000);
                RectF qrBgRect = new RectF(
                    qrLeft - qrBgPadding, y - qrBgPadding,
                    qrLeft + QR_SIZE + qrBgPadding, y + QR_SIZE + qrBgPadding);
                canvas.drawRoundRect(qrBgRect, dpToPx(ctx, 16), dpToPx(ctx, 16), qrBgPaint);

                // 绿色边框
                Paint qrBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                qrBorderPaint.setStyle(Paint.Style.STROKE);
                qrBorderPaint.setStrokeWidth(dpToPx(ctx, 3));
                qrBorderPaint.setColor(COLOR_ACCENT_GREEN);
                canvas.drawRoundRect(qrBgRect, dpToPx(ctx, 16), dpToPx(ctx, 16), qrBorderPaint);

                canvas.drawBitmap(qrBitmap, qrLeft, y, null);
                y += QR_SIZE + qrBgPadding * 2 + dpToPx(ctx, 16);
            }
        } catch (Exception e) {
            Logger.warning(null, "报告分享", "二维码生成失败: " + e.getMessage());
        }

        // 底部提示
        Paint tipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tipPaint.setColor(COLOR_TEXT_DIM);
        tipPaint.setTextSize(dpToPx(ctx, 18));
        tipPaint.setTextAlign(Paint.Align.CENTER);
        String tipText = "长按识别二维码 · 安全下载 · 完全免费";
        canvas.drawText(tipText, WIDTH_PX / 2, y, tipPaint);
    }

    /** 获取徽章颜色 */
    private static int getBadgeColor(int stars) {
        if (stars >= 4) return COLOR_ACCENT_GREEN_DARK;
        if (stars >= 3) return 0xFFCA8A04; // 黄色
        if (stars >= 2) return 0xFFEA580C; // 橙色
        return 0xFFB91C1C; // 红色
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

        // 基本信息
        sb.append("── 基本信息 ──\n");
        sb.append("合约验证：").append(result.isVerified ? "✅ 已开源" : "❌ 未开源").append("\n");
        sb.append("合约年龄：").append(result.contractAge).append("\n");
        sb.append("持有者数：").append(result.holderCount).append("\n");
        sb.append("Owner：").append(result.ownerAddress).append("\n");
        if (!result.burnPercent.equals("未知")) sb.append("黑洞占比：").append(result.burnPercent).append("\n");
        if (!result.top10Percent.equals("未知")) sb.append("Top10 占比：").append(result.top10Percent).append("\n");
        sb.append("\n");

        // LP 流动性
        sb.append("── LP 流动性 ──\n");
        sb.append(result.lpInfo).append("\n");
        if (!result.lpLockedPercent.equals("未知")) sb.append("LP 锁仓率：").append(result.lpLockedPercent).append("\n");
        sb.append("池子深度：").append(result.poolDepth).append("\n\n");

        // 风险因素
        if (!result.riskFactors.isEmpty()) {
            sb.append("── 风险因素 ──\n");
            for (String rf : result.riskFactors) {
                if (sb.length() < 3000) sb.append("❌ ").append(rf).append("\n");
            }
            sb.append("\n");
        }

        // 安全因素
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
        y += dpToPx(ctx, 50); // 标题
        y += dpToPx(ctx, 12); // 装饰线
        y += dpToPx(ctx, 30); // 间距
        String[] lines = reportText.split("\n");
        int textLineHeight = dpToPx(ctx, 34);
        int maxTextWidth = WIDTH_PX - PADDING * 2 - cardPadding * 2;
        Paint measurePaint = new Paint();
        measurePaint.setTextSize(dpToPx(ctx, 21));

        for (String line : lines) {
            if (line.trim().isEmpty()) {
                y += dpToPx(ctx, 12);
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
        y += dpToPx(ctx, 260); // header
        y += dpToPx(ctx, 100) + dpToPx(ctx, 16); // 星级徽章
        // 卡片
        int cardPadding = dpToPx(ctx, 28);
        y += cardPadding + calculateTextAreaHeight(ctx, reportText, cardPadding) + cardPadding;
        y += dpToPx(ctx, 20); // gap
        y += dpToPx(ctx, 420); // footer card
        y += dpToPx(ctx, 20); // bottom padding
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
        SharedPreferences prefs = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        return prefs.getString("download_url", DEFAULT_DOWNLOAD_URL);
    }

    private static int dpToPx(Context ctx, int dp) {
        float density = ctx.getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}