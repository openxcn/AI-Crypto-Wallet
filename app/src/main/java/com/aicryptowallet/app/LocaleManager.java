package com.aicryptowallet.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

/**
 * 语言管理器：持久化并应用用户选择的显示语言
 */
public class LocaleManager {

    private static final String PREFS_NAME = "locale_prefs";
    private static final String KEY_LANGUAGE = "selected_language";

    // 与 showLanguageDialog 中选项顺序保持一致
    public static final String[] SUPPORTED_LANGUAGES = {"简体中文", "繁體中文", "English", "日本語", "Deutsch"};

    /**
     * 获取持久化的语言标签（显示名），默认简体中文
     */
    public static String getSelectedLanguageLabel(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_LANGUAGE, SUPPORTED_LANGUAGES[0]);
    }

    /**
     * 设置并持久化语言
     */
    public static void setSelectedLanguage(Context ctx, String languageLabel) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LANGUAGE, languageLabel).apply();
    }

    /**
     * 根据显示名获取 Locale
     */
    public static Locale getLocaleForLanguage(String languageLabel) {
        switch (languageLabel) {
            case "English":
                return Locale.ENGLISH;
            case "日本語":
                return Locale.JAPANESE;
            case "Deutsch":
                return Locale.GERMAN;
            case "繁體中文":
                return Locale.TRADITIONAL_CHINESE;
            case "简体中文":
            default:
                return Locale.SIMPLIFIED_CHINESE;
        }
    }

    /**
     * 获取当前生效的 Locale
     */
    public static Locale getCurrentLocale(Context ctx) {
        return getLocaleForLanguage(getSelectedLanguageLabel(ctx));
    }

    /**
     * 包装 Context，用于在 Application/Activity attachBaseContext 中应用语言
     */
    public static Context applyLocale(Context base) {
        Locale locale = getCurrentLocale(base);
        return updateResources(base, locale);
    }

    /**
     * 在 Application.onCreate 中调用，全局生效
     */
    public static void applyLocale(Activity activity) {
        Locale locale = getCurrentLocale(activity);
        updateResources(activity, locale);
    }

    private static Context updateResources(Context context, Locale locale) {
        Locale.setDefault(locale);
        Resources res = context.getResources();
        Configuration config = new Configuration(res.getConfiguration());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale);
            config.setLocales(new LocaleList(locale));
            return context.createConfigurationContext(config);
        } else {
            config.locale = locale;
            res.updateConfiguration(config, res.getDisplayMetrics());
            return context;
        }
    }
}
