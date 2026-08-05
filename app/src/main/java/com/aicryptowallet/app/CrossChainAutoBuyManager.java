package com.aicryptowallet.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨链自动买入授权管理器。
 *
 * 当 AI 检测到某条链上资产（如 TRX、SOL）的买入信号，而用户当前没有该链钱包时，
 * 需要用户一次性授权：目标链、目标资产、收款地址、是否允许 AI 自动买入。
 * 授权后写入本地持久化，后续同一链+资产的买入信号可直接自动执行，无需再次询问。
 *
 * 与 SafetyGate 合约白名单的关系：
 * - 本管理器解决"跨链买入目标链/钱包缺失"的授权问题。
 * - SafetyGate 合约白名单解决"代币是否允许 AI 自动交易"的问题。
 * - 两者同时满足，AI 才能静默自动跨链买入。
 */
public class CrossChainAutoBuyManager {

    private static final String PREFS_NAME = "cross_chain_auto_buy";
    private static final String KEY_AUTH_LIST = "auth_list";

    /**
     * 单条授权记录
     */
    public static class AuthRecord {
        public final String chain;           // 目标链，如 TRX/SOL
        public final String asset;           // 目标资产：NATIVE 或合约地址
        public final String destinationAddress; // 目标链收款地址
        public final long createdAt;
        public final boolean allowAutoBuy;   // 是否允许检测到信号后自动买入

        public AuthRecord(String chain, String asset, String destinationAddress,
                          boolean allowAutoBuy, long createdAt) {
            this.chain = chain;
            this.asset = asset;
            this.destinationAddress = destinationAddress;
            this.allowAutoBuy = allowAutoBuy;
            this.createdAt = createdAt;
        }

        public String key() {
            return (chain + "_" + asset).toLowerCase();
        }
    }

    /**
     * 添加或更新授权记录。
     */
    public static void authorize(Context ctx, String chain, String asset,
                                  String destinationAddress, boolean allowAutoBuy) {
        if (chain == null || chain.isEmpty() || asset == null || asset.isEmpty()) return;
        if (destinationAddress == null || destinationAddress.isEmpty()) return;

        List<AuthRecord> list = load(ctx);
        String key = (chain + "_" + asset).toLowerCase();

        // 去重：同链同资产只保留最新一条
        List<AuthRecord> filtered = new ArrayList<>();
        for (AuthRecord r : list) {
            if (!r.key().equals(key)) {
                filtered.add(r);
            }
        }
        filtered.add(new AuthRecord(chain, asset, destinationAddress, allowAutoBuy, System.currentTimeMillis()));
        save(ctx, filtered);

        Logger.info(ctx, "跨链自动买入", "已授权 chain=" + chain + " asset=" + asset
            + " address=" + destinationAddress + " autoBuy=" + allowAutoBuy);
    }

    /**
     * 撤销某链某资产的自动买入授权。
     */
    public static void revoke(Context ctx, String chain, String asset) {
        List<AuthRecord> list = load(ctx);
        String key = (chain + "_" + asset).toLowerCase();
        List<AuthRecord> filtered = new ArrayList<>();
        for (AuthRecord r : list) {
            if (!r.key().equals(key)) {
                filtered.add(r);
            }
        }
        save(ctx, filtered);
        Logger.info(ctx, "跨链自动买入", "已撤销授权 chain=" + chain + " asset=" + asset);
    }

    /**
     * 查询某链某资产是否已授权自动买入。
     */
    public static boolean isAuthorized(Context ctx, String chain, String asset) {
        AuthRecord r = find(ctx, chain, asset);
        return r != null && r.allowAutoBuy;
    }

    /**
     * 查询某链某资产的授权记录。
     */
    public static AuthRecord find(Context ctx, String chain, String asset) {
        if (chain == null || asset == null) return null;
        String key = (chain + "_" + asset).toLowerCase();
        for (AuthRecord r : load(ctx)) {
            if (r.key().equals(key)) {
                return r;
            }
        }
        return null;
    }

    /**
     * 获取用户所有授权记录。
     */
    public static List<AuthRecord> load(Context ctx) {
        List<AuthRecord> result = new ArrayList<>();
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_AUTH_LIST, "[]");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String chain = o.optString("chain", "");
                String asset = o.optString("asset", "");
                String address = o.optString("destination_address", "");
                boolean auto = o.optBoolean("allow_auto_buy", false);
                long ts = o.optLong("created_at", 0);
                if (!chain.isEmpty() && !asset.isEmpty() && !address.isEmpty()) {
                    result.add(new AuthRecord(chain, asset, address, auto, ts));
                }
            }
        } catch (Exception e) {
            Logger.warning(ctx, "跨链自动买入", "加载授权记录失败: " + e.getMessage());
        }
        return result;
    }

    private static void save(Context ctx, List<AuthRecord> list) {
        try {
            JSONArray arr = new JSONArray();
            for (AuthRecord r : list) {
                JSONObject o = new JSONObject();
                o.put("chain", r.chain);
                o.put("asset", r.asset);
                o.put("destination_address", r.destinationAddress);
                o.put("allow_auto_buy", r.allowAutoBuy);
                o.put("created_at", r.createdAt);
                arr.put(o);
            }
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_AUTH_LIST, arr.toString())
                .apply();
        } catch (Exception e) {
            Logger.warning(ctx, "跨链自动买入", "保存授权记录失败: " + e.getMessage());
        }
    }
}
