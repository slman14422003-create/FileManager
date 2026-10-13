# Keep readable stack traces in crash reports (mapping.txt is uploaded by the workflow)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Hardening: remove all debug/verbose logging and flatten package names
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
-repackageclasses ''
-allowaccessmodification

# Extra archive formats (ArcExtra is loaded by name from Arc)
-keep class com.fileman.app.ArcExtra { public static *; }
-keep class com.fileman.app.ArcExtra$* { *; }
-keep class com.fileman.app.Arc$Backend { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.junrar.**
-dontwarn org.slf4j.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
