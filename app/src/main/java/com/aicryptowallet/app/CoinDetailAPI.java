package com.aicryptowallet.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 币种详情数据获取器
 *
 * 设计目标：
 *  - 国内大陆网络环境可用，所有 API 端点国内可直连
 *  - 不依赖 CoinGecko / Etherscan 等被 GFW 阻断的海外 API
 *  - 双源回退：Gate.io（首选） → Binance Cloudflare 镜像（备选）
 *
 * 数据源：
 *  1. Gate.io  -  https://api.gateio.ws  (国内直连，无需 key)
 *  2. Binance CF镜像 - https://data-api.binance.vision  (国内直连)
 *
 * 提供：
 *  - 24h 行情（价格、涨跌、最高/最低、成交量）
 *  - K 线数据（1m / 5m / 15m / 1h / 4h / 1d / 1w，可自定义数量）
 */
public class CoinDetailAPI {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build();

    /** 24h 行情聚合数据 */
    public static class Ticker24h {
        public double lastPrice;
        public double changePercent;     // 24h 涨跌 %
        public double highPrice;
        public double lowPrice;
        public double volumeBase;        // 24h 成交量（基础币）
        public double volumeQuote;       // 24h 成交量（计价币 USDT）
        public boolean success;
        public String source;            // 数据源标识（gate / binance_cf）
    }

    // ============== 24h 行情 ==============

    /**
     * 获取 24h 行情数据
     * @param symbol 币种符号（如 BTC、ETH）
     * @return Ticker24h，失败时 success=false
     */
    public static Ticker24h fetchTicker24h(Context ctx, String symbol) {
        // 1. Gate.io 优先
        Ticker24h t = fetchTickerFromGate(symbol);
        if (t.success) {
            t.source = "gate";
            Logger.success(ctx, "币种详情", symbol + " 24h行情 Gate.io 成功 价格=$" + t.lastPrice);
            return t;
        }

        // 2. Binance CF 镜像
        t = fetchTickerFromBinanceCF(symbol);
        if (t.success) {
            t.source = "binance_cf";
            Logger.success(ctx, "币种详情", symbol + " 24h行情 BinanceCF 成功 价格=$" + t.lastPrice);
            return t;
        }

        Logger.warning(ctx, "币种详情", symbol + " 24h行情所有数据源均失败");
        return t;
    }

    private static Ticker24h fetchTickerFromGate(String symbol) {
        Ticker24h t = new Ticker24h();
        try {
            String pair = symbol.toUpperCase() + "_USDT";
            String url = "https://api.gateio.ws/api/v4/spot/tickers?currency_pair=" + pair;
            Request req = new Request.Builder().url(url)
                .header("Accept", "application/json").get().build();
            try (Response resp = CLIENT.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return t;
                String body = resp.body().string();
                JSONArray arr = new JSONArray(body);
                if (arr.length() == 0) return t;
                JSONObject obj = arr.getJSONObject(0);
                t.lastPrice = Double.parseDouble(obj.optString("last", "0"));
                t.changePercent = Double.parseDouble(obj.optString("change_percentage", "0"));
                t.highPrice = Double.parseDouble(obj.optString("high_24h", "0"));
                t.lowPrice = Double.parseDouble(obj.optString("low_24h", "0"));
                t.volumeBase = Double.parseDouble(obj.optString("base_volume", "0"));
                t.volumeQuote = Double.parseDouble(obj.optString("quote_volume", "0"));
                t.success = t.lastPrice > 0;
            }
        } catch (Exception e) {
            // 静默失败，让上层走下个数据源
        }
        return t;
    }

    private static Ticker24h fetchTickerFromBinanceCF(String symbol) {
        Ticker24h t = new Ticker24h();
        try {
            String pair = symbol.toUpperCase() + "USDT";
            String url = "https://data-api.binance.vision/api/v3/ticker/24hr?symbol=" + pair;
            Request req = new Request.Builder().url(url)
                .header("Accept", "application/json").get().build();
            try (Response resp = CLIENT.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return t;
                String body = resp.body().string();
                JSONObject obj = new JSONObject(body);
                t.lastPrice = Double.parseDouble(obj.optString("lastPrice", "0"));
                t.changePercent = Double.parseDouble(obj.optString("priceChangePercent", "0"));
                t.highPrice = Double.parseDouble(obj.optString("highPrice", "0"));
                t.lowPrice = Double.parseDouble(obj.optString("lowPrice", "0"));
                t.volumeBase = Double.parseDouble(obj.optString("volume", "0"));
                t.volumeQuote = Double.parseDouble(obj.optString("quoteVolume", "0"));
                t.success = t.lastPrice > 0;
            }
        } catch (Exception e) {
            // 静默失败
        }
        return t;
    }

    // ============== K 线数据 ==============

    /**
     * 获取 K 线数据
     * @param symbol   币种符号（BTC、ETH）
     * @param interval 周期：1m/5m/15m/1h/4h/1d/1w
     * @param limit    数量（最大 1000）
     * @return K 线列表，失败返回空列表
     */
    public static List<SimpleKlineView.Kline> fetchKlines(Context ctx, String symbol, String interval, int limit) {
        // 1. Gate.io
        List<SimpleKlineView.Kline> klines = fetchKlinesFromGate(symbol, interval, limit);
        if (!klines.isEmpty()) {
            Logger.success(ctx, "币种详情", symbol + " K线 Gate.io 成功 " + klines.size() + " 根 interval=" + interval);
            return klines;
        }

        // 2. Binance CF 镜像
        klines = fetchKlinesFromBinanceCF(symbol, interval, limit);
        if (!klines.isEmpty()) {
            Logger.success(ctx, "币种详情", symbol + " K线 BinanceCF 成功 " + klines.size() + " 根 interval=" + interval);
            return klines;
        }

        Logger.warning(ctx, "币种详情", symbol + " K线所有数据源均失败 interval=" + interval);
        return klines;
    }

    /** Gate.io K 线：interval 用 1m/5m/15m/1h/4h/1d/1w */
    private static List<SimpleKlineView.Kline> fetchKlinesFromGate(String symbol, String interval, int limit) {
        List<SimpleKlineView.Kline> list = new ArrayList<>();
        try {
            String pair = symbol.toUpperCase() + "_USDT";
            String url = "https://api.gateio.ws/api/v4/spot/candlesticks?currency_pair=" + pair
                + "&interval=" + interval + "&limit=" + limit;
            Request req = new Request.Builder().url(url)
                .header("Accept", "application/json").get().build();
            try (Response resp = CLIENT.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return list;
                String body = resp.body().string();
                JSONArray arr = new JSONArray(body);
                for (int i = 0; i < arr.length(); i++) {
                    JSONArray k = arr.getJSONArray(i);
                    // Gate.io: [time_sec, volume_quote, close, high, low, open, volume_base, name]
                    long time = k.optLong(0) * 1000;
                    double open = k.optDouble(5);
                    double high = k.optDouble(3);
                    double low = k.optDouble(4);
                    double close = k.optDouble(2);
                    double volume = k.optDouble(6);
                    if (close > 0) {
                        list.add(new SimpleKlineView.Kline(time, open, high, low, close, volume));
                    }
                }
            }
        } catch (Exception e) {
            // 静默失败
        }
        return list;
    }

    /** Binance CF K 线：interval 用 1m/5m/15m/1h/4h/1d/1w */
    private static List<SimpleKlineView.Kline> fetchKlinesFromBinanceCF(String symbol, String interval, int limit) {
        List<SimpleKlineView.Kline> list = new ArrayList<>();
        try {
            String pair = symbol.toUpperCase() + "USDT";
            String url = "https://data-api.binance.vision/api/v3/klines?symbol=" + pair
                + "&interval=" + interval + "&limit=" + limit;
            Request req = new Request.Builder().url(url)
                .header("Accept", "application/json").get().build();
            try (Response resp = CLIENT.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return list;
                String body = resp.body().string();
                JSONArray arr = new JSONArray(body);
                for (int i = 0; i < arr.length(); i++) {
                    JSONArray k = arr.getJSONArray(i);
                    // Binance: [openTime, open, high, low, close, volume, closeTime, ...]
                    long time = k.optLong(0);
                    double open = Double.parseDouble(k.optString(1, "0"));
                    double high = Double.parseDouble(k.optString(2, "0"));
                    double low = Double.parseDouble(k.optString(3, "0"));
                    double close = Double.parseDouble(k.optString(4, "0"));
                    double volume = Double.parseDouble(k.optString(5, "0"));
                    if (close > 0) {
                        list.add(new SimpleKlineView.Kline(time, open, high, low, close, volume));
                    }
                }
            }
        } catch (Exception e) {
            // 静默失败
        }
        return list;
    }

    /** 周期符号 → 显示名称 */
    public static String intervalLabel(String interval) {
        switch (interval) {
            case "1m": return "1分";
            case "5m": return "5分";
            case "15m": return "15分";
            case "1h": return "1时";
            case "4h": return "4时";
            case "1d": return "1天";
            case "1w": return "1周";
            default: return interval;
        }
    }
}
