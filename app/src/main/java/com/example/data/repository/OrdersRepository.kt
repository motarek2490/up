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

/** Result of approving an order / regenerating host credentials. [accessCode] is shown once. */
data class HostAccess(
    val invitationId: String,
    val slug: String,
    /** The host logs into the portal with this username (the invitation slug) + [accessCode]. */
    val username: String,
    val accessCode: String?,
    val credentialsFailed: Boolean = false,
    val alreadyApproved: Boolean = false
)

class OrdersRepository {
    private val firestore get() = FirebaseProvider.firestore
    private val functions get() = FirebaseProvider.functions
    private val auth get() = FirebaseProvider.auth

    companion object {
        private const val TAG = "OrdersRepository"
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

    private fun asString(v: Any?): String? = (v as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private fun toHostAccess(res: Map<String, Any?>, fallbackInvitationId: String): HostAccess {
        val invitationId = asString(res["invitationId"]) ?: fallbackInvitationId
        // The REAL slug is what the website resolves links with. The invitation document id is NOT a
        // valid replacement (using it produced links that open "no invitation with this data").
        val slug = asString(res["slug"])
            ?: throw IllegalStateException("الخادم لم يُرجع الـ slug الحقيقي للدعوة. انشر آخر نسخة من الدوال: firebase deploy --only functions")
        return HostAccess(
            invitationId = invitationId,
            slug = slug,
            username = asString(res["hostUsername"]) ?: slug,
            accessCode = asString(res["accessCode"]),
            credentialsFailed = res["credentialsError"] != null,
            alreadyApproved = res["alreadyApproved"] == true
        )
    }

    /**
     * Approves an order through the `approveOrder` Cloud Function ONLY.
     *
     * The function does everything server-side in one transaction (order -> approved, invitation
     * created/published, unique slug + /slugs lookup, host credentials as a scrypt hash) and returns
     * the slug (= host username) and the one-time access code.
     *
     * There is intentionally NO direct-Firestore fallback anymore: it wrote the host password in
     * plaintext into a publicly readable invitation document, never created the hashed credentials the
     * website's hostLogin verifies, and could produce links the website cannot resolve.
     */
    suspend fun approveOrder(orderId: String): HostAccess = withContext(Dispatchers.IO) {
        try {
            val res = FirebaseProvider.callCloudFunction("approveOrder", mapOf("orderId" to orderId))
            if (res["success"] != true) throw IllegalStateException("ردّ غير متوقع من الخادم")
            toHostAccess(res, fallbackInvitationId = orderId)
        } catch (e: Exception) {
            Log.e(TAG, "approveOrder failed", e)
            throw IllegalStateException(FirebaseProvider.describeFunctionError("approveOrder", e), e)
        }
    }

    /**
     * Generates a NEW host access code (the previous one stops working and active host sessions are
     * revoked). The plaintext code is only returned here, once; the server stores just a hash.
     */
    suspend fun setHostCredentials(invitationId: String): HostAccess = withContext(Dispatchers.IO) {
        try {
            val res = FirebaseProvider.callCloudFunction("setHostCredentials", mapOf("invitationId" to invitationId))
            if (res["success"] != true) throw IllegalStateException("ردّ غير متوقع من الخادم")
            toHostAccess(res, fallbackInvitationId = invitationId)
        } catch (e: Exception) {
            Log.e(TAG, "setHostCredentials failed", e)
            throw IllegalStateException(FirebaseProvider.describeFunctionError("setHostCredentials", e), e)
        }
    }

    /** Rejects a pending order through the `rejectOrder` Cloud Function (server decides, no bypass). */
    suspend fun rejectOrder(orderId: String, reason: String): Unit = withContext(Dispatchers.IO) {
        try {
            val res = FirebaseProvider.callCloudFunction("rejectOrder", mapOf("orderId" to orderId, "reason" to reason))
            if (res["success"] != true) throw IllegalStateException("ردّ غير متوقع من الخادم")
            Unit
        } catch (e: Exception) {
            Log.e(TAG, "rejectOrder failed", e)
            throw IllegalStateException(FirebaseProvider.describeFunctionError("rejectOrder", e), e)
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
