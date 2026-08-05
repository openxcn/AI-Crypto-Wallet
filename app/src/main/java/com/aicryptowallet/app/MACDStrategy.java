package com.aicryptowallet.app;

/**
 * MACD 趋势跟踪策略
 */
public class MACDStrategy implements TradingStrategy {
    private final int fastPeriod;
    private final int slowPeriod;
    private final int signalPeriod;

    public MACDStrategy(int fastPeriod, int slowPeriod, int signalPeriod) {
        this.fastPeriod = fastPeriod;
        this.slowPeriod = slowPeriod;
        this.signalPeriod = signalPeriod;
    }

    @Override
    public Signal analyze(MarketData data) {
        double[] prices = data.prices;
        if (prices.length < slowPeriod + signalPeriod + 2) return Signal.HOLD;

        double[][] macd = TechnicalIndicators.macd(prices, fastPeriod, slowPeriod, signalPeriod);
        double[] macdLine = macd[0];
        double[] signalLine = macd[1];
        double[] histogram = macd[2];

        if (histogram.length < 2) return Signal.HOLD;

        double currentHist = histogram[histogram.length - 1];
        double prevHist = histogram[histogram.length - 2];

        // MACD 金叉
        if (macdLine[macdLine.length - 1] > signalLine[signalLine.length - 1] &&
            macdLine[macdLine.length - 2] <= signalLine[signalLine.length - 2]) {
            return Signal.BUY;
        }

        // MACD 死叉
        if (macdLine[macdLine.length - 1] < signalLine[signalLine.length - 1] &&
            macdLine[macdLine.length - 2] >= signalLine[signalLine.length - 2]) {
            return Signal.SELL;
        }

        // 柱状图趋势
        if (currentHist > 0 && currentHist > prevHist) {
            return Signal.STRONG_BUY;
        }
        if (currentHist < 0 && currentHist < prevHist) {
            return Signal.STRONG_SELL;
        }

        return Signal.HOLD;
    }

    @Override
    public String getName() {
        return "MACD(" + fastPeriod + "," + slowPeriod + "," + signalPeriod + ")";
    }
}
