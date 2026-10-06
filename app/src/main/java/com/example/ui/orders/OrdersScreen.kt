package com.example.ui.orders

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.data.model.DataState
import com.example.data.model.Order
import com.example.ui.components.*
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(
    ordersViewModel: OrdersViewModel,
    selectedOrderIdFromDeepLink: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val ordersState by ordersViewModel.ordersState.collectAsState()
    val orders by ordersViewModel.filteredOrders.collectAsState()
    val searchQuery by ordersViewModel.searchQuery.collectAsState()
    val statusFilter by ordersViewModel.statusFilter.collectAsState()
    val actionState by ordersViewModel.actionState.collectAsState()

    var selectedOrderForReject by remember { mutableStateOf<Order?>(null) }
    data class ApprovedSuccessData(val order: Order, val username: String, val hostCode: String?, val guestUrl: String, val hostPortalUrl: String, val hostCredentialsFailed: Boolean)
    var approvedSuccessOrder by remember { mutableStateOf<ApprovedSuccessData?>(null) }
    var selectedOrderForDetail by remember { mutableStateOf<Order?>(null) }
    var selectedOrderForDelete by remember { mutableStateOf<Order?>(null) }

    val allOrders = remember(ordersState) {
        if (ordersState is DataState.Success) (ordersState as DataState.Success<List<Order>>).data else emptyList()
    }
    val pendingCount = remember(allOrders) { allOrders.count { it.isPending } }

    // Check if opened with a deep link
    LaunchedEffect(selectedOrderIdFromDeepLink, allOrders) {
        if (!selectedOrderIdFromDeepLink.isNullOrBlank()) {
            val found = allOrders.find { it.id == selectedOrderIdFromDeepLink }
            if (found != null) {
                selectedOrderForDetail = found
            }
        }
    }

    LaunchedEffect(actionState) {
        if (actionState is OrderActionState.ApprovedSuccess) {
            val s = actionState as OrderActionState.ApprovedSuccess
            approvedSuccessOrder = ApprovedSuccessData(s.order, s.username, s.hostCode, s.guestUrl, s.hostPortalUrl, s.hostCredentialsFailed)
        }
    }

    // Copy to clipboard helper
    fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        HapticUtils.performLightHaptic(view)
        Toast.makeText(context, "تم نسخ $label: $text", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            FridaTopBar(
                title = "إدارة طلبات الدفع والباقات",
                subtitle = "${allOrders.size} طلب مسجل"
            )
        },
        containerColor = FridaBlack,
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Search & Filters Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(FridaSurface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { ordersViewModel.setSearchQuery(it) },
                    placeholder = { Text("بحث برقم الطلب، اسم العميل، الهاتف، المحفظة...", fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "بحث",
                            tint = FridaGold
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { ordersViewModel.setSearchQuery("") }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "مسح",
                                    tint = FridaTextTertiary
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = FridaGold,
                        unfocusedBorderColor = FridaBorder,
                        focusedTextColor = FridaTextPrimary,
                        unfocusedTextColor = FridaTextPrimary,
                        focusedContainerColor = FridaCard,
                        unfocusedContainerColor = FridaCard
                    ),
                    singleLine = true
                )

                // Filter Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = statusFilter == "all",
                        onClick = { ordersViewModel.setStatusFilter("all") },
                        label = { Text("الكل (${allOrders.size})", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = FridaGoldContainer,
                            selectedLabelColor = FridaGoldLight,
                            containerColor = FridaCard,
                            labelColor = FridaTextSecondary
                        )
                    )

                    FilterChip(
                        selected = statusFilter == "pending",
                        onClick = { ordersViewModel.setStatusFilter("pending") },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("معلقة ($pendingCount)", fontSize = 12.sp)
                                if (pendingCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(FridaAmber)
                                    )
                                }
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = FridaAmberContainer,
                            selectedLabelColor = FridaAmber,
                            containerColor = FridaCard,
                            labelColor = FridaTextSecondary
                        )
                    )

                    FilterChip(
                        selected = statusFilter == "approved",
                        onClick = { ordersViewModel.setStatusFilter("approved") },
                        label = { Text("معتمدة", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = FridaGreenContainer,
                            selectedLabelColor = FridaGreen,
                            containerColor = FridaCard,
                            labelColor = FridaTextSecondary
                        )
                    )

                    FilterChip(
                        selected = statusFilter == "rejected",
                        onClick = { ordersViewModel.setStatusFilter("rejected") },
                        label = { Text("مرفوضة", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = FridaRedContainer,
                            selectedLabelColor = FridaRed,
                            containerColor = FridaCard,
                            labelColor = FridaTextSecondary
                        )
                    )
                }
            }

            // Orders List Area
            when {
                ordersState is DataState.Loading && orders.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(color = FridaGold)
                            Text("جاري تحميل الطلبات...", color = FridaTextSecondary, fontSize = 14.sp)
                        }
                    }
                }
                ordersState is DataState.Error && orders.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = FridaRed,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = (ordersState as DataState.Error).message,
                                color = FridaRed,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Button(
                                onClick = {
                                    HapticUtils.performLightHaptic(view)
                                    ordersViewModel.setStatusFilter(statusFilter)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = FridaGold, contentColor = FridaBlack),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("إعادة المحاولة", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                orders.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ReceiptLong,
                                contentDescription = null,
                                tint = FridaTextTertiary,
                                modifier = Modifier.size(56.dp)
                            )
                            Text(
                                text = "لا توجد طلبات مطابقة للبحث أو الفلتر",
                                style = MaterialTheme.typography.bodyLarge,
                                color = FridaTextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
                    ) {
                    items(orders, key = { it.id }) { order ->
                        OrderItemCard(
                            order = order,
                            onApprove = { ordersViewModel.approveOrder(order) },
                            onReject = { selectedOrderForReject = order },
                            onOpenWhatsApp = {
                                val msg = "أهلاً بك أستاذ ${order.clientName}، بخصوص طلبكم رقم #${order.orderNumber} لباقة ${order.packageName} على منصة FRIDA للدعوات الملكية..."
                                ordersViewModel.openWhatsApp(context, order.phoneNumber, msg)
                            },
                            onCopyPhone = { copyToClipboard("رقم هاتف العميل", order.phoneNumber) },
                            onCopyWallet = { copyToClipboard("رقم فودافون كاش", order.vodafoneCashNumber) },
                            onViewDetail = { selectedOrderForDetail = order },
                            onDelete = { selectedOrderForDelete = order }
                        )
                    }

                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            FridaOutlinedButton(
                                text = "تحميل المزيد من الطلبات",
                                icon = Icons.Default.ExpandMore,
                                onClick = { ordersViewModel.loadMore() }
                            )
                        }
                    }
                }
            }
        }
    }
    }

    // Dialogs
    selectedOrderForReject?.let { order ->
        RejectOrderDialog(
            order = order,
            onConfirm = { reason ->
                ordersViewModel.rejectOrder(order, reason)
                selectedOrderForReject = null
            },
            onDismiss = { selectedOrderForReject = null }
        )
    }

    selectedOrderForDelete?.let { order ->
        AlertDialog(
            onDismissRequest = { selectedOrderForDelete = null },
            title = {
                Text(
                    text = "حذف الطلب نهائياً؟",
                    color = FridaRed,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Right,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Text(
                    text = "هل أنت متأكد من رغبتك في حذف طلب العميل '${order.clientName}' ذو الرقم #${order.orderNumber} بشكل نهائي من قاعدة البيانات؟ لا يمكن التراجع عن هذا الإجراء لاحقاً.",
                    color = FridaTextPrimary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Right,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        HapticUtils.performLightHaptic(view)
                        ordersViewModel.deleteOrder(order.id, order.orderNumber)
                        selectedOrderForDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FridaRed,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("نعم، احذف نهائياً", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedOrderForDelete = null }) {
                    Text("إلغاء", color = FridaTextSecondary)
                }
            },
            containerColor = FridaCardElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }

    approvedSuccessOrder?.let { successData ->
        val order = successData.order
        ApproveSuccessDialog(
            order = order,
            username = successData.username,
            hostCode = successData.hostCode,
            guestUrl = successData.guestUrl,
            hostPortalUrl = successData.hostPortalUrl,
            hostCredentialsFailed = successData.hostCredentialsFailed,
            onRegenerateCode = {
                ordersViewModel.regenerateHostCredentials(order.invitationId, order)
            },
            onOpenWhatsApp = {
                val codeLine = if (!successData.hostCode.isNullOrBlank()) "\nكود الدخول: ${successData.hostCode}" else ""
                val msg = "أهلاً بك أستاذ ${order.clientName}،\n" +
                    "تم تأكيد وتفعيل حجز باقة ${order.packageName} لدعوتكم على منصة FRIDA بنجاح! 🎉\n\n" +
                    "رابط الدعوة للضيوف:\n${successData.guestUrl}\n\n" +
                    "لوحة تحكم المضيف:\n${successData.hostPortalUrl}\n" +
                    "اسم المستخدم: ${successData.username}$codeLine\n\n" +
                    "ألف مبروك ويسعدنا دائماً خدمتكم!"
                ordersViewModel.openWhatsApp(context, order.phoneNumber, msg)
                approvedSuccessOrder = null
                ordersViewModel.clearActionState()
            },
            onDismiss = {
                approvedSuccessOrder = null
                ordersViewModel.clearActionState()
            }
        )
    }

    // Loading overlay when cloud function is executing
    if (actionState is OrderActionState.Loading) {
        val loading = actionState as OrderActionState.Loading
        Dialog(onDismissRequest = {}) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = FridaCardElevated,
                border = BorderStroke(1.dp, FridaGold)
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .widthIn(min = 280.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { if (loading.progressFraction > 0f) loading.progressFraction else 0f },
                            color = FridaGold,
                            trackColor = FridaBorder,
                            modifier = Modifier.size(54.dp),
                            strokeWidth = 3.5.dp
                        )
                        if (loading.percentage > 0) {
                            Text(
                                text = "${loading.percentage}%",
                                color = FridaGoldLight,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (loading.percentage > 0) {
                        LinearProgressIndicator(
                            progress = { loading.progressFraction },
                            color = FridaGold,
                            trackColor = FridaCard,
                            modifier = Modifier.fillMaxWidth().height(6.dp)
                        )
                    }

                    Text(
                        text = loading.message,
                        color = FridaGoldLight,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    // Error dialog when action fails
    if (actionState is OrderActionState.Error) {
        val errorState = actionState as OrderActionState.Error
        AlertDialog(
            onDismissRequest = { ordersViewModel.clearActionState() },
            title = {
                Text(
                    text = "تعذر تنفيذ الإجراء",
                    color = FridaRed,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Text(
                    text = errorState.message,
                    color = FridaTextPrimary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { ordersViewModel.clearActionState() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FridaGold,
                        contentColor = FridaBlack
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("حسناً", fontWeight = FontWeight.Bold)
                }
            },
            containerColor = FridaCardElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
fun OrderItemCard(
    order: Order,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onOpenWhatsApp: () -> Unit,
    onCopyPhone: () -> Unit,
    onCopyWallet: () -> Unit,
    onViewDetail: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd - hh:mm a", Locale("ar")) }
    val formattedDate = remember(order.createdAt) {
        order.createdAt?.toDate()?.let { dateFormat.format(it) } ?: "غير محدد"
    }

    LuxuryCard(
        isHighlighted = order.isPending,
        onClick = onViewDetail
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "طلب #${order.orderNumber}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = FridaGoldLight
                )
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = FridaTextTertiary
                )
            }

            StatusBadge(status = order.status)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Client & Payment Info Box
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = FridaSurface,
            border = BorderStroke(1.dp, FridaBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Client Name
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "العميل:",
                        color = FridaTextSecondary,
                        fontSize = 12.sp
                    )
                    Text(
                        text = order.clientName,
                        color = FridaTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                // Phone Number
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "رقم الهاتف:",
                        color = FridaTextSecondary,
                        fontSize = 12.sp
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = order.phoneNumber,
                            color = FridaGoldLight,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        IconButton(
                            onClick = onCopyPhone,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "نسخ الهاتف",
                                tint = FridaTextTertiary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                // Vodafone Cash Wallet Number
                if (order.vodafoneCashNumber.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "محفظة فودافون كاش:",
                            color = FridaTextSecondary,
                            fontSize = 12.sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = order.vodafoneCashNumber,
                                color = FridaRed,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            IconButton(
                                onClick = onCopyWallet,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "نسخ رقم المحفظة",
                                    tint = FridaTextTertiary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                // Package & Amount
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "الباقة والمبلغ:",
                        color = FridaTextSecondary,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "${order.packageName} • ${order.amount} ج.م",
                        color = FridaGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                if (!order.rejectionReason.isNullOrBlank()) {
                    Text(
                        text = "سبب الرفض: ${order.rejectionReason}",
                        color = FridaRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Receipt thumbnail preview if present
        if (!order.receiptImageUrl.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AsyncImage(
                    model = order.receiptImageUrl,
                    contentDescription = "صورة إيصال التحويل",
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, FridaBorderGold, RoundedCornerShape(8.dp))
                )
                Text(
                    text = "مرفق إيصال تحويل فودافون كاش",
                    color = FridaTextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Action Buttons
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (order.isPending) {
                // 1. "اعتماد وتفعيل الطلب"
                FridaGoldButton(
                    text = "اعتماد وتفعيل الطلب",
                    icon = Icons.Default.CheckCircle,
                    onClick = onApprove
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 2. "رفض الطلب"
                    FridaOutlinedButton(
                        text = "رفض الطلب",
                        icon = Icons.Default.Cancel,
                        borderColor = FridaRed,
                        textColor = FridaRed,
                        onClick = onReject,
                        modifier = Modifier.weight(1f)
                    )

                    // 3. "مراسلة العميل عبر واتساب"
                    FridaOutlinedButton(
                        text = "واتساب",
                        icon = Icons.Default.Chat,
                        borderColor = FridaGreen,
                        textColor = FridaGreen,
                        onClick = onOpenWhatsApp,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                // If already approved or rejected, offer quick WhatsApp contact
                FridaOutlinedButton(
                    text = "مراسلة العميل عبر واتساب",
                    icon = Icons.Default.Chat,
                    borderColor = FridaGreen,
                    textColor = FridaGreen,
                    onClick = onOpenWhatsApp,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Always offer a red "Delete Permanently" button at the very bottom
            FridaOutlinedButton(
                text = "حذف الطلب نهائياً",
                icon = Icons.Default.Delete,
                borderColor = FridaRed.copy(alpha = 0.7f),
                textColor = FridaRed,
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
