package com.aicryptowallet.app;

import org.json.JSONArray;
import org.json.JSONObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 多链市场数据获取器
 * 支持 Binance API 获取 K 线数据（覆盖主流链）
 */
public class MultiChainMarketData {
    private static final X509TrustManager TRUST_ALL_TM = new X509TrustManager() {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    };

    private static final SSLSocketFactory TRUST_ALL_SSL;
    static {
        SSLSocketFactory sf = null;
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{TRUST_ALL_TM}, new SecureRandom());
            sf = sc.getSocketFactory();
        } catch (Exception ignored) {}
        TRUST_ALL_SSL = sf;
    }

    private static final OkHttpClient client = createClient();

    private static OkHttpClient createClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true);
        if (TRUST_ALL_SSL != null) {
            builder.sslSocketFactory(TRUST_ALL_SSL, TRUST_ALL_TM);
            builder.hostnameVerifier((hostname, session) -> true);
        }
        return builder.build();
    }

    // 链 -> Binance 交易对映射
    private static final Map<String, String> CHAIN_SYMBOLS = new HashMap<>();
    static {
        CHAIN_SYMBOLS.put("ETH", "ETHUSDT");
        CHAIN_SYMBOLS.put("BNB", "BNBUSDT");
        CHAIN_SYMBOLS.put("SOL", "SOLUSDT");
        CHAIN_SYMBOLS.put("TRX", "TRXUSDT");
        CHAIN_SYMBOLS.put("AVAX", "AVAXUSDT");
        CHAIN_SYMBOLS.put("SUI", "SUIUSDT");
        CHAIN_SYMBOLS.put("APT", "APTUSDT");
        CHAIN_SYMBOLS.put("ADA", "ADAUSDT");
        CHAIN_SYMBOLS.put("MATIC", "MATICUSDT");
        CHAIN_SYMBOLS.put("NEAR", "NEARUSDT");
        CHAIN_SYMBOLS.put("FTM", "FTMUSDT");
        CHAIN_SYMBOLS.put("ATOM", "ATOMUSDT");
        CHAIN_SYMBOLS.put("DOT", "DOTUSDT");
        CHAIN_SYMBOLS.put("ALGO", "ALGOUSDT");
        CHAIN_SYMBOLS.put("CELO", "CELOUSDT");
        CHAIN_SYMBOLS.put("XTZ", "XTZUSDT");
        CHAIN_SYMBOLS.put("ONE", "ONEUSDT");
        CHAIN_SYMBOLS.put("DOGE", "DOGEUSDT");
        CHAIN_SYMBOLS.put("XRP", "XRPUSDT");
        CHAIN_SYMBOLS.put("SHIB", "SHIBUSDT");
        CHAIN_SYMBOLS.put("PEPE", "PEPEUSDT");
        CHAIN_SYMBOLS.put("WIF", "WIFUSDT");
        CHAIN_SYMBOLS.put("BONK", "BONKUSDT");
        CHAIN_SYMBOLS.put("FLOKI", "FLOKIUSDT");
        CHAIN_SYMBOLS.put("TRUMP", "TRUMPUSDT");
    }

    // 链 -> DEX Router 映射
    private static final Map<String, String> DEX_ROUTERS = new HashMap<>();
    static {
        DEX_ROUTERS.put("ETH", "0x7a250d5630B4cF539739dF2C5dAcb4c659F2488D"); // Uniswap V2
        DEX_ROUTERS.put("BNB", "0x10ED43C718714eb63d5aA57B78B54704E256024E"); // PancakeSwap V2
        DEX_ROUTERS.put("AVAX", "0x60aE616a2155Ee3d9A68541Ba4544862310933d4"); // Trader Joe
        DEX_ROUTERS.put("MATIC", "0xa5E0829CaCEd8fFDD4De3c43696c57F7D7A678ff"); // QuickSwap
        DEX_ROUTERS.put("FTM", "0xF491e7B69E4244ad4002BC14e878a34207E38c29"); // SpookySwap
        DEX_ROUTERS.put("GLMR", "0x1b02dA8Cb0d097eB8D57A175b88c7D8b47997506"); // StellaSwap
        DEX_ROUTERS.put("CELO", "0x56234Fcba37F33694d6f18460dfcc36464b4fe34"); // Ubeswap
        DEX_ROUTERS.put("ONE", "0x24ad62502d1C652Cc7684081169D04896aC20f30"); // ViperSwap
    }

    // 链 -> Wrapped Token 映射
    private static final Map<String, String> WRAPPED_TOKENS = new HashMap<>();
    static {
        WRAPPED_TOKENS.put("ETH", "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2");
        WRAPPED_TOKENS.put("BNB", "0xbb4CdB9CBd36B01bD1cBaEBF2De08d9173bc095c");
        WRAPPED_TOKENS.put("AVAX", "0xB31f66AA3C1e785363F0875A1B74E27b85FD66c7");
        WRAPPED_TOKENS.put("MATIC", "0x0d500B1d8E8eF31E21C99d1Db9A6444d3ADf1270");
        WRAPPED_TOKENS.put("FTM", "0x21be370D5312f44cB42ce377BC9b8a0cEF1A4C83");
        WRAPPED_TOKENS.put("GLMR", "0xAcc15dC74880C9944775448304B263D191c6077F");
        WRAPPED_TOKENS.put("CELO", "0x471EcE3750Da237f93B8E339c536989b8978a438");
        WRAPPED_TOKENS.put("ONE", "0xcF664087a5bB0237a0BAd6742852ec6c8d69A27a");
    }

    // Gate.io 交易对映射（无需注册，免费公开 API）
    private static final Map<String, String> GATEIO_SYMBOLS = new HashMap<>();
    static {
        GATEIO_SYMBOLS.put("ETH", "ETH_USDT");
        GATEIO_SYMBOLS.put("BNB", "BNB_USDT");
        GATEIO_SYMBOLS.put("SOL", "SOL_USDT");
        GATEIO_SYMBOLS.put("TRX", "TRX_USDT");
        GATEIO_SYMBOLS.put("AVAX", "AVAX_USDT");
        GATEIO_SYMBOLS.put("SUI", "SUI_USDT");
        GATEIO_SYMBOLS.put("APT", "APT_USDT");
        GATEIO_SYMBOLS.put("ADA", "ADA_USDT");
        GATEIO_SYMBOLS.put("MATIC", "POL_USDT");
        GATEIO_SYMBOLS.put("NEAR", "NEAR_USDT");
        GATEIO_SYMBOLS.put("FTM", "FTM_USDT");
        GATEIO_SYMBOLS.put("ATOM", "ATOM_USDT");
        GATEIO_SYMBOLS.put("DOT", "DOT_USDT");
        GATEIO_SYMBOLS.put("ALGO", "ALGO_USDT");
        GATEIO_SYMBOLS.put("CELO", "CELO_USDT");
        GATEIO_SYMBOLS.put("XTZ", "XTZ_USDT");
        GATEIO_SYMBOLS.put("ONE", "ONE_USDT");
        GATEIO_SYMBOLS.put("DOGE", "DOGE_USDT");
        GATEIO_SYMBOLS.put("XRP", "XRP_USDT");
        GATEIO_SYMBOLS.put("SHIB", "SHIB_USDT");
        GATEIO_SYMBOLS.put("PEPE", "PEPE_USDT");
        GATEIO_SYMBOLS.put("WIF", "WIF_USDT");
        GATEIO_SYMBOLS.put("BONK", "BONK_USDT");
        GATEIO_SYMBOLS.put("FLOKI", "FLOKI_USDT");
        GATEIO_SYMBOLS.put("TRUMP", "TRUMP_USDT");
    }

    /**
     * 获取 K 线数据（用于策略分析）
     * @param chain 链名称
     * @param interval K 线周期（1m, 5m, 15m, 1h, 4h, 1d）
     * @param limit K 线数量
     */
    public static MarketData getKlines(String chain, String interval, int limit) throws Exception {
        String symbol = CHAIN_SYMBOLS.get(chain);
        if (symbol == null) throw new Exception("不支持的链: " + chain);

        Exception lastException = null;

        // 路径1：Binance API
        try {
            return getKlinesFromBinance(symbol, interval, limit);
        } catch (Exception e) {
            lastException = e;
        }

        // 路径2：Gate.io API（无需注册，免费公开）
        try {
            return getKlinesFromGateIO(chain, interval, limit);
        } catch (Exception e) {
            lastException = e;
        }

        throw new Exception("所有 K 线数据源均失败（Binance + Gate.io）", lastException);
    }

    private static MarketData getKlinesFromBinance(String symbol, String interval, int limit) throws Exception {
        // 优先使用 Binance CF 镜像（国内可访问），失败后回退主站
        String[] urls = {
            "https://data-api.binance.vision/api/v3/klines?symbol=" + symbol + "&interval=" + interval + "&limit=" + limit,
            "https://api.binance.com/api/v3/klines?symbol=" + symbol + "&interval=" + interval + "&limit=" + limit
        };
        Exception lastEx = null;
        for (String url : urls) {
            try {
                Request request = new Request.Builder().url(url).get().build();
                try (Response response = client.newCall(request).execute()) {
                    String resp = response.body() != null ? response.body().string() : "";
                    JSONArray jsonArr = new JSONArray(resp);
                    return parseKlineArray(jsonArr, symbol, 1, 2, 3, 4, 5);
                }
            } catch (Exception e) {
                lastEx = e;
            }
        }
        throw lastEx != null ? lastEx : new Exception("Binance 所有镜像均失败");
    }

    private static MarketData getKlinesFromGateIO(String chain, String interval, int limit) throws Exception {
        String gateSymbol = GATEIO_SYMBOLS.get(chain);
        if (gateSymbol == null) throw new Exception("Gate.io 不支持该链: " + chain);

        // Gate.io interval 映射：1m/5m/15m/1h/4h/1d → 60/300/900/3600/14400/86400
        Map<String, String> intervalMap = new HashMap<>();
        intervalMap.put("1m", "60");
        intervalMap.put("5m", "300");
        intervalMap.put("15m", "900");
        intervalMap.put("30m", "1800");
        intervalMap.put("1h", "3600");
        intervalMap.put("4h", "14400");
        intervalMap.put("1d", "86400");
        intervalMap.put("1w", "604800");
        String gateInterval = intervalMap.getOrDefault(interval, "3600");

        String url = "https://api.gateio.ws/api/v4/spot/candlesticks?currency_pair=" + gateSymbol
            + "&interval=" + gateInterval + "&limit=" + limit;

        Request request = new Request.Builder()
            .url(url)
            .get()
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONArray jsonArr = new JSONArray(resp);
            // Gate.io 返回格式：[timestamp, volume_quote, close, high, low, open, volume_base, ...]
            return parseKlineArray(jsonArr, chain, 5, 3, 4, 2, 1);
        }
    }

    private static MarketData parseKlineArray(JSONArray jsonArr, String chainLabel,
                                               int openIdx, int highIdx, int lowIdx, int closeIdx, int volumeIdx) throws Exception {
        MarketData data = new MarketData();
        data.symbol = chainLabel;
        int size = jsonArr.length();
        data.prices = new double[size];
        data.volumes = new double[size];
        data.highs = new double[size];
        data.lows = new double[size];

        for (int i = 0; i < size; i++) {
            JSONArray candle = jsonArr.getJSONArray(i);
            data.highs[i] = candle.getDouble(highIdx);
            data.lows[i] = candle.getDouble(lowIdx);
            data.prices[i] = candle.getDouble(closeIdx);
            data.volumes[i] = candle.getDouble(volumeIdx);
        }

        data.currentPrice = data.prices[size - 1];
        if (size > 1) {
            data.change24h = (data.currentPrice - data.prices[0]) / data.prices[0] * 100;
        }
        data.volume24h = data.volumes[size - 1] * data.currentPrice;

        return data;
    }

    /**
     * 获取所有支持的链列表
     */
    public static List<String> getSupportedChains() {
        return new ArrayList<>(CHAIN_SYMBOLS.keySet());
    }

    /**
     * 获取 DEX Router 地址
     */
    public static String getDexRouter(String chain) {
        return DEX_ROUTERS.getOrDefault(chain, "");
    }

    /**
     * 获取 Wrapped Token 地址
     */
    public static String getWrappedToken(String chain) {
        return WRAPPED_TOKENS.getOrDefault(chain, "");
    }

    /**
     * 检查链是否支持 DEX 交易
     */
    public static boolean isDexSupported(String chain) {
        return DEX_ROUTERS.containsKey(chain);
    }

    /**
     * 获取实时价格
     */
    public static double getRealtimePrice(String chain) throws Exception {
        String symbol = CHAIN_SYMBOLS.get(chain);
        if (symbol == null) throw new Exception("不支持的链: " + chain);

        Exception lastException = null;

        try {
            return getPriceFromBinance(symbol);
        } catch (Exception e) {
            lastException = e;
        }

        try {
            return getPriceFromGateIO(chain);
        } catch (Exception e) {
            lastException = e;
        }

        throw new Exception("所有价格数据源均失败", lastException);
    }

    private static double getPriceFromBinance(String symbol) throws Exception {
        String url = "https://api.binance.com/api/v3/ticker/price?symbol=" + symbol;

        Request request = new Request.Builder()
            .url(url)
            .get()
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            return json.getDouble("price");
        }
    }

    private static double getPriceFromGateIO(String chain) throws Exception {
        String gateSymbol = GATEIO_SYMBOLS.get(chain);
        if (gateSymbol == null) throw new Exception("Gate.io 不支持该链: " + chain);

        String url = "https://api.gateio.ws/api/v4/spot/tickers?currency_pair=" + gateSymbol;

        Request request = new Request.Builder()
            .url(url)
            .get()
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONArray arr = new JSONArray(resp);
            if (arr.length() > 0) {
                JSONObject ticker = arr.getJSONObject(0);
                return ticker.getDouble("last");
            }
            throw new Exception("Gate.io 返回空数据");
        }
    }
}
