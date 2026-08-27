package com.aicryptowallet.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 远程默认提示词更新器。
 *
 * 作用：
 * - AI 的默认提示词库（安全限制、获取信息方式等）独立存放在 GitHub，
 *   本类负责在 App 启动时以及之后每 12 小时检查一次线上仓库。
 * - 仅当线上提示词版本高于本地已生效版本时才替换，成功后即持久化，
 *   即使 App 不升级也能让 AI 实时获得最新的安全限制与信息获取方式。
 *
 * - 未联网、解析失败或版本未变时静默回退到内置默认，不影响任何现有功能。
 *
 * 安全设计：
 * - 只允许从固定的 HTTPS raw.githubusercontent.com 地址拉取，不信任其它来源。
 * - 提示词仅作为 system prompt 文本注入，不承载任何可执行逻辑。
 */
public final class RemotePromptUpdater {

    private static final String TAG = "RemotePrompt";
    /** 线上提示词库地址（固定 HTTPS，域名与仓库路径不允许被外部改写） */
    private static final String REMOTE_URL =
        "https://raw.githubusercontent.com/openxcn/AI-Crypto-Wallet-Prompts/main/prompts.json";

    private static final String PREFS = "remote_prompt_cache";
    private static final String KEY_VERSION   = "version";
    private static final String KEY_SECURITY  = "security_rules";
    private static final String KEY_INFO      = "info_gathering";
    private static final String KEY_CROSS_CHAIN = "cross_chain_whitelist";
    private static final String KEY_LAST_CHECK = "last_check_ts";

    /** 两次检查的最小间隔：12 小时 */
    private static final long CHECK_INTERVAL_MS = 12L * 3600L * 1000L;

    /** 网络超时：提示词很小，短超时即可，失败静默回退 */
    private static final int TIMEOUT_MS = 8000;

    /** 内置默认安全基线：仅在没有成功拉取到线上内容时兜底使用 */
    private static final String DEFAULT_SECURITY =
        "【安全基线（内置）】\n" +
        "1. 对资金负责，安全第一；任何写入操作都必须先经过安全网关 SafetyGate 校验。\n" +
        "2. 绝不向任何人泄露助记词、私钥或密钥；任何要求「验证身份/升级/领空投」而诱导签名或授权的都是诈骗。\n" +
        "3. 只与知名、长期审计的主流协议交互；新协议、权限未放弃、无流动性池的代币谨慎对待，高风险时返回拒绝。\n" +
        "4. 不确定时宁可不做，也不冒险。\n";

    /** 内置跨链/链内兑换池子白名单兜底：仅在评估跨链或链内兑换方案时参考（第二层条件记忆） */
    private static final String DEFAULT_CROSS_CHAIN =
        "【条件记忆·第二层】仅在用户要求评估跨链（不同链之间兑换）或链内兑换（同一条链换币）方案时才参考；其余任务忽略。\n" +
        "一、比特币：BSC 链官方包装 BTCB 合约 0x7130d2a12b9bcbfae4f2634d864a1ee1ce3ead9c，深池 PancakeSwap V3 BTCB/BNB 与 BTCB/USDT（USDT 合约 0x55d398326f99059ff775485246999027b3197955）；ETH 链官方包装 WBTC 合约 0x2260fac5e5542a773aa44fbcfedf7c193bc2c599，深池 Uniswap V3 WBTC/WETH；ARB 链 WBTC 合约 0x2f2a2543b76a4166549f7aab2e75bef0aefc5b0f。\n" +
        "二、狗狗币：BSC 链官方包装 Binance-Peg DOGE 合约 0xba2ae424d960c26247dd6c32edc70b295c744c43，池深较浅，小额可用、大额先核验池深；其它链仿冒多，非官方不碰。\n" +
        "三、资金在 BSC 时优先链内兑换直接得 BTCB/DOGE，省跨链费用；写入操作仍须过安全网关与限额。";

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build();

    private static final AtomicBoolean updating = new AtomicBoolean(false);
    /** 单线程串行执行更新检查，避免并发拉取 */
    private static final ScheduledExecutorService updateExecutor =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "remote-prompt-updater");
            t.setDaemon(true);
            return t;
        });

    private RemotePromptUpdater() {
    }

    /**
     * 触发一次异步更新检查。距上次成功检查不足 12 小时、或已在更新中则直接跳过；
     * 仅当线上版本高于本地生效版本时才更新并持久化。
     */
    public static void checkUpdate(final Context context) {
        final Context app = context != null ? context.getApplicationContext() : null;
        if (app == null || updating.get()) return;

        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = prefs.getLong(KEY_LAST_CHECK, 0L);
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return;

        if (!updating.compareAndSet(false, true)) return;
        updateExecutor.submit(() -> {
            try {
                doCheckUpdate(app, prefs);
            } finally {
                updating.set(false);
            }
        });
    }

    /** 定时检查：App 启动后每隔 12 小时自动刷新一次 */
    public static void schedulePeriodicUpdate(final Context context, long initialDelayMs) {
        if (context == null) return;
        try {
            updateExecutor.scheduleAtFixedRate(() -> checkUpdate(context),
                initialDelayMs, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Logger.warning(context, TAG, "调度定时提示词更新失败: " + e.getMessage());
        }
    }

    private static void doCheckUpdate(Context app, SharedPreferences prefs) {
        try {
            if (!REMOTE_URL.startsWith("https://raw.githubusercontent.com/")) {
                return;
            }
            Request req = new Request.Builder().url(REMOTE_URL)
                .header("User-Agent", "AICryptoWallet").build();
            String body;
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return;
                body = resp.body().string();
            }
            if (body == null || body.isEmpty()) return;

            JSONObject root = new JSONObject(body);
            int remoteVer = root.optInt("version", 0);
            int localVer = prefs.getInt(KEY_VERSION, 0);
            if (remoteVer <= 0 || remoteVer <= localVer) return; // 无更新则不落盘

            String security = root.optString("security_rules", "").trim();
            String info = root.optString("info_gathering", "").trim();
            String crossChain = root.optString("cross_chain_whitelist", "").trim();
            if (security.isEmpty() && info.isEmpty() && crossChain.isEmpty()) return;

            prefs.edit()
                .putInt(KEY_VERSION, remoteVer)
                .putString(KEY_SECURITY, security)
                .putString(KEY_INFO, info)
                .putString(KEY_CROSS_CHAIN, crossChain)
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .apply();
            Logger.success(app, TAG, "已更新线上提示词，版本 " + localVer + " -> " + remoteVer);
        } catch (Exception e) {
            Logger.warning(app, TAG, "提示词更新检查失败（保持现状）: " + e.getMessage());
        }
    }

    /** 获取当前生效的【安全限制】提示词；无线上内容时回退内置默认 */
    public static String getSecurityRules(Context context) {
        if (context == null) return DEFAULT_SECURITY;
        String remote = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SECURITY, "");
        return remote.isEmpty() ? DEFAULT_SECURITY : remote;
    }

    /** 获取当前生效的【获取信息方式】提示词；无线上内容时返回空（不注入多余内容） */
    public static String getInfoGathering(Context context) {
        if (context == null) return "";
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_INFO, "");
    }

    /** 获取当前生效的【跨链/链内兑换池子白名单】条件记忆；无线上内容时回退内置兜底 */
    public static String getCrossChainWhitelist(Context context) {
        if (context == null) return DEFAULT_CROSS_CHAIN;
        String remote = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CROSS_CHAIN, "");
        return remote.isEmpty() ? DEFAULT_CROSS_CHAIN : remote;
    }

    /** 当前生效的提示词版本（0 表示仍为内置默认） */
    public static int getVersion(Context context) {
        if (context == null) return 0;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_VERSION, 0);
    }
}