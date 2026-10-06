package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AdminConfig
import com.example.data.model.Invitation
import com.example.data.model.Order
import com.example.data.model.PublicConfig
import com.example.data.repository.AuthRepository
import com.example.util.WorkerUrlValidator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FridaUnitTests {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun testPinVerificationAndFailClosed() {
        val authRepo = AuthRepository(context)
        authRepo.setAdminPin(null)

        // When no PIN is configured, verifyPin must FAIL CLOSED (return false)
        assertFalse("PIN verification must fail-closed when no PIN is configured", authRepo.verifyPin("1234"))
        assertFalse("PIN verification must fail-closed for empty string", authRepo.verifyPin(""))

        // Set PIN
        authRepo.setAdminPin("4321")
        assertTrue("PIN must be marked as configured", authRepo.hasAdminPin())
        assertTrue("Valid PIN must succeed", authRepo.verifyPin("4321"))
        assertFalse("Invalid PIN must fail", authRepo.verifyPin("1111"))
    }

    @Test
    fun testPinLockoutMechanism() {
        val authRepo = AuthRepository(context)
        authRepo.setAdminPin(null)
        authRepo.setAdminPin("8888")

        // Trigger consecutive failures up to max (5 attempts)
        for (i in 1..4) {
            assertFalse(authRepo.verifyPin("0000"))
            assertFalse(authRepo.isPinLockedOut())
        }
        // 5th failed attempt triggers lockout
        assertFalse(authRepo.verifyPin("0000"))
        assertTrue("System must be locked out after 5 failures", authRepo.isPinLockedOut())
        assertTrue("Lockout remaining seconds must be > 0", authRepo.getLockoutRemainingSeconds() > 0)

        // Even correct PIN is rejected during active lockout
        assertFalse("Correct PIN must be rejected during lockout", authRepo.verifyPin("8888"))
    }

    @Test
    fun testWorkerUrlAllowlistValidation() {
        // Valid domain in allowlist
        val validUrl = "https://frida-invitations-worker.frida.workers.dev"
        val normalized = WorkerUrlValidator.validateAndSanitize(validUrl)
        assertEquals("https://frida-invitations-worker.frida.workers.dev", normalized)

        // Invalid / untrusted domain must throw SecurityException
        try {
            WorkerUrlValidator.validateAndSanitize("https://evil-server.com/api")
            fail("Expected SecurityException for unapproved worker domain")
        } catch (e: Exception) {
            assertTrue(e is SecurityException || e is IllegalArgumentException)
        }

        // Invalid protocol (HTTP instead of HTTPS)
        try {
            WorkerUrlValidator.validateAndSanitize("http://frida-invitations-worker.frida.workers.dev")
            fail("Expected SecurityException for http protocol")
        } catch (e: Exception) {
            assertTrue(e is SecurityException || e is IllegalArgumentException)
        }
    }

    @Test
    fun testPriceAndSettingsValidation() {
        // Test Price Validation logic
        fun isValidPrice(price: Double): Boolean = price > 0.0

        assertTrue(isValidPrice(150.0))
        assertTrue(isValidPrice(500.5))
        assertFalse(isValidPrice(0.0))
        assertFalse(isValidPrice(-50.0))

        val config = PublicConfig(
            vodafoneCashNumber = "01099998888",
            walletOwnerName = "فريدا للدعوات الإلكترونية",
            tier1Price = 250.0,
            tier2Price = 450.0,
            tier3Price = 750.0,
            customerSupportWhatsApp = "+201099998888"
        )

        assertEquals("01099998888", config.vodafoneCashNumber)
        assertEquals(250.0, config.tier1Price, 0.001)
        assertEquals(450.0, config.tier2Price, 0.001)
        assertEquals(750.0, config.tier3Price, 0.001)
    }

    @Test
    fun testOrdersFilteringAndSearch() {
        val orders = listOf(
            Order(id = "1", orderNumber = "ORD-101", clientName = "أحمد ومريم", status = "pending", amount = 300.0, packageName = "الباقة الفضية"),
            Order(id = "2", orderNumber = "ORD-102", clientName = "محمد وسارة", status = "approved", amount = 500.0, packageName = "الباقة الذهبية"),
            Order(id = "3", orderNumber = "ORD-103", clientName = "يوسف ونوران", status = "rejected", amount = 700.0, packageName = "الباقة الماسية")
        )

        // Filter by status
        val pendingOrders = orders.filter { it.status == "pending" }
        assertEquals(1, pendingOrders.size)
        assertEquals("ORD-101", pendingOrders.first().orderNumber)

        val approvedOrders = orders.filter { it.status == "approved" }
        assertEquals(1, approvedOrders.size)
        assertEquals("ORD-102", approvedOrders.first().orderNumber)

        // Search by query
        val searchResults = orders.filter { it.clientName.contains("سارة") || it.orderNumber.contains("102") }
        assertEquals(1, searchResults.size)
        assertEquals("محمد وسارة", searchResults.first().clientName)
    }

    @Test
    fun testModelDefaultValuesFallback() {
        // Verify default values do not contain fabricated names
        val defaultOrder = Order(id = "test")
        assertEquals("غير محدد", defaultOrder.clientName)
        assertEquals("غير محدد", defaultOrder.packageName)
        assertEquals(0.0, defaultOrder.amount, 0.001)

        val defaultInvitation = Invitation(id = "test_inv")
        assertEquals("غير محدد", defaultInvitation.packageTier)
        assertEquals("", defaultInvitation.groomName)
        assertEquals("", defaultInvitation.brideName)
    }
}
