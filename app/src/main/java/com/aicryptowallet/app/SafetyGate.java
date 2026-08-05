package com.aicryptowallet.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统一安全网关 - Agent Runtime 安全层核心组件
 *
 * 用户选择的安全策略：全自动 + 熔断
 * AI 全自动执行，但设硬性熔断（连续亏损/单日次数/异常波动自动停止）
 *
 * 所有 ContractCaller.callWrite 必须经过此网关校验
 * 所有 AIAgentActivity.executeTrade 也应经过此网关
 *
 * 核心机制：
 * 1. 合约白名单（可配置，首次调用需用户确认；白名单内全自动）
 * 2. 单笔限额 / 日累计限额 / 仓位比例
 * 3. 连续亏损熔断（连续 N 次亏损自动停止 AI）
 * 4. 单日交易次数熔断（超过自动停止）
 * 5. 异常错误率熔断（API 错误率 >30% 自动停止）
 * 6. 全量审计日志（所有决策与执行记录）
 *
 * 数据持久化到 SharedPreferences，跨重启保留
 */
public class SafetyGate {
    private static final String PREFS_NAME = "safety_gate_state";
    private static final String KEY_CONSECUTIVE_LOSSES = "consecutive_losses";
    private static final String KEY_DAILY_TRADE_COUNT = "daily_trade_count";
    private static final String KEY_DAILY_STATS_DATE = "daily_stats_date";
    private static final String KEY_CIRCUIT_BREAKER_UNTIL = "circuit_breaker_until";
    private static final String KEY_WHITELIST = "contract_whitelist";
    private static final String KEY_DAILY_TRADE_VOLUME = "daily_trade_volume";

    // 默认熔断阈值
    public static final int MAX_CONSECUTIVE_LOSSES = 3;       // 连续 3 次亏损熔断
    public static final int MAX_DAILY_TRADE_COUNT = 20;        // 单日 20 笔熔断
    public static final double MAX_DAILY_TRADE_VOLUME = 5000;  // 单日累计 $5000 熔断
    public static final double MAX_ERROR_RATE = 0.3;           // 错误率 30% 熔断
    public static final long CIRCUIT_BREAKER_DURATION = 6 * 60 * 60 * 1000L; // 熔断 6 小时

    // 单笔金额阈值（超过此值需要额外审计但不阻断）
    public static final double LARGE_TRADE_THRESHOLD = 200;    // $200 以上为大额

    private final Context ctx;
    private final TradeAuthManager authManager;
    private final RiskManager riskManager;
    private final DAppWhitelistManager dappWhitelistManager;

    // 内存中的错误计数（用于错误率熔断）
    private final AtomicInteger totalAttempts = new AtomicInteger(0);
    private final AtomicInteger failedAttempts = new AtomicInteger(0);

    // Activity 引用 - 用于白名单确认弹窗（仅在 Activity 运行期间持有）
    private volatile Activity hostActivity;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    public SafetyGate(Context ctx, TradeAuthManager authManager, RiskManager riskManager) {
        this.ctx = ctx.getApplicationContext();
        this.authManager = authManager;
        this.riskManager = riskManager;
        this.dappWhitelistManager = new DAppWhitelistManager(this.ctx);
        checkAndResetDailyStats();
    }

    /**
     * 绑定 Activity - 在 Activity onResume 时调用，用于弹窗
     */
    public void attachActivity(Activity activity) {
        this.hostActivity = activity;
    }

    /**
     * 解绑 Activity - 在 Activity onPause 时调用，避免内存泄漏
     */
    public void detachActivity() {
        this.hostActivity = null;
    }

    /**
     * 校验结果
     */
    public static class CheckResult {
        public boolean allowed;
        public String reason;
        public boolean needsUserConfirm;  // 是否需要用户确认（白名单外）

        public CheckResult(boolean allowed, String reason, boolean needsUserConfirm) {
            this.allowed = allowed;
            this.reason = reason;
            this.needsUserConfirm = needsUserConfirm;
        }

        public static CheckResult allow() {
            return new CheckResult(true, "允许", false);
        }

        public static CheckResult deny(String reason) {
            return new CheckResult(false, reason, false);
        }

        public static CheckResult needsConfirm(String reason) {
            return new CheckResult(false, reason, true);
        }
    }

    /**
     * 核心校验方法：检查合约调用是否被允许
     * @param contract 目标合约地址
     * @param value 附带的原生币数量（wei）
     * @param operationDesc 操作描述
     */
    public CheckResult check(String contract, BigInteger value, String operationDesc) {
        return checkInternal(contract, value, operationDesc, false, null, null);
    }

    /**
     * 带白名单确认的校验方法 - 当目标代币不在白名单时，弹窗询问用户：
     *   "加入白名单并继续" / "本次允许（不加入白名单）" / "拒绝"
     * 调用线程会被阻塞，直到用户在 UI 上做出选择。
     *
     * @param contract       目标代币合约地址（如 0x...）
     * @param value          附带的原生币数量（wei）
     * @param operationDesc  操作描述
     * @param tokenSymbol    代币符号（用于弹窗展示，可为 null）
     * @return CheckResult
     */
    public CheckResult checkWithWhitelistConfirm(String contract, BigInteger value,
                                                   String operationDesc, String tokenSymbol) {
        return checkInternal(contract, value, operationDesc, true, tokenSymbol, null);
    }

    /**
     * 带白名单确认 + 金额估算的校验方法 - 用于 swap_tokens 工具
     */
    public CheckResult checkWithWhitelistConfirm(String contract, BigInteger value,
                                                   String operationDesc, String tokenSymbol,
                                                   double approxUsd) {
        return checkInternal(contract, value, operationDesc, true, tokenSymbol, approxUsd);
    }

    /**
     * 校验 DApp 发起的交易：优先检查 DApp 白名单，命中则自动放行；
     * 否则回退到普通代币白名单确认流程。
     *
     * @param domain        DApp 域名（origin 或 normalize 后的域名）
     * @param contract      目标合约地址
     * @param value         附带的原生币数量（wei）
     * @param operationDesc 操作描述
     * @param approxUsd     预估交易金额（USD）
     */
    public CheckResult checkDAppTransaction(String domain, String contract, BigInteger value,
                                              String operationDesc, double approxUsd) {
        CheckResult base = checkBaseLimits(operationDesc);
        if (!base.allowed) return base;

        if (domain != null && !domain.isEmpty()
                && dappWhitelistManager.isWhitelisted(domain)
                && dappWhitelistManager.isOperationAllowed(domain, "transaction")
                && dappWhitelistManager.checkTransactionAllowed(domain, approxUsd)) {
            Logger.info(ctx, "安全网关", "DApp白名单自动放行: domain=" + domain
                + " 金额=$" + String.format("%.2f", approxUsd));
            return CheckResult.allow();
        }

        return checkTokenWhitelist(contract, value, operationDesc, true, null, approxUsd);
    }

    /**
     * 内部统一校验实现
     * @param needWhitelistConfirm 是否启用白名单确认弹窗
     * @param tokenSymbol          代币符号（展示用）
     * @param approxUsdOverride    USD 金额覆盖（若为 null 则用 estimateValueUsd 估算）
     */
    private CheckResult checkInternal(String contract, BigInteger value, String operationDesc,
                                        boolean needWhitelistConfirm, String tokenSymbol, Double approxUsdOverride) {
        CheckResult base = checkBaseLimits(operationDesc);
        if (!base.allowed) return base;
        return checkTokenWhitelist(contract, value, operationDesc, needWhitelistConfirm, tokenSymbol, approxUsdOverride);
    }

    /**
     * 基础安全限制检查：熔断、自动交易开关、单日次数/金额、连续亏损。
     */
    private CheckResult checkBaseLimits(String operationDesc) {
        // 1. 熔断检查（最高优先级）
        long breakerUntil = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_CIRCUIT_BREAKER_UNTIL, 0);
        if (System.currentTimeMillis() < breakerUntil) {
            long remaining = (breakerUntil - System.currentTimeMillis()) / 60000;
            return CheckResult.deny("熔断中，剩余 " + remaining + " 分钟（连续亏损/超限触发）");
        }

        // 2. 自动交易开关检查
        if (!authManager.isAutoTradeEnabled()) {
            return CheckResult.deny("AI 自动交易未开启");
        }

        // 3. 单日交易次数检查
        int dailyCount = getDailyTradeCount();
        if (dailyCount >= MAX_DAILY_TRADE_COUNT) {
            triggerCircuitBreaker("单日交易次数超限: " + dailyCount);
            return CheckResult.deny("单日交易次数超限 (" + dailyCount + "/" + MAX_DAILY_TRADE_COUNT + ")，已触发熔断");
        }

        // 4. 单日累计金额检查
        double dailyVolume = getDailyTradeVolume();
        if (dailyVolume >= MAX_DAILY_TRADE_VOLUME) {
            triggerCircuitBreaker("单日交易金额超限: $" + dailyVolume);
            return CheckResult.deny("单日交易金额超限 ($" + dailyVolume + "/$" + MAX_DAILY_TRADE_VOLUME + ")，已触发熔断");
        }

        // 5. 连续亏损检查
        int consecutiveLosses = getConsecutiveLosses();
        if (consecutiveLosses >= MAX_CONSECUTIVE_LOSSES) {
            triggerCircuitBreaker("连续亏损 " + consecutiveLosses + " 次");
            return CheckResult.deny("连续亏损 " + consecutiveLosses + " 次触发熔断");
        }

        return CheckResult.allow();
    }

    /**
     * 代币合约白名单检查 + 金额审计。
     */
    private CheckResult checkTokenWhitelist(String contract, BigInteger value, String operationDesc,
                                              boolean needWhitelistConfirm, String tokenSymbol, Double approxUsdOverride) {
        // 6. 合约白名单检查 —— 默认黑名单，白名单外必须用户确认
        // 原生币（NATIVE / DEX_ROUTER / 空）豁免白名单
        // R-MAB 平台币永久豁免白名单
        boolean isNativeLike = contract == null
            || "NATIVE".equalsIgnoreCase(contract)
            || "DEX_ROUTER".equalsIgnoreCase(contract)
            || TokenRiskAnalyzer.RMAB_CONTRACT.equalsIgnoreCase(contract);
        boolean inWhitelist = isNativeLike || isWhitelisted(contract);

        if (!inWhitelist) {
            Logger.warning(ctx, "安全网关", "调用非白名单合约: " + contract + " 操作: " + operationDesc);
            if (needWhitelistConfirm) {
                // 同步弹窗询问用户
                WhitelistConfirmDecision decision = showWhitelistConfirmDialog(contract, tokenSymbol, operationDesc);
                switch (decision) {
                    case ADD_AND_CONTINUE:
                        addToWhitelist(contract);
                        Logger.info(ctx, "安全网关", "用户批准并将代币加入白名单: " + contract);
                        inWhitelist = true;
                        break;
                    case ONCE_ALLOW:
                        Logger.info(ctx, "安全网关", "用户本次允许（不加入白名单）: " + contract);
                        break;
                    case DENY:
                        return CheckResult.deny("用户拒绝交易非白名单代币: " + contract);
                    case TIMEOUT_OR_NO_UI:
                        return CheckResult.deny("未获得用户确认（超时或无 UI 上下文），已拦截买入: " + contract);
                }
            } else {
                // 未启用白名单确认：保持旧行为（仅记录日志，不阻断）
                // 仅在已知的低风险路径保留此分支（如 approve_token 已在 swap 之前完成白名单校验）
            }
        }

        // 7. 金额审计
        double valueUsd = (approxUsdOverride != null) ? approxUsdOverride : estimateValueUsd(value);
        if (valueUsd > LARGE_TRADE_THRESHOLD) {
            Logger.warning(ctx, "安全网关", "大额交易: $" + String.format("%.2f", valueUsd)
                + " 合约=" + contract + " 操作=" + operationDesc);
        }

        // 全部通过
        Logger.info(ctx, "安全网关", "放行: " + operationDesc
            + " 合约=" + contract + " 金额≈$" + String.format("%.2f", valueUsd)
            + " 白名单=" + inWhitelist);
        return CheckResult.allow();
    }

    /** 用户对白名单确认弹窗的决策 */
    private enum WhitelistConfirmDecision {
        ADD_AND_CONTINUE,   // 加入白名单并继续
        ONCE_ALLOW,         // 仅本次允许
        DENY,               // 拒绝
        TIMEOUT_OR_NO_UI    // 超时或无 Activity（拒绝）
    }

    /**
     * 同步弹窗：询问用户是否将代币加入白名单
     * 调用线程（Agent 后台线程）会被 CountDownLatch 阻塞，直到用户在 UI 线程做出选择
     * 超时 60 秒自动拒绝，避免 Agent 卡死
     */
    private WhitelistConfirmDecision showWhitelistConfirmDialog(String contract, String tokenSymbol, String operationDesc) {
        if (hostActivity == null || hostActivity.isFinishing() || hostActivity.isDestroyed()) {
            Logger.warning(ctx, "安全网关", "无可用 Activity，无法弹窗，拒绝交易: " + contract);
            return WhitelistConfirmDecision.TIMEOUT_OR_NO_UI;
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final WhitelistConfirmDecision[] decision = new WhitelistConfirmDecision[1];
        decision[0] = WhitelistConfirmDecision.TIMEOUT_OR_NO_UI;

        String displaySymbol = (tokenSymbol != null && !tokenSymbol.isEmpty()) ? tokenSymbol : "未知名代币";
        String shortContract = contract.substring(0, 10) + "..." + contract.substring(contract.length() - 6);
        String message = "AI 准备买入代币：\n\n"
            + "  代币符号: " + displaySymbol + "\n"
            + "  合约地址: " + shortContract + "\n"
            + "  操作描述: " + operationDesc + "\n\n"
            + "⚠️ 该代币不在交易白名单中\n"
            + "默认所有代币都是黑名单，请确认是否允许交易：\n\n"
            + "• 加入白名单并继续：以后该代币 AI 可自动买入\n"
            + "• 仅本次允许：本次买入但不加入白名单\n"
            + "• 拒绝：取消本次交易";

        uiHandler.post(() -> {
            try {
                new AlertDialog.Builder(hostActivity)
                    .setTitle(ctx.getString(R.string.title_transaction_whitelist_confirmation))
                    .setMessage(message)
                    .setPositiveButton(ctx.getString(R.string.label_join_the_whitelist_and_continue), (DialogInterface d, int w) -> {
                        decision[0] = WhitelistConfirmDecision.ADD_AND_CONTINUE;
                        latch.countDown();
                    })
                    .setNeutralButton(ctx.getString(R.string.label_allowed_this_time_only), (DialogInterface d, int w) -> {
                        decision[0] = WhitelistConfirmDecision.ONCE_ALLOW;
                        latch.countDown();
                    })
                    .setNegativeButton(ctx.getString(R.string.btn_reject), (DialogInterface d, int w) -> {
                        decision[0] = WhitelistConfirmDecision.DENY;
                        latch.countDown();
                    })
                    .setOnCancelListener(d -> {
                        decision[0] = WhitelistConfirmDecision.DENY;
                        latch.countDown();
                    })
                    .setCancelable(false)
                    .show();
            } catch (Exception e) {
                Logger.error(ctx, "安全网关", "弹窗显示失败: " + e.getMessage(), e);
                decision[0] = WhitelistConfirmDecision.TIMEOUT_OR_NO_UI;
                latch.countDown();
            }
        });

        try {
            // 最多等待 60 秒，避免 Agent 永久卡死
            if (!latch.await(60, TimeUnit.SECONDS)) {
                Logger.warning(ctx, "安全网关", "白名单确认弹窗超时 60s，自动拒绝: " + contract);
                return WhitelistConfirmDecision.TIMEOUT_OR_NO_UI;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return WhitelistConfirmDecision.TIMEOUT_OR_NO_UI;
        }
        return decision[0];
    }

    /**
     * 交易成功后回调
     */
    public void onTradeSuccess(double tradeAmountUsd) {
        incrementDailyTradeCount();
        addDailyTradeVolume(tradeAmountUsd);
        totalAttempts.incrementAndGet();
    }

    /**
     * 交易失败后回调
     */
    public void onTradeFailure() {
        totalAttempts.incrementAndGet();
        failedAttempts.incrementAndGet();
        // 检查错误率
        if (totalAttempts.get() >= 10) {
            double errorRate = (double) failedAttempts.get() / totalAttempts.get();
            if (errorRate > MAX_ERROR_RATE) {
                triggerCircuitBreaker("错误率超限: " + String.format("%.1f%%", errorRate * 100));
            }
            // 重置计数器，滑动窗口
            if (totalAttempts.get() >= 20) {
                totalAttempts.set(0);
                failedAttempts.set(0);
            }
        }
    }

    /**
     * 交易亏损后回调
     */
    public void onTradeLoss(double lossAmount) {
        int losses = getConsecutiveLosses() + 1;
        setConsecutiveLosses(losses);
        Logger.warning(ctx, "安全网关", "交易亏损 $" + String.format("%.2f", lossAmount)
            + "，连续亏损 " + losses + "/" + MAX_CONSECUTIVE_LOSSES);
        if (losses >= MAX_CONSECUTIVE_LOSSES) {
            triggerCircuitBreaker("连续亏损 " + losses + " 次");
        }
    }

    /**
     * 交易盈利后回调（重置连续亏损计数）
     */
    public void onTradeProfit(double profitAmount) {
        if (getConsecutiveLosses() > 0) {
            setConsecutiveLosses(0);
            Logger.info(ctx, "安全网关", "交易盈利 $" + String.format("%.2f", profitAmount)
                + "，重置连续亏损计数");
        }
    }

    /**
     * 触发熔断
     */
    public void triggerCircuitBreaker(String reason) {
        long until = System.currentTimeMillis() + CIRCUIT_BREAKER_DURATION;
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_CIRCUIT_BREAKER_UNTIL, until).apply();
        Logger.error(ctx, "安全网关", "!!! 触发熔断 !!! 原因=" + reason
            + " 持续=" + (CIRCUIT_BREAKER_DURATION / 3600000) + "小时"
            + " 恢复时间=" + new java.util.Date(until));
        // 同时关闭自动交易
        authManager.setAutoTradeEnabled(false);
    }

    /**
     * 手动解除熔断
     */
    public void resetCircuitBreaker() {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_CIRCUIT_BREAKER_UNTIL, 0)
            .putInt(KEY_CONSECUTIVE_LOSSES, 0)
            .apply();
        Logger.info(ctx, "安全网关", "熔断已手动解除");
    }

    /**
     * 检查是否处于熔断状态
     */
    public boolean isCircuitBroken() {
        long until = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_CIRCUIT_BREAKER_UNTIL, 0);
        return System.currentTimeMillis() < until;
    }

    /**
     * 获取熔断剩余时间（分钟）
     */
    public long getCircuitBreakerRemainingMinutes() {
        long until = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_CIRCUIT_BREAKER_UNTIL, 0);
        long remaining = until - System.currentTimeMillis();
        return remaining > 0 ? remaining / 60000 : 0;
    }

    // ========== 合约白名单 ==========

    public boolean isWhitelisted(String contract) {
        if (contract == null) return false;
        String whitelist = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_WHITELIST, "");
        if (whitelist.isEmpty()) return false;
        return whitelist.toLowerCase().contains(contract.toLowerCase());
    }

    public void addToWhitelist(String contract) {
        if (contract == null) return;
        Set<String> set = getWhitelistSet();
        set.add(contract.toLowerCase());
        saveWhitelistSet(set);
        Logger.info(ctx, "安全网关", "添加白名单: " + contract);
    }

    public Set<String> getWhitelistSet() {
        String whitelist = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_WHITELIST, "");
        Set<String> set = new HashSet<>();
        if (!whitelist.isEmpty()) {
            for (String s : whitelist.split(",")) {
                if (!s.isEmpty()) set.add(s);
            }
        }
        return set;
    }

    private void saveWhitelistSet(Set<String> set) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_WHITELIST, String.join(",", set)).apply();
    }

    // ========== 内部辅助方法 ==========

    private int getConsecutiveLosses() {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_CONSECUTIVE_LOSSES, 0);
    }

    private void setConsecutiveLosses(int n) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_CONSECUTIVE_LOSSES, n).apply();
    }

    private int getDailyTradeCount() {
        checkAndResetDailyStats();
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_DAILY_TRADE_COUNT, 0);
    }

    private void incrementDailyTradeCount() {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_DAILY_TRADE_COUNT, getDailyTradeCount() + 1).apply();
    }

    private double getDailyTradeVolume() {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_DAILY_TRADE_VOLUME, 0);
    }

    private void addDailyTradeVolume(double amount) {
        double current = getDailyTradeVolume();
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_DAILY_TRADE_VOLUME, (float) (current + amount)).apply();
    }

    /**
     * 检查并重置每日统计（跨天时自动重置）
     */
    private void checkAndResetDailyStats() {
        long today = System.currentTimeMillis() / (1000 * 60 * 60 * 24);
        long savedDay = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_DAILY_STATS_DATE, today);
        if (today != savedDay) {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_DAILY_STATS_DATE, today)
                .putInt(KEY_DAILY_TRADE_COUNT, 0)
                .putFloat(KEY_DAILY_TRADE_VOLUME, 0)
                .apply();
            Logger.info(ctx, "安全网关", "跨天重置每日统计");
        }
    }

    /**
     * 估算 wei 价值的 USD 金额（粗略，用于审计日志和限额检查）
     */
    private double estimateValueUsd(BigInteger valueWei) {
        if (valueWei == null || valueWei.equals(BigInteger.ZERO)) return 0;
        try {
            String chain = WalletManager.getChain(ctx);
            java.util.Map<String, Double> prices = ChainAPI.getPrices(ctx);
            double price = prices.getOrDefault(chain, 0.0);
            double amount = valueWei.doubleValue() / Math.pow(10, 18);
            return amount * price;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 获取安全状态摘要（供 UI 展示和 AI 上下文注入）
     */
    public String getSafetyStatusSummary() {
        StringBuilder sb = new StringBuilder();
        if (isCircuitBroken()) {
            sb.append("⚠️ 熔断中，剩余 ").append(getCircuitBreakerRemainingMinutes()).append(" 分钟\n");
        } else {
            sb.append("✓ 安全网关正常\n");
        }
        sb.append("今日交易: ").append(getDailyTradeCount()).append("/").append(MAX_DAILY_TRADE_COUNT).append(" 次\n");
        sb.append("今日累计: $").append(String.format("%.2f", getDailyTradeVolume()))
          .append("/$").append(MAX_DAILY_TRADE_VOLUME).append("\n");
        sb.append("连续亏损: ").append(getConsecutiveLosses()).append("/").append(MAX_CONSECUTIVE_LOSSES).append(" 次\n");
        sb.append("白名单合约: ").append(getWhitelistSet().size()).append(" 个\n");
        return sb.toString().trim();
    }
}