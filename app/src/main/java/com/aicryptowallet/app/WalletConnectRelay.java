package com.aicryptowallet.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.bouncycastle.crypto.engines.ChaCha7539Engine;
import org.bouncycastle.crypto.macs.Poly1305;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * WalletConnect v2 中继客户端
 * 处理 WalletConnect 协议的 WebSocket 中继连接、加密通信和会话管理。
 * 支持 Transit 等使用 WalletConnect 协议的 DApp。
 */
public class WalletConnectRelay {

    private static final String TAG = "WalletConnectRelay";
    private static final String RELAY_URL = "wss://relay.walletconnect.org";
    // WalletConnect 项目 ID 默认值（无效占位符，需在 APP 设置页填入自己的projectId）
    // 免费注册：https://cloud.walletconnect.com （支持 Google 登录）
    private static final String DEFAULT_PROJECT_ID = "a151c68073e69a51f112e741b9a21ef2";
    public static final String PREF_NAME = "wc_config";
    public static final String PREF_KEY_PROJECT_ID = "project_id";

    /**
     * 获取当前生效的 WalletConnect Project ID
     * 优先读取用户在设置页配置的值，未配置则使用默认值
     */
    private String getProjectId() {
        try {
            String saved = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .getString(PREF_KEY_PROJECT_ID, null);
            if (saved != null && !saved.trim().isEmpty()) {
                return saved.trim();
            }
        } catch (Exception e) {
            Logger.error(appContext, TAG, "读取 projectId 失败: " + e.getMessage(), e);
        }
        return DEFAULT_PROJECT_ID;
    }

    /**
     * 设置用户自定义的 WalletConnect Project ID（供外部调用，持久化到 SharedPreferences）
     * @return true 表示保存成功
     */
    public boolean setCustomProjectId(String projectId) {
        try {
            appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_KEY_PROJECT_ID, projectId != null ? projectId.trim() : "")
                    .apply();
            Logger.info(appContext, TAG, "用户自定义 projectId 已保存: " + (projectId != null ? projectId.trim() : "(空)"));
            return true;
        } catch (Exception e) {
            Logger.error(appContext, TAG, "保存 projectId 失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 获取当前配置的 Project ID（供设置页回显使用）
     */
    public String getConfiguredProjectId() {
        return getProjectId();
    }

    private final OkHttpClient httpClient;
    private WebSocket webSocket;
    private final Handler mainHandler;
    private final WalletConnectCallback callback;
    private Context appContext;

    // 当前会话
    private String topic;
    private byte[] symKey;
    private String pairingTopic;
    private byte[] pairingSymKey;
    private String sessionTopic;
    private byte[] sessionSymKey;

    /** 获取配对 topic（供 DAppBrowserActivity 查询缓存消息） */
    public String getPairingTopic() {
        return pairingTopic;
    }

    // 订阅 ID 映射
    private final Map<String, String> subscriptions = new ConcurrentHashMap<>();
    private final AtomicLong requestId;

    // 等待响应的 latch
    private volatile CountDownLatch subscribeLatch;
    private volatile String subscribeResult;

    // 会话状态
    private boolean connected = false;
    private String peerMetaUrl;
    private String peerMetaName;
    private JSONObject sessionProposal;
    private boolean sessionApproved = false;

    // 缓存的会话请求（等待用户确认）
    private JSONObject pendingSessionProposal;

    /**
     * 回调接口
     */
    public interface WalletConnectCallback {
        /** 收到会话提案，需要用户确认连接 */
        void onSessionProposal(String dappName, String dappUrl, String dappIcon,
                               JSONArray requiredChains, JSONObject proposal);
        /** 收到会话请求（签名/交易等） */
        void onSessionRequest(String method, JSONArray params, long requestId);
        /** 中继连接成功 */
        void onRelayConnected();
        /** 连接断开 */
        void onDisconnected(String reason);
        /** 错误 */
        void onError(String error);
    }

    public WalletConnectRelay(Context ctx, WalletConnectCallback callback) {
        this.appContext = ctx.getApplicationContext();
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
        // WalletConnect 中继要求 request id 为较大的随机整数，避免从 1 开始被中继拒绝
        this.requestId = new AtomicLong(Math.abs(new SecureRandom().nextLong()));
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 解析 wc: URI 并开始连接
     */
    public void connect(String wcUri) {
        connect(wcUri, null);
    }

    /**
     * 解析 wc: URI 并连接，可选择复用已有的 WebSocket
     * @param wcUri WalletConnect URI
     * @param existingWs 已有的中继 WebSocket（来自 DApp 桥接），为 null 则创建新连接
     */
    public void connect(String wcUri, okhttp3.WebSocket existingWs) {
        Logger.info(appContext, TAG, "解析 WalletConnect URI: " + wcUri + " existingWs=" + (existingWs != null));
        try {
            if (!wcUri.startsWith("wc:")) {
                notifyError("无效的 WalletConnect URI");
                return;
            }

            // 格式: wc:topic@2?symKey=hex&relay-protocol=irn 或 wc:topic@2?expiryTimestamp=xxx (无symKey)
            String uriBody = wcUri.substring(3);
            int atIndex = uriBody.indexOf('@');
            if (atIndex < 0) {
                notifyError("URI 格式错误：缺少 @");
                return;
            }

            String handshakeTopic = uriBody.substring(0, atIndex);
            String queryPart = uriBody.substring(atIndex + 1);
            int queryStart = queryPart.indexOf('?');
            if (queryStart >= 0) {
                queryPart = queryPart.substring(queryStart + 1);
            }

            // 解析查询参数
            Map<String, String> params = parseQueryParams(queryPart);
            String symKeyHex = params.get("symKey");

            this.pairingTopic = handshakeTopic;
            this.topic = pairingTopic;

            Logger.info(appContext, TAG, "URI 完整查询参数: " + queryPart);
            Logger.info(appContext, TAG, "symKey 参数: " + (symKeyHex != null ? symKeyHex.substring(0, Math.min(16, symKeyHex.length())) + "...(len=" + symKeyHex.length() + ")" : "null"));

            if (symKeyHex != null && !symKeyHex.isEmpty()) {
                // 标准格式：DApp 提供 symKey
                this.pairingSymKey = hexToBytes(symKeyHex);
                this.symKey = pairingSymKey;
                Logger.info(appContext, TAG, "topic=" + pairingTopic + " symKeyLen=" + pairingSymKey.length + " (DApp提供symKey)");
            } else {
                // Pairing URI 无 symKey：本地生成随机 symKey，使用 URI 的 topic
                // DApp 会在同一 topic 上等待钱包响应
                byte[] randomKey = new byte[32];
                new SecureRandom().nextBytes(randomKey);
                this.pairingSymKey = randomKey;
                this.symKey = pairingSymKey;
                Logger.info(appContext, TAG, "topic=" + pairingTopic + " symKeyLen=32 (本地生成symKey，Pairing URI无symKey)");
            }

            if (existingWs != null) {
                // 复用 DApp 的桥接 WebSocket，不创建新连接
                Logger.info(appContext, TAG, "复用 DApp 桥接 WebSocket，直接订阅 topic=" + pairingTopic);
                this.webSocket = existingWs;
                connected = true;
                mainHandler.post(() -> {
                    if (callback != null) callback.onRelayConnected();
                });
                subscribe(pairingTopic);
            } else {
                // 创建新的中继连接
                connectRelay();
            }
        } catch (Exception e) {
            Logger.error(appContext, TAG, "解析 URI 失败: " + e.getMessage(), e);
            notifyError("解析 URI 失败: " + e.getMessage());
        }
    }

    /**
     * 连接 WalletConnect 中继服务器
     */
    private void connectRelay() {
        String projectId = getProjectId();
        String jwt = generateJwt(projectId);
        String relayUrl;
        if (jwt != null) {
            // 同时发送 auth (JWT) 和 projectId 参数，确保 relay 能识别 Project ID
            relayUrl = RELAY_URL + "?auth=" + jwt + "&projectId=" + projectId;
            Logger.info(appContext, TAG, "连接中继(带JWT+projectId): " + RELAY_URL +
                    "?auth=<JWT len=" + jwt.length() + ">&projectId=" + projectId);
        } else {
            relayUrl = RELAY_URL + "?projectId=" + projectId;
            Logger.warning(appContext, TAG, "JWT生成失败，回退到projectId方式: " + relayUrl);
        }

        Request request = new Request.Builder()
                .url(relayUrl)
                .header("Origin", "https://swap.transit.finance")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                Logger.info(appContext, TAG, "中继 WebSocket 已连接");
                mainHandler.post(() -> {
                    if (callback != null) callback.onRelayConnected();
                });
                // 订阅配对主题
                subscribe(pairingTopic);
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                handleRelayMessage(text);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                String respInfo = "";
                if (response != null) {
                    respInfo = " code=" + response.code() + " msg=" + response.message();
                    try {
                        String body = response.body() != null ? response.body().string() : "null";
                        respInfo += " body=" + body.substring(0, Math.min(500, body.length()));
                    } catch (Exception e) {
                        respInfo += " bodyReadError=" + e.getMessage();
                    }
                }
                Logger.error(appContext, TAG, "中继连接失败: " + t.getMessage() + respInfo, t);
                notifyError("中继连接失败: " + t.getMessage() + respInfo);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                Logger.info(appContext, TAG, "中继连接关闭: code=" + code + " reason=" + reason);
                connected = false;
                mainHandler.post(() -> {
                    if (callback != null) callback.onDisconnected(reason);
                });
            }
        });
    }

    /**
     * 生成 WalletConnect v2 JWT（EdDSA/Ed25519 签名）
     * 中继服务器要求所有连接都带 auth=JWT 参数
     */
    private String generateJwt(String projectId) {
        try {
            // 1. 生成 Ed25519 密钥对
            SecureRandom random = new SecureRandom();
            byte[] privKeyBytes = new byte[32];
            random.nextBytes(privKeyBytes);
            Ed25519PrivateKeyParameters privKeyParams = new Ed25519PrivateKeyParameters(privKeyBytes, 0);
            Ed25519PublicKeyParameters pubKeyParams = privKeyParams.generatePublicKey();
            byte[] pubKeyBytes = pubKeyParams.getEncoded();

            // 2. 编码公钥为 DID key（did:key:z + base58btc(multicodec + publicKey)）
            // Ed25519 multicodec prefix: 0xed01
            byte[] multicodecKey = new byte[2 + pubKeyBytes.length];
            multicodecKey[0] = (byte) 0xed;
            multicodecKey[1] = 0x01;
            System.arraycopy(pubKeyBytes, 0, multicodecKey, 2, pubKeyBytes.length);
            String didKey = "did:key:z" + base58btcEncode(multicodecKey);

            // 3. 构造 JWT header 和 payload
            long now = System.currentTimeMillis() / 1000;
            long exp = now + 86400; // 24小时有效期

            String header = "{\"alg\":\"EdDSA\",\"typ\":\"JWT\"}";
            String payload = "{\"iss\":\"" + didKey + "\",\"sub\":\"" + projectId +
                    "\",\"aud\":\"https://relay.walletconnect.org\",\"iat\":" + now +
                    ",\"exp\":" + exp + "}";

            // 4. Base64URL 编码
            String headerB64 = base64UrlEncode(header.getBytes("UTF-8"));
            String payloadB64 = base64UrlEncode(payload.getBytes("UTF-8"));
            String signingInput = headerB64 + "." + payloadB64;
            byte[] signingBytes = signingInput.getBytes("UTF-8");

            // 5. Ed25519 签名
            Ed25519Signer signer = new Ed25519Signer();
            signer.init(true, privKeyParams);
            signer.update(signingBytes, 0, signingBytes.length);
            byte[] signature = signer.generateSignature();

            String sigB64 = base64UrlEncode(signature);
            String jwt = signingInput + "." + sigB64;

            Logger.info(appContext, TAG, "JWT生成成功: didKey=" + didKey.substring(0, Math.min(30, didKey.length())) +
                    "... jwtLen=" + jwt.length());
            return jwt;
        } catch (Exception e) {
            Logger.error(appContext, TAG, "JWT生成失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Base64URL 编码（无填充）
     */
    private String base64UrlEncode(byte[] data) {
        return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static final String BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    /**
     * Base58btc 编码（用于 DID key）
     */
    private String base58btcEncode(byte[] input) {
        if (input.length == 0) return "";
        // 统计前导零
        int zeros = 0;
        while (zeros < input.length && input[zeros] == 0) zeros++;
        // 转 BigInteger
        byte[] copy = new byte[input.length + 1];
        System.arraycopy(input, 0, copy, 1, input.length);
        BigInteger num = new BigInteger(copy);
        // 编码
        StringBuilder sb = new StringBuilder();
        BigInteger fiftyEight = BigInteger.valueOf(58);
        while (num.compareTo(BigInteger.ZERO) > 0) {
            BigInteger[] divRem = num.divideAndRemainder(fiftyEight);
            num = divRem[0];
            sb.insert(0, BASE58_ALPHABET.charAt(divRem[1].intValue()));
        }
        // 前导零映射为 '1'
        for (int i = 0; i < zeros; i++) sb.insert(0, '1');
        return sb.toString();
    }

    /**
     * 订阅主题
     */
    private void subscribe(String topic) {
        long id = requestId.getAndIncrement();
        try {
            JSONObject msg = new JSONObject();
            msg.put("id", id);
            msg.put("jsonrpc", "2.0");
            msg.put("method", "irn_subscribe");
            JSONObject params = new JSONObject();
            params.put("topic", topic);
            msg.put("params", params);

            String msgStr = msg.toString();
            Logger.info(appContext, TAG, "发送订阅: " + msgStr);
            webSocket.send(msgStr);
        } catch (Exception e) {
            Logger.error(appContext, TAG, "订阅发送失败: " + e.getMessage(), e);
        }
    }

    /**
     * 发布消息到主题
     */
    private void publish(String topic, String encryptedMessage, int tag, int ttl) {
        long id = requestId.getAndIncrement();
        try {
            JSONObject msg = new JSONObject();
            msg.put("id", id);
            msg.put("jsonrpc", "2.0");
            msg.put("method", "irn_publish");
            JSONObject params = new JSONObject();
            params.put("topic", topic);
            params.put("message", encryptedMessage);
            params.put("ttl", ttl);
            params.put("tag", tag);
            msg.put("params", params);

            String msgStr = msg.toString();
            Logger.info(appContext, TAG, "发送发布: topic=" + topic + " tag=" + tag);
            webSocket.send(msgStr);
        } catch (Exception e) {
            Logger.error(appContext, TAG, "发布发送失败: " + e.getMessage(), e);
        }
    }

    /**
     * 处理中继消息（public 以便桥接 WebSocket 分发消息）
     */
    public void handleRelayMessage(String text) {
        try {
            JSONObject msg = new JSONObject(text);
            String method = msg.optString("method", "");
            String id = msg.optString("id", "");

            // 处理错误响应（中继返回的 JSON-RPC 错误）
            if (msg.has("error")) {
                JSONObject error = msg.optJSONObject("error");
                String errMsg = error != null ? error.optString("message", "未知错误") : "未知错误";
                int errCode = error != null ? error.optInt("code", -1) : -1;
                Logger.error(appContext, TAG, "中继返回错误: id=" + id + " code=" + errCode + " msg=" + errMsg, null);
                // 如果是订阅错误，标记连接失败
                if (errCode != 0) {
                    notifyError("中继错误: " + errMsg + " (code=" + errCode + ")");
                }
                return;
            }

            if ("irn_subscription".equals(method) || (!id.isEmpty() && msg.has("result"))) {
                // 订阅确认 - result 可能是字符串、数组或对象
                JSONObject params = msg.optJSONObject("params");
                String subId = null;
                if (params != null) {
                    subId = params.optString("id", "");
                }
                if (subId == null || subId.isEmpty()) {
                    Object result = msg.opt("result");
                    if (result instanceof String) {
                        subId = (String) result;
                    } else if (result instanceof org.json.JSONArray) {
                        // result 是数组格式 ["subscription_id"]
                        org.json.JSONArray arr = (org.json.JSONArray) result;
                        if (arr.length() > 0) {
                            subId = arr.optString(0, "");
                        }
                    }
                }
                Logger.info(appContext, TAG, "订阅成功: " + subId);
                if (topic != null && subId != null && !subId.isEmpty()) {
                    subscriptions.put(topic, subId);
                }
                connected = true;
                return;
            }

            if ("irn_message".equals(method) || "irn_publish".equals(method)) {
                JSONObject params = msg.optJSONObject("params");
                if (params == null) return;
                String msgTopic = params.optString("topic", "");
                String encryptedMsg = params.optString("message", "");

                Logger.info(appContext, TAG, "收到加密消息: topic=" + msgTopic);

                // 解密消息
                byte[] decrypted = decryptMessage(encryptedMsg, msgTopic);
                if (decrypted == null) {
                    Logger.error(appContext, TAG, "消息解密失败", null);
                    return;
                }

                String decryptedStr = new String(decrypted, "UTF-8");
                Logger.info(appContext, TAG, "解密消息: " + decryptedStr);

                JSONObject payload = new JSONObject(decryptedStr);
                String payloadMethod = payload.optString("method", "");
                JSONObject payloadParams = payload.optJSONObject("params");

                if ("wc_sessionProposal".equals(payloadMethod) || "wc_sessionPropose".equals(payloadMethod)) {
                    // 会话提案
                    handleSessionProposal(payload, msgTopic);
                } else if ("wc_sessionRequest".equals(payloadMethod)) {
                    // 会话请求
                    handleSessionRequest(payload, msgTopic);
                } else if ("wc_sessionDelete".equals(payloadMethod)) {
                    // 会话删除
                    Logger.info(appContext, TAG, "DApp 请求删除会话");
                    cleanup();
                }
            }
        } catch (Exception e) {
            Logger.error(appContext, TAG, "处理中继消息异常: " + e.getMessage(), e);
        }
    }

    /**
     * 处理会话提案
     */
    private void handleSessionProposal(JSONObject payload, String msgTopic) {
        try {
            // 配对会话转变为正式会话
            this.sessionTopic = msgTopic;
            this.sessionSymKey = pairingSymKey;

            JSONObject params = payload.optJSONObject("params");
            this.sessionProposal = payload;
            this.pendingSessionProposal = payload;

            // 提取 DApp 信息
            JSONObject proposer = params != null ? params.optJSONObject("proposer") : null;
            JSONObject peerMeta = proposer != null ? proposer.optJSONObject("metadata") : null;
            if (peerMeta != null) {
                peerMetaName = peerMeta.optString("name", "未知DApp");
                peerMetaUrl = peerMeta.optString("url", "");
            } else {
                peerMetaName = "未知DApp";
                peerMetaUrl = "";
            }

            JSONArray requiredNamespaces = params != null ? params.optJSONArray("requiredNamespaces") : null;
            JSONArray requiredChains = new JSONArray();
            if (requiredNamespaces != null) {
                for (int i = 0; i < requiredNamespaces.length(); i++) {
                    JSONObject ns = requiredNamespaces.optJSONObject(i);
                    if (ns != null) {
                        JSONArray chains = ns.optJSONArray("chains");
                        if (chains != null) {
                            for (int j = 0; j < chains.length(); j++) {
                                requiredChains.put(chains.optString(j));
                            }
                        }
                    }
                }
            }

            Logger.info(appContext, TAG, "会话提案: name=" + peerMetaName + " url=" + peerMetaUrl + " chains=" + requiredChains);

            final String dappName = peerMetaName;
            final String dappUrl = peerMetaUrl;
            final JSONArray chains = requiredChains;
            final JSONObject proposal = payload;

            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onSessionProposal(dappName, dappUrl, "", chains, proposal);
                }
            });
        } catch (Exception e) {
            Logger.error(appContext, TAG, "处理会话提案异常: " + e.getMessage(), e);
        }
    }

    /**
     * 处理会话请求
     */
    private void handleSessionRequest(JSONObject payload, String msgTopic) {
        try {
            JSONObject params = payload.optJSONObject("params");
            if (params == null) return;

            JSONObject request = params.optJSONObject("request");
            if (request == null) return;

            String method = request.optString("method", "");
            JSONArray requestParams = request.optJSONArray("params");
            long reqId = payload.optLong("id", 0);

            Logger.info(appContext, TAG, "会话请求: method=" + method + " id=" + reqId);

            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onSessionRequest(method, requestParams != null ? requestParams : new JSONArray(), reqId);
                }
            });
        } catch (Exception e) {
            Logger.error(appContext, TAG, "处理会话请求异常: " + e.getMessage(), e);
        }
    }

    /**
     * 批准会话（用户确认连接后调用）
     */
    public void approveSession(String address, String chainId) {
        if (sessionTopic == null || sessionSymKey == null) {
            notifyError("没有待处理的会话提案");
            return;
        }

        try {
            // 构建会话批准响应
            JSONObject response = new JSONObject();
            response.put("id", sessionProposal != null ? sessionProposal.optLong("id", 1) : 1);
            response.put("jsonrpc", "2.0");
            response.put("result", buildSessionApproval(address, chainId));

            String responseStr = response.toString();
            Logger.info(appContext, TAG, "会话批准响应: " + responseStr);

            byte[] encrypted = encryptMessage(responseStr.getBytes("UTF-8"), sessionSymKey);
            if (encrypted != null) {
                String encryptedBase64 = Base64.encodeToString(encrypted, Base64.NO_WRAP);
                publish(sessionTopic, encryptedBase64, 0, 86400);
                sessionApproved = true;
                Logger.info(appContext, TAG, "会话已批准，发布到 topic=" + sessionTopic);
            }
        } catch (Exception e) {
            Logger.error(appContext, TAG, "批准会话失败: " + e.getMessage(), e);
            notifyError("批准会话失败: " + e.getMessage());
        }
    }

    /**
     * 拒绝会话
     */
    public void rejectSession() {
        if (sessionTopic == null || sessionSymKey == null) return;

        try {
            JSONObject response = new JSONObject();
            response.put("id", sessionProposal != null ? sessionProposal.optLong("id", 1) : 1);
            response.put("jsonrpc", "2.0");
            JSONObject error = new JSONObject();
            error.put("code", 5001);
            error.put("message", "用户拒绝连接");
            response.put("error", error);

            String responseStr = response.toString();
            byte[] encrypted = encryptMessage(responseStr.getBytes("UTF-8"), sessionSymKey);
            if (encrypted != null) {
                String encryptedBase64 = Base64.encodeToString(encrypted, Base64.NO_WRAP);
                publish(sessionTopic, encryptedBase64, 0, 86400);
            }
        } catch (Exception e) {
            Logger.error(appContext, TAG, "拒绝会话失败: " + e.getMessage(), e);
        } finally {
            cleanup();
        }
    }

    /**
     * 响应会话请求
     */
    public void respondSessionRequest(long requestId, String resultJson) {
        if (sessionTopic == null || sessionSymKey == null) {
            notifyError("没有活动的会话");
            return;
        }

        try {
            JSONObject response = new JSONObject();
            response.put("id", requestId);
            response.put("jsonrpc", "2.0");
            response.put("result", new JSONObject(resultJson));

            String responseStr = response.toString();
            byte[] encrypted = encryptMessage(responseStr.getBytes("UTF-8"), sessionSymKey);
            if (encrypted != null) {
                String encryptedBase64 = Base64.encodeToString(encrypted, Base64.NO_WRAP);
                publish(sessionTopic, encryptedBase64, 0, 86400);
            }
        } catch (Exception e) {
            Logger.error(appContext, TAG, "响应会话请求失败: " + e.getMessage(), e);
        }
    }

    /**
     * 响应会话请求错误
     */
    public void respondSessionRequestError(long requestId, int errorCode, String errorMessage) {
        if (sessionTopic == null || sessionSymKey == null) return;

        try {
            JSONObject response = new JSONObject();
            response.put("id", requestId);
            response.put("jsonrpc", "2.0");
            JSONObject error = new JSONObject();
            error.put("code", errorCode);
            error.put("message", errorMessage);
            response.put("error", error);

            String responseStr = response.toString();
            byte[] encrypted = encryptMessage(responseStr.getBytes("UTF-8"), sessionSymKey);
            if (encrypted != null) {
                String encryptedBase64 = Base64.encodeToString(encrypted, Base64.NO_WRAP);
                publish(sessionTopic, encryptedBase64, 0, 86400);
            }
        } catch (Exception e) {
            Logger.error(appContext, TAG, "响应错误失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构建会话批准的 namespace
     */
    private JSONObject buildSessionApproval(String address, String chainId) {
        try {
            JSONObject result = new JSONObject();

            // session 属性
            JSONObject session = new JSONObject();
            session.put("relay", new JSONObject().put("protocol", "irn"));
            session.put("controller", address);
            JSONObject selfMeta = new JSONObject();
            selfMeta.put("name", "AI Crypto Wallet");
            selfMeta.put("description", "AI Crypto Wallet - Android");
            selfMeta.put("url", "https://aicryptowallet.app");
            JSONArray icons = new JSONArray();
            icons.put("https://aicryptowallet.app/icon.png");
            selfMeta.put("icons", icons);
            session.put("self", selfMeta);
            session.put("peer", sessionProposal != null
                    ? sessionProposal.optJSONObject("params").optJSONObject("proposer")
                    : new JSONObject());
            if (sessionProposal != null) {
                session.put("expiry", sessionProposal.optJSONObject("params").optLong("expiry", 0));
            }
            result.put("session", session);

            // 批准的 namespace
            JSONObject namespaces = new JSONObject();
            JSONObject eip155 = new JSONObject();
            JSONArray chains = new JSONArray();
            chains.put("eip155:" + chainIdToDecimal(chainId));
            eip155.put("chains", chains);
            JSONArray methods = new JSONArray();
            methods.put("eth_sendTransaction");
            methods.put("eth_sign");
            methods.put("personal_sign");
            methods.put("eth_signTypedData");
            methods.put("eth_signTypedData_v4");
            methods.put("eth_signTransaction");
            methods.put("wallet_switchEthereumChain");
            methods.put("wallet_addEthereumChain");
            eip155.put("methods", methods);
            JSONArray events = new JSONArray();
            events.put("accountsChanged");
            events.put("chainChanged");
            events.put("connect");
            events.put("disconnect");
            eip155.put("events", events);
            JSONArray accounts = new JSONArray();
            accounts.put("eip155:" + chainIdToDecimal(chainId) + ":" + address);
            eip155.put("accounts", accounts);
            namespaces.put("eip155", eip155);
            result.put("namespaces", namespaces);

            return result;
        } catch (Exception e) {
            Logger.error(appContext, TAG, "构建会话批准失败: " + e.getMessage(), e);
            return new JSONObject();
        }
    }

    /**
     * 链 ID 转为十进制
     */
    private int chainIdToDecimal(String chainId) {
        if (chainId == null) return 1;
        if (chainId.startsWith("0x")) {
            return Integer.parseInt(chainId.substring(2), 16);
        }
        try {
            return Integer.parseInt(chainId);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * 加密消息（XChaCha20-Poly1305）
     * 输出格式: 24字节 nonce + ciphertext + 16字节 tag
     */
    private byte[] encryptMessage(byte[] plaintext, byte[] key) {
        try {
            // 生成 24 字节 nonce
            byte[] nonce = new byte[24];
            new SecureRandom().nextBytes(nonce);

            // 使用 XChaCha20-Poly1305 加密
            byte[] ciphertext = xChaCha20Poly1305Encrypt(plaintext, key, nonce);

            // 组合: nonce + ciphertext + tag
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bos.write(nonce);
            bos.write(ciphertext);
            return bos.toByteArray();
        } catch (Exception e) {
            Logger.error(appContext, TAG, "加密失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 解密消息（XChaCha20-Poly1305）
     * 输入格式: 24字节 nonce + ciphertext + 16字节 tag
     */
    private byte[] decryptMessage(String encryptedBase64, String topic) {
        try {
            byte[] data = Base64.decode(encryptedBase64, Base64.DEFAULT);
            if (data.length < 40) {
                Logger.error(appContext, TAG, "加密数据太短: " + data.length, null);
                return null;
            }

            // 提取 nonce (前24字节)
            byte[] nonce = Arrays.copyOfRange(data, 0, 24);
            // 剩余是 ciphertext + tag
            byte[] ciphertext = Arrays.copyOfRange(data, 24, data.length);

            // 选择正确的密钥
            byte[] key = topic.equals(sessionTopic) ? sessionSymKey
                    : topic.equals(pairingTopic) ? pairingSymKey
                    : symKey;

            if (key == null) {
                Logger.error(appContext, TAG, "没有对应的密钥: topic=" + topic, null);
                return null;
            }

            return xChaCha20Poly1305Decrypt(ciphertext, key, nonce);
        } catch (Exception e) {
            Logger.error(appContext, TAG, "解密失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * XChaCha20-Poly1305 加密
     */
    private byte[] xChaCha20Poly1305Encrypt(byte[] plaintext, byte[] key, byte[] nonce24) throws Exception {
        // HChaCha20: 从 24字节 nonce 导出子密钥和 12字节 nonce
        byte[] subKey = new byte[32];
        byte[] subNonce = new byte[12];
        hChaCha20(key, nonce24, subKey, subNonce);

        // ChaCha20 加密
        ChaCha7539Engine chacha = new ChaCha7539Engine();
        chacha.init(true, new ParametersWithIV(new KeyParameter(subKey), subNonce));

        byte[] ciphertext = new byte[plaintext.length];
        chacha.processBytes(plaintext, 0, plaintext.length, ciphertext, 0);

        // Poly1305 MAC
        byte[] aad = new byte[0]; // 无附加认证数据
        byte[] tag = poly1305Mac(subKey, subNonce, ciphertext, aad);

        // 组合: ciphertext + tag
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(ciphertext);
        bos.write(tag);
        return bos.toByteArray();
    }

    /**
     * XChaCha20-Poly1305 解密
     */
    private byte[] xChaCha20Poly1305Decrypt(byte[] ciphertextWithTag, byte[] key, byte[] nonce24) throws Exception {
        if (ciphertextWithTag.length < 16) return null;

        int ciphertextLen = ciphertextWithTag.length - 16;
        byte[] ciphertext = Arrays.copyOfRange(ciphertextWithTag, 0, ciphertextLen);
        byte[] tag = Arrays.copyOfRange(ciphertextWithTag, ciphertextLen, ciphertextWithTag.length);

        // HChaCha20
        byte[] subKey = new byte[32];
        byte[] subNonce = new byte[12];
        hChaCha20(key, nonce24, subKey, subNonce);

        // 验证 Poly1305 MAC
        byte[] aad = new byte[0];
        byte[] expectedTag = poly1305Mac(subKey, subNonce, ciphertext, aad);
        if (!MessageDigest.isEqual(tag, expectedTag)) {
            Logger.error(appContext, TAG, "Poly1305 MAC 验证失败", null);
            return null;
        }

        // ChaCha20 解密
        ChaCha7539Engine chacha = new ChaCha7539Engine();
        chacha.init(false, new ParametersWithIV(new KeyParameter(subKey), subNonce));

        byte[] plaintext = new byte[ciphertext.length];
        chacha.processBytes(ciphertext, 0, ciphertext.length, plaintext, 0);

        return plaintext;
    }

    /**
     * HChaCha20: 从 24字节 nonce 导出子密钥和 12字节 nonce
     */
    private void hChaCha20(byte[] key, byte[] nonce24, byte[] outSubKey, byte[] outSubNonce) throws Exception {
        // 设置 ChaCha20 初始状态
        int[] state = new int[16];
        // 常量 "expand 32-byte k"
        state[0] = 0x61707865;
        state[1] = 0x3320646e;
        state[2] = 0x79622d32;
        state[3] = 0x6b206574;
        // 密钥 (8 words)
        for (int i = 0; i < 8; i++) {
            state[4 + i] = ByteBuffer.wrap(key, i * 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        }
        // Nonce 前16字节作为 counter (4 words) + nonce前4字节
        state[12] = ByteBuffer.wrap(nonce24, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        state[13] = ByteBuffer.wrap(nonce24, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        state[14] = ByteBuffer.wrap(nonce24, 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        state[15] = ByteBuffer.wrap(nonce24, 12, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();

        // 执行 ChaCha20 块操作
        int[] workingState = Arrays.copyOf(state, 16);
        chachaBlock(workingState);

        // 输出: 前32字节是子密钥，接下来32字节中的前12字节是子nonce
        for (int i = 0; i < 8; i++) {
            int val = workingState[i];
            outSubKey[i * 4] = (byte) (val & 0xFF);
            outSubKey[i * 4 + 1] = (byte) ((val >> 8) & 0xFF);
            outSubKey[i * 4 + 2] = (byte) ((val >> 16) & 0xFF);
            outSubKey[i * 4 + 3] = (byte) ((val >> 24) & 0xFF);
        }
        // 接下来 4 words (indices 12-15) 作为 nonce 基础
        for (int i = 0; i < 3; i++) {
            int val = workingState[12 + i];
            outSubNonce[i * 4] = (byte) (val & 0xFF);
            outSubNonce[i * 4 + 1] = (byte) ((val >> 8) & 0xFF);
            outSubNonce[i * 4 + 2] = (byte) ((val >> 16) & 0xFF);
            outSubNonce[i * 4 + 3] = (byte) ((val >> 24) & 0xFF);
        }
    }

    /**
     * ChaCha20 四分之一轮
     */
    private void chachaBlock(int[] state) {
        for (int i = 0; i < 10; i++) {
            // 列轮
            quarterRound(state, 0, 4, 8, 12);
            quarterRound(state, 1, 5, 9, 13);
            quarterRound(state, 2, 6, 10, 14);
            quarterRound(state, 3, 7, 11, 15);
            // 对角线轮
            quarterRound(state, 0, 5, 10, 15);
            quarterRound(state, 1, 6, 11, 12);
            quarterRound(state, 2, 7, 8, 13);
            quarterRound(state, 3, 4, 9, 14);
        }
    }

    private void quarterRound(int[] state, int a, int b, int c, int d) {
        state[a] += state[b]; state[d] = Integer.rotateLeft(state[d] ^ state[a], 16);
        state[c] += state[d]; state[b] = Integer.rotateLeft(state[b] ^ state[c], 12);
        state[a] += state[b]; state[d] = Integer.rotateLeft(state[d] ^ state[a], 8);
        state[c] += state[d]; state[b] = Integer.rotateLeft(state[b] ^ state[c], 7);
    }

    /**
     * Poly1305 MAC 计算
     */
    private byte[] poly1305Mac(byte[] key, byte[] nonce12, byte[] ciphertext, byte[] aad) throws Exception {
        // 生成 Poly1305 密钥（ChaCha20 加密的前32字节）
        byte[] otk = new byte[64];
        byte[] zeroBlock = new byte[64];
        ChaCha7539Engine chacha = new ChaCha7539Engine();
        chacha.init(true, new ParametersWithIV(new KeyParameter(key), nonce12));
        chacha.processBytes(zeroBlock, 0, 64, otk, 0);

        byte[] polyKey = Arrays.copyOfRange(otk, 0, 32);

        Poly1305 poly = new Poly1305();
        poly.init(new KeyParameter(polyKey));

        // 添加 AAD
        if (aad != null && aad.length > 0) {
            poly.update(aad, 0, aad.length);
            // 填充到 16 字节边界
            int padLen = (16 - (aad.length % 16)) % 16;
            if (padLen > 0) {
                poly.update(new byte[padLen], 0, padLen);
            }
        }

        // 添加密文
        poly.update(ciphertext, 0, ciphertext.length);
        int padLen = (16 - (ciphertext.length % 16)) % 16;
        if (padLen > 0) {
            poly.update(new byte[padLen], 0, padLen);
        }

        // 添加长度
        byte[] lenBlock = new byte[16];
        ByteBuffer.wrap(lenBlock).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(aad != null ? aad.length : 0)
                .putLong(ciphertext.length);
        poly.update(lenBlock, 0, 16);

        byte[] tag = new byte[16];
        poly.doFinal(tag, 0);

        // XOR with first 16 bytes of otk
        for (int i = 0; i < 16; i++) {
            tag[i] ^= otk[32 + i];
        }

        return tag;
    }

    /**
     * 清理资源
     */
    public void cleanup() {
        connected = false;
        sessionApproved = false;
        sessionTopic = null;
        sessionSymKey = null;
        pendingSessionProposal = null;
        sessionProposal = null;
        subscriptions.clear();
        if (webSocket != null) {
            try {
                webSocket.close(1000, "用户断开");
            } catch (Exception ignored) {}
            webSocket = null;
        }
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        cleanup();
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean isSessionApproved() {
        return sessionApproved;
    }

    // ========== 工具方法 ==========

    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new ConcurrentHashMap<>();
        if (query == null || query.isEmpty()) return params;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                params.put(kv[0], kv[1]);
            }
        }
        return params;
    }

    private byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private void notifyError(String error) {
        Logger.error(appContext, TAG, error, null);
        mainHandler.post(() -> {
            if (callback != null) callback.onError(error);
        });
    }
}