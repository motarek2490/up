package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("FRIDA Admin", appName)
  }

  @Test
  fun `test pin security pbkdf2 and lockout`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val authRepo = com.example.data.repository.AuthRepository(context)

    authRepo.setAdminPin("1234")
    org.junit.Assert.assertTrue(authRepo.hasAdminPin())
    org.junit.Assert.assertTrue(authRepo.verifyPin("1234"))
    org.junit.Assert.assertFalse(authRepo.verifyPin("9999"))

    // Test consecutive failures lockout
    for (i in 1..4) {
      authRepo.verifyPin("0000")
    }
    org.junit.Assert.assertTrue(authRepo.isPinLockedOut())
    org.junit.Assert.assertTrue(authRepo.getLockoutRemainingSeconds() > 0)
    // Even correct PIN is blocked during active lockout
    org.junit.Assert.assertFalse(authRepo.verifyPin("1234"))
  }
}
