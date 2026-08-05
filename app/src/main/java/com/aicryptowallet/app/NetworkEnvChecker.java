package com.aicryptowallet.app;

import android.content.Context;
import android.telephony.TelephonyManager;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 网络环境检测器。
 *
 * 通过多种轻量方式判断用户是否可能处于中国大陆网络环境：
 * 1. 系统国家/地区代码（CN）
 * 2. 运营商 MCC（460 开头）
 * 3. 时区（Asia/Shanghai、Asia/Chongqing 等）
 * 4. 公网 IP 归属地（调用 ipapi.co 或 ip.sb，超时 3 秒）
 *
 * 任一条件命中即视为"可能在中国大陆"。检测过程不阻塞主线程。
 */
public class NetworkEnvChecker {

    private static final String[] CN_TIMEZONES = {
        "Asia/Shanghai", "Asia/Chongqing", "Asia/Harbin", "Asia/Urumqi", "Asia/Kashgar"
    };

    private static final String[] CN_COUNTRY_HINTS = {
        "CN", "CHINA", "中国"
    };

    // 中国大陆运营商 MCC（避免仅依赖系统语言/时区误报）
    private static final String[] CN_MCCS = {"460", "461"};

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    /**
     * 同步判断当前网络环境是否属于中国大陆。
     *
     * 策略：完全以公网 IP 属地为准。只要 IP 不在中国大陆，即使系统语言是中文、
     * 时区是上海、插着国内 SIM 卡，也视为海外网络环境（可能是翻墙），全部放行。
     * 只有 IP 明确归属中国大陆时，才弹出网络环境提醒。
     */
    public static boolean isMainlandChina(Context ctx) {
        try {
            return isMainlandByIp(ctx);
        } catch (Exception e) {
            Logger.warning(ctx, "网络检测", "检测网络环境异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 软判定：系统语言为中文或 SIM/网络国家码为 CN。
     * 不再单独作为大陆判定依据。
     */
    private static boolean isMainlandByLocaleSoft(Context ctx) {
        try {
            Locale locale = Locale.getDefault();
            if (locale != null) {
                String language = locale.getLanguage();
                if ("zh".equalsIgnoreCase(language)) return true;
            }

            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                if (isCnCountry(tm.getSimCountryIso())) return true;
                if (isCnCountry(tm.getNetworkCountryIso())) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean hasMainlandMcc(Context ctx) {
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return false;
            String mcc = getMccFromOperator(tm.getSimOperator());
            if (mcc == null) mcc = getMccFromOperator(tm.getNetworkOperator());
            if (mcc == null || mcc.isEmpty()) return false;
            for (String cnMcc : CN_MCCS) {
                if (mcc.startsWith(cnMcc)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * 异步检测网络环境，通过回调返回结果。
     */
    public static void checkAsync(Context ctx, NetworkCheckCallback callback) {
        executor.execute(() -> {
            boolean result = isMainlandChina(ctx);
            if (callback != null) {
                callback.onResult(result);
            }
        });
    }

    private static String getMccFromOperator(String operator) {
        if (operator == null || operator.length() < 3) return null;
        return operator.substring(0, 3);
    }

    private static boolean isCnCountry(String country) {
        if (country == null || country.isEmpty()) return false;
        String upper = country.toUpperCase();
        for (String hint : CN_COUNTRY_HINTS) {
            if (upper.equals(hint)) return true;
        }
        return false;
    }

    private static boolean isMainlandByTimezone() {
        try {
            String tz = java.util.TimeZone.getDefault().getID();
            for (String cnTz : CN_TIMEZONES) {
                if (cnTz.equalsIgnoreCase(tz)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * 通过公网 IP 属地判断。
     * 使用 ipapi.co 和 ip.sb 两个服务，任一服务明确返回中国大陆才命中。
     * 严格匹配 "CN" / "China" / "中国"，避免包含 "CN" 子串的其他国家名误报。
     */
    private static boolean isMainlandByIp(Context ctx) {
        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build();

        String[] urls = {
            "https://ipapi.co/country/",
            "https://api.ip.sb/geoip"
        };

        for (String url : urls) {
            try {
                Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 AICryptoWallet/1.0")
                    .build();
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) continue;
                    String body = response.body() != null ? response.body().string() : "";
                    if (body == null || body.isEmpty()) continue;
                    if (isMainlandCountryBody(body)) {
                        return true;
                    }
                }
            } catch (Exception e) {
                // 单个服务失败继续尝试下一个
            }
        }
        return false;
    }

    private static boolean isMainlandCountryBody(String body) {
        String upper = body.toUpperCase().trim();
        // ipapi.co/country/ 返回纯文本 "CN"
        if (upper.equals("CN")) return true;
        // ip.sb/geoip 返回 JSON，其中 country 字段为 "CN" 或 "China"
        if (upper.contains("\"COUNTRY\":\"CN\"")) return true;
        if (upper.contains("\"COUNTRY\":\"CHINA\"")) return true;
        if (upper.contains("\"COUNTRY_CODE\":\"CN\"")) return true;
        if (body.contains("中国")) return true;
        return false;
    }

    public interface NetworkCheckCallback {
        void onResult(boolean isMainlandChina);
    }
}
