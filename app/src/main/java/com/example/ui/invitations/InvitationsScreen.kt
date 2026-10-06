package com.example.ui.invitations

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.example.data.model.DataState
import com.example.data.model.Invitation
import com.example.ui.components.*
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvitationsScreen(
    invitationsViewModel: InvitationsViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val invitationsDataState by invitationsViewModel.filteredInvitations.collectAsState()
    val allInvitationsState by invitationsViewModel.invitationsState.collectAsState()
    val searchQuery by invitationsViewModel.searchQuery.collectAsState()
    val statusFilter by invitationsViewModel.statusFilter.collectAsState()
    val actionState by invitationsViewModel.actionState.collectAsState()

    val totalCount = remember(allInvitationsState) {
        if (allInvitationsState is DataState.Success) (allInvitationsState as DataState.Success).data.size else 0
    }

    var previewSlugAndTitle by remember { mutableStateOf<Pair<String, String>?>(null) }
    var extendInvitationTarget by remember { mutableStateOf<Invitation?>(null) }
    var confirmTerminateTarget by remember { mutableStateOf<Invitation?>(null) }
    var confirmDeleteTarget by remember { mutableStateOf<Invitation?>(null) }

    val customTemplates by invitationsViewModel.customTemplates.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var screenError by remember { mutableStateOf<String?>(null) }

    if (screenError != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FridaBlack)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = FridaSurface,
                border = BorderStroke(1.dp, FridaRed),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
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
                        text = "تعذر عرض شاشة الدعوات بسبب خطأ غير متوقع",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = FridaTextPrimary,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = screenError ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = FridaRed,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = {
                            screenError = null
                            invitationsViewModel.setStatusFilter("all")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = FridaGold, contentColor = FridaBlack),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("إعادة المحاولة", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        return
    }

    Scaffold(
        topBar = {
            FridaTopBar(
                title = "إدارة ومتابعة الدعوات والقوالب",
                subtitle = if (totalCount > 0) "$totalCount دعوة مسجلة" else null
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
            // Main Navigation Tabs
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = FridaSurface,
                contentColor = FridaGold
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("سجل الدعوات ($totalCount)", fontWeight = FontWeight.Bold, fontSize = 13.sp) },
                    selectedContentColor = FridaGoldLight,
                    unselectedContentColor = FridaTextTertiary
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("القوالب المخصصة (${customTemplates.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp) },
                    selectedContentColor = FridaGoldLight,
                    unselectedContentColor = FridaTextTertiary
                )
            }

            if (selectedTab == 1) {
                CustomTemplatesView(
                    templates = customTemplates,
                    onSaveTemplate = { template -> invitationsViewModel.saveCustomTemplate(template) },
                    onToggleActive = { templateId, isActive -> invitationsViewModel.toggleCustomTemplateActive(templateId, isActive) },
                    onDeleteTemplate = { templateId -> invitationsViewModel.deleteCustomTemplate(templateId) }
                )
            } else {
                // Search & Filter Header
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
                    onValueChange = { invitationsViewModel.setSearchQuery(it) },
                    placeholder = { Text("بحث باسم العروسين، العنوان، الرابط المختصر، القاعة...", fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "بحث",
                            tint = FridaGold
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { invitationsViewModel.setSearchQuery("") }) {
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

                // Status Filter Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = statusFilter == "all" || statusFilter == null,
                        onClick = { invitationsViewModel.setStatusFilter("all") },
                        label = { Text("الكل", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = FridaGoldContainer,
                            selectedLabelColor = FridaGoldLight,
                            containerColor = FridaCard,
                            labelColor = FridaTextSecondary
                        )
                    )

                    FilterChip(
                        selected = statusFilter == "published",
                        onClick = { invitationsViewModel.setStatusFilter("published") },
                        label = { Text("منشورة", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = FridaGreenContainer,
                            selectedLabelColor = FridaGreen,
                            containerColor = FridaCard,
                            labelColor = FridaTextSecondary
                        )
                    )

                    FilterChip(
                        selected = statusFilter == "pending",
                        onClick = { invitationsViewModel.setStatusFilter("pending") },
                        label = { Text("معلقة", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = FridaAmberContainer,
                            selectedLabelColor = FridaAmber,
                            containerColor = FridaCard,
                            labelColor = FridaTextSecondary
                        )
                    )

                    FilterChip(
                        selected = statusFilter == "draft",
                        onClick = { invitationsViewModel.setStatusFilter("draft") },
                        label = { Text("مسودة", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = FridaCardElevated,
                            selectedLabelColor = FridaTextPrimary,
                            containerColor = FridaCard,
                            labelColor = FridaTextSecondary
                        )
                    )

                    FilterChip(
                        selected = statusFilter == "expired",
                        onClick = { invitationsViewModel.setStatusFilter("expired") },
                        label = { Text("منتهية", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = FridaCardElevated,
                            selectedLabelColor = FridaTextTertiary,
                            containerColor = FridaCard,
                            labelColor = FridaTextSecondary
                        )
                    )
                }
            }

            // Content Area based on DataState
            when (val state = invitationsDataState) {
                is DataState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(color = FridaGold)
                            Text("جاري تحميل الدعوات...", color = FridaTextSecondary, fontSize = 14.sp)
                        }
                    }
                }
                is DataState.Error -> {
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
                                text = state.message,
                                color = FridaRed,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Button(
                                onClick = {
                                    HapticUtils.performLightHaptic(view)
                                    invitationsViewModel.setStatusFilter(statusFilter)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = FridaGold, contentColor = FridaBlack),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("إعادة المحاولة", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                is DataState.Success -> {
                    val invitations = state.data.distinctBy { it.id }
                    if (invitations.isEmpty()) {
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
                                    imageVector = Icons.Default.MailOutline,
                                    contentDescription = null,
                                    tint = FridaTextTertiary,
                                    modifier = Modifier.size(56.dp)
                                )
                                Text(
                                    text = "لا توجد دعوات مطابقة للبحث أو الفلتر",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = FridaTextSecondary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
                        ) {
                            items(
                                items = invitations,
                                key = { inv -> inv.id.ifBlank { "${inv.slug}_${System.identityHashCode(inv)}" } }
                            ) { invitation ->
                                InvitationItemCard(
                                    invitation = invitation,
                                    onPreview = {
                                        previewSlugAndTitle = Pair(invitation.slug, invitation.coupleTitle)
                                    },
                                    onCopyGuestLink = {
                                        val guestUrl = com.example.util.WebsiteUrlProvider.getGuestUrl(invitation.slug)
                                        invitationsViewModel.copyLinks(context, "رابط الضيوف", guestUrl)
                                    },
                                    onCopyHostLink = {
                                        // The access code is stored only as a hash and cannot be shown again.
                                        val hostUrl = com.example.util.WebsiteUrlProvider.getHostPortalUrl(invitation.slug)
                                        val fullText = "$hostUrl\nاسم المستخدم: ${invitation.slug}"
                                        invitationsViewModel.copyLinks(context, "رابط بوابة المضيف", fullText)
                                    },
                                    onExtend = { extendInvitationTarget = invitation },
                                    onTerminate = { confirmTerminateTarget = invitation },
                                    onReactivate = { invitationsViewModel.reactivateInvitation(invitation.id) },
                                    onDelete = { confirmDeleteTarget = invitation }
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
                                        text = "تحميل المزيد من الدعوات",
                                        icon = Icons.Default.ExpandMore,
                                        onClick = { invitationsViewModel.loadMore() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }

            // WebView Preview Dialog
            previewSlugAndTitle?.let { (slug, title) ->
                InvitationPreviewDialog(
                    slug = slug,
                    title = title,
                    onDismiss = { previewSlugAndTitle = null }
                )
            }

            // Extend Dialog
            extendInvitationTarget?.let { inv ->
                ExtendInvitationDialog(
                    invitation = inv,
                    onConfirm = { days ->
                        invitationsViewModel.extendInvitation(inv.id, days)
                        extendInvitationTarget = null
                    },
                    onDismiss = { extendInvitationTarget = null }
                )
            }

            // Confirm Terminate Dialog
            confirmTerminateTarget?.let { inv ->
                ConfirmActionDialog(
                    title = "إنهاء صلاحية الدعوة",
                    message = "هل أنت متأكد من رغبتك في إيقاف وإنهاء صلاحية دعوة ${inv.coupleTitle}؟ لن يتمكن الضيوف من الوصول إليها.",
                    confirmText = "إيقاف الدعوة",
                    isDestructive = true,
                    onConfirm = {
                        invitationsViewModel.terminateInvitation(inv.id)
                        confirmTerminateTarget = null
                    },
                    onDismiss = { confirmTerminateTarget = null }
                )
            }

            // Confirm Delete Dialog
            confirmDeleteTarget?.let { inv ->
                ConfirmActionDialog(
                    title = "حذف الدعوة نهائياً",
                    message = "هل أنت متأكد من حذف دعوة ${inv.coupleTitle} نهائياً من قاعدة البيانات؟ لا يمكن التراجع عن هذا الإجراء.",
                    confirmText = "حذف نهائي",
                    isDestructive = true,
                    onConfirm = {
                        invitationsViewModel.deleteInvitation(inv.id)
                        confirmDeleteTarget = null
                    },
                    onDismiss = { confirmDeleteTarget = null }
                )
            }

            // Loading overlay
            if (actionState is InvitationActionState.Loading) {
                val msg = (actionState as InvitationActionState.Loading).message
                Dialog(onDismissRequest = {}) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = FridaCardElevated,
                        border = BorderStroke(1.dp, FridaGold)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(color = FridaGold)
                            Text(
                                text = msg,
                                color = FridaGoldLight,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InvitationItemCard(
    invitation: Invitation,
    onPreview: () -> Unit,
    onCopyGuestLink: () -> Unit,
    onCopyHostLink: () -> Unit,
    onExtend: () -> Unit,
    onTerminate: () -> Unit,
    onReactivate: () -> Unit,
    onDelete: () -> Unit
) {
    val coupleTitleText = remember(invitation) {
        try { invitation.coupleTitle } catch (e: Throwable) { "دعوة بدون عنوان" }
    }
    val slugText = remember(invitation) {
        try { invitation.slug } catch (e: Throwable) { "" }
    }
    val statusText = remember(invitation) {
        try { invitation.status } catch (e: Throwable) { "draft" }
    }
    val hostCodeText = remember(invitation) {
        try { invitation.hostCode ?: "" } catch (e: Throwable) { "" }
    }
    val eventDateText = remember(invitation) {
        try { invitation.eventDate } catch (e: Throwable) { "" }
    }
    val venueNameText = remember(invitation) {
        try { invitation.venueName } catch (e: Throwable) { "" }
    }

    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd", Locale("ar")) }
    val formattedExpiry = remember(invitation.expiresAt) {
        try {
            invitation.expiresAt?.toDate()?.let { dateFormat.format(it) } ?: "غير محدد"
        } catch (e: Throwable) {
            "غير محدد"
        }
    }

    LuxuryCard(
        isHighlighted = statusText == "published"
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = coupleTitleText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = FridaGoldLight
                )
                Text(
                    text = "الرابط: /i/$slugText",
                    style = MaterialTheme.typography.labelSmall,
                    color = FridaTextTertiary
                )
            }

            StatusBadge(status = statusText)
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Info details row
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = FridaSurface,
            border = BorderStroke(1.dp, FridaBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (eventDateText.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("موعد المناسبة:", color = FridaTextSecondary, fontSize = 12.sp)
                        Text(eventDateText, color = FridaTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }

                if (venueNameText.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("مكان الحفل / القاعة:", color = FridaTextSecondary, fontSize = 12.sp)
                        Text(venueNameText, color = FridaTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("تاريخ انتهاء الصلاحية:", color = FridaTextSecondary, fontSize = 12.sp)
                    Text(formattedExpiry, color = FridaGoldDeep, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }

                if (hostCodeText.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("كود المضيف:", color = FridaTextSecondary, fontSize = 12.sp)
                        Text(
                            text = hostCodeText,
                            color = FridaGoldLight,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Link Copying Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FridaOutlinedButton(
                text = "نسخ رابط الضيوف",
                icon = Icons.Default.Link,
                onClick = onCopyGuestLink,
                modifier = Modifier.weight(1f)
            )

            FridaOutlinedButton(
                text = "بوابة المضيف",
                icon = Icons.Default.AdminPanelSettings,
                onClick = onCopyHostLink,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Main action & lifecycle buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Preview WebView
            FridaGoldButton(
                text = "معاينة الدعوة",
                icon = Icons.Default.Visibility,
                onClick = onPreview,
                modifier = Modifier.weight(1.2f)
            )

            // Extend Days
            FridaOutlinedButton(
                text = "تمديد",
                icon = Icons.Default.Update,
                borderColor = FridaGold,
                textColor = FridaGold,
                onClick = onExtend,
                modifier = Modifier.weight(1f)
            )

            // More Lifecycle Actions Dropdown / buttons
            if (invitation.status == "published") {
                FridaOutlinedButton(
                    text = "إنهاء",
                    icon = Icons.Default.StopCircle,
                    borderColor = FridaAmber,
                    textColor = FridaAmber,
                    onClick = onTerminate,
                    modifier = Modifier.weight(0.9f)
                )
            } else {
                FridaOutlinedButton(
                    text = "تفعيل",
                    icon = Icons.Default.PlayCircle,
                    borderColor = FridaGreen,
                    textColor = FridaGreen,
                    onClick = onReactivate,
                    modifier = Modifier.weight(0.9f)
                )
            }

            // Direct Delete
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(46.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteForever,
                    contentDescription = "حذف نهائي",
                    tint = FridaRed
                )
            }
        }
    }
}
