package com.aicryptowallet.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import android.graphics.drawable.Drawable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.ref.WeakReference;
import androidx.core.content.FileProvider;
import java.math.BigInteger;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import com.aicryptowallet.app.crosschain.CrossChainLimitConfig;

public class HomeActivity extends BaseActivity {

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build();

    private static final ExecutorService IMAGE_EXECUTOR = Executors.newFixedThreadPool(3);

    private androidx.core.widget.NestedScrollView tabHome;
    private ScrollView tabAssets, tabTrade, tabSettings;
    private LinearLayout tabDiscover;
    private LinearLayout gridMine;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout assetsSwipeRefresh;
    private LinearLayout marketListContainer;
    private LinearLayout manualRecordsContainer;
    private LinearLayout aiRecordsContainer;
    private TextView tvMarketLoading;
    private TextView tvManualNoRecords;
    private TextView tvAiNoRecords;
    private CheckBox cbShowOnlyAiTrades;
    private TextView btnExportAiRecords;
    private TextView tabTradeManual;
    private TextView tabTradeAI;
    private int currentTradeTab = 0; // 0=手动记录, 1=AI操作记录

    // 外部跳转入口：直接打开 AI 操作记录
    public static final String EXTRA_SHOW_AI_RECORDS = "show_ai_records";
    public static final String EXTRA_AI_ONLY_TRADES = "ai_only_trades";

    // 交易记录分页加载
    private int txCurrentPage = 1;
    private boolean txLoadingMore = false;
    private boolean txHasMore = true;
    private View txLoadMoreFooter;
    private TextView tvMyWalletName;
    private TextView tvMyWalletAddress;
    // AI Status Card views
    private LinearLayout aiStatusCard;
    private LinearLayout lowBalanceCard;
    private View aiStatusDot;
    private TextView tvAiStatusText, tvAiCardPnL, tvAiCardWinRate, tvAiCardTrades, tvAiCardChain;
    private TextView btnStartAIFromHome, btnAddFunds;
    private TextView btnScrollToTop;
    private int marketTab = 0;
    // 行情分页
    private static final int MARKET_PAGE_SIZE = 20;
    private int marketCurrentPage = 0;
    private boolean marketLoadingMore = false;
    private boolean marketHasMore = true;
    private List<MarketCoin> marketAllCoins = new ArrayList<>();
    // 行情置顶币种（持久化）
    private static final String PREFS_MARKET = "market_prefs";
    private static final String KEY_PINNED_COINS = "pinned_coins";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService allWalletsExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService marketExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService aiRecordsExecutor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    // 资产加载任务去重：已有任务在执行时，新请求只记录待刷新，不重复排队清空列表
    private volatile boolean isLoadingAssets = false;
    private volatile boolean pendingAssetRefresh = false;
    private boolean balanceVisible = true;
    private double lastTotalBalanceUsd = 0.0;
    private double lastAllWalletsTotalUsd = 0.0;
    private DataCache dataCache;
    // 资产子Tab (Assets | DeFi | NFT | 授权)
    private TextView tabAssetsTab, tabDeFiTab, tabNftTab, tabApprovalsTab;
    private LinearLayout assetsContentArea, nftContentArea, deFiContainer, approvalsContainer;
    private LinearLayout nftListContainer;
    private LinearLayout smallAssetsContainer;
    private TextView btnFoldSmallAssets;
    private boolean smallAssetsFolded = true; // 默认折叠
    private LinearLayout networkWarningBanner;
    private TextView tvTodayPnL, tvDeFiLoading, tvApprovalsLoading;
    private int currentAssetSubTab = 0; // 0=资产, 1=DeFi, 2=NFT, 3=授权

    // 行情池：200+ 主流币种
    private static final String[] TARGET_PAIRS = buildMarketPairs();
    private static final Map<String, String> COIN_NAMES = buildCoinNames();

    private static String[] buildMarketPairs() {
        String[] symbols = {
            "BTC","ETH","BNB","SOL","TRX","XRP","DOGE","ADA","AVAX","DOT",
            "SHIB","MATIC","LINK","LTC","UNI","ATOM","ETC","XLM","BCH","FIL",
            "HBAR","ICP","APT","ARB","OP","NEAR","STX","GRT","IMX","VET",
            "AAVE","ALGO","DOT2","EGLD","MANA","SAND","AXS","FLOW","XTZ","KCS",
            "MKR","EOS","THETA","ZEC","DASH","NEO","CAKE","RUNE","SNX","COMP",
            "1INCH","CRV","SUSHI","YFI","BAL","LDO","SSV","RPL","FXS","PENDLE",
            "TIA","SUI","SEI","INJ","FET","RNDR","AGIX","OCEAN","ARKM","WLD",
            "ENA","STRK","ZRO","BLAST","MODE","MANTA","METIS","OP2","BASE","AEVO",
            "TAO","GALA","CHZ","ENJ","BAT","CVC","STORJ","AR","HNT","ICP2",
            "MINA","CELO","ONE","ROSE","SC","IOTX","ANKR","SKL","COTI","DGB",
            "ZIL","ONT","QTUM","IOST","WIN","SUN","JST","BTT","PEOPLE","MASK",
            "DOGE2","FLOKI","BONK","PEPE","WIF","BOME","SHIB2","MEME","AIDOGE","TURBO",
            "MOG","NEIRO","TOMI","PEPE2","SPX","HARRY","MICHI","TREMP","POPCAT","BODEN",
            "GOAT","MOODENG","PENGU","FWOG","AI16Z","ZEREBRO","LUNC","LUNA","FTT","COTI2",
            "XEC","BSV","BTG","DCR","XMR","KAVA","OSMO","SCRT","USTC","TERRA",
            "PAXG","GUSD","TUSD","USDD","FRAX","LUSD","SUSD","DAI2","USDC","USDT2",
            "WBTC","WETH","WEETH","RETH","STETH","CBETH","RSK","BIT","NFT","APE",
            "DYDX","GMX","GNS","SNX2","PERP","DODO","MUX","LYRA","VELA","KWENTA",
            "JOE","SUSHI2","CAKE2","BURGER","BAKE","XVS","AUTO","ALPACA","CREAM","FOR",
            "MTL","POWR","REQ","NKN","DATA","BAND","API3","TRB","LINK2","UMA",
            "REN","KEEP","NU","CGLD","OXT","LPT","RLC","NMR","BNT","KNC",
            "LOOM","STEEM","HIVE","SBD","WAVES","NXT","ARDR","IGNIS","ZRX","REP"
        };
        String[] pairs = new String[symbols.length];
        for (int i = 0; i < symbols.length; i++) {
            pairs[i] = symbols[i] + "_USDT";
        }
        return pairs;
    }

    private static Map<String, String> buildCoinNames() {
        Map<String, String> map = new HashMap<>();
        String[][] names = {
            {"BTC","Bitcoin"},{"ETH","Ethereum"},{"BNB","BNB Chain"},{"SOL","Solana"},{"TRX","TRON"},
            {"XRP","XRP"},{"DOGE","Dogecoin"},{"ADA","Cardano"},{"AVAX","Avalanche"},{"DOT","Polkadot"},
            {"SHIB","Shiba Inu"},{"MATIC","Polygon"},{"LINK","Chainlink"},{"LTC","Litecoin"},{"UNI","Uniswap"},
            {"ATOM","Cosmos"},{"ETC","Ethereum Classic"},{"XLM","Stellar"},{"BCH","Bitcoin Cash"},{"FIL","Filecoin"},
            {"HBAR","Hedera"},{"ICP","Internet Computer"},{"APT","Aptos"},{"ARB","Arbitrum"},{"OP","Optimism"},
            {"NEAR","NEAR Protocol"},{"STX","Stacks"},{"GRT","The Graph"},{"IMX","Immutable X"},{"VET","VeChain"},
            {"AAVE","Aave"},{"ALGO","Algorand"},{"EGLD","MultiversX"},{"MANA","Decentraland"},{"SAND","The Sandbox"},
            {"AXS","Axie Infinity"},{"FLOW","Flow"},{"XTZ","Tezos"},{"KCS","KuCoin Token"},{"MKR","Maker"},
            {"EOS","EOS"},{"THETA","Theta Network"},{"ZEC","Zcash"},{"DASH","Dash"},{"NEO","Neo"},
            {"CAKE","PancakeSwap"},{"RUNE","THORChain"},{"SNX","Synthetix"},{"COMP","Compound"},{"1INCH","1inch"},
            {"CRV","Curve DAO"},{"SUSHI","SushiSwap"},{"YFI","yearn.finance"},{"BAL","Balancer"},{"LDO","Lido DAO"},
            {"SSV","ssv.network"},{"RPL","Rocket Pool"},{"FXS","Frax Share"},{"PENDLE","Pendle"},{"TIA","Celestia"},
            {"SUI","Sui"},{"SEI","Sei"},{"INJ","Injective"},{"FET","Fetch.ai"},{"RNDR","Render"},
            {"AGIX","SingularityNET"},{"OCEAN","Ocean Protocol"},{"ARKM","Arkham"},{"WLD","Worldcoin"},{"ENA","Ethena"},
            {"STRK","Starknet"},{"ZRO","LayerZero"},{"BLAST","Blast"},{"MODE","Mode"},{"MANTA","Manta Network"},
            {"METIS","Metis"},{"BASE","Base"},{"AEVO","Aevo"},{"TAO","Bittensor"},{"GALA","Gala"},
            {"CHZ","Chiliz"},{"ENJ","Enjin"},{"BAT","Basic Attention"},{"CVC","Civic"},{"STORJ","Storj"},
            {"AR","Arweave"},{"HNT","Helium"},{"MINA","Mina"},{"CELO","Celo"},{"ONE","Harmony"},
            {"ROSE","Oasis Network"},{"SC","Siacoin"},{"IOTX","IoTeX"},{"ANKR","Ankr"},{"SKL","SKALE"},
            {"COTI","COTI"},{"DGB","DigiByte"},{"ZIL","Zilliqa"},{"ONT","Ontology"},{"QTUM","Qtum"},
            {"IOST","IOST"},{"WIN","WINkLink"},{"SUN","Sun Token"},{"JST","JUST"},{"BTT","BitTorrent"},
            {"PEOPLE","ConstitutionDAO"},{"MASK","Mask Network"},{"FLOKI","FLOKI"},{"BONK","Bonk"},{"PEPE","Pepe"},
            {"WIF","dogwifhat"},{"BOME","Book of Meme"},{"MEME","Memecoin"},{"AIDOGE","ArbDoge AI"},{"TURBO","Turbo"},
            {"MOG","Mog Coin"},{"NEIRO","Neiro"},{"TOMI","tomiNet"},{"SPX","SPX6900"},{"HARRY","HarryPotterObamaSonic10Inu"},
            {"MICHI","Michi"},{"TREMP","Doland Tremp"},{"POPCAT","Popcat"},{"BODEN","Jeo Boden"},{"GOAT","GOAT"},
            {"MOODENG","Moo Deng"},{"PENGU","Pudgy Penguins"},{"FWOG","FWOG"},{"AI16Z","ai16z"},{"ZEREBRO","Zerebro"},
            {"LUNC","Terra Classic"},{"LUNA","Terra"},{"FTT","FTX Token"},{"XEC","eCash"},{"BSV","Bitcoin SV"},
            {"BTG","Bitcoin Gold"},{"DCR","Decred"},{"XMR","Monero"},{"KAVA","Kava"},{"OSMO","Osmosis"},
            {"SCRT","Secret"},{"USTC","TerraClassicUSD"},{"PAXG","PAX Gold"},{"GUSD","Gemini Dollar"},{"TUSD","TrueUSD"},
            {"USDD","USDD"},{"FRAX","Frax"},{"LUSD","Liquidity USD"},{"SUSD","sUSD"},{"WBTC","Wrapped Bitcoin"},
            {"WETH","Wrapped Ether"},{"WEETH","Wrapped eETH"},{"RETH","Rocket Pool ETH"},{"STETH","Lido Staked ETH"},{"CBETH","Coinbase Wrapped ETH"},
            {"BIT","BitDAO"},{"APE","ApeCoin"},{"DYDX","dYdX"},{"GMX","GMX"},{"GNS","Gains Network"},
            {"PERP","Perpetual Protocol"},{"DODO","DODO"},{"MUX","MUX Protocol"},{"LYRA","Lyra Finance"},{"VELA","Vela"},
            {"KWENTA","Kwenta"},{"JOE","Trader Joe"},{"BURGER","BurgerCities"},{"BAKE","BakerySwap"},{"XVS","Venus"},
            {"AUTO","Auto"},{"ALPACA","Alpaca Finance"},{"CREAM","Cream Finance"},{"FOR","ForTube"},{"MTL","Metal"},
            {"POWR","Power Ledger"},{"REQ","Request"},{"NKN","NKN"},{"DATA","Streamr"},{"BAND","Band Protocol"},
            {"API3","API3"},{"TRB","Tellor"},{"UMA","UMA"},{"REN","Ren"},{"KEEP","Keep Network"},
            {"NU","NuCypher"},{"CGLD","Celo Gold"},{"OXT","Orchid"},{"LPT","Livepeer"},{"RLC","iExec"},
            {"NMR","Numeraire"},{"BNT","Bancor"},{"KNC","Kyber Network"},{"LOOM","Loom Network"},{"STEEM","Steem"},
            {"HIVE","Hive"},{"SBD","Steem Dollars"},{"WAVES","Waves"},{"NXT","NXT"},{"ARDR","Ardor"},
            {"IGNIS","Ignis"},{"ZRX","0x"},{"REP","Augur"}
        };
        for (String[] pair : names) {
            map.put(pair[0], pair[1]);
        }
        return map;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 主题必须在 setContentView 之前应用
        ThemeManager.applyTheme(this);

        try {
            // WalletManager.hasWallet 会触发 static { System.loadLibrary("TrustWalletCore") }
            // 如果 JNI 库加载失败，会抛 UnsatisfiedLinkError (Error, 非 Exception)
            // 必须在 try-catch(Throwable) 内调用
            if (!WalletManager.hasWallet(this)) {
                startActivity(new Intent(this, MainActivity.class));
                finish();
                return;
            }

            setContentView(R.layout.activity_home);
            // 截屏限制已移除（便于调试和用户截图反馈）

            // 动态设置版本号（跟随真实版本号）
            try {
                TextView tvVersion = findViewById(R.id.tvVersion);
                if (tvVersion != null) {
                    tvVersion.setText(BuildConfig.VERSION_DISPLAY);
                }
            } catch (Exception ignored) {}

            dataCache = new DataCache(this);
            initViews();
            initTabs();
            initMarketTabs();
            initQuickActions();
            initTradeTab();
            initDiscoverTab();
            initMyPage();
            initSettings();
            handleIntent(getIntent());
            loadWalletInfo();
            loadAssetsFromCache();                 // 先显示缓存（秒开）
            optimizeNodeAndLoadAssets();             // 自动测速选节点后刷新
            // 冷启动更早拉起 AI 前台服务：若用户已开启 AI，立即在后台运行，无需等 onResume
            ensureAgentServiceIfRunning();

        } catch (Throwable t) {
            Logger.error(this, "HomeActivity", "onCreate FATAL: " + t.getClass().getName() + ": " + t.getMessage(), t);
            // 同步写入外部存储以便查看
            try {
                File extDir = getExternalFilesDir(null);
                if (extDir != null) {
                    File crashFile = new File(extDir, "crash_log_v" + BuildConfig.VERSION_NAME + ".txt");
                    java.io.FileWriter fw = new java.io.FileWriter(crashFile, true);
                    fw.write("=== CRASH " + new java.util.Date() + " ===\n");
                    fw.write("Error: " + t.getClass().getName() + ": " + t.getMessage() + "\n");
                    java.io.StringWriter sw = new java.io.StringWriter();
                    t.printStackTrace(new java.io.PrintWriter(sw));
                    fw.write(sw.toString() + "\n\n");
                    fw.close();
                }
            } catch (Exception ignored) {}
            try {
                Toast.makeText(this, getString(R.string.toast_failed_to_start_ai, t.getMessage()), Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {}
            finish();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        // 通过 intent 重新回到前台时，同样确保 AI 前台服务在运行（避免必须点击 AI 按钮才启动）
        try {
            ensureAgentServiceIfRunning();
        } catch (Throwable ignore) {}
    }

    /**
     * 处理外部跳转意图：直接定位到 AI 操作记录页
     */
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra(EXTRA_SHOW_AI_RECORDS, false)) {
            boolean onlyTrades = intent.getBooleanExtra(EXTRA_AI_ONLY_TRADES, false);
            openTradeRecords(true, onlyTrades);
        }
    }

    /**
     * 打开交易记录页，并可指定显示 AI 操作记录及其过滤状态
     */
    private void openTradeRecords(boolean showAiRecords, boolean onlyTrades) {
        if (showAiRecords) {
            // 直接定位到 AI 操作记录，避免先加载手动记录造成闪烁/空白感
            currentTradeTab = 1;
            if (cbShowOnlyAiTrades != null) {
                cbShowOnlyAiTrades.setChecked(onlyTrades);
            }
        }
        switchTab(2);
        if (showAiRecords) {
            // 同步 Tab 视觉状态和容器显示
            updateTradeTabVisuals(false);
            if (manualRecordsContainer != null) manualRecordsContainer.setVisibility(View.GONE);
            if (tvManualNoRecords != null) tvManualNoRecords.setVisibility(View.GONE);
            if (aiRecordsContainer != null) aiRecordsContainer.setVisibility(View.VISIBLE);
            if (tvAiNoRecords != null) tvAiNoRecords.setVisibility(View.VISIBLE);
            if (cbShowOnlyAiTrades != null) cbShowOnlyAiTrades.setVisibility(View.VISIBLE);
            if (btnExportAiRecords != null) btnExportAiRecords.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 更新交易记录子 Tab 的视觉状态（不触发点击事件）
     */
    private void updateTradeTabVisuals(boolean isManual) {
        if (tabTradeManual != null) {
            tabTradeManual.setBackgroundResource(isManual ? R.drawable.trade_tab_active : 0);
            tabTradeManual.setTextColor(isManual ? 0xFFFFFFFF : 0xFF6E6E7A);
            tabTradeManual.setTypeface(null, isManual ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        if (tabTradeAI != null) {
            tabTradeAI.setBackgroundResource(isManual ? 0 : R.drawable.trade_tab_active);
            tabTradeAI.setTextColor(isManual ? 0xFF6E6E7A : 0xFFFFFFFF);
            tabTradeAI.setTypeface(null, isManual ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
        }
    }

    /**
     * 选中交易记录子 Tab：0=手动记录，1=AI操作记录
     */
    private void selectTradeSubTab(int tab) {
        if (tab == 0 && tabTradeManual != null) {
            tabTradeManual.performClick();
        } else if (tab == 1 && tabTradeAI != null) {
            tabTradeAI.performClick();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Logger.info(this, "HomeActivity", "onResume called");
        try {
            // AI 已开启时自动拉起后台前台服务，避免必须点进 AI 页面才启动
            ensureAgentServiceIfRunning();
            updateAIStatusCard();
            // 延迟检查余额，避免与 Activity 初始化冲突
            handler.postDelayed(() -> {
                try {
                    Logger.info(HomeActivity.this, "HomeActivity", "Delayed updateLowBalanceCard");
                    updateLowBalanceCard();
                } catch (Exception e) {
                    Logger.error(HomeActivity.this, "HomeActivity", "updateLowBalanceCard failed: " + e.getMessage(), e);
                }
            }, 500);
        } catch (Exception e) {
            Logger.error(this, "HomeActivity", "onResume error: " + e.getMessage(), e);
        }
    }

    /**
     * 若用户此前已开启 AI（agent_running=true），自动启动 AgentForegroundService，
     * 保证 App 打开后 AI 立即在后台运行，无需手动点击 AI 按钮。
     */
    private void ensureAgentServiceIfRunning() {
        try {
            boolean agentRunning = getSharedPreferences("ai_agent_prefs", Context.MODE_PRIVATE)
                .getBoolean("agent_running", false);
            if (!agentRunning) return;
            android.content.Intent svcIntent = new android.content.Intent(this, AgentForegroundService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(svcIntent);
            } else {
                startService(svcIntent);
            }
            Logger.info(this, "HomeActivity", "AI 已开启，自动启动后台前台服务");
        } catch (Exception e) {
            Logger.warning(this, "HomeActivity", "自动启动 AI 服务失败: " + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        allWalletsExecutor.shutdownNow();
        marketExecutor.shutdownNow();
        aiRecordsExecutor.shutdownNow();
        handler.removeCallbacksAndMessages(null);
    }

    private void initViews() {
        try {
            tabHome = findViewById(R.id.tabHome);
            tabAssets = findViewById(R.id.tabAssets);
            tabTrade = findViewById(R.id.tabTrade);
            tabDiscover = findViewById(R.id.tabDiscover);
            tabSettings = findViewById(R.id.tabSettings);
            assetsSwipeRefresh = findViewById(R.id.assetsSwipeRefresh);
            marketListContainer = findViewById(R.id.marketListContainer);
            tvMarketLoading = findViewById(R.id.tvMarketLoading);
            manualRecordsContainer = findViewById(R.id.manualRecordsContainer);
            aiRecordsContainer = findViewById(R.id.aiRecordsContainer);
            tvManualNoRecords = findViewById(R.id.tvManualNoRecords);
            tvAiNoRecords = findViewById(R.id.tvAiNoRecords);
            cbShowOnlyAiTrades = findViewById(R.id.cbShowOnlyAiTrades);
            btnExportAiRecords = findViewById(R.id.btnExportAiRecords);
            tabTradeManual = findViewById(R.id.tabTradeManual);
            tabTradeAI = findViewById(R.id.tabTradeAI);
            tvMyWalletName = findViewById(R.id.tvMyWalletName);
            tvMyWalletAddress = findViewById(R.id.tvMyWalletAddress);
            
            // AI Status Card - may be null on old APK versions
            aiStatusCard = findViewById(R.id.aiStatusCard);
            lowBalanceCard = findViewById(R.id.lowBalanceCard);
            aiStatusDot = findViewById(R.id.aiStatusDot);
            tvAiStatusText = findViewById(R.id.tvAiStatusText);
            tvAiCardPnL = findViewById(R.id.tvAiCardPnL);
            tvAiCardWinRate = findViewById(R.id.tvAiCardWinRate);
            tvAiCardTrades = findViewById(R.id.tvAiCardTrades);
            tvAiCardChain = findViewById(R.id.tvAiCardChain);
            btnStartAIFromHome = findViewById(R.id.btnStartAIFromHome);
            btnAddFunds = findViewById(R.id.btnAddFunds);
            btnScrollToTop = findViewById(R.id.btnScrollToTop);

            // 钱包切换按钮
            View walletSwitcher = findViewById(R.id.walletSwitcher);
            if (walletSwitcher != null) {
                walletSwitcher.setOnClickListener(v -> {
                    Logger.action(this, "UI操作", "钱包切换器", null);
                    showWalletSwitcher();
                });
            }

            // 回到顶部按钮逻辑
            if (btnScrollToTop != null && tabAssets != null) {
                btnScrollToTop.setOnClickListener(v -> {
                    Logger.action(this, "UI操作", "回到顶部", null);
                    tabAssets.smoothScrollTo(0, 0);
                });
                tabAssets.getViewTreeObserver().addOnScrollChangedListener(() -> {
                    int scrollY = tabAssets.getScrollY();
                    btnScrollToTop.setVisibility(scrollY > 300 ? View.VISIBLE : View.GONE);
                });
            }

            // 行情页上滑加载更多
            if (tabHome != null) {
                tabHome.setOnScrollChangeListener((androidx.core.widget.NestedScrollView.OnScrollChangeListener) (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                    View child = tabHome.getChildAt(0);
                    if (child == null) return;
                    int bottom = child.getBottom();
                    int height = tabHome.getHeight();
                    if (scrollY + height >= bottom - 200 && !marketLoadingMore && marketHasMore) {
                        loadMarketPage(false);
                    }
                });
            }

            // 资产子Tab
            tabAssetsTab = findViewById(R.id.tabAssetsTab);
            tabDeFiTab = findViewById(R.id.tabDeFiTab);
            tabNftTab = findViewById(R.id.tabNftTab);
            tabApprovalsTab = findViewById(R.id.tabApprovalsTab);
            assetsContentArea = findViewById(R.id.assetsContentArea);
            nftContentArea = findViewById(R.id.nftContentArea);
            nftListContainer = findViewById(R.id.nftListContainer);
            deFiContainer = findViewById(R.id.deFiContainer);
            approvalsContainer = findViewById(R.id.approvalsContainer);
            networkWarningBanner = findViewById(R.id.networkWarningBanner);
            tvTodayPnL = findViewById(R.id.tvTodayPnL);
            tvDeFiLoading = findViewById(R.id.tvDeFiLoading);
            tvApprovalsLoading = findViewById(R.id.tvApprovalsLoading);
            smallAssetsContainer = findViewById(R.id.smallAssetsContainer);
            btnFoldSmallAssets = findViewById(R.id.btnFoldSmallAssets);
            initAssetSubTabs();

            Logger.info(this, "HomeActivity", String.format(
                "Views: home=%b assets=%b trade=%b discover=%b settings=%b refresh=%b market=%b manual=%b ai=%b",
                tabHome != null, tabAssets != null, tabTrade != null, tabDiscover != null, tabSettings != null,
                assetsSwipeRefresh != null, marketListContainer != null, manualRecordsContainer != null, aiRecordsContainer != null));
            Logger.info(this, "HomeActivity", String.format(
                "AI views: card=%b lowBal=%b dot=%b pnl=%b btn=%b",
                aiStatusCard != null, lowBalanceCard != null,
                aiStatusDot != null, tvAiCardPnL != null, btnStartAIFromHome != null));
        } catch (Exception e) {
            Logger.error(this, "HomeActivity", "initViews binding error: " + e.getMessage(), e);
        }

        // AI card buttons
        if (btnStartAIFromHome != null) {
            btnStartAIFromHome.setOnClickListener(v -> {
                Logger.action(this, "UI操作", "启动AI", null);
                try {
                    // Check AI is enabled
                    if (!WalletManager.isAIEnabled(this)) {
                        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                            .setTitle(getString(R.string.title_ai_assistant_is_not))
                            .setMessage(getString(R.string.msg_please_configure_and_enable))
                            .setPositiveButton(getString(R.string.label_to_set), (dialog, which) -> {
                                switchTab(4);
                            })
                            .setNegativeButton(getString(R.string.btn_s_decline), null)
                            .show();
                        return;
                    }
                    // 后台线程检查资产（避免主线程网络请求失败）
                    btnStartAIFromHome.setEnabled(false);
                    Toast.makeText(this, getString(R.string.toast_checking_assets), Toast.LENGTH_SHORT).show();
                    TradeAuthManager tam = new TradeAuthManager(this);
                    tam.checkInBackground(this, result -> {
                        btnStartAIFromHome.setEnabled(true);
                        if (result.allowed) {
                            startActivity(new Intent(HomeActivity.this, AIAgentActivity.class));
                        } else {
                            Toast.makeText(HomeActivity.this, result.reason, Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Exception e) {
                    btnStartAIFromHome.setEnabled(true);
                    Toast.makeText(this, getString(R.string.toast_failed_to_start_ai, e.getMessage()), Toast.LENGTH_SHORT).show();
                }
            });
        }
        if (btnAddFunds != null) {
            btnAddFunds.setOnClickListener(v -> {
                Logger.action(this, "UI操作", "充值", null);
                try {
                    // Navigate to receive page
                    Intent intent = new Intent(this, ReceiveActivity.class);
                    intent.putExtra("chain", WalletManager.getChain(this));
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, getString(R.string.toast_failed_to_open_payout), Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void initTabs() {
        View navAssets = findViewById(R.id.navAssets);
        View navHome = findViewById(R.id.navHome);
        View navTrade = findViewById(R.id.navTrade);
        View navDiscover = findViewById(R.id.navDiscover);
        View navSettings = findViewById(R.id.navSettings);
        if (navAssets != null) navAssets.setOnClickListener(v -> {
            Logger.action(this, "UI操作", "底部Tab-资产", null);
            switchTab(0);
        });
        if (navHome != null) navHome.setOnClickListener(v -> {
            Logger.action(this, "UI操作", "底部Tab-首页", null);
            switchTab(1);
        });
        // 底部中间"AI"按钮：直接跳转到 AI 智能体页面
        if (navTrade != null) navTrade.setOnClickListener(v -> {
            Logger.action(this, "UI操作", "底部Tab-交易", null);
            try {
                startActivity(new Intent(HomeActivity.this, AIAgentActivity.class));
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.toast_failed_to_open_ai, e.getMessage()), Toast.LENGTH_SHORT).show();
            }
        });
        if (navDiscover != null) navDiscover.setOnClickListener(v -> {
            Logger.action(this, "UI操作", "底部Tab-发现", null);
            switchTab(3);
        });
        if (navSettings != null) navSettings.setOnClickListener(v -> {
            Logger.action(this, "UI操作", "底部Tab-我的", null);
            switchTab(4);
        });
        if (assetsSwipeRefresh != null) {
            assetsSwipeRefresh.setOnRefreshListener(() -> {
                try {
                    Logger.info(this, "资产刷新", "用户下拉触发刷新");
                    loadAssets();
                } catch (Throwable t) {
                    Logger.error(this, "资产刷新", "下拉刷新异常: " + t.getClass().getName() + ": " + t.getMessage(), t);
                    if (assetsSwipeRefresh != null) {
                        assetsSwipeRefresh.setRefreshing(false);
                    }
                    Toast.makeText(HomeActivity.this, getString(R.string.toast_refresh_exception, t.getMessage()), Toast.LENGTH_SHORT).show();
                }
            });
            assetsSwipeRefresh.setColorSchemeResources(
                R.color.accent_blue, R.color.purple, R.color.green
            );
        } else {
            Logger.error(this, "HomeActivity", "assetsSwipeRefresh is null in initTabs", null);
        }
        switchTab(0);
    }

    private void switchTab(int index) {
        if (assetsSwipeRefresh != null) assetsSwipeRefresh.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        if (tabHome != null) tabHome.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        if (tabTrade != null) tabTrade.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        if (tabDiscover != null) tabDiscover.setVisibility(index == 3 ? View.VISIBLE : View.GONE);
        if (tabSettings != null) tabSettings.setVisibility(index == 4 ? View.VISIBLE : View.GONE);

        int[] iconIds = {R.id.iconAssets, R.id.iconHome, R.id.iconTrade, R.id.iconDiscover, R.id.iconSettings};
        int[] textIds = {R.id.textAssets, R.id.textHome, R.id.textTrade, R.id.textDiscover, R.id.textSettings};
        int activeColor = 0xFF2997F4;
        int inactiveColor = 0xFF6E6E7A;

        for (int i = 0; i < 5; i++) {
            TextView icon = findViewById(iconIds[i]);
            TextView text = findViewById(textIds[i]);
            if (icon != null) icon.setTextColor(i == index ? activeColor : inactiveColor);
            if (text != null) text.setTextColor(i == index ? activeColor : inactiveColor);
        }

        if (index == 0) {
            loadAssets();
            loadWalletInfo();
            updateAIStatusCard();
        }
        if (index == 1) {
            loadMarketData();
        }
        if (index == 2) {
            // 切换到交易页时根据当前 Tab 加载对应记录
            loadTradeRecords();
        }
        if (index == 4) {
            loadMyInfo();
        }

        Logger.actionResult(this, "UI操作", "切换Tab", "Tab" + index);
    }

    // ============================================================
    // 资产子Tab (Assets | DeFi | NFT | 授权)
    // ============================================================

    private void initAssetSubTabs() {
        if (tabAssetsTab != null) tabAssetsTab.setOnClickListener(v -> switchAssetSubTab(0));
        if (tabDeFiTab != null) tabDeFiTab.setOnClickListener(v -> switchAssetSubTab(1));
        if (tabNftTab != null) tabNftTab.setOnClickListener(v -> switchAssetSubTab(2));
        if (tabApprovalsTab != null) tabApprovalsTab.setOnClickListener(v -> switchAssetSubTab(3));
    }

    private void switchAssetSubTab(int tab) {
        currentAssetSubTab = tab;
        TextView[] tabs = {tabAssetsTab, tabDeFiTab, tabNftTab, tabApprovalsTab};
        for (int i = 0; i < tabs.length; i++) {
            if (tabs[i] != null) {
                boolean active = (i == tab);
                tabs[i].setTextColor(active ? 0xFFFFFFFF : 0xFF6E6E7A);
                tabs[i].setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            }
        }
        if (assetsContentArea != null) assetsContentArea.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        if (deFiContainer != null) deFiContainer.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        if (nftContentArea != null) nftContentArea.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        if (approvalsContainer != null) approvalsContainer.setVisibility(tab == 3 ? View.VISIBLE : View.GONE);

        if (tab == 1) loadDeFiData();
        if (tab == 2) loadNftTabData();
        if (tab == 3) loadApprovalsData();
    }

    private void loadDeFiData() {
        if (deFiContainer == null || tvDeFiLoading == null) return;
        executor.execute(() -> {
            try {
                String address = WalletManager.getWalletAddress(this);
                String chain = WalletManager.getChain(this);
                java.util.List<String[]> positions = ChainAPI.getDeFiPortfolio(this, chain, address);
                handler.post(() -> {
                    deFiContainer.removeAllViews();
                    if (positions.isEmpty()) {
                        TextView empty = new TextView(HomeActivity.this);
                        empty.setText(getString(R.string.text_no_defi_position_data));
                        empty.setTextColor(0xFF6E6E7A);
                        empty.setTextSize(14);
                        empty.setGravity(Gravity.CENTER);
                        empty.setPadding(0, dpToPx(32), 0, dpToPx(32));
                        deFiContainer.addView(empty);
                    } else {
                        for (String[] pos : positions) {
                            addDeFiItem(deFiContainer, pos);
                        }
                    }
                });
            } catch (Exception e) {
                handler.post(() -> {
                    deFiContainer.removeAllViews();
                    TextView err = new TextView(HomeActivity.this);
                    err.setText(getString(R.string.text_defi_data_loading_failed, e.getMessage()));
                    err.setTextColor(0xFF9B9BA7);
                    err.setTextSize(13);
                    err.setGravity(Gravity.CENTER);
                    err.setPadding(0, dpToPx(16), 0, dpToPx(16));
                    deFiContainer.addView(err);
                });
            }
        });
    }

    private void addDeFiItem(LinearLayout container, String[] pos) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));
        item.setBackgroundColor(0xFF121216);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dpToPx(6));
        item.setLayoutParams(lp);

        // pos[0]=协议名, pos[1]=仓位价值, pos[2]=币种明细, pos[3]=APR/APY
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(pos[0]);
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(14);
        name.setTypeface(null, Typeface.BOLD);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        top.addView(name);

        TextView value = new TextView(this);
        value.setText(pos[1]);
        value.setTextColor(0xFFFFFFFF);
        value.setTextSize(14);
        value.setTypeface(null, Typeface.BOLD);
        top.addView(value);
        item.addView(top);

        LinearLayout bot = new LinearLayout(this);
        bot.setOrientation(LinearLayout.HORIZONTAL);
        bot.setGravity(Gravity.CENTER_VERTICAL);
        bot.setPadding(0, dpToPx(4), 0, 0);

        TextView detail = new TextView(this);
        detail.setText(pos[2]);
        detail.setTextColor(0xFF9B9BA7);
        detail.setTextSize(12);
        detail.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        bot.addView(detail);

        if (pos.length > 3 && pos[3] != null && !pos[3].isEmpty()) {
            TextView apr = new TextView(this);
            apr.setText(pos[3]);
            apr.setTextColor(pos[3].startsWith("-") ? 0xFFE84D4D : 0xFF00D084);
            apr.setTextSize(12);
            bot.addView(apr);
        }
        item.addView(bot);
        container.addView(item);
    }

    private void loadNftTabData() {
        if (nftContentArea == null || nftListContainer == null) return;
        executor.execute(() -> {
            try {
                String address = WalletManager.getWalletAddress(this);
                String chain = WalletManager.getChain(this);
                java.util.List<String[]> nfts = ChainAPI.getNFTList(this, chain, address);
                handler.post(() -> {
                    View nfc = nftListContainer;
                    if (!(nfc instanceof LinearLayout)) return;
                    LinearLayout container = (LinearLayout) nfc;
                    container.removeAllViews();
                    if (nfts.isEmpty()) {
                        TextView empty = new TextView(HomeActivity.this);
                        empty.setText(getString(R.string.text_no_nfts));
                        empty.setTextColor(0xFF6E6E7A);
                        empty.setTextSize(14);
                        empty.setGravity(Gravity.CENTER);
                        empty.setPadding(0, dpToPx(32), 0, dpToPx(32));
                        container.addView(empty);
                    } else {
                        for (String[] nft : nfts) {
                            View item = getLayoutInflater().inflate(R.layout.item_nft, null);
                            ((TextView) item.findViewById(R.id.tvNftName)).setText(nft[1]);
                            ((TextView) item.findViewById(R.id.tvNftCollection)).setText(nft[3]);
                            ((TextView) item.findViewById(R.id.tvNftTokenId)).setText("#" + nft[2]);
                            ((TextView) item.findViewById(R.id.tvNftFloorPrice)).setText(nft[5]);
                            String imageUrl = nft[4];
                            if (imageUrl != null && !imageUrl.isEmpty()) {
                                ImageView iv = item.findViewById(R.id.ivNftImage);
                                if (iv != null) loadNftImage(iv, imageUrl);
                            }
                            container.addView(item);
                        }
                    }
                });
            } catch (Exception e) {
                handler.post(() -> {
                    View nfc = nftListContainer;
                    if (nfc instanceof LinearLayout) {
                        ((LinearLayout) nfc).removeAllViews();
                        TextView err = new TextView(HomeActivity.this);
                        err.setText(getString(R.string.text_nft_loading_failed));
                        err.setTextColor(0xFF9B9BA7);
                        err.setGravity(Gravity.CENTER);
                        err.setPadding(0, dpToPx(16), 0, dpToPx(16));
                        ((LinearLayout) nfc).addView(err);
                    }
                });
            }
        });
    }

    private void loadNftImage(ImageView iv, String imageUrl) {
        try {
            com.bumptech.glide.Glide.with(this)
                .load(imageUrl)
                .placeholder(0xFF1A1A1F)
                .error(0xFF1A1A1F)
                .into(iv);
        } catch (Exception ignored) {}
    }

    private static final String[][] KNOWN_SPENDERS = {
        {"0x7a250d5630B4cF539739dF2C5dAcb4c659F2488D", "Uniswap V2 Router"},
        {"0x10ED43C718714eb63d5aA57B78B54704E256024E", "PancakeSwap Router"},
        {"0x1111111254EEB25477B68fb85Ed929f73A960582", "1inch Router v5"},
        {"0xDef1C0ded9bec7F1a1670819833240f027b25EfF", "0x Router"},
        {"0x7D1AfA7B718fb893dB30A3aBc0Cfc608AaCfeBB0", "Polygon zkEVM Bridge"},
        {"0xe66B31678d6C16E9ebf358268a790B763C133750", "Multichain Router"},
    };

    private void loadApprovalsData() {
        if (approvalsContainer == null || tvApprovalsLoading == null) return;
        executor.execute(() -> {
            try {
                String address = WalletManager.getWalletAddress(this);
                String chain = WalletManager.getChain(this);
                java.util.List<String[]> approvals = ChainAPI.getTokenApprovals(this, chain, address);
                handler.post(() -> {
                    approvalsContainer.removeAllViews();
                    if (approvals.isEmpty()) {
                        TextView empty = new TextView(HomeActivity.this);
                        empty.setText(getString(R.string.text_token_authorization_not_detected));
                        empty.setTextColor(0xFF6E6E7A);
                        empty.setTextSize(14);
                        empty.setGravity(Gravity.CENTER);
                        empty.setPadding(0, dpToPx(32), 0, dpToPx(32));
                        approvalsContainer.addView(empty);
                    } else {
                        for (String[] app : approvals) {
                            addApprovalItem(approvalsContainer, app);
                        }
                    }
                });
            } catch (Exception e) {
                handler.post(() -> {
                    approvalsContainer.removeAllViews();
                    TextView err = new TextView(HomeActivity.this);
                    err.setText(getString(R.string.text_authorization_scan_failed, e.getMessage()));
                    err.setTextColor(0xFF9B9BA7);
                    err.setGravity(Gravity.CENTER);
                    err.setPadding(0, dpToPx(16), 0, dpToPx(16));
                    approvalsContainer.addView(err);
                });
            }
        });
    }

    private void addApprovalItem(LinearLayout container, String[] app) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));
        item.setBackgroundColor(0xFF121216);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dpToPx(4));
        item.setLayoutParams(lp);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView tokenName = new TextView(this);
        tokenName.setText(app[0]); // 代币符号
        tokenName.setTextColor(0xFFFFFFFF);
        tokenName.setTextSize(14);
        info.addView(tokenName);

        TextView spender = new TextView(this);
        spender.setText(app[3]); // 被授权合约标签
        spender.setTextColor(0xFF6E6E7A);
        spender.setTextSize(11);
        info.addView(spender);

        TextView amount = new TextView(this);
        amount.setText(getString(R.string.text_authorized, app[2])); // 已授权额度
        amount.setTextColor(0xFF9B9BA7);
        amount.setTextSize(11);
        info.addView(amount);

        item.addView(info);

        // app[0]=符号, app[1]=合约地址, app[2]=已授权额度, app[3]=被授权合约标签, app[4]=被授权地址
        TextView revokeBtn = new TextView(this);
        revokeBtn.setText(getString(R.string.text_undo));
        revokeBtn.setTextColor(0xFFE84D4D);
        revokeBtn.setTextSize(12);
        revokeBtn.setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6));
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setShape(GradientDrawable.RECTANGLE);
        btnBg.setColor(0x20E84D4D);
        btnBg.setCornerRadius(dpToPx(6));
        revokeBtn.setBackground(btnBg);
        revokeBtn.setOnClickListener(v -> {
            revokeApproval(app[0], app[1], app[4]);
        });
        item.addView(revokeBtn);
        container.addView(item);
    }

    private void revokeApproval(String symbol, String tokenContract, String spender) {
        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.title_revoke_authorisation))
            .setMessage(getString(R.string.msg_confirm_revoke_auth, symbol, spender.substring(0, 10)))
            .setPositiveButton(getString(R.string.str_confirm), (d, w) -> {
                Toast.makeText(this, getString(R.string.toast_undoing), Toast.LENGTH_SHORT).show();
                executor.execute(() -> {
                    try {
                        String chain = WalletManager.getChain(this);
                        String rpcUrl = ChainAPI.getDefaultRpc(this, chain);
                        String mnemonic = WalletManager.getMnemonic(this);
                        if (mnemonic == null || mnemonic.isEmpty()) {
                            handler.post(() -> Toast.makeText(this, getString(R.string.toast_unable_to_get_private), Toast.LENGTH_LONG).show());
                            return;
                        }
                        // 使用 ContractCaller 写入 approve(..., 0) 撤销授权
                        String txHash = ContractCaller.erc20Approve(this, chain, tokenContract, spender, BigInteger.ZERO, null);
                        handler.post(() -> {
                            Toast.makeText(this, getString(R.string.toast_auth_revoked, txHash.substring(0, 14)), Toast.LENGTH_LONG).show();
                            loadApprovalsData();
                        });
                    } catch (Exception e) {
                        handler.post(() -> Toast.makeText(this, getString(R.string.toast_undo_failed, e.getMessage()), Toast.LENGTH_LONG).show());
                    }
                });
            })
            .setNegativeButton(getString(R.string.btn_s_decline), null)
            .show();
    }

    // ============================================================
    // 网络检测与警告
    // ============================================================

    private void showNetworkWarning() {
        if (networkWarningBanner != null) {
            networkWarningBanner.setVisibility(View.VISIBLE);
            View btn = findViewById(R.id.btnDismissWarning);
            if (btn != null) btn.setOnClickListener(v -> networkWarningBanner.setVisibility(View.GONE));
        }
    }

    private void hideNetworkWarning() {
        if (networkWarningBanner != null) networkWarningBanner.setVisibility(View.GONE);
    }

    private void initMarketTabs() {
        final TextView tabAll = findViewById(R.id.tabMarketAll);
        final TextView tabGainers = findViewById(R.id.tabMarketGainers);
        final TextView tabLosers = findViewById(R.id.tabMarketLosers);

        if (tabAll == null || tabGainers == null || tabLosers == null) {
            Logger.error(this, "HomeActivity", "initMarketTabs: market tabs are null", null);
            return;
        }

        View.OnClickListener tabClickListener = v -> {
            int id = v.getId();
            if (id == R.id.tabMarketAll) {
                Logger.action(this, "UI操作", "行情-全部", null);
                marketTab = 0;
            } else if (id == R.id.tabMarketGainers) {
                Logger.action(this, "UI操作", "行情-涨幅榜", null);
                marketTab = 1;
            } else if (id == R.id.tabMarketLosers) {
                Logger.action(this, "UI操作", "行情-跌幅榜", null);
                marketTab = 2;
            }
            updateMarketTabUI(tabAll, tabGainers, tabLosers);
            // 切换Tab时重置并只加载前20
            loadMarketData();
        };

        tabAll.setOnClickListener(tabClickListener);
        tabGainers.setOnClickListener(tabClickListener);
        tabLosers.setOnClickListener(tabClickListener);
    }

    private void updateMarketTabUI(TextView tabAll, TextView tabGainers, TextView tabLosers) {
        tabAll.setTextColor(marketTab == 0 ? 0xFF2997F4 : 0xFF6E6E7A);
        tabAll.setBackgroundResource(marketTab == 0 ? R.drawable.tab_indicator_blue : 0);
        tabAll.setTextColor(marketTab == 0 ? 0xFF2997F4 : 0xFF6E6E7A);
        if (marketTab == 0) {
            tabAll.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            tabAll.setTypeface(null, android.graphics.Typeface.NORMAL);
        }

        tabGainers.setTextColor(marketTab == 1 ? 0xFF2997F4 : 0xFF6E6E7A);
        tabGainers.setBackgroundResource(marketTab == 1 ? R.drawable.tab_indicator_blue : 0);
        if (marketTab == 1) {
            tabGainers.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            tabGainers.setTypeface(null, android.graphics.Typeface.NORMAL);
        }

        tabLosers.setTextColor(marketTab == 2 ? 0xFF2997F4 : 0xFF6E6E7A);
        tabLosers.setBackgroundResource(marketTab == 2 ? R.drawable.tab_indicator_blue : 0);
        if (marketTab == 2) {
            tabLosers.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            tabLosers.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
    }

    /**
     * 加载行情数据（重置分页）。
     */
    private void loadMarketData() {
        marketCurrentPage = 0;
        marketAllCoins.clear();
        marketHasMore = true;
        loadMarketPage(true);
    }

    /**
     * 分页加载行情。
     * @param reset 是否重置列表（首次加载或切换 Tab）
     */
    private void loadMarketPage(boolean reset) {
        if (marketListContainer == null || tvMarketLoading == null) return;
        if (marketLoadingMore) return;

        if (reset) {
            marketCurrentPage = 0;
            marketAllCoins.clear();
            marketHasMore = true;
            // 先尝试显示缓存（秒开）
            boolean hasCache = renderMarketFromCache();
            if (hasCache) {
                tvMarketLoading.setVisibility(View.GONE);
                Logger.info(this, "行情", "显示缓存数据，后台刷新中");
            } else {
                marketListContainer.removeAllViews();
                tvMarketLoading.setVisibility(View.VISIBLE);
                tvMarketLoading.setText(getString(R.string.text_memuat));
            }
        } else {
            marketLoadingMore = true;
            // 添加底部 loading footer
            View footer = getLayoutInflater().inflate(R.layout.item_market_load_more, null);
            if (marketListContainer.indexOfChild(footer) < 0) {
                marketListContainer.addView(footer);
            }
        }

        marketExecutor.execute(() -> {
            try {
                Request request = new Request.Builder()
                    .url("https://api.gateio.ws/api/v4/spot/tickers")
                    .get()
                    .build();

                try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                    String body = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());

                    JSONArray arr = new JSONArray(body);
                    Map<String, JSONObject> tickerMap = new HashMap<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.getJSONObject(i);
                        String pair = obj.getString("currency_pair");
                        tickerMap.put(pair, obj);
                    }

                    List<MarketCoin> coins = new ArrayList<>();
                    JSONArray cacheArr = new JSONArray();
                    for (String pair : TARGET_PAIRS) {
                        JSONObject obj = tickerMap.get(pair);
                        if (obj == null) continue;
                        String symbol = pair.replace("_USDT", "");
                        double price = obj.optDouble("last", 0);
                        double changePercent = obj.optDouble("change_percentage", 0);
                        String name = COIN_NAMES.containsKey(symbol) ? COIN_NAMES.get(symbol) : symbol;
                        MarketCoin coin = new MarketCoin(symbol, name, price, changePercent);
                        coins.add(coin);

                        JSONObject c = new JSONObject();
                        c.put("symbol", symbol);
                        c.put("name", name);
                        c.put("price", price);
                        c.put("changePercent", changePercent);
                        cacheArr.put(c);
                    }

                    // 保存完整缓存
                    if (dataCache != null && reset) {
                        dataCache.saveMarketData(cacheArr.toString());
                    }

                    marketAllCoins.addAll(coins);

                    handler.post(() -> {
                        // 移除 loading footer
                        if (!reset) {
                            marketLoadingMore = false;
                            for (int i = marketListContainer.getChildCount() - 1; i >= 0; i--) {
                                View child = marketListContainer.getChildAt(i);
                                if (child.getTag() != null && "load_more_footer".equals(child.getTag())) {
                                    marketListContainer.removeViewAt(i);
                                }
                            }
                        }

                        if (reset) {
                            marketListContainer.removeAllViews();
                        }

                        // 先显示置顶币种
                        Set<String> pinned = getPinnedCoins();
                        List<MarketCoin> displayCoins = new ArrayList<>(marketAllCoins);
                        if (reset && marketTab == 0 && !pinned.isEmpty()) {
                            List<MarketCoin> pinnedCoins = new ArrayList<>();
                            List<MarketCoin> others = new ArrayList<>();
                            for (MarketCoin c : displayCoins) {
                                if (pinned.contains(c.symbol)) pinnedCoins.add(c);
                                else others.add(c);
                            }
                            displayCoins.clear();
                            displayCoins.addAll(pinnedCoins);
                            displayCoins.addAll(others);
                        }

                        // 排序
                        if (marketTab == 1) {
                            Collections.sort(displayCoins, (a, b) -> Double.compare(b.changePercent, a.changePercent));
                        } else if (marketTab == 2) {
                            Collections.sort(displayCoins, (a, b) -> Double.compare(a.changePercent, b.changePercent));
                        }

                        // 分页截取
                        int start = reset ? 0 : (marketCurrentPage * MARKET_PAGE_SIZE);
                        int end = Math.min(start + MARKET_PAGE_SIZE, displayCoins.size());
                        List<MarketCoin> pageCoins = displayCoins.subList(start, end);

                        for (int i = 0; i < pageCoins.size(); i++) {
                            MarketCoin coin = pageCoins.get(i);
                            int rank = reset ? (i + 1) : (marketListContainer.getChildCount() - countPinnedFirst(pinned, displayCoins) + i + 1);
                            addMarketItem(coin, rank);
                        }

                        marketCurrentPage++;
                        marketHasMore = end < displayCoins.size();

                        tvMarketLoading.setVisibility(View.GONE);
                        if (marketListContainer.getChildCount() == 0) {
                            tvMarketLoading.setVisibility(View.VISIBLE);
                            tvMarketLoading.setText(getString(R.string.text_no_nfts));
                        }
                        Logger.actionResult(HomeActivity.this, "UI操作", "行情刷新", "成功 共 " + marketAllCoins.size() + " 币种");
                    });
                }
            } catch (Exception e) {
                Logger.error(this, "行情", "加载失败: " + e.getMessage());
                handler.post(() -> {
                    marketLoadingMore = false;
                    Logger.actionResult(HomeActivity.this, "UI操作", "行情刷新", "失败");
                    for (int i = marketListContainer.getChildCount() - 1; i >= 0; i--) {
                        View child = marketListContainer.getChildAt(i);
                        if (child.getTag() != null && "load_more_footer".equals(child.getTag())) {
                            marketListContainer.removeViewAt(i);
                        }
                    }
                    if (marketListContainer.getChildCount() == 0) {
                        marketListContainer.removeAllViews();
                        tvMarketLoading.setVisibility(View.VISIBLE);
                        tvMarketLoading.setText(getString(R.string.text_nft_loading_failed));
                        tvMarketLoading.setOnClickListener(v -> loadMarketData());
                    }
                });
            }
        });
    }

    private int countPinnedFirst(Set<String> pinned, List<MarketCoin> coins) {
        if (marketTab != 0 || pinned.isEmpty()) return 0;
        int count = 0;
        for (MarketCoin c : coins) {
            if (pinned.contains(c.symbol)) count++;
        }
        return count;
    }

    private void addMarketItem(MarketCoin coin, int rank) {
        View item = getLayoutInflater().inflate(R.layout.item_market, null);

        TextView tvRank = item.findViewById(R.id.tvRank);
        ImageView ivCoinIcon = item.findViewById(R.id.ivCoinIcon);
        TextView tvCoinIcon = item.findViewById(R.id.tvCoinIcon);
        TextView tvCoinName = item.findViewById(R.id.tvCoinName);
        TextView tvCoinSymbol = item.findViewById(R.id.tvCoinSymbol);
        TextView tvPrice = item.findViewById(R.id.tvPrice);
        TextView tvChange = item.findViewById(R.id.tvChange);

        tvRank.setText(String.valueOf(rank));
        tvCoinName.setText(coin.name);
        tvCoinSymbol.setText(coin.symbol);
        tvPrice.setText(formatPrice(coin.price));

        // 加载开源 LOGO，失败时用首字母占位
        TokenLogoLoader.load(this, ivCoinIcon, coin.symbol, "", tvCoinIcon);

        String changeText = (coin.changePercent >= 0 ? "+" : "") + formatPercent(coin.changePercent);
        tvChange.setText(changeText);
        if (coin.changePercent >= 0) {
            tvChange.setBackgroundResource(R.drawable.bg_green);
        } else {
            tvChange.setBackgroundResource(R.drawable.bg_red);
        }

        // 长按置顶/取消置顶
        item.setOnLongClickListener(v -> {
            togglePinCoin(coin.symbol);
            return true;
        });

        item.setOnClickListener(v -> {
            Logger.action(HomeActivity.this, "UI操作", "行情项-" + coin.name, null);
            try {
                Intent intent = new Intent(HomeActivity.this, CoinDetailActivity.class);
                intent.putExtra(CoinDetailActivity.EXTRA_SYMBOL, coin.symbol);
                intent.putExtra(CoinDetailActivity.EXTRA_NAME, coin.name);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(HomeActivity.this, getString(R.string.toast_failed_to_open_ai, e.getMessage()), Toast.LENGTH_SHORT).show();
            }
        });

        marketListContainer.addView(item);
    }

    private void togglePinCoin(String symbol) {
        Set<String> pinned = getPinnedCoins();
        boolean added;
        if (pinned.contains(symbol)) {
            pinned.remove(symbol);
            added = false;
        } else {
            pinned.add(symbol);
            added = true;
        }
        savePinnedCoins(pinned);
        Toast.makeText(this, added ? getString(R.string.toast_pinned_post) + symbol : getString(R.string.btn_unpinned) + symbol, Toast.LENGTH_SHORT).show();
        loadMarketData();
    }

    private Set<String> getPinnedCoins() {
        SharedPreferences prefs = getSharedPreferences(PREFS_MARKET, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_PINNED_COINS, "");
        Set<String> set = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) return set;
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) set.add(t.toUpperCase());
        }
        return set;
    }

    private void savePinnedCoins(Set<String> pinned) {
        SharedPreferences prefs = getSharedPreferences(PREFS_MARKET, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_PINNED_COINS, TextUtils.join(",", pinned)).apply();
    }

    /** 从缓存渲染行情数据，返回是否成功显示了缓存。只渲染第一页 + 置顶币种。 */
    private boolean renderMarketFromCache() {
        if (dataCache == null || !dataCache.hasMarketCache()) return false;
        try {
            String json = dataCache.getCachedMarketData();
            if (json == null || json.isEmpty()) return false;
            JSONArray arr = new JSONArray(json);
            if (arr.length() == 0) return false;

            List<MarketCoin> allCoins = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String symbol = obj.optString("symbol", "");
                String name = obj.optString("name", symbol);
                double price = obj.optDouble("price", 0);
                double changePercent = obj.optDouble("changePercent", 0);
                allCoins.add(new MarketCoin(symbol, name, price, changePercent));
            }

            Set<String> pinned = getPinnedCoins();
            if (marketTab == 0 && !pinned.isEmpty()) {
                List<MarketCoin> pinnedCoins = new ArrayList<>();
                List<MarketCoin> others = new ArrayList<>();
                for (MarketCoin c : allCoins) {
                    if (pinned.contains(c.symbol)) pinnedCoins.add(c);
                    else others.add(c);
                }
                allCoins.clear();
                allCoins.addAll(pinnedCoins);
                allCoins.addAll(others);
            }

            if (marketTab == 1) {
                Collections.sort(allCoins, (a, b) -> Double.compare(b.changePercent, a.changePercent));
            } else if (marketTab == 2) {
                Collections.sort(allCoins, (a, b) -> Double.compare(a.changePercent, b.changePercent));
            }

            marketListContainer.removeAllViews();
            tvMarketLoading.setVisibility(View.GONE);
            int end = Math.min(MARKET_PAGE_SIZE, allCoins.size());
            for (int i = 0; i < end; i++) {
                addMarketItem(allCoins.get(i), i + 1);
            }
            Logger.info(this, "行情", "缓存渲染完成: " + allCoins.size() + " 币种，本页 " + end + " 个");
            return true;
        } catch (Exception e) {
            Logger.error(this, "行情", "缓存渲染失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 解析资产价值字符串（兼容任意货币符号、千分位和小数点）
     */
    private double parseAssetValue(String valueStr) {
        if (valueStr == null || valueStr.isEmpty()) return 0.0;
        try {
            String cleaned = valueStr.replaceAll("[^0-9\\.\\-]", "").trim();
            return Double.parseDouble(cleaned);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private String formatPrice(double price) {
        double converted = CurrencyManager.convertFromUsd(this, price);
        DecimalFormat df;
        if (converted >= 1) {
            df = new DecimalFormat("#,##0.00");
        } else if (converted >= 0.01) {
            df = new DecimalFormat("0.0000");
        } else {
            df = new DecimalFormat("0.000000");
        }
        return CurrencyManager.getCurrencySymbol(CurrencyManager.getSelectedCurrency(this)) + df.format(converted);
    }

    private String formatPercent(double percent) {
        DecimalFormat df = new DecimalFormat("0.00");
        return df.format(percent) + "%";
    }

    public static class MarketCoin {
        String symbol;
        String name;
        double price;
        double changePercent;

        MarketCoin(String symbol, String name, double price, double changePercent) {
            this.symbol = symbol;
            this.name = name;
            this.price = price;
            this.changePercent = changePercent;
        }
    }

    private void initQuickActions() {
        View.OnClickListener receiveListener = v -> {
            Logger.action(this, "UI操作", "接收", null);
            Intent intent = new Intent(this, ReceiveActivity.class);
            intent.putExtra("chain", WalletManager.getChain(this));
            startActivity(intent);
        };
        View btnReceive = findViewById(R.id.btnReceiveAssets);
        if (btnReceive != null) btnReceive.setOnClickListener(receiveListener);

        View.OnClickListener sendListener = v -> {
            Logger.action(this, "UI操作", "发送", null);
            Intent intent = new Intent(this, SendActivity.class);
            intent.putExtra("symbol", WalletManager.getChain(this));
            intent.putExtra("name", ChainAPI.getChainName(WalletManager.getChain(this)));
            intent.putExtra("balance", "0");
            intent.putExtra("value", CurrencyManager.formatFiat(this, 0));
            intent.putExtra("contract", "");
            startActivity(intent);
        };
        View btnSend = findViewById(R.id.btnSendAssets);
        if (btnSend != null) btnSend.setOnClickListener(sendListener);

        View.OnClickListener swapListener = v -> {
            Logger.action(this, "UI操作", "兑换", null);
            Intent intent = new Intent(this, SwapActivity.class);
            startActivity(intent);
        };
        View btnSwap = findViewById(R.id.btnSwapAssets);
        if (btnSwap != null) btnSwap.setOnClickListener(swapListener);

        // 顶部"交易"快捷按钮：直接打开 AI 操作记录页
        View.OnClickListener tradeListener = v -> {
            Logger.action(this, "UI操作", "AI交易", null);
            openTradeRecords(true, false);
        };
        View btnAI = findViewById(R.id.btnAIAgentAssets);
        if (btnAI != null) btnAI.setOnClickListener(tradeListener);

        View btnCopy = findViewById(R.id.btnCopyAddress);
        if (btnCopy != null) btnCopy.setOnClickListener(v -> {
            Logger.action(this, "UI操作", "复制地址", null);
            String address = WalletManager.getWalletAddress(this);
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("address", address));
            Toast.makeText(this, getString(R.string.toast_address_copied), Toast.LENGTH_SHORT).show();
        });

        View.OnClickListener toggleBalanceListener = v -> {
            Logger.action(this, "UI操作", "余额显示切换", null);
            toggleBalanceVisibility();
        };
        View btnToggle = findViewById(R.id.btnToggleBalanceAssets);
        if (btnToggle != null) btnToggle.setOnClickListener(toggleBalanceListener);

        View btnAddToken = findViewById(R.id.btnAddToken);
        if (btnAddToken != null) btnAddToken.setOnClickListener(v -> {
            Logger.action(this, "UI操作", "添加代币", null);
            showAddTokenDialog();
        });

    }

    private void initTradeTab() {
        if (tabTradeManual != null && tabTradeAI != null) {
            View.OnClickListener tabSwitcher = v -> {
                boolean isManual = (v == tabTradeManual);
                Logger.action(this, "UI操作", isManual ? "交易-手动记录" : "交易-AI记录", null);
                currentTradeTab = isManual ? 0 : 1;
                // 切换 Tab 视觉样式
                tabTradeManual.setBackgroundResource(isManual ? R.drawable.trade_tab_active : 0);
                tabTradeManual.setTextColor(isManual ? 0xFFFFFFFF : 0xFF6E6E7A);
                tabTradeManual.setTypeface(null, isManual ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                tabTradeAI.setBackgroundResource(isManual ? 0 : R.drawable.trade_tab_active);
                tabTradeAI.setTextColor(isManual ? 0xFF6E6E7A : 0xFFFFFFFF);
                tabTradeAI.setTypeface(null, isManual ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
                // 切换容器显示
                if (manualRecordsContainer != null) manualRecordsContainer.setVisibility(isManual ? View.VISIBLE : View.GONE);
                if (tvManualNoRecords != null) tvManualNoRecords.setVisibility(isManual ? View.VISIBLE : View.GONE);
                if (aiRecordsContainer != null) aiRecordsContainer.setVisibility(isManual ? View.GONE : View.VISIBLE);
                if (tvAiNoRecords != null) tvAiNoRecords.setVisibility(isManual ? View.GONE : View.VISIBLE);
                if (cbShowOnlyAiTrades != null) cbShowOnlyAiTrades.setVisibility(isManual ? View.GONE : View.VISIBLE);
                if (btnExportAiRecords != null) btnExportAiRecords.setVisibility(isManual ? View.GONE : View.VISIBLE);
                // 切换时按需加载
                if (isManual) {
                    loadManualRecords();
                } else {
                    loadAIRecords();
                }
            };
            tabTradeManual.setOnClickListener(tabSwitcher);
            tabTradeAI.setOnClickListener(tabSwitcher);
            // 勾选框：只显示 AI 交易记录
            if (cbShowOnlyAiTrades != null) {
                cbShowOnlyAiTrades.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    Logger.action(this, "UI操作", "AI记录-" + (isChecked ? "仅交易" : "全部"), null);
                    loadAIRecords();
                });
            }
            // 导出 AI 操作记录
            if (btnExportAiRecords != null) {
                btnExportAiRecords.setOnClickListener(v -> exportAIRecords());
            }
            // 默认显示手动记录
            currentTradeTab = 0;
        }
    }

    private void initDiscoverTab() {
        View.OnClickListener openBrowser = v -> {
            try {
                startActivity(new Intent(HomeActivity.this, DAppBrowserActivity.class));
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.toast_dapp_browser_failed_to), Toast.LENGTH_SHORT).show();
            }
        };

        // 搜索栏：输入 URL 直接打开，输入关键词走搜索引擎
        EditText etSearch = findViewById(R.id.etDappSearch);
        View btnSearchGo = findViewById(R.id.btnDappSearchGo);
        View btnScan = findViewById(R.id.btnDappScan);

        // 智能识别 URL / 关键词
        final View.OnClickListener searchAction = v -> {
            Logger.action(this, "UI操作", "搜索", null);
            if (etSearch == null) return;
            String input = etSearch.getText().toString().trim();
            if (input.isEmpty()) {
                Toast.makeText(this, getString(R.string.toast_please_enter_url_or), Toast.LENGTH_SHORT).show();
                return;
            }
            String targetUrl = smartParseInput(input);
            try {
                Intent intent = new Intent(HomeActivity.this, DAppBrowserActivity.class);
                intent.putExtra("url", targetUrl);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.toast_failed_to_open_ai, e.getMessage()), Toast.LENGTH_SHORT).show();
            }
        };
        if (btnSearchGo != null) btnSearchGo.setOnClickListener(searchAction);
        if (etSearch != null) {
            etSearch.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP)) {
                    searchAction.onClick(v);
                    return true;
                }
                return false;
            });
        }
        if (btnScan != null) btnScan.setOnClickListener(v -> {
            Logger.action(this, "UI操作", "扫码", null);
            Toast.makeText(this, getString(R.string.toast_scanning_function_under_development), Toast.LENGTH_SHORT).show();
        });

        final TextView tabHot = findViewById(R.id.tabDiscoverHot);
        final TextView tabExplore = findViewById(R.id.tabDiscoverExplore);
        final TextView tabMine = findViewById(R.id.tabDiscoverMine);
        final LinearLayout gridHot = findViewById(R.id.dappGridHot);
        final LinearLayout gridExplore = findViewById(R.id.dappGridExplore);
        gridMine = findViewById(R.id.dappGridMine);
        if (tabHot != null && tabExplore != null && tabMine != null) {
            View.OnClickListener tabSwitcher = v -> {
                if (v == tabHot) Logger.action(this, "UI操作", "发现-热门", null);
                else if (v == tabExplore) Logger.action(this, "UI操作", "发现-跨链", null);
                else if (v == tabMine) Logger.action(this, "UI操作", "发现-我的", null);
                int activeColor = 0xFFFFFFFF;
                int inactiveColor = 0xFF6E6E7A;
                int activeSize = 20;
                int inactiveSize = 18;
                tabHot.setTextColor(v == tabHot ? activeColor : inactiveColor);
                tabExplore.setTextColor(v == tabExplore ? activeColor : inactiveColor);
                tabMine.setTextColor(v == tabMine ? activeColor : inactiveColor);
                tabHot.setTextSize(v == tabHot ? activeSize : inactiveSize);
                tabExplore.setTextSize(v == tabExplore ? activeSize : inactiveSize);
                tabMine.setTextSize(v == tabMine ? activeSize : inactiveSize);
                tabHot.setTypeface(null, v == tabHot ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                tabExplore.setTypeface(null, v == tabExplore ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                tabMine.setTypeface(null, v == tabMine ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                if (gridHot != null) gridHot.setVisibility(v == tabHot ? View.VISIBLE : View.GONE);
                if (gridExplore != null) gridExplore.setVisibility(v == tabExplore ? View.VISIBLE : View.GONE);
                if (gridMine != null) {
                    gridMine.setVisibility(v == tabMine ? View.VISIBLE : View.GONE);
                    if (v == tabMine) refreshMineDApps();
                }
            };
            tabHot.setOnClickListener(tabSwitcher);
            tabExplore.setOnClickListener(tabSwitcher);
            tabMine.setOnClickListener(tabSwitcher);
        } else {
            Logger.error(this, "HomeActivity", "initDiscoverTab: discover tabs are null", null);
        }

        // 跨链 DApp 列表
        String[][] crossChainDapps = {
            {"Transit", "⇄", "dapp_icon_bg_dark", "https://swap.transit.finance"},
            {"Stargate", "S", "dapp_icon_bg_blue", "https://stargate.finance"},
            {"deBridge", "dB", "dapp_icon_bg_purple", "https://debridge.finance"},
            {"Across", "A", "dapp_icon_bg_green", "https://app.across.to"},
            {"Orbiter", "O", "dapp_icon_bg_coral", "https://www.orbiter.finance"},
            {"Hop", "H", "dapp_icon_bg_teal", "https://app.hop.exchange"},
            {"Synapse", "Sy", "dapp_icon_bg_red", "https://synapseprotocol.com"},
            {"cBridge", "cB", "dapp_icon_bg_yellow", "https://cbridge.celer.network"},
            {"Wormhole", "W", "dapp_icon_bg_darkblue", "https://portalbridge.com"},
            {"LI.FI", "LI", "dapp_icon_bg_pink", "https://li.fi"},
            {"Bungee", "Bu", "dapp_icon_bg_blue", "https://bungee.exchange"},
            {"Rango", "R", "dapp_icon_bg_green", "https://app.rango.exchange"},
            {"Squid", "Sq", "dapp_icon_bg_purple", "https://app.squidrouter.com"},
            {"Relay", "Re", "dapp_icon_bg_coral", "https://relay.link"},
            {"Jumper", "J", "dapp_icon_bg_teal", "https://jumper.exchange"}
        };
        if (gridExplore != null) {
            populateDAppGrid(gridExplore, crossChainDapps);
        }

        int[] dappIds = {
            R.id.dappUniswap, R.id.dappPancake, R.id.dappAave, R.id.dappVenus, R.id.dappGMGN,
            R.id.dappQuickSwap, R.id.dappSushi, R.id.dappCurve, R.id.dappLido, R.id.dappOpenDApp,
            R.id.dappTransit
        };
        String[] dappUrls = {
            "https://app.uniswap.org", "https://pancakeswap.finance",
            "https://app.aave.com", "https://app.venus.io", "https://gmgn.ai",
            "https://quickswap.exchange", "https://app.sushi.com",
            "https://curve.fi", "https://lido.fi", null,
            "https://swap.transit.finance"
        };
        for (int i = 0; i < dappIds.length; i++) {
            final String url = dappUrls[i];
            View dappBtn = findViewById(dappIds[i]);
            if (dappBtn == null) {
                Logger.error(this, "HomeActivity", "initDiscoverTab: dapp button " + dappIds[i] + " is null", null);
                continue;
            }
            dappBtn.setOnClickListener(v -> {
                Logger.action(this, "UI操作", "DApp入口", null);
                if (url == null) {
                    openBrowser.onClick(v);
                } else {
                    try {
                        Intent intent = new Intent(HomeActivity.this, DAppBrowserActivity.class);
                        intent.putExtra("url", url);
                        startActivity(intent);
                    } catch (Exception e) {
                        openBrowser.onClick(v);
                    }
                }
            });
        }

        int[] earnIds = {R.id.dappEarnUniswap, R.id.dappEarnVenus, R.id.dappEarnPancake, R.id.dappEarnAave};
        String[] earnUrls = {"https://app.uniswap.org/#/pool", "https://app.venus.io", "https://pancakeswap.finance/pools", "https://app.aave.com"};
        for (int i = 0; i < earnIds.length; i++) {
            final String url = earnUrls[i];
            View earnBtn = findViewById(earnIds[i]);
            if (earnBtn == null) continue;
            earnBtn.setOnClickListener(v -> {
                Logger.action(this, "UI操作", "理财入口", null);
                try {
                    Intent intent = new Intent(HomeActivity.this, DAppBrowserActivity.class);
                    intent.putExtra("url", url);
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, getString(R.string.toast_failed_to_open_ai_2), Toast.LENGTH_SHORT).show();
                }
            });
        }

        View dappAIAgent = findViewById(R.id.dappAIAgent);
        if (dappAIAgent != null) dappAIAgent.setOnClickListener(v -> {
            Logger.action(this, "UI操作", "AI智能体", null);
            try {
                boolean aiEnabled = WalletManager.isAIEnabled(this);
                if (!aiEnabled) {
                    new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                        .setTitle(getString(R.string.title_ai_assistant_is_not))
                        .setMessage(getString(R.string.msg_please_configure_and_enable))
                        .setPositiveButton(getString(R.string.label_to_set), (dialog, which) -> {
                            switchTab(4);
                        })
                        .setNegativeButton(getString(R.string.btn_s_decline), null)
                        .show();
                } else {
                    startActivity(new Intent(HomeActivity.this, AIAgentActivity.class));
                }
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.toast_failed_to_open_ai_2), Toast.LENGTH_SHORT).show();
            }
        });

        View dappAISwap = findViewById(R.id.dappAISwap);
        if (dappAISwap != null) dappAISwap.setOnClickListener(v -> {
            Logger.action(this, "UI操作", "AI兑换", null);
            try {
                startActivity(new Intent(HomeActivity.this, SwapActivity.class));
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.toast_failed_to_open_redemption), Toast.LENGTH_SHORT).show();
            }
        });

        View dappAIMeme = findViewById(R.id.dappAIMeme);
        if (dappAIMeme != null) dappAIMeme.setOnClickListener(v -> {
            Logger.action(this, "UI操作", "AI Meme", null);
            Toast.makeText(this, getString(R.string.toast_meme_analysis_function_is), Toast.LENGTH_SHORT).show();
        });
        View dappAINews = findViewById(R.id.dappAINews);
        if (dappAINews != null) dappAINews.setOnClickListener(v -> {
            Logger.action(this, "UI操作", "AI资讯", null);
            Toast.makeText(this, getString(R.string.toast_on_chain_intelligence_in), Toast.LENGTH_SHORT).show();
        });
    }

    /** 动态填充 DApp 网格 */
    private void populateDAppGrid(LinearLayout container, String[][] dapps) {
        container.removeAllViews();
        int cols = 5;
        LinearLayout row = null;
        for (int i = 0; i < dapps.length; i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) rowParams.topMargin = dpToPx(4);
                container.addView(row, rowParams);
            }
            String name = dapps[i][0];
            String icon = dapps[i][1];
            String bgRes = dapps[i][2];
            String url = dapps[i][3];

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(android.view.Gravity.CENTER);
            int pad = dpToPx(8);
            item.setPadding(pad, pad, pad, pad);
            item.setClickable(true);
            item.setFocusable(true);
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            item.setLayoutParams(itemParams);

            TextView iconView = new TextView(this);
            iconView.setText(icon);
            iconView.setTextColor(0xFFFFFFFF);
            iconView.setTextSize(22);
            iconView.setGravity(android.view.Gravity.CENTER);
            int iconSize = dpToPx(52);
            iconView.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
            int bgId = getResources().getIdentifier(bgRes, "drawable", getPackageName());
            if (bgId != 0) iconView.setBackgroundResource(bgId);

            TextView nameView = new TextView(this);
            nameView.setText(name);
            nameView.setTextColor(0xFFFFFFFF);
            nameView.setTextSize(12);
            nameView.setMaxLines(1);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nameParams.topMargin = dpToPx(6);
            nameView.setLayoutParams(nameParams);

            item.addView(iconView);
            item.addView(nameView);
            item.setOnClickListener(v -> {
                recordDAppUsage(name, url);
                try {
                    Intent intent = new Intent(HomeActivity.this, DAppBrowserActivity.class);
                    intent.putExtra("url", url);
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, getString(R.string.toast_failed_to_open_ai_2), Toast.LENGTH_SHORT).show();
                }
            });
            row.addView(item);
        }
    }

    /** 记录 DApp 使用 */
    private void recordDAppUsage(String name, String url) {
        SharedPreferences prefs = getSharedPreferences("dapp_usage", MODE_PRIVATE);
        String existing = prefs.getString("used_dapps", "");
        String entry = name + "|" + url;
        if (!existing.contains(entry)) {
            String updated = existing.isEmpty() ? entry : existing + "," + entry;
            prefs.edit().putString("used_dapps", updated).apply();
        }
    }

    /** 刷新"我的"Tab */
    private void refreshMineDApps() {
        if (gridMine == null) return;
        SharedPreferences prefs = getSharedPreferences("dapp_usage", MODE_PRIVATE);
        String used = prefs.getString("used_dapps", "");
        gridMine.removeAllViews();
        if (used.isEmpty()) {
            TextView tvEmpty = new TextView(this);
            tvEmpty.setText(getString(R.string.text_no_nfts));
            tvEmpty.setTextColor(0xFF6E6E7A);
            tvEmpty.setTextSize(14);
            tvEmpty.setGravity(android.view.Gravity.CENTER);
            tvEmpty.setPadding(0, dpToPx(40), 0, 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            gridMine.addView(tvEmpty, params);
            return;
        }
        String[] entries = used.split(",");
        String[][] mineDapps = new String[entries.length][4];
        for (int i = 0; i < entries.length; i++) {
            String[] parts = entries[i].split("\\|");
            mineDapps[i][0] = parts[0];
            mineDapps[i][1] = parts[0].substring(0, 1);
            mineDapps[i][2] = "dapp_icon_bg_dark";
            mineDapps[i][3] = parts.length > 1 ? parts[1] : "";
        }
        populateDAppGrid(gridMine, mineDapps);
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void initMyPage() {
        int[] myMenuIds = {
            R.id.myMenuAssets, R.id.myMenuWallets, R.id.myMenuRecords, R.id.myMenuSecurity,
            R.id.myMenuAIModel, R.id.myMenuLanguage, R.id.myMenuCurrency, R.id.myMenuTheme, R.id.myMenuAbout
        };
        for (int id : myMenuIds) {
            View v = findViewById(id);
            if (v == null) {
                Logger.error(this, "HomeActivity", "initMyPage: menu item " + id + " is null", null);
                continue;
            }
            if (id == R.id.myMenuAssets) {
                v.setOnClickListener(v2 -> {
                    Logger.action(this, "UI操作", "我的-资产总览", null);
                    startActivity(new Intent(HomeActivity.this, AssetOverviewActivity.class));
                });
            } else if (id == R.id.myMenuWallets) {
                v.setOnClickListener(v2 -> {
                    Logger.action(this, "UI操作", "我的-钱包管理", null);
                    startActivity(new Intent(HomeActivity.this, WalletManagementActivity.class));
                });
            } else if (id == R.id.myMenuRecords) {
                v.setOnClickListener(v2 -> {
                    Logger.action(this, "UI操作", "我的-交易", null);
                    openTradeRecords(true, false);
                });
            } else if (id == R.id.myMenuSecurity) {
                v.setOnClickListener(v2 -> {
                    Logger.action(this, "UI操作", "我的-安全中心", null);
                    showSecurityCenter();
                });
            } else if (id == R.id.myMenuAIModel) {
                v.setOnClickListener(v2 -> {
                    Logger.action(this, "UI操作", "我的-AI模型配置", null);
                    startActivity(new Intent(HomeActivity.this, ModelConfigActivity.class));
                });
            } else if (id == R.id.myMenuLanguage) {
                v.setOnClickListener(v2 -> {
                    Logger.action(this, "UI操作", "我的-语言", null);
                    showLanguageDialog();
                });
            } else if (id == R.id.myMenuCurrency) {
                v.setOnClickListener(v2 -> {
                    Logger.action(this, "UI操作", "我的-货币单位", null);
                    showCurrencyDialog();
                });
            } else if (id == R.id.myMenuTheme) {
                v.setOnClickListener(v2 -> {
                    Logger.action(this, "UI操作", "我的-主题模式", null);
                    showThemeDialog();
                });
            } else if (id == R.id.myMenuAbout) {
                v.setOnClickListener(v2 -> {
                    Logger.action(this, "UI操作", "我的-关于", null);
                    showAboutDialog();
                });
            }
        }
        // 更新当前模型显示
        updateCurrentModelDisplay();
        // 更新当前主题显示
        updateCurrentThemeDisplay();
        // 更新当前语言显示
        updateCurrentLanguageDisplay();
        // 更新当前货币单位显示
        updateCurrentCurrencyDisplay();
        // 更新资产卡片标题中的货币单位
        updateAssetCardTitle();
    }

    private void updateCurrentThemeDisplay() {
        TextView tvCurrentTheme = findViewById(R.id.tvCurrentThemeMode);
        if (tvCurrentTheme == null) return;
        tvCurrentTheme.setText(ThemeManager.getCurrentModeLabel(this));
    }

    private void showLanguageDialog() {
        String current = LocaleManager.getSelectedLanguageLabel(this);
        String[] languages = LocaleManager.SUPPORTED_LANGUAGES;
        int checked = 0;
        for (int i = 0; i < languages.length; i++) {
            if (languages[i].equals(current)) {
                checked = i;
                break;
            }
        }
        final int[] checkedItem = {checked};

        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.str_select_language))
            .setSingleChoiceItems(languages, checked, (dialog, which) -> {
                checkedItem[0] = which;
            })
            .setPositiveButton(getString(R.string.str_confirm), (dialog, which) -> {
                String selected = languages[checkedItem[0]];
                if (!selected.equals(current)) {
                    LocaleManager.setSelectedLanguage(this, selected);
                    LocaleManager.applyLocale(this);
                    updateCurrentLanguageDisplay();
                    Logger.action(this, "UI操作", "语言切换", selected);
                    // 重启应用以全局应用语言
                    Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        finish();
                    }
                }
            })
            .setNegativeButton(getString(R.string.str_s_decline), null)
            .show();
    }

    private void showCurrencyDialog() {
        String current = CurrencyManager.getSelectedCurrency(this);
        String[] currencies = CurrencyManager.SUPPORTED_CURRENCIES;
        int checked = 0;
        for (int i = 0; i < currencies.length; i++) {
            if (currencies[i].equals(current)) {
                checked = i;
                break;
            }
        }
        final int[] checkedItem = {checked};

        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.str_select_currency))
            .setSingleChoiceItems(currencies, checked, (dialog, which) -> {
                checkedItem[0] = which;
            })
            .setPositiveButton(getString(R.string.str_confirm), (dialog, which) -> {
                String selected = currencies[checkedItem[0]];
                if (!selected.equals(current)) {
                    CurrencyManager.setSelectedCurrency(this, selected);
                    CurrencyManager.refreshRatesAsync(this);
                    updateCurrentCurrencyDisplay();
                    updateAssetCardTitle();
                    refreshCurrencyDisplays();
                    Logger.action(this, "UI操作", "货币单位切换", selected);
                }
            })
            .setNegativeButton(getString(R.string.str_s_decline), null)
            .show();
    }

    private void updateCurrentLanguageDisplay() {
        TextView tv = findViewById(R.id.tvCurrentLanguage);
        if (tv == null) return;
        tv.setText(LocaleManager.getSelectedLanguageLabel(this));
    }

    private void updateCurrentCurrencyDisplay() {
        TextView tv = findViewById(R.id.tvCurrentCurrency);
        if (tv == null) return;
        tv.setText(CurrencyManager.getSelectedCurrency(this));
    }

    private void updateAssetCardTitle() {
        TextView tv = findViewById(R.id.tvAssetCardTitle);
        if (tv == null) return;
        String currency = CurrencyManager.getSelectedCurrency(this);
        tv.setText(getString(R.string.str_current_wallet, currency));
    }

    /**
     * 货币单位切换后刷新首页所有法币显示
     */
    private void refreshCurrencyDisplays() {
        TextView tvTotal = findViewById(R.id.tvTotalBalanceAssets);
        if (tvTotal != null && balanceVisible) {
            tvTotal.setText(CurrencyManager.formatFiat(this, lastTotalBalanceUsd));
        }
        TextView tvAll = findViewById(R.id.tvAllWalletsTotal);
        if (tvAll != null && balanceVisible) {
            tvAll.setText(CurrencyManager.formatFiat(this, lastAllWalletsTotalUsd));
        }
        updateTodayPnL(lastAllWalletsTotalUsd, new HashMap<>());
        updateAIStatusCard();
    }

    private void showCrossChainLimitDialog() {
        CrossChainLimitConfig config = new CrossChainLimitConfig(this);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(8));

        String currencyCode = CurrencyManager.getSelectedCurrency(this);

        TextView tvSingleLabel = new TextView(this);
        tvSingleLabel.setText(getString(R.string.text_single_limit, currencyCode));
        tvSingleLabel.setTextColor(Color.WHITE);
        tvSingleLabel.setTextSize(14);
        layout.addView(tvSingleLabel);

        EditText etSingle = new EditText(this);
        etSingle.setHint(getString(R.string.hint_e_10));
        etSingle.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etSingle.setTextColor(Color.WHITE);
        etSingle.setHintTextColor(0xFF9B9BA7);
        etSingle.setText(String.format(Locale.getDefault(), "%.2f", config.getSingleLimitUsd()));
        layout.addView(etSingle);

        TextView tvDailyLabel = new TextView(this);
        tvDailyLabel.setText(getString(R.string.text_daily_limit, currencyCode));
        tvDailyLabel.setTextColor(Color.WHITE);
        tvDailyLabel.setTextSize(14);
        tvDailyLabel.setPadding(0, dpToPx(12), 0, 0);
        layout.addView(tvDailyLabel);

        EditText etDaily = new EditText(this);
        etDaily.setHint(getString(R.string.hint_e_10));
        etDaily.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etDaily.setTextColor(Color.WHITE);
        etDaily.setHintTextColor(0xFF9B9BA7);
        etDaily.setText(String.format(Locale.getDefault(), "%.2f", config.getDailyLimitUsd()));
        layout.addView(etDaily);

        TextView tvTip = new TextView(this);
        tvTip.setText(getString(R.string.text_cross_chain_operations_that));
        tvTip.setTextColor(0xFF9B9BA7);
        tvTip.setTextSize(12);
        tvTip.setPadding(0, dpToPx(12), 0, 0);
        layout.addView(tvTip);

        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.title_cross_chain_limit_settings))
            .setView(layout)
            .setPositiveButton(getString(R.string.btn_saving), (dialog, which) -> {
                try {
                    double single = Double.parseDouble(etSingle.getText().toString().trim());
                    double daily = Double.parseDouble(etDaily.getText().toString().trim());
                    if (single <= 0 || daily <= 0) {
                        Toast.makeText(this, getString(R.string.toast_limit_must_be_greater), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (single > daily) {
                        Toast.makeText(this, getString(R.string.toast_single_limit_cannot_be), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    config.setSingleLimitUsd(single);
                    config.setDailyLimitUsd(daily);
                    Logger.action(this, "UI操作", "跨链限额保存", "single=" + single + ", daily=" + daily);
                    Toast.makeText(this, getString(R.string.toast_cross_chain_quota_saved), Toast.LENGTH_SHORT).show();
                } catch (NumberFormatException e) {
                    Toast.makeText(this, getString(R.string.toast_please_enter_valid_number), Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton(getString(R.string.btn_s_decline), null)
            .show();
    }

    private void showThemeDialog() {
        int currentMode = ThemeManager.getThemeMode(this);
        String[] labels = ThemeManager.MODE_LABELS;
        final int[] checkedItem = {currentMode};

        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.title_theme_mode))
            .setSingleChoiceItems(labels, currentMode, (dialog, which) -> {
                checkedItem[0] = which;
            })
            .setPositiveButton(getString(R.string.str_confirm), (dialog, which) -> {
                int newMode = checkedItem[0];
                if (newMode != currentMode) {
                    ThemeManager.setThemeMode(this, newMode);
                    updateCurrentThemeDisplay();
                    // 重建Activity以应用新主题
                    recreate();
                }
            })
            .setNegativeButton(getString(R.string.btn_s_decline), null)
            .show();
    }

    private void updateCurrentModelDisplay() {
        TextView tvCurrentModel = findViewById(R.id.tvCurrentModel);
        if (tvCurrentModel == null) return;
        String activeId = ModelProviderManager.getActiveProviderId(this);
        if (activeId != null && !activeId.isEmpty()) {
            ModelProviderManager.ProviderInfo info = ModelProviderManager.BUILTIN_PROVIDERS.get(activeId);
            if (info != null) {
                String model = ModelProviderManager.getActiveModel(this);
                tvCurrentModel.setText(info.name + " / " + model);
            } else {
                tvCurrentModel.setText(activeId + " / " + ModelProviderManager.getActiveModel(this));
            }
        } else {
            tvCurrentModel.setText(getString(R.string.text_not_configured));
        }
    }


    private void toggleBalanceVisibility() {
        try {
            balanceVisible = !balanceVisible;
            String masked = "******";
            String eye = balanceVisible ? "👁" : "👁‍🗨";
            TextView btnEye = findViewById(R.id.btnToggleBalanceAssets);
            if (btnEye != null) btnEye.setText(eye);
            TextView tv2 = findViewById(R.id.tvTotalBalanceAssets);
            if (tv2 != null) {
                if (balanceVisible) {
                    tv2.setText(CurrencyManager.formatFiat(this, lastTotalBalanceUsd));
                } else {
                    tv2.setText(masked);
                }
            }
            TextView tvAll = findViewById(R.id.tvAllWalletsTotal);
            if (tvAll != null) {
                if (balanceVisible) {
                    tvAll.setText(CurrencyManager.formatFiat(this, lastAllWalletsTotalUsd));
                } else {
                    tvAll.setText(masked);
                }
            }
        } catch (Exception e) {
            Logger.error(this, "余额显示", "切换失败", e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001) {
            Logger.info(this, "资产刷新", "代币扫描返回，自动刷新资产列表");
            loadAssets(false);
        }
    }

    private void showAddTokenDialog() {
        String chain = WalletManager.getChain(this);
        if (!ChainAPI.isEVM(chain)) {
            Toast.makeText(this, getString(R.string.toast_the_current_chain_does_2), Toast.LENGTH_SHORT).show();
            return;
        }

        View view = getLayoutInflater().inflate(R.layout.dialog_add_token, null);
        EditText etContract = view.findViewById(R.id.etContractAddress);
        EditText etSymbol = view.findViewById(R.id.etTokenSymbol);
        EditText etName = view.findViewById(R.id.etTokenName);
        EditText etDecimals = view.findViewById(R.id.etTokenDecimals);

        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.title_add_tokens_pound, ChainAPI.getChainName(chain)))
            .setView(view)
            .setPositiveButton(getString(R.string.btn_tambah), (dialog, which) -> {
                String contract = etContract.getText().toString().trim();
                if (contract.isEmpty()) {
                    Toast.makeText(this, getString(R.string.toast_please_enter_the_contract), Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!WalletManager.isValidAddress(contract, chain)) {
                    Toast.makeText(this, getString(R.string.toast_incorrect_contract_address_format), Toast.LENGTH_SHORT).show();
                    return;
                }
                final String[] fields = new String[]{
                    etSymbol.getText().toString().trim(),
                    etName.getText().toString().trim(),
                    etDecimals.getText().toString().trim()
                };

                executor.execute(() -> {
                    try {
                        String symbol = fields[0];
                        String name = fields[1];
                        String decimalsStr = fields[2];
                        if (symbol.isEmpty() || name.isEmpty() || decimalsStr.isEmpty()) {
                            String[] info = ChainAPI.getTokenInfo(this, chain, contract);
                            if (info == null) {
                                handler.post(() -> Toast.makeText(this, getString(R.string.toast_unable_to_read_contract), Toast.LENGTH_LONG).show());
                                return;
                            }
                            if (symbol.isEmpty()) symbol = info[0];
                            if (name.isEmpty()) name = info[1];
                            if (decimalsStr.isEmpty()) decimalsStr = info[2];
                        }
                        if (symbol.isEmpty()) symbol = "TOKEN";
                        if (name.isEmpty()) name = symbol;
                        if (decimalsStr.isEmpty()) decimalsStr = "18";

                        final String finalSymbol = symbol;
                        final String finalName = name;
                        final String finalDecimals = decimalsStr;
                        WalletManager.addCustomToken(this, chain, finalSymbol, finalName, contract, finalDecimals);
                        handler.post(() -> {
                            Toast.makeText(this, getString(R.string.toast_was_added, finalSymbol), Toast.LENGTH_SHORT).show();
                            loadAssets();
                        });
                    } catch (Exception e) {
                        handler.post(() -> Toast.makeText(this, getString(R.string.toast_failed_to_add, e.getMessage()), Toast.LENGTH_LONG).show());
                    }
                });
            })
            .setNegativeButton(getString(R.string.btn_s_decline), null)
            .show();
        Logger.actionResult(this, "UI操作", "添加代币", "弹窗已打开");
    }

    /**
     * 智能识别用户输入：URL 直接打开，关键词走百度搜索
     * 参考 TP 钱包发现页搜索逻辑
     */
    private String smartParseInput(String input) {
        String s = input.trim();
        if (s.isEmpty()) return "https://www.baidu.com";
        // 已带协议头
        if (s.startsWith("http://") || s.startsWith("https://")) {
            return s;
        }
        // 判断是否像 URL：包含 . 且不含空格，且末段是常见后缀或就是域名
        // 例如 baidu.com / www.google.com / app.uniswap.org
        boolean looksLikeUrl = s.contains(".") && !s.contains(" ")
            && !s.startsWith(".") && !s.endsWith(".");
        if (looksLikeUrl) {
            return "https://" + s;
        }
        // 否则作为关键词搜索（用百度，国内可用）
        try {
            return "https://www.baidu.com/s?wd=" + java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return "https://www.baidu.com/s?wd=" + s;
        }
    }

    private void loadWalletInfo() {
        try {
            String name = WalletManager.getWalletName(this);
            String address = WalletManager.getWalletAddress(this);
            String chain = WalletManager.getChain(this);

            TextView tvAddr = findViewById(R.id.tvAssetAddress);
            if (tvAddr != null && address != null) {
                tvAddr.setText(address);
            }

            if (tvMyWalletName != null && name != null) {
                tvMyWalletName.setText(name);
            }
            if (tvMyWalletAddress != null && address != null && address.length() > 10) {
                String shortAddr = address.substring(0, 6) + "..." + address.substring(address.length() - 4);
                tvMyWalletAddress.setText(shortAddr);
            }

            // 更新钱包切换按钮
            updateWalletSwitcherDisplay();
        } catch (Exception e) {
            Logger.error(this, "钱包信息", "加载失败", e);
        }
    }

    private void updateWalletSwitcherDisplay() {
        try {
            String name = WalletManager.getWalletName(this);
            String chain = WalletManager.getChain(this);
            TextView tvName = findViewById(R.id.tvWalletSwitcherName);
            TextView tvChain = findViewById(R.id.tvWalletSwitcherChain);
            TextView tvIcon = findViewById(R.id.tvWalletSwitcherIcon);
            if (tvName != null) tvName.setText(name);
            if (tvChain != null) tvChain.setText(ChainAPI.getChainName(chain));
            if (tvIcon != null) {
                tvIcon.setText(chain);
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                bg.setColor(Color.parseColor(ChainAPI.getChainColor(chain)));
                tvIcon.setBackground(bg);
                tvIcon.setTextColor(Color.WHITE);
                tvIcon.setTextSize(11);
            }
        } catch (Exception e) {
            Logger.error(this, "钱包切换", "更新显示失败", e);
        }
    }

    private void showWalletSwitcher() {
        View view = getLayoutInflater().inflate(R.layout.dialog_wallet_switcher, null);
        LinearLayout chainBarContainer = view.findViewById(R.id.chainBarContainer);
        LinearLayout walletListContainer = view.findViewById(R.id.walletListContainer);
        TextView tvSelectedChainName = view.findViewById(R.id.tvSelectedChainName);
        TextView btnAddWalletToChain = view.findViewById(R.id.btnAddWalletToChain);
        View btnClose = view.findViewById(R.id.btnCloseWalletSheet);
        View btnCreateWallet = view.findViewById(R.id.btnCreateWallet);

        // 底部半屏弹窗
        BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.BottomSheetDialog);
        dialog.setContentView(view);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setDimAmount(0.6f);
        }

        // 关闭按钮
        btnClose.setOnClickListener(v -> dialog.dismiss());

        // 创建/导入钱包
        btnCreateWallet.setOnClickListener(v -> {
            Logger.action(this, "UI操作", "钱包弹窗-创建钱包", null);
            dialog.dismiss();
            Intent intent = new Intent(HomeActivity.this, MainActivity.class);
            intent.putExtra("force_create", true);
            startActivity(intent);
        });

        // 加载所有钱包
        List<WalletManager.WalletInfo> wallets = WalletManager.getAllWallets(this);
        String activeId = WalletManager.getActiveWalletId(this);
        String currentChain = WalletManager.getChain(this);

        // 按链分组钱包
        Map<String, List<WalletManager.WalletInfo>> chainWallets = new java.util.LinkedHashMap<>();
        for (WalletManager.WalletInfo w : wallets) {
            String chain = w.chain != null ? w.chain : "ETH";
            List<WalletManager.WalletInfo> list = chainWallets.get(chain);
            if (list == null) {
                list = new ArrayList<>();
                chainWallets.put(chain, list);
            }
            list.add(w);
        }

        // 如果当前链没有钱包，默认选第一个有钱包的链
        final String[] selectedChain = {currentChain};
        if (!chainWallets.containsKey(selectedChain[0]) && !chainWallets.isEmpty()) {
            selectedChain[0] = chainWallets.keySet().iterator().next();
        }

        // 更新右侧顶部链名
        tvSelectedChainName.setText(ChainAPI.getChainName(this, selectedChain[0]));

        // 添加钱包到当前链
        btnAddWalletToChain.setOnClickListener(v -> {
            dialog.dismiss();
            Intent intent = new Intent(HomeActivity.this, MainActivity.class);
            intent.putExtra("force_create", true);
            intent.putExtra("target_chain", selectedChain[0]);
            startActivity(intent);
        });

        // 构建左侧链栏（开源原生币 LOGO）
        int iconSize = (int) (48 * getResources().getDisplayMetrics().density);
        for (Map.Entry<String, List<WalletManager.WalletInfo>> entry : chainWallets.entrySet()) {
            String chain = entry.getKey();
            boolean isSelected = chain.equals(selectedChain[0]);
            int chainColor = Color.parseColor(ChainAPI.getChainColor(chain));

            // 链图标容器
            LinearLayout chainItem = new LinearLayout(this);
            chainItem.setOrientation(LinearLayout.VERTICAL);
            chainItem.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, iconSize + 24);
            itemParams.setMargins(0, 8, 0, 8);
            chainItem.setLayoutParams(itemParams);
            chainItem.setClickable(true);
            chainItem.setFocusable(true);
            chainItem.setTag(chain);

            // 图标区域：ImageView 加载开源 LOGO，TextView 作为缩写兜底
            FrameLayout iconContainer = new FrameLayout(this);
            iconContainer.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));

            ImageView ivLogo = new ImageView(this);
            ivLogo.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            ivLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            ivLogo.setVisibility(View.GONE);

            TextView tvFallback = new TextView(this);
            tvFallback.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            tvFallback.setGravity(Gravity.CENTER);
            tvFallback.setTextSize(12);
            tvFallback.setTextColor(Color.WHITE);
            tvFallback.setTypeface(null, Typeface.BOLD);
            tvFallback.setText(chain);
            GradientDrawable fallbackBg = new GradientDrawable();
            fallbackBg.setShape(GradientDrawable.OVAL);
            fallbackBg.setColor(chainColor);
            tvFallback.setBackground(fallbackBg);

            iconContainer.addView(ivLogo);
            iconContainer.addView(tvFallback);
            chainItem.addView(iconContainer);

            // 加载开源原生币 LOGO
            loadChainLogo(ivLogo, tvFallback, chain);

            // 选中高亮背景
            if (isSelected) {
                GradientDrawable selBg = new GradientDrawable();
                selBg.setShape(GradientDrawable.RECTANGLE);
                selBg.setCornerRadius(12);
                selBg.setColor(0x1AFFFFFF);
                chainItem.setBackground(selBg);
            }

            chainItem.setOnClickListener(cv -> {
                selectedChain[0] = chain;
                tvSelectedChainName.setText(ChainAPI.getChainName(HomeActivity.this, chain));

                // 刷新链栏高亮
                for (int i = 0; i < chainBarContainer.getChildCount(); i++) {
                    View child = chainBarContainer.getChildAt(i);
                    if (child instanceof LinearLayout) {
                        LinearLayout item = (LinearLayout) child;
                        String childChain = (String) item.getTag();
                        boolean sel = chain.equals(childChain);
                        if (sel) {
                            GradientDrawable sBg = new GradientDrawable();
                            sBg.setShape(GradientDrawable.RECTANGLE);
                            sBg.setCornerRadius(12);
                            sBg.setColor(0x1AFFFFFF);
                            item.setBackground(sBg);
                        } else {
                            item.setBackground(null);
                        }
                    }
                }

                // 刷新右侧钱包列表
                walletListContainer.removeAllViews();
                List<WalletManager.WalletInfo> cw = chainWallets.get(chain);
                if (cw != null) {
                    renderWalletCards(walletListContainer, cw, activeId, dialog);
                }
            });

            chainBarContainer.addView(chainItem);
        }

        // 构建右侧钱包卡片
        List<WalletManager.WalletInfo> selectedWallets = chainWallets.get(selectedChain[0]);
        if (selectedWallets != null) {
            renderWalletCards(walletListContainer, selectedWallets, activeId, dialog);
        }

        dialog.show();
    }

    private void renderWalletCards(LinearLayout container, List<WalletManager.WalletInfo> wallets,
                                   String activeId, BottomSheetDialog dialog) {
        for (WalletManager.WalletInfo w : wallets) {
            View card = getLayoutInflater().inflate(R.layout.item_wallet_switcher, null);
            TextView tvName = card.findViewById(R.id.tvWalletItemName);
            TextView tvAddr = card.findViewById(R.id.tvWalletItemAddress);
            TextView tvType = card.findViewById(R.id.tvWalletItemType);
            TextView tvCheck = card.findViewById(R.id.tvWalletItemCheck);
            TextView tvBalance = card.findViewById(R.id.tvWalletItemBalance);

            tvName.setText(w.name);
            tvAddr.setText(w.getShortAddress());
            tvCheck.setVisibility(w.id.equals(activeId) ? View.VISIBLE : View.GONE);

            // 链品牌色背景卡片
            String chain = w.chain != null ? w.chain : "ETH";
            int chainColor = Color.parseColor(ChainAPI.getChainColor(chain));
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setShape(GradientDrawable.RECTANGLE);
            cardBg.setCornerRadius(dpToPx(14));
            cardBg.setColor(chainColor);
            if (w.id.equals(activeId)) {
                cardBg.setStroke(dpToPx(3), Color.WHITE);
            }
            card.setBackground(cardBg);

            // 文字统一白色，确保在任何链色上都可见
            tvName.setTextColor(Color.WHITE);
            tvAddr.setTextColor(0xCCFFFFFF);
            tvBalance.setTextColor(Color.WHITE);
            tvCheck.setTextColor(Color.WHITE);

            // 观察钱包标签
            if (w.isWatchOnly()) {
                tvType.setText(getString(R.string.text_observation_wallet));
                tvType.setTextColor(Color.WHITE);
                GradientDrawable tagBg = new GradientDrawable();
                tagBg.setShape(GradientDrawable.RECTANGLE);
                tagBg.setCornerRadius(dpToPx(4));
                tagBg.setColor(0x4D000000);
                tvType.setBackground(tagBg);
                tvType.setVisibility(View.VISIBLE);
            } else {
                tvType.setVisibility(View.GONE);
            }

            // 余额：当前钱包显示缓存值，其他显示 0
            if (w.id.equals(activeId)) {
                dataCache.setCurrentWallet(w.id);
                double total = dataCache.getCachedTotalValue();
                tvBalance.setText(CurrencyManager.formatFiat(this, total));
            } else {
                tvBalance.setText(CurrencyManager.formatFiat(this, 0));
            }

            card.setOnClickListener(v2 -> {
                Logger.action(HomeActivity.this, "UI操作", "钱包弹窗-切换钱包", null);
                if (!w.id.equals(activeId)) {
                    WalletManager.setActiveWalletId(HomeActivity.this, w.id);
                    Logger.actionResult(HomeActivity.this, "UI操作", "切换钱包", w.name);
                    WalletManager.setChain(HomeActivity.this, w.chain);
                    dialog.dismiss();
                    Toast.makeText(HomeActivity.this, getString(R.string.toast_switched_to, w.name), Toast.LENGTH_SHORT).show();
                    loadWalletInfo();
                    // 切换钱包：立即秒开该钱包的缓存数据，再由后台任务刷新覆盖
                    dataCache.setCurrentWallet(WalletManager.getWalletAddress(this));
                    loadAssetsFromCache();
                    loadAssets();
                    updateAIStatusCard();
                } else {
                    dialog.dismiss();
                }
            });

            card.setOnLongClickListener(v2 -> {
                showWalletOptionsDialog(w, dialog);
                return true;
            });

            container.addView(card);
        }
    }

    /** 加载链开源原生币 LOGO，加载失败或无映射时显示缩写兜底 */
    private void loadChainLogo(ImageView iv, TextView fallback, String chain) {
        String url = ChainAPI.getChainLogoUrl(chain);
        if (url == null) {
            iv.setVisibility(View.GONE);
            fallback.setVisibility(View.VISIBLE);
            return;
        }
        int chainColor = Color.parseColor(ChainAPI.getChainColor(chain));
        iv.setVisibility(View.VISIBLE);
        fallback.setVisibility(View.GONE);
        Glide.with(this)
            .load(url)
            .placeholder(new ColorDrawable(chainColor))
            .listener(new RequestListener<Drawable>() {
                @Override
                public boolean onLoadFailed(GlideException e, Object model,
                                            Target<Drawable> target, boolean isFirstResource) {
                    iv.setVisibility(View.GONE);
                    fallback.setVisibility(View.VISIBLE);
                    return false;
                }
                @Override
                public boolean onResourceReady(Drawable resource, Object model,
                                               Target<Drawable> target, DataSource dataSource,
                                               boolean isFirstResource) {
                    iv.setVisibility(View.VISIBLE);
                    fallback.setVisibility(View.GONE);
                    return false;
                }
            })
            .into(iv);
    }

    /**
     * 钱包操作菜单（重命名/删除）
     */
    private void showWalletOptionsDialog(WalletManager.WalletInfo w, BottomSheetDialog parentDialog) {
        String[] items = w.isWatchOnly() 
            ? new String[]{"重命名", "删除", "取消"}
            : new String[]{"重命名", "删除", "取消"};

        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(w.name)
            .setItems(items, (d, which) -> {
                if (which == 0) {
                    // 重命名
                    showRenameDialog(w, parentDialog);
                } else if (which == 1) {
                    // 删除
                    showDeleteConfirmDialog(w, parentDialog);
                }
            })
            .setNegativeButton(getString(R.string.btn_s_decline), null)
            .show();
    }

    /**
     * 删除确认弹窗
     */
    private void showDeleteConfirmDialog(WalletManager.WalletInfo w, BottomSheetDialog parentDialog) {
        String message = w.isWatchOnly()
            ? "确定删除观察钱包 \"" + w.name + "\" 吗？\n\n地址：" + w.getShortAddress()
            : "确定删除钱包 \"" + w.name + "\" 吗？\n\n此操作不可撤销，请确保已备份助记词。\n\n地址：" + w.getShortAddress();

        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.title_delete_wallet))
            .setMessage(message)
            .setPositiveButton(getString(R.string.text_delete), (d, which) -> {
                WalletManager.removeWallet(this, w.id);
                Toast.makeText(this, getString(R.string.toast_deleted), Toast.LENGTH_SHORT).show();
                loadWalletInfo();
                loadAssets();
                updateAIStatusCard();
                if (parentDialog != null) parentDialog.dismiss();
                // 如果所有钱包都被删除，返回创建页
                if (!WalletManager.hasWallet(this)) {
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                }
            })
            .setNegativeButton(getString(R.string.btn_s_decline), null)
            .show();
    }

    /**
     * HD派生新账户弹窗
     */
    private void showAddHdAccountDialog() {
        WalletManager.WalletInfo active = WalletManager.getActiveWallet(this);
        if (active == null || active.mnemonicEnc == null || active.mnemonicEnc.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_mnemonic_words_available), Toast.LENGTH_SHORT).show();
            return;
        }

        int nextIndex = WalletManager.getNextHdIndex(this);
        String mnemonic = WalletManager.getMnemonic(this);
        String chain = WalletManager.getChain(this);
        String address = WalletManager.deriveAddressAtIndex(mnemonic, chain, nextIndex);

        if (address.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_failed_to_derive_address), Toast.LENGTH_SHORT).show();
            return;
        }

        View view = getLayoutInflater().inflate(R.layout.dialog_add_watch_wallet, null);
        EditText etName = view.findViewById(R.id.etWatchName);
        View etAddress = view.findViewById(R.id.etWatchAddress);
        View spChain = view.findViewById(R.id.spWatchChain);

        etName.setText("Account " + (nextIndex + 1));
        // 只读显示地址
        etAddress.setVisibility(View.GONE);
        spChain.setVisibility(View.GONE);

        // 修改标题和显示地址
        for (int i = 0; i < ((LinearLayout) view).getChildCount(); i++) {
            View child = ((LinearLayout) view).getChildAt(i);
            if (child instanceof TextView) {
                String text = ((TextView) child).getText().toString();
                if ("添加观察钱包".equals(text)) {
                    ((TextView) child).setText(getString(R.string.text_derived_hd_accounts));
                } else if ("输入地址即可查看资产，无需私钥".equals(text)) {
                    ((TextView) child).setText(getString(R.string.text_derive_account_from_the, (nextIndex + 1)));
                    ((TextView) child).setVisibility(View.VISIBLE);
                } else if ("钱包地址".equals(text) || "选择链".equals(text)) {
                    child.setVisibility(View.GONE);
                }
            }
        }
        // 在名称下方显示地址
        TextView tvDerivedAddr = new TextView(this);
        tvDerivedAddr.setText(address);
        tvDerivedAddr.setTextColor(0xFF6E6E7A);
        tvDerivedAddr.setTextSize(11);
        tvDerivedAddr.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvDerivedAddr.setPadding(0, 4, 0, 0);
        ((LinearLayout) view).addView(tvDerivedAddr, 5); // 插入在名称EditText之后

        final String finalAddress = address;
        final String finalChain = chain;

        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setView(view)
            .setPositiveButton(getString(R.string.btn_buat), (d, which) -> {
                String name = etName.getText().toString().trim();
                if (name.isEmpty()) name = "Account " + (nextIndex + 1);
                WalletManager.addHdAccount(this, name, finalAddress, finalChain);
                Toast.makeText(this, getString(R.string.toast_hd_account_created, name), Toast.LENGTH_SHORT).show();
                loadWalletInfo();
                loadAssets();
                updateAIStatusCard();
            })
            .setNegativeButton(getString(R.string.btn_s_decline), null)
            .show();
    }

    private void showRenameDialog(WalletManager.WalletInfo w, BottomSheetDialog parentDialog) {
        View view = getLayoutInflater().inflate(R.layout.dialog_add_watch_wallet, null);
        EditText etName = view.findViewById(R.id.etWatchName);
        View etAddress = view.findViewById(R.id.etWatchAddress);
        View spChain = view.findViewById(R.id.spWatchChain);

        etName.setText(w.name);
        // 隐藏地址和链字段（重命名不需要）
        etAddress.setVisibility(View.GONE);
        spChain.setVisibility(View.GONE);
        // 隐藏地址标签和链标签，修改标题
        for (int i = 0; i < ((LinearLayout) view).getChildCount(); i++) {
            View child = ((LinearLayout) view).getChildAt(i);
            if (child instanceof TextView) {
                String text = ((TextView) child).getText().toString();
                if ("添加观察钱包".equals(text)) {
                    ((TextView) child).setText(getString(R.string.text_rename_wallet));
                } else if ("输入地址即可查看资产，无需私钥".equals(text) 
                    || "钱包地址".equals(text) || "选择链".equals(text)) {
                    child.setVisibility(View.GONE);
                }
            }
        }

        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setView(view)
            .setPositiveButton(getString(R.string.str_confirm), (d, which) -> {
                String newName = etName.getText().toString().trim();
                if (newName.isEmpty()) {
                    Toast.makeText(this, getString(R.string.toast_name_can_not_be), Toast.LENGTH_SHORT).show();
                    return;
                }
                WalletManager.renameWallet(this, w.id, newName);
                Toast.makeText(this, getString(R.string.toast_renamed), Toast.LENGTH_SHORT).show();
                loadWalletInfo();
                if (parentDialog != null) parentDialog.dismiss();
                showWalletSwitcher();
            })
            .setNegativeButton(getString(R.string.btn_s_decline), null)
            .show();
    }

    private void showAddWatchWalletDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_add_watch_wallet, null);
        EditText etName = view.findViewById(R.id.etWatchName);
        EditText etAddress = view.findViewById(R.id.etWatchAddress);
        Spinner spChain = view.findViewById(R.id.spWatchChain);

        // 链选择下拉
        String[] chainSymbols = new String[ChainAPI.CHAIN_CONFIG.length];
        String[] chainDisplayNames = new String[ChainAPI.CHAIN_CONFIG.length];
        int defaultIdx = 0;
        for (int i = 0; i < ChainAPI.CHAIN_CONFIG.length; i++) {
            chainSymbols[i] = ChainAPI.CHAIN_CONFIG[i][0];
            chainDisplayNames[i] = ChainAPI.CHAIN_CONFIG[i][0] + "  " + ChainAPI.CHAIN_CONFIG[i][1];
            if (chainSymbols[i].equals("BNB")) defaultIdx = i;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, chainDisplayNames);
        spChain.setAdapter(adapter);
        spChain.setSelection(defaultIdx);

        // 默认名称
        int watchCount = 0;
        for (WalletManager.WalletInfo w : WalletManager.getAllWallets(this)) {
            if (w.isWatchOnly()) watchCount++;
        }
        etName.setText(getString(R.string.text_observation_wallet, (watchCount + 1)));

        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setView(view)
            .setPositiveButton(getString(R.string.btn_tambah), (dialog, which) -> {
                String name = etName.getText().toString().trim();
                String address = etAddress.getText().toString().trim();
                int chainIdx = spChain.getSelectedItemPosition();

                if (name.isEmpty()) name = "观察钱包";
                if (address.isEmpty()) {
                    Toast.makeText(this, getString(R.string.toast_please_enter_wallet_name), Toast.LENGTH_SHORT).show();
                    return;
                }
                if (chainIdx < 0 || chainIdx >= chainSymbols.length) {
                    Toast.makeText(this, getString(R.string.toast_please_select_chain), Toast.LENGTH_SHORT).show();
                    return;
                }

                String chain = chainSymbols[chainIdx];
                if (!WalletManager.isValidAddress(address, chain)) {
                    Toast.makeText(this, getString(R.string.toast_incorrect_address_format_chain, ChainAPI.getChainName(chain)), Toast.LENGTH_LONG).show();
                    return;
                }

                WalletManager.addWatchOnlyWallet(this, name, address, chain);
                Toast.makeText(this, getString(R.string.toast_observation_wallet_added), Toast.LENGTH_SHORT).show();
                loadWalletInfo();
                loadAssets();
                updateAIStatusCard();
            })
            .setNegativeButton(getString(R.string.btn_s_decline), null)
            .show();
    }

    private void loadMyInfo() {
        loadWalletInfo();
        updateCurrentModelDisplay();
    }

    private void loadAssets() {
        loadAssets(true);
    }

    /**
     * 为当前链自动测速选最快节点，然后刷新资产
     */
    private void optimizeNodeAndLoadAssets() {
        executor.execute(() -> {
            try {
                String chain = WalletManager.getChain(this);
                String best = NodeManager.findFastestNode(chain);
                if (best != null && !best.isEmpty()) {
                    String current = NodeManager.getSelectedNode(this, chain);
                    if (!best.equals(current)) {
                        NodeManager.setSelectedNode(this, chain, best);
                        Logger.info(this, "节点优化", Logger.getChainChineseName(chain) + " 切换到最快节点: " + best);
                    }
                }
            } catch (Exception e) {
                Logger.warning(this, "节点优化", "测速失败: " + e.getMessage());
            } finally {
                handler.post(this::loadAssets);
            }
        });
    }

    /**
     * 从暂存区快速加载资产（秒开）
     */
    private void loadAssetsFromCache() {
        try {
            String address = WalletManager.getWalletAddress(this);
            dataCache.setCurrentWallet(address);

            if (!dataCache.hasValidCache(address)) {
                Logger.info(this, "缓存加载", "无有效缓存，跳过");
                LinearLayout tc = findViewById(R.id.tokenListContainer);
                if (tc != null) tc.removeAllViews();
                return;
            }

            Logger.info(this, "缓存加载", "从暂存区加载资产数据");

            // 显示缓存的总资产
            double totalValue = dataCache.getCachedTotalValue();
            lastTotalBalanceUsd = totalValue;
            TextView tvTotalBal = findViewById(R.id.tvTotalBalanceAssets);
            if (tvTotalBal != null) tvTotalBal.setText(balanceVisible ? CurrencyManager.formatFiat(this, lastTotalBalanceUsd) : "******");

            // 从缓存读取所有钱包总资产，先显示上次数值，避免启动时白屏
            double cachedAllTotal = dataCache.getCachedAllWalletsTotal();
            if (cachedAllTotal > 0) {
                lastAllWalletsTotalUsd = cachedAllTotal;
                TextView tvAll = findViewById(R.id.tvAllWalletsTotal);
                if (tvAll != null) tvAll.setText(balanceVisible ? CurrencyManager.formatFiat(this, lastAllWalletsTotalUsd) : "******");
                Logger.info(this, "缓存加载", "所有钱包总资产缓存：" + CurrencyManager.formatFiat(this, lastAllWalletsTotalUsd));
            }

            // 显示缓存的代币列表
            LinearLayout tokenContainer = findViewById(R.id.tokenListContainer);
            if (tokenContainer != null) {
                tokenContainer.removeAllViews();
                List<String[]> cachedTokens = dataCache.getCachedTokens();
                for (String[] asset : cachedTokens) {
                    if (asset.length < 4) continue;
                    View item = getLayoutInflater().inflate(R.layout.item_asset, null);
                    ((TextView) item.findViewById(R.id.tvTokenSymbol)).setText(asset[0]);
                    ((TextView) item.findViewById(R.id.tvTokenSymbol2)).setText(asset[0]);
                    ((TextView) item.findViewById(R.id.tvTokenName)).setText(asset[1]);
                    ((TextView) item.findViewById(R.id.tvTokenAmount)).setText(asset[2]);
                    ((TextView) item.findViewById(R.id.tvTokenValue)).setText(CurrencyManager.formatFiat(this, parseAssetValue(asset[3])));
                    // 缓存路径也要绑定点击事件，否则缓存期间点击资产打不开
                    final String symbol = asset[0];
                    final String name = asset[1];
                    final String balance = asset[2];
                    final String value = CurrencyManager.formatFiat(this, parseAssetValue(asset[3]));
                    final String contract = asset.length > 4 ? asset[4] : "";
                    // v2.4.71: 加载代币 LOGO（trustwallet/assets via jsDelivr CDN）
                    TokenLogoLoader.load(this, item.findViewById(R.id.ivTokenLogo), symbol, contract, item.findViewById(R.id.tvTokenSymbol));
                    item.setOnClickListener(v -> {
                        Logger.action(HomeActivity.this, "UI操作", "代币项-" + symbol, null);
                        Intent intent = new Intent(HomeActivity.this, TokenDetailActivity.class);
                        intent.putExtra("symbol", symbol);
                        intent.putExtra("name", name);
                        intent.putExtra("balance", balance);
                        intent.putExtra("value", value);
                        intent.putExtra("contract", contract);
                        startActivity(intent);
                    });
                    // 长按隐藏代币（与 loadAssets 路径行为一致）
                    if (!contract.isEmpty()) {
                        item.setOnLongClickListener(v -> {
                            new AlertDialog.Builder(HomeActivity.this, R.style.AlertDialogCustom)
                                .setTitle(getString(R.string.title_hide_tokens))
                                .setMessage(getString(R.string.msg_confirm_hide_asset, symbol))
                                .setPositiveButton(getString(R.string.btn_hiding), (d, w) -> {
                                    try {
                                        String currentChain = WalletManager.getChain(HomeActivity.this);
                                        hideToken(currentChain, contract);
                                        tokenContainer.removeView(item);
                                        Toast.makeText(HomeActivity.this, getString(R.string.toast_hidden, symbol), Toast.LENGTH_SHORT).show();
                                    } catch (Exception e) {
                                        Toast.makeText(HomeActivity.this, getString(R.string.toast_concealment_failure, e.getMessage()), Toast.LENGTH_SHORT).show();
                                    }
                                })
                                .setNegativeButton(getString(R.string.btn_s_decline), null)
                                .show();
                            return true;
                        });
                    }
                    tokenContainer.addView(item);
                }
            }

            // 显示缓存的 AI 状态
            updateAIStatusCardFromCache();

            // 显示缓存年龄提示（如果过期）
            if (dataCache.isExpired()) {
                long ageSeconds = dataCache.getCacheAgeSeconds();
                Logger.info(this, "缓存加载", "缓存已过期 " + ageSeconds + " 秒，正在后台刷新...");
            }
        } catch (Exception e) {
            Logger.error(this, "缓存加载", "失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从缓存更新 AI 状态卡片
     */
    private void updateAIStatusCardFromCache() {
        try {
            if (aiStatusCard == null) return;

            String cachedStatus = dataCache.getCachedAIStatus();
            if (cachedStatus.isEmpty()) return;

            aiStatusCard.setVisibility(View.VISIBLE);

            boolean isRunning = cachedStatus.contains("运行中");
            if (aiStatusDot != null) {
                aiStatusDot.setBackgroundResource(isRunning ? R.drawable.dot_green : R.drawable.dot_red);
            }
            if (tvAiStatusText != null) {
                tvAiStatusText.setText(cachedStatus);
            }
            if (btnStartAIFromHome != null) {
                btnStartAIFromHome.setText(isRunning ? getString(R.string.str_manage) : getString(R.string.str_start));
            }

            double pnl = dataCache.getCachedAIPnL();
            if (tvAiCardPnL != null) {
                String sign = pnl >= 0 ? "+" : "";
                tvAiCardPnL.setText(sign + CurrencyManager.formatFiat(this, pnl));
                tvAiCardPnL.setTextColor(pnl >= 0 ? 0xFF00D084 : 0xFFFF4757);
            }
            if (tvAiCardWinRate != null) {
                tvAiCardWinRate.setText(dataCache.getCachedAIWinRate());
            }
            if (tvAiCardTrades != null) {
                tvAiCardTrades.setText(String.valueOf(dataCache.getCachedAITrades()));
            }
            if (tvAiCardChain != null) {
                tvAiCardChain.setText(dataCache.getCachedAIChain());
            }
        } catch (Exception e) {
            Logger.error(this, "缓存AI状态", "更新失败: " + e.getMessage(), e);
        }
    }

    private void loadAssets(boolean autoDiscover) {
        LinearLayout tokenContainer = findViewById(R.id.tokenListContainer);
        if (tokenContainer == null) {
            Logger.error(this, "资产加载", "容器未找到", null);
            if (assetsSwipeRefresh != null) assetsSwipeRefresh.setRefreshing(false);
            return;
        }

        // 任务去重：已有加载任务在执行时，不重复排队、不清空列表，
        // 仅记录待刷新，当前任务完成后会自动补一次（避免列表反复清空却刷不出来）
        if (isLoadingAssets) {
            pendingAssetRefresh = true;
            Logger.info(this, "资产加载", "已有加载任务在执行，合并本次刷新请求");
            if (assetsSwipeRefresh != null && assetsSwipeRefresh.isRefreshing()) {
                // 保留 spinner，待当前任务完成后统一停止
            }
            return;
        }
        isLoadingAssets = true;

        String currentAddr = WalletManager.getWalletAddress(this);
        dataCache.setCurrentWallet(currentAddr);

        // 秒开：有缓存时先显示缓存资产，避免刷新时列表空白；后台扫描完成后会覆盖更新
        if (dataCache.hasValidCache(currentAddr)) {
            loadAssetsFromCache();
        } else {
            // 无缓存，显示 Loading 占位
            tokenContainer.removeAllViews();
            TextView loadingText = new TextView(this);
            loadingText.setText("Loading...");
            loadingText.setTextColor(getResources().getColor(R.color.text_secondary));
            loadingText.setTextSize(14);
            loadingText.setGravity(Gravity.CENTER);
            loadingText.setPadding(48, 48, 48, 48);
            tokenContainer.addView(loadingText);
        }

        // 安全超时：30秒后强制停止刷新动画，防止 spinner 卡死
        final long safetyTimeout = 30000;
        handler.postDelayed(() -> {
            if (assetsSwipeRefresh != null && assetsSwipeRefresh.isRefreshing()) {
                assetsSwipeRefresh.setRefreshing(false);
                Logger.warning(HomeActivity.this, "资产刷新", "安全超时，强制停止刷新动画");
            }
        }, safetyTimeout);

        executor.execute(() -> {
          try {
            String chain = WalletManager.getChain(this);
            String address = WalletManager.getWalletAddress(this);
            double totalValue = 0;

            if (autoDiscover) {
                handler.post(() -> {
                    try {
                        String wAddr = WalletManager.getWalletAddress(HomeActivity.this);
                        String wChain = WalletManager.getChain(HomeActivity.this);
                        Logger.info(HomeActivity.this, "自动发现", "启动 WebView 后台扫描代币");
                        TokenAutoDiscovery.discover(HomeActivity.this, wChain, wAddr, foundCount -> {
                            if (foundCount > 0) {
                                Logger.success(HomeActivity.this, "自动发现", "扫描完成，发现 " + foundCount + " 个新代币，自动刷新资产列表");
                                Toast.makeText(HomeActivity.this, getString(R.string.toast_token_found_loading, foundCount), Toast.LENGTH_SHORT).show();
                                handler.postDelayed(() -> loadAssets(false), 500);
                            } else if (foundCount == 0) {
                                Logger.info(HomeActivity.this, "自动发现", "扫描完成，未发现新代币");
                            }
                        });
                    } catch (Throwable t) {
                        Logger.error(HomeActivity.this, "自动发现", "启动扫描异常: " + t.getMessage(), t);
                    }
                });
            }

            java.util.List<String[]> allTokens = new java.util.ArrayList<>();

            // 网络加载失败标记：原生币或代币查询抛异常时，保留旧缓存，避免用 0/空数据覆盖真实资产造成显示不稳定
            final boolean[] loadFailed = {false};

            double nativeBalance = 0;
            try {
                nativeBalance = ChainAPI.getNativeBalance(this, chain, address);
            } catch (Exception e) {
                loadFailed[0] = true;
                Logger.warning(this, "资产加载", "原生币余额查询失败，保留旧缓存: " + e.getMessage());
                // 回退用缓存的原生币余额，避免显示归零
                try {
                    nativeBalance = dataCache.getCachedNativeBalance();
                } catch (Exception ignore) {}
            }

            try {
                // 首屏秒开：跳过慢速的 Transfer 全量扫描，先渲染原生币+已知代币
                java.util.List<String[]> discoveredTokens = ChainAPI.getAllTokenBalances(this, chain, address, true, true);
                if (discoveredTokens != null && !discoveredTokens.isEmpty()) {
                    Set<String> hiddenTokens = getHiddenTokens(chain);
                    for (String[] token : discoveredTokens) {
                        String contractAddr = token.length > 4 ? token[4] : "";
                        if (hiddenTokens.contains(contractAddr.toLowerCase())) continue;
                        allTokens.add(token);
                    }
                }
            } catch (Exception e) {
                loadFailed[0] = true;
                Logger.warning(this, "资产加载", "代币发现失败，保留旧缓存: " + e.getMessage());
            }
            Map<String, Double> prices;
            double nativePrice = 0;
            try {
                prices = ChainAPI.getPrices(this);
                nativePrice = prices.getOrDefault(chain, 0.0);
            } catch (Exception e) {
                prices = new HashMap<>();
            }
            final Map<String, Double> finalPrices = prices;

            double nativeValue = nativeBalance * nativePrice;
            totalValue += nativeValue;
            String nativeName = ChainAPI.getChainName(chain);

            allTokens.add(0, new String[]{
                chain, nativeName,
                ChainAPI.formatAmount(nativeBalance),
                String.valueOf(nativeValue),
                "", "true"
            });

            for (int i = 1; i < allTokens.size(); i++) {
                String[] token = allTokens.get(i);
                if (token.length < 5) continue;
                try {
                    double balance = Double.parseDouble(token[2]);
                    String symbol = token[0];
                    double price = prices.getOrDefault(symbol, 0.0);
                    double value = balance * price;
                    totalValue += value;
                    token[3] = String.valueOf(value);
                } catch (Exception ignore) {}
            }

            final double finalTotal = totalValue;
            final String[][] finalAssets = allTokens.toArray(new String[0][]);

            // 网络加载失败时，用缓存数据兜底渲染，避免列表被清空/显示不稳定
            if (loadFailed[0] && dataCache.hasValidCache(address)) {
                try {
                    List<String[]> cached = dataCache.getCachedTokens();
                    if (cached != null && !cached.isEmpty()) {
                        allTokens.clear();
                        allTokens.addAll(cached);
                        Logger.info(this, "资产加载", "网络失败，用缓存数据兜底渲染 " + allTokens.size() + " 项");
                    }
                } catch (Exception ignore) {}
            }

            // 资产变动提醒：统一去重去抖（恒定余额不重复报、已通知代币不重复报、前后台/刷新不叠加）
            try {
                if (dataCache.hasValidCache(address) && !loadFailed[0]) {
                    DataCache.AssetChangeResult chg = dataCache.detectAssetChange(address, allTokens, nativeBalance);
                    if (chg.shouldNotify) {
                        // 原生币到账文案：仅当明显增加时带上名称+余额
                        String nativePart = chg.nativeIncreased && !chg.nativeName.isEmpty()
                            ? chg.nativeName + " " + chg.nativeBalanceText : "";
                        String msg;
                        if (!chg.newTokens.isEmpty() && !nativePart.isEmpty()) {
                            msg = getString(R.string.msg_asset_changed, chg.newTokens + "、" + nativePart);
                        } else if (!chg.newTokens.isEmpty()) {
                            msg = getString(R.string.msg_new_asset_arrived, chg.newTokens, "");
                        } else {
                            msg = getString(R.string.msg_asset_changed, nativePart);
                        }
                        Logger.success(this, "资产变动", "检测到资产变动: " + msg);
                        final String fMsg = msg;
                        final String fChain = chain;
                        final String fAddr = address;
                        // 异步补全最新交易哈希并推送：点击通知直达该笔交易详情，返回键退回资产列表
                        executor.execute(() -> {
                            String txHash = getLatestIncomingTxHash(fChain, fAddr);
                            AINotificationHelper.notifyAssetChange(HomeActivity.this,
                                getString(R.string.title_asset_change_reminder), fMsg, txHash, fChain);
                        });
                    }
                }
            } catch (Throwable t) {
                Logger.warning(this, "资产变动", "检测异常: " + t.getMessage());
            }

            // 保存到缓存暂存区
            try {
                dataCache.saveAssets(address, chain, finalTotal, nativeBalance, nativeValue, allTokens, prices);
            } catch (Exception e) {
                Logger.error(this, "缓存保存", "失败: " + e.getMessage(), e);
            }

            final double currentWalletTotalVal = finalTotal;

            handler.post(() -> {
                // 如果钱包已再次切换，跳过旧数据显示
                if (!currentAddr.equals(WalletManager.getWalletAddress(HomeActivity.this))) {
                    Logger.info(HomeActivity.this, "资产加载", "钱包已切换，跳过旧数据显示");
                    if (assetsSwipeRefresh != null && assetsSwipeRefresh.isRefreshing()) {
                        assetsSwipeRefresh.setRefreshing(false);
                    }
                    return;
                }
                tokenContainer.removeAllViews();
                if (smallAssetsContainer != null) smallAssetsContainer.removeAllViews();
                int smallCount = 0;

                for (String[] asset : finalAssets) {
                    View item = getLayoutInflater().inflate(R.layout.item_asset, null);
                    ((TextView) item.findViewById(R.id.tvTokenSymbol)).setText(asset[0]);
                    ((TextView) item.findViewById(R.id.tvTokenSymbol2)).setText(asset[0]);
                    ((TextView) item.findViewById(R.id.tvTokenName)).setText(asset[1]);
                    ((TextView) item.findViewById(R.id.tvTokenAmount)).setText(asset[2]);
                    ((TextView) item.findViewById(R.id.tvTokenValue)).setText(CurrencyManager.formatFiat(this, parseAssetValue(asset[3])));

                    String symbol = asset[0];
                    String name = asset[1];
                    String balance = asset[2];
                    String value = CurrencyManager.formatFiat(this, parseAssetValue(asset[3]));
                    String contract = asset.length > 4 ? asset[4] : "";
                    // v2.4.71: 加载代币 LOGO
                    TokenLogoLoader.load(this, item.findViewById(R.id.ivTokenLogo), symbol, contract, item.findViewById(R.id.tvTokenSymbol));
                    item.setOnClickListener(v -> {
                        Logger.action(HomeActivity.this, "UI操作", "代币项-" + symbol, null);
                        Intent intent = new Intent(HomeActivity.this, TokenDetailActivity.class);
                        intent.putExtra("symbol", symbol);
                        intent.putExtra("name", name);
                        intent.putExtra("balance", balance);
                        intent.putExtra("value", value);
                        intent.putExtra("contract", contract);
                        startActivity(intent);
                    });

                    if (!contract.isEmpty()) {
                        item.setOnLongClickListener(v -> {
                            new AlertDialog.Builder(HomeActivity.this, R.style.AlertDialogCustom)
                                .setTitle(getString(R.string.title_hide_tokens))
                                .setMessage(getString(R.string.msg_confirm_hide_restore, symbol))
                                .setPositiveButton(getString(R.string.btn_hiding), (dialog, which) -> {
                                    hideToken(chain, contract);
                                })
                                .setNegativeButton(getString(R.string.btn_s_decline), null)
                                .show();
                            return true;
                        });
                    }

                    // 小额资产（< $1）放入折叠容器
                    double tokenValue = parseValueString(value);
                    boolean isNative = "true".equals(asset.length > 5 ? asset[5] : "");
                    if (!isNative && tokenValue > 0 && tokenValue < 1.0 && smallAssetsContainer != null) {
                        smallAssetsContainer.addView(item);
                        smallCount++;
                    } else {
                        tokenContainer.addView(item);
                    }
                }

                // 显示/隐藏小额资产折叠按钮
                if (btnFoldSmallAssets != null && smallAssetsContainer != null) {
                    final int finalSmallCount = smallCount;
                    if (finalSmallCount > 0) {
                        btnFoldSmallAssets.setVisibility(View.VISIBLE);
                        btnFoldSmallAssets.setText(getString(R.string.str_expand, finalSmallCount));
                        btnFoldSmallAssets.setOnClickListener(v -> {
                            smallAssetsFolded = !smallAssetsFolded;
                            if (smallAssetsFolded) {
                                smallAssetsContainer.setVisibility(View.GONE);
                                btnFoldSmallAssets.setText(getString(R.string.str_expand, finalSmallCount));
                            } else {
                                smallAssetsContainer.setVisibility(View.VISIBLE);
                                btnFoldSmallAssets.setText(getString(R.string.str_unfold_small_assets, finalSmallCount));
                            }
                        });
                        smallAssetsContainer.setVisibility(View.GONE);
                    } else {
                        btnFoldSmallAssets.setVisibility(View.GONE);
                        smallAssetsContainer.setVisibility(View.GONE);
                    }
                }

                lastTotalBalanceUsd = finalTotal;
                TextView tvTotalBal = findViewById(R.id.tvTotalBalanceAssets);
                if (tvTotalBal != null) tvTotalBal.setText(balanceVisible ? CurrencyManager.formatFiat(this, lastTotalBalanceUsd) : "******");

                if (assetsSwipeRefresh != null && assetsSwipeRefresh.isRefreshing()) {
                    assetsSwipeRefresh.setRefreshing(false);
                    Toast.makeText(HomeActivity.this, getString(R.string.toast_asset_has_been_refreshed), Toast.LENGTH_SHORT).show();
                }
            });

            // 所有钱包总资产单独计算，避免阻塞当前钱包资产列表渲染
            allWalletsExecutor.execute(() -> {
                double allTotal = currentWalletTotalVal;
                try {
                    allTotal = calculateAllWalletsTotal(finalPrices, currentWalletTotalVal, address);
                } catch (Exception e) {
                    Logger.error(this, "总资产", "计算所有钱包总资产失败: " + e.getMessage(), e);
                }
                final double resultAllTotal = allTotal;
                handler.post(() -> {
                    lastAllWalletsTotalUsd = resultAllTotal;
                    TextView tvAll = findViewById(R.id.tvAllWalletsTotal);
                    if (tvAll != null) tvAll.setText(balanceVisible ? CurrencyManager.formatFiat(HomeActivity.this, lastAllWalletsTotalUsd) : "******");
                    updateTodayPnL(resultAllTotal, finalPrices);
                });
            });

            // 首屏已秒开渲染，后台补充 Transfer 扫描：发现新代币后自动刷新（不阻塞当前渲染）
            executor.execute(() -> {
                try {
                    java.util.List<String[]> scanned = ChainAPI.getAllTokenBalances(HomeActivity.this, chain, address);
                    if (scanned == null || scanned.isEmpty()) return;
                    Set<String> knownContracts = new HashSet<>();
                    for (String[] a : allTokens) {
                        if (a.length > 4 && a[4] != null && !a[4].isEmpty()) knownContracts.add(a[4].toLowerCase());
                    }
                    boolean hasNew = false;
                    for (String[] s : scanned) {
                        if (s.length > 4 && s[4] != null && !s[4].isEmpty()
                            && !knownContracts.contains(s[4].toLowerCase())) {
                            hasNew = true;
                            break;
                        }
                    }
                    if (hasNew) {
                        Logger.info(HomeActivity.this, "代币发现", "后台扫描发现新代币，自动刷新资产列表");
                        handler.post(() -> loadAssets(false));
                    }
                } catch (Exception e) {
                    Logger.warning(HomeActivity.this, "代币发现", "后台补充扫描失败: " + e.getMessage());
                }
            });
          } catch (Throwable t) {
            Logger.error(this, "资产刷新", "加载资产失败: " + t.getClass().getName() + ": " + t.getMessage(), t);
            handler.post(() -> {
                try {
                    if (assetsSwipeRefresh != null && assetsSwipeRefresh.isRefreshing()) {
                        assetsSwipeRefresh.setRefreshing(false);
                    }
                    showNetworkWarning();
                    Toast.makeText(HomeActivity.this, getString(R.string.toast_refresh_failed, t.getMessage()), Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {}
            });
          } finally {
            // 重置加载标志；若期间又有刷新请求合并，则补执行一次
            isLoadingAssets = false;
            if (pendingAssetRefresh) {
                pendingAssetRefresh = false;
                handler.post(() -> loadAssets(false));
            }
          }
        });
    }

    /**
     * 计算所有钱包总资产（USD）。会查询每条链上每个钱包的原生币和代币余额。
     * 使用轻量代币发现，避免做远程列表/区块浏览器扫描，逐钱包更新 UI。
     * 耗时操作，请在后台线程调用。
     *
     * @param prices      价格表（key: chain/symbol, value: USD 价格）
     * @param currentTotal 当前已算好的当前钱包总资产，避免重复查询
     * @param currentAddress 当前钱包地址，用于跳过重复计算
     */
    private double calculateAllWalletsTotal(Map<String, Double> prices, double currentTotal, String currentAddress) {
        return calculateAllWalletsTotalInternal(prices, currentTotal, currentAddress, false);
    }

    /**
     * 使用缓存价格估算所有钱包总资产。缓存加载路径使用，避免启动时大量网络请求。
     */
    private double calculateAllWalletsTotalFromCache(Map<String, Double> prices, double currentTotal, String currentAddress) {
        return calculateAllWalletsTotalInternal(prices, currentTotal, currentAddress, true);
    }

    /**
     * 所有钱包总资产计算统一实现。
     * @param cacheMode true 表示缓存估算模式：不更新 UI、不保存缓存、失败静默
     */
    private double calculateAllWalletsTotalInternal(Map<String, Double> prices, double currentTotal, String currentAddress, boolean cacheMode) {
        double total = 0;
        try {
            List<WalletManager.WalletInfo> allWallets = WalletManager.getAllWallets(this);
            if (allWallets == null || allWallets.isEmpty()) return 0;

            if (prices == null || prices.isEmpty()) {
                Logger.warning(this, "总资产", "价格表为空，返回当前钱包总资产");
                return currentTotal;
            }

            Logger.info(this, "总资产", "开始计算 " + allWallets.size() + " 个钱包总资产");

            int processed = 0;
            int totalCount = allWallets.size();
            for (WalletManager.WalletInfo w : allWallets) {
                if (w == null || w.address == null || w.address.isEmpty()) continue;
                if (currentAddress != null && w.address.equalsIgnoreCase(currentAddress)) {
                    total += currentTotal;
                    Logger.info(this, "总资产", "当前钱包 " + w.chain + " 计入 " + ChainAPI.formatValue(currentTotal));
                    // 进度更新
                    if (!cacheMode) postAllWalletsProgress(processed + 1, totalCount, total);
                    continue;
                }
                // 避免连续请求同一节点导致 Socket closed，非当前钱包之间间隔 120ms
                if (processed > 0) {
                    try { Thread.sleep(120); } catch (Exception ignored) {}
                }
                String chain = w.chain != null ? w.chain : "ETH";
                try {
                    double walletTotal = 0;
                    double nativeBalance = ChainAPI.getNativeBalance(this, chain, w.address);
                    double nativePrice = prices.getOrDefault(chain, 0.0);
                    walletTotal += nativeBalance * nativePrice;

                    // 轻量代币余额：只做内置热门 + 自定义 + 持久化代币，不做远程发现
                    List<String[]> tokens = ChainAPI.getAllTokenBalances(this, chain, w.address, false);
                    if (tokens != null) {
                        for (String[] token : tokens) {
                            if (token.length < 4) continue;
                            try {
                                double balance = Double.parseDouble(token[2]);
                                double price = prices.getOrDefault(token[0], 0.0);
                                walletTotal += balance * price;
                            } catch (Exception ignored) {}
                        }
                    }
                    total += walletTotal;
                    processed++;
                    Logger.info(this, "总资产", "钱包 " + chain + " " + w.address.substring(0, 6) + "... 计入 " + ChainAPI.formatValue(walletTotal));
                    // 进度更新
                    if (!cacheMode) postAllWalletsProgress(processed + 1, totalCount, total);
                } catch (Exception e) {
                    Logger.warning(this, "总资产", "计算钱包失败 " + chain + ": " + e.getMessage());
                }
            }
            Logger.success(this, "总资产", "所有钱包总资产: " + ChainAPI.formatValue(total));
            if (!cacheMode) {
                dataCache.saveAllWalletsTotal(total);
            }
        } catch (Exception e) {
            Logger.error(this, "总资产", "calculateAllWalletsTotal 失败: " + e.getMessage(), e);
        }
        return total;
    }

    /**
     * 在后台线程中向主线程发送所有钱包总资产计算进度。
     */
    private void postAllWalletsProgress(int processed, int total, double currentTotal) {
        handler.post(() -> {
            try {
                lastAllWalletsTotalUsd = currentTotal;
                TextView tvAll = findViewById(R.id.tvAllWalletsTotal);
                if (tvAll != null) {
                    String text = balanceVisible ? CurrencyManager.formatFiat(this, lastAllWalletsTotalUsd) : "******";
                    tvAll.setText(text);
                }
                Logger.info(this, "总资产", "进度 " + processed + "/" + total + " = " + CurrencyManager.formatFiat(this, currentTotal));
            } catch (Exception ignored) {}
        });
    }

    /**
     * 更新今日盈亏显示。
     *
     * 逻辑：
     *  1. 基于【全部钱包总资产】计算
     *  2. 用户向 APP 内任何钱包转入或转出的资产不计入盈亏（通过今日净流入剔除）
     *
     * @param currentTotal 全部钱包当前总资产（USD）
     * @param prices       价格表，用于把今日交易金额换算成 USD
     */
    private void updateTodayPnL(double currentTotal, java.util.Map<String, Double> prices) {
        try {
            // 保存每日快照（首次加载时）
            dataCache.saveDailySnapshotIfNeeded(currentTotal);

            double lastSnapshot = dataCache.getLastSnapshotValue();
            TextView tvAll = findViewById(R.id.tvAllWalletsTotal);
            if (lastSnapshot > 0 && tvTodayPnL != null) {
                double netInflow = calculateTodayNetInflow(prices);
                double adjustedChange = currentTotal - lastSnapshot - netInflow;
                double pct = (adjustedChange / lastSnapshot) * 100;
                String sign = adjustedChange >= 0 ? "+" : "";
                String color = adjustedChange >= 0 ? "#00D084" : "#FF4757";
                tvTodayPnL.setText(String.format(getString(R.string.str_profit_loss_today) + " %s%s(%s%.2f%%)", sign, CurrencyManager.formatFiat(this, adjustedChange), sign, pct));
                tvTodayPnL.setTextColor(android.graphics.Color.parseColor(color));
                // 全部钱包总资产数字随盈亏状态变色：盈利绿色，亏损红色
                if (tvAll != null) tvAll.setTextColor(android.graphics.Color.parseColor(color));
                Logger.info(this, "今日盈亏", "总资产 " + ChainAPI.formatValue(currentTotal) + " 快照 " + ChainAPI.formatValue(lastSnapshot) + " 净流入 " + ChainAPI.formatValue(netInflow) + " 盈亏 " + ChainAPI.formatValue(adjustedChange));
            } else {
                if (tvTodayPnL != null) tvTodayPnL.setText(getString(R.string.str_profit_loss_today) + " " + CurrencyManager.formatFiat(this, 0) + "(0.00%)");
                // 无快照时恢复默认浅蓝色
                if (tvAll != null) tvAll.setTextColor(android.graphics.Color.parseColor("#A0BBFF"));
            }
        } catch (Exception e) {
            Logger.error(this, "今日盈亏", "计算失败: " + e.getMessage(), e);
        }
    }

    /**
     * 计算今日净流入（USD）。
     *
     * 只统计今日 00:00 之后的交易；APP 内钱包互转忽略；转入为正、转出为负。
     * 数据来源为本地缓存的交易记录，若缓存未包含今日交易则暂时无法剔除。
     */
    private double calculateTodayNetInflow(java.util.Map<String, Double> prices) {
        double netInflow = 0;
        try {
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(new java.util.Date());
            java.util.List<WalletManager.WalletInfo> allWallets = WalletManager.getAllWallets(this);
            if (allWallets == null || allWallets.isEmpty()) return 0;

            java.util.Set<String> ownedAddresses = new java.util.HashSet<>();
            for (WalletManager.WalletInfo w : allWallets) {
                if (w.address != null) ownedAddresses.add(w.address.toLowerCase());
            }

            for (WalletManager.WalletInfo w : allWallets) {
                if (w == null || w.address == null || w.address.isEmpty()) continue;
                String chain = w.chain != null ? w.chain : "ETH";
                try {
                    java.util.List<String[]> txs = ChainAPI.loadTxCache(this, chain, w.address, "");
                    if (txs == null) continue;
                    for (String[] tx : txs) {
                        if (tx == null || tx.length < TxRecord.MIN_FIELD_COUNT) continue;
                        String time = tx[TxRecord.INDEX_TIME];
                        if (time == null || !time.startsWith(today)) continue;
                        String status = tx[TxRecord.INDEX_STATUS];
                        if (!"success".equals(status)) continue;

                        String from = tx[TxRecord.INDEX_FROM];
                        String to = tx[TxRecord.INDEX_TO];
                        String amountStr = tx[TxRecord.INDEX_AMOUNT];
                        String symbol = tx[TxRecord.INDEX_SYMBOL];
                        if (symbol == null || symbol.isEmpty()) symbol = chain;

                        boolean fromOwned = from != null && ownedAddresses.contains(from.toLowerCase());
                        boolean toOwned = to != null && ownedAddresses.contains(to.toLowerCase());

                        double amount = 0;
                        try {
                            if (amountStr != null) {
                                amount = Double.parseDouble(amountStr.replaceAll("[^0-9\\.]", ""));
                            }
                        } catch (Exception ignored) {}

                        double price = prices != null ? prices.getOrDefault(symbol, 0.0) : 0.0;
                        double value = amount * price;

                        if (!fromOwned && toOwned) {
                            // 外部转入 APP 内钱包：净流入增加
                            netInflow += value;
                        } else if (fromOwned && !toOwned) {
                            // 从 APP 内钱包转出到外部：净流入减少
                            netInflow -= value;
                        }
                        // 其他情况（内部转账、无法识别）忽略
                    }
                } catch (Exception e) {
                    Logger.warning(this, "净流入", "计算钱包 " + chain + " 净流入失败: " + e.getMessage());
                }
            }
            Logger.info(this, "净流入", "今日净流入: " + ChainAPI.formatValue(netInflow));
        } catch (Exception e) {
            Logger.error(this, "净流入", "calculateTodayNetInflow 失败: " + e.getMessage(), e);
        }
        return netInflow;
    }

    private void loadTradeRecords() {
        // 兼容旧调用：根据当前 Tab 加载对应记录
        if (currentTradeTab == 0) {
            loadManualRecords();
        } else {
            loadAIRecords();
        }
    }

    /**
     * 从格式化金额字符串中提取数值
     * 支持任意货币符号，如 "$1,234.56" / "¥1,234.56" / "€1.234,56"
     */
    private double parseValueString(String formatted) {
        if (formatted == null || formatted.isEmpty()) return 0;
        try {
            String cleaned = formatted.replaceAll("[^0-9\\.\\-]", "").trim();
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 获取该钱包最新的一笔收款交易哈希（尽力而为）。
     * 用于资产变动通知点击后直达该笔交易详情。
     */
    private String getLatestIncomingTxHash(String chain, String address) {
        try {
            java.util.List<String[]> txs = ChainAPI.getTransactionHistory(this, chain, address, "", 1);
            if (txs == null || txs.isEmpty()) return null;
            // 优先取最近一笔"收款"交易（我方为接收方）；无则取第一笔
            for (String[] tx : txs) {
                if (tx.length > 2 && tx[2] != null
                    && tx[2].equalsIgnoreCase(address) && tx[0] != null && !tx[0].isEmpty()) {
                    return tx[0];
                }
            }
            String first = txs.get(0)[0];
            return first != null && !first.isEmpty() ? first : null;
        } catch (Exception e) {
            Logger.warning(this, "资产变动", "获取最新交易哈希失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 加载手动记录（链上交易历史，由用户在 SendActivity/SwapActivity 等场景产生）
     *
     * 缓存优先策略：
     *  1. 立即读取本地缓存（秒开渲染）
     *  2. 后台异步拉取链上数据
     *  3. 拉到新数据后覆盖缓存并刷新 UI
     *  4. 链上无数据时保留缓存显示
     */
    private void loadManualRecords() {
        if (manualRecordsContainer == null || tvManualNoRecords == null) return;
        // 重置分页状态
        txCurrentPage = 1;
        txLoadingMore = false;
        txHasMore = true;
        manualRecordsContainer.removeAllViews();
        tvManualNoRecords.setVisibility(View.GONE);

        final String chain = WalletManager.getChain(this);
        final String address = WalletManager.getWalletAddress(this);

        // 先加载缓存（秒开，网络不好时也能显示上次的数据）
        java.util.List<String[]> cachedTxs = ChainAPI.loadTxCache(this, chain, address, "");
        if (!cachedTxs.isEmpty()) {
            for (String[] tx : cachedTxs) {
                addManualRecordItem(tx, address);
            }
            txHasMore = cachedTxs.size() >= 20;
            if (txHasMore) {
                addLoadMoreFooter();
            }
            Logger.info(this, "交易记录", "HomeActivity 缓存 " + cachedTxs.size() + " 条");
        }

        // 设置ScrollView滚动监听（分页加载）
        setupTxScrollListener();

        // 后台异步拉取最新数据
        final int page = 1;
        executor.execute(() -> {
            try {
                java.util.List<String[]> txs = ChainAPI.getTransactionHistory(this, chain, address, "", page);

                handler.post(() -> {
                    manualRecordsContainer.removeAllViews();
                    if (txs == null || txs.isEmpty()) {
                        // 网络获取为空：如果有缓存则保留缓存显示，否则显示空
                        if (cachedTxs.isEmpty()) {
                            tvManualNoRecords.setVisibility(View.VISIBLE);
                            tvManualNoRecords.setText(getString(R.string.text_no_nfts));
                            tvManualNoRecords.setOnClickListener(null);
                        } else {
                            // 恢复缓存显示
                            for (String[] tx : cachedTxs) {
                                addManualRecordItem(tx, address);
                            }
                            txHasMore = cachedTxs.size() >= 20;
                            if (txHasMore) addLoadMoreFooter();
                            Logger.info(this, "交易记录", "HomeActivity 网络0条，显示缓存 " + cachedTxs.size() + " 条");
                        }
                    } else {
                        tvManualNoRecords.setVisibility(View.GONE);
                        for (String[] tx : txs) {
                            addManualRecordItem(tx, address);
                        }
                        txHasMore = txs.size() >= 20;
                        if (txHasMore) {
                            addLoadMoreFooter();
                        }
                        ChainAPI.saveTxCache(this, chain, address, "", txs);
                        Logger.info(this, "交易记录", "HomeActivity 第" + page + "页 " + txs.size() + " 条");
                    }
                });
            } catch (Exception e) {
                Logger.error(this, "交易记录", "手动记录加载失败: " + e.getMessage());
                handler.post(() -> {
                    if (cachedTxs.isEmpty()) {
                        manualRecordsContainer.removeAllViews();
                        tvManualNoRecords.setVisibility(View.VISIBLE);
                        tvManualNoRecords.setText(getString(R.string.text_nft_loading_failed));
                        tvManualNoRecords.setOnClickListener(v -> {
                        Logger.action(this, "UI操作", "交易-加载手动记录", null);
                        loadManualRecords();
                    });
                    }
                    // 有缓存则保留，不显示错误
                });
            }
        });
        Logger.actionResult(this, "UI操作", "手动记录加载", "完成");
    }

    /**
     * 设置交易记录ScrollView滚动监听，滚动到底部自动加载下一页
     */
    private void setupTxScrollListener() {
        if (tabTrade == null) return;
        tabTrade.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (txLoadingMore || !txHasMore) return;
            int scrollY = tabTrade.getScrollY();
            int height = tabTrade.getHeight();
            int scrollViewHeight = tabTrade.getChildAt(0).getHeight();
            // 距离底部小于200px时触发加载
            if (scrollViewHeight - (scrollY + height) < 200) {
                loadMoreTxRecords();
            }
        });
    }

    /**
     * 加载更多交易记录（下一页）
     */
    private void loadMoreTxRecords() {
        if (txLoadingMore || !txHasMore) return;
        txLoadingMore = true;

        final String chain = WalletManager.getChain(this);
        final String address = WalletManager.getWalletAddress(this);
        final int nextPage = txCurrentPage + 1;

        // 更新footer文字
        if (txLoadMoreFooter instanceof TextView) {
            ((TextView) txLoadMoreFooter).setText(getString(R.string.text_load_more_by));
        }

        executor.execute(() -> {
            try {
                java.util.List<String[]> txs = ChainAPI.getTransactionHistory(this, chain, address, "", nextPage);
                handler.post(() -> {
                    // 移除旧的footer
                    if (txLoadMoreFooter != null && txLoadMoreFooter.getParent() == manualRecordsContainer) {
                        manualRecordsContainer.removeView(txLoadMoreFooter);
                    }
                    if (txs != null && !txs.isEmpty()) {
                        for (String[] tx : txs) {
                            addManualRecordItem(tx, address);
                        }
                        txCurrentPage = nextPage;
                        txHasMore = txs.size() >= 20;
                        if (txHasMore) {
                            addLoadMoreFooter();
                        }
                        Logger.info(this, "交易记录", "HomeActivity 第" + nextPage + "页 " + txs.size() + " 条");
                    } else {
                        txHasMore = false;
                        // 显示"没有更多了"
                        TextView endText = new TextView(this);
                        endText.setText(getString(R.string.text_no_more_information));
                        endText.setTextColor(0xFF6E6E7A);
                        endText.setTextSize(13);
                        endText.setGravity(Gravity.CENTER);
                        endText.setPadding(48, 32, 48, 48);
                        manualRecordsContainer.addView(endText);
                    }
                    txLoadingMore = false;
                });
            } catch (Exception e) {
                Logger.error(this, "交易记录", "加载更多失败: " + e.getMessage());
                handler.post(() -> {
                    if (txLoadMoreFooter instanceof TextView) {
                        ((TextView) txLoadMoreFooter).setText(getString(R.string.text_nft_loading_failed));
                        txLoadMoreFooter.setOnClickListener(v -> {
                        Logger.action(this, "UI操作", "交易-加载更多", null);
                        txLoadingMore = false;
                        loadMoreTxRecords();
                    });
                    }
                    txLoadingMore = false;
                });
            }
        });
    }

    /**
     * 添加"上滑加载更多"底部提示
     */
    private void addLoadMoreFooter() {
        if (txLoadMoreFooter == null) {
            txLoadMoreFooter = new TextView(this);
        }
        ((TextView) txLoadMoreFooter).setText(getString(R.string.text_swipe_up_to_load));
        ((TextView) txLoadMoreFooter).setTextColor(0xFF6E6E7A);
        ((TextView) txLoadMoreFooter).setTextSize(13);
        ((TextView) txLoadMoreFooter).setGravity(Gravity.CENTER);
        ((TextView) txLoadMoreFooter).setPadding(48, 32, 48, 32);
        if (txLoadMoreFooter.getParent() != manualRecordsContainer) {
            manualRecordsContainer.addView(txLoadMoreFooter);
        }
    }

    /**
     * 加载 AI 操作记录（包含 AI 工具调用、分析任务、自动交易等）
     */
    private void loadAIRecords() {
        if (aiRecordsContainer == null || tvAiNoRecords == null) return;
        // 防止重复调用导致状态错乱
        if ("加载中...".equals(aiRecordsContainer.getTag())) return;
        aiRecordsContainer.setTag("加载中...");
        aiRecordsContainer.removeAllViews();
        tvAiNoRecords.setVisibility(View.GONE);
        if (cbShowOnlyAiTrades != null) cbShowOnlyAiTrades.setVisibility(View.VISIBLE);

        TextView loadingText = new TextView(this);
        loadingText.setText(getString(R.string.text_memuat));
        loadingText.setTextColor(0xFF6E6E7A);
        loadingText.setTextSize(14);
        loadingText.setGravity(Gravity.CENTER);
        loadingText.setPadding(48, 48, 48, 48);
        aiRecordsContainer.addView(loadingText);

        final boolean showOnlyTrades = cbShowOnlyAiTrades != null && cbShowOnlyAiTrades.isChecked();
        final long startTime = System.currentTimeMillis();

        aiRecordsExecutor.execute(() -> {
            try {
                List<AIOperationLog> logs = loadAIOperationLogs();

                if (showOnlyTrades) {
                    List<AIOperationLog> filtered = new ArrayList<>();
                    for (AIOperationLog log : logs) {
                        if ("trade".equals(log.type) && "success".equals(log.status)) {
                            filtered.add(log);
                        }
                    }
                    logs = filtered;
                }

                final List<AIOperationLog> finalLogs = logs;
                long elapsed = System.currentTimeMillis() - startTime;
                Logger.info(this, "AI操作记录", "加载到 " + finalLogs.size() + " 条记录，耗时 " + elapsed + "ms");
                handler.post(() -> {
                    aiRecordsContainer.setTag(null);
                    aiRecordsContainer.removeAllViews();
                    if (finalLogs.isEmpty()) {
                        tvAiNoRecords.setVisibility(View.VISIBLE);
                        tvAiNoRecords.setText(showOnlyTrades ? getString(R.string.label_no_successful_ai_transactions) : getString(R.string.label_no_record_of_ai_operation));
                        tvAiNoRecords.setOnClickListener(null);
                    } else {
                        tvAiNoRecords.setVisibility(View.GONE);
                        for (AIOperationLog log : finalLogs) {
                            try {
                                addAIOperationLogItem(log);
                            } catch (Exception itemErr) {
                                Logger.error(this, "AI操作记录", "渲染单条记录失败: " + itemErr.getMessage(), itemErr);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                Logger.error(this, "AI操作记录", "加载失败: " + e.getMessage(), e);
                handler.post(() -> {
                    aiRecordsContainer.setTag(null);
                    aiRecordsContainer.removeAllViews();
                    tvAiNoRecords.setVisibility(View.VISIBLE);
                    tvAiNoRecords.setText(getString(R.string.text_nft_loading_failed));
                    tvAiNoRecords.setOnClickListener(v -> {
                        Logger.action(this, "UI操作", "交易-加载AI记录", null);
                        loadAIRecords();
                    });
                });
            }
        });
    }

    /**
     * 加载 AI 操作日志，并兼容合并旧版 TradeRecord 数据（按 txHash 去重）
     */
    private List<AIOperationLog> loadAIOperationLogs() {
        List<AIOperationLog> logs = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        try {
            logs.addAll(AIOperationLogManager.loadAll(this));
            long t1 = System.currentTimeMillis();

            List<TradeRecord> oldRecords = TradeRecord.loadAll(this);
            long t2 = System.currentTimeMillis();

            java.util.Set<String> seenTxHashes = new java.util.HashSet<>();
            for (AIOperationLog log : logs) {
                if (log.txHash != null && !log.txHash.isEmpty()) {
                    seenTxHashes.add(log.txHash);
                }
            }
            for (TradeRecord r : oldRecords) {
                if (r.txHash != null && !r.txHash.isEmpty() && seenTxHashes.contains(r.txHash)) {
                    continue;
                }
                logs.add(AIOperationLog.fromTradeRecord(r));
            }
            Collections.sort(logs, (o1, o2) -> Long.compare(o2.timestamp, o1.timestamp));
            long t3 = System.currentTimeMillis();
            Logger.info(this, "AI操作记录", "loadAIOperationLogs 分阶段耗时: AI日志 " + (t1 - t0) + "ms, 旧交易 " + (t2 - t1) + "ms, 合并排序 " + (t3 - t2) + "ms, 总计 " + (t3 - t0) + "ms, 条数 " + logs.size());
        } catch (Exception e) {
            Logger.error(this, "AI操作记录", "合并日志失败: " + e.getMessage(), e);
        }
        return logs;
    }

    private void addTradeRecordItem(String[] tx, String myAddress) {
        // 兼容旧调用，转发到手动记录
        addManualRecordItem(tx, myAddress);
    }

    private void addManualRecordItem(String[] tx, String myAddress) {
        View item = getLayoutInflater().inflate(R.layout.item_transaction, null);

        String from = tx.length > 1 ? tx[1] : "";
        boolean isSend = from.equalsIgnoreCase(myAddress);

        // 代币 symbol（parseTransferLog 返回的第 8 个字段，index=7）
        String symbol = tx.length > 7 ? tx[7] : "";
        if (symbol == null || symbol.isEmpty()) symbol = "";

        // 交易类型（index=6）：transfer, contract_call, approval
        String txType = tx.length > 6 ? tx[6] : "transfer";

        TextView tvType = item.findViewById(R.id.tvTxType);
        TextView tvIcon = item.findViewById(R.id.tvTxTypeIcon);

        // 根据交易类型设置不同的图标和标签
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
            // transfer: 转入/转出
            typeLabel = (isSend ? "发送" : "接收") + (symbol.isEmpty() ? "" : " " + symbol);
            iconText = isSend ? "↑" : "↓";
            iconColor = isSend ? R.color.text_red : R.color.text_green;
            bgColor = isSend ? R.color.red : R.color.green;
        }

        tvType.setText(typeLabel);
        tvIcon.setText(iconText);
        tvIcon.setTextColor(getResources().getColor(iconColor));
        tvIcon.getBackground().setTint(getResources().getColor(bgColor));
        tvIcon.getBackground().setAlpha(30);

        String time = tx.length > 4 ? tx[4] : "";
        ((TextView) item.findViewById(R.id.tvTxTime)).setText(time);

        // 金额附带代币 symbol，如"+50 R-MAB"、"-0.001 BNB"
        String amount;
        int amountColor;
        String valStr = (tx.length > 3 && tx[3] != null) ? tx[3] : "";
        if ("approval".equals(txType) || valStr.isEmpty()) {
            amount = "--";
            amountColor = R.color.text_secondary;
        } else if ("contract_call".equals(txType)) {
            amount = (isSend ? "-" : "+") + valStr + (symbol.isEmpty() ? "" : " " + symbol);
            amountColor = isSend ? R.color.text_red : R.color.text_green;
        } else {
            amount = (isSend ? "-" : "+") + valStr + (symbol.isEmpty() ? "" : " " + symbol);
            amountColor = isSend ? R.color.text_red : R.color.text_green;
        }
        TextView tvAmount = item.findViewById(R.id.tvTxAmount);
        tvAmount.setText(amount);
        tvAmount.setTextColor(getResources().getColor(amountColor));

        String status = tx.length > 5 ? tx[5] : "success";
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
            Logger.action(this, "UI操作", "交易记录-" + txHash, null);
            Intent intent = new Intent(this, TxDetailActivity.class);
            intent.putExtra(TxDetailActivity.EXTRA_TX_HASH, txHash);
            intent.putExtra(TxDetailActivity.EXTRA_CHAIN, WalletManager.getChain(this));
            intent.putExtra(TxDetailActivity.EXTRA_TX_DATA, txData);
            startActivity(intent);
        });

        manualRecordsContainer.addView(item);
    }

    /**
     * 添加 AI 操作记录条目（支持 trade / tool / analysis / notify 等多种类型）
     */
    private void addAIOperationLogItem(AIOperationLog log) {
        View item = getLayoutInflater().inflate(R.layout.item_transaction, null);

        TextView tvType = item.findViewById(R.id.tvTxType);
        TextView tvIcon = item.findViewById(R.id.tvTxTypeIcon);

        String typeLabel;
        String iconText;
        int iconColor;
        int bgColor;
        int amountColor;
        String amountText;

        if ("trade".equals(log.type)) {
            boolean isBuy = "BUY".equalsIgnoreCase(log.side);
            typeLabel = isBuy ? "AI 买入" : "AI 卖出";
            iconText = isBuy ? "↑" : "↓";
            iconColor = isBuy ? R.color.text_green : R.color.text_red;
            bgColor = isBuy ? R.color.green : R.color.red;
            amountColor = isBuy ? R.color.text_green : R.color.text_red;
            DecimalFormat df = new DecimalFormat("#.####");
            amountText = (isBuy ? "+" : "-") + df.format(log.amount) + " " + (log.pair != null ? log.pair : "");
        } else if ("analysis".equals(log.type)) {
            typeLabel = "AI 分析";
            iconText = "AI";
            iconColor = R.color.text_blue;
            bgColor = R.color.text_blue;
            amountColor = R.color.text_secondary;
            amountText = log.chain != null && !log.chain.isEmpty() ? log.chain : "--";
        } else if ("notify".equals(log.type)) {
            typeLabel = "AI 通知";
            iconText = "!";
            iconColor = R.color.text_secondary;
            bgColor = R.color.text_secondary;
            amountColor = R.color.text_secondary;
            amountText = "--";
        } else {
            // tool / 其他
            typeLabel = getToolDisplayName(log.toolName);
            iconText = "⚙";
            iconColor = R.color.text_secondary;
            bgColor = R.color.text_secondary;
            amountColor = R.color.text_secondary;
            amountText = log.chain != null && !log.chain.isEmpty() ? log.chain : "--";
        }

        tvType.setText(typeLabel);
        tvIcon.setText(iconText);
        tvIcon.setTextColor(getResources().getColor(iconColor));
        tvIcon.getBackground().setTint(getResources().getColor(bgColor));
        tvIcon.getBackground().setAlpha(30);

        // 时间
        String time = "";
        if (log.timestamp > 0) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault());
            time = sdf.format(new java.util.Date(log.timestamp));
        }
        ((TextView) item.findViewById(R.id.tvTxTime)).setText(time);

        // 副标题：用自然语言描述这次操作
        String desc = "";
        if (log.description != null && !log.description.isEmpty()) {
            desc = log.description;
        } else if ("trade".equals(log.type) && log.pair != null && !log.pair.isEmpty()) {
            desc = log.pair;
        } else if (log.toolName != null && !log.toolName.isEmpty()) {
            desc = getToolDisplayName(log.toolName);
        }
        if (desc.length() > 60) {
            desc = desc.substring(0, 57) + "...";
        }
        TextView tvTxHash = item.findViewById(R.id.tvTxHash);
        if (tvTxHash != null) {
            tvTxHash.setText(desc);
            tvTxHash.setVisibility(desc.isEmpty() ? View.GONE : View.VISIBLE);
        }

        // 数量/摘要
        TextView tvAmount = item.findViewById(R.id.tvTxAmount);
        tvAmount.setText(amountText);
        tvAmount.setTextColor(getResources().getColor(amountColor));

        // 状态
        TextView tvStatus = item.findViewById(R.id.tvTxStatus);
        if ("success".equalsIgnoreCase(log.status)) {
            tvStatus.setText(getString(R.string.text_berhasil));
            tvStatus.setTextColor(getResources().getColor(R.color.text_green));
        } else if ("failed".equalsIgnoreCase(log.status) || "error".equalsIgnoreCase(log.status)) {
            tvStatus.setText(getString(R.string.text_kalah));
            tvStatus.setTextColor(getResources().getColor(R.color.text_red));
        } else {
            tvStatus.setText(getString(R.string.text_processing));
            tvStatus.setTextColor(getResources().getColor(R.color.text_secondary));
        }

        // 点击查看详情弹窗
        item.setOnClickListener(v -> {
            Logger.action(this, "UI操作", "AI操作记录-" + log.type, null);
            showAIOperationDetailDialog(log);
        });

        aiRecordsContainer.addView(item);
    }

    /**
     * 显示 AI 操作记录详情弹窗
     */
    private void showAIOperationDetailDialog(AIOperationLog log) {
        StringBuilder msg = new StringBuilder();
        msg.append("类型：").append(getLogTypeDisplayName(log.type)).append("\n");
        if (log.toolName != null && !log.toolName.isEmpty()) {
            msg.append("工具：").append(getToolDisplayName(log.toolName)).append("\n");
        }
        if (log.chain != null && !log.chain.isEmpty()) {
            msg.append("链：").append(log.chain).append("\n");
        }
        msg.append("状态：").append(getLogStatusDisplayName(log.status)).append("\n");
        msg.append("时间：").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(new java.util.Date(log.timestamp))).append("\n\n");
        if (log.description != null && !log.description.isEmpty()) {
            msg.append("描述：").append(log.description).append("\n\n");
        }
        if (log.params != null && !log.params.isEmpty() && !"{}".equals(log.params)) {
            msg.append("参数：").append(log.params).append("\n\n");
        }
        if (log.result != null && !log.result.isEmpty()) {
            msg.append("结果：").append(log.result);
        }

        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.title_ai_operation_details))
            .setMessage(msg.toString())
            .setPositiveButton(getString(R.string.btn_off), null)
            .show();
    }

    /**
     * 导出 AI 操作记录为记事本文件并支持分享到第三方平台
     */
    private void exportAIRecords() {
        List<AIOperationLog> logs = AIOperationLogManager.loadAll(this);
        if (logs == null || logs.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_ai_action_records), Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            StringBuilder sb = new StringBuilder();
            sb.append("═══════════════════════════════════════\n");
            sb.append("AI 加密货币钱包 - AI 操作记录导出\n");
            sb.append("导出时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()).format(new Date())).append("\n");
            sb.append("记录条数: ").append(logs.size()).append("\n");
            sb.append("═══════════════════════════════════════\n\n");

            for (int i = 0; i < logs.size(); i++) {
                AIOperationLog log = logs.get(i);
                sb.append("【").append(getLogTypeDisplayName(log.type)).append("】");
                sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date(log.timestamp))).append("\n");
                if (log.toolName != null && !log.toolName.isEmpty()) {
                    sb.append("工具: ").append(getToolDisplayName(log.toolName)).append("\n");
                }
                if (log.chain != null && !log.chain.isEmpty()) {
                    sb.append("链: ").append(log.chain).append("\n");
                }
                sb.append("状态: ").append(getLogStatusDisplayName(log.status)).append("\n");
                if (log.description != null && !log.description.isEmpty()) {
                    sb.append("描述: ").append(log.description).append("\n");
                }
                if (log.params != null && !log.params.isEmpty() && !"{}".equals(log.params)) {
                    sb.append("参数: ").append(log.params).append("\n");
                }
                if (log.result != null && !log.result.isEmpty()) {
                    sb.append("结果: ").append(log.result).append("\n");
                }
                if (log.txHash != null && !log.txHash.isEmpty()) {
                    sb.append("交易哈希: ").append(log.txHash).append("\n");
                }
                sb.append("───────────────────────────────────────\n\n");
            }

            sb.append("（由 AI 加密货币钱包 - 红魔团队开发）");

            String fileName = "AI操作记录_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
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

            Logger.info(this, "AI操作记录", "导出成功: " + fileName + "，共 " + logs.size() + " 条");
        } catch (Exception e) {
            Logger.error(this, "AI操作记录", "导出失败", e);
            Toast.makeText(this, getString(R.string.toast_export_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 将 AI 工具名映射为中文显示名称
     */
    private String getToolDisplayName(String toolName) {
        if (toolName == null || toolName.isEmpty()) return "AI 操作";
        switch (toolName) {
            case "get_wallet_address": return "查询钱包地址";
            case "get_wallet_assets": return "获取钱包资产";
            case "get_native_balance": return "查询原生币余额";
            case "get_token_balance": return "查询代币余额";
            case "get_token_price": return "查询代币价格";
            case "get_position": return "查询持仓";
            case "get_market_data": return "获取行情数据";
            case "get_safety_status": return "查询安全状态";
            case "call_contract_read": return "合约只读调用";
            case "call_contract_write": return "合约写入调用";
            case "swap_tokens": return "代币兑换";
            case "cross_chain_swap": return "跨链兑换";
            case "authorize_cross_chain_buy": return "授权跨链买入";
            case "approve_token": return "代币授权";
            case "send_native": return "原生币转账";
            case "ask_user": return "请求用户确认";
            case "search_news": return "搜索新闻";
            case "fetch_web_page": return "获取网页内容";
            case "browser_open_url": return "打开浏览器链接";
            case "browser_get_state": return "获取浏览器状态";
            case "browser_click": return "浏览器点击";
            case "browser_input": return "浏览器输入";
            case "browser_evaluate": return "浏览器执行脚本";
            case "browser_close": return "关闭浏览器页面";
            case "get_dapp_address": return "获取 DApp 地址";
            case "get_function_signature": return "获取函数签名";
            case "open_create_wallet": return "创建钱包";
            case "list_wallets": return "列出钱包";
            case "switch_wallet": return "切换钱包";
            case "query_dapp_whitelist": return "查询 DApp 白名单";
            case "request_dapp_whitelist": return "申请 DApp 白名单";
            case "remove_dapp_whitelist": return "移除 DApp 白名单";
            default: return toolName;
        }
    }

    /**
     * 将 AI 操作类型映射为中文显示名称
     */
    private String getLogTypeDisplayName(String type) {
        if ("trade".equals(type)) return "AI 交易";
        if ("tool".equals(type)) return "AI 工具调用";
        if ("analysis".equals(type)) return "AI 分析";
        if ("notify".equals(type)) return "AI 通知";
        return type != null ? type : "未知";
    }

    /**
     * 将 AI 操作状态映射为中文显示名称
     */
    private String getLogStatusDisplayName(String status) {
        if ("success".equalsIgnoreCase(status)) return "成功";
        if ("failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status)) return "失败";
        if ("pending".equalsIgnoreCase(status)) return "处理中";
        return status != null ? status : "未知";
    }

    private String getExplorerTxUrl(String chain, String txHash) {
        Map<String, String> explorers = new HashMap<>();
        explorers.put("ETH", "https://etherscan.io/tx/");
        explorers.put("BNB", "https://bscscan.com/tx/");
        explorers.put("MATIC", "https://polygonscan.com/tx/");
        explorers.put("ARB", "https://arbiscan.io/tx/");
        explorers.put("AVAX", "https://snowtrace.io/tx/");
        explorers.put("FTM", "https://ftmscan.com/tx/");
        explorers.put("CORE", "https://scan.coredao.org/tx/");
        explorers.put("CELO", "https://celoscan.io/tx/");
        explorers.put("ONE", "https://explorer.harmony.one/tx/");
        explorers.put("GLMR", "https://moonscan.io/tx/");
        explorers.put("KAVA", "https://kavascan.com/tx/");
        String base = explorers.get(chain);
        return base != null ? base + txHash : null;
    }

    private void initSettings() {
        findViewById(R.id.btnViewLogs).setOnClickListener(v -> {
            Logger.action(this, "UI操作", "设置-查看日志", null);
            startActivity(new Intent(HomeActivity.this, LogViewerActivity.class));
            Logger.actionResult(HomeActivity.this, "UI操作", "日志导出", "完成");
        });

        findViewById(R.id.btnNodeSelector).setOnClickListener(v -> {
            Logger.action(this, "UI操作", "设置-节点选择器", null);
            Intent intent = new Intent(HomeActivity.this, NodeSelectorActivity.class);
            intent.putExtra("chain", WalletManager.getChain(HomeActivity.this));
            startActivity(intent);
            Logger.actionResult(HomeActivity.this, "UI操作", "节点选择器", "已打开");
        });
    }

    private void showAboutDialog() {
        // 跳转到独立的关于页面
        startActivity(new Intent(this, AboutActivity.class));
        Logger.actionResult(this, "UI操作", "关于", "已打开");
    }

    private void showSecurityCenter() {
        startActivity(new Intent(this, SecurityCenterActivity.class));
        Logger.actionResult(this, "UI操作", "安全中心", "已打开");
    }

    private void showManageHiddenDialog() {
        String chain = WalletManager.getChain(this);
        Set<String> hidden = getHiddenTokens(chain);
        if (hidden.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_hidden_tokens), Toast.LENGTH_SHORT).show();
            return;
        }

        View view = getLayoutInflater().inflate(R.layout.dialog_hidden_tokens, null);
        LinearLayout container = view.findViewById(R.id.hiddenTokensContainer);

        Map<String, String[]> knownTokens = new HashMap<>();
        String[][] customs = WalletManager.getCustomTokens(this, chain);
        for (String[] t : customs) {
            if (t.length >= 3) knownTokens.put(t[2].toLowerCase(), t);
        }

        for (String contract : hidden) {
            String[] info = knownTokens.get(contract);
            String symbol = info != null ? info[0] : contract.substring(0, Math.min(6, contract.length())) + "...";
            String name = info != null ? info[1] : contract;

            TextView item = new TextView(this);
            item.setText(symbol + "  " + name + "\n" + contract);
            item.setTextColor(0xffffffff);
            item.setTextSize(13);
            item.setPadding(24, 24, 24, 24);
            item.setBackground(getResources().getDrawable(R.drawable.card_background));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 16;
            item.setLayoutParams(lp);
            item.setOnClickListener(v -> {
                Logger.action(this, "UI操作", "隐藏代币-" + symbol, null);
                new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                    .setTitle(getString(R.string.title_resume_display))
                    .setMessage(getString(R.string.msg_do_you_want_to, symbol))
                    .setPositiveButton(getString(R.string.label_recover), (d, w) -> {
                        unhideToken(chain, contract);
                    })
                    .setNegativeButton(getString(R.string.btn_s_decline), null)
                    .show();
            });
            container.addView(item);
        }

        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.title_hidden_tokens, ChainAPI.getChainName(chain)))
            .setView(view)
            .setPositiveButton(getString(R.string.btn_restore_all), (dialog, which) -> showAllHiddenTokens(chain))
            .setNegativeButton(getString(R.string.btn_off), null)
            .show();
        Logger.actionResult(this, "UI操作", "隐藏代币管理", "弹窗已打开");
    }

    private void unhideToken(String chain, String contractAddr) {
        android.content.SharedPreferences prefs = getSharedPreferences("hidden_tokens", Context.MODE_PRIVATE);
        String key = "hidden_" + chain;
        Set<String> hidden = getHiddenTokens(chain);
        hidden.remove(contractAddr.toLowerCase());
        prefs.edit().putString(key, TextUtils.join(",", hidden)).apply();
        Toast.makeText(this, getString(R.string.toast_display_resumed), Toast.LENGTH_SHORT).show();
        loadAssets();
    }

    @Override
    public void onBackPressed() {
        // 若从上层 Activity（如 AIAgentActivity 的"操作记录"）进入本页，
        // 按返回应回到上一层；仅当本页是任务根（正常主界面入口）时才退出 APP
        if (!isTaskRoot()) {
            finish();
            return;
        }
        finishAffinity();
    }

    private Set<String> getHiddenTokens(String chain) {
        Set<String> hidden = new HashSet<>();
        android.content.SharedPreferences prefs = getSharedPreferences("hidden_tokens", Context.MODE_PRIVATE);
        String key = "hidden_" + chain;
        String stored = prefs.getString(key, "");
        if (!stored.isEmpty()) {
            for (String addr : stored.split(",")) {
                if (!addr.trim().isEmpty()) {
                    hidden.add(addr.trim().toLowerCase());
                }
            }
        }
        return hidden;
    }

    private void hideToken(String chain, String contractAddr) {
        android.content.SharedPreferences prefs = getSharedPreferences("hidden_tokens", Context.MODE_PRIVATE);
        String key = "hidden_" + chain;
        Set<String> hidden = getHiddenTokens(chain);
        hidden.add(contractAddr.toLowerCase());
        prefs.edit().putString(key, TextUtils.join(",", hidden)).apply();
        Toast.makeText(this, getString(R.string.toast_hidden), Toast.LENGTH_SHORT).show();
        loadAssets();
    }

    private void showAllHiddenTokens(String chain) {
        android.content.SharedPreferences prefs = getSharedPreferences("hidden_tokens", Context.MODE_PRIVATE);
        String key = "hidden_" + chain;
        prefs.edit().remove(key).apply();
        Toast.makeText(this, getString(R.string.toast_all_tokens_shown), Toast.LENGTH_SHORT).show();
        loadAssets();
    }

    private static byte[] readAllBytes(java.io.InputStream is) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(8192);
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static int calculateSampleSize(int width, int height, int reqWidth, int reqHeight) {
        if (width <= 0 || height <= 0) return 1;
        int sample = 1;
        while ((width / sample) > reqWidth || (height / sample) > reqHeight) {
            sample *= 2;
        }
        return sample;
    }

    /**
     * 从 AIAgentActivity 的 SharedPreferences 读取 AI 状态并更新卡片
     */
    private void updateAIStatusCard() {
        try {
            Logger.info(this, "HomeActivity", "updateAIStatusCard started");
            SharedPreferences prefs = getSharedPreferences("ai_agent_prefs", Context.MODE_PRIVATE);
            boolean isRunning = prefs.getBoolean("agent_running", false);
            double dailyPnL = prefs.getFloat("agent_daily_pnl", 0);
            int tradeCount = prefs.getInt("agent_trade_count", 0);
            int winCount = prefs.getInt("agent_win_count", 0);
            int closedCount = prefs.getInt("agent_closed_count", 0);
            String chain = prefs.getString("agent_chain", "ETH");

            Logger.info(this, "HomeActivity", String.format("AI state: running=%b pnl=%.2f trades=%d", isRunning, dailyPnL, tradeCount));

            if (aiStatusCard == null) {
                Logger.warning(this, "HomeActivity", "aiStatusCard is null!");
                return;
            }

            // Always show the card (AI is a core feature)
            aiStatusCard.setVisibility(View.VISIBLE);

            // Update status dot and text
            if (aiStatusDot != null) {
                aiStatusDot.setBackgroundResource(isRunning ? R.drawable.dot_green : R.drawable.dot_red);
            }
            if (tvAiStatusText != null) {
                tvAiStatusText.setText(isRunning ? getString(R.string.label_ai_running) : getString(R.string.str_ai_not_started));
            }
            if (btnStartAIFromHome != null) {
                btnStartAIFromHome.setText(isRunning ? getString(R.string.str_manage) : getString(R.string.str_start));
            }

            // Update stats
            if (tvAiCardPnL != null) {
                String sign = dailyPnL >= 0 ? "+" : "";
                tvAiCardPnL.setText(sign + CurrencyManager.formatFiat(this, dailyPnL));
                tvAiCardPnL.setTextColor(dailyPnL >= 0 ? 0xFF00D084 : 0xFFFF4757);
            }
            if (tvAiCardWinRate != null) {
                if (closedCount > 0) {
                    tvAiCardWinRate.setText(String.format("%.1f%%", (double) winCount / closedCount * 100));
                } else {
                    tvAiCardWinRate.setText("--");
                }
            }
            if (tvAiCardTrades != null) {
                tvAiCardTrades.setText(String.valueOf(tradeCount));
            }
            if (tvAiCardChain != null) {
                tvAiCardChain.setText(chain);
            }
            Logger.info(this, "HomeActivity", "updateAIStatusCard completed successfully");
        } catch (Exception e) {
            Logger.error(this, "HomeActivity", "updateAIStatusCard failed: " + e.getMessage(), e);
        }
    }

    /**
     * 检查余额是否低于 $200，显示/隐藏加仓提醒
     */
    private void updateLowBalanceCard() {
        try {
            if (lowBalanceCard == null) return;

            SharedPreferences prefs = getSharedPreferences("ai_agent_prefs", Context.MODE_PRIVATE);
            boolean isRunning = prefs.getBoolean("agent_running", false);

            // Only show if AI is not running
            if (isRunning) {
                lowBalanceCard.setVisibility(View.GONE);
                return;
            }

            // Check total balance on background thread（使用和资产页相同的批量查询方式）
            executor.execute(() -> {
                try {
                    String chain = WalletManager.getChain(HomeActivity.this);
                    String address = WalletManager.getWalletAddress(HomeActivity.this);
                    double nativeBalance = ChainAPI.getNativeBalance(HomeActivity.this, chain, address);
                    java.util.Map<String, Double> prices = ChainAPI.getPrices(HomeActivity.this);
                    double price = prices.getOrDefault(chain, 0.0);
                    double totalValue = nativeBalance * price;

                    // 用批量查询查 R-MAB（和资产页相同方式，getERC20Balance 单查容易失败）
                    double rmabBalance = 0;
                    try {
                        java.util.List<String[]> allTokens = ChainAPI.getAllTokenBalances(HomeActivity.this, "BNB", address);
                        if (allTokens != null) {
                            for (String[] token : allTokens) {
                                if (token.length >= 5) {
                                    String contract = token[4] != null ? token[4].toLowerCase() : "";
                                    String symbol = token[0] != null ? token[0].toUpperCase() : "";
                                    if (contract.equalsIgnoreCase(AppConfig.SMART_TOKEN_CONTRACT)
                                        || symbol.contains("R-MAB") || symbol.contains("RMAB")) {
                                        try { rmabBalance = Double.parseDouble(token[2]); } catch (Exception ignored) {}
                                        break;
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) {}

                    final boolean balanceOk = totalValue >= AppConfig.MIN_BALANCE_FOR_AI
                        || rmabBalance >= AppConfig.RMAB_THRESHOLD_FOR_AI;

                    handler.post(() -> {
                        if (lowBalanceCard != null) {
                            if (!balanceOk) {
                                lowBalanceCard.setVisibility(View.VISIBLE);
                            } else {
                                lowBalanceCard.setVisibility(View.GONE);
                            }
                        }
                    });
                } catch (Exception e) {
                    // ignore
                }
            });
        } catch (Exception e) {
            // ignore - keep card hidden
        }
    }
}