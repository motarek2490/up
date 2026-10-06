package com.example.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.FirebaseProvider
import com.example.data.model.DashboardStats
import com.example.data.repository.OrdersRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class DashboardUiState {
    data object Loading : DashboardUiState()
    data class Success(val stats: DashboardStats) : DashboardUiState()
    data class Error(val message: String) : DashboardUiState()
}

class DashboardViewModel(
    private val ordersRepository: OrdersRepository = OrdersRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        if (FirebaseProvider.auth.currentUser != null) {
            loadDashboardStats()
        } else {
            _uiState.value = DashboardUiState.Success(DashboardStats())
        }
    }

    fun loadDashboardStats() {
        if (FirebaseProvider.auth.currentUser == null) {
            _uiState.value = DashboardUiState.Success(DashboardStats())
            _isRefreshing.value = false
            return
        }

        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                val stats = ordersRepository.getDashboardStats()
                _uiState.value = DashboardUiState.Success(stats)
            } catch (e: Exception) {
                _uiState.value = DashboardUiState.Error("تعذر تحميل بيانات الإحصائيات: ${e.localizedMessage}")
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
