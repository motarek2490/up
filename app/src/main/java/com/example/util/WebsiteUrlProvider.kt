package com.example.util

import android.net.Uri

/**
 * Builds the public links of an invitation from the CURRENT website base URL and the invitation's
 * real slug. Links are always computed here and never read back from Firestore, so a wrong base
 * URL can never be "frozen" inside stored documents.
 *
 * Routes used by the website: /i/{slug} (guests) and /portal/{slug} (host portal).
 */
object WebsiteUrlProvider {

    /** Default base URL (from .env WEBSITE_BASE_URL). */
    val DEFAULT_BASE_URL: String
        get() = AppConfig.defaultWebsiteUrl

    @Volatile
    private var customBaseUrl: String? = null

    /** Accepts only http(s) URLs; anything else is ignored and the default is kept. */
    fun setBaseUrl(url: String?) {
        val trimmed = url?.trim()?.trimEnd('/')
        if (!trimmed.isNullOrBlank() && trimmed.startsWith("https://", ignoreCase = true)) {
            customBaseUrl = trimmed
        }
    }

    fun getBaseUrl(): String = customBaseUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL

    private fun safeSlug(slug: String): String = Uri.encode(slug.trim().lowercase())

    fun getGuestUrl(slug: String): String = "${getBaseUrl()}/i/${safeSlug(slug)}"

    fun getHostPortalUrl(slug: String): String = "${getBaseUrl()}/portal/${safeSlug(slug)}"
}
