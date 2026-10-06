package com.example.ui.invitations

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.DataState
import com.example.data.model.Invitation
import com.example.data.repository.InvitationsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed class InvitationActionState {
    data object Idle : InvitationActionState()
    data class Loading(val message: String) : InvitationActionState()
    data class Success(val message: String) : InvitationActionState()
    data class Error(val message: String) : InvitationActionState()
}

class InvitationsViewModel(
    private val invitationsRepository: InvitationsRepository = InvitationsRepository()
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _statusFilter = MutableStateFlow<String?>("all")
    val statusFilter: StateFlow<String?> = _statusFilter.asStateFlow()

    private val _limit = MutableStateFlow<Long>(50)
    val limit: StateFlow<Long> = _limit.asStateFlow()

    private val _actionState = MutableStateFlow<InvitationActionState>(InvitationActionState.Idle)
    val actionState: StateFlow<InvitationActionState> = _actionState.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val invitationsState: StateFlow<DataState<List<Invitation>>> = combine(
        _statusFilter,
        _limit
    ) { filter, limitCount ->
        Pair(filter, limitCount)
    }.flatMapLatest { (filter, limitCount) ->
        invitationsRepository.getInvitationsFlow(filter, limitCount)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DataState.Loading)

    val filteredInvitations: StateFlow<DataState<List<Invitation>>> = combine(
        invitationsState,
        _searchQuery
    ) { state, query ->
        try {
            when (state) {
                is DataState.Loading -> DataState.Loading
                is DataState.Error -> DataState.Error(state.message)
                is DataState.Success -> {
                    val list = state.data
                    if (query.isBlank()) {
                        DataState.Success(list)
                    } else {
                        val q = query.trim().lowercase()
                        val filtered = list.filter { inv ->
                            try {
                                inv.coupleTitle.lowercase().contains(q) ||
                                        inv.slug.lowercase().contains(q) ||
                                        inv.venueName.lowercase().contains(q) ||
                                        inv.title.lowercase().contains(q)
                            } catch (e: Throwable) {
                                false
                            }
                        }
                        DataState.Success(filtered)
                    }
                }
            }
        } catch (e: Throwable) {
            DataState.Error("حدث خطأ أثناء فلترة وإعداد بيانات الدعوات: ${e.localizedMessage ?: "خطأ غير متوقع"}")
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DataState.Loading)

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
        _actionState.value = InvitationActionState.Idle
    }

    /**
     * Extends invitation lifecycle via `adminUpdateInvitationLifecycle` with action = "extend".
     */
    fun extendInvitation(invitationId: String, extendDays: Int) {
        viewModelScope.launch {
            _actionState.value = InvitationActionState.Loading("جاري تمديد صلاحية الدعوة $extendDays يوم...")
            try {
                invitationsRepository.adminUpdateInvitationLifecycle(invitationId, "extend", extendDays)
                _actionState.value = InvitationActionState.Success("تم تمديد صلاحية الدعوة بنجاح لمدة $extendDays يوم")
                _snackbarMessage.emit("تم تمديد صلاحية الدعوة بنجاح")
            } catch (e: Exception) {
                Log.e("InvitationsViewModel", "Error extending invitation", e)
                _actionState.value = InvitationActionState.Error("فشل تمديد الصلاحية: ${e.localizedMessage}")
                _snackbarMessage.emit("فشل التمديد: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Terminates invitation via `adminUpdateInvitationLifecycle` with action = "terminate".
     */
    fun terminateInvitation(invitationId: String) {
        viewModelScope.launch {
            _actionState.value = InvitationActionState.Loading("جاري إنهاء وإيقاف الدعوة...")
            try {
                invitationsRepository.adminUpdateInvitationLifecycle(invitationId, "terminate")
                _actionState.value = InvitationActionState.Success("تم إنهاء الدعوة بنجاح")
                _snackbarMessage.emit("تم إيقاف وإنهاء الدعوة")
            } catch (e: Exception) {
                Log.e("InvitationsViewModel", "Error terminating invitation", e)
                _actionState.value = InvitationActionState.Error("فشل إنهاء الدعوة: ${e.localizedMessage}")
                _snackbarMessage.emit("فشل إنهاء الدعوة: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Reactivates invitation via `adminUpdateInvitationLifecycle` with action = "reactivate".
     */
    fun reactivateInvitation(invitationId: String) {
        viewModelScope.launch {
            _actionState.value = InvitationActionState.Loading("جاري إعادة تفعيل الدعوة...")
            try {
                invitationsRepository.adminUpdateInvitationLifecycle(invitationId, "reactivate", 30)
                _actionState.value = InvitationActionState.Success("تم إعادة تفعيل الدعوة بنجاح")
                _snackbarMessage.emit("تمت إعادة تفعيل الدعوة بنجاح")
            } catch (e: Exception) {
                Log.e("InvitationsViewModel", "Error reactivating invitation", e)
                _actionState.value = InvitationActionState.Error("فشل إعادة التفعيل: ${e.localizedMessage}")
                _snackbarMessage.emit("فشل إعادة التفعيل: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Deletes the invitation through the server-side Cloud Function only (no client fallback).
     */
    fun deleteInvitation(invitationId: String) {
        viewModelScope.launch {
            _actionState.value = InvitationActionState.Loading("جاري حذف الدعوة من قاعدة البيانات...")
            try {
                invitationsRepository.deleteInvitation(invitationId)
                _actionState.value = InvitationActionState.Success("تم حذف الدعوة نهائياً")
                _snackbarMessage.emit("تم حذف الدعوة بنجاح")
            } catch (e: Exception) {
                Log.e("InvitationsViewModel", "Error deleting invitation", e)
                _actionState.value = InvitationActionState.Error("فشل حذف الدعوة: ${e.localizedMessage}")
                _snackbarMessage.emit("فشل الحذف: ${e.localizedMessage}")
            }
        }
    }

    val customTemplates: StateFlow<List<com.example.data.model.CustomTemplate>> =
        invitationsRepository.getCustomTemplatesFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveCustomTemplate(template: com.example.data.model.CustomTemplate) {
        viewModelScope.launch {
            try {
                invitationsRepository.saveCustomTemplate(template)
                _snackbarMessage.emit("تم حفظ القالب المخصص بنجاح")
            } catch (e: Exception) {
                _snackbarMessage.emit("فشل حفظ القالب: ${e.localizedMessage}")
            }
        }
    }

    fun toggleCustomTemplateActive(templateId: String, isActive: Boolean) {
        viewModelScope.launch {
            try {
                invitationsRepository.toggleCustomTemplateActive(templateId, isActive)
                _snackbarMessage.emit(if (isActive) "تم تفعيل القالب" else "تم تعطيل القالب")
            } catch (e: Exception) {
                _snackbarMessage.emit("تعذر تغيير حالة القالب: ${e.localizedMessage}")
            }
        }
    }

    fun deleteCustomTemplate(templateId: String) {
        viewModelScope.launch {
            try {
                invitationsRepository.deleteCustomTemplate(templateId)
                _snackbarMessage.emit("تم حذف القالب المخصص")
            } catch (e: Exception) {
                _snackbarMessage.emit("فشل حذف القالب: ${e.localizedMessage}")
            }
        }
    }

    fun copyLinks(context: Context, label: String, link: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, link)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "تم نسخ $label: $link", Toast.LENGTH_SHORT).show()
    }
}
