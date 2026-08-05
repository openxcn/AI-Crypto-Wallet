package com.aicryptowallet.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AI 模型供应商管理器
 * - 管理多个供应商的 API Key（AES-256-GCM 加密存储）
 * - 支持用户选择当前使用的供应商
 * - 内置 20 家供应商预设（API 端点、模型列表）
 */
public class ModelProviderManager {

    private static final String PREFS = "model_providers";
    private static final String KEY_ACTIVE_PROVIDER = "active_provider_id";
    private static final String KEY_PROVIDER_IDS = "provider_ids";
    private static final String PREFIX_KEY = "key_";
    private static final String PREFIX_MODEL = "model_";
    private static final String PREFIX_NAME = "name_";
    private static final String PREFIX_URL = "url_";
    private static final String PREFIX_TYPE = "type_"; // openai / anthropic
    private static final String PREFIX_FORMAT = "format_"; // 接口格式: "openai" or "anthropic"

    // AES 密钥别名（实际用 SharedPreferences 存加密密钥，简化为固定种子）
    private static final String KEYSTORE_ALIAS = "model_provider_aes";
    private static final byte[] AES_SEED = "AiCryptoWallet2026ModelMgr!@#$".getBytes();

    /**
     * 供应商信息
     */
    public static class ProviderInfo {
        public String id;
        public String name;
        public String apiUrl;
        public String defaultModel;
        public String type; // "openai" or "anthropic"
        public boolean hasApiKey;
        public String apiKeyMasked; // sk-***xxxx
        public String selectedModel;
        public String selectedFormat; // "openai" or "anthropic"
    }

    /**
     * 内置 20 家供应商预设
     */
    public static final Map<String, ProviderInfo> BUILTIN_PROVIDERS = new LinkedHashMap<>();
    static {
        // 境外
        add("openai", "OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-4.1", "openai");
        add("anthropic", "Anthropic Claude", "https://api.anthropic.com/v1/messages", "claude-sonnet-4-6-20250617", "anthropic");
        add("google", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini-2.5-flash", "openai");
        add("groq", "Groq", "https://api.groq.com/openai/v1/chat/completions", "llama-4-maverick-17b-128e-instruct", "openai");
        add("mistral", "Mistral AI", "https://api.mistral.ai/v1/chat/completions", "mistral-large-latest", "openai");
        add("openrouter", "OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "openai/gpt-4.1", "openai");
        add("together", "Together AI", "https://api.together.xyz/v1/chat/completions", "meta-llama/Llama-4-Maverick-17B-128E-Instruct-FP8", "openai");
        add("huggingface", "Hugging Face", "https://api-inference.huggingface.co/v1/chat/completions", "Qwen/Qwen3-235B-A22B", "openai");
        add("cohere", "Cohere", "https://api.cohere.ai/v2/chat", "command-r-plus", "openai");
        add("replicate", "Replicate", "https://api.replicate.com/v1/models/meta/meta-llama-4-maverick/predictions", "meta-llama-4-maverick", "openai");

        // 自定义
        add("custom", "自定义 API", "", "", "openai");

        // 国内
        add("deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-v4-pro", "openai");
        add("zhipu", "智谱AI GLM", "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-4.7-flash", "openai");
        add("qwen", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen3.7-plus", "openai");
        add("baidu", "百度文心", "https://qianfan.baidubce.com/v2/chat/completions", "ernie-4.5", "openai");
        add("bytedance", "字节豆包", "https://ark.cn-beijing.volces.com/api/v3/chat/completions", "doubao-1.5-pro-32k", "openai");
        add("moonshot", "月之暗面 Kimi", "https://api.moonshot.cn/v1/chat/completions", "kimi-k2.5", "openai");
        add("xfyun", "讯飞星火", "https://spark-api-open.xf-yun.com/v1/chat/completions", "spark-4.0-ultra", "openai");
        add("baichuan", "百川智能", "https://api.baichuan-ai.com/v1/chat/completions", "Baichuan4", "openai");
        add("minimax", "MiniMax", "https://api.minimax.chat/v1/text/chatcompletion_v2", "MiniMax-M2.7", "openai");
        add("yi", "零一万物 Yi", "https://api.lingyiwanwu.com/v1/chat/completions", "yi-lightning", "openai");
    }

    private static void add(String id, String name, String apiUrl, String defaultModel, String type) {
        ProviderInfo info = new ProviderInfo();
        info.id = id;
        info.name = name;
        info.apiUrl = apiUrl;
        info.defaultModel = defaultModel;
        info.type = type;
        BUILTIN_PROVIDERS.put(id, info);
    }

    // ========== 各供应商模型列表 ==========

    private static final Map<String, String[]> MODEL_LISTS = new LinkedHashMap<>();
    static {
        MODEL_LISTS.put("openai", new String[]{
            "gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano",
            "o4", "o4-mini", "o3", "o3-mini",
            "gpt-4o", "gpt-4o-mini", "gpt-4-turbo"
        });
        MODEL_LISTS.put("anthropic", new String[]{
            "claude-opus-5-20250724", "claude-sonnet-4-6-20250617",
            "claude-opus-4-1-20250514", "claude-sonnet-4-5-20250514",
            "claude-haiku-4-5-20250514", "claude-opus-4-20250514",
            "claude-sonnet-4-20250514"
        });
        MODEL_LISTS.put("google", new String[]{
            "gemini-2.5-pro", "gemini-2.5-flash",
            "gemini-2.0-flash", "gemini-2.0-pro",
            "gemini-1.5-pro", "gemini-1.5-flash"
        });
        MODEL_LISTS.put("groq", new String[]{
            "llama-4-maverick-17b-128e-instruct", "llama-4-scout-17b-16e-instruct",
            "mixtral-8x7b-32768", "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant", "gemma2-9b-it"
        });
        MODEL_LISTS.put("mistral", new String[]{
            "mistral-large-latest", "mistral-small-latest",
            "mistral-medium", "codestral-latest",
            "open-mistral-nemo"
        });
        MODEL_LISTS.put("openrouter", new String[]{
            "openai/gpt-4.1", "openai/o4", "openai/o3",
            "anthropic/claude-sonnet-4-6", "anthropic/claude-opus-5",
            "google/gemini-2.5-pro", "google/gemini-2.5-flash",
            "deepseek/deepseek-v4-pro", "meta-llama/llama-4-maverick"
        });
        MODEL_LISTS.put("together", new String[]{
            "meta-llama/Llama-4-Maverick-17B-128E-Instruct-FP8",
            "meta-llama/Llama-4-Scout-17B-16E-Instruct",
            "deepseek-ai/DeepSeek-V4", "Qwen/Qwen3-235B-A22B"
        });
        MODEL_LISTS.put("huggingface", new String[]{
            "Qwen/Qwen3-235B-A22B", "meta-llama/Llama-4-Maverick-17B-128E-Instruct",
            "deepseek-ai/DeepSeek-V4", "mistralai/Mixtral-8x7B-Instruct-v0.1"
        });
        MODEL_LISTS.put("cohere", new String[]{
            "command-r-plus", "command-r", "command-a-03-2025",
            "command", "command-light"
        });
        MODEL_LISTS.put("replicate", new String[]{
            "meta/meta-llama-4-maverick", "meta/meta-llama-3.1-405b-instruct",
            "mistralai/mixtral-8x7b-instruct-v0.1"
        });
        MODEL_LISTS.put("deepseek", new String[]{
            "deepseek-v4-pro", "deepseek-v4-flash"
        });
        MODEL_LISTS.put("zhipu", new String[]{
            "glm-4.7-flash", "glm-5.2", "glm-4-flash",
            "glm-4-plus", "glm-4-air", "glm-4-long",
            "glm-4v-flash", "glm-4v-plus"
        });
        MODEL_LISTS.put("qwen", new String[]{
            "qwen3.7-max", "qwen3.7-plus", "qwen3.7-flash",
            "qwen-plus", "qwen-turbo", "qwen-max",
            "qwen3-235b-a22b"
        });
        MODEL_LISTS.put("baidu", new String[]{
            "ernie-4.5", "ernie-4.0-turbo-128k",
            "ernie-4.0-8k", "ernie-3.5-8k",
            "ernie-speed-128k", "ernie-lite-8k"
        });
        MODEL_LISTS.put("bytedance", new String[]{
            "doubao-1.5-pro-32k", "doubao-1.5-lite-32k",
            "doubao-1.5-vision-pro-32k", "doubao-1.5-pro-256k",
            "doubao-pro-32k", "doubao-lite-32k"
        });
        MODEL_LISTS.put("moonshot", new String[]{
            "kimi-k2.5", "kimi-k3",
            "moonshot-v1-128k", "moonshot-v1-32k",
            "moonshot-v1-8k"
        });
        MODEL_LISTS.put("xfyun", new String[]{
            "spark-4.0-ultra", "spark-max", "spark-pro",
            "spark-lite", "spark-3.5-max"
        });
        MODEL_LISTS.put("baichuan", new String[]{
            "Baichuan4", "Baichuan4-Air", "Baichuan4-Turbo",
            "Baichuan3-Turbo", "Baichuan2-Turbo"
        });
        MODEL_LISTS.put("minimax", new String[]{
            "MiniMax-M2.7", "abab7", "abab6.5s-chat",
            "abab6.5-chat", "abab5.5s-chat"
        });
        MODEL_LISTS.put("yi", new String[]{
            "yi-lightning", "yi-large", "yi-medium",
            "yi-large-turbo", "yi-vision"
        });
    }

    /** 各供应商的可选 API 地址（多于一个时以下拉形式展示） */
    private static final Map<String, String[]> URL_OPTIONS = new LinkedHashMap<>();
    static {
        URL_OPTIONS.put("deepseek", new String[]{
            "https://api.deepseek.com",
            "https://api.deepseek.com/anthropic"
        });
    }

    /** 获取供应商的 API 地址选项（多于一个时以下拉展示） */
    public static String[] getUrlOptions(String providerId) {
        return URL_OPTIONS.get(providerId);
    }

    /** 获取供应商的模型列表（用于下拉选择） */
    public static String[] getModelList(String providerId) {
        String[] list = MODEL_LISTS.get(providerId);
        if (list != null) return list;
        // 默认给一个包含预设模型的列表
        ProviderInfo preset = BUILTIN_PROVIDERS.get(providerId);
        if (preset != null) return new String[]{preset.defaultModel};
        return new String[]{"gpt-4o"};
    }

    /** 获取供应商支持的接口格式列表 */
    public static String[] getApiFormats(String providerId) {
        ProviderInfo preset = BUILTIN_PROVIDERS.get(providerId);
        if (preset == null) return new String[]{"openai"};
        if ("anthropic".equals(preset.type)) {
            return new String[]{"anthropic", "openai"};
        }
        return new String[]{"openai"};
    }

    // ========== 加密存储 ==========

    private static SecretKey getAesKey(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String keyStr = prefs.getString(KEYSTORE_ALIAS, "");
        if (!keyStr.isEmpty()) {
            byte[] keyBytes = Base64.decode(keyStr, Base64.NO_WRAP);
            return new SecretKeySpec(keyBytes, "AES");
        }
        // 首次生成密钥
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(256, new SecureRandom(AES_SEED));
            SecretKey key = kg.generateKey();
            prefs.edit().putString(KEYSTORE_ALIAS, Base64.encodeToString(key.getEncoded(), Base64.NO_WRAP)).apply();
            return key;
        } catch (Exception e) {
            return new SecretKeySpec(AES_SEED, "AES");
        }
    }

    private static String encrypt(String plain, Context ctx) {
        try {
            SecretKey key = getAesKey(ctx);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes("UTF-8"));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Exception e) {
            Logger.error(null, "ModelProvider", "加密 API Key 失败: " + e.getMessage(), e);
            return "";
        }
    }

    private static String decrypt(String encrypted, Context ctx) {
        try {
            SecretKey key = getAesKey(ctx);
            byte[] combined = Base64.decode(encrypted, Base64.NO_WRAP);
            byte[] iv = new byte[12];
            byte[] cipherText = new byte[combined.length - 12];
            System.arraycopy(combined, 0, iv, 0, 12);
            System.arraycopy(combined, 12, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(cipherText), "UTF-8");
        } catch (Exception e) {
            Logger.error(null, "ModelProvider", "解密 API Key 失败: " + e.getMessage(), e);
            return "";
        }
    }

    // ========== 公共 API ==========

    /** 保存供应商的 API Key */
    public static void saveApiKey(Context ctx, String providerId, String apiKey) {
        String encrypted = encrypt(apiKey, ctx);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(PREFIX_KEY + providerId, encrypted).apply();

        // 记录已配置的供应商ID
        addProviderId(ctx, providerId);
        Logger.info(null, "ModelProvider", "保存 API Key: " + providerId);
    }

    /** 获取供应商的 API Key（解密后） */
    public static String getApiKey(Context ctx, String providerId) {
        String encrypted = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREFIX_KEY + providerId, "");
        if (encrypted.isEmpty()) return "";
        return decrypt(encrypted, ctx);
    }

    /** 保存供应商的选定模型 */
    public static void saveModel(Context ctx, String providerId, String model) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(PREFIX_MODEL + providerId, model).apply();
    }

    /** 获取供应商的选定模型 */
    public static String getModel(Context ctx, String providerId) {
        String model = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREFIX_MODEL + providerId, "");
        if (model.isEmpty()) {
            ProviderInfo preset = BUILTIN_PROVIDERS.get(providerId);
            if (preset != null) return preset.defaultModel;
        }
        return model;
    }

    /** 获取供应商的 API URL */
    public static String getApiUrl(Context ctx, String providerId) {
        String url = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREFIX_URL + providerId, "");
        if (url.isEmpty()) {
            ProviderInfo preset = BUILTIN_PROVIDERS.get(providerId);
            if (preset != null) return preset.apiUrl;
        }
        return url;
    }

    /** 保存自定义 API URL */
    public static void saveApiUrl(Context ctx, String providerId, String url) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(PREFIX_URL + providerId, url).apply();
    }

    /** 获取供应商类型 */
    public static String getType(Context ctx, String providerId) {
        String type = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREFIX_TYPE + providerId, "");
        if (type.isEmpty()) {
            ProviderInfo preset = BUILTIN_PROVIDERS.get(providerId);
            if (preset != null) return preset.type;
        }
        return "openai";
    }

    /** 保存接口格式 */
    public static void saveFormat(Context ctx, String providerId, String format) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(PREFIX_FORMAT + providerId, format).apply();
    }

    /** 保存自定义名称（仅用于自定义API） */
    public static void saveName(Context ctx, String providerId, String name) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(PREFIX_NAME + providerId, name).apply();
    }

    /** 获取自定义名称 */
    public static String getName(Context ctx, String providerId) {
        String name = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREFIX_NAME + providerId, "");
        if (name.isEmpty()) {
            ProviderInfo preset = BUILTIN_PROVIDERS.get(providerId);
            if (preset != null) return preset.name;
        }
        return name;
    }

    /** 获取接口格式 */
    public static String getFormat(Context ctx, String providerId) {
        String format = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREFIX_FORMAT + providerId, "");
        if (format.isEmpty()) {
            ProviderInfo preset = BUILTIN_PROVIDERS.get(providerId);
            if (preset != null) return preset.type; // 默认用供应商原生格式
        }
        return format;
    }

    /** 设置当前使用的供应商 */
    public static void setActiveProvider(Context ctx, String providerId) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE_PROVIDER, providerId).apply();
        Logger.info(null, "ModelProvider", "激活供应商: " + providerId);
    }

    /** 获取当前使用的供应商ID */
    public static String getActiveProviderId(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_PROVIDER, "");
    }

    /** 获取当前激活供应商的 API Key */
    public static String getActiveApiKey(Context ctx) {
        String activeId = getActiveProviderId(ctx);
        if (activeId.isEmpty()) return "";
        return getApiKey(ctx, activeId);
    }

    /** 获取当前激活供应商的模型 */
    public static String getActiveModel(Context ctx) {
        String activeId = getActiveProviderId(ctx);
        if (activeId.isEmpty()) return "";
        return getModel(ctx, activeId);
    }

    /** 获取当前激活供应商的 API URL */
    public static String getActiveApiUrl(Context ctx) {
        String activeId = getActiveProviderId(ctx);
        if (activeId.isEmpty()) return "";
        return getApiUrl(ctx, activeId);
    }

    /** 获取当前激活供应商的类型 */
    public static String getActiveType(Context ctx) {
        String activeId = getActiveProviderId(ctx);
        if (activeId.isEmpty()) return "openai";
        return getType(ctx, activeId);
    }

    /** 删除供应商配置 */
    public static void deleteProvider(Context ctx, String providerId) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.remove(PREFIX_KEY + providerId);
        editor.remove(PREFIX_MODEL + providerId);
        editor.remove(PREFIX_URL + providerId);
        editor.remove(PREFIX_FORMAT + providerId);

        // 如果删除的是当前激活的，清除激活状态
        if (providerId.equals(getActiveProviderId(ctx))) {
            editor.remove(KEY_ACTIVE_PROVIDER);
        }

        // 从ID列表移除
        String ids = prefs.getString(KEY_PROVIDER_IDS, "");
        ids = ids.replace(providerId + ",", "").replace("," + providerId, "").replace(providerId, "");
        editor.putString(KEY_PROVIDER_IDS, ids);
        editor.apply();
        Logger.info(null, "ModelProvider", "删除供应商: " + providerId);
    }

    /** 获取所有已配置的供应商列表 */
    public static List<ProviderInfo> getConfiguredProviders(Context ctx) {
        List<ProviderInfo> result = new ArrayList<>();
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String ids = prefs.getString(KEY_PROVIDER_IDS, "");

        // 先添加自定义 API（始终排第一）
        ProviderInfo customPreset = BUILTIN_PROVIDERS.get("custom");
        if (customPreset != null) {
            ProviderInfo info = new ProviderInfo();
            info.id = "custom";
            info.name = getName(ctx, "custom");
            info.apiUrl = getApiUrl(ctx, "custom");
            info.defaultModel = customPreset.defaultModel;
            info.type = customPreset.type;
            info.hasApiKey = !getApiKey(ctx, "custom").isEmpty();
            if (info.hasApiKey) {
                info.apiKeyMasked = maskKey(getApiKey(ctx, "custom"));
            }
            info.selectedModel = getModel(ctx, "custom");
            info.selectedFormat = getFormat(ctx, "custom");
            result.add(info);
        }

        // 添加已配置的供应商
        if (!ids.isEmpty()) {
            for (String id : ids.split(",")) {
                id = id.trim();
                if (id.isEmpty() || "custom".equals(id)) continue;
                ProviderInfo preset = BUILTIN_PROVIDERS.get(id);
                if (preset == null) continue;
                ProviderInfo info = new ProviderInfo();
                info.id = id;
                info.name = getName(ctx, id);
                info.apiUrl = getApiUrl(ctx, id);
                info.defaultModel = preset.defaultModel;
                info.type = preset.type;
                info.hasApiKey = !getApiKey(ctx, id).isEmpty();
                if (info.hasApiKey) {
                    String key = getApiKey(ctx, id);
                    info.apiKeyMasked = maskKey(key);
                }
                info.selectedModel = getModel(ctx, id);
                info.selectedFormat = getFormat(ctx, id);
                result.add(info);
            }
        }

        // 再添加未配置的预设（自定义API始终在列表中，不重复添加）
        for (ProviderInfo preset : BUILTIN_PROVIDERS.values()) {
            if ("custom".equals(preset.id)) continue;
            boolean alreadyAdded = false;
            for (ProviderInfo existing : result) {
                if (existing.id.equals(preset.id)) {
                    alreadyAdded = true;
                    break;
                }
            }
            if (!alreadyAdded) {
                ProviderInfo info = new ProviderInfo();
                info.id = preset.id;
                info.name = getName(ctx, preset.id);
                info.apiUrl = preset.apiUrl;
                info.defaultModel = preset.defaultModel;
                info.type = preset.type;
                info.hasApiKey = false;
                info.selectedModel = preset.defaultModel;
                info.selectedFormat = preset.type;
                result.add(info);
            }
        }
        return result;
    }

    private static void addProviderId(Context ctx, String providerId) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String ids = prefs.getString(KEY_PROVIDER_IDS, "");
        if (!ids.contains(providerId)) {
            ids = ids.isEmpty() ? providerId : ids + "," + providerId;
            prefs.edit().putString(KEY_PROVIDER_IDS, ids).apply();
        }
    }

    private static String maskKey(String key) {
        if (key == null || key.length() <= 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}