# ══════════════════════════════════════════════════════════════════════
# 🛡️ MAXIMUM SECURITY & OBFUSCATION RULES (Visa Auto-Auth)
# ══════════════════════════════════════════════════════════════════════

# 1. Multi-pass optimization & class flattening
-optimizationpasses 5
-allowaccessmodification
-mergeinterfacesaggressively
-overloadaggressively
-repackageclasses 'com.visahunter.auth.x'

# 2. Prevent decompilers from recovering original names
-dontusemixedcaseclassnames
-renamesourcefileattribute 'SourceFile'
-keepattributes SourceFile,LineNumberTable,InnerClasses,EnclosingMethod

# 3. Strip all debugging and verbose logs from release binary
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# 4. Keep only AndroidManifest entry points
-keep public class com.visahunter.auth.MainActivity {
    public void *(android.view.View);
}
-keep public class com.visahunter.auth.AlmavivaAccessibilityService

# 5. OkHttp & Okio configuration
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
