package com.aicryptowallet.app;

import java.util.List;

/**
 * 市场数据模型
 */
public class MarketData {
    public String symbol;
    public double currentPrice;
    public double[] prices;       // 历史收盘价
    public double[] volumes;      // 历史成交量
    public double[] highs;        // 历史最高价
    public double[] lows;         // 历史最低价
    public long timestamp;
    public double change24h;      // 24h 涨跌幅
    public double volume24h;      // 24h 成交量

    public MarketData() {
        this.timestamp = System.currentTimeMillis();
    }

    public static MarketData from(List<double[]> candles) {
        MarketData data = new MarketData();
        int size = candles.size();
        data.prices = new double[size];
        data.volumes = new double[size];
        data.highs = new double[size];
        data.lows = new double[size];

        for (int i = 0; i < size; i++) {
            double[] candle = candles.get(i);
            data.highs[i] = candle[0];
            data.lows[i] = candle[1];
            data.prices[i] = candle[2];
            data.volumes[i] = candle[3];
        }

        if (size > 0) {
            data.currentPrice = data.prices[size - 1];
        }
        if (size > 1) {
            data.change24h = (data.currentPrice - data.prices[0]) / data.prices[0] * 100;
        }
        return data;
    }
}
