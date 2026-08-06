package com.aicryptowallet.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * AI 交易智能体界面 - 多链支持
 */
public class AIAgentActivity extends BaseActivity {

    private static final String PREFS = "ai_agent_prefs";
    private static final String KEY_CHAT_HISTORY = "chat_history";
    private static final String KEY_ARCHIVED_SESSIONS = "archived_sessions";
    private static final String KEY_ACTIVE_SESSION_ID = "active_session_id";
    private static final int CHECK_INTERVAL_MINUTES = 5;
    private static final int MAX_ARCHIVED_SESSIONS = 50;

    /** Activity 是否在前台，供通知管理器判断是否需要弹聊天通知 */
    public static volatile boolean isForeground = false;

    private TextView tvStatus, tvSignal, tvSignalReason, tvNextCheck;
    private TextView tvRSI, tvMACD, tvMA20, tvMA50;
    private TextView tvDailyPnL, tvTradeCount, tvWinRate;
    private TextView tvCurrentPrice, tvChainPrice;
    private TextView btnStartAgent;
    private TextView tvCurrentChain;
    private View statusDot;
    private View indicatorsHeader, indicatorsBody;
    private TextView tvIndicatorsToggle;
    private boolean indicatorsExpanded = false;

    // 聊天面板相关
    private View detailsPanel;          // 详情面板（原 ScrollView）
    private View chatPanel;             // 聊天面板
    private TextView btnToggleChat;     // 顶部切换按钮
    private TextView btnChatMenu;       // 右上角下拉菜单按钮（包含新会话、会话列表、导出、清空）
    private LinearLayout chatList;      // 消息列表容器
    private ScrollView chatScroll;      // 消息滚动视图
    private EditText etChatInput;       // 输入框
    private View btnChatSend;           // 发送按钮
    private boolean chatMode = true;    // 默认进入聊天模式（第一幕是对话框）
    private final List<String[]> chatHistory = new ArrayList<>(); // [role, content]
    private OkHttpClient chatHttpClient;
    private AgentMemory agentMemory;    // 智能体记忆（自述文件）

    private AIAnalyzer aiAnalyzer;
    private RiskManager riskManager;
    private TradeAuthManager tradeAuthManager;
    private SafetyGate safetyGate; // Agent 模式安全网关
    private ScheduledExecutorService scheduler;
    // 注意：executor / chatExecutor 故意不用 final，因为 onDestroy 中会 shutdown，
    // 重新进入 Activity 时需要在 onCreate 中重新创建，否则 submit 会抛 RejectedExecutionException
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    // 聊天专用线程池：AgentRuntime 可能触发 SafetyGate 弹窗同步阻塞 60s，
    // 用独立线程池避免卡死主 executor（影响定时分析等任务）
    private ExecutorService chatExecutor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile boolean isRunning = false;
    private volatile boolean autoTradeEnabled = false;
    private volatile String selectedChain = "ETH";
    private final java.util.concurrent.atomic.AtomicReference<String> pendingCreateWalletChain =
        new java.util.concurrent.atomic.AtomicReference<>();
    // 线程安全统计字段：scheduler 线程、executor 交易线程、UI 线程三方共享
    private final AtomicInteger tradeCount = new AtomicInteger(0);
    private final AtomicInteger winCount = new AtomicInteger(0);
    private final AtomicInteger closedTradeCount = new AtomicInteger(0); // 已平仓的卖出笔数，作为胜率分母
    private final DoubleAdder dailyPnL = new DoubleAdder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Logger.action(this, "AI Agent", "打开 AI 交易智能体页面", null);
            
            setContentView(R.layout.activity_ai_agent);
            Logger.info(this, "初始化", "设置布局完成");

            aiAnalyzer = new AIAnalyzer();
            Logger.info(this, "初始化", "AI 分析器创建完成");
            
            riskManager = new RiskManager(this);
            Logger.info(this, "初始化", "风控管理器创建完成");
            
            tradeAuthManager = new TradeAuthManager(this);
            Logger.info(this, "初始化", "交易授权管理器创建完成");

            safetyGate = new SafetyGate(this, tradeAuthManager, riskManager);
            // 绑定 Activity 引用，用于 AI 买入非白名单代币时的确认弹窗
            safetyGate.attachActivity(this);
            Logger.info(this, "初始化", "安全网关创建完成（已绑定 Activity，启用白名单确认）");

            // 重新进入页面时，executor / chatExecutor 可能在上次 onDestroy 已被 shutdown，
            // 这里确保它们是可用状态（Executors.newSingleThreadExecutor 已在字段初始化，
            // 但若上次退出时 shutdown 过，需要重建）
            if (executor == null || executor.isShutdown()) {
                executor = Executors.newSingleThreadExecutor();
            }
            if (chatExecutor == null || chatExecutor.isShutdown()) {
                chatExecutor = Executors.newSingleThreadExecutor();
            }

            agentMemory = new AgentMemory(this);
            Logger.info(this, "初始化", "智能体记忆加载完成: " + agentMemory.getAiName());

            initViews();
            Logger.info(this, "初始化", "视图初始化完成");
            
            loadState();
            Logger.info(this, "初始化", "状态加载完成");

            setupChainSpinner();
            Logger.info(this, "初始化", "链选择器设置完成");

            updateUI();
            Logger.success(this, "初始化", "AI Agent 初始化成功");

            // 加载历史聊天记录到内存
            loadChatHistory();
            Logger.info(this, "初始化", "聊天记录加载完成: " + chatHistory.size() + " 条");

            // 兼容旧数据：如果当前有聊天记录但没有活跃会话 ID，生成一个
            SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String activeId = prefs.getString(KEY_ACTIVE_SESSION_ID, "");
            if ((activeId == null || activeId.isEmpty()) && chatHistory != null && !chatHistory.isEmpty()) {
                activeId = String.valueOf(System.currentTimeMillis());
                prefs.edit().putString(KEY_ACTIVE_SESSION_ID, activeId).apply();
            }

            // 默认进入聊天模式（第一幕是对话框），渲染历史记录或欢迎语
            String presetMessage = getIntent().getStringExtra("preset_message");
            if (chatList != null) {
                if (!TextUtils.isEmpty(presetMessage)) {
                    // 从行情页等外部跳转过来，自动发送预设消息，无需用户手动粘贴
                    renderChatHistory();
                    scrollChatToBottom();
                    sendChatMessage(presetMessage);
                } else if (chatHistory.isEmpty()) {
                    String welcome = agentMemory != null ? agentMemory.getWelcomeMessage() :
                        "你好！我是 AI 助手，可以回答关于加密货币的问题。";
                    appendChatMessage("assistant", welcome);
                } else {
                    renderChatHistory();
                }
                scrollChatToBottom();
            }
        } catch (Exception e) {
            String errorMsg = "初始化失败: " + e.getMessage();
            Logger.error(this, "初始化", errorMsg, e);
            Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void initViews() {
        try {
            findViewById(R.id.btnBack).setOnClickListener(v -> finish());
            findViewById(R.id.btnSettings).setOnClickListener(v -> showSettings());
            View btnRecords = findViewById(R.id.btnRecords);
            if (btnRecords != null) {
                btnRecords.setOnClickListener(v -> {
                    Logger.action(AIAgentActivity.this, "UI操作", "AI-操作记录", null);
                    Intent intent = new Intent(AIAgentActivity.this, HomeActivity.class);
                    intent.putExtra(HomeActivity.EXTRA_SHOW_AI_RECORDS, true);
                    intent.putExtra(HomeActivity.EXTRA_AI_ONLY_TRADES, false);
                    startActivity(intent);
                });
            }

            tvStatus = findViewById(R.id.tvStatus);
            tvSignal = findViewById(R.id.tvSignal);
            tvSignalReason = findViewById(R.id.tvSignalReason);
            tvNextCheck = findViewById(R.id.tvNextCheck);
            tvRSI = findViewById(R.id.tvRSI);
            tvMACD = findViewById(R.id.tvMACD);
            tvMA20 = findViewById(R.id.tvMA20);
            tvMA50 = findViewById(R.id.tvMA50);
            tvDailyPnL = findViewById(R.id.tvDailyPnL);
            tvTradeCount = findViewById(R.id.tvTradeCount);
            tvWinRate = findViewById(R.id.tvWinRate);
            tvCurrentPrice = findViewById(R.id.tvCurrentPrice);
            tvChainPrice = findViewById(R.id.tvChainPrice);
            btnStartAgent = findViewById(R.id.btnStartAgent);
            tvCurrentChain = findViewById(R.id.tvCurrentChain);
            statusDot = findViewById(R.id.statusDot);
            indicatorsHeader = findViewById(R.id.indicatorsHeader);
            indicatorsBody = findViewById(R.id.indicatorsBody);
            tvIndicatorsToggle = findViewById(R.id.tvIndicatorsToggle);

            // 聊天面板相关视图
            detailsPanel = findViewById(R.id.detailsPanel);
            chatPanel = findViewById(R.id.chatPanel);
            btnToggleChat = findViewById(R.id.btnToggleChat);
            btnChatMenu = findViewById(R.id.btnChatMenu);
            chatList = findViewById(R.id.chatList);
            chatScroll = findViewById(R.id.chatScroll);
            etChatInput = findViewById(R.id.etChatInput);
            btnChatSend = findViewById(R.id.btnChatSend);

            // 初始化聊天用的 HttpClient（超时放宽到 60s 以适配大模型）
            chatHttpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();

            // 顶部切换按钮
            if (btnToggleChat != null) {
                btnToggleChat.setOnClickListener(v -> toggleChatMode());
            }
            // 右上角下拉菜单（新会话 / 会话列表 / 导出 / 清空）
            if (btnChatMenu != null) {
                btnChatMenu.setOnClickListener(v -> showChatMenu(v));
            }
            // 发送按钮
            if (btnChatSend != null) {
                btnChatSend.setOnClickListener(v -> sendChatMessage());
            }
            // 输入框回车发送
            if (etChatInput != null) {
                etChatInput.setOnEditorActionListener((v, actionId, event) -> {
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                        sendChatMessage();
                        return true;
                    }
                    return false;
                });
            }

            // 显式设置默认显示聊天面板（第一幕是对话框），避免布局默认值不生效
            chatMode = true;
            if (chatPanel != null) chatPanel.setVisibility(View.VISIBLE);
            if (detailsPanel != null) detailsPanel.setVisibility(View.GONE);
            if (btnToggleChat != null) btnToggleChat.setText(getString(R.string.text_details_2));

            // Collapsible indicators toggle
            if (indicatorsHeader != null) {
                indicatorsHeader.setOnClickListener(v -> {
                    indicatorsExpanded = !indicatorsExpanded;
                    if (indicatorsBody != null) {
                        indicatorsBody.setVisibility(indicatorsExpanded ? View.VISIBLE : View.GONE);
                    }
                    if (tvIndicatorsToggle != null) {
                        tvIndicatorsToggle.setText(indicatorsExpanded ? getString(R.string.btn_collapse) : getString(R.string.str_expand));
                    }
                });
            }

            if (btnStartAgent != null) {
                btnStartAgent.setOnClickListener(v -> {
                    if (isRunning) {
                        stopAgent();
                    } else {
                        startAgent();
                    }
                });
            }
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.toast_view_initialization_error, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isForeground = true;
        // 重新绑定 SafetyGate 的 Activity 引用（防止被回收后引用失效）
        if (safetyGate != null) {
            safetyGate.attachActivity(this);
        }
        // 注册 ask_user 回调：让 AI 能在重大决策时向用户提问
        AgentToolRegistry.setAskUserCallback(askUserCallback);
        // 标记 Activity 在前台：让前台服务跳过分析，避免与 Activity 的 scheduler 重复
        AgentForegroundService.activityInForeground = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        isForeground = false;
        // 注销 ask_user 回调，避免 Activity 被销毁后仍被调用
        AgentToolRegistry.clearAskUserCallback();
        // 标记 Activity 不在前台：让前台服务接管分析周期
        AgentForegroundService.activityInForeground = false;
    }

    /**
     * ask_user 工具的回调实现。
     * AI 在重大决策时调用此回调向用户提问，阻塞等待用户回复。
     * 实现方式：在聊天界面显示问题，通过 CountDownLatch 等待用户下一条消息作为回复。
     */
    private final AgentToolRegistry.AskUserCallback askUserCallback = (question, contextDesc, urgency) -> {
        final String[] userReply = {null};
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        // 在主线程将问题显示到聊天界面，并注册一次性回复监听器
        handler.post(() -> {
            try {
                StringBuilder display = new StringBuilder();
                String urgencyLabel = "high".equals(urgency) ? "【紧急】" : "medium".equals(urgency) ? "【重要】" : "【参考】";
                display.append(urgencyLabel).append("AI 需要你的确认\n\n");
                display.append(question).append("\n");
                if (contextDesc != null && !contextDesc.isEmpty()) {
                    display.append("\n上下文: ").append(contextDesc).append("\n");
                }
                display.append("\n请回复「同意」「不同意」或输入你的意见。");

                appendChatMessage("assistant", display.toString());
                saveChatHistory();

                // 注册一次性回复监听器：下一条用户消息作为回复
                setPendingAskUserReply(reply -> {
                    userReply[0] = reply;
                    latch.countDown();
                });
            } catch (Exception e) {
                Logger.error(this, "ask_user", "显示问题失败: " + e.getMessage(), e);
                latch.countDown();
            }
        });

        // 阻塞等待用户回复，最多等 120 秒（比 SafetyGate 的 60s 长，给用户更多思考时间）
        try {
            if (!latch.await(120, java.util.concurrent.TimeUnit.SECONDS)) {
                Logger.warning(this, "ask_user", "用户 120 秒内未回复，视为不同意");
                // 清除监听器
                setPendingAskUserReply(null);
                return ""; // 空字符串表示超时
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "";
        }
        return userReply[0] != null ? userReply[0] : "";
    };

    /** pending ask_user 回复监听器（非 null 时，下一条用户消息会回调此监听器而不是走正常聊天流程） */
    private PendingAskUserReply pendingAskUserReply;

    /** 回复监听器接口 */
    private interface PendingAskUserReply {
        void onReply(String reply);
    }

    /** 设置 pending ask_user 回复监听器（传 null 清除） */
    private void setPendingAskUserReply(PendingAskUserReply listener) {
        this.pendingAskUserReply = listener;
    }

    private void setupChainSpinner() {
        try {
            // 直接使用当前钱包主链，不再让用户选择
            String walletChain = WalletManager.getChain(this);
            if (walletChain != null && !walletChain.isEmpty()) {
                selectedChain = walletChain;
            }
            Logger.info(this, "链设置", "使用当前钱包主链: " + selectedChain);

            // 更新 UI 显示
            if (tvCurrentChain != null) {
                String displayName = selectedChain;
                // 显示更友好的名称
                if ("BNB".equals(selectedChain)) displayName = "BNB Chain";
                else if ("ETH".equals(selectedChain)) displayName = "Ethereum";
                else if ("MATIC".equals(selectedChain)) displayName = "Polygon";
                else if ("AVAX".equals(selectedChain)) displayName = "Avalanche";
                else if ("FTM".equals(selectedChain)) displayName = "Fantom";
                else if ("ARB".equals(selectedChain)) displayName = "Arbitrum";
                else if ("OP".equals(selectedChain)) displayName = "Optimism";
                else if ("BASE".equals(selectedChain)) displayName = "Base";
                tvCurrentChain.setText(displayName);
            }
        } catch (Exception e) {
            Logger.error(this, "链设置", "初始化失败", e);
        }
    }

    private void startAgent() {
        try {
            // 后台线程检查资产条件（避免主线程网络请求失败）
            if (btnStartAgent != null) btnStartAgent.setEnabled(false);
            Toast.makeText(this, getString(R.string.toast_checking_assets), Toast.LENGTH_SHORT).show();
            tradeAuthManager.checkInBackground(this, result -> {
                if (btnStartAgent != null) btnStartAgent.setEnabled(true);
                if (!result.allowed) {
                    Toast.makeText(AIAgentActivity.this, result.reason, Toast.LENGTH_LONG).show();
                    Logger.warning(this, "AI 启动", "不满足开启条件: " + result.reason);
                    return;
                }
                try {
                    isRunning = true;
                    if (btnStartAgent != null) {
                        btnStartAgent.setText(getString(R.string.text_stop_agent));
                    }
                    saveState();

                    scheduler = Executors.newSingleThreadScheduledExecutor();
                    scheduler.scheduleAtFixedRate(() -> {
                        runAnalysisCycle();
                    }, 0, CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES);

                    // 同时启动前台服务，保证 App 关闭后 AI 仍可运行和推送
                    try {
                        android.content.Intent svcIntent = new android.content.Intent(this, AgentForegroundService.class);
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            startForegroundService(svcIntent);
                        } else {
                            startService(svcIntent);
                        }
                        Logger.info(this, "AI 启动", "前台服务已启动");
                    } catch (Exception svcErr) {
                        Logger.warning(this, "AI 启动", "前台服务启动失败（不影响 Activity 内分析）: " + svcErr.getMessage());
                    }

                    Toast.makeText(AIAgentActivity.this, getString(R.string.toast_ai_agent_started, selectedChain), Toast.LENGTH_SHORT).show();
                    Logger.info(this, "AI 启动", "用户开启自动交易，当前链: " + selectedChain);
                } catch (Exception e) {
                    Toast.makeText(AIAgentActivity.this, getString(R.string.toast_failed_to_start_ai, e.getMessage()), Toast.LENGTH_LONG).show();
                    isRunning = false;
                }
            });
        } catch (Exception e) {
            if (btnStartAgent != null) btnStartAgent.setEnabled(true);
            Toast.makeText(this, getString(R.string.toast_failed_to_start_ai, e.getMessage()), Toast.LENGTH_LONG).show();
            isRunning = false;
        }
    }

    private void stopAgent() {
        isRunning = false;
        if (btnStartAgent != null) {
            btnStartAgent.setText(getString(R.string.text_launch_ai_agent));
        }
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
        // 同时停止前台服务，释放后台运行
        try {
            android.content.Intent svcIntent = new android.content.Intent(this, AgentForegroundService.class);
            stopService(svcIntent);
            Logger.info(this, "AI 停止", "前台服务已停止");
        } catch (Exception svcErr) {
            Logger.warning(this, "AI 停止", "停止前台服务失败: " + svcErr.getMessage());
        }
        saveState();
        Toast.makeText(this, getString(R.string.toast_ai_agent_has_stopped), Toast.LENGTH_SHORT).show();
    }

    // ============================================================
    // 聊天模式：详情/聊天 切换 + 自然语言对话
    // ============================================================

    /** 切换详情面板和聊天面板 */
    private void toggleChatMode() {
        chatMode = !chatMode;
        if (chatMode) {
            if (detailsPanel != null) detailsPanel.setVisibility(View.GONE);
            if (chatPanel != null) chatPanel.setVisibility(View.VISIBLE);
            if (btnToggleChat != null) btnToggleChat.setText(getString(R.string.text_details_2));
            // 切换到聊天面板时：如果有历史记录则渲染历史，否则显示欢迎语
            if (chatList != null && chatList.getChildCount() == 0) {
                if (chatHistory.isEmpty()) {
                    // 首次使用，显示欢迎语
                    String welcome = agentMemory != null ? agentMemory.getWelcomeMessage() :
                        "你好！我是 AI 助手，可以回答关于加密货币的问题。";
                    appendChatMessage("assistant", welcome);
                } else {
                    // 渲染历史聊天记录
                    renderChatHistory();
                }
            }
            scrollChatToBottom();
        } else {
            if (detailsPanel != null) detailsPanel.setVisibility(View.VISIBLE);
            if (chatPanel != null) chatPanel.setVisibility(View.GONE);
            if (btnToggleChat != null) btnToggleChat.setText(getString(R.string.text_chat));
        }
    }

    /** 将内存中的 chatHistory 渲染到聊天列表 */
    private void renderChatHistory() {
        if (chatList == null) return;
        chatList.removeAllViews();
        for (String[] turn : chatHistory) {
            if (turn.length < 2) continue;
            // 跳过思考中占位消息（不应被保存，但防御性处理）
            if ("assistant_thinking".equals(turn[0])) continue;
            long ts = turn.length >= 3 ? parseLongSafe(turn[2]) : 0;
            appendChatMessage(turn[0], turn[1], ts, false);
        }
    }

    /** 发送输入框里的消息 */
    private void sendChatMessage() {
        if (etChatInput == null) return;
        String text = etChatInput.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;
        etChatInput.setText("");
        sendChatMessage(text);
    }

    /** 发送指定文本消息（支持外部跳转自动触发） */
    private void sendChatMessage(String text) {
        if (chatList == null || TextUtils.isEmpty(text)) return;

        // 如果有 pending ask_user 回复监听器，用户消息作为回复而不是新聊天
        // AI 之前通过 ask_user 工具向用户提问并在阻塞等待，这里把回复回调给它
        if (pendingAskUserReply != null) {
            PendingAskUserReply listener = pendingAskUserReply;
            pendingAskUserReply = null;
            long now = System.currentTimeMillis();
            appendChatMessage("user", text, now);
            chatHistory.add(new String[]{"user", text, String.valueOf(now)});
            Logger.info(this, "AI 对话", "用户(回复ask): " + text);
            saveChatHistory();
            listener.onReply(text);
            return;
        }

        long now = System.currentTimeMillis();
        appendChatMessage("user", text, now);
        chatHistory.add(new String[]{"user", text, String.valueOf(now)});
        // 写入日志：用户消息
        Logger.info(this, "AI 对话", "用户: " + text);

        // 占位的"思考中"气泡
        appendChatMessage("assistant_thinking", "思考中...");

        // 后台调用 LLM（用 chatExecutor 避免卡死主 executor）
        chatExecutor.execute(() -> {
            String reply;
            try {
                reply = callChatLLMWithRetry(text, 2);
            } catch (Exception e) {
                reply = "调用 AI 失败（已重试）: " + e.getMessage();
                Logger.error(this, "AI 聊天", "LLM 调用失败（已重试）", e);
            }
            final String finalReply = reply;
            handler.post(() -> {
                // 移除"思考中"占位
                removeLastThinkingMessage();
                // 解析 AI 回复中的 @SET 指令（AI 修改自身记忆）
                if (agentMemory != null && agentMemory.applySetCommand(finalReply)) {
                    Logger.info(this, "AI 记忆", "AI 通过 @SET 修改了自身配置");
                }
                // 显示给用户的回复（去掉 @SET 指令部分，保持界面干净）
                String displayReply = finalReply;
                if (displayReply.contains("@SET")) {
                    // 保留 @SET 之前的自然语言回复，去掉指令部分
                    int setIdx = displayReply.indexOf("@SET");
                    String before = displayReply.substring(0, setIdx).trim();
                    displayReply = before.isEmpty() ? "已按你的要求修改。" : before;
                }
                long aiNow = System.currentTimeMillis();
                appendChatMessage("assistant", displayReply, aiNow);
                chatHistory.add(new String[]{"assistant", finalReply, String.valueOf(aiNow)});
                // 写入日志：AI 回复
                Logger.info(AIAgentActivity.this, "AI 对话", "AI: " + finalReply);
                saveChatHistory();

                // 如果本轮工具要求打开创建钱包页，在聊天界面显示快捷按钮
                String createChain = pendingCreateWalletChain.getAndSet(null);
                if (createChain != null && !createChain.isEmpty()) {
                    appendCreateWalletButton(createChain);
                }
            });
        });
    }

    /** 在聊天列表追加一条消息，role: user / assistant / assistant_thinking */
    private void appendChatMessage(String role, String content) {
        // 默认使用当前时间作为时间轴，新消息需要推送系统通知
        appendChatMessage(role, content, System.currentTimeMillis(), true);
    }

    /** 带时间戳的 appendChatMessage：在每条消息上方显示时间轴 */
    private void appendChatMessage(String role, String content, long timestamp) {
        appendChatMessage(role, content, timestamp, true);
    }

    /**
     * 带时间戳的 appendChatMessage：
     * @param timestamp 消息时间轴时间戳
     * @param notify 是否同步推送系统通知。加载历史记录/切换会话重放时应传 false，
     *               避免每次打开 AI 页面都重新推送一遍旧对话内容。
     */
    private void appendChatMessage(String role, String content, long timestamp, boolean notify) {
        if (chatList == null) return;

        boolean isUser = "user".equals(role);
        boolean isThinking = "assistant_thinking".equals(role);

        // 时间轴标签（独立一行，显示日期+时间）
        String timeLabel = formatChatTimestamp(timestamp);
        TextView tvTime = new TextView(this);
        tvTime.setText(timeLabel);
        tvTime.setTextColor(0xFF8E8E9A);
        tvTime.setTextSize(11);
        tvTime.setAlpha(0.85f);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        timeLp.gravity = Gravity.CENTER_HORIZONTAL;
        timeLp.setMargins(0, 14, 0, 4);
        tvTime.setLayoutParams(timeLp);
        chatList.addView(tvTime);

        // 消息气泡
        TextView bubble = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, 0, 0, 4);

        if (isUser) {
            lp.gravity = Gravity.END;
            bubble.setBackgroundResource(R.drawable.balance_card_background);
            bubble.setTextColor(0xFFFFFFFF);
            bubble.setPadding(28, 16, 28, 16);
        } else {
            lp.gravity = Gravity.START;
            bubble.setBackgroundResource(R.drawable.card_background);
            bubble.setTextColor(isThinking ? 0xFF6E6E7A : 0xFFFFFFFF);
            bubble.setPadding(28, 16, 28, 16);
        }
        bubble.setLayoutParams(lp);
        bubble.setTextSize(14);
        bubble.setMaxWidth(getResources().getDisplayMetrics().widthPixels * 3 / 4);
        bubble.setText(content);
        bubble.setTextIsSelectable(true);
        bubble.setOnLongClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                android.content.ClipData clip = android.content.ClipData.newPlainText("AI对话", content);
                cm.setPrimaryClip(clip);
                Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        chatList.addView(bubble);
        scrollChatToBottom();

        // AI 助手消息同步推送系统通知（仅新消息，Activity 不在前台时）
        if (!isUser && !isThinking && notify) {
            AINotificationHelper.notifyChatReply(this, "AI 助手", content);
        }
    }

    /** 格式化聊天时间轴显示：始终显示 MM-dd HH:mm，今天标"今天" */
    private String formatChatTimestamp(long millis) {
        if (millis <= 0) return "历史";
        try {
            java.util.Calendar now = java.util.Calendar.getInstance();
            java.util.Calendar msg = java.util.Calendar.getInstance();
            msg.setTimeInMillis(millis);
            java.text.SimpleDateFormat hmSdf = new java.text.SimpleDateFormat("HH:mm",
                java.util.Locale.getDefault());
            String hm = hmSdf.format(msg.getTime());

            // 同一天
            if (now.get(java.util.Calendar.YEAR) == msg.get(java.util.Calendar.YEAR)
                && now.get(java.util.Calendar.DAY_OF_YEAR) == msg.get(java.util.Calendar.DAY_OF_YEAR)) {
                return "今天 " + hm;
            }
            // 昨天
            now.add(java.util.Calendar.DAY_OF_YEAR, -1);
            if (now.get(java.util.Calendar.YEAR) == msg.get(java.util.Calendar.YEAR)
                && now.get(java.util.Calendar.DAY_OF_YEAR) == msg.get(java.util.Calendar.DAY_OF_YEAR)) {
                return "昨天 " + hm;
            }
            // 更早
            java.text.SimpleDateFormat mdhmSdf = new java.text.SimpleDateFormat("MM-dd HH:mm",
                java.util.Locale.getDefault());
            return mdhmSdf.format(msg.getTime());
        } catch (Exception e) {
            return "";
        }
    }

    /** 移除最后一条"思考中"占位消息（含其时间轴标签） */
    private void removeLastThinkingMessage() {
        if (chatList == null || chatList.getChildCount() == 0) return;
        // 当前结构：每条消息 = 时间轴 TextView + 气泡 TextView（共 2 个 view）
        // 思考中占位也是 2 个 view，需要都移除
        int last = chatList.getChildCount() - 1;
        chatList.removeViewAt(last); // 气泡
        if (chatList.getChildCount() > 0) {
            // 检查倒数第二个是否是时间轴（TextView 且文字是时间格式或"历史"）
            // 简单处理：思考中占位的时间轴是刚加进去的，直接移除
            // 但要避免误删上一条真实消息的时间轴，所以检查上一个 view 是否就是时间轴
            // 实际上 appendChatMessage 总是成对添加，所以倒数第二个一定是该消息的时间轴
            View maybeTime = chatList.getChildAt(chatList.getChildCount() - 1);
            if (maybeTime instanceof TextView) {
                TextView tv = (TextView) maybeTime;
                // 思考中占位的时间轴是当前时间，简单判断：文字长度短且不是上一条 AI 回复的内容
                // 更安全的做法：检查文字是否符合时间格式 HH:mm 或 MM-dd HH:mm 或 "昨天 HH:mm"
                String text = tv.getText().toString();
                if (text.matches("^\\d{1,2}:\\d{2}$")
                    || text.matches("^\\d{1,2}-\\d{1,2} \\d{1,2}:\\d{2}$")
                    || text.startsWith("昨天 ")
                    || "历史".equals(text)) {
                    chatList.removeViewAt(chatList.getChildCount() - 1);
                }
            }
        }
    }

    /** 在聊天界面追加一个进入指定链钱包创建页的按钮 */
    private void appendCreateWalletButton(String chain) {
        if (chatList == null) return;
        String chainName = ChainAPI.getChainName(this, chain);
        int chainColor = Color.parseColor(ChainAPI.getChainColor(chain));

        TextView btn = new TextView(this);
        btn.setText(getString(R.string.text_create_wallet, chainName));
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(14);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(36, 18, 36, 18);
        btn.setClickable(true);
        btn.setFocusable(true);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(24);
        bg.setColor(chainColor);
        btn.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.gravity = Gravity.START;
        lp.setMargins(0, 8, 0, 18);
        btn.setLayoutParams(lp);

        btn.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("force_create", true);
            intent.putExtra("target_chain", chain);
            startActivity(intent);
            Logger.action(this, "AI 对话", "点击创建钱包按钮", "chain=" + chain);
        });

        chatList.addView(btn);
        scrollChatToBottom();
    }

    private void scrollChatToBottom() {
        chatScroll.post(() -> chatScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    // ============================================================
    // 聊天记录持久化（保存到 SharedPreferences，下次打开自动加载）
    // ============================================================

    /** 保存聊天记录到本地存储（最多保留最近 200 条，避免存储无限增长） */
    private void saveChatHistory() {
        try {
            JSONArray arr = new JSONArray();
            int start = Math.max(0, chatHistory.size() - 200);
            for (int i = start; i < chatHistory.size(); i++) {
                String[] turn = chatHistory.get(i);
                if (turn.length < 2) continue;
                // 不保存"思考中"占位消息
                if ("assistant_thinking".equals(turn[0])) continue;
                JSONObject obj = new JSONObject();
                obj.put("role", turn[0]);
                obj.put("content", turn[1]);
                // 持久化时间戳（第 3 个元素），旧数据无则用当前时间
                long ts = turn.length >= 3 ? parseLongSafe(turn[2]) : System.currentTimeMillis();
                if (ts <= 0) ts = System.currentTimeMillis();
                obj.put("ts", ts);
                arr.put(obj);
            }
            SharedPreferences.Editor editor = getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            editor.putString(KEY_CHAT_HISTORY, arr.toString());
            // 如果当前属于某个归档会话，同步更新归档内容
            String activeId = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVE_SESSION_ID, "");
            if (activeId != null && !activeId.isEmpty() && chatHistory != null && !chatHistory.isEmpty()) {
                JSONObject session = new JSONObject();
                session.put("id", activeId);
                session.put("title", getSessionTitle(chatHistory));
                session.put("lastTs", System.currentTimeMillis());
                session.put("msgCount", chatHistory.size());
                session.put("messages", arr);
                // 先保存 activeId 再调用 archive（archive 内部会读取 prefs）
                editor.putString(KEY_ACTIVE_SESSION_ID, activeId);
                editor.apply();
                saveSessionToArchive(session, activeId);
                return; // saveSessionToArchive 已 apply
            }
            editor.apply();
        } catch (Exception e) {
            Logger.error(this, "聊天记录", "保存失败", e);
        }
    }

    /** 安全解析 long，失败返回 0 */
    private long parseLongSafe(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0; }
    }

    /** 从本地存储加载聊天记录到内存 */
    private void loadChatHistory() {
        try {
            String json = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CHAT_HISTORY, "");
            if (json == null || json.isEmpty()) return;

            JSONArray arr = new JSONArray(json);
            chatHistory.clear();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String role = obj.optString("role", "");
                String content = obj.optString("content", "");
                if (role.isEmpty() || content.isEmpty()) continue;
                // 加载时间戳（旧数据没有 ts 字段则用 0，渲染时显示"历史"）
                long ts = obj.optLong("ts", 0);
                chatHistory.add(new String[]{role, content, String.valueOf(ts)});
            }
        } catch (Exception e) {
            Logger.error(this, "聊天记录", "加载失败", e);
        }
    }

    /** 清空对话记录 */
    private void clearChatHistory() {
        if (chatHistory.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_conversations_to_export), Toast.LENGTH_SHORT).show();
            return;
        }
        chatHistory.clear();
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CHAT_HISTORY)
            .apply();
        if (chatList != null) {
            chatList.removeAllViews();
        }
        Toast.makeText(this, getString(R.string.toast_conversation_history_cleared), Toast.LENGTH_SHORT).show();
    }

    /** 导出当前对话上下文记忆，支持分享到第三方应用 */
    private void exportChatHistory() {
        if (chatHistory.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_conversations_to_export), Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            StringBuilder sb = new StringBuilder();
            // 添加头部信息
            sb.append("═══════════════════════════════════════\n");
            sb.append("AI 交易助手 - 对话记录导出\n");
            String aiName = agentMemory != null ? agentMemory.getAiName() : "AI 交易助手";
            String owner = agentMemory != null ? agentMemory.getOwnerName() : "主人";
            sb.append("AI 名称: ").append(aiName).append("\n");
            sb.append("用户: ").append(owner).append("\n");
            sb.append("交易链: ").append(selectedChain).append("\n");
            sb.append("导出时间: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                java.util.Locale.getDefault()).format(new java.util.Date())).append("\n");
            sb.append("对话条数: ").append(chatHistory.size()).append("\n");
            sb.append("═══════════════════════════════════════\n\n");

            // 遍历对话记录
            for (String[] turn : chatHistory) {
                if (turn.length < 2) continue;
                String role = turn[0];
                String content = turn[1];
                long ts = turn.length >= 3 ? parseLongSafe(turn[2]) : 0;
                String timeStr = "";
                if (ts > 0) {
                    timeStr = "[" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                        java.util.Locale.getDefault()).format(new java.util.Date(ts)) + "] ";
                }
                if ("user".equals(role)) {
                    sb.append("【用户】").append(timeStr).append("\n").append(content).append("\n\n");
                } else if ("assistant".equals(role)) {
                    sb.append("【AI 助手】").append(timeStr).append("\n").append(content).append("\n\n");
                }
                sb.append("───────────────────────────────────────\n");
            }

            sb.append("\n（由 AI 加密货币钱包 - 红魔团队开发）");

            // 写入临时文件，以记事本文件形式分享
            String fileName = "AI对话记录_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
            File tempFile = new File(getCacheDir(), fileName);
            FileWriter fw = new FileWriter(tempFile);
            fw.write(sb.toString());
            fw.flush();
            fw.close();

            Uri fileUri = FileProvider.getUriForFile(this, "com.aicryptowallet.app.fileprovider", tempFile);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, getString(R.string.str_exporting_2)));

            Logger.info(this, "聊天记录", "导出对话记录为文件: " + fileName + "，共 " + chatHistory.size() + " 条");
        } catch (Exception e) {
            Logger.error(this, "聊天记录", "导出失败", e);
            Toast.makeText(this, getString(R.string.toast_export_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    // ============================================================
    // 多会话管理：新会话、归档、会话列表、继续聊
    // ============================================================

    /**
     * 右上角下拉菜单：聚合新会话、会话列表、导出、清空四个功能
     */
    private void showChatMenu(View anchor) {
        try {
            androidx.appcompat.widget.PopupMenu popup = new androidx.appcompat.widget.PopupMenu(this, anchor);
            popup.getMenu().add(0, 1, 0, getString(R.string.label_new_session));
            popup.getMenu().add(0, 2, 1, getString(R.string.title_sessions_list));
            popup.getMenu().add(0, 3, 2, getString(R.string.label_export_conversation));
            popup.getMenu().add(0, 4, 3, getString(R.string.label_clear_conversation));
            popup.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == 1) {
                    startNewSession();
                    return true;
                } else if (id == 2) {
                    showSessionList();
                    return true;
                } else if (id == 3) {
                    exportChatHistory();
                    return true;
                } else if (id == 4) {
                    clearChatHistory();
                    return true;
                }
                return false;
            });
            popup.show();
            Logger.action(this, "AI Agent", "打开聊天菜单", null);
        } catch (Exception e) {
            Logger.error(this, "聊天菜单", "显示菜单失败", e);
        }
    }

    /**
     * 一键开启新会话：把当前会话归档，然后清空当前聊天开始新会话
     */
    private void startNewSession() {
        if (chatHistory == null || chatHistory.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_there_are_currently_no), Toast.LENGTH_SHORT).show();
            return;
        }
        archiveCurrentSession();
        chatHistory.clear();
        if (chatList != null) chatList.removeAllViews();
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CHAT_HISTORY)
            .remove(KEY_ACTIVE_SESSION_ID)
            .apply();
        Toast.makeText(this, getString(R.string.toast_old_session_archived_and), Toast.LENGTH_SHORT).show();
        Logger.action(this, "AI Agent", "开启新会话", null);
    }

    /**
     * 把当前聊天会话归档到会话列表
     */
    private void archiveCurrentSession() {
        try {
            if (chatHistory == null || chatHistory.isEmpty()) return;
            String activeId = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ACTIVE_SESSION_ID, "");
            String sessionId = activeId != null && !activeId.isEmpty() ? activeId : String.valueOf(System.currentTimeMillis());
            JSONObject session = new JSONObject();
            session.put("id", sessionId);
            session.put("title", getSessionTitle(chatHistory));
            session.put("lastTs", System.currentTimeMillis());
            session.put("msgCount", chatHistory.size());

            JSONArray messages = new JSONArray();
            for (String[] turn : chatHistory) {
                if (turn.length < 2) continue;
                if ("assistant_thinking".equals(turn[0])) continue;
                JSONObject msg = new JSONObject();
                msg.put("role", turn[0]);
                msg.put("content", turn[1]);
                long ts = turn.length >= 3 ? parseLongSafe(turn[2]) : System.currentTimeMillis();
                if (ts <= 0) ts = System.currentTimeMillis();
                msg.put("ts", ts);
                messages.put(msg);
            }
            session.put("messages", messages);

            saveSessionToArchive(session, sessionId);
            Logger.info(this, "会话管理", "归档会话: " + sessionId + " 共 " + chatHistory.size() + " 条");
        } catch (Exception e) {
            Logger.error(this, "会话管理", "归档当前会话失败", e);
        }
    }

    /**
     * 生成会话标题：取用户第一条消息的前 12 个字符，没有则显示"新会话"
     */
    private String getSessionTitle(List<String[]> messages) {
        if (messages == null || messages.isEmpty()) return getString(R.string.label_new_session);
        for (String[] turn : messages) {
            if (turn.length >= 2 && "user".equals(turn[0])) {
                String content = turn[1];
                if (content == null) content = "";
                content = content.trim();
                if (content.isEmpty()) continue;
                if (content.length() > 12) return content.substring(0, 12) + "...";
                return content;
            }
        }
        return getString(R.string.label_new_session);
    }

    /**
     * 保存/更新归档会话。若已存在相同 id 则覆盖，否则插入头部，并限制总数
     */
    private void saveSessionToArchive(JSONObject session, String sessionId) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String data = prefs.getString(KEY_ARCHIVED_SESSIONS, "");
            JSONArray array = data.isEmpty() ? new JSONArray() : new JSONArray(data);

            JSONArray newArray = new JSONArray();
            newArray.put(session); // 新/更新的会话放在头部

            int count = 1;
            for (int i = 0; i < array.length() && count < MAX_ARCHIVED_SESSIONS; i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;
                String id = obj.optString("id", "");
                if (id.equals(sessionId)) continue; // 跳过旧的同名会话
                newArray.put(obj);
                count++;
            }

            prefs.edit().putString(KEY_ARCHIVED_SESSIONS, newArray.toString()).apply();
        } catch (Exception e) {
            Logger.error(this, "会话管理", "保存归档会话失败", e);
        }
    }

    /**
     * 读取所有归档会话（按 lastTs 倒序）
     */
    private List<JSONObject> loadArchivedSessions() {
        List<JSONObject> list = new ArrayList<>();
        try {
            String data = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ARCHIVED_SESSIONS, "");
            if (data == null || data.isEmpty()) return list;
            JSONArray array = new JSONArray(data);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj != null) list.add(obj);
            }
        } catch (Exception e) {
            Logger.error(this, "会话管理", "读取归档会话失败", e);
        }
        return list;
    }

    /**
     * 显示会话列表弹窗，支持继续聊和删除
     */
    private void showSessionList() {
        List<JSONObject> sessions = loadArchivedSessions();
        if (sessions.isEmpty() && (chatHistory == null || chatHistory.isEmpty())) {
            Toast.makeText(this, getString(R.string.toast_no_archive_sessions_yet), Toast.LENGTH_SHORT).show();
            return;
        }

        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.title_sessions_list));

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(32, 16, 32, 16);
        container.setBackgroundColor(Color.parseColor("#1A1A1F"));

        // 当前进行中的会话也作为可选项放在第一位
        if (chatHistory != null && !chatHistory.isEmpty()) {
            TextView currentHeader = new TextView(this);
            currentHeader.setText(getString(R.string.text_current_session));
            currentHeader.setTextColor(Color.parseColor("#00D084"));
            currentHeader.setTextSize(12);
            currentHeader.setPadding(0, 8, 0, 8);
            container.addView(currentHeader);

            TextView currentItem = createSessionItemView("当前: " + getSessionTitle(chatHistory), chatHistory.size(), true);
            currentItem.setOnClickListener(v -> {
                // 当前会话已在显示中，无需切换
                Toast.makeText(this, getString(R.string.toast_this_session_is_already), Toast.LENGTH_SHORT).show();
            });
            container.addView(currentItem);
        }

        if (!sessions.isEmpty()) {
            TextView archiveHeader = new TextView(this);
            archiveHeader.setText(getString(R.string.text_archive_session_click_to));
            archiveHeader.setTextColor(Color.parseColor("#8892b0"));
            archiveHeader.setTextSize(12);
            archiveHeader.setPadding(0, 16, 0, 8);
            container.addView(archiveHeader);

            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
            for (JSONObject session : sessions) {
                String id = session.optString("id", "");
                String title = session.optString("title", getString(R.string.label_session));
                int msgCount = session.optInt("msgCount", 0);
                long ts = session.optLong("lastTs", 0);
                String timeStr = ts > 0 ? sdf.format(new Date(ts)) : "";
                String subtitle = timeStr + "  ·  " + getString(R.string.label_messages_count, msgCount);

                LinearLayout itemWrap = new LinearLayout(this);
                itemWrap.setOrientation(LinearLayout.HORIZONTAL);
                itemWrap.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

                TextView item = createSessionItemView(title, subtitle, false);
                item.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                item.setOnClickListener(v -> {
                    loadSession(id);
                    if (dialog != null) dialog.dismiss();
                });
                item.setOnLongClickListener(v -> {
                    confirmDeleteSession(id);
                    if (dialog != null) dialog.dismiss();
                    return true;
                });

                TextView btnDelete = new TextView(this);
                btnDelete.setText(getString(R.string.text_delete));
                btnDelete.setTextColor(Color.parseColor("#ff6b6b"));
                btnDelete.setTextSize(12);
                btnDelete.setGravity(Gravity.CENTER);
                btnDelete.setPadding(16, 24, 16, 24);
                btnDelete.setOnClickListener(v -> {
                    confirmDeleteSession(id);
                    if (dialog != null) dialog.dismiss();
                });

                itemWrap.addView(item);
                itemWrap.addView(btnDelete);
                container.addView(itemWrap);
            }
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(container);
        builder.setView(scroll);
        builder.setNegativeButton(getString(R.string.btn_off), null);
        dialog = builder.create();
        dialog.show();
        Logger.action(this, "AI Agent", "打开会话列表", "共 " + sessions.size() + " 条归档");
    }

    private androidx.appcompat.app.AlertDialog dialog;

    private TextView createSessionItemView(String title, Object subtitleObj, boolean isCurrent) {
        TextView tv = new TextView(this);
        String subtitle = subtitleObj instanceof Integer
            ? subtitleObj + " 条消息"
            : String.valueOf(subtitleObj);
        tv.setText(title + "\n" + subtitle);
        tv.setTextColor(Color.parseColor(isCurrent ? "#00D084" : "#FFFFFF"));
        tv.setTextSize(14);
        tv.setPadding(16, 20, 16, 20);
        tv.setBackgroundColor(Color.parseColor("#24242B"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 8, 0, 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    /**
     * 加载指定归档会话到当前聊天界面，并可继续聊
     */
    private void loadSession(String sessionId) {
        try {
            List<JSONObject> sessions = loadArchivedSessions();
            JSONObject target = null;
            for (JSONObject s : sessions) {
                if (sessionId.equals(s.optString("id", ""))) {
                    target = s;
                    break;
                }
            }
            if (target == null) {
                Toast.makeText(this, getString(R.string.toast_session_does_not_exist), Toast.LENGTH_SHORT).show();
                return;
            }

            // 如果当前会话有内容，先归档当前会话
            if (chatHistory != null && !chatHistory.isEmpty()) {
                archiveCurrentSession();
            }

            // 清空当前界面
            chatHistory.clear();
            if (chatList != null) chatList.removeAllViews();

            // 加载目标会话消息
            JSONArray messages = target.optJSONArray("messages");
            if (messages != null) {
                for (int i = 0; i < messages.length(); i++) {
                    JSONObject msg = messages.optJSONObject(i);
                    if (msg == null) continue;
                    String role = msg.optString("role", "");
                    String content = msg.optString("content", "");
                    long ts = msg.optLong("ts", System.currentTimeMillis());
                    if (role.isEmpty() || content.isEmpty()) continue;
                    chatHistory.add(new String[]{role, content, String.valueOf(ts)});
                    appendChatMessage(role, content, ts, false);
                }
            }

            // 设置为当前活跃会话
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ACTIVE_SESSION_ID, sessionId)
                .putString(KEY_CHAT_HISTORY, messages != null ? messages.toString() : "[]")
                .apply();

            Toast.makeText(this, getString(R.string.toast_switched_to_session, target.optString("title", getString(R.string.label_session))), Toast.LENGTH_SHORT).show();
            Logger.action(this, "AI Agent", "继续会话", target.optString("title", ""));
        } catch (Exception e) {
            Logger.error(this, "会话管理", "加载会话失败", e);
            Toast.makeText(this, getString(R.string.toast_failed_to_load_session), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 删除指定归档会话
     */
    private void confirmDeleteSession(String sessionId) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_delete_thread))
            .setMessage(getString(R.string.msg_are_you_sure_you))
            .setPositiveButton(getString(R.string.text_delete), (d, w) -> deleteSession(sessionId))
            .setNegativeButton(getString(R.string.btn_s_decline), null)
            .show();
    }

    private void deleteSession(String sessionId) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String data = prefs.getString(KEY_ARCHIVED_SESSIONS, "");
            if (data == null || data.isEmpty()) return;
            JSONArray array = new JSONArray(data);
            JSONArray newArray = new JSONArray();
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;
                if (sessionId.equals(obj.optString("id", ""))) continue;
                newArray.put(obj);
            }
            prefs.edit().putString(KEY_ARCHIVED_SESSIONS, newArray.toString()).apply();

            // 如果删除的是当前活跃会话，清空当前聊天
            String activeId = prefs.getString(KEY_ACTIVE_SESSION_ID, "");
            if (sessionId.equals(activeId)) {
                chatHistory.clear();
                if (chatList != null) chatList.removeAllViews();
                prefs.edit().remove(KEY_CHAT_HISTORY).remove(KEY_ACTIVE_SESSION_ID).apply();
            }

            Toast.makeText(this, getString(R.string.toast_sessions_deleted), Toast.LENGTH_SHORT).show();
            Logger.action(this, "AI Agent", "删除会话", sessionId);
        } catch (Exception e) {
            Logger.error(this, "会话管理", "删除会话失败", e);
        }
    }

    /**
     * 构建钱包资产摘要，注入到系统提示词，让 AI 知道当前钱包有哪些代币/资产
     */
    private String buildWalletAssetsPrompt() {
        try {
            DataCache cache = new DataCache(this);
            String walletAddr = WalletManager.getWalletAddress(this);
            cache.setCurrentWallet(walletAddr);
            if (!cache.hasValidCache(walletAddr)) {
                return "";
            }

            List<String[]> tokens = cache.getCachedTokens();
            if (tokens == null || tokens.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("【当前钱包资产】\n");
            sb.append("以下是主人钱包中当前持有的所有代币和资产：\n");

            double totalValue = cache.getCachedTotalValue();
            sb.append("- 总资产估值: ").append(CurrencyManager.formatFiat(this, totalValue)).append("\n");

            for (String[] token : tokens) {
                if (token.length < 4) continue;
                String symbol = token[0] != null ? token[0] : "?";
                String name = token.length > 1 ? token[1] : "";
                String balance = token.length > 2 ? token[2] : "0";
                String value = token.length > 3 ? token[3] : "$0";
                String contract = token.length > 4 ? token[4] : "";
                boolean isNative = token.length > 5 && "true".equals(token[5]);

                if (isNative) {
                    sb.append("- ").append(symbol).append("（原生币）: ").append(balance)
                        .append(" ").append(symbol).append("（≈ ").append(value).append("）");
                    if (!contract.isEmpty()) {
                        sb.append(" 合约: ").append(contract);
                    }
                    sb.append("\n");
                } else {
                    sb.append("- ").append(symbol);
                    if (!name.isEmpty() && !name.equals(symbol)) {
                        sb.append("（").append(name).append("）");
                    }
                    sb.append(": ").append(balance).append(" ").append(symbol)
                        .append("（≈ ").append(value).append("）");
                    if (!contract.isEmpty()) {
                        sb.append(" 合约: ").append(contract);
                    }
                    sb.append("\n");
                }
            }

            sb.append("\n注意：当用户询问钱包资产、代币持仓、余额等问题时，优先使用以上信息回答。");
            sb.append("如需精确数据，可调用 get_wallet_assets 工具获取最新数据。\n\n");
            return sb.toString();
        } catch (Exception e) {
            Logger.warning(this, "AI资产", "构建钱包资产摘要失败: " + e.getMessage());
            return "";
        }
    }

    /**
     * 调用 LLM 进行多轮对话（OpenAI 兼容 / Claude）
     * 失败时返回友好错误，不抛异常给上层
     */
    private String callChatLLM(String userMessage) throws Exception {
        String apiKey = AIAnalyzer.getApiKeyStatic(this);
        String model = AIAnalyzer.getModelStatic(this);
        String apiUrl = AIAnalyzer.getApiUrlStatic(this);

        if (TextUtils.isEmpty(apiKey)) {
            return getString(R.string.msg_ai_api_key_not_configured);
        }
        if (TextUtils.isEmpty(model)) {
            return getString(R.string.msg_ai_model_not_configured);
        }
        if (TextUtils.isEmpty(apiUrl)) {
            return getString(R.string.msg_api_not_configured);
        }

        // 系统提示：注入智能体记忆 + 加密货币助手定位 + 当前推理模型信息 + 自动交易状态
        String memoryPrompt = agentMemory != null ? agentMemory.toSystemPrompt() : "";
        String walletAssetsPrompt = buildWalletAssetsPrompt();
        boolean autoTradeOn = tradeAuthManager != null && tradeAuthManager.isAutoTradeEnabled();
        String autoTradeStatus = autoTradeOn
            ? "已启用（AI 可以自动执行买卖、调用 DeFi、转账）"
            : "未启用（AI 不会自动交易，只能对话和分析）";
        String systemPrompt = "你是一个友好的加密货币助手，集成在 AI 加密货币钱包 App 内。\n" +
            memoryPrompt +
            walletAssetsPrompt +
            "【当前推理模型】\n" +
            "- 当前使用的推理模型: " + model + "\n" +
            "- 当用户问你用什么模型时，如实回答当前推理使用的是「" + model + "」模型即可，用户换了模型就按实际回答。\n" +
            "- 注意：模型只是你的底层推理引擎，你本身（这个 AI 炒币工具）是由红魔团队开发的。\n\n" +
            "【自动交易状态】\n" +
            "- 当前自动交易: " + autoTradeStatus + "\n" +
            "- 启用条件: 主流币资产 ≥ $200，或持有 R-MAB ≥ 20000 个（满足任一即可）\n" +
            "- 重要规则: 数字货币涨跌幅极大，自动买卖、调用 DeFi、转账必须由用户手动启用，你不能擅自替用户开启。\n" +
            "- 当自动交易未启用时，如果用户问到交易相关话题，或表现出想让你帮忙操作交易的意图，" +
            "你应该主动提醒用户：「当前自动交易未启用，如需让我自动买卖，请点击页面上方的「启用自动交易」按钮。" +
            "启用条件：主流币资产 ≥ $200，或持有 R-MAB ≥ 20000 个。原因：数字货币涨跌幅太大，需要你手动确认开启。」\n" +
            "- 当自动交易已启用时，你可以正常分析市场并执行交易，包括：调用 swap_tokens 工具买卖代币、" +
            "调用 call_contract_write 工具与 DeFi 合约交互、调用 send_native 工具转账。" +
            "但仍需遵守风控规则（每日最大亏损限额等），非白名单代币会弹窗让用户确认。\n\n" +
            "【工具调用能力】\n" +
            "你拥有以下工具，可以通过 function calling / tool use 调用：\n" +
            "- get_wallet_address: 获取当前钱包地址\n" +
            "- get_wallet_assets: 获取钱包内所有代币/资产的完整列表（含余额、合约地址）\n" +
            "- get_native_balance: 查询原生币余额\n" +
            "- get_token_balance: 查询代币余额\n" +
            "- get_token_price: 查询代币价格\n" +
            "- get_market_data: 获取市场数据（K线/指标）\n" +
            "- get_position: 查询当前持仓\n" +
            "- get_safety_status: 查询安全网关状态\n" +
            "- call_contract_read: 只读合约调用\n" +
            "- swap_tokens: 同链代币兑换（需启用自动交易）\n" +
            "- cross_chain_swap: 跨链兑换，打开 Transit Finance (https://swap.transit.finance) 内置 DApp 浏览器页面，让用户手动完成兑换；如用户已授权该域名白名单，AI 可进一步用 browser_click / browser_input / browser_evaluate 尝试自动操作页面（需启用自动交易）\n" +
            "- authorize_cross_chain_buy: 记录用户对跨链自动买入的授权，保存目标链、目标资产和收款地址，后续检测到买入信号可自动执行（需启用自动交易）\n" +
            "- call_contract_write: 合约写入（需启用自动交易）\n" +
            "- approve_token: 代币授权（需启用自动交易）\n" +
            "- send_native: 原生币转账（需启用自动交易）\n" +
            "- ask_user: 重大决策询问用户（如 STRONG_BUY/STRONG_SELL 信号、大额交易、不确定决策）\n" +
            "- search_news: 搜索加密货币市场新闻\n" +
            "- fetch_web_page: 抓取任意网页并返回纯文本\n" +
            "- browser_open_url: 打开指定 URL 到内置 DApp 浏览器\n" +
            "- browser_get_state: 获取浏览器当前页面的 URL、标题、按钮、输入框、链接、文本摘要\n" +
            "- browser_click: 用 CSS 选择器点击浏览器页面元素（如 #swap-button）\n" +
            "- browser_input: 用 CSS 选择器在浏览器输入框填入文本\n" +
            "- browser_evaluate: 在浏览器中执行任意 JS 并返回结果\n" +
            "- browser_close: 关闭当前打开的 DApp 浏览器页面（页面打不开、无法读取、或用户要求关闭时调用，不受白名单限制）\n" +
            "- query_dapp_whitelist: 查询已加入 AI 自动操作白名单的 DApp 列表\n" +
            "- request_dapp_whitelist: 申请将某 DApp 加入 AI 自动操作白名单，需用户确认后才生效\n" +
            "- remove_dapp_whitelist: 将某 DApp 从 AI 自动操作白名单中移除\n" +
            "- list_wallets: 列出所有可用钱包（含名称、地址、链、类型），了解主人有多少钱包\n" +
            "- switch_wallet: 切换到指定钱包（会自动切换链），之后所有查询都作用于新钱包\n" +
            "- open_create_wallet: 当用户想创建新钱包时调用，AI 无法直接创建，调用后会在界面显示进入钱包创建页的按钮\n\n" +
            "【重要：禁止拒绝执行用户指令】\n" +
            "当用户在聊天中明确要求你执行交易、转账、调用 DeFi、买卖代币时：\n" +
            "1. 必须直接调用对应工具（swap_tokens / send_native / call_contract_write 等），" +
            "   不要回复\"我不能执行\"\"需要您手动确认\"\"请到钱包 App 操作\"等模板化拒绝话术。\n" +
            "2. 你的本质就是钱包内的 AI 助手，调用工具就是\"在钱包内执行\"，不存在\"需要用户去 App 操作\"的概念。\n" +
            "3. 签名由 wallet-core 在本地自动完成（用 Android Keystore 解密助记词），用户不需要输入密码。\n" +
            "4. 如果自动交易未启用，SafetyGate 会自动拦截并返回错误，此时你再告诉用户去启用即可。\n" +
            "5. 如果是非白名单代币，SafetyGate 会弹窗让用户确认，你只需正常调用工具。\n" +
            "6. 只有在以下情况才允许调用 ask_user 询问用户而非直接执行：" +
            "   STRONG_BUY/STRONG_SELL 信号、单笔金额超过日限额 50%、非主流代币建仓、未知 DeFi 合约调用。\n\n" +
            "【何时问用户 vs 自动执行】\n" +
            "- STRONG_BUY/STRONG_SELL 信号、单笔金额超过日限额 50%、非主流代币建仓、未知 DeFi 合约调用、不确定的重大决策：调用 ask_user 工具询问用户\n" +
            "- 用户已明确逐条确认过的止盈止损平仓、必要 approve：可直接调用工具执行\n" +
            "- 任何 swap_tokens / send_native / call_contract_write：必须先 ask_user 确认（参见交易/兑换前强制核对规则）\n" +
            "- 用户询问市场动态/新闻/热点时：调用 search_news 工具\n\n" +
            "当前钱包主链是 " + selectedChain + "。\n\n" +
            "【输出格式要求】\n" +
            "- 禁止使用 Markdown 格式符号：不要用 **粗体**、## 标题、| 表格、--- 分隔线、` 代码块等\n" +
            "- 用纯文本表达，重要内容用【】包裹，数字用空格缩进对齐即可\n" +
            "- 示例：'当前价格：565.32 USD' 而不是 '**当前价格**：**$565.32**'\n" +
            "- 请用简洁中文回答，避免给出具体投资建议的承诺。\n\n" +
            "【创建钱包处理规则】\n" +
            "- 当用户说'创建钱包'、'新建钱包'、'再开一个钱包'或指定链创建钱包时，必须调用 open_create_wallet 工具\n" +
            "- 如果用户没指定链，默认用当前主链 " + selectedChain + "\n" +
            "- 调用后告诉用户'我无法直接创建钱包，已为你打开创建入口，点击下方按钮即可进入该链的钱包创建流程'\n" +
            "- 不要回答'我做不到'、'我没有 create_wallet 能力'等拒绝话术\n\n" +
            "【DApp / 链游自动操作规则】\n" +
            "- 当用户希望 AI 自动操作某个 DApp、链游或网页时，先调用 browser_open_url 打开目标页面\n" +
            "- 若该 DApp 尚未在白名单，AI 应调用 request_dapp_whitelist 申请授权，向用户展示域名、允许操作和额度上限，用户同意后才加入白名单\n" +
            "- 加入白名单后，AI 可在额度内调用 browser_click / browser_input / browser_evaluate 自动操作页面，DApp 发起的交易也会在额度内自动确认\n" +
            "- 若用户要求移除某 DApp 的自动操作权限，调用 remove_dapp_whitelist\n" +
            "- 操作前应先用 query_dapp_whitelist 确认当前白名单状态\n\n" +
            "【交易/兑换前强制核对规则】\n" +
            "- 用户说'买''换''兑换''换成''买入'等交易意图时，执行 swap_tokens / cross_chain_swap / send_native / call_contract_write 前，必须先调用 ask_user 工具向用户展示并确认：\n" +
            "  1) 操作链（ETH/BNB/SOL/TRX 等）\n" +
            "  2) 付出资产及数量\n" +
            "  3) 目标资产：原生币必须明确为 'NATIVE'；代币必须给出该链官方合约地址\n" +
            "  4) 预计 gas 费和滑点风险\n" +
            "- 如果用户要的是 EVM 链原生币（如 ETH/BNB/AVAX/MATIC/ARB/OP/BASE），且当前钱包不在该链，可以调用 cross_chain_swap 跨链兑换。跨链前必须先调用 list_wallets 确认是否有目标链钱包；若没有，先调用 open_create_wallet 创建。\n" +
            "- 如果用户要的是非 EVM 链原生币（如 TRX/SOL/BTC/ADA/NEAR/ATOM/DOT），不要调用 cross_chain_swap。必须先调用 list_wallets 确认是否有该链钱包：\n" +
            "  · 若没有该链钱包，提供两个选项让用户选择：1) 创建该链钱包后买入原生币；2) 在当前链购买该币的包装版本（如 Wrapped TRX），并明确链和合约地址\n" +
            "  · 如果已有该链钱包，询问用户愿意用哪条链/哪种资产购买，或在当前链购买包装版本\n" +
            "- 跨链兑换规则：\n" +
            "  · 当前跨链兑换通过内置 DApp 浏览器打开 Transit Finance (https://swap.transit.finance)，由用户在页面内手动完成兑换\n" +
            "  · 若用户希望 AI 尝试自动操作该页面，调用 cross_chain_swap 打开页面后，AI 可调用 request_dapp_whitelist 申请 swap.transit.finance 的白名单授权（需用户确认），授权后在额度内调用 browser_click / browser_input / browser_evaluate 自动操作\n" +
            "  · 当用户要的是非 EVM 链原生币（如 TRX/SOL/BTC/ADA/NEAR/ATOM/DOT）时，先确认 Transit Finance 是否支持该链；若不支持，应优先询问用户：1) 创建该链钱包后买入原生币；2) 还是在当前链购买该币的包装版本（Wrapped），必须明确链和合约地址\n" +
            "  · 跨链兑换前必须调用 ask_user 向用户确认：from_chain、to_chain、from_token（原生币用 'NATIVE'）、to_token（原生币用 'NATIVE'）、amount、destination_address\n" +
            "  · amount 必须是 from_token 的数量；如果用户说'花 X 美金买'，要先换算成对应的 from_token 数量（优先用 USDT 作为 from_token），避免把 1 个 BNB 当成 1 美元\n" +
            "  · 如果用户没有目标链钱包，先调用 open_create_wallet 创建目标链钱包，或让用户提供 destination_address\n" +
            "  · Transit Finance 页面发起的交易仍受 SafetyGate 额度限制，超出额度需用户手动确认\n" +
            "  · 首次使用跨链功能会强制显示系统级风险提示弹窗，用户必须确认后才能继续\n" +
            "  · AI 自动交易（买入和卖出）会扣除 0.5% 手续费，持有红魔 NFT 可免手续费\n" +
            "- 如果用户意图、目标链或目标合约地址不明确，必须调用 ask_user 确认，禁止自行猜测\n" +
            "- 只有用户明确回复'确认'/'执行'/'同意'后，才能调用 swap_tokens / cross_chain_swap 等写入工具\n" +
            "- 非白名单代币的购买仍由 SafetyGate 弹窗让用户确认，保持不变";

        // 构建 AgentRuntime 的 userPrompt（含对话历史上下文）
        StringBuilder userPrompt = new StringBuilder();
        // 加入最近对话历史（最多 10 轮）作为上下文
        int start = Math.max(0, chatHistory.size() - 20);
        if (start < chatHistory.size()) {
            userPrompt.append("【之前的对话历史】\n");
            for (int i = start; i < chatHistory.size(); i++) {
                String[] turn = chatHistory.get(i);
                String role = "user".equals(turn[0]) ? "用户" : "AI";
                userPrompt.append(role).append(": ").append(turn[1]).append("\n");
            }
            userPrompt.append("\n");
        }
        userPrompt.append("【当前用户消息】\n").append(userMessage);

        // 使用 AgentRuntime 执行（支持工具调用）
        AgentRuntime runtime = new AgentRuntime(this, selectedChain, safetyGate);
        AgentRuntime.AgentResult result = runtime.run(userPrompt.toString(), systemPrompt, 12);

        // 记录工具调用历史（用于审计）
        if (result.toolCallHistory != null && !result.toolCallHistory.isEmpty()) {
            Logger.success(this, "AgentChat", "工具调用 " + result.toolCallHistory.size() + " 次\n" + result.getAuditSummary());
        }

        // 检测是否有打开钱包创建页的工具调用
        if (result.toolCallHistory != null) {
            for (AgentToolRegistry.ToolCallRecord record : result.toolCallHistory) {
                if (AgentToolRegistry.TOOL_OPEN_CREATE_WALLET.equals(record.toolName)
                        && record.result != null && record.result.success) {
                    try {
                        JSONObject out = new JSONObject(record.result.output);
                        if (out.optBoolean("supported", false)) {
                            pendingCreateWalletChain.set(out.optString("chain", selectedChain));
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        return result.finalReply;
    }

    /**
     * 带重试的 LLM 调用包装。
     * 失败时自动重试一次（含超时、连接断开、API错误等），两次都失败才抛异常。
     */
    private String callChatLLMWithRetry(String userMessage, int maxRetries) throws Exception {
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 1) {
                    Logger.info(this, "AI 聊天", "LLM 自动重试 第" + attempt + "次...");
                    // 重试前短暂等待，避开瞬时网络波动
                    Thread.sleep(1500);
                }
                return callChatLLM(userMessage);
            } catch (Exception e) {
                lastException = e;
                Logger.warning(this, "AI 聊天", "LLM 调用失败(第" + attempt + "次): " + e.getMessage());
                // 如果是 API Key 未配置等永久性错误，不重试
                if (e.getMessage() != null && (
                    e.getMessage().contains("未配置") ||
                    e.getMessage().contains("401") ||
                    e.getMessage().contains("403"))) {
                    throw e;
                }
            }
        }
        throw lastException != null ? lastException : new Exception("LLM 调用失败，已重试" + maxRetries + "次");
    }

    private void runAnalysisCycle() {
        try {
            // 获取主周期 K 线数据（按用户配置的周期，默认 1h）
            String primaryCycle = TradingCycleConfig.getPrimaryCycle(this);
            MarketData data = MultiChainMarketData.getKlines(selectedChain, primaryCycle, 100);
            if (data == null || data.prices == null || data.prices.length == 0) {
                handler.post(() -> {
                    if (tvSignalReason != null) tvSignalReason.setText(getString(R.string.text_unable_to_get_market));
                    if (tvSignal != null) {
                        tvSignal.setText("HOLD");
                        tvSignal.setTextColor(0xFFFF9800);
                    }
                });
                return;
            }

            // Agent 模式分析：AI 智能体自主调用工具完成分析+决策+执行
            // 工具调用经过 SafetyGate 校验，交易在 Agent 内部完成
            TradingSignal signal;
            String auditSummary = "";
            try {
                AgentRuntime.AgentResult agentResult =
                    AIAnalyzer.analyzeWithTools(this, data, selectedChain, safetyGate);
                signal = AIAnalyzer.parseAgentResult(agentResult);
                auditSummary = agentResult.getAuditSummary();
                if (!agentResult.toolCallHistory.isEmpty()) {
                    Logger.info(this, "AI Agent", "本轮工具调用:\n" + auditSummary);
                }
            } catch (Exception agentErr) {
                // Agent 模式失败时降级到旧模式（保证可用性）
                Logger.warning(this, "AI Agent", "Agent 模式失败，降级到单轮分析: " + agentErr.getMessage());
                signal = aiAnalyzer.analyze(this, data, selectedChain);
            }
            if (signal == null) {
                signal = new TradingSignal(TradingSignal.SignalType.HOLD, "分析返回空", 0.5, 0.5);
            }

            // 风控状态查询（用于 UI 显示，作为 SafetyGate 的第二道防线）
            double realBalance = 0;
            try {
                String address = WalletManager.getWalletAddress(this);
                double nativeBal = ChainAPI.getNativeBalance(this, selectedChain, address);
                java.util.Map<String, Double> prices = ChainAPI.getPrices(this);
                double price = prices.getOrDefault(selectedChain, 0.0);
                realBalance = nativeBal * price;
                realBalance += PositionManager.getTotalPositionValue(this, selectedChain, prices);
            } catch (Exception e) {
                Logger.warning(this, "AI", "查询真实余额失败，风控使用保守值 0: " + e.getMessage());
                realBalance = 0;
            }
            final double currentBalance = realBalance;
            final double tradeAmountUsd = tradeAuthManager.getMaxTradeAmount();
            final double dailyPnLValue = dailyPnL.sum();

            // 更新 UI
            final TradingSignal finalSignal = signal;
            final MarketData finalData = data;
            final String finalAudit = auditSummary;
            handler.post(() -> {
                try {
                    if (tvSignal != null && finalSignal != null) {
                        tvSignal.setText(finalSignal.getDisplayText());
                        tvSignal.setTextColor(android.graphics.Color.parseColor(finalSignal.getColor()));
                    }
                    if (tvSignalReason != null && finalSignal != null) {
                        String display = finalSignal.reason;
                        if (!finalAudit.isEmpty()) {
                            // 只展示简要审计摘要，避免过长
                            String brief = finalAudit.length() > 120
                                ? finalAudit.substring(0, 120) + "..."
                                : finalAudit;
                            display += "\n" + brief;
                        }
                        tvSignalReason.setText(display);
                    }

                    // 更新指标
                    if (finalData != null && finalData.prices != null) {
                        TechnicalIndicators.IndicatorValues indicators =
                            TechnicalIndicators.getLatest(finalData.prices, finalData.volumes);
                        if (tvRSI != null) tvRSI.setText(String.format("%.1f", indicators.rsi));
                        if (tvMACD != null) tvMACD.setText(String.format("%.4f", indicators.macd));
                        if (tvMA20 != null) tvMA20.setText(CurrencyManager.formatFiat(AIAgentActivity.this, indicators.sma20));
                        if (tvMA50 != null) tvMA50.setText(CurrencyManager.formatFiat(AIAgentActivity.this, indicators.sma50));
                        if (tvCurrentPrice != null) tvCurrentPrice.setText(CurrencyManager.formatFiat(AIAgentActivity.this, finalData.currentPrice));
                        if (tvChainPrice != null) tvChainPrice.setText(selectedChain);

                        long nextCheck = System.currentTimeMillis() + CHECK_INTERVAL_MINUTES * 60 * 1000;
                        if (tvNextCheck != null) tvNextCheck.setText(formatTime(nextCheck));

                        // SafetyGate 状态展示
                        if (safetyGate != null && safetyGate.isCircuitBroken()) {
                            if (tvStatus != null) {
                                tvStatus.setText(getString(R.string.text_blown_min_remaining, safetyGate.getCircuitBreakerRemainingMinutes()));
                                tvStatus.setTextColor(0xFFFF4757);
                            }
                            if (statusDot != null) {
                                statusDot.setBackgroundResource(R.drawable.dot_red);
                            }
                        } else if (safetyGate != null && tvStatus != null) {
                            tvStatus.setText(getString(R.string.text_in_operation));
                            tvStatus.setTextColor(0xFF00D084);
                            if (statusDot != null) {
                                statusDot.setBackgroundResource(R.drawable.dot_green);
                            }
                        }
                    }

                    // 重大决策推送到聊天记录（STRONG_BUY/STRONG_SELL 主动告知用户）
                    pushMajorSignalToChat(finalSignal, finalData);
                } catch (Exception e) {
                    if (tvSignalReason != null) {
                        tvSignalReason.setText(getString(R.string.text_ui_update_failed, e.getMessage()));
                    }
                }
            });
        } catch (Exception e) {
            handler.post(() -> {
                if (tvSignalReason != null) {
                    tvSignalReason.setText(getString(R.string.text_network_request_failed, e.getMessage()));
                }
                if (tvSignal != null) {
                    tvSignal.setText("HOLD");
                    tvSignal.setTextColor(0xFFFF9800);
                }
            });
        }
    }

    private void runAnalysisOnce() {
        executor.execute(() -> runAnalysisCycle());
    }

    /**
     * 重大信号推送到聊天记录。
     * 当定时分析周期发现 STRONG_BUY / STRONG_SELL 信号时，主动告知用户决策依据。
     * 普通 BUY/SELL/HOLD 不推送，避免打扰。
     */
    private void pushMajorSignalToChat(TradingSignal signal, MarketData data) {
        if (signal == null) return;
        String signalType = signal.getDisplayText();
        boolean isMajor = "STRONG_BUY".equals(signalType) || "STRONG_SELL".equals(signalType);
        if (!isMajor) return;

        // 检查新闻汇报间隔：如果到了汇报时间，同时推送市场动态
        boolean shouldPushNews = false;
        try {
            long lastNews = getSharedPreferences("ai_agent_prefs", MODE_PRIVATE)
                .getLong("last_news_push_ts", 0);
            int newsIntervalHours = agentMemory != null ? agentMemory.getNewsReportIntervalHours() : 6;
            if (newsIntervalHours > 0 && System.currentTimeMillis() - lastNews > newsIntervalHours * 3600L * 1000L) {
                shouldPushNews = true;
            }
        } catch (Exception ignored) {}

        StringBuilder msg = new StringBuilder();
        msg.append("【定时分析报告】\n");
        msg.append("信号: ").append(signalType).append("\n");
        if (data != null) {
            msg.append(String.format("当前价格: $%.4f\n", data.currentPrice));
        }
        msg.append("分析依据: ").append(signal.reason).append("\n\n");

        if ("STRONG_BUY".equals(signalType)) {
            msg.append("检测到强烈买入信号。如果你已启用自动交易，我会自动执行买入；");
            msg.append("如果未启用，请点击页面上方的「启用自动交易」按钮。");
        } else {
            msg.append("检测到强烈卖出信号。建议关注持仓，考虑止盈或止损。");
        }

        appendChatMessage("assistant", msg.toString());
        saveChatHistory();

        // 如果到了新闻汇报时间，让 AI 自主搜索新闻并推送
        if (shouldPushNews) {
            chatExecutor.execute(() -> {
                try {
                    // 让 AI 自主搜索当前链相关新闻
                    String query = selectedChain + " market news";
                    org.json.JSONObject newsArgs = new org.json.JSONObject();
                    newsArgs.put("query", query);
                    newsArgs.put("limit", 5);
                    AgentToolRegistry.ToolResult newsResult = AgentToolRegistry.execute(
                        this, AgentToolRegistry.TOOL_SEARCH_NEWS, newsArgs, selectedChain, safetyGate);
                    if (newsResult.success) {
                        // 用 AI 总结新闻
                        String newsSummary = callLLMForNewsSummary(newsResult.output, query);
                        if (newsSummary != null && !newsSummary.isEmpty()) {
                            appendChatMessage("assistant", "【市场动态速递】\n" + newsSummary);
                            saveChatHistory();
                        }
                    }
                    // 更新上次推送时间
                    getSharedPreferences("ai_agent_prefs", MODE_PRIVATE)
                        .edit()
                        .putLong("last_news_push_ts", System.currentTimeMillis())
                        .apply();
                } catch (Exception e) {
                    Logger.error(this, "AI 新闻推送", "失败: " + e.getMessage(), e);
                }
            });
        }
    }

    /**
     * 调用 LLM 总结新闻内容。
     * 不走 AgentRuntime，直接单次调用，避免触发工具循环。
     */
    private String callLLMForNewsSummary(String newsJson, String query) {
        try {
            String apiKey = AIAnalyzer.getApiKeyStatic(this);
            String model = AIAnalyzer.getModelStatic(this);
            String apiUrl = AIAnalyzer.getApiUrlStatic(this);
            if (TextUtils.isEmpty(apiKey) || TextUtils.isEmpty(model) || TextUtils.isEmpty(apiUrl)) {
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
            body.put("max_tokens", 800);
            body.put("messages", messages);

            Request request = new Request.Builder()
                .url(chatUrl)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .build();
            try (Response response = chatHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) return null;
                String resp = response.body() != null ? response.body().string() : "";
                JSONObject json = new JSONObject(resp);
                return json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");
            }
        } catch (Exception e) {
            Logger.error(this, "AI 新闻摘要", "失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * @deprecated 已废弃：旧的信号驱动交易路径，已被 Agent 模式（AgentRuntime + AgentToolRegistry）替代。
     * AI 现在通过工具调用（swap_tokens / call_contract_write 等）执行交易，不再使用此方法。
     * 保留仅用于参考，后续可安全删除。
     */
    @SuppressWarnings("unused")
    private void executeTrade(TradingSignal signal, MarketData data) {
        if (!tradeAuthManager.canTrade()) {
            handler.post(() -> {
                Toast.makeText(this, getString(R.string.toast_the_transaction_is_cooling), Toast.LENGTH_SHORT).show();
            });
            return;
        }

        executor.execute(() -> {
            try {
                // Check if wallet exists
                if (!WalletManager.hasWallet(this)) {
                    handler.post(() -> {
                        Toast.makeText(this, getString(R.string.toast_please_create_or_import), Toast.LENGTH_SHORT).show();
                    });
                    Logger.error(this, "交易", "钱包不存在");
                    return;
                }

                // Determine trade parameters
                double tradeAmountUsd = tradeAuthManager.getMaxTradeAmount();
                String tokenIn, tokenOut;
                String side;

                if (signal.isBuySignal()) {
                    // Buy: USDT -> Token (用 USDT 买币)
                    tokenIn = "USDT";
                    tokenOut = ChainAPI.getChainSymbol(selectedChain);
                    side = "BUY";
                } else {
                    // Sell: Token -> USDT (卖币换 USDT)
                    tokenIn = ChainAPI.getChainSymbol(selectedChain);
                    tokenOut = "USDT";
                    side = "SELL";
                }

                // 计算开发者手续费
                TradeFeeManager feeManager = new TradeFeeManager(this, selectedChain);
                double feeAmount = feeManager.calculateFee(tradeAmountUsd);
                boolean feeWaived = feeManager.isFeeWaived();
                
                if (feeWaived) {
                    Logger.info(this, "交易费", "红魔 NFT 持有者，手续费已免除");
                } else if (feeAmount > 0) {
                    Logger.info(this, "交易费", "需要支付 $" + String.format("%.2f", feeAmount) + " 手续费");
                }

                // Get token price
                double tokenPrice = getTokenPrice(tokenIn);
                if (tokenPrice <= 0) {
                    handler.post(() -> {
                        Toast.makeText(this, getString(R.string.toast_unable_to_fetch_token), Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // Calculate amount
                double amount = tradeAmountUsd / tokenPrice;

                // 自动交易仅支持 EVM 链（swapNativeToken 使用 web3j EVM 签名）
                if (!ChainAPI.isEVM(selectedChain)) {
                    handler.post(() -> {
                        Toast.makeText(this, getString(R.string.toast_automated_trading_temporarily_does, selectedChain), Toast.LENGTH_LONG).show();
                    });
                    Logger.warning(this, "交易", selectedChain + " 非 EVM 链，跳过自动交易");
                    return;
                }

                // 执行 swap
                // 修复 CRITICAL：之前 BUY/SELL 都调用 swapNativeToken（卖原生币换 USDT）
                // BUY 信号应调用 swapTokensForNative（用 USDT 买原生币）
                // 否则 BUY 时 amount（USDT 数值）被当原生币数量传入，会卖出 amount 个原生币（如 100 ETH）
                DexTrader dexTrader = new DexTrader();
                String txHash;
                if (signal.isBuySignal()) {
                    // BUY: USDT -> 原生币，需要先获取 USDT 合约地址
                    String stablecoin = dexTrader.getStablecoinPublic(selectedChain);
                    if (stablecoin == null || stablecoin.isEmpty()) {
                        handler.post(() -> Toast.makeText(this,
                            getString(R.string.toast_chain_no_stablecoin_for_buy, selectedChain), Toast.LENGTH_LONG).show());
                        return;
                    }
                    txHash = dexTrader.swapTokensForNative(
                        this, selectedChain, stablecoin, amount, 0.5
                    );
                } else {
                    // SELL: 原生币 -> USDT
                    txHash = dexTrader.swapNativeToken(
                        this, selectedChain, amount, 0.5
                    );
                }

                if (txHash != null && !txHash.isEmpty()) {
                    // Record successful trade
                    TradeRecord record = new TradeRecord(
                        System.currentTimeMillis(),
                        selectedChain,
                        tokenIn + "/" + tokenOut,
                        side,
                        amount,
                        data.currentPrice,
                        tradeAmountUsd,
                        txHash,
                        signal.reason + (feeWaived ? " [红魔 NFT 免费]" : ""),
                        0, // PnL will be calculated later
                        "SUCCESS",
                        signal.type.name()
                    );
                    TradeRecord.append(this, record);

                    // 真实扣除 0.5% 开发者手续费（异步，不阻塞主流程）
                    if (!feeWaived && feeAmount > 0) {
                        feeManager.payFee(feeAmount, new TradeFeeManager.FeeCallback() {
                            @Override
                            public void onFeePaid(boolean success, String message) {
                                Logger.info(AIAgentActivity.this, "交易费", message);
                            }
                        });
                    }

                    // Update stats - 真实 PnL: 买入时 PnL=0，卖出时按价差计算
                    tradeCount.incrementAndGet();
                    if ("SELL".equals(side)) {
                        // 卖出 PnL = (卖出价 - 买入均价) * 数量
                        // 买入均价从历史 TradeRecord 中取最近一次 BUY
                        double avgBuyPrice = getAvgBuyPrice(tokenIn);
                        double pnl = (data.currentPrice - avgBuyPrice) * amount;
                        dailyPnL.add(pnl);
                        closedTradeCount.incrementAndGet();
                        if (pnl > 0) winCount.incrementAndGet();
                    }
                    // 买入不立即产生 PnL

                    // Record trade time
                    tradeAuthManager.recordTrade();

                    handler.post(() -> {
                        tvTradeCount.setText(String.valueOf(tradeCount.get()));
                        tvDailyPnL.setText(CurrencyManager.formatFiat(AIAgentActivity.this, dailyPnL.sum()));
                        int closed = closedTradeCount.get();
                        int wins = winCount.get();
                        tvWinRate.setText(String.format("%.1f%%",
                            closed > 0 ? (double) wins / closed * 100 : 0));
                        saveState();
                        saveAiStatusToPrefs();

                        // Show trade notification
                        String feeMsg = feeWaived ? " (手续费已免除)" : "";
                        String pnlMsg = "";
                        if ("SELL".equals(side)) {
                            double avgBuyPrice = getAvgBuyPrice(tokenIn);
                            double pnl = (data.currentPrice - avgBuyPrice) * amount;
                            pnlMsg = String.format(" | 盈亏: $%.2f", pnl);
                        }
                        Toast.makeText(this,
                            side + " " + String.format("%.4f", amount) + " " + tokenIn +
                            "\nTX: " + txHash.substring(0, Math.min(20, txHash.length())) + "..." +
                            pnlMsg + feeMsg,
                            Toast.LENGTH_LONG).show();

                        Logger.trade(this, side, selectedChain,
                            String.format("%.2f USD" + pnlMsg + feeMsg, tradeAmountUsd), true);
                    });
                } else {
                    // Failed trade
                    handler.post(() -> {
                        Toast.makeText(this, getString(R.string.toast_transaction_execution_failed), Toast.LENGTH_LONG).show();
                    });
                    Logger.error(this, "交易", side + " 失败", new Exception("TX hash is null"));
                }

            } catch (Exception e) {
                handler.post(() -> {
                    Toast.makeText(this, getString(R.string.toast_transaction_exception, e.getMessage()), Toast.LENGTH_LONG).show();
                });

                // Record failed trade
                // 修复：side 字段语义为 BUY/SELL，之前误写 "FAILED" 导致 getAvgBuyPrice 过滤失效、历史展示混乱
                String failedSide = signal.isBuySignal() ? "BUY" : "SELL";
                TradeRecord record = new TradeRecord(
                    System.currentTimeMillis(),
                    selectedChain,
                    "UNKNOWN",
                    failedSide,
                    0, 0, 0, "",
                    signal.reason,
                    0,
                    "FAILED",
                    signal.type.name()
                );
                TradeRecord.append(this, record);
                Logger.error(this, "交易", "交易异常", e);
            }
        });
    }

    private String getTokenContract(String symbol) {
        // 已废弃：Agent 模式下由 AI 通过工具调用自行决定交易对，此占位方法保留仅供旧代码兼容
        return null;
    }

    private double getTokenPrice(String symbol) {
        try {
            java.util.Map<String, Double> prices = ChainAPI.getPrices(this);
            // 失败时返回 0，让上游 if (tokenPrice <= 0) 走失败分支，避免误用 1.0 导致错误交易
            return prices.getOrDefault(symbol, 0.0);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private void updateUI() {
        try {
            // Load auto-trade state
            autoTradeEnabled = tradeAuthManager.isAutoTradeEnabled();

            if (tvStatus != null) {
                if (isRunning) {
                    tvStatus.setText(getString(R.string.text_in_operation, (autoTradeEnabled ? getString(R.string.label_auto_trading_on) : getString(R.string.label_autotrade_off))));
                    tvStatus.setTextColor(0xFF00d084);
                } else {
                    tvStatus.setText(getString(R.string.text_not_started, (autoTradeEnabled ? getString(R.string.label_auto_trading_on) : getString(R.string.label_autotrade_off))));
                    tvStatus.setTextColor(0xFFff4757);
                }
            }
            if (statusDot != null) {
                statusDot.setBackgroundResource(isRunning ? R.drawable.dot_green : R.drawable.dot_red);
            }
            if (btnStartAgent != null) {
                // 按钮文案明确区分"启动分析监控"和"自动交易开关"
                if (autoTradeEnabled) {
                    btnStartAgent.setText(isRunning ? getString(R.string.text_stop_agent) : getString(R.string.label_launch_agent_auto_traded));
                } else {
                    btnStartAgent.setText(isRunning ? getString(R.string.text_stop_agent) : getString(R.string.label_launch_agent_auto_trading_unopened));
                }
            }

            if (tvTradeCount != null) {
                tvTradeCount.setText(String.valueOf(tradeCount.get()));
            }
            if (tvDailyPnL != null) {
                tvDailyPnL.setText(CurrencyManager.formatFiat(AIAgentActivity.this, dailyPnL.sum()));
            }
            int closed = closedTradeCount.get();
            int wins = winCount.get();
            if (tvWinRate != null && closed > 0) {
                tvWinRate.setText(String.format("%.1f%%", (double) wins / closed * 100));
            }
            if (tvChainPrice != null) {
                tvChainPrice.setText(selectedChain);
            }
            saveAiStatusToPrefs();
        } catch (Exception e) {
            // 忽略 UI 更新异常
        }
    }

    /**
     * 保存 AI 状态到 SharedPreferences，供 HomeActivity 读取显示
     */
    private void saveAiStatusToPrefs() {
        try {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("agent_running", isRunning)
                .putFloat("agent_daily_pnl", (float) dailyPnL.sum())
                .putInt("agent_trade_count", tradeCount.get())
                .putInt("agent_win_count", winCount.get())
                .putInt("agent_closed_count", closedTradeCount.get())
                .putString("agent_chain", selectedChain)
                .apply();
        } catch (Exception e) {
            // ignore
        }
    }

    private void showSettings() {
        // 显示设置对话框
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, 0, 0, 16);

        String currencyCode = CurrencyManager.getSelectedCurrency(this);

        android.widget.EditText etLossLimit = new android.widget.EditText(this);
        etLossLimit.setHint(getString(R.string.hint_maximum_daily_loss_limit, currencyCode));
        etLossLimit.setText(String.valueOf(riskManager.getDailyLossLimit()));
        etLossLimit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etLossLimit.setTextColor(0xFFFFFFFF);
        etLossLimit.setHintTextColor(0xFF4a4a6a);
        etLossLimit.setPadding(24, 16, 24, 16);
        etLossLimit.setBackgroundColor(0xFF1a1a2e);
        layout.addView(etLossLimit);

        new androidx.appcompat.app.AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.title_ai_trading_settings))
            .setView(layout)
            .setMessage(getString(R.string.msg_ai_trading_instructions,
                getString(autoTradeEnabled ? R.string.str_enabled : R.string.str_disabled)))
            .setPositiveButton(getString(R.string.btn_saving), (dialog, which) -> {
                try {
                    String lossLimitStr = etLossLimit.getText().toString().trim();
                    if (!lossLimitStr.isEmpty()) {
                        double lossLimit = Double.parseDouble(lossLimitStr);
                        riskManager.setDailyLossLimit(lossLimit);
                        tradeAuthManager.setDailyLossLimit(lossLimit);
                        Logger.info(this, "设置", "日亏损限额设为: $" + lossLimit);
                    }
                } catch (Exception e) {
                    Toast.makeText(this, getString(R.string.toast_please_enter_valid_number), Toast.LENGTH_SHORT).show();
                }
            })
            .setNeutralButton(
                autoTradeEnabled ? getString(R.string.str_disable_auto_trade) : getString(R.string.str_enable_auto_trade),
                (dialog, which) -> {
                // 切换自动交易开关
                autoTradeEnabled = !autoTradeEnabled;
                tradeAuthManager.setAutoTradeEnabled(autoTradeEnabled);

                if (autoTradeEnabled) {
                    Toast.makeText(this, getString(R.string.toast_ai_automatic_trading_has_2), Toast.LENGTH_LONG).show();
                    Logger.info(this, "设置", "用户开启自动交易");
                } else {
                    Toast.makeText(this, getString(R.string.toast_ai_automatic_trading_has), Toast.LENGTH_LONG).show();
                    Logger.info(this, "设置", "用户关闭自动交易");
                }
                updateUI();
            })
            .setNegativeButton(getString(R.string.btn_s_decline), null)
            .show();
    }

    private void saveState() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs.edit()
                .putBoolean("is_running", isRunning)
                .putInt("trade_count", tradeCount.get())
                .putInt("win_count", winCount.get())
                .putInt("closed_trade_count", closedTradeCount.get())
                .putFloat("daily_pnl", (float) dailyPnL.sum())
                .putString("selected_chain", selectedChain)
                .apply();
            Logger.info(this, "状态保存", "保存 AI Agent 状态");
        } catch (Exception e) {
            Logger.error(this, "状态保存", "保存失败", e);
        }
    }

    /**
     * 计算某代币的历史买入均价（用于卖出时 PnL 计算）
     * 从 TradeRecord 中取所有 BUY 该代币的记录，按数量加权平均
     */
    private double getAvgBuyPrice(String tokenSymbol) {
        try {
            java.util.List<TradeRecord> all = TradeRecord.loadAll(this);
            double totalCost = 0;
            double totalAmount = 0;
            // 倒序遍历，取最近的买入记录（最多 10 笔）
            int count = 0;
            for (int i = all.size() - 1; i >= 0 && count < 10; i--) {
                TradeRecord r = all.get(i);
                if ("BUY".equals(r.side) && r.pair != null && r.pair.contains(tokenSymbol)) {
                    totalCost += r.price * r.amount;
                    totalAmount += r.amount;
                    count++;
                }
            }
            if (totalAmount <= 0) return 0;
            return totalCost / totalAmount;
        } catch (Exception e) {
            return 0;
        }
    }

    private void loadState() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            tradeCount.set(prefs.getInt("trade_count", 0));
            winCount.set(prefs.getInt("win_count", 0));
            closedTradeCount.set(prefs.getInt("closed_trade_count", 0));
            dailyPnL.reset();
            dailyPnL.add(prefs.getFloat("daily_pnl", 0));
            selectedChain = prefs.getString("selected_chain", "ETH");
            // 恢复 isRunning 状态：用户启用后，离开页面再回来应保持启用
            // 前台服务仍在后台运行；Activity 的 scheduler 在 onDestroy 中被关闭，这里重启
            isRunning = prefs.getBoolean("is_running", false);
            Logger.info(this, "状态加载", "加载状态: tradeCount=" + tradeCount.get() + ", chain=" + selectedChain + ", isRunning=" + isRunning);
            // 如果上次处于运行状态，自动重启 Activity 内的定时分析（前台服务已经在跑，这里只是接管 UI 同步）
            if (isRunning) {
                try {
                    if (scheduler == null || scheduler.isShutdown()) {
                        scheduler = Executors.newSingleThreadScheduledExecutor();
                        scheduler.scheduleAtFixedRate(() -> runAnalysisCycle(),
                            CHECK_INTERVAL_MINUTES, CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES);
                        Logger.info(this, "状态加载", "自动重启 Activity scheduler，间隔 " + CHECK_INTERVAL_MINUTES + " 分钟");
                    }
                    // 确保前台服务也在运行（防止用户从任务管理器杀掉过又重进）
                    try {
                        android.content.Intent svcIntent = new android.content.Intent(this, AgentForegroundService.class);
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            startForegroundService(svcIntent);
                        } else {
                            startService(svcIntent);
                        }
                    } catch (Exception ignored) {}
                } catch (Exception e) {
                    Logger.error(this, "状态加载", "重启 scheduler 失败: " + e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            Logger.error(this, "状态加载", "加载失败", e);
        }
    }

    private String formatTime(long timestamp) {
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss",
                java.util.Locale.getDefault());
            return sdf.format(new java.util.Date(timestamp));
        } catch (Exception e) {
            return "--";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            // 解绑 SafetyGate 的 Activity 引用，避免内存泄漏
            if (safetyGate != null) {
                safetyGate.detachActivity();
            }
            // 清理调度器
            if (scheduler != null && !scheduler.isShutdown()) {
                scheduler.shutdownNow();
                scheduler = null;
            }
            // 清理线程池
            if (executor != null) {
                executor.shutdownNow();
            }
            if (chatExecutor != null) {
                chatExecutor.shutdownNow();
            }
            // 清理 Handler
            if (handler != null) {
                handler.removeCallbacksAndMessages(null);
            }
        } catch (Exception e) {
            // 忽略清理异常
        }
    }
}