package com.example

import com.example.util.AppConfig
import com.example.util.WebsiteUrlProvider
import com.example.util.WorkerUrlValidator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryHelpersTests {

    @Test
    fun defaultWorkerUrl_isHttpsAndAllowedToReceiveAdminToken() {
        val url = AppConfig.defaultWorkerUrl
        assertTrue(url.startsWith("https://"))
        assertFalse(url.endsWith("/"))
        assertEquals(url, WorkerUrlValidator.validateAndSanitize(url))
    }

    @Test
    fun typoHost_isGoneFromAllowlist() {
        val hosts = WorkerUrlValidator.getAllowedHosts()
        assertTrue("frida-invitations-worker.frida.workers.dev" in hosts)
        assertFalse(hosts.any { it.contains("invitationes") })
    }

    @Test
    fun websiteBaseUrlFromFirestore_doesNotWidenTheTokenAllowlist() {
        WebsiteUrlProvider.setBaseUrl("https://evil.example.com")
        try {
            assertFalse("evil.example.com" in WorkerUrlValidator.getAllowedHosts())
        } finally {
            WebsiteUrlProvider.setBaseUrl(WebsiteUrlProvider.DEFAULT_BASE_URL)
        }
    }

    @Test
    fun links_useRealSlug_guestAndPortalRoutes() {
        WebsiteUrlProvider.setBaseUrl("https://example.com/")
        assertEquals("https://example.com/i/ahmed-mariam", WebsiteUrlProvider.getGuestUrl("Ahmed-Mariam"))
        assertEquals("https://example.com/portal/ahmed-mariam", WebsiteUrlProvider.getHostPortalUrl(" ahmed-mariam "))
    }

    @Test
    fun links_encodeArabicSlugs() {
        WebsiteUrlProvider.setBaseUrl("https://example.com")
        val url = WebsiteUrlProvider.getGuestUrl("أحمد-مريم")
        assertTrue(url.startsWith("https://example.com/i/"))
        assertFalse(url.contains(" "))
        assertTrue(url.contains("%"))
    }

    @Test
    fun baseUrl_rejectsPlainHttp() {
        WebsiteUrlProvider.setBaseUrl("https://example.com")
        WebsiteUrlProvider.setBaseUrl("http://insecure.example.com")
        assertEquals("https://example.com", WebsiteUrlProvider.getBaseUrl())
    }
}
