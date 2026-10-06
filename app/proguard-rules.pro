# ---------------------------------------------------------------------------
# FRIDA Admin - R8 / ProGuard rules (release builds have minification enabled)
# ---------------------------------------------------------------------------

# Readable crash stack traces (upload the mapping file to Play Console / Crashlytics).
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*
-renamesourcefileattribute SourceFile

# The app reads BuildConfig fields by reflection (GOOGLE_WEB_CLIENT_ID, CLOUDFLARE_WORKER_URL,
# ALLOWED_WORKER_HOSTS). Without this R8 may strip/rename them and the code silently falls back
# to defaults.
-keep class com.example.BuildConfig { *; }

# App Check provider factories are instantiated via Class.forName(...) in FridaApplication.
-keep class com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory { *; }
-keep class com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory { *; }

# Firebase / Play Services / Credentials / OkHttp / Coroutines ship their own consumer rules.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
