package com.example.ui.settings

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.SettingsDataState
import com.example.ui.components.*
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val publicConfigState by settingsViewModel.publicConfigState.collectAsState()
    val adminConfigState by settingsViewModel.adminConfigState.collectAsState()
    val saveState by settingsViewModel.saveState.collectAsState()

    var vodafoneCashNumber by remember { mutableStateOf("") }
    var walletOwnerName by remember { mutableStateOf("") }
    var tier1Price by remember { mutableStateOf("") }
    var tier2Price by remember { mutableStateOf("") }
    var tier3Price by remember { mutableStateOf("") }
    var customerSupportWhatsApp by remember { mutableStateOf("") }

    var maintenanceMode by remember { mutableStateOf(false) }
    var autoNotificationEnabled by remember { mutableStateOf(true) }
    var cloudflareWorkerUrl by remember { mutableStateOf("") }
    var websiteBaseUrl by remember { mutableStateOf(com.example.util.WebsiteUrlProvider.getBaseUrl()) }

    var biometricEnabled by remember { mutableStateOf(settingsViewModel.isBiometricEnabled()) }
    var showPinDialog by remember { mutableStateOf(false) }
    var newPinInput by remember { mutableStateOf("") }

    var isPublicLoaded by remember { mutableStateOf(false) }
    var isAdminLoaded by remember { mutableStateOf(false) }

    // Synchronize form when data loads from Firestore
    LaunchedEffect(publicConfigState) {
        if (publicConfigState is SettingsDataState.Success) {
            val cfg = (publicConfigState as SettingsDataState.Success).data
            vodafoneCashNumber = cfg.vodafoneCashNumber
            walletOwnerName = cfg.walletOwnerName
            tier1Price = if (cfg.tier1Price > 0) cfg.tier1Price.toString() else ""
            tier2Price = if (cfg.tier2Price > 0) cfg.tier2Price.toString() else ""
            tier3Price = if (cfg.tier3Price > 0) cfg.tier3Price.toString() else ""
            customerSupportWhatsApp = cfg.customerSupportWhatsApp
            isPublicLoaded = true
        }
    }

    LaunchedEffect(adminConfigState) {
        if (adminConfigState is SettingsDataState.Success) {
            val cfg = (adminConfigState as SettingsDataState.Success).data
            maintenanceMode = cfg.maintenanceMode
            autoNotificationEnabled = cfg.autoNotificationEnabled
            cloudflareWorkerUrl = cfg.cloudflareWorkerUrl
            websiteBaseUrl = cfg.websiteBaseUrl
            isAdminLoaded = true
        }
    }

    LaunchedEffect(saveState) {
        when (val s = saveState) {
            is SettingsSaveState.Success -> {
                Toast.makeText(context, s.message, Toast.LENGTH_LONG).show()
                settingsViewModel.clearSaveState()
            }
            is SettingsSaveState.Error -> {
                Toast.makeText(context, s.message, Toast.LENGTH_LONG).show()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            FridaTopBar(
                title = "الإعدادات وأسعار الباقات",
                subtitle = "التحكم المباشر في إعدادات المنصة"
            )
        },
        containerColor = FridaBlack,
        modifier = modifier
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 40.dp)
        ) {
            // Loading / Error Banner for Public Config
            if (publicConfigState is SettingsDataState.Loading) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = FridaCard,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = FridaGold, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("جاري مزامنة إعدادات المنصة من السيرفر...", color = FridaTextSecondary, fontSize = 13.sp)
                        }
                    }
                }
            } else if (publicConfigState is SettingsDataState.Error) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = FridaCardElevated,
                        border = BorderStroke(1.dp, FridaRed),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = FridaRed)
                            Text((publicConfigState as SettingsDataState.Error).message, color = FridaTextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            // Section 1: Vodafone Cash Payment Settings
            item {
                Text(
                    text = "بيانات استقبال تحويلات فودافون كاش",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = FridaGoldLight
                )
            }

            item {
                LuxuryCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = vodafoneCashNumber,
                            onValueChange = { vodafoneCashNumber = it },
                            label = { Text("رقم فودافون كاش المستلم") },
                            placeholder = { Text("010XXXXXXXX") },
                            leadingIcon = {
                                Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = FridaRed)
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = FridaGold,
                                unfocusedBorderColor = FridaBorder
                            )
                        )

                        OutlinedTextField(
                            value = walletOwnerName,
                            onValueChange = { walletOwnerName = it },
                            label = { Text("اسم صاحب المحفظة (الذي يظهر للعميل)") },
                            placeholder = { Text("منصة فريدا للدعوات") },
                            leadingIcon = {
                                Icon(Icons.Default.Person, contentDescription = null, tint = FridaGold)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = FridaGold,
                                unfocusedBorderColor = FridaBorder
                            )
                        )
                    }
                }
            }

            // Section 2: Package Pricing
            item {
                Text(
                    text = "أسعار باقات الدعوات (ج.م)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = FridaGoldLight
                )
            }

            item {
                LuxuryCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Tier 1
                        val t1Valid = tier1Price.toDoubleOrNull()?.let { it > 0 } ?: false
                        OutlinedTextField(
                            value = tier1Price,
                            onValueChange = { tier1Price = it },
                            label = { Text("سعر الباقة الفضية / الأساسية (ج.م)") },
                            leadingIcon = {
                                Icon(Icons.Default.Payments, contentDescription = null, tint = FridaGold)
                            },
                            isError = tier1Price.isNotBlank() && !t1Valid,
                            supportingText = if (tier1Price.isNotBlank() && !t1Valid) {
                                { Text("يجب إدخال سعر صحيح أكبر من صفر", color = FridaRed) }
                            } else null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = FridaGold,
                                unfocusedBorderColor = FridaBorder
                            )
                        )

                        // Tier 2
                        val t2Valid = tier2Price.toDoubleOrNull()?.let { it > 0 } ?: false
                        OutlinedTextField(
                            value = tier2Price,
                            onValueChange = { tier2Price = it },
                            label = { Text("سعر الباقة الذهبية (ج.م)") },
                            leadingIcon = {
                                Icon(Icons.Default.WorkspacePremium, contentDescription = null, tint = FridaGold)
                            },
                            isError = tier2Price.isNotBlank() && !t2Valid,
                            supportingText = if (tier2Price.isNotBlank() && !t2Valid) {
                                { Text("يجب إدخال سعر صحيح أكبر من صفر", color = FridaRed) }
                            } else null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = FridaGold,
                                unfocusedBorderColor = FridaBorder
                            )
                        )

                        // Tier 3
                        val t3Valid = tier3Price.toDoubleOrNull()?.let { it > 0 } ?: false
                        OutlinedTextField(
                            value = tier3Price,
                            onValueChange = { tier3Price = it },
                            label = { Text("سعر الباقة الملكية / VIP (ج.م)") },
                            leadingIcon = {
                                Icon(Icons.Default.Diamond, contentDescription = null, tint = FridaGoldLight)
                            },
                            isError = tier3Price.isNotBlank() && !t3Valid,
                            supportingText = if (tier3Price.isNotBlank() && !t3Valid) {
                                { Text("يجب إدخال سعر صحيح أكبر من صفر", color = FridaRed) }
                            } else null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = FridaGold,
                                unfocusedBorderColor = FridaBorder
                            )
                        )

                        // WhatsApp Support
                        OutlinedTextField(
                            value = customerSupportWhatsApp,
                            onValueChange = { customerSupportWhatsApp = it },
                            label = { Text("رقم واتساب الدعم الفني وخدمة العملاء") },
                            placeholder = { Text("2010XXXXXXXX") },
                            leadingIcon = {
                                Icon(Icons.Default.Chat, contentDescription = null, tint = FridaGreen)
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = FridaGold,
                                unfocusedBorderColor = FridaBorder
                            )
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        FridaGoldButton(
                            text = "حفظ إعدادات الباقات والدفع",
                            icon = Icons.Default.Save,
                            isLoading = saveState is SettingsSaveState.Saving,
                            enabled = isPublicLoaded && saveState !is SettingsSaveState.Saving,
                            onClick = {
                                HapticUtils.performLightHaptic(view)
                                settingsViewModel.updatePublicConfig(
                                    vodafoneCashNumber = vodafoneCashNumber,
                                    walletOwnerName = walletOwnerName,
                                    tier1PriceStr = tier1Price,
                                    tier2PriceStr = tier2Price,
                                    tier3PriceStr = tier3Price,
                                    supportWhatsApp = customerSupportWhatsApp
                                )
                            }
                        )
                    }
                }
            }

            // Section 3: Local App Security (Biometrics & PIN)
            item {
                Text(
                    text = "الأمان والحماية المحلية للتطبيق",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = FridaGoldLight
                )
            }

            item {
                LuxuryCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "القفل ببصمة الإصبع أو الوجه",
                                    color = FridaTextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "طلب المصادقة الحيوية عند فتح التطبيق أو العودة من الخلفية",
                                    color = FridaTextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            Switch(
                                checked = biometricEnabled,
                                onCheckedChange = {
                                    biometricEnabled = it
                                    settingsViewModel.setBiometricEnabled(it)
                                    HapticUtils.performLightHaptic(view)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = FridaGold,
                                    checkedTrackColor = FridaGoldContainer
                                )
                            )
                        }

                        Divider(color = FridaBorder)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "رمز PIN الإداري",
                                    color = FridaTextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = if (settingsViewModel.hasPin()) "تم تعيين رمز PIN مشفّر" else "غير مفعّل",
                                    color = if (settingsViewModel.hasPin()) FridaGreen else FridaTextTertiary,
                                    fontSize = 12.sp
                                )
                            }
                            FridaOutlinedButton(
                                text = if (settingsViewModel.hasPin()) "تغيير PIN" else "تعيين PIN",
                                onClick = { showPinDialog = true }
                            )
                        }
                    }
                }
            }

            // Section 4: Website Domain & Server Configuration
            item {
                Text(
                    text = "إعدادات منصة الويب والخادم السحابي",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = FridaGoldLight
                )
            }

            item {
                LuxuryCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = websiteBaseUrl,
                            onValueChange = { websiteBaseUrl = it },
                            label = { Text("رابط منصة الويب الأساسي (Website Base URL)") },
                            placeholder = { Text("https://frida-ed3b5.web.app") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = FridaGold,
                                unfocusedBorderColor = FridaBorder
                            )
                        )

                        OutlinedTextField(
                            value = cloudflareWorkerUrl,
                            onValueChange = { cloudflareWorkerUrl = it },
                            label = { Text("رابط الـ Cloudflare Worker الأساسي") },
                            placeholder = { Text(com.example.util.AppConfig.defaultWorkerUrl) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = FridaGold,
                                unfocusedBorderColor = FridaBorder
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "وضع الصيانة (Maintenance Mode)",
                                    color = FridaTextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "إيقاف استقبال طلبات جديدة مؤقتاً في الموقع",
                                    color = FridaTextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            Switch(
                                checked = maintenanceMode,
                                onCheckedChange = { maintenanceMode = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = FridaAmber,
                                    checkedTrackColor = FridaAmberContainer
                                )
                            )
                        }

                        FridaOutlinedButton(
                            text = "حفظ إعدادات المنصة والخادم",
                            icon = Icons.Default.CloudSync,
                            enabled = isAdminLoaded && saveState !is SettingsSaveState.Saving,
                            onClick = {
                                HapticUtils.performLightHaptic(view)
                                settingsViewModel.updateAdminConfig(
                                    maintenanceMode = maintenanceMode,
                                    autoNotification = autoNotificationEnabled,
                                    cloudflareWorkerUrl = cloudflareWorkerUrl,
                                    websiteBaseUrl = websiteBaseUrl
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Section 5: Logout
            item {
                Button(
                    onClick = {
                        HapticUtils.performLightHaptic(view)
                        settingsViewModel.signOut()
                        onSignOut()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FridaRedContainer,
                        contentColor = FridaRed
                    ),
                    border = BorderStroke(1.dp, FridaRed)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Logout, contentDescription = null, tint = FridaRed)
                        Text(
                            text = "تسجيل الخروج من الحساب الإداري",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    // Set/Change PIN Dialog
    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = {
                showPinDialog = false
                newPinInput = ""
            },
            title = {
                Text(
                    text = "تعيين رمز PIN الأمان",
                    color = FridaTextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "أدخل رمز PIN مكوّن من 4 أرقام:",
                        color = FridaTextSecondary,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = newPinInput,
                        onValueChange = {
                            if (it.length <= 4 && it.all { ch -> ch.isDigit() }) {
                                newPinInput = it
                            }
                        },
                        label = { Text("رمز PIN (4 أرقام)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = FridaGold,
                            unfocusedBorderColor = FridaBorder
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPinInput.length == 4) {
                            settingsViewModel.setPin(newPinInput)
                            Toast.makeText(context, "تم تعيين رمز PIN بنجاح", Toast.LENGTH_SHORT).show()
                            showPinDialog = false
                            newPinInput = ""
                        }
                    },
                    enabled = newPinInput.length == 4,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FridaGold,
                        contentColor = FridaBlack
                    )
                ) {
                    Text("حفظ الرمز", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPinDialog = false
                        newPinInput = ""
                    }
                ) {
                    Text("إلغاء", color = FridaTextTertiary)
                }
            },
            containerColor = FridaCardElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }
}
