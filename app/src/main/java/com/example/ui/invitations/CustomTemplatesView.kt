package com.example.ui.invitations

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CustomTemplate
import com.example.ui.components.FridaGoldButton
import com.example.ui.components.LuxuryCard
import com.example.ui.theme.*

@Composable
fun CustomTemplatesView(
    templates: List<CustomTemplate>,
    onSaveTemplate: (CustomTemplate) -> Unit,
    onToggleActive: (String, Boolean) -> Unit,
    onDeleteTemplate: (String) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editingTemplate by remember { mutableStateOf<CustomTemplate?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "قوالب الدعوات المخصصة (${templates.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = FridaGoldLight
            )
            FridaGoldButton(
                text = "إضافة قالب",
                icon = Icons.Default.Add,
                onClick = {
                    editingTemplate = CustomTemplate()
                    showAddDialog = true
                }
            )
        }

        if (templates.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "لا توجد قوالب مخصصة مضافة حتى الآن",
                    color = FridaTextSecondary,
                    fontSize = 14.sp
                )
            }
        } else {
            val uniqueTemplates = remember(templates) { templates.distinctBy { it.id } }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    items = uniqueTemplates,
                    key = { tmpl -> tmpl.id.ifBlank { "${tmpl.name}_${System.identityHashCode(tmpl)}" } }
                ) { template ->
                    LuxuryCard(onClick = {
                        editingTemplate = template
                        showAddDialog = true
                    }) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = template.name,
                                    fontWeight = FontWeight.Bold,
                                    color = FridaGoldLight,
                                    fontSize = 15.sp
                                )
                                if (template.description.isNotBlank()) {
                                    Text(
                                        text = template.description,
                                        color = FridaTextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                                Text(
                                    text = "التصنيف: ${template.category}",
                                    color = FridaTextTertiary,
                                    fontSize = 11.sp
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Switch(
                                    checked = template.isActive,
                                    onCheckedChange = { isActive ->
                                        onToggleActive(template.id, isActive)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = FridaBlack,
                                        checkedTrackColor = FridaGold,
                                        uncheckedThumbColor = FridaTextTertiary,
                                        uncheckedTrackColor = FridaCardElevated
                                    )
                                )

                                IconButton(onClick = { onDeleteTemplate(template.id) }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "حذف القالب",
                                        tint = FridaRed
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog && editingTemplate != null) {
        val target = editingTemplate!!
        var name by remember { mutableStateOf(target.name) }
        var description by remember { mutableStateOf(target.description) }
        var previewUrl by remember { mutableStateOf(target.previewImageUrl) }
        var category by remember { mutableStateOf(target.category) }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = {
                Text(
                    text = if (target.id.isBlank()) "إضافة قالب مخصص جديد" else "تعديل القالب المخصص",
                    color = FridaGoldLight,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("اسم القالب") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("وصف القالب") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = previewUrl,
                        onValueChange = { previewUrl = it },
                        label = { Text("رابط صورة المعاينة (URL)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = { Text("التصنيف (مثال: wedding, royal, classic)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (name.isNotBlank()) {
                            onSaveTemplate(
                                target.copy(
                                    name = name.trim(),
                                    description = description.trim(),
                                    previewImageUrl = previewUrl.trim(),
                                    category = category.trim()
                                )
                            )
                            showAddDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = FridaGold, contentColor = FridaBlack)
                ) {
                    Text("حفظ القالب", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("إلغاء", color = FridaTextSecondary)
                }
            },
            containerColor = FridaCardElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }
}
