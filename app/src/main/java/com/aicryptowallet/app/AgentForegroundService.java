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
    private static final int CHECK_INTERVAL_MINUTES = 5;
    private static final MediaType JSON_TYPE = MediaType.parse("application/json");

    private ScheduledExecutorService scheduler;
    private TradeAuthManager tradeAuthManager;
    private RiskManager riskManager;
    private SafetyGate safetyGate;
    private AgentMemory agentMemory;
    private OkHttpClient httpClient;

    /** 当 AIAgentActivity 在前台时设为 true，本服务跳过分析避免与 Activity 的 scheduler 重复 */
    public static volatile boolean activityInForeground = false;

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
        } catch (Exception e) {
            Logger.error(this, "FGS", "启动定时任务失败: " + e.getMessage(), e);
        }
        return START_STICKY;
    }

    private void runAnalysisCycle() {
        String selectedChain = WalletManager.getChain(this);
        if (selectedChain == null || selectedChain.isEmpty()) {
            selectedChain = "ETH";
        }
        try {
            // 当 Activity 在前台时，让 Activity 的 scheduler 处理（避免重复分析）
            if (activityInForeground) {
                return;
            }

            // 注意：不检查 autoTradeEnabled —— 用户启动了 Agent 就要持续监控分析
            // 是否执行真实交易由 SafetyGate 在工具调用层拦截（autoTradeEnabled=false 时写入工具被拒绝）

            AINotificationHelper.notifyScheduledTask(this, "AI 定时分析开始", "链: " + selectedChain + "，正在执行周期分析...");

            // 拉取主周期 K 线
            String primaryCycle = TradingCycleConfig.getPrimaryCycle(this);
            MarketData data = MultiChainMarketData.getKlines(selectedChain, primaryCycle, 100);
            if (data == null || data.prices == null || data.prices.length == 0) {
                Logger.warning(this, "FGS", "无法获取市场数据");
                return;
            }

            // Agent 分析（AI 可自主调用工具，包括自动交易）
            TradingSignal signal;
            try {
                AgentRuntime.AgentResult agentResult =
                    AIAnalyzer.analyzeWithTools(this, data, selectedChain, safetyGate);
                signal = AIAnalyzer.parseAgentResult(agentResult);
                if (agentResult != null && !agentResult.toolCallHistory.isEmpty()) {
                    Logger.info(this, "FGS", "本轮工具调用 " + agentResult.toolCallHistory.size() + " 次");
                }
            } catch (Exception agentErr) {
                Logger.warning(this, "FGS", "Agent 模式失败，降级单轮 LLM 分析: " + agentErr.getMessage());
                try {
                    AIAnalyzer fallbackAnalyzer = new AIAnalyzer();
                    signal = fallbackAnalyzer.analyze(this, data, selectedChain);
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

            handleSignal(signal, data, selectedChain);
            String resultText = "链: " + selectedChain + "，信号: " + signal.getDisplayText();
            AINotificationHelper.notifyScheduledTask(this, "AI 定时分析完成", resultText);
            AIOperationLogManager.logAnalysis(this, selectedChain, "AI 定时分析完成", resultText, "success");
        } catch (Exception e) {
            Logger.error(this, "FGS", "分析周期失败: " + e.getMessage(), e);
            String errText = "链: " + selectedChain + "，错误: " + e.getMessage();
            AINotificationHelper.notifyScheduledTask(this, "AI 定时分析失败", errText);
            AIOperationLogManager.logAnalysis(this, selectedChain, "AI 定时分析失败", errText, "failed");
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
        boolean isMajor = "STRONG_BUY".equals(signalType) || "STRONG_SELL".equals(signalType);

        if (isMajor) {
            StringBuilder msg = new StringBuilder();
            msg.append("【后台分析报告】\n");
            msg.append("信号: ").append(signalType).append("\n");
            if (data != null) {
                msg.append(String.format("当前价格: $%.4f\n", data.currentPrice));
            }
            msg.append("分析依据: ").append(signal.reason).append("\n");

            if ("STRONG_BUY".equals(signalType)) {
                msg.append("\n检测到强烈买入信号。如果你已启用自动交易，AI 已尝试自动执行买入。");
            } else {
                msg.append("\n检测到强烈卖出信号。建议关注持仓。");
            }

            appendToChatHistory("assistant", msg.toString());
            AINotificationHelper.notifyAlert(this, "【" + chain + "】" + signalType, signal.reason);
        }

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

        if (shouldPushNews) {
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
                        appendToChatHistory("assistant", "【市场动态速递】\n" + newsSummary);
                        AINotificationHelper.notifyScheduledTask(this, "【市场动态】" + chain, newsSummary);
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

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (scheduler != null) {
                scheduler.shutdown();
                scheduler = null;
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
