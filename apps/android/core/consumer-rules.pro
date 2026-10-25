# R8 rules every app that depends on :core inherits (docs/parts/android.md §6).

# UniFFI's generated code looks the Rust library's functions up by name through a JNA Library interface, maps its
# structures by field order and hands callback interfaces (SecretStore, CoreListener) to Rust as JNA callbacks. All of
# that is reflection, so nothing in the generated package may be renamed or removed.
-keep class app.geunal.core.** { *; }

# JNA itself resolves its native glue and the user's interfaces reflectively.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
# JNA references AWT and platform classes that Android does not have; they are never reached.
-dontwarn java.awt.**
-dontwarn com.sun.jna.platform.**
