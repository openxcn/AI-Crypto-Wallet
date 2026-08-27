package com.aicryptowallet.app;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * AI 智能体前台服务 - App 关闭后仍保持 AI 运行和推送
 *
 * 产品定位：
 *  - 用户在 AIAgentActivity 启用智能体后，本服务启动并保持前台运行
 *  - 周期性（默认 5 分钟）调用 AgentRuntime 分析市场并自动执行交易
 *  - STRONG_BUY/STRONG_SELL 信号通过系统通知推送，并写入聊天记录
 *  - 按 agentMemory.newsReportIntervalHours 定时搜索新闻并 LLM 总结后推送
 *  - 当 AIAgentActivity 在前台时，本服务跳过分析，避免与 Activity 的 scheduler 重复
 *
 * 安全设计：
 *  - SafetyGate 绑定 null Activity，非白名单代币交易在无 UI 上下文时自动拒绝
 *  - 白名单内代币和原生币（NATIVE/DEX_ROUTER）可正常自动交易
 *  - 所有操作经 SafetyGate 校验，限额/熔断/审计与 Activity 模式一致
 */
public class AgentForegroundService extends Service {

    public static final int NOTIFICATION_ID_PERSIST = AINotificationHelper.NOTIFICATION_ID_PERSIST;
    public static final String PREFS = "ai_agent_prefs";
    /** 后台分析报告存储 Key（JSONArray，每项含 ts/content） */
    public static final String KEY_BACKGROUND_REPORTS = "background_analysis_reports";
    private static final int MAX_BACKGROUND_REPORTS = 50;
    private static final int CHECK_INTERVAL_MINUTES = 5;
    private static final int ASSET_CHECK_INTERVAL_SECONDS = 60;
    private static final MediaType JSON_TYPE = MediaType.parse("application/json");

    private ScheduledExecutorService scheduler;
    private ScheduledExecutorService assetScheduler;
    private ScheduledExecutorService chatScheduler;
    private TradeAuthManager tradeAuthManager;
    private RiskManager riskManager;
    private SafetyGate safetyGate;
    private AgentMemory agentMemory;
    private OkHttpClient httpClient;

    /** 当 AIAgentActivity 在前台时设为 true，本服务跳过分析避免与 Activity 的 scheduler 重复 */
    public static volatile boolean activityInForeground = false;

    /** 主动闲聊发送后通知已打开的聊天页刷新（由 AIAgentActivity 注册/注销） */
    public static volatile Runnable onProactiveChatListener;
    private static final android.os.Handler mainHandler =
        new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            AINotificationHelper.createChannels(this);
            startForeground(NOTIFICATION_ID_PERSIST, AINotificationHelper.buildPersistNotification(this, "AI 智能体后台运行中"));

            tradeAuthManager = new TradeAuthManager(this);
            riskManager = new RiskManager(this);
            safetyGate = new SafetyGate(this, tradeAuthManager, riskManager);
            // Service 无 Activity 上下文：非白名单代币交易会自动拒绝（符合项目规则）
            safetyGate.attachActivity(null);
            agentMemory = new AgentMemory(this);

            httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();

            Logger.info(this, "FGS", "AI 前台服务已创建");
        } catch (Exception e) {
            Logger.error(this, "FGS", "服务创建失败: " + e.getMessage(), e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (scheduler == null || scheduler.isShutdown()) {
                scheduler = Executors.newSingleThreadScheduledExecutor();
                // 延迟 10 秒首次执行，避免与 Activity 启动同时跑；之后按 CHECK_INTERVAL_MINUTES 分钟周期
                long intervalSeconds = CHECK_INTERVAL_MINUTES * 60L;
                scheduler.scheduleAtFixedRate(this::runAnalysisCycle, 10, intervalSeconds, TimeUnit.SECONDS);
                Logger.info(this, "FGS", "定时分析已启动，间隔 " + CHECK_INTERVAL_MINUTES + " 分钟");
            }

            // 独立的资产实时监测定时器（更短周期），前后台均监测资产变动并推送
            if (assetScheduler == null || assetScheduler.isShutdown()) {
                assetScheduler = Executors.newSingleThreadScheduledExecutor();
                assetScheduler.scheduleAtFixedRate(this::checkAssetChanges, 20,
                    ASSET_CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
                Logger.info(this, "FGS", "后台资产监测已启动，间隔 " + ASSET_CHECK_INTERVAL_SECONDS + " 秒");
            }

            // 主动闲聊定时器：按频率档位决定是否主动发一条闲聊消息
            if (chatScheduler == null || chatScheduler.isShutdown()) {
                chatScheduler = Executors.newSingleThreadScheduledExecutor();
                chatScheduler.scheduleAtFixedRate(this::maybeSendProactiveChat, 60,
                    CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES);
                Logger.info(this, "FGS", "主动闲聊调度已启动，间隔 " + CHECK_INTERVAL_MINUTES + " 分钟");
            }
        } catch (Exception e) {
            Logger.error(this, "FGS", "启动定时任务失败: " + e.getMessage(), e);
        }
        return START_STICKY;
    }

    private void runAnalysisCycle() {
        try {
            // 当 Activity 在前台时，让 Activity 的 scheduler 处理（避免重复分析）
            if (activityInForeground) {
                return;
            }

            // 门控1：距上次真正分析未达用户配置间隔则跳过（节省 LLM 调用）
            int intervalMin = AIAgentSettings.getAnalysisIntervalMinutes(this);
            if (intervalMin > 0) {
                long lastTs = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getLong("aiAgent_lastAnalysisTs", 0L);
                long now = System.currentTimeMillis();
                if (lastTs > 0 && now - lastTs < intervalMin * 60L * 1000L) {
                    return;
                }
            }

            // 门控2：资产没有变动（无买卖/转账/余额变化）则不调用大模型分析，避免空耗 token
            if (!hasAssetActivity()) {
                Logger.info(this, "FGS", "资产无变动，跳过 LLM 分析（省 token）");
                return;
            }

            // 注意：不检查 autoTradeEnabled —— 用户启动了 Agent 就要持续监控分析
            // 是否执行真实交易由 SafetyGate 在工具调用层拦截（autoTradeEnabled=false 时写入工具被拒绝）

            // 多链监控：收集用户所有钱包覆盖的链，对每条链分别分析
            java.util.List<String> chains = collectMonitorChains();
            if (chains.isEmpty()) {
                Logger.warning(this, "FGS", "没有可监控的链，跳过本轮分析");
                return;
            }

            String primaryCycle = TradingCycleConfig.getPrimaryCycle(this);
            // 本轮已确认执行分析，更新"上次分析"时间戳
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong("aiAgent_lastAnalysisTs", System.currentTimeMillis()).apply();

            int analyzed = 0;
            for (String chain : chains) {
                analyzed += analyzeChain(chain, primaryCycle);
            }

            if (analyzed > 0) {
                AIOperationLogManager.logAnalysis(this, "ALL", "AI 定时分析完成",
                    "已分析 " + analyzed + " 条链", "success");
            }
        } catch (Exception e) {
            Logger.error(this, "FGS", "分析周期失败: " + e.getMessage(), e);
            AIOperationLogManager.logAnalysis(this, "ALL", "AI 定时分析失败", e.getMessage(), "failed");
        }
    }

    /** 收集需要监控的链：所有钱包覆盖的去重后的链列表；无钱包时回退到当前链 */
    private java.util.List<String> collectMonitorChains() {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        try {
            java.util.List<WalletManager.WalletInfo> wallets = WalletManager.getAllWallets(this);
            for (WalletManager.WalletInfo w : wallets) {
                if (w != null && w.chain != null && !w.chain.isEmpty()) {
                    set.add(w.chain);
                }
            }
        } catch (Exception e) {
            Logger.warning(this, "FGS", "读取钱包列表失败: " + e.getMessage());
        }
        if (set.isEmpty()) {
            String chain = WalletManager.getChain(this);
            if (chain != null && !chain.isEmpty()) set.add(chain);
            else set.add("ETH");
        }
        return new java.util.ArrayList<>(set);
    }

    /**
     * 轻量检测当前钱包资产是否有变动（余额/代币变化）。
     * 无变动返回 false，此时不调用大模型做分析，以达到省 token 的目的。
     * 检测失败时保守放行（返回 true），避免漏掉真正的资产变动。
     */
    private boolean hasAssetActivity() {
        try {
            String chain = WalletManager.getChain(this);
            String address = WalletManager.getWalletAddress(this);
            if (chain == null || chain.isEmpty() || address == null || address.isEmpty()) return false;

            double nativeBalance;
            try {
                nativeBalance = ChainAPI.getNativeBalance(this, chain, address);
            } catch (Exception e) {
                return true;
            }
            java.util.List<String[]> tokens;
            try {
                tokens = ChainAPI.getAllTokenBalances(this, chain, address, false);
            } catch (Exception e) {
                tokens = new java.util.ArrayList<>();
            }

            java.util.List<String[]> allTokens = new java.util.ArrayList<>();
            allTokens.add(new String[]{chain, ChainAPI.getChainName(chain),
                ChainAPI.formatAmount(nativeBalance), "0", "", "true"});
            allTokens.addAll(tokens);

            DataCache.AssetChangeResult chg = new DataCache(this)
                .detectAssetChange(address, allTokens, nativeBalance);
            return chg.shouldNotify;
        } catch (Exception e) {
            Logger.warning(this, "FGS", "资产变动检测失败，保守放行: " + e.getMessage());
            return true;
        }
    }

    /** 对单条链执行一次完整分析，返回是否成功分析 */
    private int analyzeChain(String chain, String primaryCycle) {
        try {
            // 拉取主周期 K 线
            MarketData data = MultiChainMarketData.getKlines(chain, primaryCycle, 100);
            if (data == null || data.prices == null || data.prices.length == 0) {
                Logger.warning(this, "FGS", "无法获取市场数据 chain=" + chain);
                return 0;
            }

            // Agent 分析（AI 可自主调用工具，包括自动交易）
            TradingSignal signal;
            try {
                AgentRuntime.AgentResult agentResult =
                    AIAnalyzer.analyzeWithTools(this, data, chain, safetyGate);
                signal = AIAnalyzer.parseAgentResult(agentResult);
                if (agentResult != null && !agentResult.toolCallHistory.isEmpty()) {
                    Logger.info(this, "FGS", "本轮工具调用 " + agentResult.toolCallHistory.size() + " 次 chain=" + chain);
                }
            } catch (Exception agentErr) {
                Logger.warning(this, "FGS", "Agent 模式失败，降级单轮 LLM 分析 chain=" + chain + ": " + agentErr.getMessage());
                try {
                    AIAnalyzer fallbackAnalyzer = new AIAnalyzer();
                    signal = fallbackAnalyzer.analyze(this, data, chain);
                } catch (Exception llmErr) {
                    Logger.warning(this, "FGS", "LLM 不可用（网络不通/未配 Key），降级本地策略引擎");
                    signal = new StrategyEngine().analyze(data);
                }
            }
            if (signal == null) {
                signal = new StrategyEngine().analyze(data);
                if (signal == null) {
                    signal = new TradingSignal(TradingSignal.SignalType.HOLD, "所有分析路径均失败", 0, 0);
                }
            }

            handleSignal(signal, data, chain);
            String resultText = "链: " + chain + "，信号: " + signal.getDisplayText();
            AIOperationLogManager.logAnalysis(this, chain, "AI 定时分析完成", resultText, "success");
            return 1;
        } catch (Exception e) {
            Logger.error(this, "FGS", "分析链失败 chain=" + chain + ": " + e.getMessage(), e);
            String errText = "链: " + chain + "，错误: " + e.getMessage();
            AIOperationLogManager.logAnalysis(this, chain, "AI 定时分析失败", errText, "failed");
            return 0;
        }
    }

    /**
     * 处理分析结果：
     *  - STRONG_BUY/STRONG_SELL 写入聊天记录 + 推送系统通知
     *  - 定时（按 newsReportIntervalHours）搜索新闻并 LLM 总结后推送
     */
    private void handleSignal(TradingSignal signal, MarketData data, String chain) {
        if (signal == null) return;
        String signalType = signal.getDisplayText();
        boolean isMajor = signal.type == TradingSignal.SignalType.STRONG_BUY
            || signal.type == TradingSignal.SignalType.STRONG_SELL;

        // 后台分析报告：不再推送到聊天框，整体存入"后台分析报告"下拉列表中
        StringBuilder msg = new StringBuilder();
        msg.append("信号: ").append(signalType).append("\n");
        if (data != null) {
            msg.append("当前价格: $").append(String.format("%.4f", data.currentPrice)).append("\n");
            if (data.change24h != 0) {
                msg.append(String.format("24h涨跌: %+.2f%%\n", data.change24h));
            }
        }
        msg.append("分析依据: ").append(signal.reason).append("\n");

        if ("强烈买入".equals(signalType)) {
            msg.append("\n检测到强烈买入信号。如果你已启用自动交易，AI 已尝试自动执行买入。");
        } else if ("强烈卖出".equals(signalType)) {
            msg.append("\n检测到强烈卖出信号。建议关注持仓。");
        } else {
            msg.append("\n当前无强烈买卖信号，维持现有策略，持续监控。");
        }

        appendBackgroundAnalysisReport(chain, msg.toString());
        // 通知瘦身：后台分析报告不再推送系统通知，仅保留在顶部下拉列表

        // 定时新闻推送
        boolean shouldPushNews = false;
        try {
            long lastNews = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong("last_news_push_ts", 0);
            int newsIntervalHours = agentMemory != null ? agentMemory.getNewsReportIntervalHours() : 24;
            if (newsIntervalHours > 0 && System.currentTimeMillis() - lastNews > newsIntervalHours * 3600L * 1000L) {
                shouldPushNews = true;
            }
        } catch (Exception ignored) {}

        if (shouldPushNews && AIAgentSettings.isProactiveTradingEnabled(this)) {
            try {
                String query = chain + " market news";
                JSONObject newsArgs = new JSONObject();
                newsArgs.put("query", query);
                newsArgs.put("limit", 5);
                AgentToolRegistry.ToolResult newsResult = AgentToolRegistry.execute(
                    this, AgentToolRegistry.TOOL_SEARCH_NEWS, newsArgs, chain, safetyGate);
                if (newsResult.success) {
                    String newsSummary = callLLMForNewsSummary(newsResult.output, query);
                    if (newsSummary != null && !newsSummary.isEmpty()) {
                        appendBackgroundAnalysisReport(chain, "【市场动态】\n" + newsSummary);
                    }
                }
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putLong("last_news_push_ts", System.currentTimeMillis())
                    .apply();
            } catch (Exception e) {
                Logger.error(this, "FGS", "新闻推送失败: " + e.getMessage(), e);
            }
        }
    }

    /** 将消息追加到聊天记录（持久化到 SharedPreferences，Activity 重开时会加载） */
    private void appendToChatHistory(String role, String content) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String json = prefs.getString("chat_history", "[]");
            JSONArray arr = new JSONArray(json);
            JSONObject msg = new JSONObject();
            msg.put("role", role);
            msg.put("content", content);
            arr.put(msg);
            // 最多保留 200 条
            while (arr.length() > 200) {
                JSONArray newArr = new JSONArray();
                for (int i = 1; i < arr.length(); i++) newArr.put(arr.get(i));
                arr = newArr;
            }
            prefs.edit().putString("chat_history", arr.toString()).apply();
        } catch (Exception e) {
            Logger.error(this, "FGS", "写入聊天记录失败: " + e.getMessage(), e);
        }
    }

    /** 将后台分析报告追加到独立存储（供聊天框左上角下拉列表展示），不进入聊天框 */
    private void appendBackgroundAnalysisReport(String chain, String content) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String json = prefs.getString(KEY_BACKGROUND_REPORTS, "[]");
            JSONArray arr = new JSONArray(json);
            JSONObject report = new JSONObject();
            report.put("ts", System.currentTimeMillis());
            report.put("chain", chain);
            report.put("content", content);
            arr.put(report);
            // 最多保留最近 MAX_BACKGROUND_REPORTS 条
            while (arr.length() > MAX_BACKGROUND_REPORTS) {
                JSONArray newArr = new JSONArray();
                for (int i = 1; i < arr.length(); i++) newArr.put(arr.get(i));
                arr = newArr;
            }
            prefs.edit().putString(KEY_BACKGROUND_REPORTS, arr.toString()).apply();
            Logger.info(this, "FGS", "已保存后台分析报告 chain=" + chain);
        } catch (Exception e) {
            Logger.error(this, "FGS", "写入后台分析报告失败: " + e.getMessage(), e);
        }
    }

    /** 调用 LLM 总结新闻（不走 AgentRuntime，避免触发工具循环） */
    private String callLLMForNewsSummary(String newsJson, String query) {
        try {
            String apiKey = AIAnalyzer.getApiKeyStatic(this);
            String model = AIAnalyzer.getModelStatic(this);
            String apiUrl = AIAnalyzer.getApiUrlStatic(this);
            if (apiKey == null || apiKey.isEmpty() || model == null || model.isEmpty() || apiUrl == null || apiUrl.isEmpty()) {
                return null;
            }

            String systemPrompt = "你是一个加密货币新闻编辑。请把给定的新闻数据整理成简洁的中文摘要，" +
                "突出重点事件、市场影响和风险提示。控制在 300 字以内，使用要点格式。";
            String userPrompt = "搜索关键词: " + query + "\n\n新闻数据:\n" + newsJson +
                "\n\n请整理成中文摘要，突出重点和市场影响。";

            JSONArray messages = new JSONArray();
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messages.put(sysMsg);
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);
            messages.put(userMsg);

            String chatUrl = apiUrl;
            if (!chatUrl.endsWith("/chat/completions")) {
                chatUrl = chatUrl.endsWith("/") ? chatUrl + "chat/completions" : chatUrl + "/chat/completions";
            }
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", messages);
            body.put("max_tokens", 800);
            body.put("temperature", 0.3);

            Request req = new Request.Builder()
                .url(chatUrl)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

            try (Response resp = httpClient.newCall(req).execute()) {
                String respStr = resp.body() != null ? resp.body().string() : "";
                JSONObject respJson = new JSONObject(respStr);
                JSONArray choices = respJson.optJSONArray("choices");
                if (choices != null && choices.length() > 0) {
                    return choices.getJSONObject(0).getJSONObject("message").getString("content");
                }
            }
        } catch (Exception e) {
            Logger.error(this, "FGS", "LLM 总结新闻失败: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * 后台实时资产监测：轻量对比当前资产快照与上次快照，发现变动即推送通知。
     * 点击通知直达该笔交易详情，返回键退回资产列表。
     */
    private void checkAssetChanges() {
        try {
            String chain = WalletManager.getChain(this);
            String address = WalletManager.getWalletAddress(this);
            if (chain == null || chain.isEmpty() || address == null || address.isEmpty()) return;

            // AIAgent 前台时由 Activity 自行检测，避免重复通知；HomeActivity 刷新与后台监测可能并存，靠快照去重
            if (activityInForeground) return;

            double nativeBalance;
            try {
                nativeBalance = ChainAPI.getNativeBalance(this, chain, address);
            } catch (Exception e) {
                return;
            }

            java.util.List<String[]> tokens;
            try {
                tokens = ChainAPI.getAllTokenBalances(this, chain, address, false);
            } catch (Exception e) {
                tokens = new java.util.ArrayList<>();
            }

            // 资产变动检测：与 HomeActivity 共用同一套"去重去抖"（恒定余额不重复报、
            // 已通知代币不重复报、FGS 与前台刷新不叠加通知）
            DataCache dataCache = new DataCache(this);
            String nativeName = ChainAPI.getChainName(chain);
            java.util.List<String[]> allTokens = new java.util.ArrayList<>();
            allTokens.add(new String[]{chain, nativeName,
                ChainAPI.formatAmount(nativeBalance), "0", "", "true"});
            allTokens.addAll(tokens);

            DataCache.AssetChangeResult chg = dataCache.detectAssetChange(address, allTokens, nativeBalance);
            if (chg.shouldNotify && AIAgentSettings.isProactiveTradingEnabled(this)) {
                Logger.success(this, "FGS", "后台检测到资产变动");
                String txHash = getLatestIncomingTxHash(chain, address);
                String title = getString(R.string.title_asset_change_reminder);
                String content = !chg.newTokens.isEmpty()
                    ? getString(R.string.msg_asset_changed, chg.newTokens)
                    : getString(R.string.msg_asset_changed_bg);
                AINotificationHelper.notifyAssetChange(this, title, content, txHash, chain);
            }
        } catch (Exception e) {
            Logger.warning(this, "FGS", "资产监测失败: " + e.getMessage());
        }
    }

    /** 主动闲聊：按频率档位与当日限额决定是否主动发一条闲聊消息 */
    private void maybeSendProactiveChat() {
        try {
            if (!AIAgentSettings.isProactiveChatEnabled(this)) return;

            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

            // 当日限额（按自然日重置）
            int dailyLimit = AIAgentSettings.getDailyChatLimit(this);
            String dayKey = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(new java.util.Date());
            int sentToday = prefs.getInt("chat_sent_date_" + dayKey, 0);
            if (dailyLimit > 0 && sentToday >= dailyLimit) return;

            // 最小间隔（按频率档位）
            long minGap = chatMinGapMillis(AIAgentSettings.getChatFrequency(this));
            long lastTs = prefs.getLong("last_proactive_chat_ts", 0);
            if (System.currentTimeMillis() - lastTs < minGap) return;

            String topic = pickChatTopic();
            String content = buildProactiveChatMessage(topic);
            if (content == null || content.isEmpty()) return;

            appendToChatHistory("assistant", content);
            if (AIAgentSettings.isProactiveChatEnabled(this)) {
                AINotificationHelper.notifyScheduledTask(this,
                    getString(R.string.str_proactive_chat_title), content);
            }
            prefs.edit()
                .putLong("last_proactive_chat_ts", System.currentTimeMillis())
                .putInt("chat_sent_date_" + dayKey, sentToday + 1)
                .apply();
            Logger.info(this, "FGS", "已发送一条主动闲聊，今日第 " + (sentToday + 1) + " 条");
            // 通知已打开的聊天页实时刷新（若在聊天页，立即显示这条主动闲聊）
            Runnable l = onProactiveChatListener;
            if (l != null) {
                mainHandler.post(l);
            }
        } catch (Exception e) {
            Logger.warning(this, "FGS", "主动闲聊失败: " + e.getMessage());
        }
    }

    /** 按频率档位返回两条主动闲聊之间的最小间隔毫秒 */
    private long chatMinGapMillis(int freq) {
        switch (freq) {
            case AIAgentSettings.FREQ_OCCASIONAL: return 6L * 3600L * 1000L; // 偶尔：每 6 小时
            case AIAgentSettings.FREQ_TALKY:      return 3600L * 1000L;      // 话痨：每 1 小时
            case AIAgentSettings.FREQ_UNLIMITED:  return 30L * 60L * 1000L;  // 不限：每 30 分钟
            case AIAgentSettings.FREQ_NORMAL:
            default:                              return 4L * 3600L * 1000L; // 正常：每 4 小时
        }
    }

    /** 随机挑选一个闲聊话题（世界观/人生/金融/数字货币/科技） */
    private static final String[] CHAT_TOPICS = {
        "对世界观的看法与思考",
        "人生的意义与选择",
        "关于金融投资本质的思考",
        "数字货币与去中心化的未来",
        "科技趋势与人类未来的关系",
        "如何面对市场波动保持心态平和",
        "长期主义与短期主义的取舍"
    };
    private String pickChatTopic() {
        return CHAT_TOPICS[(int) (System.currentTimeMillis() % CHAT_TOPICS.length)];
    }

    /**
     * 本地生成主动闲聊消息（不调用大模型，零 token 消耗）。
     * 用户没回复时，AI 主动找用户聊天只应随手打个招呼、抛个话茬，
     * 用预设话术随机组合，最大限度节省 token，同时保留人情味与打招呼。
     */
    private String buildProactiveChatMessage(String topic) {
        try {
            String owner = (agentMemory != null && agentMemory.getOwnerName() != null
                    && !agentMemory.getOwnerName().isEmpty())
                ? agentMemory.getOwnerName() : "主人";

            // 打招呼开场白池（随机挑一个，避免一成不变）
            String[] openers = {
                "嘿" + owner + "，刚忙完歇了口气，",
                "嗨" + owner + "，看到手机正好想起你，",
                owner + "，这会儿闲着也是闲着，",
                "嗯，" + owner + "，我正琢磨点事儿，",
                "喂，" + owner + "，要不要听我念叨两句，"
            };
            // 话题衔接/反问池
            String[] bridges = {
                "突然想问问你：" + topic + "，你心里是咋想的？",
                "我一直在想" + topic + "这档子事，挺想听你的看法，你怎么看？",
                "你说" + topic + "，换你会上心吗？跟我唠唠呗。",
                "我也说不准，但" + topic + "这问题挺有意思的，你觉得呢？"
            };

            long seed = System.currentTimeMillis();
            String opener = openers[(int) (seed % openers.length)];
            String bridge = bridges[(int) ((seed / 7) % bridges.length)];

            // 金融相关话题自动附带免责声明
            boolean fin = topic.contains("金融") || topic.contains("投资")
                || topic.contains("市场") || topic.contains("波动");
            String disclaimer = fin ? "\n\n（纯闲聊哈，非投资建议，自担风险）" : "";

            return opener + "\n" + bridge + disclaimer;
        } catch (Exception e) {
            Logger.warning(this, "FGS", "主动闲聊本地生成失败: " + e.getMessage());
        }
        return null;
    }

    /** 获取该钱包最新的一笔收款交易哈希（尽力而为），用于通知点击直达交易详情 */
    private String getLatestIncomingTxHash(String chain, String address) {
        try {
            java.util.List<String[]> txs = ChainAPI.getTransactionHistory(this, chain, address, "", 1);
            if (txs == null || txs.isEmpty()) return null;
            for (String[] tx : txs) {
                if (tx.length > 2 && tx[2] != null
                    && tx[2].equalsIgnoreCase(address) && tx[0] != null && !tx[0].isEmpty()) {
                    return tx[0];
                }
            }
            String first = txs.get(0)[0];
            return first != null && !first.isEmpty() ? first : null;
        } catch (Exception e) {
            Logger.warning(this, "FGS", "获取最新交易哈希失败: " + e.getMessage());
            return null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (scheduler != null) {
                scheduler.shutdown();
                scheduler = null;
            }
            if (assetScheduler != null) {
                assetScheduler.shutdown();
                assetScheduler = null;
            }
            if (chatScheduler != null) {
                chatScheduler.shutdown();
                chatScheduler = null;
            }
            Logger.info(this, "FGS", "AI 前台服务已停止");
        } catch (Exception e) {
            Logger.error(this, "FGS", "服务停止失败: " + e.getMessage(), e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
