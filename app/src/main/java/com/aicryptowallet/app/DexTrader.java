package com.aicryptowallet.app;

import android.content.Context;
import android.util.Log;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

/**
 * DEX 交易器 - 支持 Uniswap/PancakeSwap/QuickSwap/Pangolin/SpookySwap 代币兑换
 * 所有 calldata 均通过 web3j FunctionEncoder 生成，确保 ABI 编码正确
 * 优化：每条 EVM 链配置正确的 DEX Router 和 Wrapped Native；复用静态 OkHttpClient
 */
public class DexTrader {
    private static final String TAG = "DexTrader";
    private static final MediaType JSON_TYPE = MediaType.parse("application/json");
    // 复用静态 client，避免每次交易创建新实例（ConnectionPool/Dispatcher 泄漏）
    private static final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build();

    // === 各链 DEX Router 地址（V2 兼容） ===
    // Ethereum: Uniswap V2
    private static final String UNISWAP_V2_ROUTER = "0x7a250d5630B4cF539739dF2C5dAcb4c659F2488D";
    // BNB Chain: PancakeSwap V2
    private static final String PANCAKESWAP_ROUTER = "0x10ED43C718714eb63d5aA57B78B54704E256024E";
    // Polygon: QuickSwap V2
    private static final String QUICKSWAP_ROUTER = "0xa5E0829CaCED8fFDD4De3c43696c57F7D7a678ff";
    // Avalanche: Pangolin V2
    private static final String PANGOLIN_ROUTER = "0xE54Ca86531e17Ef3616d22Ca28b0D458b6C89106";
    // Fantom: SpookySwap V2
    private static final String SPOOKYSWAP_ROUTER = "0xF491e7B69E4244ad4002BC14e878a34207E38c29";
    // Moonbeam: StellaSwap V2
    private static final String STELLASWAP_ROUTER = "0x70085a09D30D6f8C4ecF6eE101404A03b60FD1c6";
    // Kava: Kinetix Finance V2
    private static final String KINETIX_ROUTER = "0x1b02dA8Cb0d097eB8D57A175b88c7D8b47998006";
    // Celo: Ubeswap V2
    private static final String UBERSWAP_ROUTER = "0xE3D8bd6Aed4F159bc8000a9cD47CfDb4F778865f";
    // Harmony: Viper V2
    private static final String VIPER_ROUTER = "0x184a8D0D76Be29a471fc91b9D5042B5b42D47063";

    // === 各链 Wrapped Native 代币 ===
    private static final String WETH = "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2";
    private static final String WBNB = "0xbb4CdB9CBd36B01bD1cBaEBF2De08d9173bc095c";
    private static final String WMATIC = "0x0d500B1d8E8eF31E21C99d1Db9A6444d3ADf1270";
    private static final String WAVAX = "0xB31f66AA3C1e785363F0875A1B3E7337D6b43E7B";
    private static final String WFTM = "0x21be370D5312f44cB42ce377BC9b8a0cEF1A4C83";
    private static final String WGLMR_REAL = "0xAcc15DC74880C9944775448304B263B191c92a3F";
    private static final String WKAVA = "0xc86c2C6d2A0a0f49c19B2a059c7BFa6a72E114F3";
    private static final String WCELO = "0x471EcE3750Da237f93B8E339c536989b8978a638";
    private static final String WONE = "0xcf664087a5BB0937081d675eF75e7A8c8Be1E24E";

    // 各链 chainId（EIP-155），用于签名防跨链重放
    private static long getChainIdForChain(String chain) {
        switch (chain) {
            case "ETH": return 1;
            case "BNB": return 56;
            case "MATIC": return 137;
            case "AVAX": return 43114;
            case "FTM": return 250;
            case "GLMR": return 1284;
            case "KAVA": return 2222;
            case "CELO": return 42220;
            case "ONE": return 1666600000;
            default: return 1;
        }
    }

    // 各链稳定币（USDT）地址，用于原生币 -> 稳定币的默认兑换
    private static String getStablecoin(String chain) {
        switch (chain) {
            case "ETH": return "0xdAC17F958D2ee523a2206206994597C13D831ec7";
            case "BNB": return "0x55d398326f99059fF775485246999027B3197955";
            case "MATIC": return "0xc2132D05D31c914a87C6611C10748AEb04B58e8F";
            case "AVAX": return "0xc7198437980c041c805A1EDcbA50c1Ce5db95118";
            case "FTM": return "0x049d68029688eAbF473097a2fA3882884671A6E7";
            case "GLMR": return "0xc234A67a4F840E49237339030f2cA0F4d6C932F4";
            case "KAVA": return "0xB44a9B6905a7a80bF4dC4D19B1e9c4Ba705dB55f";
            case "CELO": return "0x765DE816845861e75A25fCA122bb6898B8B1282a";
            case "ONE": return "0x3015697121bD4b81013C57918C9823ba658726Ce";
            default: return "";
        }
    }

    /**
     * 公开方法：获取指定链的稳定币（USDT）合约地址
     * 供 AIAgentActivity BUY 信号使用
     */
    public String getStablecoinPublic(String chain) {
        return getStablecoin(chain);
    }

    /**
     * 获取各链对应的 DEX Router 地址
     * 修复：之前 AVAX/MATIC/FTM 等都用 Ethereum 的 Uniswap V2 地址，导致 swap 必失败
     */
    private String getRouterAddress(String chain) {
        switch (chain) {
            case "BNB":   return PANCAKESWAP_ROUTER;
            case "MATIC": return QUICKSWAP_ROUTER;
            case "AVAX":  return PANGOLIN_ROUTER;
            case "FTM":   return SPOOKYSWAP_ROUTER;
            case "GLMR":  return STELLASWAP_ROUTER;
            case "KAVA":  return KINETIX_ROUTER;
            case "CELO":  return UBERSWAP_ROUTER;
            case "ONE":   return VIPER_ROUTER;
            case "ETH":
            default:      return UNISWAP_V2_ROUTER;
        }
    }

    /**
     * 获取各链的 Wrapped Native 代币地址
     */
    private String getWrappedToken(String chain) {
        switch (chain) {
            case "BNB":   return WBNB;
            case "MATIC": return WMATIC;
            case "AVAX":  return WAVAX;
            case "FTM":   return WFTM;
            case "GLMR":  return WGLMR_REAL;
            case "KAVA":  return WKAVA;
            case "CELO":  return WCELO;
            case "ONE":   return WONE;
            case "ETH":
            default:      return WETH;
        }
    }

    /**
     * 代币兑换（代币换代币 / 代币换ETH / ETH换代币）
     * @param slippage 滑点百分比，0.5 表示 0.5%
     */
    public String swapTokens(Context ctx, String tokenIn, String tokenOut,
                             double amountIn, double slippage) throws Exception {
        String chain = WalletManager.getChain(ctx);
        String rpcUrl = WalletManager.getRpcUrl(ctx, chain);
        String mnemonic = WalletManager.getMnemonic(ctx);
        String fromAddress = WalletManager.getWalletAddress(ctx);
        long chainId = getChainIdForChain(chain);

        String router = getRouterAddress(chain);
        String wrappedToken = getWrappedToken(chain);

        String actualTokenIn = tokenIn != null ? tokenIn : wrappedToken;
        String actualTokenOut = tokenOut != null ? tokenOut : wrappedToken;
        boolean isEthInput = tokenIn == null;

        int decimalsIn = isEthInput ? 18 : getTokenDecimals(rpcUrl, actualTokenIn);
        BigInteger amountInWei = toWei(amountIn, decimalsIn);

        BigInteger amountOutEstimate = getAmountsOut(rpcUrl, router, actualTokenIn, actualTokenOut, amountInWei);
        BigInteger amountOutMin = applySlippage(amountOutEstimate, slippage);

        Function fn;
        BigInteger value;
        if (isEthInput) {
            // swapExactETHForTokens(uint256 amountOutMin, address[] path, address to, uint256 deadline)
            fn = new Function("swapExactETHForTokens",
                Arrays.asList(
                    new Uint256(amountOutMin),
                    new DynamicArray<>(Address.class, Arrays.asList(
                        new Address(wrappedToken),
                        new Address(actualTokenOut)
                    )),
                    new Address(fromAddress),
                    new Uint256(BigInteger.valueOf(System.currentTimeMillis() / 1000 + 300))
                ),
                Collections.singletonList(new TypeReference<Uint256>() {})
            );
            value = amountInWei;
        } else {
            // 需要先 approve Router，并获取 approve 使用的 nonce（swap 用 nonce+1 本地递增）
            long approveNonce = approveToken(ctx, rpcUrl, mnemonic, actualTokenIn, router, amountInWei, chainId);
            // swapExactTokensForTokens(uint256 amountIn, uint256 amountOutMin, address[] path, address to, uint256 deadline)
            fn = new Function("swapExactTokensForTokens",
                Arrays.asList(
                    new Uint256(amountInWei),
                    new Uint256(amountOutMin),
                    new DynamicArray<>(Address.class, Arrays.asList(
                        new Address(actualTokenIn),
                        new Address(actualTokenOut)
                    )),
                    new Address(fromAddress),
                    new Uint256(BigInteger.valueOf(System.currentTimeMillis() / 1000 + 300))
                ),
                Collections.singletonList(new TypeReference<Uint256>() {})
            );
            value = BigInteger.ZERO;
            String data = FunctionEncoder.encode(fn);
            // 用 approveNonce + 1 避免 swap 替换 approve（同 nonce 竞态）
            return sendTransactionWithNonce(ctx, rpcUrl, mnemonic, router, data, value, chainId, approveNonce + 1);
        }
        String data = FunctionEncoder.encode(fn);
        return sendTransaction(ctx, rpcUrl, mnemonic, router, data, value, chainId);
    }

    /**
     * 兑换原生代币（ETH/BNB）为稳定币 USDT（SELL 信号专用）
     * @param slippage 滑点百分比，0.5 表示 0.5%
     */
    public String swapNativeToken(Context ctx, String chain, double amountIn, double slippage) throws Exception {
        String rpcUrl = ChainAPI.getRpcUrlStatic(ctx, chain);
        String mnemonic = WalletManager.getMnemonic(ctx);
        String fromAddress = WalletManager.getWalletAddress(ctx);
        long chainId = getChainIdForChain(chain);
        String router = getRouterAddress(chain);
        String wnative = getWrappedToken(chain);
        String tokenOut = getStablecoin(chain);
        if (tokenOut == null || tokenOut.isEmpty()) {
            throw new Exception(chain + " 链未配置稳定币地址，无法执行 swap");
        }

        BigInteger amountInWei = toWei(amountIn, 18);
        BigInteger amountOutEstimate = getAmountsOut(rpcUrl, router, wnative, tokenOut, amountInWei);
        BigInteger amountOutMin = applySlippage(amountOutEstimate, slippage);

        // swapExactETHForTokens(uint256 amountOutMin, address[] path, address to, uint256 deadline)
        Function fn = new Function("swapExactETHForTokens",
            Arrays.asList(
                new Uint256(amountOutMin),
                new DynamicArray<>(Address.class, Arrays.asList(
                    new Address(wnative),
                    new Address(tokenOut)
                )),
                new Address(fromAddress),
                new Uint256(BigInteger.valueOf(System.currentTimeMillis() / 1000 + 1200))
            ),
            Collections.singletonList(new TypeReference<Uint256>() {})
        );
        String data = FunctionEncoder.encode(fn);

        return sendTransaction(ctx, rpcUrl, mnemonic, router, data, amountInWei, chainId);
    }

    /**
     * 用代币（如 USDT，ERC-20/BEP-20）兑换原生代币（BUY 信号专用）
     * 修复 CRITICAL：之前 AIAgentActivity BUY 信号错误调用 swapNativeToken（卖原生币换 USDT），
     * 方向完全相反，且 amount（USDT 数值）被当原生币数量传入，会卖出 amount 个原生币而非用 amount USDT 买币
     * @param tokenIn 输入代币合约地址（如 USDT）
     * @param amountIn 输入代币数量（人类可读，非 wei）
     * @param slippage 滑点百分比，0.5 表示 0.5%
     */
    public String swapTokensForNative(Context ctx, String chain, String tokenIn,
                                       double amountIn, double slippage) throws Exception {
        if (tokenIn == null || tokenIn.isEmpty()) {
            throw new Exception("swapTokensForNative 需要指定输入代币地址");
        }
        String rpcUrl = ChainAPI.getRpcUrlStatic(ctx, chain);
        String mnemonic = WalletManager.getMnemonic(ctx);
        String fromAddress = WalletManager.getWalletAddress(ctx);
        long chainId = getChainIdForChain(chain);
        String router = getRouterAddress(chain);
        String wnative = getWrappedToken(chain);

        int decimalsIn = getTokenDecimals(rpcUrl, tokenIn);
        BigInteger amountInWei = toWei(amountIn, decimalsIn);

        // 路径：tokenIn -> wnative -> 解包成原生币
        BigInteger amountOutEstimate = getAmountsOut(rpcUrl, router, tokenIn, wnative, amountInWei);
        BigInteger amountOutMin = applySlippage(amountOutEstimate, slippage);

        // 先 approve，并获取 approve nonce（swap 用 nonce+1 本地递增避免竞态）
        long approveNonce = approveToken(ctx, rpcUrl, mnemonic, tokenIn, router, amountInWei, chainId);

        // swapExactTokensForETH(uint256 amountIn, uint256 amountOutMin, address[] path, address to, uint256 deadline)
        Function fn = new Function("swapExactTokensForETH",
            Arrays.asList(
                new Uint256(amountInWei),
                new Uint256(amountOutMin),
                new DynamicArray<>(Address.class, Arrays.asList(
                    new Address(tokenIn),
                    new Address(wnative)
                )),
                new Address(fromAddress),
                new Uint256(BigInteger.valueOf(System.currentTimeMillis() / 1000 + 1200))
            ),
            Collections.singletonList(new TypeReference<Uint256>() {})
        );
        String data = FunctionEncoder.encode(fn);
        // 用 approveNonce + 1 避免 swap 替换 approve（同 nonce 竞态）
        return sendTransactionWithNonce(ctx, rpcUrl, mnemonic, router, data, BigInteger.ZERO, chainId, approveNonce + 1);
    }

    /**
     * 滑点保护：slippage=0.5 表示 0.5%，返回 expected * (10000 - 50) / 10000
     */
    private BigInteger applySlippage(BigInteger expected, double slippage) {
        int basisPoints = (int) Math.round(slippage * 100); // 0.5 -> 50
        if (basisPoints >= 10000) basisPoints = 9999;
        if (basisPoints < 0) basisPoints = 0;
        return expected.multiply(BigInteger.valueOf(10000 - basisPoints))
            .divide(BigInteger.valueOf(10000));
    }

    /**
     * 查询链上真实兑换输出（getAmountsOut）
     * 公开方法，供 SwapActivity 显示真实最小获得数量
     * @param ctx Context
     * @param chain 链代码（如 "ETH", "BNB"）
     * @param tokenIn 输入代币合约地址（原生币传 null）
     * @param tokenOut 输出代币合约地址（原生币传 null）
     * @param amountIn 输入数量（人类可读，如 0.5）
     * @return 输出数量（人类可读），失败抛异常
     */
    public double getAmountOutPublic(Context ctx, String chain,
                                      String tokenIn, String tokenOut,
                                      double amountIn) throws Exception {
        String rpcUrl = ChainAPI.getRpcUrlStatic(ctx, chain);
        String router = getRouterAddress(chain);
        String wnative = getWrappedToken(chain);
        String actualTokenIn = tokenIn != null ? tokenIn : wnative;
        String actualTokenOut = tokenOut != null ? tokenOut : wnative;
        int decimalsIn = (tokenIn == null) ? 18 : getTokenDecimals(rpcUrl, actualTokenIn);
        BigInteger amountInWei = toWei(amountIn, decimalsIn);
        BigInteger amountOutWei = getAmountsOut(rpcUrl, router, actualTokenIn, actualTokenOut, amountInWei);
        // 输出代币 decimals（原生币默认 18）
        int decimalsOut = (tokenOut == null) ? 18 : getTokenDecimals(rpcUrl, actualTokenOut);
        return new BigDecimal(amountOutWei).divide(BigDecimal.TEN.pow(decimalsOut)).doubleValue();
    }

    /**
     * 获取链上 DEX Router 地址（公开，供外部查询）
     */
    public String getRouterAddressPublic(String chain) {
        return getRouterAddress(chain);
    }

    private BigInteger getAmountsOut(String rpcUrl, String router,
                                     String tokenIn, String tokenOut,
                                     BigInteger amountIn) throws Exception {
        // getAmountsOut(uint256 amountIn, address[] path) - 使用 web3j FunctionEncoder
        Function fn = new Function("getAmountsOut",
            Arrays.asList(
                new Uint256(amountIn),
                new DynamicArray<>(Address.class, Arrays.asList(
                    new Address(tokenIn),
                    new Address(tokenOut)
                ))
            ),
            Collections.singletonList(new TypeReference<DynamicArray<Uint256>>() {})
        );
        String data = FunctionEncoder.encode(fn);

        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "eth_call");
        JSONArray params = new JSONArray();
        JSONObject callObj = new JSONObject();
        callObj.put("to", router);
        callObj.put("data", data);
        params.put(callObj);
        params.put("latest");
        body.put("params", params);

        Request request = new Request.Builder()
            .url(rpcUrl)
            .post(RequestBody.create(body.toString(), JSON_TYPE))
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            if (json.has("error")) {
                throw new Exception("getAmountsOut 失败: " + json.getJSONObject("error").optString("message"));
            }
            String result = json.optString("result", "0x");
            if (result.length() > 130) {
                List<Type> decoded = FunctionReturnDecoder.decode(result, fn.getOutputParameters());
                if (!decoded.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    DynamicArray<Uint256> arr = (DynamicArray<Uint256>) decoded.get(0);
                    if (!arr.getValue().isEmpty()) {
                        return arr.getValue().get(arr.getValue().size() - 1).getValue();
                    }
                }
                // 兜底：取最后 64 字符
                String amountOutHex = result.substring(result.length() - 64);
                return new BigInteger(amountOutHex, 16);
            }
        }
        // 失败时抛异常，避免用假数据导致三明治攻击
        throw new Exception("getAmountsOut 返回为空，无法计算输出数量（路径可能无流动性）");
    }

    /**
     * approve 代币并返回使用的 nonce（用于后续 swap 本地递增）
     * 修复：之前 approve 后 Thread.sleep(2000) 等 tx 入 mempool，但 Ethereum 出块 12 秒，
     * 2 秒不够。如果 approve 还没入 mempool，swap 查 pending nonce 会得到相同值，
     * 导致 swap tx 替换 approve tx（同 nonce），approve 被丢弃，swap 必然失败。
     * 现在改为本地递增 nonce：approve 用 N，swap 用 N+1，避免竞态。
     */
    private long approveToken(Context ctx, String rpcUrl, String mnemonic,
                              String token, String spender, BigInteger amount, long chainId) throws Exception {
        String fromAddress = WalletManager.getWalletAddress(ctx);
        long nonce = getNonce(rpcUrl, fromAddress);
        // approve(address spender, uint256 amount) - 使用 web3j FunctionEncoder
        Function approveFn = new Function("approve",
            Arrays.asList(
                new Address(spender),
                new Uint256(amount)
            ),
            Collections.emptyList()
        );
        String data = FunctionEncoder.encode(approveFn);
        sendTransactionWithNonce(ctx, rpcUrl, mnemonic, token, data, BigInteger.ZERO, chainId, nonce);
        return nonce; // 返回给调用方，swap 用 nonce+1
    }

    private String sendTransaction(Context ctx, String rpcUrl, String mnemonic,
                                   String to, String data, BigInteger value, long chainId) throws Exception {
        String fromAddress = WalletManager.getWalletAddress(ctx);
        long nonce = getNonce(rpcUrl, fromAddress);
        return sendTransactionWithNonce(ctx, rpcUrl, mnemonic, to, data, value, chainId, nonce);
    }

    /**
     * 公开方法：执行任意 raw 交易（供 ContractCaller 调用）
     * Agent Runtime 通过此方法让 AI 能调用任意合约
     * @param ctx Context
     * @param chain 链标识
     * @param to 目标合约地址
     * @param data 已编码的 calldata（hex 字符串，含 0x 前缀）
     * @param value 附带的原生币数量（wei）
     * @return 交易哈希
     */
    public String executeRawTransaction(Context ctx, String chain, String to,
                                         String data, BigInteger value) throws Exception {
        String rpcUrl = ChainAPI.getRpcUrlStatic(ctx, chain);
        String mnemonic = WalletManager.getMnemonic(ctx);
        long chainId = getChainIdForChain(chain);
        // 去掉 0x 前缀（如果有）后重新编码
        if (data.startsWith("0x")) data = data.substring(2);
        // 直接构造 RawTransaction，data 字段 web3j 接受 hex
        return sendTransactionWithData(ctx, rpcUrl, mnemonic, to, data, value, chainId);
    }

    /**
     * 内部方法：用指定 data 签名并广播交易
     */
    private String sendTransactionWithData(Context ctx, String rpcUrl, String mnemonic,
                                            String to, String dataHex, BigInteger value,
                                            long chainId) throws Exception {
        String fromAddress = WalletManager.getWalletAddress(ctx);
        String chain = WalletManager.getChain(ctx);

        long nonce = getNonce(rpcUrl, fromAddress);
        BigInteger gasPrice = getGasPrice(rpcUrl);
        // estimateGas 需要 0x 前缀的 data
        BigInteger gasLimit = estimateGas(rpcUrl, fromAddress, to, "0x" + dataHex, value);

        Credentials credentials = getCredentials(mnemonic, chain);

        RawTransaction rawTransaction = RawTransaction.createTransaction(
            BigInteger.valueOf(nonce),
            gasPrice,
            gasLimit,
            to,
            value,
            dataHex  // web3j 内部会处理
        );

        byte[] signedMessage = TransactionEncoder.signMessage(rawTransaction, chainId, credentials);
        String signedTx = Numeric.toHexString(signedMessage);

        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "eth_sendRawTransaction");
        JSONArray params = new JSONArray();
        params.put(signedTx);
        body.put("params", params);

        Request request = new Request.Builder()
            .url(rpcUrl)
            .post(RequestBody.create(body.toString(), JSON_TYPE))
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            if (json.has("error")) {
                throw new Exception("广播失败: " + json.getJSONObject("error").optString("message"));
            }
            String result = json.optString("result", "");
            if (result.isEmpty()) {
                throw new Exception("广播未返回交易哈希（节点可能异常）");
            }
            return result;
        }
    }

    /**
     * 用指定 nonce 签名并广播交易（支持本地递增避免 approve/swap 竞态）
     */
    private String sendTransactionWithNonce(Context ctx, String rpcUrl, String mnemonic,
                                            String to, String data, BigInteger value, long chainId,
                                            long nonce) throws Exception {
        String chain = WalletManager.getChain(ctx);

        BigInteger gasPrice = getGasPrice(rpcUrl);
        BigInteger gasLimit = estimateGas(rpcUrl, WalletManager.getWalletAddress(ctx), to, data, value);

        // 修复：传入 chain 让 getCredentials 用 wallet-core 按链推导私钥
        Credentials credentials = getCredentials(mnemonic, chain);

        RawTransaction rawTransaction = RawTransaction.createTransaction(
            BigInteger.valueOf(nonce),
            gasPrice,
            gasLimit,
            to,
            value,
            data
        );

        // EIP-155: 带 chainId 签名，防止跨链重放攻击
        byte[] signedMessage = TransactionEncoder.signMessage(rawTransaction, chainId, credentials);
        String signedTx = Numeric.toHexString(signedMessage);

        // 广播交易
        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "eth_sendRawTransaction");
        JSONArray params = new JSONArray();
        params.put(signedTx);
        body.put("params", params);

        Request request = new Request.Builder()
            .url(rpcUrl)
            .post(RequestBody.create(body.toString(), JSON_TYPE))
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            if (json.has("error")) {
                throw new Exception("广播失败: " + json.getJSONObject("error").optString("message"));
            }
            String result = json.optString("result", "");
            if (result.isEmpty()) {
                throw new Exception("广播未返回交易哈希（节点可能异常）");
            }
            return result;
        }
    }

    /**
     * 获取 web3j Credentials
     * 修复 CRITICAL：之前硬编码 m/44'/60'/0'/0/0 推导私钥，与 WalletManager.deriveAddress
     * 使用 wallet-core 按链 coin type 推导地址不一致。BNB 链 CoinType.SMARTCHAIN 的 coin type 是 714，
     * 推导出的地址与 m/44'/60'/0'/0/0 推出的不同，导致 swap 签名时私钥与地址不匹配。
     * 现在复用 WalletManager.getPrivateKey(mnemonic, chain) 获取 wallet-core 推导的私钥字节，
     * 确保与地址推导完全同源。
     */
    private Credentials getCredentials(String mnemonic, String chain) throws Exception {
        wallet.core.jni.PrivateKey privKey = WalletManager.getPrivateKey(mnemonic, chain);
        if (privKey == null) {
            throw new Exception("无法用 wallet-core 推导私钥（链: " + chain + "）");
        }
        byte[] privKeyBytes = privKey.data();
        ECKeyPair keyPair = ECKeyPair.create(privKeyBytes);
        return Credentials.create(keyPair);
    }

    /**
     * 公开静态方法：供 DAppBrowserActivity 等其他类复用 Credentials 推导
     * @param ctx Context
     * @param chain 链标识
     * @return web3j Credentials
     */
    public static Credentials getCredentialsForChain(android.content.Context ctx, String chain) throws Exception {
        String mnemonic = WalletManager.getMnemonic(ctx);
        return new DexTrader().getCredentials(mnemonic, chain);
    }

    private long getNonce(String rpcUrl, String address) throws Exception {
        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "eth_getTransactionCount");
        JSONArray params = new JSONArray();
        params.put(address);
        params.put("pending"); // 用 pending 包含未确认交易，避免 approve/swap nonce 竞态
        body.put("params", params);

        Request request = new Request.Builder()
            .url(rpcUrl)
            .post(RequestBody.create(body.toString(), JSON_TYPE))
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            String nonceHex = json.optString("result", "0x0");
            return Long.parseLong(nonceHex.substring(2), 16);
        }
    }

    private BigInteger getGasPrice(String rpcUrl) throws Exception {
        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "eth_gasPrice");
        body.put("params", new JSONArray());

        Request request = new Request.Builder()
            .url(rpcUrl)
            .post(RequestBody.create(body.toString(), JSON_TYPE))
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            String gasPriceHex = json.optString("result", "0x0");
            return new BigInteger(gasPriceHex.substring(2), 16);
        }
    }

    private BigInteger estimateGas(String rpcUrl, String from, String to,
                                   String data, BigInteger value) throws Exception {
        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "eth_estimateGas");
        JSONArray params = new JSONArray();
        JSONObject txObj = new JSONObject();
        txObj.put("from", from);
        txObj.put("to", to);
        txObj.put("data", data);
        txObj.put("value", "0x" + value.toString(16));
        params.put(txObj);
        body.put("params", params);

        Request request = new Request.Builder()
            .url(rpcUrl)
            .post(RequestBody.create(body.toString(), JSON_TYPE))
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            // 修复：之前未检查 error 字段，estimateGas 失败（交易将 revert）时回退到 0x5208（21000）
            // 21000 是普通转账 gas，swap 通常需要 100000-300000，回退值导致 swap 必然 out-of-gas 失败
            if (json.has("error")) {
                throw new Exception("estimateGas 失败（交易可能 revert）: "
                    + json.getJSONObject("error").optString("message"));
            }
            String gasHex = json.optString("result", "");
            if (gasHex.isEmpty() || gasHex.length() < 3) {
                throw new Exception("estimateGas 未返回有效结果");
            }
            BigInteger gas = new BigInteger(gasHex.substring(2), 16);
            // 修复：设上限防止节点返回异常大值（如区块 gas limit ~30M）导致用户付巨额 gas
            BigInteger GAS_LIMIT_MAX = BigInteger.valueOf(1_000_000);
            if (gas.compareTo(GAS_LIMIT_MAX) > 0) {
                gas = GAS_LIMIT_MAX;
            }
            // 加 20% 余量
            return gas.multiply(BigInteger.valueOf(120)).divide(BigInteger.valueOf(100));
        }
    }

    private int getTokenDecimals(String rpcUrl, String tokenAddress) throws Exception {
        // decimals() - 使用 web3j FunctionEncoder
        Function decimalsFn = new Function("decimals",
            Collections.emptyList(),
            Collections.singletonList(new TypeReference<Uint8>() {})
        );
        String data = FunctionEncoder.encode(decimalsFn);

        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "eth_call");
        JSONArray params = new JSONArray();
        JSONObject callObj = new JSONObject();
        callObj.put("to", tokenAddress);
        callObj.put("data", data);
        params.put(callObj);
        params.put("latest");
        body.put("params", params);

        Request request = new Request.Builder()
            .url(rpcUrl)
            .post(RequestBody.create(body.toString(), JSON_TYPE))
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            String result = json.optString("result", "0x");
            // 防护空返回或非标准合约，默认 18
            if (result.length() <= 2 || result.equals("0x")) return 18;
            return Integer.parseInt(result.substring(2), 16);
        }
    }

    private BigInteger toWei(double amount, int decimals) {
        // 用 BigDecimal 避免浮点精度损失（如 1.1 * 1e18 截断少 1 wei）
        return BigDecimal.valueOf(amount)
            .multiply(BigDecimal.TEN.pow(decimals))
            .toBigInteger();
    }
}
