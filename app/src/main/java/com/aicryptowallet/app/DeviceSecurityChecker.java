package com.aicryptowallet.app;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 设备安全检测器。
 *
 * 启动时检测手机是否存在安全隐患：
 * 1. Root / 已解锁 Bootloader
 * 2. 模拟器
 * 3. USB 调试（ADB）开启
 * 4. 开发者选项开启
 *
 * 命中任意一项即视为不安全设备，建议用户更换设备或承担风险。
 */
public class DeviceSecurityChecker {

    /**
     * 检测设备存在的安全隐患，返回风险描述列表。
     * 若列表为空，表示未检测到明显风险。
     */
    public static List<String> checkSecurityRisks(Context ctx) {
        List<String> risks = new ArrayList<>();
        try {
            if (isDeviceRooted()) {
                risks.add("设备已 Root / 已解锁 Bootloader，私钥和助记词可能被恶意应用读取");
            }
            if (isEmulator()) {
                risks.add("当前设备疑似模拟器，存在运行环境不可信风险");
            }
            if (isUsbDebuggingEnabled(ctx)) {
                risks.add("USB 调试（ADB）已开启，电脑端可连接并读取设备数据");
            }
            if (isDeveloperOptionsEnabled(ctx)) {
                risks.add("开发者选项已开启，部分调试功能可能降低系统安全性");
            }
        } catch (Exception e) {
            Logger.warning(ctx, "安全检测", "设备安全检测异常: " + e.getMessage());
        }
        return risks;
    }

    /**
     * 综合多种特征判断设备是否已 Root。
     */
    public static boolean isDeviceRooted() {
        // 1. 测试签名
        String tags = Build.TAGS;
        if (tags != null && tags.contains("test-keys")) {
            return true;
        }

        // 2. 常见 su 路径
        String[] paths = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sbin/su",
            "/system/sd/xbin/su",
            "/system/app/Superuser.apk",
            "/magisk/.core/bin/su",
            "/sbin/.magisk/mirror/system_root/system/bin/su"
        };
        for (String path : paths) {
            if (new File(path).exists()) {
                return true;
            }
        }

        // 3. 尝试执行 su（带 1.5 秒超时，防止挂起）
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            boolean finished = process.waitFor(1500, TimeUnit.MILLISECONDS);
            if (finished && process.exitValue() == 0) {
                return true;
            }
        } catch (Exception ignored) {
        } finally {
            if (process != null) {
                try { process.destroyForcibly(); } catch (Exception ignored) {}
            }
        }

        return false;
    }

    /**
     * 综合多种特征判断是否在模拟器上运行。
     */
    public static boolean isEmulator() {
        String hardware = Build.HARDWARE;
        String product = Build.PRODUCT;
        String model = Build.MODEL;
        String manufacturer = Build.MANUFACTURER;
        String fingerprint = Build.FINGERPRINT;
        String board = Build.BOARD;
        String device = Build.DEVICE;

        if (hardware == null) hardware = "";
        if (product == null) product = "";
        if (model == null) model = "";
        if (manufacturer == null) manufacturer = "";
        if (fingerprint == null) fingerprint = "";
        if (board == null) board = "";
        if (device == null) device = "";

        String lowerHw = hardware.toLowerCase();
        String lowerProd = product.toLowerCase();
        String lowerModel = model.toLowerCase();
        String lowerManu = manufacturer.toLowerCase();
        String lowerFp = fingerprint.toLowerCase();
        String lowerBoard = board.toLowerCase();
        String lowerDevice = device.toLowerCase();

        // 常见模拟器特征
        if (lowerHw.contains("goldfish")
            || lowerHw.contains("ranchu")
            || lowerHw.contains("vbox86")
            || lowerHw.contains("ttvm")
            || lowerHw.contains("nox")
            || lowerHw.contains("genymotion")
            || lowerHw.contains("ldplayer")
            || lowerHw.contains("memu")
            || lowerHw.contains("bluestacks")) {
            return true;
        }

        if (lowerProd.contains("sdk")
            || lowerProd.contains("google_sdk")
            || lowerProd.contains("sdk_x86")
            || lowerProd.contains("vbox86p")
            || lowerProd.contains("nox")
            || lowerProd.contains("ldplayer")
            || lowerProd.contains("memu")
            || lowerProd.contains("bluestacks")) {
            return true;
        }

        if (lowerModel.contains("emulator")
            || lowerModel.contains("google_sdk")
            || lowerModel.contains("sdk")
            || lowerModel.contains("nox")
            || lowerModel.contains("ldplayer")
            || lowerModel.contains("memu")
            || lowerModel.contains("bluestacks")) {
            return true;
        }

        if (lowerManu.contains("genymotion")
            || lowerManu.contains("nox")
            || lowerManu.contains("ldplayer")
            || lowerManu.contains("memu")
            || lowerManu.contains("bluestacks")) {
            return true;
        }

        if (lowerFp.contains("generic")
            || lowerFp.contains("unknown")
            || lowerFp.contains("google/sdk")) {
            return true;
        }

        if (lowerBoard.contains("goldfish")
            || lowerBoard.contains("ranchu")
            || lowerBoard.contains("vbox86")) {
            return true;
        }

        if (lowerDevice.contains("generic")) {
            return true;
        }

        return false;
    }

    /**
     * USB 调试（ADB）是否开启。
     */
    public static boolean isUsbDebuggingEnabled(Context ctx) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 开发者选项是否开启。
     */
    public static boolean isDeveloperOptionsEnabled(Context ctx) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }
}
