package com.aicryptowallet.app.crosschain;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.aicryptowallet.app.ChainAPI;
import com.aicryptowallet.app.Logger;
import com.aicryptowallet.app.WalletManager;
import com.google.protobuf.ByteString;
import org.json.JSONObject;
import org.web3j.utils.Numeric;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import wallet.core.java.AnySigner;
import wallet.core.jni.CoinType;
import wallet.core.jni.HDWallet;
import wallet.core.jni.PrivateKey;
import wallet.core.jni.proto.Ethereum;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 跨链兑换管理器：聚合多 Provider 报价、限额检查、风险确认、执行签名广播
 */
public class CrossChainSwapManager {

    private static final ExecutorService executor = Executors.newFixedThreadPool(3);
    private static final OkHttpClient rpcClient = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build();
    private static final MediaType JSON_TYPE = MediaType.parse("application/json");

    private final Context ctx;
    private final List<CrossChainProvider> providers = new ArrayList<>();
    private final CrossChainLimitConfig limitConfig;

    public CrossChainSwapManager(Context ctx) {
        this.ctx = ctx != null ? ctx.getApplicationContext() : null;
        this.limitConfig = new CrossChainLimitConfig(ctx);
        providers.add(new StargateCrossChainProvider(ctx));
    }

    public CrossChainLimitConfig getLimitConfig() {
        return limitConfig;
    }

    /**
     * 获取所有 Provider 的最优报价
     * @param amountUsd 用于限额判断的 USD 金额（调用方传入）
     */
    public CrossChainQuote findBestQuote(CrossChainRequest request, double amountUsd) {
        Logger.info(ctx, "CrossChain", "开始多 Provider 比价: " + request.fromChain + " → " + request.toChain);

        // 限额预检查
        CrossChainLimitConfig.LimitCheckResult limit = limitConfig.check(amountUsd);
        if (!limit.allowed) {
            Logger.warning(ctx, "CrossChain", "限额未通过: " + limit.reason);
            return null;
        }

        List<Future<CrossChainQuote>> futures = new ArrayList<>();
        for (CrossChainProvider p : providers) {
            if (!p.isSupported(request.fromChain, request.toChain, request.fromToken, request.toToken)) {
                continue;
            }
            Future<CrossChainQuote> f = executor.submit(new Callable<CrossChainQuote>() {
                @Override
                public CrossChainQuote call() {
                    try {
                        return p.quote(request);
                    } catch (Exception e) {
                        Logger.error(ctx, "CrossChain", p.getName() + " 报价失败", e);
                        return null;
                    }
                }
            });
            futures.add(f);
        }

        List<CrossChainQuote> quotes = new ArrayList<>();
        for (Future<CrossChainQuote> f : futures) {
            try {
                CrossChainQuote q = f.get(15, TimeUnit.SECONDS);
                if (q != null) quotes.add(q);
            } catch (Exception e) {
                Logger.error(ctx, "CrossChain", "获取报价超时或失败", e);
            }
        }

        if (quotes.isEmpty()) {
            Logger.warning(ctx, "CrossChain", "没有可用 Provider 报价");
            return null;
        }

        // 选择规则：到账数量最多，且总成本最低
        Collections.sort(quotes, (a, b) -> {
            double scoreA = score(a);
            double scoreB = score(b);
            return Double.compare(scoreB, scoreA);
        });

        CrossChainQuote best = quotes.get(0);
        Logger.success(ctx, "CrossChain", "最优报价: " + best.providerName + ", 到账 " + best.toAmount);
        return best;
    }

    /**
     * 执行跨链兑换（带风险确认和限额检查）
     * @param callback 结果回调
     */
    public void execute(final CrossChainRequest request, final double amountUsd,
                        final ExecutionCallback callback) {
        executor.execute(() -> {
            try {
                // 1. 限额检查
                CrossChainLimitConfig.LimitCheckResult limit = limitConfig.check(amountUsd);
                if (!limit.allowed) {
                    Logger.warning(ctx, "CrossChain", "超过限额: " + limit.reason);
                    post(() -> CrossChainRiskDialog.showOverLimitSuggestion(ctx, limit.reason,
                        new CrossChainRiskDialog.Callback() {
                            @Override public void onConfirmed() {}
                            @Override public void onCancelled() {
                                if (callback != null) callback.onResult(false, null, "超过限额: " + limit.reason);
                            }
                        }));
                    return;
                }

                // 2. 获取最优报价
                final CrossChainQuote quote = findBestQuote(request, amountUsd);
                if (quote == null) {
                    String err = "当前跨链桥暂不支持 " + request.fromChain + " -> " + request.toChain
                        + " 的 " + request.fromToken + " -> " + request.toToken + " 兑换。"
                        + "目前仅支持 ETH/BNB/AVAX/MATIC/ARB/OP/BASE 等 EVM 链之间的 USDT 同质跨链；"
                        + "TRX/SOL/BTC/ADA 等非 EVM 链原生币请创建该链钱包后买入，或在当前链购买包装版本。";
                    Logger.warning(ctx, "CrossChain", err);
                    if (callback != null) callback.onResult(false, null, err);
                    return;
                }

                // 3. 首次使用需确认风险协议
                if (!limitConfig.isRiskConfirmed()) {
                    final CrossChainQuote finalQuote = quote;
                    post(() -> CrossChainRiskDialog.showRiskConfirm(ctx, quote.providerName,
                        quote.fromChain + " → " + quote.toChain, amountUsd,
                        new CrossChainRiskDialog.Callback() {
                            @Override public void onConfirmed() {
                                limitConfig.setRiskConfirmed(true);
                                executeAfterConfirm(request, finalQuote, amountUsd, callback);
                            }
                            @Override public void onCancelled() {
                                if (callback != null) callback.onResult(false, null, "用户取消风险确认");
                            }
                        }));
                    return;
                }

                // 4. 已确认风险，直接执行
                executeAfterConfirm(request, quote, amountUsd, callback);

            } catch (Exception e) {
                Logger.error(ctx, "CrossChain", "执行跨链兑换异常", e);
                if (callback != null) callback.onResult(false, null, "执行异常: " + e.getMessage());
            }
        });
    }

    private void executeAfterConfirm(CrossChainRequest request, CrossChainQuote quote,
                                      double amountUsd, ExecutionCallback callback) {
        // 再次获取交易数据（报价和 swap 可能不同）
        CrossChainResult result = null;
        for (CrossChainProvider p : providers) {
            if (p.getName().equals(quote.providerName)) {
                result = p.swap(request, quote);
                break;
            }
        }
        if (result == null || !result.success) {
            String err = result != null ? result.error : "未找到对应 Provider";
            if (callback != null) callback.onResult(false, null, err);
            return;
        }

        // 如果需要 approve，先执行 approve
        if (result.approveTo != null && !result.approveTo.isEmpty()
            && result.approveData != null && !result.approveData.isEmpty()) {
            String approveTx = signAndSendEVM(request.fromChain, result.approveTo, result.approveData, "0");
            if (approveTx == null || approveTx.isEmpty()) {
                if (callback != null) callback.onResult(false, null, "approve 交易失败");
                return;
            }
            Logger.success(ctx, "CrossChain", "approve 已广播: " + approveTx);
        }

        // 执行主交易
        if (CrossChainUtils.isEVM(request.fromChain)) {
            String txHash = signAndSendEVM(request.fromChain, result.txTo, result.txData, result.txValue);
            if (txHash == null || txHash.isEmpty()) {
                if (callback != null) callback.onResult(false, null, "跨链主交易签名或广播失败");
                return;
            }
            // 记录今日已用额度
            limitConfig.addTodayUsedUsd(amountUsd);
            Logger.success(ctx, "CrossChain", "跨链交易已广播: " + txHash);
            if (callback != null) callback.onResult(true, txHash, null);
        } else {
            // 非 EVM 链把交易数据返回给调用方处理
            if (callback != null) callback.onResult(true, result, "非 EVM 链需手动签名广播");
        }
    }

    /** EVM 链签名并广播交易 */
    private String signAndSendEVM(String chain, String to, String data, String value) {
        try {
            String rpcUrl = WalletManager.getRpcUrl(ctx, chain);
            String fromAddress = WalletManager.getWalletAddress(ctx);
            String mnemonic = WalletManager.getMnemonic(ctx);
            long chainId = getChainId(rpcUrl);
            long nonce = getNonce(rpcUrl, fromAddress);
            BigInteger gasPrice = getGasPrice(rpcUrl);
            BigInteger gasLimit = BigInteger.valueOf(300000); // 跨链通常 gas 较高

            HDWallet wallet = new HDWallet(mnemonic, "");
            PrivateKey privateKey = wallet.getKeyForCoin(CoinType.ETHEREUM);

            BigInteger txValue = (value == null || value.isEmpty()) ? BigInteger.ZERO
                : (value.startsWith("0x") ? new BigInteger(value.substring(2), 16) : new BigInteger(value));

            Ethereum.SigningInput input = Ethereum.SigningInput.newBuilder()
                .setChainId(ByteString.copyFrom(BigInteger.valueOf(chainId).toByteArray()))
                .setNonce(ByteString.copyFrom(BigInteger.valueOf(nonce).toByteArray()))
                .setGasPrice(ByteString.copyFrom(gasPrice.toByteArray()))
                .setGasLimit(ByteString.copyFrom(gasLimit.toByteArray()))
                .setToAddress(to)
                .setTransaction(Ethereum.Transaction.newBuilder()
                    .setContractGeneric(Ethereum.Transaction.ContractGeneric.newBuilder()
                        .setAmount(ByteString.copyFrom(txValue.toByteArray()))
                        .setData(ByteString.copyFrom(Numeric.hexStringToByteArray(data)))
                        .build())
                    .build())
                .setPrivateKey(ByteString.copyFrom(privateKey.data()))
                .build();

            Ethereum.SigningOutput output = AnySigner.sign(input, CoinType.ETHEREUM, Ethereum.SigningOutput.parser());
            String signedTx = "0x" + bytesToHex(output.getEncoded().toByteArray());
            return broadcastTx(rpcUrl, signedTx);
        } catch (Exception e) {
            Logger.error(ctx, "CrossChain", "EVM 签名广播失败", e);
            return null;
        }
    }

    private double score(CrossChainQuote q) {
        // 简单评分：到账数量 - 总成本 * 10（成本权重）
        double toAmount = parseDouble(q.toAmount);
        return toAmount - q.totalCostUsd() * 10;
    }

    private double parseDouble(String s) {
        try {
            if (s == null || s.isEmpty()) return 0;
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private void post(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }

    private long getChainId(String rpcUrl) throws Exception {
        return Long.parseLong(rpcCall(rpcUrl, "eth_chainId", new org.json.JSONArray()).substring(2), 16);
    }

    private long getNonce(String rpcUrl, String address) throws Exception {
        org.json.JSONArray params = new org.json.JSONArray();
        params.put(address);
        params.put("pending");
        return Long.parseLong(rpcCall(rpcUrl, "eth_getTransactionCount", params).substring(2), 16);
    }

    private BigInteger getGasPrice(String rpcUrl) throws Exception {
        return new BigInteger(rpcCall(rpcUrl, "eth_gasPrice", new org.json.JSONArray()).substring(2), 16);
    }

    private String broadcastTx(String rpcUrl, String signedTx) throws Exception {
        org.json.JSONArray params = new org.json.JSONArray();
        params.put(signedTx);
        String result = rpcCall(rpcUrl, "eth_sendRawTransaction", params);
        if (result == null || result.isEmpty()) {
            throw new Exception("广播未返回交易哈希");
        }
        return result;
    }

    private String rpcCall(String rpcUrl, String method, org.json.JSONArray params) throws Exception {
        org.json.JSONObject body = new org.json.JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", method);
        body.put("params", params);
        try (Response r = rpcClient.newCall(new Request.Builder()
                .url(rpcUrl)
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build()).execute()) {
            String text = r.body() != null ? r.body().string() : "{}";
            org.json.JSONObject json = new org.json.JSONObject(text);
            if (json.has("error")) {
                throw new Exception(json.getJSONObject("error").optString("message"));
            }
            return json.optString("result", "");
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public interface ExecutionCallback {
        void onResult(boolean success, Object data, String error);
    }
}
