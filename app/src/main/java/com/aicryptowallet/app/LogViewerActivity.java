package com.aicryptowallet.app;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 日志查看器 - 显示所有操作日志、错误和闪退记录
 */
public class LogViewerActivity extends BaseActivity implements View.OnClickListener {

    private LinearLayout logContainer;
    private TextView tvLogCount, tvLastUpdate;
    private TextView tabLogs, tabCrashes;
    private TextView btnExport, btnShare;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isLoading = false;
    private boolean showingCrashes = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_viewer);

        logContainer = findViewById(R.id.logContainer);
        tvLogCount = findViewById(R.id.tvLogCount);
        tvLastUpdate = findViewById(R.id.tvLastUpdate);

        // Back button
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnClearLogs).setOnClickListener(v -> showClearDialog());
        
        // Export and Share buttons
        btnExport = findViewById(R.id.btnExport);
        btnShare = findViewById(R.id.btnShare);
        btnExport.setOnClickListener(v -> showExportDialog());
        btnShare.setOnClickListener(v -> shareLogs());

        // Tab buttons
        tabLogs = findViewById(R.id.tabLogs);
        tabCrashes = findViewById(R.id.tabCrashes);
        
        tabLogs.setOnClickListener(this);
        tabCrashes.setOnClickListener(this);

        // Default to logs tab
        updateTabStyles(false);
        loadLogsAsync();
        // 清理 1 天前的过期临时日志文件
        cleanupOldTempLogs();
    }

    @Override
    public void onClick(View v) {
        if (v == tabLogs) {
            if (!showingCrashes) return; // Already on logs tab
            showingCrashes = false;
            updateTabStyles(false);
            loadLogsAsync();
        } else if (v == tabCrashes) {
            if (showingCrashes) return; // Already on crashes tab
            showingCrashes = true;
            updateTabStyles(true);
            loadCrashLogsAsync();
        }
    }

    private void updateTabStyles(boolean showingCrashes) {
        if (showingCrashes) {
            tabLogs.setTextColor(Color.parseColor("#8892b0"));
            tabLogs.setBackgroundResource(R.drawable.card_background);
            tabCrashes.setTextColor(Color.WHITE);
            tabCrashes.setBackgroundResource(R.drawable.balance_card_background);
        } else {
            tabLogs.setTextColor(Color.WHITE);
            tabLogs.setBackgroundResource(R.drawable.balance_card_background);
            tabCrashes.setTextColor(Color.parseColor("#8892b0"));
            tabCrashes.setBackgroundResource(R.drawable.card_background);
        }
    }

    private void loadLogsAsync() {
        if (isLoading) return;
        isLoading = true;
        
        new Thread(() -> {
            try {
                List<String> logs = Logger.loadLogs(LogViewerActivity.this);
                mainHandler.post(() -> {
                    displayLogs(logs);
                    isLoading = false;
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    addEmptyMessage("加载失败: " + e.getMessage());
                    isLoading = false;
                });
            }
        }).start();
    }

    private void loadCrashLogsAsync() {
        if (isLoading) return;
        isLoading = true;
        
        new Thread(() -> {
            try {
                List<String> crashes = Logger.loadCrashLogs(LogViewerActivity.this);
                mainHandler.post(() -> {
                    displayCrashLogs(crashes);
                    isLoading = false;
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    addEmptyMessage("加载失败: " + e.getMessage());
                    isLoading = false;
                });
            }
        }).start();
    }

    // 分批渲染每批条数，避免一次性创建过多 View 导致老手机卡死/闪退
    private static final int BATCH_SIZE = 200;

    private void displayLogs(List<String> logs) {
        logContainer.removeAllViews();

        // Update stats
        tvLogCount.setText(getString(R.string.text_total_logs, logs.size()));
        if (!logs.isEmpty()) {
            String firstLog = logs.get(0);
            String timestamp = firstLog.substring(0, Math.min(23, firstLog.length()));
            tvLastUpdate.setText(getString(R.string.text_last_updated, timestamp));
        } else {
            tvLastUpdate.setText(getString(R.string.text_last_updated));
        }

        // Display logs
        if (logs.isEmpty()) {
            addEmptyMessage("暂无日志记录");
        } else {
            // 分批渲染，每批渲染完成后让出主线程，防止一次渲染过多 View 卡死闪退
            renderLogBatch(logs, 0);
        }
    }

    private void renderLogBatch(final List<String> logs, final int start) {
        if (isFinishing() || isDestroyed()) return;
        int end = Math.min(start + BATCH_SIZE, logs.size());
        for (int i = start; i < end; i++) {
            try {
                addLogItem(logs.get(i));
            } catch (Exception ignored) {
                // 单条日志渲染失败不影响整个页面
            }
        }
        if (end < logs.size()) {
            final int next = end;
            mainHandler.post(() -> renderLogBatch(logs, next));
        }
    }

    private void displayCrashLogs(List<String> crashes) {
        logContainer.removeAllViews();

        tvLogCount.setText(getString(R.string.text_total_flashbacks, crashes.size()));
        tvLastUpdate.setText(getString(R.string.text_crash_record));

        if (crashes.isEmpty()) {
            addEmptyMessage("暂无闪退记录");
        } else {
            renderCrashBatch(crashes, 0);
        }
    }

    private void renderCrashBatch(final List<String> crashes, final int start) {
        if (isFinishing() || isDestroyed()) return;
        int end = Math.min(start + BATCH_SIZE, crashes.size());
        for (int i = start; i < end; i++) {
            try {
                addCrashItem(crashes.get(i));
            } catch (Exception ignored) {
                // 单条闪退记录渲染失败不影响整个页面
            }
        }
        if (end < crashes.size()) {
            final int next = end;
            mainHandler.post(() -> renderCrashBatch(crashes, next));
        }
    }

    private void addEmptyMessage(String message) {
        TextView tv = new TextView(this);
        tv.setText(message);
        tv.setTextColor(Color.parseColor("#4a4a6a"));
        tv.setTextSize(14);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setPadding(48, 48, 48, 48);
        
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        tv.setLayoutParams(params);
        logContainer.addView(tv);
    }

    private void addLogItem(String log) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(24, 20, 24, 20);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 8, 0, 8);
        item.setLayoutParams(params);
        item.setBackgroundResource(R.drawable.card_background);

        // Parse log - format: timestamp | level | module | thread | message [| EXCEPTION: ...]
        String[] parts = log.split(" \\| ", 5);
        if (parts.length < 4) return;

        String timestamp = parts[0].trim();
        String level = parts[1].trim();
        String module = parts[2].trim();
        String thread = parts.length > 3 ? parts[3].trim() : "";
        String message = parts.length > 4 ? parts[4].trim() : "";

        // Level badge
        TextView tvLevel = new TextView(this);
        tvLevel.setText(level);
        tvLevel.setTextColor(Logger.getLevelColor(level));
        tvLevel.setTextSize(10);
        tvLevel.setPadding(12, 4, 12, 4);
        tvLevel.setBackgroundResource(R.drawable.card_background);

        LinearLayout.LayoutParams levelParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        tvLevel.setLayoutParams(levelParams);

        // Module + Thread
        TextView tvModule = new TextView(this);
        String moduleText = module;
        if (!thread.isEmpty()) {
            moduleText += " (" + thread + ")";
        }
        tvModule.setText(moduleText);
        tvModule.setTextColor(Color.parseColor("#667eea"));
        tvModule.setTextSize(11);
        tvModule.setPadding(0, 8, 0, 4);

        // Message
        TextView tvMessage = new TextView(this);
        tvMessage.setText(message);
        tvMessage.setTextColor(Color.WHITE);
        tvMessage.setTextSize(13);
        tvMessage.setPadding(0, 0, 0, 4);
        tvMessage.setMovementMethod(new ScrollingMovementMethod());

        // Timestamp
        TextView tvTimestamp = new TextView(this);
        tvTimestamp.setText(timestamp);
        tvTimestamp.setTextColor(Color.parseColor("#4a4a6a"));
        tvTimestamp.setTextSize(10);

        item.addView(tvLevel);
        item.addView(tvModule);
        item.addView(tvMessage);
        item.addView(tvTimestamp);

        logContainer.addView(item);
    }

    private void addCrashItem(String crashReport) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(24, 20, 24, 20);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 12, 0, 12);
        item.setLayoutParams(params);
        item.setBackgroundResource(R.drawable.balance_card_background);

        // Title
        TextView tvTitle = new TextView(this);
        tvTitle.setText(getString(R.string.text_crash_record));
        tvTitle.setTextColor(Color.parseColor("#ff4757"));
        tvTitle.setTextSize(14);
        tvTitle.setPadding(0, 0, 0, 12);

        // Parse crash report
        String[] lines = crashReport.split("\n");
        StringBuilder content = new StringBuilder();
        boolean foundTrace = false;
        
        for (String line : lines) {
            if (line.contains("Time:")) {
                content.append("🕐 ").append(line.replace("Time: ", "")).append("\n");
            } else if (line.contains("Cause:")) {
                content.append("❌ ").append(line.replace("Cause: ", "")).append("\n");
            } else if (line.contains("Thread:")) {
                content.append("🧵 ").append(line.replace("Thread: ", "")).append("\n");
            } else if (line.contains("Device:")) {
                content.append("📱 ").append(line.replace("Device: ", "")).append("\n");
            } else if (line.contains("App Version:")) {
                content.append("📦 ").append(line.replace("App Version: ", "")).append("\n");
            } else if (line.contains("Stack Trace:")) {
                foundTrace = true;
                content.append("📋 堆栈:\n");
            } else if (foundTrace && !line.startsWith("===")) {
                // 截断过长的堆栈
                if (line.length() > 100) {
                    content.append(line.substring(0, 100)).append("...\n");
                } else {
                    content.append(line).append("\n");
                }
            }
        }

        TextView tvContent = new TextView(this);
        tvContent.setText(content.toString());
        tvContent.setTextColor(Color.parseColor("#ffffff"));
        tvContent.setTextSize(12);
        tvContent.setPadding(0, 0, 0, 0);
        tvContent.setMovementMethod(new ScrollingMovementMethod());
        tvContent.setMaxLines(15);
        tvContent.setOnClickListener(v -> {
            // 点击展开完整堆栈
            new AlertDialog.Builder(LogViewerActivity.this, R.style.AlertDialogCustom)
                .setTitle(getString(R.string.title_full_flashback_stack))
                .setMessage(crashReport)
                .setPositiveButton(getString(R.string.btn_off), null)
                .show();
        });

        item.addView(tvTitle);
        item.addView(tvContent);

        logContainer.addView(item);
    }

    private void showClearDialog() {
        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.title_clear_log))
            .setMessage(getString(R.string.msg_are_you_sure_you_3))
            .setPositiveButton(getString(R.string.label_empty_action_log), (dialog, which) -> {
                Logger.clearLogs(this);
                loadLogsAsync();
                Toast.makeText(this, getString(R.string.toast_operation_log_cleared), Toast.LENGTH_SHORT).show();
            })
            .setNeutralButton(getString(R.string.label_clear_flashback_record), (dialog, which) -> {
                Logger.clearCrashLogs(this);
                loadCrashLogsAsync();
                Toast.makeText(this, getString(R.string.toast_flashback_record_cleared), Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(getString(R.string.btn_s_decline), null)
            .show();
    }

    /**
     * 显示导出对话框
     */
    private void showExportDialog() {
        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.title_export_as_json))
            .setMessage(getString(R.string.msg_choose_export_type))
            .setPositiveButton(getString(R.string.btn_operation_log), (dialog, which) -> exportLogsToFile("operation"))
            .setNeutralButton(getString(R.string.text_crash_record), (dialog, which) -> exportLogsToFile("crash"))
            .setNegativeButton(getString(R.string.btn_s_decline), null)
            .show();
    }

    /**
     * 导出日志到文件
     */
    private void exportLogsToFile(String type) {
        List<String> logs;
        String fileName;
        
        if ("crash".equals(type)) {
            logs = Logger.loadCrashLogs(this);
            fileName = "crash_logs_" + getCurrentTimestamp() + ".txt";
        } else {
            logs = Logger.loadLogs(this);
            fileName = "operation_logs_" + getCurrentTimestamp() + ".txt";
        }
        
        if (logs.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_blog_posts_to), Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 保存到 Download 目录
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File logFile = new File(downloadDir, fileName);
        
        try {
            FileWriter fw = new FileWriter(logFile);
            fw.write("=== " + (type.equals("crash") ? "闪退记录" : "操作日志") + " ===\n");
            fw.write("导出时间：" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()) + "\n");
            fw.write("日志总数：" + logs.size() + "\n\n");
            
            for (String log : logs) {
                fw.write(log + "\n");
            }
            fw.close();
            
            Toast.makeText(this, getString(R.string.toast_exported_to_downloads, fileName), Toast.LENGTH_LONG).show();
            
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.toast_export_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 分享日志 - 以 .txt 文件形式分享
     */
    private void shareLogs() {
        List<String> logs;
        String title;

        if (showingCrashes) {
            logs = Logger.loadCrashLogs(this);
            title = "闪退记录";
        } else {
            logs = Logger.loadLogs(this);
            title = "操作日志";
        }

        if (logs.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_blog_posts_to), Toast.LENGTH_SHORT).show();
            return;
        }

        // 临时文件路径
        File tempDir = getCacheDir();
        String fileName = title + "_" + getCurrentTimestamp() + ".txt";
        File tempFile = new File(tempDir, fileName);

        try {
            // 写入临时文件
            FileWriter fw = new FileWriter(tempFile);
            fw.write("=== " + title + " ===\n");
            fw.write("导出时间：" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()) + "\n");
            fw.write("日志总数：" + logs.size() + "\n\n");

            // 写入全部日志，不截断
            for (int i = 0; i < logs.size(); i++) {
                fw.write(logs.get(i) + "\n");
            }

            fw.flush();
            fw.close();

            // 使用 FileProvider 获取 URI
            Uri fileUri = FileProvider.getUriForFile(this, "com.aicryptowallet.app.fileprovider", tempFile);

            // 创建分享 Intent
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(shareIntent, getString(R.string.str_share)));

            // 修复：之前 startActivity 后立即 delete 临时文件，
            // 但目标 App（微信/QQ/邮件）读取文件是异步的，导致分享出去是 0 字节。
            // 现在改为不立即删除，靠系统在低磁盘时自动清理 cacheDir。
            // 同时在 onCreate 时清理 1 天前的过期临时文件，避免无限累积。
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.toast_failed_to_share, e.getMessage()), Toast.LENGTH_LONG).show();
            tempFile.delete();
            e.printStackTrace();
        }
    }

    /**
     * 清理 cacheDir 中超过 1 天的过期临时日志文件
     * 在 onCreate 时调用，避免临时文件无限累积
     */
    private void cleanupOldTempLogs() {
        File tempDir = getCacheDir();
        File[] files = tempDir.listFiles();
        if (files == null) return;
        long oneDayMs = 24L * 60 * 60 * 1000;
        long cutoff = System.currentTimeMillis() - oneDayMs;
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".txt") && f.lastModified() < cutoff) {
                f.delete();
            }
        }
    }

    /**
     * 获取当前时间戳
     */
    private String getCurrentTimestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date());
    }
}