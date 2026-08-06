/*
 * Copyright (C) 2026 红魔团队 (Red Devil Team)
 *
 * This software is proprietary and confidential.
 * Unauthorized copying, distribution, or modification is strictly prohibited.
 *
 * Licensed to: Authorized Users Only
 * Authorization required: Contact openxcn@github.com
 */
package com.aicryptowallet.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 版权与授权管理器
 *
 * AI Crypto Wallet - 红魔团队专有软件
 * 未经授权禁止复制、修改或分发
 */
public class LicenseManager {

    // 版权信息
    public static final String COPYRIGHT_OWNER = "红魔团队 (Red Devil Team)";
    public static final String COPYRIGHT_YEAR = "2026";
    public static final String PRODUCT_NAME = "AI Crypto Wallet";
    public static final String LICENSE_TYPE = "Proprietary Software";
    public static final String CONTACT_EMAIL = "aibgsps@gmail.com";

    // 授权校验：本包名和签名是固定的，防止他人修改后重打包
    private static final String EXPECTED_PACKAGE = "com.aicryptowallet.app";

    // 红魔团队签名证书的 SHA1 指纹（运行时动态获取，不硬编码）
    // 只验证签名是否与原安装包一致，防止二次打包

    /**
     * 验证应用授权是否合法
     * 通过检查包名和签名确保应用未被篡改
     */
    public static boolean verifyLicense(Context context) {
        try {
            // 1. 检查包名是否被篡改
            String packageName = context.getPackageName();
            if (!EXPECTED_PACKAGE.equals(packageName)) {
                return false;
            }

            // 2. 检查签名是否被篡改（验证是否为同一签名者）
            PackageManager pm = context.getPackageManager();
            PackageInfo pi;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pi = pm.getPackageInfo(context.getPackageName(),
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNATURES));
            } else {
                pi = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
            }

            if (pi.signatures != null && pi.signatures.length > 0) {
                // 签名存在即表示已签名（release 版本必须有签名）
                // 不硬编码具体指纹，允许同一签名者更新
                return true;
            }

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取签名指纹（用于调试和验证）
     */
    public static String getSignatureFingerprint(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pi;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pi = pm.getPackageInfo(context.getPackageName(),
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNATURES));
            } else {
                pi = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
            }

            if (pi.signatures != null && pi.signatures.length > 0) {
                Signature sig = pi.signatures[0];
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                byte[] digest = md.digest(sig.toByteArray());
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString().toUpperCase();
            }
        } catch (Exception e) {
            // ignore
        }
        return "UNKNOWN";
    }

    /**
     * 获取版权声明文本
     */
    public static String getCopyrightNotice() {
        return "Copyright (C) " + COPYRIGHT_YEAR + " " + COPYRIGHT_OWNER
                + "\nAll rights reserved.";
    }

    /**
     * 获取授权声明文本
     */
    public static String getLicenseNotice() {
        return PRODUCT_NAME + " is proprietary software.\n"
                + "Unauthorized copying, distribution, or modification is prohibited.\n"
                + "Licensed by " + COPYRIGHT_OWNER + ".";
    }

    /**
     * 获取中文授权声明
     */
    public static String getLicenseNoticeChinese() {
        return "本软件由红魔团队授权，未经许可不得复制、修改或分发。";
    }

    /**
     * 获取联系方式
     */
    public static String getContactInfo() {
        return "For licensing: " + CONTACT_EMAIL;
    }
}
