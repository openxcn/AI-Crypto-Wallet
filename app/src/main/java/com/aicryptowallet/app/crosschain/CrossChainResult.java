package com.aicryptowallet.app.crosschain;

import org.json.JSONObject;

/**
 * 跨链兑换执行结果（拿到交易数据后由用户/AI 签名广播）
 */
public class CrossChainResult {
    public final boolean success;
    public final String providerName;
    public final String requestId;
    public final String txTo;        // 合约交互地址
    public final String txData;      // 交易 data
    public final String txValue;     // 原生币数量（hex 或 decimal）
    public final String approveTo;   // 需要 approve 的合约地址
    public final String approveData; // approve 交易 data
    public final String error;
    public final JSONObject rawResponse;

    public CrossChainResult(boolean success, String providerName, String requestId,
                            String txTo, String txData, String txValue,
                            String approveTo, String approveData, String error,
                            JSONObject rawResponse) {
        this.success = success;
        this.providerName = providerName;
        this.requestId = requestId;
        this.txTo = txTo;
        this.txData = txData;
        this.txValue = txValue;
        this.approveTo = approveTo;
        this.approveData = approveData;
        this.error = error;
        this.rawResponse = rawResponse;
    }

    public static CrossChainResult error(String providerName, String message) {
        return new CrossChainResult(false, providerName, "", "", "", "", "", "", message, null);
    }
}
