package com.aicryptowallet.app.crosschain;

/**
 * 跨链兑换状态跟踪
 */
public class CrossChainStatus {
    public static final String PENDING = "pending";
    public static final String SUCCESS = "success";
    public static final String FAILED = "failed";

    public final String status;
    public final String txHash;
    public final String error;
    public final double toAmountReceived; // 实际到账数量（如果返回）

    public CrossChainStatus(String status, String txHash, String error, double toAmountReceived) {
        this.status = status;
        this.txHash = txHash;
        this.error = error;
        this.toAmountReceived = toAmountReceived;
    }

    public boolean isFinal() {
        return SUCCESS.equals(status) || FAILED.equals(status);
    }
}
