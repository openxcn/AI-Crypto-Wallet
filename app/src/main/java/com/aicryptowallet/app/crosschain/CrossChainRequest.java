package com.aicryptowallet.app.crosschain;

/**
 * 跨链兑换请求参数
 */
public class CrossChainRequest {
    public final String fromChain;
    public final String toChain;
    public final String fromToken;   // 合约地址或符号，NATIVE 表示原生币
    public final String toToken;     // 合约地址或符号
    public final String amount;      // 源链最小单位数量（字符串，避免精度丢失）
    public final String fromAddress;
    public final String toAddress;
    public final double slippage;    // 滑点百分比，如 1.5

    public CrossChainRequest(String fromChain, String toChain, String fromToken, String toToken,
                             String amount, String fromAddress, String toAddress, double slippage) {
        this.fromChain = fromChain;
        this.toChain = toChain;
        this.fromToken = fromToken;
        this.toToken = toToken;
        this.amount = amount;
        this.fromAddress = fromAddress;
        this.toAddress = toAddress;
        this.slippage = slippage;
    }

    public boolean isNativeFrom() {
        return fromToken == null || fromToken.isEmpty()
            || "NATIVE".equalsIgnoreCase(fromToken)
            || "0x0000000000000000000000000000000000000000".equalsIgnoreCase(fromToken);
    }

    public boolean isNativeTo() {
        return toToken == null || toToken.isEmpty()
            || "NATIVE".equalsIgnoreCase(toToken)
            || "0x0000000000000000000000000000000000000000".equalsIgnoreCase(toToken);
    }
}
