package com.aicryptowallet.app;

import java.util.ArrayList;
import java.util.List;

/**
 * 技术指标计算引擎
 * 支持 MA、EMA、RSI、MACD、布林带、ATR 等经典指标
 */
public class TechnicalIndicators {

    /**
     * 简单移动平均线 (SMA)
     */
    public static double[] sma(double[] prices, int period) {
        if (prices.length < period) return new double[0];
        double[] result = new double[prices.length - period + 1];
        for (int i = 0; i < result.length; i++) {
            double sum = 0;
            for (int j = i; j < i + period; j++) {
                sum += prices[j];
            }
            result[i] = sum / period;
        }
        return result;
    }

    /**
     * 指数移动平均线 (EMA)
     */
    public static double[] ema(double[] prices, int period) {
        if (prices.length < period) return new double[0];
        double[] result = new double[prices.length];
        double multiplier = 2.0 / (period + 1);

        // 第一个 EMA 值 = 前 period 个价格的 SMA
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += prices[i];
        }
        result[period - 1] = sum / period;

        for (int i = period; i < prices.length; i++) {
            result[i] = (prices[i] - result[i - 1]) * multiplier + result[i - 1];
        }
        return result;
    }

    /**
     * RSI 相对强弱指标
     */
    public static double[] rsi(double[] prices, int period) {
        if (prices.length < period + 1) return new double[0];
        double[] result = new double[prices.length];
        double[] gains = new double[prices.length];
        double[] losses = new double[prices.length];

        for (int i = 1; i < prices.length; i++) {
            double change = prices[i] - prices[i - 1];
            gains[i] = Math.max(change, 0);
            losses[i] = Math.max(-change, 0);
        }

        // 初始平均增益/损失
        double avgGain = 0, avgLoss = 0;
        for (int i = 1; i <= period; i++) {
            avgGain += gains[i];
            avgLoss += losses[i];
        }
        avgGain /= period;
        avgLoss /= period;

        result[period] = avgLoss == 0 ? 100 : 100 - (100 / (1 + avgGain / avgLoss));

        for (int i = period + 1; i < prices.length; i++) {
            avgGain = (avgGain * (period - 1) + gains[i]) / period;
            avgLoss = (avgLoss * (period - 1) + losses[i]) / period;
            result[i] = avgLoss == 0 ? 100 : 100 - (100 / (1 + avgGain / avgLoss));
        }
        return result;
    }

    /**
     * MACD 指标
     * 返回 [MACD线, 信号线, 柱状图]
     */
    public static double[][] macd(double[] prices, int fastPeriod, int slowPeriod, int signalPeriod) {
        double[] emaFast = ema(prices, fastPeriod);
        double[] emaSlow = ema(prices, slowPeriod);

        double[] macdLine = new double[prices.length];
        for (int i = 0; i < prices.length; i++) {
            if (i >= slowPeriod - 1) {
                macdLine[i] = emaFast[i] - emaSlow[i];
            }
        }

        double[] signalLine = ema(macdLine, signalPeriod);

        double[][] result = new double[3][prices.length];
        result[0] = macdLine;
        result[1] = signalLine;
        for (int i = 0; i < prices.length; i++) {
            result[2][i] = macdLine[i] - signalLine[i];
        }
        return result;
    }

    /**
     * 布林带 (Bollinger Bands)
     * 返回 [上轨, 中轨, 下轨]
     */
    public static double[][] bollingerBands(double[] prices, int period, double stdDevMultiplier) {
        double[] sma = sma(prices, period);
        double[][] result = new double[3][sma.length];

        for (int i = 0; i < sma.length; i++) {
            double sumSqDiff = 0;
            for (int j = i; j < i + period; j++) {
                double diff = prices[j] - sma[i];
                sumSqDiff += diff * diff;
            }
            double stdDev = Math.sqrt(sumSqDiff / period);
            result[0][i] = sma[i] + stdDevMultiplier * stdDev; // 上轨
            result[1][i] = sma[i];                              // 中轨
            result[2][i] = sma[i] - stdDevMultiplier * stdDev; // 下轨
        }
        return result;
    }

    /**
     * ATR 平均真实波幅
     */
    public static double[] atr(double[] highs, double[] lows, double[] closes, int period) {
        if (closes.length < period + 1) return new double[0];
        double[] trueRanges = new double[closes.length];
        trueRanges[0] = highs[0] - lows[0];

        for (int i = 1; i < closes.length; i++) {
            double tr = Math.max(
                highs[i] - lows[i],
                Math.max(
                    Math.abs(highs[i] - closes[i - 1]),
                    Math.abs(lows[i] - closes[i - 1])
                )
            );
            trueRanges[i] = tr;
        }

        double[] result = new double[closes.length];
        double sum = 0;
        for (int i = 1; i <= period; i++) {
            sum += trueRanges[i];
        }
        result[period] = sum / period;

        for (int i = period + 1; i < closes.length; i++) {
            result[i] = (result[i - 1] * (period - 1) + trueRanges[i]) / period;
        }
        return result;
    }

    /**
     * 成交量加权平均价格 (VWAP)
     */
    public static double[] vwap(double[] prices, double[] volumes) {
        double[] result = new double[prices.length];
        double cumulativeTPV = 0;
        double cumulativeVolume = 0;

        for (int i = 0; i < prices.length; i++) {
            cumulativeTPV += prices[i] * volumes[i];
            cumulativeVolume += volumes[i];
            result[i] = cumulativeVolume > 0 ? cumulativeTPV / cumulativeVolume : prices[i];
        }
        return result;
    }

    /**
     * 获取最新指标值
     */
    public static IndicatorValues getLatest(double[] prices, double[] volumes) {
        double[] rsi14 = rsi(prices, 14);
        double[][] macd12269 = macd(prices, 12, 26, 9);
        double[] sma20 = sma(prices, 20);
        double[] sma50 = sma(prices, 50);
        double[] ema12 = ema(prices, 12);
        double[] ema26 = ema(prices, 26);
        double[][] bb = bollingerBands(prices, 20, 2.0);

        int last = prices.length - 1;

        IndicatorValues values = new IndicatorValues();
        values.currentPrice = prices[last];
        values.rsi = rsi14.length > 0 ? rsi14[rsi14.length - 1] : 50;
        values.macd = macd12269[0][last];
        values.macdSignal = macd12269[1][last];
        values.macdHistogram = macd12269[2][last];
        values.sma20 = sma20.length > 0 ? sma20[sma20.length - 1] : prices[last];
        values.sma50 = sma50.length > 0 ? sma50[sma50.length - 1] : prices[last];
        values.ema12 = ema12.length > 0 ? ema12[ema12.length - 1] : prices[last];
        values.ema26 = ema26.length > 0 ? ema26[ema26.length - 1] : prices[last];
        values.bbUpper = bb[0].length > 0 ? bb[0][bb[0].length - 1] : prices[last];
        values.bbMiddle = bb[1].length > 0 ? bb[1][bb[1].length - 1] : prices[last];
        values.bbLower = bb[2].length > 0 ? bb[2][bb[2].length - 1] : prices[last];
        return values;
    }

    public static class IndicatorValues {
        public double currentPrice;
        public double rsi;
        public double macd;
        public double macdSignal;
        public double macdHistogram;
        public double sma20;
        public double sma50;
        public double ema12;
        public double ema26;
        public double bbUpper;
        public double bbMiddle;
        public double bbLower;
    }
}
