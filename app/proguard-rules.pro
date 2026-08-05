# Trust Wallet Core JNI - must not be obfuscated
-keep class wallet.core.jni.** { *; }
-keep class wallet.core.java.** { *; }

# Protobuf generated classes
# 修复：之前 -keepclassmembers 仅保留成员，-keep 仅保留类名不保留成员
# GeneratedMessageLite.Builder 不继承 GeneratedMessageLite，其子类未被保留
# release 构建中 newBuilder() 及链式 setter 可能被混淆，导致签名调用崩溃
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite$* { *; }

# web3j - FunctionEncoder 通过反射获取 TypeReference 的泛型类型
# 修复：release 构建可能混淆 web3j 类导致 ABI 编码异常
-keep class org.web3j.** { *; }
-keepclassmembers class org.web3j.** { *; }

# slf4j - 忽略缺失的 StaticLoggerBinder（okhttp 内部日志依赖，运行时不使用）
-dontwarn org.slf4j.**
-keep class org.slf4j.** { *; }
