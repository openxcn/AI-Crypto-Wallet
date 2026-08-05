package com.aicryptowallet.app;

import android.content.Context;
import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * SMART 代币工具类 - 检查用户是否持有足够 SMART 代币
 */
public class SmartTokenUtils {
    
    private static final OkHttpClient client = new OkHttpClient();
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    
    /**
     * 检查用户是否拥有足够 SMART 代币（免手续费）
     */
    public static void hasFreeTradePermission(Context ctx, String chain, String callback) {
        new Thread(() -> {
            try {
                boolean hasPermission = checkSmartBalance(ctx, chain);
                if ("1".equals(callback)) {
                    // 直接返回布尔值
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
    
    /**
     * 检查 SMART 代币余额
     */
    public static boolean checkSmartBalance(Context ctx, String chain) {
        try {
            String address = WalletManager.getWalletAddress(ctx);
            String rpcUrl = WalletManager.getRpcUrl(ctx, chain);
            
            if (rpcUrl == null || rpcUrl.isEmpty()) {
                return false;
            }
            
            // 代币 balanceOf 方法: 0x70a08231 + address(32 bytes)
            String addressPadded = address.substring(2).toLowerCase();
            String padding = "0".repeat(64 - addressPadded.length());
            String data = "0x70a08231" + padding + addressPadded;
            
            JSONObject json = new JSONObject();
            json.put("jsonrpc", "2.0");
            json.put("id", 1);
            json.put("method", "eth_call");
            JSONArray params = new JSONArray();
            JSONObject callObj = new JSONObject();
            callObj.put("to", AppConfig.getSmartTokenContract());
            callObj.put("data", data);
            params.put(new JSONArray().put("latest"));
            json.put("params", params);
            
            RequestBody body = RequestBody.create(json.toString(), JSON_TYPE);
            Request request = new Request.Builder()
                .url(rpcUrl)
                .post(body)
                .build();
            
            try (Response response = client.newCall(request).execute()) {
                if (response.body() == null) return false;
                
                String resp = response.body().string();
                JSONObject result = new JSONObject(resp);
                String balanceHex = result.getString("result");
                
                // 转换为十进制
                BigInteger balance = new BigInteger(balanceHex, 16);
                
                // SMART 代币通常是 18 位小数
                BigInteger threshold = BigInteger.valueOf(AppConfig.SMART_FREE_THRESHOLD)
                    .multiply(BigInteger.TEN.pow(18));
                
                return balance.compareTo(threshold) >= 0;
            }
        } catch (Exception e) {
            return false;
        }
    }
}
