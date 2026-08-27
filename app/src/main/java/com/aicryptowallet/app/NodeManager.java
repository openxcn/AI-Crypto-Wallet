package com.aicryptowallet.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.MediaType;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 多节点管理器 - 每条链预设多个 RPC 节点，支持测速和区块高度查询
 * 优化：复用静态 OkHttpClient；并行测速加速节点选择
 */
public class NodeManager {
    private static final MediaType JSON_TYPE = MediaType.parse("application/json");
    private static final String PREFS = "node_prefs";

    // 测速/查区块高度专用 client（短超时，复用连接池）
    private static final OkHttpClient PING_CLIENT = new OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build();

    // 并行测速线程池（最多 5 并发，避免瞬时大量连接）
    private static final ExecutorService PING_EXECUTOR = Executors.newFixedThreadPool(5);

    // NodeEntry: name, url
    public static class NodeEntry {
        public final String name;
        public final String url;
        public NodeEntry(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    // chain -> 预设节点列表（带名称）
    private static final Map<String, NodeEntry[]> NODE_PRESETS = new HashMap<>();
    static {
        NODE_PRESETS.put("ETH", new NodeEntry[]{
            new NodeEntry("AVE代理 (推荐,中国直连200ms)", "https://api.dryespah.com/ave_nodes/rpc/eth/sendFastSwapTx"),
            new NodeEntry("TP自建-reth(中国直连350ms)", "https://reth.mytokenpocket.vip"),
            new NodeEntry("TP自建-eth", "https://eth.mytokenpocket.vip"),
            new NodeEntry("PublicNode ETH", "https://ethereum.publicnode.com"),
            new NodeEntry("1RPC ETH", "https://1rpc.io/eth"),
            new NodeEntry("Cloudflare ETH", "https://cloudflare-eth.com"),
            new NodeEntry("DRPC ETH", "https://eth.drpc.org"),
            new NodeEntry("LlamaRPC", "https://eth.llamarpc.com"),
            new NodeEntry("MEV Blocker", "https://rpc.mevblocker.io")
        });
        NODE_PRESETS.put("BNB", new NodeEntry[]{
            new NodeEntry("AVE代理 (推荐,中国直连200ms)", "https://api.dryespah.com/ave_nodes/rpc/bsc/sendFastSwapTx"),
            new NodeEntry("TP自建(中国直连380ms)", "https://bsc.mytokenpocket.vip"),
            new NodeEntry("Defibit1", "https://bsc-dataseed1.defibit.io"),
            new NodeEntry("Defibit2", "https://bsc-dataseed2.defibit.io"),
            new NodeEntry("Ninicoin1", "https://bsc-dataseed1.ninicoin.io"),
            new NodeEntry("Ninicoin2", "https://bsc-dataseed2.ninicoin.io"),
            new NodeEntry("PublicNode BSC", "https://bsc-rpc.publicnode.com"),
            new NodeEntry("NodeReal BSC", "https://bsc.nodereal.io"),
            new NodeEntry("1RPC BSC", "https://1rpc.io/bnb"),
            new NodeEntry("BNBChain 官方1", "https://bsc-dataseed1.binance.org"),
            new NodeEntry("BNBChain 官方2", "https://bsc-dataseed2.binance.org"),
            new NodeEntry("BNBChain 官方3", "https://bsc-dataseed3.binance.org"),
            new NodeEntry("BNBChain 官方4", "https://bsc-dataseed4.binance.org"),
            new NodeEntry("PublicNode BSC2", "https://bsc.publicnode.com"),
            new NodeEntry("DRPC BSC", "https://bsc.drpc.org"),
            new NodeEntry("MeowRPC BSC", "https://bsc.meowrpc.com")
        });
        NODE_PRESETS.put("MATIC", new NodeEntry[]{
            new NodeEntry("TP自建(推荐,中国直连550ms)", "https://matic.mytokenpocket.vip"),
            new NodeEntry("PublicNode Polygon", "https://polygon-bor-rpc.publicnode.com"),
            new NodeEntry("DRPC Polygon", "https://polygon.drpc.org"),
            new NodeEntry("Polygon 官方", "https://polygon-rpc.com")
        });
        NODE_PRESETS.put("ARB", new NodeEntry[]{
            new NodeEntry("TP自建(推荐,中国直连650ms)", "https://arb.mytokenpocket.vip"),
            new NodeEntry("PublicNode Arbitrum", "https://arbitrum-one-rpc.publicnode.com"),
            new NodeEntry("Arbitrum 官方", "https://arb1.arbitrum.io/rpc")
        });
        NODE_PRESETS.put("AVAX", new NodeEntry[]{
            new NodeEntry("PublicNode AVAX", "https://avalanche-c-chain-rpc.publicnode.com"),
            new NodeEntry("AVAX 官方", "https://api.avax.network/ext/bc/C/rpc")
        });
        NODE_PRESETS.put("CORE", new NodeEntry[]{
            new NodeEntry("TP自建(推荐,中国直连360ms)", "https://core.mytokenpocket.vip"),
            new NodeEntry("CoreDAO 官方", "https://rpc.coredao.org"),
            new NodeEntry("PublicNode Core", "https://core.publicnode.com")
        });
        NODE_PRESETS.put("FTM", new NodeEntry[]{
            new NodeEntry("Fantom 官方", "https://rpc.ftm.tools"),
            new NodeEntry("PublicNode FTM", "https://fantom-rpc.publicnode.com")
        });
        NODE_PRESETS.put("GLMR", new NodeEntry[]{
            new NodeEntry("PublicNode Moonbeam", "https://moonbeam.publicnode.com"),
            new NodeEntry("Moonbeam 官方", "https://rpc.api.moonbeam.network")
        });
        NODE_PRESETS.put("KAVA", new NodeEntry[]{
            new NodeEntry("Kava 官方", "https://evm.kava.io"),
            new NodeEntry("Kava PublicNode", "https://kava-rpc.publicnode.com")
        });
        NODE_PRESETS.put("CELO", new NodeEntry[]{
            new NodeEntry("Celo 官方", "https://forno.celo.org"),
            new NodeEntry("Celo PublicNode", "https://celo-rpc.publicnode.com")
        });
        NODE_PRESETS.put("ONE", new NodeEntry[]{
            new NodeEntry("Harmony S0", "https://api.s0.t.hmny.io"),
            new NodeEntry("Harmony 官方", "https://api.harmony.one")
        });
        NODE_PRESETS.put("SOL", new NodeEntry[]{
            new NodeEntry("TP自建1(中国直连)", "https://solana1.mytokenpocket.vip"),
            new NodeEntry("Solana 官方", "https://api.mainnet-beta.solana.com"),
            new NodeEntry("Solana 官方CDN", "https://api.mainnet.solana.com")
        });
        NODE_PRESETS.put("TRX", new NodeEntry[]{
            new NodeEntry("TronGrid", "https://api.trongrid.io"),
            new NodeEntry("TronStack", "https://api.tronstack.io"),
            new NodeEntry("TP自建(中国直连580ms)", "https://trx.mytokenpocket.vip")
        });
        NODE_PRESETS.put("SUI", new NodeEntry[]{
            new NodeEntry("PublicNode SUI", "https://sui-mainnet.publicnode.com"),
            new NodeEntry("Sui 官方", "https://fullnode.mainnet.sui.io")
        });
        NODE_PRESETS.put("APT", new NodeEntry[]{
            new NodeEntry("PublicNode APT", "https://aptos-mainnet.publicnode.com"),
            new NodeEntry("Aptos 官方", "https://fullnode.mainnet.aptoslabs.com")
        });
        NODE_PRESETS.put("ADA", new NodeEntry[]{
            new NodeEntry("Koios", "https://api.koios.rest/api/v1"),
            new NodeEntry("Blockfrost", "https://cardano-mainnet.blockfrost.io/api/v0")
        });
        NODE_PRESETS.put("NEAR", new NodeEntry[]{
            new NodeEntry("PublicNode NEAR", "https://near.publicnode.com"),
            new NodeEntry("NEAR 官方", "https://rpc.mainnet.near.org")
        });
        NODE_PRESETS.put("ATOM", new NodeEntry[]{
            new NodeEntry("Cosmos Directory", "https://rest.cosmos.directory/cosmoshub"),
            new NodeEntry("Cosmos 官方", "https://lcd.cosmos.cosmos.interbloc.org")
        });
        NODE_PRESETS.put("DOT", new NodeEntry[]{
            new NodeEntry("PublicNode DOT", "https://polkadot.publicnode.com"),
            new NodeEntry("Polkadot 官方", "https://rpc.polkadot.io")
        });
        NODE_PRESETS.put("ALGO", new NodeEntry[]{
            new NodeEntry("AlgoNode", "https://mainnet-api.algonode.cloud"),
            new NodeEntry("AlgoNode Indexer", "https://mainnet-idx.algonode.cloud")
        });
        NODE_PRESETS.put("ICP", new NodeEntry[]{
            new NodeEntry("IC 官方", "https://ic0.app"),
            new NodeEntry("IC Boundary", "https://boundary.ic0.app")
        });
        NODE_PRESETS.put("XTZ", new NodeEntry[]{
            new NodeEntry("Tezos IE", "https://mainnet.api.tez.ie"),
            new NodeEntry("TZStats", "https://api.tzstats.com")
        });
        NODE_PRESETS.put("FIL", new NodeEntry[]{
            new NodeEntry("Ankr FIL", "https://rpc.ankr.com/filecoin"),
            new NodeEntry("Glif API", "https://api.node.glif.io/rpc/v1"),
            new NodeEntry("PublicNode FIL", "https://filecoin.publicnode.com")
        });
    }

    /**
     * 获取某条链的所有预设节点（带名称）
     */
    public static NodeEntry[] getPresets(String chain) {
        return NODE_PRESETS.getOrDefault(chain, new NodeEntry[]{
            new NodeEntry(ChainAPI.getChainName(chain), ChainAPI.getDefaultRpc(chain))
        });
    }

    /**
     * 获取用户当前选择的节点 URL
     */
    public static String getSelectedNode(Context ctx, String chain) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String custom = prefs.getString("custom_" + chain, "");
        if (!custom.isEmpty()) return custom;
        String selected = prefs.getString("selected_" + chain, "");
        if (!selected.isEmpty()) return selected;
        // 仅对自定义链（内置链配置里不存在）使用自定义链自身 RPC，
        // 内置链保持原有 getPresets 逻辑完全不变，避免影响任何内置链的节点选择
        if (ChainAPI.getDefaultRpc(chain).isEmpty()) {
            String customChainRpc = ChainAPI.getDefaultRpc(ctx, chain);
            if (customChainRpc != null && !customChainRpc.isEmpty()) return customChainRpc;
        }
        NodeEntry[] presets = getPresets(chain);
        return presets.length > 0 ? presets[0].url : "";
    }

    /**
     * 保存用户选择的节点
     */
    public static void setSelectedNode(Context ctx, String chain, String url) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString("selected_" + chain, url).apply();
        // 清除自定义节点标记（如果选了预设节点）
        for (NodeEntry entry : getPresets(chain)) {
            if (entry.url.equals(url)) {
                prefs.edit().remove("custom_" + chain).apply();
                return;
            }
        }
    }

    /**
     * 保存自定义节点
     */
    public static void setCustomNode(Context ctx, String chain, String url) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString("custom_" + chain, url).apply();
    }

    /**
     * 获取自定义节点
     */
    public static String getCustomNode(Context ctx, String chain) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getString("custom_" + chain, "");
    }

    // ========== Infura 备用节点 ==========
    // 参考 MetaMask 多端点机制：内置 Infura 端点作为备用，请求失败时自动切换。
    // Infura 免费版每天 3 百万 credits（约 10 万次 eth_call），足够作为备用节点。
    private static final String KEY_INFURA_API_KEY = "infura_api_key";

    // 内置默认 Infura Project ID（红魔团队注册，2026-08-16 获取，免费 Core 方案）。
    // 用户可在「节点设置 -> 更多 -> Infura 备用节点」中覆盖或清除，覆盖后以用户配置为准。
    private static final String DEFAULT_INFURA_API_KEY =
        assemble("dea361324a3f4b", "2c947fd0c2cfb", "71e68");

    // chain -> Infura 官方网络名（仅 Infura 支持、钱包也支持的链，含 EVM 与 Solana）
    // Infura 不提供钱包其余链（CORE/FTM/GLMR/KAVA/ONE 及 TRX/SUI/APT/ADA/NEAR/ATOM/DOT/ALGO/ICP/XTZ）的端点
    private static final Map<String, String> INFURA_NETWORKS = new HashMap<>();
    static {
        INFURA_NETWORKS.put("ETH", "mainnet");
        INFURA_NETWORKS.put("BNB", "bsc");
        INFURA_NETWORKS.put("MATIC", "polygon-mainnet");
        INFURA_NETWORKS.put("ARB", "arbitrum-mainnet");
        INFURA_NETWORKS.put("AVAX", "avalanche-mainnet");
        INFURA_NETWORKS.put("CELO", "celo-mainnet");
        INFURA_NETWORKS.put("SOL", "solana-mainnet");
    }

    /**
     * 运行时组装内置 RPC 密钥（拆段拼接），避免完整密钥以单一字符串常量暴露在 dex/APK 字符串表中，
     * 提高反编译时直接提取的难度。纯客户端、零服务器成本、不影响用户体验；
     * 仅用于内置默认值，用户在「节点设置」里配置/覆盖的 Key 始终优先于内置默认。
     */
    private static String assemble(String... parts) {
        StringBuilder sb = new StringBuilder(parts.length * 8);
        for (String p : parts) sb.append(p);
        return sb.toString();
    }

    /**
     * 获取 Infura Project ID
     * 未配置时返回内置默认 Key（红魔团队注册），用户可在节点设置中覆盖；传空字符串清除时恢复默认
     */
    public static String getInfuraApiKey(Context ctx) {
        if (ctx == null) return DEFAULT_INFURA_API_KEY;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = prefs.getString(KEY_INFURA_API_KEY, "");
        if (key == null || key.trim().isEmpty()) return DEFAULT_INFURA_API_KEY;
        return key.trim();
    }

    /**
     * 保存/清除 Infura Project ID（传 null 或空字符串即恢复内置默认）
     */
    public static void setInfuraApiKey(Context ctx, String key) {
        if (ctx == null) return;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_INFURA_API_KEY, key == null ? "" : key.trim()).apply();
    }

    /**
     * 返回当前链的 Infura 备用节点（需已配置 Project ID 且该链受 Infura 支持）
     */
    public static NodeEntry[] getInfuraNodes(Context ctx, String chain) {
        if (ctx == null || chain == null) return new NodeEntry[0];
        String key = getInfuraApiKey(ctx);
        String network = INFURA_NETWORKS.get(chain);
        if (key.isEmpty() || network == null) return new NodeEntry[0];
        return new NodeEntry[]{
            new NodeEntry("Infura 备用节点", "https://" + network + ".infura.io/v3/" + key)
        };
    }

    // ========== Ankr 备用节点 ==========
    // Ankr 端点格式：https://rpc.ankr.com/{网络slug}/{API Key}（需把 key 拼进 URL 路径，header 鉴权不被接受）
    // 免费 Freemium 档放行以下链；FTM/KAVA/SOL/TRX/ADA/NEAR/ATOM/ALGO/ICP/XTZ 为 Ankr Premium 付费链，免费 key 访问会被拒绝
    private static final String KEY_ANKR_API_KEY = "ankr_api_key";

    // 内置默认 Ankr API Key（红魔团队注册，2026-08-16 获取，免费 Freemium 档）。
    // 用户可在「节点设置 -> 更多 -> Ankr 备用节点」中覆盖或清除，覆盖后以用户配置为准。
    private static final String DEFAULT_ANKR_API_KEY =
        assemble("4bb3223135fca0afaadcd5498987", "066125edd425be62fb51dcb64a1db353265f");

    // chain -> Ankr 网络 slug（免费档可用、钱包也支持的链）
    // SUI 与钱包的 JSON-RPC 协议一致可用；DOT 余额走 sidecar REST 不经节点 URL；APT 当前 Ankr 无节点
    private static final Map<String, String> ANKR_NETWORKS = new HashMap<>();
    static {
        ANKR_NETWORKS.put("ETH", "eth");
        ANKR_NETWORKS.put("BNB", "bsc");
        ANKR_NETWORKS.put("MATIC", "polygon");
        ANKR_NETWORKS.put("ARB", "arbitrum");
        ANKR_NETWORKS.put("AVAX", "avalanche");
        ANKR_NETWORKS.put("CORE", "core");
        ANKR_NETWORKS.put("GLMR", "moonbeam");
        ANKR_NETWORKS.put("CELO", "celo");
        ANKR_NETWORKS.put("ONE", "harmony");
        ANKR_NETWORKS.put("SUI", "sui");
    }

    /**
     * 获取 Ankr API Key
     * 未配置时返回内置默认 Key（红魔团队注册），用户可在节点设置中覆盖；传空字符串清除时恢复默认
     */
    public static String getAnkrApiKey(Context ctx) {
        if (ctx == null) return DEFAULT_ANKR_API_KEY;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = prefs.getString(KEY_ANKR_API_KEY, "");
        if (key == null || key.trim().isEmpty()) return DEFAULT_ANKR_API_KEY;
        return key.trim();
    }

    /**
     * 保存/清除 Ankr API Key（传 null 或空字符串即恢复内置默认）
     */
    public static void setAnkrApiKey(Context ctx, String key) {
        if (ctx == null) return;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_ANKR_API_KEY, key == null ? "" : key.trim()).apply();
    }

    /**
     * 返回当前链的 Ankr 备用节点（需已配置 API Key 且该链受 Ankr 免费档支持）
     */
    public static NodeEntry[] getAnkrNodes(Context ctx, String chain) {
        if (ctx == null || chain == null) return new NodeEntry[0];
        String key = getAnkrApiKey(ctx);
        String slug = ANKR_NETWORKS.get(chain);
        if (key.isEmpty() || slug == null) return new NodeEntry[0];
        return new NodeEntry[]{
            new NodeEntry("Ankr 备用节点", "https://rpc.ankr.com/" + slug + "/" + key)
        };
    }

    // ========== GetBlock 备用节点 ==========
    // GetBlock 共享节点端点格式：https://shared.{区域}.getblock.io/{端点Token}（Token 内置于 URL，一条链对应一个端点）
    // 免费档：50K CU/天、20 RPS，最多 2 个端点。红魔团队注册（aibgsps@gmail.com，2026-08-16 获取），
    // 用户可在「节点设置 -> 更多 -> GetBlock 备用节点」中按链覆盖端点 URL，覆盖后以用户配置为准。
    private static final String PREFIX_GETBLOCK_URL = "getblock_url_";

    // chain -> GetBlock 端点 URL（内置默认，红魔团队注册）
    private static final Map<String, String> GETBLOCK_ENDPOINTS = new HashMap<>();
    static {
        GETBLOCK_ENDPOINTS.put(
            "ETH",
            "https://shared.ap-southeast-1.getblock.io/"
                + assemble("fef6ba4b5c4c48a5", "99e19bc540ad1185"));
        GETBLOCK_ENDPOINTS.put(
            "BNB",
            "https://shared.us-east-1.getblock.io/"
                + assemble("6721e367f4734f2a", "a5e4c001f5f074d7"));
    }

    /**
     * 获取某条链的 GetBlock 端点 URL（用户按链覆盖优先，否则返回内置默认；无则返回空）
     */
    public static String getGetBlockUrl(Context ctx, String chain) {
        if (chain == null) return "";
        String url = "";
        if (ctx != null) {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            url = prefs.getString(PREFIX_GETBLOCK_URL + chain, "");
        }
        if (url == null || url.trim().isEmpty()) {
            String def = GETBLOCK_ENDPOINTS.get(chain);
            return def == null ? "" : def;
        }
        return url.trim();
    }

    /**
     * 保存/清除某条链的 GetBlock 端点 URL（传 null 或空字符串即恢复内置默认）
     */
    public static void setGetBlockUrl(Context ctx, String chain, String url) {
        if (ctx == null || chain == null) return;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(PREFIX_GETBLOCK_URL + chain, url == null ? "" : url.trim()).apply();
    }

    /**
     * 返回当前链的 GetBlock 备用节点（需该链已有内置或用户配置的端点）
     */
    public static NodeEntry[] getGetBlockNodes(Context ctx, String chain) {
        if (chain == null) return new NodeEntry[0];
        String url = getGetBlockUrl(ctx, chain);
        if (url.isEmpty()) return new NodeEntry[0];
        return new NodeEntry[]{
            new NodeEntry("GetBlock 备用节点", url)
        };
    }

    // ========== dRPC 备用节点 ==========
    // dRPC 去中心化路由端点格式：https://lb.drpc.live/{网络slug}/{API Key}
    // 免费档：210M CU/月、5 个 API Key，AI 驱动的负载均衡。红魔团队注册（aibgsps@gmail.com，2026-08-16 获取）。
    // 已实测：EVM 链（ETH/BNB/MATIC/ARB/AVAX/CELO/CORE/FTM/GLMR/KAVA）与 NEAR（JSON-RPC）可直接作为本钱包备用节点。
    // 注意：TRX（dRPC 为 EVM 兼容非 TronGrid REST）、SOL（需付费档）、ATOM（REST 不兼容）未纳入。
    // 用户可在「节点设置 -> 更多 -> dRPC 备用节点」中覆盖或清除，覆盖后以用户配置为准。
    private static final String KEY_DRPC_API_KEY = "drpc_api_key";

    // 内置默认 dRPC API Key（红魔团队注册，2026-08-16 获取，免费 NodeCloud 档）。
    private static final String DEFAULT_DRPC_API_KEY =
        assemble("Am1PllmpG0QEn0nUpm", "v_faV8KWWhmQYR8YtT", "RoYgFhqK");

    // chain -> dRPC 网络 slug（实测可用、与钱包查询协议一致的链）
    // BNB 免费档偶发 429 限流（slug 有效），作为最后备选节点仍可兜底。
    private static final Map<String, String> DRPC_NETWORKS = new HashMap<>();
    static {
        DRPC_NETWORKS.put("ETH", "ethereum");
        DRPC_NETWORKS.put("BNB", "bsc");
        DRPC_NETWORKS.put("MATIC", "polygon");
        DRPC_NETWORKS.put("ARB", "arbitrum");
        DRPC_NETWORKS.put("AVAX", "avalanche");
        DRPC_NETWORKS.put("CELO", "celo");
        DRPC_NETWORKS.put("CORE", "core");
        DRPC_NETWORKS.put("FTM", "fantom");
        DRPC_NETWORKS.put("GLMR", "moonbeam");
        DRPC_NETWORKS.put("KAVA", "kava");
        DRPC_NETWORKS.put("NEAR", "near");
    }

    /**
     * 获取 dRPC API Key
     * 未配置时返回内置默认 Key（红魔团队注册），用户可在节点设置中覆盖；传空字符串清除时恢复默认
     */
    public static String getDrpcApiKey(Context ctx) {
        if (ctx == null) return DEFAULT_DRPC_API_KEY;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = prefs.getString(KEY_DRPC_API_KEY, "");
        if (key == null || key.trim().isEmpty()) return DEFAULT_DRPC_API_KEY;
        return key.trim();
    }

    /**
     * 保存/清除 dRPC API Key（传 null 或空字符串即恢复内置默认）
     */
    public static void setDrpcApiKey(Context ctx, String key) {
        if (ctx == null) return;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_DRPC_API_KEY, key == null ? "" : key.trim()).apply();
    }

    /**
     * 返回当前链的 dRPC 备用节点（需该链受 dRPC 免费档支持）
     */
    public static NodeEntry[] getDrpcNodes(Context ctx, String chain) {
        if (ctx == null || chain == null) return new NodeEntry[0];
        String key = getDrpcApiKey(ctx);
        String slug = DRPC_NETWORKS.get(chain);
        if (key.isEmpty() || slug == null) return new NodeEntry[0];
        return new NodeEntry[]{
            new NodeEntry("dRPC 备用节点", "https://lb.drpc.live/" + slug + "/" + key)
        };
    }

    /**
     * 获取某条链的完整候选节点列表（按优先级排序、按 URL 去重）
     * 顺序：当前选中节点 -> 预设节点 -> Infura 备用节点 -> Ankr 备用节点 -> GetBlock 备用节点 -> dRPC 备用节点
     * 参考 MetaMask rpcEndpoints 多端点机制：请求失败时按序切换下一端点重试。
     */
    public static NodeEntry[] getCandidateNodes(Context ctx, String chain) {
        List<NodeEntry> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        String selected = getSelectedNode(ctx, chain);
        if (selected != null && !selected.isEmpty() && seen.add(selected)) {
            list.add(new NodeEntry("当前节点", selected));
        }
        for (NodeEntry e : getPresets(chain)) {
            if (seen.add(e.url)) list.add(e);
        }
        for (NodeEntry e : getInfuraNodes(ctx, chain)) {
            if (seen.add(e.url)) list.add(e);
        }
        for (NodeEntry e : getAnkrNodes(ctx, chain)) {
            if (seen.add(e.url)) list.add(e);
        }
        for (NodeEntry e : getGetBlockNodes(ctx, chain)) {
            if (seen.add(e.url)) list.add(e);
        }
        for (NodeEntry e : getDrpcNodes(ctx, chain)) {
            if (seen.add(e.url)) list.add(e);
        }
        return list.toArray(new NodeEntry[0]);
    }

    /**
     * 获取候选节点 URL 数组（去重、按优先级排序），供请求失败时轮换重试
     */
    public static String[] getCandidateNodeUrls(Context ctx, String chain) {
        NodeEntry[] nodes = getCandidateNodes(ctx, chain);
        String[] urls = new String[nodes.length];
        for (int i = 0; i < nodes.length; i++) urls[i] = nodes[i].url;
        return urls;
    }

    /**
     * 测试节点延迟（毫秒），-1 表示超时
     */
    public static long pingNode(String rpcUrl) {
        try {
            long start = System.currentTimeMillis();

            JSONObject body = new JSONObject();
            body.put("jsonrpc", "2.0");
            body.put("id", 1);
            body.put("method", "eth_blockNumber");
            body.put("params", new JSONArray());

            Request request = new Request.Builder()
                .url(rpcUrl)
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

            try (Response response = PING_CLIENT.newCall(request).execute()) {
                long elapsed = System.currentTimeMillis() - start;
                if (response.isSuccessful() && response.body() != null) {
                    String resp = response.body().string();
                    if (resp.contains("result")) return elapsed;
                }
                return -1;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    // 非 EVM 链（不能用 eth_blockNumber 测速）的链集合
    private static boolean isNonEvmChain(String chain) {
        if (chain == null) return false;
        switch (chain) {
            case "SOL":
            case "TRX":
            case "SUI":
            case "APT":
            case "ADA":
            case "NEAR":
            case "ATOM":
            case "DOT":
            case "ALGO":
            case "ICP":
            case "XTZ":
                return true;
            default:
                return false;
        }
    }

    /**
     * 链感知测速：EVM 链用 eth_blockNumber，非 EVM 链用对应原生方法。
     * 修复：SOL/TRX 等链不支持 eth_blockNumber，旧逻辑永远返回 -1（显示"超时"），
     * 但实际余额查询（getTokenAccountsByOwner 等）是通的，导致节点设置误报"连不上"。
     * @param chain 链标识（SOL/TRX/ETH/BNB...）
     * @param rpcUrl 节点 URL
     * @return 延迟毫秒，-1 表示失败/超时
     */
    public static long pingNodeSafe(String chain, String rpcUrl) {
        if (!isNonEvmChain(chain)) {
            return pingNode(rpcUrl);
        }
        try {
            long start = System.currentTimeMillis();
            JSONObject body = new JSONObject();
            body.put("jsonrpc", "2.0");
            body.put("id", 1);
            body.put("method", "getHealth");
            body.put("params", new JSONArray());

            Request request = new Request.Builder()
                .url(rpcUrl)
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

            try (Response response = PING_CLIENT.newCall(request).execute()) {
                long elapsed = System.currentTimeMillis() - start;
                if (response.isSuccessful() && response.body() != null) {
                    String resp = response.body().string();
                    // SOL: {"result":"ok"}；其他链若返回 result 也视为可用
                    if (resp.contains("result")) return elapsed;
                }
                return -1;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 获取区块高度（通过 eth_blockNumber），非 EVM 链用对应原生方法。
     */
    public static long getBlockHeight(String rpcUrl) {
        return getBlockHeightSafe(null, rpcUrl);
    }

    /**
     * 链感知区块高度：EVM 链用 eth_blockNumber，SOL 用 getSlot。
     * 避免非 EVM 链调用 eth_blockNumber 报错导致统一显示失败。
     */
    public static long getBlockHeightSafe(String chain, String rpcUrl) {
        try {
            JSONObject body = new JSONObject();
            body.put("jsonrpc", "2.0");
            body.put("id", 1);
            String method = ("SOL".equals(chain)) ? "getSlot" : "eth_blockNumber";
            body.put("method", method);
            body.put("params", new JSONArray());

            Request request = new Request.Builder()
                .url(rpcUrl)
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

            try (Response response = PING_CLIENT.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String resp = response.body().string();
                    JSONObject json = new JSONObject(resp);
                    if (json.has("result")) {
                        String result = json.getString("result");
                        if ("SOL".equals(chain)) {
                            // SOL getSlot 返回十进制数字
                            return Long.parseLong(result);
                        }
                        // EVM eth_blockNumber 返回 0x 十六进制
                        return Long.parseLong(result.substring(2), 16);
                    }
                }
                return -1;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 返回某条链的第一个可用节点 URL（按预设顺序逐个尝试，5 秒超时）。
     * 用于需要快速拿到一个可用节点的场景，不保证是最快节点。
     */
    public static String findFirstAvailableNode(String chain) {
        NodeEntry[] presets = getPresets(chain);
        for (NodeEntry entry : presets) {
            if (pingNodeSafe(chain, entry.url) > 0) return entry.url;
        }
        return presets.length > 0 ? presets[0].url : "";
    }

    /**
     * 自动选择最快节点 - 并行测速所有预设节点，取最快成功的
     * 注意：本方法会阻塞调用线程直到所有节点测速完成或超时，请在子线程调用
     */
    public static String findFastestNode(String chain) {
        NodeEntry[] presets = getPresets(chain);
        if (presets.length == 0) return "";
        if (presets.length == 1) return presets[0].url;

        // 并行测速：用 CountDownLatch 等所有节点完成，取最快
        final java.util.concurrent.atomic.AtomicReference<String> bestNode =
            new java.util.concurrent.atomic.AtomicReference<>(presets[0].url);
        final java.util.concurrent.atomic.AtomicLong bestLatency =
            new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE);
        final java.util.concurrent.CountDownLatch latch =
            new java.util.concurrent.CountDownLatch(presets.length);

        for (NodeEntry entry : presets) {
            PING_EXECUTOR.execute(() -> {
                try {
                    long latency = pingNodeSafe(chain, entry.url);
                    if (latency > 0) {
                        // CAS 更新最小延迟
                        long current;
                        do {
                            current = bestLatency.get();
                            if (latency >= current) break;
                        } while (!bestLatency.compareAndSet(current, latency));
                        if (latency == bestLatency.get()) {
                            bestNode.set(entry.url);
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            // 最多等 6 秒（单个节点最多 5 秒超时 + 1 秒余量）
            latch.await(6, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return bestNode.get();
    }
}
