package com.aicryptowallet.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.icu.text.NumberFormat;
import android.icu.util.Currency;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 货币单位管理器：持久化用户选择、获取实时汇率、统一格式化法币金额
 */
public class CurrencyManager {

    private static final String PREFS_NAME = "currency_prefs";
    private static final String KEY_CURRENCY = "selected_currency";
    private static final String KEY_RATES = "rates_json";
    private static final String KEY_RATES_AT = "rates_updated_at";

    // 默认 USD，保证向后兼容
    public static final String DEFAULT_CURRENCY = "USD";

    // 支持的货币列表
    public static final String[] SUPPORTED_CURRENCIES = {
            "USD", "CNY", "EUR", "JPY", "GBP", "KRW", "RUB", "HKD", "SGD", "AUD"
    };

    // 无注册、免费的汇率源（USD 为基准）
    private static final String RATES_URL = "https://api.exchangerate-api.com/v4/latest/USD";
    private static final long RATES_TTL_MS = 10 * 60 * 1000L; // 10 分钟缓存

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private static final Map<String, Double> FALLBACK_RATES = new HashMap<String, Double>() {{
        put("USD", 1.0);
        put("CNY", 7.25);
        put("EUR", 0.92);
        put("JPY", 150.0);
        put("GBP", 0.79);
        put("KRW", 1340.0);
        put("RUB", 92.0);
        put("HKD", 7.82);
        put("SGD", 1.35);
        put("AUD", 1.53);
    }};

    /**
     * 获取用户当前选中的货币代码
     */
    public static String getSelectedCurrency(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_CURRENCY, DEFAULT_CURRENCY);
    }

    /**
     * 设置用户选中的货币代码
     */
    public static void setSelectedCurrency(Context ctx, String currency) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_CURRENCY, currency).apply();
    }

    /**
     * 获取货币符号（如 USD -> $）
     */
    public static String getCurrencySymbol(String currencyCode) {
        try {
            java.util.Currency currency = java.util.Currency.getInstance(currencyCode);
            return currency.getSymbol();
        } catch (Exception e) {
            return currencyCode;
        }
    }

    /**
     * 将 USD 金额转换为用户选中的货币
     */
    public static double convertFromUsd(Context ctx, double usdAmount) {
        String target = getSelectedCurrency(ctx);
        if ("USD".equals(target)) {
            return usdAmount;
        }
        double rate = getRate(ctx, target);
        return usdAmount * rate;
    }

    /**
     * 获取指定货币相对 USD 的汇率
     */
    public static double getRate(Context ctx, String targetCurrency) {
        Map<String, Double> rates = loadRates(ctx);
        Double rate = rates.get(targetCurrency);
        if (rate != null && rate > 0) {
            return rate;
        }
        Double fallback = FALLBACK_RATES.get(targetCurrency);
        return fallback != null ? fallback : 1.0;
    }

    /**
     * 格式化法币金额，带符号、千分位、两位小数
     */
    public static String formatFiat(Context ctx, double usdAmount) {
        String currencyCode = getSelectedCurrency(ctx);
        double amount = convertFromUsd(ctx, usdAmount);
        String symbol = getCurrencySymbol(currencyCode);

        // 日元、韩元等通常不用小数
        int fractionDigits = ("JPY".equals(currencyCode) || "KRW".equals(currencyCode)) ? 0 : 2;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            NumberFormat formatter = NumberFormat.getCurrencyInstance(Locale.getDefault());
            formatter.setCurrency(Currency.getInstance(currencyCode));
            formatter.setMaximumFractionDigits(fractionDigits);
            formatter.setMinimumFractionDigits(fractionDigits);
            return formatter.format(amount);
        } else {
            DecimalFormat df = new DecimalFormat();
            df.setMaximumFractionDigits(fractionDigits);
            df.setMinimumFractionDigits(fractionDigits);
            df.setGroupingUsed(true);
            return symbol + df.format(amount);
        }
    }

    /**
     * 获取 USD -> 目标货币的汇率文本，如 "1 USD ≈ 7.25 CNY"
     */
    public static String getRateDisplayText(Context ctx) {
        String target = getSelectedCurrency(ctx);
        if ("USD".equals(target)) {
            return "1 USD = 1 USD";
        }
        double rate = getRate(ctx, target);
        return String.format(Locale.getDefault(), "1 USD ≈ %.2f %s", rate, target);
    }

    /**
     * 触发汇率刷新（异步）
     */
    public static void refreshRatesAsync(Context ctx) {
        Request request = new Request.Builder()
                .url(RATES_URL)
                .get()
                .build();
        HTTP_CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w("CurrencyManager", "汇率刷新失败: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful() || response.body() == null) {
                    Log.w("CurrencyManager", "汇率接口非成功响应: " + response.code());
                    return;
                }
                try {
                    String json = response.body().string();
                    JSONObject obj = new JSONObject(json);
                    JSONObject ratesObj = obj.getJSONObject("rates");
                    SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    prefs.edit()
                            .putString(KEY_RATES, ratesObj.toString())
                            .putLong(KEY_RATES_AT, System.currentTimeMillis())
                            .apply();
                    Log.i("CurrencyManager", "汇率刷新成功");
                } catch (Exception e) {
                    Log.w("CurrencyManager", "汇率解析失败: " + e.getMessage());
                }
            }
        });
    }

    /**
     * 判断汇率缓存是否过期
     */
    public static boolean isRatesExpired(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long updatedAt = prefs.getLong(KEY_RATES_AT, 0);
        return System.currentTimeMillis() - updatedAt > RATES_TTL_MS;
    }

    private static Map<String, Double> loadRates(Context ctx) {
        Map<String, Double> result = new HashMap<>();
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_RATES, null);
        if (json == null || json.isEmpty()) {
            return result;
        }
        try {
            JSONObject obj = new JSONObject(json);
            for (String code : SUPPORTED_CURRENCIES) {
                if (obj.has(code)) {
                    result.put(code, obj.getDouble(code));
                }
            }
        } catch (Exception e) {
            Log.w("CurrencyManager", "汇率缓存解析失败: " + e.getMessage());
        }
        return result;
    }
}
