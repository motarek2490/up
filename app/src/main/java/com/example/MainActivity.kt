package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.repository.AuthRepository
import com.example.ui.auth.AuthUiState
import com.example.ui.auth.AuthViewModel
import com.example.ui.auth.BiometricLockScreen
import com.example.ui.auth.LoginScreen
import com.example.ui.dashboard.DashboardViewModel
import com.example.ui.invitations.InvitationsViewModel
import com.example.ui.navigation.MainAppContainer
import com.example.ui.orders.OrdersViewModel
import com.example.ui.reviews_audio.ReviewsAudioViewModel
import com.example.ui.settings.SettingsViewModel
import com.example.ui.theme.FridaBlack
import com.example.ui.theme.FridaTheme

class MainActivity : FragmentActivity() {

    private var deepLinkOrderIdState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIntent(intent)

        val authRepository = AuthRepository(applicationContext)

        setContent {
            FridaTheme {
                val authViewModel: AuthViewModel = viewModel {
                    AuthViewModel(authRepository)
                }
                val authState by authViewModel.uiState.collectAsState()
                val deepLinkOrderId by deepLinkOrderIdState

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = FridaBlack
                ) {
                    AnimatedContent(
                        targetState = authState,
                        label = "AuthRootTransition"
                    ) { state ->
                        when (state) {
                            is AuthUiState.Authenticated -> {
                                val dashboardViewModel: DashboardViewModel = viewModel()
                                val ordersViewModel: OrdersViewModel = viewModel()
                                val invitationsViewModel: InvitationsViewModel = viewModel()
                                val settingsViewModel: SettingsViewModel = viewModel {
                                    SettingsViewModel(authRepository = authRepository)
                                }
                                val reviewsAudioViewModel: ReviewsAudioViewModel = viewModel()

                                LaunchedEffect(Unit) {
                                    dashboardViewModel.loadDashboardStats()
                                    try {
                                        com.example.data.repository.FcmRepository().registerCurrentToken()
                                    } catch (e: Exception) {
                                        android.util.Log.w("MainActivity", "Token registration on auth: ${e.message}")
                                    }
                                }

                                MainAppContainer(
                                    authViewModel = authViewModel,
                                    dashboardViewModel = dashboardViewModel,
                                    ordersViewModel = ordersViewModel,
                                    invitationsViewModel = invitationsViewModel,
                                    settingsViewModel = settingsViewModel,
                                    reviewsAudioViewModel = reviewsAudioViewModel,
                                    deepLinkOrderId = deepLinkOrderId
                                )
                            }
                            is AuthUiState.LockedWithPinOrBiometric -> {
                                BiometricLockScreen(
                                    authViewModel = authViewModel
                                )
                            }
                            else -> {
                                LoginScreen(
                                    authViewModel = authViewModel
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val id = intent.getStringExtra("EXTRA_ORDER_ID")
            ?: intent.getStringExtra("orderId")
            ?: intent.getStringExtra("EXTRA_INVITATION_ID")
            ?: intent.getStringExtra("invitationId")
            ?: intent.data?.getQueryParameter("orderId")
            ?: intent.data?.getQueryParameter("invitationId")
        if (!id.isNullOrBlank()) {
            val sanitized = id.trim()
            // Strictly validate ID format: alphanumeric, dashes, underscores, max 64 chars
            if (sanitized.length <= 64 && sanitized.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
                deepLinkOrderIdState.value = sanitized
            } else {
                android.util.Log.w("MainActivity", "Rejected invalid or malformed ID from deep link: $id")
            }
        }
    }
}
