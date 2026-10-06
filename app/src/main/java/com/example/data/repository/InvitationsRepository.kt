package com.example.data.repository

import android.util.Log
import com.example.data.FirebaseProvider
import com.example.data.model.DataState
import com.example.data.model.Invitation
import com.example.data.model.getSafeString
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class InvitationsRepository {
    private val firestore get() = FirebaseProvider.firestore
    private val functions get() = FirebaseProvider.functions
    private val auth get() = FirebaseProvider.auth

    companion object {
        private const val TAG = "InvitationsRepository"

        private val DEFINITIVE_FUNCTION_ERRORS = setOf(
            FirebaseFunctionsException.Code.PERMISSION_DENIED,
            FirebaseFunctionsException.Code.UNAUTHENTICATED,
            FirebaseFunctionsException.Code.INVALID_ARGUMENT,
            FirebaseFunctionsException.Code.FAILED_PRECONDITION,
            FirebaseFunctionsException.Code.ALREADY_EXISTS
        )
    }

    /**
     * Live invitations list, newest first. Status filter + ordering are applied on the server
     * BEFORE `limit` (so the newest N matching invitations are returned). Needs the composite
     * index (status ASC, createdAt DESC) from firestore.indexes.json; if it is not deployed yet,
     * it falls back to an unordered query + local sort rather than failing.
     */
    fun getInvitationsFlow(
        statusFilter: String? = null,
        limit: Long = 50
    ): Flow<DataState<List<Invitation>>> = callbackFlow {
        if (auth.currentUser == null) {
            trySend(DataState.Error("يجب تسجيل الدخول كمدير أولاً"))
            awaitClose { }
            return@callbackFlow
        }

        trySend(DataState.Loading)

        val serverStatus = statusFilter?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("all", ignoreCase = true) }
            ?.lowercase()

        var registration: ListenerRegistration? = null

        fun attach(ordered: Boolean) {
            registration?.remove()
            var query: Query = firestore.collection("invitations")
            if (serverStatus != null) query = query.whereEqualTo("status", serverStatus)
            if (ordered) query = query.orderBy("createdAt", Query.Direction.DESCENDING)

            registration = query.limit(limit).addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (ordered && error.code == FirebaseFirestoreException.Code.FAILED_PRECONDITION) {
                        Log.w(TAG, "Invitations index missing, falling back to unordered query: ${error.message}")
                        attach(ordered = false)
                        return@addSnapshotListener
                    }
                    val errorMsg = if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        "غير مصرّح بالوصول للدعوات. يرجى التأكد من صلاحية الإدارة."
                    } else {
                        "تعذر جلب الدعوات: ${error.localizedMessage}"
                    }
                    Log.e(TAG, "Error listening to invitations collection", error)
                    trySend(DataState.Error(errorMsg))
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    try {
                        val invitations = snapshot.documents
                            .mapNotNull { doc ->
                                try {
                                    Invitation.fromDoc(doc)
                                } catch (e: Throwable) {
                                    Log.e(TAG, "Error parsing invitation doc ${doc.id}", e)
                                    null
                                }
                            }
                            .sortedByDescending {
                                try {
                                    it.createdAt?.toDate()?.time ?: 0L
                                } catch (e: Throwable) {
                                    0L
                                }
                            }
                        trySend(DataState.Success(invitations))
                    } catch (e: Throwable) {
                        Log.e(TAG, "Unexpected error processing invitations snapshot", e)
                        trySend(DataState.Error("خطأ أثناء معالجة بيانات الدعوات: ${e.localizedMessage}"))
                    }
                }
            }
        }

        attach(ordered = true)
        awaitClose { registration?.remove() }
    }.flowOn(Dispatchers.IO)

    /**
     * Invitation lifecycle ("extend" / "terminate" / "reactivate" / "delete") through the
     * `adminUpdateInvitationLifecycle` Cloud Function ONLY. The server decides and keeps status
     * values, expiry format, slug lookups and host sessions consistent; there is no direct-Firestore
     * fallback (it used a different status value and a different expiry type than the website).
     * "reactivate" requires [extendDays].
     */
    suspend fun adminUpdateInvitationLifecycle(
        invitationId: String,
        action: String,
        extendDays: Int? = null
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        val payload = mutableMapOf<String, Any?>(
            "invitationId" to invitationId,
            "action" to action
        )
        if (extendDays != null) payload["extendDays"] = extendDays
        try {
            val res = FirebaseProvider.callCloudFunction("adminUpdateInvitationLifecycle", payload)
            if (res["success"] != true) throw IllegalStateException("ردّ غير متوقع من الخادم")
            res
        } catch (e: Exception) {
            Log.e(TAG, "adminUpdateInvitationLifecycle($action) failed", e)
            throw IllegalStateException(
                FirebaseProvider.describeFunctionError("adminUpdateInvitationLifecycle", e), e
            )
        }
    }

    /**
     * Deletes an invitation (+ RSVPs, host vault, slug lookups) via the server. The paid order is kept.
     */
    suspend fun deleteInvitation(invitationId: String) = withContext(Dispatchers.IO) {
        adminUpdateInvitationLifecycle(invitationId, "delete")
    }

    /**
     * Custom Templates (custom_templates collection) CRUD operations
     */
    fun getCustomTemplatesFlow(): Flow<List<com.example.data.model.CustomTemplate>> = callbackFlow {
        if (auth.currentUser == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        val query = firestore.collection("custom_templates").limit(100)
        val registration = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Error fetching custom_templates: ${error.message}")
                trySend(emptyList())
                return@addSnapshotListener
            }
            val list = snapshot?.documents?.mapNotNull { doc -> com.example.data.model.CustomTemplate.fromDoc(doc) } ?: emptyList()
            trySend(list)
        }
        awaitClose { registration.remove() }
    }.flowOn(Dispatchers.IO)

    suspend fun saveCustomTemplate(template: com.example.data.model.CustomTemplate) = withContext(Dispatchers.IO) {
        val templateId = if (template.id.isBlank()) "template_${System.currentTimeMillis()}" else template.id
        val map = hashMapOf(
            "id" to templateId,
            "name" to template.name,
            "title" to template.name,
            "description" to template.description,
            "previewImageUrl" to template.previewImageUrl,
            "category" to template.category,
            "isActive" to template.isActive,
            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
        )
        if (template.id.isBlank()) {
            map["createdAt"] = com.google.firebase.firestore.FieldValue.serverTimestamp()
        }
        firestore.collection("custom_templates").document(templateId).set(map, com.google.firebase.firestore.SetOptions.merge()).await()
    }

    suspend fun toggleCustomTemplateActive(templateId: String, isActive: Boolean) = withContext(Dispatchers.IO) {
        firestore.collection("custom_templates").document(templateId).update("isActive", isActive).await()
    }

    suspend fun deleteCustomTemplate(templateId: String) = withContext(Dispatchers.IO) {
        firestore.collection("custom_templates").document(templateId).delete().await()
    }
}
