package com.aicryptowallet.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;

/**
 * 云端 AI 分析器 - 调用用户配置的 API
 * 系统不自带分析逻辑，完全依赖用户配置的 AI 模型
 */
public class AIAnalyzer {
    private static final MediaType JSON_TYPE = MediaType.parse("application/json");
    private static final String CLAUDE_URL = "https://api.anthropic.com/v1/messages";
    
    private final OkHttpClient client;

    public AIAnalyzer() {
        // 缩短超时时间，避免长时间卡死
        this.client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build();
    }

    /**
     * 多周期分析市场数据并返回交易信号
     * 中长线改造：拉取多周期 K 线，要求共振才出强信号
     *
     * @param ctx Context
     * @param primaryData 主周期数据（用户配置的周期，由 AIAgentActivity 拉取）
     * @param chain 链标识
     * @return 综合交易信号
     */
    public TradingSignal analyze(Context ctx, MarketData primaryData, String chain) throws Exception {
        String apiKey = getApiKey(ctx);
        String model = getModel(ctx);
        String apiUrl = getApiUrl(ctx);

        if (apiKey == null || apiKey.isEmpty()) {
            return new TradingSignal(TradingSignal.SignalType.HOLD, ctx.getString(R.string.label_ai_api_key_not_configured_short), 0, 0);
        }
        if (model == null || model.isEmpty()) {
            return new TradingSignal(TradingSignal.SignalType.HOLD, ctx.getString(R.string.label_ai_model_not_configured_short), 0, 0);
        }
        if (apiUrl == null || apiUrl.isEmpty()) {
            return new TradingSignal(TradingSignal.SignalType.HOLD, ctx.getString(R.string.label_api_not_configured), 0, 0);
        }

        // 拉取多周期数据
        String primaryCycle = TradingCycleConfig.getPrimaryCycle(ctx);
        String secondaryCycle = TradingCycleConfig.getSecondaryCycle(ctx);
        String tertiaryCycle = TradingCycleConfig.getTertiaryCycle(ctx);

        // 拉取次周期 K 线（如果与主周期不同）
        MarketData secondaryData = null;
        if (!secondaryCycle.equals(primaryCycle)) {
            try {
                secondaryData = MultiChainMarketData.getKlines(chain, secondaryCycle, 100);
            } catch (Exception e) {
                Logger.warning(ctx, "AI 分析", "拉取次周期 " + secondaryCycle + " 失败: " + e.getMessage());
            }
        }

        MarketData tertiaryData = null;
        if (tertiaryCycle != null && !tertiaryCycle.isEmpty() && !tertiaryCycle.equals(primaryCycle)) {
            try {
                tertiaryData = MultiChainMarketData.getKlines(chain, tertiaryCycle, 100);
            } catch (Exception e) {
                Logger.warning(ctx, "AI 分析", "拉取第三周期 " + tertiaryCycle + " 失败: " + e.getMessage());
            }
        }

        // 构建多周期提示词（含持仓上下文）
        String prompt = buildMultiCyclePrompt(ctx, primaryData, secondaryData, tertiaryData,
            chain, primaryCycle, secondaryCycle, tertiaryCycle);

        // 调用 AI
        String response;
        if (model.startsWith("claude")) {
            response = callClaude(apiKey, model, prompt);
        } else {
            response = callOpenAICompatible(apiKey, apiUrl, model, prompt);
        }

        TradingSignal signal = parseResponse(response);

        // 多周期共振检查：如果要求共振但次周期不支持，降低信号强度
        if (TradingCycleConfig.isRequireResonance(ctx) && secondaryData != null) {
            signal = applyResonanceFilter(signal, primaryData, secondaryData);
        }

        return signal;
    }

    /**
     * 多周期共振过滤：主次周期信号不一致时降级
     */
    private TradingSignal applyResonanceFilter(TradingSignal signal, MarketData primary, MarketData secondary) {
        if (signal == null || signal.type == TradingSignal.SignalType.HOLD) return signal;

        // 简单共振判断：次周期的短期均线方向
        if (secondary == null || secondary.prices == null || secondary.prices.length < 20) return signal;

        double[] secondaryPrices = secondary.prices;
        double sma5 = 0, sma20 = 0;
        int n5 = Math.min(5, secondaryPrices.length);
        int n20 = Math.min(20, secondaryPrices.length);
        for (int i = secondaryPrices.length - n5; i < secondaryPrices.length; i++) sma5 += secondaryPrices[i];
        for (int i = secondaryPrices.length - n20; i < secondaryPrices.length; i++) sma20 += secondaryPrices[i];
        sma5 /= n5; sma20 /= n20;

        boolean secondaryBullish = sma5 > sma20;
        boolean primaryBullish = signal.isBuySignal();

        if (primaryBullish != secondaryBullish) {
            // 主次周期不一致，降级信号
            TradingSignal.SignalType downgraded;
            if (signal.type == TradingSignal.SignalType.STRONG_BUY) downgraded = TradingSignal.SignalType.BUY;
            else if (signal.type == TradingSignal.SignalType.STRONG_SELL) downgraded = TradingSignal.SignalType.SELL;
            else downgraded = TradingSignal.SignalType.HOLD; // 已是 BUY/SELL 且不共振，降为 HOLD

            return new TradingSignal(downgraded,
                signal.reason + " [次周期不共振，信号降级]",
                signal.buyRatio * 0.6, signal.sellRatio * 0.6);
        }
        return signal;
    }

    /**
     * 构建多周期提示词（含持仓上下文）
     */
    private String buildMultiCyclePrompt(Context ctx, MarketData primary, MarketData secondary,
                                           MarketData tertiary, String chain,
                                           String primaryCycle, String secondaryCycle, String tertiaryCycle) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 AI 中长线炒币助手，专注于中长线交易决策。请基于多周期技术分析给出交易建议。\n\n");

        sb.append("=== 钱包状态 ===\n");
        sb.append("链：").append(chain).append("\n");
        try {
            sb.append(PositionManager.getPositionSummary(ctx)).append("\n");
        } catch (Exception e) {
            sb.append("持仓信息获取失败\n");
        }
        sb.append(TradingCycleConfig.getConfigSummary(ctx)).append("\n\n");

        sb.append("=== 主周期 (").append(primaryCycle).append(") ===\n");
        appendCycleData(ctx, sb, primary);

        if (secondary != null) {
            sb.append("\n=== 次周期 (").append(secondaryCycle).append(") ===\n");
            appendCycleData(ctx, sb, secondary);
        }

        if (tertiary != null) {
            sb.append("\n=== 长周期 (").append(tertiaryCycle).append(") ===\n");
            appendCycleData(ctx, sb, tertiary);
        }

        sb.append("\n=== 分析要求 ===\n");
        sb.append("1. 中长线视角：关注趋势而非短期波动\n");
        sb.append("2. 多周期共振：多个周期信号一致时信号更强\n");
        sb.append("3. 风险优先：不确定时返回 HOLD\n");
        sb.append("4. 考虑当前持仓：避免过度建仓或重复操作\n\n");

        sb.append("请严格按照以下 JSON 格式返回（不要包含其他文字）：\n");
        sb.append("{\n");
        sb.append("  \"signal\": \"STRONG_BUY\" 或 \"BUY\" 或 \"HOLD\" 或 \"SELL\" 或 \"STRONG_SELL\",\n");
        sb.append("  \"confidence\": 0-100 的整数，\n");
        sb.append("  \"reason\": \"简要说明理由（50 字以内，含多周期判断）\"\n");
        sb.append("}\n");

        return sb.toString();
    }

    private void appendCycleData(Context ctx, StringBuilder sb, MarketData data) {
        if (data == null || data.prices == null || data.prices.length == 0) {
            sb.append("数据不可用\n");
            return;
        }
        sb.append("当前价格：").append(CurrencyManager.formatFiat(ctx, data.currentPrice)).append("\n");

        TechnicalIndicators.IndicatorValues indicators =
            TechnicalIndicators.getLatest(data.prices, data.volumes);

        sb.append("- RSI(14): ").append(String.format("%.2f", indicators.rsi)).append("\n");
        sb.append("- MACD: ").append(String.format("%.4f", indicators.macd))
          .append(" (Signal: ").append(String.format("%.4f", indicators.macdSignal)).append(")\n");
        sb.append("- MA(20): ").append(CurrencyManager.formatFiat(ctx, indicators.sma20))
          .append("  MA(50): ").append(CurrencyManager.formatFiat(ctx, indicators.sma50)).append("\n");
        sb.append("- 布林带: ").append(CurrencyManager.formatFiat(ctx, indicators.bbLower))
          .append(" ~ ").append(CurrencyManager.formatFiat(ctx, indicators.bbUpper)).append("\n");

        // 最近 5 根 K 线（中长线无需 10 根）
        sb.append("- 近 5 根收盘: ");
        int start = Math.max(0, data.prices.length - 5);
        for (int i = start; i < data.prices.length; i++) {
            sb.append(CurrencyManager.formatFiat(ctx, data.prices[i]));
            if (i < data.prices.length - 1) sb.append(" → ");
        }
        sb.append("\n");
    }

    private String callOpenAICompatible(String apiKey, String apiUrl, String model, String prompt) throws Exception {
        Exception lastException = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return callOpenAICompatibleOnce(apiKey, apiUrl, model, prompt);
            } catch (Exception e) {
                lastException = e;
                if (attempt == 1 && isRetryableError(e)) {
                    Logger.warning(null, "AIAnalyzer", "OpenAI 调用失败，1.5s 后重试: " + e.getMessage());
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    continue;
                }
                throw e;
            }
        }
        throw lastException != null ? lastException : new Exception("OpenAI 调用失败");
    }

    private String callOpenAICompatibleOnce(String apiKey, String apiUrl, String model, String prompt) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", 500);

        JSONArray messages = new JSONArray();
        JSONObject msg = new JSONObject();
        msg.put("role", "user");
        msg.put("content", prompt);
        messages.put(msg);
        body.put("messages", messages);

        Request request = new Request.Builder()
            .url(apiUrl)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(body.toString(), JSON_TYPE))
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new Exception("API错误 (" + response.code() + "): " + resp);
            }
            JSONObject json = new JSONObject(resp);
            return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");
        }
    }

    private String callClaude(String apiKey, String model, String prompt) throws Exception {
        Exception lastException = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return callClaudeOnce(apiKey, model, prompt);
            } catch (Exception e) {
                lastException = e;
                if (attempt == 1 && isRetryableError(e)) {
                    Logger.warning(null, "AIAnalyzer", "Claude 调用失败，1.5s 后重试: " + e.getMessage());
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    continue;
                }
                throw e;
            }
        }
        throw lastException != null ? lastException : new Exception("Claude 调用失败");
    }

    private String callClaudeOnce(String apiKey, String model, String prompt) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", 500);

        JSONArray messages = new JSONArray();
        JSONObject msg = new JSONObject();
        msg.put("role", "user");
        msg.put("content", prompt);
        messages.put(msg);
        body.put("messages", messages);

        Request request = new Request.Builder()
            .url(CLAUDE_URL)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(RequestBody.create(body.toString(), JSON_TYPE))
            .build();

        try (Response response = client.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new Exception("API错误 (" + response.code() + "): " + resp);
            }
            JSONObject json = new JSONObject(resp);
            return json.getJSONArray("content")
                .getJSONObject(0)
                .getString("text");
        }
    }

    /**
     * 判断异常是否属于可重试的临时错误。
     */
    private boolean isRetryableError(Exception e) {
        if (e instanceof SocketTimeoutException) return true;
        if (e instanceof ConnectException) return true;
        if (e instanceof UnknownHostException) return true;
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("timeout") || msg.contains("connect") || msg.contains("socket")) return true;
        if (msg.contains("ssl") || msg.contains("handshake")) return true;
        int code = extractHttpCode(msg);
        if (code == 401 || code == 403) return false;
        if (code >= 500 || code == 429) return true;
        if (msg.contains("api key") || msg.contains("apikey") || msg.contains("authentication") || msg.contains("unauthorized")) {
            return false;
        }
        return false;
    }

    private int extractHttpCode(String msg) {
        try {
            int start = msg.indexOf('(');
            int end = msg.indexOf(')', start);
            if (start >= 0 && end > start) {
                return Integer.parseInt(msg.substring(start + 1, end).trim());
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private TradingSignal parseResponse(String response) {
        try {
            String jsonStr = response;
            int start = response.indexOf("{");
            int end = response.lastIndexOf("}");
            if (start >= 0 && end > start) {
                jsonStr = response.substring(start, end + 1);
            }

            JSONObject json = new JSONObject(jsonStr);
            String signal = json.optString("signal", "HOLD");
            int confidence = json.optInt("confidence", 50);
            String reason = json.optString("reason", "AI 分析完成");

            TradingSignal.SignalType type;
            switch (signal) {
                case "STRONG_BUY": type = TradingSignal.SignalType.STRONG_BUY; break;
                case "BUY": type = TradingSignal.SignalType.BUY; break;
                case "SELL": type = TradingSignal.SignalType.SELL; break;
                case "STRONG_SELL": type = TradingSignal.SignalType.STRONG_SELL; break;
                default: type = TradingSignal.SignalType.HOLD;
            }

            return new TradingSignal(type, reason, confidence / 100.0, 0);
        } catch (Exception e) {
            return new TradingSignal(TradingSignal.SignalType.HOLD, "AI 响应解析失败", 0, 0);
        }
    }

    private String getApiKey(Context ctx) {
        return getApiKeyStatic(ctx);
    }

    private String getModel(Context ctx) {
        return getModelStatic(ctx);
    }

    private String getApiUrl(Context ctx) {
        return getApiUrlStatic(ctx);
    }

    /**
     * 静态访问 AI 配置，供 AgentRuntime 复用
     * 优先从 ai_agent_prefs 读取，回退到 WalletManager 全局配置
     * 这样保证 AIAgentActivity 和 HomeActivity 对话使用相同配置
     */
    public static String getApiKeyStatic(Context ctx) {
        // 优先使用用户配置的模型供应商 API Key
        String activeKey = ModelProviderManager.getActiveApiKey(ctx);
        if (activeKey != null && !activeKey.isEmpty()) {
            return activeKey;
        }
        SharedPreferences prefs = ctx.getSharedPreferences("ai_agent_prefs", Context.MODE_PRIVATE);
        String key = prefs.getString("api_key", "");
        if (key == null || key.isEmpty()) {
            key = WalletManager.getAPIKey(ctx);
        }
        return key;
    }

    public static String getModelStatic(Context ctx) {
        // 优先使用用户配置的模型供应商模型
        String activeModel = ModelProviderManager.getActiveModel(ctx);
        if (activeModel != null && !activeModel.isEmpty()) {
            return activeModel;
        }
        SharedPreferences prefs = ctx.getSharedPreferences("ai_agent_prefs", Context.MODE_PRIVATE);
        String model = prefs.getString("model", "");
        if (model == null || model.isEmpty()) {
            model = WalletManager.getAIModel(ctx);
        }
        return model;
    }

    public static String getApiUrlStatic(Context ctx) {
        // 优先使用用户配置的模型供应商 API URL
        String activeUrl = ModelProviderManager.getActiveApiUrl(ctx);
        if (activeUrl != null && !activeUrl.isEmpty()) {
            return activeUrl;
        }
        SharedPreferences prefs = ctx.getSharedPreferences("ai_agent_prefs", Context.MODE_PRIVATE);
        String url = prefs.getString("api_url", "");
        if (url == null || url.isEmpty()) {
            url = WalletManager.getAPIUrl(ctx);
        }
        return url;
    }

    // ============================================================
    // Agent 模式（Tool Use）- 中长线炒币助手核心
    // ============================================================

    /**
     * 使用 Agent 模式分析市场 - AI 智能体自主调用工具完成分析
     *
     * 与旧 analyze() 的区别：
     * - 旧：单轮 LLM 调用，AI 只返回 JSON 信号
     * - 新：多轮工具调用，AI 可自主拉数据、查询持仓、检查安全状态，最后返回决策
     *
     * 这是"AI 能自己调用合约访问 DApp"的产品定位实现
     *
     * @param ctx            Context
     * @param primaryData    主周期数据（AIAgentActivity 预拉取）
     * @param chain          链标识
     * @param safetyGate     安全网关（用于写入工具校验）
     * @return AgentResult 包含最终决策和工具调用历史
     */
    public static AgentRuntime.AgentResult analyzeWithTools(Context ctx, MarketData primaryData,
                                                              String chain, SafetyGate safetyGate) throws Exception {
        AgentRuntime runtime = new AgentRuntime(ctx, chain, safetyGate);

        String systemPrompt = buildAgentSystemPrompt(ctx, chain);
        String userPrompt = buildAgentUserPrompt(ctx, primaryData, chain);

        return runtime.run(userPrompt, systemPrompt, 6); // 分析任务用 6 轮足够
    }

    /**
     * 构建 Agent 系统提示词 - 设定角色、安全规则、可用工具说明
     */
    private static String buildAgentSystemPrompt(Context ctx, String chain) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个专业的 AI 中长线炒币助手智能体，运行在用户的加密钱包 APP 中。\n\n");

        sb.append("=== 你的身份 ===\n");
        sb.append("- 你能通过工具调用直接访问区块链、查询余额、调用合约、执行交易\n");
        sb.append("- 你的所有写入操作都经过安全网关 SafetyGate 校验（限额/熔断/审计）\n");
        sb.append("- 你必须对用户资金负责，安全第一，不确定时返回 HOLD\n\n");

        sb.append("=== 中长线交易原则 ===\n");
        sb.append("1. 关注 1h/4h/1d 多周期趋势，忽略 5m/15m 短期噪音\n");
        sb.append("2. 多周期共振时信号更强，不一致时降级或持有\n");
        sb.append("3. 单次建仓不超过总仓位的 30%，分批建仓优先\n");
        sb.append("4. 必须设置止损（默认 -8%）和止盈（默认 +20%）\n");
        sb.append("5. 连续亏损 3 次自动停止，等待用户介入\n\n");

        sb.append("=== 安全规则（必须遵守）===\n");
        sb.append("1. 执行任何写入操作前，先调用 get_safety_status 确认未熔断\n");
        sb.append("2. 操作描述 operation_desc 必须清晰说明意图，例如：中长线建仓 ETH $200\n");
        sb.append("3. 不允许调用 send_native 转账到陌生地址（除非用户明确指示）\n");
        sb.append("4. 不允许授权给未知合约（白名单外的 spender）\n");
        sb.append("5. 单笔交易金额不超过用户配置的限额\n");
        sb.append("6. 出现异常立即停止，返回 HOLD 并说明原因\n\n");

        sb.append("=== 何时问用户 vs 自动执行 ===\n");
        sb.append("【需要调用 ask_user 工具询问用户的场景】\n");
        sb.append("- 信号为 STRONG_BUY / STRONG_SELL：先调用 ask_user 让用户确认再执行交易\n");
        sb.append("- 单笔交易金额超过用户单日限额 50%\n");
        sb.append("- 涉及非主流代币（非 ETH/BNB/SOL/TRX/MATIC/AVAX）的建仓\n");
        sb.append("- 涉及调用未知 DeFi 合约（call_contract_write）\n");
        sb.append("- 任何不确定的重大决策\n");
        sb.append("ask_user 工具会阻塞等待用户回复。用户回复「同意」或类似肯定词后再执行；");
        sb.append("回复「不同意」或 120 秒未回复则放弃本次操作，返回 HOLD。\n\n");
        sb.append("【可以直接自动执行的场景】\n");
        sb.append("- 信号为 BUY / SELL（非 STRONG）且金额在限额内\n");
        sb.append("- 已有持仓的止盈/止损平仓（保护性操作优先）\n");
        sb.append("- 必要的 approve 操作（如需先授权再 swap）\n");
        sb.append("- 用户在聊天中明确指示「买入 X」「卖出 Y」「立即执行」时\n\n");

        sb.append("=== 何时搜索新闻 ===\n");
        sb.append("- 用户在聊天中询问市场动态、新闻、热点时，调用 search_news\n");
        sb.append("- 出现剧烈波动（4h 涨跌幅 > 5%）时，可调用 search_news 了解原因\n");
        sb.append("- 不要每轮都搜新闻，按 newsReportIntervalHours 间隔由系统自动触发\n\n");

        sb.append("=== DApp 浏览器操作能力 ===\n");
        sb.append("你还可通过 App 内置 DApp 浏览器操作网页：\n");
        sb.append("- browser_open_url：打开指定 URL 到内置浏览器\n");
        sb.append("- browser_get_state：获取当前页面 URL、标题、可点击元素、输入框、文本摘要\n");
        sb.append("- browser_click：用 CSS 选择器点击页面元素（如 #swap-button）\n");
        sb.append("- browser_input：用 CSS 选择器在输入框填入文本\n");
        sb.append("- browser_evaluate：执行任意 JS 并返回结果\n");
        sb.append("- browser_close：关闭当前打开的 DApp 浏览器页面（页面打不开、无法读取、或用户要求关闭时调用，不受白名单限制）\n");
        sb.append("使用流程：先 browser_open_url 打开页面，等待 2-3 秒后 browser_get_state 查看页面，再决定点击或输入。页面打不开或用户要求关闭时调用 browser_close 关闭。涉及资金操作前必须调用 ask_user 让用户确认。\n\n");

        sb.append("=== 决策流程 ===\n");
        sb.append("1. 调用 get_position 了解当前持仓\n");
        sb.append("2. 调用 get_market_data 拉取主周期（1h）和次周期（4h）K线\n");
        sb.append("3. 调用 get_safety_status 检查安全状态\n");
        sb.append("4. 综合分析后给出决策：STRONG_BUY/BUY/HOLD/SELL/STRONG_SELL\n");
        sb.append("5. 若决策为 STRONG_BUY/STRONG_SELL：调用 ask_user 询问用户是否执行\n");
        sb.append("6. 若决策为 BUY/SELL 且安全状态允许：直接调用 swap_tokens 执行，并附 operation_desc\n");
        sb.append("7. 若决策买入的目标资产在另一条链，可调用 cross_chain_swap 打开 Transit Finance (https://swap.transit.finance) 内置 DApp 浏览器页面，由用户手动完成兑换；如用户已授权 swap.transit.finance 白名单，AI 可继续用 browser_click / browser_input / browser_evaluate 尝试自动操作页面。如果目标是非 EVM 链原生币（如 TRX/SOL/BTC/ADA），先确认 Transit 是否支持；若不支持，应询问用户是否创建该链钱包后买入原生币，或在当前链购买包装版本。如果用户没有目标链钱包，先调用 open_create_wallet 创建钱包。Transit 页面发起的交易仍受 SafetyGate 额度限制，超出额度需用户手动确认。首次使用会强制显示系统级风险提示弹窗\n");
        sb.append("8. 返回最终决策理由和执行情况\n\n");

        sb.append("=== 输出格式 ===\n");
        sb.append("最终回复必须包含以下 JSON 块（用 ```json 包裹）：\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"signal\": \"STRONG_BUY|BUY|HOLD|SELL|STRONG_SELL\",\n");
        sb.append("  \"confidence\": 0-100,\n");
        sb.append("  \"reason\": \"决策理由（含多周期分析）\",\n");
        sb.append("  \"action_taken\": \"执行的操作描述，如 '已买入 0.05 ETH ($120)' 或 '无操作'\"\n");
        sb.append("}\n");
        sb.append("```\n");

        return sb.toString();
    }

    /**
     * 构建 Agent 用户提示词
     */
    private static String buildAgentUserPrompt(Context ctx, MarketData primaryData, String chain) {
        StringBuilder sb = new StringBuilder();
        sb.append("请对 ").append(chain).append(" 进行中长线分析并给出决策。\n\n");

        sb.append("=== 当前钱包状态 ===\n");
        try {
            sb.append("地址：").append(WalletManager.getWalletAddress(ctx)).append("\n");
        } catch (Exception e) {
            sb.append("地址获取失败\n");
        }

        sb.append("\n=== 主周期市场数据（已预拉取）===\n");
        if (primaryData != null && primaryData.prices != null && primaryData.prices.length > 0) {
            TechnicalIndicators.IndicatorValues ind =
                TechnicalIndicators.getLatest(primaryData.prices, primaryData.volumes);
            sb.append("当前价格：$").append(String.format("%.4f", primaryData.currentPrice)).append("\n");
            sb.append("RSI(14): ").append(String.format("%.2f", ind.rsi)).append("\n");
            sb.append("MACD: ").append(String.format("%.4f", ind.macd))
              .append(" (Signal: ").append(String.format("%.4f", ind.macdSignal)).append(")\n");
            sb.append("MA20: $").append(String.format("%.4f", ind.sma20))
              .append("  MA50: $").append(String.format("%.4f", ind.sma50)).append("\n");
        } else {
            sb.append("（无预拉取数据，请用 get_market_data 工具获取）\n");
        }

        sb.append("\n=== 周期配置 ===\n");
        sb.append(TradingCycleConfig.getConfigSummary(ctx)).append("\n");

        sb.append("\n=== 本地策略引擎分析（多策略投票：MA+RSI+MACD）===\n");
        try {
            StrategyEngine engine = new StrategyEngine();
            TradingSignal localSignal = engine.analyze(primaryData);
            sb.append("投票结果：").append(localSignal.getDisplayText()).append("\n");
            sb.append("置信度：").append(String.format("%.0f", Math.max(localSignal.buyRatio, localSignal.sellRatio))).append("%\n");
            sb.append("理由：").append(localSignal.reason).append("\n");
            sb.append("（以上为本地策略引擎的预计算，仅供参考。你可基于全貌分析覆盖此信号）\n");
        } catch (Exception e) {
            sb.append("（本地策略引擎计算失败，请忽略）\n");
        }

        sb.append("\n=== 任务 ===\n");
        sb.append("1. 调用 get_position 和 get_safety_status 了解状态\n");
        sb.append("2. 调用 get_market_data 拉取 4h 周期数据（次周期）\n");
        sb.append("3. 综合主次周期分析，给出中长线决策\n");
        sb.append("4. 若决策为 BUY/SELL 且安全状态允许，调用 swap_tokens 执行交易\n");
        sb.append("5. 返回最终 JSON 决策块\n");

        return sb.toString();
    }

    /**
     * 从 Agent 结果解析出 TradingSignal（兼容旧调用方）
     */
    public static TradingSignal parseAgentResult(AgentRuntime.AgentResult result) {
        if (result == null || result.finalReply == null || result.finalReply.isEmpty()) {
            return new TradingSignal(TradingSignal.SignalType.HOLD, "Agent 无响应", 0, 0);
        }

        try {
            String reply = result.finalReply;
            // 提取 JSON 块
            int jsonStart = reply.indexOf("```json");
            int jsonEnd = reply.indexOf("```", jsonStart + 7);
            String jsonStr;
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                jsonStr = reply.substring(jsonStart + 7, jsonEnd).trim();
            } else {
                // 兜底：找第一个 { 到最后一个 }
                int s = reply.indexOf("{");
                int e = reply.lastIndexOf("}");
                if (s < 0 || e <= s) {
                    return new TradingSignal(TradingSignal.SignalType.HOLD, reply.substring(0, Math.min(100, reply.length())), 0.5, 0);
                }
                jsonStr = reply.substring(s, e + 1);
            }

            JSONObject json = new JSONObject(jsonStr);
            String signal = json.optString("signal", "HOLD");
            int confidence = json.optInt("confidence", 50);
            String reason = json.optString("reason", "Agent 决策");
            String action = json.optString("action_taken", "无操作");
            String fullReason = reason + (action.isEmpty() || "无操作".equals(action) ? "" : " | " + action);

            TradingSignal.SignalType type;
            switch (signal) {
                case "STRONG_BUY": type = TradingSignal.SignalType.STRONG_BUY; break;
                case "BUY": type = TradingSignal.SignalType.BUY; break;
                case "SELL": type = TradingSignal.SignalType.SELL; break;
                case "STRONG_SELL": type = TradingSignal.SignalType.STRONG_SELL; break;
                default: type = TradingSignal.SignalType.HOLD;
            }
            return new TradingSignal(type, fullReason, confidence / 100.0, 0);
        } catch (Exception e) {
            return new TradingSignal(TradingSignal.SignalType.HOLD,
                "Agent 响应解析失败: " + e.getMessage(), 0, 0);
        }
    }
}
