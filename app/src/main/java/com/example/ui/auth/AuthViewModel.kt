package com.example.ui.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.FirebaseProvider
import com.example.data.repository.AdminAccessDeniedException
import com.example.data.repository.AuthNetworkException
import com.example.data.repository.AuthRepository
import com.example.data.repository.FcmRepository
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

sealed class AuthUiState {
    data object Idle : AuthUiState()
    data object Loading : AuthUiState()
    data class Authenticated(val email: String, val displayName: String?) : AuthUiState()
    data object LockedWithPinOrBiometric : AuthUiState()
    data class Error(val message: String, val isNoCredentialError: Boolean = false) : AuthUiState()
}

class AuthViewModel(
    private val authRepository: AuthRepository,
    private val fcmRepository: FcmRepository = FcmRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    init {
        checkCurrentAuthState()
    }

    private fun getServerClientId(context: Context): String {
        try {
            val field = BuildConfig::class.java.getField("GOOGLE_WEB_CLIENT_ID")
            val id = field.get(null) as? String
            if (!id.isNullOrBlank()) return id.trim()
        } catch (ignored: Exception) {}

        val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        if (resId != 0) {
            val resVal = context.getString(resId)
            if (resVal.isNotBlank()) return resVal.trim()
        }

        return "678727715764-f0818mlvddi0co9nv1jl4etkd80e2qt0.apps.googleusercontent.com"
    }

    fun checkCurrentAuthState() {
        val user = authRepository.currentUser
        if (user != null) {
            viewModelScope.launch {
                _uiState.value = AuthUiState.Loading
                try {
                    val isAdmin = authRepository.verifyAdminClaim()
                    if (isAdmin) {
                        if (authRepository.isBiometricLockEnabled() || authRepository.hasAdminPin()) {
                            _uiState.value = AuthUiState.LockedWithPinOrBiometric
                        } else {
                            _uiState.value = AuthUiState.Authenticated(
                                email = user.email ?: "",
                                displayName = user.displayName
                            )
                        }
                        // Register FCM asynchronously in background without blocking login
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                fcmRepository.registerCurrentToken()
                            } catch (e: Exception) {
                                Log.w("AuthViewModel", "FCM token register non-fatal: ${e.message}")
                            }
                        }
                    }
                } catch (e: AdminAccessDeniedException) {
                    _uiState.value = AuthUiState.Error(e.message ?: "هذا الحساب لا يملك صلاحية الوصول للوحة التحكم")
                } catch (e: AuthNetworkException) {
                    _uiState.value = AuthUiState.Error(e.message ?: "تعذر التحقق بسبب مشكلة في الشبكة، اضغط إعادة المحاولة")
                } catch (e: Exception) {
                    _uiState.value = AuthUiState.Error("تعذر التحقق من صلاحيات الأدمن: ${e.localizedMessage}")
                }
            }
        } else {
            _uiState.value = AuthUiState.Idle
        }
    }

    fun unlockApp() {
        val user = authRepository.currentUser
        if (user != null) {
            _uiState.value = AuthUiState.Authenticated(
                email = user.email ?: "",
                displayName = user.displayName
            )
        } else {
            _uiState.value = AuthUiState.Idle
        }
    }

    fun lockAppIfEnabled() {
        if (authRepository.isUserLoggedIn && (authRepository.isBiometricLockEnabled() || authRepository.hasAdminPin())) {
            _uiState.value = AuthUiState.LockedWithPinOrBiometric
        }
    }

    fun verifyPin(enteredPin: String): Boolean {
        val isValid = authRepository.verifyPin(enteredPin)
        if (isValid) {
            unlockApp()
        }
        return isValid
    }

    fun getPinLockoutRemainingSeconds(): Int {
        return authRepository.getLockoutRemainingSeconds()
    }

    fun signInWithGoogleCredential(idToken: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            try {
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                val authResult = FirebaseProvider.auth.signInWithCredential(credential).await()
                val user = authResult.user ?: throw IllegalStateException("تعذر استخراج بيانات المستخدم بعد المصادقة")

                // Strict Admin Claim Verification
                val isAdmin = authRepository.verifyAdminClaim()
                if (isAdmin) {
                    _uiState.value = AuthUiState.Authenticated(
                        email = user.email ?: "",
                        displayName = user.displayName
                    )
                    _snackbarMessage.emit("مرحباً بك ${user.displayName ?: user.email}")

                    // Background FCM token registration
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            fcmRepository.registerCurrentToken()
                        } catch (e: Exception) {
                            Log.w("AuthViewModel", "FCM token register non-fatal: ${e.message}")
                        }
                    }
                }
            } catch (e: AdminAccessDeniedException) {
                _uiState.value = AuthUiState.Error(e.message ?: "هذا الحساب لا يملك صلاحية الوصول للوحة التحكم")
            } catch (e: AuthNetworkException) {
                _uiState.value = AuthUiState.Error(e.message ?: "خطأ في الاتصال بالشبكة أثناء التحقق من الصلاحيات")
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Sign in error", e)
                val msg = e.localizedMessage ?: e.message ?: "خطأ غير معروف"
                val detailedMsg = when {
                    msg.contains("10") || msg.contains("12500") -> "خطأ في إعدادات التطبيق (رمز 10/12500): يرجى التأكد من مطابقة مفتاح Web Client ID في Firebase Console."
                    msg.contains("12501") -> "تم إغلاق نافذة المصادقة دون إكمال التوثيق (رمز 12501)."
                    else -> "فشل توثيق الحساب عبر Firebase: $msg"
                }
                _uiState.value = AuthUiState.Error(detailedMsg)
            }
        }
    }

    fun signInWithEmail(email: String, pass: String) {
        if (email.isBlank() || pass.isBlank()) {
            _uiState.value = AuthUiState.Error("يرجى إدخال البريد الإلكتروني وكلمة المرور")
            return
        }
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            try {
                val authResult = FirebaseProvider.auth.signInWithEmailAndPassword(email.trim(), pass).await()
                val user = authResult.user ?: throw IllegalStateException("تعذر تسجيل الدخول")

                val isAdmin = authRepository.verifyAdminClaim()
                if (isAdmin) {
                    _uiState.value = AuthUiState.Authenticated(
                        email = user.email ?: "",
                        displayName = user.displayName
                    )
                    _snackbarMessage.emit("تم تسجيل الدخول بنجاح")

                    // Background FCM token registration
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            fcmRepository.registerCurrentToken()
                        } catch (e: Exception) {
                            Log.w("AuthViewModel", "FCM token register non-fatal: ${e.message}")
                        }
                    }
                }
            } catch (e: AdminAccessDeniedException) {
                _uiState.value = AuthUiState.Error(e.message ?: "هذا الحساب لا يملك صلاحية الوصول للوحة التحكم")
            } catch (e: AuthNetworkException) {
                _uiState.value = AuthUiState.Error(e.message ?: "خطأ في الشبكة، اضغط إعادة المحاولة")
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Email sign in error", e)
                val msg = e.localizedMessage ?: e.message ?: "خطأ غير معروف"
                _uiState.value = AuthUiState.Error("فشل تسجيل الدخول بالبريد: $msg")
            }
        }
    }

    fun clearLoading() {
        if (_uiState.value is AuthUiState.Loading) {
            _uiState.value = AuthUiState.Idle
        }
    }

    /**
     * Google Sign-In via Credential Manager with resilient fallback options and explanatory error messages.
     */
    fun startGoogleSignIn(activity: Activity) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            try {
                val credentialManager = CredentialManager.create(activity)
                val serverClientId = getServerClientId(activity)

                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(serverClientId)
                    .setAutoSelectEnabled(false)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val result = try {
                    credentialManager.getCredential(activity, request)
                } catch (e: Exception) {
                    if (e is GetCredentialCancellationException) throw e
                    Log.w("AuthViewModel", "Primary GetGoogleIdOption failed, trying GetSignInWithGoogleOption: ${e.message}")

                    // Fallback option
                    val fallbackOption = com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption.Builder(serverClientId)
                        .build()
                    val fallbackRequest = GetCredentialRequest.Builder()
                        .addCredentialOption(fallbackOption)
                        .build()
                    credentialManager.getCredential(activity, fallbackRequest)
                }

                val credential = result.credential

                if (credential is androidx.credentials.CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    signInWithGoogleCredential(googleIdTokenCredential.idToken)
                } else {
                    _uiState.value = AuthUiState.Error("نوع بيانات الاعتماد المسترجعة من Google غير مدعوم: ${credential.type}")
                }
            } catch (e: NoCredentialException) {
                Log.w("AuthViewModel", "No Google account found via CredentialManager", e)
                _uiState.value = AuthUiState.Error(
                    message = "لم يتم العثور على رمز توثيق لحساب Google على الهاتف. يمكنك استخدام البريد الإلكتروني وكلمة المرور للدخول فورا.",
                    isNoCredentialError = true
                )
            } catch (e: GetCredentialCancellationException) {
                Log.d("AuthViewModel", "Google Sign-In canceled by user")
                _uiState.value = AuthUiState.Idle
            } catch (e: GetCredentialException) {
                Log.e("AuthViewModel", "GetCredentialException details: type=${e.type}, message=${e.message}", e)
                val msg = e.message ?: ""
                val explanatoryText = when {
                    msg.contains("12501") || e.type.contains("12501") ->
                        "خطأ توثيق Google (12501): البصمة الرقمية غير مسجلة.\n" +
                        "• SHA-1:\n45:B8:D7:5B:4C:EF:B8:76:60:9C:EA:AC:9F:3B:15:50:4D:6D:60:4D\n" +
                        "• SHA-256:\n02:41:F7:4E:4D:3C:A1:40:AB:CE:B6:4F:AC:AB:E8:0D:36:C1:CA:7D:B8:3A:D1:49:79:36:71:7E:BF:A3:89:81"
                    msg.contains("10") || e.type.contains("10") ->
                        "خطأ إعدادات Google (10): مفتاح OAuth Client ID غير معتمد لهذه الحزمة في Firebase."
                    else ->
                        "تعذر إتمام الدخول عبر Google: ${e.localizedMessage ?: msg}. يمكنك الدخول بالبريد الإلكتروني وكلمة المرور."
                }
                _uiState.value = AuthUiState.Error(explanatoryText)
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Google Sign in exception", e)
                _uiState.value = AuthUiState.Error("حدث خطأ أثناء الاتصال بخدمات Google: ${e.localizedMessage ?: e.message}")
            }
        }
    }

    /**
     * Signs out: deletes FCM token from server and device BEFORE signing out from Firebase Auth.
     */
    fun signOut(activity: Activity? = null) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            try {
                fcmRepository.unregisterAndClearToken()
            } catch (e: Exception) {
                Log.w("AuthViewModel", "FCM unregister non-fatal: ${e.message}")
            }
            authRepository.signOut()
            activity?.let {
                try {
                    val credentialManager = CredentialManager.create(it)
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (e: Exception) {
                    // ignore
                }
            }
            _uiState.value = AuthUiState.Idle
            _snackbarMessage.emit("تم تسجيل الخروج بنجاح")
        }
    }

    fun clearError() {
        if (_uiState.value is AuthUiState.Error) {
            _uiState.value = AuthUiState.Idle
        }
    }
}
