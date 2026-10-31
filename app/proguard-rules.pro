# Astra Trader R8 / ProGuard rules
-keep class com.example.data.local.** { *; }
-keep class com.example.domain.** { *; }
-keep class com.example.forecast.** { *; }
-keep interface com.example.network.BinanceV5ApiService { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-dontwarn java.lang.invoke.StringConcatFactory
