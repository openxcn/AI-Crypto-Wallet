package com.aicryptowallet.app;

import java.util.ArrayList;
import java.util.List;

/**
 * 策略引擎 - 多策略投票决策
 */
public class StrategyEngine {
    private final List<TradingStrategy> strategies;

    public StrategyEngine() {
        strategies = new ArrayList<>();
        strategies.add(new MAStrategy(10, 30));
        strategies.add(new MAStrategy(5, 20));
        strategies.add(new RSIStrategy(14, 30, 70));
        strategies.add(new MACDStrategy(12, 26, 9));
    }

    public void addStrategy(TradingStrategy strategy) {
        strategies.add(strategy);
    }

    public TradingSignal analyze(MarketData data) {
        if (strategies.isEmpty()) {
            return new TradingSignal(TradingSignal.SignalType.HOLD, "无策略", 0, 0);
        }

        int buyVotes = 0;
        int sellVotes = 0;
        int strongBuyVotes = 0;
        int strongSellVotes = 0;

        for (TradingStrategy strategy : strategies) {
            TradingStrategy.Signal signal = strategy.analyze(data);
            switch (signal) {
                case STRONG_BUY:
                    strongBuyVotes++;
                    buyVotes += 2;
                    break;
                case BUY:
                    buyVotes++;
                    break;
                case STRONG_SELL:
                    strongSellVotes++;
                    sellVotes += 2;
                    break;
                case SELL:
                    sellVotes++;
                    break;
                default:
                    break;
            }
        }

        int totalVotes = strategies.size() * 2;
        double buyRatio = (double) buyVotes / totalVotes;
        double sellRatio = (double) sellVotes / totalVotes;

        TradingSignal.SignalType result = TradingSignal.SignalType.HOLD;
        String reason = "无明确信号";

        if (buyRatio >= 0.7 || strongBuyVotes >= 2) {
            result = TradingSignal.SignalType.STRONG_BUY;
            reason = "强烈买入信号 (" + buyVotes + "/" + totalVotes + ")";
        } else if (buyRatio >= 0.5) {
            result = TradingSignal.SignalType.BUY;
            reason = "买入信号 (" + buyVotes + "/" + totalVotes + ")";
        } else if (sellRatio >= 0.7 || strongSellVotes >= 2) {
            result = TradingSignal.SignalType.STRONG_SELL;
            reason = "强烈卖出信号 (" + sellVotes + "/" + totalVotes + ")";
        } else if (sellRatio >= 0.5) {
            result = TradingSignal.SignalType.SELL;
            reason = "卖出信号 (" + sellVotes + "/" + totalVotes + ")";
        }

        return new TradingSignal(result, reason, buyRatio, sellRatio);
    }

    public List<TradingStrategy> getStrategies() {
        return strategies;
    }
}
