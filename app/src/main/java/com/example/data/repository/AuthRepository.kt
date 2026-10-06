package com.example.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.example.data.FirebaseProvider
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

class AdminAccessDeniedException(message: String = "هذا الحساب لا يملك صلاحية الوصول للوحة التحكم") : Exception(message)
class AuthNetworkException(message: String = "خطأ في الاتصال بالخادم، يرجى إعادة المحاولة") : Exception(message)

class AuthRepository(private val context: Context) {
    private val auth get() = FirebaseProvider.auth
    private val prefs = context.getSharedPreferences("frida_admin_auth_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "AuthRepository"
        private const val PBKDF2_ITERATIONS = 50_000
        private const val KEY_LENGTH = 256
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 30_000L // 30 seconds
        private const val CLAIMS_TIMEOUT_MS = 8_000L
    }

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    val isUserLoggedIn: Boolean
        get() = auth.currentUser != null

    /**
     * Server-authoritative admin verification based on Firebase Auth custom claims.
     *
     * Three distinct outcomes (they must never be conflated):
     *  - Claims fetched and the account IS an admin  -> returns true.
     *  - Claims fetched and the account is NOT admin -> signs out and throws [AdminAccessDeniedException].
     *  - Claims could NOT be fetched (timeout / no network) -> throws [AuthNetworkException]
     *    WITHOUT signing the user out, so a weak connection can never log a real admin out.
     *
     * Order: fresh token (forced refresh) first; if that is inconclusive, the cached token is used.
     * The cached token is only trusted for an "admin" result. Firestore Security Rules remain the
     * real enforcement point for every data access.
     */
    suspend fun verifyAdminClaim(): Boolean = withContext(Dispatchers.IO) {
        val user = auth.currentUser ?: return@withContext false

        fun isAdmin(claims: Map<String, Any>): Boolean =
            claims["admin"] == true || claims["role"] == "admin"

        suspend fun readClaims(forceRefresh: Boolean): Boolean? =
            withTimeoutOrNull(CLAIMS_TIMEOUT_MS) {
                try {
                    isAdmin(user.getIdToken(forceRefresh).await().claims)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Claims check (forceRefresh=$forceRefresh) inconclusive: ${e.message}")
                    null
                }
            }

        val fresh = readClaims(forceRefresh = true)
        val verdict = fresh ?: readClaims(forceRefresh = false)

        when (verdict) {
            true -> {
                Log.d(TAG, "Admin status verified via Auth Claims for ${user.uid}")
                true
            }
            false -> {
                Log.w(TAG, "Access denied: Account ${user.email} (${user.uid}) is not an authorized admin")
                auth.signOut()
                throw AdminAccessDeniedException(
                    "الحساب (${user.email ?: "المحدد"}) غير مصرح له بالوصول للوحة التحكم الإدارية. " +
                        "يرجى التأكد من تفعيل صلاحيات الإدارة السحابية (Admin claims)."
                )
            }
            null -> {
                Log.w(TAG, "Could not verify admin claims for ${user.uid}; keeping session, asking to retry")
                throw AuthNetworkException(
                    "تعذر التحقق من صلاحيات الإدارة بسبب ضعف الاتصال بالإنترنت. تأكد من الشبكة وأعد المحاولة."
                )
            }
        }
    }

    suspend fun getIdToken(): String? = withContext(Dispatchers.IO) {
        try {
            auth.currentUser?.getIdToken(false)?.await()?.token
        } catch (e: Exception) {
            null
        }
    }

    fun isBiometricLockEnabled(): Boolean {
        return prefs.getBoolean("biometric_lock_enabled", false)
    }

    fun setBiometricLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("biometric_lock_enabled", enabled).apply()
    }

    fun hasAdminPin(): Boolean {
        val stored = prefs.getString("admin_pin_encrypted", null)
            ?: prefs.getString("admin_pin_hash", null)
        return !stored.isNullOrBlank()
    }

    fun setAdminPin(pin: String?) {
        if (pin.isNullOrBlank()) {
            prefs.edit()
                .remove("admin_pin_encrypted")
                .remove("admin_pin_hash")
                .apply()
            resetFailedAttempts()
        } else {
            val salt = ByteArray(16)
            SecureRandom().nextBytes(salt)
            val hash = hashPbkdf2(pin, salt)
            val encodedSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
            val encodedHash = Base64.encodeToString(hash, Base64.NO_WRAP)
            val rawPayload = "pbkdf2:$encodedSalt:$encodedHash"

            // Encrypt using Keystore
            val encryptedPayload = encryptWithKeystore(rawPayload)
            prefs.edit()
                .putString("admin_pin_encrypted", encryptedPayload)
                .remove("admin_pin_hash")
                .apply()
            resetFailedAttempts()
        }
    }

    fun getLockoutRemainingSeconds(): Int {
        val lockoutUntil = prefs.getLong("pin_lockout_until", 0L)
        val now = System.currentTimeMillis()
        return if (lockoutUntil > now) {
            ((lockoutUntil - now) / 1000).toInt() + 1
        } else {
            0
        }
    }

    fun isPinLockedOut(): Boolean = getLockoutRemainingSeconds() > 0

    /**
     * Verifies entered PIN:
     * - Fails closed if PIN is blank or if no stored hash exists while lock is enabled.
     * - Blocks during active lockout.
     */
    fun verifyPin(enteredPin: String): Boolean {
        if (enteredPin.isBlank()) {
            return false
        }
        if (isPinLockedOut()) {
            return false
        }

        val storedRaw = prefs.getString("admin_pin_encrypted", null)
        val legacyStored = prefs.getString("admin_pin_hash", null)

        val decryptedPayload = when {
            storedRaw != null -> decryptWithKeystore(storedRaw)
            legacyStored != null -> legacyStored
            else -> null
        }

        // FAIL CLOSED: if no stored hash exists, reject verification
        if (decryptedPayload.isNullOrBlank()) {
            return false
        }

        val isValid = when {
            decryptedPayload.startsWith("pbkdf2:") -> {
                val parts = decryptedPayload.split(":")
                if (parts.size == 3) {
                    try {
                        val salt = Base64.decode(parts[1], Base64.NO_WRAP)
                        val expectedHash = Base64.decode(parts[2], Base64.NO_WRAP)
                        val actualHash = hashPbkdf2(enteredPin, salt)
                        MessageDigest.isEqual(expectedHash, actualHash)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error verifying PBKDF2 hash", e)
                        false
                    }
                } else false
            }
            else -> false
        }

        if (isValid) {
            resetFailedAttempts()
            // If it was legacy unencrypted, upgrade to Keystore encrypted
            if (storedRaw == null && legacyStored != null) {
                setAdminPin(enteredPin)
            }
        } else {
            recordFailedAttempt()
        }

        return isValid
    }

    private fun recordFailedAttempt() {
        val attempts = prefs.getInt("pin_failed_attempts", 0) + 1
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            val lockoutUntil = System.currentTimeMillis() + LOCKOUT_DURATION_MS
            prefs.edit()
                .putInt("pin_failed_attempts", 0)
                .putLong("pin_lockout_until", lockoutUntil)
                .apply()
        } else {
            prefs.edit().putInt("pin_failed_attempts", attempts).apply()
        }
    }

    private fun resetFailedAttempts() {
        prefs.edit()
            .remove("pin_failed_attempts")
            .remove("pin_lockout_until")
            .apply()
    }

    private fun hashPbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    // Android Keystore AES-GCM Encryption
    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val alias = "FridaAdminPinKey"
        if (!keyStore.containsAlias(alias)) {
            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            keyGen.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            )
            return keyGen.generateKey()
        }
        return (keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
    }

    private fun encryptWithKeystore(plainText: String): String {
        return try {
            val key = getOrCreateSecretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteBuffer.allocate(4 + iv.size + cipherText.size)
                .putInt(iv.size)
                .put(iv)
                .put(cipherText)
                .array()
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Keystore encryption unavailable (e.g. testing environment), fallback to obfuscation: ${e.message}")
            "raw:" + Base64.encodeToString(plainText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
    }

    private fun decryptWithKeystore(encryptedData: String): String? {
        if (encryptedData.startsWith("raw:")) {
            val base64 = encryptedData.removePrefix("raw:")
            return String(Base64.decode(base64, Base64.NO_WRAP), Charsets.UTF_8)
        }
        return try {
            val key = getOrCreateSecretKey()
            val combined = Base64.decode(encryptedData, Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(combined)
            val ivLength = buffer.int
            val iv = ByteArray(ivLength)
            buffer.get(iv)
            val cipherText = ByteArray(buffer.remaining())
            buffer.get(cipherText)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val plainBytes = cipher.doFinal(cipherText)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt PIN payload", e)
            null
        }
    }

    fun signOut() {
        auth.signOut()
    }
}
