package com.example.ui.orders

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.DataState
import com.example.data.model.Order
import com.example.data.repository.HostAccess
import com.example.data.repository.OrdersRepository
import com.example.util.WebsiteUrlProvider
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.net.URLEncoder

sealed class OrderActionState {
    data object Idle : OrderActionState()
    data class Loading(
        val message: String,
        val percentage: Int = 0,
        val progressFraction: Float = 0f
    ) : OrderActionState()
    data class ApprovedSuccess(
        val order: Order,
        /** Host portal username (= invitation slug). */
        val username: String,
        /** One-time host access code (null if it could not be generated). */
        val hostCode: String?,
        val guestUrl: String,
        val hostPortalUrl: String,
        val hostCredentialsFailed: Boolean = false
    ) : OrderActionState()
    data class RejectedSuccess(val orderId: String) : OrderActionState()
    data class Error(val message: String) : OrderActionState()
}

class OrdersViewModel(
    private val ordersRepository: OrdersRepository = OrdersRepository()
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _statusFilter = MutableStateFlow<String?>("all")
    val statusFilter: StateFlow<String?> = _statusFilter.asStateFlow()

    private val _limit = MutableStateFlow<Long>(50)
    val limit: StateFlow<Long> = _limit.asStateFlow()

    private val _actionState = MutableStateFlow<OrderActionState>(OrderActionState.Idle)
    val actionState: StateFlow<OrderActionState> = _actionState.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val ordersState: StateFlow<DataState<List<Order>>> = combine(
        _statusFilter,
        _limit
    ) { filter, limitCount ->
        Pair(filter, limitCount)
    }.flatMapLatest { (filter, limitCount) ->
        ordersRepository.getOrdersFlow(filter, limitCount)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DataState.Loading)

    val filteredOrders: StateFlow<List<Order>> = combine(
        ordersState,
        _searchQuery
    ) { state, query ->
        when (state) {
            is DataState.Success -> {
                val list = state.data
                if (query.isBlank()) {
                    list
                } else {
                    val q = query.trim().lowercase()
                    list.filter { order ->
                        order.orderNumber.lowercase().contains(q) ||
                                order.clientName.lowercase().contains(q) ||
                                order.phoneNumber.contains(q) ||
                                order.vodafoneCashNumber.contains(q) ||
                                order.packageName.lowercase().contains(q)
                    }
                }
            }
            else -> emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setStatusFilter(filter: String?) {
        _statusFilter.value = filter
    }

    fun loadMore() {
        _limit.value += 50
    }

    fun clearActionState() {
        _actionState.value = OrderActionState.Idle
    }

    private fun successState(order: Order, access: HostAccess, credsFailed: Boolean) =
        OrderActionState.ApprovedSuccess(
            order = order.copy(status = "approved", invitationId = access.invitationId, slug = access.slug),
            username = access.username,
            hostCode = access.accessCode,
            guestUrl = WebsiteUrlProvider.getGuestUrl(access.slug),
            hostPortalUrl = WebsiteUrlProvider.getHostPortalUrl(access.slug),
            hostCredentialsFailed = credsFailed
        )

    /**
     * Approves an order. Everything (invitation, slug, host credentials) is done by the server
     * in one call; the app only displays the result (username = slug, one-time access code, links).
     */
    fun approveOrder(order: Order) {
        if (_actionState.value is OrderActionState.Loading) {
            Log.d("OrdersViewModel", "approveOrder ignored: operation already in progress")
            return
        }

        viewModelScope.launch {
            _actionState.value = OrderActionState.Loading(
                message = "جاري اعتماد الطلب ونشر الدعوة وتوليد بيانات المضيف...",
                percentage = 50,
                progressFraction = 0.5f
            )
            try {
                val access = ordersRepository.approveOrder(order.id)
                // If the order was ALREADY approved (e.g. from the website admin) the server does not rotate
                // the host code: customers may already hold it. Only an explicit "regenerate" press does.

                _actionState.value = OrderActionState.Loading("اكتملت العملية!", 100, 1.0f)
                kotlinx.coroutines.delay(200)

                val credsFailed = access.credentialsFailed || access.accessCode == null
                _actionState.value = successState(order, access, credsFailed)

                _snackbarMessage.emit(
                    when {
                        access.alreadyApproved -> "الطلب #${order.orderNumber} معتمد مسبقاً. الكود القديم لا يُعرض ثانيةً؛ اضغط «إعادة توليد» فقط إن لزم."
                        credsFailed -> "تم اعتماد الطلب #${order.orderNumber}، لكن تعذر توليد كود المضيف — أعد المحاولة"
                        else -> "تم اعتماد وتفعيل الطلب #${order.orderNumber} ونشر الدعوة بنجاح"
                    }
                )
            } catch (e: Exception) {
                Log.e("OrdersViewModel", "Failed to approve order", e)
                _actionState.value = OrderActionState.Error("فشل اعتماد الطلب: ${e.localizedMessage}")
                _snackbarMessage.emit("خطأ أثناء الاعتماد: ${e.localizedMessage}")
            }
        }
    }

    /** Issues a NEW host access code (the old one stops working). */
    fun regenerateHostCredentials(invitationId: String, currentOrder: Order) {
        viewModelScope.launch {
            _actionState.value = OrderActionState.Loading("جاري توليد كود مضيف جديد...")
            try {
                val access = ordersRepository.setHostCredentials(invitationId.ifBlank { currentOrder.invitationId })
                _actionState.value = successState(currentOrder, access, credsFailed = access.accessCode == null)
                _snackbarMessage.emit("تم توليد كود مضيف جديد")
            } catch (e: Exception) {
                Log.e("OrdersViewModel", "Failed to regenerate host credentials", e)
                _actionState.value = OrderActionState.Error("فشل توليد كود المضيف: ${e.localizedMessage}")
                _snackbarMessage.emit("فشل توليد الكود: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Rejects order strictly via existing Cloud Function `rejectOrder`.
     */
    fun rejectOrder(order: Order, reason: String) {
        if (_actionState.value is OrderActionState.Loading) return

        viewModelScope.launch {
            _actionState.value = OrderActionState.Loading(
                message = "الاتصال بالسحابة وإرسال سبب الرفض...",
                percentage = 35,
                progressFraction = 0.35f
            )
            try {
                ordersRepository.rejectOrder(order.id, reason)
                _actionState.value = OrderActionState.Loading(
                    message = "تم تحديث حالة الطلب إلى مرفوض",
                    percentage = 100,
                    progressFraction = 1.0f
                )
                kotlinx.coroutines.delay(200)
                _actionState.value = OrderActionState.RejectedSuccess(order.id)
                _snackbarMessage.emit("تم رفض الطلب #${order.orderNumber}")
            } catch (e: Exception) {
                Log.e("OrdersViewModel", "Failed to reject order", e)
                _actionState.value = OrderActionState.Error("فشل رفض الطلب: ${e.localizedMessage}")
                _snackbarMessage.emit("خطأ أثناء الرفض: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Permanently deletes an order from the database
     */
    fun deleteOrder(orderId: String, orderNumber: String) {
        viewModelScope.launch {
            _actionState.value = OrderActionState.Loading("جاري حذف الطلب نهائياً من قاعدة البيانات...")
            try {
                ordersRepository.deleteOrder(orderId)
                _actionState.value = OrderActionState.Idle
                _snackbarMessage.emit("تم حذف الطلب #$orderNumber نهائياً بنجاح")
            } catch (e: Exception) {
                Log.e("OrdersViewModel", "Failed to delete order", e)
                _actionState.value = OrderActionState.Error("فشل حذف الطلب: ${e.localizedMessage}")
                _snackbarMessage.emit("خطأ أثناء الحذف: ${e.localizedMessage}")
            }
        }
    }

    /**
     * WhatsApp messaging helper: handles international numbers and Egyptian local numbers,
     * and catches missing app or link failures gracefully.
     */
    fun openWhatsApp(
        context: Context,
        phoneNumber: String,
        message: String
    ) {
        viewModelScope.launch {
            try {
                var cleanPhone = phoneNumber.filter { it.isDigit() || it == '+' }.trim()
                if (cleanPhone.startsWith("+")) {
                    cleanPhone = cleanPhone.removePrefix("+")
                } else if (cleanPhone.startsWith("00")) {
                    cleanPhone = cleanPhone.removePrefix("00")
                }

                // If Egyptian local 01XXXXXXXXX
                if (cleanPhone.startsWith("01") && cleanPhone.length == 11) {
                    cleanPhone = "2$cleanPhone"
                }

                if (cleanPhone.isBlank()) {
                    _snackbarMessage.emit("رقم الهاتف غير صالح للمراسلة عبر واتساب")
                    return@launch
                }

                val encodedMessage = URLEncoder.encode(message, "UTF-8")
                val url = "https://wa.me/$cleanPhone?text=$encodedMessage"
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse(url)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Log.w("OrdersViewModel", "WhatsApp activity not found", e)
                _snackbarMessage.emit("تطبيق WhatsApp غير مثبت على هذا الجهاز")
            } catch (e: Exception) {
                Log.e("OrdersViewModel", "Failed to open WhatsApp", e)
                _snackbarMessage.emit("تعذر فتح تطبيق WhatsApp: ${e.localizedMessage}")
            }
        }
    }
}
