package com.example.ui.auth

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.FridaGoldButton
import com.example.ui.components.HapticUtils
import com.example.ui.theme.*

@Composable
fun LoginScreen(
    authViewModel: AuthViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val uiState by authViewModel.uiState.collectAsState()

    val activity = context as? Activity
    val scrollState = rememberScrollState()

    var showEmailForm by remember { mutableStateOf(false) }
    var emailInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(uiState) {
        if (uiState is AuthUiState.Error) {
            showEmailForm = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        FridaBlack,
                        FridaSurface,
                        FridaCard
                    )
                )
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // Luxury Logo Frame
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(FridaCardElevated)
                    .border(
                        BorderStroke(
                            2.dp,
                            Brush.linearGradient(listOf(FridaGold, FridaGoldLight, FridaGoldDark))
                        ),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.frida_admin_logo_1790770643013),
                    contentDescription = "شعار فريدا للمناسبات",
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "FRIDA Admin",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = FridaGoldLight,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "بوابة إدارة المنصة والطلبات الملكية",
                style = MaterialTheme.typography.bodyMedium,
                color = FridaTextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Error or Missing Account Message Display
            if (uiState is AuthUiState.Error) {
                val errorState = uiState as AuthUiState.Error
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = FridaCardElevated,
                    border = BorderStroke(1.dp, if (errorState.isNoCredentialError) FridaAmber else FridaRed),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = if (errorState.isNoCredentialError) Icons.Default.Info else Icons.Default.GppBad,
                                contentDescription = null,
                                tint = if (errorState.isNoCredentialError) FridaAmber else FridaRed,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = errorState.message,
                                color = FridaTextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (errorState.isNoCredentialError) {
                            Button(
                                onClick = {
                                    HapticUtils.performLightHaptic(view)
                                    try {
                                        val intent = Intent(Settings.ACTION_ADD_ACCOUNT).apply {
                                            putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        try {
                                            val intent = Intent(Settings.ACTION_SYNC_SETTINGS)
                                            context.startActivity(intent)
                                        } catch (ignored: Exception) {}
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = FridaAmber,
                                    contentColor = FridaBlack
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "فتح إعدادات الحسابات لإضافة Google",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Google Sign In via Credential Manager
            Button(
                onClick = {
                    HapticUtils.performLightHaptic(view)
                    activity?.let { authViewModel.startGoogleSignIn(it) }
                },
                enabled = uiState !is AuthUiState.Loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FridaGold,
                    contentColor = FridaBlack,
                    disabledContainerColor = FridaCardElevated,
                    disabledContentColor = FridaTextTertiary
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp)
            ) {
                if (uiState is AuthUiState.Loading && !showEmailForm) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = FridaBlack,
                        strokeWidth = 2.5.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "جاري التحقق من صلاحيات المدير...",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "تسجيل الدخول بحساب Google",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (uiState is AuthUiState.Loading) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        HapticUtils.performLightHaptic(view)
                        authViewModel.clearLoading()
                    }
                ) {
                    Text(
                        text = "إلغاء والعودة",
                        color = FridaTextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Option to toggle Email/Password Form
            TextButton(
                onClick = {
                    showEmailForm = !showEmailForm
                    authViewModel.clearError()
                }
            ) {
                Text(
                    text = if (showEmailForm) "إخفاء الدخول بالبريد وكلمة المرور" else "أو الدخول بالبريد الإلكتروني وكلمة المرور",
                    color = FridaGoldDeep,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Email & Password Expansion Form
            AnimatedVisibility(
                visible = showEmailForm,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = FridaCard,
                    border = BorderStroke(1.dp, FridaBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "بيانات المدير المسجلة",
                            style = MaterialTheme.typography.titleSmall,
                            color = FridaGoldLight,
                            fontWeight = FontWeight.Bold
                        )

                        OutlinedTextField(
                            value = emailInput,
                            onValueChange = { emailInput = it },
                            label = { Text("البريد الإلكتروني") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Email,
                                    contentDescription = null,
                                    tint = FridaGold
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = FridaGold,
                                unfocusedBorderColor = FridaBorder,
                                focusedTextColor = FridaTextPrimary,
                                unfocusedTextColor = FridaTextPrimary,
                                focusedContainerColor = FridaSurface,
                                unfocusedContainerColor = FridaSurface
                            ),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            label = { Text("كلمة المرور") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = FridaGold
                                )
                            },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = if (passwordVisible) "إخفاء كلمة المرور" else "إظهار كلمة المرور",
                                        tint = FridaTextSecondary
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = FridaGold,
                                unfocusedBorderColor = FridaBorder,
                                focusedTextColor = FridaTextPrimary,
                                unfocusedTextColor = FridaTextPrimary,
                                focusedContainerColor = FridaSurface,
                                unfocusedContainerColor = FridaSurface
                            ),
                            singleLine = true
                        )

                        FridaGoldButton(
                            text = "دخول بالبريد",
                            icon = Icons.Default.Login,
                            isLoading = uiState is AuthUiState.Loading && showEmailForm,
                            onClick = {
                                HapticUtils.performLightHaptic(view)
                                authViewModel.signInWithEmail(emailInput, passwordInput)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Security Disclaimer Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = FridaGoldDeep,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "مصادقة إدارية مشفّرة عبر Firebase Custom Claims",
                    color = FridaTextTertiary,
                    fontSize = 11.sp
                )
            }
        }
    }
}
