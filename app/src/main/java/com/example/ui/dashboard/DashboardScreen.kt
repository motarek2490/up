package com.example.ui.dashboard

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Order
import com.example.ui.components.*
import com.example.ui.theme.*
import java.text.DecimalFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    dashboardViewModel: DashboardViewModel,
    onNavigateToOrders: (orderId: String?) -> Unit,
    onNavigateToInvitations: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToReviews: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by dashboardViewModel.uiState.collectAsState()
    val isRefreshing by dashboardViewModel.isRefreshing.collectAsState()
    val view = LocalView.current
    val numberFormat = remember { DecimalFormat("#,###.##") }

    Scaffold(
        topBar = {
            FridaTopBar(
                title = "لوحة التحكم FRIDA",
                subtitle = "نظرة عامة على الطلبات والدعوات",
                actions = {
                    IconButton(onClick = {
                        HapticUtils.performLightHaptic(view)
                        dashboardViewModel.loadDashboardStats()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "تحديث",
                            tint = FridaGold
                        )
                    }
                }
            )
        },
        containerColor = FridaBlack,
        modifier = modifier
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { dashboardViewModel.loadDashboardStats() },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val state = uiState) {
                is DashboardUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = FridaGold)
                    }
                }
                is DashboardUiState.Error -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = FridaRed,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = state.message,
                                color = FridaTextSecondary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            FridaOutlinedButton(
                                text = "إعادة المحاولة",
                                onClick = { dashboardViewModel.loadDashboardStats() }
                            )
                        }
                    }
                }
                is DashboardUiState.Success -> {
                    val stats = state.stats
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp)
                    ) {
                        // Top Greeting & System Status Banner
                        item {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = FridaCardElevated,
                                border = BorderStroke(
                                    1.dp,
                                    Brush.horizontalGradient(listOf(FridaGoldDark, FridaGold))
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "مرحبًا بك في منصة FRIDA الملكية",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = FridaGoldLight
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "الخادم السحابي متصل ويعمل بنظام Firebase Custom Claim",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = FridaTextSecondary
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(FridaGoldContainer)
                                            .border(1.dp, FridaGold, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.WorkspacePremium,
                                            contentDescription = null,
                                            tint = FridaGold,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Stat Cards Grid
                        item {
                            Text(
                                text = "الإحصائيات الرئيسية",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = FridaGoldDeep
                            )
                        }

                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    // Pending Orders
                                    StatCard(
                                        title = "الطلبات المعلقة",
                                        value = "${stats.pendingOrdersCount}",
                                        subtitle = if (stats.pendingOrdersCount > 0) "تتطلب مراجعة واعتماد" else "لا توجد طلبات معلقة",
                                        icon = Icons.Default.PendingActions,
                                        accentColor = if (stats.pendingOrdersCount > 0) FridaAmber else FridaGreen,
                                        hasAlert = stats.pendingOrdersCount > 0,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { onNavigateToOrders(null) }
                                    )

                                    // Published Invitations
                                    StatCard(
                                        title = "الدعوات النشطة",
                                        value = "${stats.publishedInvitationsCount}",
                                        subtitle = "منشورة ومتاحة للضيوف",
                                        icon = Icons.Default.Celebration,
                                        accentColor = FridaGold,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { onNavigateToInvitations() }
                                    )
                                }

                                 Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    // Total Revenue
                                    StatCard(
                                        title = "إجمالي الإيرادات المعتمدة",
                                        value = "${numberFormat.format(stats.totalApprovedRevenue)} ج.م",
                                        subtitle = "مجموع المبالغ المعتمدة",
                                        icon = Icons.Default.AccountBalanceWallet,
                                        accentColor = FridaGreen,
                                        modifier = Modifier.weight(1f)
                                    )

                                    // Total Visitors
                                    StatCard(
                                        title = "إجمالي زوار المنصة",
                                        value = "${numberFormat.format(stats.totalVisitorsCount)} زائر",
                                        subtitle = "إحصائيات زيارات الموقع",
                                        icon = Icons.Default.Visibility,
                                        accentColor = FridaGoldLight,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        // Quick Navigation Shortcuts
                        item {
                            Text(
                                text = "الوصول السريع للأقسام",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = FridaGoldDeep
                            )
                        }

                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                QuickActionCard(
                                    title = "إدارة الطلبات",
                                    icon = Icons.Default.ReceiptLong,
                                    badgeCount = stats.pendingOrdersCount.toInt(),
                                    onClick = { onNavigateToOrders(null) },
                                    modifier = Modifier.weight(1f)
                                )

                                QuickActionCard(
                                    title = "الدعوات",
                                    icon = Icons.Default.MailOutline,
                                    onClick = { onNavigateToInvitations() },
                                    modifier = Modifier.weight(1f)
                                )

                                QuickActionCard(
                                    title = "الأسعار",
                                    icon = Icons.Default.PriceCheck,
                                    onClick = { onNavigateToSettings() },
                                    modifier = Modifier.weight(1f)
                                )

                                QuickActionCard(
                                    title = "التقييمات",
                                    icon = Icons.Default.StarBorder,
                                    onClick = { onNavigateToReviews() },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        // Recent 10 Orders Header
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "أحدث الطلبات الواردة",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = FridaGoldDeep
                                )

                                TextButton(onClick = { onNavigateToOrders(null) }) {
                                    Text(
                                        text = "عرض الكل",
                                        color = FridaGold,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Recent Orders List
                        if (stats.recentOrders.isEmpty()) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = FridaCard,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Inbox,
                                            contentDescription = null,
                                            tint = FridaTextTertiary,
                                            modifier = Modifier.size(40.dp)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "لا توجد طلبات حديثة حاليًا",
                                            color = FridaTextSecondary,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                            }
                        } else {
                            items(stats.recentOrders, key = { it.id }) { order ->
                                OrderQuickCard(
                                    order = order,
                                    onClick = { onNavigateToOrders(order.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuickActionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeCount: Int = 0
) {
    val view = LocalView.current
    Surface(
        modifier = modifier
            .height(82.dp)
            .clickable {
                HapticUtils.performLightHaptic(view)
                onClick()
            },
        shape = RoundedCornerShape(12.dp),
        color = FridaCard,
        border = BorderStroke(1.dp, FridaBorder)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = FridaGold,
                        modifier = Modifier.size(24.dp)
                    )
                    if (badgeCount > 0) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 6.dp, y = (-4).dp),
                            shape = CircleShape,
                            color = FridaAmber
                        ) {
                            Text(
                                text = "$badgeCount",
                                color = FridaBlack,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = title,
                    color = FridaTextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun OrderQuickCard(
    order: Order,
    onClick: () -> Unit
) {
    val view = LocalView.current
    LuxuryCard(
        isHighlighted = order.isPending,
        onClick = {
            HapticUtils.performLightHaptic(view)
            onClick()
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "#${order.orderNumber}",
                        fontWeight = FontWeight.Bold,
                        color = FridaGoldLight,
                        fontSize = 14.sp
                    )
                    StatusBadge(status = order.status)
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = order.clientName,
                    fontWeight = FontWeight.SemiBold,
                    color = FridaTextPrimary,
                    fontSize = 15.sp
                )

                Text(
                    text = "${order.packageName} • ${order.amount} ج.م",
                    color = FridaTextSecondary,
                    fontSize = 13.sp
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronLeft,
                contentDescription = "تفاصيل",
                tint = FridaGold
            )
        }
    }
}
