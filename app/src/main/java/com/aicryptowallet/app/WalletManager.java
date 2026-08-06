package com.aicryptowallet.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;
import wallet.core.jni.CoinType;
import wallet.core.jni.HDWallet;
import wallet.core.jni.PrivateKey;

public class WalletManager {
    private static final String PREFS_NAME = "wallet_prefs";
    // 旧版单钱包字段（迁移用）
    private static final String KEY_WALLET_NAME = "wallet_name";
    private static final String KEY_WALLET_PASSWORD = "wallet_password";
    private static final String KEY_WALLET_MNEMONIC = "wallet_mnemonic";
    private static final String KEY_WALLET_MNEMONIC_ENC = "wallet_mnemonic_enc";
    private static final String KEY_WALLET_ADDRESS = "wallet_address";
    private static final String KEY_WALLET_CHAIN = "wallet_chain";
    // 新版多钱包字段
    private static final String KEY_WALLETS = "wallets_json";
    private static final String KEY_ACTIVE_WALLET_ID = "active_wallet_id";
    // AI 配置（全局共享）
    private static final String KEY_AI_ENABLED = "ai_enabled";
    private static final String KEY_AI_MODEL = "ai_model";
    private static final String KEY_AI_API_KEY = "ai_api_key";
    private static final String KEY_AI_API_URL = "ai_api_url";
    private static final String KEY_RPC_PREFIX = "rpc_";
    private static final String KEY_CUSTOM_TOKENS = "custom_tokens";

    private static final String KEYSTORE_ALIAS = "aicw_mnemonic_key";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final int GCM_TAG_LENGTH = 128;

    static {
        System.loadLibrary("TrustWalletCore");
    }

    // ========== 钱包数据结构 ==========

    public static class WalletInfo {
        public String id;
        public String name;
        public String address;
        public String chain;
        public String type;        // normal / watch_only / imported
        public String mnemonicEnc;  // 加密后的助记词（或私钥）
        public String password;     // 密码哈希
        public boolean backedUp;   // 是否已完成助记词备份验证

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", id);
                obj.put("name", name);
                obj.put("address", address);
                obj.put("chain", chain);
                obj.put("type", type != null ? type : "normal");
                obj.put("mnemonicEnc", mnemonicEnc != null ? mnemonicEnc : "");
                obj.put("password", password != null ? password : "");
                obj.put("backedUp", backedUp);
            } catch (Exception ignored) {}
            return obj;
        }

        public static WalletInfo fromJson(JSONObject obj) {
            WalletInfo w = new WalletInfo();
            w.id = obj.optString("id", "");
            w.name = obj.optString("name", "我的钱包");
            w.address = obj.optString("address", "");
            w.chain = obj.optString("chain", "ETH");
            w.type = obj.optString("type", "normal");
            w.mnemonicEnc = obj.optString("mnemonicEnc", "");
            w.password = obj.optString("password", "");
            w.backedUp = obj.optBoolean("backedUp", false);
            return w;
        }

        public String getShortAddress() {
            if (address != null && address.length() > 10) {
                return address.substring(0, 6) + "..." + address.substring(address.length() - 4);
            }
            return address;
        }

        public String getTypeLabel() {
            if ("watch_only".equals(type)) return "观察";
            if ("imported".equals(type)) return "导入";
            return "HD";
        }

        public boolean isWatchOnly() {
            return "watch_only".equals(type);
        }

        public boolean hasPrivateKey() {
            return !"watch_only".equals(type);
        }
    }

    // ========== 多钱包管理 ==========

    public static SharedPreferences getPrefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 迁移旧版单钱包到多钱包格式（幂等，已迁移则跳过）
     */
    private static void migrateIfNeeded(Context ctx) {
        SharedPreferences prefs = getPrefs(ctx);
        if (prefs.contains(KEY_WALLETS)) return; // 已迁移

        if (!prefs.contains(KEY_WALLET_ADDRESS)) return; // 无旧钱包

        String name = prefs.getString(KEY_WALLET_NAME, "我的钱包");
        String address = prefs.getString(KEY_WALLET_ADDRESS, "");
        String chain = prefs.getString(KEY_WALLET_CHAIN, "ETH");
        String password = prefs.getString(KEY_WALLET_PASSWORD, "");
        String mnemonicEnc = prefs.getString(KEY_WALLET_MNEMONIC_ENC, "");
        if (mnemonicEnc.isEmpty()) {
            String plain = prefs.getString(KEY_WALLET_MNEMONIC, "");
            if (!plain.isEmpty()) {
                mnemonicEnc = encryptMnemonic(plain);
                if (mnemonicEnc == null) mnemonicEnc = "";
            }
        }

        WalletInfo w = new WalletInfo();
        w.id = UUID.randomUUID().toString();
        w.name = name;
        w.address = address;
        w.chain = chain;
        w.type = "normal";
        w.password = password;
        w.mnemonicEnc = mnemonicEnc;
        w.backedUp = true; // 旧版钱包默认标记为已备份（在引入备份验证功能之前创建的）

        JSONArray arr = new JSONArray();
        arr.put(w.toJson());

        prefs.edit()
            .putString(KEY_WALLETS, arr.toString())
            .putString(KEY_ACTIVE_WALLET_ID, w.id)
            .apply();
    }

    public static boolean hasWallet(Context ctx) {
        migrateIfNeeded(ctx);
        String json = getPrefs(ctx).getString(KEY_WALLETS, "");
        return !json.isEmpty();
    }

    public static String getActiveWalletId(Context ctx) {
        migrateIfNeeded(ctx);
        return getPrefs(ctx).getString(KEY_ACTIVE_WALLET_ID, "");
    }

    public static void setActiveWalletId(Context ctx, String walletId) {
        getPrefs(ctx).edit().putString(KEY_ACTIVE_WALLET_ID, walletId).apply();
    }

    public static List<WalletInfo> getAllWallets(Context ctx) {
        migrateIfNeeded(ctx);
        List<WalletInfo> list = new ArrayList<>();
        String json = getPrefs(ctx).getString(KEY_WALLETS, "");
        if (json.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                list.add(WalletInfo.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception e) {
            android.util.Log.e("WalletManager", "解析钱包列表失败", e);
        }
        return list;
    }

    public static WalletInfo getActiveWallet(Context ctx) {
        String activeId = getActiveWalletId(ctx);
        for (WalletInfo w : getAllWallets(ctx)) {
            if (w.id.equals(activeId)) return w;
        }
        // 回退：返回第一个钱包
        List<WalletInfo> all = getAllWallets(ctx);
        if (!all.isEmpty()) {
            setActiveWalletId(ctx, all.get(0).id);
            return all.get(0);
        }
        return null;
    }

    public static WalletInfo getWalletById(Context ctx, String walletId) {
        for (WalletInfo w : getAllWallets(ctx)) {
            if (w.id.equals(walletId)) return w;
        }
        return null;
    }

    // ========== 单钱包兼容方法（委托到活跃钱包）==========

    public static String getWalletName(Context ctx) {
        WalletInfo w = getActiveWallet(ctx);
        return w != null ? w.name : "我的钱包";
    }

    public static String getWalletAddress(Context ctx) {
        WalletInfo w = getActiveWallet(ctx);
        return w != null ? w.address : "";
    }

    public static String getChain(Context ctx) {
        WalletInfo w = getActiveWallet(ctx);
        return w != null ? w.chain : "ETH";
    }

    public static void setChain(Context ctx, String chain) {
        WalletInfo w = getActiveWallet(ctx);
        if (w != null) {
            w.chain = chain;
            saveWalletList(ctx, getAllWallets(ctx));
        }
    }

    public static String getPassword(Context ctx) {
        WalletInfo w = getActiveWallet(ctx);
        return w != null ? w.password : "";
    }

    public static String getMnemonic(Context ctx) {
        WalletInfo w = getActiveWallet(ctx);
        if (w == null) return "";
        String enc = w.mnemonicEnc;
        if (enc != null && !enc.isEmpty()) {
            String decrypted = decryptMnemonic(enc);
            if (decrypted != null) return decrypted;
        }
        return "";
    }

    public static boolean verifyPassword(Context ctx, String inputPassword) {
        String stored = getPassword(ctx);
        if (stored == null || stored.isEmpty()) return true;
        if (stored.startsWith("pbkdf2$")) {
            try {
                String[] parts = stored.split("\\$");
                if (parts.length != 4) return false;
                int iterations = Integer.parseInt(parts[1]);
                byte[] salt = hexToBytes(parts[2]);
                byte[] expectedHash = hexToBytes(parts[3]);
                javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                    inputPassword.toCharArray(), salt, iterations, 256);
                javax.crypto.SecretKeyFactory skf = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                byte[] actualHash = skf.generateSecret(spec).getEncoded();
                return java.security.MessageDigest.isEqual(expectedHash, actualHash);
            } catch (Exception e) {
                return false;
            }
        }
        boolean match = java.security.MessageDigest.isEqual(
            stored.getBytes(), inputPassword.getBytes());
        return match;
    }

    // ========== 钱包增删改 ==========

    public static void saveWallet(Context ctx, String name, String password, String mnemonic, String address, String chain) {
        migrateIfNeeded(ctx);
        String encMnemonic = encryptMnemonic(mnemonic);
        if (encMnemonic == null) encMnemonic = "";

        WalletInfo w = new WalletInfo();
        w.id = UUID.randomUUID().toString();
        w.name = name;
        w.address = address;
        w.chain = chain;
        w.type = "normal";
        w.password = hashPassword(password);
        w.mnemonicEnc = encMnemonic;
        w.backedUp = false; // 新创建的钱包尚未完成备份验证

        List<WalletInfo> list = getAllWallets(ctx);
        list.add(w);
        saveWalletList(ctx, list);
        setActiveWalletId(ctx, w.id);
    }

    public static void addWallet(Context ctx, WalletInfo wallet) {
        List<WalletInfo> list = getAllWallets(ctx);
        list.add(wallet);
        saveWalletList(ctx, list);
        setActiveWalletId(ctx, wallet.id);
    }

    public static void removeWalletByAddress(Context ctx, String address) {
        List<WalletInfo> list = getAllWallets(ctx);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).address.equalsIgnoreCase(address)) {
                list.remove(i);
                break;
            }
        }
        saveWalletList(ctx, list);
    }

    public static void markWalletBackedUp(Context ctx, String address) {
        List<WalletInfo> list = getAllWallets(ctx);
        for (WalletInfo w : list) {
            if (w.address.equalsIgnoreCase(address)) {
                w.backedUp = true;
                break;
            }
        }
        saveWalletList(ctx, list);
    }

    public static void removeWallet(Context ctx, String walletId) {
        List<WalletInfo> list = getAllWallets(ctx);
        WalletInfo removed = null;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(walletId)) {
                removed = list.remove(i);
                break;
            }
        }
        saveWalletList(ctx, list);

        // 如果删除的是活跃钱包，切换到第一个
        String activeId = getActiveWalletId(ctx);
        if (walletId.equals(activeId)) {
            if (!list.isEmpty()) {
                setActiveWalletId(ctx, list.get(0).id);
            } else {
                getPrefs(ctx).edit().remove(KEY_ACTIVE_WALLET_ID).apply();
            }
        }
    }

    /**
     * 添加观察钱包（仅地址，无私钥，不可签名）
     */
    public static void addWatchOnlyWallet(Context ctx, String name, String address, String chain) {
        migrateIfNeeded(ctx);
        WalletInfo w = new WalletInfo();
        w.id = UUID.randomUUID().toString();
        w.name = name;
        w.address = address;
        w.chain = chain;
        w.type = "watch_only";
        w.backedUp = true; // 观察钱包无需助记词备份

        List<WalletInfo> list = getAllWallets(ctx);
        list.add(w);
        saveWalletList(ctx, list);
        setActiveWalletId(ctx, w.id);
    }

    /**
     * 重命名钱包
     */
    public static void renameWallet(Context ctx, String walletId, String newName) {
        List<WalletInfo> list = getAllWallets(ctx);
        for (WalletInfo w : list) {
            if (w.id.equals(walletId)) {
                w.name = newName;
                break;
            }
        }
        saveWalletList(ctx, list);
    }

    public static void clearWallet(Context ctx) {
        getPrefs(ctx).edit().clear().apply();
    }

    private static void saveWalletList(Context ctx, List<WalletInfo> list) {
        JSONArray arr = new JSONArray();
        for (WalletInfo w : list) {
            arr.put(w.toJson());
        }
        getPrefs(ctx).edit().putString(KEY_WALLETS, arr.toString()).apply();
    }

    // ========== 密码哈希 ==========

    private static String hashPassword(String password) {
        try {
            byte[] salt = new byte[16];
            new java.security.SecureRandom().nextBytes(salt);
            int iterations = 100000;
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                password.toCharArray(), salt, iterations, 256);
            javax.crypto.SecretKeyFactory skf = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = skf.generateSecret(spec).getEncoded();
            return "pbkdf2$" + iterations + "$"
                + bytesToHex(salt) + "$" + bytesToHex(hash);
        } catch (Exception e) {
            return password;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    // ========== 助记词加密/解密 ==========

    private static SecretKey getOrCreateSecretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEYSTORE_ALIAS, null);
        }
        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE);
        android.security.keystore.KeyGenParameterSpec keySpec =
            new android.security.keystore.KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
                    | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes("GCM")
            .setEncryptionPaddings("NoPadding")
            .setKeySize(256)
            .build();
        keyGenerator.init(keySpec);
        return keyGenerator.generateKey();
    }

    private static String encryptMnemonic(String plain) {
        if (plain == null || plain.isEmpty()) return null;
        try {
            SecretKey key = getOrCreateSecretKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] ciphertext = cipher.doFinal(plain.getBytes("UTF-8"));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Exception e) {
            android.util.Log.e("WalletManager", "助记词加密失败", e);
            return null;
        }
    }

    private static String decryptMnemonic(String enc) {
        if (enc == null || enc.isEmpty()) return null;
        try {
            byte[] combined = Base64.decode(enc, Base64.NO_WRAP);
            if (combined.length < 13) return null;
            byte[] iv = new byte[12];
            byte[] ciphertext = new byte[combined.length - 12];
            System.arraycopy(combined, 0, iv, 0, 12);
            System.arraycopy(combined, 12, ciphertext, 0, ciphertext.length);
            SecretKey key = getOrCreateSecretKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            byte[] plain = cipher.doFinal(ciphertext);
            return new String(plain, "UTF-8");
        } catch (Exception e) {
            android.util.Log.e("WalletManager", "助记词解密失败", e);
            return null;
        }
    }

    // ========== AI 配置 ==========

    public static boolean isAIEnabled(Context ctx) {
        return getPrefs(ctx).getBoolean(KEY_AI_ENABLED, false);
    }

    public static String getAIModel(Context ctx) {
        return getPrefs(ctx).getString(KEY_AI_MODEL, "");
    }

    public static String getAPIKey(Context ctx) {
        return getPrefs(ctx).getString(KEY_AI_API_KEY, "");
    }

    public static String getAPIUrl(Context ctx) {
        return getPrefs(ctx).getString(KEY_AI_API_URL, "");
    }

    public static void saveAIConfig(Context ctx, boolean enabled, String model, String apiKey, String apiUrl) {
        getPrefs(ctx).edit()
            .putBoolean(KEY_AI_ENABLED, enabled)
            .putString(KEY_AI_MODEL, model)
            .putString(KEY_AI_API_KEY, apiKey)
            .putString(KEY_AI_API_URL, apiUrl)
            .commit();
    }

    // ========== RPC ==========

    public static String getRpcUrl(Context ctx, String chain) {
        return NodeManager.getSelectedNode(ctx, chain);
    }

    public static void saveRpcUrl(Context ctx, String chain, String url) {
        NodeManager.setSelectedNode(ctx, chain, url);
    }

    // ========== 自定义代币 ==========

    public static String[][] getCustomTokens(Context ctx, String chain) {
        String data = getPrefs(ctx).getString(KEY_CUSTOM_TOKENS + "_" + chain, "");
        if (data.isEmpty()) return new String[0][];
        String[] tokens = data.split(";");
        String[][] result = new String[tokens.length][];
        for (int i = 0; i < tokens.length; i++) {
            result[i] = tokens[i].split("\\|");
        }
        return result;
    }

    public static void addCustomToken(Context ctx, String chain, String symbol, String name, String contract, String decimals) {
        String existing = getPrefs(ctx).getString(KEY_CUSTOM_TOKENS + "_" + chain, "");
        String newToken = symbol + "|" + name + "|" + contract + "|" + decimals;
        String updated = existing.isEmpty() ? newToken : existing + ";" + newToken;
        getPrefs(ctx).edit().putString(KEY_CUSTOM_TOKENS + "_" + chain, updated).commit();
    }

    public static void removeCustomToken(Context ctx, String chain, String contract) {
        String data = getPrefs(ctx).getString(KEY_CUSTOM_TOKENS + "_" + chain, "");
        if (data.isEmpty()) return;
        String[] tokens = data.split(";");
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            String[] parts = token.split("\\|");
            if (parts.length >= 3 && !parts[2].equalsIgnoreCase(contract)) {
                if (sb.length() > 0) sb.append(";");
                sb.append(token);
            }
        }
        getPrefs(ctx).edit().putString(KEY_CUSTOM_TOKENS + "_" + chain, sb.toString()).commit();
    }

    // ========== 链工具 ==========

    public static CoinType getCoinType(String chain) {
        switch (chain) {
            case "ETH":   return CoinType.ETHEREUM;
            case "BNB":   return CoinType.SMARTCHAIN;
            case "SOL":   return CoinType.SOLANA;
            case "TRX":   return CoinType.TRON;
            case "AVAX":  return CoinType.AVALANCHECCHAIN;
            case "SUI":   return CoinType.SUI;
            case "APT":   return CoinType.APTOS;
            case "ADA":   return CoinType.CARDANO;
            case "MATIC": return CoinType.POLYGON;
            case "NEAR":  return CoinType.NEAR;
            case "FTM":   return CoinType.FANTOM;
            case "ATOM":  return CoinType.COSMOS;
            case "DOT":   return CoinType.POLKADOT;
            case "GLMR":  return CoinType.MOONBEAM;
            case "KAVA":  return CoinType.KAVA;
            case "ALGO":  return CoinType.ALGORAND;
            case "ICP":   return CoinType.INTERNETCOMPUTER;
            case "CELO":  return CoinType.CELO;
            case "XTZ":   return CoinType.TEZOS;
            case "ONE":   return CoinType.HARMONY;
            default:      return CoinType.ETHEREUM;
        }
    }

    public static String generateMnemonic() {
        try {
            HDWallet wallet = new HDWallet(128, "");
            return wallet.mnemonic();
        } catch (Exception e) {
            throw new RuntimeException("助记词生成失败: " + e.getMessage(), e);
        }
    }

    public static String deriveAddress(String mnemonic, String chain) {
        return deriveAddressAtIndex(mnemonic, chain, 0);
    }

    /**
     * 从助记词派生指定索引的地址（同一助记词生成多账户）
     * 默认路径 m/44'/coinType'/0'/0/0，替换最后的0为指定索引
     */
    public static String deriveAddressAtIndex(String mnemonic, String chain, int index) {
        try {
            HDWallet wallet = new HDWallet(mnemonic, "");
            CoinType coinType = getCoinType(chain);
            if (index == 0) {
                return wallet.getAddressForCoin(coinType);
            }
            // 获取默认路径 m/44'/60'/0'/0/0，替换末尾索引
            String defaultPath = coinType.derivationPath();
            String customPath = defaultPath.replaceAll("/\\d+$", "/" + index);
            PrivateKey key = wallet.getKey(coinType, customPath);
            return coinType.deriveAddress(key);
        } catch (Exception e) {
            android.util.Log.e("WalletManager", "deriveAddressAtIndex failed: " + e.getMessage());
            return "";
        }
    }

    /**
     * 获取当前活跃钱包已使用的最大HD索引+1
     */
    public static int getNextHdIndex(Context ctx) {
        int maxIndex = -1;
        for (WalletInfo w : getAllWallets(ctx)) {
            if ("normal".equals(w.type) && w.name != null && w.name.startsWith("Account ")) {
                try {
                    int idx = Integer.parseInt(w.name.substring(8));
                    if (idx > maxIndex) maxIndex = idx;
                } catch (Exception ignored) {}
            }
        }
        return maxIndex + 1;
    }

    /**
     * 导入私钥钱包（私钥导入，非助记词）
     */
    public static void saveImportedWallet(Context ctx, String name, String password, String privateKeyHex, String address, String chain) {
        migrateIfNeeded(ctx);
        String encPrivateKey = encryptMnemonic(privateKeyHex); // 复用加密方法
        if (encPrivateKey == null) encPrivateKey = "";

        WalletInfo w = new WalletInfo();
        w.id = UUID.randomUUID().toString();
        w.name = name;
        w.address = address;
        w.chain = chain;
        w.type = "imported";
        w.password = hashPassword(password);
        w.mnemonicEnc = encPrivateKey; // 存储加密后的私钥
        w.backedUp = true; // 导入钱包由用户提供私钥/助记词，无需额外备份

        List<WalletInfo> list = getAllWallets(ctx);
        list.add(w);
        saveWalletList(ctx, list);
        setActiveWalletId(ctx, w.id);
    }

    /**
     * 从私钥Hex推导地址
     */
    public static String deriveAddressFromPrivateKey(String privateKeyHex, String chain) {
        try {
            // 去掉0x前缀
            if (privateKeyHex.startsWith("0x") || privateKeyHex.startsWith("0X")) {
                privateKeyHex = privateKeyHex.substring(2);
            }
            byte[] keyBytes = hexToBytes(privateKeyHex);
            PrivateKey privateKey = new PrivateKey(keyBytes);
            CoinType coinType = getCoinType(chain);
            return coinType.deriveAddress(privateKey);
        } catch (Exception e) {
            android.util.Log.e("WalletManager", "deriveAddressFromPrivateKey failed: " + e.getMessage());
            return "";
        }
    }

    /**
     * 判断输入是私钥（64位hex）还是助记词（多个单词）
     */
    public static boolean isPrivateKey(String input) {
        if (input == null) return false;
        String s = input.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        return s.matches("[0-9a-fA-F]{64}") && !s.contains(" ");
    }

    /**
     * 添加HD派生账户（同一助记词，不同索引）
     */
    public static void addHdAccount(Context ctx, String name, String address, String chain) {
        migrateIfNeeded(ctx);
        // 获取活跃钱包的助记词和密码
        WalletInfo active = getActiveWallet(ctx);
        if (active == null || active.mnemonicEnc == null || active.mnemonicEnc.isEmpty()) return;

        WalletInfo w = new WalletInfo();
        w.id = UUID.randomUUID().toString();
        w.name = name;
        w.address = address;
        w.chain = chain;
        w.type = "normal";
        w.mnemonicEnc = active.mnemonicEnc;  // 共享同一助记词
        w.password = active.password;         // 共享同一密码

        List<WalletInfo> list = getAllWallets(ctx);
        list.add(w);
        saveWalletList(ctx, list);
        setActiveWalletId(ctx, w.id);
    }

    public static PrivateKey getPrivateKey(String mnemonic, String chain) {
        try {
            HDWallet wallet = new HDWallet(mnemonic, "");
            CoinType coinType = getCoinType(chain);
            return wallet.getKeyForCoin(coinType);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isValidAddress(String address, String chain) {
        try {
            CoinType coinType = getCoinType(chain);
            return coinType.validate(address);
        } catch (Exception e) {
            return false;
        }
    }

    public static String generateAddress(String chain) {
        String mnemonic = generateMnemonic();
        return deriveAddress(mnemonic, chain);
    }
}