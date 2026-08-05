package com.aicryptowallet.app.crosschain;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨链兑换报价
 */
public class CrossChainQuote {
    public final String providerName;
    public final String fromChain;
    public final String toChain;
    public final String fromToken;
    public final String toToken;
    public final String fromAmount;
    public final String toAmount;      // 预估到账数量
    public final String toAmountMin;   // 最少到账数量（含滑点）
    public final double feeUsd;        // 预估手续费（USD）
    public final double gasFeeUsd;     // 预估 Gas（USD）
    public final int durationSeconds;  // 预估耗时
    public final List<Step> steps;
    public final JSONObject rawResponse;

    public CrossChainQuote(String providerName, String fromChain, String toChain,
                           String fromToken, String toToken, String fromAmount,
                           String toAmount, String toAmountMin, double feeUsd,
                           double gasFeeUsd, int durationSeconds, JSONObject rawResponse) {
        this.providerName = providerName;
        this.fromChain = fromChain;
        this.toChain = toChain;
        this.fromToken = fromToken;
        this.toToken = toToken;
        this.fromAmount = fromAmount;
        this.toAmount = toAmount;
        this.toAmountMin = toAmountMin;
        this.feeUsd = feeUsd;
        this.gasFeeUsd = gasFeeUsd;
        this.durationSeconds = durationSeconds;
        this.rawResponse = rawResponse;
        this.steps = new ArrayList<>();
    }

    public void addStep(String type, String chain, String description, String provider) {
        steps.add(new Step(type, chain, description, provider));
    }

    public double totalCostUsd() {
        return feeUsd + gasFeeUsd;
    }

    public static class Step {
        public final String type;        // SWAP / BRIDGE / APPROVE
        public final String chain;
        public final String description;
        public final String provider;

        public Step(String type, String chain, String description, String provider) {
            this.type = type;
            this.chain = chain;
            this.description = description;
            this.provider = provider;
        }
    }
}
