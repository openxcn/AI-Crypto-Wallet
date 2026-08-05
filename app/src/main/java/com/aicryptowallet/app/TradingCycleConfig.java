package com.aicryptowallet.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * AI 交易周期配置 - 中长线多周期组合
 *
 * 用户选择：用户可配置周期组合，默认 1h/4h
 *
 * 之前硬编码 5m K 线 + 5min 检查 = 短线高频逻辑，与"中长线炒币助手"定位严重错配
 * 中长线通常需要 1h/4h/1d K 线 + 小时级检查周期
 *
 * 多周期共振：要求多个周期信号同向才出强信号，降低假信号
 */
public class TradingCycleConfig {
    private static final String PREFS_NAME = "trading_cycle_prefs";
    private static final String KEY_PRIMARY_CYCLE = "primary_cycle";    // 主周期
    private static final String KEY_SECONDARY_CYCLE = "secondary_cycle"; // 次周期
    private static final String KEY_TERTIARY_CYCLE = "tertiary_cycle";   // 第三周期（可空）
    private static final String KEY_CHECK_INTERVAL_HOURS = "check_interval_hours";
    private static final String KEY_REQUIRE_RESONANCE = "require_resonance"; // 是否要求多周期共振

    // 可选周期
    public static final String CYCLE_15M = "15m";
    public static final String CYCLE_1H = "1h";
    public static final String CYCLE_4H = "4h";
    public static final String CYCLE_1D = "1d";

    /**
     * 默认配置：1h/4h 双周期，2 小时检查，要求共振
     */
    public static final String DEFAULT_PRIMARY = CYCLE_1H;
    public static final String DEFAULT_SECONDARY = CYCLE_4H;
    public static final String DEFAULT_TERTIARY = "";  // 默认不启用第三周期
    public static final int DEFAULT_CHECK_INTERVAL_HOURS = 2;
    public static final boolean DEFAULT_REQUIRE_RESONANCE = true;

    public static String getPrimaryCycle(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PRIMARY_CYCLE, DEFAULT_PRIMARY);
    }

    public static String getSecondaryCycle(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SECONDARY_CYCLE, DEFAULT_SECONDARY);
    }

    public static String getTertiaryCycle(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TERTIARY_CYCLE, DEFAULT_TERTIARY);
    }

    public static int getCheckIntervalHours(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_CHECK_INTERVAL_HOURS, DEFAULT_CHECK_INTERVAL_HOURS);
    }

    public static boolean isRequireResonance(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_REQUIRE_RESONANCE, DEFAULT_REQUIRE_RESONANCE);
    }

    public static void saveConfig(Context ctx, String primary, String secondary,
                                    String tertiary, int checkIntervalHours,
                                    boolean requireResonance) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PRIMARY_CYCLE, primary)
            .putString(KEY_SECONDARY_CYCLE, secondary)
            .putString(KEY_TERTIARY_CYCLE, tertiary == null ? "" : tertiary)
            .putInt(KEY_CHECK_INTERVAL_HOURS, checkIntervalHours)
            .putBoolean(KEY_REQUIRE_RESONANCE, requireResonance)
            .apply();
    }

    /**
     * 获取所有启用的周期列表
     */
    public static String[] getActiveCycles(Context ctx) {
        String p = getPrimaryCycle(ctx);
        String s = getSecondaryCycle(ctx);
        String t = getTertiaryCycle(ctx);
        java.util.List<String> list = new java.util.ArrayList<>();
        if (p != null && !p.isEmpty()) list.add(p);
        if (s != null && !s.isEmpty()) list.add(s);
        if (t != null && !t.isEmpty()) list.add(t);
        return list.toArray(new String[0]);
    }

    /**
     * 获取检查间隔（毫秒）
     */
    public static long getCheckIntervalMs(Context ctx) {
        return getCheckIntervalHours(ctx) * 60L * 60L * 1000L;
    }

    /**
     * 获取配置摘要（供 UI 展示和 AI 上下文注入）
     */
    public static String getConfigSummary(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("周期配置: ");
        String[] cycles = getActiveCycles(ctx);
        sb.append(String.join(" + ", cycles));
        sb.append(" (");
        if (isRequireResonance(ctx)) sb.append("要求共振");
        else sb.append("不要求共振");
        sb.append("), 检查间隔=").append(getCheckIntervalHours(ctx)).append("小时");
        return sb.toString();
    }
}
