package com.aicryptowallet.app;

import android.content.Context;
import android.content.Intent;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

/**
 * 跨链兑换辅助类。
 *
 * 将内部链码/代币映射为第三方跨链兑换服务（ChangeNOW、Changelly、SimpleSwap）
 * 可识别的参数，并生成可直接在 DAppBrowserActivity 中打开的兑换 URL。
 *
 * 设计原则：
 * 1. 不维护大量代币元数据，只映射主流链原生币和常用包装代币。
 * 2. 代币用合约地址表示时，优先尝试映射为兑换服务认识的 ticker；无法映射时保留合约地址并附加到 URL 的备注字段。
 * 3. 所有 URL 均通过 DAppBrowserActivity 打开，沿用已有的 WebView 安全策略和钱包注入。
 */
public class CrossChainSwapHelper {

    /** 支持的跨链兑换服务商 */
    public enum Provider {
        CHANGENOW,
        CHANGELLY,
        SIMPLESWAP
    }

    // 内部链码 -> 各服务商链/网络代码
    private static final Map<String, Map<Provider, String>> CHAIN_CODE_MAP = new HashMap<>();
    static {
        putChain("ETH", "eth", "ethereum", "eth");
        putChain("BNB", "bsc", "bsc", "bnb");
        putChain("MATIC", "matic", "matic", "matic");
        putChain("ARB", "arbitrum", "arbitrum", "arb");
        putChain("AVAX", "avax", "avax_c", "avax");
        putChain("FTM", "ftm", "ftm", "ftm");
        putChain("CORE", "core", "core", "core");
        putChain("TRX", "trx", "trx", "trx");
        putChain("SOL", "sol", "sol", "sol");
        putChain("NEAR", "near", "near", "near");
        putChain("ADA", "ada", "ada", "ada");
        putChain("DOT", "dot", "dot", "dot");
        putChain("ATOM", "atom", "atom", "atom");
        putChain("SUI", "sui", "sui", "sui");
        putChain("APT", "apt", "apt", "apt");
        putChain("ALGO", "algo", "algo", "algo");
        putChain("XTZ", "xtz", "xtz", "xtz");
    }

    private static void putChain(String internal, String changeNow, String changelly, String simpleSwap) {
        Map<Provider, String> m = new HashMap<>();
        m.put(Provider.CHANGENOW, changeNow);
        m.put(Provider.CHANGELLY, changelly);
        m.put(Provider.SIMPLESWAP, simpleSwap);
        CHAIN_CODE_MAP.put(internal, m);
    }

    /**
     * 构建跨链兑换 URL。
     *
     * @param provider        服务商
     * @param fromChain       付出链内部代码（如 ETH/BNB/TRX/SOL）
     * @param toChain         目标链内部代码
     * @param fromAsset       付出资产：原生币传 "NATIVE"，代币传合约地址
     * @param toAsset         目标资产：原生币传 "NATIVE"，代币传合约地址
     * @param amount          付出数量（人类可读单位）
     * @param destinationAddr 目标链收款地址
     * @param refundAddr      付出链退款地址（可选，部分服务支持）
     * @return 可直接打开的 HTTPS URL
     */
    public static String buildUrl(Provider provider,
                                  String fromChain,
                                  String toChain,
                                  String fromAsset,
                                  String toAsset,
                                  double amount,
                                  String destinationAddr,
                                  String refundAddr) {
        String fromSymbol = resolveAssetSymbol(provider, fromChain, fromAsset);
        String toSymbol = resolveAssetSymbol(provider, toChain, toAsset);
        String fromNetwork = getProviderChainCode(fromChain, provider);
        String toNetwork = getProviderChainCode(toChain, provider);

        // 如果映射不到链代码，保持内部代码，让用户在页面上手动选择
        if (fromNetwork == null) fromNetwork = fromChain.toLowerCase();
        if (toNetwork == null) toNetwork = toChain.toLowerCase();

        try {
            switch (provider) {
                case CHANGENOW:
                    return buildChangeNowUrl(fromNetwork, toNetwork, fromSymbol, toSymbol,
                        amount, destinationAddr, refundAddr, fromAsset, toAsset);
                case CHANGELLY:
                    return buildChangellyUrl(fromNetwork, toNetwork, fromSymbol, toSymbol,
                        amount, destinationAddr, refundAddr, fromAsset, toAsset);
                case SIMPLESWAP:
                    return buildSimpleSwapUrl(fromNetwork, toNetwork, fromSymbol, toSymbol,
                        amount, destinationAddr, refundAddr, fromAsset, toAsset);
                default:
                    return buildChangeNowUrl(fromNetwork, toNetwork, fromSymbol, toSymbol,
                        amount, destinationAddr, refundAddr, fromAsset, toAsset);
            }
        } catch (Exception e) {
            // URL 编码失败时回退到纯文本拼接
            return "https://changenow.io";
        }
    }

    /**
     * 使用默认服务商（ChangeNOW）构建 URL。
     */
    public static String buildUrl(String fromChain,
                                  String toChain,
                                  String fromAsset,
                                  String toAsset,
                                  double amount,
                                  String destinationAddr,
                                  String refundAddr) {
        return buildUrl(Provider.CHANGENOW, fromChain, toChain, fromAsset, toAsset,
            amount, destinationAddr, refundAddr);
    }

    private static String buildChangeNowUrl(String fromNetwork,
                                            String toNetwork,
                                            String fromSymbol,
                                            String toSymbol,
                                            double amount,
                                            String destinationAddr,
                                            String refundAddr,
                                            String fromAsset,
                                            String toAsset) throws Exception {
        StringBuilder sb = new StringBuilder("https://changenow.io/exchange");
        sb.append("?from=").append(encode(fromSymbol));
        sb.append("&to=").append(encode(toSymbol));
        sb.append("&amount=").append(amount);
        if (destinationAddr != null && !destinationAddr.isEmpty()) {
            sb.append("&address=").append(encode(destinationAddr));
        }
        if (refundAddr != null && !refundAddr.isEmpty()) {
            sb.append("&refundAddress=").append(encode(refundAddr));
        }
        // 附加链/合约备注，帮助用户核对
        sb.append("&fromNetwork=").append(encode(fromNetwork));
        sb.append("&toNetwork=").append(encode(toNetwork));
        appendContractNotes(sb, fromAsset, toAsset);
        return sb.toString();
    }

    private static String buildChangellyUrl(String fromNetwork,
                                            String toNetwork,
                                            String fromSymbol,
                                            String toSymbol,
                                            double amount,
                                            String destinationAddr,
                                            String refundAddr,
                                            String fromAsset,
                                            String toAsset) throws Exception {
        StringBuilder sb = new StringBuilder("https://changelly.com");
        sb.append("?from=").append(encode(fromSymbol));
        sb.append("&to=").append(encode(toSymbol));
        sb.append("&amount=").append(amount);
        if (destinationAddr != null && !destinationAddr.isEmpty()) {
            sb.append("&address=").append(encode(destinationAddr));
        }
        if (refundAddr != null && !refundAddr.isEmpty()) {
            sb.append("&refundAddress=").append(encode(refundAddr));
        }
        sb.append("&fromNetwork=").append(encode(fromNetwork));
        sb.append("&toNetwork=").append(encode(toNetwork));
        appendContractNotes(sb, fromAsset, toAsset);
        return sb.toString();
    }

    private static String buildSimpleSwapUrl(String fromNetwork,
                                             String toNetwork,
                                             String fromSymbol,
                                             String toSymbol,
                                             double amount,
                                             String destinationAddr,
                                             String refundAddr,
                                             String fromAsset,
                                             String toAsset) throws Exception {
        StringBuilder sb = new StringBuilder("https://simpleswap.io");
        sb.append("?symbol=").append(encode(fromSymbol));
        sb.append("&symbolTo=").append(encode(toSymbol));
        sb.append("&amount=").append(amount);
        if (destinationAddr != null && !destinationAddr.isEmpty()) {
            sb.append("&address=").append(encode(destinationAddr));
        }
        if (refundAddr != null && !refundAddr.isEmpty()) {
            sb.append("&refundAddress=").append(encode(refundAddr));
        }
        sb.append("&fromNetwork=").append(encode(fromNetwork));
        sb.append("&toNetwork=").append(encode(toNetwork));
        appendContractNotes(sb, fromAsset, toAsset);
        return sb.toString();
    }

    private static void appendContractNotes(StringBuilder sb, String fromAsset, String toAsset) throws Exception {
        if (fromAsset != null && !fromAsset.isEmpty() && !"NATIVE".equalsIgnoreCase(fromAsset)) {
            sb.append("&fromContract=").append(encode(fromAsset));
        }
        if (toAsset != null && !toAsset.isEmpty() && !"NATIVE".equalsIgnoreCase(toAsset)) {
            sb.append("&toContract=").append(encode(toAsset));
        }
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value != null ? value : "", "UTF-8");
    }

    /**
     * 将内部资产标识解析为兑换服务可识别的 ticker。
     * 原生币用链主币符号，代币尝试查表，无法识别时返回合约地址本身。
     */
    private static String resolveAssetSymbol(Provider provider, String chain, String asset) {
        if (asset == null || asset.isEmpty() || "NATIVE".equalsIgnoreCase(asset)) {
            return getNativeSymbol(chain);
        }
        String key = (chain + "_" + asset).toLowerCase();
        String mapped = TOKEN_SYMBOL_MAP.get(key);
        if (mapped != null) return mapped;
        // 尝试用合约地址前 6 位作为备注符号
        return asset.length() > 6 ? asset.substring(0, 6) : asset;
    }

    private static String getNativeSymbol(String chain) {
        switch (chain.toUpperCase()) {
            case "ETH": return "eth";
            case "BNB": return "bnb";
            case "MATIC": return "matic";
            case "ARB": return "eth"; // Arbitrum 原生 ETH
            case "AVAX": return "avax";
            case "FTM": return "ftm";
            case "CORE": return "core";
            case "TRX": return "trx";
            case "SOL": return "sol";
            case "NEAR": return "near";
            case "ADA": return "ada";
            case "DOT": return "dot";
            case "ATOM": return "atom";
            case "SUI": return "sui";
            case "APT": return "apt";
            case "ALGO": return "algo";
            case "XTZ": return "xtz";
            default: return chain.toLowerCase();
        }
    }

    private static String getProviderChainCode(String chain, Provider provider) {
        Map<Provider, String> m = CHAIN_CODE_MAP.get(chain.toUpperCase());
        return m != null ? m.get(provider) : null;
    }

    /**
     * 打开跨链兑换页面。
     *
     * @param ctx      上下文
     * @param url      兑换 URL
     * @param chain    当前链上下文（用于日志）
     * @param provider 服务商（仅用于日志展示）
     */
    public static void openCrossChainSwap(Context ctx, String url, String chain, Provider provider) {
        Logger.action(ctx, "跨链兑换", "打开 " + provider.name() + " URL=" + url + " chain=" + chain, null);
        Intent intent = new Intent(ctx, DAppBrowserActivity.class);
        intent.putExtra("url", url);
        intent.putExtra("title", "跨链兑换");
        intent.putExtra("source", "cross_chain_swap");
        if (!(ctx instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        ctx.startActivity(intent);
    }

    /**
     * 用默认服务商打开跨链兑换页面。
     */
    public static void openCrossChainSwap(Context ctx, String url, String chain) {
        openCrossChainSwap(ctx, url, chain, Provider.CHANGENOW);
    }

    // 主流包装代币/稳定币符号映射（链码_合约地址小写 -> 服务商 ticker）
    private static final Map<String, String> TOKEN_SYMBOL_MAP = new HashMap<>();
    static {
        // BNB Chain
        TOKEN_SYMBOL_MAP.put("bnb_0x55d398326f99059ff775485246999027b3197955".toLowerCase(), "usdt"); // BSC USDT
        TOKEN_SYMBOL_MAP.put("bnb_0x8ac76a51cc950d9822d68b83fe1ad97b32cd580d".toLowerCase(), "usdc"); // BSC USDC
        TOKEN_SYMBOL_MAP.put("bnb_0x2170ed0880ac9a755fd29b2688956bd959f933f8".toLowerCase(), "eth");  // BSC ETH
        TOKEN_SYMBOL_MAP.put("bnb_0x6679eb24f59dfe1117ab2e9e8635391f3479b4a7".toLowerCase(), "trx");  // BSC TRX

        // Ethereum
        TOKEN_SYMBOL_MAP.put("eth_0xdac17f958d2ee523a2206206994597c13d831ec7".toLowerCase(), "usdt"); // ERC20 USDT
        TOKEN_SYMBOL_MAP.put("eth_0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48".toLowerCase(), "usdc"); // ERC20 USDC
        TOKEN_SYMBOL_MAP.put("eth_0x2260fac5e5542a773aa44fbcfedf7c193bc2c599".toLowerCase(), "wbtc"); // WBTC
        TOKEN_SYMBOL_MAP.put("eth_0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2".toLowerCase(), "weth"); // WETH

        // TRON
        TOKEN_SYMBOL_MAP.put("trx_TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t".toLowerCase(), "usdt"); // TRC20 USDT
        TOKEN_SYMBOL_MAP.put("trx_TPYmHEhy5n8TCEfYGqW2rP1ggAbYkGmDy".toLowerCase(), "usdt");  // TRC20 USDT 备选

        // Polygon
        TOKEN_SYMBOL_MAP.put("matic_0xc2132d05d31c914a87c6611c10748aeb04b58e8f".toLowerCase(), "usdt"); // Polygon USDT
        TOKEN_SYMBOL_MAP.put("matic_0x2791bca1f2de4661ed88a30c99a7a9449aa84174".toLowerCase(), "usdc"); // Polygon USDC

        // Arbitrum
        TOKEN_SYMBOL_MAP.put("arb_0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9".toLowerCase(), "usdt"); // Arb USDT
        TOKEN_SYMBOL_MAP.put("arb_0xaf88d065e77c8cc2239327c5edb3a432268e5831".toLowerCase(), "usdc"); // Arb USDC
    }
}
