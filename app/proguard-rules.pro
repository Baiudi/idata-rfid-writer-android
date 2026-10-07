# 保留 iData UHF SDK 原生接口，避免混淆导致 NoSuchMethodError
-keep class com.idata.** { *; }
-keep class **.IdataUhfAdapter { *; }
-dontwarn com.idata.**
