package com.aicryptowallet.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class ChainAPI {
    private static final MediaType JSON_TYPE = MediaType.parse("application/json");

    // 信任所有证书的 SSLContext（用于绕过国内 SSL 握手干扰）
    private static final javax.net.ssl.SSLSocketFactory TRUST_ALL_SSL_SOCKET_FACTORY;
    private static final javax.net.ssl.X509TrustManager TRUST_ALL_TM;
    static {
        javax.net.ssl.X509TrustManager tm = new javax.net.ssl.X509TrustManager() {
            @Override public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
            @Override public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
            @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
        };
        TRUST_ALL_TM = tm;
        javax.net.ssl.SSLContext sc;
        try {
            sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, new javax.net.ssl.TrustManager[]{tm}, new java.security.SecureRandom());
        } catch (Exception e) {
            sc = null;
        }
        TRUST_ALL_SSL_SOCKET_FACTORY = sc != null ? sc.getSocketFactory() : null;
    }

    // 大幅减少超时时间，避免在中国大陆访问国外 API 时卡死
    // 添加 User-Agent 拦截器：TP节点(bsc.mytokenpocket.vip)要求 User-Agent 头，否则返回"header not found"
    // 修复 SSLHandshakeException: 信任所有证书，绕过国内 SSL 干扰
    private static final OkHttpClient client = createTrustAllClient(10, 15);

    // 批量请求专用：更长的超时（一次发 20+ 个 eth_call）
    // 修复 SSLHandshakeException: connection closed — 不缓存连接，每次新建，避免复用 stale 连接
    private static final OkHttpClient batchClient = createTrustAllClient(15, 60);

    /**
     * 创建信任所有证书的 OkHttpClient（绕过国内 SSL 握手干扰）
     */
    private static OkHttpClient createTrustAllClient(int connectTimeoutSec, int readTimeoutSec) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(new okhttp3.ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            // 强制优先 IPv4：国内手机网络常出现 IPv6 路由不通，导致解析到 IPv6 后 10s 超时"连不上节点"
            .dns(hostname -> {
                java.net.InetAddress[] all = java.net.InetAddress.getAllByName(hostname);
                java.util.List<java.net.InetAddress> v4 = new java.util.ArrayList<>();
                for (java.net.InetAddress a : all) {
                    if (a instanceof java.net.Inet4Address) v4.add(a);
                }
                return v4.isEmpty() ? java.util.Arrays.asList(all) : v4;
            })
            .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0")
                .build()));
        if (TRUST_ALL_SSL_SOCKET_FACTORY != null) {
            builder.sslSocketFactory(TRUST_ALL_SSL_SOCKET_FACTORY, TRUST_ALL_TM);
            builder.hostnameVerifier((hostname, session) -> true);
        }
        return builder.build();
    }

    // 价格 API 专用：更长超时 + 失败重试 + SSL 信任
    private static final OkHttpClient priceClient = createTrustAllClient(8, 10);

    // === SOL/TRX 代币余额缓存（按钱包缓存，避免循环内逐币重复 RPC） ===
    // SOL/TRX 是非 EVM 链，不能用 eth_call 查代币，必须用链原生方法：
    //   SOL: getTokenAccountsByOwner（一次拿到钱包全部 SPL 代币）
    //   TRX: TronGrid /v1/accounts/{address} 返回的 trc20 字段（一次拿到全部 TRC20 余额）
    private static final Map<String, Map<String, Double>> solTokenCache = new HashMap<>();
    private static final Map<String, Long> solTokenCacheTs = new HashMap<>();
    private static final Map<String, Map<String, Double>> trxTokenCache = new HashMap<>();
    private static final Map<String, Long> trxTokenCacheTs = new HashMap<>();
    private static final long TOKEN_CACHE_TTL_MS = 30000L;

    // IP 直连专用 client：用于绕过 SNI 阻断直连 Cloudflare CDN IP。
    // 关键设计：不直接用 IP URL，而是用域名 URL + 自定义 DNS 把域名解析到指定 IP。
    // 这样 TLS 握手的 SNI 字段就是域名（而非 IP），Cloudflare CDN 才会接受连接。
    // 信任所有证书（仅在 IP 直连场景使用，目标 URL 已限制为已知区块链浏览器域名，
    // 即使被 MITM 最多泄露钱包地址这种公开信息，安全风险可控）。
    private static OkHttpClient createBypassClient(final String targetIp) {
        try {
            javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    @Override public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    @Override public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                }
            };
            javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new java.security.SecureRandom());
            final java.net.InetAddress forcedAddr = java.net.InetAddress.getByName(targetIp);
            return new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .sslSocketFactory(sslContext.getSocketFactory(), (javax.net.ssl.X509TrustManager) trustAll[0])
                .hostnameVerifier((hostname, session) -> true)
                .dns(hostname -> java.util.Collections.singletonList(forcedAddr))
                .build();
        } catch (Exception e) {
            return new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
        }
    }

    // chainCode -> {displayName, defaultRpc, symbol, decimals, isEVM}
    public static final String[][] CHAIN_CONFIG = {
        {"ETH",    "Ethereum",     "https://api.dryespah.com/ave_nodes/rpc/eth/sendFastSwapTx", "ETH",  "18", "true"},
        {"BNB",    "BNB Chain",    "https://api.dryespah.com/ave_nodes/rpc/bsc/sendFastSwapTx", "BNB",  "18", "true"},
        {"SOL",    "Solana",       "https://solana1.mytokenpocket.vip",      "SOL",  "9",  "false"},
        {"TRX",    "TRON",         "https://api.xwjtyrs.com/ave_nodes/rpc/tron/sendFastSwapTx", "TRX",  "6",  "false"},
        {"AVAX",   "Avalanche",    "https://avalanche.publicnode.com",      "AVAX", "18", "true"},
        {"SUI",    "Sui",          "https://sui-mainnet.publicnode.com",    "SUI",  "9",  "false"},
        {"APT",    "Aptos",        "https://aptos-mainnet.publicnode.com",  "APT",  "8",  "false"},
        {"ADA",    "Cardano",      "https://api.koios.rest/api/v1",         "ADA",  "6",  "false"},
        {"MATIC",  "Polygon",      "https://matic.mytokenpocket.vip",       "MATIC","18", "true"},
        {"ARB",    "Arbitrum",     "https://arb.mytokenpocket.vip",         "ETH",  "18", "true"},
        {"NEAR",   "Near",         "https://near.publicnode.com",           "NEAR", "24", "false"},
        {"FTM",    "Fantom",       "https://rpc.fantom.network",            "FTM",  "18", "true"},
        {"CORE",   "Core Chain",   "https://core.mytokenpocket.vip",        "CORE", "18", "true"},
        {"ATOM",   "Cosmos Hub",   "https://rest.cosmos.directory/cosmoshub","ATOM","6",  "false"},
        {"DOT",    "Polkadot",     "https://polkadot.publicnode.com",       "DOT",  "10", "false"},
        {"GLMR",   "Moonbeam",     "https://moonbeam.publicnode.com",       "GLMR", "18", "true"},
        {"KAVA",   "Kava",         "https://evm.kava.io",                   "KAVA", "18", "true"},
        {"ALGO",   "Algorand",     "https://mainnet-api.algonode.cloud",    "ALGO", "6",  "false"},
        {"ICP",    "Internet Computer","https://ic0.app",                   "ICP",  "8",  "false"},
        {"CELO",   "Celo",         "https://forno.celo.org",                "CELO", "18", "true"},
        {"XTZ",    "Tezos",        "https://mainnet.api.tez.ie",            "XTZ",  "6",  "false"},
        {"ONE",    "Harmony",      "https://api.s0.t.hmny.io",              "ONE",  "18", "true"},
    };

    private static final Set<String> EVM_CHAINS = new HashSet<>(Arrays.asList(
        "ETH", "BNB", "AVAX", "MATIC", "ARB", "CORE", "FTM", "GLMR", "KAVA", "CELO", "ONE"
    ));

    private static final Map<String, String[]> CHAIN_FALLBACK_RPCS = new HashMap<>();
    static {
        CHAIN_FALLBACK_RPCS.put("ETH", new String[]{
            "https://reth.mytokenpocket.vip",
            "https://eth.mytokenpocket.vip",
            "https://ethereum-rpc.publicnode.com",
            "https://eth.drpc.org"
        });
        CHAIN_FALLBACK_RPCS.put("BNB", new String[]{
            "https://bsc.mytokenpocket.vip",
            "https://bsc-dataseed1.defibit.io",
            "https://bsc-dataseed1.ninicoin.io",
            "https://bsc-rpc.publicnode.com",
            "https://bsc.nodereal.io",
            "https://1rpc.io/bnb",
            "https://bsc-dataseed1.binance.org",
            "https://bsc-dataseed2.binance.org",
            "https://bsc-dataseed3.binance.org",
            "https://bsc-dataseed4.binance.org",
            "https://bsc.publicnode.com",
            "https://bsc.drpc.org",
            "https://bsc.meowrpc.com"
        });
        CHAIN_FALLBACK_RPCS.put("MATIC", new String[]{
            "https://polygon-bor-rpc.publicnode.com",
            "https://polygon.drpc.org"
        });
        CHAIN_FALLBACK_RPCS.put("ARB", new String[]{
            "https://arbitrum-one-rpc.publicnode.com",
            "https://arb1.arbitrum.io/rpc"
        });
        CHAIN_FALLBACK_RPCS.put("AVAX", new String[]{
            "https://avalanche-c-chain-rpc.publicnode.com",
            "https://api.avax.network/ext/bc/C/rpc"
        });
        CHAIN_FALLBACK_RPCS.put("FTM", new String[]{
            "https://fantom-rpc.publicnode.com",
            "https://rpc.ftm.tools"
        });
        CHAIN_FALLBACK_RPCS.put("CORE", new String[]{
            "https://core.publicnode.com",
            "https://rpc.coredao.org"
        });
        CHAIN_FALLBACK_RPCS.put("ONE", new String[]{
            "https://api.s0.t.hmny.io",
            "https://harmony-rpc.publicnode.com"
        });
        CHAIN_FALLBACK_RPCS.put("CELO", new String[]{
            "https://celo-rpc.publicnode.com"
        });
        CHAIN_FALLBACK_RPCS.put("GLMR", new String[]{
            "https://moonbeam-rpc.publicnode.com"
        });
        CHAIN_FALLBACK_RPCS.put("KAVA", new String[]{
            "https://kava-rpc.publicnode.com"
        });
    }

    private static final Map<String, String> COIN_IDS = new HashMap<>();
    private static final Map<String, String> GATE_PAIRS = new HashMap<>();
    static {
        COIN_IDS.put("ETH", "ethereum");
        COIN_IDS.put("BNB", "binancecoin");
        COIN_IDS.put("SOL", "solana");
        COIN_IDS.put("TRX", "tron");
        COIN_IDS.put("AVAX", "avalanche-2");
        COIN_IDS.put("SUI", "sui");
        COIN_IDS.put("APT", "aptos");
        COIN_IDS.put("ADA", "cardano");
        COIN_IDS.put("MATIC", "matic-network");
        COIN_IDS.put("ARB", "arbitrum");
        COIN_IDS.put("CORE", "coredaoorg");
        COIN_IDS.put("NEAR", "near");
        COIN_IDS.put("FTM", "fantom");
        COIN_IDS.put("ATOM", "cosmos");
        COIN_IDS.put("DOT", "polkadot");
        COIN_IDS.put("GLMR", "moonbeam");
        COIN_IDS.put("KAVA", "kava");
        COIN_IDS.put("ALGO", "algorand");
        COIN_IDS.put("ICP", "internet-computer");
        COIN_IDS.put("CELO", "celo");
        COIN_IDS.put("XTZ", "tezos");
        COIN_IDS.put("ONE", "harmony");
        COIN_IDS.put("USDT", "tether");
        COIN_IDS.put("USDC", "usd-coin");
        COIN_IDS.put("BUSD", "binance-usd");

        // Gate.io 交易对（中国可访问，无需API key）
        GATE_PAIRS.put("ETH", "ETH_USDT");
        GATE_PAIRS.put("BNB", "BNB_USDT");
        GATE_PAIRS.put("SOL", "SOL_USDT");
        GATE_PAIRS.put("TRX", "TRX_USDT");
        GATE_PAIRS.put("AVAX", "AVAX_USDT");
        GATE_PAIRS.put("SUI", "SUI_USDT");
        GATE_PAIRS.put("APT", "APT_USDT");
        GATE_PAIRS.put("ADA", "ADA_USDT");
        GATE_PAIRS.put("MATIC", "MATIC_USDT");
        GATE_PAIRS.put("ARB", "ARB_USDT");
        GATE_PAIRS.put("CORE", "CORE_USDT");
        GATE_PAIRS.put("NEAR", "NEAR_USDT");
        GATE_PAIRS.put("FTM", "FTM_USDT");
        GATE_PAIRS.put("ATOM", "ATOM_USDT");
        GATE_PAIRS.put("DOT", "DOT_USDT");
        GATE_PAIRS.put("GLMR", "GLMR_USDT");
        GATE_PAIRS.put("KAVA", "KAVA_USDT");
        GATE_PAIRS.put("ALGO", "ALGO_USDT");
        GATE_PAIRS.put("ICP", "ICP_USDT");
        GATE_PAIRS.put("CELO", "CELO_USDT");
        GATE_PAIRS.put("XTZ", "XTZ_USDT");
        GATE_PAIRS.put("ONE", "ONE_USDT");
        GATE_PAIRS.put("USDT", "USDT_USDC");
        GATE_PAIRS.put("USDC", "USDC_USDT");
        GATE_PAIRS.put("BUSD", "BUSD_USDT");
    }

    /** 链标识色 — 用于链选择器和钱包列表中的链颜色标识 */
    private static final Map<String, String> CHAIN_COLORS = new HashMap<>();
    static {
        CHAIN_COLORS.put("ETH",   "#627EEA");
        CHAIN_COLORS.put("BNB",   "#F3BA2F");
        CHAIN_COLORS.put("SOL",   "#9945FF");
        CHAIN_COLORS.put("TRX",   "#FF060A");
        CHAIN_COLORS.put("AVAX",  "#E84142");
        CHAIN_COLORS.put("SUI",   "#4DA2FF");
        CHAIN_COLORS.put("APT",   "#00A3FF");
        CHAIN_COLORS.put("ADA",   "#0033AD");
        CHAIN_COLORS.put("MATIC", "#8247E5");
        CHAIN_COLORS.put("ARB",   "#28A0F0");
        CHAIN_COLORS.put("NEAR",  "#000000");
        CHAIN_COLORS.put("FTM",   "#1969FF");
        CHAIN_COLORS.put("CORE",  "#FF9211");
        CHAIN_COLORS.put("ATOM",  "#2E3148");
        CHAIN_COLORS.put("DOT",   "#E6007A");
        CHAIN_COLORS.put("GLMR",  "#E1147D");
        CHAIN_COLORS.put("KAVA",  "#FF433E");
        CHAIN_COLORS.put("ALGO",  "#000000");
        CHAIN_COLORS.put("ICP",   "#29ABE2");
        CHAIN_COLORS.put("CELO",  "#35D07F");
        CHAIN_COLORS.put("ONE",   "#00AEE9");
    }

    /** 获取链标识色 */
    public static String getChainColor(String chain) {
        String color = CHAIN_COLORS.get(chain);
        if (color != null) return color;
        return getCustomChainColor(chain);
    }

    /** 链代码 -> TrustWallet 开源 LOGO 文件夹名 */
    private static final Map<String, String> CHAIN_LOGO_FOLDERS = new HashMap<String, String>() {{
        put("ETH",   "ethereum");
        put("BNB",   "smartchain");
        put("SOL",   "solana");
        put("TRX",   "tron");
        put("AVAX",  "avalanchec");
        put("SUI",   "sui");
        put("APT",   "aptos");
        put("ADA",   "cardano");
        put("MATIC", "polygon");
        put("ARB",   "arbitrum");
        put("NEAR",  "near");
        put("FTM",   "fantom");
        put("CORE",  "coredao");
        put("ATOM",  "cosmos");
        put("DOT",   "polkadot");
        put("GLMR",  "moonbeam");
        put("KAVA",  "kava");
        put("ALGO",  "algorand");
        put("ICP",   "internetcomputer");
        put("CELO",  "celo");
        put("XTZ",   "tezos");
        put("ONE",   "harmony");
        put("BTC",   "bitcoin");
    }};

    /** 获取开源原生币 LOGO 地址，没有映射时返回 null */
    public static String getChainLogoUrl(String chain) {
        String folder = CHAIN_LOGO_FOLDERS.get(chain != null ? chain.toUpperCase() : "");
        if (folder == null) return null;
        return "https://assets-cdn.trustwallet.com/blockchains/" + folder + "/info/logo.png";
    }

    public static boolean isBuiltinChain(String chain) {
        return CHAIN_COLORS.containsKey(chain);
    }

    // ============================================================
    // 自定义链（用户自己添加）
    // ============================================================
    private static final String CUSTOM_CHAINS_KEY = "custom_chains";
    private static final String[] CUSTOM_COLORS = {
        "#FF5500", "#00CC88", "#CC44FF", "#FF8844", "#44BBFF",
        "#FF4488", "#44FFAA", "#AA44FF", "#FFAA00", "#00AACC"
    };

    public static class CustomChain {
        public String code;
        public String name;
        public String rpc;
        public String symbol;
        public int decimals;
        public boolean isEVM;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("code", code);
                o.put("name", name);
                o.put("rpc", rpc);
                o.put("symbol", symbol);
                o.put("decimals", decimals);
                o.put("isEVM", isEVM);
            } catch (Exception ignored) {}
            return o;
        }

        public static CustomChain fromJson(JSONObject o) {
            CustomChain c = new CustomChain();
            c.code = o.optString("code", "");
            c.name = o.optString("name", "");
            c.rpc = o.optString("rpc", "");
            c.symbol = o.optString("symbol", "");
            c.decimals = o.optInt("decimals", 18);
            c.isEVM = o.optBoolean("isEVM", true);
            return c;
        }
    }

    private static Context appCtx;

    /** 由 Application 初始化，供无参静态方法读取自定义链 */
    public static void init(Context ctx) {
        if (ctx != null) appCtx = ctx.getApplicationContext();
    }

    public static List<CustomChain> getCustomChains(Context ctx) {
        List<CustomChain> list = new ArrayList<>();
        try {
            SharedPreferences prefs = ctx.getSharedPreferences("chain_api", Context.MODE_PRIVATE);
            String json = prefs.getString(CUSTOM_CHAINS_KEY, "");
            if (!json.isEmpty()) {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    list.add(CustomChain.fromJson(arr.getJSONObject(i)));
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    public static void addCustomChain(Context ctx, CustomChain chain) {
        List<CustomChain> list = getCustomChains(ctx);
        for (CustomChain c : list) {
            if (c.code.equals(chain.code)) return;
        }
        list.add(chain);
        saveCustomChains(ctx, list);
    }

    public static void removeCustomChain(Context ctx, String code) {
        List<CustomChain> list = getCustomChains(ctx);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).code.equals(code)) list.remove(i);
        }
        saveCustomChains(ctx, list);
    }

    /** 更新自定义链（code 为标识，按 code 定位替换；不存在则新增）*/
    public static void updateCustomChain(Context ctx, CustomChain chain) {
        List<CustomChain> list = getCustomChains(ctx);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).code.equals(chain.code)) {
                list.set(i, chain);
                saveCustomChains(ctx, list);
                return;
            }
        }
        list.add(chain);
        saveCustomChains(ctx, list);
    }

    private static void saveCustomChains(Context ctx, List<CustomChain> list) {
        JSONArray arr = new JSONArray();
        for (CustomChain c : list) arr.put(c.toJson());
        ctx.getSharedPreferences("chain_api", Context.MODE_PRIVATE)
            .edit().putString(CUSTOM_CHAINS_KEY, arr.toString()).apply();
    }

    public static String getCustomChainColor(String code) {
        int idx = Math.abs(code.hashCode()) % CUSTOM_COLORS.length;
        return CUSTOM_COLORS[idx];
    }

    /** 获取所有可用链（内置 + 自定义）*/
    public static String[][] getAllChainConfigs(Context ctx) {
        List<CustomChain> customs = getCustomChains(ctx);
        int total = CHAIN_CONFIG.length + customs.size();
        String[][] all = new String[total][6];
        System.arraycopy(CHAIN_CONFIG, 0, all, 0, CHAIN_CONFIG.length);
        for (int i = 0; i < customs.size(); i++) {
            CustomChain cc = customs.get(i);
            all[CHAIN_CONFIG.length + i] = new String[]{
                cc.code, cc.name, cc.rpc, cc.symbol,
                String.valueOf(cc.decimals), String.valueOf(cc.isEVM)
            };
        }
        return all;
    }

    public static String getDefaultRpc(String chain) {
        for (String[] c : CHAIN_CONFIG) {
            if (c[0].equals(chain)) return c[2];
        }
        return "";
    }

    public static String getDefaultRpc(Context ctx, String chain) {
        String rpc = getDefaultRpc(chain);
        if (!rpc.isEmpty()) return rpc;
        for (CustomChain cc : getCustomChains(ctx)) {
            if (cc.code.equals(chain)) return cc.rpc;
        }
        return "";
    }

    public static String getChainName(String chain) {
        for (String[] c : CHAIN_CONFIG) {
            if (c[0].equals(chain)) return c[1];
        }
        if (appCtx != null) {
            for (CustomChain cc : getCustomChains(appCtx)) {
                if (cc.code.equals(chain)) return cc.name;
            }
        }
        return chain;
    }

    public static String getChainName(Context ctx, String chain) {
        for (String[] c : CHAIN_CONFIG) {
            if (c[0].equals(chain)) return c[1];
        }
        for (CustomChain cc : getCustomChains(ctx)) {
            if (cc.code.equals(chain)) return cc.name;
        }
        return chain;
    }

    public static String getChainSymbol(String chain) {
        for (String[] c : CHAIN_CONFIG) {
            if (c[0].equals(chain)) return c[3];
        }
        if (appCtx != null) {
            for (CustomChain cc : getCustomChains(appCtx)) {
                if (cc.code.equals(chain)) return cc.symbol;
            }
        }
        return chain;
    }

    public static int getChainDecimals(String chain) {
        for (String[] c : CHAIN_CONFIG) {
            if (c[0].equals(chain)) return Integer.parseInt(c[4]);
        }
        return 18;
    }

    public static boolean isEVM(String chain) {
        return EVM_CHAINS.contains(chain);
    }

    public static boolean isEVM(Context ctx, String chain) {
        if (EVM_CHAINS.contains(chain)) return true;
        for (CustomChain cc : getCustomChains(ctx)) {
            if (cc.code.equals(chain)) return cc.isEVM;
        }
        return false;
    }

    /** 是否为用户自定义链（含币安测试网等测试链）。自定义链上的代币不套用真实价格。 */
    public static boolean isCustomChain(Context ctx, String chain) {
        if (chain == null || chain.isEmpty()) return false;
        for (CustomChain cc : getCustomChains(ctx)) {
            if (cc.code.equals(chain)) return true;
        }
        return false;
    }

    /**
     * 在已获取的价格表上取代币价格。
     * 自定义/测试链上的代币一律返回 0，避免测试代币（如测试网 USDT）套用到主网同名代币的真实价格。
     * 复用调用方已获取的 prices，不额外联网。
     */
    public static double resolveTokenPrice(Map<String, Double> prices, Context ctx, String chain, String symbol) {
        if (isCustomChain(ctx, chain)) return 0;
        return prices != null ? prices.getOrDefault(symbol, 0.0) : 0.0;
    }

    /**
     * 在已获取的价格表上取原生币价格。自定义/测试链统一返回 0。
     */
    public static double resolveNativePrice(Map<String, Double> prices, Context ctx, String chain) {
        if (isCustomChain(ctx, chain)) return 0;
        return prices != null ? prices.getOrDefault(chain, 0.0) : 0.0;
    }

    private static String getRpcUrl(Context ctx, String chain) {
        String url = WalletManager.getRpcUrl(ctx, chain);
        // 如果 WalletManager 返回空或默认占位，先回退到自定义链用户填写的 RPC
        if (url == null || url.isEmpty() || "default".equals(url)) {
            for (CustomChain cc : getCustomChains(ctx)) {
                if (cc.code.equals(chain) && cc.rpc != null && !cc.rpc.isEmpty()) {
                    return cc.rpc;
                }
            }
            url = NodeManager.findFirstAvailableNode(chain);
            if (url != null && !url.isEmpty()) {
                Logger.network(ctx, "RPC", "WalletManager 无节点，自动选择 " + Logger.getChainChineseName(chain) + " 节点：" + url);
            }
        }
        Logger.network(ctx, "RPC", "获取 " + Logger.getChainChineseName(chain) + " 节点：" + url);
        return url;
    }

    /**
     * 获取链的 RPC URL（公开方法）- 直接使用 WalletManager
     */
    public static String getRpcUrlStatic(Context ctx, String chain) {
        String url = WalletManager.getRpcUrl(ctx, chain);
        // 自定义链可无预设节点，回退到用户填写的 RPC
        if (url == null || url.isEmpty() || "default".equals(url)) {
            for (CustomChain cc : getCustomChains(ctx)) {
                if (cc.code.equals(chain) && cc.rpc != null && !cc.rpc.isEmpty()) {
                    return cc.rpc;
                }
            }
        }
        return url;
    }

    public static double getNativeBalance(Context ctx, String chain, String address) throws Exception {
        Logger.info(ctx, "余额查询", "开始查询 " + Logger.getChainChineseName(chain) + " 余额");
        try {
            double balance;
            if (isEVM(ctx, chain)) {
                balance = getEVMBalance(ctx, chain, address);
            } else {
                switch (chain) {
                    case "SOL": balance = getSolBalance(ctx, address); break;
                    case "TRX": balance = getTrxBalance(ctx, address); break;
                    case "SUI": balance = getSuiBalance(ctx, address); break;
                    case "APT": balance = getAptosBalance(ctx, address); break;
                    case "ADA": balance = getCardanoBalance(ctx, address); break;
                    case "NEAR": balance = getNearBalance(ctx, address); break;
                    case "ATOM": balance = getCosmosBalance(ctx, address); break;
                    case "DOT": balance = getPolkadotBalance(ctx, address); break;
                    case "ALGO": balance = getAlgorandBalance(ctx, address); break;
                    case "ICP": balance = getICPBalance(ctx, address); break;
                    case "XTZ": balance = getTezosBalance(ctx, address); break;
                    default: balance = 0;
                }
            }
            Logger.success(ctx, "余额查询", Logger.getChainChineseName(chain) + " 余额：" + balance);
            return balance;
        } catch (Exception e) {
            Logger.error(ctx, "余额查询", Logger.getChainChineseName(chain) + " 余额查询失败：" + e.getMessage());
            throw e;
        }
    }

    // === EVM chains ===
    private static double getEVMBalance(Context ctx, String chain, String address) throws Exception {
        String rpcUrl = getRpcUrl(ctx, chain);
        if (rpcUrl == null || rpcUrl.isEmpty()) return 0;

        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("method", "eth_getBalance");
        JSONArray params = new JSONArray();
        params.put(address);
        params.put("latest");
        body.put("params", params);
        body.put("id", 1);

        Request request = new Request.Builder()
            .url(rpcUrl)
            .post(RequestBody.create(body.toString(), JSON_TYPE))
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            if (json.has("error")) {
                // RPC failed, try fallback nodes
                return getEVMBalanceFallback(ctx, chain, address);
            }
            String result = json.getString("result");
            BigInteger wei = new BigInteger(result.substring(2), 16);
            int decimals = getChainDecimals(chain);
            return wei.doubleValue() / Math.pow(10, decimals);
        } catch (java.io.IOException e) {
            // 连接层异常（Socket closed / SocketTimeout 等），切换备用节点
            Logger.warning(ctx, "余额查询", chain + " 主节点连接失败: " + e.getMessage() + "，尝试备用节点");
            return getEVMBalanceFallback(ctx, chain, address);
        }
    }

    private static double getEVMBalanceFallback(Context ctx, String chain, String address) {
        // Try other preset nodes
        NodeManager.NodeEntry[] presets = NodeManager.getPresets(chain);
        String currentRpc = getRpcUrl(ctx, chain);

        for (NodeManager.NodeEntry entry : presets) {
            String rpcUrl = entry.url;
            if (rpcUrl.equals(currentRpc)) continue;

            try {
                JSONObject body = new JSONObject();
                body.put("jsonrpc", "2.0");
                body.put("method", "eth_getBalance");
                JSONArray params = new JSONArray();
                params.put(address);
                params.put("latest");
                body.put("params", params);
                body.put("id", 1);

                Request request = new Request.Builder()
                    .url(rpcUrl)
                    .post(RequestBody.create(body.toString(), JSON_TYPE))
                    .build();

                try (Response response = client.newCall(request).execute()) {
                    String resp = response.body() != null ? response.body().string() : "";
                    JSONObject json = new JSONObject(resp);
                    if (json.has("result")) {
                        // This node works, save it
                        NodeManager.setSelectedNode(ctx, chain, rpcUrl);
                        String result = json.getString("result");
                        BigInteger wei = new BigInteger(result.substring(2), 16);
                        int decimals = getChainDecimals(chain);
                        return wei.doubleValue() / Math.pow(10, decimals);
                    }
                }
            } catch (Exception e) {
                // Try next node
            }
        }

        // 自定义链没有预设节点时，回落已知可靠的公开测试网节点
        String[] fallbacks = getCustomChainFallbackNodes(chain);
        if (fallbacks != null) {
            for (String rpcUrl : fallbacks) {
                if (rpcUrl.equals(currentRpc)) continue;
                try {
                    JSONObject body = new JSONObject();
                    body.put("jsonrpc", "2.0");
                    body.put("method", "eth_getBalance");
                    JSONArray params = new JSONArray();
                    params.put(address);
                    params.put("latest");
                    body.put("params", params);
                    body.put("id", 1);

                    Request request = new Request.Builder()
                        .url(rpcUrl)
                        .post(RequestBody.create(body.toString(), JSON_TYPE))
                        .build();

                    try (Response response = client.newCall(request).execute()) {
                        String resp = response.body() != null ? response.body().string() : "";
                        JSONObject json = new JSONObject(resp);
                        if (json.has("result")) {
                            NodeManager.setSelectedNode(ctx, chain, rpcUrl);
                            String result = json.getString("result");
                            BigInteger wei = new BigInteger(result.substring(2), 16);
                            int decimals = getChainDecimals(chain);
                            return wei.doubleValue() / Math.pow(10, decimals);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return 0;
    }

    /** 为已知的 EVM 测试网自定义链提供可靠的公开备用节点 */
    private static String[] getCustomChainFallbackNodes(String chain) {
        if (chain == null) return null;
        String lower = chain.toLowerCase();
        boolean isBscTest = (lower.contains("bsc") || lower.contains("binance") || lower.contains("bnb"));
        if (isBscTest && (lower.contains("test") || lower.contains("testnet"))) {
            return new String[]{
                "https://bsc-testnet-rpc.publicnode.com",
                "https://data-seed-prebsc-1-s1.bnbchain.org:8545",
                "https://data-seed-prebsc-2-s1.bnbchain.org:8545",
                "https://bsc-testnet.bnbchain.org",
                "https://bsc-testnet-dataseed.bnbchain.org"
            };
        }
        return null;
    }

    /** 直接用指定 client 和 rpcUrl 查代币余额（用于 BSC Binance 节点 IP 直连） */
    private static double getERC20BalanceDirect(OkHttpClient httpClient, String rpcUrl, String tokenContract, String walletAddress, int decimals) throws Exception {
        String data = "0x70a08231000000000000000000000000" + walletAddress.toLowerCase().replace("0x", "");

        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("method", "eth_call");
        JSONArray params = new JSONArray();
        JSONObject callObj = new JSONObject();
        callObj.put("to", tokenContract);
        callObj.put("data", data);
        params.put(callObj);
        params.put("latest");
        body.put("params", params);
        body.put("id", 1);

        Request request = new Request.Builder()
            .url(rpcUrl)
            .post(RequestBody.create(body.toString(), JSON_TYPE))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            String result = json.getString("result");
            if (result.equals("0x") || result.length() < 66) return 0;
            BigInteger balance = new BigInteger(result.substring(2), 16);
            return balance.doubleValue() / Math.pow(10, decimals);
        }
    }

    public static double getERC20Balance(Context ctx, String chain, String walletAddress, String tokenContract, int decimals) {
        // SOL/TRX 是非 EVM 链，不能用 eth_call 查代币余额，必须用链原生方法
        if ("SOL".equals(chain)) {
            try {
                return getSolTokenBalance(ctx, walletAddress, tokenContract, decimals);
            } catch (Exception e) {
                Logger.error(ctx, "代币余额", "SOL 代币查询失败: " + e.getMessage());
                return 0;
            }
        }
        if ("TRX".equals(chain)) {
            try {
                return getTrxTokenBalance(ctx, walletAddress, tokenContract, decimals);
            } catch (Exception e) {
                Logger.error(ctx, "代币余额", "TRX 代币查询失败: " + e.getMessage());
                return 0;
            }
        }

        String rpcUrl = getRpcUrl(ctx, chain);
        if (rpcUrl == null || rpcUrl.isEmpty()) return 0;

        String data = "0x70a08231000000000000000000000000" + walletAddress.toLowerCase().replace("0x", "");

        Request request;
        try {
            JSONObject body = new JSONObject();
            body.put("jsonrpc", "2.0");
            body.put("method", "eth_call");
            JSONArray params = new JSONArray();
            JSONObject callObj = new JSONObject();
            callObj.put("to", tokenContract);
            callObj.put("data", data);
            params.put(callObj);
            params.put("latest");
            body.put("params", params);
            body.put("id", 1);

            request = new Request.Builder()
                .url(rpcUrl)
                .header("User-Agent", "Mozilla/5.0")
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();
        } catch (Exception e) {
            Logger.error(ctx, "代币余额", "构建请求失败: " + e.getMessage());
            return 0;
        }

        int retryCount = 0;
        int maxRetries = 2;
        while (retryCount <= maxRetries) {
            try {
                Logger.info(ctx, "代币余额", "查询 " + chain + " 代币 " + tokenContract + " 钱包=" + walletAddress + " decimals=" + decimals + (retryCount > 0 ? " (重试" + retryCount + ")" : ""));
                try (Response response = client.newCall(request).execute()) {
                    String resp = response.body() != null ? response.body().string() : "";
                    Logger.info(ctx, "代币余额", "RPC 响应: " + resp);
                    JSONObject json = new JSONObject(resp);
                    if (json.has("error")) {
                        Logger.error(ctx, "代币余额", "RPC 返回错误: " + json.optJSONObject("error"));
                        throw new Exception("RPC error");
                    }
                    String result = json.getString("result");
                    if (result.equals("0x") || result.length() < 66) {
                        Logger.info(ctx, "代币余额", "余额为 0（result=" + result + "）");
                        return 0;
                    }
                    BigInteger balance = new BigInteger(result.substring(2), 16);
                    double value = balance.doubleValue() / Math.pow(10, decimals);
                    Logger.success(ctx, "代币余额", "代币 " + tokenContract + " 余额=" + value);
                    return value;
                }
            } catch (Exception e) {
                Logger.error(ctx, "代币余额", "查询代币 " + tokenContract + " 失败(attempt=" + retryCount + "): " + e.getMessage());
                retryCount++;
                if (retryCount <= maxRetries) {
                    try { Thread.sleep(500); } catch (Exception ie) {}
                }
            }
        }
        Logger.warning(ctx, "代币余额", "查询代币 " + tokenContract + " 多次重试失败，返回0");
        return 0;
    }

    // === SOL ===
    private static double getSolBalance(Context ctx, String address) throws Exception {
        String rpcUrl = getRpcUrl(ctx, "SOL");
        try {
            return getSolBalanceFromUrl(rpcUrl, address);
        } catch (Exception e) {
            Logger.warning(ctx, "余额查询", "SOL 主节点失败: " + e.getMessage() + "，尝试备用节点");
            return getSolBalanceFallback(ctx, address);
        }
    }

    private static double getSolBalanceFromUrl(String rpcUrl, String address) throws Exception {
        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("method", "getBalance");
        JSONArray params = new JSONArray();
        params.put(address);
        body.put("params", params);
        body.put("id", 1);

        Request request = new Request.Builder()
            .url(rpcUrl)
            .post(RequestBody.create(body.toString(), JSON_TYPE))
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            if (json.has("error")) {
                throw new Exception("RPC error: " + json.opt("error"));
            }
            JSONObject result = json.getJSONObject("result");
            long lamports = result.getLong("value");
            return lamports / 1e9;
        }
    }

    private static double getSolBalanceFallback(Context ctx, String address) throws Exception {
        NodeManager.NodeEntry[] presets = NodeManager.getPresets("SOL");
        String currentRpc = getRpcUrl(ctx, "SOL");
        Exception lastEx = new Exception("无 SOL 备用节点");
        for (NodeManager.NodeEntry entry : presets) {
            if (entry.url.equals(currentRpc)) continue;
            try {
                double bal = getSolBalanceFromUrl(entry.url, address);
                Logger.success(ctx, "余额查询", "SOL 备用节点成功: " + entry.name);
                return bal;
            } catch (Exception e) {
                lastEx = e;
            }
        }
        throw lastEx;
    }

    // === TRX (Tron) ===
    private static double getTrxBalance(Context ctx, String address) throws Exception {
        String apiUrl = getRpcUrl(ctx, "TRX");
        try {
            return getTrxBalanceFromUrl(apiUrl, address);
        } catch (Exception e) {
            Logger.warning(ctx, "余额查询", "TRX 主节点失败: " + e.getMessage() + "，尝试备用节点");
            return getTrxBalanceFallback(ctx, address);
        }
    }

    private static double getTrxBalanceFromUrl(String apiUrl, String address) throws Exception {
        Request request = new Request.Builder()
            .url(apiUrl + "/v1/accounts/" + address)
            .get()
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            if (json.has("data") && json.getJSONArray("data").length() > 0) {
                JSONObject data = json.getJSONArray("data").getJSONObject(0);
                long sun = data.optLong("balance", 0);
                return sun / 1e6;
            }
            return 0;
        }
    }

    private static double getTrxBalanceFallback(Context ctx, String address) throws Exception {
        NodeManager.NodeEntry[] presets = NodeManager.getPresets("TRX");
        String currentRpc = getRpcUrl(ctx, "TRX");
        Exception lastEx = new Exception("无 TRX 备用节点");
        for (NodeManager.NodeEntry entry : presets) {
            if (entry.url.equals(currentRpc)) continue;
            try {
                double bal = getTrxBalanceFromUrl(entry.url, address);
                Logger.success(ctx, "余额查询", "TRX 备用节点成功: " + entry.name);
                return bal;
            } catch (Exception e) {
                lastEx = e;
            }
        }
        throw lastEx;
    }

    // === SOL SPL 代币余额（非 EVM，不能用 eth_call，改用 getTokenAccountsByOwner） ===
    private static String[] solTokenEndpoints(Context ctx) {
        List<String> list = new ArrayList<>();
        String sel = getRpcUrl(ctx, "SOL");
        if (sel != null && !sel.isEmpty()) list.add(sel);
        for (NodeManager.NodeEntry e : NodeManager.getPresets("SOL")) {
            if (!list.contains(e.url)) list.add(e.url);
        }
        return list.toArray(new String[0]);
    }

    /** 一次 getTokenAccountsByOwner 拉取钱包全部 SPL 代币，返回 mint->原始余额(lamports) */
    private static Map<String, Double> fetchSolTokenBalances(Context ctx, String wallet) throws Exception {
        Exception lastEx = null;
        for (String rpcUrl : solTokenEndpoints(ctx)) {
            try {
                JSONObject body = new JSONObject();
                body.put("jsonrpc", "2.0");
                body.put("id", 1);
                body.put("method", "getTokenAccountsByOwner");
                JSONArray params = new JSONArray();
                params.put(wallet);
                JSONObject cfg = new JSONObject();
                cfg.put("programId", "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
                params.put(cfg);
                JSONObject opt = new JSONObject();
                opt.put("encoding", "jsonParsed");
                opt.put("commitment", "confirmed");
                params.put(opt);
                body.put("params", params);

                Request request = new Request.Builder()
                    .url(rpcUrl)
                    .post(RequestBody.create(body.toString(), JSON_TYPE))
                    .build();
                Map<String, Double> result = new HashMap<>();
                try (Response response = client.newCall(request).execute()) {
                    String resp = response.body() != null ? response.body().string() : "";
                    JSONObject json = new JSONObject(resp);
                    JSONObject res = json.optJSONObject("result");
                    if (res == null) throw new Exception("SOL RPC 无 result: " + resp);
                    JSONArray value = res.optJSONArray("value");
                    if (value != null) {
                        for (int i = 0; i < value.length(); i++) {
                            JSONObject acc = value.optJSONObject(i);
                            if (acc == null) continue;
                            JSONObject account = acc.optJSONObject("account");
                            if (account == null) continue;
                            JSONObject data = account.optJSONObject("data");
                            if (data == null) continue;
                            JSONObject parsed = data.optJSONObject("parsed");
                            if (parsed == null) continue;
                            JSONObject info = parsed.optJSONObject("info");
                            if (info == null) continue;
                            String mint = info.optString("mint", "");
                            JSONObject ta = info.optJSONObject("tokenAmount");
                            if (mint.isEmpty() || ta == null) continue;
                            String amount = ta.optString("amount", "0");
                            double raw;
                            try { raw = Double.parseDouble(amount); } catch (Exception ex) { continue; }
                            if (raw > 0) result.put(mint.toLowerCase(), raw);
                        }
                    }
                }
                return result;
            } catch (Exception e) {
                lastEx = e;
            }
        }
        throw lastEx != null ? lastEx : new Exception("SOL 节点全部不可用");
    }

    private static double getSolTokenBalance(Context ctx, String wallet, String mint, int decimals) throws Exception {
        String key = wallet.toLowerCase();
        Map<String, Double> cache = solTokenCache.get(key);
        Long ts = solTokenCacheTs.get(key);
        if (cache == null || ts == null || System.currentTimeMillis() - ts > TOKEN_CACHE_TTL_MS) {
            cache = fetchSolTokenBalances(ctx, wallet);
            solTokenCache.put(key, cache);
            solTokenCacheTs.put(key, System.currentTimeMillis());
        }
        Double raw = cache.get(mint.toLowerCase());
        if (raw == null) return 0;
        return raw / Math.pow(10, decimals);
    }

    // === TRX TRC20 代币余额（非 EVM，不能用 eth_call，改用 TronGrid /v1/accounts 的 trc20 字段） ===
    private static String[] trxTokenEndpoints(Context ctx) {
        List<String> list = new ArrayList<>();
        String sel = getRpcUrl(ctx, "TRX");
        if (sel != null && !sel.isEmpty()) list.add(sel);
        for (NodeManager.NodeEntry e : NodeManager.getPresets("TRX")) {
            if (!list.contains(e.url)) list.add(e.url);
        }
        return list.toArray(new String[0]);
    }

    /** 一次 /v1/accounts/{address} 拉取钱包全部 TRC20，返回 合约地址->原始余额(sun) */
    private static Map<String, Double> fetchTrxTokenBalances(Context ctx, String wallet) throws Exception {
        Exception lastEx = null;
        for (String base : trxTokenEndpoints(ctx)) {
            try {
                Request request = new Request.Builder()
                    .url(base + "/v1/accounts/" + wallet)
                    .get()
                    .build();
                Map<String, Double> result = new HashMap<>();
                try (Response response = client.newCall(request).execute()) {
                    String resp = response.body() != null ? response.body().string() : "";
                    JSONObject json = new JSONObject(resp);
                    JSONArray data = json.optJSONArray("data");
                    if (data == null || data.length() == 0) return result;
                    JSONArray trc20 = data.getJSONObject(0).optJSONArray("trc20");
                    if (trc20 != null) {
                        for (int i = 0; i < trc20.length(); i++) {
                            JSONObject item = trc20.optJSONObject(i);
                            if (item == null) continue;
                            Iterator<String> it = item.keys();
                            while (it.hasNext()) {
                                String c = it.next().toLowerCase();
                                double raw = item.optDouble(c, 0);
                                if (raw > 0) result.put(c, raw);
                            }
                        }
                    }
                }
                return result;
            } catch (Exception e) {
                lastEx = e;
            }
        }
        throw lastEx != null ? lastEx : new Exception("TRX 节点全部不可用");
    }

    private static double getTrxTokenBalance(Context ctx, String wallet, String contract, int decimals) throws Exception {
        String key = wallet.toLowerCase();
        Map<String, Double> cache = trxTokenCache.get(key);
        Long ts = trxTokenCacheTs.get(key);
        if (cache == null || ts == null || System.currentTimeMillis() - ts > TOKEN_CACHE_TTL_MS) {
            cache = fetchTrxTokenBalances(ctx, wallet);
            trxTokenCache.put(key, cache);
            trxTokenCacheTs.put(key, System.currentTimeMillis());
        }
        Double raw = cache.get(contract.toLowerCase());
        if (raw == null) return 0;
        return raw / Math.pow(10, decimals);
    }

    // === SUI ===
    private static double getSuiBalance(Context ctx, String address) throws Exception {
        String rpcUrl = getRpcUrl(ctx, "SUI");
        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("method", "sui_getBalance");
        JSONArray params = new JSONArray();
        params.put(address);
        params.put("0x2::sui::SUI");
        body.put("params", params);
        body.put("id", 1);

        Request request = new Request.Builder()
            .url(rpcUrl)
            .post(RequestBody.create(body.toString(), JSON_TYPE))
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            JSONObject result = json.getJSONObject("result");
            String totalBalance = result.getString("totalBalance");
            return Long.parseLong(totalBalance) / 1e9;
        }
    }

    // === APT (Aptos) ===
    private static double getAptosBalance(Context ctx, String address) throws Exception {
        String apiUrl = getRpcUrl(ctx, "APT");
        Request request = new Request.Builder()
            .url(apiUrl + "/accounts/" + address + "/balance/0x1::aptos_coin::AptosCoin")
            .header("Accept", "application/json")
            .get()
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            if (json.has("coin")) {
                JSONObject coin = json.getJSONObject("coin");
                String value = coin.getString("value");
                return new BigInteger(value).doubleValue() / 1e8;
            }
            return 0;
        }
    }

    // === ADA (Cardano via Koios) ===
    private static double getCardanoBalance(Context ctx, String address) throws Exception {
        String apiUrl = getRpcUrl(ctx, "ADA");
        JSONObject body = new JSONObject();
        JSONArray arr = new JSONArray();
        arr.put(address);
        body.put("_addresses", arr);

        Request request = new Request.Builder()
            .url(apiUrl + "/address_info")
            .post(RequestBody.create(body.toString(), JSON_TYPE))
            .header("Content-Type", "application/json")
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONArray jsonArr = new JSONArray(resp);
            if (jsonArr.length() > 0) {
                JSONObject info = jsonArr.getJSONObject(0);
                long lovelace = info.getLong("balance");
                return lovelace / 1e6;
            }
            return 0;
        }
    }

    // === NEAR ===
    private static double getNearBalance(Context ctx, String address) throws Exception {
        String rpcUrl = getRpcUrl(ctx, "NEAR");
        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("method", "query");
        JSONObject params = new JSONObject();
        params.put("request_type", "view_account");
        params.put("finality", "final");
        params.put("account_id", address);
        body.put("params", params);
        body.put("id", 1);

        Request request = new Request.Builder()
            .url(rpcUrl)
            .post(RequestBody.create(body.toString(), JSON_TYPE))
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            JSONObject result = json.getJSONObject("result");
            String balance = result.getString("amount");
            return new BigInteger(balance).doubleValue() / 1e24;
        }
    }

    // === ATOM (Cosmos) ===
    private static double getCosmosBalance(Context ctx, String address) throws Exception {
        String apiUrl = getRpcUrl(ctx, "ATOM");
        Request request = new Request.Builder()
            .url(apiUrl + "/cosmos/bank/v1beta1/balances/" + address)
            .get()
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            // Cosmos REST API 返回 {"balances": [...]}，balances 直接是数组
            JSONArray balances = json.getJSONArray("balances");
            for (int i = 0; i < balances.length(); i++) {
                JSONObject bal = balances.getJSONObject(i);
                if (bal.getString("denom").equals("uatom")) {
                    String amount = bal.getString("amount");
                    return Long.parseLong(amount) / 1e6;
                }
            }
            return 0;
        }
    }

    // === DOT (Polkadot) ===
    private static double getPolkadotBalance(Context ctx, String address) throws Exception {
        // Polkadot 余额查询需要 blake2b 哈希计算 storage key（Java 标准库不支持）
        // 改用 Polkadot 官方 sidecar REST API 查询，避免错误的哈希算法
        String sidecarUrl = "https://polkadot-public-sidecar.parity-chains.parity.io/accounts/" + address + "/balance-info";
        Request request = new Request.Builder()
            .url(sidecarUrl)
            .get()
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            // free 字段是字符串形式的最小单位（planks），1 DOT = 10^10 planks
            String freeStr = json.optString("free", "0");
            BigInteger free = new BigInteger(freeStr);
            int decimals = json.optInt("tokenDecimals", 10);
            return free.doubleValue() / Math.pow(10, decimals);
        } catch (Exception e) {
            Logger.warning(ctx, "余额查询", "Polkadot sidecar 查询失败: " + e.getMessage());
            return 0;
        }
    }

    // === ALGO (Algorand) ===
    private static double getAlgorandBalance(Context ctx, String address) throws Exception {
        String apiUrl = getRpcUrl(ctx, "ALGO");
        Request request = new Request.Builder()
            .url(apiUrl + "/v2/accounts/" + address)
            .header("Accept", "application/json")
            .get()
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            if (json.has("account")) {
                JSONObject account = json.getJSONObject("account");
                long microAlgos = account.getLong("amount");
                return microAlgos / 1e6;
            }
            return 0;
        }
    }

    // === ICP (Internet Computer) ===
    private static double getICPBalance(Context ctx, String address) throws Exception {
        String apiUrl = getRpcUrl(ctx, "ICP");
        String accountId = address;
        if (address.startsWith("0x")) {
            accountId = address.substring(2);
        }
        Request request = new Request.Builder()
            .url(apiUrl + "/ledger?account_id=" + accountId + "&canister_id=ryjl3-tyaaa-aaaaa-aaaba-cai")
            .header("Accept", "application/json")
            .get()
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            if (json.has("balance")) {
                long e8s = Long.parseLong(json.getString("balance"));
                return e8s / 1e8;
            }
            return 0;
        }
    }

    // === XTZ (Tezos) ===
    private static double getTezosBalance(Context ctx, String address) throws Exception {
        String apiUrl = getRpcUrl(ctx, "XTZ");
        Request request = new Request.Builder()
            .url(apiUrl + "/explorer/account/" + address)
            .header("Accept", "application/json")
            .get()
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            long mutez = json.getLong("balance");
            return mutez / 1e6;
        }
    }

    public static Map<String, Double> getPrices(Context ctx) throws Exception {
        Map<String, Double> prices = new HashMap<>();

        // 0. 本地缓存（30 分钟内直接复用，避免频繁请求）
        if (ctx != null) {
            try {
                android.content.SharedPreferences prefs = ctx.getSharedPreferences("price_cache", android.content.Context.MODE_PRIVATE);
                long cachedAt = prefs.getLong("cached_at", 0);
                long now = System.currentTimeMillis();
                if (cachedAt > 0 && now - cachedAt < 30 * 60 * 1000) {
                    String cached = prefs.getString("prices", "");
                    if (!cached.isEmpty()) {
                        JSONObject obj = new JSONObject(cached);
                        java.util.Iterator<String> keys = obj.keys();
                        while (keys.hasNext()) {
                            String k = keys.next();
                            prices.put(k, obj.getDouble(k));
                        }
                        if (!prices.isEmpty()) {
                            Logger.network(ctx, "价格 API", "使用本地缓存（" + (now - cachedAt) / 1000 + "秒前）" + prices.size() + " 个价格");
                            return prices;
                        }
                    }
                }
            } catch (Exception ignore) {}
        }

        // 0. Gate.io（中国直连，无需API key，速度快）— 最优先
        try {
            Logger.network(ctx, "价格 API", "Gate.io 请求中...");
            Map<String, Double> gatePrices = fetchGatePrices();
            if (gatePrices != null && !gatePrices.isEmpty()) {
                prices.putAll(gatePrices);
                savePriceCache(ctx, prices);
                Logger.success(ctx, "价格 API", "Gate.io 成功获取 " + prices.size() + " 个价格");
                return prices;
            }
            Logger.warning(ctx, "价格 API", "Gate.io 返回空数据（HTTP可能非200或被墙）");
        } catch (Exception e) {
            Logger.warning(ctx, "价格 API", "Gate.io 失败：" + e.getMessage());
        }

        // 1. Binance Cloudflare镜像（data-api.binance.vision，国内可访问）
        try {
            Logger.network(ctx, "价格 API", "Binance镜像(CF) 请求中...");
            String url = "https://data-api.binance.vision/api/v3/ticker/price";
            Map<String, Double> bnCfPrices = fetchBinancePrices(url);
            if (bnCfPrices != null && !bnCfPrices.isEmpty()) {
                prices.putAll(bnCfPrices);
                savePriceCache(ctx, prices);
                Logger.success(ctx, "价格 API", "Binance镜像(CF)成功获取 " + prices.size() + " 个价格");
                return prices;
            }
        } catch (Exception e) {
            Logger.warning(ctx, "价格 API", "Binance镜像(CF)失败：" + e.getMessage());
        }

        // 2. 币安官方（国内常被墙）— 备用
        try {
            Logger.network(ctx, "价格 API", "币安 API 请求中...");
            String url = "https://api.binance.com/api/v3/ticker/price";
            Map<String, Double> binancePrices = fetchBinancePrices(url);
            if (binancePrices != null && !binancePrices.isEmpty()) {
                prices.putAll(binancePrices);
                savePriceCache(ctx, prices);
                Logger.success(ctx, "价格 API", "币安成功获取 " + prices.size() + " 个价格");
                return prices;
            }
        } catch (Exception e) {
            Logger.warning(ctx, "价格 API", "币安失败：" + e.getMessage());
        }

        // 2. CoinCap（备用，国内勉强可访问）
        try {
            Logger.network(ctx, "价格 API", "CoinCap 请求中...");
            String url = "https://api.coincap.io/v2/assets";
            Request request = new Request.Builder().url(url)
                .header("Accept", "application/json").get().build();
            try (Response response = priceClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String resp = response.body().string();
                    JSONObject json = new JSONObject(resp);
                    JSONArray data = json.getJSONArray("data");
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject item = data.getJSONObject(i);
                        String symbol = item.optString("symbol", "").toUpperCase();
                        if (COIN_IDS.containsKey(symbol)) {
                            try {
                                double price = Double.parseDouble(item.optString("priceUsd", "0"));
                                if (price > 0) prices.put(symbol, price);
                            } catch (Exception ignore) {}
                        }
                    }
                }
            }
            if (!prices.isEmpty()) {
                savePriceCache(ctx, prices);
                Logger.success(ctx, "价格 API", "CoinCap 成功获取 " + prices.size() + " 个价格");
                return prices;
            }
        } catch (Exception e) {
            Logger.warning(ctx, "价格 API", "CoinCap 失败：" + e.getMessage());
        }

        // 3. CoinGecko（最后备用，国内经常被墙）
        try {
            Logger.network(ctx, "价格 API", "CoinGecko 请求中...");
            StringBuilder ids = new StringBuilder();
            for (String id : COIN_IDS.values()) {
                if (ids.length() > 0) ids.append(",");
                ids.append(id);
            }
            String url = "https://api.coingecko.com/api/v3/simple/price?ids=" + ids + "&vs_currencies=usd";
            Request request = new Request.Builder().url(url)
                .header("Accept", "application/json").get().build();
            try (Response response = priceClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String resp = response.body().string();
                    JSONObject json = new JSONObject(resp);
                    for (String symbol : COIN_IDS.keySet()) {
                        String coinId = COIN_IDS.get(symbol);
                        if (json.has(coinId)) {
                            try {
                                JSONObject priceObj = json.getJSONObject(coinId);
                                double price = priceObj.getDouble("usd");
                                if (price > 0) prices.put(symbol, price);
                            } catch (Exception ignore) {}
                        }
                    }
                }
            }
            if (!prices.isEmpty()) {
                savePriceCache(ctx, prices);
                Logger.success(ctx, "价格 API", "CoinGecko 成功获取 " + prices.size() + " 个价格");
                return prices;
            }
        } catch (Exception e) {
            Logger.warning(ctx, "价格 API", "CoinGecko 失败：" + e.getMessage());
        }

        if (prices.isEmpty()) {
            Logger.error(ctx, "价格 API", "所有价格源均失败，请检查网络");
        }
        return prices;
    }

    /**
     * 从 Gate.io 获取主流币价格（中国直连，无需API key）
     * API: GET https://api.gateio.ws/api/v4/spot/tickers?currency_pair=ETH_USDT&BTC_USDT...
     * 响应: [{currency_pair:"ETH_USDT", last:"3500.5"}, ...]
     */
    private static Map<String, Double> fetchGatePrices() throws Exception {
        Map<String, Double> prices = new HashMap<>();
        // 批量请求所有交易对
        StringBuilder pairs = new StringBuilder();
        for (Map.Entry<String, String> entry : GATE_PAIRS.entrySet()) {
            if (pairs.length() > 0) pairs.append("&currency_pair=");
            pairs.append(entry.getValue());
        }
        String url = "https://api.gateio.ws/api/v4/spot/tickers?currency_pair=" + pairs.toString();
        Request request = new Request.Builder().url(url)
            .header("Accept", "application/json").get().build();
        try (Response response = priceClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return prices;
            String resp = response.body().string();
            JSONArray arr = new JSONArray(resp);
            // 建立 pair → symbol 反向映射
            Map<String, String> pairToSymbol = new HashMap<>();
            for (Map.Entry<String, String> entry : GATE_PAIRS.entrySet()) {
                pairToSymbol.put(entry.getValue(), entry.getKey());
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.getJSONObject(i);
                String pair = item.optString("currency_pair", "");
                String symbol = pairToSymbol.get(pair);
                if (symbol == null) continue;
                try {
                    double price = Double.parseDouble(item.optString("last", "0"));
                    if (price > 0) prices.put(symbol, price);
                } catch (Exception ignore) {}
            }
        }
        return prices;
    }

    /**
     * 拉取币安所有 ticker 价格。带 2 次重试。
     * 失败返回 null。
     */
    private static Map<String, Double> fetchBinancePrices(String url) {
        Exception last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                Request request = new Request.Builder().url(url)
                    .header("Accept", "application/json").get().build();
                try (Response response = priceClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        last = new Exception("HTTP " + response.code());
                        continue;
                    }
                    String resp = response.body() != null ? response.body().string() : "";
                    JSONArray arr = new JSONArray(resp);
                    Map<String, Double> result = new HashMap<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject item = arr.getJSONObject(i);
                        String symbol = item.optString("symbol", "");
                        if (!symbol.endsWith("USDT")) continue;
                        String base = symbol.substring(0, symbol.length() - 4);
                        try {
                            double price = item.getDouble("price");
                            if (price > 0) result.put(base, price);
                        } catch (Exception ignore) {}
                    }
                    // 稳定币价格固定为 1 USD（Binance 没有 USDTUSDT 交易对）
                    result.put("USDT", 1.0);
                    result.put("USDC", 1.0);
                    result.put("BUSD", 1.0);
                    result.put("DAI", 1.0);
                    if (!result.isEmpty()) return result;
                }
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null) throw new RuntimeException(last);
        return null;
    }

    /**
     * 将价格写入本地缓存
     */
    private static void savePriceCache(Context ctx, Map<String, Double> prices) {
        if (ctx == null || prices == null || prices.isEmpty()) return;
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, Double> e : prices.entrySet()) {
                obj.put(e.getKey(), e.getValue());
            }
            ctx.getSharedPreferences("price_cache", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("prices", obj.toString())
                .putLong("cached_at", System.currentTimeMillis())
                .apply();
        } catch (Exception ignore) {}
    }

    // EVM 代币 Transfer event topic: keccak256("Transfer(address,address,uint256)")
    // ETH 链为 ERC-20，BNB 链为 BEP-20，但事件签名相同
    private static final String TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    /**
     * 各链常用代币列表（ETH链为ERC-20，BNB链为BEP-20，不依赖任何第三方 API，直接 RPC balanceOf 查询）
     * 格式: {chain, contract, symbol, name, decimals}
     * @deprecated 已被 BSC_POPULAR_TOKENS（500个BSC热门代币）替代，保留仅用于非BSC链
     */
    private static final String[][] COMMON_TOKENS = {
        // BSC 链常用代币
        {"BNB", "0x55d398326f99059fF775485246999027B3197955", "USDT", "Tether USD", "18"},
        {"BNB", "0x8AC76A51cc950d9822D68b8FEb1a1345Ff9B6cB2", "USDC", "USD Coin", "18"},
        {"BNB", "0xe9e7CEA3DedcA5984780Bafc599bD69ADd087D56", "BUSD", "Binance USD", "18"},
        {"BNB", "0x0E09FaBB73Bd3Ade0a17ECC321fD13a19e81cE82", "CAKE", "PancakeSwap", "18"},
        {"BNB", "0x7130d2A12B9BCbFAe4f2634d864C1cb3948E7D0e", "BTCB", "Bitcoin BEP20", "18"},
        {"BNB", "0x2170Ed0880ac9D756f7AC4482C75d6129b1B1F4F", "ETH", "Ethereum BEP20", "18"},
        {"BNB", "0xbb4CdB9CBd36B01bD1cBaEBF2De08d9173bc095c", "WBNB", "Wrapped BNB", "18"},
        {"BNB", "0x3EE2200Efb3400fAbB9AacF31297cBdD1d435D47", "ADA", "Cardano BEP20", "18"},
        {"BNB", "0xbA2aE424d960c26247Dd6c32edC70B295c744C43", "DOGE", "Dogecoin BEP20", "18"},
        {"BNB", "0x2859e4544C4bB03966803b044A93563Bd2D0DD4D", "SHIB", "SHIBA INU BEP20", "18"},
        {"BNB", "0xF8A0BF9cF54Bb92F17374d9e9A321E6a111a88bF", "LINK", "ChainLink BEP20", "18"},
        {"BNB", "0x1D2F0da169ceB9Fc7223fFF739ac392743f15D535", "XRP", "XRP BEP20", "18"},
        {"BNB", "0x7083609fCE4d1d8Dc0C979AAb8c8698AaE5CA2Ff", "DOT", "Polkadot BEP20", "18"},
        {"BNB", "0x570E5C8D4eaB429b85921F3Bc4Fda9CbcA2c4878", "FIL", "FileCoin BEP20", "18"},
        {"BNB", "0xCF3C8122fAD33992BbD5f12F2aE32353536C1689", "AVAX", "Avalanche BEP20", "18"},
        {"BNB", "0x1AF3F329e8BE154074D8769D1FFa4eE058B1DBc3", "DAI", "Dai BEP20", "18"},
        {"BNB", "0x4338665CBB7B2485A8855A139b75D5e34AB0DB94", "STETH", "Staked ETH BEP20", "18"},
        {"BNB", "0x14016E85a25aeb13065688cAFB43044C2ef86784", "TUSD", "TrueUSD BEP20", "18"},
        {"BNB", "0x8595F9dA7b868b1822194fAEd312235E43007bD7", "BTT", "BitTorrent BEP20", "18"},
        {"BNB", "0xCC42724C6683B7E57334c4E856f4c9965ED76274", "RETH", "Rocket Pool ETH BEP20", "18"},
        {"BNB", "0x7445857E7d50C2a58ebdaC3e3dB8D69C4E7e5f3D", "LEVER", "LeverFi BEP20", "18"},

        // Ethereum 链常用代币
        {"ETH", "0xdAC17F958D2ee523a2206206994597C13D831ec7", "USDT", "Tether USD", "6"},
        {"ETH", "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", "USDC", "USD Coin", "6"},
        {"ETH", "0x6B175474E89094C44Da98b954EedeAC495271d0F", "DAI", "Dai Stablecoin", "18"},
        {"ETH", "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2", "WETH", "Wrapped Ether", "18"},
        {"ETH", "0x2260FAC5E5542a773Aa44fBCfeDf7C193bc2C599", "WBTC", "Wrapped BTC", "8"},
        {"ETH", "0x514910771AF9Ca656af840dff83E8264EcF986CA", "LINK", "Chainlink", "18"},
        {"ETH", "0x1f9840a85d5aF5bf1D1762F925BDADdC4201F984", "UNI", "Uniswap", "18"},
        {"ETH", "0x95aD61b0a150d79219dCF64E1E6Cc01f0B64C4cE", "SHIB", "SHIBA INU", "18"},
        {"ETH", "0x6982508145454Ce325dDbE47a25d0043C938B731", "PEPE", "Pepe", "18"},
        {"ETH", "0x7D1AfA7B718fb893dB30A3aBc0Cfc608AaCfeBB0", "MATIC", "Matic Network", "18"},
        {"ETH", "0x5283D2916CF7Ccc4F3000f7c4779E25498c563B1", "AAVE", "Aave Token", "18"},
        {"ETH", "0xC18360217D8F7Ab5e7c516566761Ea12Ce7F9D72", "ENS", "Ethereum Name Service", "18"},
        {"ETH", "0xc00e94Cb662C3520282E6f5717214004A7f26888", "COMP", "Compound", "18"},
        {"ETH", "0x0bc529c00C6401aEF6D220BE8C6Ea1667F6Ad93e", "YFI", "Yearn Finance", "18"},
        {"ETH", "0x1E4CBE9fd5C5c741E7569026A9c6B5A8dDb7E5cB", "FRAX", "Frax", "18"},
        {"ETH", "0x3432B6A60D23Ca0dFCa7761B7ab56459D9C964D0", "FXS", "Frax Share", "18"},
        {"ETH", "0xDe30da39c46104798bB5aA3fe8B9e0e1F348163F", "GTC", "Gitcoin", "18"},
        {"ETH", "0x9E4c9770FC1B5E90A066809473522F6E9Fd5D746", "MC", "Merit Circle", "18"},

        // Polygon 链常用代币
        {"MATIC", "0xc2132D05D31c914a87C6611C10748AEb04B58e8F", "USDT", "Tether USD", "6"},
        {"MATIC", "0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174", "USDC", "USD Coin", "6"},
        {"MATIC", "0x0d500B1d8E8eF31E21C99d1Db9A6444d3ADf1270", "WMATIC", "Wrapped Matic", "18"},
        {"MATIC", "0x8f3Cf7ad23Cd3CaDbD9735AFf958023239c6A063", "DAI", "Dai Stablecoin", "18"},
        {"MATIC", "0x53E0bca35eC356BD5ddDFebbD1Fc0fD03FaBad39", "LINK", "ChainLink Token", "18"},
        {"MATIC", "0xBbba073C31bF03b8ACf7C28C079D63F526766320", "WETH", "Wrapped Ether", "18"},
        {"MATIC", "0x1BFD67037B42Cf73acF2047067bd4F2C47D9BfD6", "WBTC", "Wrapped BTC", "8"},

        // Arbitrum 链常用代币
        {"ARB", "0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9", "USDT", "Tether USD", "6"},
        {"ARB", "0xaf88d065e77c8cC2239327C5EDb3A432268e5831", "USDC", "USD Coin", "6"},
        {"ARB", "0x82aF49447D8a07e3bd95BD0d56f35241523bFbab", "WETH", "Wrapped Ether", "18"},
        {"ARB", "0x2f2a2543B76A4166549F7aaB2e75Bef0aefC5B0f", "WBTC", "Wrapped BTC", "8"},
        {"ARB", "0xDA10009cBd5D07dd0CeCc66161FC93D7c9000da1", "DAI", "Dai Stablecoin", "18"},
    };


    // BSC 热门代币列表（来自 TP 钱包 ethereum-56.json，前 500 个）
    // 格式: {contract, symbol, name, decimals}
    // 参考 TP 钱包方案：内置热门代币 + 查余额，只显示有余额的
    private static final String[][] BSC_POPULAR_TOKENS = {
        {"0x55d398326f99059ff775485246999027b3197955", "USDT", "Tether USD (BSC-USD)", "18"},
        {"0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c", "WBNB", "Wrapped BNB", "18"},
        {"0x570a5d26f7765ecb712c0924e4de545b89fd43df", "SOL", "SOLANA (SOL)", "18"},
        {"0x0e09fabb73bd3ade0a17ecc321fd13a19e81ce82", "CAKE", "PancakeSwap Token", "18"},
        {"0x92cb10e1d503b5c41f54fcc6b576176e6f29fbad", "R-MAB", "R-MAB Token", "8"},
        {"0x2dca79ad9909989e2081793961866ad6e7777777", "GOUT", "GOUT", "18"},
        {"0x00e1656e45f18ec6747f5a8496fd39b50b38396d", "BCOIN", "Bomber Coin", "18"},
        {"0x2170ed0880ac9a755fd29b2688956bd959f933f8", "ETH", "Ethereum Token", "18"},
        {"0x7130d2a12b9bcbfae4f2634d864a1ee1ce3ead9c", "BTCB", "BTCB Token", "18"},
        {"0x12bb890508c125661e03b09ec06e404bc9289040", "RACA", "Radio Caca V2", "18"},
        {"0xc748673057861a797275cd8a068abb95a902e8de", "BABYDOGE", "Baby Doge Coin", "9"},
        {"0xc9882def23bc42d53895b8361d0b1edc7570bc6a", "FIST", "FistToken", "6"},
        {"0xba2ae424d960c26247dd6c32edc70b295c744c43", "DOGE", "Dogecoin", "8"},
        {"0x156ab3346823b651294766e23e6cf87254d68962", "LUNA", "LUNA (Wormhole)", "6"},
        {"0x4a2c860cec6471b9f5f5a336eb4f38bb21683c98", "GST", "GreenSatoshiToken", "8"},
        {"0x965f527d9159dce6288a2219db51fc6eef120dd1", "BSW", "Biswap", "18"},
        {"0xe9e7cea3dedca5984780bafc599bd69add087d56", "BUSD", "BUSD Token", "18"},
        {"0x23396cf899ca06c4472205fc903bdb4de249d6fc", "UST", "Wrapped UST Token", "18"},
        {"0x3019bf2a2ef8040c242c9a4c5c4bd4c81678b2a1", "GMT", "Green Metaverse Token", "8"},
        {"0xd9979e2479aea29751d31ae512a61297b98fbbf4", "TORII", "TORII", "18"},
        {"0x3203c9e46ca618c8c1ce5dc67e7e9d75f5da2377", "MBOX", "Mobox", "18"},
        {"0x2859e4544c4bb03966803b044a93563bd2d0dd4d", "SHIB", "SHIBA INU", "18"},
        {"0x8c851d1a123ff703bd1f9dabe631b69902df5f97", "BNX", "BinaryX", "18"},
        {"0xd40bedb44c081d2935eeba6ef5a3c8a31a1bbe13", "HERO", "Metahero", "18"},
        {"0x9fd87aefe02441b123c3c32466cd9db4c578618f", "THG", "Thetan Gem", "18"},
        {"0x3ee2200efb3400fabb9aacf31297cbdd1d435d47", "ADA", "Cardano Token", "18"},
        {"0x1d2f0da169ceb9fc7b3144628db156f3f6c60dbe", "XRP", "XRP Token", "18"},
        {"0x12a055d95855b4ec2cd70c1a5eadb1ed43eaef65", "FON", "Fonvity Token", "18"},
        {"0xacfc95585d80ab62f67a14c566c1b7a49fe91167", "FEG", "FEGtoken", "9"},
        {"0x26619fa1d4c957c58096bbbeca6588dcfb12e109", "TIME", "TIME", "18"},
        {"0x26193c7fa4354ae49ec53ea2cebc513dc39a10aa", "SEA", "SharkShakeSea", "18"},
        {"0xAb14952d2902343fde7c65D7dC095e5c8bE86920", "GOMA", "Goma Shiba Inu", "9"},
        {"0x04fa9eb295266d9d4650edcb879da204887dc3da", "OSK", "OSK", "18"},
        {"0xa57ac35ce91ee92caefaa8dc04140c8e232c2e50", "PIT", "Pitbull", "9"},
        {"0x641ec142e67ab213539815f67e4276975c2f8d50", "DOGEKING", "DogeKing", "18"},
        {"0xd41fdb03ba84762dd66a0af1a6c8540ff1ba5dfb", "SFP", "SafePal Token", "18"},
        {"0x69b14e8d3cebfdd8196bfe530954a0c226e5008e", "SPACEPI", "SpacePi Token", "9"},
        {"0x23766cb8a96ff2f46f664bc7d088a6306de73618", "DOR", "Day Of Rights", "18"},
        {"0x7a565284572d03ec50c35396f7d6001252eb43b6", "DOGEZILLA", "DogeZilla", "9"},
        {"0x6b23c89196deb721e6fd9726e6c76e4810a464bc", "XWG", "XWG", "18"},
        {"0x40c8225329bd3e28a043b029e0d07a5344d2c27c", "AOG", "AgeOfGods", "18"},
        {"0x4b0f1812e5df2a09796481ff14017e6005508003", "TWT", "Trust Wallet", "18"},
        {"0xcc42724c6683b7e57334c4e856f4c9965ed682bd", "MATIC", "Matic Token", "18"},
        {"0x0dfcb45eae071b3b846e220560bbcdd958414d78", "LIBERO", "Libero Financial Freedom", "18"},
        {"0x3b3691d4c3ec75660f203f41adc6296a494404d0", "CATS", "Catcoin", "0"},
        {"0x7083609fce4d1d8dc0c979aab8c869ea2c873402", "DOT", "Polkadot Token", "18"},
        {"0xc001bbe2b87079294c63ece98bdd0a88d761434e", "EGC", "EverGrow Coin", "9"},
        {"0x9e9bef94795bfe87a11a0369b4e0c3b60a6fcf2b", "MBANK", "MetaBank", "18"},
        {"0xe0f94ac5462997d2bc57287ac3a3ae4c31345d66", "CEEK", "CEEK", "18"},
        {"0x31471e0791fcdbe82fbf4c44943255e923f1b794", "PVU", "Plant vs Undead Token", "18"},
        {"0x17e65e6b9b166fb8e7c59432f0db126711246bc0", "TIFI", "TiFi Token", "18"},
        {"0x87230146e138d3f296a9a77e497a2a83012e9bc5", "SQUID", "Squid Game", "18"},
        {"0xfb5b838b6cfeedc2873ab27866079ac55363d37e", "FLOKI", "FLOKI", "9"},
        {"0x23ce9e926048273ef83be0a3a8ba9cb6d45cd978", "DAR", "Dalarnia", "6"},
        {"0x0d8ce2a99bb6e3b7db580ed848240e4a0f9ae153", "FIL", "Filecoin", "18"},
        {"0x2B3F34e9D4b127797CE6244Ea341a83733ddd6E4", "FLOKI", "FLOKI", "9"},
        {"0x477bc8d23c634c154061869478bce96be6045d12", "SFUND", "SeedifyFund", "18"},
        {"0xc544d8ab2b5ed395b96e3ec87462801eca579ae1", "SFO", "starfishos", "18"},
        {"0xd9de2b1973e57dc9dba90c35d6cd940ae4a3cbe1", "MILO", "Milo Inu", "9"},
        {"0x8f0528ce5ef7b51152a59745befdd91d97091d2f", "ALPACA", "AlpacaToken", "18"},
        {"0xd0c4bc1b89bbd105eecb7eba3f13e7648c0de38f", "WEB3", "WEB3 Inu", "9"},
        {"0xf8a0bf9cf54bb92f17374d9e9a321e6a111a51bd", "LINK", "ChainLink Token", "18"},
        {"0xf700d4c708c2be1463e355f337603183d20e0808", "GQ", "Galactic Quadrant", "18"},
        {"0x02ff5065692783374947393723dba9599e59f591", "YOOSHI", "YOOSHI", "9"},
        {"0x9131066022b909c65edd1aaf7ff213dacf4e86d0", "LAND", "META-UTOPIA LAND", "18"},
        {"0x8076c74c5e3f5852037f31ff0093eeb8c8add8d3", "SAFEMOON", "SafeMoon", "9"},
        {"0x0c1253a30da9580472064a91946c5ce0c58acf7f", "TITA", "Titan Hunters", "18"},
        {"0xb6b91269413b6b99242b1c0bc611031529999999", "CALO", "CALO", "18"},
        {"0x29a1e54de0fce58e1018535d30af77a9d2d940c4", "HCT", "HERO CAT TOKEN", "18"},
        {"0x7ddee176f665cd201f93eede625770e2fd911990", "GALA", "pTokens GALA", "18"},
        {"0x10f6f2b97f3ab29583d9d38babf2994df7220c21", "TEDDY", "TeddyDoge", "18"},
        {"0x53e562b9b7e5e94b81f10e96ee70ad06df3d2657", "BABY", "BabySwap Token", "18"},
        {"0xc5db5afee4c55dfad5f2b8226c6ac882e6956a0a", "OSK-DAO", "Pego eco-governance token", "18"},
        {"0x7e58a5c150b3c9171100fdee0dd22ee666db9545", "NUSIC", "Nusic Token", "18"},
        {"0x5b6bf0c7f989de824677cfbd507d9635965e9cd3", "GMM", "Gamium", "18"},
        {"0xd8a2ae43fd061d24acd538e3866ffc2c05151b53", "AIR", "AIR", "18"},
        {"0x03ff0ff224f904be3118461335064bb48df47938", "ONE", "Harmony ONE", "18"},
        {"0x7008ed7fdfa1fd5be00cd5c1ca8c7723eb8ce533", "YES", "YES", "18"},
        {"0xbf5140a22578168fd562dccf235e5d43a02ce9b1", "UNI", "Uniswap", "18"},
        {"0xad6742a35fb341a9cc6ad674738dd8da98b94fb1", "WOM", "Wombat Token", "18"},
        {"0x71fc2c893e41eabdf9c4afda3b2cdb46b93cd8aa", "MIC", "MIC", "18"},
        {"0xd74b782e05aa25c50e7330af541d46e18f36661c", "QUACK", "RichQUACK.com", "9"},
        {"0x8a87c36bb9e9b91c76e7a0a374a59e57cf0c0f5b", "SUC", "SUC", "18"},
        {"0xa77346760341460b42c230ca6d21d4c8e743fa9c", "PETS", "MicroPets", "18"},
        {"0x7ae5709c585ccfb3e61ff312ec632c21a5f03f70", "DOGEDASH", "DogeDash", "18"},
        {"0xa58950f05fea2277d2608748412bf9f802ea4901", "WSG", "Wall Street Games", "18"},
        {"0x4338665cbb7b2485a8855a139b75d5e34ab0db94", "LTC", " Litecoin Token", "18"},
        {"0xfb62ae373aca027177d1c18ee0862817f9080d08", "DPET", "My DeFi Pet Token", "18"},
        {"0x6cad12b3618a3c7ef1feb6c91fdc3251f58c2a90", "NINO", "Ninneko Token", "18"},
        {"0xe4fae3faa8300810c835970b9187c268f55d998f", "CATE", "CateCoin", "9"},
        {"0x0a2046c7faa5a5f2b38c0599deb4310ab781cc83", "META", "MetaversePRO", "9"},
        {"0x1e4402fa427a7a835fc64ea6d051404ce767a569", "HOUND", "Hound", "18"},
        {"0xd02193a88ff17d40c0bb97d316067b3fd4cb3b9d", "TDG", "Teddy dog", "8"},
        {"0xc709878167ed069aea15fd0bd4e9758ceb4da193", "DOD", "Day Of Defeat", "18"},
        {"0xacb8f52dc63bb752a51186d1c55868adbffee9c1", "BP", "BP", "18"},
        {"0xe2604c9561d490624aa35e156e65e590eb749519", "GM", "GoldMiner", "18"},
        {"0xba552586ea573eaa3436f04027ff4effd0c0abbb", "MEER", "Meer (bsc)", "18"},
        {"0x8bf9dc93b6f81a5fc70d0b451596fd2b09fe92c3", "TAU", "TAU Token", "6"},
        {"0x5f4bde007dc06b867f86ebfe4802e34a1ffeed63", "HIGH", "Highstreet Token", "18"},
        {"0xa0cc4414019471bef0fe0b07a76da1f7cdc4ccf7", "MOY", "moeny", "6"},
        {"0x8e9b87cad37610d60120a1f48aa1036e24a3831a", "XPS", "X-PARALLEL SPACE", "18"},
        {"0x57b798d2252557f13a9148a075a72816f2707356", "RATS", "Ratscoin", "0"},
        {"0xe618ef7c64afede59a81cef16d0161c914ebab17", "TITI", "Titi Financial", "18"},
        {"0x41515885251e724233c6ca94530d6dcf3a20dec7", "BSC-HO", "Binance-Peg BSC-HO", "18"},
        {"0x6598463d6cbe4b51e9977437bf1200df4c45286c", "SOCA", "Socaverse", "9"},
        {"0x9c65ab58d8d978db963e63f2bfb7121627e3a739", "MDX", "MDX", "18"},
        {"0xcf6bb5389c92bdda8a3747ddb454cb7a64626c63", "XVS", "Venus", "18"},
        {"0x42981d0bfbaf196529376ee702f2a9eb9092fcb5", "SFM", "SafeMoon", "9"},
        {"0xda3d20e21caeb1cf6dd84370aa0325087326f07a", "AITN", "Artificial Intelligence Technology Network", "18"},
        {"0x17932655690e19fa85fdd3f051d795098e69773d", "NFD", "New Free Dao", "18"},
        {"0xe9c7a827a4ba133b338b844c19241c864e95d75f", "FSV", "FileSystemVideo", "6"},
        {"0x0c2bfa54d6d4231b6213803df616a504767020ea", "CC", "CloudChat Token", "18"},
        {"0x6a684b3578f5b07c0aa02fafc33ed248ae0c2db2", "TTC", "Tech Trees Coin", "18"},
        {"0x05ad6e30a855be07afa57e08a4f30d00810a402e", "TINC", "Tiny Coin", "18"},
        {"0x79ebc9a2ce02277a4b5b3a768b1c0a4ed75bd936", "CATGIRL", "CatGirl", "9"},
        {"0xad29abb318791d579433d831ed122afeaf29dcfe", "FTM", "Fantom", "18"},
        {"0x9ab70e92319f0b9127df78868fd3655fb9f1e322", "WWY", "WeWay Token", "18"},
        {"0xa64455a4553c9034236734faddaddbb64ace4cc7", "SANTOS", "FC Santos Fan Token", "8"},
        {"0x04c747b40be4d535fc83d09939fb0f626f32800b", "ITAM", "ITAM", "18"},
        {"0x8ac76a51cc950d9822d68b83fe1ad97b32cd580d", "USDC", "Binance-Peg USD Coin", "18"},
        {"0x88479186bac914e4313389a64881f5ed0153c765", "SQUIDGROW", "SquidGrow", "19"},
        {"0xfebe8c1ed424dbf688551d4e2267e7a53698f0aa", "VINU", "Vita Inu", "18"},
        {"0x78650b139471520656b9e7aa7a5e9276814a38e9", "BTCST", "StandardBTCHashrateToken", "17"},
        {"0x76a797a59ba2c17726896976b7b3747bfd1d220f", "TONCOIN", "Wrapped TON Coin", "9"},
        {"0x4a846d300f793752ee8bd579192c477130c4b369", "LITE", "LITE", "18"},
        {"0x196eb1d21c05cc265ea0a1479e924e7983467838", "UVT", "Universe Token", "18"},
        {"0xc9849e6fdb743d08faee3e34dd2d1bc69ea11a51", "BUNNY", "Bunny Token", "18"},
        {"0x5b1baec64af6dc54e6e04349315919129a6d3c23", "DXCT", "DNAxCAT", "18"},
        {"0x317eb4ad9cfac6232f0046831322e895507bcbeb", "TDX", "Tidex Token", "18"},
        {"0x6129173a8DB84137eD1Cf51E97F1B118557C6098", "STFT", "Starfield Token", "18"},
        {"0x123458c167a371250d325bd8b1fff12c8af692a7", "DRAC", "DRAC Token", "18"},
        {"0xe74b9f7c4a5003e817f594615a6b8f5ad3e30c70", "SATT", "SATT", "9"},
        {"0x2222227e22102fe3322098e4cbfe18cfebd57c95", "TLM", "Alien Worlds Trilium", "4"},
        {"0x81cad0ab645a1792f585ce93c5f955ff3ecc3951", "SPAC", "SPACE AGE", "18"},
        {"0xab15b79880f11cffb58db25ec2bc39d28c4d80d2", "SMON", "StarMon", "18"},
        {"0x141127b397594c542ee64a75135d652094e83f20", "HAYYA", "Hayya", "9"},
        {"0x1d229b958d5ddfca92146585a8711aecbe56f095", "ZOO", "ZooToken", "18"},
        {"0xfcb520b47f5601031e0eb316f553a3641ff4b13c", "LIZ", "LIZ", "8"},
        {"0x4691937a7508860f876c9c0a2a617e7d9e945d4b", "WOO", "Wootrade Network", "18"},
        {"0xe4cc45bb5dbda06db6183e8bf016569f40497aa5", "GAL", "Project Galaxy", "18"},
        {"0xd64f7b8962ede286Ac66E290B5d3e5f8f4e019FB", "ME", "MEMetaverse", "18"},
        {"0x0eb3a705fc54725037cc9e008bdede697f62f335", "ATOM", "Cosmos Token", "18"},
        {"0x98afac3b663113d29dc2cd8c2d1d14793692f110", "MVS", "Multiverse", "18"},
        {"0x4f5f7a7dca8ba0a7983381d23dfc5eaf4be9c79a", "STI", "Seek Tiger", "10"},
        {"0x1f39dd2bf5a27e2d4ed691dcf933077371777cb0", "NORA", "SnowCrash Token", "18"},
        {"0x42bfa18f3f7d82bd7240d8ce5935d51679c5115d", "S2K", "Sports 2K75", "9"},
        {"0x0173295183685f27c84db046b5f0bea3e683c24b", "CAT", "Cat", "18"},
        {"0x755f34709e369d37c6fa52808ae84a32007d1155", "NABOX", "Nabox Token", "18"},
        {"0xeacad6c99965cde0f31513dd72de79fa24610767", "MSC", "MetaSwap", "18"},
        {"0xb78b7e82b074c267dd487db293a8faf831ae2d71", "ELV", "ELVES", "8"},
        {"0x715d400f88c167884bbcc41c5fea407ed4d2f8a0", "AXS", "Axie Infinity Shard", "18"},
        {"0x566f47bf6e0fd69cd97da548573a6127c18ce1c0", "META", "Metaverse", "5"},
        {"0x9678e42cebeb63f23197d726b29b1cb20d0064e5", "IOTX", "IoTeX Network", "18"},
        {"0xb2f53069e1555793481aafe639f8e274f4ec8435", "BSC-ZEED", "Binance-Peg BSC-ZEED", "18"},
        {"0x0ebc30459551858e81306d583025d12c7d795fa2", "ADOGE", "Amazing doge", "9"},
        {"0x8424b4c691473c873067b65d5f40f3ff0bf7463e", "SHIBKING", "SHIBKING INU", "18"},
        {"0x086ddd008e20dd74c4fb216170349853f8ca8289", "MBE", "MxmBoxcEus Token", "18"},
        {"0xae21bfe30aff40a66bc9a950d61c1a6b1c82ad2a", "FUT", "FUT", "18"},
        {"0x5dc53496e8dd50887785d75d432cba6a86f82cad", "ACC", "ACCToken", "18"},
        {"0x08ba0619b1e7a582e0bce5bbe9843322c954c340", "BMON", "Binamon", "18"},
        {"0xa1adfa98d869258356459c491d08fc1eb245705b", "SDOGE", "SincereDoge", "9"},
        {"0xca3f508b8e4dd382ee878a314789373d80a5190a", "BIFI", "beefy.finance", "18"},
        {"0x20de22029ab63cf9a7cf5feb2b737ca1ee4c82a6", "CHESS", "Chess", "18"},
        {"0x0b15ddf19d47e6a86a56148fb4afffc6929bcb89", "IDIA", "Impossible Decentralized Incubator Access Token", "18"},
        {"0x9f589e3eabe42ebc94a44727b3f3531c0c877809", "TKO", "Tokocrypto Token", "18"},
        {"0x8a5d7fcd4c90421d21d30fcc4435948ac3618b2f", "MONSTA", "Cake Monster", "18"},
        {"0x87ecea8512516ced5db9375c63c23a0846c73a57", "BSC-EPK", "EpiK Protocol", "18"},
        {"0xa2b726b1145a4773f68593cf171187d8ebe4d495", "INJ", "Injective Protocol", "18"},
        {"0x1fa4a73a3f0133f0025378af00236f3abdee5d63", "NEAR", "NEAR Protocol", "18"},
        {"0xd5cbae3f69b0640724a6532cc81be9c798a755a7", "SNS", "SING NFT SHOW", "18"},
        {"0xaa8f550ed21ae4ece978f4141c4551d1deb7390a", "ROC", "Rocket Raccoon", "18"},
        {"0xa7f552078dcc247c2684336020c03648500c6d9f", "EPS", "Ellipsis", "18"},
        {"0x2aa504586d6cab3c59fa629f74c586d78b93a025", "APC", "ArenaPlay", "18"},
        {"0x869a7236280e2516491de8848391b2a02077daf7", "OSK-DAO", "OSK-DAO", "18"},
        {"0x9521728bf66a867bc65a93ece4a543d817871eb7", "CREO", "CreoEngine", "18"},
        {"0xe070cca5cdfb3f2b434fb91eaf67fa2084f324d7", "BEE", "BEE Capital", "18"},
        {"0xa184088a740c695e156f91f5cc086a06bb78b827", "AUTO", "AUTOv2", "18"},
        {"0x8d9cad20080110998966b63a87fb9d51eb49b798", "SFC", "Secret Foundation Coin", "6"},
        {"0xa85c461c66038ffc8433e2a961339b7f36656e16", "CT", "Create", "9"},
        {"0xf6a3d5c35e5641553bce157cc0e8719f81d56bab", "ENFT", "ENFT Token", "18"},
        {"0xca07f2cadb981c7886a83357b4540002c1f41020", "LAEEB", "Laeeb", "18"},
        {"0xa9f059f63cd432b65a723eeeff69304fd780070c", "SANJI", "Sanji Inu", "9"},
        {"0x7b985a5eb655238d910b52b7e25b2648c1eabc45", "FTR", "Future", "18"},
        {"0x4afc8c2be6a0783ea16e16066fde140d15979296", "HARE", "Hare Token", "9"},
        {"0x49f2145d6366099e13b10fbf80646c0f377ee7f6", "PORTO", "FC Porto Fan Token", "8"},
        {"0x0a4e1bdfa75292a98c15870aef24bd94bffe0bd4", "FOTA", "Fight Of The Ages", "18"},
        {"0xbf151f63d8d1287db5fc7a3bc104a9c38124cdeb", "AVN", "AVNRich Token", "18"},
        {"0xc864019047b864b6ab609a968ae2725dfaee808a", "BIT", "BIT TOKEN", "9"},
        {"0x1e226f8527d9f73048f4b660af44d902d4508bc2", "FENOMY", "Fenomy", "9"},
        {"0xC0Ff5EaB9909E78b7391539fa43e9688DD0b9e31", "TIP", "TIP", "18"},
        {"0x485d37ca1c8d4e0b5b11b87604816a4843c079ed", "DRB", "DigimonRabbit", "9"},
        {"0x4732a86106064577933552fcea993d30bec950a5", "DIGICHAIN", "DIGICHAIN COIN", "9"},
        {"0xb86abcb37c3a4b64f74f59301aff131a1becc787", "ZIL", "Zilliqa", "12"},
        {"0xae2df9f730c54400934c06a17462c41c08a06ed8", "DOBO", "DogeBonk.com", "9"},
        {"0x78f5d389f5cdccfc41594abab4b0ed02f31398b3", "APX", "ApolloX Token", "18"},
        {"0xe45fB6F2D975d6D92fFe2ddB5136312938Fe9252", "BMM", "Big Mouth Monster Token", "18"},
        {"0x05ad901cf196cbdceab3f8e602a47aadb1a2e69d", "ZORO", "Zoro Inu", "18"},
        {"0x873a5dfd10af54e54df6e2d646190ac6b5f62736", "BIFA", "BIFA", "18"},
        {"0x762539b45a1dcce3d36d080f74d1aed37844b878", "LINA", "Linear Token", "18"},
        {"0xaef0d72a118ce24fee3cd1d43d383897d05b4e99", "WIN", "WINk", "18"},
        {"0x746681150a5f0a84c8f78ba4bde0ca98461e8117", "PLAY", "PLAY", "9"},
        {"0xa78775bba7a542f291e5ef7f13c6204e704a90ba", "METO", "Metafluence", "18"},
        {"0xd9d0e3dd09c78930de4ac83856bd0af6d3dd2022", "GERMANY", "Germany", "18"},
        {"0xf21768ccbc73ea5b6fd3c687208a7c2def2d966e", "REEF", "Reef.finance", "18"}
    };

    // ETH (200 tokens from TP wallet)
    private static final String[][] ETH_POPULAR_TOKENS = {
        {"BUSD", "Binance USD", "0x4fabb145d64652a948d72533023f6e7a623c7c53", "18"},
        {"DAI", "Dai Stablecoin", "0x6b175474e89094c44da98b954eedeac495271d0f", "18"},
        {"FRAX", "Frax", "0x853d955acef822db058eb8505911ed77f175b99e", "18"},
        {"GUSD", "Gemini dollar", "0x056fd409e1d7a124bd7017459dfea2f387b6d5cd", "2"},
        {"HUSD", "HUSD", "0xdf574c24545e5ffecb9a659c229253d4111d87e1", "8"},
        {"MIM", "Magic Internet Money", "0x99d8a9c45b2eca8864373a26d1459e3dff1e17f3", "18"},
        {"TUSD", "TrueUSD", "0x0000000000085d4780B73119b644AE5ecd22b376", "18"},
        {"USDC", "USD Coin", "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "6"},
        {"USDD", "Decentralized USD", "0x0c10bf8fcb7bf5412187a595ab97a3609160b5c6", "18"},
        {"USDD", "Decentralized USD", "0x0C10bF8FcB7Bf5412187A595ab97a3609160b5c6", "18"},
        {"USDK", "USDK", "0x1c48f86ae57291f7686349f12601910bd8d470bb", "18"},
        {"USDN", "Neutrino USD", "0x674c6ad92fd080e4004b2312b45f796a192d27a0", "18"},
        {"USDP", "Pax Dollar", "0x8e870d67f660d95d5be530380d0ec0bd388289e1", "18"},
        {"USDS", "StableUSD", "0xa4bdb11dc0a2bec88d24a3aa1e6bb17201112ebe", "6"},
        {"USDT", "Tether USD", "0xdac17f958d2ee523a2206206994597c13d831ec7", "6"},
        {"WAGMI", "WAGMI", "0x1e987DF68CC13d271e621ec82E050A1BbD62c180", "18"},
        {"WAS", "Wasder Token", "0x0c572544a4ee47904d54aaa6a970af96b6f00e1b", "18"},
        {"WAX", "Wax Token", "0x39bb259f66e1c59d5abef88375979b4d20d98022", "8"},
        {"WAXE", "WAX Economic Token", "0x7a2bc711e19ba6aff6ce8246c546e8c4b4944dfd", "8"},
        {"wCELO", "Wrapped Celo", "0xe452e6ea2ddeb012e20db73bf5d3863a3ac8d77a", "18"},
        {"wCUSD", "Wrapped Celo USD", "0xad3e3fc59dff318beceaab7d00eb4f68b1ecf195", "18"},
        {"WHALE", "WHALE", "0x9355372396e3f6daf13359b7b607a3374cc638e0", "4"},
        {"WIC", "WaykiCoin", "0x4f878c0852722b0976a955d68b376e4cd4ae99e5", "8"},
        {"WILD", "Wilder", "0x2a3bff78b79a009976eea096a51a948a3dc00e34", "18"},
        {"WISE", "Wise Token", "0x66a0f676479cee1d7373f3dc2e2952778bff5bd6", "18"},
        {"WISE", "Wise Token", "0x66a0f676479Cee1d7373f3DC2e2952778BfF5bd6", "18"},
        {"wMANA", "Wrapped Decentraland MANA", "0xfd09cf7cfffa9932e33668311c4777cb9db3c9be", "18"},
        {"WNXM", "Wrapped NXM", "0x0d438f3b5175bebc262bf23753c1e53d03432bde", "18"},
        {"WOO", "Wootrade Network", "0x4691937a7508860f876c9c0a2a617e7d9e945d4b", "18"},
        {"WOOF", "WoofWork.io", "0x6bc08509b36a98e829dffad49fde5e412645d0a3", "18"},
        {"WPC", "WePiggy Coin", "0x6f620ec89b8479e97a6985792d0c64f237566746", "18"},
        {"WQTUM", "WQtum", "0x3103df8f05c4d8af16fd22ae63e406b97fec6938", "18"},
        {"WSCRT", "Wrapped SCRT", "0x2b89bf8ba858cd2fcee1fada378d5cd6936968be", "6"},
        {"WXT", "Wirex Token", "0xa02120696c7b8fe16c09c749e4598819b2b0e915", "18"},
        {"$ANRX", "AnRKey X", "0xcae72a7a0fd9046cf6b165ca54c9e3a3872109e0", "18"},
        {"$DG", "decentral.games", "0xee06a81a695750e71a662b51066f2c74cf4478a0", "18"},
        {"0XBTC", "0xBitcoin Token", "0xb6ed7644c69416d67b522e20bc294a9a9b405b31", "8"},
        {"1INCH", "1INCH Token", "0x111111111117dc0aa78b770fa6a738034120c302", "18"},
        {"1ONE", "Harmony ONE", "0xD5cd84D6f044AbE314Ee7E414d37cae8773ef9D3", "18"},
        {"aAAVE", "Aave Interest bearing Aave Token", "0xba3D9687Cf50fE253cd2e1cFeEdE1d6787344Ed5", "18"},
        {"AAVE", "Aave Token", "0x7fc66500c84a76ad7e9c93437bfc5ac33e2ddae9", "18"},
        {"ABT", "ArcBlock", "0xb98d4c97425d9908e66e53a6fdf673acca0be986", "18"},
        {"ABYSS", "ABYSS", "0x0e8d6b471e332f140e7d9dbb99e5e3822f728da6", "18"},
        {"AC", "ACoconut", "0x9a0aba393aac4dfbff4333b06c407458002c6183", "18"},
        {"ACH", "Alchemy", "0xed04915c23f00a313a544955524eb7dbd823143d", "8"},
        {"ACX", "Across Protocol Token", "0x44108f0223A3C3028F5Fe7AEC7f9bb2E66beF82F", "18"},
        {"ADP", "Adappter Token", "0xc314b0e758d5ff74f63e307a86ebfe183c95767b", "18"},
        {"ADS", "Adshares", "0xcfcecfe2bd2fed07a9145222e8a7ad9cf1ccd22a", "11"},
        {"ADX", "AdEx Network", "0xade00c28244d5ce17d72e40330b1c318cd12b7c3", "18"},
        {"AERGO", "Aergo", "0x91Af0fBB28ABA7E31403Cb457106Ce79397FD4E6", "18"},
        {"agEUR", "agEUR", "0x1a7e4e63778b4f12a199c062f3efdd288afcbce8", "18"},
        {"AGIX", "SingularityNET Token", "0x5b7533812759b45c2b44c19e320ba2cd2681b542", "8"},
        {"AGLD", "Adventure Gold", "0x32353a6c91143bfd6c7d363b546e62a9a2489a20", "18"},
        {"AGLD", "Adventure Gold", "0x32353A6C91143bfd6C7d363B546e62a9A2489A20", "18"},
        {"AIDI", "AIDI", "0xdA1E53E088023Fe4D1DC5a418581748f52CBd1b8", "9"},
        {"AIOZ", "AIOZ Network", "0x626e8036deb333b408be468f951bdb42433cbf18", "18"},
        {"AKITA", "Akita Inu", "0x3301ee63fb29f863f2333bd4466acb46cd8323e6", "18"},
        {"AKRO", "Akropolis", "0x8ab7404063ec4dbcfd4598215992dc3f8ec853d7", "18"},
        {"ALCX", "Alchemix", "0xdbdb4d16eda451d0503b854cf79d55697f90c8df", "18"},
        {"ALD", "Aladdin Token", "0xb26C4B3Ca601136Daf98593feAeff9E0CA702a8D", "18"},
        {"ALEPH", "aleph.im v2", "0x27702a26126e0b3702af63ee09ac4d1a084ef628", "18"},
        {"ALI", "Artificial Liquid Intelligence Token", "0x6b0b3a982b4634ac68dd83a4dbf02311ce324181", "18"},
        {"ALICE", "ALICE", "0xac51066d7bec65dc4589368da368b212745d63e8", "6"},
        {"ALPHA", "AlphaToken", "0xa1faa113cbe53436df28ff0aee54275c13b40975", "18"},
        {"AMB", "Amber Token", "0x4dc3643dbc642b72c158e7f3d2ff232df61cb6ce", "18"},
        {"AMO", "AMO Coin", "0x38c87aa89b2b8cd9b95b736e1fa7b612ea972169", "18"},
        {"AMP", "Amp", "0xff20817765cb7f73d4bde2e66e067e58d11095c2", "18"},
        {"AMPL", "Ampleforth", "0xd46ba6d942050d489dbd938a2c909a5d5039a161", "9"},
        {"ANKR", "Ankr Network", "0x8290333cef9e6d528dd5618fb97a76f268f3edd4", "18"},
        {"ANKRETH", "Ankr ETH", "0xe95a203b1a91a908f9b9ce46459d101078c2c3cb", "18"},
        {"ankrETH", "Ankr Staked ETH", "0xE95A203B1a91a908F9B9CE46459d101078c2c3cb", "18"},
        {"ANT", "Aragon Network Token", "0xa117000000f279d81a1d3cc75430faa017fa5a2e", "18"},
        {"ANY", "Anyswap", "0xf99d58e463a2e07e5692127302c20a191861b4d6", "18"},
        {"anyFSN", "Fusion", "0x979aCA85bA37c675e78322ed5d97fa980B9Bdf00", "18"},
        {"anyLTC", "ANY Litecoin", "0x0abcfbfa8e3fda8b7fba18721caf7d5cf55cf5f5", "8"},
        {"APE", "ApeCoin", "0x4d224452801aced8b2f0aebe155379bb5d594381", "18"},
        {"API3", "API3", "0x0b38210ea11411557c13457D4dA7dC6ea731B88a", "18"},
        {"APM", "APM Coin", "0xc8c424b91d8ce0137bab4b832b7f7d154156ba6c", "18"},
        {"AQT", "Alpha Quark Token", "0x2a9bDCFF37aB68B95A53435ADFd8892e86084F93", "18"},
        {"ARA", "Ara Token", "0xa92e7c82b11d10716ab534051b271d2f6aef7df5", "18"},
        {"ARCONA", "Arcona Distribution Contract", "0x0f71b8de197a1c84d31de0f1fa7926c365f052b3", "18"},
        {"ARMOR", "Armor", "0x1337def16f9b486faed0293eb623dc8395dfe46a", "18"},
        {"ARPA", "ARPA Token", "0xba50933c268f567bdc86e1ac131be072c6b0b71a", "18"},
        {"ASD", "AscendEX token", "0xff742d05420b6aca4481f635ad8341f81a6300c2", "18"},
        {"ASKO", "Askobar Network", "0xeeee2a622330e6d2036691e983dee87330588603", "18"},
        {"AST", "AirSwap", "0x27054b13b1b798b345b591a4d22e6562d47ea75a", "4"},
        {"ATA", "Automata", "0xa2120b9e674d3fc3875f415a7df52e382f141225", "18"},
        {"Auction", "Bounce Token", "0xa9b1eb5908cfc3cdf91f9b8b3a74108598009096", "18"},
        {"AUDIO", "Audius", "0x18aaa7115705e8be94bffebde57af9bfc265b998", "18"},
        {"AURA", "Aura", "0xC0c293ce456fF0ED870ADd98a0828Dd4d2903DBF", "18"},
        {"AURA", "Aurora DAO", "0xcdcfc0f66c522fd086a1b725ea3c0eeb9f9e8814", "18"},
        {"AURORA", "Aurora", "0xaaaaaa20d9e0e2461697782ef11675f668207961", "18"},
        {"AUTO", "CUBE", "0x622dFfCc4e83C64ba959530A5a5580687a57581b", "18"},
        {"AVINOC", "AVINOC Token", "0xf1ca9cb74685755965c7458528a36934df52a3ef", "18"},
        {"AWC", "Atomic Wallet Token", "0xad22f63404f7305e4713ccbd4f296f34770513f4", "8"},
        {"AXIAV3", "AXIA TOKEN (axiaprotocol.io)", "0x793786e2dd4cc492ed366a94b88a3ff9ba5e7546", "18"},
        {"AXL", "Axelar", "0x467719ad09025fcc6cf6f8311755809d45a5e5f3", "6"},
        {"AXS", "Axie Infinity Shard", "0xbb0e17ef65f82ab018d8edd776e8dd940327b28b", "18"},
        {"AXS", "Axie Infinity Shard", "0xf5d669627376ebd411e34b98f19c868c8aba5ada", "18"},
        {"BABYDOGE", "Baby Doge Coin", "0xac57de9c1a09fec648e93eb98875b212db0d460b", "9"},
        {"BABYDOGE", "BabyDoge Coin", "0xac8e13ecc30da7ff04b842f21a62a1fb0f10ebd5", "9"},
        {"BAC", "Basis", "0x3449fc1cd036255ba1eb19d65ff4ba2b8903a69a", "18"},
        {"BADGER", "Badger", "0x3472a5a71965499acd81997a54bba8d852c6e53d", "18"},
        {"BAL", "BAL", "0xba100000625a3754423978a60c9317c58a424e3d", "18"},
        {"BANANA", "ApeSwapFinance Banana", "0x92DF60c51C710a1b1C20E42D85e221f3A1bFc7f2", "18"},
        {"BAND", "BandToken", "0xba11d00c5f74255f56a5e366f4f77f5a186d7f55", "18"},
        {"BASE", "Base Protocol", "0x07150e919b4de5fd6a63de1f9384828396f25fdc", "9"},
        {"BAT", "Basic Attention Token", "0x0d8775f648430679a709e98d2b0cb6250d2887ef", "18"},
        {"BAX", "BABB BAX", "0xf920e4F3FBEF5B3aD0A25017514B769bDc4Ac135", "18"},
        {"BBTC", "Binance Wrapped BTC", "0x9be89d2a4cd102d8fecc6bf9da793be995c22541", "8"},
        {"BCDT", "Blockchain Certified Data Token", "0xacfa209fb73bf3dd5bbfb1101b9bc999c49062a5", "18"},
        {"BDP", "BDPToken", "0xf3dcbc6d72a4e1892f7917b7c43b74131df8480e", "18"},
        {"BEAN3CRV-f", "Curve.fi Factory USD Metapool: Bean", "0xc9c32cd16bf7efb85ff14e0c8603cc90f6f2ee49", "18"},
        {"BEL", "Bella", "0xa91ac63d040deb1b7a5e4d4134ad23eb0ba07e14", "18"},
        {"BEND", "Bend Token", "0x0d02755a5700414b26ff040e1de35d337df56218", "18"},
        {"BEPRO", "BetProtocolToken", "0xcf3c8be2e2c42331da80ef210e9b1b307c03d36a", "18"},
        {"BETA", "Beta Token", "0xbe1a001fe942f96eea22ba08783140b9dcc09d28", "18"},
        {"BEZOGE", "Bezoge Earth", "0xdc349913d53b446485e98b76800b6254f43df695", "9"},
        {"BFC", "Bifrost", "0x0c7d5ae016f806603cb1782bea29ac69471cab9c", "18"},
        {"BFC", "Bifrost", "0x0c7D5ae016f806603CB1782bEa29AC69471CAb9c", "18"},
        {"BFLY", "Butterfly Protocol Governance Token", "0xf680429328caaacabee69b7a9fdb21a71419c063", "18"},
        {"BFT", "BT Token", "0x01ff50f8b7f74e4f00580d9596cd3d0d6d6e326f", "18"},
        {"BICO", "Biconomy Token", "0xf17e65822b568b3903685a7c9f496cf7656cc6c2", "18"},
        {"BIDAO", "Bidao", "0x25e1474170c4c0aa64fa98123bdc8db49d7802fa", "18"},
        {"BIFI", "beefy.finance", "0x5870700f1272a1adbb87c3140bd770880a95e55d", "18"},
        {"BIOT", "BioPassport Coin", "0xc07A150ECAdF2cc352f5586396e344A6b17625EB", "9"},
        {"BIT", "BitDAO", "0x1a4b46696b2bb4794eb3d4c26f1c55f9170fa4c5", "18"},
        {"BITANT", "BitANT", "0x15ee120fd69bec86c1d38502299af7366a41d1a6", "18"},
        {"BLANK", "GoBlank Token", "0x41a3dba3d677e573636ba691a70ff2d606c29666", "18"},
        {"BLID", "Bolide", "0x8A7aDc1B690E81c758F1BD0F72DFe27Ae6eC56A5", "18"},
        {"BLUR", "Blur", "0x5283d291dbcf85356a21ba090e6db59121208b44", "18"},
        {"BLZ", "Bluzelle Token", "0x5732046a883704404f284ce41ffadd5b007fd668", "18"},
        {"BMC", "BitMartToken", "0x986EE2B944c42D017F52Af21c4c69B84DBeA35d8", "18"},
        {"BMI", "Bridge Mutual", "0x725c263e32c72ddc3a19bea12c5a0479a81ee688", "18"},
        {"BNB", "BNB Foundation", "0xb8c77482e45f1f44de1745f52c74426c631bdd52", "18"},
        {"BNB", "BNB", "0xB8c77482e45F1F44dE1745F52C74426C631bDD52", "18"},
        {"BNSD", "bns.finance", "0x668dbf100635f593a3847c0bdaf21f0a09380188", "18"},
        {"BNT", "Bancor Network Token", "0x1f573d6fb3f13d689ff844b4ce37794d79a7ff1c", "18"},
        {"BOA", "BOSAGORA", "0x746dda2ea243400d5a63e0700f190ab79f06489e", "7"},
        {"BOBA", "Boba Token", "0x42bbfa2e77757c645eeaad1655e0911a7553efbc", "18"},
        {"BOND", "BarnBridge Governance Token", "0x0391d2021f89dc339f60fff84546ea23e337750f", "18"},
        {"BOND", "BarnBridge Governance Token", "0x0391D2021f89DC339F60Fff84546EA23E337750f", "18"},
        {"BONE", "BONE SHIBASWAP", "0x9813037ee2218799597d83d4a5b6f3b6778218d9", "18"},
        {"BOO", "SpookyToken", "0x55af5865807b196bd0197e0902746f31fbccfa58", "18"},
        {"BORA", "BORA", "0x26fb86579e371c7aedc461b2ddef0a8628c93d3b", "18"},
        {"BORING", "BoringDAO", "0xBC19712FEB3a26080eBf6f2F7849b417FdD792CA", "18"},
        {"BOSO", "Sumati BOSO", "0x24196b54b6400049f69fa7f92c3634dfa44689f0", "18"},
        {"BOSON", "Boson Token", "0xc477d038d5420c6a9e0b031712f61c5120090de9", "18"},
        {"BOTS", "Bot Ocean", "0xf9fbe825bfb2bf3e387af0dc18cac8d87f29dea8", "18"},
        {"BOTTO", "Botto", "0x9dfad1b7102d46b1b197b90095b5c4e9f5845bba", "18"},
        {"BOX", "BOX Token", "0xe1A178B681BD05964d3e3Ed33AE731577d9d96dD", "18"},
        {"BRC", "Bridge Coin", "0x11c49e5ca7222f89909a6ec42d81eb6b2af5ff40", "18"},
        {"BRD", "Bread", "0x558ec3152e2eb2174905cd19aea4e34a23de9ad6", "18"},
        {"BRZ", "BRZ", "0x420412e765bfa6d85aaac94b4f7b708c89be2e2b", "4"},
        {"BST", "BlocksquareToken", "0x509a38b7a1cc0dcd83aa9d06214663d9ec7c7f4a", "18"},
        {"BTM", "Bytom", "0xcb97e65f07da24d46bcdd078ebebd7c6e6e3d750", "8"},
        {"BTMX", "BitMax token", "0xcca0c9c383076649604eE31b20248BC04FdF61cA", "18"},
        {"BTRST", "BTRST", "0x799ebfabe77a6e34311eeee9825190b9ece32824", "18"},
        {"BTSE", "BTSE Token", "0x666d875c600aa06ac1cf15641361dec3b00432ef", "8"},
        {"BTSG", "BitSong", "0x05079687D35b93538cbd59fe5596380cae9054A9", "18"},
        {"BZ", "BZ", "0x4375e7ad8a01b8ec3ed041399f62d9cd120e0063", "18"},
        {"BZN", "Benzene", "0x6524B87960c2d573AE514fd4181777E7842435d4", "18"},
        {"BZRX", "bZx Protocol Token", "0x56d811088235F11C8920698a204A5010a788f4b3", "18"},
        {"BZZ", "BZZ", "0x19062190b1925b5b6689d7073fdfc8c2976ef8cb", "16"},
        {"C20", "Crypto20", "0x26e75307fc0c021472feb8f727839531f112f317", "18"},
        {"C98", "Coin98", "0xae12c5930881c53715b369cec7606b70d8eb229f", "18"},
        {"CAP", "Cap", "0x43044f861ec040db59a7e324c40507addb673142", "18"},
        {"CAS", "Cashaa", "0xe8780b48bdb05f928697a5e8155f672ed91462f7", "18"},
        {"CAW", "A Hunters Dream", "0xf3b9569f82b18aef890de263b84189bd33ebe452", "18"},
        {"cBAT", "Compound Basic Attention Token", "0x6c8c6b02e7b2be14d4fa6022dfd6d75921d90e4e", "8"},
        {"cDAI", "cDAI", "0x5d3a536E4D6DbD6114cc1Ead35777bAB948E3643", "8"},
        {"CDT", "CoinDash Token", "0x177d39ac676ed1c67a2b268ad7f1e58826e5b0af", "18"},
        {"CEEK", "CEEK", "0xb056c38f6b7dc4064367403e26424cd2c60655e1", "18"},
        {"CEL", "Celsius", "0xaaaebe6fe48e54f431b0c390cfaf0b017d09d42d", "4"},
        {"CELL", "Cellframe Token", "0x26c8afbbfe1ebaca03c2bb082e69d0476bffe099", "18"},
        {"CELR", "CelerToken", "0x4f9254c83eb525f9fcf346490bbb3ed28a81c667", "18"},
        {"CERE", "CERE Network", "0x2da719db753dfa10a62e140f436e1d67f2ddb0d6", "10"},
        {"CET", "CoinEx Token", "0x081f67afa0ccf8c7b17540767bbe95df2ba8d97f", "18"},
        {"cETH", "Compound Ether", "0x4ddc2d193948926d02f9b1fe9e1daa0718270ed5", "8"},
        {"cEUR", "Celo Euro", "0xee586e7eaad39207f0549bc65f19e336942c992f", "18"},
        {"CGT", "CACHE Gold", "0xf5238462e7235c7b62811567e63dd17d12c2eaa0", "8"},
        {"CHAIN", "Chain Games", "0xc4c2614e694cf534d407ee49f8e44d125e4681c4", "18"},
        {"CHIRP", "Chirp", "0xd3c4163e18aaebe43b4fc8b1a53dc3241fb3d017", "18"},
        {"CHO", "choise.com Token", "0xbba39fd2935d5769116ce38d46a71bde9cf03099", "18"},
        {"CHR", "Chroma", "0x8A2279d4A90B6fe1C4B30fa660cC9f926797bAA2", "6"},
        {"CHSB", "SwissBorg Token", "0xba9d4199fab4f26efe3551d490e3821486f135ba", "8"},
        {"CHZ", "chiliZ", "0x3506424f91fd33084466f402d5d97f05f8e3b4af", "18"},
        {"CIV", "Civilization", "0x37fe0f067fa808ffbdd12891c0858532cfe7361d", "18"},
        {"CLV", "Clover", "0x80c62fe4487e1351b47ba49809ebd60ed085bf52", "18"},
        {"CND", "Cindicator Token", "0xd4c435f5b09f855c3317c8524cb1f586e42795fa", "18"},
        {"CNTR", "Centaur Token", "0x03042482d64577a7bdb282260e2ea4c8a89c064b", "18"},
        {"COMP", "Compound", "0xc00e94cb662c3520282e6f5717214004a7f26888", "18"},
        {"COR", "COR Token", "0x9c2dc0c3cc2badde84b0025cf4df1c5af288d835", "18"},
        {"CORE", "cVault.finance", "0x62359ed7505efc61ff1d56fef82158ccaffa23d7", "18"},
        {"COS", "Contentos", "0x589891a198195061cb8ad1a75357a3b7dbadd7bc", "18"},
        {"COT", "CosplayToken", "0x5CAc718A3AE330d361e39244BF9e67AB17514CE8", "18"},
        {"COTI", "COTI Token", "0xddb3422497e61e13543bea06989c0789117555c5", "18"},
        {"COVAL", "CircuitsOfValue", "0x3d658390460295fb963f54dc0899cfb1c30776df", "8"},
        {"COW", "CoW Protocol Token", "0xdef1ca1fb7fbcdc777520aa7f396b4e015f497ab", "18"},
        {"CQT", "Covalent Query Token", "0xd417144312dbf50465b1c641d016962017ef6240", "18"},
        // 补充高共识代币（官方标准主网合约地址）
        {"ARB", "Arbitrum", "0xB50721BCf8d664c30412Cfbc6cf7a15145234ad1", "18"},
        {"OP", "Optimism", "0x4200000000000000000000000000000000000042", "18"},
        {"PEPE", "Pepe", "0x6982508145454Ce325dDbE47a25d0043C938B731", "18"},
        {"LDO", "Lido DAO", "0x5A98FcBEA516Cf06857215779Fd812CA3beF1B32", "18"},
        {"MKR", "Maker", "0x9f8F72aA9304c8B593d555F12eF6589cC3A579A2", "18"},
        {"SNX", "Synthetix", "0xC011a73ee8576Fb46F5E1c5751cA3B9Fe0af2a6F", "18"},
    };

    // MATIC (200 tokens from TP wallet)
    private static final String[][] MATIC_POPULAR_TOKENS = {
        {"BUSD", "(PoS) Binance USD", "0xdab529f40e671a1d4bf91361c21bf9f0c9712ab7", "18"},
        {"DAI", "-", "0x8f3cf7ad23cd3cadbd9735aff958023239c6a063", "18"},
        {"FRAX", "Frax", "0x45c32fa6df82ead1e2ef74d17b76547eddfaff89", "18"},
        {"GUSD", "Gemini dollar (PoS)", "0xc8a94a3d3d2dabc3c1caffffdca6a7543c3e3e65", "2"},
        {"HUSD", "HUSD (PoS)", "0x2088c47fc0c78356c622f79dba4cbe1ccfa84a91", "8"},
        {"PAX", "Paxos Standard (PoS)", "0x6f3b3286fd86d8b47ec737ceb3d0d354cc657b3e", "18"},
        {"TUSD", "TrueUSD (PoS)", "0x2e1ad108ff1d8c782fcbbb89aad783ac49586756", "18"},
        {"USDC", "-", "0x2791bca1f2de4661ed88a30c99a7a9449aa84174", "6"},
        {"USDD", "Decentralized USD (PoS)", "0xffa4d863c96e743a2e1513824ea006b8d0353c57", "18"},
        {"USDK", "USDK (PoS)", "0xd07a7fac2857901e4bec0d89bbdae764723aab86", "18"},
        {"USDT", "(PoS) Tether USD", "0xc2132d05d31c914a87c6611c10748aeb04b58e8f", "6"},
        {"UST", "UST (Wormhole)", "0xe6469ba6d2fd6130788e0ea9c0a0515900563b59", "6"},
        {"UST", "Wrapped UST Token (PoS)", "0x692597b009d13c4049a947cab2239b7d6517875f", "18"},
        {"wBAN", "Wrapped Banano", "0xe20b9e246db5a0d21bf9209e4858bc9a3ff7a034", "18"},
        {"WBNB", "Wrapped BNB (Wormhole)", "0xecdcb5b88f8e3c15f95c720c51c71c9e2080525d", "18"},
        {"WCRO", "Wrapped CRO", "0xf2d8124b8f9267dad61351c7ad252362880c6638", "18"},
        {"WELT", "FABWELT", "0x23e8b6a3f6891254988b84da3738d2bfe5e703b9", "18"},
        {"WEXpoly", "WaultSwap Polygon", "0x4c4BF319237D98a30A929A96112EfFa8DA3510EB", "18"},
        {"WHENGOOD", "WHENGOOD", "0xF4DB57355020Cd1eF9e2F843F494EC615964e868", "9"},
        {"WNT", "Wicrypt Network Token", "0x82a0e6c02b91ec9f6ff943c0a933c03dbaa19689", "18"},
        {"WOMBAT", "Wombat", "0x0c9c7712c83b3c70e7c5e11100d33d9401bdf9dd", "18"},
        {"WOO", "Wootrade Network (PoS)", "0x1b815d120b3ef02039ee11dc2d33de7aa4a8c603", "18"},
        {"WOW", "WOWswap", "0x855d4248672a1fce482165e8dbe1207b94b1968a", "18"},
        {"WPC", "WePiggy Coin", "0x6F620EC89B8479e97A6985792d0c64F237566746", "18"},
        {"wPPC", "WrappedPeercoin", "0x91E7E32C710661C44ae44D10Aa86135d91C3Ed65", "6"},
        {"WRLD", "NFT Worlds", "0xd5d86fc8d5c0ea1ac1ac5dfab6e529c9967a45e9", "18"},
        {"WT", "WinGoal Token", "0x72b6a3155ef6c5a6222f2f946a03e3f3fd991e3c", "18"},
        {"wUSD+", "Wrapped USD+", "0x4e36d8006416ea1d939a0eeae73afdaca86bd376", "6"},
        {"WXT", "Wirex Token", "0xBBCA42c60b5290F2c48871A596492F93fF0Ddc82", "18"},
        {"$KMC", "$KMC", "0x44d09156c7b4acf0c64459fbcced7613f5519918", "18"},
        {"$ZKP", "$ZKP Token", "0x9A06Db14D639796B25A6ceC6A1bf614fd98815EC", "18"},
        {"1FLR", "Flare Token", "0x5f0197ba06860dac7e31258bdf749f92b6a636d4", "18"},
        {"1INCH", "1Inch (PoS)", "0x9c2c5fd7b07e95ee044ddeba0e97a665f142394f", "18"},
        {"AAVE", "-", "0xd6df932a45c0f255f85145f286ea0b292b21c90b", "18"},
        {"ADS", "Adshares (PoS)", "0x598e49f01befeb1753737934a5b11fea9119c796", "11"},
        {"ADX", "AdEx Network (PoS)", "0xdda7b23d2d72746663e7939743f929a3d85fc975", "18"},
        {"aETH", "Ankr Eth2 Reward Bearing Bond (PoS)", "0xc4e82ba0fe6763cbe5e9cbca0ba7cbd6f91c6018", "18"},
        {"agEUR", "agEUR", "0xe0b52e49357fd4daf2c15e02058dce6bc0057db4", "18"},
        {"AGIX", "SingularityNET Token (PoS)", "0x190eb8a183d22a4bdf278c6791b152228857c033", "8"},
        {"AIOZ", "AIOZ Network (PoS)", "0xe2341718c6c0cbfa8e6686102dd8fbf4047a9e9b", "18"},
        {"AMP", "Amp Token (PoS)", "0x0621d647cecbFb64b79E44302c1933cB4f27054d", "18"},
        {"AMP", "Amp Token (PoS)", "0x0621d647cecbfb64b79e44302c1933cb4f27054d", "18"},
        {"AMUSDC", "Aave Matic Market USDC", "0x1a13f4ca1d028320a707d99520abfefca3998b7f", "6"},
        {"amUSDT", "-", "0x60d55f02a771d515e077c9c2403a1ef324885cec", "6"},
        {"AMWMATIC", "Aave Matic Market WMATIC", "0x8df3aad3a84da6b69a4da8aec3ea40d9091b2ac4", "18"},
        {"ANKR", "Ankr (PoS)", "0x101a023270368c0d50bffb62780f4afd4ea79c35", "18"},
        {"APE", "ApeCoin (PoS)", "0xB7b31a6BC18e48888545CE79e83E06003bE70930", "18"},
        {"APPLE", "APPLE", "0xda8F20bf431d04a3661250F922D75e2bBE0B001C", "18"},
        {"ASTRAFER", "Astrafer", "0xdfce1e99a31c4597a3f8a8945cbfa9037655e335", "18"},
        {"ATA", "Automata (PoS)", "0x0df0f72ee0e5c9b7ca761ecec42754992b2da5bf", "18"},
        {"ATK", "Attack", "0xF868939Ee81F04f463010BC52EAb91c0839eF08c", "18"},
        {"AVAX", "Avalanche Token", "0x2c89bbc92bd86f8075d1decc58c7f4e0107f286b", "18"},
        {"AVAX", "Avalanche Token", "0x2C89bbc92BD86F8075d1DEcc58C7F4E0107f286b", "18"},
        {"BABM", "Babylon Chain", "0xaa51f070e728ed97ea9815285a2a827764305873", "18"},
        {"babyshib", "Polybabyshib", "0x828bf755C43ae94513735A6735A59Cc5f8B36C77", "18"},
        {"BAL", "Balancer (PoS)", "0x9a71012b13ca4d3d0cdc72a177df3ef03b0e76a3", "18"},
        {"BANANA", "ApeSwapFinance Banana", "0x5d47baba0d66083c52009271faf3f50dcc01023c", "18"},
        {"BAND", "BandToken (PoS)", "0xA8b1E0764f85f53dfe21760e8AfE5446D82606ac", "18"},
        {"BAND", "BandToken (PoS)", "0xa8b1e0764f85f53dfe21760e8afe5446d82606ac", "18"},
        {"BAT", "Basic Attention Token (PoS)", "0x3cef98bb43d732e2f285ee605a8158cde967d219", "18"},
        {"BCT", "Toucan Protocol: Base Carbon Tonne", "0x2f800db0fdb5223b3c3f354886d907a671414a7f", "18"},
        {"BEL", "Bella (PoS)", "0x28C388FB1F4fa9F9eB445f0579666849EE5eeb42", "18"},
        {"BEPRO", "BetProtocolToken (PoS)", "0x07cc1cc3628cc1615120df781ef9fc8ec2feae09", "18"},
        {"BIFI", "beefy.finance", "0xfbdd194376de19a88118e84e279b977f165d01b8", "18"},
        {"BLOK", "BLOK", "0x229b1b6c23ff8953d663c4cbb519717e323a0a84", "18"},
        {"BLZ", "Bluzelle Token (PoS)", "0x438b28c5aa5f00a817b7def7ce2fb3d5d1970974", "18"},
        {"BNB", "Binance Token", "0x5c4b7ccbf908e64f32e12c6650ec0c96d717f03f", "18"},
        {"BNB", "BNB (PoS)", "0x3BA4c387f786bFEE076A58914F5Bd38d668B42c3", "18"},
        {"BNT", "Bancor Network Token (PoS)", "0xc26d47d5c33ac71ac5cf9f776d63ba292a4f7842", "18"},
        {"BOB", "BOB", "0xB0B195aEFA3650A6908f15CdaC7D92F8a5791B0B", "18"},
        {"BOBA", "Boba Token (PoS)", "0xa4B2B20b2C73c7046ED19AC6bfF5E5285c58F20a", "18"},
        {"BOMB", "Bombcrypto Coin", "0xb2c63830d4478cb331142fac075a39671a5541dc", "18"},
        {"BORING", "BoringDAO (PoS)", "0xff88434E29d1E2333aD6baa08D358b436196da6b", "18"},
        {"BOSON", "Boson Token (PoS)", "0x9b3b0703d392321ad24338ff1f846650437a43c9", "18"},
        {"BP", "Baby Pistachio", "0x573db383a9e800444bac2dfbed120756f7fc8715", "9"},
        {"BRK", "BRKToken", "0xef8bd27ea1fd7fa02831c6d5b1a5921bbb4e7efe", "18"},
        {"BTU", "BTU Protocol (PoS)", "0xfdc26cda2d2440d0e83cd1dee8e8be48405806dc", "18"},
        {"BWO", "Battle World", "0xC1543024DC71247888a7e139c644F44E75E96d38", "18"},
        {"BWO", "Battle World", "0xc1543024dc71247888a7e139c644f44e75e96d38", "18"},
        {"BZRX", "bZx Protocol Token (PoS)", "0x54cfe73f2c7d0c4b62ab869b473f5512dc0944d2", "18"},
        {"CAS", "CASToken", "0xec4fe610b4107c95b56decc885089c06f85a63cb", "18"},
        {"CAT", "CAT", "0xb932d203f83b8417be0f61d9dafad09cc24a4715", "18"},
        {"CD", "Credit DAO Token", "0xcdc1d4304b5c88a1e40f0fa68241bf5dd1816ee4", "18"},
        {"CEL", "Celsius (PoS)", "0xd85d1e945766fea5eda9103f918bd915fbca63e6", "4"},
        {"CHAMP", "Ultimate Champions Token", "0xED755dBa6Ec1eb520076Cec051a582A6d81A8253", "18"},
        {"CHAMP", "NFT Champions", "0x8f9e8e833a69aa467e42c46cca640da84dd4585f", "8"},
        {"CHC", "CHC", "0x6ab4d79c4dbb009a2c4c6b7f8e8e067fc92e28b9", "18"},
        {"CHICK", "loserchick", "0x9e725cf7265d12fd5f59499aff1258ca92cac74d", "18"},
        {"CHS", "CHS", "0x03ae3df74ed02a83bf941447f464e8369ba44440", "18"},
        {"CHSB", "SwissBorg (PoS)", "0x67ce67ec4fcd4aca0fcb738dd080b2a21ff69d75", "8"},
        {"CHZ", "CHZ (PoS)", "0xf1938Ce12400f9a761084E7A80d37e732a4dA056", "18"},
        {"CHZ", "CHZ (PoS)", "0xf1938ce12400f9a761084e7a80d37e732a4da056", "18"},
        {"CIOTX", "Crosschain IOTX", "0x300211def2a644b036a9bdd3e58159bb2074d388", "18"},
        {"COMP", "(PoS) Compound", "0x8505b9d2254a7ae468c0e9dd10ccea3a837aef5c", "18"},
        {"CPIE", "CremePieSwap Token", "0xfad70FD116559914240faB82b0078c4E82a6a1B8", "18"},
        {"CPLE", "Carpool Life Economy", "0x87fcfBD3Eae94524D5Ef0c42D01f3DFC96142451", "18"},
        {"CRO", "CRO (PoS)", "0xada58df0f643d959c2a47c9d4d4c1a4defe3f11c", "8"},
        {"CROWD", "CrowdToken", "0x483dd3425278c1f79f377f1034d9d2cae55648b6", "18"},
        {"CRV", "CRV (PoS)", "0x172370d5cd63279efa6d502dab29171933a610af", "18"},
        {"CUBO", "CUBO token", "0x381d168de3991c7413d46e3459b48a5221e3dfe4", "18"},
        {"CXO", "CargoX Token (PoS)", "0xf2ae0038696774d65e67892c9d301c5f2cbbda58", "18"},
        {"DAFI", "DAFI Token (PoS)", "0x638df98ad8069a15569da5a6b01181804c47e34c", "18"},
        {"DATA", "Streamr", "0x3a9a81d576d83ff21f26f325066054540720fc34", "18"},
        {"DATA", "Streamr", "0x3a9A81d576d83FF21f26f325066054540720fC34", "18"},
        {"DERC", "DeRace Token", "0xb35fcbcf1fd489fce02ee146599e893fdcdc60e6", "18"},
        {"DEUS", "DEUS", "0xde5ed76e7c05ec5e4572cfc88d1acea165109e44", "18"},
        {"DF", "dForce (PoS)", "0x08c15fa26e519a78a666d19ce5c646d55047e0a3", "18"},
        {"DFYN", "DFYN Token (PoS)", "0xc168e40227e4ebd8c1cae80f7a55a4f0e6d66c97", "18"},
        {"DG", "Decentral Games (PoS)", "0xef938b6da8576a896f6e0321ef80996f4890f9c4", "18"},
        {"DHT", "dHedge DAO Token (PoS)", "0x8c92e38eca8210f4fcbf17f0951b198dd7668292", "18"},
        {"DLYCOP", "Daily COP", "0x1659ffb2d40dfb1671ac226a0d9dcc95a774521a", "18"},
        {"DOGZ", "DOGZ (PoS)", "0x29198a281fe6ed6a49abe32a5d6864adccd7e89e", "18"},
        {"DQUICK", "-", "0xf28164a485b0b2c90639e47b0f377b4a438a16b1", "18"},
        {"Dreamdoge", "Dreamdoge", "0xe68ff461C0392A86DF024B70b66AAeDC2dDe39E7", "18"},
        {"DROSE", "Digital Rose Game", "0x8fe893e3b6d7d2407509446257a782e4f6fefa7d", "18"},
        {"DSLA", "DSLA (PoS)", "0xa0e390e9cea0d0e8cd40048ced9fa9ea10d71639", "18"},
        {"EG", "Energy Guardian", "0x6104947274a38CaEeb66ec84C5b78095a75AD4f8", "6"},
        {"ELON", "Dogelon (PoS)", "0xe0339c80ffde91f3e20494df88d4206d86024cdf", "18"},
        {"ENJ", "Enjin Coin (PoS)", "0x7ec26842f195c852fa843bb9f6d8b583a274a157", "18"},
        {"ENO", "EnoToken (PoS)", "0x7f36C54Da31b2Dd355CAfFEC0249F26Da41e3fcD", "18"},
        {"eQUAD", "Quadrant Protocol", "0xdab625853c2b35d0a9c6bd8e5a097a664ef4ccfb", "18"},
        {"ERN", "Ethernity Chain (PoS)", "0x0e50bea95fe001a370a4f1c220c49aedcb982dec", "18"},
        {"ETHM", "Ethereum Meta", "0x55b1a124c04a54eefdefe5fa2ef5f852fb5f2f26", "18"},
        {"EURS", "STASIS EURS Token (PoS)", "0xe111178a87a3bff0c8d18decba5798827539ae99", "2"},
        {"EWTB", "Energy Web Token Bridged (PoS)", "0x43e4b063f96c33f0433863a927f5bad34bb4b03d", "18"},
        {"FEG", "FEGtoken (PoS)", "0xf391f574c63d9b8764b7a1f56d6383762e07b75b", "9"},
        {"FET", "Fetch (PoS)", "0x7583feddbcefa813dc18259940f76a02710a8905", "18"},
        {"FISH", " ", "0x3a3df212b7aa91aa0402b9035b098891d276572b", "18"},
        {"FLAME", "FireStarter", "0x22e3f02f86bc8ea0d73718a2ae8851854e62adc5", "18"},
        {"FORTH", "Ampleforth Governance (PoS)", "0x5ecba59dacc1adc5bdea35f38a732823fc3de977", "18"},
        {"FOX", "FOX (PoS)", "0x65a05db8322701724c197af82c9cae41195b0aa8", "18"},
        {"FREE", "Free Coin (PoS)", "0x7cef6ed1e07079e174601d39066ad0856cb47988", "18"},
        {"FRM", "Ferrum Network Token", "0xd99bafe5031cc8b345cb2e8c80135991f12d7130", "18"},
        {"FRONT", "Frontier Token (PoS)", "0xa3ed22eee92a3872709823a6970069e12a4540eb", "18"},
        {"FSHIB", "FSHIB", "0x5E5c5D6b67285C2F6346b40e88688BBf198Cbe1d", "18"},
        {"FSN", "Fusion Token (PoS)", "0xfa1171334cb3a0f0a91e8ca6765f10e9638d1cbf", "18"},
        {"FTM", "UNKNOWN", "0xb85517b87bf64942adf3a0b9e4c71e4bc5caa4e5", "18"},
        {"FTM", "Fantom Token (PoS)", "0xc9c1c1c20b3658f8787cc2fd702267791f224ce1", "18"},
        {"FXS", "-", "0x3e121107f6f22da4911079845a470757af4e1a1b", "18"},
        {"FXS", "Frax Share", "0x1a3acf6d19267e2d3e7f898f42803e90c9219062", "18"},
        {"FYN", "Affyn", "0x3b56a704c01d650147ade2b8cee594066b3f9421", "18"},
        {"GAIA", "GAIA Everworld", "0x723b17718289a91af252d616de2c77944962d122", "18"},
        {"GBYTE", "Imported GBYTE", "0xab5f7a0e20b0d056aed4aa4528c78da45be7308b", "18"},
        {"GEL", "Gelato Network Token", "0x15b7c0c907e4C6b9AdaAaabC300C08991D6CEA05", "18"},
        {"GEO$", " ", "0xf1428850f92b87e629c6f3a3b75bffbc496f7ba6", "18"},
        {"GET", "GET Protocol (PoS)", "0xdb725f82818de83e99f1dac22a9b5b51d3d04dd4", "18"},
        {"GFB", "GameFiBox Token", "0x8Dd28e47e550243313eA997c4906605e7876c764", "18"},
        {"GFI", "Gravity Finance", "0x874e178a2f3f3f9d34db862453cd756e7eab0381", "18"},
        {"GHAF", "GHA Foundation", "0x7585042b97f82404438c778185Ea6F5797B4e8ad", "18"},
        {"GHST", "Aavegotchi GHST Token (PoS)", "0x385eeac5cb85a38a9a07a70c73e0a3271cfb54a7", "18"},
        {"GLM", "Golem Network Token (PoS)", "0x0b220b82f3ea3b7f6d9a1d8ab58930c064a2b5bf", "18"},
        {"GMEE", "GAMEE", "0xcf32822ff397ef82425153a9dcb726e5ff61dca7", "18"},
        {"GNO", "Gnosis Token (PoS)", "0x5ffd62d3c3ee2e81c00a7b9079fb248e7df024a8", "18"},
        {"GNS", "Gains Network", "0xe5417af564e4bfda1c483642db72007871397896", "18"},
        {"GNS", "Gains Network", "0xE5417Af564e4bFDA1c483642db72007871397896", "18"},
        {"GOGO", "UNKNOWN", "0xdd2af2e723547088d3846841fbdcc6a8093313d6", "18"},
        {"GPAY", "GemPay", "0x29c28f7e50ea89343fe2b2c75f2652b883ed0cbd", "18"},
        {"GRT", "Graph Token (PoS)", "0x5fe2b58c013d7601147dcdd68c143a77499f5531", "18"},
        {"GTC", "Gitcoin (PoS)", "0xdb95f9188479575f3f718a245eca1b3bf74567ec", "18"},
        {"Guru", "Guru", "0x96e7593E376a8f75fD52ae71B7b45358eF373AE8", "18"},
        {"HELLO", "Hello Metaverse Token", "0x25412B8e8fd20fD5C875227DA1641Ef490191a5d", "18"},
        {"HERO", "HERO", "0x6afcff9189e8ed3fcc1cffa184feb1276f6a82a5", "18"},
        {"HOGE", "hoge.finance", "0x58c1bbb508e96cfec1787acf6afe1c7008a5b064", "9"},
        {"HONOR", "HONOR", "0xb82A20B4522680951F11c94c54B8800c1C237693", "18"},
        {"HOP", "Hop", "0xc5102fE9359FD9a28f877a67E36B0F050d81a3CC", "18"},
        {"HOPR", "Hopr (PoS)", "0x6ccbf3627b2c83afef05bf2f035e7f7b210fe30d", "18"},
        {"HOT", "UNKNOWN", "0x0C51f415cF478f8D08c246a6C6Ee180C5dC3A012", "18"},
        {"HOT", "HOLO (PoS)", "0x0c51f415cf478f8d08c246a6c6ee180c5dc3a012", "18"},
        {"HT", "HuobiToken (PoS)", "0xfad65eb62a97ff5ed91b23afd039956aaca6e93b", "18"},
        {"ICE", "Decentral Games ICE", "0xc6c855ad634dcdad23e64da71ba85b8c51e5ad7c", "18"},
        {"ICE", "IceToken (PoS)", "0xdf00c50a3dae240860f57b77508203b8d9593283", "18"},
        {"ICE", "IceToken", "0x4e1581f01046efdd7a1a2cdb0f82cdd7f71f2e59", "18"},
        {"ICHI", "ICHI", "0x111111517e4929d3dcbdfa7cce55d30d4b6bc4d6", "18"},
        {"IFNT", "IFINITE", "0x198380A405036307cF3ECa2a6350560D0a14bC6A", "18"},
        {"INJ", "Injective Token (PoS)", "0x4e8dc2149eac3f3def36b1c281ea466338249371", "18"},
        {"INST", "Instadapp (PoS)", "0xf50d05a1402d0adafa880d36050736f9f6ee7dee", "18"},
        {"IOEN", "Internet of Energy Network", "0xd0e9c8f5fae381459cf07ec506c1d2896e8b5df6", "18"},
        {"IOTX", "IoTeX Network (PoS)", "0xf6372cdb9c1d3674e83842e3800f2a62ac9f3c66", "18"},
        {"IQ", "Everipedia IQ (PoS)", "0xb9638272ad6998708de56bbc0a290a1de534a578", "18"},
        {"ITFX", "INS3 Token", "0xeb48CF0caA8B39E0B3b1fEE774caa9F9c45dbcD9", "18"},
        {"IUX", "GeniuX", "0x346404079b3792a6c548b072b9c4dddfb92948d5", "18"},
        {"IXT", "PlanetIX", "0xe06bd4f5aac8d0aa337d13ec88db6defc6eaeefe", "18"},
        {"JPYC", "JPY Coin (PoS)", "0x6ae7dfc73e0dde2aa99ac063dcf7e8a63265108c", "18"},
        {"JRT", "Jarvis Reward Token (PoS)", "0x596ebe76e2db4470966ea395b0d063ac6197a8c5", "18"},
        {"KAKA", "KAKA", "0x12a65A254c69849F9Be78C6625c81D9C7Ffda771", "18"},
        {"KASTA", "KastaToken", "0x235737dbb56e8517391473f7c964db31fa6ef280", "18"},
        {"KEEP", "KEEP Token (PoS)", "0x42f37a1296b2981f7c3caced84c5096b2eb0c72c", "18"},
        {"KITTY", "KITTY", "0x182dB1252C39073eeC9d743F13b5eeb80FDE314e", "18"},
        {"KLIMA", "Klima DAO", "0x4e78011ce80ee02d2c3e649fb657e45898257815", "9"},
        {"KNC", "Kyber Network Crystal v2 (PoS)", "0x1c954e8fe737f99f68fa1ccda3e51ebdb291948c", "18"},
        {"KNOT", "Karmaverse Knot", "0xb763f1177e9b2fb66fbe0d50372e3e2575c043e5", "18"},
        {"KOGECOIN", "kogecoin.io", "0x13748d548d95d78a3c83fe3f32604b4796cffa23", "9"},
        {"KOLO", "KOLO Music (PoS)", "0xe1240e13FDA129845d17b73eaE548Cd690e8DEC8", "6"},
        {"KOM", "Kommunitas", "0xc004e2318722ea2b15499d6375905d75ee5390b8", "8"},
        {"LCX", "LCX (PoS)", "0xe8a51d0dd1b4525189dda2187f90ddf0932b5482", "18"},
        {"LDO", "Lido DAO Token (PoS)", "0xc3c7d422809852031b44ab29eec9f1eff2a58756", "18"},
        {"LEND", "(PoS) EthLend Token", "0x313d009888329c9d1cf4f75ca3f32566335bd604", "18"},
        {"LEO", "Bitfinex LEO Token (PoS)", "0x06d02e9d62a13fc76bb229373fb3bbbd1101d2fc", "18"},
        {"LIF3", "LIF3", "0x56ac3cb5e74b8a5a25ec9dc05155af1dad2715bd", "18"},
        {"LINK", "-", "0x53e0bca35ec356bd5dddfebbd1fc0fd03fabad39", "18"},
    };

    // AVAX (93 tokens from TP wallet)
    private static final String[][] AVAX_POPULAR_TOKENS = {
        {"BUSD", "BUSD Token", "0x9C9e5fD8bbc25984B178FdCE6117Defa39d2db39", "18"},
        {"DAI", "Dai Stablecoin", "0xbA7dEebBFC5fA1100Fb055a87773e1E99Cd3507a", "18"},
        {"FRAX", "Frax", "0xDC42728B0eA910349ed3c6e1c9Dc06b5FB591f98", "18"},
        {"FRAX", "Frax", "0xd24c2ad096400b6fbcd2ad8b24e7acbc21a1da64", "18"},
        {"TUSD", "TrueUSD", "0x1c20e891bab6b1727d14da358fae2984ed9b59eb", "18"},
        {"USDC", "USD Coin", "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e", "6"},
        {"USDD", "Decentralized USD", "0xcf799767d366d789e8B446981C2D578E241fa25c", "18"},
        {"USDt", "TetherToken", "0x9702230a8ea53601f5cd2dc00fdbc13d4df4a8c7", "6"},
        {"WALBT", "Wrapped AllianceBlock Token", "0x9E037dE681CaFA6E661e6108eD9c2bd1AA567Ecd", "18"},
        {"WBTC.e", "Wrapped BTC", "0x50b7545627a5162F82A992c33b87aDc75187B218", "8"},
        {"WBTC.e", "Wrapped BTC", "0x50b7545627a5162f82a992c33b87adc75187b218", "8"},
        {"WETH.e", "Wrapped Ether", "0x49D5c2BdFfac6CE2BFdB6640F4F80f226bc10bAB", "18"},
        {"1INCH.e", "1INCH Token", "0xd501281565bf7789224523144fe5d98e8b28f267", "18"},
        {"aaBLOCK", "Blocknet", "0xC931f61B1534EB21D8c11B24f3f5Ab2471d4aB50", "8"},
        {"AAVE.e", "Aave Token", "0x63a72806098Bd3D9520cC43356dD78afe5D386D9", "18"},
        {"AAVE.e", "Aave Token", "0x63a72806098bd3d9520cc43356dd78afe5d386d9", "18"},
        {"ALPHA.e", "AlphaToken", "0x2147efff675e4a4ee1c2f918d181cdbd7a8e208f", "18"},
        {"ANY", "Anyswap", "0xb44a9b6905af7c801311e8f4e76932ee959c663c", "18"},
        {"anyUSDT", "Tether USD", "0x94977c9888f3d2fafae290d33fab4a5a598ad764", "6"},
        {"AVE", "Avaware", "0x78ea17559B3D2CF85a7F9C2C704eda119Db5E6dE", "18"},
        {"AVME", "AVME", "0x1ECd47FF4d9598f89721A2866BFEb99505a413Ed", "18"},
        {"BAT.e", "Basic Attention Token", "0x98443b96ea4b0858fdf3219cd13e98c7a4690588", "18"},
        {"BIFI", "beefy.finance", "0xd6070ae98b8069de6b494332d1a1a81b6179d960", "18"},
        {"BOBA", "BOBA Token", "0x3cD790449CF7D187a143d4Bd7F4654d4f2403e02", "18"},
        {"BTC.b", "Bitcoin", "0x152b9d0FdC40C096757F570A51E494bd4b943E50", "8"},
        {"BUSD.e", "Binance USD", "0x19860ccb0a68fd4213ab9d8266f7bbf05a8dde98", "18"},
        {"COMP.e", "Compound", "0xc3048e19e76cb9a3aa9d77d8c03c29fc906e2437", "18"},
        {"CRA", "CRA", "0xA32608e873F9DdEF944B24798db69d80Bbb4d1ed", "18"},
        {"CRV.e", "Curve DAO Token", "0x249848beca43ac405b8102ec90dd5f22ca513c06", "18"},
        {"CYCLE", "Cycle Token", "0x81440C939f2C1E34fc7048E518a637205A632a74", "18"},
        {"DAI.e", "Dai Stablecoin", "0xd586E7F844cEa2F87f50152665BCbc2C279D8d70", "18"},
        {"DAI.e", "Dai Stablecoin", "0xd586e7f844cea2f87f50152665bcbc2c279d8d70", "18"},
        {"DOGEHR", "Doge Hashrate", "0x93fb731883787e36Dac7d86D500725e1D1b39F71", "18"},
        {"DYP", "DeFiYieldProtocol", "0x961C8c0B1aaD0c0b10a51FeF6a867E3091BCef17", "18"},
        {"ETHM", "Ethereum Meta", "0x55b1a124c04a54eefdefe5fa2ef5f852fb5f2f26", "18"},
        {"FXS", "Frax Share", "0x214db107654ff987ad859f34125307783fc8e387", "18"},
        {"GDL", "Gondola", "0xD606199557c8Ab6F4Cc70bD03FaCc96ca576f142", "18"},
        {"GRT.e", "Graph Token", "0x8a0cac13c7da965a312f08ea4229c37869e85cb9", "18"},
        {"HCT", "Hurricane Token", "0x45C13620B55C35A5f539d26E88247011Eb10fDbd", "18"},
        {"HOOP", "Hoopoe", "0x0592af5414F2f8d90a5ae3C25E937804D3965C87", "18"},
        {"HUSKY", "Husky", "0x65378b697853568dA9ff8EaB60C13E1Ee9f4a654", "18"},
        {"JADE", "Jade Protocol", "0x80B010450fDAf6a3f8dF033Ee296E92751D603B3", "18"},
        {"JOE", "JoeToken", "0x6e84a6216eA6dACC71eE8E6b0a5B7322EEbC0fDd", "18"},
        {"JOE", "JoeToken", "0x6e84a6216ea6dacc71ee8e6b0a5b7322eebc0fdd", "18"},
        {"KNC", "Kyber Network Crystal v2", "0x39fc9e94caeacb435842fadedecb783589f50f5f", "18"},
        {"LINK", "ChainLink Token", "0xB3fe5374F67D7a22886A0eE082b2E2f9d2651651", "18"},
        {"LINK.e", "Chainlink Token", "0x5947bb275c521040051d82396192181b413227a3", "18"},
        {"LYD", "LydiaFinance Token", "0x4C9B4E1AC6F24CdE3660D5E4Ef1eBF77C710C084", "18"},
        {"MKR.e", "Maker", "0x88128fd4b259552a9a1d457f435a6527aab72d42", "18"},
        {"NAKAMOTO", "Nakamoto", "0x18820540e8b713906468CC3cB68b8a57E35c0e0A", "18"},
        {"OOE", "OpenOcean", "0x0ebd9537a25f56713e34c45b38f421a1e7191469", "18"},
        {"OPUS", "Canopus", "0x76076880e1EBBcE597e6E15c47386cd34de4930F", "18"},
        {"ORBS", "Orbs", "0x340fe1d898eccaad394e2ba0fc1f93d27c7b717a", "18"},
        {"PEFI", "PenguinToken", "0xe896CDeaAC9615145c0cA09C8Cd5C25bced6384c", "18"},
        {"PENDLE", "Pendle", "0xfb98b335551a418cd0737375a2ea0ded62ea213b", "18"},
        {"PNG", "Pangolin", "0x60781C2586D68229fde47564546784ab3fACA982", "18"},
        {"QI", "BENQI", "0x8729438EB15e2C8B576fCc6AeCdA6A148776C0F5", "18"},
        {"QI", "BENQI", "0x8729438eb15e2c8b576fcc6aecda6a148776c0f5", "18"},
        {"RISE", "EverRise", "0xc17c30e98541188614df99239cabd40280810ca3", "18"},
        {"RUGPULL", "RUGPULL", "0x61eCd63e42C27415696e10864d70ecEA4aA11289", "18"},
        {"SHERPA", "Sherpa", "0xa5E59761eBD4436fa4d20E1A27cBa29FB2471Fc6", "18"},
        {"SHIB.e", "SHIBA INU", "0x02d980a0d7af3fb7cf7df8cb35d9edbcf355f665", "18"},
        {"sJADE", "sJADE", "0x3D9eAB723df76808bB84c05b20De27A2e69EF293", "18"},
        {"SNOB", "Snowball", "0xC38f41A296A4493Ff429F1238e030924A1542e50", "18"},
        {"SNX.e", "Synthetix Network Token", "0xbec243c995409e6520d7c41e404da5deba4b209b", "18"},
        {"SPELL", "Spell Token", "0xce1bffbd5374dac86a2893119683f4911a2f7814", "18"},
        {"SPORE", "Spore.Finance", "0x6e7f5C0b9f4432716bDd0a77a3601291b9D9e985", "9"},
        {"STG", "StargateToken", "0x2F6F07CDcf3588944Bf4C42aC74ff24bF56e7590", "18"},
        {"SURE", "inSure", "0x5fc17416925789e0852fbfcd81c490ca4abc51f9", "18"},
        {"SUSHI", "SushiToken", "0x39cf1BD5f15fb22eC3D9Ff86b0727aFc203427cc", "18"},
        {"SUSHI.e", "SushiToken", "0x37B608519F91f70F2EeB0e5Ed9AF4061722e4F76", "18"},
        {"SUSHI.e", "SushiToken", "0x37b608519f91f70f2eeb0e5ed9af4061722e4f76", "18"},
        {"SWAP.e", "TrustSwap Token", "0xc7b5d72c836e718cda8888eaf03707faef675079", "18"},
        {"SYN", "Synapse", "0x1f1e7c893855525b303f99bdf5c3c05be09ca251", "18"},
        {"TUNDRA", "TUNDRAToken", "0x21c5402c3b7d40c89cc472c9df5dd7e51bbab1b1", "18"},
        {"UMA.e", "UMA Voting Token v1", "0x3bd2b1c7ed8d396dbb98ded3aebb41350a5b2339", "18"},
        {"UNCX", "UniCrypt", "0x3b9e3b5c616A1A038fDc190758Bbe9BAB6C7A857", "18"},
        {"UNI.e", "Uniswap", "0x8eBAf22B6F053dFFeaf46f4Dd9eFA95D89ba8580", "18"},
        {"UNI.e", "Uniswap", "0x8ebaf22b6f053dffeaf46f4dd9efa95d89ba8580", "18"},
        {"USD+", "USD+", "0xe80772eaf6e2e18b651f160bc9158b2a5cafca65", "6"},
        {"USDC.e", "USD Coin", "0xA7D7079b0FEaD91F3e65f86E8915Cb59c1a4C664", "6"},
        {"USDC.e", "USD Coin", "0xa7d7079b0fead91f3e65f86e8915cb59c1a4c664", "6"},
        {"USDT.e", "Tether USD", "0xc7198437980c041c805a1edcba50c1ce5db95118", "6"},
        {"VEE", "Vee", "0x3709E8615E02C15B096f8a9B460ccb8cA8194e86", "18"},
        {"VSO", "VersoToken", "0x846D50248BAf8b7ceAA9d9B53BFd12d7D7FBB25a", "18"},
        {"WAVAX", "Wrapped AVAX", "0xb31f66aa3c1e785363f0875a1b74e27b85fd66c7", "18"},
        {"WBTC", "Wrapped BTC", "0x408D4cD0ADb7ceBd1F1A1C33A0Ba2098E1295bAB", "8"},
        {"XAVA", "Avalaunch", "0xd1c3f94DE7e5B45fa4eDBBA472491a9f4B166FC4", "18"},
        {"XETA", "XANA", "0x31c994AC062C1970C086260Bc61babB708643fAc", "18"},
        {"xJOE", "JoeBar", "0x57319d41f71e81f3c65f2a47ca4e001ebafd4f33", "18"},
        {"YAK", "Yak Token", "0x59414b3089ce2AF0010e7523Dea7E2b35d776ec7", "18"},
        {"YFI.e", "yearn.finance", "0x9eaac1b23d935365bd7b542fe22ceee2922f52dc", "18"},
        {"ZRX.e", "ZRX", "0x596fa47043f99a4e0f122243b841e55375cde0d2", "18"},
    };

    // FTM (91 tokens from TP wallet)
    private static final String[][] FTM_POPULAR_TOKENS = {
        {"DAI", "Dai Stablecoin", "0x8d11ec38a3eb5e956b052f67da8bdc9bef8abf3e", "18"},
        {"FRAX", "Frax", "0xdc301622e621166bd8e82f2ca0a26c13ad0be355", "18"},
        {"MIM", "Magic Internet Money", "0x82f0b8b456c1a451378467398982d4834b6829c1", "18"},
        {"TUSD", "TrueUSD", "0x9879abdea01a879644185341f7af7d8343556b7a", "18"},
        {"USDC", "USD Coin", "0x04068da6c83afcfa0e13ba15a6696662335d5b75", "6"},
        {"USDD", "Decentralized USD", "0xcf799767d366d789e8B446981C2D578E241fa25c", "18"},
        {"Wallet Skin - Th", "Wallet Skin - The Rebirth of NFTs", "0x749ca2666D20d659c25952f8FAa249eC5Ae72189", "0"},
        {"wBAN", "Wrapped Banano", "0xe20b9e246db5a0d21bf9209e4858bc9a3ff7a034", "18"},
        {"WIGO", "WigoSwap Token", "0xe992beab6659bff447893641a378fbbf031c5bd6", "18"},
        {"2OMB", "2omb Token", "0x7a6e4e3cc2ac9924605dca4ba31d1831c84b44ae", "18"},
        {"2SHARES", "2SHARE Token", "0xc54a1684fd1bef1f077a336e6be4bd9a3096a6ca", "18"},
        {"3SHARES", "3SHARE Token", "0x6437adac543583c4b31bf0323a0870430f5cc2e7", "18"},
        {"AAVE", "Aave", "0x6a07a792ab2965c72a5b8088d3a069a7ac3a993b", "18"},
        {"AAVE", "Aave", "0x6a07A792ab2965C72a5B8088d3a069A7aC3a993B", "18"},
        {"ALPACA", "AlpacaToken", "0xad996a45fd2373ed0b10efa4a8ecb9de445a4302", "18"},
        {"ALPHA", "AlphaToken", "0x11eb3aa66fe1f2b75cb353d3e874e96968182bda", "18"},
        {"ANY", "Anyswap", "0xddcb3ffd12750b45d32e084887fdf1aabab34239", "18"},
        {"AVAX", "Avalanche", "0x511d35c52a3c244e7b8bd92c0c297755fbd89212", "18"},
        {"AVAX", "Avalanche", "0x511D35c52a3C244E7b8bd92c0C297755FbD89212", "18"},
        {"BABYBOO", "BABYBOO", "0x471762a7017a5b1a3e931f1a97aa03ef1e7f4a73", "18"},
        {"BAND", "Band", "0x46e7628e8b4350b2716ab470ee0ba1fa9e76c6c5", "18"},
        {"BAND", "Band", "0x46E7628E8b4350b2716ab470eE0bA1fa9e76c6C5", "18"},
        {"BEETS", "BeethovenxToken", "0xf24bcf4d1e507740041c9cfd2dddb29585adce1e", "18"},
        {"BIFI", "Beefy.Finance", "0xd6070ae98b8069de6b494332d1a1a81b6179d960", "18"},
        {"BNB", "Binance", "0xd67de0e0a0fd7b15dc8348bb9be742f3c5850454", "18"},
        {"BOBA", "BOBA Token", "0x4389b230D15119c347B9E8BEA6d930A21aaDF6BA", "18"},
        {"BOO", "SpookyToken", "0x841fad6eae12c286d1fd18d1d525dffa75c7effe", "18"},
        {"BSHARE", "BSHARE", "0x49c290ff692149a4e16611c694fded42c954ab7a", "18"},
        {"BTC", "Bitcoin", "0x321162cd933e2be498cd2267a90534a804051b11", "8"},
        {"BTC", "Bitcoin", "0x321162Cd933E2Be498Cd2267a90534A804051b11", "8"},
        {"CEL", "Celsius", "0x2c78f1b70ccf63cdee49f9233e9faa99d43aa07e", "4"},
        {"CREAM", "Cream", "0x657a1861c15a3ded9af0b6799a195a249ebdcbc6", "18"},
        {"CRV", "Curve DAO", "0x1e4f97b9f9f913c46f1632781732927b9019c68b", "18"},
        {"CRV", "Curve DAO", "0x1E4F97b9f9F913c46F1632781732927B9019C68b", "18"},
        {"DOLA", "Dola USD Stablecoin", "0x3129662808bec728a27ab6a6b9afd3cbaca8a43c", "18"},
        {"ETH", "Ethereum", "0x74b23882a30290451a17c44f4f05243b6b58c76d", "18"},
        {"FBAND", "fBAND", "0x078eef5a2fb533e1a4d487ef64b27df113d12c32", "18"},
        {"FBTC", "fBTC", "0xe1146b9ac456fcbb60644c36fd3f868a9072fc6e", "18"},
        {"FETH", "fETH", "0x658b0c7613e890ee50b8c4bc6a3f41ef411208ad", "18"},
        {"FLIBERO", "Fantom Libero Financial Freedom", "0xc3f069d7439baf6d4d6e9478d9cc77778e62d147", "18"},
        {"fmXEN", "XEN Crypto", "0xeF4B763385838FfFc708000f884026B8c0434275", "18"},
        {"frxETH", "Frax Ether", "0x9e73f99ee061c8807f69f9c6ccc44ea3d8c373ee", "18"},
        {"FUSDT", "Frapped USDT", "0x049d68029688eabf473097a2fc38ef61633a3c7a", "6"},
        {"FXS", "Frax Share", "0x7d016eec9c25232b01f23ef992d98ca97fc2af5a", "18"},
        {"GEIST", "Geist.Finance Protocol Token", "0xd8321aa83fb0a4ecd6348d4577431310a6e0814d", "18"},
        {"GEL", "Gelato Network Token", "0x15b7c0c907e4C6b9AdaAaabC300C08991D6CEA05", "18"},
        {"HEC", "Hector", "0x5c4fdfc5233f935f20d2adba572f770c2e377ab0", "9"},
        {"HEC", "Hector", "0x5C4FDfc5233f935f20D2aDbA572F770c2E377Ab0", "9"},
        {"HEGIC", "Hegic", "0x44b26e839eb3572c5e959f994804a5de66600349", "18"},
        {"HOGE", "hoge.finance", "0xf31778d591c558140398f46feca42a6a2dbffe90", "9"},
        {"ICE", "IceToken", "0xf16e81dce15b08f326220742020379b855b87df9", "18"},
        {"KEK", "Cryptokek.com", "0x627524d78b4fc840c887ffec90563c7a42b671fd", "18"},
        {"KP3R", "Keep3r", "0x2a5062d22adcfaafbd5c541d4da82e4b450d4212", "18"},
        {"LIF3", "LIF3", "0xbf60e7414ef09026733c1e7de72e7393888c64da", "18"},
        {"LINK", "ChainLink", "0xb3654dc3d10ea7645f8319668e8f54d2574fbdc8", "18"},
        {"LQDR", "Liquid Driver", "0x10b620b2dbac4faa7d7ffd71da486f5d44cd86f9", "18"},
        {"LUNC", "Luna", "0x95dD59343a893637BE1c3228060EE6afBf6F0730", "6"},
        {"MMY", "MUMMY", "0x01e77288b38b416f972428d562454fb329350bac", "18"},
        {"ORBS", "Orbs", "0x3e01b7e242d5af8064cb9a8f9468ac0f8683617c", "18"},
        {"ORBS", "Orbs", "0x3E01B7E242D5AF8064cB9A8F9468aC0f8683617c", "18"},
        {"ORN", "Orion Protocol", "0xD2cDcB6BdEE6f78DE7988a6A60d13F6eF0b576D9", "8"},
        {"OXD", "0xDAO", "0xc165d941481e68696f43ee6e99bfb2b23e0e3114", "18"},
        {"PAE", "Ripae", "0x8a41f13a4fae75ca88b1ee726ee9d52b148b0498", "18"},
        {"POWER", "Power", "0x131c7afb4e5f5c94a27611f7210dfec2215e85ae", "18"},
        {"RISE", "EverRise", "0xC17c30e98541188614dF99239cABD40280810cA3", "18"},
        {"SCREAM", "Scream", "0xe0654c8e6fd4d733349ac7e09f6f23da256bf475", "18"},
        {"SFTM", "Staked FTM", "0x69c744d3444202d35a2783929a0f930f2fbb05ad", "18"},
        {"SHADE", "ShadeToken", "0x3a3841f5fa9f2c283ea567d5aeea3af022dd2262", "18"},
        {"sHEC", "Staked Hector", "0x75bdef24285013387a47775828bec90b91ca9a5f", "9"},
        {"SNX", "Synthetix Network", "0x56ee926bd8c72b2d5fa1af4d9e4cbb515a1e3adc", "18"},
        {"SNX", "Synthetix Network", "0x56ee926bD8c72B2d5fa1aF4d9E4Cbb515a1E3Adc", "18"},
        {"SOLID", "Solidly", "0x888ef71766ca594ded1f0fa3ae64ed2941740a20", "18"},
        {"SPELL", "Spell Token", "0x468003b688943977e6130f4f68f23aad939a1040", "18"},
        {"SPIRIT", "SpiritSwap Token", "0x5cc61a78f164885776aa610fb0fe1257df78e59b", "18"},
        {"STEAK", "SteakToken", "0x05848b832e872d9edd84ac5718d58f21fd9c9649", "18"},
        {"STG", "StargateToken", "0x2f6f07cdcf3588944bf4c42ac74ff24bf56e7590", "18"},
        {"SUSHI", "Sushi", "0xae75a438b2e0cb8bb01ec1e1e376de11d44477cc", "18"},
        {"SUSHI", "Sushi", "0xae75A438b2E0cB8Bb01Ec1E1e376De11D44477CC", "18"},
        {"SYN", "Synapse", "0xe55e19fb4f2d85af758950957714292dac1e25b2", "18"},
        {"TAROT", "Tarot", "0xc5e2b037d30a390e62180970b3aa4e91868764cd", "18"},
        {"TETU", "TETU Reward Token", "0x65c9d9d080714cda7b5d58989dc27f897f165179", "18"},
        {"TOMB", "TOMB", "0x6c021ae822bea943b2e66552bde1d2696a53fbb7", "18"},
        {"TOR", "TOR", "0x74e23df9110aa9ea0b6ff2faee01e740ca1c642e", "18"},
        {"TREEB", "Treeb", "0xc60d7067dfbc6f2caf30523a064f416a5af52963", "18"},
        {"TSHARE", "TSHARE", "0x4cdf39285d7ca8eb3f090fda0c069ba5f4145b37", "18"},
        {"VED", "veDNA", "0x6C38b023Ffeb63c7b975835e5B2D755E6cAB6eb6", "18"},
        {"WFTM", "Wrapped Fantom", "0x21be370d5312f44cb42ce377bc9b8a0cef1a4c83", "18"},
        {"YEL", "YEL Token", "0xd3b71117e6c1558c1553305b44988cd944e97300", "18"},
        {"YFI", "yearn.finance", "0x29b0da86e484e1c0029b56e817912d778ac0ec69", "18"},
        {"YFI", "yearn.finance", "0x29b0Da86e484E1C0029B56e817912d778aC0EC69", "18"},
        {"ZOO", "ZOO", "0x09e145a1d53c0045f41aeef25d8ff982ae74dd56", "0"},
    };

    // TRX (23 tokens from TP wallet)
    private static final String[][] TRX_POPULAR_TOKENS = {
        {"TUSD", "TrueUSD", "TUpMhErZL2fhh4sVNULAbNKLokS4GjC1F4", "18"},
        {"USDC", "USD Coin", "TEkxiTehnzSmSe2XqrBj4w32RUN966rdz8", "6"},
        {"USDD", "Usdd Stablecoin", "TXDk8mbtRbXeYuMNS83CfKPaYYT8XWv9Hz", "18"},
        {"USDT", "Tether USD", "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "6"},
        {"WBTT", "Wrapped BitTorrent", "TKfjV9RNKJJCqPvBtK8L7Knykh7DNWvnYt", "6"},
        {"WIN", "WINK", "TLa2f6VPqDgRE67v1736s7bJ8Ray5wYjU7", "6"},
        {"BTC", "Bitcoin (BTC)", "TN3W4H6rK2ce4vX9YnFQHwKENnHjoxb3m9", "8"},
        {"BTT", "BitTorrent", "TAFjULxiVgT4qWk6UZwjqwZXTSaGaqnVp4", "18"},
        {"ETH", "Ethereum", "THb4CqiFdwNHsWsQCs4JhzwjMWys4aqCbF", "18"},
        {"FIST", "FistToken", "TL6K6iaEkn8kdnJ79a8Be3S4RFf4pFkGE8", "6"},
        {"JST", "JUST GOV (JST)", "TCFLL5dx5ZJdKnWuesXxi1VPwjLVmWZZy9", "18"},
        {"META", "META Token", "TY5WnpU6bLxKrrAaa7JSQht1sPdgU4hkyk", "18"},
        {"Milk", "Milk", "TAzopbXcjczXr12k8uaXH1Adg9gpFKuUE3", "6"},
        {"NFT", "APENFT", "TFczxzPhnThNSqr5by8tvxsdCFRRz6cPNq", "6"},
        {"OSK", "OSK", "TDk91SWz2GvwfZwMTGX21d4ngUUH8YZZAv", "18"},
        {"POSCHE", "POSCHE Token", "TYofwf9oM6CPU1rCTwNnEuwzhB5DTQu8rW", "6"},
        {"RON", "Revolution", "TWaaMa462AUWGnKizYM17RuXKPv6Ej1xtE", "6"},
        {"SPB", "Spirit Bead", "TLcs9Y9ydPznz5UvAY8GeQdeL3mbuJtQ6j", "6"},
        {"sTRX", "Staked TRX", "TU3kjFuhtEo42tsCBtfYUAZxoqQ4yuSLQ5", "18"},
        {"SUN", "SUN TOKEN", "TSSMHYeV2uE9qYH95DqyoCuNCzEL1NvU3S", "18"},
        {"USD1", "World Liberty Financial USD", "TPFqcBAaaUMCSVRCqPaQ9QnzKhmuoLR6Rc", "18"},
        {"USDDOLD", "Decentralized USD", "TPYmHEhy5n8TCEfYGqW2rPxsghSfzghPDn", "18"},
        {"USDJ", "USDJ", "TMwFHYXLJaRUPeW6421aqXL4ZEzPRFGkGT", "18"},
    };

    // ONE (32 tokens from TP wallet)
    private static final String[][] ONE_POPULAR_TOKENS = {
        {"WONE", "Wrapped ONE", "0xcf664087a5bb0237a0bad6742852ec6c8d69a27a", "18"},
        {"wsWAGMI", "Wrapped sWAGMI", "0xBb948620Fa9cD554eF9A331B13eDeA9B181F9D45", "18"},
        {"0Gambling.io", "0Gambling.io", "0x556e28cd92660abbfdb7bd6e11df9f435fcf8d3c", "8"},
        {"1AXS", "Axie Infinity Shard", "0x14a7b318fed66ffdcc80c1517c172c13852865de", "18"},
        {"1ETH", "ETH", "0x6983d1e6def3690c4d616b13597a09e6193ea013", "18"},
        {"1USDC", "USD Coin", "0x985458e523db3d53125813ed68c274899e9dfab4", "6"},
        {"ABR", "Allbridge", "0xf80eD129002B0eE58C6d2E63D0D7Dc9Fc9f3383C", "18"},
        {"BLANTIK", "Blantik", "0x4e875c4daeA507b6E22fe1b180Ca41816293A921", "18"},
        {"bscSYN", "Synapse", "0xf2ec007a5af19603df418806ce502b80dd115fbb", "18"},
        {"CZR", "Czarcoin", "0xdeceea6cc94a06b6dbe68aac1cb08d6facb4d598", "18"},
        {"DAIKI", "Daikiri Token", "0xf315803ba9da293765ab163e7db98e8d6df6d361", "18"},
        {"DFKBLOATER", "Bloater", "0x78aed65a2cc40c7d8b0df1554da60b38ad351432", "0"},
        {"DFKBLUEEGG", "Blue Egg", "0x9678518e04Fe02FB30b55e2D0e554E26306d0892", "0"},
        {"DFKGOLD", "Gold", "0x3a4edcf3312f44ef027acfd8c21382a5259936e7", "3"},
        {"DFKLWITCR", "Lesser Wit Crystal", "0x17ff2016c9eccfbf4fc4da6ef95fe646d2c9104f", "0"},
        {"DFKTEARS", "Gaia's Tears", "0x24ea0d436d3c2602fbfefbe6a16bbc304c963d04", "0"},
        {"DUST", "DUST", "0xbd58c54657cd753eee336f7ed7cb57567704cd48", "18"},
        {"HSNAKE", "HarmonySnake", "0xc2852a5f9439b5494bbe5f023d5bf02ddbc8a040", "18"},
        {"HYPERMATIC", "HYPERMATIC", "0x995ad353a351ff3679a9baa56cded78970205c03", "18"},
        {"JEWEL", "Jewels", "0x72cb10c6bfa5624dd07ef608027e366bd690048f", "18"},
        {"MET", "Metatr.one", "0x725553bc9aa0939362671407dfdeb162dd37d168", "18"},
        {"MUTAN", "MutanNFT", "0xF672D572d3f4CBa1b5ff66A173CBB21c84074C28", "0"},
        {"nUSD", "nUSD", "0xED2a7edd7413021d440b09D654f3b87712abAB66", "18"},
        {"oneminer.finance", "oneminer.finance", "0xa32fad9962499bee76b5b96daa3f6d2ce648aada", "18"},
        {"PENNY", "PENNY", "0x1d5d2ee701c31b1543c5cc6c0aa373e3d42e4bce", "18"},
        {"ROY", "Royale", "0xfe1b516a7297eb03229a8b5afad80703911e81cb", "18"},
        {"synFRAX", "Synapse FRAX", "0x1852F70512298d56e9c8FDd905e02581E04ddb2a", "18"},
        {"TET", "Tetcoin", "0x59fbce10f6fc5fc9016ae294557a235661765c41", "18"},
        {"USBL", "softbalanced.com", "0xe211dfd985633da0c5d6a82e469c726a59d45d1b", "18"},
        {"VIPER", "Viper", "0xea589e93ff18b1a1f1e9bac7ef3e86ab62addc79", "18"},
        {"xJEWEL", "xJewels", "0xa9ce83507d872c5e1273e745abcfda849daa654f", "18"},
        {"XLT", "Litetokens", "0xa1c0423a3dbfbcf0b9567c58fa76784d88973342", "18"},
    };

    /**
     * 第2层：从 PancakeSwap 远程获取 BSC 代币列表
     * URL: https://tokens.pancakeswap.finance/cmc.json（免费，无需注册）
     * 约 9921 个 BSC 代币，缓存到 SharedPreferences 24 小时过期
     *
     * @param chainId 链ID，BSC = "56"
     * @return 代币列表，格式: {address, symbol, name, decimals}
     */
    private static java.util.List<String[]> fetchPancakeSwapTokens(Context ctx, String chainId) throws Exception {
        java.util.List<String[]> result = new java.util.ArrayList<>();
        String cacheKey = "pancakeswap_tokens_" + chainId;

        // 1. 检查本地缓存
        if (ctx != null) {
            try {
                android.content.SharedPreferences prefs = ctx.getSharedPreferences("token_list_cache", android.content.Context.MODE_PRIVATE);
                long cachedAt = prefs.getLong(cacheKey + "_time", 0);
                long now = System.currentTimeMillis();
                // 缓存 24 小时有效
                if (cachedAt > 0 && now - cachedAt < 24 * 60 * 60 * 1000) {
                    String cached = prefs.getString(cacheKey, "");
                    if (!cached.isEmpty()) {
                        JSONArray arr = new JSONArray(cached);
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject t = arr.getJSONObject(i);
                            result.add(new String[]{
                                t.optString("address", ""),
                                t.optString("symbol", ""),
                                t.optString("name", ""),
                                t.optString("decimals", "18")
                            });
                        }
                        Logger.info(ctx, "代币发现", "PancakeSwap 使用本地缓存（" +
                            (now - cachedAt) / 1000 + "秒前）" + result.size() + " 个代币");
                        return result;
                    }
                }
            } catch (Exception e) {
                Logger.warning(ctx, "代币发现", "PancakeSwap 缓存读取失败: " + e.getMessage());
            }
        }

        // 2. 网络请求（使用 batchClient，超时 20 秒，因为 JSON 约 2.5MB）
        String url = "https://tokens.pancakeswap.finance/cmc.json";
        Logger.network(ctx, "代币发现", "PancakeSwap 远程列表请求中...");
        Request request = new Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build();

        try (Response response = batchClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new Exception("HTTP " + response.code());
            }
            String resp = response.body().string();
            JSONObject json = new JSONObject(resp);

            // 解析 tokens 数组，过滤指定 chainId
            JSONArray tokensArr = json.optJSONArray("tokens");
            if (tokensArr == null) {
                throw new Exception("JSON 中无 tokens 数组");
            }

            JSONArray cacheArr = new JSONArray();
            for (int i = 0; i < tokensArr.length(); i++) {
                try {
                    JSONObject t = tokensArr.getJSONObject(i);
                    // 只保留 chainId 匹配的代币（BSC = 56）
                    String tokenChainId = t.optString("chainId", "");
                    if (!chainId.equals(tokenChainId)) continue;

                    String address = t.optString("address", "");
                    String symbol = t.optString("symbol", "");
                    String name = t.optString("name", "");
                    String decimals = String.valueOf(t.optInt("decimals", 18));

                    if (address.isEmpty() || symbol.isEmpty()) continue;

                    result.add(new String[]{address, symbol, name, decimals});

                    // 准备缓存数据
                    JSONObject cacheItem = new JSONObject();
                    cacheItem.put("address", address);
                    cacheItem.put("symbol", symbol);
                    cacheItem.put("name", name);
                    cacheItem.put("decimals", decimals);
                    cacheArr.put(cacheItem);
                } catch (Exception ignore) { /* 跳过单个代币解析错误 */ }
            }

            // 3. 缓存到 SharedPreferences
            if (ctx != null && !result.isEmpty()) {
                try {
                    ctx.getSharedPreferences("token_list_cache", android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putString(cacheKey, cacheArr.toString())
                        .putLong(cacheKey + "_time", System.currentTimeMillis())
                        .apply();
                } catch (Exception e) {
                    Logger.warning(ctx, "代币发现", "PancakeSwap 缓存写入失败: " + e.getMessage());
                }
            }

            Logger.success(ctx, "代币发现", "PancakeSwap 远程获取 chainId=" + chainId +
                " 共 " + result.size() + " 个代币");
        }

        return result;
    }

    // ============================================================
    // Layer 0: 自动发现 API（优先级最高，一次请求获取用户所有代币）
    // ============================================================

    /**
     * DeBank API — IP 直连绕过 GFW DNS 封锁
     * openapi.debank.com 被中国 GFW DNS 污染，用域名 URL + 自定义 DNS 解析到 AWS CloudFront IP
     * DeBank 是中国公司（上海），API 本身不被封锁，只是 DNS 被污染
     */
    private static java.util.List<String[]> fetchDeBankTokensWithIPBypass(Context ctx, String chain, String address) {
        java.util.List<String[]> result = new java.util.ArrayList<>();
        String debankChainId = getDeBankChainId(chain);
        if (debankChainId == null) return result;

        String url = "https://openapi.debank.com/v1/user/token_list?id=" + address + "&chain_id=" + debankChainId + "&is_all=false";
        Logger.network(ctx, "代币发现", "DeBank API 请求中（IP 直连绕 DNS）... chain=" + debankChainId);

        // DeBank openapi 用 AWS CloudFront，已知 IP（从 debank.com 解析得到）
        String[] cloudfrontIPs = {"35.79.90.34", "52.193.49.251", "52.69.226.201"};

        for (String ip : cloudfrontIPs) {
            try {
                OkHttpClient ipClient = createBypassClient(ip);
                Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "application/json")
                    .get()
                    .build();

                try (Response response = ipClient.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        Logger.warning(ctx, "代币发现", "DeBank IP直连 " + ip + " HTTP " + response.code());
                        continue;
                    }
                    String resp = response.body().string();
                    JSONArray tokensArr = new JSONArray(resp);
                    int count = 0;
                    for (int i = 0; i < tokensArr.length(); i++) {
                        try {
                            JSONObject t = tokensArr.getJSONObject(i);
                            String contract = t.optString("id", "");
                            String symbol = t.optString("symbol", "");
                            String name = t.optString("name", "");
                            int decimals = t.optInt("decimals", 18);
                            double amount = t.optDouble("amount", 0);
                            if (contract.isEmpty() || symbol.isEmpty() || amount <= 0) continue;
                            if (contract.equals(debankChainId)) continue;
                            result.add(new String[]{contract, symbol, name, String.valueOf(decimals)});
                            count++;
                        } catch (Exception ignore) {}
                    }
                    Logger.success(ctx, "代币发现", "DeBank IP直连 " + ip + " 获取 " + debankChainId + " 共 " + count + " 个有余额代币");
                    return result;
                }
            } catch (Exception e) {
                Logger.warning(ctx, "代币发现", "DeBank IP直连 " + ip + " 失败: " + e.getMessage());
            }
        }
        Logger.warning(ctx, "代币发现", "DeBank 所有 IP 直连均失败");
        return result;
    }

    private static String getDeBankChainId(String chain) {
        switch (chain) {
            case "ETH": return "eth";
            case "BNB": return "bsc";
            case "MATIC": return "matic";
            case "AVAX": return "avax";
            case "FTM": return "ftm";
            case "GLMR": return "glmr";
            case "CELO": return "celo";
            case "ONE": return "one";
            case "KAVA": return "kava";
            case "SOL": return "sol";
            case "TRX": return "tron";
            case "ATOM": return "cosmos";
            case "DOT": return "polkadot";
            case "SUI": return "sui";
            case "APT": return "aptos";
            case "ADA": return "cardano";
            case "NEAR": return "near";
            case "ALGO": return "algo";
            case "ICP": return "icp";
            case "XTZ": return "tezos";
            default: return null;
        }
    }

    /**
     * 第2层扩展：从 1inch 获取多链代币列表（免费，无需注册）
     * URL: https://tokens.1inch.eth.link
     * 支持: ETH(1), MATIC(137), AVAX(43114), FTM(250) 等
     * 约 2570 个代币，用 chainId 过滤目标链
     * 缓存到 SharedPreferences 24 小时过期
     */
    private static java.util.List<String[]> fetch1inchTokens(Context ctx, String chainId) throws Exception {
        java.util.List<String[]> result = new java.util.ArrayList<>();
        String cacheKey = "1inch_tokens_" + chainId;
        
        if (ctx != null) {
            try {
                android.content.SharedPreferences prefs = ctx.getSharedPreferences("token_list_cache", android.content.Context.MODE_PRIVATE);
                long cachedAt = prefs.getLong(cacheKey + "_time", 0);
                long now = System.currentTimeMillis();
                if (cachedAt > 0 && now - cachedAt < 24 * 60 * 60 * 1000) {
                    String cached = prefs.getString(cacheKey, "");
                    if (cached != null && !cached.isEmpty()) {
                        JSONArray arr = new JSONArray(cached);
                        for (int i = 0; i < arr.length(); i++) {
                            JSONArray token = arr.getJSONArray(i);
                            result.add(new String[]{token.getString(0), token.getString(1), token.getString(2), token.getString(3)});
                        }
                        if (!result.isEmpty()) return result;
                    }
                }
            } catch (Exception ignore) {}
        }
        
        Request req = new Request.Builder()
            .url("https://tokens.1inch.eth.link")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .get().build();
        
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) throw new Exception("HTTP " + resp.code());
            String body = resp.body().string();
            JSONObject json = new JSONObject(body);
            JSONArray tokens = json.getJSONArray("tokens");
            JSONArray cacheArr = new JSONArray();
            for (int i = 0; i < tokens.length(); i++) {
                JSONObject t = tokens.getJSONObject(i);
                String cid = t.optString("chainId", "");
                if (!cid.equals(chainId)) continue;
                String addr = t.optString("address", "");
                String sym = t.optString("symbol", "");
                String name = t.optString("name", "");
                String dec = t.optString("decimals", "18");
                if (addr.isEmpty() || sym.isEmpty()) continue;
                result.add(new String[]{addr, sym, name, dec});
                cacheArr.put(new JSONArray().put(addr).put(sym).put(name).put(dec));
            }
            if (ctx != null && !result.isEmpty()) {
                try {
                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("token_list_cache", android.content.Context.MODE_PRIVATE);
                    prefs.edit().putString(cacheKey, cacheArr.toString()).putLong(cacheKey + "_time", System.currentTimeMillis()).apply();
                } catch (Exception ignore) {}
            }
        }
        return result;
    }

    /**
     * 第2层扩展：从 CoinGecko 获取以太坊代币列表
     * URL: https://tokens.coingecko.com/ethereum/all.json
     * 约 4850 个以太坊代币，缓存 24 小时
     */
    private static java.util.List<String[]> fetchCoinGeckoTokens(Context ctx) throws Exception {
        java.util.List<String[]> result = new java.util.ArrayList<>();
        String cacheKey = "coingecko_eth_tokens";
        
        if (ctx != null) {
            try {
                android.content.SharedPreferences prefs = ctx.getSharedPreferences("token_list_cache", android.content.Context.MODE_PRIVATE);
                long cachedAt = prefs.getLong(cacheKey + "_time", 0);
                long now = System.currentTimeMillis();
                if (cachedAt > 0 && now - cachedAt < 24 * 60 * 60 * 1000) {
                    String cached = prefs.getString(cacheKey, "");
                    if (cached != null && !cached.isEmpty()) {
                        JSONArray arr = new JSONArray(cached);
                        for (int i = 0; i < arr.length(); i++) {
                            JSONArray token = arr.getJSONArray(i);
                            result.add(new String[]{token.getString(0), token.getString(1), token.getString(2), token.getString(3)});
                        }
                        if (!result.isEmpty()) return result;
                    }
                }
            } catch (Exception ignore) {}
        }
        
        Request req = new Request.Builder()
            .url("https://tokens.coingecko.com/ethereum/all.json")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .get().build();
        
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) throw new Exception("HTTP " + resp.code());
            String body = resp.body().string();
            JSONObject json = new JSONObject(body);
            JSONArray tokens = json.getJSONArray("tokens");
            JSONArray cacheArr = new JSONArray();
            for (int i = 0; i < tokens.length(); i++) {
                JSONObject t = tokens.getJSONObject(i);
                String addr = t.optString("address", "");
                String sym = t.optString("symbol", "");
                String name = t.optString("name", "");
                String dec = t.optString("decimals", "18");
                if (addr.isEmpty() || sym.isEmpty()) continue;
                result.add(new String[]{addr, sym, name, dec});
                cacheArr.put(new JSONArray().put(addr).put(sym).put(name).put(dec));
            }
            if (ctx != null && !result.isEmpty()) {
                try {
                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("token_list_cache", android.content.Context.MODE_PRIVATE);
                    prefs.edit().putString(cacheKey, cacheArr.toString()).putLong(cacheKey + "_time", System.currentTimeMillis()).apply();
                } catch (Exception ignore) {}
            }
        }
        return result;
    }

    /**
     * 第2层扩展：从 GitHub 获取 Solana SPL 代币列表
     * URL: https://raw.githubusercontent.com/solana-labs/token-list/main/src/tokens/solana.tokenlist.json
     * 约 13644 个代币，缓存 24 小时。只取 chainId=101（主网）的代币
     */
    private static java.util.List<String[]> fetchSolanaTokens(Context ctx) throws Exception {
        java.util.List<String[]> result = new java.util.ArrayList<>();
        String cacheKey = "solana_tokens";
        
        if (ctx != null) {
            try {
                android.content.SharedPreferences prefs = ctx.getSharedPreferences("token_list_cache", android.content.Context.MODE_PRIVATE);
                long cachedAt = prefs.getLong(cacheKey + "_time", 0);
                long now = System.currentTimeMillis();
                if (cachedAt > 0 && now - cachedAt < 24 * 60 * 60 * 1000) {
                    String cached = prefs.getString(cacheKey, "");
                    if (cached != null && !cached.isEmpty()) {
                        JSONArray arr = new JSONArray(cached);
                        for (int i = 0; i < arr.length(); i++) {
                            JSONArray token = arr.getJSONArray(i);
                            result.add(new String[]{token.getString(0), token.getString(1), token.getString(2), token.getString(3)});
                        }
                        if (!result.isEmpty()) return result;
                    }
                }
            } catch (Exception ignore) {}
        }
        
        Request req = new Request.Builder()
            .url("https://raw.githubusercontent.com/solana-labs/token-list/main/src/tokens/solana.tokenlist.json")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .get().build();
        
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) throw new Exception("HTTP " + resp.code());
            String body = resp.body().string();
            JSONObject json = new JSONObject(body);
            JSONArray tokens = json.getJSONArray("tokens");
            JSONArray cacheArr = new JSONArray();
            for (int i = 0; i < tokens.length(); i++) {
                JSONObject t = tokens.getJSONObject(i);
                int cid = t.optInt("chainId", 0);
                if (cid != 101) continue; // Solana mainnet only
                String addr = t.optString("address", "");
                String sym = t.optString("symbol", "");
                String name = t.optString("name", "");
                int dec = t.optInt("decimals", 0);
                if (addr.isEmpty() || sym.isEmpty()) continue;
                result.add(new String[]{addr, sym, name, String.valueOf(dec)});
                cacheArr.put(new JSONArray().put(addr).put(sym).put(name).put(String.valueOf(dec)));
            }
            if (ctx != null && !result.isEmpty()) {
                try {
                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("token_list_cache", android.content.Context.MODE_PRIVATE);
                    prefs.edit().putString(cacheKey, cacheArr.toString()).putLong(cacheKey + "_time", System.currentTimeMillis()).apply();
                } catch (Exception ignore) {}
            }
        }
        return result;
    }

    /**
     * 第2层扩展：从 TronGrid API 获取 TRON TRC20 代币列表
     * URL: https://apilist.tronscanapi.com/api/token?sort=volume24hr&limit=200
     * 免费，无需注册。返回格式和标准 Token List 不同
     */
    private static java.util.List<String[]> fetchTronGridTokens(Context ctx) throws Exception {
        java.util.List<String[]> result = new java.util.ArrayList<>();
        String cacheKey = "trongrid_tokens";
        
        if (ctx != null) {
            try {
                android.content.SharedPreferences prefs = ctx.getSharedPreferences("token_list_cache", android.content.Context.MODE_PRIVATE);
                long cachedAt = prefs.getLong(cacheKey + "_time", 0);
                long now = System.currentTimeMillis();
                if (cachedAt > 0 && now - cachedAt < 24 * 60 * 60 * 1000) {
                    String cached = prefs.getString(cacheKey, "");
                    if (cached != null && !cached.isEmpty()) {
                        JSONArray arr = new JSONArray(cached);
                        for (int i = 0; i < arr.length(); i++) {
                            JSONArray token = arr.getJSONArray(i);
                            result.add(new String[]{token.getString(0), token.getString(1), token.getString(2), token.getString(3)});
                        }
                        if (!result.isEmpty()) return result;
                    }
                }
            } catch (Exception ignore) {}
        }
        
        Request req = new Request.Builder()
            .url("https://apilist.tronscanapi.com/api/token?sort=volume24hr&limit=200")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .get().build();
        
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) throw new Exception("HTTP " + resp.code());
            String body = resp.body().string();
            JSONObject json = new JSONObject(body);
            JSONArray tokens = json.getJSONArray("data");
            JSONArray cacheArr = new JSONArray();
            for (int i = 0; i < tokens.length(); i++) {
                JSONObject t = tokens.getJSONObject(i);
                String addr = t.optString("tokenID", "");
                String sym = t.optString("abbr", "");
                String name = t.optString("name", "");
                int dec = t.optInt("precision", 6);
                if (addr.isEmpty() || sym.isEmpty()) continue;
                result.add(new String[]{addr, sym, name, String.valueOf(dec)});
                cacheArr.put(new JSONArray().put(addr).put(sym).put(name).put(String.valueOf(dec)));
            }
            if (ctx != null && !result.isEmpty()) {
                try {
                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("token_list_cache", android.content.Context.MODE_PRIVATE);
                    prefs.edit().putString(cacheKey, cacheArr.toString()).putLong(cacheKey + "_time", System.currentTimeMillis()).apply();
                } catch (Exception ignore) {}
            }
        }
        return result;
    }


    /**
     * 拉取该地址在某链上的全部代币余额（完整版）。
     * 数据源策略（三层代币发现 + 用户自定义，靠 seenContracts 去重合并）：
     *   第0层：DeBank API（已失效，快速失败）
     *   第1层：内置热门代币列表（BSC 500个 + 其他链预置） + RPC balanceOf 查询
     *   第2层：1inch/CoinGecko/Solana/TronGrid 远程代币列表
     *   第3层：IP直连区块浏览器网页抓取（兜底）
     *   补充：用户手动添加的代币（getCustomTokens）
     * Returns: symbol, name, balance, value, contract_address, logo_url, is_verified
     */
    public static java.util.List<String[]> getAllTokenBalances(Context ctx, String chain, String address) throws Exception {
        return getAllTokenBalances(ctx, chain, address, true);
    }

    /**
     * 拉取该地址在某链上的代币余额（可控是否做远程代币发现）。
     * lightMode=true 时只做：自定义代币 + 持久化代币 + 内置热门代币，不做 1inch/CoinGecko/区块浏览器扫描。
     * 用于总资产估算，避免耗时。
     * Returns: symbol, name, balance, value, contract_address, logo_url, is_verified
     */
    public static java.util.List<String[]> getAllTokenBalances(Context ctx, String chain, String address, boolean fullDiscovery) throws Exception {
        // 默认不跳过 Transfer 扫描（保持原行为）
        return getAllTokenBalances(ctx, chain, address, fullDiscovery, false);
    }

    /**
     * 拉取该地址在某链上的代币余额（可控是否做远程代币发现 & 是否跳过 Transfer 扫描）。
     * skipTransferScan=true 时跳过慢速的全量 Transfer 事件扫描，用于首屏秒开；
     * 新代币可在随后台扫描中发现。
     * Returns: symbol, name, balance, value, contract_address, logo_url, is_verified
     */
    public static java.util.List<String[]> getAllTokenBalances(Context ctx, String chain, String address,
                                                                boolean fullDiscovery, boolean skipTransferScan) throws Exception {
        java.util.List<String[]> tokens = new java.util.ArrayList<>();
        Set<String> seenContracts = new HashSet<>();
        Map<String, Double> prices = getPrices(ctx);
        // 自定义/测试链上的代币不套用真实价格（避免测试网 USDT 命中主网 USDT 价格）
        if (isCustomChain(ctx, chain)) {
            prices = new java.util.HashMap<>();
        }

        // 1. 轻量代币发现：内置热门代币（秒出）
        if (fullDiscovery) {
            // 完整发现：内置热门 + 远程列表 + 区块浏览器兜底
            fetchCommonTokenBalances(ctx, chain, address, tokens, seenContracts, prices, true);
        } else {
            // 轻量发现：只做内置热门代币，不做远程列表/区块浏览器
            fetchCommonTokenBalances(ctx, chain, address, tokens, seenContracts, prices, false);
        }

        // 2. 叠加用户手动添加的代币（去重）
        String[][] customs = WalletManager.getCustomTokens(ctx, chain);
        for (String[] t : customs) {
            if (t.length < 4) continue;
            String symbol = t[0];
            String name = t[1];
            String contract = t[2].toLowerCase();
            int decimals;
            try { decimals = Integer.parseInt(t[3]); } catch (Exception e) { continue; }
            if (seenContracts.contains(contract)) continue;
            try {
                double balance = getERC20Balance(ctx, chain, address, contract, decimals);
                if (balance <= 0) continue;
                double value = balance * prices.getOrDefault(symbol, 0.0);
                tokens.add(new String[]{
                    symbol, name,
                    formatAmount(balance),
                    formatValue(ctx, value),
                    contract,
                    "",
                    "true"
                });
            } catch (Exception ignore) {}
        }

        // 2.5. 加载持久化的已发现代币（上次 Transfer 扫描发现的非热门代币，如 GOUT）
        // 如果这些代币还有余额，直接加入列表，跳过本次 Transfer 扫描
        boolean needTransferScan = true;
        try {
            DataCache cache = new DataCache(ctx);
            java.util.List<String[]> discovered = cache.getDiscoveredTokens();
            if (!discovered.isEmpty()) {
                int foundCount = 0;
                java.util.List<String[]> updatedDiscovered = new java.util.ArrayList<>(); // 保存修正后的 symbol
                String rpcUrl0 = WalletManager.getRpcUrl(ctx, chain);
                Logger.info(ctx, "代币发现", "持久化代币 RPC URL: " + (rpcUrl0 != null ? rpcUrl0 : "null"));
                for (String[] dt : discovered) {
                    String symbol = dt[0];
                    String contract = dt[1].toLowerCase();
                    String decimalsStr = dt[2];
                    if (seenContracts.contains(contract)) continue;
                    try {
                        int decimals = Integer.parseInt(decimalsStr);
                        // 如果 symbol 是 UNKNOWN，重新查询一次（首次启动可能因超时失败）
                        if ("UNKNOWN".equals(symbol)) {
                            if (rpcUrl0 == null || rpcUrl0.isEmpty()) {
                                Logger.warning(ctx, "代币发现", "RPC URL 为空，跳过 symbol 查询: " + contract);
                            } else {
                                try {
                                    Logger.info(ctx, "代币发现", "查询 symbol: " + contract + " via " + rpcUrl0);
                                    String realSymbol = callContractMethod(rpcUrl0, contract, "0x95d89b41");
                                    Logger.info(ctx, "代币发现", "symbol 查询结果: " + contract + " -> " + (realSymbol != null ? realSymbol : "null"));
                                    if (realSymbol != null && !realSymbol.isEmpty()) {
                                        symbol = realSymbol;
                                        Logger.info(ctx, "代币发现", "修正持久化代币 symbol: " + contract + " -> " + symbol);
                                    }
                                } catch (Exception e) {
                                    Logger.error(ctx, "代币发现", "symbol 查询异常: " + contract + " error=" + e.getClass().getSimpleName() + ": " + e.getMessage());
                                }
                            }
                        }
                        double balance = getERC20Balance(ctx, chain, address, contract, decimals);
                        seenContracts.add(contract);
                        if (balance > 0) {
                            double value = balance * prices.getOrDefault(symbol, 0.0);
                            tokens.add(new String[]{
                                symbol, symbol,
                                formatAmount(balance),
                                formatValue(ctx, value),
                                contract,
                                "",
                                "true"
                            });
                            foundCount++;
                        }
                        updatedDiscovered.add(new String[]{symbol, contract, decimalsStr});
                    } catch (Exception ignore) {}
                }
                // 如果有 symbol 修正，更新持久化数据
                if (!updatedDiscovered.isEmpty()) {
                    cache.saveDiscoveredTokens(updatedDiscovered);
                }
                // 持久化的代币全部找到（还有余额），跳过慢速的 Transfer 扫描
                if (foundCount >= discovered.size()) {
                    needTransferScan = false;
                    Logger.info(ctx, "代币发现", "持久化代币全部找到，跳过 Transfer 扫描（秒开）");
                }
            }
        } catch (Exception e) {
            Logger.warning(ctx, "代币发现", "加载持久化代币失败: " + e.getMessage());
        }

        // 3. 全量扫描 Transfer 事件（仅当持久化代币未全部找到时，首次启动或新代币转入）
        // skipTransferScan=true（首屏秒开）时跳过，新代币由随后台扫描发现
        if (needTransferScan && !skipTransferScan) {
            discoverTokensFromTransferLogs(ctx, chain, address, tokens, seenContracts, prices);
        }

        Logger.info(ctx, "代币发现", "最终汇总：" + Logger.getChainChineseName(chain) + " 共 " + tokens.size() + " 个代币");
        return tokens;
    }

    /**
     * 通过全量扫描钱包的 Transfer 事件发现未知代币
     * 解决热门列表和区块浏览器都漏掉的新代币问题（如 GOUT 等小众代币）
     * 只查 to=wallet 方向（转入），提取合约地址后查询余额
     */
    private static void discoverTokensFromTransferLogs(Context ctx, String chain, String address,
                                                          java.util.List<String[]> tokens, Set<String> seenContracts,
                                                          Map<String, Double> prices) {
        if (!isEVMChain(chain)) return;

        // 低频节流：30秒内不重复全量扫描（首次启动仍会扫描，之后下拉刷新秒开）
        try {
            android.content.SharedPreferences prefs = ctx.getSharedPreferences("token_scan_prefs", android.content.Context.MODE_PRIVATE);
            long lastScan = prefs.getLong("last_scan_" + chain + "_" + address, 0);
            long now = System.currentTimeMillis();
            if (now - lastScan < 30 * 1000L) {
                Logger.info(ctx, "代币发现", "距上次全量扫描不足30秒，跳过（秒开）");
                return;
            }
            prefs.edit().putLong("last_scan_" + chain + "_" + address, now).apply();
        } catch (Exception e) {}

        String transferTopic = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
        String walletPadded = "0x000000000000000000000000" + address.toLowerCase().replace("0x", "");

        // 构造候选节点列表：优先非AVE节点（AVE不支持eth_getLogs）
        java.util.List<String> candidateUrls = new java.util.ArrayList<>();
        String savedRpc = WalletManager.getRpcUrl(ctx, chain);
        for (NodeManager.NodeEntry node : NodeManager.getPresets(chain)) {
            if (node.url != null && !node.url.contains("sendFastSwapTx") && !candidateUrls.contains(node.url)) candidateUrls.add(node.url);
        }
        for (NodeManager.NodeEntry node : NodeManager.getPresets(chain)) {
            if (node.url != null && node.url.contains("sendFastSwapTx") && !candidateUrls.contains(node.url)) candidateUrls.add(node.url);
        }
        if (savedRpc != null && !savedRpc.isEmpty() && !savedRpc.contains("sendFastSwapTx")) {
            candidateUrls.remove(savedRpc);
            candidateUrls.add(0, savedRpc);
        }

        // 查询最新区块高度
        String currentRpc = null;
        long latestBlock = 0;
        for (String rpcUrl : candidateUrls) {
            try {
                JSONObject body = new JSONObject();
                body.put("jsonrpc", "2.0");
                body.put("id", 1);
                body.put("method", "eth_blockNumber");
                body.put("params", new JSONArray());
                Request request = new Request.Builder()
                    .url(rpcUrl)
                    .post(RequestBody.create(body.toString(), JSON_TYPE))
                    .build();
                try (Response response = client.newCall(request).execute()) {
                    String resp = response.body() != null ? response.body().string() : "";
                    JSONObject json = new JSONObject(resp);
                    if (json.has("result")) {
                        latestBlock = new java.math.BigInteger(json.getString("result").substring(2), 16).longValue();
                        currentRpc = rpcUrl;
                        break;
                    }
                }
            } catch (Exception e) {}
        }
        if (currentRpc == null || latestBlock == 0) return;

        // 只查最近 100,000 区块（约 3-4 天），从最新区块往前找，找到新合约就停止；
        // 避免首次空钱包因全量扫描数百个分块而长时间阻塞资产列表渲染
        long fromBlock = Math.max(0, latestBlock - 100000);
        int chunkSize = 5000;
        int consecutiveEmpty = 0;
        int newTokenCount = 0;
        long scanStart = System.currentTimeMillis();
        int maxScanSeconds = 8;

        Logger.info(ctx, "代币发现", "开始全量扫描 Transfer 事件发现新代币，范围 " + fromBlock + " - " + latestBlock);

        for (long end = latestBlock; end > fromBlock; end -= chunkSize) {
            long start = Math.max(fromBlock, end - chunkSize + 1);
            // 时间预算：防止在无交易的钱包上长时间扫描，阻塞资产列表加载
            if (System.currentTimeMillis() - scanStart > maxScanSeconds * 1000L) {
                Logger.info(ctx, "代币发现", "扫描超过 " + maxScanSeconds + " 秒，提前停止");
                break;
            }
            try {
                JSONObject filter = new JSONObject();
                filter.put("fromBlock", "0x" + Long.toHexString(start));
                filter.put("toBlock", "0x" + Long.toHexString(end));
                JSONArray topics = new JSONArray();
                topics.put(transferTopic);
                topics.put(JSONObject.NULL);
                topics.put(walletPadded); // to=wallet（转入方向）
                filter.put("topics", topics);

                JSONArray params = new JSONArray();
                params.put(filter);

                JSONObject body = new JSONObject();
                body.put("jsonrpc", "2.0");
                body.put("id", 1);
                body.put("method", "eth_getLogs");
                body.put("params", params);
                Request request = new Request.Builder()
                    .url(currentRpc)
                    .post(RequestBody.create(body.toString(), JSON_TYPE))
                    .build();
                String resp;
                try (Response response = client.newCall(request).execute()) {
                    resp = response.body() != null ? response.body().string() : "";
                }
                JSONObject json = new JSONObject(resp);
                if (json.has("error")) {
                    if (chunkSize > 1250) {
                        chunkSize = chunkSize / 2;
                        end += chunkSize * 2;
                    }
                    continue;
                }
                JSONArray logs = json.optJSONArray("result");
                if (logs == null || logs.length() == 0) {
                    consecutiveEmpty++;
                    // 连续 5 段（约 2.5 万区块）无转入即停止，避免空钱包全量空扫阻塞
                    if (consecutiveEmpty >= 5) {
                        Logger.info(ctx, "代币发现", "连续 " + consecutiveEmpty + " 段无转入，停止扫描");
                        break;
                    }
                    continue;
                }

                consecutiveEmpty = 0;
                for (int i = 0; i < logs.length(); i++) {
                    JSONObject log = logs.getJSONObject(i);
                    String contract = log.optString("address", "").toLowerCase();
                    if (seenContracts.contains(contract)) continue; // 已查过的代币跳过

                    // 新代币！查询 symbol、decimals、余额
                    try {
                        String symbol = callContractMethod(currentRpc, contract, "0x95d89b41");
                        if (symbol == null || symbol.isEmpty()) symbol = "UNKNOWN";
                        int decimals = getTokenDecimals(currentRpc, contract);
                        double balance = getERC20Balance(ctx, chain, address, contract, decimals);
                        seenContracts.add(contract);
                        if (balance <= 0) continue; // 余额为0不显示

                        double value = balance * prices.getOrDefault(symbol, 0.0);
                        tokens.add(new String[]{
                            symbol, symbol,
                            formatAmount(balance),
                            formatValue(ctx, value),
                            contract,
                            "",
                            "true"
                        });
                        newTokenCount++;
                        Logger.info(ctx, "代币发现", "Transfer 扫描发现新代币: " + symbol + " 余额=" + balance + " 合约=" + contract);
                    } catch (Exception e) {
                        seenContracts.add(contract); // 标记已查，避免重复尝试
                    }
                }
            } catch (Exception e) {
                // 单块失败不阻断
            }
        }

        if (newTokenCount > 0) {
            Logger.info(ctx, "代币发现", "全量扫描完成，新发现 " + newTokenCount + " 个未知代币");
            // 持久化新发现的代币，下次启动直接加载，跳过 Transfer 扫描
            try {
                DataCache cache = new DataCache(ctx);
                java.util.List<String[]> toPersist = new java.util.ArrayList<>();
                // 合并已有的持久化代币 + 新发现的
                java.util.List<String[]> existing = cache.getDiscoveredTokens();
                // 用合约地址去重
                java.util.Set<String> persistedContracts = new java.util.HashSet<>();
                for (String[] t : existing) {
                    if (t.length > 1 && t[1] != null) {
                        persistedContracts.add(t[1].toLowerCase());
                        toPersist.add(t);
                    }
                }
                // 添加本次新发现的
                for (String[] t : tokens) {
                    // token 格式: [symbol, name, balance, value, contract, "", "true"]
                    if (t.length > 4 && t[4] != null && !t[4].isEmpty()) {
                        String contract = t[4].toLowerCase();
                        if (!persistedContracts.contains(contract)) {
                            // 需要获取 decimals（从 token 信息中推算，或重新查询）
                            int decimals = 18;
                            try {
                                decimals = getTokenDecimals(currentRpc, contract);
                            } catch (Exception e) {}
                            toPersist.add(new String[]{t[0], contract, String.valueOf(decimals)});
                            persistedContracts.add(contract);
                        }
                    }
                }
                cache.saveDiscoveredTokens(toPersist);
                Logger.info(ctx, "代币发现", "持久化 " + toPersist.size() + " 个代币，下次启动秒开");
            } catch (Exception e) {
                Logger.warning(ctx, "代币发现", "持久化代币失败: " + e.getMessage());
            }
        }
    }

    /**
     * Multicall3 合约地址 - 跨链统一部署
     * https://www.multicall3.com/
     */
    private static final String MULTICALL3_ADDRESS = "0xcA11bde05977b3631167028862bE2a173976CA11";

    /**
     * 三层代币发现 + 批量余额查询（lightMode=true 时只做第1层内置热门代币，跳过远程列表和区块浏览器）。
     */
    private static void fetchCommonTokenBalances(Context ctx, String chain, String address,
                                                   java.util.List<String[]> tokens, Set<String> seenContracts,
                                                   Map<String, Double> prices, boolean lightMode) {
        String rpcUrl = getRpcUrlStatic(ctx, chain);
        if (rpcUrl == null || rpcUrl.isEmpty()) return;

        // 1. 收集所有要查询的代币合约地址
        java.util.List<String[]> chainTokens = new java.util.ArrayList<>();
        java.util.Set<String> discoveredContracts = new HashSet<>();

        // === 第0层：DeBank API 自动发现（优先级最高，一次获取用户所有代币） ===
        try {
            java.util.List<String[]> debankTokens = fetchDeBankTokensWithIPBypass(ctx, chain, address);
            int debankAdded = 0;
            for (String[] tokenInfo : debankTokens) {
                String contract = tokenInfo[0].toLowerCase();
                if (!seenContracts.contains(contract) && !discoveredContracts.contains(contract)) {
                    chainTokens.add(new String[]{chain, contract, tokenInfo[1], tokenInfo[2], tokenInfo[3]});
                    seenContracts.add(contract);
                    discoveredContracts.add(contract);
                    debankAdded++;
                }
            }
            if (debankAdded > 0) {
                Logger.success(ctx, "代币发现", "第0层：DeBank 自动发现 " + debankAdded + " 个代币");
            }
        } catch (Exception e) {
            Logger.warning(ctx, "代币发现", "第0层：DeBank 自动发现失败: " + e.getMessage());
        }

        // === 第1层：内置热门代币列表 ===
        // 1a. BSC 热门代币（500个，来自 TP 钱包）
        if ("BNB".equals(chain)) {
            for (String[] tokenInfo : BSC_POPULAR_TOKENS) {
                String contract = tokenInfo[0].toLowerCase();
                if (!seenContracts.contains(contract) && !discoveredContracts.contains(contract)) {
                    chainTokens.add(new String[]{chain, contract, tokenInfo[1], tokenInfo[2], tokenInfo[3]});
                    seenContracts.add(contract);
                    discoveredContracts.add(contract);
                }
            }
            Logger.info(ctx, "代币发现", "第1层：BSC热门代币 " + BSC_POPULAR_TOKENS.length + " 个已加载");
        }

        // 1c. ETH 热门代币（200个，来自 TP 钱包）
        if ("ETH".equals(chain)) {
            for (String[] tokenInfo : ETH_POPULAR_TOKENS) {
                String contract = tokenInfo[0].toLowerCase();
                if (!seenContracts.contains(contract) && !discoveredContracts.contains(contract)) {
                    chainTokens.add(new String[]{chain, contract, tokenInfo[1], tokenInfo[2], tokenInfo[3]});
                    seenContracts.add(contract);
                    discoveredContracts.add(contract);
                }
            }
            Logger.info(ctx, "代币发现", "第1层：ETH热门代币 " + ETH_POPULAR_TOKENS.length + " 个已加载");
        }

        // 1d. MATIC/Polygon 热门代币（200个，来自 TP 钱包）
        if ("MATIC".equals(chain)) {
            for (String[] tokenInfo : MATIC_POPULAR_TOKENS) {
                String contract = tokenInfo[0].toLowerCase();
                if (!seenContracts.contains(contract) && !discoveredContracts.contains(contract)) {
                    chainTokens.add(new String[]{chain, contract, tokenInfo[1], tokenInfo[2], tokenInfo[3]});
                    seenContracts.add(contract);
                    discoveredContracts.add(contract);
                }
            }
            Logger.info(ctx, "代币发现", "第1层：MATIC热门代币 " + MATIC_POPULAR_TOKENS.length + " 个已加载");
        }

        // 1e. AVAX 热门代币（93个，来自 TP 钱包）
        if ("AVAX".equals(chain)) {
            for (String[] tokenInfo : AVAX_POPULAR_TOKENS) {
                String contract = tokenInfo[0].toLowerCase();
                if (!seenContracts.contains(contract) && !discoveredContracts.contains(contract)) {
                    chainTokens.add(new String[]{chain, contract, tokenInfo[1], tokenInfo[2], tokenInfo[3]});
                    seenContracts.add(contract);
                    discoveredContracts.add(contract);
                }
            }
            Logger.info(ctx, "代币发现", "第1层：AVAX热门代币 " + AVAX_POPULAR_TOKENS.length + " 个已加载");
        }

        // 1f. FTM/Fantom 热门代币（91个，来自 TP 钱包）
        if ("FTM".equals(chain)) {
            for (String[] tokenInfo : FTM_POPULAR_TOKENS) {
                String contract = tokenInfo[0].toLowerCase();
                if (!seenContracts.contains(contract) && !discoveredContracts.contains(contract)) {
                    chainTokens.add(new String[]{chain, contract, tokenInfo[1], tokenInfo[2], tokenInfo[3]});
                    seenContracts.add(contract);
                    discoveredContracts.add(contract);
                }
            }
            Logger.info(ctx, "代币发现", "第1层：FTM热门代币 " + FTM_POPULAR_TOKENS.length + " 个已加载");
        }

        // 1g. TRX/TRON 热门代币（23个，来自 TP 钱包）
        if ("TRX".equals(chain)) {
            for (String[] tokenInfo : TRX_POPULAR_TOKENS) {
                String contract = tokenInfo[0].toLowerCase();
                if (!seenContracts.contains(contract) && !discoveredContracts.contains(contract)) {
                    chainTokens.add(new String[]{chain, contract, tokenInfo[1], tokenInfo[2], tokenInfo[3]});
                    seenContracts.add(contract);
                    discoveredContracts.add(contract);
                }
            }
            Logger.info(ctx, "代币发现", "第1层：TRX热门代币 " + TRX_POPULAR_TOKENS.length + " 个已加载");
        }

        // 1h. ONE/Harmony 热门代币（32个，来自 TP 钱包）
        if ("ONE".equals(chain)) {
            for (String[] tokenInfo : ONE_POPULAR_TOKENS) {
                String contract = tokenInfo[0].toLowerCase();
                if (!seenContracts.contains(contract) && !discoveredContracts.contains(contract)) {
                    chainTokens.add(new String[]{chain, contract, tokenInfo[1], tokenInfo[2], tokenInfo[3]});
                    seenContracts.add(contract);
                    discoveredContracts.add(contract);
                }
            }
            Logger.info(ctx, "代币发现", "第1层：ONE热门代币 " + ONE_POPULAR_TOKENS.length + " 个已加载");
        }

        // 1b. 其他链的预置常用代币（COMMON_TOKENS，已过时但仍在用）
        int commonCount = 0;
        for (String[] tokenInfo : COMMON_TOKENS) {
            if (chain.equals(tokenInfo[0]) && !seenContracts.contains(tokenInfo[1].toLowerCase())) {
                chainTokens.add(tokenInfo);
                seenContracts.add(tokenInfo[1].toLowerCase());
                discoveredContracts.add(tokenInfo[1].toLowerCase());
                commonCount++;
            }
        }
        if (commonCount > 0) {
            Logger.info(ctx, "代币发现", "第1层：预置代币 " + commonCount + " 个已加载");
        }

        // === 第2层：1inch 多链代币列表（PancakeSwap 已删除：9921个代币查余额太慢） ===
        if (!lightMode && ("ETH".equals(chain) || "MATIC".equals(chain) || "AVAX".equals(chain) || "FTM".equals(chain) || "ARB".equals(chain))) {
            try {
                String chainId;
                switch (chain) {
                    case "ETH": chainId = "1"; break;
                    case "MATIC": chainId = "137"; break;
                    case "AVAX": chainId = "43114"; break;
                    case "FTM": chainId = "250"; break;
                    case "ARB": chainId = "42161"; break;
                    default: chainId = "1"; break;
                }
                java.util.List<String[]> inchTokens = fetch1inchTokens(ctx, chainId);
                int inchAdded = 0;
                for (String[] tokenInfo : inchTokens) {
                    String contract = tokenInfo[0].toLowerCase();
                    if (!seenContracts.contains(contract) && !discoveredContracts.contains(contract)) {
                        chainTokens.add(new String[]{chain, contract, tokenInfo[1], tokenInfo[2], tokenInfo[3]});
                        seenContracts.add(contract);
                        discoveredContracts.add(contract);
                        inchAdded++;
                    }
                }
                Logger.info(ctx, "代币发现", "第2层：1inch 远程列表加载 " + inchAdded + " 个新代币（总共 " + inchTokens.size() + " 个）");
            } catch (Exception e) {
                Logger.warning(ctx, "代币发现", "第2层：1inch 远程列表获取失败: " + e.getMessage());
            }
        }

        // 2c. CoinGecko ETH 代币列表（补充，比 1inch 更全面）
        if (!lightMode && "ETH".equals(chain)) {
            try {
                java.util.List<String[]> cgTokens = fetchCoinGeckoTokens(ctx);
                int cgAdded = 0;
                for (String[] tokenInfo : cgTokens) {
                    String contract = tokenInfo[0].toLowerCase();
                    if (!seenContracts.contains(contract) && !discoveredContracts.contains(contract)) {
                        chainTokens.add(new String[]{chain, contract, tokenInfo[1], tokenInfo[2], tokenInfo[3]});
                        seenContracts.add(contract);
                        discoveredContracts.add(contract);
                        cgAdded++;
                    }
                }
                Logger.info(ctx, "代币发现", "第2层：CoinGecko 远程列表加载 " + cgAdded + " 个新代币（总共 " + cgTokens.size() + " 个）");
            } catch (Exception e) {
                Logger.warning(ctx, "代币发现", "第2层：CoinGecko 远程列表获取失败: " + e.getMessage());
            }
        }

        // 2d. Solana SPL 代币列表
        if (!lightMode && "SOL".equals(chain)) {
            try {
                java.util.List<String[]> solTokens = fetchSolanaTokens(ctx);
                int solAdded = 0;
                for (String[] tokenInfo : solTokens) {
                    String contract = tokenInfo[0].toLowerCase();
                    if (!seenContracts.contains(contract) && !discoveredContracts.contains(contract)) {
                        chainTokens.add(new String[]{chain, contract, tokenInfo[1], tokenInfo[2], tokenInfo[3]});
                        seenContracts.add(contract);
                        discoveredContracts.add(contract);
                        solAdded++;
                    }
                }
                Logger.info(ctx, "代币发现", "第2层：Solana Token List 加载 " + solAdded + " 个代币（总共 " + solTokens.size() + " 个）");
            } catch (Exception e) {
                Logger.warning(ctx, "代币发现", "第2层：Solana Token List 获取失败: " + e.getMessage());
            }
        }

        // 2e. TronGrid TRC20 代币列表
        if (!lightMode && "TRX".equals(chain)) {
            try {
                java.util.List<String[]> tronTokens = fetchTronGridTokens(ctx);
                int tronAdded = 0;
                for (String[] tokenInfo : tronTokens) {
                    String contract = tokenInfo[0].toLowerCase();
                    if (!seenContracts.contains(contract) && !discoveredContracts.contains(contract)) {
                        chainTokens.add(new String[]{chain, contract, tokenInfo[1], tokenInfo[2], tokenInfo[3]});
                        seenContracts.add(contract);
                        discoveredContracts.add(contract);
                        tronAdded++;
                    }
                }
                Logger.info(ctx, "代币发现", "第2层：TronGrid 远程列表加载 " + tronAdded + " 个新代币（总共 " + tronTokens.size() + " 个）");
            } catch (Exception e) {
                Logger.warning(ctx, "代币发现", "第2层：TronGrid 远程列表获取失败: " + e.getMessage());
            }
        }

        // === 第3层：IP直连区块浏览器网页抓取（兜底，轻量模式跳过） ===
        if (lightMode) {
            Logger.info(ctx, "代币发现", "轻量模式：跳过远程列表和区块浏览器扫描");
        }
        if (!lightMode) try {
            java.util.List<String[]> discoveredFromExplorer = discoverTokensViaExplorer(ctx, chain, address);
            int explorerAdded = 0;
            for (String[] tokenInfo : discoveredFromExplorer) {
                String contract = tokenInfo[1].toLowerCase();
                if (seenContracts.contains(contract) || discoveredContracts.contains(contract)) continue;
                chainTokens.add(tokenInfo);
                seenContracts.add(contract);
                discoveredContracts.add(contract);
                explorerAdded++;
            }
            if (explorerAdded > 0) {
                Logger.info(ctx, "代币发现", "第3层：区块浏览器抓取补充 " + explorerAdded + " 个新代币");
            }
        } catch (Exception e) {
            Logger.warning(ctx, "代币发现", "第3层：区块浏览器抓取失败: " + e.getMessage());
        }

        if (chainTokens.isEmpty()) return;

        // 优化：限制最多查询100个代币，避免516个代币串行查询导致卡死
        final int MAX_TOKEN_QUERY = 100;
        if (chainTokens.size() > MAX_TOKEN_QUERY) {
            Logger.info(ctx, "代币发现", "代币数量过多（" + chainTokens.size() + "），限制查询前 " + MAX_TOKEN_QUERY + " 个避免卡死");
            chainTokens = chainTokens.subList(0, MAX_TOKEN_QUERY);
        }

        Logger.info(ctx, "代币发现", Logger.getChainChineseName(chain) +
            " 共收集 " + chainTokens.size() + " 个代币待查余额");

        // 2. 优先尝试 JSON-RPC 批量查询余额
        try {
            int count = batchBalancesViaMulticall3(ctx, chain, rpcUrl, address, chainTokens, tokens, prices);
            Logger.success(ctx, "代币发现", "批量查询 " + Logger.getChainChineseName(chain) +
                " 查询 " + chainTokens.size() + " 个代币，找到 " + count + " 个有余额");
            return;
        } catch (Exception e) {
            Logger.warning(ctx, "代币发现", "批量查询失败，回退逐个查询: " + e.getMessage());
        }

        // 3. 回退：逐个 RPC 查询（通用多链回退，使用 NodeManager 该链全部预设节点）
        String fallbackRpc = rpcUrl;
        OkHttpClient fallbackClient = client;
        java.util.List<String> singleFallbackList = new java.util.ArrayList<>();
        for (NodeManager.NodeEntry entry : NodeManager.getPresets(chain)) {
            if (entry.url != null && !entry.url.equals(rpcUrl) && !singleFallbackList.contains(entry.url)) {
                singleFallbackList.add(entry.url);
            }
        }
        String[] singleFallbackRpcs = singleFallbackList.toArray(new String[0]);
        int singleFallbackStage = -1; // -1=主节点, 0+=备用节点索引
        int count = 0;
        int failCount = 0;
        for (String[] tokenInfo : chainTokens) {
            String contract = tokenInfo[1].toLowerCase();
            String symbol = tokenInfo[2];
            String name = tokenInfo[3];
            int decimals;
            try { decimals = Integer.parseInt(tokenInfo[4]); } catch (Exception e) { continue; }
            if (decimals == 0) continue;

            try {
                if (symbol.isEmpty()) {
                    String[] meta = getERC20Metadata(fallbackRpc, contract);
                    if (meta == null) continue;
                    symbol = meta[0];
                    name = meta[1];
                    try { decimals = Integer.parseInt(meta[2]); } catch (Exception e) { decimals = 18; }
                }
                double balance = getERC20Balance(ctx, chain, address, contract, decimals);
                if (balance <= 0) { failCount = 0; continue; }
                failCount = 0;

                double value = balance * prices.getOrDefault(symbol, 0.0);
                tokens.add(new String[]{
                    symbol, name,
                    formatAmount(balance),
                    formatValue(ctx, value),
                    contract,
                    "",
                    "true"
                });
                count++;
            } catch (Exception e) {
                if (singleFallbackRpcs != null) {
                    failCount++;
                    if (failCount >= 3) {
                        singleFallbackStage++;
                        if (singleFallbackStage < singleFallbackRpcs.length) {
                            fallbackRpc = singleFallbackRpcs[singleFallbackStage];
                            Logger.info(ctx, "代币发现", "连续失败" + failCount + "次，回退到备用节点("+(singleFallbackStage+1)+"/"+singleFallbackRpcs.length+"): " + fallbackRpc);
                        } else {
                            Logger.warning(ctx, "代币发现", "所有" + Logger.getChainChineseName(chain) + "节点均不可用，跳过");
                            break;
                        }
                        fallbackClient = client;
                        failCount = 0;
                    }
                }
            }
        }
        Logger.success(ctx, "代币发现", "逐个查询 " + Logger.getChainChineseName(chain) +
            " 扫描 " + chainTokens.size() + " 个，找到 " + count + " 个有余额");
    }

    /**
     * 通过区块浏览器网页抓取，动态发现钱包涉及的所有代币（无需 API key）
     * 支持 BSC/ETH/Polygon/Arbitrum/Optimism 等主流链
     *
     * 策略：访问 https://<explorer>/tokentxns?a=<wallet>，从 HTML 中提取所有代币合约地址
     * 这是 BSC 链上唯一可靠的免费代币发现方案（BSC RPC 全部封锁了 eth_getLogs）
     *
     * @return 代币信息数组列表，每个元素 = {chain, contract, symbol, name, decimals}
     */
    /** 检测 Cloudflare Challenge 页（非真实页面） */
    private static boolean isCloudflareChallenge(String html) {
        return html.contains("Just a moment")
            || html.contains("请稍候")
            || html.contains("cf-challenge")
            || html.contains("challenge-platform")
            || (html.length() < 60000 && !html.contains("/token/0x"));
    }

    private static java.util.List<String[]> discoverTokensViaExplorer(Context ctx, String chain, String wallet) {
        java.util.List<String[]> discovered = new java.util.ArrayList<>();
        java.util.Set<String> seen = new HashSet<>();

        // 各链对应的区块浏览器 URL（无需 API key，直接抓取网页）
        String explorerUrl;
        String hostHeader = null; // 用于 SNI 阻断绕过
        String[] ipFallbacks = null; // Cloudflare CDN IP 备用
        switch (chain) {
            case "BNB":
                explorerUrl = "https://bscscan.com/tokentxns?a=" + wallet;
                hostHeader = "bscscan.com";
                ipFallbacks = new String[]{"104.26.13.158", "104.26.12.158", "172.67.72.93", "104.16.132.229", "172.64.149.15", "104.18.43.147", "162.159.135.232", "188.114.96.1"};
                break;
            case "ETH":
                explorerUrl = "https://etherscan.io/tokentxns?a=" + wallet;
                hostHeader = "etherscan.io";
                ipFallbacks = new String[]{"104.18.32.38", "104.18.33.38"};
                break;
            case "MATIC":
                explorerUrl = "https://polygonscan.com/tokentxns?a=" + wallet;
                hostHeader = "polygonscan.com";
                ipFallbacks = new String[]{"104.18.32.38", "104.18.33.38"};
                break;
            case "ARB":
                explorerUrl = "https://arbiscan.io/tokentxns?a=" + wallet;
                hostHeader = "arbiscan.io";
                break;
            case "OP":
                explorerUrl = "https://optimistic.etherscan.io/tokentxns?a=" + wallet;
                hostHeader = "optimistic.etherscan.io";
                break;
            case "BASE":
                explorerUrl = "https://basescan.org/tokentxns?a=" + wallet;
                hostHeader = "basescan.org";
                break;
            case "AVAX":
                explorerUrl = "https://snowtrace.io/tokentxns?a=" + wallet;
                hostHeader = "snowtrace.io";
                ipFallbacks = new String[]{"104.18.32.38", "104.18.33.38"};
                break;
            case "FTM":
                explorerUrl = "https://ftmscan.com/tokentxns?a=" + wallet;
                hostHeader = "ftmscan.com";
                ipFallbacks = new String[]{"104.18.32.38", "104.18.33.38"};
                break;
            default:
                return discovered; // 该链不支持网页抓取
        }

        // 先尝试标准 URL（用默认 client），失败后用域名 URL + 自定义 DNS 绕过 SNI 阻断
        // 关键：不用 IP URL，而是用域名 URL + 自定义 DNS 把域名解析到 Cloudflare CDN IP
        // 这样 TLS 握手 SNI 字段是域名，Cloudflare CDN 才会接受连接
        String[] ipsToTry = (ipFallbacks != null) ? ipFallbacks : new String[0];

        String html = null;
        Exception lastError = null;

        // 第 1 轮：用默认 client 直接访问域名
        try {
            Request.Builder rb = new Request.Builder().url(explorerUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept-Encoding", "gzip, deflate")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .header("Sec-Ch-Ua", "\"Chromium\";v=\"120\", \"Not_A Brand\";v=\"8\", \"Google Chrome\";v=\"120\"")
                .header("Sec-Ch-Ua-Mobile", "?0")
                .header("Sec-Ch-Ua-Platform", "\"Windows\"")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Upgrade-Insecure-Requests", "1");
            Request req = rb.get().build();
            try (Response resp = client.newCall(req).execute()) {
                String bodyStr = "";
                try { bodyStr = resp.body() != null ? resp.body().string() : ""; } catch (Exception ignore) {}
                if (resp.isSuccessful() && !bodyStr.isEmpty() && !isCloudflareChallenge(bodyStr)) {
                    html = bodyStr;
                    Logger.info(ctx, "代币发现", "区块浏览器抓取成功 " + html.length() + " bytes (标准域名)");
                } else if (resp.isSuccessful() && isCloudflareChallenge(bodyStr)) {
                    Logger.warning(ctx, "代币发现", "Cloudflare JS Challenge 页（中国无法通过），跳过IP直连快速放弃");
                    return discovered;
                } else {
                    Logger.warning(ctx, "代币发现", "区块浏览器抓取失败 HTTP " + resp.code() + " (标准域名)");
                }
            }
        } catch (Exception e) {
            lastError = e;
            Logger.warning(ctx, "代币发现", "标准域名抓取失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // 第 2 轮：用域名 URL + 自定义 DNS 直连 Cloudflare CDN IP（绕过 SNI 阻断）
        if (html == null) {
            for (String ip : ipsToTry) {
                try {
                    OkHttpClient ipClient = createBypassClient(ip);
                    Request.Builder rb = new Request.Builder().url(explorerUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .header("Accept-Encoding", "gzip, deflate")
                        .header("Cache-Control", "no-cache")
                        .header("Pragma", "no-cache")
                        .header("Sec-Ch-Ua", "\"Chromium\";v=\"120\", \"Not_A Brand\";v=\"8\", \"Google Chrome\";v=\"120\"")
                        .header("Sec-Ch-Ua-Mobile", "?0")
                        .header("Sec-Ch-Ua-Platform", "\"Windows\"")
                        .header("Sec-Fetch-Dest", "document")
                        .header("Sec-Fetch-Mode", "navigate")
                        .header("Sec-Fetch-Site", "none")
                        .header("Sec-Fetch-User", "?1")
                        .header("Upgrade-Insecure-Requests", "1");
                    Request req = rb.get().build();
                    try (Response resp = ipClient.newCall(req).execute()) {
                        String bodyStr = "";
                        try { bodyStr = resp.body() != null ? resp.body().string() : ""; } catch (Exception ignore) {}
                        if (resp.isSuccessful() && !bodyStr.isEmpty() && !isCloudflareChallenge(bodyStr)) {
                            html = bodyStr;
                            Logger.info(ctx, "代币发现", "区块浏览器抓取成功 " + html.length() + " bytes (IP直连 " + ip + ")");
                            break;
                        } else if (resp.isSuccessful() && isCloudflareChallenge(bodyStr)) {
                            Logger.warning(ctx, "代币发现", "Cloudflare Challenge 页 (IP直连 " + ip + " size=" + bodyStr.length() + ")，尝试下一个 IP");
                        } else {
                            Logger.warning(ctx, "代币发现", "IP直连 " + ip + " 失败 HTTP " + resp.code());
                        }
                    }
                } catch (Exception e) {
                    lastError = e;
                    Logger.warning(ctx, "代币发现", "IP直连 " + ip + " 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }
        if (html == null) {
            Logger.warning(ctx, "代币发现", "所有 URL 均失败 (" + chain + ")" + (lastError != null ? " 最后错误: " + lastError.getMessage() : ""));
            return discovered;
        }

        try {
            // 从 HTML 中提取所有代币合约地址
            // 链接格式：href="/token/0xABC...?a=0xWALLET"
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "/token/(0x[a-fA-F0-9]{40})", java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher matcher = pattern.matcher(html);
            int count = 0;
            while (matcher.find()) {
                String contract = matcher.group(1).toLowerCase();
                if (seen.contains(contract)) continue;
                seen.add(contract);
                discovered.add(new String[]{chain, contract, "", "", "0"});
                count++;
            }
            Logger.info(ctx, "代币发现", "区块浏览器抓取 " + chain + " 发现 " + count + " 个代币合约");

            // 尝试提取代币 symbol（从 alt 属性或链接文本）
            java.util.regex.Pattern symPattern = java.util.regex.Pattern.compile(
                "(?:ERC-20|BEP-20|Polygon):\\s*([^()\\n]+?)\\s*\\(([^()\\n]+)\\)");
            java.util.regex.Matcher symMatcher = symPattern.matcher(html);
            while (symMatcher.find()) {
                String name = symMatcher.group(1).trim();
                String symbol = symMatcher.group(2).trim();
                int symEnd = symMatcher.end();
                java.util.regex.Pattern nextToken = java.util.regex.Pattern.compile(
                    "/token/(0x[a-fA-F0-9]{40})", java.util.regex.Pattern.CASE_INSENSITIVE);
                java.util.regex.Matcher nextMatcher = nextToken.matcher(html.substring(symEnd));
                if (nextMatcher.find()) {
                    String contract = nextMatcher.group(1).toLowerCase();
                    for (String[] token : discovered) {
                        if (token[1].toLowerCase().equals(contract)) {
                            token[2] = symbol;
                            token[3] = name;
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Logger.error(ctx, "代币发现", "解析 HTML 失败: " + e.getMessage(), e);
        }
        return discovered;
    }

    /**
     * 通过 RPC 调用代币的 symbol()/name()/decimals() 获取代币元数据（ERC-20/BEP-20 通用）
     * @return [symbol, name, decimals] 或 null
     */
    public static String[] getERC20Metadata(String rpcUrl, String contract) {
        String symbolHex = callStringMethod(rpcUrl, contract, "0x95d89b41"); // symbol()
        String symbol = parseStringResult(symbolHex);
        if (symbol == null || symbol.isEmpty()) return null;
        String nameHex = callStringMethod(rpcUrl, contract, "0x06fdde03");   // name()
        String name = parseStringResult(nameHex);
        if (name == null || name.isEmpty()) name = symbol;
        String decimalsHex = callStringMethod(rpcUrl, contract, "0x313ce567"); // decimals()
        int decimals = 18;
        if (decimalsHex != null && decimalsHex.length() >= 66) {
            try {
                decimals = new BigInteger(decimalsHex.substring(2, 66), 16).intValue();
            } catch (Exception ignore) {}
        }
        return new String[]{symbol, name, String.valueOf(decimals)};
    }

    /**
     * 调用合约的无参数 string/uint 返回方法，返回原始 hex 结果
     */
    private static String callStringMethod(String rpcUrl, String contract, String dataHex) {
        try {
            JSONObject body = new JSONObject();
            body.put("jsonrpc", "2.0");
            body.put("method", "eth_call");
            JSONArray params = new JSONArray();
            JSONObject call = new JSONObject();
            call.put("to", contract);
            call.put("data", dataHex);
            params.put(call);
            params.put("latest");
            body.put("params", params);
            body.put("id", 1);

            Request req = new Request.Builder().url(rpcUrl)
                .post(RequestBody.create(body.toString(), JSON_TYPE)).build();
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                JSONObject r = new JSONObject(resp.body().string());
                if (r.has("error")) return null;
                return r.optString("result", "0x");
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析代币 symbol()/name() 返回的 string（可能是动态 string 或 bytes32）
     */
    private static String parseStringResult(String hex) {
        if (hex == null || hex.length() < 10) return "";
        hex = hex.substring(2); // remove 0x
        if (hex.length() < 128) return "";
        try {
            // 动态 string 编码：offset(32) + length(32) + data
            int len = new BigInteger(hex.substring(64, 128), 16).intValue();
            if (len <= 0 || len > 64) {
                // 可能是 bytes32 编码
                if (hex.length() >= 64) {
                    byte[] bytes = new BigInteger(hex.substring(0, 64), 16).toByteArray();
                    StringBuilder sb = new StringBuilder();
                    for (byte b : bytes) {
                        if (b >= 32 && b < 127) sb.append((char) b);
                    }
                    return sb.toString().trim();
                }
                return "";
            }
            String dataHex = hex.substring(128, 128 + len * 2);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < dataHex.length(); i += 2) {
                int c = Integer.parseInt(dataHex.substring(i, i + 2), 16);
                if (c >= 32 && c < 127) sb.append((char) c);
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 批量查询代币 balanceOf - 使用 JSON-RPC 批量请求
     * 一次 HTTP POST 发送多个 eth_call 请求，大幅减少网络往返
     * 比 Multicall3 更简单可靠（不依赖特定合约，不需要手工 ABI 编码）
     *
     * JSON-RPC 批量格式：[{jsonrpc,method,params,id}, ...]
     */
    private static int batchBalancesViaMulticall3(Context ctx, String chain, String rpcUrl,
                                                    String walletAddress, java.util.List<String[]> chainTokens,
                                                    java.util.List<String[]> tokens, Map<String, Double> prices) throws Exception {
        // Multicall3 是 EVM 合约，SOL/TRX 非 EVM 链不支持 eth_call，
        // 直接抛异常让调用方回退到逐个查询（走 getERC20Balance 里的链原生方法）
        if (!isEVMChain(chain)) {
            throw new Exception("非 EVM 链不支持 Multicall3 批量查询，回退逐个查询");
        }
        int n = chainTokens.size();
        String addrPadded = walletAddress.substring(2).toLowerCase();
        while (addrPadded.length() < 64) addrPadded = "0" + addrPadded;

        // 通用多节点回退：主节点失败后依次尝试 NodeManager 中该链的所有预设节点
        String batchRpcUrl = rpcUrl;
        OkHttpClient batchHttpClient = batchClient;
        java.util.List<String> fallbackList = new java.util.ArrayList<>();
        for (NodeManager.NodeEntry entry : NodeManager.getPresets(chain)) {
            if (entry.url != null && !entry.url.equals(rpcUrl) && !fallbackList.contains(entry.url)) {
                fallbackList.add(entry.url);
            }
        }
        String[] fallbackRpcs = fallbackList.toArray(new String[0]);
        int fallbackStage = -1; // -1=主节点, 0+=fallbackRpcs索引

        // 分批发送（每批 50 个代币），避免 HTTP 503
        int batchSize = 50;
        int count = 0;
        String[] results = new String[n];
        java.util.Arrays.fill(results, null);

        for (int batchStart = 0; batchStart < n; batchStart += batchSize) {
            int batchEnd = Math.min(batchStart + batchSize, n);

            JSONArray batchReq = new JSONArray();
            int validInBatch = 0;
            for (int i = batchStart; i < batchEnd; i++) {
                int tokenDecimals;
                try { tokenDecimals = Integer.parseInt(chainTokens.get(i)[4]); } catch (Exception e) { tokenDecimals = 0; }
                if (tokenDecimals == 0) continue;
                String tokenAddr = chainTokens.get(i)[1].toLowerCase();
                String callData = "0x70a08231" + addrPadded; // balanceOf(address)

                JSONObject call = new JSONObject();
                call.put("to", tokenAddr);
                call.put("data", callData);

                JSONArray params = new JSONArray();
                params.put(call);
                params.put("latest");

                JSONObject req = new JSONObject();
                req.put("jsonrpc", "2.0");
                req.put("method", "eth_call");
                req.put("params", params);
                req.put("id", i);
                batchReq.put(req);
                validInBatch++;
            }
            if (validInBatch == 0) continue;

            Request request = new Request.Builder()
                .url(batchRpcUrl)
                .post(RequestBody.create(batchReq.toString(), JSON_TYPE))
                .header("Content-Type", "application/json")
                .build();

            String respBody;
            try (Response response = batchHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    Logger.warning(ctx, "代币发现", "批量查询 HTTP " + response.code() + " (批次 " + (batchStart/batchSize+1) + "/" + ((n+batchSize-1)/batchSize) + ")");
                    // 通用多链回退
                    if (fallbackRpcs != null) {
                        fallbackStage++;
                        if (fallbackStage < fallbackRpcs.length) {
                            batchRpcUrl = fallbackRpcs[fallbackStage];
                            Logger.info(ctx, "代币发现", "回退到备用节点("+(fallbackStage+1)+"/"+fallbackRpcs.length+"): " + batchRpcUrl);
                            batchHttpClient = batchClient;
                            batchStart -= batchSize;
                        }
                    }
                    continue;
                }
                respBody = response.body().string();
            } catch (Exception e) {
                Logger.warning(ctx, "代币发现", "批量查询连接失败 (批次 " + (batchStart/batchSize+1) + "): " + e.getMessage());
                if (fallbackRpcs != null) {
                    fallbackStage++;
                    if (fallbackStage < fallbackRpcs.length) {
                        batchRpcUrl = fallbackRpcs[fallbackStage];
                        Logger.info(ctx, "代币发现", "连接失败，回退到备用节点("+(fallbackStage+1)+"/"+fallbackRpcs.length+"): " + batchRpcUrl);
                        batchHttpClient = batchClient;
                        batchStart -= batchSize;
                    }
                }
                continue;
            }

            try {
                JSONArray batchResp = new JSONArray(respBody);
                for (int i = 0; i < batchResp.length(); i++) {
                    JSONObject resp = batchResp.getJSONObject(i);
                    int id = resp.optInt("id", -1);
                    if (id < 0 || id >= n) continue;
                    if (resp.has("error")) {
                        results[id] = null;
                    } else {
                        results[id] = resp.optString("result", "0x");
                    }
                }
            } catch (Exception e) {
                // 批量响应解析失败（空响应/非法JSON/node异常）。若静默跳过会让该批次真实代币被丢弃，
                // 造成资产列表时有时无（显示不稳定）。抛出异常触发调用方逐个查询回退，保证真实代币不漏。
                Logger.warning(ctx, "代币发现", "批量查询解析失败 (批次 " + (batchStart/batchSize+1) + "): " + e.getMessage());
                throw new Exception("批量查询解析失败，回退逐个查询");
            }
        }

        // 处理每个代币的余额
        for (int i = 0; i < n; i++) {
            String resultHex = results[i];
            String[] tokenInfo = chainTokens.get(i);
            String symbol = tokenInfo[2];
            String contractAddr = tokenInfo[1].toLowerCase();
            // v2.4.72: 对 USDT 等关键代币添加完整诊断日志 + 直接 eth_call 回退
            if ("USDT".equals(symbol) || "USDC".equals(symbol) || "BNB".equals(symbol)) {
                Logger.info(ctx, "代币发现", "关键代币批量查询: " + symbol + " contract=" + contractAddr +
                    " resultHex=" + (resultHex != null ? resultHex : "null") +
                    " len=" + (resultHex != null ? resultHex.length() : 0));
            }
            if (resultHex == null || resultHex.length() < 10) {
                // v2.4.72: 关键代币批量查询失败，用直接 eth_call 回退
                if ("USDT".equals(symbol) || "USDC".equals(symbol)) {
                    try {
                        String directBalance = callContractMethod(rpcUrl, contractAddr, "0x70a08231" + addrPadded);
                        Logger.info(ctx, "代币发现", symbol + " 批量查询失败，直接 eth_call 回退: " + directBalance);
                        if (directBalance != null && directBalance.length() >= 66) {
                            resultHex = directBalance;
                        }
                    } catch (Exception e) {
                        Logger.error(ctx, "代币发现", symbol + " 直接 eth_call 回退失败: " + e.getMessage());
                    }
                }
                if (resultHex == null || resultHex.length() < 10) continue;
            }

            int decimals;
            try { decimals = Integer.parseInt(tokenInfo[4]); } catch (Exception e) { continue; }
            if (decimals == 0) continue;

            // 解析 balanceOf 返回值 (uint256, 32 bytes)
            String hex = resultHex.substring(2);
            if (hex.length() < 64) continue;
            BigInteger balanceWei;
            try {
                balanceWei = new BigInteger(hex.substring(0, 64), 16);
            } catch (Exception e) {
                continue;
            }
            if (balanceWei.compareTo(BigInteger.ZERO) <= 0) {
                // v2.4.72: 关键代币余额为 0，用直接 eth_call 确认
                if ("USDT".equals(symbol) || "USDC".equals(symbol)) {
                    try {
                        String directBalance = callContractMethod(rpcUrl, contractAddr, "0x70a08231" + addrPadded);
                        Logger.info(ctx, "代币发现", symbol + " 批量查询余额=0，直接 eth_call 确认: " + directBalance);
                        if (directBalance != null && directBalance.length() >= 66) {
                            String directHex = directBalance.substring(2);
                            if (directHex.length() >= 64) {
                                BigInteger directWei = new BigInteger(directHex.substring(0, 64), 16);
                                if (directWei.compareTo(BigInteger.ZERO) > 0) {
                                    balanceWei = directWei;
                                    Logger.info(ctx, "代币发现", symbol + " 直接 eth_call 确认有余额，使用直接查询结果");
                                }
                            }
                        }
                    } catch (Exception e) {
                        Logger.error(ctx, "代币发现", symbol + " 直接 eth_call 确认失败: " + e.getMessage());
                    }
                }
                if (balanceWei.compareTo(BigInteger.ZERO) <= 0) continue;
            }

            String name = tokenInfo[3];
            String contract = tokenInfo[1].toLowerCase();

            // 未知代币（从链上扫描发现的）需要查 symbol/name/decimals
            if (symbol == null || symbol.isEmpty()) {
                String[] meta = getERC20Metadata(rpcUrl, contract);
                if (meta == null) continue;
                symbol = meta[0];
                name = meta[1];
                try { decimals = Integer.parseInt(meta[2]); } catch (Exception e) { decimals = 18; }
            }

            double balance = balanceWei.doubleValue() / Math.pow(10, decimals);
            double value = balance * prices.getOrDefault(symbol, 0.0);

            tokens.add(new String[]{
                symbol, name,
                formatAmount(balance),
                formatValue(ctx, value),
                contract,
                "",
                "true"
            });
            count++;
        }
        return count;
    }

    /**
     * Get NFT list for a wallet using DeBank API
     */
    public static java.util.List<String[]> getNFTList(Context ctx, String chain, String address) throws Exception {
        java.util.List<String[]> nfts = new java.util.ArrayList<>();

        String debankChainId = getDeBankChainId(chain);
        if (debankChainId == null) return nfts;

        String url = "https://openapi.debank.com/v1/user/nft_list?id=" + address + "&chain_id=" + debankChainId;

        Request request = new Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONArray jsonArr = new JSONArray(resp);

            for (int i = 0; i < jsonArr.length(); i++) {
                JSONObject nft = jsonArr.getJSONObject(i);
                String contractId = nft.optString("contract_id", "");
                String name = nft.optString("name", "NFT");
                String tokenId = nft.optString("token_id", "");
                String collectionName = nft.optString("collection_name", "");
                String imageUrl = nft.optString("content", "");
                double floorPrice = nft.optDouble("floor_price", 0);

                nfts.add(new String[]{
                    contractId,
                    name,
                    tokenId,
                    collectionName,
                    imageUrl,
                    formatValue(ctx, floorPrice)
                });
            }
        }

        return nfts;
    }

    // ============================================================
    // 自定义 NFT 存储（按链+钱包地址独立存储，不混入 ERC20 代币列表）
    // ============================================================
    private static final String CUSTOM_NFTS_PREFS = "custom_nfts";

    public static void addCustomNFT(Context ctx, String chain, String walletAddress, String contract, String tokenId, String name, String imageUrl) {
        java.util.List<String[]> list = getCustomNFTs(ctx, chain, walletAddress);
        for (String[] nft : list) {
            if (nft[0].equalsIgnoreCase(contract) && nft[1].equals(tokenId)) return;
        }
        list.add(new String[]{contract, tokenId, name != null ? name : "", imageUrl != null ? imageUrl : ""});
        saveCustomNFTs(ctx, chain, walletAddress, list);
    }

    public static java.util.List<String[]> getCustomNFTs(Context ctx, String chain, String walletAddress) {
        java.util.List<String[]> list = new java.util.ArrayList<>();
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(CUSTOM_NFTS_PREFS, Context.MODE_PRIVATE);
            String key = chain + "_" + walletAddress.toLowerCase();
            String json = prefs.getString(key, "");
            if (!json.isEmpty()) {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONArray nftArr = arr.getJSONArray(i);
                    list.add(new String[]{
                        nftArr.optString(0, ""), // contract
                        nftArr.optString(1, ""), // tokenId
                        nftArr.optString(2, ""), // name
                        nftArr.optString(3, "")  // imageUrl
                    });
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    public static void removeCustomNFT(Context ctx, String chain, String walletAddress, String contract, String tokenId) {
        java.util.List<String[]> list = getCustomNFTs(ctx, chain, walletAddress);
        list.removeIf(nft -> nft[0].equalsIgnoreCase(contract) && nft[1].equals(tokenId));
        saveCustomNFTs(ctx, chain, walletAddress, list);
    }

    private static void saveCustomNFTs(Context ctx, String chain, String walletAddress, java.util.List<String[]> list) {
        JSONArray arr = new JSONArray();
        for (String[] nft : list) {
            JSONArray nftArr = new JSONArray();
            nftArr.put(nft[0]);
            nftArr.put(nft[1]);
            nftArr.put(nft[2]);
            nftArr.put(nft[3]);
            arr.put(nftArr);
        }
        String key = chain + "_" + walletAddress.toLowerCase();
        ctx.getSharedPreferences(CUSTOM_NFTS_PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, arr.toString()).apply();
    }

    /**
     * 查询 ERC721 tokenURI 获取 NFT 元数据（名称/图片）
     */
    public static String[] getNFTMetadata(String rpcUrl, String contract, String tokenId) throws Exception {
        String data = "0xc87b56dd0000000000000000000000000000000000000000000000000000000000000000";
        // tokenId 可能为十进制或十六进制，按十六进制编码
        try {
            java.math.BigInteger tid = new java.math.BigInteger(tokenId);
            String hex = tid.toString(16);
            // 补齐 64 位
            while (hex.length() < 64) hex = "0" + hex;
            data = "0xc87b56dd" + hex;
        } catch (Exception e) {
            throw new Exception("无效的 TokenID");
        }

        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("method", "eth_call");
        JSONArray params = new JSONArray();
        JSONObject callObj = new JSONObject();
        callObj.put("to", contract);
        callObj.put("data", data);
        params.put(callObj);
        params.put("latest");
        body.put("params", params);
        body.put("id", 1);

        Request request = new Request.Builder()
            .url(rpcUrl)
            .post(RequestBody.create(body.toString(), JSON_TYPE))
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            if (json.has("error")) throw new Exception(json.getJSONObject("error").optString("message", "RPC error"));
            String result = json.getString("result");
            if (result == null || result.equals("0x")) throw new Exception("无 tokenURI 数据");
            // 解码 hex 字符串
            String uriHex = result.substring(2);
            StringBuilder uri = new StringBuilder();
            for (int i = 0; i < uriHex.length(); i += 2) {
                String pair = uriHex.substring(i, i + 2);
                if (!pair.equals("00")) {
                    uri.append((char) Integer.parseInt(pair, 16));
                }
            }
            String uriStr = uri.toString();
            // 如果 URI 是 data:application/json 则直接解析
            String name = "", image = "";
            if (uriStr.startsWith("data:application/json")) {
                String b64 = uriStr.contains("base64,") ? uriStr.substring(uriStr.indexOf("base64,") + 7) : uriStr.substring(uriStr.indexOf(",") + 1);
                try {
                    byte[] decoded = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                    JSONObject meta = new JSONObject(new String(decoded, "UTF-8"));
                    name = meta.optString("name", "");
                    image = meta.optString("image", "");
                } catch (Exception ignored) {}
            } else if (uriStr.startsWith("http://") || uriStr.startsWith("https://")) {
                // 尝试从 HTTP 获取元数据
                try {
                    Request metaReq = new Request.Builder().url(uriStr).header("User-Agent", "Mozilla/5.0").get().build();
                    try (Response metaResp = client.newCall(metaReq).execute()) {
                        String metaBody = metaResp.body() != null ? metaResp.body().string() : "";
                        JSONObject meta = new JSONObject(metaBody);
                        name = meta.optString("name", "");
                        image = meta.optString("image", "");
                    }
                } catch (Exception ignored) {}
            }
            return new String[]{name, image, uriStr};
        }
    }

    /**
     * 查询 ERC721 ownerOf 验证钱包是否拥有该 NFT
     */
    public static boolean checkNFTOwnership(String rpcUrl, String contract, String tokenId, String walletAddress) {
        try {
            java.math.BigInteger tid = new java.math.BigInteger(tokenId);
            String hex = tid.toString(16);
            while (hex.length() < 64) hex = "0" + hex;
            String data = "0x6352211e" + hex; // ownerOf(bytes32 tokenId)

            JSONObject body = new JSONObject();
            body.put("jsonrpc", "2.0");
            body.put("method", "eth_call");
            JSONArray params = new JSONArray();
            JSONObject callObj = new JSONObject();
            callObj.put("to", contract);
            callObj.put("data", data);
            params.put(callObj);
            params.put("latest");
            body.put("params", params);
            body.put("id", 1);

            Request request = new Request.Builder()
                .url(rpcUrl)
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

            try (Response response = client.newCall(request).execute()) {
                String resp = response.body() != null ? response.body().string() : "";
                JSONObject json = new JSONObject(resp);
                if (json.has("error")) return false;
                String result = json.getString("result");
                if (result == null || result.equals("0x")) return false;
                // 解码地址
                String addrHex = "0x" + result.substring(26);
                return addrHex.equalsIgnoreCase(walletAddress);
            }
        } catch (Exception e) {
            return false;
        }
    }

    public static String formatAmount(double amount) {
        if (amount == 0) return "0";
        if (amount < 0.0001) return String.format("%.8f", amount);
        if (amount < 1) return String.format("%.6f", amount);
        if (amount < 1000) return String.format("%.4f", amount);
        if (amount < 1000000) return String.format("%.2f", amount);
        return String.format("%.0f", amount);
    }

    /**
     * 格式化法币金额（USD，仅用于日志/内部计算兼容）
     */
    public static String formatValue(double value) {
        if (value == 0) return "$0.00";
        if (value < 0.01) return "$0.00";
        return "$" + String.format("%.2f", value);
    }

    /**
     * 格式化法币金额（跟随用户选中的货币单位）
     */
    public static String formatValue(Context ctx, double value) {
        return CurrencyManager.formatFiat(ctx, value);
    }

    private static final int TX_CACHE_VERSION = 6;

    /**
     * 加载交易记录缓存（供 HomeActivity / TokenDetailActivity 共用）
     * 缓存键：tx_cache_v{ver}_{chain}_{address}_{contract or "all"}
     */
    public static java.util.List<String[]> loadTxCache(Context ctx, String chain, String address, String contractAddress) {
        java.util.List<String[]> list = new java.util.ArrayList<>();
        try {
            if (address == null || address.isEmpty()) return list;
            String cacheKeyBase = "tx_cache_v" + TX_CACHE_VERSION + "_" + chain + "_" + address.toLowerCase() + "_"
                + (contractAddress == null || contractAddress.isEmpty() ? "all" : contractAddress.toLowerCase());
            android.content.SharedPreferences prefs = ctx.getSharedPreferences("tx_cache_prefs", Context.MODE_PRIVATE);

            String cachedVersion = prefs.getString("cache_version", "0");
            if (!String.valueOf(TX_CACHE_VERSION).equals(cachedVersion)) {
                Logger.info(ctx, "交易记录", "缓存版本不匹配(" + cachedVersion + "->" + TX_CACHE_VERSION + ")，清除旧缓存");
                prefs.edit().clear().putString("cache_version", String.valueOf(TX_CACHE_VERSION)).apply();
                return list;
            }

            String json = prefs.getString(cacheKeyBase, "");
            if (json.isEmpty()) return list;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONArray txArr = arr.getJSONArray(i);
                String[] tx = new String[TxRecord.FIELD_COUNT];
                for (int j = 0; j < txArr.length() && j < TxRecord.FIELD_COUNT; j++) {
                    tx[j] = txArr.getString(j);
                }
                for (int j = txArr.length(); j < TxRecord.FIELD_COUNT; j++) {
                    tx[j] = "";
                }
                // 过滤掉金额明显错误的记录（时间戳/区块号等超大数字）
                if (tx[TxRecord.INDEX_AMOUNT] != null && !tx[TxRecord.INDEX_AMOUNT].isEmpty() && !tx[TxRecord.INDEX_AMOUNT].startsWith("--")) {
                    if (!isReasonableAmount(tx[TxRecord.INDEX_AMOUNT].replaceAll("^[+-]", "").split(" ")[0])) {
                        tx[TxRecord.INDEX_AMOUNT] = "";
                    }
                }
                list.add(tx);
            }
        } catch (Exception e) {
            Logger.error(ctx, "交易记录", "加载缓存失败: " + e.getMessage(), e);
        }
        return list;
    }

    /**
     * 保存交易记录到缓存（供 HomeActivity / TokenDetailActivity 共用）
     */
    public static void saveTxCache(Context ctx, String chain, String address, String contractAddress, java.util.List<String[]> txs) {
        try {
            if (address == null || address.isEmpty()) return;
            String cacheKeyBase = "tx_cache_v" + TX_CACHE_VERSION + "_" + chain + "_" + address.toLowerCase() + "_"
                + (contractAddress == null || contractAddress.isEmpty() ? "all" : contractAddress.toLowerCase());
            JSONArray arr = new JSONArray();
            for (String[] tx : txs) {
                JSONArray txArr = new JSONArray();
                for (String s : tx) txArr.put(s == null ? "" : s);
                arr.put(txArr);
            }
            ctx.getSharedPreferences("tx_cache_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("cache_version", String.valueOf(TX_CACHE_VERSION))
                .putString(cacheKeyBase, arr.toString())
                .apply();
            Logger.info(ctx, "交易记录", "已缓存 " + txs.size() + " 条");
        } catch (Exception e) {
            Logger.error(ctx, "交易记录", "保存缓存失败: " + e.getMessage(), e);
        }
    }

    /**
     * 扫描钱包的所有 Transfer 事件（不限合约地址）
     * 解决代币不在缓存列表里导致漏查的问题（如新转入的未知代币）
     * 只查 to=wallet 方向（转入），跳过已在 existingTxs 中的交易
     */
    private static java.util.List<String[]> fetchAllTransferEvents(Context ctx, String chain, String address, java.util.List<String[]> existingTxs) {
        java.util.List<String[]> newTxs = new java.util.ArrayList<>();
        if (!isEVMChain(chain)) return newTxs;

        // 收集已有的交易 hash，避免重复
        java.util.Set<String> existingHashes = new java.util.HashSet<>();
        for (String[] tx : existingTxs) {
            if (tx.length > 0 && tx[0] != null) existingHashes.add(tx[0]);
        }

        String transferTopic = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
        String walletPadded = "0x000000000000000000000000" + address.toLowerCase().replace("0x", "");

        // 构造候选节点列表：优先非AVE节点（AVE不支持eth_getLogs）
        java.util.List<String> candidateUrls = new java.util.ArrayList<>();
        String savedRpc = WalletManager.getRpcUrl(ctx, chain);
        for (NodeManager.NodeEntry node : NodeManager.getPresets(chain)) {
            if (node.url != null && !node.url.contains("sendFastSwapTx") && !candidateUrls.contains(node.url)) candidateUrls.add(node.url);
        }
        for (NodeManager.NodeEntry node : NodeManager.getPresets(chain)) {
            if (node.url != null && node.url.contains("sendFastSwapTx") && !candidateUrls.contains(node.url)) candidateUrls.add(node.url);
        }
        if (savedRpc != null && !savedRpc.isEmpty() && !savedRpc.contains("sendFastSwapTx")) {
            candidateUrls.remove(savedRpc);
            candidateUrls.add(0, savedRpc);
        }

        // 查询最新区块高度
        String currentRpc = null;
        long latestBlock = 0;
        for (String rpcUrl : candidateUrls) {
            try {
                JSONObject body = new JSONObject();
                body.put("jsonrpc", "2.0");
                body.put("id", 1);
                body.put("method", "eth_blockNumber");
                body.put("params", new JSONArray());
                Request request = new Request.Builder()
                    .url(rpcUrl)
                    .post(RequestBody.create(body.toString(), JSON_TYPE))
                    .build();
                try (Response response = client.newCall(request).execute()) {
                    String resp = response.body() != null ? response.body().string() : "";
                    JSONObject json = new JSONObject(resp);
                    if (json.has("result")) {
                        latestBlock = new java.math.BigInteger(json.getString("result").substring(2), 16).longValue();
                        currentRpc = rpcUrl;
                        break;
                    }
                }
            } catch (Exception e) {
                Logger.warning(ctx, "交易历史", "节点 " + rpcUrl + " 获取区块号失败: " + e.getMessage());
            }
        }
        if (currentRpc == null || latestBlock == 0) return newTxs;

        long fromBlock = Math.max(0, latestBlock - 500000);
        int chunkSize = 5000;
        int consecutiveEmpty = 0;
        int totalFound = 0;

        // 缓存已查询过 symbol/decimals 的合约
        java.util.Map<String, String> symbolCache = new java.util.HashMap<>();
        java.util.Map<String, Integer> decimalsCache = new java.util.HashMap<>();

        Logger.info(ctx, "交易历史", "开始全量扫描钱包 Transfer 事件，范围 " + fromBlock + " - " + latestBlock);

        for (long end = latestBlock; end > fromBlock; end -= chunkSize) {
            long start = Math.max(fromBlock, end - chunkSize + 1);
            try {
                JSONObject filter = new JSONObject();
                filter.put("fromBlock", "0x" + Long.toHexString(start));
                filter.put("toBlock", "0x" + Long.toHexString(end));
                JSONArray topics = new JSONArray();
                topics.put(transferTopic);
                topics.put(JSONObject.NULL);
                topics.put(walletPadded); // to=wallet（转入方向）
                filter.put("topics", topics);

                JSONArray params = new JSONArray();
                params.put(filter);

                JSONObject body = new JSONObject();
                body.put("jsonrpc", "2.0");
                body.put("id", 1);
                body.put("method", "eth_getLogs");
                body.put("params", params);
                Request request = new Request.Builder()
                    .url(currentRpc)
                    .post(RequestBody.create(body.toString(), JSON_TYPE))
                    .build();
                String resp;
                try (Response response = client.newCall(request).execute()) {
                    resp = response.body() != null ? response.body().string() : "";
                }
                JSONObject json = new JSONObject(resp);
                if (json.has("error")) {
                    // chunkSize 降级重试
                    if (chunkSize > 1250) {
                        chunkSize = chunkSize / 2;
                        end += chunkSize * 2; // 重新查这一段
                    }
                    continue;
                }
                JSONArray logs = json.optJSONArray("result");
                if (logs == null || logs.length() == 0) {
                    consecutiveEmpty++;
                    // AVE代理节点不支持eth_getLogs，3个chunk返回0条就跳到下一个节点
                    if (currentRpc.contains("sendFastSwapTx") && consecutiveEmpty >= 3) {
                        Logger.warning(ctx, "交易历史", "AVE代理节点不支持eth_getLogs，跳过");
                        // 尝试切换到下一个候选节点
                        boolean switched = false;
                        for (String nextRpc : candidateUrls) {
                            if (!nextRpc.equals(currentRpc) && !nextRpc.contains("sendFastSwapTx")) {
                                long nextBlock = getCurrentBlockNumber(nextRpc);
                                if (nextBlock > 0) {
                                    currentRpc = nextRpc;
                                    latestBlock = nextBlock;
                                    fromBlock = Math.max(0, latestBlock - 500000);
                                    end = latestBlock;
                                    start = Math.max(fromBlock, end - chunkSize + 1);
                                    consecutiveEmpty = 0;
                                    totalFound = 0;
                                    switched = true;
                                    Logger.info(ctx, "交易历史", "切换到节点: " + nextRpc);
                                    break;
                                }
                            }
                        }
                        if (!switched) break;
                        continue;
                    }
                    if (totalFound > 0 && consecutiveEmpty >= 5) break;
                    if (totalFound == 0 && consecutiveEmpty >= 100) break;
                    continue;
                }

                consecutiveEmpty = 0;
                for (int i = 0; i < logs.length(); i++) {
                    JSONObject log = logs.getJSONObject(i);
                    String hash = log.optString("transactionHash", "");
                    if (existingHashes.contains(hash)) continue; // 跳过已查到的交易

                    String contract = log.optString("address", "").toLowerCase();
                    // 查询或缓存 symbol/decimals
                    String sym = symbolCache.get(contract);
                    if (sym == null) {
                        sym = "";
                        try {
                            String decoded = callContractMethod(currentRpc, contract, "0x95d89b41");
                            if (decoded != null && !decoded.isEmpty()) sym = decoded;
                        } catch (Exception e) {}
                        symbolCache.put(contract, sym);
                    }
                    Integer dec = decimalsCache.get(contract);
                    if (dec == null) {
                        dec = getTokenDecimals(currentRpc, contract);
                        decimalsCache.put(contract, dec);
                    }

                    String[] tx = parseTransferLog(log, address, sym, dec, contract);
                    if (tx != null) {
                        newTxs.add(tx);
                        existingHashes.add(hash);
                        totalFound++;
                    }
                }
            } catch (Exception e) {
                // 单块失败不阻断
            }
        }

        Logger.info(ctx, "交易历史", "全量扫描完成，新发现 " + totalFound + " 条未知代币交易");
        return newTxs;
    }

    /**
     * Get transaction history for a wallet address
     * Returns: [hash, from, to, value, timestamp, status, type]
     */
    public static java.util.List<String[]> getTransactionHistory(Context ctx, String chain, String address, String contractAddress) throws Exception {
        return getTransactionHistory(ctx, chain, address, contractAddress, 1);
    }

    /**
     * 获取交易历史（支持分页）
     * @param page 页码，从1开始（BscScan每页约25条）
     */
    public static java.util.List<String[]> getTransactionHistory(Context ctx, String chain, String address, String contractAddress, int page) throws Exception {
        java.util.List<String[]> txs = new java.util.ArrayList<>();

        // EVM 链：三层路径查询
        //  路径0 - HTML抓取（秒级返回）：
        //    /txs 页面 → 原生BNB交易（分页 &p=N）
        //    /tokentxns 页面 → 代币交易（分页 &p=N）
        //  路径1 - EVM 代币交易（eth_getLogs）：仅第1页时执行，后续页跳过（公共节点限制多）
        //  路径2 - 原生币交易（RPC兜底）：仅路径0失败时执行
        //
        // 关键设计：
        //  - 公共BSC节点几乎都限制eth_getLogs（limit exceeded / 要求付费token）
        //  - 代币交易主要通过路径0的/tokentxns HTML抓取获取（和/txs一样OkHttp直连）
        //  - 路径1的eth_getLogs仅作补充，能查到多少算多少
        if (isEVMChain(chain)) {
            // ===== 路径0：HTML抓取 /txs + /tokentxns =====
            boolean path0NativeSuccess = false;
            boolean path0TokenSuccess = false;
            java.util.Set<String> existingHashes = new java.util.HashSet<>();
            String addrLower = address.toLowerCase();
            boolean hasContract = contractAddress != null && !contractAddress.isEmpty();

            // 0a. 抓取/txs页面（原生BNB交易）— 仅查看全部交易时
            if (!hasContract) {
                String txsPageUrl = getExplorerTxsUrl(chain, address);
                if (page > 1 && txsPageUrl != null) txsPageUrl += "&p=" + page;
                if (txsPageUrl != null) {
                    try {
                        Logger.info(ctx, "交易历史", "路径0 抓取原生交易: " + txsPageUrl);
                        String html = fetchWithIpDirectFallback(ctx, txsPageUrl, chain);
                        if (html != null && !html.isEmpty() && html.contains("0x")) {
                            int htmlFound = parseTxsFromHtml(html, addrLower, chain, txs);
                            if (htmlFound > 0) {
                                path0NativeSuccess = true;
                                for (String[] tx : txs) {
                                    if (tx.length > 0 && tx[0] != null) existingHashes.add(tx[0]);
                                }
                                Logger.success(ctx, "交易历史", "路径0 原生交易 " + htmlFound + " 条");
                            }
                        }
                    } catch (Exception e) {
                        Logger.warning(ctx, "交易历史", "路径0 原生交易异常: " + e.getMessage());
                    }
                }
            }

            // 0b. 抓取代币交易页面
            //  !hasContract(首页): 抓取/tokentxns显示全部代币交易（主方案）
            //  hasContract(代币详情): 跳过，优先用eth_getLogs(路径1)直接查该合约
            //                         路径1失败后再走路径1b HTML兜底
            if (!hasContract) {
            String tokenTxsUrl = getExplorerTokenTxsUrl(chain, address);
            if (page > 1 && tokenTxsUrl != null) tokenTxsUrl += "&p=" + page;
            if (tokenTxsUrl != null) {
                try {
                    Logger.info(ctx, "交易历史", "路径0 抓取代币交易: " + tokenTxsUrl);
                    String tokenHtml = fetchWithIpDirectFallback(ctx, tokenTxsUrl, chain);
                    if (tokenHtml != null && !tokenHtml.isEmpty() && tokenHtml.contains("0x")) {
                        java.util.List<String[]> tokenTxs = new java.util.ArrayList<>();
                        int tokenFound = parseTokenTxsFromHtml(ctx, tokenHtml, addrLower, chain, tokenTxs, existingHashes);
                        Logger.info(ctx, "交易历史", "路径0 解析到代币交易 " + tokenFound + " 条");

                        if (!tokenTxs.isEmpty()) {
                            for (String[] tx : tokenTxs) {
                                if (tx.length > 0 && tx[0] != null) existingHashes.add(tx[0]);
                            }
                            txs.addAll(tokenTxs);
                            path0TokenSuccess = true;
                            Logger.success(ctx, "交易历史", "路径0 代币交易 " + tokenTxs.size() + " 条");
                        }
                    }
                } catch (Exception e) {
                    Logger.warning(ctx, "交易历史", "路径0 代币交易异常: " + e.getMessage());
                }
            }
            }

            // ===== 路径1：EVM 代币交易（eth_getLogs + HTML抓取双路并行）=====
            // 特定代币(hasContract): HTML抓取优先（区块浏览器，无需注册），eth_getLogs作为补充
            // 全部代币(!hasContract): 补充查询 - 路径0已获取则跳过
            try {
            if (hasContract ? page == 1 : (!path0TokenSuccess && page == 1)) {
            if (hasContract) {
                // 查看特定代币：HTML抓取优先（最可靠），eth_getLogs作为补充
                java.util.Set<String> contractTxHashes = new java.util.HashSet<>();
                boolean htmlWorked = false;

                // 优先：HTML抓取 /tokentxns?contract=CA（与已验证的解析器兼容，无需任何API key）
                String tokenPageUrl = getExplorerTokenTxsUrl(chain, address, contractAddress);
                if (page > 1 && tokenPageUrl != null) tokenPageUrl += "&p=" + page;
                if (tokenPageUrl != null) {
                    try {
                        Logger.info(ctx, "交易历史", "HTML优先抓取: " + tokenPageUrl);
                        String tokenHtml = fetchWithIpDirectFallback(ctx, tokenPageUrl, chain);
                        if (tokenHtml != null && !tokenHtml.isEmpty() && tokenHtml.contains("0x")) {
                            java.util.List<String[]> htmlTxs = new java.util.ArrayList<>();
                            int htmlFound = parseTokenTxsFromHtml(ctx, tokenHtml, addrLower, chain, htmlTxs, existingHashes, contractAddress);
                            Logger.info(ctx, "交易历史", "HTML抓取解析到 " + htmlFound + " 条代币交易");
                            if (!htmlTxs.isEmpty()) {
                                for (String[] tx : htmlTxs) {
                                    if (tx.length > 0 && tx[0] != null) {
                                        contractTxHashes.add(tx[0]);
                                        existingHashes.add(tx[0]);
                                    }
                                }
                                txs.addAll(htmlTxs);
                                htmlWorked = true;
                                Logger.success(ctx, "交易历史", "HTML抓取 代币交易 " + htmlTxs.size() + " 条");
                            }
                        }
                    } catch (Exception e) {
                        Logger.warning(ctx, "交易历史", "HTML抓取异常: " + e.getMessage());
                    }
                }

                // 补充：eth_getLogs 尝试（获得HTML可能遗漏的交易）
                try {
                    java.util.Map<String, String> symMap = new java.util.HashMap<>();
                    String caLower = contractAddress.toLowerCase();
                    try {
                        DataCache cache = new DataCache(ctx);
                        for (String[] token : cache.getCachedTokens()) {
                            if (token.length > 4 && token[4] != null && token[4].equalsIgnoreCase(caLower)) {
                                symMap.put(caLower, token.length > 0 ? token[0] : "");
                                break;
                            }
                        }
                    } catch (Exception e) {}

                    Logger.info(ctx, "交易历史", "eth_getLogs补充查询: " + contractAddress);
                    java.util.List<String[]> rpcTxs = fetchTokenTransferEvents(ctx, chain, address, contractAddress, symMap);
                    if (rpcTxs != null && !rpcTxs.isEmpty()) {
                        int rpcAdded = 0;
                        for (String[] tx : rpcTxs) {
                            if (tx.length > 0 && tx[0] != null && !contractTxHashes.contains(tx[0])) {
                                txs.add(tx);
                                rpcAdded++;
                            }
                        }
                        Logger.success(ctx, "交易历史", "eth_getLogs补充 " + rpcAdded + " 条新交易");
                    }
                } catch (Exception e) {
                    Logger.warning(ctx, "交易历史", "eth_getLogs补充查询异常: " + e.getMessage());
                }

                if (!htmlWorked && txs.isEmpty()) {
                    Logger.warning(ctx, "交易历史", "所有方法均未获取到代币交易");
                }
            } else {
                    // 查看全部交易：批量查询所有持有代币的Transfer事件
                    java.util.List<String> contracts = new java.util.ArrayList<>();
                    try {
                        DataCache cache = new DataCache(ctx);
                        java.util.List<String[]> cachedTokens = cache.getCachedTokens();
                        if (cachedTokens != null) {
                            for (String[] token : cachedTokens) {
                                if (token.length > 4 && token[4] != null && !token[4].isEmpty()) {
                                    String c = token[4].toLowerCase();
                                    if (!contracts.contains(c)) contracts.add(c);
                                }
                            }
                        }
                    } catch (Exception e) {}
                    Logger.info(ctx, "交易历史", "路径1 查询 " + contracts.size() + " 个代币");

                    if (contracts.isEmpty()) {
                        txs.addAll(fetchEvmTransactionHistoryViaRPC(ctx, chain, address, contractAddress));
                    } else {
                        String wrappedNative = getWrappedNativeContract(chain);
                        if (wrappedNative != null && !contracts.contains(wrappedNative.toLowerCase())) {
                            contracts.add(wrappedNative.toLowerCase());
                        }
                        java.util.List<String[]> batchTxs = fetchEvmTransactionHistoryBatch(ctx, chain, address, contracts);
                        txs.addAll(batchTxs);
                    }
                    java.util.List<String[]> allTxs = fetchAllTransferEvents(ctx, chain, address, txs);
                    txs.addAll(allTxs);
                    Logger.info(ctx, "交易历史", "路径1 代币交易 " + txs.size() + " 条");
                }
            } else {
                Logger.info(ctx, "交易历史", "路径0 已获取代币交易，跳过路径1（eth_getLogs）");
            }
            } catch (Exception e) {
                Logger.warning(ctx, "交易历史", "路径1 代币交易查询异常: " + e.getMessage());
            }

            // ===== 路径2：原生币交易（RPC兜底）=====
            // 仅在路径0未获取到原生交易且第1页时执行
            try {
                if (!hasContract && !path0NativeSuccess && page == 1) {
                    Logger.info(ctx, "交易历史", "路径0 未获取原生交易，执行 RPC 兜底");
                    java.util.Set<String> erc20Hashes = new java.util.HashSet<>();
                    for (String[] tx : txs) {
                        if (tx.length > 0 && tx[0] != null && !tx[0].isEmpty()) {
                            erc20Hashes.add(tx[0]);
                        }
                    }
                    java.util.List<String[]> nativeTxs = fetchEvmNativeTxViaEtherscanV2(ctx, chain, address, erc20Hashes);
                    txs.addAll(nativeTxs);
                    Logger.info(ctx, "交易历史", "路径2 原生币交易(RPC兜底) " + nativeTxs.size() + " 条");
                }
            } catch (Exception e) {
                Logger.warning(ctx, "交易历史", "路径2 原生币交易查询异常: " + e.getMessage());
            }

            // 合并后按时间倒序排序
            java.util.Collections.sort(txs, (a, b) -> {
                try {
                    return b[4].compareTo(a[4]);
                } catch (Exception e) {
                    return 0;
                }
            });
            // 记录最终返回的交易列表详情
            Logger.info(ctx, "交易历史", "最终返回 " + txs.size() + " 条交易");
            for (int i = 0; i < Math.min(txs.size(), 10); i++) {
                String[] tx = txs.get(i);
                String sym = tx.length > 7 ? tx[7] : "";
                String contract = tx.length > 8 ? tx[8] : "";
                Logger.info(ctx, "交易历史", String.format("Tx[%d] hash=%s symbol=%s value=%s from=%s to=%s time=%s type=%s contract=%s",
                    i, tx[0].substring(0, 8), sym, tx[3],
                    tx[1].isEmpty() ? "N/A" : tx[1].substring(0, 8),
                    tx[2].isEmpty() ? "N/A" : tx[2].substring(0, 8),
                    tx[4], tx[6], contract.isEmpty() ? "N/A" : contract.substring(0, 8)));
            }
            return txs;
        } else if ("SOL".equals(chain)) {
            // Solana transaction history via public API
            String rpcUrl = WalletManager.getRpcUrl(ctx, chain);
            JSONObject body = new JSONObject();
            body.put("jsonrpc", "2.0");
            body.put("id", 1);
            body.put("method", "getSignaturesForAddress");
            JSONArray params = new JSONArray();
            params.put(address);
            JSONObject opts = new JSONObject();
            opts.put("limit", 50);
            params.put(opts);
            body.put("params", params);

            Request request = new Request.Builder()
                .url(rpcUrl)
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();
            try (Response response = client.newCall(request).execute()) {
                String resp = response.body() != null ? response.body().string() : "";
                JSONObject json = new JSONObject(resp);
                JSONArray results = json.optJSONArray("result");
                if (results != null) {
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject sig = results.getJSONObject(i);
                        String hash = sig.optString("signature", "");
                        long slot = sig.optLong("slot", 0);
                        boolean err = sig.isNull("err") || "null".equals(String.valueOf(sig.opt("err")));
                        String timeStr = "";
                        try {
                            long ts = sig.optLong("blockTime", 0) * 1000;
                            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault());
                            timeStr = sdf.format(new java.util.Date(ts));
                        } catch (Exception e) {}
                        String[] txRow = new String[TxRecord.FIELD_COUNT];
                        txRow[TxRecord.INDEX_HASH] = hash;
                        txRow[TxRecord.INDEX_FROM] = address;
                        txRow[TxRecord.INDEX_TO] = "";
                        txRow[TxRecord.INDEX_AMOUNT] = "0";
                        txRow[TxRecord.INDEX_TIME] = timeStr;
                        txRow[TxRecord.INDEX_STATUS] = err ? "success" : "failed";
                        txRow[TxRecord.INDEX_TYPE] = "transfer";
                        txRow[TxRecord.INDEX_SYMBOL] = "";
                        txRow[TxRecord.INDEX_CONTRACT] = "";
                        txs.add(txRow);
                    }
                }
            }
        } else if ("TRX".equals(chain)) {
            // TRON via tronscan API
            String url = "https://apilist.tronscanapi.com/api/transaction?sort=-timestamp&count=true&limit=50&start=0&address=" + address;
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = client.newCall(request).execute()) {
                String resp = response.body() != null ? response.body().string() : "";
                JSONObject json = new JSONObject(resp);
                JSONArray results = json.optJSONArray("data");
                if (results != null) {
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject tx = results.getJSONObject(i);
                        String hash = tx.optString("hash", "");
                        String from = tx.optString("from_address", "");
                        String to = tx.optString("to_address", "");
                        long amount = tx.optLong("amount", 0);
                        double val = amount / 1000000.0;
                        long ts = tx.optLong("timestamp", 0);
                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault());
                        String timeStr = sdf.format(new java.util.Date(ts));
                        String status = tx.optInt("result", 0) == 0 ? "success" : "failed";
                        String[] txRow = new String[TxRecord.FIELD_COUNT];
                        txRow[TxRecord.INDEX_HASH] = hash;
                        txRow[TxRecord.INDEX_FROM] = from;
                        txRow[TxRecord.INDEX_TO] = to;
                        txRow[TxRecord.INDEX_AMOUNT] = formatAmount(val);
                        txRow[TxRecord.INDEX_TIME] = timeStr;
                        txRow[TxRecord.INDEX_STATUS] = status;
                        txRow[TxRecord.INDEX_TYPE] = "transfer";
                        txRow[TxRecord.INDEX_SYMBOL] = "";
                        txRow[TxRecord.INDEX_CONTRACT] = "";
                        txs.add(txRow);
                    }
                }
            }
        }

        return txs;
    }

    /**
     * Get token info (symbol, name, decimals) from contract address via RPC
     */
    public static String[] getTokenInfo(Context ctx, String chain, String contractAddress) throws Exception {
        if (!isEVMChain(chain)) return null;

        // 主节点 + 该链全部预设节点，主节点限流/失败时自动切换备用节点重试
        java.util.List<String> nodeList = new java.util.ArrayList<>();
        String primary = WalletManager.getRpcUrl(ctx, chain);
        if (primary != null && !primary.isEmpty() && !nodeList.contains(primary)) nodeList.add(primary);
        for (NodeManager.NodeEntry entry : NodeManager.getPresets(chain)) {
            if (entry.url != null && !entry.url.isEmpty() && !nodeList.contains(entry.url)) {
                nodeList.add(entry.url);
            }
        }
        // 自定义测试网：追加该链专属回退节点（如 BSC 测试网），保证主节点失败后可切换
        String[] customFallbacks = getCustomChainFallbackNodes(chain);
        if (customFallbacks != null) {
            for (String u : customFallbacks) {
                if (u != null && !u.isEmpty() && !nodeList.contains(u)) nodeList.add(u);
            }
        }
        Logger.info(ctx, "代币识别", "识别链=" + chain + " 主节点=" + primary + " 备用节点数=" + nodeList.size());

        Exception lastEx = null;
        for (String rpcUrl : nodeList) {
            try {
                // Call name()
                String name = callContractMethod(rpcUrl, contractAddress, "0x06fdde03");
                // Call symbol()
                String symbol = callContractMethod(rpcUrl, contractAddress, "0x95d89b41");
                // Call decimals()
                String decimalsHex = callContractMethod(rpcUrl, contractAddress, "0x313ce567");

                if (name == null || symbol == null) {
                    // 该节点未取到 symbol/name，记为失败继续尝试下一节点
                    Logger.warning(ctx, "代币识别", "节点 " + rpcUrl + " 未返回 symbol/name（可能限流或该合约无 name()/symbol()）");
                    lastEx = new Exception("节点未返回 symbol/name: " + rpcUrl);
                    continue;
                }

                // Parse decimals
                // 修复：之前 Integer.parseInt(decimalsHex, 16) 未去掉 "0x" 前缀
                // Integer.parseInt 不接受 "0x" 前缀（只有 Integer.decode 接受），直接抛 NumberFormatException
                // 被 catch 后回退到默认 18，导致所有自动读取精度的代币都被错误存为 18
                // 后续 getERC20Balance 用错误精度计算，USDT(6)/USDC(6)/WBTC(8) 等余额显示放大 10^12 倍
                int decimals = 18;
                try {
                    if (decimalsHex != null && decimalsHex.length() >= 4 && decimalsHex.startsWith("0x")) {
                        decimals = new java.math.BigInteger(decimalsHex.substring(2), 16).intValue();
                    } else if (decimalsHex != null && decimalsHex.length() >= 2) {
                        decimals = new java.math.BigInteger(decimalsHex, 16).intValue();
                    }
                } catch (Exception e) {}

                return new String[]{symbol, name, String.valueOf(decimals)};
            } catch (Exception e) {
                Logger.warning(ctx, "代币识别", "节点 " + rpcUrl + " 异常: " + e.getMessage());
                lastEx = e;
                // 连接/限流异常，继续尝试下一节点
            }
        }
        if (lastEx != null) throw lastEx;
        return null;
    }

    public static String callContractMethod(String rpcUrl, String contract, String data) throws Exception {
        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "eth_call");
        JSONArray params = new JSONArray();
        JSONObject callObj = new JSONObject();
        callObj.put("to", contract);
        callObj.put("data", data);
        params.put(callObj);
        params.put("latest");
        body.put("params", params);

        // 用 client（5/8秒超时）— batchClient 复用 stale 连接导致 SSLHandshakeException
        // symbol/decimals 查询是简单 eth_call，AVE 代理节点响应快，不需要长超时
        Request request = new Request.Builder()
            .url(rpcUrl)
            .post(RequestBody.create(body.toString(), JSON_TYPE))
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            String result = json.optString("result", "");
            if (result.isEmpty() || "0x".equals(result)) return null;

            // Decode string result (ABI encoded)
            if (data.equals("0x06fdde03") || data.equals("0x95d89b41")) {
                return decodeAbiString(result);
            }
            return result;
        }
    }

    private static String decodeAbiString(String hex) {
        try {
            if (hex.startsWith("0x")) hex = hex.substring(2);
            if (hex.length() < 64) return null;
            // ABI 编码动态字符串：前 32 字节 = 偏移量（不是长度！）
            int offset = Integer.parseInt(hex.substring(0, 64), 16);
            int dataStart = offset * 2; // 转为字节偏移
            if (dataStart + 64 > hex.length()) return null;
            // 偏移量处 = 字符串长度（32 字节）
            int length = Integer.parseInt(hex.substring(dataStart, dataStart + 64), 16);
            if (length <= 0 || length > 1000) return null;
            // 长度后面 = 字符串数据（按字节读取后用 UTF-8 解码，支持中文等非 ASCII 字符）
            int dataBytesStart = dataStart + 64;
            if (dataBytesStart + length * 2 > hex.length()) {
                length = (hex.length() - dataBytesStart) / 2;
            }
            byte[] bytes = new byte[length];
            for (int i = 0; i < length; i++) {
                int pos = dataBytesStart + i * 2;
                if (pos + 2 > hex.length()) break;
                bytes[i] = (byte) Integer.parseInt(hex.substring(pos, pos + 2), 16);
            }
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isEVMChain(String chain) {
        for (String[] cfg : CHAIN_CONFIG) {
            if (cfg[0].equals(chain)) return "true".equals(cfg[5]);
        }
        return false;
    }

    /** 从内置代币列表查找已知 decimals，找不到返回 -1 */
    private static int findKnownDecimals(String contract) {
        if (contract == null) return -1;
        String c = contract.toLowerCase();
        // 查 BSC 热门代币
        for (String[] t : BSC_POPULAR_TOKENS) {
            if (t[0].toLowerCase().equals(c)) {
                try { return Integer.parseInt(t[3]); } catch (Exception e) { return -1; }
            }
        }
        // 查 COMMON_TOKENS（包含 ETH/MATIC/ARB 等常用代币）
        for (String[] t : COMMON_TOKENS) {
            if (t[1].toLowerCase().equals(c)) {
                try { return Integer.parseInt(t[4]); } catch (Exception e) { return -1; }
            }
        }
        // 查 ETH 热门代币
        for (String[] t : ETH_POPULAR_TOKENS) {
            if (t[0].toLowerCase().equals(c)) {
                try { return Integer.parseInt(t[3]); } catch (Exception e) { return -1; }
            }
        }
        // 查 MATIC 热门代币
        for (String[] t : MATIC_POPULAR_TOKENS) {
            if (t[0].toLowerCase().equals(c)) {
                try { return Integer.parseInt(t[3]); } catch (Exception e) { return -1; }
            }
        }
        return -1;
    }

    /**
     * 返回某链的内置热门代币列表（来源：TP 钱包热门代币）
     * 每条格式：{contract, symbol, name, decimals}
     * 供"热门代币"管理界面展示与搜索使用
     */
    public static java.util.List<String[]> getPopularTokens(String chain) {
        java.util.List<String[]> result = new java.util.ArrayList<>();
        String[][] arr = null;
        if ("BNB".equals(chain)) arr = BSC_POPULAR_TOKENS;
        else if ("ETH".equals(chain)) arr = ETH_POPULAR_TOKENS;
        else if ("MATIC".equals(chain)) arr = MATIC_POPULAR_TOKENS;
        else if ("AVAX".equals(chain)) arr = AVAX_POPULAR_TOKENS;
        else if ("FTM".equals(chain)) arr = FTM_POPULAR_TOKENS;
        else if ("TRX".equals(chain)) arr = TRX_POPULAR_TOKENS;
        else if ("ONE".equals(chain)) arr = ONE_POPULAR_TOKENS;
        if (arr != null) {
            for (String[] t : arr) {
                if (t.length >= 4) {
                    result.add(new String[]{t[0], t[1], t[2], t[3]});
                }
            }
        }
        // 附加 COMMON_TOKENS 中属于该链的常用代币（{chain, contract, symbol, name, decimals}）
        for (String[] t : COMMON_TOKENS) {
            if (t.length >= 5 && chain.equals(t[0])) {
                String contract = t[1].toLowerCase();
                boolean dup = false;
                for (String[] e : result) {
                    if (e[0].equalsIgnoreCase(contract)) { dup = true; break; }
                }
                if (!dup) result.add(new String[]{t[1], t[2], t[3], t[4]});
            }
        }
        return result;
    }

    /** 查询代币精度（decimals），优先从合约本身获取，失败时查内置列表，最后默认 18 */
    public static int getTokenDecimals(String rpcUrl, String contract) {
        // 优先从合约本身获取 decimals（最准确）
        try {
            String decHex = callContractMethod(rpcUrl, contract, "0x313ce567");
            if (decHex != null && decHex.startsWith("0x") && decHex.length() >= 4) {
                int rpcDec = new java.math.BigInteger(decHex.substring(2), 16).intValue();
                if (rpcDec >= 0 && rpcDec <= 36) return rpcDec; // 合理范围 0-36
            }
        } catch (Exception e) {
            Logger.warning(null, "代币精度", "合约 " + contract + " decimals 查询失败: " + e.getMessage());
        }
        // RPC 失败，查内置列表作为备选
        int known = findKnownDecimals(contract);
        if (known >= 0) return known;
        // 最后默认 18
        return 18;
    }

    /**
     * EVM 交易历史 - 通过 JSON-RPC eth_getLogs 查询代币 Transfer 事件
     *
     * 设计目标：
     *  - 与余额查询使用完全相同的网络通道（同一个 RPC 节点 URL）
     *  - 不再依赖 Etherscan/BscScan 等 HTTP API（国内被 GFW 阻断）
     *  - 与 getEVMBalanceFallback 一致的多节点回退：当前节点失败时自动尝试其他预设节点
     *
     * 多节点回退策略（与余额查询一致）：
     *  1. 当前选中节点（WalletManager.getRpcUrl）
     *  2. 失败则遍历 NodeManager.getPresets(chain) 所有预设节点
     *  3. 找到第一个能成功响应 eth_getLogs 的节点，完成查询
     *
     * 查询策略：
     *  - 获取最新区块号
     *  - 往回查 500,000 个区块（BSC 3秒出块 ≈ 17天；ETH 12秒出块 ≈ 69天）
     *  - 分块查询，chunkSize=5,000（AVE 节点实测支持）
     *  - 找到 200 条就提前停止（避免无谓扫描）
     *  - 解析 Transfer 事件日志：from=topic[1], to=topic[2], value=data
     */
    private static java.util.List<String[]> fetchEvmTransactionHistoryViaRPC(
            Context ctx, String chain, String address, String contractAddress) {

        java.util.List<String[]> txs = new java.util.ArrayList<>();

        // 若未指定合约地址，用各链的"包装代币"作为默认查询合约
        // （WETH/WBNB 等的 Transfer 事件能覆盖大部分合约级转账）
        String actualContract = contractAddress;
        if (actualContract == null || actualContract.isEmpty()) {
            actualContract = getWrappedNativeContract(chain);
            if (actualContract == null) {
                Logger.info(ctx, "交易历史", chain + " 无包装代币，跳过原生币交易查询");
                return txs;
            }
        }

        // 查找代币 symbol 和 decimals（用于 UI 显示和金额精度计算）
        String txSymbol = "";
        int txDecimals = 18;
        String wrappedNative = getWrappedNativeContract(chain);
        if (wrappedNative != null && wrappedNative.equalsIgnoreCase(actualContract)) {
            // 包装原生币（WBNB→BNB, WETH→ETH）
            txSymbol = chain;
            txDecimals = 18;
        } else {
            // 从 DataCache 查找用户持有的代币信息
            try {
                DataCache cache = new DataCache(ctx);
                for (String[] token : cache.getCachedTokens()) {
                    if (token.length > 4 && token[4] != null
                        && token[4].equalsIgnoreCase(actualContract)) {
                        if (token.length > 0) txSymbol = token[0];
                        break;
                    }
                }
            } catch (Exception e) {}
            // decimals 通过 RPC 查询
            try {
                String rpcUrl0 = WalletManager.getRpcUrl(ctx, chain);
                txDecimals = getTokenDecimals(rpcUrl0, actualContract);
            } catch (Exception e) {}
        }

        // 构建候选节点列表：当前选中节点 + 所有预设节点（去重）
        java.util.List<String> candidateUrls = new java.util.ArrayList<>();
        String currentRpc = WalletManager.getRpcUrl(ctx, chain);
        if (currentRpc != null && !currentRpc.isEmpty()) candidateUrls.add(currentRpc);
        for (NodeManager.NodeEntry entry : NodeManager.getPresets(chain)) {
            if (!candidateUrls.contains(entry.url)) candidateUrls.add(entry.url);
        }
        Logger.info(ctx, "交易历史", chain + " 候选节点 " + candidateUrls.size() + " 个");

        // 遍历候选节点，第一个成功的就完成查询
        for (String rpcUrl : candidateUrls) {
            if (rpcUrl == null || rpcUrl.isEmpty()) continue;
            try {
                Logger.info(ctx, "交易历史", "尝试节点: " + rpcUrl);

                // 1. 获取最新区块号（这一步失败说明节点不可用或不响应）
                long latestBlock = getCurrentBlockNumber(rpcUrl);
                if (latestBlock <= 0) {
                    Logger.warning(ctx, "交易历史", "节点 " + rpcUrl + " 获取区块号失败");
                    continue;
                }

                // 2. 计算查询范围：往回查 500,000 个区块（BSC≈17天，ETH≈69天）
                long fromBlock = Math.max(0, latestBlock - 500_000);
                Logger.info(ctx, "交易历史", "节点 " + rpcUrl + " 查询范围 " + fromBlock + " - " + latestBlock);

                // 3. 分块查询 eth_getLogs（自适应 chunkSize：先 5000，失败降级到 2000/1000）
                long chunkSize = 5_000;
                long end = latestBlock;
                long start = Math.max(fromBlock, end - chunkSize + 1);
                int totalFound = 0;
                boolean nodeWorked = false;
                int consecutiveFailures = 0;
                int consecutiveEmpty = 0;  // 连续 0 条计数，用于提前终止

                while (start >= fromBlock && totalFound < 200) {
                    JSONArray logs = fetchTransferLogs(ctx, rpcUrl, actualContract, address, start, end);
                    if (logs != null) {
                        nodeWorked = true;
                        consecutiveFailures = 0;
                        for (int i = 0; i < logs.length() && totalFound < 200; i++) {
                            try {
                                JSONObject log = logs.getJSONObject(i);
                                String[] tx = parseTransferLog(log, address, txSymbol, txDecimals, actualContract);
                                if (tx != null) {
                                    txs.add(tx);
                                    totalFound++;
                                }
                            } catch (Exception e) {
                                // 单条解析失败不阻断整体
                            }
                        }
                        Logger.info(ctx, "交易历史", "块 " + start + "-" + end + " 命中 " + logs.length() + " 条");

                        // 优化：找到交易后连续 5 个空块就停止；未找到时查完整个范围
                        if (logs.length() == 0) {
                            consecutiveEmpty++;
                            int threshold = totalFound > 0 ? 5 : 100;
                            if (consecutiveEmpty >= threshold && totalFound == 0) {
                                Logger.info(ctx, "交易历史", "连续 " + consecutiveEmpty + " 个块无交易，提前终止");
                                break;
                            }
                            if (totalFound > 0 && consecutiveEmpty >= 5) {
                                Logger.info(ctx, "交易历史", "已找到 " + totalFound + " 条交易，连续 5 个块无新交易，停止查询");
                                break;
                            }
                        } else {
                            consecutiveEmpty = 0;
                        }
                    } else {
                        consecutiveFailures++;
                        Logger.warning(ctx, "交易历史", "块 " + start + "-" + end + " 查询失败 (连续 " + consecutiveFailures + " 次)");

                        // 自适应降级：连续 2 次失败，减小 chunkSize 重试当前块
                        if (consecutiveFailures >= 2 && chunkSize > 1_000) {
                            chunkSize = chunkSize / 2;
                            consecutiveFailures = 0;
                            start = Math.max(fromBlock, end - chunkSize + 1);
                            Logger.info(ctx, "交易历史", "降级 chunkSize 至 " + chunkSize + " 重试");
                            continue;
                        }

                        // 连续 5 次失败（已降级到 1000 仍失败），放弃该节点
                        if (consecutiveFailures >= 5) {
                            Logger.warning(ctx, "交易历史", "连续 5 次失败，放弃节点 " + rpcUrl);
                            break;
                        }
                    }

                    if (start <= fromBlock) break;
                    end = start - 1;
                    start = Math.max(fromBlock, end - chunkSize + 1);
                }

                // 4. 节点工作过就视为成功，记录可用节点并退出循环
                if (nodeWorked) {
                    // 与 getEVMBalanceFallback 一致：发现可用节点后保存
                    if (!rpcUrl.equals(currentRpc)) {
                        NodeManager.setSelectedNode(ctx, chain, rpcUrl);
                        Logger.success(ctx, "交易历史", "已切换到可用节点: " + rpcUrl);
                    }
                    Logger.success(ctx, "交易历史", "RPC 查询完成 chain=" + chain + " 共 " + txs.size() + " 条");

                    // 按时间倒序排序
                    java.util.Collections.sort(txs, (a, b) -> {
                        try {
                            return b[4].compareTo(a[4]);
                        } catch (Exception e) {
                            return 0;
                        }
                    });
                    return txs;
                }
            } catch (Exception e) {
                Logger.warning(ctx, "交易历史", "节点 " + rpcUrl + " 异常: " + e.getMessage());
            }
        }

        Logger.warning(ctx, "交易历史", "所有 " + candidateUrls.size() + " 个节点均无法查询 " + chain + " 交易历史");
        return txs;
    }

    /**
     * 查询单个代币合约的Transfer事件（用于TokenDetailActivity）
     * 通过RPC eth_getLogs查询指定合约地址对钱包地址的Transfer事件
     * 返回格式同parseTransferLog：[hash, from, to, value, time, status, type, symbol]
     */
    private static java.util.List<String[]> fetchTokenTransferEvents(Context ctx, String chain,
                                                                      String address, String contractAddress,
                                                                      java.util.Map<String, String> symMap) {
        java.util.List<String[]> txs = new java.util.ArrayList<>();
        String caLower = contractAddress.toLowerCase();

        // 获取symbol
        String symbol = symMap != null ? symMap.getOrDefault(caLower, "") : "";
        if (symbol == null) symbol = "";

        // 获取decimals
        int decimals = 18;
        String rpcUrl = WalletManager.getRpcUrl(ctx, chain);
        if (rpcUrl != null && !rpcUrl.isEmpty()) {
            decimals = getTokenDecimals(rpcUrl, caLower);
        }

        // 构造合约地址数组
        JSONArray contractArray = new JSONArray();
        contractArray.put(caLower);

        // 构造钱包地址padded
        String addressPadded = "0x000000000000000000000000" + address.toLowerCase().replace("0x", "");

        // 候选节点：优先使用支持 eth_getLogs 的节点，AVE代理节点排末尾
        java.util.List<String> candidateUrls = new java.util.ArrayList<>();
        NodeManager.NodeEntry[] presets = NodeManager.getPresets(chain);
        // 先加非AVE节点
        for (NodeManager.NodeEntry entry : presets) {
            if (entry != null && entry.url != null && !entry.url.contains("sendFastSwapTx") && !candidateUrls.contains(entry.url)) {
                candidateUrls.add(entry.url);
            }
        }
        // AVE代理节点排最后
        for (NodeManager.NodeEntry entry : presets) {
            if (entry != null && entry.url != null && entry.url.contains("sendFastSwapTx") && !candidateUrls.contains(entry.url)) {
                candidateUrls.add(entry.url);
            }
        }
        // 当前选中节点如果不是AVE，提到最前面
        if (rpcUrl != null && !rpcUrl.isEmpty() && !rpcUrl.contains("sendFastSwapTx")) {
            candidateUrls.remove(rpcUrl);
            candidateUrls.add(0, rpcUrl);
        }

        for (String url : candidateUrls) {
            if (url == null || url.isEmpty()) continue;
            try {
                long latestBlock = getCurrentBlockNumber(url);
                if (latestBlock <= 0) continue;

                long fromBlock = Math.max(0, latestBlock - 500_000);
                Logger.info(ctx, "交易历史", "单代币查询 " + symbol + " 范围 " + fromBlock + "-" + latestBlock);

                long chunkSize = 1_000; // TP节点对热门合约限制较严，1000块更稳定
                long end = latestBlock;
                long start = Math.max(fromBlock, end - chunkSize + 1);
                int totalFound = 0;
                int consecutiveEmpty = 0;

                while (start >= fromBlock && totalFound < 200) {
                    JSONArray fromLogs = queryLogsBatch(ctx, url, contractArray, addressPadded, null, start, end);
                    JSONArray toLogs = queryLogsBatch(ctx, url, contractArray, null, addressPadded, start, end);

                    if (fromLogs != null || toLogs != null) {
                        java.util.Map<String, JSONObject> merged = new java.util.LinkedHashMap<>();
                        if (fromLogs != null) {
                            for (int i = 0; i < fromLogs.length(); i++) {
                                try {
                                    JSONObject log = fromLogs.getJSONObject(i);
                                    String hash = log.optString("transactionHash", "");
                                    String logIdx = log.optString("logIndex", "0");
                                    merged.put(hash + "_" + logIdx, log);
                                } catch (Exception e) {}
                            }
                        }
                        if (toLogs != null) {
                            for (int i = 0; i < toLogs.length(); i++) {
                                try {
                                    JSONObject log = toLogs.getJSONObject(i);
                                    String hash = log.optString("transactionHash", "");
                                    String logIdx = log.optString("logIndex", "0");
                                    merged.put(hash + "_" + logIdx, log);
                                } catch (Exception e) {}
                            }
                        }

                        for (JSONObject log : merged.values()) {
                            try {
                                String[] tx = parseTransferLog(log, address, symbol, decimals, caLower);
                                if (tx != null) {
                                    txs.add(tx);
                                    totalFound++;
                                }
                            } catch (Exception e) {}
                        }

                        if (merged.isEmpty()) {
                            consecutiveEmpty++;
                            // AVE代理节点不支持eth_getLogs，3个chunk返回0条就跳过
                            boolean isAveProxy = url.contains("sendFastSwapTx");
                            if (isAveProxy && consecutiveEmpty >= 3) {
                                Logger.warning(ctx, "交易历史", "AVE代理节点不支持eth_getLogs，跳过");
                                break;
                            }
                            int threshold = totalFound > 0 ? 5 : 100;
                            if (consecutiveEmpty >= threshold) break;
                        } else {
                            consecutiveEmpty = 0;
                        }
                    } else {
                        break;
                    }

                    if (start <= fromBlock) break;
                    end = start - 1;
                    start = Math.max(fromBlock, end - chunkSize + 1);
                }

                if (totalFound > 0) {
                    Logger.success(ctx, "交易历史", symbol + " Transfer事件 " + totalFound + " 条");
                    break;
                }
            } catch (Exception e) {
                Logger.warning(ctx, "交易历史", "节点 " + url + " 查询失败: " + e.getMessage());
            }
        }

        return txs;
    }

    /**
     * 批量查询多个代币合约的 Transfer 事件（单次 RPC 调用查所有合约）
     *
     * 重要：AVE代理节点（sendFastSwapTx端点）不支持 eth_getLogs，
     *       所以候选节点优先使用TP/公共节点，AVE排在末尾仅作最后兜底。
     *
     * 策略：
     *  - 获取最新区块号
     *  - 往回查 500,000 个区块（BSC≈17天，ETH≈69天）
     *  - 分块查询，chunkSize=5,000，address 参数为数组
     *  - 双向查询：from=wallet 和 to=wallet 合并去重
     *  - AVE节点3个chunk空结果就跳到下一个节点
     */
    private static java.util.List<String[]> fetchEvmTransactionHistoryBatch(Context ctx, String chain,
                                                                                String address, java.util.List<String> contractList) {
        java.util.List<String[]> txs = new java.util.ArrayList<>();

        if (contractList == null || contractList.isEmpty()) {
            return txs;
        }

        // 构造 address 数组（小写）
        JSONArray addressArray = new JSONArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String c : contractList) {
            String lower = c.toLowerCase();
            if (!seen.contains(lower)) {
                addressArray.put(lower);
                seen.add(lower);
            }
        }

        // 候选节点：优先使用支持 eth_getLogs 的节点，AVE代理节点排末尾（不支持日志查询）
        java.util.List<String> candidateUrls = new java.util.ArrayList<>();
        String currentRpc = WalletManager.getRpcUrl(ctx, chain);
        NodeManager.NodeEntry[] presets = NodeManager.getPresets(chain);
        // 先加非AVE节点（TP/公共节点都支持eth_getLogs）
        for (NodeManager.NodeEntry entry : presets) {
            if (entry != null && entry.url != null && !entry.url.contains("sendFastSwapTx") && !candidateUrls.contains(entry.url)) {
                candidateUrls.add(entry.url);
            }
        }
        // AVE代理节点排最后（不支持eth_getLogs，只支持余额查询和发交易）
        for (NodeManager.NodeEntry entry : presets) {
            if (entry != null && entry.url != null && entry.url.contains("sendFastSwapTx") && !candidateUrls.contains(entry.url)) {
                candidateUrls.add(entry.url);
            }
        }
        // 当前选中节点如果不是AVE，提到最前面
        if (currentRpc != null && !currentRpc.isEmpty() && !currentRpc.contains("sendFastSwapTx")) {
            candidateUrls.remove(currentRpc);
            candidateUrls.add(0, currentRpc);
        }
        Logger.info(ctx, "交易历史", chain + " 批量查询 " + addressArray.length() + " 个合约，候选节点 " + candidateUrls.size() + " 个");

        String addressPadded = "0x000000000000000000000000" + address.toLowerCase().replace("0x", "");

        // 构造 contract→{symbol, decimals} 映射（用于 UI 显示代币名称和正确计算金额精度）
        final java.util.Map<String, String> contractSymbolMap = new java.util.HashMap<>();
        final java.util.Map<String, Integer> contractDecimalsMap = new java.util.HashMap<>();
        try {
            DataCache cache = new DataCache(ctx);
            for (String[] token : cache.getCachedTokens()) {
                if (token.length > 4 && token[4] != null && !token[4].isEmpty()) {
                    String c = token[4].toLowerCase();
                    String sym = token.length > 0 ? token[0] : "";
                    // WBNB 显示为 BNB（包装原生币 → 原生币名称）
                    if ("WBNB".equalsIgnoreCase(sym)) sym = chain;
                    contractSymbolMap.put(c, sym);
                }
            }
        } catch (Exception e) {}
        // 包装原生币特殊处理
        String wrappedNative = getWrappedNativeContract(chain);
        if (wrappedNative != null) {
            String wnLower = wrappedNative.toLowerCase();
            contractSymbolMap.put(wnLower, chain);
            contractDecimalsMap.put(wnLower, 18);
        }
        // 通过 RPC 查询每个代币的 decimals（每个合约 1 次 eth_call，可接受）
        String rpcForDecimals = currentRpc;
        if (rpcForDecimals != null && !rpcForDecimals.isEmpty()) {
            for (String c : seen) {
                if (!contractDecimalsMap.containsKey(c)) {
                    int dec = getTokenDecimals(rpcForDecimals, c);
                    contractDecimalsMap.put(c, dec);
                }
            }
        }

        for (String rpcUrl : candidateUrls) {
            if (rpcUrl == null || rpcUrl.isEmpty()) continue;
            try {
                Logger.info(ctx, "交易历史", "尝试节点: " + rpcUrl);

                long latestBlock = getCurrentBlockNumber(rpcUrl);
                if (latestBlock <= 0) {
                    Logger.warning(ctx, "交易历史", "节点 " + rpcUrl + " 获取区块号失败");
                    continue;
                }

                long fromBlock = Math.max(0, latestBlock - 500_000);
                Logger.info(ctx, "交易历史", "节点 " + rpcUrl + " 查询范围 " + fromBlock + " - " + latestBlock);

                long chunkSize = 5_000;
                long end = latestBlock;
                long start = Math.max(fromBlock, end - chunkSize + 1);
                int totalFound = 0;
                boolean nodeWorked = false;
                int consecutiveFailures = 0;
                int consecutiveEmpty = 0;

                while (start >= fromBlock && totalFound < 200) {
                    // 批量查询：from 方向 + to 方向
                    JSONArray fromLogs = queryLogsBatch(ctx, rpcUrl, addressArray, addressPadded, null, start, end);
                    JSONArray toLogs = queryLogsBatch(ctx, rpcUrl, addressArray, null, addressPadded, start, end);

                    if (fromLogs != null || toLogs != null) {
                        nodeWorked = true;
                        consecutiveFailures = 0;

                        // 合并去重
                        java.util.Map<String, JSONObject> merged = new java.util.LinkedHashMap<>();
                        if (fromLogs != null) {
                            for (int i = 0; i < fromLogs.length() && totalFound < 200; i++) {
                                try {
                                    JSONObject log = fromLogs.getJSONObject(i);
                                    String hash = log.optString("transactionHash", "");
                                    String logIdx = log.optString("logIndex", "0");
                                    merged.put(hash + "_" + logIdx, log);
                                } catch (Exception e) {}
                            }
                        }
                        if (toLogs != null) {
                            for (int i = 0; i < toLogs.length() && totalFound < 200; i++) {
                                try {
                                    JSONObject log = toLogs.getJSONObject(i);
                                    String hash = log.optString("transactionHash", "");
                                    String logIdx = log.optString("logIndex", "0");
                                    merged.put(hash + "_" + logIdx, log);
                                } catch (Exception e) {}
                            }
                        }

                        for (JSONObject log : merged.values()) {
                            try {
                                String logContract = log.optString("address", "").toLowerCase();
                                String txSym = contractSymbolMap.getOrDefault(logContract, "");
                                int txDec = contractDecimalsMap.getOrDefault(logContract, 18);
                                String[] tx = parseTransferLog(log, address, txSym, txDec, logContract);
                                if (tx != null) {
                                    txs.add(tx);
                                    totalFound++;
                                }
                            } catch (Exception e) {}
                        }

                        Logger.info(ctx, "交易历史", "块 " + start + "-" + end + " 命中 " + merged.size() + " 条");

                        if (merged.isEmpty()) {
                            consecutiveEmpty++;
                            // AVE代理节点不支持eth_getLogs，3个chunk返回0条就跳过该节点
                            boolean isAveProxy = rpcUrl.contains("sendFastSwapTx");
                            if (isAveProxy && consecutiveEmpty >= 3) {
                                Logger.warning(ctx, "交易历史", "AVE代理节点不支持eth_getLogs，3个chunk全0条，跳过该节点");
                                break;
                            }
                            // 优化：找到交易后，连续 5 个空块就停止（0.85 天无新交易）
                            // 未找到交易时，查完整个 500,000 区块（100 个 chunk）确保不遗漏
                            int threshold = totalFound > 0 ? 5 : 100;
                            if (consecutiveEmpty >= threshold && totalFound == 0) {
                                Logger.info(ctx, "交易历史", "连续 " + consecutiveEmpty + " 个块无交易，提前终止");
                                break;
                            }
                            if (totalFound > 0 && consecutiveEmpty >= 5) {
                                Logger.info(ctx, "交易历史", "已找到 " + totalFound + " 条交易，连续 5 个块无新交易，停止查询");
                                break;
                            }
                        } else {
                            consecutiveEmpty = 0;
                        }
                    } else {
                        consecutiveFailures++;
                        Logger.warning(ctx, "交易历史", "块 " + start + "-" + end + " 查询失败 (连续 " + consecutiveFailures + " 次)");

                        if (consecutiveFailures >= 2 && chunkSize > 1_000) {
                            chunkSize = chunkSize / 2;
                            consecutiveFailures = 0;
                            start = Math.max(fromBlock, end - chunkSize + 1);
                            Logger.info(ctx, "交易历史", "降级 chunkSize 至 " + chunkSize + " 重试");
                            continue;
                        }

                        if (consecutiveFailures >= 5) {
                            Logger.warning(ctx, "交易历史", "连续 5 次失败，放弃节点 " + rpcUrl);
                            break;
                        }
                    }

                    if (start <= fromBlock) break;
                    end = start - 1;
                    start = Math.max(fromBlock, end - chunkSize + 1);
                }

                if (nodeWorked) {
                    if (!rpcUrl.equals(currentRpc)) {
                        NodeManager.setSelectedNode(ctx, chain, rpcUrl);
                        Logger.success(ctx, "交易历史", "已切换到可用节点: " + rpcUrl);
                    }
                    Logger.success(ctx, "交易历史", "批量查询完成 chain=" + chain + " 共 " + txs.size() + " 条");

                    java.util.Collections.sort(txs, (a, b) -> {
                        try { return b[4].compareTo(a[4]); } catch (Exception e) { return 0; }
                    });
                    return txs;
                }
            } catch (Exception e) {
                Logger.warning(ctx, "交易历史", "节点 " + rpcUrl + " 异常: " + e.getMessage());
            }
        }

        Logger.warning(ctx, "交易历史", "所有节点均无法批量查询，回退到逐个查询");
        return txs;
    }

    /** 批量 eth_getLogs 查询，address 参数为数组，失败返回 null */
    private static JSONArray queryLogsBatch(Context ctx, String rpcUrl, JSONArray addressArray,
                                               String fromTopic, String toTopic, long fromBlock, long toBlock) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                JSONObject body = new JSONObject();
                body.put("jsonrpc", "2.0");
                body.put("method", "eth_getLogs");
                body.put("id", 1);

                JSONArray params = new JSONArray();
                JSONObject filter = new JSONObject();
                filter.put("address", addressArray);
                filter.put("fromBlock", "0x" + Long.toHexString(fromBlock));
                filter.put("toBlock", "0x" + Long.toHexString(toBlock));

                JSONArray topics = new JSONArray();
                topics.put("0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef");
                topics.put(fromTopic == null ? JSONObject.NULL : fromTopic);
                topics.put(toTopic == null ? JSONObject.NULL : toTopic);
                filter.put("topics", topics);

                params.put(filter);
                body.put("params", params);

                Request request = new Request.Builder()
                    .url(rpcUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .post(RequestBody.create(body.toString(), JSON_TYPE))
                    .build();

                try (Response response = batchClient.newCall(request).execute()) {
                    String resp = response.body() != null ? response.body().string() : "";
                    JSONObject json = new JSONObject(resp);
                    if (json.has("error")) {
                        String errMsg = json.optJSONObject("error").optString("message", "unknown");
                        if (errMsg.contains("limit") || errMsg.contains("range") || errMsg.contains("exceed")) {
                            return null;
                        }
                        if (attempt < 2) { Thread.sleep(1000); continue; }
                        Logger.warning(ctx, "交易历史", "eth_getLogs batch error: " + errMsg);
                        return null;
                    }
                    JSONArray result = json.optJSONArray("result");
                    if (result != null) return result;
                }
            } catch (Exception e) {
                if (attempt < 2) { try { Thread.sleep(1000); } catch (InterruptedException ie) {} }
                else Logger.warning(ctx, "交易历史", "eth_getLogs batch 异常: " + e.getMessage());
            }
        }
        return null;
    }

    /** 获取最新区块号，失败返回 0 */
    private static long getCurrentBlockNumber(String rpcUrl) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                JSONObject body = new JSONObject();
                body.put("jsonrpc", "2.0");
                body.put("method", "eth_blockNumber");
                body.put("params", new JSONArray());
                body.put("id", 1);

                Request request = new Request.Builder()
                    .url(rpcUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .post(RequestBody.create(body.toString(), JSON_TYPE))
                    .build();

                try (Response response = batchClient.newCall(request).execute()) {
                    String resp = response.body() != null ? response.body().string() : "";
                    JSONObject json = new JSONObject(resp);
                    if (json.has("error")) {
                        if (attempt < 2) { Thread.sleep(1000); continue; }
                        return 0;
                    }
                    String result = json.optString("result", "0x0");
                    if (result == null || result.isEmpty() || !result.startsWith("0x")) {
                        if (attempt < 2) { Thread.sleep(1000); continue; }
                        return 0;
                    }
                    long blockNum = new java.math.BigInteger(result.substring(2), 16).longValue();
                    if (blockNum > 0) return blockNum;
                }
            } catch (Exception e) {
                if (attempt < 2) { try { Thread.sleep(1000); } catch (InterruptedException ie) {} }
            }
        }
        return 0;
    }

    /**
     * 查询代币 Transfer 事件日志（ERC-20/BEP-20 通用）
     * Transfer(address indexed from, address indexed to, uint256 value)
     * topic[0] = 0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef（Transfer 事件签名）
     * topic[1] = from 地址（32字节左填充 0）
     * topic[2] = to 地址（32字节左填充 0）
     *
     * 同时查询 from 和 to 两个方向（用两个 filter 请求合并），避免漏掉任意一方
     */
    private static JSONArray fetchTransferLogs(Context ctx, String rpcUrl, String contract, String address,
                                                   long fromBlock, long toBlock) {
        // 构造 address 的 32 字节 hex（左填充 0）
        String addressPadded = "0x000000000000000000000000" + address.toLowerCase().replace("0x", "");

        // 同时查 from=address 和 to=address，合并结果
        JSONArray fromLogs = queryLogs(ctx, rpcUrl, contract, addressPadded, null, fromBlock, toBlock);
        JSONArray toLogs = queryLogs(ctx, rpcUrl, contract, null, addressPadded, fromBlock, toBlock);

        // 两个方向都失败说明节点不响应 eth_getLogs
        if (fromLogs == null && toLogs == null) return null;

        // 合并去重（按交易 hash）
        java.util.Map<String, JSONObject> merged = new java.util.LinkedHashMap<>();
        if (fromLogs != null) {
            for (int i = 0; i < fromLogs.length(); i++) {
                try {
                    JSONObject log = fromLogs.getJSONObject(i);
                    String hash = log.optString("transactionHash", "");
                    String logIdx = log.optString("logIndex", "0");
                    merged.put(hash + "_" + logIdx, log);
                } catch (Exception e) {
                    // 单条解析失败跳过
                }
            }
        }
        if (toLogs != null) {
            for (int i = 0; i < toLogs.length(); i++) {
                try {
                    JSONObject log = toLogs.getJSONObject(i);
                    String hash = log.optString("transactionHash", "");
                    String logIdx = log.optString("logIndex", "0");
                    merged.put(hash + "_" + logIdx, log);
                } catch (Exception e) {
                    // 单条解析失败跳过
                }
            }
        }

        JSONArray result = new JSONArray();
        for (JSONObject log : merged.values()) result.put(log);
        return result;
    }

    /** 执行 eth_getLogs 单次查询，失败返回 null */
    private static JSONArray queryLogs(Context ctx, String rpcUrl, String contract, String fromTopic, String toTopic,
                                           long fromBlock, long toBlock) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                JSONObject body = new JSONObject();
                body.put("jsonrpc", "2.0");
                body.put("method", "eth_getLogs");
                body.put("id", 1);

                JSONArray params = new JSONArray();
                JSONObject filter = new JSONObject();
                filter.put("address", contract);
                filter.put("fromBlock", "0x" + Long.toHexString(fromBlock));
                filter.put("toBlock", "0x" + Long.toHexString(toBlock));

                JSONArray topics = new JSONArray();
                topics.put("0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef");
                topics.put(fromTopic == null ? JSONObject.NULL : fromTopic);
                topics.put(toTopic == null ? JSONObject.NULL : toTopic);
                filter.put("topics", topics);

                params.put(filter);
                body.put("params", params);

                Request request = new Request.Builder()
                    .url(rpcUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .post(RequestBody.create(body.toString(), JSON_TYPE))
                    .build();

                try (Response response = batchClient.newCall(request).execute()) {
                    String resp = response.body() != null ? response.body().string() : "";
                    JSONObject json = new JSONObject(resp);
                    if (json.has("error")) {
                        String errMsg = json.optJSONObject("error").optString("message", "unknown");
                        if (errMsg.contains("limit") || errMsg.contains("range") || errMsg.contains("exceed")) {
                            Logger.warning(ctx, "交易历史", "eth_getLogs 区块范围超限，缩小范围: " + errMsg);
                            return null;
                        }
                        if (attempt < 2) { Thread.sleep(1000); continue; }
                        Logger.warning(ctx, "交易历史", "eth_getLogs error: " + errMsg);
                        return null;
                    }
                    JSONArray result = json.optJSONArray("result");
                    if (result != null) return result;
                }
            } catch (Exception e) {
                if (attempt < 2) { try { Thread.sleep(1000); } catch (InterruptedException ie) {} }
                else Logger.warning(ctx, "交易历史", "eth_getLogs 异常: " + e.getMessage());
            }
        }
        return null;
    }

    /** 解析 Transfer 事件日志为统一格式的交易记录 */
    private static String[] parseTransferLog(JSONObject log, String walletAddress, String symbol, int decimals, String contractAddress) throws Exception {
        String hash = log.optString("transactionHash", "");

        JSONArray topics = log.optJSONArray("topics");
        if (topics == null || topics.length() < 3) return null;

        // topic[1] = from（32字节，后20字节是地址）
        String fromTopic = topics.optString(1, "");
        String from = "0x" + fromTopic.substring(fromTopic.length() - 40);

        // topic[2] = to
        String toTopic = topics.optString(2, "");
        String to = "0x" + toTopic.substring(toTopic.length() - 40);

        // data = value（32字节无符号大整数，十六进制）
        String data = log.optString("data", "0x0");
        double value;
        try {
            java.math.BigInteger rawInt = new java.math.BigInteger(data.substring(2), 16);
            java.math.BigDecimal raw = new java.math.BigDecimal(rawInt);
            // 按代币实际精度计算（USDT=6, R-MAB=8, BNB=18 等）
            value = raw.divide(java.math.BigDecimal.TEN.pow(decimals)).doubleValue();
        } catch (Exception e) {
            value = 0;
        }

        // 区块号 → 时间戳：eth_getLogs 不返回时间戳，用区块号近似显示
        String blockHex = log.optString("blockNumber", "0x0");
        long blockNum = new java.math.BigInteger(blockHex.substring(2), 16).longValue();
        String timeStr = "块 #" + blockNum;

        // 解析日志是否成功（logIndex 存在即视为成功）
        String status = "success";

        String[] txRow = new String[TxRecord.FIELD_COUNT];
        txRow[TxRecord.INDEX_HASH] = hash;
        txRow[TxRecord.INDEX_FROM] = from;
        txRow[TxRecord.INDEX_TO] = to;
        txRow[TxRecord.INDEX_AMOUNT] = formatAmount(value);
        txRow[TxRecord.INDEX_TIME] = timeStr;
        txRow[TxRecord.INDEX_STATUS] = status;
        txRow[TxRecord.INDEX_TYPE] = "transfer";
        txRow[TxRecord.INDEX_SYMBOL] = symbol != null ? symbol : "";
        txRow[TxRecord.INDEX_CONTRACT] = contractAddress != null ? contractAddress : "";
        return txRow;
    }

    /** 获取各链原生包装代币合约地址（用于查原生币交易历史的近似方案） */
    private static String getWrappedNativeContract(String chain) {
        switch (chain) {
            case "ETH":   return "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2";  // WETH
            case "BNB":   return "0xbb4CdB9CBd36B01bD1cBaEBF2De08d9173bc095c";  // WBNB
            case "MATIC": return "0x0d500B1d8E8eF31E21C99d1Db9A6444d3ADf1270";  // WMATIC
            case "AVAX":  return "0xB31f66AA3C1e785363F0875A1B74E27b85FD66c7";  // WAVAX
            case "FTM":   return "0x21be370D5312f44cB42ce377BC9b8a0cEF1A4C83";  // WFTM
            case "ARB":   return "0x82aF49447D8a07e3bd95BD0d56f35241523fBab1";  // WETH (Arbitrum)
            case "GLMR":  return "0xAcc15dC74880C9944775448309B6b9b15fBC0581";  // WGLMR
            case "OP":    return "0x4200000000000000000000000000000000000006";  // WETH (Optimism)
            default:      return null;
        }
    }

    /**
     * 原生币交易历史 - 通过 Etherscan V2 API + IP 直连获取
     *
     * 为什么需要这个方法：
     *  - 原生币（BNB/ETH/MATIC）转账不产生日志，eth_getLogs 查不到
     *  - 标准 JSON-RPC 没有按地址查交易历史的方法
     *  - 必须用索引服务（Etherscan/BscScan 等）
     *
     * 国内访问策略（三层回退）：
     *  1. 普通直连 api.etherscan.io（海外/VPN 用户直接成功）
     *  2. Cloudflare anycast IP 直连 api.etherscan.io（绕 GFW DNS 阻断）
     *  3. 全部失败返回空列表（UI 显示"暂无原生币交易记录"）
     *
     * Etherscan V2 API 格式：
     *  https://api.etherscan.io/v2/api?chainid={chainid}&module=account&action=txlist
     *    &address={address}&page=1&offset=50&sort=desc
     *
     * @return 交易列表 [hash, from, to, value, time, status, type]
     */
    private static java.util.List<String[]> fetchEvmNativeTxViaEtherscanV2(Context ctx, String chain, String address, java.util.Set<String> erc20Hashes) {
        java.util.List<String[]> txs = new java.util.ArrayList<>();

        // 策略0（HTML抓取）已在外部 getTransactionHistory 方法中优先执行
        // 这里仅执行 RPC 策略1-4 作为兜底

        // 获取 RPC URL
        String rpcUrl = getRpcUrl(ctx, chain);
        if (rpcUrl == null || rpcUrl.isEmpty()) {
            Logger.warning(ctx, "交易历史", chain + " 无可用 RPC 节点");
            return txs;
        }

        String addrLower = address.toLowerCase();
        String chainSymbol = chain; // BNB, ETH 等

        Logger.info(ctx, "交易历史", "查询原生币交易 chain=" + chain + " addr=" + address + " erc20Hashes=" + erc20Hashes.size());

        try {
            java.util.Set<String> knownHashes = new java.util.HashSet<>(erc20Hashes);

            // ===== 策略1：从代币交易 hash 反查原生交易（所有类型） =====
            // TP钱包在BNB视角也显示合约调用、授权等，因此不再过滤类型
            int strategy1Found = 0;
            for (String hash : erc20Hashes) {
                try {
                    String[] nativeTx = fetchNativeTxByHash(ctx, rpcUrl, hash, addrLower, chainSymbol);
                    if (nativeTx != null) {
                        txs.add(nativeTx);
                        knownHashes.add(nativeTx[0]);
                        strategy1Found++;
                    }
                } catch (Exception e) {}
            }
            Logger.info(ctx, "交易历史", "策略1 代币反查 找到 " + strategy1Found + " 条原生交易");

            // ===== 策略2：从代币交易的区块号查同区块及邻近区块的原生交易 =====
            // 纯BNB转账不产生事件日志，但通常发生在代币交易附近（前后几个区块）
            // 查已知区块前后各5个区块，额外开销仅10次请求/区块
            int strategy2Found = 0;
            java.util.Set<Long> checkedBlocks = new java.util.HashSet<>();
            for (String hash : erc20Hashes) {
                try {
                    // 通过 tx hash 获取 blockNumber
                    JSONObject body = new JSONObject();
                    body.put("jsonrpc", "2.0");
                    body.put("method", "eth_getTransactionByHash");
                    body.put("id", 1);
                    JSONArray params = new JSONArray();
                    params.put(hash);
                    body.put("params", params);

                    Request request = new Request.Builder()
                        .url(rpcUrl)
                        .post(RequestBody.create(body.toString(), JSON_TYPE))
                        .build();

                    try (Response response = client.newCall(request).execute()) {
                        String resp = response.body() != null ? response.body().string() : "";
                        JSONObject json = new JSONObject(resp);
                        JSONObject txObj = json.optJSONObject("result");
                        if (txObj == null) continue;

                        String blockNumHex = txObj.optString("blockNumber", "");
                        if (blockNumHex.isEmpty() || !blockNumHex.startsWith("0x")) continue;
                        long blockNum = new java.math.BigInteger(blockNumHex.substring(2), 16).longValue();

                        // 查这个区块前后各5个区块（共11个区块）
                        for (long b = blockNum - 5; b <= blockNum + 5; b++) {
                            if (b <= 0 || checkedBlocks.contains(b)) continue;
                            checkedBlocks.add(b);
                            java.util.List<String[]> blockTxs = fetchNativeTxsInBlock(ctx, rpcUrl, b, addrLower, chainSymbol, knownHashes);
                            for (String[] tx : blockTxs) {
                                txs.add(tx);
                                knownHashes.add(tx[0]);
                                strategy2Found++;
                            }
                        }
                    }
                } catch (Exception e) {}
            }
            Logger.info(ctx, "交易历史", "策略2 邻近区块扫描 找到 " + strategy2Found + " 条原生交易（" + checkedBlocks.size() + " 个区块）");

            // ===== 策略3：从最新区块往前扫描，寻找纯 BNB 转账 =====
            // 纯BNB转账不产生事件日志，只能逐块查 eth_getBlockByNumber
            // 从最新区块往前扫，找到20条或扫完500个区块即停
            // 500区块 ≈ 25分钟的交易窗口，最多500次请求
            int strategy3Found = 0;
            long latestBlock = getCurrentBlockNumber(rpcUrl);
            if (latestBlock > 0) {
                long scanEnd = Math.max(1, latestBlock - 500);
                int emptyBlocks = 0;
                for (long b = latestBlock; b >= scanEnd && strategy3Found < 20; b--) {
                    if (checkedBlocks.contains(b)) continue;
                    checkedBlocks.add(b);
                    java.util.List<String[]> blockTxs = fetchNativeTxsInBlock(ctx, rpcUrl, b, addrLower, chainSymbol, knownHashes);
                    if (blockTxs.isEmpty()) {
                        emptyBlocks++;
                        // 连续30个区块无相关交易，提前退出
                        if (emptyBlocks >= 30) break;
                    } else {
                        emptyBlocks = 0;
                        for (String[] tx : blockTxs) {
                            txs.add(tx);
                            knownHashes.add(tx[0]);
                            strategy3Found++;
                        }
                    }
                }
            }
            Logger.info(ctx, "交易历史", "策略3 近期扫描 找到 " + strategy3Found + " 条原生交易");

            // ===== 策略4：用 eth_getLogs 查 WBNB Deposit/Withdrawal 事件 =====
            // BNB 包装/解包操作：Deposit = BNB→WBNB, Withdrawal = WBNB→BNB
            // 正确的事件签名（keccak256）：
            //   Deposit(address,uint256)     = 0xe1fffcc4923d04b559f4d29a8bfc6cda04eb5b0d3c460751c2402c5c5cc9109c
            //   Withdrawal(address,uint256)  = 0x7fcf532c15f0a6db0bd6d0e038bea71d30d808c7d98cb3bf7268a95bf5081b65
            int strategy4Found = 0;
            String wrappedNative = getWrappedNativeContract(chain);
            if (wrappedNative != null) {
                strategy4Found = queryWrappedNativeEventsV2(ctx, rpcUrl, wrappedNative.toLowerCase(), addrLower, chainSymbol, txs, knownHashes);
                Logger.info(ctx, "交易历史", "策略4 Deposit/Withdrawal 找到 " + strategy4Found + " 条");
            }

            Logger.success(ctx, "交易历史", "原生币交易共 " + txs.size() + " 条 chain=" + chain);
        } catch (Exception e) {
            Logger.error(ctx, "交易历史", "查询原生币交易失败: " + e.getMessage(), e);
        }

        return txs;
    }

    /** 查询单个区块中与钱包地址相关的原生交易（排除已知 hash） */
    private static java.util.List<String[]> fetchNativeTxsInBlock(Context ctx, String rpcUrl, long blockNum, String addrLower, String chainSymbol, java.util.Set<String> knownHashes) {
        java.util.List<String[]> txs = new java.util.ArrayList<>();
        try {
            JSONObject body = new JSONObject();
            body.put("jsonrpc", "2.0");
            body.put("method", "eth_getBlockByNumber");
            body.put("id", 1);
            JSONArray params = new JSONArray();
            params.put("0x" + Long.toHexString(blockNum));
            params.put(true); // 包含完整交易对象
            body.put("params", params);

            Request request = new Request.Builder()
                .url(rpcUrl)
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

            try (Response response = client.newCall(request).execute()) {
                String resp = response.body() != null ? response.body().string() : "";
                JSONObject json = new JSONObject(resp);
                if (json.has("error")) return txs;

                JSONObject block = json.optJSONObject("result");
                if (block == null) return txs;

                // 获取区块时间戳
                String timestampHex = block.optString("timestamp", "0x0");
                long timestamp = 0;
                try {
                    if (timestampHex.startsWith("0x")) {
                        timestamp = new java.math.BigInteger(timestampHex.substring(2), 16).longValue();
                    }
                } catch (Exception e) {}

                String timeStr = "";
                if (timestamp > 0) {
                    try {
                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault());
                        timeStr = sdf.format(new java.util.Date(timestamp * 1000));
                    } catch (Exception e) {}
                }

                JSONArray transactions = block.optJSONArray("transactions");
                if (transactions == null) return txs;

                for (int i = 0; i < transactions.length(); i++) {
                    try {
                        JSONObject tx = transactions.getJSONObject(i);
                        String from = tx.optString("from", "").toLowerCase();
                        String to = tx.optString("to", "").toLowerCase();
                        String hash = tx.optString("hash", "");
                        String valueHex = tx.optString("value", "0x0");
                        String input = tx.optString("input", "0x");

                        if (!from.equals(addrLower) && !to.equals(addrLower)) continue;
                        if (knownHashes.contains(hash)) continue;

                        double val = 0;
                        try {
                            if (valueHex.startsWith("0x")) {
                                java.math.BigInteger wei = new java.math.BigInteger(valueHex.substring(2), 16);
                                val = new java.math.BigDecimal(wei).divide(java.math.BigDecimal.TEN.pow(18)).doubleValue();
                            }
                        } catch (Exception e) {}

                        String type = "transfer";
                        boolean isContractCall = input != null && input.length() > 2 && !"0x".equals(input);
                        if (isContractCall) {
                            boolean isApproval = input.toLowerCase().startsWith("0x095ea7b3");
                            boolean isTransfer = input.toLowerCase().startsWith("0xa9059cbb");
                            type = isApproval ? "approval" : (isTransfer ? "transfer" : "contract_call");
                        }

                        String[] txRow = new String[TxRecord.FIELD_COUNT];
                        txRow[TxRecord.INDEX_HASH] = hash;
                        txRow[TxRecord.INDEX_FROM] = from;
                        txRow[TxRecord.INDEX_TO] = to;
                        txRow[TxRecord.INDEX_AMOUNT] = formatAmount(val);
                        txRow[TxRecord.INDEX_TIME] = timeStr;
                        txRow[TxRecord.INDEX_STATUS] = "success";
                        txRow[TxRecord.INDEX_TYPE] = type;
                        txRow[TxRecord.INDEX_SYMBOL] = "";
                        txRow[TxRecord.INDEX_CONTRACT] = "";
                        txs.add(txRow);
                    } catch (Exception e) {
                        Logger.error(null, "交易解析", "fetchNativeTxsInBlock 单行解析异常", e);
                    }
                }
            }
        } catch (Exception e) {
            Logger.error(null, "交易解析", "fetchNativeTxsInBlock 解析异常", e);
        }
        return txs;
    }

    /** 查询 WBNB Deposit/Withdrawal 事件（正确的事件签名） */
    private static int queryWrappedNativeEventsV2(Context ctx, String rpcUrl, String wrappedNative,
                                                    String addrLower, String chainSymbol,
                                                    java.util.List<String[]> txs, java.util.Set<String> knownHashes) {
        int found = 0;
        try {
            long latestBlock = getCurrentBlockNumber(rpcUrl);
            if (latestBlock == 0) return 0;

            long fromBlock = Math.max(0, latestBlock - 500_000);
            String addressPadded = "0x000000000000000000000000" + addrLower.replace("0x", "");

            long chunkSize = 5000;
            long end = latestBlock;
            long start = Math.max(fromBlock, end - chunkSize + 1);
            int consecutiveEmpty = 0;

            while (start >= fromBlock && found < 50) {
                // Deposit(address indexed dst, uint256 wad)
                JSONArray depositLogs = queryLogsByTopics(ctx, rpcUrl, wrappedNative,
                    "0xe1fffcc4923d04b559f4d29a8bfc6cda04eb5b0d3c460751c2402c5c5cc9109c", addressPadded, start, end);
                // Withdrawal(address indexed src, uint256 wad)
                JSONArray withdrawalLogs = queryLogsByTopics(ctx, rpcUrl, wrappedNative,
                    "0x7fcf532c15f0a6db0bd6d0e038bea71d30d808c7d98cb3bf7268a95bf5081b65", addressPadded, start, end);

                java.util.List<JSONObject> logs = new java.util.ArrayList<>();
                if (depositLogs != null) { for (int i = 0; i < depositLogs.length(); i++) { try { logs.add(depositLogs.getJSONObject(i)); } catch (Exception e) {} } }
                if (withdrawalLogs != null) { for (int i = 0; i < withdrawalLogs.length(); i++) { try { logs.add(withdrawalLogs.getJSONObject(i)); } catch (Exception e) {} } }

                for (JSONObject log : logs) {
                    try {
                        String hash = log.optString("transactionHash", "");
                        if (hash.isEmpty() || knownHashes.contains(hash)) continue;
                        knownHashes.add(hash);

                        String[] nativeTx = fetchNativeTxByHash(ctx, rpcUrl, hash, addrLower, chainSymbol);
                        if (nativeTx != null) {
                            txs.add(nativeTx);
                            found++;
                        }
                    } catch (Exception e) {}
                }

                if (logs.isEmpty()) {
                    consecutiveEmpty++;
                    if (found > 0 && consecutiveEmpty >= 5) break;
                } else {
                    consecutiveEmpty = 0;
                }

                if (start <= fromBlock) break;
                end = start - 1;
                start = Math.max(fromBlock, end - chunkSize + 1);
            }
        } catch (Exception e) {
            Logger.warning(ctx, "交易历史", "WBNB Deposit/Withdrawal 查询失败: " + e.getMessage());
        }
        return found;
    }

    /** 根据 tx hash 查询原生交易详情，返回 [hash, from, to, value, time, status, type] */
    private static String[] fetchNativeTxByHash(Context ctx, String rpcUrl, String hash, String addrLower, String chainSymbol) {
        try {
            JSONObject body = new JSONObject();
            body.put("jsonrpc", "2.0");
            body.put("method", "eth_getTransactionByHash");
            body.put("id", 1);
            JSONArray params = new JSONArray();
            params.put(hash);
            body.put("params", params);

            Request request = new Request.Builder()
                .url(rpcUrl)
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

            try (Response response = client.newCall(request).execute()) {
                String resp = response.body() != null ? response.body().string() : "";
                JSONObject json = new JSONObject(resp);
                if (json.has("error")) return null;

                JSONObject tx = json.optJSONObject("result");
                if (tx == null) return null;

                String from = tx.optString("from", "").toLowerCase();
                String to = tx.optString("to", "").toLowerCase();
                String valueHex = tx.optString("value", "0x0");
                String input = tx.optString("input", "0x");
                String blockHash = tx.optString("blockHash", "");

                if (!from.equals(addrLower) && !to.equals(addrLower)) return null;

                double val = 0;
                try {
                    if (valueHex.startsWith("0x")) {
                        java.math.BigInteger wei = new java.math.BigInteger(valueHex.substring(2), 16);
                        val = new java.math.BigDecimal(wei).divide(java.math.BigDecimal.TEN.pow(18)).doubleValue();
                    }
                } catch (Exception e) {}

                // 判断交易类型
                String type = "transfer";
                boolean isContractCall = input != null && input.length() > 2 && !"0x".equals(input);
                if (isContractCall) {
                    boolean isApproval = input.toLowerCase().startsWith("0x095ea7b3");
                    boolean isTransfer = input.toLowerCase().startsWith("0xa9059cbb");
                    type = isApproval ? "approval" : (isTransfer ? "transfer" : "contract_call");
                }

                // 获取区块时间戳
                String blockNumHex = tx.optString("blockNumber", "0x0");
                String timeStr = "";
                try {
                    if (blockNumHex.startsWith("0x")) {
                        long blockNum = new java.math.BigInteger(blockNumHex.substring(2), 16).longValue();
                        timeStr = getBlockTimestamp(rpcUrl, blockNum);
                    }
                } catch (Exception e) {}

                String[] txRow = new String[TxRecord.FIELD_COUNT];
                txRow[TxRecord.INDEX_HASH] = hash;
                txRow[TxRecord.INDEX_FROM] = from;
                txRow[TxRecord.INDEX_TO] = to;
                txRow[TxRecord.INDEX_AMOUNT] = formatAmount(val);
                txRow[TxRecord.INDEX_TIME] = timeStr;
                txRow[TxRecord.INDEX_STATUS] = "success";
                txRow[TxRecord.INDEX_TYPE] = type;
                txRow[TxRecord.INDEX_SYMBOL] = "";
                txRow[TxRecord.INDEX_CONTRACT] = "";
                return txRow;
            }
        } catch (Exception e) {
            Logger.error(null, "交易解析", "queryWrappedNativeEventsV2 解析异常", e);
            return null;
        }
    }

    /** 获取区块时间戳（带简单缓存避免重复查询） */
    private static java.util.Map<String, String> blockTimestampCache = new java.util.HashMap<>();
    private static String getBlockTimestamp(String rpcUrl, long blockNum) {
        String key = String.valueOf(blockNum);
        if (blockTimestampCache.containsKey(key)) return blockTimestampCache.get(key);

        try {
            JSONObject body = new JSONObject();
            body.put("jsonrpc", "2.0");
            body.put("method", "eth_getBlockByNumber");
            body.put("id", 1);
            JSONArray params = new JSONArray();
            params.put("0x" + Long.toHexString(blockNum));
            params.put(false); // 不需要交易列表，只要区块头
            body.put("params", params);

            Request request = new Request.Builder()
                .url(rpcUrl)
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

            try (Response response = client.newCall(request).execute()) {
                String resp = response.body() != null ? response.body().string() : "";
                JSONObject json = new JSONObject(resp);
                JSONObject block = json.optJSONObject("result");
                if (block != null) {
                    String tsHex = block.optString("timestamp", "0x0");
                    if (tsHex.startsWith("0x")) {
                        long ts = new java.math.BigInteger(tsHex.substring(2), 16).longValue();
                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault());
                        String timeStr = sdf.format(new java.util.Date(ts * 1000));
                        // 限制缓存大小
                        if (blockTimestampCache.size() > 200) blockTimestampCache.clear();
                        blockTimestampCache.put(key, timeStr);
                        return timeStr;
                    }
                }
            }
        } catch (Exception e) {}
        return "块 #" + blockNum;
    }

    /**
     * 查询单笔交易详情（公开方法，供TxDetailActivity调用）
     * 通过RPC eth_getTransactionByHash + eth_getTransactionReceipt 获取完整信息
     * @return [hash, from, to, value, time, status, type, symbol, blockNumber, gasUsed, gasPrice]
     *         如果查询失败返回null
     */
    public static String[] getTransactionDetail(Context ctx, String chain, String txHash) {
        String rpcUrl = WalletManager.getRpcUrl(ctx, chain);
        if (rpcUrl == null || rpcUrl.isEmpty()) return null;

        try {
            // 1. 查交易基本信息
            JSONObject body = new JSONObject();
            body.put("jsonrpc", "2.0");
            body.put("method", "eth_getTransactionByHash");
            body.put("id", 1);
            JSONArray params = new JSONArray();
            params.put(txHash);
            body.put("params", params);

            Request request = new Request.Builder()
                .url(rpcUrl)
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

            JSONObject tx;
            try (Response response = client.newCall(request).execute()) {
                String resp = response.body() != null ? response.body().string() : "";
                JSONObject json = new JSONObject(resp);
                if (json.has("error")) return null;
                tx = json.optJSONObject("result");
                if (tx == null) return null;
            }

            String from = tx.optString("from", "");
            String to = tx.optString("to", "");
            String valueHex = tx.optString("value", "0x0");
            String input = tx.optString("input", "0x");
            String blockNumHex = tx.optString("blockNumber", "");
            String gasHex = tx.optString("gas", "0x0");
            String gasPriceHex = tx.optString("gasPrice", "0x0");

            // 2. 查交易回执（获取status和gasUsed）
            String status = "pending";
            String gasUsedHex = "0x0";
            try {
                JSONObject rcptBody = new JSONObject();
                rcptBody.put("jsonrpc", "2.0");
                rcptBody.put("method", "eth_getTransactionReceipt");
                rcptBody.put("id", 1);
                JSONArray rcptParams = new JSONArray();
                rcptParams.put(txHash);
                rcptBody.put("params", rcptParams);

                Request rcptReq = new Request.Builder()
                    .url(rpcUrl)
                    .post(RequestBody.create(rcptBody.toString(), JSON_TYPE))
                    .build();

                try (Response rcptResp = client.newCall(rcptReq).execute()) {
                    String rcptStr = rcptResp.body() != null ? rcptResp.body().string() : "";
                    JSONObject rcptJson = new JSONObject(rcptStr);
                    JSONObject rcpt = rcptJson.optJSONObject("result");
                    if (rcpt != null) {
                        String statusHex = rcpt.optString("status", "");
                        if ("0x1".equals(statusHex)) status = "success";
                        else if ("0x0".equals(statusHex)) status = "failed";
                        gasUsedHex = rcpt.optString("gasUsed", "0x0");
                    }
                }
            } catch (Exception e) {}

            // 3. 解析数值
            double value = 0;
            try {
                if (valueHex.startsWith("0x")) {
                    java.math.BigInteger wei = new java.math.BigInteger(valueHex.substring(2), 16);
                    value = new java.math.BigDecimal(wei).divide(java.math.BigDecimal.TEN.pow(18)).doubleValue();
                }
            } catch (Exception e) {}

            // 4. 判断交易类型
            String type = "transfer";
            if (input != null && input.length() > 2 && !"0x".equals(input)) {
                boolean isApproval = input.toLowerCase().startsWith("0x095ea7b3");
                boolean isTransfer = input.toLowerCase().startsWith("0xa9059cbb");
                type = isApproval ? "approval" : (isTransfer ? "transfer" : "contract_call");
            }

            // 5. 获取区块号和时间戳
            String blockNumber = "";
            String timeStr = "";
            if (blockNumHex != null && blockNumHex.startsWith("0x")) {
                try {
                    long bn = new java.math.BigInteger(blockNumHex.substring(2), 16).longValue();
                    blockNumber = String.valueOf(bn);
                    timeStr = getBlockTimestamp(rpcUrl, bn);
                } catch (Exception e) {}
            }

            // 6. 计算Gas费
            String gasStr = "";
            try {
                java.math.BigInteger gasUsed = new java.math.BigInteger(gasUsedHex.substring(2), 16);
                java.math.BigInteger gasPrice = new java.math.BigInteger(gasPriceHex.substring(2), 16);
                java.math.BigInteger totalGas = gasUsed.multiply(gasPrice);
                double gasBnb = new java.math.BigDecimal(totalGas).divide(java.math.BigDecimal.TEN.pow(18)).doubleValue();
                gasStr = formatAmount(gasBnb);
            } catch (Exception e) {}

            // 7. 获取链原生代币symbol
            String chainSymbol = getChainSymbol(chain);

            return new String[]{
                txHash, from, to, formatAmount(value), timeStr, status, type, chainSymbol,
                blockNumber, gasStr, ""
            };
        } catch (Exception e) {
            Logger.warning(ctx, "交易详情", "查询失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 获取单笔交易在区块浏览器的URL（公开方法）
     */
    public static String getExplorerTxUrl(String chain, String txHash) {
        switch (chain) {
            case "BNB":   return "https://bscscan.com/tx/" + txHash;
            case "ETH":   return "https://etherscan.io/tx/" + txHash;
            case "MATIC": return "https://polygonscan.com/tx/" + txHash;
            case "ARB":   return "https://arbiscan.io/tx/" + txHash;
            case "AVAX":  return "https://snowtrace.io/tx/" + txHash;
            case "FTM":   return "https://ftmscan.com/tx/" + txHash;
            default:      return null;
        }
    }

    /** 按事件签名和地址查询日志（通用版，不限 Transfer） */
    private static JSONArray queryLogsByTopics(Context ctx, String rpcUrl, String contract, String eventSignature, String addressTopic,
                                                 long fromBlock, long toBlock) {
        try {
            JSONObject body = new JSONObject();
            body.put("jsonrpc", "2.0");
            body.put("method", "eth_getLogs");
            body.put("id", 1);

            JSONArray params = new JSONArray();
            JSONObject filter = new JSONObject();
            filter.put("address", contract);
            filter.put("fromBlock", "0x" + Long.toHexString(fromBlock));
            filter.put("toBlock", "0x" + Long.toHexString(toBlock));

            JSONArray topics = new JSONArray();
            topics.put(eventSignature);
            topics.put(addressTopic == null ? JSONObject.NULL : addressTopic);
            filter.put("topics", topics);

            params.put(filter);
            body.put("params", params);

            Request request = new Request.Builder()
                .url(rpcUrl)
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

            try (Response response = client.newCall(request).execute()) {
                String resp = response.body() != null ? response.body().string() : "";
                JSONObject json = new JSONObject(resp);
                if (json.has("error")) return null;
                return json.optJSONArray("result");
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取 Etherscan 系 API 响应（IP 直连绕 GFW 回退方案）
     *
     * 背景：国内大陆网络下 api.etherscan.io / api.bscscan.com 等被 GFW DNS 污染，
     *       普通 OkHttpClient 直连会因 DNS 解析失败或连接重置而超时。
     *
     * 策略（三层回退）：
     *  1) 先用普通 client 直连域名（海外用户或国内挂 VPN 时直接成功，省去 IP 直连开销）
     *  2) 失败后用 Cloudflare anycast IP 直连（域名 SNI 保留，TLS 握手通过）
     *     - Etherscan/BscScan/Polygonscan/Snowtrace/Ftmscan/Moonscan 全部托管在 Cloudflare 后
     *     - Cloudflare anycast IP 会根据 SNI 自动路由到对应站点
     *  3) 全部失败返回 null，调用方降级为空列表或缓存
     *
     * 安全说明：信任所有证书仅在 IP 直连分支使用，目标 URL 已限定为已知 Etherscan 系域名，
     *           即使被 MITM 最多泄露钱包地址这种公开信息，安全风险可控。
     *
     * @param ctx   上下文（仅用于日志）
     * @param url   完整的 Etherscan API URL（含 https://域名/路径?参数）
     * @param chain 链代码（仅用于日志）
     * @return 响应体字符串；失败返回 null
     */
    public static String fetchWithIpDirectFallback(Context ctx, String url, String chain) {
        if (url == null || url.isEmpty()) return null;

        // 根据 URL 自动选择 Accept 头：API 走 JSON，网页走 HTML
        boolean isApiUrl = url.contains("/api?") || url.contains("/v2/api");
        String acceptHeader = isApiUrl ? "application/json" : "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

        // ---------- 第 1 层：普通 client 直连（海外/VPN 用户优先走此路） ----------
        // 国内网络波动大，重试最多2次（共3次尝试），间隔1秒
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                if (attempt > 0) {
                    Thread.sleep(1000);
                    Logger.info(ctx, "交易历史", "普通直连第 " + (attempt + 1) + " 次重试");
                }
                Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", acceptHeader)
                    .get()
                    .build();
                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String body = response.body().string();
                        if (!body.isEmpty()) {
                            Logger.info(ctx, "交易历史", "普通直连成功 chain=" + chain + " len=" + body.length() + (attempt > 0 ? " (重试" + attempt + ")" : ""));
                            return body;
                        }
                    }
                }
            } catch (Exception e) {
                Logger.warning(ctx, "交易历史", "普通直连失败(attempt=" + attempt + "): " + e.getClass().getSimpleName());
            }
        }

        // ---------- 第 2 层：Cloudflare anycast IP 直连 ----------
        // 这些 IP 来自 Cloudflare 公开的 IP 段（https://www.cloudflare.com/ips/）
        // 104.16.0.0/12 与 172.64.0.0/13 内的 anycast 地址会按 TLS SNI 自动路由
        String[] cloudflareIPs = {
            "104.16.132.229",  // Cloudflare anycast
            "172.64.149.15",   // Cloudflare anycast
            "104.18.43.147",   // Cloudflare anycast
            "162.159.135.232", // Cloudflare anycast
            "104.17.135.84",   // Cloudflare anycast
            "104.19.14.5",     // Cloudflare anycast
            "172.67.72.93",    // Cloudflare anycast
            "104.26.12.158",   // Cloudflare anycast
            "104.26.13.158",   // Cloudflare anycast
            "188.114.96.1",    // Cloudflare anycast
            "188.114.97.1",    // Cloudflare anycast
        };

        for (String ip : cloudflareIPs) {
            try {
                OkHttpClient ipClient = createBypassClient(ip);
                Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", acceptHeader)
                    .get()
                    .build();

                try (Response response = ipClient.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        Logger.warning(ctx, "交易历史", "IP直连 " + ip + " HTTP " + response.code());
                        continue;
                    }
                    String body = response.body().string();
                    // Cloudflare Challenge 页特征：包含 "Just a moment" / "请稍候" / "cf-challenge"
                    if (body.contains("Just a moment") || body.contains("请稍候") || body.contains("cf-challenge")) {
                        Logger.warning(ctx, "交易历史", "IP直连 " + ip + " 命中 Cloudflare Challenge，跳过");
                        continue;
                    }
                    if (body.isEmpty()) {
                        Logger.warning(ctx, "交易历史", "IP直连 " + ip + " 返回空响应");
                        continue;
                    }
                    Logger.success(ctx, "交易历史", "IP直连 " + ip + " 成功 chain=" + chain + " len=" + body.length());
                    return body;
                }
            } catch (Exception e) {
                Logger.warning(ctx, "交易历史", "IP直连 " + ip + " 失败: " + e.getClass().getSimpleName());
            }
        }

        Logger.warning(ctx, "交易历史", "所有请求方式均失败 chain=" + chain + " url=" + url);
        return null;
    }

    /** 获取区块浏览器交易列表页面URL（HTML抓取方案 - 原生币交易） */
    private static String getExplorerTxsUrl(String chain, String address) {
        switch (chain) {
            case "BNB":   return "https://bscscan.com/txs?a=" + address;
            case "ETH":   return "https://etherscan.io/txs?a=" + address;
            case "MATIC": return "https://polygonscan.com/txs?a=" + address;
            case "ARB":   return "https://arbiscan.io/txs?a=" + address;
            case "AVAX":  return "https://snowtrace.io/txs?a=" + address;
            case "FTM":   return "https://ftmscan.com/txs?a=" + address;
            default:      return null;
        }
    }

    /** 获取区块浏览器代币转账页面URL（HTML抓取方案 - ERC-20/BEP-20代币交易） */
    private static String getExplorerTokenTxsUrl(String chain, String address) {
        switch (chain) {
            case "BNB":   return "https://bscscan.com/tokentxns?a=" + address;
            case "ETH":   return "https://etherscan.io/tokentxns?a=" + address;
            case "MATIC": return "https://polygonscan.com/tokentxns?a=" + address;
            case "ARB":   return "https://arbiscan.io/tokentxns?a=" + address;
            case "AVAX":  return "https://snowtrace.io/tokentxns?a=" + address;
            case "FTM":   return "https://ftmscan.com/tokentxns?a=" + address;
            default:      return null;
        }
    }

    /**
     * 获取代币专属页面URL（只显示该代币的交易记录）
     * 格式: /token/CONTRACT_ADDRESS?a=WALLET_ADDRESS
     * BscScan/Etherscan的token页面服务端已过滤，只返回该代币的Transfer事件
     */
    private static String getExplorerTokenPageUrl(String chain, String contractAddress, String walletAddress) {
        String ca = contractAddress.startsWith("0x") ? contractAddress : "0x" + contractAddress;
        switch (chain) {
            case "BNB":   return "https://bscscan.com/token/" + ca + "?a=" + walletAddress;
            case "ETH":   return "https://etherscan.io/token/" + ca + "?a=" + walletAddress;
            case "MATIC": return "https://polygonscan.com/token/" + ca + "?a=" + walletAddress;
            case "ARB":   return "https://arbiscan.io/token/" + ca + "?a=" + walletAddress;
            case "AVAX":  return "https://snowtrace.io/token/" + ca + "?a=" + walletAddress;
            case "FTM":   return "https://ftmscan.com/token/" + ca + "?a=" + walletAddress;
            default:      return null;
        }
    }

    /**
     * 从缓存中获取代币symbol（使用HashMap优化查找性能）
     */
    private static final java.util.Map<String, String> tokenSymbolCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static String getTokenSymbolFromCache(Context ctx, String contractAddress) {
        try {
            String caLower = contractAddress.toLowerCase();
            // 先查内存缓存
            String cached = tokenSymbolCache.get(caLower);
            if (cached != null) return cached;

            // 内存缓存未命中，查DataCache
            DataCache cache = new DataCache(ctx);
            for (String[] token : cache.getCachedTokens()) {
                if (token.length > 4 && token[4] != null && token[4].equalsIgnoreCase(caLower)) {
                    String symbol = token.length > 0 ? token[0] : "";
                    tokenSymbolCache.put(caLower, symbol);
                    return symbol;
                }
            }
        } catch (Exception e) {}
        return "";
    }

    /**
     * 从区块浏览器HTML页面解析交易记录
     * Etherscan/BscScan的/txs页面HTML包含交易表格，每行格式：
     *   <tr> ... <td>hash</td> <td>method</td> <td>block</td> <td>time</td> <td>from</td> <td>to</td> <td>value</td> ... </tr>
     * 使用正则提取每行的关键字段
     */
    private static int parseTxsFromHtml(String html, String addrLower, String chainSymbol, java.util.List<String[]> txs) {
        int found = 0;
        try {
            // BscScan/Etherscan /txs 页面的 HTML 结构（服务端渲染，OkHttp 可直接获取）：
            // 每笔交易一个 <tr>，包含：
            //   - hash: <a href="/tx/0x...">0x...</a>
            //   - method: <span ... title="Swap Exact ETH For Tokens">Swap Exact ETH F...</span>
            //   - block: <a href="/block/111467440">111467440</a>
            //   - timestamp: showLocalDate 列有 unix 时间戳
            //   - from: data-highlight-target="0x..." 或 href="/address/0x..."
            //   - direction: badge IN / OUT
            //   - to: href="/address/0x..." 或 data-highlight-target="0x..."
            //   - value: data-bs-title="0.01 BNB | $5.66" 或文本 >0.01 BNB<

            java.util.regex.Pattern rowPattern = java.util.regex.Pattern.compile(
                "<tr[^>]*>.*?</tr>", java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher rowMatcher = rowPattern.matcher(html);

            java.util.Set<String> seenHashes = new java.util.HashSet<>();
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault());

            while (rowMatcher.find()) {
                try {
                    String row = rowMatcher.group();

                    // 1. 提取交易哈希: href="/tx/0x..."
                    java.util.regex.Pattern hashPattern = java.util.regex.Pattern.compile("href=\"/tx/(0x[0-9a-fA-F]{64})\"");
                    java.util.regex.Matcher hashMatcher = hashPattern.matcher(row);
                    if (!hashMatcher.find()) continue;
                    String hash = hashMatcher.group(1);
                    if (seenHashes.contains(hash)) continue;

                    // 2. 提取 from 和 to 地址
                    // from: data-highlight-target="0x..." (如果出现在 OUT badge 之前)
                    // to: data-highlight-target="0x..." (如果出现在 IN/OUT badge 之后)
                    // 也有 href="/address/0x..." 格式
                    String from = "";
                    String to = "";
                    java.util.List<String> allAddrs = new java.util.ArrayList<>();

                    // 提取所有 data-highlight-target="0x..." 地址
                    java.util.regex.Pattern highlightPattern = java.util.regex.Pattern.compile("data-highlight-target=\"(0x[0-9a-fA-F]{40})\"");
                    java.util.regex.Matcher highlightMatcher = highlightPattern.matcher(row);
                    while (highlightMatcher.find()) {
                        String addr = highlightMatcher.group(1).toLowerCase();
                        if (!allAddrs.contains(addr)) allAddrs.add(addr);
                    }
                    // 提取所有 href="/address/0x..." 地址
                    java.util.regex.Pattern addrHrefPattern = java.util.regex.Pattern.compile("href=\"/address/(0x[0-9a-fA-F]{40})\"");
                    java.util.regex.Matcher addrHrefMatcher = addrHrefPattern.matcher(row);
                    while (addrHrefMatcher.find()) {
                        String addr = addrHrefMatcher.group(1).toLowerCase();
                        if (!allAddrs.contains(addr)) allAddrs.add(addr);
                    }

                    // 用 IN/OUT badge 分割 from 和 to
                    // OUT: from=钱包地址，to=对方
                    // IN: from=对方，to=钱包地址
                    boolean isOut = row.contains(">OUT<") || row.contains("text-uppercase w-100 py-1.5\">OUT");
                    boolean isIn = row.contains(">IN<") || row.contains("text-uppercase w-100 py-1.5\">IN");

                    // from = 第1个地址（出现在 IN/OUT badge 之前的 td）
                    // to = 第2个地址（出现在 IN/OUT badge 之后的 td）
                    // 更可靠的方法：根据 IN/OUT 推断
                    if (allAddrs.size() >= 2) {
                        from = allAddrs.get(0);
                        to = allAddrs.get(1);
                    } else if (allAddrs.size() == 1) {
                        if (isOut) {
                            from = addrLower; // 本地址是 from
                            to = allAddrs.get(0);
                        } else if (isIn) {
                            from = allAddrs.get(0);
                            to = addrLower; // 本地址是 to
                        } else {
                            from = allAddrs.get(0);
                        }
                    }
                    // 如果没有地址但能匹配到 data-bs-title 中的地址
                    if (from.isEmpty() && to.isEmpty()) {
                        // 尝试从 data-bs-title 中提取
                        java.util.regex.Pattern titleAddrPattern = java.util.regex.Pattern.compile("data-bs-title=\"(0x[0-9a-fA-F]{40})\"");
                        java.util.regex.Matcher titleAddrMatcher = titleAddrPattern.matcher(row);
                        while (titleAddrMatcher.find()) {
                            String addr = titleAddrMatcher.group(1).toLowerCase();
                            if (!allAddrs.contains(addr)) allAddrs.add(addr);
                        }
                        if (allAddrs.size() >= 2) { from = allAddrs.get(0); to = allAddrs.get(1); }
                        else if (allAddrs.size() == 1) { from = allAddrs.get(0); }
                    }

                    // 只保留与钱包相关的交易
                    if (!from.equals(addrLower) && !to.equals(addrLower)) continue;

                    // 3. 提取时间戳
                    String timeStr = "";
                    // showLocalDate 列: >1784719983<
                    java.util.regex.Pattern tsPattern = java.util.regex.Pattern.compile("class='showLocalDate'[^>]*>.*?>(\\d{9,10})<");
                    java.util.regex.Matcher tsMatcher = tsPattern.matcher(row);
                    if (tsMatcher.find()) {
                        try {
                            long ts = Long.parseLong(tsMatcher.group(1));
                            if (ts > 1000000) timeStr = sdf.format(new java.util.Date(ts * 1000));
                        } catch (Exception e) {}
                    }
                    // 备选: showDate 列 "2026-07-22 11:33:03"
                    if (timeStr.isEmpty()) {
                        java.util.regex.Pattern datePattern = java.util.regex.Pattern.compile(">(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})<");
                        java.util.regex.Matcher dateMatcher = datePattern.matcher(row);
                        if (dateMatcher.find()) {
                            timeStr = dateMatcher.group(1);
                        }
                    }

                    // 4. 提取值（BNB/ETH数量）
                    String valueStr = "0";
                    // 方法1: data-bs-title="0.01 BNB | $5.66"（支持普通小数点和<b>.</b>加粗小数点）
                    java.util.regex.Pattern valTitlePattern = java.util.regex.Pattern.compile("data-bs-title=\"([\\d,]+(?:(?:\\.|<b>\\.</b>)\\d+)?)\\s+(?:BNB|ETH|MATIC|AVAX|FTM)");
                    java.util.regex.Matcher valTitleMatcher = valTitlePattern.matcher(row);
                    if (valTitleMatcher.find()) {
                        valueStr = valTitleMatcher.group(1).replace(",", "").replace("<b>", "").replace("</b>", "");
                    }
                    // 方法2: 文本 >0.01 BNB< （支持普通小数点和 BscScan <b>.</b>加粗小数点两种格式）
                    if ("0".equals(valueStr)) {
                        java.util.regex.Pattern valTextPattern = java.util.regex.Pattern.compile(">([\\d,]+)((?:\\.|<b>\\.</b>)\\d+)?\\s+(?:BNB|ETH|MATIC|AVAX|FTM)<");
                        java.util.regex.Matcher valTextMatcher = valTextPattern.matcher(row);
                        if (valTextMatcher.find()) {
                            String intPart = valTextMatcher.group(1).replace(",", "");
                            String decPart = valTextMatcher.group(2) != null ? valTextMatcher.group(2).replace("<b>", "").replace("</b>", "") : "";
                            valueStr = intPart + decPart;
                        }
                    }

                    // 5. 提取方法名（判断交易类型）
                    String type = "transfer";
                    // badge title: title="Swap Exact ETH For Tokens" 或 title="Transfer" 或 title="Approve"
                    java.util.regex.Pattern methodPattern = java.util.regex.Pattern.compile("title=\"(\\w[^\"]*?)\"");
                    java.util.regex.Matcher methodMatcher = methodPattern.matcher(row);
                    // 取 td_functionNameOri 中的 title
                    int funcIdx = row.indexOf("td_functionNameOri");
                    if (funcIdx > 0) {
                        String funcPart = row.substring(funcIdx, Math.min(row.length(), funcIdx + 500));
                        java.util.regex.Matcher funcMatcher = java.util.regex.Pattern.compile("title=\"([^\"]+)\"").matcher(funcPart);
                        if (funcMatcher.find()) {
                            String method = funcMatcher.group(1).toLowerCase();
                            if (method.contains("approve") || method.contains("set approval")) {
                                type = "approval";
                            } else if (method.contains("transfer")) {
                                type = "transfer";
                            } else {
                                type = "contract_call";
                            }
                        }
                    }

                    // 6. 判断交易状态（failed 标记）
                    String status = "success";
                    if (row.contains("text-danger") && row.contains("Fail")) {
                        status = "failed";
                    }

                    seenHashes.add(hash);
                    // 原生币交易 symbol 用链 symbol（仅 transfer 类型显示）
                    String txSymbol = "transfer".equals(type) ? chainSymbol : "";
                    String[] txRow = new String[TxRecord.FIELD_COUNT];
                    txRow[TxRecord.INDEX_HASH] = hash;
                    txRow[TxRecord.INDEX_FROM] = from;
                    txRow[TxRecord.INDEX_TO] = to;
                    txRow[TxRecord.INDEX_AMOUNT] = valueStr;
                    txRow[TxRecord.INDEX_TIME] = timeStr;
                    txRow[TxRecord.INDEX_STATUS] = status;
                    txRow[TxRecord.INDEX_TYPE] = type;
                    txRow[TxRecord.INDEX_SYMBOL] = txSymbol;
                    txRow[TxRecord.INDEX_CONTRACT] = "";
                    txs.add(txRow);
                    found++;
                } catch (Exception e) {
                    Logger.error(null, "交易解析", "parseTxsFromHtml 单行解析异常", e);
                }
            }
        } catch (Exception e) {
            Logger.error(null, "交易解析", "parseTxsFromHtml 解析异常", e);
        }
        return found;
    }

    /**
     * 从区块浏览器代币转账页面HTML解析ERC-20/BEP-20交易记录
     * Etherscan/BscScan的/tokentxns页面HTML包含代币转账表格，每行格式：
     *   <tr> ... <td>hash</td> <td>method</td> <td>time</td> <td>from</td> <td>to(IN/OUT)</td> <td>value</td> <td>token</td> ... </tr>
     * 与/txs页面的区别：value列是代币数量而非原生币，多一个token列含代币名称和合约地址
     */
    private static int parseTokenTxsFromHtml(Context ctx, String html, String addrLower, String chainSymbol,
                                              java.util.List<String[]> txs, java.util.Set<String> existingHashes) {
        return parseTokenTxsFromHtml(ctx, html, addrLower, chainSymbol, txs, existingHashes, null);
    }

    private static int parseTokenTxsFromHtml(Context ctx, String html, String addrLower, String chainSymbol,
                                              java.util.List<String[]> txs, java.util.Set<String> existingHashes,
                                              String filterContract) {
        int found = 0;
        try {
            java.util.regex.Pattern rowPattern = java.util.regex.Pattern.compile(
                "<tr[^>]*>.*?</tr>", java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher rowMatcher = rowPattern.matcher(html);

            java.util.Set<String> seenHashes = new java.util.HashSet<>();
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault());

            while (rowMatcher.find()) {
                try {
                    String row = rowMatcher.group();

                    // 1. 提取交易哈希: href="/tx/0x..."
                    java.util.regex.Pattern hashPattern = java.util.regex.Pattern.compile("href=\"/tx/(0x[0-9a-fA-F]{64})\"");
                    java.util.regex.Matcher hashMatcher = hashPattern.matcher(row);
                    if (!hashMatcher.find()) continue;
                    String hash = hashMatcher.group(1);
                    if (seenHashes.contains(hash) || existingHashes.contains(hash)) continue;

                    // 2. 提取 from 和 to 地址
                    // /tokentxns页面的地址格式特点：
                    //   - From地址在 href="/address/0x完整40字符" 中
                    //   - To地址可能是缩写 0xfF7F9639...3b74aAFae（不在链接中），也可能是完整地址
                    //   - IN/OUT badge 在 From 和 To 之间
                    String from = "";
                    String to = "";
                    java.util.List<String> allAddrs = new java.util.ArrayList<>();

                    // 提取所有完整40字符地址
                    // data-highlight-target="0x..."
                    java.util.regex.Pattern highlightPattern = java.util.regex.Pattern.compile("data-highlight-target=\"(0x[0-9a-fA-F]{40})\"");
                    java.util.regex.Matcher highlightMatcher = highlightPattern.matcher(row);
                    while (highlightMatcher.find()) {
                        String addr = highlightMatcher.group(1).toLowerCase();
                        if (!allAddrs.contains(addr)) allAddrs.add(addr);
                    }
                    // href="/address/0x..."
                    java.util.regex.Pattern addrHrefPattern = java.util.regex.Pattern.compile("href=\"/address/(0x[0-9a-fA-F]{40})\"");
                    java.util.regex.Matcher addrHrefMatcher = addrHrefPattern.matcher(row);
                    while (addrHrefMatcher.find()) {
                        String addr = addrHrefMatcher.group(1).toLowerCase();
                        if (!allAddrs.contains(addr)) allAddrs.add(addr);
                    }

                    boolean isOut = row.contains(">OUT<") || row.contains("text-uppercase w-100 py-1.5\">OUT");
                    boolean isIn = row.contains(">IN<") || row.contains("text-uppercase w-100 py-1.5\">IN");

                    if (allAddrs.size() >= 2) {
                        from = allAddrs.get(0);
                        to = allAddrs.get(1);
                    } else if (allAddrs.size() == 1) {
                        // tokentxns中To地址常为缩写，无法提取完整地址
                        // 用IN/OUT badge推断：本地址是from还是to
                        if (isIn) {
                            from = allAddrs.get(0); // 对方是from
                            to = addrLower; // 本地址是to（IN=接收）
                        } else if (isOut) {
                            from = addrLower; // 本地址是from（OUT=发送）
                            to = allAddrs.get(0); // 对方是to
                        } else {
                            // 无IN/OUT标记，尝试用钱包地址推断
                            if (allAddrs.get(0).equals(addrLower)) {
                                from = addrLower;
                            } else {
                                from = allAddrs.get(0);
                                to = addrLower;
                            }
                        }
                    } else {
                        // 没有提取到任何完整地址，用IN/OUT推断
                        from = isIn ? "" : addrLower;
                        to = isIn ? addrLower : "";
                    }

                    // 只保留与钱包相关的交易
                    if (!from.equals(addrLower) && !to.equals(addrLower)) continue;

                    // 3. 提取时间戳
                    String timeStr = "";
                    java.util.regex.Pattern tsPattern = java.util.regex.Pattern.compile("class='showLocalDate'[^>]*>.*?>(\\d{9,10})<");
                    java.util.regex.Matcher tsMatcher = tsPattern.matcher(row);
                    if (tsMatcher.find()) {
                        try {
                            long ts = Long.parseLong(tsMatcher.group(1));
                            if (ts > 1000000) timeStr = sdf.format(new java.util.Date(ts * 1000));
                        } catch (Exception e) {}
                    }
                    if (timeStr.isEmpty()) {
                        java.util.regex.Pattern datePattern = java.util.regex.Pattern.compile(">(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})<");
                        java.util.regex.Matcher dateMatcher = datePattern.matcher(row);
                        if (dateMatcher.find()) {
                            timeStr = dateMatcher.group(1);
                        }
                    }

                    // 4. 提取代币数量
                    // /tokentxns页面的金额格式：<span class="td_showAmount" data-bs-title="88 | $0.00">88</span>
                    // /token/CONTRACT页面的金额格式：>1,000.50 USDT<
                    // BscScan金额格式：普通小数点"100.5" 或 <b>标签加粗小数点"100<b>.</b>5"
                    String valueStr = "";
                    // 方法0a: 从 td_showAmount class 精确提取span内文本（支持多class名，支持普通小数点和<b>.</b>两种格式）
                    java.util.regex.Pattern valShowAmountPattern = java.util.regex.Pattern.compile("class=\"[^\"]*td_showAmount[^\"]*\"[^>]*>([+-]?[\\d,]+(?:(?:\\.|<b>\\.</b>)\\d+)?)");
                    java.util.regex.Matcher valShowAmountMatcher = valShowAmountPattern.matcher(row);
                    if (valShowAmountMatcher.find()) {
                        String rawVal = valShowAmountMatcher.group(1).replace(",", "").replace("<b>.</b>", ".");
                        if (isReasonableAmount(rawVal)) {
                            valueStr = rawVal;
                            Logger.info(null, "交易解析", "方法0a匹配金额(td_showAmount文本): " + valueStr);
                        }
                    }
                    // 方法0b: 从 td_showAmount 的 data-bs-title 属性提取（格式: "88 | $0.00" 或 "100 USDT | $100.00"）
                    if (valueStr.isEmpty()) {
                        java.util.regex.Pattern valTitleSpanPattern = java.util.regex.Pattern.compile("class=\"[^\"]*td_showAmount[^\"]*\"[^>]*data-bs-title=\"([^\"]+)\"");
                        java.util.regex.Matcher valTitleSpanMatcher = valTitleSpanPattern.matcher(row);
                        if (valTitleSpanMatcher.find()) {
                            String titleVal = valTitleSpanMatcher.group(1);
                            java.util.regex.Pattern numPattern = java.util.regex.Pattern.compile("([+-]?[\\d,]+(?:\\.\\d+)?)");
                            java.util.regex.Matcher numMatcher = numPattern.matcher(titleVal);
                            if (numMatcher.find()) {
                                String rawVal = numMatcher.group(1).replace(",", "");
                                if (isReasonableAmount(rawVal)) {
                                    valueStr = rawVal;
                                    Logger.info(null, "交易解析", "方法0b匹配金额(td_showAmount title): " + valueStr);
                                }
                            }
                        }
                    }
                    // 方法1: 任意data-bs-title中的金额（格式: "100 USDT | $100.00" 或 "88 | $0.00"）
                    if (valueStr.isEmpty()) {
                        java.util.regex.Pattern valTitlePattern = java.util.regex.Pattern.compile("data-bs-title=\"([+-]?[\\d,]+(?:\\.\\d+)?)");
                        java.util.regex.Matcher valTitleMatcher = valTitlePattern.matcher(row);
                        while (valTitleMatcher.find()) {
                            String rawVal = valTitleMatcher.group(1).replace(",", "");
                            if (isReasonableAmount(rawVal)) {
                                valueStr = rawVal;
                                Logger.info(null, "交易解析", "方法1匹配金额(data-bs-title): " + valueStr);
                                break;
                            }
                        }
                    }
                    // 方法2: /token/页面格式 >1,000.50 USDT< 或 >+1,000.50 USDT<（支持普通小数点和<b>.</b>，代币符号可包含数字）
                    if (valueStr.isEmpty()) {
                        java.util.regex.Pattern valTokenPattern = java.util.regex.Pattern.compile(">[+-]?([\\d,]+(?:(?:\\.|<b>\\.</b>)\\d+)?)\\s+[A-Za-z0-9]{2,15}<");
                        java.util.regex.Matcher valTokenMatcher = valTokenPattern.matcher(row);
                        while (valTokenMatcher.find()) {
                            String rawVal = valTokenMatcher.group(1).replace(",", "").replace("<b>", "").replace("</b>", "");
                            if (isReasonableAmount(rawVal)) {
                                valueStr = rawVal;
                                Logger.info(null, "交易解析", "方法2匹配金额(token格式): " + valueStr);
                                break;
                            }
                        }
                    }
                    // 方法3: 金额$价值 格式，如 >88$0.00< 或 >9.85$9.84<（支持普通小数点和<b>.</b>）
                    if (valueStr.isEmpty()) {
                        java.util.regex.Pattern valDollarPattern = java.util.regex.Pattern.compile(">([\\d,]+(?:(?:\\.|<b>\\.</b>)\\d+)?)\\$");
                        java.util.regex.Matcher valDollarMatcher = valDollarPattern.matcher(row);
                        while (valDollarMatcher.find()) {
                            String rawVal = valDollarMatcher.group(1).replace(",", "").replace("<b>", "").replace("</b>", "");
                            if (isReasonableAmount(rawVal)) {
                                valueStr = rawVal;
                                Logger.info(null, "交易解析", "方法3匹配金额($格式): " + valueStr);
                                break;
                            }
                        }
                    }
                    // 如果所有精确方法都失败，金额设为空（UI会显示--），不再使用危险的兜底方法
                    if (valueStr.isEmpty()) {
                        Logger.info(null, "交易解析", "所有方法均未匹配到合理金额，标记为空");
                    }

                    // 5. 提取代币合约地址
                    String tokenContract = "";
                    // /tokentxns 页面的token链接格式：href="/token/0xCONTRACT?a=ADDR"
                    // 注意：href中有?a=参数，不能要求40hex后紧跟引号
                    java.util.regex.Pattern contractPattern = java.util.regex.Pattern.compile("href=\"/token/(0x[0-9a-fA-F]{40})");
                    java.util.regex.Matcher contractMatcher = contractPattern.matcher(row);
                    if (contractMatcher.find()) {
                        tokenContract = contractMatcher.group(1).toLowerCase();
                    }

                    // 合约过滤：如果指定了 filterContract，只保留匹配的交易
                    // /tokentxns?contract=CA 不会服务端过滤，必须客户端过滤
                    if (filterContract != null && !filterContract.isEmpty()
                        && !tokenContract.equals(filterContract.toLowerCase())) {
                        continue;
                    }

                    // 6. 提取代币Symbol
                    // /tokentxns页面的token列格式：
                    //   BEP-20: 月球币(月球币)  → symbol=月球币（括号内）
                    //   Binance-Peg ...(BSC-U...)  → symbol=BSC-U（截断的）
                    //   BEP-20: USDT(USDT)  → symbol=USDT
                    String tokenSymbol = "";
                    // 方法1: 从token href链接上下文提取 <a href="/token/0x...">...symbol...</a>
                    java.util.regex.Pattern tokenLinkPattern = java.util.regex.Pattern.compile("href=\"/token/0x[0-9a-fA-F]{40}\"[^>]*>([^<]+)");
                    java.util.regex.Matcher tokenLinkMatcher = tokenLinkPattern.matcher(row);
                    if (tokenLinkMatcher.find()) {
                        String tokenText = tokenLinkMatcher.group(1).trim();
                        // 提取最后一个括号中的内容
                        java.util.regex.Matcher bm = java.util.regex.Pattern.compile("\\(([^)]+)\\)$").matcher(tokenText);
                        if (bm.find()) {
                            tokenSymbol = bm.group(1);
                        } else {
                            // 没有括号，直接使用文本（去除空白和特殊字符）
                            tokenSymbol = tokenText.replaceAll("[\\s\\u200b]+", "").trim();
                        }
                    }
                    // 方法2: 括号内的symbol  BEP-20: 名称(符号)  或 名称(符号)
                    if (tokenSymbol.isEmpty()) {
                        java.util.regex.Pattern bracketSymPattern = java.util.regex.Pattern.compile("\\(([^)]{1,20})\\)");
                        java.util.regex.Matcher bracketSymMatcher = bracketSymPattern.matcher(row);
                        // 从后往前找，取最后一个括号内的（因为可能有嵌套括号）
                        while (bracketSymMatcher.find()) {
                            String candidate = bracketSymMatcher.group(1).trim();
                            // 过滤掉明显不是symbol的内容（如中文描述、过长的文本）
                            if (candidate.length() <= 20 && !candidate.matches(".*[\\u4e00-\\u9fa5].*")) {
                                tokenSymbol = candidate;
                            }
                        }
                    }
                    // 方法3: 从已知的代币列表中查找（通过合约地址）
                    if (tokenSymbol.isEmpty() && !tokenContract.isEmpty()) {
                        String knownSymbol = getTokenSymbolFromCache(ctx, tokenContract);
                        if (!knownSymbol.isEmpty()) {
                            tokenSymbol = knownSymbol;
                        }
                    }

                    // 7. 提取方法名（判断交易类型）
                    String type = "transfer";
                    int funcIdx = row.indexOf("td_functionNameOri");
                    if (funcIdx > 0) {
                        String funcPart = row.substring(funcIdx, Math.min(row.length(), funcIdx + 500));
                        java.util.regex.Matcher funcMatcher = java.util.regex.Pattern.compile("title=\"([^\"]+)\"").matcher(funcPart);
                        if (funcMatcher.find()) {
                            String method = funcMatcher.group(1).toLowerCase();
                            if (method.contains("approve") || method.contains("set approval")) {
                                type = "approval";
                            } else if (method.contains("transfer")) {
                                type = "transfer";
                            } else {
                                type = "contract_call";
                            }
                        }
                    }

                    // 8. 判断交易状态
                    String status = "success";
                    if (row.contains("text-danger") && row.contains("Fail")) {
                        status = "failed";
                    }

                    seenHashes.add(hash);
                    // 代币交易：symbol用代币symbol（非原生币），第9个字段为代币合约地址
                    String txSymbol = "transfer".equals(type) ? tokenSymbol : "";
                    String[] txRow = new String[TxRecord.FIELD_COUNT];
                    txRow[TxRecord.INDEX_HASH] = hash;
                    txRow[TxRecord.INDEX_FROM] = from;
                    txRow[TxRecord.INDEX_TO] = to;
                    txRow[TxRecord.INDEX_AMOUNT] = valueStr;
                    txRow[TxRecord.INDEX_TIME] = timeStr;
                    txRow[TxRecord.INDEX_STATUS] = status;
                    txRow[TxRecord.INDEX_TYPE] = type;
                    txRow[TxRecord.INDEX_SYMBOL] = txSymbol;
                    txRow[TxRecord.INDEX_CONTRACT] = tokenContract;
                    txs.add(txRow);
                    found++;
                    Logger.info(null, "交易解析", String.format("tokenTx hash=%s symbol=%s value=%s from=%s to=%s type=%s contract=%s",
                        hash.substring(0, 8), tokenSymbol, valueStr,
                        from.isEmpty() ? "N/A" : from.substring(0, 8),
                        to.isEmpty() ? "N/A" : to.substring(0, 8),
                        type, tokenContract.isEmpty() ? "N/A" : tokenContract.substring(0, 8)));
                } catch (Exception e) {
                    Logger.error(null, "交易解析", "parseTokenTxsFromHtml 单行解析异常", e);
                }
            }
        } catch (Exception e) {
            Logger.error(null, "交易解析", "parseTokenTxsFromHtml 解析异常", e);
        }
        return found;
    }

    /**
     * 获取区块浏览器代币转账页面URL（带合约地址过滤）
     * BscScan支持: /tokentxns?a=address&contract=contractAddress 过滤特定代币
     */
    private static String getExplorerTokenTxsUrl(String chain, String address, String contractAddress) {
        String baseUrl = getExplorerTokenTxsUrl(chain, address);
        if (baseUrl != null && contractAddress != null && !contractAddress.isEmpty()) {
            baseUrl += "&contract=" + contractAddress;
        }
        return baseUrl;
    }

    private static String getEtherscanBaseUrl(String chain) {
        switch (chain) {
            case "ETH": return "https://api.etherscan.io";
            case "BNB": return "https://api.bscscan.com";
            case "MATIC": return "https://api.polygonscan.com";
            case "ARB": return "https://api.arbiscan.io";
            case "AVAX": return "https://api.snowtrace.io";
            case "FTM": return "https://api.ftmscan.com";
            case "GLMR": return "https://api-moonbeam.moonscan.io";
            case "CELO": return "https://api.celoscan.io";
            case "ONE": return "https://api.explorer.harmony.one";
            default: return null;
        }
    }

    private static String getEtherscanApiKey(String chain) {
        // 未配置真实 API key，返回空字符串（调用时不拼接 apikey 参数，走免费限速通道）
        // 如需提升速率限制，请在此处填入各链申请到的真实 Etherscan/BscScan API key
        return "";
    }

    /**
     * 检查金额字符串是否合理：排除时间戳、区块号等明显错误值
     * 过滤策略：
     *  - 必须大于0
     *  - 排除10位以1开头的纯整数（2001-2286年Unix时间戳特征，当前2026年约178xxxxxxx）
     *  - 带小数点的一律视为合法金额
     *  - 不限制最大金额（SHIB/BABYDOGE等meme币单笔可达几千万/几亿）
     */
    private static boolean isReasonableAmount(String rawVal) {
        if (rawVal == null || rawVal.isEmpty()) return false;
        String cleaned = rawVal.replace(",", "").trim();
        if (cleaned.isEmpty() || cleaned.equals("0") || cleaned.equals("-") || cleaned.equals("+")) return false;
        // 移除所有正负号前缀（支持"--123"这种无效格式）
        String numPart = cleaned.replaceAll("^[+-]+", "");
        if (numPart.isEmpty() || numPart.equals("0")) return false;
        try {
            double val = Double.parseDouble(numPart);
            if (val <= 0) return false;
            // 带小数点，一定是金额
            if (numPart.contains(".")) return true;
            // 纯整数：排除10位以1开头的Unix时间戳（2001-2286年）
            if (numPart.matches("1\\d{9}")) return false;
            // 其他纯整数只要在合理范围（< 1万亿，避免匹配到gas used等超大数字）都接受
            if (val >= 1e12) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 通过 eth_getCode 获取合约部署字节码，用于检测函数选择器是否存在
     * @param rpcUrl RPC 节点地址
     * @param contract 合约地址
     * @return 合约字节码（hex 字符串），失败返回 null
     */
    public static String getContractCode(String rpcUrl, String contract) throws Exception {
        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "eth_getCode");
        JSONArray params = new JSONArray();
        params.put(contract);
        params.put("latest");
        body.put("params", params);

        Request request = new Request.Builder()
            .url(rpcUrl)
            .post(RequestBody.create(body.toString(), JSON_TYPE))
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            String result = json.optString("result", "");
            if (result.isEmpty() || "0x".equals(result)) return null;
            return result;
        }
    }

    // ============================================================
    // DeFi Portfolio (DeBank API)
    // ============================================================

    public static List<String[]> getDeFiPortfolio(Context ctx, String chain, String address) throws Exception {
        List<String[]> result = new ArrayList<>();
        String debankChainId = getDeBankChainId(chain);
        if (debankChainId == null) return result;

        String url = "https://openapi.debank.com/v1/user/protocol?id=" + address + "&chain_id=" + debankChainId;
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (body.isEmpty()) return result;
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.getJSONObject(i);
                String protocolName = item.optString("name", "Unknown");
                double netUsdValue = item.optDouble("net_usd_value", 0);
                String valueStr = formatValue(ctx, netUsdValue);

                StringBuilder detail = new StringBuilder();
                JSONArray portfolioList = item.optJSONArray("portfolio_item_list");
                if (portfolioList != null) {
                    for (int j = 0; j < Math.min(3, portfolioList.length()); j++) {
                        JSONObject pi = portfolioList.getJSONObject(j);
                        JSONObject detailObj = pi.optJSONObject("detail");
                        if (detailObj != null) {
                            JSONArray supplyList = detailObj.optJSONArray("supply_token_list");
                            if (supplyList != null) {
                                for (int k = 0; k < supplyList.length(); k++) {
                                    if (detail.length() > 0) detail.append(", ");
                                    JSONObject st = supplyList.getJSONObject(k);
                                    detail.append(st.optString("symbol", "?")).append("x")
                                          .append(formatAmount(st.optDouble("amount", 0)));
                                }
                            }
                        }
                    }
                }

                String aprStr = "";
                if (detail.length() == 0) detail.append(protocolName);
                result.add(new String[]{protocolName, valueStr, detail.toString(), aprStr});
            }
        }
        return result;
    }

    // ============================================================
    // Token Approvals Scanner
    // ============================================================

    public static List<String[]> getTokenApprovals(Context ctx, String chain, String address) throws Exception {
        List<String[]> result = new ArrayList<>();
        if (!isEVM(ctx, chain)) return result;

        String rpcUrl = getDefaultRpc(ctx, chain);

        // 获取该链的所有代币（使用 getAllTokenBalances 代替不存在的 TokenAutoDiscovery.getTokens）
        List<String[]> tokens = new ArrayList<>();
        try {
            List<String[]> allBalances = getAllTokenBalances(ctx, chain, address);
            if (allBalances != null) {
                for (String[] bal : allBalances) {
                    // getAllTokenBalances 格式: [symbol, name, amount, value, contract, isNative]
                    String contract = bal.length > 4 ? bal[4] : "";
                    String symbol = bal[0];
                    if (contract.isEmpty() || symbol.isEmpty()) continue; // 跳过原生币
                    tokens.add(new String[]{contract, symbol});
                }
            }
        } catch (Exception ignored) {}

        // 对每个代币检查 allowance
        for (String[] token : tokens) {
            try {
                String tokenAddr = token[0];
                String symbol = token[1];
                if (symbol == null || symbol.isEmpty()) continue;

                for (String[] spenderInfo : KNOWN_SPENDERS) {
                    String spenderAddr = spenderInfo[0];
                    String spenderLabel = spenderInfo[1];

                    BigInteger allowance = ContractCaller.erc20Allowance(ctx, chain, tokenAddr, address, spenderAddr);
                    if (allowance != null && allowance.compareTo(BigInteger.ZERO) > 0) {
                        String allowanceStr;
                        if (allowance.compareTo(new BigInteger("1000000000000000000000000000")) > 0) {
                            allowanceStr = "无限";
                        } else {
                            allowanceStr = formatAmount(allowance.doubleValue() / Math.pow(10, 18));
                        }
                        result.add(new String[]{symbol, tokenAddr, allowanceStr, spenderLabel, spenderAddr});
                    }
                }
            } catch (Exception ignored) {}
        }
        return result;
    }

    private static final String[][] KNOWN_SPENDERS = {
        {"0x7a250d5630B4cF539739dF2C5dAcb4c659F2488D", "Uniswap V2"},
        {"0x10ED43C718714eb63d5aA57B78B54704E256024E", "PancakeSwap"},
        {"0x1111111254EEB25477B68fb85Ed929f73A960582", "1inch v5"},
        {"0xDef1C0ded9bec7F1a1670819833240f027b25EfF", "0x Protocol"},
        {"0xe66B31678d6C16E9ebf358268a790B763C133750", "Multichain"},
    };
}
