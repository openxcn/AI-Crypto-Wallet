package com.aicryptowallet.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;

public class TokenDetailActivity extends BaseActivity {

    private String tokenSymbol, tokenName, tokenBalance, tokenValue, contractAddress, chain;
    private int tokenDecimals = 18; // 默认18，通过RPC查询合约获取真实值
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    // AI 分析独立线程池：避免与交易加载共用单线程导致分析排队卡死
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    // AI 分析看门狗：网络分析超时后恢复按钮，避免按钮卡在禁用态"点不动"
    private Runnable aiBtnWatchdog;
    private LinearLayout txListContainer;
    private ProgressBar progressLoading;
    private TextView tvNoTx;
    private ScrollView txScrollView;
    private SwipeRefreshLayout swipeRefresh;
    // 交易记录分页加载
    private int txCurrentPage = 1;
    private boolean txLoadingMore = false;
    private boolean txHasMore = true;
    private View txLoadMoreFooter;
    // 风险分析
    private View layoutRiskAnalysis;
    private View layoutRiskActions;
    private TextView tvRiskStars;
    private TextView tvRiskLevel;
    private TextView tvDustWarning;
    private TextView btnHideToken;
    private TokenRiskAnalyzer.RiskResult riskResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_token_detail);

        tokenSymbol = getIntent().getStringExtra("symbol");
        tokenName = getIntent().getStringExtra("name");
        tokenBalance = getIntent().getStringExtra("balance");
        tokenValue = getIntent().getStringExtra("value");
        contractAddress = getIntent().getStringExtra("contract");
        chain = WalletManager.getChain(this);

        initViews();
        loadTokenInfo();
        loadTransactions();
        initRiskAnalysis();
        
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (aiBtnWatchdog != null) handler.removeCallbacks(aiBtnWatchdog);
        if (!executor.isShutdown()) {
            executor.shutdownNow();
        }
        if (!aiExecutor.isShutdown()) {
            aiExecutor.shutdownNow();
        }
        handler.removeCallbacksAndMessages(null);
    }

    private void initViews() {
        findViewById(R.id.btnBack).setOnClickListener(v -> { Logger.action(this, "UI操作", "返回", null); finish(); });
        findViewById(R.id.btnAddToken).setOnClickListener(v -> { Logger.action(this, "UI操作", "添加代币", null); showAddTokenDialog(); });

        findViewById(R.id.btnCopyContract).setOnClickListener(v -> {
            Logger.action(this, "UI操作", "复制合约", null);
            if (contractAddress != null && !contractAddress.isEmpty()) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("contract", contractAddress));
                Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show();
                Logger.actionResult(this, "UI操作", "复制合约地址", "成功");
            }
        });

        findViewById(R.id.btnReceive).setOnClickListener(v -> {
            Logger.action(this, "UI操作", "接收", null);
            Intent intent = new Intent(this, ReceiveActivity.class);
            intent.putExtra("chain", chain);
            startActivity(intent);
            Logger.actionResult(this, "UI操作", "接收", tokenSymbol);
        });

        // 发送按钮：跳转 SendActivity 并带上当前代币信息
        findViewById(R.id.btnSend).setOnClickListener(v -> {
            Logger.action(this, "UI操作", "发送", null);
            Intent intent = new Intent(this, SendActivity.class);
            intent.putExtra("symbol", tokenSymbol);
            intent.putExtra("name", tokenName);
            intent.putExtra("balance", tokenBalance);
            intent.putExtra("value", tokenValue);
            intent.putExtra("contract", contractAddress == null ? "" : contractAddress);
            startActivity(intent);
            Logger.actionResult(this, "UI操作", "发送", tokenSymbol);
        });

        findViewById(R.id.btnExplorer).setOnClickListener(v -> { Logger.action(this, "UI操作", "浏览器", null); openExplorer(); });

        txListContainer = findViewById(R.id.txListContainer);
        progressLoading = findViewById(R.id.progressLoading);
        tvNoTx = findViewById(R.id.tvNoTx);
        txScrollView = findViewById(R.id.txScrollView);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        swipeRefresh.setColorSchemeResources(R.color.text_blue);
        swipeRefresh.setOnRefreshListener(this::refreshTransactions);
    }

    private void loadTokenInfo() {
        ((TextView) findViewById(R.id.tvTokenTitle)).setText(tokenSymbol);
        ((TextView) findViewById(R.id.tvTokenSymbol)).setText(tokenSymbol);
        ((TextView) findViewById(R.id.tvTokenFullName)).setText(tokenName);
        ((TextView) findViewById(R.id.tvTokenBalance)).setText(tokenBalance);
        ((TextView) findViewById(R.id.tvTokenValue)).setText(tokenValue);

        String displayContract = contractAddress != null && !contractAddress.isEmpty()
            ? "合约: " + contractAddress.substring(0, 6) + "..." + contractAddress.substring(contractAddress.length() - 4)
            : "原生代币";
        ((TextView) findViewById(R.id.tvContractAddress)).setText(displayContract);

        // Load token logo using multi-layer loader (same as home page)
        ImageView iv = findViewById(R.id.ivTokenLogo);
        TokenLogoLoader.load(this, iv, tokenSymbol, contractAddress);
        // 通过 RPC 查询合约规格（name/symbol/decimals），确保显示准确的代币信息
        if (contractAddress != null && !contractAddress.isEmpty()) {
            queryTokenSpecsFromChain();
        }
    }

    /** 通过 RPC 查询代币合约的 name()/symbol()/decimals()，获取链上真实规格 */
    private void queryTokenSpecsFromChain() {
        executor.execute(() -> {
            // 0) 优先从本地缓存加载代币 name/symbol，避免每次都走链上 RPC（慢/易失败）
            String[] cached = findCachedSpec();
            if (cached != null) {
                Logger.info(this, "TokenDetail", "使用本地缓存代币规格: symbol=" + cached[0] + " name=" + cached[1]);
                handler.post(() -> applySpecsToUi(cached[0], cached[1], tokenDecimals));
                return;
            }
            try {
                String[] specs = ChainAPI.getTokenInfo(this, chain, contractAddress);
                if (specs != null && specs.length >= 3) {
                    final String chainSymbol = specs[0];
                    final String chainName = specs[1];
                    final int chainDecimals;
                    try {
                        chainDecimals = Integer.parseInt(specs[2]);
                    } catch (Exception e) {
                        // fallback to default
                        handler.post(() -> showSpecsFallback());
                        return;
                    }

                    tokenDecimals = chainDecimals;
                    Logger.info(this, "TokenDetail", "RPC查询合约规格: symbol=" + chainSymbol
                        + " name=" + chainName + " decimals=" + chainDecimals);

                    handler.post(() -> applySpecsToUi(chainSymbol, chainName, chainDecimals));
                } else {
                    handler.post(() -> showSpecsFallback());
                }
            } catch (Exception e) {
                Logger.warning(this, "TokenDetail", "RPC查询合约规格异常: " + e.getMessage());
                handler.post(() -> showSpecsFallback());
            }
        });
    }

    private void showSpecsFallback() {
        Logger.warning(this, "TokenDetail", "RPC查询合约规格失败，使用传入的 symbol/name");
    }

    /** 从本地缓存查找当前代币的 symbol/name；找不到返回 null */
    private String[] findCachedSpec() {
        try {
            if (contractAddress == null || contractAddress.isEmpty()) return null;
            DataCache cache = new DataCache(this);
            String addr = WalletManager.getWalletAddress(this);
            cache.setCurrentWallet(addr);
            if (!cache.hasValidCache(addr)) return null;
            for (String[] token : cache.getCachedTokens()) {
                if (token.length < 2) continue;
                String c = token.length > 4 ? token[4] : "";
                if (!c.isEmpty() && c.equalsIgnoreCase(contractAddress)) {
                    String pathSymbol = token[0] != null ? token[0] : "";
                    String pathName = token[1] != null ? token[1] : "";
                    if (!pathSymbol.isEmpty() || !pathName.isEmpty()) {
                        return new String[]{pathSymbol, pathName};
                    }
                }
            }
        } catch (Exception e) {
            Logger.warning(this, "TokenDetail", "读取本地缓存代币规格失败: " + e.getMessage());
        }
        return null;
    }

    /** 将代币规格（symbol/name/decimals）应用到 UI */
    private void applySpecsToUi(String chainSymbol, String chainName, final int chainDecimals) {
        if (chainSymbol != null && !chainSymbol.isEmpty()) {
            tokenSymbol = chainSymbol;
            ((TextView) findViewById(R.id.tvTokenTitle)).setText(chainSymbol);
            ((TextView) findViewById(R.id.tvTokenSymbol)).setText(chainSymbol);
        }
        if (chainName != null && !chainName.isEmpty()) {
            tokenName = chainName;
            ((TextView) findViewById(R.id.tvTokenFullName)).setText(chainName);
        }
        // 显示 decimals 信息
        View layoutSpecs = findViewById(R.id.layoutTokenSpecs);
        if (layoutSpecs != null) {
            layoutSpecs.setVisibility(View.VISIBLE);
            TextView tvDecimals = findViewById(R.id.tvTokenDecimals);
            if (tvDecimals != null) {
                tvDecimals.setText(getString(R.string.text_accuracy_decimal_place, chainDecimals));
            }
        }
    }

    // ===== 风险分析 =====

    private void initRiskAnalysis() {
        // 原生代币不需要风险分析
        if (contractAddress == null || contractAddress.isEmpty()) {
            Logger.info(this, "AI风险分析", "原生代币，跳过风险分析");
            return;
        }

        Logger.info(this, "AI风险分析", "初始化风险分析区域: contract=" + contractAddress + " symbol=" + tokenSymbol);

        layoutRiskAnalysis = findViewById(R.id.layoutRiskAnalysis);
        layoutRiskActions = findViewById(R.id.layoutRiskActions);
        tvRiskStars = findViewById(R.id.tvRiskStars);
        tvRiskLevel = findViewById(R.id.tvRiskLevel);
        tvDustWarning = findViewById(R.id.tvDustWarning);
        btnHideToken = findViewById(R.id.btnHideToken);

        // 显示风险分析区域
        if (layoutRiskAnalysis != null) {
            layoutRiskAnalysis.setVisibility(View.VISIBLE);
            Logger.info(this, "AI风险分析", "风险分析区域已设为 VISIBLE");
        } else {
            Logger.warning(this, "AI风险分析", "layoutRiskAnalysis 为 null！");
        }

        // AI 分析按钮
        View btnAi = findViewById(R.id.btnAiAnalyze);
        if (btnAi != null) {
            btnAi.setOnClickListener(v -> {
                Logger.action(this, "UI操作", "AI分析", null);
                Logger.info(this, "AI风险分析", "用户点击了 AI 分析按钮");
                startAiAnalysis();
            });
            Logger.info(this, "AI风险分析", "AI 分析按钮点击监听器已设置");
        } else {
            Logger.warning(this, "AI风险分析", "btnAiAnalyze 为 null！");
        }

        // AI 禁止交易按钮
        findViewById(R.id.btnBlockToken).setOnClickListener(v -> { Logger.action(this, "UI操作", "拉黑代币", null); blockToken(); });

        // 强制白名单按钮
        findViewById(R.id.btnWhitelistToken).setOnClickListener(v -> { Logger.action(this, "UI操作", "白名单代币", null); whitelistToken(); });

        // 一键隐藏（粉尘攻击/凭空收到的垃圾币）
        if (btnHideToken != null) {
            btnHideToken.setOnClickListener(v -> {
                Logger.action(this, "UI操作", "隐藏代币", tokenSymbol + " (" + chain + ")");
                HomeActivity.hideTokenStatic(this, chain, contractAddress);
                if (btnHideToken != null) btnHideToken.setVisibility(View.GONE);
                Toast.makeText(this, getString(R.string.toast_hidden_from_detail, tokenSymbol), Toast.LENGTH_SHORT).show();
                Logger.actionResult(this, "UI操作", "隐藏代币", "已隐藏 " + tokenSymbol + "，返回资产列表后将不再显示");
            });
        }

        // 分享报告按钮
        TextView btnShare = findViewById(R.id.btnShareReport);
        btnShare.setOnClickListener(v -> {
            Logger.action(this, "UI操作", "分享代币", null);
            TokenRiskAnalyzer.RiskResult result = riskResult;
            // 如果当前没有 riskResult，尝试从缓存构建
            if (result == null) {
                int stars = RiskManager.getCachedRiskStars(this, chain, contractAddress);
                String report = RiskManager.getCachedRiskReport(this, chain, contractAddress);
                if (stars >= 0 && report != null && !report.isEmpty()) {
                    result = new TokenRiskAnalyzer.RiskResult();
                    result.stars = stars;
                    result.report = report;
                    result.score = stars * 20;
                    result.riskFactors = new java.util.ArrayList<>();
                    result.safeFactors = new java.util.ArrayList<>();
                    result.dangerousFuncs = new java.util.ArrayList<>();
                    result.contractAge = "";
                    result.holderCount = "";
                    result.ownerAddress = "";
                    result.lpInfo = "";
                    result.lpLockedPercent = "";
                    result.poolDepth = "";
                    result.burnPercent = "";
                    result.top10Percent = "";
                    result.isVerified = false;
                    result.isHighRisk = stars <= 3;
                    result.contractSymbol = tokenSymbol;
                }
            }
            if (result != null) {
                ReportShareGenerator.shareReport(this, result, tokenSymbol, contractAddress, chain);
                Logger.actionResult(this, "UI操作", "分享代币", tokenSymbol);
            } else {
                Toast.makeText(this, getString(R.string.toast_please_complete_the_ai), Toast.LENGTH_SHORT).show();
            }
        });

        // 加载缓存的风险评分
        int cachedStars = RiskManager.getCachedRiskStars(this, chain, contractAddress);
        if (cachedStars >= 0) {
            updateRiskDisplay(cachedStars);
            // 有缓存结果时也显示分享按钮
            findViewById(R.id.btnShareReport).setVisibility(View.VISIBLE);
        }
    }

    private void startAiAnalysis() {
        Logger.info(this, "AI风险分析", "startAiAnalysis 开始: chain=" + chain + " contract=" + contractAddress + " symbol=" + tokenSymbol);

        // 网络预检：未联网时直接提示，不进入分析，避免按钮被禁用后无结果
        if (!isNetworkAvailable()) {
            Toast.makeText(this, getString(R.string.text_no_network), Toast.LENGTH_LONG).show();
            return;
        }

        TextView btn = findViewById(R.id.btnAiAnalyze);
        // 点击后：红色 + "分析中"，便于区分是否正在分析
        setAiBtnAnalyzing(btn);

        // 看门狗：分析最多等待 3 分钟，超时后无论如何都恢复按钮，防止"点不动"
        if (aiBtnWatchdog != null) handler.removeCallbacks(aiBtnWatchdog);
        aiBtnWatchdog = () -> {
            setAiBtnIdle(btn);
            btn.setEnabled(true);
            Toast.makeText(TokenDetailActivity.this, getString(R.string.text_ai_analysis_timeout), Toast.LENGTH_LONG).show();
        };
        handler.postDelayed(aiBtnWatchdog, 180000);

        aiExecutor.execute(() -> {
            try {
                Logger.info(TokenDetailActivity.this, "AI风险分析", "开始 TokenRiskAnalyzer.analyze...");
                TokenRiskAnalyzer.RiskResult result = TokenRiskAnalyzer.analyze(
                    this, chain, contractAddress, tokenSymbol);
                riskResult = result;
                Logger.success(TokenDetailActivity.this, "AI风险分析", "风险分析完成: stars=" + result.stars);

                // 缓存分析结果
                RiskManager.saveRiskScore(this, chain, contractAddress, result.stars, result.report);

                handler.post(() -> {
                    if (aiBtnWatchdog != null) handler.removeCallbacks(aiBtnWatchdog);
                    setAiBtnIdle(btn);
                    btn.setEnabled(true);
                    updateRiskDisplay(result.stars);
                    // 显示底部分享按钮
                    findViewById(R.id.btnShareReport).setVisibility(View.VISIBLE);
                    showRiskReportDialog(result);
                    Logger.actionResult(TokenDetailActivity.this, "UI操作", "AI分析", tokenSymbol);
                });
            } catch (Exception e) {
                Logger.error(TokenDetailActivity.this, "AI风险分析", "风险分析异常: " + e.getMessage(), e);
                handler.post(() -> {
                    if (aiBtnWatchdog != null) handler.removeCallbacks(aiBtnWatchdog);
                    setAiBtnIdle(btn);
                    btn.setEnabled(true);
                    Toast.makeText(TokenDetailActivity.this, getString(R.string.text_ai_analysis, e.getMessage()), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /** 按钮进入"分析中"状态：红色文字 + 红色底 + "分析中" */
    private void setAiBtnAnalyzing(TextView btn) {
        if (btn == null) return;
        btn.setText(getString(R.string.text_analyzing));
        btn.setTextColor(0xFFFF6B6B);
        btn.setBackgroundResource(R.drawable.card_background_red);
    }

    /** 按钮恢复"空闲"状态：绿色文字 + AI 分析 + 默认底 */
    private void setAiBtnIdle(TextView btn) {
        if (btn == null) return;
        btn.setText(getString(R.string.text_ai_analysis));
        btn.setTextColor(0xFF4ADE80);
        btn.setBackgroundResource(R.drawable.card_background);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnected();
    }

    private void updateRiskDisplay(int stars) {
        if (tvRiskStars == null || tvRiskLevel == null) return;

        StringBuilder starText = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            starText.append(i < stars ? "★" : "☆");
        }
        tvRiskStars.setText(starText.toString());

        int score = riskResult != null ? riskResult.score : 0;
        String levelText;
        int levelColor;
        switch (stars) {
            case 5: levelText = "极低风险 (" + score + "分)"; levelColor = 0xFF34C759; break;
            case 4: levelText = "低风险 (" + score + "分)"; levelColor = 0xFF34C759; break;
            case 3: levelText = "中等风险 (" + score + "分)"; levelColor = 0xFFFF9500; break;
            case 2: levelText = "高风险 (" + score + "分)"; levelColor = 0xFFFF453A; break;
            default: levelText = "极高风险 (" + score + "分)"; levelColor = 0xFFFF453A; break;
        }
        tvRiskLevel.setText(levelText);
        tvRiskLevel.setTextColor(levelColor);

        // 高风险（≤3星）显示操作按钮
        boolean isHighRisk = stars <= 3;
        if (layoutRiskActions != null) {
            layoutRiskActions.setVisibility(isHighRisk ? View.VISIBLE : View.GONE);
        }

        // 检查是否已在黑名单/白名单中
        boolean isBlocked = RiskManager.isBlacklisted(this, chain, contractAddress);
        boolean isWhitelisted = RiskManager.isWhitelisted(this, chain, contractAddress);

        TextView btnBlock = findViewById(R.id.btnBlockToken);
        TextView btnWhitelist = findViewById(R.id.btnWhitelistToken);

        if (isBlocked) {
            btnBlock.setText(getString(R.string.text_transaction_banned));
            btnBlock.setTextColor(0xFF9B9BA7);
        } else {
            btnBlock.setText(getString(R.string.text_ai_prohibits_transactions));
            btnBlock.setTextColor(0xFFFF453A);
        }
        if (isWhitelisted) {
            btnWhitelist.setText(getString(R.string.text_whitelisted));
            btnWhitelist.setTextColor(0xFF34C759);
        }

        // 粉尘攻击/凭空收到的垃圾币：显示预警横幅 + 一键隐藏按钮
        boolean isDust = riskResult != null && riskResult.isDustToken;
        if (tvDustWarning != null) {
            tvDustWarning.setVisibility(isDust ? View.VISIBLE : View.GONE);
        }
        if (btnHideToken != null) {
            boolean alreadyHidden = HomeActivity.getHiddenTokensStatic(this, chain)
                    .contains(contractAddress.toLowerCase());
            btnHideToken.setVisibility((isDust && !alreadyHidden) ? View.VISIBLE : View.GONE);
        }
    }

    private void showRiskReportDialog(TokenRiskAnalyzer.RiskResult result) {
        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.title_ai_risk_analysis_report))
            .setMessage(result.report)
            .setPositiveButton(getString(R.string.btn_share_pictures), (d, w) -> {
                ReportShareGenerator.shareReport(this, result, tokenSymbol, contractAddress, chain);
            })
            .setNegativeButton(getString(R.string.btn_got_it), null)
            .setNeutralButton(getString(R.string.label_view_risk_log), (d, w) -> {
                String log = RiskManager.exportRiskLog(this, chain);
                new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                    .setTitle(getString(R.string.title_risk_action_record))
                    .setMessage(log)
                    .setPositiveButton(getString(R.string.btn_off), null)
                    .show();
            })
            .show();
    }

    private void blockToken() {
        if (contractAddress == null || contractAddress.isEmpty()) return;

        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.text_ai_prohibits_transactions))
            .setMessage(getString(R.string.msg_token_block_confirm, tokenSymbol, contractAddress))
            .setPositiveButton(getString(R.string.btn_confirm_prohibition), (d, w) -> {
                RiskManager.addToBlacklist(this, chain, contractAddress);
                updateRiskDisplay(riskResult != null ? riskResult.stars : 3);
                Toast.makeText(this, getString(R.string.toast_trading_banned, tokenSymbol), Toast.LENGTH_SHORT).show();
                Logger.actionResult(this, "UI操作", "拉黑代币", tokenSymbol);
            })
            .setNegativeButton(getString(R.string.btn_s_decline), null)
            .show();
    }

    private void whitelistToken() {
        if (contractAddress == null || contractAddress.isEmpty()) return;

        // R-MAB 平台币永久豁免，无需加入白名单
        if (TokenRiskAnalyzer.RMAB_CONTRACT.equalsIgnoreCase(contractAddress)) {
            Toast.makeText(this, getString(R.string.toast_rmab_exempt_whitelist), Toast.LENGTH_LONG).show();
            return;
        }

        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.title_high_risk_operation_warning))
            .setMessage(getString(R.string.msg_token_whitelist_confirm, tokenSymbol))
            .setPositiveButton(getString(R.string.label_i_am_aware_of_the_risks_continue), (d, w) -> {
                RiskManager.addToWhitelist(this, chain, contractAddress, tokenSymbol);
                updateRiskDisplay(riskResult != null ? riskResult.stars : 3);
                Toast.makeText(this, getString(R.string.toast_has_been_added_to, tokenSymbol), Toast.LENGTH_LONG).show();
                Logger.actionResult(this, "UI操作", "白名单代币", tokenSymbol);
            })
            .setNegativeButton(getString(R.string.btn_s_decline), null)
            .show();
    }

    private void loadTransactions() {
        // 重置分页状态
        txCurrentPage = 1;
        txLoadingMore = false;
        txHasMore = true;
        progressLoading.setVisibility(View.VISIBLE);
        tvNoTx.setVisibility(View.GONE);
        txListContainer.removeAllViews();

        Logger.info(this, "TokenDetail", "加载交易记录: symbol=" + tokenSymbol
            + " contract=" + (contractAddress != null ? contractAddress : "null"));

        // 先加载缓存（秒开，网络不好时也能显示上次的数据）
        List<String[]> cachedTxs = loadTxCache();
        Logger.info(this, "TokenDetail", "缓存原始 " + cachedTxs.size() + " 条");
        List<String[]> filteredCache = filterTxsByContract(cachedTxs);
        Logger.info(this, "TokenDetail", "缓存过滤后 " + filteredCache.size() + " 条");
        if (!filteredCache.isEmpty()) {
            for (String[] tx : filteredCache) {
                addTransactionItem(tx);
            }
            txHasMore = filteredCache.size() >= 20;
            if (txHasMore) {
                addLoadMoreFooter();
            }
            progressLoading.setVisibility(View.GONE);
        }

        // 设置ScrollView滚动监听
        setupTxScrollListener();

        // 后台异步拉取最新数据
        executor.execute(() -> {
            String address = WalletManager.getWalletAddress(this);
            List<String[]> txs = new ArrayList<>();

            try {
                txs = ChainAPI.getTransactionHistory(this, chain, address, contractAddress, 1);
            } catch (Exception e) {
                Logger.warning(this, "交易记录", "拉取交易历史失败: " + e.getMessage());
            }

            final List<String[]> finalTxs = filterTxsByContract(txs);
            Logger.info(this, "TokenDetail", "网络返回 " + txs.size() + " 条，过滤后 " + finalTxs.size() + " 条");
            handler.post(() -> {
                progressLoading.setVisibility(View.GONE);
                if (finalTxs.isEmpty()) {
                    // 网络获取为空：如果有缓存则保留缓存显示，否则显示空
                    if (filteredCache.isEmpty()) {
                        if (txListContainer.getChildCount() == 0) {
                            tvNoTx.setVisibility(View.VISIBLE);
                        }
                    }
                    // 有缓存则保留显示，不覆盖
                } else {
                    tvNoTx.setVisibility(View.GONE);
                    txListContainer.removeAllViews();
                    for (String[] tx : finalTxs) {
                        addTransactionItem(tx);
                    }
                    saveTxCache(finalTxs);
                    txHasMore = finalTxs.size() >= 20;
                    if (txHasMore) {
                        addLoadMoreFooter();
                    }
                }
            });
        });
    }

    private void refreshTransactions() {
        txCurrentPage = 1;
        txHasMore = true;
        executor.execute(() -> {
            String address = WalletManager.getWalletAddress(this);
            List<String[]> txs = new ArrayList<>();
            try {
                txs = ChainAPI.getTransactionHistory(this, chain, address, contractAddress, 1);
            } catch (Exception e) {
                Logger.warning(this, "交易记录", "刷新交易历史失败: " + e.getMessage());
            }

            final List<String[]> finalTxs = filterTxsByContract(txs);
            handler.post(() -> {
                swipeRefresh.setRefreshing(false);
                txListContainer.removeAllViews();
                if (!finalTxs.isEmpty()) {
                    tvNoTx.setVisibility(View.GONE);
                    for (String[] tx : finalTxs) {
                        addTransactionItem(tx);
                    }
                    saveTxCache(finalTxs);
                    txHasMore = finalTxs.size() >= 20;
                    if (txHasMore) {
                        addLoadMoreFooter();
                    }
                } else {
                    tvNoTx.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    /** 按当前合约地址过滤交易：原生币详情页只显示 contract 为空的交易，代币详情页只显示对应该合约的交易 */
    private List<String[]> filterTxsByContract(List<String[]> txs) {
        List<String[]> result = new ArrayList<>();
        if (txs == null) return result;
        boolean isNative = contractAddress == null || contractAddress.isEmpty();
        for (String[] tx : txs) {
            if (tx == null || tx.length < TxRecord.MIN_FIELD_COUNT) {
                Logger.warning(this, "TokenDetail", "过滤时跳过异常交易数组，长度=" + (tx != null ? tx.length : "null"));
                continue;
            }
            String txContract = tx.length > TxRecord.INDEX_CONTRACT ? tx[TxRecord.INDEX_CONTRACT] : "";
            if (txContract == null) txContract = "";
            if (isNative) {
                if (txContract.isEmpty()) result.add(tx);
            } else {
                if (contractAddress.equalsIgnoreCase(txContract)) {
                    result.add(tx);
                } else {
                    Logger.info(this, "TokenDetail", "合约不匹配: 当前=" + contractAddress
                        + " 交易contract=" + txContract + " symbol=" + tx[TxRecord.INDEX_SYMBOL]);
                }
            }
        }
        return result;
    }

    private void setupTxScrollListener() {
        if (txScrollView == null) return;
        txScrollView.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (txLoadingMore || !txHasMore) return;
            int scrollY = txScrollView.getScrollY();
            int height = txScrollView.getHeight();
            int scrollViewHeight = txScrollView.getChildAt(0).getHeight();
            if (scrollViewHeight - (scrollY + height) < 200) {
                loadMoreTxRecords();
            }
        });
    }

    private void loadMoreTxRecords() {
        if (txLoadingMore || !txHasMore) return;
        txLoadingMore = true;

        String address = WalletManager.getWalletAddress(this);
        final int nextPage = txCurrentPage + 1;

        if (txLoadMoreFooter instanceof TextView) {
            ((TextView) txLoadMoreFooter).setText(getString(R.string.text_load_more_by));
        }

        executor.execute(() -> {
            List<String[]> txs = new ArrayList<>();
            try {
                txs = ChainAPI.getTransactionHistory(this, chain, address, contractAddress, nextPage);
            } catch (Exception e) {
                Logger.warning(this, "交易记录", "加载更多交易失败: " + e.getMessage());
            }

            final List<String[]> finalTxs = filterTxsByContract(txs);
            handler.post(() -> {
                if (txLoadMoreFooter != null && txLoadMoreFooter.getParent() == txListContainer) {
                    txListContainer.removeView(txLoadMoreFooter);
                }
                if (!finalTxs.isEmpty()) {
                    for (String[] tx : finalTxs) {
                        addTransactionItem(tx);
                    }
                    txCurrentPage = nextPage;
                    txHasMore = finalTxs.size() >= 20;
                    if (txHasMore) {
                        addLoadMoreFooter();
                    }
                } else {
                    txHasMore = false;
                    TextView endText = new TextView(this);
                    endText.setText(getString(R.string.text_no_more_information));
                    endText.setTextColor(0xFF6E6E7A);
                    endText.setTextSize(13);
                    endText.setGravity(Gravity.CENTER);
                    endText.setPadding(48, 32, 48, 48);
                    txListContainer.addView(endText);
                }
                txLoadingMore = false;
            });
        });
    }

    private void addLoadMoreFooter() {
        if (txLoadMoreFooter == null) {
            txLoadMoreFooter = new TextView(this);
        }
        ((TextView) txLoadMoreFooter).setText(getString(R.string.text_swipe_up_to_load));
        ((TextView) txLoadMoreFooter).setTextColor(0xFF6E6E7A);
        ((TextView) txLoadMoreFooter).setTextSize(13);
        ((TextView) txLoadMoreFooter).setGravity(Gravity.CENTER);
        ((TextView) txLoadMoreFooter).setPadding(48, 32, 48, 32);
        if (txLoadMoreFooter.getParent() != txListContainer) {
            txListContainer.addView(txLoadMoreFooter);
        }
    }

    /** 加载交易记录缓存 */
    private List<String[]> loadTxCache() {
        return ChainAPI.loadTxCache(this, chain, WalletManager.getWalletAddress(this), contractAddress);
    }

    /** 保存交易记录到缓存 */
    private void saveTxCache(List<String[]> txs) {
        ChainAPI.saveTxCache(this, chain, WalletManager.getWalletAddress(this), contractAddress, txs);
    }

    private void addTransactionItem(String[] tx) {
        View item = getLayoutInflater().inflate(R.layout.item_transaction, null);

        boolean isSend = tx[1].equalsIgnoreCase(WalletManager.getWalletAddress(this));

        // 交易类型（index=6）：transfer, contract_call, approval
        String txType = tx.length > TxRecord.INDEX_TYPE ? tx[TxRecord.INDEX_TYPE] : "transfer";

        // 判断当前页是否为原生币页，以及该交易是否为原生交易
        String txContract = tx.length > TxRecord.INDEX_CONTRACT && tx[TxRecord.INDEX_CONTRACT] != null
                ? tx[TxRecord.INDEX_CONTRACT] : "";
        boolean isNativePage = contractAddress == null || contractAddress.isEmpty();
        boolean isNativeTx = txContract.isEmpty();

        // 只使用交易本身携带的 symbol，避免代币交易在 BNB 页面显示为 BNB
        String sym = tx.length > TxRecord.INDEX_SYMBOL && tx[TxRecord.INDEX_SYMBOL] != null
                ? tx[TxRecord.INDEX_SYMBOL] : "";
        // 原生币页面的原生交易，symbol 回退到当前页面 tokenSymbol（BNB/ETH 等），避免 BNB 交易显示 --
        if (sym.isEmpty() && isNativePage && isNativeTx && tokenSymbol != null) {
            sym = tokenSymbol;
        }
        // 代币详情页：用 RPC 查询到的权威 symbol 覆盖 HTML 解析的 symbol（更准确）
        if (!isNativePage && !isNativeTx && tokenSymbol != null && !tokenSymbol.isEmpty()
                && !sym.equalsIgnoreCase(tokenSymbol)) {
            sym = tokenSymbol;
        }

        String typeLabel;
        String iconText;
        int iconColor;
        int bgColor;

        if ("contract_call".equals(txType)) {
            typeLabel = "合约调用";
            iconText = "⚙";
            iconColor = R.color.text_secondary;
            bgColor = R.color.text_secondary;
        } else if ("approval".equals(txType)) {
            typeLabel = "授权";
            iconText = "✓";
            iconColor = R.color.text_blue;
            bgColor = R.color.text_blue;
        } else {
            typeLabel = (isSend ? "发送" : "接收") + (sym.isEmpty() ? "" : " " + sym);
            iconText = isSend ? "↑" : "↓";
            iconColor = isSend ? R.color.text_red : R.color.text_green;
            bgColor = isSend ? R.color.red : R.color.green;
        }

        TextView tvType = item.findViewById(R.id.tvTxType);
        TextView tvIcon = item.findViewById(R.id.tvTxTypeIcon);
        tvType.setText(typeLabel);
        tvIcon.setText(iconText);
        tvIcon.setTextColor(getResources().getColor(iconColor));
        tvIcon.getBackground().setTint(getResources().getColor(bgColor));
        tvIcon.getBackground().setAlpha(30);

        String time = tx.length > TxRecord.INDEX_TIME ? tx[TxRecord.INDEX_TIME] : "";
        ((TextView) item.findViewById(R.id.tvTxTime)).setText(time);

        // 授权、无金额、或合约调用但无明确 symbol 时不显示金额
        String amount;
        int amountColor;
        String amountValue = tx.length > TxRecord.INDEX_AMOUNT && tx[TxRecord.INDEX_AMOUNT] != null
                ? tx[TxRecord.INDEX_AMOUNT] : "";
        // 原生币页面金额为空时显示 0，避免 --
        if (amountValue.isEmpty() && isNativePage && isNativeTx) {
            amountValue = "0";
        }
        if ("approval".equals(txType)) {
            amount = "--";
            amountColor = R.color.text_secondary;
        } else if ("contract_call".equals(txType)) {
            // 合约调用：原生币页面已回退 symbol，代币页面只有明确知道 symbol 时才显示
            if (sym.isEmpty()) {
                amount = "--";
                amountColor = R.color.text_secondary;
            } else {
                amount = (isSend ? "-" : "+") + amountValue + " " + sym;
                amountColor = isSend ? R.color.text_red : R.color.text_green;
            }
        } else {
            amount = (isSend ? "-" : "+") + amountValue + (sym.isEmpty() ? "" : " " + sym);
            amountColor = isSend ? R.color.text_red : R.color.text_green;
        }
        TextView tvAmount = item.findViewById(R.id.tvTxAmount);
        tvAmount.setText(amount);
        tvAmount.setTextColor(getResources().getColor(amountColor));

        String status = tx.length > 5 ? tx[5] : "unknown";
        TextView tvStatus = item.findViewById(R.id.tvTxStatus);
        if ("success".equalsIgnoreCase(status)) {
            tvStatus.setText(getString(R.string.text_berhasil));
            tvStatus.setTextColor(getResources().getColor(R.color.text_green));
        } else if ("failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status)) {
            tvStatus.setText(getString(R.string.text_kalah));
            tvStatus.setTextColor(getResources().getColor(R.color.text_red));
        } else {
            tvStatus.setText(getString(R.string.text_processing));
            tvStatus.setTextColor(getResources().getColor(R.color.text_secondary));
        }

        String txHash = tx[0];
        final String[] txData = tx;
        item.setOnClickListener(v -> {
            Logger.action(this, "UI操作", "交易记录详情", null);
            Intent intent = new Intent(this, TxDetailActivity.class);
            intent.putExtra(TxDetailActivity.EXTRA_TX_HASH, txHash);
            intent.putExtra(TxDetailActivity.EXTRA_CHAIN, chain);
            intent.putExtra(TxDetailActivity.EXTRA_TX_DATA, txData);
            startActivity(intent);
        });

        txListContainer.addView(item);
    }

    private void openTxInExplorer(String txHash) {
        String url = getExplorerTxUrl(chain, txHash);
        if (url != null) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        }
    }

    private void openExplorer() {
        String address = WalletManager.getWalletAddress(this);
        String url = getExplorerAddressUrl(chain, address);
        if (url != null) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        }
        Logger.actionResult(this, "UI操作", "浏览器", contractAddress);
    }

    private String getExplorerTxUrl(String chain, String txHash) {
        switch (chain) {
            case "ETH": return "https://etherscan.io/tx/" + txHash;
            case "BNB": return "https://bscscan.com/tx/" + txHash;
            case "MATIC": return "https://polygonscan.com/tx/" + txHash;
            case "AVAX": return "https://snowtrace.io/tx/" + txHash;
            case "FTM": return "https://ftmscan.com/tx/" + txHash;
            case "GLMR": return "https://moonbeam.moonscan.io/tx/" + txHash;
            case "CELO": return "https://celoscan.io/tx/" + txHash;
            case "ONE": return "https://explorer.harmony.one/tx/" + txHash;
            case "KAVA": return "https://kavascan.com/tx/" + txHash;
            case "SOL": return "https://solscan.io/tx/" + txHash;
            case "TRX": return "https://tronscan.org/#/transaction/" + txHash;
            case "ADA": return "https://cardanoscan.io/transaction/" + txHash;
            case "DOT": return "https://polkadot.subscan.io/extrinsic/" + txHash;
            default: return null;
        }
    }

    private String getExplorerAddressUrl(String chain, String address) {
        switch (chain) {
            case "ETH": return "https://etherscan.io/address/" + address;
            case "BNB": return "https://bscscan.com/address/" + address;
            case "MATIC": return "https://polygonscan.com/address/" + address;
            case "AVAX": return "https://snowtrace.io/address/" + address;
            case "FTM": return "https://ftmscan.com/address/" + address;
            case "SOL": return "https://solscan.io/account/" + address;
            case "TRX": return "https://tronscan.org/#/address/" + address;
            default: return null;
        }
    }

    private void showAddTokenDialog() {
        Logger.actionResult(this, "UI操作", "添加代币", "弹窗已打开");
        EditText etContract = new EditText(this);
        etContract.setHint(getString(R.string.hint_enter_token_contract_address));
        etContract.setTextColor(0xFFFFFFFF);
        etContract.setHintTextColor(0xFF4a4a6a);
        etContract.setTextSize(14);
        etContract.setPadding(32, 24, 32, 24);
        etContract.setBackgroundColor(0xFF1a1a2e);

        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.title_adding_custom_token))
            .setMessage(getString(R.string.msg_enter_the_token_contract))
            .setView(etContract)
            .setPositiveButton(getString(R.string.btn_tambah), (dialog, which) -> {
                String contract = etContract.getText().toString().trim();
                if (contract.isEmpty()) {
                    Toast.makeText(this, getString(R.string.toast_please_enter_the_contract), Toast.LENGTH_SHORT).show();
                    return;
                }
                addCustomToken(contract);
            })
            .setNegativeButton(getString(R.string.btn_s_decline), null)
            .show();
    }

    private void addCustomToken(String contractAddr) {
        Toast.makeText(this, getString(R.string.toast_querying_token_information), Toast.LENGTH_SHORT).show();

        executor.execute(() -> {
            try {
                String[] tokenInfo = ChainAPI.getTokenInfo(this, chain, contractAddr);
                if (tokenInfo != null) {
                    WalletManager.addCustomToken(this, chain,
                        tokenInfo[0], tokenInfo[1], contractAddr, tokenInfo[2]);

                    handler.post(() -> {
                        Toast.makeText(this,
                            "已添加 " + tokenInfo[0] + " (" + tokenInfo[1] + ")",
                            Toast.LENGTH_SHORT).show();
                        finish();
                        startActivity(getIntent());
                    });
                } else {
                    handler.post(() ->
                        Toast.makeText(this, getString(R.string.toast_no_token_information_found), Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                handler.post(() ->
                    Toast.makeText(this, getString(R.string.toast_query_failed, e.getMessage()), Toast.LENGTH_SHORT).show());
            }
        });
    }
}