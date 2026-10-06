package com.example.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.Invitation
import com.example.data.model.Order
import com.example.ui.theme.*

@Composable
fun RejectOrderDialog(
    order: Order,
    onConfirm: (reason: String) -> Unit,
    onDismiss: () -> Unit
) {
    var reason by remember { mutableStateOf("") }
    var selectedPreset by remember { mutableStateOf("") }

    val presetReasons = listOf(
        "رقم المحفظة غير مطابق للتحويل",
        "المبلغ المحول غير كافٍ للباقة المختارة",
        "لم يتم استلام رسالة تأكيد التحويل بنجاح",
        "طلب مكرر تم اعتماده مسبقًا"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Cancel,
                    contentDescription = null,
                    tint = FridaRed
                )
                Text(
                    text = "رفض الطلب رقم ${order.orderNumber}",
                    color = FridaTextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "يرجى تحديد أو كتابة سبب الرفض لتوضيحه للعميل ${order.clientName}:",
                    color = FridaTextSecondary,
                    fontSize = 13.sp
                )

                presetReasons.forEach { preset ->
                    FilterChip(
                        selected = selectedPreset == preset,
                        onClick = {
                            selectedPreset = preset
                            reason = preset
                        },
                        label = { Text(preset, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = FridaRedContainer,
                            selectedLabelColor = FridaRed,
                            containerColor = FridaCard,
                            labelColor = FridaTextSecondary
                        )
                    )
                }

                OutlinedTextField(
                    value = reason,
                    onValueChange = {
                        reason = it
                        selectedPreset = ""
                    },
                    label = { Text("سبب الرفض بالتفصيل") },
                    placeholder = { Text("اكتب سبب الرفض...") },
                    minLines = 3,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = FridaRed,
                        unfocusedBorderColor = FridaBorder,
                        focusedTextColor = FridaTextPrimary,
                        unfocusedTextColor = FridaTextPrimary
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (reason.isNotBlank()) {
                        onConfirm(reason)
                    }
                },
                enabled = reason.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FridaRed,
                    contentColor = Color.White
                )
            ) {
                Text("تأكيد الرفض", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء", color = FridaTextTertiary)
            }
        },
        containerColor = FridaCardElevated,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun ApproveSuccessDialog(
    order: Order,
    username: String,
    hostCode: String?,
    guestUrl: String,
    hostPortalUrl: String,
    hostCredentialsFailed: Boolean = false,
    onRegenerateCode: () -> Unit = {},
    onOpenWhatsApp: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    fun copy(label: String, value: String) {
        try {
            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
            android.widget.Toast.makeText(context, "تم نسخ $label", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.w("ApproveSuccessDialog", "copy failed: ${e.message}")
        }
    }

    @Composable
    fun CopyRow(title: String, value: String, mono: Boolean = false) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = FridaGoldContainer,
            border = androidx.compose.foundation.BorderStroke(1.dp, FridaGold),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, color = FridaGoldDeep, fontSize = 11.sp)
                    Text(
                        text = value,
                        color = FridaGoldLight,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (mono) 18.sp else 12.sp,
                        letterSpacing = if (mono) 2.sp else 0.sp
                    )
                }
                IconButton(onClick = { copy(title, value) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "نسخ", tint = FridaGold)
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (hostCredentialsFailed) Icons.Default.Warning else Icons.Default.Verified,
                contentDescription = null,
                tint = if (hostCredentialsFailed) FridaAmber else FridaGold,
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                text = if (hostCredentialsFailed) "تم الاعتماد مع تعذر توليد الكود" else "تم اعتماد وتفعيل الطلب بنجاح!",
                color = if (hostCredentialsFailed) FridaAmber else FridaGoldLight,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (hostCredentialsFailed) {
                    Text(
                        text = "تم تفعيل الطلب، لكن تعذر توليد كود المضيف. اضغط لإعادة المحاولة:",
                        color = FridaAmber,
                        textAlign = TextAlign.Center,
                        fontSize = 13.sp
                    )
                    Button(
                        onClick = onRegenerateCode,
                        colors = ButtonDefaults.buttonColors(containerColor = FridaAmber, contentColor = FridaBlack),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("إعادة توليد كود المضيف", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text(
                        text = "بيانات دخول المضيف للعميل ${order.clientName}:",
                        color = FridaTextSecondary,
                        textAlign = TextAlign.Center,
                        fontSize = 13.sp
                    )
                    CopyRow("اسم المستخدم (Username)", username, mono = true)
                    if (!hostCode.isNullOrBlank()) {
                        CopyRow("كود الدخول (Password)", hostCode, mono = true)
                        Text(
                            text = "يظهر الكود مرة واحدة فقط ولا يُحفظ نصاً في الخادم — انسخه أو أرسله الآن.",
                            color = FridaAmber,
                            textAlign = TextAlign.Center,
                            fontSize = 11.sp
                        )
                    }
                    CopyRow("رابط الدعوة للضيوف", guestUrl)
                    CopyRow("رابط لوحة تحكم المضيف", hostPortalUrl)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onOpenWhatsApp,
                enabled = !hostCredentialsFailed,
                colors = ButtonDefaults.buttonColors(containerColor = FridaGreen, contentColor = FridaBlack),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(imageVector = Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("إرسال للعميل عبر واتساب", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("تم وإغلاق", color = FridaTextSecondary)
            }
        },
        containerColor = FridaCardElevated,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun ExtendInvitationDialog(
    invitation: Invitation,
    onConfirm: (days: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var daysText by remember { mutableStateOf("30") }

    val dateFormat = remember { java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale("ar")) }
    val currentExpiry = remember(invitation.expiresAt) {
        invitation.expiresAt?.toDate() ?: java.util.Date()
    }
    val currentExpiryFormatted = remember(invitation.expiresAt) {
        invitation.expiresAt?.toDate()?.let { dateFormat.format(it) } ?: "غير محدد (ينتهي الآن)"
    }

    val newExpiryFormatted = remember(daysText, currentExpiry) {
        val days = daysText.toIntOrNull() ?: 0
        val cal = java.util.Calendar.getInstance()
        cal.time = currentExpiry
        cal.add(java.util.Calendar.DAY_OF_YEAR, days)
        dateFormat.format(cal.time)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "تمديد صلاحية الدعوة",
                color = FridaTextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "الدعوة: ${invitation.coupleTitle}",
                    color = FridaGoldLight,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "تاريخ انتهاء الصلاحية الحالي: $currentExpiryFormatted",
                    color = FridaTextSecondary,
                    fontSize = 13.sp
                )
                Text(
                    text = "حدد عدد الأيام الإضافية لتمديد صلاحية الدعوة وموقع المضيف:",
                    color = FridaTextSecondary,
                    fontSize = 13.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("7", "15", "30", "60").forEach { d ->
                        FilterChip(
                            selected = daysText == d,
                            onClick = { daysText = d },
                            label = { Text("$d يوم", fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = FridaGoldContainer,
                                selectedLabelColor = FridaGoldLight,
                                containerColor = FridaCard,
                                labelColor = FridaTextSecondary
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = daysText,
                    onValueChange = { daysText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("عدد الأيام") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = FridaGold,
                        unfocusedBorderColor = FridaBorder
                    )
                )

                Spacer(modifier = Modifier.height(4.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = FridaGoldContainer.copy(alpha = 0.3f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, FridaGold.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "تاريخ الانتهاء الجديد المحدّد:",
                            color = FridaGoldDeep,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = newExpiryFormatted,
                            color = FridaGoldLight,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val d = daysText.toIntOrNull() ?: 30
                    onConfirm(d)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = FridaGold,
                    contentColor = FridaBlack
                )
            ) {
                Text("تأكيد التمديد", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء", color = FridaTextTertiary)
            }
        },
        containerColor = FridaCardElevated,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun ConfirmActionDialog(
    title: String,
    message: String,
    confirmText: String = "تأكيد",
    isDestructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                color = if (isDestructive) FridaRed else FridaTextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = message,
                color = FridaTextSecondary,
                fontSize = 14.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDestructive) FridaRed else FridaGold,
                    contentColor = if (isDestructive) Color.White else FridaBlack
                )
            ) {
                Text(confirmText, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء", color = FridaTextTertiary)
            }
        },
        containerColor = FridaCardElevated,
        shape = RoundedCornerShape(16.dp)
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InvitationPreviewDialog(
    slug: String,
    title: String,
    onDismiss: () -> Unit
) {
    val fullUrl = com.example.util.WebsiteUrlProvider.getGuestUrl(slug)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = FridaSurface),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, FridaGoldDark)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(FridaCardElevated)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "معاينة الدعوة: $title",
                            color = FridaGoldLight,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = fullUrl,
                            color = FridaTextTertiary,
                            fontSize = 11.sp
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "إغلاق",
                            tint = FridaGold
                        )
                    }
                }

                // WebView
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .background(Color.Black)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    try {
                                        android.webkit.WebView.startSafeBrowsing(ctx) { success ->
                                            android.util.Log.d("WebView", "Safe Browsing initialized: $success")
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("WebView", "Safe Browsing not supported on this platform: ${e.message}")
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: android.webkit.WebResourceRequest?
                                    ): Boolean {
                                        val uri = request?.url ?: return true
                                        val host = uri.host?.lowercase() ?: return true
                                        // Allow only exact trusted hosts
                                        val baseUri = try { android.net.Uri.parse(com.example.util.WebsiteUrlProvider.getBaseUrl()) } catch (e: Exception) { null }
                                        val baseHost = baseUri?.host?.lowercase() ?: "frida-ed3b5.web.app"
                                        if (host == baseHost || host.endsWith(".web.app") || host.endsWith(".firebaseapp.com")) {
                                            return false
                                        }
                                        return true // Block external navigation in preview
                                    }
                                }
                                loadUrl(fullUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
