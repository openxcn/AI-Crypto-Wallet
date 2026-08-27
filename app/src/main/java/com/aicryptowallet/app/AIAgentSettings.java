package com.aicryptowallet.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * AI 助手主动互动与会话偏好设置（Activity 与前台服务共用）
 *
 * 承载「主动聊天总开关」「交易通知/闲聊子开关」「聊天频率档位」「语气模板」。
 * 统一存放在 SharedPreferences，避免与 AgentMemory(JSON 文件) 的字段冲突。
 * 所有读取均带默认值，未配置时保持兼容旧版行为。
 */
public class AIAgentSettings {

    private static final String PREFS = "ai_agent_interaction_settings";

    // 聊天频率档位
    public static final int FREQ_OCCASIONAL = 0; // 偶尔聊聊：每周几次
    public static final int FREQ_NORMAL     = 1; // 正常互动：每天几次
    public static final int FREQ_TALKY      = 2; // 话痨模式：每天多次
    public static final int FREQ_UNLIMITED  = 3; // 不限：跟随 AI 判断

    // 语气/人格预设模板
    public static final int PRESET_NONE       = -1; // 未设置，沿用记忆里自由文本
    public static final int PRESET_STEADY     = 0;  // 沉稳理性
    public static final int PRESET_HUMOROUS   = 1;  // 幽默风趣
    public static final int PRESET_SARCASM    = 2;  // 毒舌吐槽
    public static final int PRESET_GENTLE     = 3;  // 温柔陪伴
    public static final int PRESET_FIRM       = 4;  // 激进果断

    private AIAgentSettings() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- 主动聊天总开关 ----------
    public static boolean isProactiveEnabled(Context c) { return sp(c).getBoolean("proactive_enabled", true); }
    public static void setProactiveEnabled(Context c, boolean v) { sp(c).edit().putBoolean("proactive_enabled", v).apply(); }

    // ---------- 交易类主动通知 ----------
    public static boolean isProactiveTradingEnabled(Context c) {
        return isProactiveEnabled(c) && sp(c).getBoolean("proactive_trading", true);
    }
    public static void setProactiveTradingEnabled(Context c, boolean v) { sp(c).edit().putBoolean("proactive_trading", v).apply(); }

    // ---------- 闲聊类主动互动 ----------
    public static boolean isProactiveChatEnabled(Context c) {
        return isProactiveEnabled(c) && sp(c).getBoolean("proactive_chat", false);
    }
    public static void setProactiveChatEnabled(Context c, boolean v) { sp(c).edit().putBoolean("proactive_chat", v).apply(); }

    // 判断是否允许任意主动消息（交易或闲聊任一开启即可）
    public static boolean isAnyProactiveEnabled(Context c) {
        return isProactiveTradingEnabled(c) || isProactiveChatEnabled(c);
    }

    // ---------- 聊天频率档位 ----------
    public static int getChatFrequency(Context c) { return sp(c).getInt("chat_frequency", FREQ_NORMAL); }
    public static void setChatFrequency(Context c, int v) { sp(c).edit().putInt("chat_frequency", v).apply(); }

    /** AI 定时市场分析的间隔（分钟）。默认 5 分钟一次。 */
    public static int getAnalysisIntervalMinutes(Context c) { return sp(c).getInt("analysis_interval_minutes", 5); }
    public static void setAnalysisIntervalMinutes(Context c, int v) { sp(c).edit().putInt("analysis_interval_minutes", v).apply(); }

    /**
     * 按频率档位计算一天内最多允许的主动闲聊次数；不限档返回 -1（不限制）。
     */
    public static int getDailyChatLimit(Context c) {
        switch (getChatFrequency(c)) {
            case FREQ_OCCASIONAL: return 3;          // 每周几次 → 简单按每天少量控制
            case FREQ_TALKY:      return -1;         // 话痨模式：不限，仅靠间隔控制
            case FREQ_UNLIMITED:  return -1;         // 不限
            case FREQ_NORMAL:
            default:              return -1;         // 正常互动：不限，仅靠间隔控制
        }
    }

    // ---------- 语气/人格预设模板 ----------
    public static int getPersonalityPreset(Context c) { return sp(c).getInt("personality_preset", PRESET_NONE); }
    public static void setPersonalityPreset(Context c, int v) { sp(c).edit().putInt("personality_preset", v).apply(); }

    /** 根据预设返回语气描述；未设置时返回 null，由上层沿用记忆中的自由文本 */
    public static String getPresetPersonalityText(Context c) {
        switch (getPersonalityPreset(c)) {
            case PRESET_STEADY:     return "沉稳理性，分析数据后给出建议，不轻易下结论，表达专业客观";
            case PRESET_HUMOROUS:   return "幽默风趣，轻松活泼，善于用俏皮话缓解紧张，但观点依然专业";
            case PRESET_SARCASM:    return "毒舌吐槽，犀利直接，带点幽默的调侃，只对事不对人，不冒犯";
            case PRESET_GENTLE:     return "温柔陪伴，体贴关怀，善于鼓励和安抚，语气亲切温和";
            case PRESET_FIRM:       return "激进果断，判断果断，行动导向，发现机会就快速给出建议";
            default:                return null;
        }
    }
}