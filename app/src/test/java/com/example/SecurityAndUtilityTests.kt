package com.example

import com.example.util.WorkerUrlValidator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecurityAndUtilityTests {

    @Test
    fun testWorkerUrlValidator_valid_accepted() {
        val validUrl = "https://frida-invitations-worker.frida.workers.dev"
        val result = WorkerUrlValidator.validateAndSanitize(validUrl)
        assertEquals(validUrl, result)
    }

    @Test
    fun testWorkerUrlValidator_https_required() {
        val httpUrl = "http://frida-invitations-worker.frida.workers.dev"
        assertThrows(SecurityException::class.java) {
            WorkerUrlValidator.validateAndSanitize(httpUrl)
        }
    }

    @Test
    fun testWorkerUrlValidator_attacker_workers_dev_rejected() {
        val attackerUrl = "https://attacker.workers.dev"
        assertThrows(SecurityException::class.java) {
            WorkerUrlValidator.validateAndSanitize(attackerUrl)
        }
    }

    @Test
    fun testWorkerUrlValidator_wrong_domain_rejected() {
        val wrongUrl = "https://someotherdomain.com"
        assertThrows(SecurityException::class.java) {
            WorkerUrlValidator.validateAndSanitize(wrongUrl)
        }
    }

    @Test
    fun testWorkerUrlValidator_unexpected_port_rejected() {
        val urlWithPort = "https://frida-invitations-worker.frida.workers.dev:8080"
        assertThrows(SecurityException::class.java) {
            WorkerUrlValidator.validateAndSanitize(urlWithPort)
        }
    }

    @Test
    fun testWorkerUrlValidator_userInfo_rejected() {
        val urlWithUserInfo = "https://user:password@frida-invitations-worker.frida.workers.dev"
        assertThrows(SecurityException::class.java) {
            WorkerUrlValidator.validateAndSanitize(urlWithUserInfo)
        }
    }

    @Test
    fun testWorkerUrlValidator_query_rejected() {
        val urlWithQuery = "https://frida-invitations-worker.frida.workers.dev/api?param=1"
        assertThrows(SecurityException::class.java) {
            WorkerUrlValidator.validateAndSanitize(urlWithQuery)
        }
    }

    @Test
    fun testWorkerUrlValidator_fragment_rejected() {
        val urlWithFragment = "https://frida-invitations-worker.frida.workers.dev/api#section"
        assertThrows(SecurityException::class.java) {
            WorkerUrlValidator.validateAndSanitize(urlWithFragment)
        }
    }
}
