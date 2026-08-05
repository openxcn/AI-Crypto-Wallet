package com.aicryptowallet.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.appcompat.app.AppCompatDelegate;

/**
 * 主题管理器：支持跟随系统、深色模式、浅色模式三种模式
 */
public class ThemeManager {

    private static final String PREFS_NAME = "theme_prefs";
    private static final String KEY_THEME_MODE = "theme_mode";
    // 0=跟随系统, 1=深色模式, 2=浅色模式
    public static final int MODE_FOLLOW_SYSTEM = 0;
    public static final int MODE_DARK = 1;
    public static final int MODE_LIGHT = 2;

    public static final String[] MODE_LABELS = {"跟随系统", "深色模式", "浅色模式"};

    public static int getThemeMode(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_THEME_MODE, MODE_FOLLOW_SYSTEM);
    }

    public static void setThemeMode(Context ctx, int mode) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_THEME_MODE, mode).apply();
    }

    /**
     * 在 Application.onCreate 中调用，全局生效
     */
    public static void applyTheme(Context ctx) {
        int mode = getThemeMode(ctx);
        applyMode(mode);
    }

    /**
     * 在 Activity.onCreate 中调用，setContentView 之前
     */
    public static void applyTheme(Activity activity) {
        applyTheme((Context) activity);
    }

    private static void applyMode(int mode) {
        switch (mode) {
            case MODE_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case MODE_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case MODE_FOLLOW_SYSTEM:
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    public static String getCurrentModeLabel(Context ctx) {
        int mode = getThemeMode(ctx);
        if (mode >= 0 && mode < MODE_LABELS.length) {
            return MODE_LABELS[mode];
        }
        return MODE_LABELS[0];
    }
}