# ─────────────────────────────────────────────────────────────────────────────
# ProGuard / R8 rules for AndroidNetworkHub
# ─────────────────────────────────────────────────────────────────────────────

# MinimalFTP — preserve all FTP server classes and interfaces.
# R8 would otherwise strip the IFileSystem interface implementations since they
# are referenced only reflectively by the MinimalFTP framework internals.
-keep class com.guichaguri.minimalftp.** { *; }
-dontwarn com.guichaguri.minimalftp.**

# ZXing QR code library
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Hilt — required to preserve generated component classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-dontwarn dagger.hilt.**

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# Preserve SAF DocumentFile class structure
-keep class androidx.documentfile.provider.** { *; }

# General Android — preserve annotated entry points
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable
