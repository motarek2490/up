package com.example.ui.auth

import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.ui.components.HapticUtils
import com.example.ui.theme.*

@Composable
fun BiometricLockScreen(
    authViewModel: AuthViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    var enteredPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    var lockoutSeconds by remember { mutableIntStateOf(0) }
    var biometricErrorMessage by remember { mutableStateOf<String?>(null) }

    val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG

    LaunchedEffect(pinError) {
        val remaining = authViewModel.getPinLockoutRemainingSeconds()
        lockoutSeconds = remaining
        if (remaining > 0) {
            var s = remaining
            while (s > 0) {
                kotlinx.coroutines.delay(1000)
                s--
                lockoutSeconds = s
            }
            pinError = false
        }
    }

    val activity = context as? FragmentActivity

    fun triggerBiometricPrompt() {
        if (activity == null) return
        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    HapticUtils.performSuccessHaptic(activity)
                    biometricErrorMessage = null
                    authViewModel.unlockApp()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Log.d("BiometricLockScreen", "Biometric error $errorCode: $errString")
                    when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> {
                            // User chose to use PIN keypad instead
                            biometricErrorMessage = null
                        }
                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> {
                            biometricErrorMessage = "تم قفل البصمة مؤقتاً بعد عدة محاولات خاطئة. يرجى استخدام رمز PIN."
                        }
                        else -> {
                            biometricErrorMessage = errString.toString()
                        }
                    }
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("تأكيد هوية المدير")
            .setSubtitle("استخدم بصمة الإصبع أو الوجه لفتح لوحة التحكم")
            .setNegativeButtonText("إلغاء / استخدام PIN")
            .setAllowedAuthenticators(authenticators)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    LaunchedEffect(Unit) {
        val biometricManager = BiometricManager.from(context)
        val canAuth = biometricManager.canAuthenticate(authenticators)
        if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
            triggerBiometricPrompt()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(FridaBlack)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Header
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = FridaGold,
                modifier = Modifier.size(48.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "قفل الأمان المحلي",
                style = MaterialTheme.typography.titleLarge,
                color = FridaGoldLight,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "أدخل رمز PIN أو استخدم البصمة لفتح لوحة التحكم",
                style = MaterialTheme.typography.bodyMedium,
                color = FridaTextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // PIN Dots Display
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (i in 0 until 4) {
                    val isFilled = i < enteredPin.length
                    Surface(
                        modifier = Modifier.size(20.dp),
                        shape = CircleShape,
                        color = if (isFilled) FridaGold else FridaCardElevated,
                        border = BorderStroke(
                            1.5.dp,
                            if (pinError) FridaRed else if (isFilled) FridaGoldLight else FridaBorder
                        )
                    ) {}
                }
            }

            if (pinError || lockoutSeconds > 0 || !biometricErrorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = when {
                        lockoutSeconds > 0 -> "تم قفل الإدخال مؤقتاً لحماية الحساب. يرجى الانتظار $lockoutSeconds ثانية"
                        pinError -> "رمز PIN غير صحيح، حاول مرة أخرى"
                        else -> biometricErrorMessage ?: ""
                    },
                    color = FridaRed,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Numeric Keypad
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val rows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("biometric", "0", "delete")
            )

            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(0.85f),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    row.forEach { key ->
                        when (key) {
                            "biometric" -> {
                                IconButton(
                                    onClick = {
                                        HapticUtils.performLightHaptic(view)
                                        triggerBiometricPrompt()
                                    },
                                    modifier = Modifier.size(68.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Fingerprint,
                                        contentDescription = "بصمة",
                                        tint = FridaGold,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                            "delete" -> {
                                IconButton(
                                    onClick = {
                                        HapticUtils.performLightHaptic(view)
                                        if (enteredPin.isNotEmpty()) {
                                            enteredPin = enteredPin.dropLast(1)
                                            pinError = false
                                        }
                                    },
                                    modifier = Modifier.size(68.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Backspace,
                                        contentDescription = "حذف",
                                        tint = FridaTextSecondary,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }
                            else -> {
                                Surface(
                                    modifier = Modifier.size(68.dp),
                                    shape = CircleShape,
                                    color = FridaCard,
                                    border = BorderStroke(1.dp, FridaBorder),
                                    onClick = {
                                        if (lockoutSeconds > 0) return@Surface
                                        HapticUtils.performLightHaptic(view)
                                        pinError = false
                                        if (enteredPin.length < 4) {
                                            val newPin = enteredPin + key
                                            enteredPin = newPin
                                            if (newPin.length == 4) {
                                                val verified = authViewModel.verifyPin(newPin)
                                                if (!verified) {
                                                    HapticUtils.performErrorHaptic(context)
                                                    pinError = true
                                                    enteredPin = ""
                                                }
                                            }
                                        }
                                    }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = key,
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = FridaTextPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
