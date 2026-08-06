package com.aicryptowallet.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.View;
import android.net.http.SslError;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class DAppBrowserActivity extends BaseActivity {

    private WebView webView;
    private EditText etUrl;
    private ProgressBar progressBar;
    private ScrollView bookmarksContainer;
    private LinearLayout dappGrid;
    private SafetyGate safetyGate;
    private DAppWhitelistManager whitelistManager;
    private WalletConnectRelay walletConnectRelay;
    private WalletJsInterface walletJsInterface;
    private final ExecutorService txExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger callbackCounter = new AtomicInteger(0);
    
    // onPageFinished 防抖：避免短时间内重复触发导致JS注入叠加
    private long lastPageFinishTime = 0;
    private static final long PAGE_FINISH_DEBOUNCE_MS = 1000;

    // AI 浏览器桥接静态引用
    private static WebView sWebView;
    private static volatile String sLastJsResult;
    private static final Object sJsResultLock = new Object();
    private static final long JS_TIMEOUT_MS = 10000;

    // 当前 Activity 实例，用于 AI 申请白名单时弹出确认弹窗
    private static volatile DAppBrowserActivity sInstance;
    private static volatile Context sAppContext;

    /**
     * 最后一次成功解析的页面域名（静态缓存）。
     * 作用：白名单校验在后台线程进行，不能依赖实例字段 currentOrigin——当 Activity 被
     * 重建/后台化、或页面导航到 about:blank/重定向等无法解析的 URL 时，currentOrigin
     * 会为空或失效，导致"旧标签页"被误判为未授权。此字段只在解析到有效域名时更新，
     * 永不因导航清空，保证校验始终使用最近一次的有效域名。
     */
    private static volatile String sCurrentDomain = "";

    // ============================================================
    // 标签页管理：记录 AI/用户打开过的网页（单 Activity 单 WebView 架构，
    // 同一时刻只加载一个页面到 WebView，但保留历史标签信息供 AI 查询/关闭）
    // ============================================================
    /** 标签页信息 */
    public static class TabInfo {
        public final String url;
        public String title;
        public final long openedAt;
        public volatile boolean isCurrent;

        TabInfo(String url, long openedAt) {
            this.url = url;
            this.title = "";
            this.openedAt = openedAt;
            this.isCurrent = true;
        }
    }

    /** 所有已记录的标签页（线程安全，最近打开的排最后） */
    private static final java.util.List<TabInfo> sTabs =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** 记录一个新标签页，并取消其他标签页的激活状态 */
    private static void addTab(String url, DAppBrowserActivity owner) {
        if (url == null || url.isEmpty()) return;
        synchronized (sTabs) {
            for (TabInfo t : sTabs) t.isCurrent = false;
            sTabs.add(new TabInfo(url, System.currentTimeMillis()));
        }
        // 清理已销毁 Activity 对应的标签（保留最新记录）
        if (owner == null) {
            pruneTabs();
        }
    }

    /** 更新当前标签页的标题（按 URL 匹配最新一条） */
    private static void updateTabTitle(String url, String title) {
        if (url == null || title == null) return;
        synchronized (sTabs) {
            for (int i = sTabs.size() - 1; i >= 0; i--) {
                TabInfo t = sTabs.get(i);
                if (t.url.equals(url)) {
                    t.title = title;
                    break;
                }
            }
        }
    }

    /** 移除已销毁 Activity 对应的标签页记录（保留仍活跃/最新的） */
    private static void pruneTabs() {
        synchronized (sTabs) {
            if (sInstance == null) { sTabs.clear(); return; }
            // 只保留当前 Activity 的 URL 记录，其余清空
            String currentUrl = null;
            try {
                WebView wv = sInstance.webView;
                if (wv != null && wv.getUrl() != null) currentUrl = wv.getUrl();
            } catch (Exception ignore) {}
            sTabs.clear();
            if (currentUrl != null) {
                TabInfo t = new TabInfo(currentUrl, System.currentTimeMillis());
                t.isCurrent = true;
                sTabs.add(t);
            }
        }
    }

    // 钱包切换检测
    private String currentWalletAddress;
    private final java.util.concurrent.atomic.AtomicReference<WhitelistDialogLatch> whitelistLatchRef =
        new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicReference<WhitelistDialogLatch> sOverlayLatchRef =
        new java.util.concurrent.atomic.AtomicReference<>();

    /** 白名单弹窗结果 */
    public static class WhitelistDialogResult {
        public final boolean allowed;
        public final boolean responded;
        private WhitelistDialogResult(boolean allowed, boolean responded) {
            this.allowed = allowed;
            this.responded = responded;
        }
        public static WhitelistDialogResult allow() { return new WhitelistDialogResult(true, true); }
        public static WhitelistDialogResult deny() { return new WhitelistDialogResult(false, true); }
        public static WhitelistDialogResult noUi() { return new WhitelistDialogResult(false, false); }
    }

    /** 弹窗等待锁 */
    private static class WhitelistDialogLatch {
        final java.util.concurrent.CountDownLatch latch;
        final WhitelistDialogResult[] result;
        WhitelistDialogLatch(java.util.concurrent.CountDownLatch latch, WhitelistDialogResult[] result) {
            this.latch = latch;
            this.result = result;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sAppContext = getApplicationContext();
        setContentView(R.layout.activity_dapp_browser);

        webView = findViewById(R.id.webView);
        etUrl = findViewById(R.id.etUrl);
        progressBar = findViewById(R.id.progressBar);
        bookmarksContainer = findViewById(R.id.bookmarksContainer);
        dappGrid = findViewById(R.id.dappGrid);

        // 初始化安全网关（DApp 发起的交易也必须经过校验）
        safetyGate = new SafetyGate(this, new TradeAuthManager(this), new RiskManager(this));
        whitelistManager = new DAppWhitelistManager(this);
        currentWalletAddress = WalletManager.getWalletAddress(this);

        setupWebView();
        setupListeners();

        String initialUrl = getIntent().getStringExtra("url");
        Logger.info(this, "DApp浏览器", "onCreate initialUrl=" + initialUrl);

        // 处理 metamask://wc?uri=... 深度链接（伪装 MetaMask）
        android.net.Uri deepLink = getIntent().getData();
        if (deepLink != null) {
            String scheme = deepLink.getScheme();
            if ("metamask".equals(scheme) || "wc".equals(scheme)) {
                String wcUri = deepLink.getQueryParameter("uri");
                if (wcUri != null) {
                    Logger.info(this, "WalletConnect", "通过深度链接收到 wc URI: " + wcUri.substring(0, Math.min(60, wcUri.length())));
                    // 延迟处理，等 WebView 初始化完成
                    final String uri = wcUri;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (walletConnectRelay != null) {
                            handleWalletConnectUri(uri);
                        }
                    }, 2000);
                }
            }
        }

        if (initialUrl != null && !initialUrl.isEmpty()) {
            // 有传入 URL：直接加载，隐藏书签，显示 WebView
            if (dappGrid != null) dappGrid.setVisibility(View.GONE);
            if (bookmarksContainer != null) bookmarksContainer.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            loadUrl(initialUrl);
        } else {
            // 无传入 URL：显示书签页
            loadDAppBookmarks();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        sInstance = this;

        // 检测钱包切换并通知 DApp
        String newAddress = WalletManager.getWalletAddress(this);
        if (currentWalletAddress != null && !currentWalletAddress.equalsIgnoreCase(newAddress)) {
            currentWalletAddress = newAddress;
            notifyWalletChanged();
            Logger.info(this, "DApp浏览器", "钱包已切换，新地址: " + newAddress);
        }
    }

    private void notifyWalletChanged() {
        if (webView == null) return;
        String address = WalletManager.getWalletAddress(this);
        String chainId = new WalletJsInterface().getChainId();
        String js = "if(window.ethereum){" +
            "ethereum.selectedAddress='" + address + "';" +
            "ethereum._accounts=[" + (address.isEmpty() ? "" : "'" + address + "'") + "];" +
            "var handlers=ethereum._eventHandlers&&ethereum._eventHandlers.accountsChanged;" +
            "if(handlers){handlers.forEach(function(h){try{h([" + (address.isEmpty() ? "" : "'" + address + "'") + "]);}catch(e){}});}" +
            "handlers=ethereum._eventHandlers&&ethereum._eventHandlers.chainChanged;" +
            "if(handlers){handlers.forEach(function(h){try{h('" + chainId + "');}catch(e){}});}" +
            "}";
        webView.evaluateJavascript(js, null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sInstance == this) {
            sInstance = null;
        }
        // 若 Activity 暂停，立即释放等待中的弹窗，避免后台线程永久阻塞
        WhitelistDialogLatch latch = whitelistLatchRef.getAndSet(null);
        if (latch != null) {
            latch.result[0] = WhitelistDialogResult.noUi();
            latch.latch.countDown();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        txExecutor.shutdownNow();
        if (walletConnectRelay != null) {
            walletConnectRelay.cleanup();
        }
        if (sWebView == webView) {
            sWebView = null;
        }
        if (sInstance == this) {
            sInstance = null;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // 修复：允许 file:// 协议访问本地文件，恶意页面可读取应用私有文件
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        // 允许混合内容（部分DApp可能加载http资源）
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // 设置 User-Agent，默认桌面模式，避免移动端 WebView 被网站弹窗导流去 App
        settings.setUserAgentString(isDesktopMode() ? DESKTOP_UA : MOBILE_UA);
        // 允许数据库存储 & DOM 存储（百度等大型站点需要）
        settings.setDatabaseEnabled(true);
        // 允许保存表单数据
        settings.setSaveFormData(true);
        // 启用 JS 自动打开窗口
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        // 必须启用多窗口支持，否则 window.open() 不会触发 onCreateWindow
        settings.setSupportMultipleWindows(true);
        // 设置缓存模式：优先使用缓存，无缓存时联网
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        // 启用 Chrome DevTools 远程调试（开发阶段用，USB 连接后 chrome://inspect 可查看控制台）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                Logger.info(DAppBrowserActivity.this, "DApp浏览器", "onPageStarted: " + url);
                // 提取 origin（协议+域名+端口），用于 DApp 授权管理
                currentOrigin = extractOrigin(url);
                // 同步更新静态域名缓存（仅当解析到有效域名时，避免导航到无法解析的 URL 时清空）
                String parsedDomain = DAppWhitelistManager.normalizeDomain(currentOrigin);
                if (parsedDomain != null && !parsedDomain.isEmpty()) {
                    sCurrentDomain = parsedDomain;
                }
                Logger.info(DAppBrowserActivity.this, "DApp浏览器", "currentOrigin=" + currentOrigin);
                // 在页面加载开始时就注入反检测脚本，尽量在其他 JS 读取之前生效
                injectAntiDetectionScripts(view);
                // 注入 WalletConnect URI 拦截器（尽可能早）
                injectWalletConnectInterceptor(view);
                // 在页面加载开始时就注入 provider，确保 DApp 检测时 window.ethereum 已存在
                injectEIP1193Provider(view);
                if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
                if (etUrl != null) etUrl.setText(url);
                if (bookmarksContainer != null) bookmarksContainer.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                // 页面内容已提交，在这个时机注入 provider 比 onPageStarted 更可靠
                // 如果 onPageStarted 的注入被跳过，这里补注
                Logger.info(DAppBrowserActivity.this, "DApp浏览器", "onPageCommitVisible: " + url);
                injectWalletConnectInterceptor(view);
                injectEIP1193Provider(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 防抖：避免短时间内多次触发导致 JS 注入叠加
                long now = System.currentTimeMillis();
                if (now - lastPageFinishTime < PAGE_FINISH_DEBOUNCE_MS) {
                    Logger.info(DAppBrowserActivity.this, "DApp浏览器", "onPageFinished 防抖，忽略短时间重复触发: " + url);
                    return;
                }
                lastPageFinishTime = now;
                
                Logger.info(DAppBrowserActivity.this, "DApp浏览器", "onPageFinished: " + url);
                // 更新标签页标题（供 browser_list_tabs 返回）
                String pageTitle = view.getTitle();
                if (pageTitle != null && !pageTitle.isEmpty()) {
                    updateTabTitle(url, pageTitle);
                }
                // 页面加载完成后再注入一次反检测脚本，防止 SPA 动态加载后重新检测环境
                injectAntiDetectionScripts(view);
                // 页面加载完成后再注入一次，确保 DApp 的检测脚本能拿到 provider
                injectEIP1193Provider(view);
                // WalletConnect 拦截器：onPageFinished 是最后保障，确保 DOM 观察器已启动
                injectWalletConnectInterceptor(view);
                // 重新广播 EIP-6963 事件：SPA 页面在 onPageStarted 时 JS 尚未加载，
                // 首次广播的 eip6963:announceProvider 可能丢失，此处重新广播确保 DApp 检测到钱包
                reAnnounceEIP6963(view);
                // 主动触发 connect 事件，让 DApp 知道钱包已就绪
                triggerWalletConnect(view);
                // 已授权的 DApp：主动发起连接请求，避免用户再点"连接钱包"按钮
                autoConnectIfAuthorized(view);
                if (progressBar != null) progressBar.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                Logger.error(DAppBrowserActivity.this, "DApp浏览器", "onReceivedError: code=" + errorCode + " desc=" + description + " url=" + failingUrl, null);
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                Toast.makeText(DAppBrowserActivity.this, getString(R.string.toast_failed_to_load, description, errorCode), Toast.LENGTH_LONG).show();
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // 国内网络环境下访问海外网站（PancakeSwap等），SSL证书常被中间设备干扰
                // 导致 ERR_CONNECTION_ABORTED，此处忽略证书错误继续加载
                Logger.warning(DAppBrowserActivity.this, "DApp浏览器", "SSL证书错误，忽略继续: " + error.getUrl() + " primaryError=" + error.getPrimaryError());
                handler.proceed();
            }

            @Override
            public void onReceivedHttpError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                Logger.error(DAppBrowserActivity.this, "DApp浏览器", "onReceivedHttpError: " + errorResponse.getStatusCode() + " " + request.getUrl(), null);
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, android.webkit.WebResourceRequest request) {
                String url = request.getUrl().toString();
                // 拦截 WalletConnect Cloud 钱包列表 API，注入我们的钱包
                if (url.contains("explorer-api.walletconnect.com") && url.contains("wallets")) {
                    Logger.info(DAppBrowserActivity.this, "WalletConnect", "拦截钱包列表 API: " + url);
                    try {
                        // 获取原始响应
                        java.net.URL javaUrl = new java.net.URL(url);
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) javaUrl.openConnection();
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(10000);
                        conn.setReadTimeout(10000);
                        int code = conn.getResponseCode();
                        if (code == 200) {
                            java.io.InputStream is = conn.getInputStream();
                            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                            byte[] buf = new byte[4096];
                            int n;
                            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                            is.close();
                            String body = baos.toString("UTF-8");

                            // 注入我们的钱包到 wallets 数组
                            String ourWallet = "{\"id\":\"aicryptowallet\",\"name\":\"AICryptoWallet\",\"image_id\":\"aicryptowallet\",\"app\":{\"android\":{\"universal_link\":\"\",\"package_name\":\"com.aicryptowallet.app\"}},\"homepage\":\"https://aicryptowallet.com\",\"chains\":[\"eip155:1\",\"eip155:56\",\"eip155:137\"],\"mobile_link\":\"aicryptowallet://wc\",\"deep_link\":\"aicryptowallet://wc\",\"rdns\":\"com.aicryptowallet.app\"}";

                            if (body.contains("\"wallets\"")) {
                                // 在 wallets 数组开头插入
                                body = body.replace("\"wallets\":[", "\"wallets\":[" + ourWallet + ",");
                            } else if (body.contains("\"data\"")) {
                                body = body.replace("\"data\":[", "\"data\":[" + ourWallet + ",");
                            } else {
                                // 如果格式不同，直接追加
                                body = "[" + ourWallet + "," + body + "]";
                            }

                            Logger.info(DAppBrowserActivity.this, "WalletConnect", "已注入 AICryptoWallet 到钱包列表");
                            byte[] newBody = body.getBytes("UTF-8");
                            android.webkit.WebResourceResponse response = new android.webkit.WebResourceResponse(
                                "application/json", "UTF-8", new java.io.ByteArrayInputStream(newBody));
                            response.setStatusCodeAndReasonPhrase(200, "OK");
                            return response;
                        }
                    } catch (Exception e) {
                        Logger.error(DAppBrowserActivity.this, "WalletConnect", "拦截钱包列表 API 失败: " + e.getMessage(), e);
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // 拦截 WalletConnect URI
                if (url.startsWith("wc:")) {
                    Logger.info(DAppBrowserActivity.this, "WalletConnect", "拦截到 wc: URI: " + url);
                    handleWalletConnectUri(url);
                    return true;
                }
                // 拦截 WalletConnect 深度链接（MetaMask / Rainbow / Trust 等 universal link）
                String extractedWcUri = extractWalletConnectUriFromDeepLink(url);
                if (extractedWcUri != null) {
                    Logger.info(DAppBrowserActivity.this, "WalletConnect", "拦截到深度链接并提取 wc URI: " + url);
                    handleWalletConnectUri(extractedWcUri);
                    return true;
                }
                if (url.startsWith("https://")) {
                    return false;
                }
                if (url.startsWith("http://")) {
                    // 修复：HTTP 明文加载可被 MITM 注入 JS 调用 ethereum.getAddress()
                    Toast.makeText(DAppBrowserActivity.this,
                        getString(R.string.title_security_warning_http_connection_is_not_secure_https_is_recommended), Toast.LENGTH_LONG).show();
                    view.loadUrl("https://" + url.substring(7));
                    return true;
                }
                // 修复：之前对所有非 http/https URL 直接 startActivity，
                // 构成 Intent Scheme 注入——恶意页面可通过 intent: URL 启动任意 Activity
                // 现在只放行已知安全的 scheme，其余拦截
                if (url.startsWith("mailto:") || url.startsWith("tel:")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception ignored) {}
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
            }

            // 拦截 window.open() 打开的新窗口（Transit 等 DApp 的 WalletConnect 深度链接通过此方式跳转）
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                // 获取新窗口的 URL
                WebView.HitTestResult result = view.getHitTestResult();
                String url = result != null ? result.getExtra() : null;

                // 尝试从新窗口请求中获取 URL（通过 JavaScript 注入获取）
                if (url == null) {
                    // 通过 evaluateJavascript 获取 window.open 的 URL
                    webView.evaluateJavascript(
                        "(function(){ return window.__lastWindowOpenUrl || ''; })();",
                        new android.webkit.ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String jsUrl) {
                                if (jsUrl != null && jsUrl.length() > 2) {
                                    String decoded = jsUrl.substring(1, jsUrl.length() - 1); // 去掉 JS 字符串引号
                                    Logger.info(DAppBrowserActivity.this, "WalletConnect",
                                        "onCreateWindow JS 捕获 URL: " + decoded);
                                    handleNewWindowUrl(decoded);
                                }
                            }
                        }
                    );
                }

                if (url != null) {
                    Logger.info(DAppBrowserActivity.this, "WalletConnect",
                        "onCreateWindow 拦截: " + url);
                    handleNewWindowUrl(url);
                }

                // 不创建新窗口，阻止跳转
                return false;
            }

            private void handleNewWindowUrl(String url) {
                if (url == null || url.isEmpty()) return;
                // 拦截 wc: URI
                if (url.startsWith("wc:")) {
                    Logger.info(DAppBrowserActivity.this, "WalletConnect", "onCreateWindow 拦截 wc: URI: " + url);
                    handleWalletConnectUri(url);
                    return;
                }
                // 拦截 WalletConnect 深度链接
                String extracted = extractWalletConnectUriFromDeepLink(url);
                if (extracted != null) {
                    Logger.info(DAppBrowserActivity.this, "WalletConnect", "onCreateWindow 拦截深度链接: " + url);
                    handleWalletConnectUri(extracted);
                    return;
                }
                // 其他 URL 在当前 WebView 中加载
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    Logger.info(DAppBrowserActivity.this, "WalletConnect", "onCreateWindow 重定向到当前 WebView: " + url);
                    webView.loadUrl(url);
                }
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                String msg = consoleMessage.message();
                Logger.info(DAppBrowserActivity.this, "WebViewConsole",
                    "[" + consoleMessage.messageLevel() + "] " + consoleMessage.sourceId() + ":" + consoleMessage.lineNumber() + " | " + msg);
                // Transit 触发 WalletConnect 连接时，仅记录日志，不再主动扫描
                // 之前的多轮扫描（DOM+Canvas+deepScan）会阻塞 JS 线程导致应用卡死
                if (msg != null && msg.contains("WALLET_CONNECT_WALLET")) {
                    Logger.info(DAppBrowserActivity.this, "WalletConnect", "检测到 Transit WC 连接触发（被动等待 URI 事件）");
                }
                return true;
            }
        });

        // Inject wallet provider for DApps
        // 注意：Java 桥命名为 _nativeEth，避免与 JS 注入的 window.ethereum 同名冲突
        walletJsInterface = new WalletJsInterface();
        webView.addJavascriptInterface(walletJsInterface, "_nativeEth");

        // Inject AI browser bridge
        webView.addJavascriptInterface(new AIBridge(), "aiBridge");
        sWebView = webView;

        // 初始化 WalletConnect 中继客户端
        walletConnectRelay = new WalletConnectRelay(this, new WalletConnectRelay.WalletConnectCallback() {
            @Override
            public void onSessionProposal(String dappName, String dappUrl, String dappIcon,
                                          JSONArray requiredChains, JSONObject proposal) {
                Logger.info(DAppBrowserActivity.this, "WalletConnect", "会话提案: " + dappName + " " + dappUrl);
                showWalletConnectSessionDialog(dappName, dappUrl, requiredChains);
            }

            @Override
            public void onSessionRequest(String method, JSONArray params, long requestId) {
                Logger.info(DAppBrowserActivity.this, "WalletConnect", "会话请求: method=" + method + " id=" + requestId);
                handleWalletConnectSessionRequest(method, params, requestId);
            }

            @Override
            public void onRelayConnected() {
                Logger.info(DAppBrowserActivity.this, "WalletConnect", "中继已连接");
            }

            @Override
            public void onDisconnected(String reason) {
                Logger.info(DAppBrowserActivity.this, "WalletConnect", "断开: " + reason);
                uiHandler.post(() -> {
                    Toast.makeText(DAppBrowserActivity.this, getString(R.string.toast_walletconnect_has_been_disconnected), Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                Logger.error(DAppBrowserActivity.this, "WalletConnect", "错误: " + error, null);
                uiHandler.post(() -> {
                    Toast.makeText(DAppBrowserActivity.this, getString(R.string.toast_walletconnect_error, error), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void setupListeners() {
        findViewById(R.id.btnBack).setOnClickListener(v -> {
            if (webView.canGoBack()) {
                webView.goBack();
            } else {
                finish();
            }
        });

        findViewById(R.id.btnForward).setOnClickListener(v -> {
            if (webView.canGoForward()) {
                webView.goForward();
            }
        });

        findViewById(R.id.btnRefresh).setOnClickListener(v -> webView.reload());

        findViewById(R.id.btnConnectWallet).setOnClickListener(v -> connectCurrentWallet());

        findViewById(R.id.btnGo).setOnClickListener(v -> {
            String url = etUrl.getText().toString().trim();
            if (!url.isEmpty()) {
                loadUrl(url);
            }
        });

        findViewById(R.id.btnMenu).setOnClickListener(v -> showBrowserMenu());

        etUrl.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP) {
                String url = etUrl.getText().toString().trim();
                if (!url.isEmpty()) {
                    loadUrl(url);
                }
                return true;
            }
            return false;
        });
    }

    /**
     * 手动触发当前钱包连接（绕过 DApp 自己的 WalletConnect 弹窗）
     */
    private void connectCurrentWallet() {
        if (webView == null) return;
        String js = "(function(){" +
            "  if (window.__aicw_connect) {" +
            "    window.__aicw_connect().then(function(accounts){" +
            "      console.log('[AI Wallet] 手动连接成功: ' + accounts[0]);" +
            "    }).catch(function(e){" +
            "      console.log('[AI Wallet] 手动连接失败: ' + e.message);" +
            "    });" +
            "    return 'triggered';" +
            "  }" +
            "  return 'no_provider';" +
            "})();";
        webView.evaluateJavascript(js, value -> {
            Logger.info(this, "DApp浏览器", "手动连接钱包结果: " + value);
            if ("\"no_provider\"".equals(value)) {
                Toast.makeText(this, getString(R.string.toast_wallet_provider_not_injected), Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 显示浏览器底部菜单（类似 TP 钱包）
     */
    private void showBrowserMenu() {
        final BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.BottomSheetDialog);
        View view = getLayoutInflater().inflate(R.layout.dialog_dapp_browser_menu, null);
        dialog.setContentView(view);

        String origin = currentOrigin;
        String url = webView != null ? webView.getUrl() : "";
        if (url == null || url.isEmpty()) url = etUrl.getText().toString().trim();
        String address = WalletManager.getWalletAddress(this);
        String shortAddr = address.length() > 12
            ? address.substring(0, 6) + "..." + address.substring(address.length() - 4)
            : address;

        ((TextView) view.findViewById(R.id.tvCurrentWallet)).setText(shortAddr);

        final String finalUrl = url;
        view.findViewById(R.id.btnMenuClose).setOnClickListener(v -> dialog.dismiss());

        // 复制URL
        view.findViewById(R.id.btnCopyUrl).setOnClickListener(v -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DApp URL", finalUrl));
                Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
        });

        // 浏览器打开
        view.findViewById(R.id.btnOpenExternal).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl)));
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.toast_unable_to_open_external), Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
        });

        // 分享
        view.findViewById(R.id.btnShare).setOnClickListener(v -> {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, finalUrl);
            startActivity(Intent.createChooser(share, getString(R.string.str_share)));
            dialog.dismiss();
        });

        // 收藏
        TextView tvFavoriteLabel = view.findViewById(R.id.tvFavoriteLabel);
        boolean isFav = isDAppFavorite(origin);
        tvFavoriteLabel.setText(isFav ? getString(R.string.btn_uncollect) : getString(R.string.str_favorites));
        view.findViewById(R.id.btnFavorite).setOnClickListener(v -> {
            if (isDAppFavorite(origin)) {
                removeDAppFavorite(origin);
                Toast.makeText(this, getString(R.string.toast_the_collection_has_been), Toast.LENGTH_SHORT).show();
            } else {
                addDAppFavorite(origin, finalUrl);
                Toast.makeText(this, getString(R.string.toast_dikoleksi), Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
        });

        // 切换桌面模式
        TextView tvDesktopModeLabel = view.findViewById(R.id.tvDesktopModeLabel);
        boolean isDesktop = isDesktopMode();
        tvDesktopModeLabel.setText(isDesktop ? getString(R.string.btn_toggle_move) : getString(R.string.str_switch_desktop));
        view.findViewById(R.id.btnDesktopMode).setOnClickListener(v -> {
            toggleDesktopMode();
            dialog.dismiss();
        });

        // 清除缓存
        view.findViewById(R.id.btnClearCache).setOnClickListener(v -> {
            if (webView != null) {
                webView.clearCache(true);
                webView.clearFormData();
                webView.clearHistory();
            }
            Toast.makeText(this, getString(R.string.toast_feed_cache_cleared), Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        // DApp详情
        view.findViewById(R.id.btnDappDetail).setOnClickListener(v -> {
            new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                .setTitle(getString(R.string.title_dapp_details))
                .setMessage(getString(R.string.msg_source_address_wallet, origin, finalUrl, address))
                .setPositiveButton(getString(R.string.btn_got_it), null)
                .show();
            dialog.dismiss();
        });

        // 返回发现页
        view.findViewById(R.id.btnBackHome).setOnClickListener(v -> {
            loadDAppBookmarks();
            dialog.dismiss();
        });

        // 切换账号
        view.findViewById(R.id.btnSwitchAccount).setOnClickListener(v -> {
            startActivityForResult(new Intent(this, WalletListActivity.class), 1002);
            dialog.dismiss();
        });

        dialog.show();
    }

    private static final String PREFS_DAPP_FAVORITES = "dapp_favorites_prefs";

    private boolean isDAppFavorite(String origin) {
        if (origin == null || origin.isEmpty()) return false;
        return getSharedPreferences(PREFS_DAPP_FAVORITES, Context.MODE_PRIVATE)
            .getBoolean("fav_" + origin, false);
    }

    private void addDAppFavorite(String origin, String url) {
        if (origin == null || origin.isEmpty()) return;
        getSharedPreferences(PREFS_DAPP_FAVORITES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("fav_" + origin, true)
            .putString("url_" + origin, url)
            .apply();
    }

    private void removeDAppFavorite(String origin) {
        if (origin == null || origin.isEmpty()) return;
        getSharedPreferences(PREFS_DAPP_FAVORITES, Context.MODE_PRIVATE)
            .edit()
            .remove("fav_" + origin)
            .remove("url_" + origin)
            .apply();
    }

    private static final String PREFS_BROWSER_SETTINGS = "browser_settings_prefs";
    private static final String KEY_DESKTOP_MODE = "desktop_mode";
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private boolean isDesktopMode() {
        return getSharedPreferences(PREFS_BROWSER_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DESKTOP_MODE, true);
    }

    private void toggleDesktopMode() {
        boolean newMode = !isDesktopMode();
        getSharedPreferences(PREFS_BROWSER_SETTINGS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DESKTOP_MODE, newMode)
            .apply();
        if (webView != null) {
            WebSettings settings = webView.getSettings();
            settings.setUserAgentString(newMode ? DESKTOP_UA : MOBILE_UA);
            webView.reload();
        }
        Toast.makeText(this, newMode ? getString(R.string.toast_desktop_mode_switched) : getString(R.string.toast_mobile_mode_switched), Toast.LENGTH_SHORT).show();
    }

    private void loadUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_url_is_empty), Toast.LENGTH_SHORT).show();
            return;
        }
        url = url.trim();
        // 已带协议头
        if (url.startsWith("http://") || url.startsWith("https://")) {
            addTab(url, this);
            webView.loadUrl(url);
            return;
        }
        // 像域名：包含 . 且不含空格
        boolean looksLikeUrl = url.contains(".") && !url.contains(" ")
            && !url.startsWith(".") && !url.endsWith(".");
        if (looksLikeUrl) {
            String resolved = "https://" + url;
            addTab(resolved, this);
            webView.loadUrl(resolved);
            return;
        }
        // 关键词搜索（百度，国内可用）
        try {
            String searchUrl = "https://www.baidu.com/s?wd=" + java.net.URLEncoder.encode(url, "UTF-8");
            addTab(searchUrl, this);
            webView.loadUrl(searchUrl);
        } catch (Exception e) {
            addTab("https://www.baidu.com/s?wd=" + url, this);
            webView.loadUrl("https://www.baidu.com/s?wd=" + url);
        }
    }

    private void loadDAppBookmarks() {
        webView.setVisibility(View.GONE);
        bookmarksContainer.setVisibility(View.VISIBLE);

        String[][] dapps = {
            // 跨链桥/跨链兑换
            {"Transit", "https://swap.transit.finance", "T"},
            {"Stargate", "https://stargate.finance", "S"},
            {"deBridge", "https://debridge.finance", "dB"},
            {"Across", "https://app.across.to", "A"},
            {"Orbiter", "https://www.orbiter.finance", "O"},
            {"Hop", "https://app.hop.exchange", "H"},
            {"Synapse", "https://synapseprotocol.com", "Sy"},
            {"cBridge", "https://cbridge.celer.network", "cB"},
            {"Wormhole", "https://portalbridge.com", "W"},
            {"LI.FI", "https://li.fi", "LI"},
            {"Bungee", "https://bungee.exchange", "Bu"},
            {"Rango", "https://app.rango.exchange", "R"},
            {"Squid", "https://app.squidrouter.com", "Sq"},
            {"Relay", "https://relay.link", "Re"},
            {"Jumper", "https://jumper.exchange", "J"},
            // DEX
            {"PancakeSwap", "https://pancakeswap.finance", "P"},
            {"Uniswap", "https://app.uniswap.org", "U"},
            {"1inch", "https://app.1inch.io", "1"},
            {"SushiSwap", "https://app.sushi.com", "Su"},
            {"Curve", "https://curve.fi", "C"},
            {"dYdX", "https://dydx.exchange", "dY"},
            {"Jupiter", "https://jup.ag", "Ju"},
            {"Orca", "https://www.orca.so", "Or"},
            {"Raydium", "https://raydium.io", "Ra"},
            {"Trader Joe", "https://traderjoexyz.com", "TJ"},
            {"QuickSwap", "https://quickswap.exchange", "Q"},
            {"SpookySwap", "https://spooky.fi", "Sp"},
            // 借贷
            {"Aave", "https://app.aave.com", "Aa"},
            {"Compound", "https://app.compound.finance", "Co"},
            // NFT
            {"OpenSea", "https://opensea.io", "OS"},
            // 浏览器
            {"Etherscan", "https://etherscan.io", "Eth"},
            {"BscScan", "https://bscscan.com", "Bsc"}
        };

        for (String[] dapp : dapps) {
            View item = getLayoutInflater().inflate(R.layout.item_dapp, null);
            ((TextView) item.findViewById(R.id.tvDappIcon)).setText(dapp[2]);
            ((TextView) item.findViewById(R.id.tvDappName)).setText(dapp[0]);
            item.setOnClickListener(v -> loadUrl(dapp[1]));
            dappGrid.addView(item);
        }
    }

    /**
     * 钱包 JS 接口 - 暴露给 DApp 的 EIP-1193 provider 后端
     * 所有写入操作必须经过 SafetyGate + 用户确认弹窗
     */
    private class WalletJsInterface {
        @android.webkit.JavascriptInterface
        public String getAddress() {
            return WalletManager.getWalletAddress(DAppBrowserActivity.this);
        }

        @android.webkit.JavascriptInterface
        public String getChainId() {
            String chain = WalletManager.getChain(DAppBrowserActivity.this);
            switch (chain) {
                case "ETH": return "0x1";
                case "BNB": return "0x38";
                case "MATIC": return "0x89";
                case "AVAX": return "0xa86a";
                case "FTM": return "0xfa";
                case "ARB": return "0xa4b1";
                case "OP": return "0xa";
                case "BASE": return "0x2105";
                default: return "0x1";
            }
        }

        /**
         * JS 层拦截到的 WalletConnect URI，转发给原生层处理
         */
        @android.webkit.JavascriptInterface
        public void handleWalletConnectUri(String uri) {
            Logger.info(DAppBrowserActivity.this, "WalletConnect", "JS 层拦截到 wc: URI: " + uri);
            DAppBrowserActivity.this.handleWalletConnectUri(uri);
        }

        /**
         * JS 层拦截到 WC URI + 缓存的 irn_publish 消息，直接传递给钱包处理
         * 这样钱包不需要等待中继转发，直接解密 session proposal
         */
        @android.webkit.JavascriptInterface
        public void handleWalletConnectUriWithPublish(String uri, String publishMsg) {
            Logger.info(DAppBrowserActivity.this, "WalletConnect", "JS 层拦截到 wc: URI + 缓存 publish: " + uri.substring(0, Math.min(60, uri.length())));
            DAppBrowserActivity.this.handleWalletConnectUriWithPublish(uri, publishMsg);
        }

        /**
         * JS 层检测到可能的 QR 码 Canvas，传入 data URL 进行解码
         * 用于捕获 Transit 等 DApp 通过 Canvas QR 码展示的 wc: URI
         */
        @android.webkit.JavascriptInterface
        public void handleQrCodeImage(String dataUrl) {
            DAppBrowserActivity.this.decodeQrCodeFromDataUrl(dataUrl);
        }

        // ===== WebSocket 桥接：用 OkHttp 替代 WebView 原生 WebSocket =====
        private final java.util.Map<String, okhttp3.WebSocket> relaySockets = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.Map<String, String> relaySocketUrls = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicInteger socketIdGen = new AtomicInteger(0);
        // 缓存 DApp 发出的 irn_publish 消息（topic → 完整 JSON），用于钱包直接注入
        private final java.util.Map<String, String> cachedPublishMessages = new java.util.concurrent.ConcurrentHashMap<>();

        /**
         * 获取第一个活跃的中继 WebSocket（供 WalletConnectRelay 复用）
         */
        public okhttp3.WebSocket getFirstRelaySocket() {
            for (okhttp3.WebSocket ws : relaySockets.values()) {
                return ws;
            }
            return null;
        }

        /**
         * 获取缓存的 DApp irn_publish 消息（用于钱包直接注入，不依赖中继转发）
         * 返回模拟的 irn_message JSON，供 handleRelayMessage 处理
         */
        public String getLastPublishForTopic(String topic) {
            if (topic == null) return null;
            String publishJson = cachedPublishMessages.get(topic);
            if (publishJson == null) return null;
            try {
                // 从 irn_publish 构造 irn_message 格式
                JSONObject publish = new JSONObject(publishJson);
                JSONObject params = publish.optJSONObject("params");
                if (params == null) return null;
                String message = params.optString("message", "");
                if (message.isEmpty()) return null;

                // 构造 irn_message 格式
                JSONObject irnMsg = new JSONObject();
                irnMsg.put("id", System.currentTimeMillis());
                irnMsg.put("jsonrpc", "2.0");
                irnMsg.put("method", "irn_message");
                JSONObject msgParams = new JSONObject();
                msgParams.put("topic", topic);
                msgParams.put("message", message);
                msgParams.put("publishedAt", System.currentTimeMillis());
                irnMsg.put("params", msgParams);
                return irnMsg.toString();
            } catch (Exception e) {
                Logger.error(DAppBrowserActivity.this, "WS桥接", "构造注入消息失败: " + e.getMessage(), e);
                return null;
            }
        }

        /**
         * 为 relay.walletconnect.org 创建 OkHttp WebSocket 连接
         * 返回 bridgeId，JS 侧通过此 ID 操作该 WebSocket
         */
        @android.webkit.JavascriptInterface
        public String createRelaySocket(String url) {
            String bridgeId = "ws_" + socketIdGen.incrementAndGet();
            Logger.info(DAppBrowserActivity.this, "WS桥接", "创建 relay WebSocket: bridgeId=" + bridgeId + " url=" + (url != null ? url.substring(0, Math.min(80, url.length())) : "null"));

            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .pingInterval(30, TimeUnit.SECONDS)
                    .build();

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(url)
                    .header("Origin", "https://swap.transit.finance")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .build();

            okhttp3.WebSocket ws = client.newWebSocket(request, new okhttp3.WebSocketListener() {
                @Override
                public void onOpen(okhttp3.WebSocket webSocket, okhttp3.Response response) {
                    Logger.info(DAppBrowserActivity.this, "WS桥接", "relay WebSocket 已连接: bridgeId=" + bridgeId);
                    uiHandler.post(() -> {
                        webView.evaluateJavascript(
                            "(function(){" +
                            "  var s = window.__relaySockets && window.__relaySockets['" + bridgeId + "'];" +
                            "  if (s) { s.readyState = 1; if (s.onopen) s.onopen({type:'open'}); }" +
                            "})()", null);
                    });
                }

                @Override
                public void onMessage(okhttp3.WebSocket webSocket, String text) {
                    // 记录原始消息（截断过长内容），便于排查中继响应
                    String shortText = text.length() > 200 ? text.substring(0, 200) + "..." : text;
                    Logger.info(DAppBrowserActivity.this, "WS桥接", "relay 消息: bridgeId=" + bridgeId + " msg=" + shortText);
                    // 转发给 DApp
                    String escaped = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
                    uiHandler.post(() -> {
                        webView.evaluateJavascript(
                            "(function(){" +
                            "  var s = window.__relaySockets && window.__relaySockets['" + bridgeId + "'];" +
                            "  if (s && s.onmessage) s.onmessage({data:'" + escaped + "'});" +
                            "})()", null);
                    });
                    // 同时转发给钱包（WalletConnectRelay），处理 irn_message 等中继推送
                    WalletConnectRelay wcr = walletConnectRelay;
                    if (wcr != null) {
                        wcr.handleRelayMessage(text);
                    }
                }

                @Override
                public void onFailure(okhttp3.WebSocket webSocket, Throwable t, okhttp3.Response response) {
                    Logger.error(DAppBrowserActivity.this, "WS桥接", "relay WebSocket 失败: bridgeId=" + bridgeId + " err=" + (t != null ? t.getMessage() : "null"), t);
                    uiHandler.post(() -> {
                        webView.evaluateJavascript(
                            "(function(){" +
                            "  var s = window.__relaySockets && window.__relaySockets['" + bridgeId + "'];" +
                            "  if (s) { s.readyState = 3; if (s.onerror) s.onerror({type:'error'}); if (s.onclose) s.onclose({code:1006,reason:'connection failed',wasClean:false}); }" +
                            "})()", null);
                    });
                    relaySockets.remove(bridgeId);
                    relaySocketUrls.remove(bridgeId);
                }

                @Override
                public void onClosed(okhttp3.WebSocket webSocket, int code, String reason) {
                    Logger.info(DAppBrowserActivity.this, "WS桥接", "relay WebSocket 关闭: bridgeId=" + bridgeId + " code=" + code + " reason=" + reason);
                    uiHandler.post(() -> {
                        webView.evaluateJavascript(
                            "(function(){" +
                            "  var s = window.__relaySockets && window.__relaySockets['" + bridgeId + "'];" +
                            "  if (s) { s.readyState = 3; if (s.onclose) s.onclose({code:" + code + ",reason:'" + (reason != null ? reason.replace("'", "\\'") : "") + "',wasClean:true}); }" +
                            "})()", null);
                    });
                    relaySockets.remove(bridgeId);
                    relaySocketUrls.remove(bridgeId);
                }
            });

            relaySockets.put(bridgeId, ws);
            relaySocketUrls.put(bridgeId, url);
            return bridgeId;
        }

        /**
         * 通过桥接 WebSocket 发送消息
         */
        @android.webkit.JavascriptInterface
        public void relaySocketSend(String bridgeId, String message) {
            okhttp3.WebSocket ws = relaySockets.get(bridgeId);
            if (ws != null) {
                // 拦截 DApp 发出的 irn_publish 消息，缓存以供钱包直接注入
                try {
                    JSONObject msg = new JSONObject(message);
                    String method = msg.optString("method", "");
                    if ("irn_publish".equals(method)) {
                        JSONObject params = msg.optJSONObject("params");
                        if (params != null) {
                            String topic = params.optString("topic", "");
                            if (!topic.isEmpty()) {
                                cachedPublishMessages.put(topic, message);
                                Logger.info(DAppBrowserActivity.this, "WS桥接", "缓存 DApp irn_publish: topic=" + topic.substring(0, Math.min(16, topic.length())) + "...");
                            }
                        }
                    }
                } catch (Exception e) {
                    // 非 JSON 或解析失败，忽略
                }
                ws.send(message);
            }
        }

        /**
         * 关闭桥接 WebSocket
         */
        @android.webkit.JavascriptInterface
        public void relaySocketClose(String bridgeId) {
            okhttp3.WebSocket ws = relaySockets.remove(bridgeId);
            relaySocketUrls.remove(bridgeId);
            if (ws != null) {
                try { ws.close(1000, "JS closed"); } catch (Exception ignored) {}
            }
        }

        /**
         * EIP-1193 统一请求入口
         *
         * @param method     方法名（eth_requestAccounts/eth_sendTransaction/personal_sign...）
         * @param paramsJson 参数 JSON 字符串
         * @param callbackId 回调 ID，JS 侧用此 ID 匹配 Promise
         */
        @android.webkit.JavascriptInterface
        public void request(String method, String paramsJson, String callbackId) {
            Logger.info(DAppBrowserActivity.this, "DApp请求", "method=" + method + " params=" + paramsJson);
            try {
                switch (method) {
                    case "eth_requestAccounts":
                    case "eth_accounts":
                        handleRequestAccounts(method, callbackId);
                        break;
                    case "eth_chainId":
                        resolveCallback(callbackId, "\"" + getChainId() + "\"");
                        break;
                    case "web3_clientVersion":
                        // DApp（如 Transit）通过此方法检测钱包类型，返回 MetaMask 版本以提高兼容性
                        resolveCallback(callbackId, "\"MetaMask/v11.0.0\"");
                        break;
                    case "wallet_switchEthereumChain":
                    case "wallet_addEthereumChain":
                        // DApp 请求切换/添加链：直接 resolve，让页面继续使用当前链
                        resolveCallback(callbackId, "null");
                        Logger.info(DAppBrowserActivity.this, "DApp连接", "DApp 请求切换/添加链，已放行: " + method);
                        break;
                    case "eth_sendTransaction":
                        handleSendTransaction(paramsJson, callbackId);
                        break;
                    case "personal_sign":
                        handlePersonalSign(paramsJson, callbackId);
                        break;
                    case "eth_signTypedData_v4":
                        handleSignTypedData(paramsJson, callbackId);
                        break;
                    case "eth_call":
                    case "eth_getBalance":
                    case "eth_getTransactionReceipt":
                    case "eth_blockNumber":
                    case "eth_gasPrice":
                    case "eth_estimateGas":
                    case "eth_getCode":
                    case "eth_getTransactionByHash":
                    case "eth_getTransactionCount":
                    case "eth_maxPriorityFeePerGas":
                    case "eth_feeHistory":
                        handleReadOnlyRpc(method, paramsJson, callbackId);
                        break;
                    default:
                        // 对未知方法返回 null 而非 reject，避免 DApp 因检测方法失败而回退到 WC
                        Logger.info(DAppBrowserActivity.this, "DApp请求", "未知方法返回null: " + method);
                        resolveCallback(callbackId, "null");
                }
            } catch (Exception e) {
                Logger.error(DAppBrowserActivity.this, "DApp请求", "处理失败: " + e.getMessage(), e);
                rejectCallback(callbackId, "处理失败: " + e.getMessage());
            }
        }
    }

    // ============================================================
    // DApp 请求处理
    // ============================================================

    private static final String PREFS_DAPP_AUTH = "dapp_auth_prefs";
    private static final String KEY_PREFIX_ORIGIN = "auth_origin_";
    private static final String KEY_PREFIX_WALLET = "auth_wallet_";
    private String currentOrigin = "";

    /**
     * 违背去中心化思想的 DApp 黑名单
     * 命中后一律禁止连接，并弹出提示
     */
    private static final Set<String> BLOCKED_DAPP_ORIGINS = new HashSet<>(Arrays.asList(
        // 示例：可在此添加具体域名，如 "https://example.com"
    ));
    private static final Set<String> BLOCKED_DAPP_KEYWORDS = new HashSet<>(Arrays.asList(
        // 示例：可在此添加关键词，如 "cex", "kyc"
    ));

    /**
     * 处理 eth_requestAccounts / eth_accounts
     * 首次连接需用户确认，确认后持久化授权；同一钱包+同一DApp下次自动连接
     */
    private void handleRequestAccounts(String method, String callbackId) {
        String address = WalletManager.getWalletAddress(this);
        String origin = currentOrigin;

        // 黑名单校验：违背去中心化思想的 DApp 禁止连接
        if (isBlockedDApp(origin)) {
            Logger.info(this, "DApp连接", "命中黑名单，禁止连接 origin=" + origin);
            showBlockedDAppDialog(origin, callbackId);
            return;
        }

        // eth_accounts 不弹窗，已授权就返回地址，未授权返回空数组
        if ("eth_accounts".equals(method)) {
            if (isDAppAuthorized(origin, address)) {
                resolveCallback(callbackId, "[\"" + address + "\"]");
            } else {
                resolveCallback(callbackId, "[]");
            }
            return;
        }

        // eth_requestAccounts：检查是否已授权
        if (isDAppAuthorized(origin, address)) {
            Logger.info(this, "DApp连接", "已授权，自动连接 origin=" + origin);
            resolveCallback(callbackId, "[\"" + address + "\"]");
            // 触发 accountsChanged 通知 DApp
            notifyAccountsChanged(address);
            return;
        }

        // 首次连接：弹窗确认
        uiHandler.post(() -> {
            String shortAddr = address.length() > 12
                ? address.substring(0, 6) + "..." + address.substring(address.length() - 4)
                : address;
            new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                .setTitle(getString(R.string.title_connect_wallet))
                .setMessage(getString(R.string.msg_dapp_connection_prompt, origin, shortAddr))
                .setPositiveButton(getString(R.string.btn_got_it), (d, w) -> {
                    // 持久化授权
                    authorizeDApp(origin, address);
                    Logger.info(this, "DApp连接", "用户授权连接 origin=" + origin);
                    resolveCallback(callbackId, "[\"" + address + "\"]");
                    notifyAccountsChanged(address);
                    Toast.makeText(this, getString(R.string.toast_wallet_connected), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(getString(R.string.btn_reject), (d, w) -> {
                    rejectCallback(callbackId, "用户拒绝连接");
                })
                .setOnCancelListener(d -> rejectCallback(callbackId, "用户取消连接"))
                .show();
        });
    }

    /**
     * 检查 DApp 是否命中黑名单
     */
    private boolean isBlockedDApp(String origin) {
        if (origin == null || origin.isEmpty()) return false;
        String lower = origin.toLowerCase();
        for (String blocked : BLOCKED_DAPP_ORIGINS) {
            if (lower.equals(blocked.toLowerCase()) || lower.contains(blocked.toLowerCase())) {
                return true;
            }
        }
        for (String keyword : BLOCKED_DAPP_KEYWORDS) {
            if (!keyword.isEmpty() && lower.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 弹出黑名单 DApp 禁止连接提示
     */
    private void showBlockedDAppDialog(String origin, String callbackId) {
        uiHandler.post(() -> {
            new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                .setTitle(getString(R.string.title_connection_blocked))
                .setMessage(getString(R.string.msg_this_dapp_violates_the, origin))
                .setPositiveButton(getString(R.string.btn_got_it), (d, w) -> {
                    rejectCallback(callbackId, "该 DApp 已被系统禁止连接");
                })
                .setOnCancelListener(d -> rejectCallback(callbackId, "该 DApp 已被系统禁止连接"))
                .show();
        });
    }

    /**
     * 检查 DApp 是否已授权连接当前钱包
     */
    private boolean isDAppAuthorized(String origin, String walletAddress) {
        if (origin == null || origin.isEmpty() || walletAddress == null) return false;
        String key = KEY_PREFIX_ORIGIN + origin;
        String savedWallet = getSharedPreferences(PREFS_DAPP_AUTH, Context.MODE_PRIVATE)
            .getString(key, "");
        return walletAddress.equalsIgnoreCase(savedWallet);
    }

    /**
     * 持久化授权 DApp 连接当前钱包
     */
    private void authorizeDApp(String origin, String walletAddress) {
        if (origin == null || origin.isEmpty()) return;
        getSharedPreferences(PREFS_DAPP_AUTH, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFIX_ORIGIN + origin, walletAddress)
            .apply();
    }

    /**
     * 清除当前钱包的所有 DApp 授权（切换钱包时调用）
     */
    public static void clearAllAuthorizations(Context ctx, String oldWalletAddress) {
        if (oldWalletAddress == null) return;
        android.content.SharedPreferences prefs = ctx.getSharedPreferences(PREFS_DAPP_AUTH, Context.MODE_PRIVATE);
        java.util.Map<String, ?> all = prefs.getAll();
        android.content.SharedPreferences.Editor editor = prefs.edit();
        for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof String && oldWalletAddress.equalsIgnoreCase((String) val)) {
                editor.remove(entry.getKey());
            }
        }
        editor.apply();
    }

    /**
     * 通知 DApp 账户已连接
     */
    private void notifyAccountsChanged(String address) {
        String js = "(function(){\n" +
            "  if (window.ethereum && window.ethereum.listeners) {\n" +
            "    var arr = ['" + address + "'];\n" +
            "    window.ethereum.listeners('accountsChanged') && window.ethereum.listeners('accountsChanged').forEach(function(h){ try{ h(arr); }catch(e){} });\n" +
            "    window.ethereum.listeners('connect') && window.ethereum.listeners('connect').forEach(function(h){ try{ h({chainId: ethereum.getChainId()}); }catch(e){} });\n" +
            "  }\n" +
            "})();";
        uiHandler.post(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    /**
     * 处理 eth_sendTransaction - DApp 请求发送交易
     * 必须经过 SafetyGate 校验；若 DApp 已加入白名单且在额度内，自动放行。
     */
    private void handleSendTransaction(String paramsJson, String callbackId) {
        try {
            JSONArray params = new JSONArray(paramsJson);
            JSONObject tx = params.getJSONObject(0);
            String to = tx.optString("to", "");
            String valueHex = tx.optString("value", "0x0");
            String data = tx.optString("data", "0x");
            String from = tx.optString("from", WalletManager.getWalletAddress(this));

            // 校验 from 地址
            String myAddr = WalletManager.getWalletAddress(this);
            if (!from.equalsIgnoreCase(myAddr)) {
                rejectCallback(callbackId, "from 地址与当前钱包不匹配");
                return;
            }

            BigInteger valueWei = parseHex(valueHex);
            String chain = WalletManager.getChain(this);
            String originDomain = getCurrentDomain();
            double txUsdValue = estimateTxUsdValue(chain, valueWei);

            // SafetyGate 校验：优先 DApp 白名单自动放行，否则走代币白名单确认
            SafetyGate.CheckResult safetyCheck = safetyGate.checkDAppTransaction(
                originDomain,
                to,
                valueWei,
                "DApp 交易 to=" + to + " value=" + valueWei + " data=" + data.substring(0, Math.min(50, data.length())),
                txUsdValue);
            if (!safetyCheck.allowed) {
                rejectCallback(callbackId, "安全网关拦截: " + safetyCheck.reason);
                return;
            }

            Logger.info(this, "DApp交易", "白名单自动放行 origin=" + originDomain + " usd=" + txUsdValue);
            executeDAppTransaction(to, data, valueWei, callbackId);
        } catch (Exception e) {
            rejectCallback(callbackId, "解析交易参数失败: " + e.getMessage());
        }
    }

    /** 估算交易 USD 价值 */
    private double estimateTxUsdValue(String chain, BigInteger valueWei) {
        try {
            java.util.Map<String, Double> prices = ChainAPI.getPrices(this);
            double price = prices.getOrDefault(chain, 0.0);
            int decimals = ChainAPI.getChainDecimals(chain);
            double amountNative = valueWei.doubleValue() / Math.pow(10, decimals);
            return amountNative * price;
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * 执行 DApp 交易（签名+广播）
     */
    private void executeDAppTransaction(String to, String data, BigInteger valueWei, String callbackId) {
        txExecutor.execute(() -> {
            try {
                String chain = WalletManager.getChain(this);
                DexTrader trader = new DexTrader();
                String txHash = trader.executeRawTransaction(this, chain, to, data, valueWei);
                safetyGate.onTradeSuccess(0);
                Logger.action(this, "DApp交易", "广播成功 hash=" + txHash, null);
                resolveCallback(callbackId, "\"" + txHash + "\"");
            } catch (Exception e) {
                Logger.error(this, "DApp交易", "广播失败: " + e.getMessage(), e);
                safetyGate.onTradeFailure();
                rejectCallback(callbackId, "交易失败: " + e.getMessage());
            }
        });
    }

    /**
     * 处理 personal_sign - DApp 请求签名消息
     */
    private void handlePersonalSign(String paramsJson, String callbackId) {
        try {
            JSONArray params = new JSONArray(paramsJson);
            String message = params.getString(0);
            String address = params.getString(1);
            String myAddr = WalletManager.getWalletAddress(this);
            if (!address.equalsIgnoreCase(myAddr)) {
                rejectCallback(callbackId, getString(R.string.msg_address_mismatch));
                return;
            }

            uiHandler.post(() -> {
                new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.title_walletconnect_signing_request))
                    .setMessage(getString(R.string.msg_dapp_requests_eip_712, message.substring(0, Math.min(200, message.length()))))
                    .setPositiveButton(getString(R.string.btn_signature), (d, w) -> signMessage(message, callbackId))
                    .setNegativeButton(getString(R.string.btn_reject), (d, w) -> rejectCallback(callbackId, getString(R.string.btn_user_refuses_to_sign)))
                    .show();
            });
        } catch (Exception e) {
            rejectCallback(callbackId, getString(R.string.toast_failed_to_parse_signature_params, e.getMessage()));
        }
    }

    /**
     * 处理 eth_signTypedData_v4 - EIP-712 结构化数据签名
     */
    private void handleSignTypedData(String paramsJson, String callbackId) {
        try {
            JSONArray params = new JSONArray(paramsJson);
            String address = params.getString(0);
            String typedData = params.getString(1);
            String myAddr = WalletManager.getWalletAddress(this);
            if (!address.equalsIgnoreCase(myAddr)) {
                rejectCallback(callbackId, getString(R.string.msg_address_mismatch));
                return;
            }

            uiHandler.post(() -> {
                new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.title_structured_signature_request))
                    .setMessage(getString(R.string.msg_dapp_requests_eip_712, typedData.substring(0, Math.min(300, typedData.length()))))
                    .setPositiveButton(getString(R.string.btn_signature), (d, w) -> signMessage(typedData, callbackId))
                    .setNegativeButton(getString(R.string.btn_reject), (d, w) -> rejectCallback(callbackId, getString(R.string.btn_user_refuses_to_sign)))
                    .show();
            });
        } catch (Exception e) {
            rejectCallback(callbackId, getString(R.string.toast_failed_to_parse_signature_params, e.getMessage()));
        }
    }

    /**
     * 签名消息（用钱包私钥）
     */
    private void signMessage(String message, String callbackId) {
        txExecutor.execute(() -> {
            try {
                String chain = WalletManager.getChain(this);
                org.web3j.crypto.Credentials creds = DexTrader.getCredentialsForChain(this, chain);
                // 简单实现：直接对 message 字符串签名（实际应按 EIP-191 前缀）
                byte[] msgBytes = message.getBytes("UTF-8");
                org.web3j.crypto.Sign.SignatureData sig = org.web3j.crypto.Sign.signMessage(msgBytes, creds.getEcKeyPair(), false);
                // web3j 的 SignatureData.getV() 在不同版本返回 byte 或 byte[]，统一处理
                BigInteger vBigInt;
                byte[] vBytes = sig.getV();
                if (vBytes == null || vBytes.length == 0) {
                    vBigInt = BigInteger.valueOf(27);
                } else if (vBytes.length == 1) {
                    vBigInt = BigInteger.valueOf(vBytes[0] & 0xFF);
                } else {
                    vBigInt = new BigInteger(1, vBytes);
                }
                String sigHex = "0x" +
                    org.web3j.utils.Numeric.toHexStringNoPrefixZeroPadded(new BigInteger(1, sig.getR()), 64) +
                    org.web3j.utils.Numeric.toHexStringNoPrefixZeroPadded(new BigInteger(1, sig.getS()), 64) +
                    org.web3j.utils.Numeric.toHexStringNoPrefixZeroPadded(vBigInt, 2);
                Logger.action(this, "DApp签名", "签名完成", null);
                resolveCallback(callbackId, "\"" + sigHex + "\"");
            } catch (Exception e) {
                Logger.error(this, "DApp签名", "签名失败: " + e.getMessage(), e);
                rejectCallback(callbackId, "签名失败: " + e.getMessage());
            }
        });
    }

    /**
     * 处理只读 RPC 调用（直接转发到节点）
     */
    private void handleReadOnlyRpc(String method, String paramsJson, String callbackId) {
        txExecutor.execute(() -> {
            try {
                String chain = WalletManager.getChain(this);
                String rpcUrl = ChainAPI.getRpcUrlStatic(this, chain);
                JSONObject body = new JSONObject();
                body.put("jsonrpc", "2.0");
                body.put("id", 1);
                body.put("method", method);
                body.put("params", new JSONArray(paramsJson));

                okhttp3.MediaType JSON = okhttp3.MediaType.parse("application/json");
                okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(rpcUrl)
                    .post(okhttp3.RequestBody.create(body.toString(), JSON))
                    .build();

                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

                try (okhttp3.Response resp = client.newCall(request).execute()) {
                    String respBody = resp.body() != null ? resp.body().string() : "";
                    JSONObject json = new JSONObject(respBody);
                    if (json.has("error")) {
                        rejectCallback(callbackId, json.getJSONObject("error").optString("message", "RPC error"));
                    } else {
                        Object result = json.opt("result");
                        String resultStr;
                        if (result == null) {
                            resultStr = "null";
                        } else if (result instanceof String) {
                            // 字符串需 JSON 编码（加引号转义）
                            resultStr = JSONObject.quote((String) result);
                        } else {
                            // Number/Boolean/JSONObject/JSONArray 直接 toString
                            resultStr = result.toString();
                        }
                        resolveCallback(callbackId, resultStr);
                    }
                }
            } catch (Exception e) {
                rejectCallback(callbackId, "RPC 调用失败: " + e.getMessage());
            }
        });
    }

    // ============================================================
    // 回调机制（通过 evaluateJavascript 把结果传回 JS）
    // ============================================================

    private void resolveCallback(String callbackId, String resultJsonLiteral) {
        // resultJsonLiteral 已是合法 JS 值（字符串带引号，对象是 JSON）
        String js = "window.__dappCallback && window.__dappCallback(" +
            "\"" + callbackId + "\", true, " + resultJsonLiteral + ", null);";
        uiHandler.post(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    private void rejectCallback(String callbackId, String errorMessage) {
        // 转义错误消息中的特殊字符
        String escaped = errorMessage == null ? "" : errorMessage
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n");
        String js = "window.__dappCallback && window.__dappCallback(" +
            "\"" + callbackId + "\", false, null, \"" + escaped + "\");";
        uiHandler.post(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    /**
     * 已授权的 DApp：页面加载完成后主动模拟点击"连接钱包"
     * 通过自动调用 eth_requestAccounts 让 DApp 直接进入已连接状态
     * 跳过 DApp 的钱包选择界面
     */
    private void autoConnectIfAuthorized(WebView view) {
        String address = WalletManager.getWalletAddress(this);
        if (!isDAppAuthorized(currentOrigin, address)) {
            Logger.info(this, "DApp连接", "未授权，不自动连接 origin=" + currentOrigin);
            return;
        }
        Logger.info(this, "DApp连接", "已授权，主动发起连接 origin=" + currentOrigin);
        // 1.5秒后主动调用 eth_requestAccounts，给 DApp 一点初始化时间
        uiHandler.postDelayed(() -> {
            if (webView == null) return;
            // 直接在JS层设置 accounts，并触发 connect/accountsChanged 事件
            // 这样即使DApp没主动调用 eth_requestAccounts，也能感知到已连接
            String js = "(function(){\n" +
                "  if (!window.ethereum || !window.__ethereumInjected) return;\n" +
                "  // 模拟 MetaMask 已连接状态\n" +
                "  window.ethereum.isConnected = function(){ return true; };\n" +
                "  // 主动触发 connect 事件\n" +
                "  if (window.ethereum.listeners) {\n" +
                "    var addr = _nativeEth.getAddress();\n" +
"    var chainId = _nativeEth.getChainId();\n" +
                "    window.ethereum.listeners('connect') && window.ethereum.listeners('connect').forEach(function(h){ try{ h({chainId: chainId}); }catch(e){} });\n" +
                "    window.ethereum.listeners('accountsChanged') && window.ethereum.listeners('accountsChanged').forEach(function(h){ try{ h([addr]); }catch(e){} });\n" +
                "  }\n" +
                "  // 触发 window#ethereum#initialized 事件（部分DApp监听）\n" +
                "  window.dispatchEvent(new Event('ethereum#initialized'));\n" +
                "  // 如果 DApp 有自动连接逻辑（如 web3-modal），触发它\n" +
                "  if (typeof window.web3 !== 'undefined' && window.web3.currentProvider) {\n" +
                "    window.web3.currentProvider.selectedAddress = _nativeEth.getAddress();\n" +
                "  }\n" +
                "  console.log('[AI Wallet] autoConnect triggered for origin=" + currentOrigin + "');\n" +
                "})();";
            webView.evaluateJavascript(js, null);
            // 同时从Native层主动调用 eth_requestAccounts（通过JS）
            String requestJs = "(function(){\n" +
                "  if (window.ethereum && window.ethereum.request) {\n" +
                "    window.ethereum.request({method: 'eth_requestAccounts'}).then(function(accounts){\n" +
                "      console.log('[AI Wallet] autoConnect success: ' + accounts[0]);\n" +
                "    }).catch(function(e){\n" +
                "      console.log('[AI Wallet] autoConnect failed: ' + e.message);\n" +
                "    });\n" +
                "  }\n" +
                "})();";
            webView.evaluateJavascript(requestJs, null);
        }, 1500);
    }

    /**
     * 注入 WalletConnect URI 拦截脚本
     * 拦截 DApp 生成的 wc: URI，只保留轻量级被动拦截：
     * 1. window.open / location.href 跳转拦截
     * 2. WebSocket 连接监控（被动触发，不主动扫描）
     * 3. CustomEvent display_uri 拦截
     * 注意：已移除 deepScan（遍历 277 个 window 属性的三层嵌套循环）、Canvas QR 扫描器、
     *       定时 DOM 扫描等重型机制，避免 JS 线程阻塞导致应用卡死。
     */
    private void injectWalletConnectInterceptor(WebView view) {
        String js = "(function(){\n" +
            "  if (window.__wcInterceptorInjected) {\n" +
            "    return;\n" +
            "  }\n" +
            "  window.__wcInterceptorInjected = true;\n" +
            "\n" +
            "  function sendWcUri(uri, source) {\n" +
            "    if (window.__wcUriFound && window.__lastWcUri === uri) return;\n" +
            "    window.__wcUriFound = true;\n" +
            "    window.__lastWcUri = uri;\n" +
            "    console.log('[AI Wallet] WC URI found (' + source + '): ' + uri.substring(0, 80) + '...');\n" +
            "    // 如果 URI 没有 symKey，尝试从 WC SDK/localStorage 提取\n" +
            "    if (uri.indexOf('symKey=') < 0) {\n" +
            "      var topicMatch = uri.match(/@([^:?]+)/);\n" +
            "      var pTopic = topicMatch ? topicMatch[1] : '';\n" +
            "      if (pTopic) {\n" +
            "        for (var i = 0; i < localStorage.length; i++) {\n" +
            "          var lsKey = localStorage.key(i);\n" +
            "          var lsVal = localStorage.getItem(lsKey);\n" +
            "          if (lsVal && lsVal.indexOf(pTopic) >= 0) {\n" +
            "            var skMatch = lsVal.match(/\"symKey\"\\s*:\\s*\"([^\"]+)\"/);\n" +
            "            if (skMatch) {\n" +
            "              uri = uri + '&symKey=' + skMatch[1];\n" +
            "              console.log('[AI Wallet] 从 localStorage 补充 symKey: ' + skMatch[1].substring(0, 16) + '...');\n" +
            "              break;\n" +
            "            }\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "    // 解析 pairing topic\n" +
            "    var pairingTopic = pTopic || '';\n" +
            "    // 查找缓存的 irn_publish 消息\n" +
            "    var cachedPub = (window.__cachedPublish && pairingTopic) ? window.__cachedPublish[pairingTopic] : null;\n" +
            "    if (cachedPub) {\n" +
            "      console.log('[AI Wallet] 找到缓存的 session proposal，直接传递给钱包');\n" +
            "      try { _nativeEth.handleWalletConnectUriWithPublish(uri, cachedPub); } catch(e) { console.log('[AI Wallet] handleWithPublish error: ' + e.message); }\n" +
            "    } else {\n" +
            "      console.log('[AI Wallet] 未找到缓存 proposal，走标准 WC 中继');\n" +
            "      try { _nativeEth.handleWalletConnectUri(uri); } catch(e) { console.log('[AI Wallet] WC URI error: ' + e.message); }\n" +
            "    }\n" +
            "    // 同时用注入式 Provider 触发事件（双通道）\n" +
            "    try {\n" +
            "      if (window.ethereum) {\n" +
            "        window.ethereum.request({method: 'eth_requestAccounts'}).then(function(accounts){\n" +
            "          console.log('[AI Wallet] 注入式连接成功: ' + (accounts && accounts[0] ? accounts[0] : 'null'));\n" +
            "          var chainId = _nativeEth.getChainId();\n" +
            "          window.ethereum.emit('connect', {chainId: chainId});\n" +
            "          window.ethereum.emit('accountsChanged', accounts);\n" +
            "        }).catch(function(e){ console.log('[AI Wallet] 注入式连接失败: ' + (e && e.message ? e.message : e)); });\n" +
            "      }\n" +
            "    } catch(e) { console.log('[AI Wallet] 注入式异常: ' + e.message); }\n" +
            "  }\n" +
            "\n" +
            "  function tryExtractWcFromUrl(url, source) {\n" +
            "    if (!url || typeof url !== 'string') return false;\n" +
            "    var lower = url.toLowerCase();\n" +
            "    var isKnown = lower.indexOf('wc:') === 0;\n" +
            "    if (!isKnown) {\n" +
            "      var schemes = ['metamask://','trust://','rainbow://','rabby://','wc://'];\n" +
            "      for (var i=0;i<schemes.length;i++){ if(lower.startsWith(schemes[i])){ isKnown=true; break; } }\n" +
            "    }\n" +
            "    if (!isKnown && lower.indexOf('walletconnect') < 0 && lower.indexOf('metamask.app.link') < 0) return false;\n" +
            "    try {\n" +
            "      var decUrl = decodeURIComponent(url);\n" +
            "      var wcIdx = decUrl.indexOf('wc:');\n" +
            "      if (wcIdx > -1) {\n" +
            "        var wc = decUrl.substring(wcIdx);\n" +
            "        sendWcUri(wc, source + ' decoded');\n" +
            "        return true;\n" +
            "      }\n" +
            "    } catch(e) {}\n" +
            "    return false;\n" +
            "  }\n" +
            "\n" +
            // 1. 拦截 window.open（锁定，防止 DApp 覆盖）
            "  try {\n" +
            "    var _origOpen = window.open;\n" +
            "    Object.defineProperty(window, 'open', {\n" +
            "      configurable: false,\n" +
            "      writable: false,\n" +
            "      value: function(url, name, features) {\n" +
            "        console.log('[AI Wallet] window.open called: ' + (url||'').substring(0, 100));\n" +
            "        if (tryExtractWcFromUrl(url, 'window.open')) return null;\n" +
            "        return _origOpen.call(window, url, name, features);\n" +
            "      }\n" +
            "    });\n" +
            "  } catch(e) {}\n" +
            "\n" +
            // 2. 拦截 location.href 赋值
            "  try {\n" +
            "    var _origLocation = window.location;\n" +
            "    var _origAssign = _origLocation.assign.bind(_origLocation);\n" +
            "    var _origReplace = _origLocation.replace.bind(_origLocation);\n" +
            "    _origLocation.assign = function(url){ if(tryExtractWcFromUrl(url, 'location.assign')) return; return _origAssign(url); };\n" +
            "    _origLocation.replace = function(url){ if(tryExtractWcFromUrl(url, 'location.replace')) return; return _origReplace(url); };\n" +
            "  } catch(e) {}\n" +
            "\n" +
            // 3. Hook WebSocket 构造函数：relay 连接走 OkHttp 桥接，其他走原生
            "  try {\n" +
            "    var _OrigWS = window.WebSocket;\n" +
            "    window.__relaySockets = {};\n" +
            "    window.WebSocket = function(url, protocols) {\n" +
            "      console.log('[AI Wallet] WebSocket: ' + (url||'').substring(0, 80));\n" +
            "      if (typeof url === 'string' && url.indexOf('relay.walletconnect') > -1) {\n" +
            "        console.log('[AI Wallet] WC relay WebSocket detected, using OkHttp bridge');\n" +
            "        // 创建 Java 桥接的 WebSocket（OkHttp，更稳定）\n" +
            "        var bridgeId = _nativeEth.createRelaySocket(url);\n" +
            "        var fakeWs = {\n" +
            "          _bridgeId: bridgeId,\n" +
            "          readyState: 0, // CONNECTING\n" +
            "          onopen: null,\n" +
            "          onmessage: null,\n" +
            "          onerror: null,\n" +
            "          onclose: null,\n" +
            "          send: function(data) {\n" +
            "            // JavaScript 层缓存 irn_publish 消息，供钱包直接注入\n" +
            "            try {\n" +
            "              var msg = JSON.parse(data);\n" +
            "              if (msg.method === 'irn_publish' && msg.params && msg.params.topic) {\n" +
            "                if (!window.__cachedPublish) window.__cachedPublish = {};\n" +
            "                window.__cachedPublish[msg.params.topic] = data;\n" +
            "                console.log('[AI Wallet] JS缓存 irn_publish: topic=' + msg.params.topic.substring(0, 16) + '...');\n" +
            "              }\n" +
            "            } catch(e) {}\n" +
            "            _nativeEth.relaySocketSend(bridgeId, data);\n" +
            "          },\n" +
            "          close: function(code, reason) { _nativeEth.relaySocketClose(bridgeId); this.readyState = 3; },\n" +
            "          addEventListener: function(type, handler) { if (type === 'open') this.onopen = handler; else if (type === 'message') this.onmessage = handler; else if (type === 'error') this.onerror = handler; else if (type === 'close') this.onclose = handler; },\n" +
            "          removeEventListener: function() {}\n" +
            "        };\n" +
            "        window.__relaySockets[bridgeId] = fakeWs;\n" +
            "        return fakeWs;\n" +
            "      }\n" +
            "      return protocols ? new _OrigWS(url, protocols) : new _OrigWS(url);\n" +
            "    };\n" +
            "    window.WebSocket.prototype = _OrigWS.prototype;\n" +
            "  } catch(e) {}\n" +
            "\n" +
            // 4. 拦截 CustomEvent display_uri（WC SDK 触发的事件）
            "  try {\n" +
            "    var _OrigCE = window.CustomEvent;\n" +
            "    window.CustomEvent = function(type, options) {\n" +
            "      if (type === 'display_uri' || type === 'uri' || type === 'wc:displayUri') {\n" +
            "        try {\n" +
            "          if (options && options.detail) {\n" +
            "            var uri = typeof options.detail === 'string' ? options.detail : (options.detail.uri || options.detail.data || '');\n" +
            "            if (uri && uri.indexOf('wc:') === 0) {\n" +
            "              sendWcUri(uri, 'CustomEvent ' + type);\n" +
            "            }\n" +
            "          }\n" +
            "        } catch(e) {}\n" +
            "      }\n" +
            "      return new _OrigCE(type, options);\n" +
            "    };\n" +
            "    window.CustomEvent.prototype = _OrigCE.prototype;\n" +
            "  } catch(e) {}\n" +
            "\n" +
            // 5. 轻量级 DOM 监听（只监听文本节点变化，不扫描属性）
            "  try {\n" +
            "    var _wcObserver = new MutationObserver(function(mutations) {\n" +
            "      if (window.__wcUriFound) return;\n" +
            "      for (var i = 0; i < mutations.length; i++) {\n" +
            "        var added = mutations[i].addedNodes;\n" +
            "        for (var j = 0; j < added.length; j++) {\n" +
            "          if (added[j].nodeType === 3) {\n" +
            "            var text = added[j].textContent || '';\n" +
            "            if (text.indexOf('wc:') === 0 && text.indexOf('@2') > -1) {\n" +
            "              sendWcUri(text.trim(), 'DOM text node');\n" +
            "              return;\n" +
            "            }\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "    });\n" +
            "    if (document.documentElement) {\n" +
            "      _wcObserver.observe(document.documentElement, { childList: true, subtree: true });\n" +
            "    }\n" +
            "  } catch(e) {}\n" +
            "\n" +
            "  console.log('[AI Wallet] WalletConnect interceptor injected (lightweight: open + WS + CustomEvent + DOM observer)');\n" +
            "})();";
        view.evaluateJavascript(js, null);
    }

    /**
     * 处理 WalletConnect URI（原生层入口）
     */
    private void handleWalletConnectUri(String uri) {
        if (walletConnectRelay != null) {
            okhttp3.WebSocket existingWs = walletJsInterface != null ? walletJsInterface.getFirstRelaySocket() : null;
            if (existingWs != null) {
                Logger.info(this, "WalletConnect", "复用桥接WebSocket，即时订阅配对topic");
            }
            walletConnectRelay.connect(uri, existingWs);
        }
    }

    /**
     * 处理 WalletConnect URI + 缓存的 irn_publish 消息
     * 直接注入 publish 消息，不依赖中继转发
     */
    private void handleWalletConnectUriWithPublish(String uri, String publishMsg) {
        Logger.info(this, "WalletConnect", "handleWalletConnectUriWithPublish: uri=" + uri.substring(0, Math.min(60, uri.length())));
        if (walletConnectRelay != null && publishMsg != null) {
            // 复用桥接 WebSocket 连接中继
            okhttp3.WebSocket existingWs = walletJsInterface != null ? walletJsInterface.getFirstRelaySocket() : null;
            walletConnectRelay.connect(uri, existingWs);

            // 将 irn_publish 转换为 irn_message 格式并注入
            try {
                JSONObject publish = new JSONObject(publishMsg);
                JSONObject params = publish.optJSONObject("params");
                if (params != null) {
                    String topic = params.optString("topic", "");
                    String message = params.optString("message", "");
                    Logger.info(this, "WalletConnect", "注入缓存 publish: topic=" + topic.substring(0, Math.min(16, topic.length())) + " msgLen=" + message.length());

                    // 构造 irn_message 格式
                    JSONObject irnMsg = new JSONObject();
                    irnMsg.put("id", System.currentTimeMillis());
                    irnMsg.put("jsonrpc", "2.0");
                    irnMsg.put("method", "irn_message");
                    JSONObject msgParams = new JSONObject();
                    msgParams.put("topic", topic);
                    msgParams.put("message", message);
                    msgParams.put("publishedAt", System.currentTimeMillis());
                    irnMsg.put("params", msgParams);

                    final String injectedMsg = irnMsg.toString();
                    Logger.info(this, "WalletConnect", "构造 irn_message 注入: " + injectedMsg.substring(0, Math.min(200, injectedMsg.length())));

                    // 延迟 300ms 注入，确保 connect 解析 URI 完成
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        walletConnectRelay.handleRelayMessage(injectedMsg);
                    }, 300);
                }
            } catch (Exception e) {
                Logger.error(this, "WalletConnect", "注入缓存 publish 失败: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 从 WalletConnect 深度链接中提取 wc: URI
     * 支持 metamask.app.link、rainbow.me、trustwallet.com 等 universal link
     */
    private String extractWalletConnectUriFromDeepLink(String url) {
        if (url == null) return null;
        String lower = url.toLowerCase();
        // 常见的 WalletConnect universal link 域名
        String[] knownHosts = {
            "metamask.app.link",
            "rnbwapp.com",
            "link.trustwallet.com",
            "app.1inch.io",
            "walletconnect.com",
            "walletconnect.org"
        };
        // 常见自定义协议（metamask://, trust:// 等）
        String[] knownSchemes = {
            "metamask://",
            "trust://",
            "rainbow://",
            "rabby://",
            "wc://"
        };
        boolean known = false;
        for (String host : knownHosts) {
            if (lower.contains(host)) { known = true; break; }
        }
        for (String scheme : knownSchemes) {
            if (lower.startsWith(scheme)) { known = true; break; }
        }
        if (!known) return null;
        Logger.info(this, "WalletConnect", "识别到深度链接: " + url.substring(0, Math.min(200, url.length())));
        // 提取 ?uri=wc:... 或路径中的 wc: URI
        int idx = lower.indexOf("uri=");
        if (idx > 0) {
            String encoded = url.substring(idx + 4);
            int end = encoded.indexOf("&");
            if (end > 0) encoded = encoded.substring(0, end);
            try {
                String decoded = java.net.URLDecoder.decode(encoded, "UTF-8");
                if (decoded.startsWith("wc:")) return decoded;
            } catch (Exception ignored) {}
        }
        // 直接在 URL 中查找 wc: 字符串
        int wcIdx = url.indexOf("wc:");
        if (wcIdx > 0) {
            String wc = url.substring(wcIdx);
            int end = wc.indexOf("&");
            if (end > 0) wc = wc.substring(0, end);
            try {
                return java.net.URLDecoder.decode(wc, "UTF-8");
            } catch (Exception ignored) { return wc; }
        }
        return null;
    }

    /**
     * 从 Canvas data URL 解码 QR 码，提取 wc: URI
     * Transit 等 DApp 通过 Canvas QR 码展示 WalletConnect URI，需要解码
     */
    private void decodeQrCodeFromDataUrl(String dataUrl) {
        try {
            // data URL 格式: data:image/png;base64,xxxx
            if (dataUrl == null || !dataUrl.startsWith("data:image/")) {
                Logger.warning(this, "WalletConnect", "QR 解码跳过：非 data:image/ 格式");
                return;
            }
            String base64 = dataUrl.substring(dataUrl.indexOf(",") + 1);
            if (base64.length() < 100) {
                Logger.warning(this, "WalletConnect", "QR 解码跳过：base64 数据太短 (" + base64.length() + " bytes)");
                return;
            }
            byte[] imageBytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            Logger.info(this, "WalletConnect", "QR 解码开始：dataUrl 长度=" + dataUrl.length() + " 图片字节=" + imageBytes.length);

            // Android Bitmap 解码
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
            if (bitmap == null) {
                Logger.warning(this, "WalletConnect", "QR 解码失败：Bitmap 解码返回 null");
                return;
            }

            Logger.info(this, "WalletConnect", "QR 解码：Bitmap " + bitmap.getWidth() + "x" + bitmap.getHeight());

            // 提取像素数据
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            bitmap.recycle();

            // 使用 ZXing 解码 QR 码
            com.google.zxing.RGBLuminanceSource source = new com.google.zxing.RGBLuminanceSource(width, height, pixels);
            com.google.zxing.BinaryBitmap binaryBitmap = new com.google.zxing.BinaryBitmap(
                new com.google.zxing.common.HybridBinarizer(source)
            );

            com.google.zxing.Result result = new com.google.zxing.qrcode.QRCodeReader().decode(binaryBitmap);
            if (result != null) {
                String text = result.getText();
                Logger.info(this, "WalletConnect", "QR 码解码成功: " + text);
                if (text != null && text.startsWith("wc:")) {
                    handleWalletConnectUri(text);
                } else {
                    Logger.info(this, "WalletConnect", "QR 码内容不是 wc: URI，忽略: " + (text != null ? text.substring(0, Math.min(50, text.length())) : "null"));
                }
            }
        } catch (com.google.zxing.NotFoundException e) {
            Logger.info(this, "WalletConnect", "QR 解码：未找到 QR 码（图片可能不是 QR 码）");
        } catch (Exception e) {
            Logger.error(this, "WalletConnect", "QR 解码异常: " + e.getMessage(), e);
        }
    }

    /**
     * 显示 WalletConnect 会话确认弹窗
     */
    private void showWalletConnectSessionDialog(String dappName, String dappUrl, JSONArray requiredChains) {
        uiHandler.post(() -> {
            String chainsStr = "";
            try {
                if (requiredChains != null && requiredChains.length() > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < Math.min(requiredChains.length(), 5); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(requiredChains.optString(i));
                    }
                    if (requiredChains.length() > 5) sb.append("...");
                    chainsStr = sb.toString();
                }
            } catch (Exception ignored) {}

            String message = "DApp 请求连接你的钱包\n\n"
                + "DApp：" + dappName + "\n"
                + "网址：" + dappUrl + "\n";
            if (!chainsStr.isEmpty()) {
                message += "请求链：" + chainsStr + "\n";
            }
            message += "\n确认后，此DApp将可以查看你的钱包地址并发起交易。";

            new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                .setTitle(getString(R.string.title_walletconnect_connection_request))
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(getString(R.string.btn_connection), (d, w) -> {
                    String address = WalletManager.getWalletAddress(this);
                    String chainId = new WalletJsInterface().getChainId();
                    Logger.action(this, "WalletConnect", "用户批准会话: " + dappName, null);
                    if (walletConnectRelay != null) {
                        walletConnectRelay.approveSession(address, chainId);
                    }
                    Toast.makeText(this, getString(R.string.toast_walletconnect_is_connected, dappName), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(getString(R.string.btn_reject), (d, w) -> {
                    Logger.action(this, "WalletConnect", "用户拒绝会话: " + dappName, null);
                    if (walletConnectRelay != null) {
                        walletConnectRelay.rejectSession();
                    }
                })
                .show();
        });
    }

    /**
     * 处理 WalletConnect 会话请求（签名/交易等）
     */
    private void handleWalletConnectSessionRequest(String method, JSONArray params, long requestId) {
        try {
            switch (method) {
                case "eth_sendTransaction":
                    if (params != null && params.length() > 0) {
                        JSONObject tx = params.getJSONObject(0);
                        String to = tx.optString("to", "");
                        String valueHex = tx.optString("value", "0x0");
                        String data = tx.optString("data", "0x");
                        uiHandler.post(() -> {
                            new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                                .setTitle(getString(R.string.title_walletconnect_transaction_request))
                                .setMessage(getString(R.string.msg_walletconnect_transaction_details,
                                    to.substring(0, Math.min(to.length(), 20)),
                                    data.substring(0, Math.min(data.length(), 50))))
                                .setPositiveButton(getString(R.string.btn_okay), (d, w) -> {
                                    txExecutor.execute(() -> {
                                        try {
                                            String chain = WalletManager.getChain(DAppBrowserActivity.this);
                                            DexTrader trader = new DexTrader();
                                            String txHash = trader.executeRawTransaction(DAppBrowserActivity.this,
                                                chain, to, data, parseHex(valueHex));
                                            JSONObject result = new JSONObject();
                                            result.put("txHash", txHash);
                                            result.put("from", WalletManager.getWalletAddress(DAppBrowserActivity.this));
                                            walletConnectRelay.respondSessionRequest(requestId, result.toString());
                                            Logger.action(DAppBrowserActivity.this, "WalletConnect交易", "成功 hash=" + txHash, null);
                                        } catch (Exception e) {
                                            walletConnectRelay.respondSessionRequestError(requestId, -32000, e.getMessage());
                                        }
                                    });
                                })
                                .setNegativeButton(getString(R.string.btn_reject), (d, w) -> {
                                    walletConnectRelay.respondSessionRequestError(requestId, 4001, "用户拒绝");
                                })
                                .show();
                        });
                    }
                    break;
                case "personal_sign":
                case "eth_signTypedData":
                case "eth_signTypedData_v4":
                    uiHandler.post(() -> {
                        String msg = params != null && params.length() > 0 ? params.optString(0, "") : "";
                        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                            .setTitle(getString(R.string.title_walletconnect_signing_request))
                            .setMessage(getString(R.string.msg_dapp_requests_eip_712, msg.substring(0, Math.min(msg.length(), 200))))
                            .setPositiveButton(getString(R.string.btn_signature), (d, w) -> {
                                txExecutor.execute(() -> {
                                    try {
                                        String chain = WalletManager.getChain(DAppBrowserActivity.this);
                                        org.web3j.crypto.Credentials creds = DexTrader.getCredentialsForChain(DAppBrowserActivity.this, chain);
                                        byte[] msgBytes = msg.getBytes("UTF-8");
                                        org.web3j.crypto.Sign.SignatureData sig = org.web3j.crypto.Sign.signMessage(msgBytes, creds.getEcKeyPair(), false);
                                        byte[] vBytes = sig.getV();
                                        java.math.BigInteger vBigInt;
                                        if (vBytes == null || vBytes.length == 0) {
                                            vBigInt = java.math.BigInteger.valueOf(27);
                                        } else if (vBytes.length == 1) {
                                            vBigInt = java.math.BigInteger.valueOf(vBytes[0] & 0xFF);
                                        } else {
                                            vBigInt = new java.math.BigInteger(1, vBytes);
                                        }
                                        String sigHex = "0x" +
                                            org.web3j.utils.Numeric.toHexStringNoPrefixZeroPadded(new java.math.BigInteger(1, sig.getR()), 64) +
                                            org.web3j.utils.Numeric.toHexStringNoPrefixZeroPadded(new java.math.BigInteger(1, sig.getS()), 64) +
                                            org.web3j.utils.Numeric.toHexStringNoPrefixZeroPadded(vBigInt, 2);
                                        JSONObject result = new JSONObject();
                                        result.put("signature", sigHex);
                                        walletConnectRelay.respondSessionRequest(requestId, result.toString());
                                    } catch (Exception e) {
                                        walletConnectRelay.respondSessionRequestError(requestId, -32000, e.getMessage());
                                    }
                                });
                            })
                            .setNegativeButton(getString(R.string.btn_reject), (d, w) -> {
                                walletConnectRelay.respondSessionRequestError(requestId, 4001, "用户拒绝");
                            })
                            .show();
                    });
                    break;
                default:
                    // 只读方法（eth_call, eth_getBalance 等）直接转发到 RPC
                    if (method.startsWith("eth_") && !method.equals("eth_sendTransaction") && !method.equals("eth_sendRawTransaction")) {
                        txExecutor.execute(() -> {
                            try {
                                String chain = WalletManager.getChain(DAppBrowserActivity.this);
                                String rpcUrl = ChainAPI.getRpcUrlStatic(DAppBrowserActivity.this, chain);
                                JSONObject body = new JSONObject();
                                body.put("jsonrpc", "2.0");
                                body.put("id", 1);
                                body.put("method", method);
                                body.put("params", params);

                                okhttp3.MediaType JSON = okhttp3.MediaType.parse("application/json");
                                okhttp3.Request request = new okhttp3.Request.Builder()
                                    .url(rpcUrl)
                                    .post(okhttp3.RequestBody.create(body.toString(), JSON))
                                    .build();
                                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                                    .build();

                                try (okhttp3.Response resp = client.newCall(request).execute()) {
                                    String respBody = resp.body() != null ? resp.body().string() : "";
                                    JSONObject json = new JSONObject(respBody);
                                    Object result = json.opt("result");
                                    JSONObject wcResult = new JSONObject();
                                    wcResult.put("result", result != null ? result : JSONObject.NULL);
                                    walletConnectRelay.respondSessionRequest(requestId, wcResult.toString());
                                }
                            } catch (Exception e) {
                                walletConnectRelay.respondSessionRequestError(requestId, -32000, e.getMessage());
                            }
                        });
                    } else {
                        walletConnectRelay.respondSessionRequestError(requestId, -32601, "不支持的方法: " + method);
                    }
                    break;
            }
        } catch (Exception e) {
            Logger.error(this, "WalletConnect", "处理会话请求异常: " + e.getMessage(), e);
            walletConnectRelay.respondSessionRequestError(requestId, -32000, e.getMessage());
        }
    }

    /**
     * 注入反检测脚本，降低 WebView 被 reCAPTCHA / hCaptcha / 设备指纹风控识别为机器人的概率
     * 包括：隐藏 navigator.webdriver、伪造 plugins/mimeTypes、Canvas/WebGL 指纹扰动等
     */
    private void injectAntiDetectionScripts(WebView view) {
        String js = "(function(){" +
            "  if (window.__antiDetectInjected) return;" +
            "  window.__antiDetectInjected = true;" +
            "  try {" +
            "    Object.defineProperty(navigator, 'webdriver', {" +
            "      get: function(){ return undefined; }," +
            "      configurable: true" +
            "    });" +
            "    Object.defineProperty(navigator, 'plugins', {" +
            "      get: function(){" +
            "        var p = [];" +
            "        p.length = 3;" +
            "        p.item = function(i){ return p[i]; };" +
            "        p.namedItem = function(n){ return null; };" +
            "        p.refresh = function(){};" +
            "        return p;" +
            "      }" +
            "    });" +
            "    Object.defineProperty(navigator, 'mimeTypes', {" +
            "      get: function(){" +
            "        var m = [];" +
            "        m.length = 0;" +
            "        m.item = function(i){ return null; };" +
            "        m.namedItem = function(n){ return null; };" +
            "        return m;" +
            "      }" +
            "    });" +
            "    Object.defineProperty(navigator, 'permissions', {" +
            "      get: function(){ return { query: function(){ return Promise.resolve({state: 'prompt'}); } }; }" +
            "    });" +
            // 关键修复：覆盖 matchMedia，让 DApp（如 Transit）认为是桌面端
            // Transit 在移动端只显示 WalletConnect 钱包，不显示 injected 钱包
            // 覆盖媒体查询让 Transit 认为是桌面端，从而显示所有钱包包括 injected
            "    var _origMatchMedia = window.matchMedia ? window.matchMedia.bind(window) : null;" +
            "    window.matchMedia = function(query){" +
            "      var result = _origMatchMedia ? _origMatchMedia(query) : { matches: false, media: query, onchange: null, addListener: function(){}, removeListener: function(){}, addEventListener: function(){}, removeEventListener: function(){}, dispatchEvent: function(){ return false; } };" +
            "      if (typeof query === 'string' && (query.indexOf('max-width') > -1 || query.indexOf('max-device-width') > -1)) {" +
            "        return { matches: false, media: query, onchange: null, addListener: function(){}, removeListener: function(){}, addEventListener: function(){}, removeEventListener: function(){}, dispatchEvent: function(){ return false; } };" +
            "      }" +
            "      if (typeof query === 'string' && (query.indexOf('min-width: 7') > -1 || query.indexOf('min-width: 8') > -1 || query.indexOf('min-width: 9') > -1 || query.indexOf('min-width: 1') > -1)) {" +
            "        return { matches: true, media: query, onchange: null, addListener: function(){}, removeListener: function(){}, addEventListener: function(){}, removeEventListener: function(){}, dispatchEvent: function(){ return false; } };" +
            "      }" +
            "      return result;" +
            "    };" +
            // 关键修复：移除 getImageData hook，它破坏了 QR 码扫描
            // 之前 hook 会修改 canvas 像素数据，导致 WalletConnect QR 码解码失败
            "    if (window.Notification) {" +
            "      Object.defineProperty(Notification, 'permission', { get: function(){ return 'default'; } });" +
            "    }" +
            "    if (typeof window.chrome === 'undefined') window.chrome = {};" +
            "    if (!window.chrome.runtime) window.chrome.runtime = {};" +
            "    var origGetParam = WebGLRenderingContext.prototype.getParameter;" +
            "    WebGLRenderingContext.prototype.getParameter = function(p){" +
            "      if (p === 37445) return 'Intel Inc.';" +
            "      if (p === 37446) return 'Intel Iris Xe Graphics';" +
            "      return origGetParam.call(this, p);" +
            "    };" +
            "    console.log('[AI Wallet] anti-detection scripts injected (matchMedia override + getImageData fix)');" +
            "  } catch(e) { console.log('[AI Wallet] anti-detection error: ' + e.message); }" +
            "})();";
        view.evaluateJavascript(js, null);
    }

    /**
     * 从 URL 提取 origin（协议+域名+端口）
     * 例如 https://app.uniswap.org/swap → https://app.uniswap.org
     */
    private String extractOrigin(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            java.net.URI uri = new java.net.URI(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (scheme == null || host == null) return "";
            StringBuilder sb = new StringBuilder().append(scheme).append("://").append(host);
            if (port > 0 && !((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443))) {
                sb.append(":").append(port);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 注入 EIP-1193 + EIP-6963 兼容 provider 脚本
     * - EIP-1193: window.ethereum.request() 标准调用接口
     * - EIP-6963: Multi Injected Provider Discovery，让 PancakeSwap/Uniswap 等现代 DApp 能发现钱包
     */
    private void injectEIP1193Provider(WebView view) {
        String js = "(function(){\n" +
            "  if (window.__ethereumInjected) { console.log('[AI Wallet] provider already injected, skipping'); return; }\n" +
            "  window.__ethereumInjected = true;\n" +
            "  var callbacks = {};\n" +
            "  var nextId = 1;\n" +
            "  window.__dappCallback = function(id, ok, result, error){\n" +
            "    var cb = callbacks[id];\n" +
            "    if (!cb) return;\n" +
            "    delete callbacks[id];\n" +
            "    if (ok) cb.resolve(result);\n" +
            "    else cb.reject(new Error(error || 'unknown error'));\n" +
            "  };\n" +
            "  var listeners = {};\n" +
            "  var provider = {\n" +
            "    isMetaMask: true,\n" +
            "    isAIWallet: true,\n" +
            "    isTrust: false,\n" +
            "    _metamask: { isUnlocked: function(){ return true; } },\n" +
            "    request: function(args){\n" +
            "      return new Promise(function(resolve, reject){\n" +
            "        var id = 'cb_' + (nextId++);\n" +
            "        callbacks[id] = {resolve: resolve, reject: reject};\n" +
            "        var params = args.params || [];\n" +
            "        try { _nativeEth.request(args.method, JSON.stringify(params), id); }\n" +
            "        catch(e){ delete callbacks[id]; reject(e); }\n" +
            "      });\n" +
            "    },\n" +
            "    on: function(event, handler){\n" +
            "      if (!listeners[event]) listeners[event] = [];\n" +
            "      listeners[event].push(handler);\n" +
            "    },\n" +
            "    removeListener: function(event, handler){\n" +
            "      if (!listeners[event]) return;\n" +
            "      listeners[event] = listeners[event].filter(function(h){return h !== handler;});\n" +
            "    },\n" +
            "    listeners: function(event){ return listeners[event] ? listeners[event].slice() : []; },\n" +
            "    emit: function(event, data){\n" +
            "      if (!listeners[event]) return;\n" +
            "      listeners[event].forEach(function(h){ try{ h(data); }catch(e){} });\n" +
            "    },\n" +
            "    enable: function(){\n" +
            "      return this.request({method: 'eth_requestAccounts'});\n" +
            "    },\n" +
            "    send: function(args){\n" +
            "      return this.request(args);\n" +
            "    },\n" +
            "    sendAsync: function(args, cb){\n" +
            "      this.request(args).then(function(r){ cb(null, {id: args.id, jsonrpc:'2.0', result: r}); }).catch(function(e){ cb(e); });\n" +
            "    }\n" +
            "  };\n" +
            "  Object.defineProperty(provider, 'selectedAddress', {get: function(){return _nativeEth.getAddress();}});\n" +
"  Object.defineProperty(provider, 'chainId', {get: function(){return _nativeEth.getChainId();}});\n" +
"  Object.defineProperty(provider, 'networkVersion', {get: function(){return parseInt(_nativeEth.getChainId(), 16).toString();}});\n" +
            "  Object.defineProperty(provider, 'isConnected', {value: function(){return true;}});\n" +
            "  // EIP-6963: Provider info（模拟 MetaMask 以提高 DApp 兼容性）\n" +
            "  var providerInfo = {\n" +
            "    rdns: 'io.metamask',\n" +
            "    uuid: 'metamask-' + Date.now(),\n" +
            "    name: 'MetaMask',\n" +
            "    icon: 'data:image/svg+xml,<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 32 32\"><rect width=\"32\" height=\"32\" rx=\"8\" fill=\"%23F6851B\"/><text x=\"16\" y=\"23\" text-anchor=\"middle\" fill=\"white\" font-size=\"20\" font-weight=\"bold\">M</text></svg>'\n" +
            "  };\n" +
            "  window.ethereum = provider;\n" +
            "  if (window.ethereum.providers) { window.ethereum.providers.unshift(provider); } else { window.ethereum.providers = [provider]; }\n" +
            "  // 使用 getter/setter 锁定 window.ethereum，防止 DApp 脚本覆盖\n" +
            "  try {\n" +
            "    var _lockedProvider = provider;\n" +
            "    Object.defineProperty(window, 'ethereum', {\n" +
            "      get: function(){ return _lockedProvider; },\n" +
            "      set: function(v){ /* 忽略覆盖，保持我们的 provider */ },\n" +
            "      configurable: false,\n" +
            "      enumerable: true\n" +
            "    });\n" +
            "  } catch(e){ console.log('[AI Wallet] defineProperty failed: ' + e.message); }\n" +
            "  window.__aicw_connect = function(){ return provider.request({method:'eth_requestAccounts'}); };\n" +
            "  // EIP-6963: 广播 provider 给 DApp\n" +
            "  window.__providerInfo = providerInfo;\n" +
            "  function announceProvider(){\n" +
            "    console.log('[AI Wallet] EIP-6963 announceProvider: rdns=' + providerInfo.rdns + ' name=' + providerInfo.name);\n" +
            "    window.dispatchEvent(new CustomEvent('eip6963:announceProvider', {\n" +
            "      detail: { info: providerInfo, provider: provider }\n" +
            "    }));\n" +
            "  }\n" +
            "  announceProvider();\n" +
            "  // 监听 DApp 的 eip6963:requestProvider，重新广播\n" +
            "  window.addEventListener('eip6963:requestProvider', function(){\n" +
            "    console.log('[AI Wallet] EIP-6963 requestProvider received from DApp, re-announcing...');\n" +
            "    announceProvider();\n" +
            "  });\n" +
            "  // 传统事件兼容\n" +
            "  window.dispatchEvent(new Event('ethereum#initialized'));\n" +
            "  console.log('[AI Wallet] EIP-6963 provider injected, rdns=com.aicryptowallet.app');\n" +
            "})();";
        view.evaluateJavascript(js, null);
    }

    /**
     * 重新广播 EIP-6963 announce 事件，解决 SPA 页面加载时序问题。
     * 现代 DApp（React/Vue SPA）在 onPageStarted 时 JS 尚未执行，
     * 首次广播的 eip6963:announceProvider 会丢失。
     * 此方法在 onPageFinished 时重新广播，确保 DApp 的 EIP-6963 监听器能收到事件。
     */
    private void reAnnounceEIP6963(WebView view) {
        String js = "(function(){\n" +
            "  if (!window.__ethereumInjected || !window.__providerInfo) return;\n" +
            "  // 重新广播 EIP-6963 announce 事件\n" +
            "  window.dispatchEvent(new CustomEvent('eip6963:announceProvider', {\n" +
            "    detail: { info: window.__providerInfo, provider: window.ethereum }\n" +
            "  }));\n" +
            "  // 同时触发传统 ethereum#initialized 事件（兼容旧版 DApp）\n" +
            "  window.dispatchEvent(new Event('ethereum#initialized'));\n" +
            "  console.log('[AI Wallet] EIP-6963 re-announced on page finish');\n" +
            "})();";
        view.evaluateJavascript(js, null);
    }

    /**
     * 主动触发钱包连接事件
     * 仅在 DApp 已授权时才自动触发 connect/accountsChanged，避免未授权时自动连接
     */
    private void triggerWalletConnect(WebView view) {
        String address = WalletManager.getWalletAddress(this);
        boolean authorized = isDAppAuthorized(currentOrigin, address);
        if (!authorized) {
            Logger.info(this, "DApp连接", "未授权，不自动触发连接 origin=" + currentOrigin);
            return;
        }
        Logger.info(this, "DApp连接", "已授权，自动触发连接 origin=" + currentOrigin);
        String js = "(function(){\n" +
            "  if (!window.ethereum || !window.__ethereumInjected) return;\n" +
            "  // 1. 触发 connect 事件\n" +
            "  if (window.ethereum._events) { window.ethereum._events.connect && window.ethereum._events.connect(); }\n" +
            "  // 2. 通知 DApp 账户已变更（触发自动连接检测）\n" +
            "  if (window.ethereum.listeners) {\n" +
            "    var addr = _nativeEth.getAddress();\n" +
            "    window.ethereum.listeners('accountsChanged') && window.ethereum.listeners('accountsChanged').forEach(function(h){ try{ h([addr]); }catch(e){} });\n" +
            "    window.ethereum.listeners('connect') && window.ethereum.listeners('connect').forEach(function(h){ try{ h({chainId: _nativeEth.getChainId()}); }catch(e){} });\n" +
            "  }\n" +
            "  // 3. 如果 DApp 有 window.ethereum.enable 调用历史，自动重新触发\n" +
            "  console.log('[AI Wallet] Wallet connect triggered, addr=' + _nativeEth.getAddress());\n" +
            "})();";
        view.evaluateJavascript(js, null);
    }

    /**
     * 解析 hex 字符串为 BigInteger
     */
    private BigInteger parseHex(String hex) {
        if (hex == null || hex.isEmpty() || "0x".equals(hex)) return BigInteger.ZERO;
        String h = hex.startsWith("0x") ? hex.substring(2) : hex;
        if (h.isEmpty()) return BigInteger.ZERO;
        return new BigInteger(h, 16);
    }

    // ============================================================
    // DApp 白名单相关
    // ============================================================

    /** 获取当前页面域名 */
    private String getCurrentDomain() {
        try {
            // 1) 优先使用静态缓存域名（UI 线程更新，后台线程可安全读取，且不受导航/重建影响）
            if (sCurrentDomain != null && !sCurrentDomain.isEmpty()) {
                return sCurrentDomain;
            }
            // 2) 回退到 UI 线程捕获的 currentOrigin。
            //    注意：不能在后台线程调用 webView.getUrl()——WebView 方法必须在 UI 线程调用，
            //    后台线程调用会返回 null/抛出异常，导致域名恒为空、白名单校验永远失败。
            if (currentOrigin != null && !currentOrigin.isEmpty()) {
                return DAppWhitelistManager.normalizeDomain(currentOrigin);
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    /** 判断当前 DApp 是否在白名单 */
    private boolean isCurrentDAppWhitelisted() {
        String domain = getCurrentDomain();
        if (whitelistManager == null) return false;
        // 先刷新缓存，确保 AI 工具新加入的域名能被读到
        whitelistManager.reload();
        return whitelistManager.isWhitelisted(domain);
    }

    /** 检查当前 DApp 是否允许指定 AI 操作 */
    private boolean isCurrentOperationAllowed(String operation) {
        String domain = getCurrentDomain();
        if (whitelistManager == null) return false;
        // 先刷新缓存，确保 AI 工具新加入的域名能被读到
        whitelistManager.reload();
        return whitelistManager.isOperationAllowed(domain, operation);
    }

    /** 显示 DApp 白名单授权弹窗（用户主动添加） */
    private void showDAppWhitelistDialog(String domain, WhitelistCallback callback) {
        if (domain == null || domain.isEmpty()) {
            if (callback != null) callback.onResult(false);
            return;
        }
        uiHandler.post(() -> {
            new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                .setTitle(getString(R.string.title_dapp_whitelist_authorization))
                .setMessage(getString(R.string.msg_dapp_whitelist_prompt, domain))
                .setPositiveButton(getString(R.string.label_authorize_and_set_limits), (d, w) -> {
                    showDAppWhitelistConfigDialog(domain, callback);
                })
                .setNegativeButton(getString(R.string.btn_reject), (d, w) -> {
                    if (callback != null) callback.onResult(false);
                })
                .setOnCancelListener(d -> {
                    if (callback != null) callback.onResult(false);
                })
                .show();
        });
    }

    /** 显示额度配置弹窗 */
    private void showDAppWhitelistConfigDialog(String domain, WhitelistCallback callback) {
        uiHandler.post(() -> {
            android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            layout.setPadding(48, 24, 48, 24);

            String currencyCode = CurrencyManager.getSelectedCurrency(this);

            android.widget.TextView tvDaily = new android.widget.TextView(this);
            tvDaily.setText(getString(R.string.text_daily_quota_limit, currencyCode));
            layout.addView(tvDaily);

            final android.widget.EditText etDaily = new android.widget.EditText(this);
            etDaily.setHint(getString(R.string.hint_e_10));
            etDaily.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            etDaily.setText("100");
            layout.addView(etDaily);

            android.widget.TextView tvPerTx = new android.widget.TextView(this);
            tvPerTx.setText(getString(R.string.text_maximum_single_transaction_limit, currencyCode));
            tvPerTx.setPadding(0, 16, 0, 0);
            layout.addView(tvPerTx);

            final android.widget.EditText etPerTx = new android.widget.EditText(this);
            etPerTx.setHint(getString(R.string.hint_e_10));
            etPerTx.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            etPerTx.setText("10");
            layout.addView(etPerTx);

            new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                .setTitle(getString(R.string.title_set_limit))
                .setView(layout)
                .setPositiveButton(getString(R.string.btn_confirm_authorization), (d, w) -> {
                    try {
                        String dailyStr = etDaily.getText().toString().trim();
                        String perTxStr = etPerTx.getText().toString().trim();
                        double dailyCap = dailyStr.isEmpty() ? 0 : Double.parseDouble(dailyStr);
                        double perTxCap = perTxStr.isEmpty() ? 0 : Double.parseDouble(perTxStr);

                        DAppWhitelistManager.Entry entry = new DAppWhitelistManager.Entry();
                        entry.domain = domain;
                        entry.allowClick = true;
                        entry.allowInput = true;
                        entry.allowEvaluate = true;
                        entry.allowTransaction = true;
                        entry.dailyCapUsd = new java.math.BigDecimal(String.valueOf(dailyCap));
                        entry.perTxCapUsd = new java.math.BigDecimal(String.valueOf(perTxCap));
                        entry.addedAt = System.currentTimeMillis();
                        entry.riskConfirmed = "用户已确认将 " + domain + " 加入 AI 自动操作白名单";
                        whitelistManager.putEntry(entry);

                        Logger.action(this, "DApp白名单", "授权域名=" + domain + " 每日额度=" + dailyCap + " 单笔额度=" + perTxCap, null);
                        if (callback != null) callback.onResult(true);
                    } catch (Exception e) {
                        Toast.makeText(this, getString(R.string.toast_wrong_credit_format), Toast.LENGTH_SHORT).show();
                        if (callback != null) callback.onResult(false);
                    }
                })
                .setNegativeButton(getString(R.string.str_s_decline), (d, w) -> {
                    if (callback != null) callback.onResult(false);
                })
                .setOnCancelListener(d -> {
                    if (callback != null) callback.onResult(false);
                })
                .show();
        });
    }

    private interface WhitelistCallback {
        void onResult(boolean granted);
    }

    /**
     * 供 AI 工具 request_dapp_whitelist 调用：弹出白名单授权确认。
     * 优先使用系统级悬浮窗弹窗（即使 App 在后台也能显示）；
     * 若未获得悬浮窗权限且 DAppBrowserActivity 在前台，则走 Activity 弹窗；
     * 两者都不可用时返回 noUi，调用方回退到 AI 聊天 ask_user。
     */
    public static WhitelistDialogResult requestWhitelistFromUI(Context ctx, String domain, String details) {
        Context appCtx = ctx != null ? ctx.getApplicationContext() : sAppContext;
        if (appCtx == null) {
            return WhitelistDialogResult.noUi();
        }

        // 1. 已获得系统悬浮窗权限：使用最高层级覆盖弹窗
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(appCtx)) {
            Logger.info(appCtx, "DApp白名单弹窗", "使用系统悬浮窗弹窗: " + domain);
            return showOverlayWhitelistDialog(appCtx, domain, details);
        }

        // 2. 未获得悬浮窗权限：尝试请求并引导用户开启（首次使用）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Logger.info(appCtx, "DApp白名单弹窗", "缺少 SYSTEM_ALERT_WINDOW 权限，引导开启: " + domain);
            AINotificationHelper.notifyPermissionRequest(appCtx, "需要悬浮窗权限",
                "AI 申请 DApp 白名单需要在其他应用上层显示弹窗，请点击前往系统设置开启权限。");
            requestOverlayPermission(appCtx);
        }

        // 3. 回退到当前 DApp 浏览器 Activity 弹窗（若在前台）
        final DAppBrowserActivity activity = sInstance;
        if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
            Logger.info(appCtx, "DApp白名单弹窗", "回退到 Activity 弹窗: " + domain);
            return activity.showAiWhitelistDialog(domain, details);
        }

        return WhitelistDialogResult.noUi();
    }

    /** AI 申请白名单的同步 Activity 弹窗（阻塞最多 60 秒等待用户点击） */
    private WhitelistDialogResult showAiWhitelistDialog(String domain, String details) {
        final CountDownLatch latch = new CountDownLatch(1);
        final WhitelistDialogResult[] result = new WhitelistDialogResult[1];
        result[0] = WhitelistDialogResult.noUi();
        whitelistLatchRef.set(new WhitelistDialogLatch(latch, result));

        uiHandler.post(() -> {
            new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                .setTitle(getString(R.string.title_ai_applies_for_dapp))
                .setMessage(details)
                .setCancelable(false)
                .setPositiveButton(getString(R.string.btn_agree_to_authorization), (d, w) -> {
                    result[0] = WhitelistDialogResult.allow();
                    latch.countDown();
                })
                .setNegativeButton(getString(R.string.btn_reject), (d, w) -> {
                    result[0] = WhitelistDialogResult.deny();
                    latch.countDown();
                })
                .setOnCancelListener(d -> {
                    result[0] = WhitelistDialogResult.deny();
                    latch.countDown();
                })
                .show();
        });

        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            whitelistLatchRef.set(null);
        }
        return result[0];
    }

    /** 系统级悬浮窗弹窗：可在后台显示，阻塞最多 60 秒 */
    private static WhitelistDialogResult showOverlayWhitelistDialog(Context appCtx, String domain, String details) {
        final CountDownLatch latch = new CountDownLatch(1);
        final WhitelistDialogResult[] result = new WhitelistDialogResult[1];
        result[0] = WhitelistDialogResult.noUi();
        sOverlayLatchRef.set(new WhitelistDialogLatch(latch, result));

        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> {
            try {
                AlertDialog dialog = new AlertDialog.Builder(
                        new android.view.ContextThemeWrapper(appCtx, R.style.AlertDialogCustom))
                    .setTitle(appCtx.getString(R.string.title_ai_applies_for_dapp))
                    .setMessage(details)
                    .setCancelable(false)
                    .setPositiveButton(appCtx.getString(R.string.btn_agree_to_authorization), (d, w) -> {
                        result[0] = WhitelistDialogResult.allow();
                        latch.countDown();
                    })
                    .setNegativeButton(appCtx.getString(R.string.btn_reject), (d, w) -> {
                        result[0] = WhitelistDialogResult.deny();
                        latch.countDown();
                    })
                    .setOnCancelListener(d -> {
                        result[0] = WhitelistDialogResult.deny();
                        latch.countDown();
                    })
                    .create();

                if (dialog.getWindow() != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                    } else {
                        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
                    }
                }
                dialog.show();
            } catch (Exception e) {
                Logger.error(appCtx, "DApp白名单弹窗", "系统悬浮窗弹窗失败: " + e.getMessage(), e);
                latch.countDown();
            }
        });

        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            sOverlayLatchRef.set(null);
        }
        return result[0];
    }

    /** 跳转到系统设置，请求悬浮窗权限 */
    private static void requestOverlayPermission(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + ctx.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
            } catch (Exception e) {
                Logger.error(ctx, "DApp白名单弹窗", "跳转悬浮窗权限设置失败: " + e.getMessage(), e);
            }
        }
    }

    // ============================================================
    // AI 浏览器桥接（供 Agent 工具调用）
    // ============================================================

    /**
     * 启动 DAppBrowserActivity 并打开指定 URL
     */
    public static void openUrl(Context ctx, String url) {
        try {
            Intent intent = new Intent(ctx, DAppBrowserActivity.class);
            intent.putExtra("url", url);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Exception e) {
            Logger.error(ctx, "DApp浏览器", "打开 URL 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 关闭 DApp 浏览器页面（AI 主动关闭）。
     * 关闭浏览器是安全操作，不受白名单校验限制，任何已打开的页面都能被关闭。
     * @param url 可选。指定后仅关闭匹配该 URL 的标签页；为空则关闭当前浏览器页面。
     * 若当前没有打开的 DApp 浏览器 Activity，则什么都不做。
     */
    public static String closePage(String url) {
        try {
            boolean hasTarget = url != null && !url.trim().isEmpty();
            if (hasTarget) {
                // 指定了 URL：先清理记录，再判断是否为当前页
                String target = url.trim();
                boolean removed = false;
                synchronized (sTabs) {
                    removed = sTabs.removeIf(t -> t.url.equals(target));
                }
                DAppBrowserActivity act = sInstance;
                if (act != null) {
                    String cur = null;
                    try {
                        WebView wv = act.webView;
                        if (wv != null && wv.getUrl() != null) cur = wv.getUrl();
                    } catch (Exception ignore) {}
                    if (cur != null && cur.equals(target)) {
                        act.runOnUiThread(act::finish);
                        return "{\"success\":true,\"message\":\"已关闭 DApp 标签页: " + target + "\"}";
                    }
                }
                if (removed) {
                    return "{\"success\":true,\"message\":\"已关闭标签页记录: " + target + "\"}";
                }
                return "{\"success\":false,\"message\":\"未找到匹配的标签页: " + target + "\"}";
            }

            // 未指定 URL：关闭当前浏览器
            DAppBrowserActivity act = sInstance;
            if (act == null) {
                return jsonError("当前没有打开的 DApp 浏览器页面");
            }
            String closingUrl = null;
            try {
                WebView wv = act.webView;
                if (wv != null && wv.getUrl() != null) closingUrl = wv.getUrl();
            } catch (Exception ignore) {}
            if (closingUrl != null) {
                final String cu = closingUrl;
                synchronized (sTabs) { sTabs.removeIf(t -> t.url.equals(cu)); }
            }
            act.runOnUiThread(act::finish);
            return "{\"success\":true,\"message\":\"已关闭 DApp 浏览器页面\"}";
        } catch (Exception e) {
            Logger.error(sAppContext, "DApp浏览器", "关闭页面失败: " + e.getMessage(), e);
            return jsonError("关闭 DApp 浏览器页面失败: " + e.getMessage());
        }
    }

    /**
     * 列出当前 DApp 浏览器中所有已记录的标签页（供 browser_list_tabs 工具调用）。
     */
    public static String listTabs() {
        try {
            JSONArray arr = new JSONArray();
            synchronized (sTabs) {
                for (TabInfo t : sTabs) {
                    JSONObject o = new JSONObject();
                    o.put("url", t.url);
                    o.put("title", t.title);
                    o.put("opened_at", t.openedAt);
                    o.put("is_current", t.isCurrent);
                    arr.put(o);
                }
            }
            JSONObject out = new JSONObject();
            out.put("success", true);
            out.put("count", arr.length());
            out.put("tabs", arr);
            return out.toString();
        } catch (Exception e) {
            Logger.error(sAppContext, "DApp浏览器", "列出标签页失败: " + e.getMessage(), e);
            return jsonError("列出标签页失败: " + e.getMessage());
        }
    }

    /**
     * 在当前 WebView 执行 JS 并同步等待结果（阻塞，带 10s 超时）
     */
    public static String evaluateJs(String script) {
        if (!checkOperationAllowed("evaluate")) {
            return jsonError("当前 DApp 未加入白名单，无法执行 JS。请先调用 request_dapp_whitelist 申请授权。");
        }
        return evaluateJsInternal(script);
    }

    /** 内部执行 JS，不做白名单校验（供只读 observation 使用） */
    private static String evaluateJsInternal(String script) {
        if (sWebView == null) {
            return jsonError("WebView 未激活，请先调用 browser_open_url 打开页面");
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] result = new String[1];
        sWebView.post(() -> {
            try {
                sWebView.evaluateJavascript(script, value -> {
                    result[0] = value;
                    latch.countDown();
                });
            } catch (Exception e) {
                result[0] = jsonError("执行 JS 异常: " + e.getMessage());
                latch.countDown();
            }
        });
        try {
            if (!latch.await(JS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return jsonError("执行 JS 超时");
            }
            return result[0] != null ? result[0] : jsonError("JS 返回为空");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return jsonError("等待中断");
        }
    }

    /** 静态方法内部检查当前 DApp 是否允许指定操作 */
    private static boolean checkOperationAllowed(String operation) {
        try {
            if (sWebView == null) return false;
            Context ctx = sWebView.getContext();
            if (!(ctx instanceof DAppBrowserActivity)) return false;
            DAppBrowserActivity act = (DAppBrowserActivity) ctx;
            return act.isCurrentOperationAllowed(operation);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取页面结构化状态：URL、标题、可交互元素、输入框、文本摘要
     */
    public static String getPageState() {
        String script =
            "(function(){" +
            "  var inputs = []; var buttons = []; var links = [];" +
            "  document.querySelectorAll('input, textarea').forEach(function(el){" +
            "    inputs.push({tag: el.tagName.toLowerCase(), type: el.type||'', placeholder: el.placeholder||'', id: el.id||'', class: el.className||'', name: el.name||''});" +
            "  });" +
            "  document.querySelectorAll('button, [role=button], a').forEach(function(el){" +
            "    var item = {tag: el.tagName.toLowerCase(), text: (el.innerText||'').trim().substring(0,80), id: el.id||'', class: el.className||''};" +
            "    if (el.tagName.toLowerCase()==='a') links.push(item); else buttons.push(item);" +
            "  });" +
            "  return JSON.stringify({" +
            "    url: location.href," +
            "    title: document.title," +
            "    readyState: document.readyState," +
            "    textPreview: (document.body ? document.body.innerText : '').substring(0,500)," +
            "    inputs: inputs.slice(0,20)," +
            "    buttons: buttons.slice(0,30)," +
            "    links: links.slice(0,20)" +
            "  });" +
            "})();";
        return parseJsonResult(evaluateJs(script));
    }

    /**
     * 点击指定 CSS 选择器的元素
     */
    public static String clickElement(String selector) {
        if (!checkOperationAllowed("click")) {
            return jsonError("当前 DApp 未加入白名单，无法自动点击。请先调用 request_dapp_whitelist 申请授权。");
        }
        String script =
            "(function(){" +
            "  var el = document.querySelector('" + escapeJsString(selector) + "');" +
            "  if (!el) return JSON.stringify({success:false, error:'元素未找到: " + escapeJsString(selector) + "'});" +
            "  el.click();" +
            "  return JSON.stringify({success:true, tag: el.tagName, text: (el.innerText||'').trim().substring(0,80)});" +
            "})();";
        return parseJsonResult(evaluateJs(script));
    }

    /**
     * 在指定输入框填入文本
     */
    public static String inputText(String selector, String text) {
        if (!checkOperationAllowed("input")) {
            return jsonError("当前 DApp 未加入白名单，无法自动输入。请先调用 request_dapp_whitelist 申请授权。");
        }
        String script =
            "(function(){" +
            "  var el = document.querySelector('" + escapeJsString(selector) + "');" +
            "  if (!el) return JSON.stringify({success:false, error:'元素未找到: " + escapeJsString(selector) + "'});" +
            "  el.focus(); el.value='" + escapeJsString(text) + "'; el.dispatchEvent(new Event('input',{bubbles:true})); el.dispatchEvent(new Event('change',{bubbles:true}));" +
            "  return JSON.stringify({success:true, tag: el.tagName, value: el.value});" +
            "})();";
        return parseJsonResult(evaluateJs(script));
    }

    /**
     * 获取指定选择器元素的文本
     */
    public static String getElementText(String selector) {
        String script =
            "(function(){" +
            "  var el = document.querySelector('" + escapeJsString(selector) + "');" +
            "  if (!el) return JSON.stringify({success:false, error:'元素未找到'});" +
            "  return JSON.stringify({success:true, text: (el.innerText||el.value||'').trim()});" +
            "})();";
        return parseJsonResult(evaluateJs(script));
    }

    private static String jsonError(String msg) {
        try {
            return new JSONObject().put("success", false).put("error", msg).toString();
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + msg.replace("\"", "\\\"") + "\"}";
        }
    }

    private static String parseJsonResult(String jsResult) {
        if (jsResult == null || jsResult.equals("null")) {
            return jsonError("页面未返回数据");
        }
        try {
            // evaluateJavascript 对字符串结果会包裹一层引号，需要解包
            if (jsResult.startsWith("\"") && jsResult.endsWith("\"")) {
                String unescaped = new org.json.JSONTokener(jsResult).nextValue().toString();
                // 验证是否为 JSON
                new JSONObject(unescaped);
                return unescaped;
            }
            new JSONObject(jsResult);
            return jsResult;
        } catch (Exception e) {
            return jsonError("解析 JS 结果失败: " + jsResult);
        }
    }

    private static String escapeJsString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    /**
     * 暴露给网页的 AI 桥接接口（未来可让网页主动调用原生能力）
     */
    private class AIBridge {
        @android.webkit.JavascriptInterface
        public String getVersion() {
            return "1.0";
        }

        @android.webkit.JavascriptInterface
        public void log(String message) {
            Logger.info(DAppBrowserActivity.this, "WebView", message);
        }
    }
}