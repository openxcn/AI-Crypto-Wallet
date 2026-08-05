package com.aicryptowallet.app;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * 应用全局上下文 - 初始化日志系统和全局异常处理
 */
public class CryptoWalletApplication extends Application {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleManager.applyLocale(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // 应用用户设置的主题模式（浅色/深色/跟随系统）
        ThemeManager.applyTheme(this);

        // 应用用户设置的语言
        LocaleManager.applyLocale(this);

        // 刷新汇率缓存（异步，失败有兜底）
        CurrencyManager.refreshRatesAsync(this);

        // 初始化全局日志器
        Logger.init(this);
        Logger.system(this, "应用启动", "CryptoWallet v" + getVersionName());

        // 版本升级时迁移节点配置
        migrateNodeConfig();
    }

    /**
     * 版本升级时将旧版保存的不可用节点更新为中国大陆可访问的节点
     * v1.9.6: BSC 从 publicnode 迁移到 defibit，清理被 DNS 劫持的 binance 节点
     * v1.9.7: BSC/ETH/TRX 迁移到 AVE 自建代理（中国直连200ms，最快）
     */
    private void migrateNodeConfig() {
        SharedPreferences prefs = getSharedPreferences("node_prefs", MODE_PRIVATE);
        int lastMigrated = prefs.getInt("node_migrated_version", 0);

        SharedPreferences.Editor ed = prefs.edit();

        // v1.9.7 迁移: 旧版保存的BSC/ETH/TRX节点 → AVE自建代理
        if (lastMigrated < 197) {
            // BSC: 所有旧节点（publicnode/defibit/ninicoin/binance）→ AVE代理
            String bscSelected = prefs.getString("selected_BNB", "");
            if (!bscSelected.contains("dryespah.com") && !bscSelected.contains("xwjtyrs.com")) {
                ed.putString("selected_BNB", "https://api.dryespah.com/ave_nodes/rpc/bsc/sendFastSwapTx");
                Logger.info(this, "节点迁移", "BSC 节点已迁移到 AVE 代理 (200ms)");
            }

            // ETH: publicnode/1rpc/cloudflare/ankr → AVE代理
            String ethSelected = prefs.getString("selected_ETH", "");
            if (!ethSelected.contains("dryespah.com") && !ethSelected.contains("xwjtyrs.com")) {
                ed.putString("selected_ETH", "https://api.dryespah.com/ave_nodes/rpc/eth/sendFastSwapTx");
                Logger.info(this, "节点迁移", "ETH 节点已迁移到 AVE 代理 (200ms)");
            }

            // TRX: trongrid/tronstack → AVE代理
            String trxSelected = prefs.getString("selected_TRX", "");
            if (!trxSelected.contains("dryespah.com") && !trxSelected.contains("xwjtyrs.com")) {
                ed.putString("selected_TRX", "https://api.xwjtyrs.com/ave_nodes/rpc/tron/sendFastSwapTx");
                Logger.info(this, "节点迁移", "TRX 节点已迁移到 AVE 代理");
            }

            ed.putInt("node_migrated_version", 197);
        }

        // v1.9.6 迁移 (兼容)
        if (lastMigrated < 196) {
            String ftmSelected = prefs.getString("selected_FTM", "");
            if (ftmSelected.contains("fantom.publicnode.com")) {
                ed.putString("selected_FTM", "https://rpc.fantom.network");
                Logger.info(this, "节点迁移", "FTM 节点已从 publicnode 迁移到官方节点");
            }
        }

        ed.apply();
    }

    private String getVersionName() {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.PackageInfo pi = pm.getPackageInfo(getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
