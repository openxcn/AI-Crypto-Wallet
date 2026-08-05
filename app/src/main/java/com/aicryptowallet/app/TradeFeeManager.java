package com.aicryptowallet.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.google.protobuf.ByteString;
import wallet.core.java.AnySigner;
import wallet.core.jni.CoinType;
import wallet.core.jni.PrivateKey;
import wallet.core.jni.HDWallet;
import wallet.core.jni.proto.Ethereum;
import org.json.JSONArray;
import org.json.JSONObject;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.generated.Uint256;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 交易费管理器 - 处理 0.5% 开发者手续费
 */
public class TradeFeeManager {
    private static final String NAME = "TradeFeeManager";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build();
    private static final MediaType JSON_TYPE = MediaType.parse("application/json");

    private Context ctx;
    private String chain;

    public TradeFeeManager(Context ctx, String chain) {
        // 使用 ApplicationContext 避免静态 executor 持有 Activity 导致内存泄漏
        this.ctx = ctx != null ? ctx.getApplicationContext() : null;
        this.chain = chain;
    }

    /**
     * 计算交易费（0.5%）
     */
    public double calculateFee(double tradeAmount) {
        return tradeAmount * AppConfig.TRADE_FEE_RATE;
    }

    /**
     * 检查是否需要收取手续费（持有红魔 NFT 可免手续费）
     */
    public boolean isFeeWaived() {
        try {
            String bnbAddress = getBnbAddress();
            if (bnbAddress == null || bnbAddress.isEmpty()) return false;
            return hasRedDevilNft(bnbAddress);
        } catch (Exception e) {
            Logger.warning(ctx, NAME, "检查红魔 NFT 失败: " + e.getMessage());
            return false;
        }
    }

    private String getBnbAddress() {
        String secret = WalletManager.getMnemonic(ctx);
        if (secret == null || secret.isEmpty()) return "";
        if (secret.trim().contains(" ")) {
            return WalletManager.deriveAddress(secret.trim(), "BNB");
        } else {
            return WalletManager.deriveAddressFromPrivateKey(secret.trim(), "BNB");
        }
    }

    private boolean hasRedDevilNft(String address) {
        try {
            List<TypeReference<?>> outputs = Arrays.asList(new TypeReference<Uint256>() {});
            String result = ContractCaller.callReadOnly(ctx, "BNB", AppConfig.RED_DEVIL_NFT_CONTRACT,
                "balanceOf", Arrays.asList(new Address(address)), outputs);
            BigInteger balance = decodeUint256(result);
            return balance != null && balance.compareTo(BigInteger.ZERO) > 0;
        } catch (Exception e) {
            Logger.warning(ctx, NAME, "查询红魔 NFT 余额失败: " + e.getMessage());
        }
        return false;
    }

    private BigInteger decodeUint256(String hex) {
        if (hex == null || hex.length() < 2 + 64) return null;
        String clean = hex.startsWith("0x") ? hex.substring(2) : hex;
        return new BigInteger(clean.substring(0, 64), 16);
    }

    /**
     * 执行手续费转账 - 实际将原生币转给开发者钱包
     * 仅 EVM 链自动扣币；非 EVM 链记录但不自动扣（避免签名复杂度）
     */
    public void payFee(double feeAmountUsd, final FeeCallback callback) {
        executor.execute(() -> {
            try {
                // 如果免除手续费，直接回调成功
                if (isFeeWaived()) {
                    mainHandler.post(() -> {
                        if (callback != null) callback.onFeePaid(true, "红魔 NFT 持有者，手续费已免除");
                    });
                    return;
                }

                // 非 EVM 链暂不自动扣币（签名 proto 各不相同）
                if (!ChainAPI.isEVM(chain)) {
                    Logger.info(ctx, "交易费", chain + " 链手续费记录: $" + String.format("%.2f", feeAmountUsd) + "（非EVM链暂不自动扣）");
                    mainHandler.post(() -> {
                        if (callback != null) callback.onFeePaid(true, "手续费已记录（非EVM链）");
                    });
                    return;
                }

                // EVM 链：按当前原生币价格折算成原生币数量
                java.util.Map<String, Double> prices = ChainAPI.getPrices(ctx);
                double nativePrice = prices.getOrDefault(chain, 0.0);
                if (nativePrice <= 0) {
                    Logger.warning(ctx, "交易费", "无法获取 " + chain + " 价格，跳过自动扣币");
                    mainHandler.post(() -> {
                        if (callback != null) callback.onFeePaid(true, "手续费已记录（价格获取失败）");
                    });
                    return;
                }
                double nativeAmount = feeAmountUsd / nativePrice;
                BigInteger amountWei = BigDecimal.valueOf(nativeAmount)
                    .multiply(BigDecimal.TEN.pow(18)).toBigInteger();

                String rpcUrl = WalletManager.getRpcUrl(ctx, chain);
                String fromAddress = WalletManager.getWalletAddress(ctx);
                String mnemonic = WalletManager.getMnemonic(ctx);
                long chainId = getChainId(rpcUrl);

                // 获取 nonce
                long nonce = getNonce(rpcUrl, fromAddress);
                BigInteger gasPrice = getGasPrice(rpcUrl);
                BigInteger gasLimit = BigInteger.valueOf(21000); // 普通转账固定 21000

                // wallet-core 签名
                HDWallet wallet = new HDWallet(mnemonic, "");
                PrivateKey privateKey = wallet.getKeyForCoin(CoinType.ETHEREUM);

                Ethereum.SigningInput input = Ethereum.SigningInput.newBuilder()
                    .setChainId(ByteString.copyFrom(BigInteger.valueOf(chainId).toByteArray()))
                    .setNonce(ByteString.copyFrom(BigInteger.valueOf(nonce).toByteArray()))
                    .setGasPrice(ByteString.copyFrom(gasPrice.toByteArray()))
                    .setGasLimit(ByteString.copyFrom(gasLimit.toByteArray()))
                    .setToAddress(AppConfig.DEVELOPER_WALLET)
                    .setTransaction(Ethereum.Transaction.newBuilder()
                        .setTransfer(Ethereum.Transaction.Transfer.newBuilder()
                            .setAmount(ByteString.copyFrom(amountWei.toByteArray()))
                            .build())
                        .build())
                    .setPrivateKey(ByteString.copyFrom(privateKey.data()))
                    .build();
                Ethereum.SigningOutput output = AnySigner.sign(input, CoinType.ETHEREUM,
                    Ethereum.SigningOutput.parser());

                String signedTx = "0x" + bytesToHex(output.getEncoded().toByteArray());
                String txHash = broadcastTx(rpcUrl, signedTx);

                Logger.success(ctx, "交易费", "手续费已支付 " + String.format("%.6f", nativeAmount) + " " + chain +
                    " ($" + String.format("%.2f", feeAmountUsd) + ") TX: " + txHash);
                mainHandler.post(() -> {
                    if (callback != null) callback.onFeePaid(true, "手续费已支付: " + String.format("%.6f", nativeAmount) + " " + chain);
                });

            } catch (Exception e) {
                Logger.error(ctx, "交易费", "手续费支付失败: " + e.getMessage());
                mainHandler.post(() -> {
                    if (callback != null) callback.onFeePaid(false, "手续费支付失败: " + e.getMessage());
                });
            }
        });
    }

    private long getNonce(String rpcUrl, String address) throws Exception {
        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0"); body.put("id", 1);
        body.put("method", "eth_getTransactionCount");
        JSONArray params = new JSONArray();
        params.put(address); params.put("pending"); // 用 pending 与 DexTrader 保持一致，避免 nonce 冲突
        body.put("params", params);
        try (Response r = client.newCall(new Request.Builder().url(rpcUrl)
            .post(RequestBody.create(body.toString(), JSON_TYPE)).build()).execute()) {
            String result = new JSONObject(r.body() != null ? r.body().string() : "{}").optString("result", "0x0");
            return Long.parseLong(result.substring(2), 16);
        }
    }

    private long getChainId(String rpcUrl) throws Exception {
        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0"); body.put("id", 1);
        body.put("method", "eth_chainId");
        body.put("params", new JSONArray());
        try (Response r = client.newCall(new Request.Builder().url(rpcUrl)
            .post(RequestBody.create(body.toString(), JSON_TYPE)).build()).execute()) {
            String result = new JSONObject(r.body() != null ? r.body().string() : "{}").optString("result", "0x1");
            return Long.parseLong(result.substring(2), 16);
        }
    }

    private BigInteger getGasPrice(String rpcUrl) throws Exception {
        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0"); body.put("id", 1);
        body.put("method", "eth_gasPrice");
        body.put("params", new JSONArray());
        try (Response r = client.newCall(new Request.Builder().url(rpcUrl)
            .post(RequestBody.create(body.toString(), JSON_TYPE)).build()).execute()) {
            String result = new JSONObject(r.body() != null ? r.body().string() : "{}").optString("result", "0x0");
            return new BigInteger(result.substring(2), 16);
        }
    }

    private String broadcastTx(String rpcUrl, String signedTx) throws Exception {
        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0"); body.put("id", 1);
        body.put("method", "eth_sendRawTransaction");
        JSONArray params = new JSONArray();
        params.put(signedTx);
        body.put("params", params);
        try (Response r = client.newCall(new Request.Builder().url(rpcUrl)
            .post(RequestBody.create(body.toString(), JSON_TYPE)).build()).execute()) {
            // 修复：之前直接 optString("result","")，未检查 error 字段
            // 当广播失败（余额不足/nonce冲突/签名错误）时 RPC 返回 error，但旧代码返回空字符串
            // 调用方 payFee 误以为成功，日志和 UI 显示"手续费已支付"但实际未转账
            JSONObject json = new JSONObject(r.body() != null ? r.body().string() : "{}");
            if (json.has("error")) {
                throw new Exception("手续费广播失败: " + json.getJSONObject("error").optString("message"));
            }
            String result = json.optString("result", "");
            if (result.isEmpty()) {
                throw new Exception("手续费广播未返回交易哈希（节点可能异常）");
            }
            return result;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * 手续费回调接口
     */
    public interface FeeCallback {
        void onFeePaid(boolean success, String message);
    }
}
