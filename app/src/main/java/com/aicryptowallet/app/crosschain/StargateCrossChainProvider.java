package com.aicryptowallet.app.crosschain;

import android.content.Context;
import com.aicryptowallet.app.ContractCaller;
import com.aicryptowallet.app.Logger;
import org.json.JSONObject;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.DynamicStruct;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint16;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.utils.Numeric;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stargate V1 直接桥 Provider
 * 完全去中心化、无需 API Key，只支持同质资产跨链（如 USDT -> USDT）
 * 文档：https://stargateprotocol.gitbook.io/stargate/developers/how-to-swap
 */
public class StargateCrossChainProvider implements CrossChainProvider {

    private static final String NAME = "Stargate";

    private final Context ctx;

    public StargateCrossChainProvider(Context ctx) {
        this.ctx = ctx != null ? ctx.getApplicationContext() : null;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isSupported(String fromChain, String toChain, String fromToken, String toToken) {
        if (fromChain == null || toChain == null) return false;
        if (fromChain.equalsIgnoreCase(toChain)) return false;
        String symbol = tokenSymbol(fromChain, fromToken);
        if (symbol == null) return false;
        String dstSymbol = tokenSymbol(toChain, toToken);
        return symbol.equals(dstSymbol);
    }

    @Override
    public CrossChainQuote quote(CrossChainRequest request) {
        try {
            ChainConfig src = CONFIG.get(request.fromChain.toUpperCase());
            ChainConfig dst = CONFIG.get(request.toChain.toUpperCase());
            if (src == null || dst == null) return null;

            String symbol = tokenSymbol(request.fromChain, request.fromToken);
            PoolInfo srcPool = src.pools.get(symbol);
            PoolInfo dstPool = dst.pools.get(symbol);
            if (srcPool == null || dstPool == null) return null;

            BigInteger amountLD = new BigInteger(request.amount);

            // 链上查询 Stargate 协议费和 LP 费
            BigInteger feeLD = queryFeeLD(request.fromChain, srcPool.poolAddress, amountLD);
            BigInteger toAmountLD = amountLD.subtract(feeLD).max(BigInteger.ZERO);

            // 默认最小到账：扣除 0.5% 滑点
            BigInteger minLD = toAmountLD.multiply(BigInteger.valueOf(995)).divide(BigInteger.valueOf(1000));

            // 查询 LayerZero 跨链消息费
            BigInteger lzFee = quoteLayerZeroFee(request.fromChain, src.router, dst.lzChainId,
                addressToBytes(request.toAddress));

            JSONObject raw = new JSONObject();
            raw.put("srcPoolId", srcPool.poolId);
            raw.put("dstPoolId", dstPool.poolId);
            raw.put("lzFee", lzFee.toString());
            raw.put("feeLD", feeLD.toString());

            CrossChainQuote quote = new CrossChainQuote(
                NAME, request.fromChain, request.toChain,
                request.fromToken, request.toToken, request.amount,
                toAmountLD.toString(), minLD.toString(), 0, 0, 600, raw
            );
            quote.addStep("BRIDGE", request.fromChain,
                request.fromChain + " " + symbol + " -> " + request.toChain + " " + symbol, NAME);
            return quote;
        } catch (Exception e) {
            Logger.error(ctx, NAME, "quote 异常", e);
            return null;
        }
    }

    @Override
    public CrossChainResult swap(CrossChainRequest request, CrossChainQuote quote) {
        try {
            ChainConfig src = CONFIG.get(request.fromChain.toUpperCase());
            ChainConfig dst = CONFIG.get(request.toChain.toUpperCase());
            if (src == null || dst == null) {
                return CrossChainResult.error(NAME, "不支持的链");
            }

            String symbol = tokenSymbol(request.fromChain, request.fromToken);
            PoolInfo srcPool = src.pools.get(symbol);
            PoolInfo dstPool = dst.pools.get(symbol);
            if (srcPool == null || dstPool == null) {
                return CrossChainResult.error(NAME, "不支持的币种或池子");
            }

            BigInteger amountLD = new BigInteger(request.amount);
            BigInteger minLD = new BigInteger(quote.toAmountMin);
            byte[] toBytes = addressToBytes(request.toAddress);

            // 重新估算 LayerZero 费用（实际执行时可能与报价有偏差，以最新链上数据为准）
            BigInteger lzFee = quoteLayerZeroFee(request.fromChain, src.router, dst.lzChainId, toBytes);

            LzTxObj lzTx = new LzTxObj(BigInteger.ZERO, BigInteger.ZERO,
                "0x0000000000000000000000000000000000000000");

            Function swapFunc = new Function("swap",
                Arrays.asList(
                    new Uint16(dst.lzChainId),
                    new Uint256(srcPool.poolId),
                    new Uint256(dstPool.poolId),
                    new Address(request.fromAddress),
                    new Uint256(amountLD),
                    new Uint256(minLD),
                    lzTx,
                    new DynamicBytes(toBytes),
                    new DynamicBytes(new byte[0])
                ),
                Collections.emptyList()
            );
            String txData = FunctionEncoder.encode(swapFunc);

            // 需要approve router 使用 USDT
            Function approveFunc = new Function("approve",
                Arrays.asList(new Address(src.router), new Uint256(amountLD)),
                Collections.emptyList()
            );
            String approveData = FunctionEncoder.encode(approveFunc);

            JSONObject raw = new JSONObject();
            raw.put("lzFee", lzFee.toString());
            raw.put("srcPoolId", srcPool.poolId);
            raw.put("dstPoolId", dstPool.poolId);

            String txValue = "0x" + lzFee.toString(16);
            return new CrossChainResult(true, NAME, "", src.router, txData,
                txValue, srcPool.tokenAddress, approveData, null, raw);
        } catch (Exception e) {
            Logger.error(ctx, NAME, "swap 异常", e);
            return CrossChainResult.error(NAME, "swap 异常: " + e.getMessage());
        }
    }

    @Override
    public CrossChainStatus checkStatus(String requestId, String txHash) {
        // 直接桥没有统一免费状态接口，返回 PENDING，用户可通过区块浏览器或 LayerZero Scan 查看
        return new CrossChainStatus(CrossChainStatus.PENDING, txHash,
            "Stargate 直接桥状态请通过区块浏览器查看", 0);
    }

    // ============================================================
    // 链上查询
    // ============================================================

    private BigInteger queryFeeLD(String chain, String poolAddress, BigInteger amountLD) {
        try {
            List<TypeReference<?>> outputs = Arrays.asList(
                new TypeReference<Uint256>() {},
                new TypeReference<Uint256>() {},
                new TypeReference<Uint256>() {}
            );
            String result = ContractCaller.callReadOnly(ctx, chain, poolAddress, "getFees",
                Arrays.asList(new Uint256(amountLD), new Bool(true)), outputs);
            BigInteger[] vals = decodeUint256Array(result, 3);
            if (vals != null && vals.length >= 3) {
                return vals[1].add(vals[2]);
            }
        } catch (Exception e) {
            Logger.warning(ctx, NAME, "查询 getFees 失败: " + e.getMessage());
        }
        // 保守估计 0.1%
        return amountLD.divide(BigInteger.valueOf(1000));
    }

    private BigInteger quoteLayerZeroFee(String chain, String router, int dstChainId, byte[] toBytes) {
        try {
            LzTxObj lzTx = new LzTxObj(BigInteger.ZERO, BigInteger.ZERO,
                "0x0000000000000000000000000000000000000000");
            List<TypeReference<?>> outputs = Arrays.asList(
                new TypeReference<Uint256>() {},
                new TypeReference<Uint256>() {}
            );
            String result = ContractCaller.callReadOnly(ctx, chain, router, "quoteLayerZeroFee",
                Arrays.asList(
                    new Uint16(dstChainId),
                    new Uint8(1),
                    new DynamicBytes(toBytes),
                    new DynamicBytes(new byte[0]),
                    lzTx
                ), outputs);
            BigInteger[] vals = decodeUint256Array(result, 2);
            if (vals != null && vals.length >= 1) {
                return vals[0];
            }
        } catch (Exception e) {
            Logger.warning(ctx, NAME, "查询 quoteLayerZeroFee 失败: " + e.getMessage());
        }
        // 保守默认值 0.01 ETH/BNB 等原生币
        return new BigInteger("10000000000000000");
    }

    private BigInteger[] decodeUint256Array(String hex, int count) {
        if (hex == null || hex.length() < 2 + count * 64) return null;
        String clean = hex.startsWith("0x") ? hex.substring(2) : hex;
        BigInteger[] out = new BigInteger[count];
        for (int i = 0; i < count; i++) {
            String chunk = clean.substring(i * 64, (i + 1) * 64);
            out[i] = new BigInteger(chunk, 16);
        }
        return out;
    }

    // ============================================================
    // 配置
    // ============================================================

    private static class PoolInfo {
        final int poolId;
        final String poolAddress;
        final String tokenAddress;
        PoolInfo(int poolId, String poolAddress, String tokenAddress) {
            this.poolId = poolId;
            this.poolAddress = poolAddress;
            this.tokenAddress = tokenAddress;
        }
    }

    private static class ChainConfig {
        final int lzChainId;
        final String router;
        final Map<String, PoolInfo> pools = new HashMap<>();
        ChainConfig(int lzChainId, String router) {
            this.lzChainId = lzChainId;
            this.router = router;
        }
        void addPool(String symbol, int poolId, String poolAddress, String tokenAddress) {
            pools.put(symbol, new PoolInfo(poolId, poolAddress, tokenAddress));
        }
    }

    private static final Map<String, ChainConfig> CONFIG = new HashMap<>();
    static {
        ChainConfig eth = new ChainConfig(101, "0x8731d54E9D02c286767d56ac03e8037C07e01e98");
        eth.addPool("USDT", 2,
            "0x38EA452219524Bb87e18dE1C24D3bB59510BD783",
            "0xdAC17F958D2ee523a2206206994597C13D831ec7");
        CONFIG.put("ETH", eth);

        ChainConfig bnb = new ChainConfig(102, "0x4a364f8c717cAAD9A442737Eb7b8A55cc6cf18D8");
        bnb.addPool("USDT", 2,
            "0x9aA83081AA06AF7208Dcc7A4cB72C94d057D2cda",
            "0x55d398326f99059fF775485246999027B3197955");
        CONFIG.put("BNB", bnb);

        ChainConfig avax = new ChainConfig(106, "0x45A01E4e04F14f7A4a6702c74187c5F6222033cd");
        avax.addPool("USDT", 2,
            "0x29e38769f23701A2e4A8Ef0492e19dA4604Be62c",
            "0xc7198437980c041c805A1EDcbA50c1Ce5db95118");
        CONFIG.put("AVAX", avax);

        ChainConfig matic = new ChainConfig(109, "0x45A01E4e04F14f7A4a6702c74187c5F6222033cd");
        matic.addPool("USDT", 2,
            "0x29e38769f23701A2e4A8Ef0492e19dA4604Be62c",
            "0xc2132D05D31c914a87C6611C10748AEb04B58e8F");
        CONFIG.put("MATIC", matic);

        ChainConfig arb = new ChainConfig(110, "0x53Bf833A5d6c4ddA888F69c22C88C9f356a41614");
        arb.addPool("USDT", 2,
            "0xB6CfcF89a7B22988bfC96632aC2A9D6daB60d641",
            "0xFd086bC7CD5C48D7f3AbBFB80C31a1c2f8c0b5c5");
        CONFIG.put("ARB", arb);
    }

    private String tokenSymbol(String chain, String token) {
        if (token == null || token.isEmpty()) return null;
        String t = token.trim();
        if (t.equalsIgnoreCase("USDT")) return "USDT";
        ChainConfig cfg = CONFIG.get(chain.toUpperCase());
        if (cfg == null) return null;
        for (Map.Entry<String, PoolInfo> e : cfg.pools.entrySet()) {
            if (e.getValue().tokenAddress.equalsIgnoreCase(t)) return e.getKey();
        }
        return null;
    }

    private byte[] addressToBytes(String address) {
        if (address == null || address.length() < 42) return new byte[20];
        String clean = address.startsWith("0x") ? address.substring(2) : address;
        if (clean.length() != 40) return new byte[20];
        return Numeric.hexStringToByteArray(clean);
    }

    // LayerZero lzTxObj 结构：uint256 dstGasForCall, uint256 dstNativeAmount, address dstNativeAddr
    public static class LzTxObj extends DynamicStruct {
        public LzTxObj(BigInteger dstGasForCall, BigInteger dstNativeAmount, String dstNativeAddr) {
            super(new Uint256(dstGasForCall), new Uint256(dstNativeAmount), new Address(dstNativeAddr));
        }
    }
}
