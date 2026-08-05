package com.aicryptowallet.app;

/**
 * RSI 超买超卖策略
 */
public class RSIStrategy implements TradingStrategy {
    private final int period;
    private final double oversold;
    private final double overbought;

    public RSIStrategy(int period, double oversold, double overbought) {
        this.period = period;
        this.oversold = oversold;
        this.overbought = overbought;
    }

    @Override
    public Signal analyze(MarketData data) {
        double[] prices = data.prices;
        if (prices.length < period + 1) return Signal.HOLD;

        double[] rsi = TechnicalIndicators.rsi(prices, period);
        if (rsi.length == 0) return Signal.HOLD;

        double currentRSI = rsi[rsi.length - 1];

        if (currentRSI <= oversold) {
            return Signal.STRONG_BUY;
        }
        if (currentRSI <= oversold + 10) {
            return Signal.BUY;
        }
        if (currentRSI >= overbought) {
            return Signal.STRONG_SELL;
        }
        if (currentRSI >= overbought - 10) {
            return Signal.SELL;
        }

        return Signal.HOLD;
    }

    @Override
    public String getName() {
        return "RSI(" + period + ")";
    }
}
