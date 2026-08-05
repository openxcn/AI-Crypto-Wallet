package com.aicryptowallet.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.List;
import java.util.Map;

/**
 * AI 交易授权管理器 - 控制用户是否允许 AI 自动交易
 */
public class TradeAuthManager {
    private static final String PREFS = "trade_auth_prefs";
    private static final String KEY_AUTH_ENABLED = "auth_enabled";
    private static final String KEY_MAX_TRADE_AMOUNT = "max_trade_amount";
    private static final String KEY_SINGLE_TRADE_LIMIT = "single_trade_limit";
    private static final String KEY_DAILY_LOSS_LIMIT = "daily_loss_limit";
    private static final String KEY_LAST_TRADE_TIME = "last_trade_time";

    private SharedPreferences prefs;

    // 缓存最近一次检查结果，避免连续调用重复查询
    private static long lastCheckTime = 0;
    private static boolean lastCheckResult = false;
    private static String lastCheckReason = "";
    private static final long CHECK_CACHE_MS = 5000;

    public TradeAuthManager(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isAutoTradeEnabled() {
        return prefs.getBoolean(KEY_AUTH_ENABLED, false);
    }

    public void setAutoTradeEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTH_ENABLED, enabled).apply();
    }

    public double getMaxTradeAmount() {
        return prefs.getFloat(KEY_MAX_TRADE_AMOUNT, 100);
    }

    public void setMaxTradeAmount(double amount) {
        prefs.edit().putFloat(KEY_MAX_TRADE_AMOUNT, (float) amount).apply();
    }

    public int getDailyTradeLimit() {
        return prefs.getInt(KEY_SINGLE_TRADE_LIMIT, 20);
    }

    public void setDailyTradeLimit(int limit) {
        prefs.edit().putInt(KEY_SINGLE_TRADE_LIMIT, limit).apply();
    }

    public double getDailyLossLimit() {
        return prefs.getFloat(KEY_DAILY_LOSS_LIMIT, 500);
    }

    public void setDailyLossLimit(double limit) {
        prefs.edit().putFloat(KEY_DAILY_LOSS_LIMIT, (float) limit).apply();
    }

    /**
     * 后台查询 AI 开启条件结果
     */
    public static class CheckResult {
        public final boolean allowed;
        public final String reason;
        public CheckResult(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }
    }

    /**
     * 后台线程检查是否可以开启 AI（使用和资产页相同的 getAllTokenBalances 批量查询）
     * 注意：必须在后台线程调用，不要在主线程调用
     */
    public static CheckResult checkAsync(Context ctx) {
        try {
            String chain = WalletManager.getChain(ctx);
            String address = WalletManager.getWalletAddress(ctx);
            Logger.info(ctx, "AI授权", "checkAsync 开始检查 chain=" + chain + " address=" + address);

            double nativeBalance = 0;
            double rmabBalance = 0;
            boolean nativeOk = false;
            boolean rmabOk = false;

            // 查询原生币余额（和资产页同样的方式）
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    nativeBalance = ChainAPI.getNativeBalance(ctx, chain, address);
                    nativeOk = true;
                    Logger.info(ctx, "AI授权", "原生币余额: " + nativeBalance + " " + chain);
                    break;
                } catch (Exception e) {
                    Logger.error(ctx, "AI授权", "原生币查询第" + (attempt+1) + "次失败: " + e.getMessage());
                    if (attempt < 2) {
                        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                    }
                }
            }

            // 用 getAllTokenBalances 批量查询（和资产页完全相同的方式，这个方法能查到 R-MAB）
            try {
                List<String[]> allTokens = ChainAPI.getAllTokenBalances(ctx, "BNB", address);
                if (allTokens != null) {
                    Logger.info(ctx, "AI授权", "批量查询返回 " + allTokens.size() + " 个代币");
                    for (String[] token : allTokens) {
                        if (token.length >= 5) {
                            String contract = token[4] != null ? token[4].toLowerCase() : "";
                            String symbol = token[0] != null ? token[0].toUpperCase() : "";
                            // 匹配 R-MAB：合约地址匹配，或符号包含 R-MAB/RMAB
                            boolean isRMAB = contract.equalsIgnoreCase(AppConfig.SMART_TOKEN_CONTRACT)
                                || symbol.contains("R-MAB") || symbol.contains("RMAB");
                            if (isRMAB) {
                                try {
                                    rmabBalance = Double.parseDouble(token[2]);
                                    rmabOk = true;
                                    Logger.success(ctx, "AI授权", "R-MAB 余额(批量查询): " + rmabBalance);
                                } catch (NumberFormatException nfe) {
                                    Logger.error(ctx, "AI授权", "R-MAB 余额解析失败: " + token[2]);
                                }
                                break;
                            }
                        }
                    }
                }
                if (!rmabOk) {
                    Logger.info(ctx, "AI授权", "批量查询未找到 R-MAB，尝试备用节点单查");
                    // 批量查询没找到 R-MAB，可能是节点不支持批量，遍历 BSC 备用节点单查
                    NodeManager.NodeEntry[] presets = NodeManager.getPresets("BNB");
                    String currentNode = NodeManager.getSelectedNode(ctx, "BNB");
                    for (NodeManager.NodeEntry entry : presets) {
                        try {
                            NodeManager.setSelectedNode(ctx, "BNB", entry.url);
                            try {
                                double bal = ChainAPI.getERC20Balance(
                                    ctx, "BNB", address, AppConfig.SMART_TOKEN_CONTRACT, 8);
                                rmabBalance = bal;
                                rmabOk = true;
                                Logger.success(ctx, "AI授权", "R-MAB 余额(备用 " + entry.name + "): " + rmabBalance);
                                break;
                            } catch (Exception e) {
                                Logger.error(ctx, "AI授权", "备用节点 " + entry.name + " 单查失败: " + e.getMessage());
                            } finally {
                                NodeManager.setSelectedNode(ctx, "BNB", currentNode);
                            }
                        } catch (Exception e2) {
                            Logger.error(ctx, "AI授权", "备用节点异常: " + e2.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                Logger.error(ctx, "AI授权", "批量查询代币失败: " + e.getMessage());
                // 批量查询失败，回退到单节点单查
                NodeManager.NodeEntry[] presets = NodeManager.getPresets("BNB");
                String currentNode = NodeManager.getSelectedNode(ctx, "BNB");
                for (NodeManager.NodeEntry entry : presets) {
                    try {
                        NodeManager.setSelectedNode(ctx, "BNB", entry.url);
                        try {
                            rmabBalance = ChainAPI.getERC20Balance(
                                ctx, "BNB", address, AppConfig.SMART_TOKEN_CONTRACT, 8);
                            rmabOk = true;
                            Logger.success(ctx, "AI授权", "R-MAB 余额(回退 " + entry.name + "): " + rmabBalance);
                            break;
                        } catch (Exception e2) {
                            // 继续下一个节点
                        } finally {
                            NodeManager.setSelectedNode(ctx, "BNB", currentNode);
                        }
                    } catch (Exception ignored) {}
                }
            }

            // 计算原生币 USD 价值
            double totalValue = 0;
            if (nativeOk) {
                try {
                    Map<String, Double> prices = ChainAPI.getPrices(ctx);
                    double price = prices.getOrDefault(chain, 0.0);
                    totalValue = nativeBalance * price;
                    Logger.info(ctx, "AI授权", "原生币价值: $" + String.format("%.2f", totalValue) + " (price=$" + price + ")");
                } catch (Exception e) {
                    Logger.error(ctx, "AI授权", "价格查询失败: " + e.getMessage());
                }
            }

            // 判断条件
            boolean cond1 = nativeOk && totalValue >= AppConfig.MIN_BALANCE_FOR_AI;
            boolean cond2 = rmabOk && rmabBalance >= AppConfig.RMAB_THRESHOLD_FOR_AI;

            if (cond1) {
                Logger.success(ctx, "AI授权", "条件1满足（主流币 ≥ $200），放行");
                return new CheckResult(true, "");
            }
            if (cond2) {
                Logger.success(ctx, "AI授权", "条件2满足（R-MAB=" + rmabBalance + " ≥ 20000），放行");
                return new CheckResult(true, "");
            }

            if (!nativeOk && !rmabOk) {
                String reason = "网络连接异常，无法查询资产。请检查网络或节点配置后重试。";
                Logger.warning(ctx, "AI授权", "两个查询均失败");
                return new CheckResult(false, reason);
            }

            String reason = String.format(java.util.Locale.getDefault(),
                "当前主流币资产 $%.2f（需 ≥ $200），R-MAB 持有 %.2f（需 ≥ 20000）。满足任一条件即可开启 AI 自动交易。",
                totalValue, rmabBalance);
            Logger.warning(ctx, "AI授权", "条件不满足: " + reason);
            return new CheckResult(false, reason);

        } catch (Exception e) {
            Logger.error(ctx, "AI授权", "checkAsync 异常: " + e.getMessage(), e);
            return new CheckResult(false, "检查资产失败: " + e.getMessage());
        }
    }

    /**
     * 检查是否可以开启 AI 自动交易（主线程调用时使用缓存结果，避免主线程网络请求）
     * 建议调用方在后台线程使用 checkAsync() 获取实时结果
     */
    public boolean canEnableAITrade(Context ctx) {
        // 如果有缓存且未过期，直接返回缓存
        if (System.currentTimeMillis() - lastCheckTime < CHECK_CACHE_MS && lastCheckReason != null) {
            return lastCheckResult;
        }
        // 主线程不能做网络请求，如果缓存过期了，后台异步刷新缓存
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Logger.info(ctx, "AI授权", "主线程调用canEnableAITrade，使用缓存或默认拒绝");
            // 返回上次结果，如果从未检查过则返回false（让UI提示后后台检查）
            return lastCheckResult;
        }
        CheckResult result = checkAsync(ctx);
        lastCheckResult = result.allowed;
        lastCheckReason = result.reason;
        lastCheckTime = System.currentTimeMillis();
        return result.allowed;
    }

    /**
     * 获取不满足条件的原因（主线程安全，返回缓存结果）
     */
    public String getAIDisabledReason(Context ctx) {
        if (lastCheckReason != null && !lastCheckReason.isEmpty()) {
            return lastCheckReason;
        }
        return "正在检查资产状态，请稍候...";
    }

    /**
     * 在后台线程执行检查并回调结果（供主线程调用）
     */
    public void checkInBackground(Context ctx, CheckCallback callback) {
        new Thread(() -> {
            CheckResult result = checkAsync(ctx);
            lastCheckResult = result.allowed;
            lastCheckReason = result.reason;
            lastCheckTime = System.currentTimeMillis();
            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onResult(result));
            }
        }).start();
    }

    public interface CheckCallback {
        void onResult(CheckResult result);
    }

    public boolean canTrade() {
        if (!isAutoTradeEnabled()) {
            return false;
        }
        long lastTradeTime = prefs.getLong(KEY_LAST_TRADE_TIME, 0);
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastTradeTime < 30000) {
            return false;
        }
        return true;
    }

    public void recordTrade() {
        prefs.edit().putLong(KEY_LAST_TRADE_TIME, System.currentTimeMillis()).apply();
    }

    public void resetDailyStats() {
        long today = System.currentTimeMillis() / (1000 * 60 * 60 * 24);
        prefs.edit().putLong("daily_stats_date", today).putInt("daily_trade_count", 0).apply();
    }
}
