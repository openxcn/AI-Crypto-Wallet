package com.aicryptowallet.app;

/**
 * 交易信号结果
 */
public class TradingSignal {
    public final SignalType type;
    public final String reason;
    public final double buyRatio;
    public final double sellRatio;
    public final long timestamp;

    public enum SignalType {
        STRONG_BUY,
        BUY,
        HOLD,
        SELL,
        STRONG_SELL
    }

    public TradingSignal(SignalType type, String reason, double buyRatio, double sellRatio) {
        this.type = type;
        this.reason = reason;
        this.buyRatio = buyRatio;
        this.sellRatio = sellRatio;
        this.timestamp = System.currentTimeMillis();
    }

    public boolean isBuySignal() {
        return type == SignalType.BUY || type == SignalType.STRONG_BUY;
    }

    public boolean isSellSignal() {
        return type == SignalType.SELL || type == SignalType.STRONG_SELL;
    }

    public String getDisplayText() {
        switch (type) {
            case STRONG_BUY: return "强烈买入";
            case BUY: return "买入";
            case SELL: return "卖出";
            case STRONG_SELL: return "强烈卖出";
            default: return "持有";
        }
    }

    public String getColor() {
        switch (type) {
            case STRONG_BUY:
            case BUY: return "#00d084";
            case STRONG_SELL:
            case SELL: return "#ff4757";
            default: return "#8892b0";
        }
    }
}
