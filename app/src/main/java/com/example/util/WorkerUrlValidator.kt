package com.example.util

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import java.net.URI

object WorkerUrlValidator {
    // Hosts that may receive the Firebase admin ID token. Keep this list minimal: every entry can
    // read a fully-privileged token. Additional hosts come from ALLOWED_WORKER_HOSTS (.env) only.
    private val DEFAULT_ALLOWED_HOSTS = setOf(
        "frida-invitations-worker.frida.workers.dev",
        "frida-invitations.com"
    )

    fun getAllowedHosts(context: Context? = null): Set<String> {
        // NOTE: the website base URL stored in Firestore (settings/admin_config) is intentionally NOT
        // trusted here. Only build-time values (.env) may receive the admin ID token, otherwise a
        // mistyped or tampered Firestore value would leak a fully-privileged token.
        val fromBuildConfig = try {
            val field = BuildConfig::class.java.getField("ALLOWED_WORKER_HOSTS")
            val hostsStr = field.get(null) as? String ?: ""
            hostsStr.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
        val fromAppConfig = AppConfig.defaultWorkerHost?.let { listOf(it) } ?: emptyList()
        return (DEFAULT_ALLOWED_HOSTS + fromAppConfig + fromBuildConfig).toSet()
    }

    /**
     * Validates that the target URL uses HTTPS and belongs to an allowed host before transmitting
     * the admin ID token.
     * Throws SecurityException if validation fails.
     */
    fun validateAndSanitize(urlStr: String, context: Context? = null): String {
        val trimmed = urlStr.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("رابط الخادم لا يمكن أن يكون فارغاً")
        }

        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            throw IllegalArgumentException("صيغة رابط الخادم غير صالحة: ${e.message}")
        }

        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()

        // 1. Strict Protocol Check (Must be HTTPS)
        if (scheme != "https") {
            throw SecurityException("بروتوكول غير آمن ($scheme). يجب استخدام https حصراً لحماية بيانات الاعتماد.")
        }

        // 2. Reject custom ports (only default HTTPS 443 / -1)
        if (uri.port != -1 && uri.port != 443) {
            throw SecurityException("غير مسموح باستخدام منافذ مخصصة (${uri.port}).")
        }

        // 3. Reject credentials in URI
        if (!uri.userInfo.isNullOrBlank()) {
            throw SecurityException("غير مسموح بإرفاق بيانات اعتماد في الرابط.")
        }

        // 4. Reject query parameters in base URL
        if (!uri.query.isNullOrBlank()) {
            throw SecurityException("غير مسموح بإرفاق معلمات استعلام في الرابط الأساسي.")
        }

        // 5. Reject URI fragments in base URL
        if (!uri.fragment.isNullOrBlank()) {
            throw SecurityException("غير مسموح بإرفاق أجزاء (fragment) في الرابط الأساسي.")
        }

        if (host.isNullOrBlank()) {
            throw SecurityException("نطاق الخادم مفقود في الرابط")
        }

        // 6. Strict Host Allowlist Check
        val allowed = getAllowedHosts(context)
        if (!allowed.contains(host)) {
            try {
                Log.e("WorkerUrlValidator", "Blocked request to untrusted host: $host. Allowed: $allowed")
            } catch (ignored: Throwable) {}
            throw SecurityException("نطاق الخادم ($host) غير مصرّح به لنقل توكن المصادقة الإدارية. يرجى مراجعة إعدادات الأمان.")
        }

        // Return sanitized base without trailing slashes
        return trimmed.trimEnd('/')
    }
}
