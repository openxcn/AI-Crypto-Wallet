package com.aicryptowallet.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import java.util.Random;

/**
 * 本地内置 LOGO 生成器
 *
 * 为代币生成彩色圆形 + 首字母的 LOGO，完全离线可用，零网络依赖。
 * 参考 MetaMask jazzicons 设计思路，使用确定性颜色 + 首字母作为占位图标。
 * 当网络加载失败时，此方案作为最终兜底，确保所有代币都有可辨识的图标。
 */
public class TokenLogoGenerator {

    private static final int SIZE_PX = 128;
    private static final float TEXT_SIZE_RATIO = 0.55f; // 文字大小占图标比例

    // 预定义配色方案（18种颜色，根据合约地址哈希确定性选择）
    private static final int[][] COLOR_SCHEMES = {
        {0xFF667EEA, 0xFF764BA2}, // 蓝紫
        {0xFF4ADE80, 0xFF22C55E}, // 绿色
        {0xFFF59E0B, 0xFFD97706}, // 橙色
        {0xFFEF4444, 0xFFDC2626}, // 红色
        {0xFF8B5CF6, 0xFF7C3AED}, // 紫色
        {0xFF06B6D4, 0xFF0891B2}, // 青色
        {0xFFEC4899, 0xFFDB2777}, // 粉色
        {0xFF14B8A6, 0xFF0D9488}, // 蓝绿
        {0xFFF97316, 0xFFEA580C}, // 深橙
        {0xFF6366F1, 0xFF4F46E5}, // 靛蓝
        {0xFF84CC16, 0xFF65A30D}, // 黄绿
        {0xFFA855F7, 0xFF9333EA}, // 紫罗兰
        {0xFFEAB308, 0xFFCA8A04}, // 金色
        {0xFF3B82F6, 0xFF2563EB}, // 蓝色
        {0xFF22D3EE, 0xFF06B6D4}, // 天蓝
        {0xFFFB923C, 0xFFF97316}, // 浅橙
        {0xFFA78BFA, 0xFF8B5CF6}, // 淡紫
        {0xFF34D399, 0xFF10B981}, // 翡翠绿
    };

    /**
     * 生成代币 LOGO Bitmap（128×128 彩色圆形 + 首字母）
     * @param symbol 代币符号（如 BTC、ETH、USDT）
     * @param contract 合约地址（用于确定性配色）
     */
    public static Bitmap generate(String symbol, String contract) {
        Bitmap bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // 根据合约地址确定性选择配色方案
        int colorIndex = 0;
        if (contract != null && !contract.isEmpty()) {
            colorIndex = Math.abs(contract.toLowerCase().hashCode()) % COLOR_SCHEMES.length;
        } else if (symbol != null && !symbol.isEmpty()) {
            colorIndex = Math.abs(symbol.hashCode()) % COLOR_SCHEMES.length;
        }
        int[] colors = COLOR_SCHEMES[colorIndex];

        float cx = SIZE_PX / 2f;
        float cy = SIZE_PX / 2f;
        float radius = SIZE_PX / 2f - 4;

        // 绘制圆形背景（渐变效果用两个同心圆模拟）
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(colors[0]);
        canvas.drawCircle(cx, cy, radius, bgPaint);

        // 内圈高光
        Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        innerPaint.setColor(colors[1]);
        canvas.drawCircle(cx, cy, radius * 0.72f, innerPaint);

        // 提取首字母（最多2个字符）
        String initials = getInitials(symbol);
        float textSize = SIZE_PX * TEXT_SIZE_RATIO;

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(textSize);
        textPaint.setFakeBoldText(true);
        textPaint.setTextAlign(Paint.Align.CENTER);

        // 根据字符数调整文字大小
        if (initials.length() == 2) {
            textPaint.setTextSize(textSize * 0.85f);
        } else if (initials.length() >= 3) {
            textPaint.setTextSize(textSize * 0.65f);
        }

        // 垂直居中
        Rect textBounds = new Rect();
        textPaint.getTextBounds(initials, 0, initials.length(), textBounds);
        float textY = cy - (textBounds.top + textBounds.bottom) / 2f;

        canvas.drawText(initials, cx, textY, textPaint);

        return bitmap;
    }

    /**
     * 提取代币符号的首字母（最多3个字符）
     */
    private static String getInitials(String symbol) {
        if (symbol == null || symbol.isEmpty()) return "?";
        String s = symbol.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
        if (s.isEmpty()) return symbol.substring(0, Math.min(1, symbol.length())).toUpperCase();
        // 取前2个字符，如果太长取前3个
        if (s.length() <= 3) return s;
        return s.substring(0, 3);
    }

    /**
     * 获取代币的确定性颜色索引（用于外部显示）
     */
    public static int getColorIndex(String contract, String symbol) {
        if (contract != null && !contract.isEmpty()) {
            return Math.abs(contract.toLowerCase().hashCode()) % COLOR_SCHEMES.length;
        }
        if (symbol != null && !symbol.isEmpty()) {
            return Math.abs(symbol.hashCode()) % COLOR_SCHEMES.length;
        }
        return 0;
    }

    /**
     * 获取配色方案的主色
     */
    public static int getPrimaryColor(int colorIndex) {
        return COLOR_SCHEMES[Math.abs(colorIndex) % COLOR_SCHEMES.length][0];
    }
}