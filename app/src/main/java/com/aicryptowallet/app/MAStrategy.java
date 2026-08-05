package com.aicryptowallet.app;

/**
 * 均线交叉策略 (MA Crossover)
 * 金叉买入，死叉卖出
 */
public class MAStrategy implements TradingStrategy {
    private final int fastPeriod;
    private final int slowPeriod;

    public MAStrategy(int fastPeriod, int slowPeriod) {
        this.fastPeriod = fastPeriod;
        this.slowPeriod = slowPeriod;
    }

    @Override
    public Signal analyze(MarketData data) {
        double[] prices = data.prices;
        if (prices.length < slowPeriod + 2) return Signal.HOLD;

        double[] fastMA = TechnicalIndicators.sma(prices, fastPeriod);
        double[] slowMA = TechnicalIndicators.sma(prices, slowPeriod);

        if (fastMA.length < 2 || slowMA.length < 2) return Signal.HOLD;

        int offset = slowPeriod - fastPeriod;
        double fastCurrent = fastMA[fastMA.length - 1];
        double slowCurrent = slowMA[slowMA.length - 1];
        double fastPrev = fastMA[fastMA.length - 2];
        double slowPrev = slowMA[Math.max(0, slowMA.length - 2)];

        // 金叉：快线从下穿慢线
        if (fastPrev <= slowPrev && fastCurrent > slowCurrent) {
            return Signal.BUY;
        }
        // 死叉：快线从上穿慢线
        if (fastPrev >= slowPrev && fastCurrent < slowCurrent) {
            return Signal.SELL;
        }

        // 趋势判断
        if (fastCurrent > slowCurrent && data.currentPrice > fastCurrent) {
            return Signal.STRONG_BUY;
        }
        if (fastCurrent < slowCurrent && data.currentPrice < fastCurrent) {
            return Signal.STRONG_SELL;
        }

        return Signal.HOLD;
    }

    @Override
    public String getName() {
        return "MA(" + fastPeriod + "/" + slowPeriod + ")";
    }
}
