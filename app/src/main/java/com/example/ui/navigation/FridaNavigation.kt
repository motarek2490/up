package com.example.ui.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.auth.AuthViewModel
import com.example.ui.dashboard.DashboardScreen
import com.example.ui.dashboard.DashboardViewModel
import com.example.ui.invitations.InvitationsScreen
import com.example.ui.invitations.InvitationsViewModel
import com.example.ui.orders.OrdersScreen
import com.example.ui.orders.OrdersViewModel
import com.example.ui.reviews_audio.ReviewsAudioScreen
import com.example.ui.reviews_audio.ReviewsAudioViewModel
import com.example.ui.settings.SettingsScreen
import com.example.ui.settings.SettingsViewModel
import com.example.ui.theme.*

enum class FridaDestination(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    Dashboard("الرئيسية", Icons.Filled.Dashboard, Icons.Outlined.Dashboard),
    Orders("الطلبات", Icons.Filled.ReceiptLong, Icons.Outlined.ReceiptLong),
    Invitations("الدعوات", Icons.Filled.Mail, Icons.Outlined.MailOutline),
    Settings("الأسعار والإعدادات", Icons.Filled.Settings, Icons.Outlined.Settings),
    ReviewsAudio("التقييمات والموسيقى", Icons.Filled.Star, Icons.Outlined.StarBorder)
}

@Composable
fun MainAppContainer(
    authViewModel: AuthViewModel,
    dashboardViewModel: DashboardViewModel,
    ordersViewModel: OrdersViewModel,
    invitationsViewModel: InvitationsViewModel,
    settingsViewModel: SettingsViewModel,
    reviewsAudioViewModel: ReviewsAudioViewModel,
    deepLinkOrderId: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentDestination by remember { mutableStateOf(FridaDestination.Dashboard) }
    var targetOrderId by remember { mutableStateOf<String?>(deepLinkOrderId) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Android 13+ Notification Permission Request
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Token will be processed automatically
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Collect snackbar messages from ViewModels
    LaunchedEffect(ordersViewModel) {
        ordersViewModel.snackbarMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(invitationsViewModel) {
        invitationsViewModel.snackbarMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(settingsViewModel) {
        settingsViewModel.snackbarMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(reviewsAudioViewModel) {
        reviewsAudioViewModel.snackbarMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    // Handle incoming deep link
    LaunchedEffect(deepLinkOrderId) {
        if (!deepLinkOrderId.isNullOrBlank()) {
            targetOrderId = deepLinkOrderId
            currentDestination = FridaDestination.Orders
        }
    }

    // Handle back button behavior
    if (currentDestination != FridaDestination.Dashboard) {
        BackHandler {
            currentDestination = FridaDestination.Dashboard
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState) { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = FridaCardElevated,
                        contentColor = FridaGoldLight,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            bottomBar = {
                NavigationBar(
                    containerColor = FridaSurface,
                    contentColor = FridaGold,
                    tonalElevation = 8.dp
                ) {
                    FridaDestination.entries.forEach { destination ->
                        val selected = currentDestination == destination
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                currentDestination = destination
                                if (destination != FridaDestination.Orders) {
                                    targetOrderId = null
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                    contentDescription = destination.title
                                )
                            },
                            label = {
                                Text(
                                    text = destination.title,
                                    fontSize = 10.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = FridaBlack,
                                selectedTextColor = FridaGoldLight,
                                indicatorColor = FridaGold,
                                unselectedIconColor = FridaTextTertiary,
                                unselectedTextColor = FridaTextTertiary
                            )
                        )
                    }
                }
            },
            containerColor = FridaBlack,
            modifier = modifier
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                AnimatedContent(
                    targetState = currentDestination,
                    label = "MainScreenCrossfade"
                ) { destination ->
                    when (destination) {
                        FridaDestination.Dashboard -> {
                            DashboardScreen(
                                dashboardViewModel = dashboardViewModel,
                                onNavigateToOrders = { orderId ->
                                    targetOrderId = orderId
                                    currentDestination = FridaDestination.Orders
                                },
                                onNavigateToInvitations = {
                                    currentDestination = FridaDestination.Invitations
                                },
                                onNavigateToSettings = {
                                    currentDestination = FridaDestination.Settings
                                },
                                onNavigateToReviews = {
                                    currentDestination = FridaDestination.ReviewsAudio
                                }
                            )
                        }
                        FridaDestination.Orders -> {
                            OrdersScreen(
                                ordersViewModel = ordersViewModel,
                                selectedOrderIdFromDeepLink = targetOrderId
                            )
                        }
                        FridaDestination.Invitations -> {
                            InvitationsScreen(
                                invitationsViewModel = invitationsViewModel
                            )
                        }
                        FridaDestination.Settings -> {
                            SettingsScreen(
                                settingsViewModel = settingsViewModel,
                                onSignOut = {
                                    authViewModel.signOut(context as? android.app.Activity)
                                }
                            )
                        }
                        FridaDestination.ReviewsAudio -> {
                            ReviewsAudioScreen(
                                viewModel = reviewsAudioViewModel
                            )
                        }
                    }
                }
            }
        }
    }
}
