# ==============================================================================
# AI Crypto Wallet - ProGuard / R8 强混淆规则（闭源核心）
# ==============================================================================

# ------------------------------------------------------------------------------
# 1. 通用规则
# ------------------------------------------------------------------------------
-dontoptimize
-dontpreverify

# 忽略警告
-dontwarn

# ------------------------------------------------------------------------------
# 2. 移除所有调试/日志代码（闭源核心）
# ------------------------------------------------------------------------------

# 移除 android.util.Log 调用
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# 移除项目自定义 Logger 调用
-assumenosideeffects class com.aicryptowallet.app.Logger {
    public static void log(...);
    public static void info(...);
    public static void debug(...);
    public static void warning(...);
    public static void error(...);
    public static void success(...);
    public static void network(...);
    public static void action(...);
    public static void system(...);
    public static void trade(...);
    public static void wallet(...);
    public static void aiAnalysis(...);
    public static void recordCrash(...);
    public static void actionResult(...);
}

# 移除 System.out.println 和 printStackTrace
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
}

# ------------------------------------------------------------------------------
# 3. 保留 Android 核心组件
# ------------------------------------------------------------------------------

# 保留所有 Activity
-keep class * extends android.app.Activity { *; }
-keep class * extends android.app.ActionBarActivity { *; }
-keep class * extends android.support.v7.app.ActionBarActivity { *; }

# 保留所有 Service、BroadcastReceiver、ContentProvider
-keep class * extends android.app.Service { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.content.ContentProvider { *; }

# 保留 Application
-keep class * extends android.app.Application { *; }

# 保留 Fragment
-keep class * extends android.app.Fragment { *; }
-keep class * extends android.support.v4.app.Fragment { *; }
-keep class * extends androidx.fragment.app.Fragment { *; }

# 保留 View 相关
-keep class * extends android.view.View { public <init>(android.content.Context); }
-keep class * extends android.view.View { public <init>(android.content.Context, android.util.AttributeSet); }
-keep class * extends android.view.View { public <init>(android.content.Context, android.util.AttributeSet, int); }
-keepclassmembers class * extends android.view.View {
    public void set*(...);
    public * get*(...);
}

# 保留自定义 View 的 setOnClickListener 等
-keepclassmembers class * {
    public void setOnClickListener(...);
    public void setOnLongClickListener(...);
    public void setText(java.lang.CharSequence);
}

# 保留 WebView JavaScript 接口
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ------------------------------------------------------------------------------
# 4. 保留核心加密和钱包库
# ------------------------------------------------------------------------------

# Trust Wallet Core JNI
-keep class wallet.core.jni.** { *; }
-keep class wallet.core.java.** { *; }
-keepclassmembers class wallet.core.jni.** { *; }

# Protobuf 生成的类
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite$* { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { *; }

# web3j - 以太坊交互库
-keep class org.web3j.** { *; }
-keepclassmembers class org.web3j.** { *; }

# Bouncy Castle - 加密库
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# bitcoinj - 比特币库
-keep class org.bitcoinj.** { *; }
-dontwarn org.bitcoinj.**

# ------------------------------------------------------------------------------
# 5. 保留反射相关（JSON 解析等）
# ------------------------------------------------------------------------------

# JSON 相关
-keep class org.json.** { *; }
-keep class org.json.JSONObject { *; }
-keep class org.json.JSONArray { *; }

# 保留 Bean 类的 getter/setter（JSON 序列化需要）
-keepclassmembers class com.aicryptowallet.app.CoinInfo {
    public <init>(...);
    public * get*();
    public void set*(...);
}
-keepclassmembers class com.aicryptowallet.app.TxRecord {
    public <init>(...);
    public * get*();
    public void set*(...);
}
-keepclassmembers class com.aicryptowallet.app.TradeRecord {
    public <init>(...);
    public * get*();
    public void set*(...);
}

# ------------------------------------------------------------------------------
# 6. OkHttp / Retrofit 保留
# ------------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ------------------------------------------------------------------------------
# 7. 保留 R 资源类
# ------------------------------------------------------------------------------
-keep class **.R { *; }
-keep class **.R$* { *; }

# ------------------------------------------------------------------------------
# 8. 保留 Serializable
# ------------------------------------------------------------------------------
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ------------------------------------------------------------------------------
# 9. 枚举保留
# ------------------------------------------------------------------------------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ------------------------------------------------------------------------------
# 10. 反射调用相关保留
# ------------------------------------------------------------------------------
-keepclassmembers class * {
    @android.annotation.Keep *;
}

# 保留可能被反射调用的方法
-keep class com.aicryptowallet.app.** {
    public *;
}

# ------------------------------------------------------------------------------
# 11. 版权与授权保护（闭源核心）
# ------------------------------------------------------------------------------

# 保留 LicenseManager 类，确保版权和授权信息不被混淆
-keep class com.aicryptowallet.app.LicenseManager { *; }
-keepclassmembers class com.aicryptowallet.app.LicenseManager { *; }

# 保留版权字符串常量
-keepclassmembers class com.aicryptowallet.app.LicenseManager {
    public static final java.lang.String COPYRIGHT_OWNER;
    public static final java.lang.String COPYRIGHT_YEAR;
    public static final java.lang.String PRODUCT_NAME;
    public static final java.lang.String LICENSE_TYPE;
    public static final java.lang.String CONTACT_EMAIL;
    public static final java.lang.String EXPECTED_PACKAGE;
}

# 保留 CryptoWalletApplication 的授权验证方法
-keepclassmembers class com.aicryptowallet.app.CryptoWalletApplication {
    private void verifyAndLogLicense();
}
