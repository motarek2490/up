package com.example.util

import com.example.BuildConfig
import java.net.URI

object WorkerUrlValidator {
    // Hosts that may receive the Firebase admin ID token. Keep this list minimal: every entry can
    // read a fully-privileged token. Additional hosts come from ALLOWED_WORKER_HOSTS (.env) and
    // from the configured website base URL.
    private val DEFAULT_ALLOWED_HOSTS = setOf(
        "frida-invitations-worker.frida.workers.dev",
        "frida-invitations.com"
    )

    fun getAllowedHosts(): Set<String> {
        val fromBuildConfig = try {
            val hostsField = BuildConfig::class.java.getField("ALLOWED_WORKER_HOSTS")
            val hostsStr = hostsField.get(null) as? String
            hostsStr?.split(",")?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val fromWebsiteUrlProvider = try {
            val baseUri = URI(com.example.util.WebsiteUrlProvider.getBaseUrl())
            baseUri.host?.lowercase()?.let { listOf(it) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val fromAppConfig = com.example.util.AppConfig.defaultWorkerHost?.let { listOf(it) } ?: emptyList()
        return (DEFAULT_ALLOWED_HOSTS + fromAppConfig + fromBuildConfig + fromWebsiteUrlProvider).toSet()
    }

    /**
     * Validates that the URL uses HTTPS and belongs to an allowed host domain.
     * Throws SecurityException or IllegalArgumentException if invalid.
     */
    fun validateAndSanitize(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isBlank()) {
            throw IllegalArgumentException("رابط سيرفر Worker غير محدد")
        }
        if (!trimmed.startsWith("https://", ignoreCase = true)) {
            throw SecurityException("يجب أن يبدأ رابط Worker بـ https:// لحماية نقل توكن المصادقة الإدارية")
        }

        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            throw IllegalArgumentException("رابط Worker غير صالح: $trimmed")
        }

        // Exact hostname validation
        val host = uri.host?.lowercase() ?: throw IllegalArgumentException("رابط Worker لا يحتوي على اسم نطاق صالح")
        
        // Strict Port validation (only standard HTTPS port 443 or default -1)
        val port = uri.port
        if (port != -1 && port != 443) {
            throw SecurityException("منفذ غير مصرّح به ($port). يسمح فقط بالمنفذ الافتراضي لـ HTTPS (443).")
        }

        // Strict UserInfo validation
        if (!uri.userInfo.isNullOrBlank()) {
            throw SecurityException("رابط الخادم لا يجب أن يحتوي على معلومات مستخدم (userinfo)")
        }

        // Strict Query/Fragment validation
        if (!uri.query.isNullOrBlank() || !uri.fragment.isNullOrBlank()) {
            throw SecurityException("رابط الخادم لا يجب أن يحتوي على استعلامات أو أجزاء مضافة (query/fragment)")
        }

        val allowedHosts = getAllowedHosts()
        if (host !in allowedHosts) {
            throw SecurityException("نطاق الخادم ($host) غير مصرّح به لنقل توكن المصادقة الإدارية")
        }

        return trimmed
    }
}
