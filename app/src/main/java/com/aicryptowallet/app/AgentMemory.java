package com.aicryptowallet.app;

import android.content.Context;
import android.util.Log;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * AI 智能体记忆管理
 * 存储在外部存储目录的 JSON 文件，AI 可通过对话修改自身配置
 * 敏感信息（钱包地址、API Key）写入时自动脱敏
 */
public class AgentMemory {

    private static final String TAG = "AgentMemory";
    private static final String MEMORY_FILE = "agent_memory.json";

    // 记忆字段
    private String aiName;
    private String ownerName;
    private String personality;
    private int checkIntervalMinutes = 5;
    private double maxDailyLoss = 50.0;
    private int newsReportIntervalHours = 24;
    private String tradingChain = "BNB";
    private String apiKeyMasked = "";            // API Key（脱敏存储）
    private long createdAt = 0;
    private long updatedAt = 0;
    private String customNotes = "";

    private final Context ctx;
    private final File memoryFile;

    public AgentMemory(Context ctx) {
        this.ctx = ctx;
        aiName = ctx.getString(R.string.str_ai_default_name);
        ownerName = ctx.getString(R.string.str_ai_default_owner);
        personality = ctx.getString(R.string.str_ai_default_personality);
        File dir = getMemoryDir(ctx);
        if (!dir.exists()) dir.mkdirs();
        this.memoryFile = new File(dir, MEMORY_FILE);
        load();
    }

    /** 获取记忆文件存储目录（外部存储） */
    public static File getMemoryDir(Context ctx) {
        File extDir = ctx.getExternalFilesDir(null);
        if (extDir == null) extDir = ctx.getFilesDir();
        File dir = new File(extDir, "agent_workspace");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 从 JSON 文件加载记忆 */
    private void load() {
        try {
            if (memoryFile.exists() && memoryFile.length() > 0) {
                byte[] data = new byte[(int) memoryFile.length()];
                FileInputStream fis = new FileInputStream(memoryFile);
                fis.read(data);
                fis.close();
                String json = new String(data, StandardCharsets.UTF_8);
                JSONObject obj = new JSONObject(json);
                aiName = obj.optString("aiName", aiName);
                ownerName = obj.optString("ownerName", ownerName);
                personality = obj.optString("personality", personality);
                checkIntervalMinutes = obj.optInt("checkIntervalMinutes", checkIntervalMinutes);
                maxDailyLoss = obj.optDouble("maxDailyLoss", maxDailyLoss);
                newsReportIntervalHours = obj.optInt("newsReportIntervalHours", newsReportIntervalHours);
                tradingChain = obj.optString("tradingChain", tradingChain);
                apiKeyMasked = obj.optString("apiKeyMasked", "");
                createdAt = obj.optLong("createdAt", System.currentTimeMillis());
                updatedAt = obj.optLong("updatedAt", createdAt);
                customNotes = obj.optString("customNotes", "");
            }
        } catch (Exception e) {
            Log.w(TAG, "加载记忆失败: " + e.getMessage());
        }
    }

    /** 保存记忆到 JSON 文件 */
    public void save() {
        try {
            updatedAt = System.currentTimeMillis();
            if (createdAt == 0) createdAt = updatedAt;

            // 自动脱敏：API Key 脱敏存储，钱包地址不存储（实时从 WalletManager 获取）
            try {
                String key = AIAnalyzer.getApiKeyStatic(ctx);
                apiKeyMasked = maskApiKey(key);
            } catch (Exception ignored) {}

            JSONObject obj = new JSONObject();
            obj.put("aiName", aiName);
            obj.put("ownerName", ownerName);
            obj.put("personality", personality);
            obj.put("checkIntervalMinutes", checkIntervalMinutes);
            obj.put("maxDailyLoss", maxDailyLoss);
            obj.put("newsReportIntervalHours", newsReportIntervalHours);
            obj.put("tradingChain", tradingChain);
            obj.put("apiKeyMasked", apiKeyMasked);
            obj.put("createdAt", createdAt);
            obj.put("updatedAt", updatedAt);
            obj.put("customNotes", customNotes);

            FileOutputStream fos = new FileOutputStream(memoryFile);
            fos.write(obj.toString(2).getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "保存记忆失败: " + e.getMessage());
        }
    }

    /** 脱敏 API Key：sk-****abcd */
    public static String maskApiKey(String key) {
        if (key == null || key.length() < 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    /** 生效的性格：优先用户选择的语气预设模板，否则沿用记忆里自由文本 */
    private String effectivePersonality() {
        String presetText = AIAgentSettings.getPresetPersonalityText(ctx);
        if (presetText != null && !presetText.isEmpty()) {
            return presetText;
        }
        return personality;
    }

    /** 生成自述文本，注入到 LLM 系统提示词中 */
    public String toSystemPrompt() {
        // 动态获取当前钱包地址（用户可能刚导入新钱包）
        String currentAddress = "";
        try {
            currentAddress = WalletManager.getWalletAddress(ctx);
        } catch (Exception ignored) {}

        String addressLine = (currentAddress != null && !currentAddress.isEmpty())
            ? "- " + ctx.getString(R.string.str_owner_current_wallet_address) + ": " + currentAddress + "\n" : "";
        String apiLine = (apiKeyMasked != null && !apiKeyMasked.isEmpty())
            ? "- AI API: " + apiKeyMasked + "\n" : "";
        String notesLine = (customNotes != null && !customNotes.isEmpty())
            ? "- " + ctx.getString(R.string.str_remarks) + ": " + customNotes + "\n" : "";
        String currency = CurrencyManager.getSelectedCurrency(ctx);
        String symbol = CurrencyManager.getCurrencySymbol(currency);

        return ctx.getString(R.string.str_ai_system_prompt,
            aiName,
            ownerName,
            effectivePersonality(),
            tradingChain,
            checkIntervalMinutes,
            symbol,
            String.format(Locale.getDefault(), "%.2f", maxDailyLoss),
            newsReportIntervalHours,
            addressLine,
            apiLine,
            notesLine,
            formatTime(createdAt),
            formatTime(updatedAt));
    }

    /** 解析消息中的 @SET 指令，返回是否修改成功 */
    public boolean applySetCommand(String message) {
        if (message == null || !message.contains("@SET")) return false;
        boolean modified = false;
        try {
            // 提取所有 @SET xxx=yyy 指令
            String[] parts = message.split("@SET");
            for (int i = 1; i < parts.length; i++) {
                String cmd = parts[i].trim();
                // 取到下一个 @SET 或行尾
                int nextAt = cmd.indexOf("@SET");
                if (nextAt > 0) cmd = cmd.substring(0, nextAt).trim();
                // 按行分割，取第一个 key=value
                String[] lines = cmd.split("\\n");
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String key = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1).trim();
                    if (applyField(key, value)) {
                        modified = true;
                    }
                }
            }
            if (modified) save();
        } catch (Exception e) {
            Log.e(TAG, "解析 @SET 失败: " + e.getMessage());
        }
        return modified;
    }

    private boolean applyField(String key, String value) {
        switch (key) {
            case "aiName":
                aiName = value;
                return true;
            case "ownerName":
                ownerName = value;
                return true;
            case "personality":
                personality = value;
                return true;
            case "checkIntervalMinutes":
                try {
                    int v = Integer.parseInt(value);
                    if (v >= 1 && v <= 60) { checkIntervalMinutes = v; return true; }
                } catch (NumberFormatException ignored) {}
                return false;
            case "maxDailyLoss":
                try {
                    double v = Double.parseDouble(value);
                    if (v >= 0 && v <= 10000) { maxDailyLoss = v; return true; }
                } catch (NumberFormatException ignored) {}
                return false;
            case "newsReportIntervalHours":
                try {
                    int v = Integer.parseInt(value);
                    if (v >= 1 && v <= 168) { newsReportIntervalHours = v; return true; }
                } catch (NumberFormatException ignored) {}
                return false;
            case "tradingChain":
                tradingChain = value;
                return true;
            case "customNotes":
                customNotes = value;
                return true;
            default:
                return false;
        }
    }

    /** 生成欢迎语 */
    public String getWelcomeMessage() {
        String owner = (ownerName != null && !ownerName.isEmpty()) ? "，" + ownerName : "";
        String currency = CurrencyManager.getSelectedCurrency(ctx);
        String symbol = CurrencyManager.getCurrencySymbol(currency);
        return ctx.getString(R.string.str_ai_welcome_message,
                owner,
                aiName,
                effectivePersonality(),
                tradingChain,
                checkIntervalMinutes,
                symbol,
                String.format(Locale.getDefault(), "%.2f", maxDailyLoss),
                symbol);
    }

    // Getters
    public String getAiName() { return aiName; }
    public String getOwnerName() { return ownerName; }
    public String getPersonality() { return personality; }
    public int getCheckIntervalMinutes() { return checkIntervalMinutes; }
    public double getMaxDailyLoss() { return maxDailyLoss; }
    public int getNewsReportIntervalHours() { return newsReportIntervalHours; }
    public String getTradingChain() { return tradingChain; }
    public String getCustomNotes() { return customNotes; }
    public String getApiKeyMasked() { return apiKeyMasked; }

    private String formatTime(long ts) {
        if (ts <= 0) return ctx.getString(R.string.str_unknown);
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
        return sdf.format(new java.util.Date(ts));
    }
}
