package com.example.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AdminConfig
import com.example.data.model.PublicConfig
import com.example.data.repository.AuthRepository
import com.example.data.repository.SettingsDataState
import com.example.data.repository.SettingsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed class SettingsSaveState {
    data object Idle : SettingsSaveState()
    data object Saving : SettingsSaveState()
    data class Success(val message: String) : SettingsSaveState()
    data class Error(val message: String) : SettingsSaveState()
}

class SettingsViewModel(
    private val settingsRepository: SettingsRepository = SettingsRepository(),
    private val authRepository: AuthRepository
) : ViewModel() {

    val publicConfigState: StateFlow<SettingsDataState<PublicConfig>> = settingsRepository.getPublicConfigFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsDataState.Loading)

    val adminConfigState: StateFlow<SettingsDataState<AdminConfig>> = settingsRepository.getAdminConfigFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsDataState.Loading)

    private val _saveState = MutableStateFlow<SettingsSaveState>(SettingsSaveState.Idle)
    val saveState: StateFlow<SettingsSaveState> = _saveState.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    fun isBiometricEnabled(): Boolean = authRepository.isBiometricLockEnabled()
    fun setBiometricEnabled(enabled: Boolean) = authRepository.setBiometricLockEnabled(enabled)

    fun hasPin(): Boolean = authRepository.hasAdminPin()
    fun setPin(pin: String?) = authRepository.setAdminPin(pin)

    fun updatePublicConfig(
        vodafoneCashNumber: String,
        walletOwnerName: String,
        tier1PriceStr: String,
        tier2PriceStr: String,
        tier3PriceStr: String,
        supportWhatsApp: String
    ) {
        val t1 = tier1PriceStr.trim().toDoubleOrNull()
        val t2 = tier2PriceStr.trim().toDoubleOrNull()
        val t3 = tier3PriceStr.trim().toDoubleOrNull()

        if (t1 == null || t1 <= 0.0) {
            _saveState.value = SettingsSaveState.Error("سعر الباقة الأساسية غير صالح (يجب أن يكون رقماً أكبر من صفر)")
            return
        }
        if (t2 == null || t2 <= 0.0) {
            _saveState.value = SettingsSaveState.Error("سعر الباقة الملكية غير صالح (يجب أن يكون رقماً أكبر من صفر)")
            return
        }
        if (t3 == null || t3 <= 0.0) {
            _saveState.value = SettingsSaveState.Error("سعر باقة VIP غير صالح (يجب أن يكون رقماً أكبر من صفر)")
            return
        }

        if (vodafoneCashNumber.isBlank() || vodafoneCashNumber.filter { it.isDigit() }.length < 8) {
            _saveState.value = SettingsSaveState.Error("يرجى إدخال رقم محفظة فودافون كاش صالح")
            return
        }

        viewModelScope.launch {
            _saveState.value = SettingsSaveState.Saving
            try {
                val updated = PublicConfig(
                    vodafoneCashNumber = vodafoneCashNumber.trim(),
                    walletOwnerName = walletOwnerName.trim(),
                    tier1Price = t1,
                    tier2Price = t2,
                    tier3Price = t3,
                    customerSupportWhatsApp = supportWhatsApp.trim()
                )
                settingsRepository.updatePublicConfig(updated)
                _saveState.value = SettingsSaveState.Success("تم حفظ إعدادات الباقات والدفع بنجاح")
                _snackbarMessage.emit("تم حفظ إعدادات الأسعار والدفع بنجاح")
            } catch (e: Exception) {
                _saveState.value = SettingsSaveState.Error("فشل حفظ الإعدادات: ${e.localizedMessage}")
                _snackbarMessage.emit("خطأ أثناء الحفظ: ${e.localizedMessage}")
            }
        }
    }

    fun updateAdminConfig(
        maintenanceMode: Boolean,
        autoNotification: Boolean,
        cloudflareWorkerUrl: String,
        websiteBaseUrl: String
    ) {
        val trimmedWorker = cloudflareWorkerUrl.trim()
        if (trimmedWorker.isNotBlank() && !trimmedWorker.startsWith("https://", ignoreCase = true)) {
            _saveState.value = SettingsSaveState.Error("يجب أن يبدأ رابط Worker بـ https:// لحماية الاتصال")
            return
        }

        val trimmedWeb = websiteBaseUrl.trim().trimEnd('/')
        if (trimmedWeb.isNotBlank() && !trimmedWeb.startsWith("http://", ignoreCase = true) && !trimmedWeb.startsWith("https://", ignoreCase = true)) {
            _saveState.value = SettingsSaveState.Error("يجب أن يبدأ رابط منصة الويب بـ https:// أو http://")
            return
        }

        viewModelScope.launch {
            _saveState.value = SettingsSaveState.Saving
            try {
                val updated = AdminConfig(
                    maintenanceMode = maintenanceMode,
                    autoNotificationEnabled = autoNotification,
                    cloudflareWorkerUrl = trimmedWorker,
                    websiteBaseUrl = trimmedWeb.ifBlank { com.example.util.WebsiteUrlProvider.DEFAULT_BASE_URL }
                )
                settingsRepository.updateAdminConfig(updated)
                _saveState.value = SettingsSaveState.Success("تم تحديث إعدادات النظام السحابي ورابط المنصة بنجاح")
                _snackbarMessage.emit("تم حفظ إعدادات النظام ورابط الموقع بنجاح")
            } catch (e: Exception) {
                _saveState.value = SettingsSaveState.Error("فشل حفظ إعدادات النظام: ${e.localizedMessage}")
                _snackbarMessage.emit("خطأ أثناء الحفظ: ${e.localizedMessage}")
            }
        }
    }

    fun clearSaveState() {
        _saveState.value = SettingsSaveState.Idle
    }

    fun signOut() {
        authRepository.signOut()
    }
}
