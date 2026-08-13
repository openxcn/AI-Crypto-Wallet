package com.aicryptowallet.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
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
