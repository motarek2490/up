package com.example.data.repository

import android.os.Build
import android.util.Log
import com.example.data.FirebaseProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class FcmRepository {
    private val firestore = FirebaseProvider.firestore
    private val auth = FirebaseProvider.auth
    private val messaging: com.google.firebase.messaging.FirebaseMessaging? by lazy {
        try {
            if (isPlayServicesAvailable()) FirebaseProvider.messaging else null
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "FcmRepository"
    }

    private fun isEmulator(): Boolean {
        return (android.os.Build.FINGERPRINT.startsWith("generic")
                || android.os.Build.FINGERPRINT.startsWith("unknown")
                || android.os.Build.MODEL.contains("google_sdk")
                || android.os.Build.MODEL.contains("Emulator")
                || android.os.Build.MODEL.contains("Android SDK built for x86")
                || android.os.Build.MANUFACTURER.contains("Genymotion")
                || (android.os.Build.BRAND.startsWith("generic") && android.os.Build.DEVICE.startsWith("generic"))
                || "google_sdk" == android.os.Build.PRODUCT
                || android.os.Build.HARDWARE.contains("goldfish")
                || android.os.Build.HARDWARE.contains("ranchu"))
    }

    private fun isPlayServicesAvailable(): Boolean {
        return try {
            val context = com.google.firebase.FirebaseApp.getInstance().applicationContext
            val resultCode = com.google.android.gms.common.GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
            resultCode == com.google.android.gms.common.ConnectionResult.SUCCESS
        } catch (e: Exception) {
            false
        }
    }

    suspend fun registerCurrentToken(): Boolean = withContext(Dispatchers.IO) {
        if (isEmulator()) {
            Log.d(TAG, "Running in cloud emulator preview; skipping FCM background token registration. In-app real-time Firestore alerts are active.")
            return@withContext false
        }
        if (!isPlayServicesAvailable()) {
            Log.d(TAG, "Google Play Services not available; skipping FCM token registration.")
            return@withContext false
        }
        val user = auth.currentUser ?: return@withContext false
        try {
            val msg = messaging ?: return@withContext false
            val token = withTimeoutOrNull(2000L) {
                try {
                    msg.token.await()
                } catch (e: Exception) {
                    val msgText = e.message ?: ""
                    if (msgText.contains("TOO_MANY_REGISTRATIONS", ignoreCase = true)) {
                        Log.d(TAG, "FCM token limit reached on test container (TOO_MANY_REGISTRATIONS). Local features remain fully operational.")
                    } else {
                        Log.d(TAG, "FCM token retrieval skipped: $msgText")
                    }
                    null
                }
            }
            if (!token.isNullOrBlank()) {
                saveToken(user.uid, token)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "FCM token retrieval non-fatal: ${e.message}")
            false
        }
    }

    suspend fun saveToken(uid: String, token: String) = withContext(Dispatchers.IO) {
        try {
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
            // Using a deterministic ID or sanitized token snippet as tokenId
            val tokenId = token.takeLast(16)
            val docRef = firestore.collection("admin_fcm_tokens")
                .document(uid)
                .collection("tokens")
                .document(tokenId)

            val data = mapOf(
                "token" to token,
                "platform" to "android",
                "deviceName" to deviceName,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )

            docRef.set(data, SetOptions.merge()).await()
            Log.d(TAG, "FCM token saved successfully for admin $uid at admin_fcm_tokens/$uid/tokens/$tokenId")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving FCM token for $uid", e)
        }
    }

    suspend fun unregisterAndClearToken(): Boolean = withContext(Dispatchers.IO) {
        if (!isPlayServicesAvailable()) return@withContext true
        try {
            val user = auth.currentUser
            val msg = messaging
            val currentToken = try { msg?.token?.await() } catch (e: Exception) { null }
            if (user != null && !currentToken.isNullOrBlank()) {
                val tokenId = currentToken.takeLast(16)
                try {
                    firestore.collection("admin_fcm_tokens")
                        .document(user.uid)
                        .collection("tokens")
                        .document(tokenId)
                        .delete()
                        .await()
                    Log.d(TAG, "Deleted admin FCM token document for ${user.uid}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete FCM token document: ${e.message}")
                }
            }
            try {
                msg?.deleteToken()?.await()
            } catch (e: Exception) {
                Log.w(TAG, "deleteToken non-fatal: ${e.message}")
            }
            Log.d(TAG, "FCM messaging token deleted from device")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering FCM token on signOut: ${e.message}")
            false
        }
    }
}
