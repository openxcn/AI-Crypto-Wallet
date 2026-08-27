package com.aicryptowallet.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.View;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 应用日志记录器 - 使用文件存储，更可靠
 * 优化：所有文件 I/O 异步执行，避免主线程 ANR；行数限制周期性触发
 */
public class Logger {
    private static final String TAG = "AICryptoWallet";
    private static String LOG_FILE = "app_logs.txt";
    private static String CRASH_FILE = "crash_logs.txt";
    private static final int MAX_LOG_LINES = 10000;
    // 日志查看器最多加载的日志行数（防止一次性渲染过多 View 导致 OOM/卡死闪退）
    private static final int MAX_LOAD_LINES = 3000;
    // 单条日志最大长度，防止超长内容（如 AI 参数/回复全文）撑爆日志文件
    private static final int MAX_LINE_LENGTH = 3000;
    // 每 N 条日志才检查一次行数限制，避免每条日志都重写整个文件
    private static final int LIMIT_CHECK_INTERVAL = 500;
    // ===== 分段日志配置 =====
    // 每段最大行数：达到后自动切到新段文件，避免单个日志文件无限变大
    private static final int MAX_SEGMENT_LINES = 1000;
    // 最多保留的日志段数量（总日志约 6000 行），超出后滚动删除最旧段
    private static final int MAX_SEGMENTS = 6;
    // 段号索引持久化（按文件名存储当前段号）
    private static final String SEG_PREFS = "logger_segments";
    private static final String SEG_KEY_PREFIX = "seg_";

    public static final String LEVEL_INFO = "INFO";
    public static final String LEVEL_SUCCESS = "SUCCESS";
    public static final String LEVEL_ERROR = "ERROR";
    public static final String LEVEL_NETWORK = "NETWORK";
    public static final String LEVEL_WARNING = "WARNING";
    public static final String LEVEL_CRASH = "CRASH";
    public static final String LEVEL_ACTION = "ACTION";
    public static final String LEVEL_SYSTEM = "SYSTEM";

    private static Context appContext;
    // 单线程异步执行器，所有文件写入都投递到这里，避免阻塞调用线程
    private static final ExecutorService logExecutor = Executors.newSingleThreadExecutor();
    // 自增计数器，用于触发周期性 limitLines
    private static final AtomicInteger logCounter = new AtomicInteger(0);

    /**
     * 初始化全局日志器
     */
    public static void init(Context context) {
        appContext = context.getApplicationContext();

        // 日志文件名加上版本号，方便区分不同版本的问题
        try {
            String versionName = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0).versionName;
            LOG_FILE = "app_logs_v" + versionName + ".txt";
            CRASH_FILE = "crash_logs_v" + versionName + ".txt";
        } catch (Exception e) {
            // 获取版本号失败则用默认文件名
        }

        // 设置全局异常处理器
        // 修复：setDefaultUncaughtExceptionHandler 后，getDefaultUncaughtExceptionHandler() 返回的就是当前 handler
        // 在 finally 中再调用会自我递归导致 StackOverflowError，覆盖原始崩溃信息
        // 必须先保存原 handler 引用，在 finally 中委托给原 handler
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    recordCrash("UNCAUGHT_EXCEPTION", t.getName(), e);
                } finally {
                    previous.uncaughtException(t, e);
                }
            }
        });

        system(null, "系统启动", "Logger 初始化完成");
    }

    /**
     * 记录日志（统一入口）
     */
    public static void log(Context ctx, String level, String module, String message, Throwable throwable) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
            .format(new Date());
        String threadName = android.os.Process.myTid() + ":" + Thread.currentThread().getName();

        // 限制单条消息长度，防止超长内容（如 AI 参数/回复全文）导致日志文件暴涨
        if (message != null && message.length() > MAX_LINE_LENGTH) {
            message = message.substring(0, MAX_LINE_LENGTH) + "...(日志过长已截断)";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(timestamp).append(" | ").append(level).append(" | ");
        sb.append(module).append(" | ").append(threadName).append(" | ");
        sb.append(message);

        if (throwable != null) {
            sb.append(" | EXCEPTION: ").append(getStackTrace(throwable));
        }

        String logEntry = sb.toString();

        // 异步分段保存到文件（不阻塞调用线程）
        saveToFileSegmented(LOG_FILE, logEntry);

        // 输出到 Logcat（同步，开销小）
        if (level.equals(LEVEL_ERROR) || level.equals(LEVEL_CRASH)) {
            android.util.Log.e(TAG, logEntry);
        } else if (level.equals(LEVEL_WARNING)) {
            android.util.Log.w(TAG, logEntry);
        } else {
            android.util.Log.d(TAG, logEntry);
        }
    }

    /**
     * 记录闪退堆栈
     */
    public static void recordCrash(String cause, String threadName, Throwable throwable) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
            .format(new Date());

        StringBuilder sb = new StringBuilder();
        sb.append("=== CRASH REPORT ===\n");
        sb.append("Time: ").append(timestamp).append("\n");
        sb.append("Cause: ").append(cause).append("\n");
        sb.append("Thread: ").append(threadName).append("\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
          .append(" (Android ").append(Build.VERSION.RELEASE).append(")\n");
        sb.append("App Version: ").append(getAppVersion()).append("\n");
        sb.append("\nStack Trace:\n");
        sb.append(getStackTrace(throwable));
        sb.append("\n=== END CRASH REPORT ===");

        String crashReport = sb.toString();

        // 1. 异步保存到内部文件
        saveToFile(CRASH_FILE, crashReport);

        // 2. 同步写入外部存储（确保 App 立即退出时也能查看）
        try {
            if (appContext != null) {
                File extDir = appContext.getExternalFilesDir(null);
                if (extDir != null) {
                    File crashFile = new File(extDir, "crash_log_v" + (LOG_FILE.contains("_v") ? LOG_FILE.substring(LOG_FILE.indexOf("_v") + 2, LOG_FILE.indexOf(".txt")) : "unknown") + ".txt");
                    FileWriter fw = new FileWriter(crashFile, true);
                    fw.write(crashReport + "\n\n");
                    fw.flush();
                    fw.close();
                }
            }
        } catch (Exception ignored) {}

        // 3. 输出到 Logcat
        android.util.Log.e(TAG, crashReport);
    }

    /**
     * 异步保存到文件（投递到单线程 executor）
     */
    private static void saveToFile(String fileName, String entry) {
        final Context ctx = appContext;
        if (ctx == null) {
            // 极少数情况下 init 未调用，直接输出到 Logcat
            android.util.Log.d(TAG, "[no-init] " + entry);
            return;
        }
        logExecutor.execute(() -> {
            FileWriter fw = null;
            try {
                File dir = ctx.getFilesDir();
                File file = new File(dir, fileName);

                // 确保文件存在
                if (!file.exists()) {
                    file.createNewFile();
                }

                // 追加写入（try-with-resources 风格，finally 关闭）
                fw = new FileWriter(file, true);
                fw.write(entry + "\n");
                fw.flush();

                // 周期性检查行数限制（每 LIMIT_CHECK_INTERVAL 条检查一次）
                int count = logCounter.incrementAndGet();
                if (count % LIMIT_CHECK_INTERVAL == 0) {
                    limitLines(fileName, MAX_LOG_LINES);
                }
            } catch (Exception e) {
                android.util.Log.e(TAG, "日志保存失败: " + fileName, e);
            } finally {
                if (fw != null) {
                    try { fw.close(); } catch (Exception ignored) {}
                }
            }
        });
    }

    /**
     * 异步分段保存到文件（投递到单线程 executor）
     * 日志按段拆分：每段最多 MAX_SEGMENT_LINES 行，达到上限自动切到新段，
     * 最多保留 MAX_SEGMENTS 个段，超出后滚动删除最旧段，保证单文件不会无限变大。
     */
    private static void saveToFileSegmented(String baseName, String entry) {
        final Context ctx = appContext;
        if (ctx == null) {
            android.util.Log.d(TAG, "[no-init] " + entry);
            return;
        }
        logExecutor.execute(() -> {
            FileWriter fw = null;
            try {
                int seg = getSegmentIndex(baseName);
                File file = segmentFile(baseName, seg);

                // 首次使用当前段时校准行数（崩溃重启后也能准确切段）
                AtomicInteger cnt = segCount(baseName);
                if (!calibrated.contains(baseName)) {
                    cnt.set(countLines(file));
                    calibrated.add(baseName);
                }

                // 当前段已满则切到新段
                if (cnt.get() >= MAX_SEGMENT_LINES) {
                    seg = rollSegment(baseName, seg);
                    file = segmentFile(baseName, seg);
                    cnt.set(countLines(file));
                }

                if (!file.exists()) {
                    file.createNewFile();
                }
                fw = new FileWriter(file, true);
                fw.write(entry + "\n");
                fw.flush();
                cnt.incrementAndGet();
            } catch (Exception e) {
                android.util.Log.e(TAG, "日志保存失败: " + baseName, e);
            } finally {
                if (fw != null) {
                    try { fw.close(); } catch (Exception ignored) {}
                }
            }
        });
    }

    /**
     * 切到下一个日志段（仅在 logExecutor 线程执行）
     * 段号 +1 并持久化，同时删除过期的最旧段文件
     */
    private static int rollSegment(String baseName, int currentSeg) {
        int next = currentSeg + 1;
        setSegmentIndex(baseName, next);
        // 删除超出保留范围的最旧段
        int oldestKeep = next - MAX_SEGMENTS + 1;
        for (int i = 0; i < oldestKeep; i++) {
            File f = segmentFile(baseName, i);
            if (f.exists()) {
                f.delete();
            }
        }
        return next;
    }

    /**
     * 统计文件非空行数（切段/校准时使用）
     */
    private static int countLines(File file) {
        if (file == null || !file.exists()) return 0;
        int n = 0;
        try (java.io.BufferedReader br = new java.io.BufferedReader(
            new java.io.FileReader(file))) {
            while (br.readLine() != null) {
                n++;
            }
        } catch (Exception ignored) {}
        return n;
    }

    /**
     * 段号索引的 SharedPreferences
     */
    private static SharedPreferences getSegPrefs() {
        if (appContext == null) return null;
        return appContext.getSharedPreferences(SEG_PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 获取当前段号（默认 0）
     */
    private static int getSegmentIndex(String baseName) {
        SharedPreferences prefs = getSegPrefs();
        if (prefs == null) return 0;
        return prefs.getInt(SEG_KEY_PREFIX + baseName, 0);
    }

    /**
     * 保存当前段号
     */
    private static void setSegmentIndex(String baseName, int idx) {
        SharedPreferences prefs = getSegPrefs();
        if (prefs == null) return;
        prefs.edit().putInt(SEG_KEY_PREFIX + baseName, idx).apply();
    }

    /**
     * 获取某段对应的文件
     * 段文件命名：{baseName}.{seg}，例如 app_logs_v3.0.15.txt.0
     */
    private static File segmentFile(String baseName, int seg) {
        return new File(appContext.getFilesDir(), baseName + "." + seg);
    }

    // 每个日志文件当前段已写行数的内存计数器（仅 logExecutor 线程访问）
    private static final java.util.Map<String, AtomicInteger> segCounts =
        new java.util.concurrent.ConcurrentHashMap<>();
    // 标记某日志文件当前段行数是否已校准
    private static final java.util.Set<String> calibrated =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 获取日志文件的行数计数器（延迟创建）
     */
    private static AtomicInteger segCount(String baseName) {
        AtomicInteger c = segCounts.get(baseName);
        if (c == null) {
            c = new AtomicInteger(0);
            segCounts.put(baseName, c);
        }
        return c;
    }

    /**
     * 限制文件行数（仅在 logExecutor 线程执行）
     */
    private static void limitLines(String fileName, int maxLines) {
        try {
            File dir = appContext.getFilesDir();
            File file = new File(dir, fileName);
            if (!file.exists()) return;

            List<String> lines = new ArrayList<>();
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.FileReader(file))) {
                String line;
                while ((line = br.readLine()) != null) {
                    lines.add(line);
                }
            }

            // 保留最新 maxLines 行
            if (lines.size() > maxLines) {
                lines = new ArrayList<>(lines.subList(lines.size() - maxLines, lines.size()));
                // 重写文件
                try (FileWriter fw = new FileWriter(file)) {
                    for (String l : lines) {
                        fw.write(l + "\n");
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * 加载所有日志
     */
    public static List<String> loadLogs(Context ctx) {
        return loadFromFile(LOG_FILE);
    }

    /**
     * 加载闪退日志
     */
    public static List<String> loadCrashLogs(Context ctx) {
        return loadFromFile(CRASH_FILE);
    }

    /**
     * 从文件加载
     * 按段号 0→当前段（旧→新）合并所有段文件，
     * 只保留最近 MAX_LOAD_LINES 行，且单行超过 MAX_LINE_LENGTH 截断，
     * 防止日志文件过大时一次性读入内存导致 OOM 或渲染过多 View 闪退。
     * 若段文件均不存在，则兼容读取旧版单文件。
     */
    private static List<String> loadFromFile(String baseName) {
        List<String> logs = new ArrayList<>();
        try {
            File dir = appContext.getFilesDir();
            java.util.ArrayDeque<String> deque = new java.util.ArrayDeque<>();
            int currentSeg = getSegmentIndex(baseName);
            boolean foundAny = false;

            for (int seg = 0; seg <= currentSeg; seg++) {
                File file = new File(dir, baseName + "." + seg);
                if (!file.exists()) continue;
                foundAny = true;
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.FileReader(file))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.trim().isEmpty()) continue;
                        if (line.length() > MAX_LINE_LENGTH) {
                            line = line.substring(0, MAX_LINE_LENGTH) + "...(截断)";
                        }
                        deque.addLast(line);
                        if (deque.size() > MAX_LOAD_LINES) {
                            deque.removeFirst();
                        }
                    }
                }
            }

            // 兼容旧版单文件（段文件不存在时读取原始文件）
            if (!foundAny) {
                File legacy = new File(dir, baseName);
                if (legacy.exists()) {
                    try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.FileReader(legacy))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (line.trim().isEmpty()) continue;
                            if (line.length() > MAX_LINE_LENGTH) {
                                line = line.substring(0, MAX_LINE_LENGTH) + "...(截断)";
                            }
                            deque.addLast(line);
                            if (deque.size() > MAX_LOAD_LINES) {
                                deque.removeFirst();
                            }
                        }
                    }
                }
            }

            logs.addAll(deque);
        } catch (Exception e) {
            android.util.Log.e(TAG, "日志加载失败", e);
        }
        return logs;
    }

    /**
     * 清空日志文件
     */
    public static void clearLogs(Context ctx) {
        // 修复：投递到 logExecutor 与写入串行化，避免主线程 I/O ANR
        // 同时避免与 saveToFile 写线程竞态导致清空后新日志写入已删除的旧 inode 而丢失
        logExecutor.execute(() -> clearFileSafe(LOG_FILE));
    }

    /**
     * 清空闪退日志
     */
    public static void clearCrashLogs(Context ctx) {
        logExecutor.execute(() -> clearFileSafe(CRASH_FILE));
    }

    /**
     * 从主日志中删除指定模块的所有记录（保留其他模块日志）。
     * 行格式：timestamp | level | module | thread | message
     */
    public static void removeModuleRecords(Context ctx, final String module) {
        final Context appCtx = ctx != null ? ctx.getApplicationContext() : null;
        if (appCtx == null) return;
        logExecutor.execute(() -> {
            try {
                File dir = appCtx.getFilesDir();
                int currentSeg = getSegmentIndex(LOG_FILE);
                for (int seg = 0; seg <= currentSeg; seg++) {
                    File file = new File(dir, LOG_FILE + "." + seg);
                    if (!file.exists()) continue;
                    List<String> lines = new ArrayList<>();
                    try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(file))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (line.trim().isEmpty()) continue;
                            if (line.contains(" | " + module + " | ")) continue;
                            lines.add(line);
                        }
                    }
                    try (FileWriter fw = new FileWriter(file)) {
                        for (String l : lines) {
                            fw.write(l + "\n");
                        }
                    }
                }
                // 行数已变，重置校准保证后续切段准确
                calibrated.remove(LOG_FILE);
                android.util.Log.d(TAG, "已删除模块日志: " + module);
            } catch (Exception e) {
                android.util.Log.e(TAG, "删除模块日志失败: " + module, e);
            }
        });
    }

    /**
     * 安全清空文件（在 logExecutor 线程执行，与写入串行化）
     * 删除主文件及所有分段文件，并重置段号索引
     */
    private static void clearFileSafe(String baseName) {
        try {
            File dir = appContext.getFilesDir();
            // 删除主文件（兼容旧单文件）
            File file = new File(dir, baseName);
            if (file.exists()) {
                file.delete();
            }
            // 删除所有分段文件
            int currentSeg = getSegmentIndex(baseName);
            for (int seg = 0; seg <= currentSeg; seg++) {
                File f = new File(dir, baseName + "." + seg);
                if (f.exists()) {
                    f.delete();
                }
            }
            // 重置段号索引和计数器
            setSegmentIndex(baseName, 0);
            segCounts.remove(baseName);
            calibrated.remove(baseName);
            logCounter.set(0);

            // 重新创建空主文件（保证后续读取兼容）
            file.createNewFile();

            android.util.Log.d(TAG, "日志已清空: " + baseName);
        } catch (Exception e) {
            android.util.Log.e(TAG, "清空日志失败: " + baseName, e);
        }
    }

    // ========== 便捷方法 ==========

    public static void action(Context ctx, String module, String action, String detail) {
        String message = action;
        if (detail != null && !detail.isEmpty()) {
            message += " - " + detail;
        }
        log(ctx, LEVEL_ACTION, module, message, null);
    }

    public static void system(Context ctx, String event, String detail) {
        log(ctx, LEVEL_SYSTEM, "系统", event + (detail != null ? " - " + detail : ""), null);
    }

    public static void info(Context ctx, String module, String message) {
        log(ctx, LEVEL_INFO, module, message, null);
    }

    public static void success(Context ctx, String module, String message) {
        log(ctx, LEVEL_SUCCESS, module, message, null);
    }

    public static void error(Context ctx, String module, String message) {
        log(ctx, LEVEL_ERROR, module, message, null);
    }

    public static void error(Context ctx, String module, String message, Throwable throwable) {
        log(ctx, LEVEL_ERROR, module, message, throwable);
    }

    public static void network(Context ctx, String module, String message) {
        log(ctx, LEVEL_NETWORK, module, message, null);
    }

    public static void warning(Context ctx, String module, String message) {
        log(ctx, LEVEL_WARNING, module, message, null);
    }

    public static void networkConnect(Context ctx, String chain, String rpcUrl, boolean success) {
        String chainName = getChainChineseName(chain);
        String status = success ? "连接成功" : "连接失败";
        log(ctx, LEVEL_NETWORK, "网络连接", chainName + " - " + rpcUrl + " - " + status, null);
    }

    public static void apiCall(Context ctx, String apiName, String url, boolean success, String result) {
        String status = success ? "成功" : "失败";
        String message = apiName + " - " + url + " - " + status;
        if (result != null && !result.isEmpty()) {
            message += " - " + result;
        }
        log(ctx, LEVEL_NETWORK, "API 调用", message, null);
    }

    public static void trade(Context ctx, String action, String chain, String detail, boolean success) {
        String chainName = getChainChineseName(chain);
        String status = success ? "成功" : "失败";
        log(ctx, success ? LEVEL_SUCCESS : LEVEL_ERROR, "交易",
            chainName + " - " + action + " - " + detail + " - " + status, null);
    }

    public static void wallet(Context ctx, String action, String detail, boolean success) {
        String status = success ? "成功" : "失败";
        log(ctx, success ? LEVEL_SUCCESS : LEVEL_ERROR, "钱包", action + " - " + detail + " - " + status, null);
    }

    public static void aiAnalysis(Context ctx, String chain, String signal, String reason) {
        String chainName = getChainChineseName(chain);
        log(ctx, LEVEL_INFO, "AI 分析", chainName + " - 信号：" + signal + " - " + reason, null);
    }

    public static String getChainChineseName(String chainCode) {
        if (chainCode == null) return "未知链";
        switch (chainCode) {
            case "ETH": return "以太坊";
            case "BNB": return "币安链";
            case "SOL": return "索拉纳";
            case "TRX": return "波场";
            case "AVAX": return "雪崩链";
            case "SUI": return "Sui 链";
            case "APT": return "阿ptos 链";
            case "ADA": return "卡尔达诺";
            case "MATIC": return "Polygon";
            case "NEAR": return "NEAR 协议";
            case "FTM": return " Fantom";
            case "ATOM": return "Cosmos";
            case "DOT": return "波卡";
            case "GLMR": return "Moonbeam";
            case "KAVA": return "Kava 链";
            case "ALGO": return "Algorand";
            case "ICP": return "互联网计算机";
            case "CELO": return "Celo 链";
            case "XTZ": return "Tezos";
            case "ONE": return "Harmony";
            default: return chainCode;
        }
    }

    public static int getLevelColor(String level) {
        switch (level) {
            case LEVEL_SUCCESS: return 0xFF00d084;
            case LEVEL_ERROR: return 0xFFff4757;
            case LEVEL_CRASH: return 0xFFff0000;
            case LEVEL_NETWORK: return 0xFF667eea;
            case LEVEL_WARNING: return 0xFFffa502;
            case LEVEL_ACTION: return 0xFF1e90ff;
            case LEVEL_SYSTEM: return 0xFF9370db;
            default: return 0xFF8892b0;
        }
    }

    private static String getAppVersion() {
        try {
            if (appContext != null) {
                android.content.pm.PackageManager pm = appContext.getPackageManager();
                android.content.pm.PackageInfo pi = pm.getPackageInfo(appContext.getPackageName(), 0);
                return pi.versionName + " (v" + pi.versionCode + ")";
            }
        } catch (Exception e) {
            // ignore
        }
        return "unknown";
    }

    // ========== 用户操作审计包装方法 ==========

    /**
     * 包装 View.OnClickListener，自动记录用户点击操作
     * 用法：btn.setOnClickListener(Logger.wrapClick(this, "首页", "点击资产Tab", originalListener))
     */
    public static View.OnClickListener wrapClick(Context ctx, String module, String action, View.OnClickListener original) {
        return v -> {
            action(ctx, module, "👆 " + action, null);
            if (original != null) {
                try {
                    original.onClick(v);
                } catch (Exception e) {
                    action(ctx, module, "❌ " + action + " 失败", e.getMessage());
                    throw e;
                }
            }
        };
    }

    /**
     * 包装 View.OnClickListener，自动记录点击操作 + 操作结果
     * 用法：btn.setOnClickListener(Logger.wrapClickWithResult(this, "首页", "切换钱包", v -> {
     *     doSomething();
     *     Logger.actionResult(this, "首页", "切换钱包", "成功: 钱包A");
     * }));
     */
    public static View.OnClickListener wrapClickWithResult(Context ctx, String module, String action, View.OnClickListener original) {
        return v -> {
            action(ctx, module, "👆 " + action, null);
            if (original != null) {
                try {
                    original.onClick(v);
                } catch (Exception e) {
                    action(ctx, module, "❌ " + action + " 异常", e.getMessage());
                    throw e;
                }
            }
        };
    }

    /**
     * 记录操作结果反馈（在操作完成后调用）
     */
    public static void actionResult(Context ctx, String module, String action, String result) {
        log(ctx, LEVEL_ACTION, module, "  ↳ " + action + " → " + result, null);
    }

    /**
     * 包装 Runnable，自动记录操作及结果
     */
    public static Runnable wrapRun(Context ctx, String module, String action, Runnable original) {
        return () -> {
            action(ctx, module, "👆 " + action, null);
            if (original != null) {
                try {
                    original.run();
                } catch (Exception e) {
                    action(ctx, module, "❌ " + action + " 异常", e.getMessage());
                    throw e;
                }
            }
        };
    }

    public static String getStackTrace(Throwable throwable) {
        if (throwable == null) return "";
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        String stack = sw.toString();
        if (stack.length() > 2000) {
            stack = stack.substring(0, 2000) + "...(truncated)";
        }
        return stack;
    }
}
