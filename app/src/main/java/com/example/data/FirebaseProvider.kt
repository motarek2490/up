package com.example.data

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage

import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object FirebaseProvider {
    private const val TAG = "FirebaseProvider"

    // Cloud Functions Region & Named Firestore Database ID
    const val FUNCTIONS_REGION = "europe-west1"
    const val FIRESTORE_DATABASE_ID = "ai-studio-frida-eb6a19f5-9126-4bbb-b06c-270aac6778bf"

    fun initialize(context: Context) {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
                Log.d(TAG, "Initialized default FirebaseApp from google-services.json")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking/initializing FirebaseApp", e)
        }
    }

    val auth: FirebaseAuth
        get() = FirebaseAuth.getInstance()

    /**
     * Always the NAMED database used by the website and Cloud Functions.
     * There is deliberately NO fallback to the "(default)" database: silently reading/writing a
     * different database makes orders and invitations "vanish" between the app and the website.
     */
    val firestore: FirebaseFirestore
        get() = FirebaseFirestore.getInstance(FirebaseApp.getInstance(), FIRESTORE_DATABASE_ID)

    val functions: FirebaseFunctions
        get() {
            val app = FirebaseApp.getInstance()
            return FirebaseFunctions.getInstance(app, FUNCTIONS_REGION)
        }

    val storage: FirebaseStorage
        get() = FirebaseStorage.getInstance()

    /**
     * Calls an HTTPS Callable function in [FUNCTIONS_REGION] (where firebase deploy puts them).
     * Errors are NOT swallowed or retried in other regions: the caller gets the real
     * [FirebaseFunctionsException] and can show a precise message (see [describeFunctionError]).
     */
    suspend fun callCloudFunction(
        functionName: String,
        data: Map<String, Any?>
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        val result = functions.getHttpsCallable(functionName).call(data).await()
        @Suppress("UNCHECKED_CAST")
        val payload = result.data as? Map<String, Any?>
        payload ?: emptyMap()
    }

    /** Turns a Cloud Functions failure into a clear Arabic message that says what to fix. */
    fun describeFunctionError(functionName: String, e: Throwable): String {
        if (e is FirebaseFunctionsException) {
            return when (e.code) {
                FirebaseFunctionsException.Code.NOT_FOUND ->
                    "الدالة «$functionName» غير منشورة في المنطقة $FUNCTIONS_REGION. نفّذ: firebase deploy --only functions"
                FirebaseFunctionsException.Code.PERMISSION_DENIED ->
                    "هذا الحساب ليس أدمن على الخادم (لا يملك صلاحية admin). تأكد من الـ custom claim ثم سجّل الخروج والدخول."
                FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                    "انتهت جلسة تسجيل الدخول. سجّل الدخول من جديد."
                FirebaseFunctionsException.Code.UNAVAILABLE,
                FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ->
                    "تعذر الوصول للخادم. تحقق من الإنترنت وأعد المحاولة."
                FirebaseFunctionsException.Code.FAILED_PRECONDITION,
                FirebaseFunctionsException.Code.INVALID_ARGUMENT,
                FirebaseFunctionsException.Code.ABORTED,
                FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
                    e.message ?: "رفض الخادم العملية"
                else -> "خطأ من الخادم (${e.code}): ${e.message ?: ""}"
            }
        }
        return e.localizedMessage ?: "خطأ غير متوقع"
    }

    val messaging: FirebaseMessaging
        get() = FirebaseMessaging.getInstance()
}

