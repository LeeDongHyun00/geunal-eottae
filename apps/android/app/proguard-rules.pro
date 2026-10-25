# App-specific R8 rules. The core's JNA and UniFFI rules come from :core's consumer-rules.pro.
# A stack trace from a tester has to point at the right line (docs/parts/android.md §6).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
