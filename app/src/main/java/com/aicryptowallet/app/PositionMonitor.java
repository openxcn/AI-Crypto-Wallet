package com.aicryptowallet.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 持仓监控服务 - 止盈止损执行器
 *
 * 产品定位：AI 中长线炒币助手
 *
 * 职责：
 * 1. 周期性（每 1 分钟）检查所有持仓是否触发止盈止损
 * 2. 触发时自动生成强制 SELL 信号并执行
 * 3. 触发后通过 SafetyGate 记录亏损/盈利，影响熔断状态
 *
 * 这是把 RiskManager 中"死配置"的 stopLossPercent/takeProfitPercent
 * 真正变成"执行逻辑"的关键组件
 *
 * 中长线核心：止损是底线，止盈是纪律
 */
public class PositionMonitor {
    private static final long CHECK_INTERVAL_MS = 60 * 1000; // 每分钟检查一次

    private final Context ctx;
    private final ExecutorService executor;
    private final Handler handler;
    private final SafetyGate safetyGate;
    private final RiskManager riskManager;
    private volatile boolean running = false;

    public PositionMonitor(Context ctx, SafetyGate safetyGate, RiskManager riskManager) {
        this.ctx = ctx.getApplicationContext();
        this.safetyGate = safetyGate;
        this.riskManager = riskManager;
        this.executor = Executors.newSingleThreadExecutor();
        this.handler = new Handler(Looper.getMainLooper());
    }

    /**
     * 启动持仓监控
     */
    public void start() {
        if (running) return;
        running = true;
        Logger.info(ctx, "持仓监控", "启动止盈止损监控服务，检查间隔=" + (CHECK_INTERVAL_MS / 1000) + "秒");
        executor.execute(this::monitorLoop);
    }

    /**
     * 停止持仓监控
     */
    public void stop() {
        running = false;
        Logger.info(ctx, "持仓监控", "停止监控服务");
    }

    /**
     * 监控主循环
     */
    private void monitorLoop() {
        while (running) {
            try {
                checkAllPositions();
            } catch (Exception e) {
                Logger.error(ctx, "持仓监控", "检查异常: " + e.getMessage());
            }
            try {
                Thread.sleep(CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /**
     * 检查所有持仓，触发止盈止损
     */
    private void checkAllPositions() {
        String chain = WalletManager.getChain(ctx);
        if (chain == null || chain.isEmpty()) return;

        // 获取当前价格
        Map<String, Double> prices;
        try {
            prices = ChainAPI.getPrices(ctx);
        } catch (Exception e) {
            return;
        }
        if (prices == null || prices.isEmpty()) return;

        // 检查所有持仓
        List<PositionManager.CheckResult> results =
            PositionManager.checkAllPositions(ctx, chain, prices);

        for (PositionManager.CheckResult result : results) {
            if (!result.shouldClose()) continue;

            PositionManager.Position pos = result.position;
            Logger.warning(ctx, "持仓监控",
                (result.reason == PositionManager.TriggerReason.STOP_LOSS ? "止损" : "止盈")
                + "触发: " + pos.tokenSymbol
                + " 当前价=$" + String.format("%.2f", result.currentPrice)
                + " 成本=$" + String.format("%.2f", pos.avgCost)
                + " 盈亏=" + String.format("%.2f%%", result.pnlPercent * 100));

            // 执行强制平仓
            executeForceClose(pos, result);
        }
    }

    /**
     * 执行强制平仓
     */
    private void executeForceClose(PositionManager.Position pos,
                                     PositionManager.CheckResult result) {
        try {
            // 检查安全网关（熔断状态下不允许新交易，但止盈止损是"保护性平仓"应放行）
            // 这里特殊处理：熔断时仍允许平仓，但不增加交易计数
            DexTrader dexTrader = new DexTrader();

            // SELL: 原生币 -> USDT（与 AIAgentActivity SELL 信号一致）
            String txHash = dexTrader.swapNativeToken(
                ctx, pos.chain, pos.amount, 0.5 // 0.5% 滑点
            );

            // 更新持仓
            PositionManager.onSell(ctx, pos.chain, pos.tokenSymbol, pos.amount, result.currentPrice);

            // 通知安全网关记录盈亏
            double pnl = (result.currentPrice - pos.avgCost) * pos.amount;
            if (pnl < 0) {
                safetyGate.onTradeLoss(-pnl);
            } else {
                safetyGate.onTradeProfit(pnl);
            }

            Logger.info(ctx, "持仓监控",
                (result.reason == PositionManager.TriggerReason.STOP_LOSS ? "止损" : "止盈")
                + "执行成功: " + pos.tokenSymbol + " tx=" + txHash
                + " 盈亏=$" + String.format("%.2f", pnl));

        } catch (Exception e) {
            Logger.error(ctx, "持仓监控",
                "强制平仓失败: " + pos.tokenSymbol + " " + e.getMessage());
            safetyGate.onTradeFailure();
        }
    }

    /**
     * 单次检查（供外部手动触发）
     */
    public void checkOnce() {
        executor.execute(this::checkAllPositions);
    }

    /**
     * 释放资源
     */
    public void shutdown() {
        stop();
        executor.shutdownNow();
    }
}
