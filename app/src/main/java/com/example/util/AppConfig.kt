package com.example.util

import com.example.BuildConfig
import java.net.URI

/**
 * Single source of truth for build-time configuration (values come from `.env` / `.env.example`
 * via the Secrets Gradle Plugin). Keeping the default Worker URL here avoids the same literal
 * being duplicated (and drifting apart) across models, repositories, view models and UI.
 */
object AppConfig {
    /** Used only if BuildConfig has no usable CLOUDFLARE_WORKER_URL. */
    private const val FALLBACK_WORKER_URL = "https://frida-invitations-worker.frida.workers.dev"

    /** Default Cloudflare Worker base URL (HTTPS, no trailing slash). */
    val defaultWorkerUrl: String
        get() {
            val fromBuild = readBuildConfigString("CLOUDFLARE_WORKER_URL")
                ?.trim()
                ?.trimEnd('/')
                ?.takeIf { it.startsWith("https://", ignoreCase = true) }
            return fromBuild ?: FALLBACK_WORKER_URL
        }

    /**
     * Public website base URL (HTTPS, no trailing slash) used to build guest / host-portal links.
     * Comes from WEBSITE_BASE_URL in .env; can be overridden at runtime from Settings
     * (stored in settings/admin_config.websiteBaseUrl).
     */
    val defaultWebsiteUrl: String
        get() = readBuildConfigString("WEBSITE_BASE_URL")
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.startsWith("https://", ignoreCase = true) }
            ?: defaultWorkerUrl

    /** Lower-case host of [defaultWorkerUrl], or null if it cannot be parsed. */
    val defaultWorkerHost: String?
        get() = try {
            URI(defaultWorkerUrl).host?.lowercase()
        } catch (e: Exception) {
            null
        }

    private fun readBuildConfigString(fieldName: String): String? = try {
        BuildConfig::class.java.getField(fieldName).get(null) as? String
    } catch (e: Exception) {
        null
    }
}
