package com.aicryptowallet.app;

/**
 * 交易策略接口
 */
public interface TradingStrategy {
    /**
     * 分析市场数据并生成交易信号
     * @param data 市场数据
     * @return 交易信号
     */
    Signal analyze(MarketData data);

    /**
     * 获取策略名称
     */
    String getName();

    enum Signal {
        STRONG_BUY,
        BUY,
        HOLD,
        SELL,
        STRONG_SELL
    }
}
