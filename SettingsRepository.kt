package com.example.data.repository

import android.util.Log
import com.example.data.FirebaseProvider
import com.example.data.model.AdminConfig
import com.example.data.model.PublicConfig
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

sealed class SettingsDataState<out T> {
    data object Loading : SettingsDataState<Nothing>()
    data class Success<T>(val data: T) : SettingsDataState<T>()
    data class Error(val message: String) : SettingsDataState<Nothing>()
}

class SettingsRepository {
    private val firestore get() = FirebaseProvider.firestore
    private val auth get() = FirebaseProvider.auth

    companion object {
        private const val TAG = "SettingsRepository"
    }

    fun getPublicConfigFlow(): Flow<SettingsDataState<PublicConfig>> = callbackFlow {
        trySend(SettingsDataState.Loading)
        val docRef = firestore.collection("settings").document("public_config")
        val registration = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                val msg = if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    "غير مصرّح بالوصول لإعدادات النظام. يرجى التأكد من صلاحية الإدارة."
                } else {
                    "تعذر جلب إعدادات الدفع: ${error.localizedMessage}"
                }
                Log.e(TAG, "Error fetching public_config", error)
                trySend(SettingsDataState.Error(msg))
                return@addSnapshotListener
            }
            if (snapshot != null && snapshot.exists()) {
                val config = PublicConfig(
                    vodafoneCashNumber = snapshot.getString("vodafoneCashNumber") ?: snapshot.getString("walletNumber") ?: "",
                    walletOwnerName = snapshot.getString("walletOwnerName") ?: "",
                    tier1Price = snapshot.getDouble("tier1Price") ?: snapshot.getLong("tier1Price")?.toDouble() ?: 0.0,
                    tier2Price = snapshot.getDouble("tier2Price") ?: snapshot.getLong("tier2Price")?.toDouble() ?: 0.0,
                    tier3Price = snapshot.getDouble("tier3Price") ?: snapshot.getLong("tier3Price")?.toDouble() ?: 0.0,
                    customerSupportWhatsApp = snapshot.getString("customerSupportWhatsApp") ?: ""
                )
                trySend(SettingsDataState.Success(config))
            } else {
                // If doc doesn't exist yet, emit empty config in success state
                trySend(SettingsDataState.Success(PublicConfig()))
            }
        }
        awaitClose { registration.remove() }
    }.flowOn(Dispatchers.IO)

    fun getAdminConfigFlow(): Flow<SettingsDataState<AdminConfig>> = callbackFlow {
        if (auth.currentUser == null) {
            trySend(SettingsDataState.Error("يجب تسجيل الدخول أولاً"))
            awaitClose { }
            return@callbackFlow
        }

        trySend(SettingsDataState.Loading)
        val docRef = firestore.collection("settings").document("admin_config")
        val registration = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                val msg = if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    "غير مصرّح بالوصول لإعدادات النظام الإدارية."
                } else {
                    "تعذر جلب إعدادات الخادم: ${error.localizedMessage}"
                }
                Log.e(TAG, "Error fetching admin_config", error)
                trySend(SettingsDataState.Error(msg))
                return@addSnapshotListener
            }
            if (snapshot != null && snapshot.exists()) {
                val siteUrl = snapshot.getString("websiteBaseUrl")
                    ?: snapshot.getString("websiteUrl")
                    ?: snapshot.getString("siteUrl")
                    ?: snapshot.getString("domain")
                    ?: com.example.util.WebsiteUrlProvider.DEFAULT_BASE_URL
                com.example.util.WebsiteUrlProvider.setBaseUrl(siteUrl)

                val config = AdminConfig(
                    maintenanceMode = snapshot.getBoolean("maintenanceMode") ?: false,
                    autoNotificationEnabled = snapshot.getBoolean("autoNotificationEnabled") ?: true,
                    cloudflareWorkerUrl = snapshot.getString("cloudflareWorkerUrl")?.takeIf { it.isNotBlank() } ?: com.example.util.AppConfig.defaultWorkerUrl,
                    websiteBaseUrl = siteUrl
                )
                trySend(SettingsDataState.Success(config))
            } else {
                trySend(SettingsDataState.Success(AdminConfig()))
            }
        }
        awaitClose { registration.remove() }
    }.flowOn(Dispatchers.IO)

    /**
     * Writes [values] to every (collection, document) target independently and reports failures.
     * Previously errors were swallowed, so the UI said "saved" even when nothing was written.
     * The first path is the one this app reads back; the second is the mirror used by the website.
     * If any target fails the call throws, naming the failed path(s).
     */
    private suspend fun writeToAll(
        values: Map<String, Any>,
        targets: List<Pair<String, String>>
    ) {
        val failures = mutableListOf<String>()
        var firstError: Exception? = null
        for ((collection, document) in targets) {
            try {
                firestore.collection(collection).document(document).set(values, SetOptions.merge()).await()
            } catch (e: Exception) {
                Log.e(TAG, "Failed setting $collection/$document: ${e.message}", e)
                failures += "$collection/$document"
                if (firstError == null) firstError = e
            }
        }
        if (failures.isNotEmpty()) {
            val detail = firstError?.localizedMessage ?: "خطأ غير معروف"
            throw IllegalStateException("تعذر الحفظ في: ${failures.joinToString("، ")} ($detail)")
        }
    }

    suspend fun updatePublicConfig(config: PublicConfig) = withContext(Dispatchers.IO) {
        val map = hashMapOf<String, Any>(
            "vodafoneCashNumber" to config.vodafoneCashNumber,
            "walletNumber" to config.vodafoneCashNumber,
            "walletOwnerName" to config.walletOwnerName,
            "tier1Price" to config.tier1Price,
            "tier2Price" to config.tier2Price,
            "tier3Price" to config.tier3Price,
            "customerSupportWhatsApp" to config.customerSupportWhatsApp
        )
        writeToAll(map, listOf("settings" to "public_config", "config" to "public"))
    }

    suspend fun updateAdminConfig(config: AdminConfig) = withContext(Dispatchers.IO) {
        val cleanBaseUrl = config.websiteBaseUrl.trim().trimEnd('/')

        val map = hashMapOf<String, Any>(
            "maintenanceMode" to config.maintenanceMode,
            "autoNotificationEnabled" to config.autoNotificationEnabled,
            "cloudflareWorkerUrl" to config.cloudflareWorkerUrl,
            "websiteBaseUrl" to cleanBaseUrl,
            "websiteUrl" to cleanBaseUrl
        )
        writeToAll(map, listOf("settings" to "admin_config", "config" to "admin"))
        // Apply the new base URL locally only once the server actually accepted it.
        com.example.util.WebsiteUrlProvider.setBaseUrl(cleanBaseUrl)
    }
}
