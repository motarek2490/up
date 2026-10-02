package com.example.data.repository

import android.util.Log
import com.example.data.FirebaseProvider
import com.example.data.model.DashboardStats
import com.example.data.model.DataState
import com.example.data.model.Order
import com.example.data.model.getSafeString
import com.google.firebase.firestore.AggregateField
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.SecureRandom

class OrdersRepository {
    private val firestore get() = FirebaseProvider.firestore
    private val functions get() = FirebaseProvider.functions
    private val auth get() = FirebaseProvider.auth

    companion object {
        private const val TAG = "OrdersRepository"

        private val secureRandom = SecureRandom()
        private const val SLUG_SUFFIX_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
        private const val MAX_SLUG_LENGTH = 40
        private const val MAX_SLUG_ATTEMPTS = 6

        /** Cloud Function errors that are a definitive server decision: never "work around" them. */
        private val DEFINITIVE_FUNCTION_ERRORS = setOf(
            FirebaseFunctionsException.Code.PERMISSION_DENIED,
            FirebaseFunctionsException.Code.UNAUTHENTICATED,
            FirebaseFunctionsException.Code.INVALID_ARGUMENT,
            FirebaseFunctionsException.Code.FAILED_PRECONDITION,
            FirebaseFunctionsException.Code.ALREADY_EXISTS
        )

        /** Cryptographically secure 6-digit host access code (100000..999999). */
        fun generateHostCode(): String = (secureRandom.nextInt(900_000) + 100_000).toString()

        private fun randomSlugSuffix(length: Int = 5): String =
            buildString { repeat(length) { append(SLUG_SUFFIX_ALPHABET[secureRandom.nextInt(SLUG_SUFFIX_ALPHABET.length)]) } }

        /** Builds a URL-safe slug base out of the couple's names. */
        internal fun buildSlugBase(groomName: String, brideName: String, orderNumber: String): String {
            val raw = if (groomName.isNotBlank() && brideName.isNotBlank()) {
                "${groomName.trim()}-${brideName.trim()}"
            } else {
                ""
            }
            val cleaned = raw
                .replace(Regex("[^a-zA-Z0-9\\u0600-\\u06FF-]"), "-")
                .replace(Regex("-{2,}"), "-")
                .trim('-')
                .take(MAX_SLUG_LENGTH)
                .trim('-')
            return cleaned.ifBlank { "invitation-$orderNumber".take(MAX_SLUG_LENGTH) }
        }
    }

    /**
     * Live orders list, newest first.
     * The status filter and ordering are applied on the SERVER before `limit`, so the newest N
     * matching orders are always returned (previously an arbitrary first-N was sorted locally and
     * recent orders could silently disappear once the collection grew past the limit).
     * Needs the composite index (status ASC, createdAt DESC) from firestore.indexes.json; if it is
     * not deployed yet, falls back to an unordered query + local sort instead of failing.
     */
    fun getOrdersFlow(
        statusFilter: String? = null,
        limit: Long = 50
    ): Flow<DataState<List<Order>>> = callbackFlow {
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
            var query: Query = firestore.collection("orders")
            if (serverStatus != null) query = query.whereEqualTo("status", serverStatus)
            if (ordered) query = query.orderBy("createdAt", Query.Direction.DESCENDING)

            registration = query.limit(limit).addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (ordered && error.code == FirebaseFirestoreException.Code.FAILED_PRECONDITION) {
                        Log.w(TAG, "Orders index missing, falling back to unordered query: ${error.message}")
                        attach(ordered = false)
                        return@addSnapshotListener
                    }
                    val errorMsg = if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        "غير مصرّح بالوصول للطلبات. يرجى التأكد من صلاحية الإدارة (admin claim)."
                    } else {
                        "حدث خطأ أثناء تحميل الطلبات: ${error.localizedMessage}"
                    }
                    Log.e(TAG, "Error listening to orders collection", error)
                    trySend(DataState.Error(errorMsg))
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val orders = snapshot.documents
                        .mapNotNull { doc -> Order.fromDoc(doc) }
                        .sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
                    trySend(DataState.Success(orders))
                }
            }
        }

        attach(ordered = true)
        awaitClose { registration?.remove() }
    }.flowOn(Dispatchers.IO)

    /**
     * Calculates dashboard statistics:
     * - Pending orders count via server aggregation
     * - Published invitations count via server aggregation
     * - Total approved revenue via server aggregate sum("amount")
     * - Recent 10 orders using shared Order.fromDoc mapper
     * Does NOT swallow errors, allowing DashboardUiState.Error to trigger in ViewModel.
     */
    suspend fun getDashboardStats(): DashboardStats = withContext(Dispatchers.IO) {
        val user = auth.currentUser ?: throw IllegalStateException("يجب تسجيل الدخول كمدير أولاً")

        val ordersCollection = firestore.collection("orders")
        val invitationsCollection = firestore.collection("invitations")

        // 1. Pending orders count
        val pendingCount = try {
            val pendingQuery = ordersCollection.whereEqualTo("status", "pending")
            val snapshot = pendingQuery.count().get(AggregateSource.SERVER).await()
            snapshot.count
        } catch (e: Exception) {
            Log.w(TAG, "Server count failed for pending orders: ${e.message}")
            try {
                ordersCollection.whereEqualTo("status", "pending").get().await().size().toLong()
            } catch (err: Exception) {
                0L
            }
        }

        // 2. Published invitations count
        val publishedCount = try {
            val publishedQuery = invitationsCollection.whereEqualTo("status", "published")
            val snapshot = publishedQuery.count().get(AggregateSource.SERVER).await()
            snapshot.count
        } catch (e: Exception) {
            Log.w(TAG, "Server count failed for published invitations: ${e.message}")
            try {
                invitationsCollection.whereEqualTo("status", "published").get().await().size().toLong()
            } catch (err: Exception) {
                0L
            }
        }

        // 3. Total approved revenue
        val totalRevenue = try {
            val sumField = AggregateField.sum("amount")
            val approvedQuery = ordersCollection.whereEqualTo("status", "approved")
            val aggregateSnapshot = approvedQuery.aggregate(sumField).get(AggregateSource.SERVER).await()
            (aggregateSnapshot.get(sumField) as? Number)?.toDouble() ?: 0.0
        } catch (e: Exception) {
            Log.w(TAG, "Server aggregate sum failed for approved orders: ${e.message}")
            try {
                val docs = ordersCollection.whereEqualTo("status", "approved").get().await()
                var sum = 0.0
                for (doc in docs.documents) {
                    val amt = doc.getDouble("amount") ?: doc.getLong("amount")?.toDouble() ?: 0.0
                    sum += amt
                }
                sum
            } catch (err: Exception) {
                0.0
            }
        }

        // 4. Total Visitors
        val visitorsCount = try {
            val statsDoc = firestore.collection("settings").document("visitor_stats").get().await()
            statsDoc.getLong("totalVisitors") ?: statsDoc.getLong("counter") ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Failed fetching visitor stats: ${e.message}")
            0L
        }

        // 5. Recent orders with safe index fallback
        val recentOrders = try {
            val recentDocs = ordersCollection
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(10)
                .get()
                .await()
            recentDocs.documents.mapNotNull { doc -> Order.fromDoc(doc) }
        } catch (e: Exception) {
            Log.w(TAG, "Ordered query failed, falling back to simple limit: ${e.message}")
            try {
                val simpleDocs = ordersCollection.limit(10).get().await()
                simpleDocs.documents.mapNotNull { doc -> Order.fromDoc(doc) }
                    .sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
            } catch (err: Exception) {
                emptyList()
            }
        }

        DashboardStats(
            pendingOrdersCount = pendingCount,
            publishedInvitationsCount = publishedCount,
            totalApprovedRevenue = totalRevenue,
            totalVisitorsCount = visitorsCount,
            recentOrders = recentOrders
        )
    }

    /** True when it is safe to retry a failed Cloud Function call through the direct-Firestore path. */
    private fun canFallBackFrom(e: Exception): Boolean =
        !(e is FirebaseFunctionsException && e.code in DEFINITIVE_FUNCTION_ERRORS)

    /**
     * Approves an order by calling the Cloud Function `approveOrder`.
     *
     * If the function is unreachable / not deployed, a direct Firestore fallback is used. The
     * fallback is hardened:
     *  - Invitation + order are written in ONE transaction (all-or-nothing).
     *  - A slug that already belongs to a DIFFERENT order is never overwritten: a free slug is
     *    chosen (random suffix) instead. Two couples with the same names can no longer merge.
     *  - Host codes come from SecureRandom.
     * Definitive server answers (permission denied, invalid argument...) are NOT bypassed.
     */
    suspend fun approveOrder(orderId: String): Map<String, Any?> = withContext(Dispatchers.IO) {
        val data = mapOf("orderId" to orderId)
        try {
            val res = FirebaseProvider.callCloudFunction("approveOrder", data)
            if (res.isNotEmpty() && res["success"] == true) {
                return@withContext res
            }
        } catch (e: Exception) {
            if (!canFallBackFrom(e)) throw e
            Log.w(TAG, "Cloud function approveOrder not available/failed, activating direct Firestore fallback", e)
        }

        val orderRef = firestore.collection("orders").document(orderId)
        val orderSnap = orderRef.get().await()
        if (!orderSnap.exists()) {
            throw IllegalStateException("لم يتم العثور على الطلب رقم $orderId في قاعدة البيانات")
        }

        // Slug explicitly attached to the order by the website (if any)
        val explicitSlug = (orderSnap.getSafeString("slug")
            ?: orderSnap.getSafeString("invitationSlug")
            ?: orderSnap.getSafeString("invitationId")
            ?: "").trim()
        if (explicitSlug.contains('/')) {
            throw IllegalStateException("معرّف الدعوة المرتبط بالطلب غير صالح: $explicitSlug")
        }

        val clientName = orderSnap.getSafeString("customerName")
            ?: orderSnap.getSafeString("clientName")
            ?: orderSnap.getSafeString("name")
            ?: "عميل فريدا"
        val clientPhone = orderSnap.getSafeString("customerPhone")
            ?: orderSnap.getSafeString("phoneNumber")
            ?: orderSnap.getSafeString("phone")
            ?: ""
        val packageTier = orderSnap.getSafeString("planTier")
            ?: orderSnap.getSafeString("packageName")
            ?: "الملكية"

        @Suppress("UNCHECKED_CAST")
        val eventDetails = orderSnap.get("eventDetails") as? Map<String, Any?>
        val groomName = eventDetails?.get("groomName") as? String
            ?: orderSnap.getSafeString("groomName")
            ?: clientName
        val brideName = eventDetails?.get("brideName") as? String
            ?: orderSnap.getSafeString("brideName")
            ?: ""
        val eventDate = eventDetails?.get("eventDate") as? String
            ?: orderSnap.getSafeString("eventDate")
            ?: ""
        val venueName = eventDetails?.get("venueName") as? String
            ?: orderSnap.getSafeString("venueName")
            ?: ""

        val orderNum = orderSnap.getSafeString("orderNumber") ?: orderId.takeLast(6)
        val baseSlug = explicitSlug.ifBlank { buildSlugBase(groomName, brideName, orderNum) }
        val orderHostCode = (orderSnap.getSafeString("hostCode")
            ?: orderSnap.getSafeString("accessCode")
            ?: orderSnap.getSafeString("hostPassword"))?.takeIf { it.isNotBlank() }

        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.MONTH, 3)
        val expiresAt = com.google.firebase.Timestamp(cal.time)
        val coupleTitle = if (brideName.isNotBlank()) "$groomName & $brideName" else "دعوة $groomName"

        // An order that already points at a slug may only claim THAT slug; otherwise try suffixed ones.
        val maxAttempts = if (explicitSlug.isNotBlank()) 1 else MAX_SLUG_ATTEMPTS
        var resolvedSlug: String? = null
        var resolvedHostCode = ""

        for (attempt in 0 until maxAttempts) {
            val candidate = if (attempt == 0) {
                baseSlug
            } else {
                "${baseSlug.take(MAX_SLUG_LENGTH - 6).trimEnd('-')}-${randomSlugSuffix()}"
            }
            val invRef = firestore.collection("invitations").document(candidate)

            val claimedCode: String? = firestore.runTransaction<String?> { tx ->
                val existing = tx.get(invRef)
                val owner = existing.getSafeString("orderId")
                if (existing.exists() && !owner.isNullOrBlank() && owner != orderId) {
                    return@runTransaction null // slug belongs to another order
                }

                val hostCode = orderHostCode
                    ?: existing.getSafeString("hostCode")?.takeIf { it.isNotBlank() }
                    ?: existing.getSafeString("accessCode")?.takeIf { it.isNotBlank() }
                    ?: generateHostCode()

                val guestUrl = com.example.util.WebsiteUrlProvider.getGuestUrl(candidate)
                val hostPortalUrl = com.example.util.WebsiteUrlProvider.getHostPortalUrl(candidate)

                val invData = hashMapOf<String, Any?>(
                    "id" to candidate,
                    "slug" to candidate,
                    "orderId" to orderId,
                    "customerName" to clientName,
                    "customerPhone" to clientPhone,
                    "title" to coupleTitle,
                    "groomName" to groomName,
                    "brideName" to brideName,
                    "eventDate" to eventDate,
                    "venueName" to venueName,
                    "packageTier" to packageTier,
                    "planTier" to packageTier,
                    "status" to "published",
                    "hostCode" to hostCode,
                    "accessCode" to hostCode,
                    // kept for compatibility with the website, which may still read this field
                    "hostPassword" to hostCode,
                    "hostPortalUrl" to hostPortalUrl,
                    "guestUrl" to guestUrl,
                    "url" to guestUrl,
                    "expiresAt" to expiresAt,
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "publishedAt" to FieldValue.serverTimestamp(),
                    "isActive" to true,
                    "eventDetails" to mapOf(
                        "groomName" to groomName,
                        "brideName" to brideName,
                        "eventDate" to eventDate,
                        "venueName" to venueName,
                        "eventTitle" to coupleTitle
                    )
                )
                val orderUpdate = hashMapOf<String, Any?>(
                    "status" to "approved",
                    "invitationId" to candidate,
                    "slug" to candidate,
                    "hostCode" to hostCode,
                    "accessCode" to hostCode,
                    "hostPortalUrl" to hostPortalUrl,
                    "guestUrl" to guestUrl,
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "approvedAt" to FieldValue.serverTimestamp()
                )

                tx.set(invRef, invData, SetOptions.merge())
                tx.set(orderRef, orderUpdate, SetOptions.merge())
                hostCode
            }.await()

            if (claimedCode != null) {
                resolvedSlug = candidate
                resolvedHostCode = claimedCode
                break
            }
        }

        val slug = resolvedSlug ?: throw IllegalStateException(
            if (explicitSlug.isNotBlank()) {
                "الدعوة ($explicitSlug) مرتبطة بطلب آخر، لا يمكن اعتماد هذا الطلب عليها. راجع الطلبات المكررة."
            } else {
                "تعذر إيجاد رابط (slug) متاح للدعوة، يرجى إعادة المحاولة."
            }
        )

        Log.i(TAG, "Approved order $orderId and linked it to invitation $slug (fallback, transactional)")

        val guestUrl = com.example.util.WebsiteUrlProvider.getGuestUrl(slug)
        val hostPortalUrl = com.example.util.WebsiteUrlProvider.getHostPortalUrl(slug)
        mapOf(
            "success" to true,
            "hostCode" to resolvedHostCode,
            "accessCode" to resolvedHostCode,
            "hostPortalUrl" to hostPortalUrl,
            "url" to hostPortalUrl,
            "guestUrl" to guestUrl,
            "slug" to slug,
            "invitationId" to slug,
            "fallback" to true
        )
    }

    /**
     * Generates or retrieves host credentials by calling `setHostCredentials`.
     * If the function fails, it saves and verifies real credentials in Firestore.
     */
    suspend fun setHostCredentials(invitationId: String, orderId: String): Map<String, Any?> = withContext(Dispatchers.IO) {
        val data = mapOf(
            "invitationId" to invitationId,
            "orderId" to orderId
        )
        try {
            val resMap = FirebaseProvider.callCloudFunction("setHostCredentials", data)
            if (resMap.isNotEmpty() && resMap["success"] == true) return@withContext resMap
        } catch (e: Exception) {
            if (!canFallBackFrom(e)) throw e
            Log.w(TAG, "Cloud function setHostCredentials failed, activating direct Firestore server update", e)
        }

        val targetId = invitationId.ifBlank { orderId }
        val invRef = firestore.collection("invitations").document(targetId)
        val invSnap = try { invRef.get().await() } catch (e: Exception) { null }

        var hostCode = invSnap?.getString("hostCode") ?: invSnap?.getString("accessCode")

        if (hostCode.isNullOrBlank() && orderId.isNotBlank()) {
            val orderSnap = try { firestore.collection("orders").document(orderId).get().await() } catch (e: Exception) { null }
            hostCode = orderSnap?.getString("hostCode") ?: orderSnap?.getString("accessCode")
        }

        if (hostCode.isNullOrBlank()) {
            hostCode = generateHostCode()
        }

        val slug = invSnap?.getString("slug") ?: targetId
        val hostPortalUrl = com.example.util.WebsiteUrlProvider.getHostPortalUrl(slug)
        val guestUrl = com.example.util.WebsiteUrlProvider.getGuestUrl(slug)

        // Save directly to invitation
        val invCreds = hashMapOf(
            "id" to slug,
            "slug" to slug,
            "hostCode" to hostCode,
            "accessCode" to hostCode,
            "hostPortalUrl" to hostPortalUrl,
            "guestUrl" to guestUrl,
            "hostCredentialsFailed" to false,
            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
        )
        invRef.set(invCreds, com.google.firebase.firestore.SetOptions.merge()).await()

        // Also save to order if orderId is provided
        if (orderId.isNotBlank()) {
            val orderCreds = hashMapOf(
                "hostCode" to hostCode,
                "accessCode" to hostCode,
                "hostPortalUrl" to hostPortalUrl,
                "guestUrl" to guestUrl,
                "invitationId" to slug,
                "slug" to slug,
                "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )
            firestore.collection("orders").document(orderId).set(orderCreds, com.google.firebase.firestore.SetOptions.merge()).await()
        }

        mapOf(
            "success" to true,
            "hostCode" to hostCode,
            "accessCode" to hostCode,
            "hostPortalUrl" to hostPortalUrl,
            "url" to hostPortalUrl,
            "guestUrl" to guestUrl,
            "slug" to slug,
            "hostCredentialsFailed" to false,
            "fallback" to true
        )
    }

    /**
     * Rejects an order with a reason by calling `rejectOrder`.
     * If the function fails, it falls back to direct Firestore update to guarantee stability.
     */
    suspend fun rejectOrder(orderId: String, reason: String): Map<String, Any?> = withContext(Dispatchers.IO) {
        val data = mapOf(
            "orderId" to orderId,
            "reason" to reason
        )
        try {
            FirebaseProvider.callCloudFunction("rejectOrder", data)
        } catch (e: Exception) {
            if (!canFallBackFrom(e)) throw e
            Log.w(TAG, "Cloud function rejectOrder failed, falling back to direct Firestore update", e)
            val orderRef = firestore.collection("orders").document(orderId)
            orderRef.update(
                "status", "rejected",
                "rejectionReason", reason,
                "updatedAt", com.google.firebase.firestore.FieldValue.serverTimestamp()
            ).await()
            
            // Set corresponding invitation status to draft/rejected if linked
            val orderSnap = orderRef.get().await()
            val invitationId = orderSnap.getString("invitationId")
            if (!invitationId.isNullOrBlank()) {
                val invRef = firestore.collection("invitations").document(invitationId)
                val invSnap = invRef.get().await()
                val owner = invSnap.getSafeString("orderId")
                // Only touch the invitation if it really belongs to THIS order; never unpublish
                // another customer's invitation that happens to share the slug.
                if (invSnap.exists() && (owner.isNullOrBlank() || owner == orderId)) {
                    invRef.update(
                        "status", "draft",
                        "updatedAt", com.google.firebase.firestore.FieldValue.serverTimestamp()
                    ).await()
                }
            }
            mapOf("success" to true, "fallback" to true)
        }
    }

    /**
     * Deletes an order permanently from the Firestore database.
     */
    suspend fun deleteOrder(orderId: String): Unit = withContext(Dispatchers.IO) {
        val user = auth.currentUser ?: throw IllegalStateException("يجب تسجيل الدخول كمدير أولاً")
        firestore.collection("orders").document(orderId).delete().await()
    }
}
