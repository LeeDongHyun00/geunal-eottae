# App-specific R8 rules. The core's JNA and UniFFI rules come from :core's consumer-rules.pro.
# A stack trace from a tester has to point at the right line (docs/parts/android.md §6).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Verbose, debug and info logs never reach a release build, whatever a library or a later change logs there
# (docs/security/app-review.md §5). Warnings and errors stay and carry no content.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# The Kakao SDK's OkHttp 4.9 looks for TLS providers this app does not ship (Conscrypt, BouncyCastle, OpenJSSE) and
# falls back to the platform's when they are absent; only the references remain for R8 to find.
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
