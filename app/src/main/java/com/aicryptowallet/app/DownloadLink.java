/*
 * Copyright (C) 2026 红魔团队 (Red Devil Team)
 *
 * This software is proprietary and confidential.
 * Unauthorized copying, distribution, or modification is strictly prohibited.
 *
 * Licensed to: Authorized Users Only
 * Authorization required: Contact aibgsps@gmail.com
 */
package com.aicryptowallet.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

/**
 * GitHub 下载链接加速工具。
 *
 * 作用：
 * - 将 github.com 的 /releases/ 下载链接自动重写为 dl.redmagic.pro 加速链接，
 *   由 Cloudflare Worker 反代 GitHub Releases，显著提升国内下载速度。
 * - 打开加速链接失败时回退到 GitHub 直连，确保新旧版本用户都能正常下载。
 *
 * 说明：
 * - 仅重写 GitHub /releases/ 下载类链接，其余 URL 原样返回，不影响其他功能。
 * - 分享图二维码直接写入加速链接（扫码即走加速），打开行为则带直连回退。
 */
public final class DownloadLink {

    /** Cloudflare Worker 加速域名（dl.redmagic.pro 子域，反代 GitHub /releases/） */
    public static final String ACCEL_DOMAIN = "https://dl.redmagic.pro";

    private DownloadLink() {
    }

    /** 判断链接是否为可加速的 GitHub releases 下载链接 */
    public static boolean isAccelerable(String url) {
        return url != null
            && url.startsWith("https://github.com/")
            && url.contains("/releases/");
    }

    /** 将 GitHub 下载链接重写为加速链接；非 GitHub/非 releases 链接原样返回 */
    public static String accelerate(String githubUrl) {
        if (!isAccelerable(githubUrl)) return githubUrl;
        return ACCEL_DOMAIN + githubUrl.substring("https://github.com".length());
    }

    /** 打开加速下载链接，启动失败时自动回退到 GitHub 直连 */
    public static void open(Context ctx, String githubUrl) {
        String accelerated = accelerate(githubUrl);
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(accelerated))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            try {
                ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e2) {
                Toast.makeText(ctx, "无法打开下载页，请前往官网获取最新版", Toast.LENGTH_LONG).show();
            }
        }
    }
}
