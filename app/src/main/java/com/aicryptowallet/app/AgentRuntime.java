package com.aicryptowallet.app;

import android.content.Context;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Agent Runtime - 多轮工具调用循环核心
 *
 * 实现 AI 智能体的"思考-行动-观察"循环：
 *   1. AI 接收用户意图 + 上下文
 *   2. AI 决定调用工具（如 get_market_data、swap_tokens）
 *   3. Runtime 执行工具，返回结果给 AI
 *   4. AI 基于结果继续思考，可能再调用工具
 *   5. 循环直到 AI 给出最终回复，或达到轮次上限
 *
 * 同时支持 OpenAI function calling 和 Claude tool use 两种协议。
 *
 * 轮次机制（动态自适应）：
 * - maxRounds 作为"基础轮次"：分析类任务给 6，对话类任务给 12
 * - 基础轮次用完后，只要 AI 持续产出"有进展"的工具调用（无死循环重复），
 *   系统自动扩展轮次，最多到 hardLimit = maxRounds + 30
 * - 死循环检测：若最近的工具调用（工具名+参数）出现重复模式，判定陷入死循环并强制停止
 * - 这样 AI 可以根据任务复杂度（如复杂链游/DApp 操作）自行决定实际使用多少轮，
 *   同时避免无限循环浪费 Token
 *
 * 安全设计：
 * - 硬上限 hardLimit 兜底防止无限循环
 * - 死循环检测提前终止无效重复操作
 * - 每轮工具调用都通过 AgentToolRegistry，自动经 SafetyGate 校验
 * - 完整审计日志，所有工具调用记录可查
 * - 不缓存敏感数据，所有变量在方法结束即释放
 */
public class AgentRuntime {

    private static final MediaType JSON_TYPE = MediaType.parse("application/json");
    private static final String CLAUDE_URL = "https://api.anthropic.com/v1/messages";
    private static final int DEFAULT_MAX_ROUNDS = 12;
    /** 基础轮次之上额外扩展的轮次数（动态自适应） */
    private static final int EXTRA_ROUNDS = 30;

    private final OkHttpClient client;
    private final Context ctx;
    private final String chain;
    private final SafetyGate safetyGate;
    private final List<AgentToolRegistry.ToolCallRecord> callHistory = new ArrayList<>();

    public AgentRuntime(Context ctx, String chain, SafetyGate safetyGate) {
        this.ctx = ctx;
        this.chain = chain;
        this.safetyGate = safetyGate;
        this.client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)   // 单轮 LLM 调用可能较慢
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();
    }

    /**
     * 运行 Agent 循环
     *
     * @param userPrompt     用户意图/分析任务
     * @param systemPrompt   系统提示词（设定角色和安全规则）
     * @param maxRounds      最大循环轮次
     * @return AgentResult 包含最终回复和工具调用历史
     */
    public AgentResult run(String userPrompt, String systemPrompt, int maxRounds) throws Exception {
        String apiKey = AIAnalyzer.getApiKeyStatic(ctx);
        String model = AIAnalyzer.getModelStatic(ctx);
        String apiUrl = AIAnalyzer.getApiUrlStatic(ctx);

        if (apiKey == null || apiKey.isEmpty()) {
            return new AgentResult("未配置 AI API Key，无法启动智能体", callHistory, false);
        }
        if (model == null || model.isEmpty()) {
            return new AgentResult("未配置 AI 模型", callHistory, false);
        }

        boolean isClaude = model.startsWith("claude");
        // 如果用户显式选择了接口格式，以用户选择为准
        String activeProviderId = ModelProviderManager.getActiveProviderId(ctx);
        if (activeProviderId != null && !activeProviderId.isEmpty()) {
            String format = ModelProviderManager.getFormat(ctx, activeProviderId);
            if ("anthropic".equals(format)) {
                isClaude = true;
            } else if ("openai".equals(format)) {
                isClaude = false;
            }
        }
        JSONArray toolsJson = isClaude
            ? AgentToolRegistry.getClaudeToolsJson()
            : AgentToolRegistry.getOpenAIToolsJson();

        // 构建初始 messages
        JSONArray messages = new JSONArray();
        // OpenAI 用 system 字段；Claude 用顶层 system 字段
        // 这里统一在 messages 中按各自协议处理

        // 加入用户提示
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        messages.put(userMsg);

        Logger.info(ctx, "AgentRuntime", "启动 Agent 循环，模型=" + model + " 链=" + chain + " maxRounds=" + maxRounds);

        // 硬上限：基础轮次 + 额外扩展，兜底防止无限循环
        int hardLimit = maxRounds + EXTRA_ROUNDS;

        for (int round = 0; round < hardLimit; round++) {
            // 调用 LLM
            JSONObject llmResponse;
            try {
                llmResponse = callLLM(apiUrl, apiKey, model, systemPrompt, messages, toolsJson, isClaude);
            } catch (Exception e) {
                Logger.error(ctx, "AgentRuntime", "第 " + (round + 1) + " 轮 LLM 调用失败: " + e.getMessage(), e);
                return new AgentResult("LLM 调用失败: " + e.getMessage(), callHistory, false);
            }

            // 解析响应：检查是否有 tool_calls / tool_use
            JSONArray toolCalls = extractToolCalls(llmResponse, isClaude);
            String assistantText = extractAssistantText(llmResponse, isClaude);

            // 把 assistant 消息加入历史
            messages.put(buildAssistantMessage(llmResponse, isClaude));

            if (toolCalls == null || toolCalls.length() == 0) {
                // 没有工具调用，循环结束
                Logger.success(ctx, "AgentRuntime", "Agent 完成，共 " + (round + 1) + " 轮，工具调用 " + callHistory.size() + " 次");
                AINotificationHelper.notifyChatReply(ctx, "AI 助手", assistantText);
                return new AgentResult(assistantText, callHistory, true);
            }

            // 执行所有工具调用
            for (int i = 0; i < toolCalls.length(); i++) {
                JSONObject toolCall = toolCalls.getJSONObject(i);
                String toolName;
                String argsJsonStr;
                String toolCallId;

                if (isClaude) {
                    toolName = toolCall.getString("name");
                    argsJsonStr = toolCall.getJSONObject("input").toString();
                    toolCallId = toolCall.optString("id", "tool_" + round + "_" + i);
                } else {
                    JSONObject fn = toolCall.getJSONObject("function");
                    toolName = fn.getString("name");
                    argsJsonStr = fn.optString("arguments", "{}");
                    toolCallId = toolCall.optString("id", "call_" + round + "_" + i);
                }

                Logger.info(ctx, "AgentRuntime", "调用工具: " + toolName + " 参数=" + argsJsonStr);

                JSONObject args = new JSONObject(argsJsonStr);
                AgentToolRegistry.ToolResult result = AgentToolRegistry.execute(ctx, toolName, args, chain, safetyGate);

                AgentToolRegistry.ToolCallRecord record =
                    new AgentToolRegistry.ToolCallRecord(toolName, argsJsonStr, result);
                callHistory.add(record);

                // 推送 AI 操作通知
                String opTitle = "AI 操作: " + toolName;
                String opContent = result.success
                    ? ("执行成功\n参数: " + argsJsonStr + "\n结果: " + result.output)
                    : ("执行失败\n参数: " + argsJsonStr + "\n错误: " + result.errorMessage);
                if (opContent.length() > 300) opContent = opContent.substring(0, 300) + "...";
                AINotificationHelper.notifyOperation(ctx, opTitle, opContent);

                // 把工具结果加入 messages
                JSONObject toolResultMsg = buildToolResultMessage(toolName, toolCallId, result, isClaude);
                messages.put(toolResultMsg);
            }

            // 已超过基础轮次后，检测是否陷入死循环（重复的无进展工具调用）
            if (round + 1 >= maxRounds && isDeadLoop(callHistory)) {
                Logger.warning(ctx, "AgentRuntime", "检测到死循环（重复工具调用），提前终止，已用 " + (round + 1) + " 轮");
                break;
            }
        }

        // 达到硬上限仍未结束
        Logger.warning(ctx, "AgentRuntime", "Agent 达到硬上限=" + hardLimit + "，强制停止");
        String finalText = "Agent 已达到最大轮次限制 (" + hardLimit + ")，最后回复: " + extractLastAssistantText(messages);
        AINotificationHelper.notifyChatReply(ctx, "AI 助手", finalText);
        return new AgentResult(finalText, callHistory, true);
    }

    /**
     * 死循环检测：检查最近的工具调用是否出现重复模式（工具名+参数完全相同的连续序列）。
     * 若最近 3 次调用与之前间隔 1~3 次的调用完全重复，判定陷入死循环。
     */
    private boolean isDeadLoop(List<AgentToolRegistry.ToolCallRecord> history) {
        int n = history.size();
        if (n < 6) return false;
        for (int gap = 1; gap <= 3; gap++) {
            boolean repeat = true;
            for (int i = 0; i < 3; i++) {
                AgentToolRegistry.ToolCallRecord cur = history.get(n - 1 - i);
                AgentToolRegistry.ToolCallRecord prev = history.get(n - 1 - i - gap);
                if (cur == null || prev == null
                        || !cur.toolName.equals(prev.toolName)
                        || !cur.arguments.equals(prev.arguments)) {
                    repeat = false;
                    break;
                }
            }
            if (repeat) return true;
        }
        return false;
    }

    public AgentResult run(String userPrompt, String systemPrompt) throws Exception {
        return run(userPrompt, systemPrompt, DEFAULT_MAX_ROUNDS);
    }

    /**
     * 调用 LLM API（OpenAI 或 Claude），带自动重试。
     * 临时失败（超时、连接问题、5xx、429）等待 1.5s 后重试一次；
     * 永久错误（401、403、缺 Key）不重试。
     */
    private JSONObject callLLM(String apiUrl, String apiKey, String model,
                                String systemPrompt, JSONArray messages,
                                JSONArray toolsJson, boolean isClaude) throws Exception {
        Exception lastException = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return callLLMOnce(apiUrl, apiKey, model, systemPrompt, messages, toolsJson, isClaude);
            } catch (Exception e) {
                lastException = e;
                if (attempt == 1 && isRetryableError(e)) {
                    Logger.warning(ctx, "AgentRuntime", "LLM 调用失败，1.5s 后重试: " + e.getMessage());
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
        throw lastException != null ? lastException : new Exception("LLM 调用失败");
    }

    /**
     * 单次 LLM API 调用（OpenAI 或 Claude）
     */
    private JSONObject callLLMOnce(String apiUrl, String apiKey, String model,
                                    String systemPrompt, JSONArray messages,
                                    JSONArray toolsJson, boolean isClaude) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", 2000);
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            body.put("system", systemPrompt); // Claude 用顶层 system
        }
        // OpenAI 用 system message（在 messages 数组开头插入）
        JSONArray messagesWithSystem = messages;
        if (!isClaude && systemPrompt != null && !systemPrompt.isEmpty()) {
            messagesWithSystem = new JSONArray();
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messagesWithSystem.put(sysMsg);
            for (int i = 0; i < messages.length(); i++) {
                messagesWithSystem.put(messages.get(i));
            }
        }
        body.put("messages", messagesWithSystem);

        // 加入 tools
        if (toolsJson != null && toolsJson.length() > 0) {
            body.put("tools", toolsJson);
            if (!isClaude) {
                body.put("tool_choice", "auto");
            }
        }

        RequestBody reqBody = RequestBody.create(body.toString(), JSON_TYPE);
        Request.Builder rb = new Request.Builder()
            .post(reqBody)
            .header("Content-Type", "application/json");

        if (isClaude) {
            rb.url(CLAUDE_URL)
              .header("x-api-key", apiKey)
              .header("anthropic-version", "2023-06-01");
        } else {
            String chatUrl = apiUrl;
            if (!chatUrl.endsWith("/chat/completions")) {
                chatUrl = chatUrl.endsWith("/") ? chatUrl + "chat/completions" : chatUrl + "/chat/completions";
            }
            rb.url(chatUrl)
              .header("Authorization", "Bearer " + apiKey);
        }

        Request request = rb.build();
        try (Response response = client.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new Exception("API错误 (" + response.code() + "): " +
                    respBody.substring(0, Math.min(300, respBody.length())));
            }
            return new JSONObject(respBody);
        }
    }

    /**
     * 判断异常是否属于可重试的临时错误。
     * 不重试：401/403/认证错误/API Key 缺失；
     * 重试：超时、连接异常、DNS 失败、5xx、429。
     */
    private boolean isRetryableError(Exception e) {
        if (e instanceof SocketTimeoutException) return true;
        if (e instanceof ConnectException) return true;
        if (e instanceof UnknownHostException) return true;
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("timeout") || msg.contains("connect") || msg.contains("socket")) return true;
        if (msg.contains("ssl") || msg.contains("handshake")) return true;
        if (msg.contains("api错误")) {
            // 从 "API错误 (123): ..." 提取状态码
            int code = extractHttpCode(msg);
            if (code == 401 || code == 403) return false;
            if (code >= 500 || code == 429) return true;
        }
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

    /**
     * 从 LLM 响应中提取 tool_calls（OpenAI 格式或 Claude 格式）
     */
    private JSONArray extractToolCalls(JSONObject llmResponse, boolean isClaude) throws Exception {
        if (isClaude) {
            JSONArray content = llmResponse.optJSONArray("content");
            if (content == null) return null;
            JSONArray toolUses = new JSONArray();
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.getJSONObject(i);
                if ("tool_use".equals(block.optString("type"))) {
                    toolUses.put(block);
                }
            }
            return toolUses.length() > 0 ? toolUses : null;
        } else {
            JSONArray choices = llmResponse.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
            if (msg == null) return null;
            JSONArray toolCalls = msg.optJSONArray("tool_calls");
            return toolCalls;
        }
    }

    /**
     * 提取 LLM 响应中的文本内容
     */
    private String extractAssistantText(JSONObject llmResponse, boolean isClaude) throws Exception {
        if (isClaude) {
            JSONArray content = llmResponse.optJSONArray("content");
            if (content == null) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.getJSONObject(i);
                if ("text".equals(block.optString("type"))) {
                    sb.append(block.optString("text", ""));
                }
            }
            return sb.toString();
        } else {
            JSONArray choices = llmResponse.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return "";
            JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
            if (msg == null) return "";
            return msg.optString("content", "");
        }
    }

    /**
     * 构造 assistant 消息，加入 messages 历史
     */
    private JSONObject buildAssistantMessage(JSONObject llmResponse, boolean isClaude) throws Exception {
        if (isClaude) {
            // Claude: 直接把原始 content 数组作为 message content
            JSONObject msg = new JSONObject();
            msg.put("role", "assistant");
            msg.put("content", llmResponse.optJSONArray("content"));
            return msg;
        } else {
            // OpenAI: 保留 message 对象（包含 tool_calls）
            JSONArray choices = llmResponse.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
                if (msg != null) return msg;
            }
            // 兜底
            JSONObject fallback = new JSONObject();
            fallback.put("role", "assistant");
            fallback.put("content", "");
            return fallback;
        }
    }

    /**
     * 构造工具结果消息
     */
    private JSONObject buildToolResultMessage(String toolName, String toolCallId,
                                                AgentToolRegistry.ToolResult result,
                                                boolean isClaude) throws Exception {
        String resultStr = result.toJsonString();
        if (isClaude) {
            // Claude: role=user, content=[{type: tool_result, tool_use_id, content}]
            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            JSONArray content = new JSONArray();
            JSONObject block = new JSONObject();
            block.put("type", "tool_result");
            block.put("tool_use_id", toolCallId);
            block.put("content", resultStr);
            content.put(block);
            msg.put("content", content);
            return msg;
        } else {
            // OpenAI: role=tool, tool_call_id, content
            JSONObject msg = new JSONObject();
            msg.put("role", "tool");
            msg.put("tool_call_id", toolCallId);
            msg.put("name", toolName);
            msg.put("content", resultStr);
            return msg;
        }
    }

    private String extractLastAssistantText(JSONArray messages) {
        try {
            for (int i = messages.length() - 1; i >= 0; i--) {
                JSONObject msg = messages.getJSONObject(i);
                if ("assistant".equals(msg.optString("role"))) {
                    Object content = msg.opt("content");
                    if (content instanceof String) return (String) content;
                }
            }
        } catch (Exception e) {}
        return "(无最后回复)";
    }

    /**
     * Agent 运行结果
     */
    public static class AgentResult {
        public final String finalReply;
        public final List<AgentToolRegistry.ToolCallRecord> toolCallHistory;
        public final boolean completed;

        public AgentResult(String finalReply, List<AgentToolRegistry.ToolCallRecord> toolCallHistory, boolean completed) {
            this.finalReply = finalReply;
            this.toolCallHistory = toolCallHistory;
            this.completed = completed;
        }

        /**
         * 生成可读的执行摘要（用于审计日志/UI 展示）
         */
        public String getAuditSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("Agent 执行完成，共调用 ").append(toolCallHistory.size()).append(" 次工具：\n");
            for (AgentToolRegistry.ToolCallRecord r : toolCallHistory) {
                sb.append("- ").append(r.toolName)
                  .append(" → ").append(r.result.success ? "成功" : "失败")
                  .append(r.result.success ? "" : "(" + r.result.errorMessage + ")")
                  .append("\n");
            }
            return sb.toString();
        }
    }
}
