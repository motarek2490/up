package com.example.data.repository

import android.util.Log
import com.example.data.FirebaseProvider
import com.example.data.model.DataState
import com.example.data.model.Invitation
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import com.google.firebase.functions.FirebaseFunctionsException

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
     * Updates invitation lifecycle via `adminUpdateInvitationLifecycle` cloud function.
     * "extend" / "terminate" / "reactivate" fall back to a direct Firestore update if the function
     * is unreachable. "delete" NEVER falls back (server-side only).
     */
    suspend fun adminUpdateInvitationLifecycle(
        invitationId: String,
        action: String,
        extendDays: Int? = null
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        val payload = mutableMapOf<String, Any>(
            "invitationId" to invitationId,
            "action" to action
        )
        if (extendDays != null) {
            payload["extendDays"] = extendDays
        }
        try {
            FirebaseProvider.callCloudFunction("adminUpdateInvitationLifecycle", payload)
        } catch (e: Exception) {
            // Deletion is destructive and irreversible: it must only ever go through the server-side
            // function. Likewise never bypass a definitive server refusal (permission, bad input...).
            if (action == "delete") throw e
            if (e is FirebaseFunctionsException && e.code in DEFINITIVE_FUNCTION_ERRORS) throw e

            Log.w(TAG, "Cloud function adminUpdateInvitationLifecycle failed, falling back to direct Firestore update", e)
            val docRef = firestore.collection("invitations").document(invitationId)
            when (action) {
                "extend" -> {
                    val days = extendDays ?: 30
                    val snapshot = docRef.get().await()
                    val currentExpiresAt = snapshot.getTimestamp("expiresAt")?.toDate() ?: java.util.Date()
                    val cal = java.util.Calendar.getInstance()
                    cal.time = currentExpiresAt
                    cal.add(java.util.Calendar.DAY_OF_YEAR, days)
                    docRef.update("expiresAt", com.google.firebase.Timestamp(cal.time)).await()
                }
                "terminate" -> {
                    docRef.update("status", "terminated", "expiresAt", com.google.firebase.Timestamp(java.util.Date())).await()
                }
                "reactivate" -> {
                    val cal = java.util.Calendar.getInstance()
                    cal.add(java.util.Calendar.DAY_OF_YEAR, 30)
                    docRef.update("status", "published", "expiresAt", com.google.firebase.Timestamp(cal.time)).await()
                }
                else -> throw e
            }
            mapOf("success" to true, "fallback" to true)
        }
    }

    /**
     * Deletes an invitation via adminUpdateInvitationLifecycle(action = "delete").
     * Server-side only: if the function is unavailable the deletion fails with an error.
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
