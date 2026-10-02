package com.example

import com.example.data.repository.OrdersRepository
import com.example.util.AppConfig
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
    fun hostCode_isAlwaysSixDigits() {
        repeat(500) {
            val code = OrdersRepository.generateHostCode()
            assertEquals(6, code.length)
            assertTrue(code.all { it.isDigit() })
            assertTrue(code.toInt() in 100_000..999_999)
        }
    }

    @Test
    fun slugBase_usesNamesAndIsUrlSafe() {
        assertEquals("Ahmed-Mariam", OrdersRepository.buildSlugBase("Ahmed", "Mariam", "1"))
        assertEquals("أحمد-مريم", OrdersRepository.buildSlugBase("أحمد", "مريم", "1"))
        // slashes / spaces / punctuation are replaced, repeated dashes collapsed
        assertEquals("A-B-C-D", OrdersRepository.buildSlugBase("A / B", "C. D", "1"))
    }

    @Test
    fun slugBase_fallsBackToOrderNumberAndRespectsMaxLength() {
        assertEquals("invitation-ORD7", OrdersRepository.buildSlugBase("", "", "ORD7"))
        assertEquals("invitation-ORD7", OrdersRepository.buildSlugBase("!!!", "???", "ORD7"))
        val long = OrdersRepository.buildSlugBase("a".repeat(60), "b".repeat(60), "1")
        assertTrue(long.length <= 40)
        assertFalse(long.startsWith("-") || long.endsWith("-"))
    }

    @Test
    fun defaultWorkerUrl_isHttpsAndAllowedToReceiveAdminToken() {
        val url = AppConfig.defaultWorkerUrl
        assertTrue(url.startsWith("https://"))
        assertFalse(url.endsWith("/"))
        // The default worker must pass the allowlist used before any token is sent to it.
        assertEquals(url, WorkerUrlValidator.validateAndSanitize(url))
    }

    @Test
    fun typoHost_isNoLongerHardcodedInAllowlist() {
        // Only reachable through ALLOWED_WORKER_HOSTS or the website base URL now.
        assertTrue("frida-invitations-worker.frida.workers.dev" in WorkerUrlValidator.getAllowedHosts())
    }
}
