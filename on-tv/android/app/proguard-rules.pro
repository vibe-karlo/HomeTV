# Shrinking only: no renaming, so stack traces in logcat stay readable.
-dontobfuscate

# Native code and reflection call back into these by name: keep them whole.
-keep class org.conscrypt.** { *; }
-keep class io.github.muntashirakon.** { *; }
-keep class fi.iki.elonen.** { *; }
# BouncyCastle is used directly (the ADB pairing certificate); its providers look classes up by name.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }

# The page's window.TVOS bridge.
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }

-dontwarn **
