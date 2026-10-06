package com.example.ui.reviews_audio

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.AudioTrack
import com.example.data.model.Review
import com.example.ui.components.*
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewsAudioScreen(
    viewModel: ReviewsAudioViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val reviews by viewModel.reviews.collectAsState()
    val audioTracks by viewModel.audioTracks.collectAsState()
    val actionState by viewModel.actionState.collectAsState()
    val audioProgress by viewModel.audioProgress.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("التقييمات والآراء (${reviews.size})", "مكتبة الموسيقى R2 (${audioTracks.size})")

    var confirmDeleteReviewTarget by remember { mutableStateOf<Review?>(null) }
    var confirmDeleteAudioTarget by remember { mutableStateOf<AudioTrack?>(null) }

    var selectedAudioUri by remember { mutableStateOf<Uri?>(null) }
    var showTrimDialog by remember { mutableStateOf(false) }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedAudioUri = uri
            showTrimDialog = true
        }
    }

    var playingTrackUrl by remember { mutableStateOf<String?>(null) }
    var mediaPlayerState by remember { mutableStateOf<MediaPlayer?>(null) }
    var isTrackPlaying by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val mp = MediaPlayer()
        mediaPlayerState = mp
        onDispose {
            mp.release()
        }
    }

    Scaffold(
        topBar = {
            FridaTopBar(
                title = "التقييمات ومكتبة الموسيقى",
                subtitle = "إدارة محتوى الموقع والوسائط"
            )
        },
        floatingActionButton = {
            if (selectedTabIndex == 1) {
                ExtendedFloatingActionButton(
                    onClick = {
                        HapticUtils.performLightHaptic(view)
                        audioPickerLauncher.launch("audio/*")
                    },
                    containerColor = FridaGold,
                    contentColor = FridaBlack,
                    icon = { Icon(Icons.Default.CloudUpload, contentDescription = null) },
                    text = { Text("رفع وقص مقطع صوتي", fontWeight = FontWeight.Bold) }
                )
            }
        },
        containerColor = FridaBlack,
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Tab Row
            PrimaryTabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = FridaSurface,
                contentColor = FridaGold,
                indicator = {
                    TabRowDefaults.PrimaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(selectedTabIndex),
                        color = FridaGold
                    )
                }
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = {
                            HapticUtils.performLightHaptic(view)
                            selectedTabIndex = index
                        },
                        text = {
                            Text(
                                text = title,
                                fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTabIndex == index) FridaGoldLight else FridaTextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    )
                }
            }

            // Tab Content
            when (selectedTabIndex) {
                0 -> {
                    // Reviews Tab
                    if (reviews.isEmpty()) {
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
                                    imageVector = Icons.Default.StarOutline,
                                    contentDescription = null,
                                    tint = FridaTextTertiary,
                                    modifier = Modifier.size(56.dp)
                                )
                                Text(
                                    text = "لا توجد تقييمات مضافة حالياً في قاعدة البيانات",
                                    color = FridaTextSecondary,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(top = 16.dp, bottom = 40.dp)
                        ) {
                            items(reviews, key = { it.id }) { review ->
                                ReviewItemCard(
                                    review = review,
                                    onApprove = { viewModel.approveReview(review.id) },
                                    onDelete = { confirmDeleteReviewTarget = review }
                                )
                            }
                        }
                    }
                }
                1 -> {
                    // Audio Tracks Tab (Cloudflare R2)
                    Column(modifier = Modifier.fillMaxSize()) {
                        Surface(
                            shape = RoundedCornerShape(0.dp),
                            color = FridaCardElevated,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudQueue,
                                    contentDescription = null,
                                    tint = FridaGold,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "الحذف من هنا يقوم بطلب نقطة نهاية Worker لحذف الملف الفعلي من Cloudflare R2",
                                    color = FridaGoldLight,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        // Audio header action row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "المقاطع الصوتية المتوفرة (${audioTracks.size})",
                                color = FridaGoldLight,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Button(
                                onClick = {
                                    HapticUtils.performLightHaptic(view)
                                    audioPickerLauncher.launch("audio/*")
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = FridaGold,
                                    contentColor = FridaBlack
                                )
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("رفع وقص مقطع", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }

                        if (audioTracks.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LibraryMusic,
                                        contentDescription = null,
                                        tint = FridaTextTertiary,
                                        modifier = Modifier.size(56.dp)
                                    )
                                    Text(
                                        text = "لا توجد مقاطع صوتية مسجلة حالياً في قائمة الصوتيات",
                                        color = FridaTextSecondary,
                                        fontSize = 14.sp
                                    )
                                    FridaGoldButton(
                                        text = "رفع وقص أول مقطع صوتي",
                                        icon = Icons.Default.CloudUpload,
                                        onClick = {
                                            HapticUtils.performLightHaptic(view)
                                            audioPickerLauncher.launch("audio/*")
                                        }
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(top = 14.dp, bottom = 40.dp)
                            ) {
                                items(audioTracks, key = { it.key }) { track ->
                                    val isPlaying = isTrackPlaying && playingTrackUrl == track.url
                                    AudioTrackItemCard(
                                        track = track,
                                        isPlaying = isPlaying,
                                        onTogglePlay = {
                                            HapticUtils.performLightHaptic(view)
                                            val mp = mediaPlayerState ?: return@AudioTrackItemCard
                                            if (playingTrackUrl == track.url) {
                                                if (mp.isPlaying) {
                                                    mp.pause()
                                                    isTrackPlaying = false
                                                } else {
                                                    mp.start()
                                                    isTrackPlaying = true
                                                }
                                            } else {
                                                try {
                                                    mp.reset()
                                                    if (track.url.startsWith("data:")) {
                                                        val cleanKey = track.key.replace(Regex("[^a-zA-Z0-9]"), "_")
                                                        val cacheFile = java.io.File(context.cacheDir, "track_$cleanKey.mp3")
                                                        if (!cacheFile.exists() || cacheFile.length() == 0L) {
                                                            val base64Data = track.url.substringAfter("base64,")
                                                            val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                                                            cacheFile.writeBytes(decodedBytes)
                                                        }
                                                        mp.setDataSource(cacheFile.absolutePath)
                                                    } else {
                                                        mp.setDataSource(track.url)
                                                    }
                                                    mp.prepareAsync()
                                                    playingTrackUrl = track.url
                                                    isTrackPlaying = false
                                                    mp.setOnPreparedListener {
                                                        it.start()
                                                        isTrackPlaying = true
                                                    }
                                                    mp.setOnCompletionListener {
                                                        isTrackPlaying = false
                                                        playingTrackUrl = null
                                                    }
                                                } catch (e: Exception) {
                                                    android.util.Log.e("ReviewsAudioScreen", "Error playing preview", e)
                                                    android.widget.Toast.makeText(context, "تعذر تشغيل المقطع: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        onDelete = { confirmDeleteAudioTarget = track }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Confirm Delete Review Dialog
    confirmDeleteReviewTarget?.let { review ->
        ConfirmActionDialog(
            title = "حذف التقييم",
            message = "هل أنت متأكد من حذف تقييم العميل \"${review.userName}\"؟",
            confirmText = "حذف التقييم",
            isDestructive = true,
            onConfirm = {
                viewModel.deleteReview(review.id)
                confirmDeleteReviewTarget = null
            },
            onDismiss = { confirmDeleteReviewTarget = null }
        )
    }

    // Confirm Delete Audio Track Dialog
    confirmDeleteAudioTarget?.let { track ->
        ConfirmActionDialog(
            title = "حذف المقطع الصوتي من Cloudflare R2",
            message = "هل أنت متأكد من حذف المقطع \"${track.title}\" (${track.key}) نهائياً من سيرفر التخزين السحابي Cloudflare R2؟",
            confirmText = "حذف المقطع من R2",
            isDestructive = true,
            onConfirm = {
                viewModel.deleteAudioTrack(track.key)
                confirmDeleteAudioTarget = null
            },
            onDismiss = { confirmDeleteAudioTarget = null }
        )
    }

    // Audio Trim & Upload Dialog
    if (showTrimDialog && selectedAudioUri != null) {
        AudioTrimAndUploadDialog(
            uri = selectedAudioUri!!,
            onDismiss = {
                showTrimDialog = false
                selectedAudioUri = null
            },
            onUpload = { title, artist, startMs, endMs, totalDurationMs ->
                viewModel.trimAndUploadAudioTrack(
                    context = context,
                    audioUri = selectedAudioUri!!,
                    title = title,
                    artist = artist,
                    startMs = startMs,
                    endMs = endMs,
                    totalDurationMs = totalDurationMs
                )
                showTrimDialog = false
                selectedAudioUri = null
            }
        )
    }

    // Live Real-Time Audio Upload & Trimming Progress Dialog
    if (audioProgress.isProcessing) {
        AudioUploadProgressDialog(progress = audioProgress)
    }

    // Action Loading overlay
    if (actionState is ReviewAudioActionState.Loading) {
        val loading = actionState as ReviewAudioActionState.Loading
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
                    CircularProgressIndicator(color = FridaGold)
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
}

@Composable
fun AudioUploadProgressDialog(
    progress: AudioProgressState
) {
    Dialog(onDismissRequest = {}) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = FridaCardElevated,
            border = BorderStroke(1.5.dp, FridaGold),
            modifier = Modifier.widthIn(min = 300.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Circular percentage display
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(80.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { progress.progressFraction },
                        color = FridaGold,
                        trackColor = FridaBorder,
                        strokeWidth = 6.dp,
                        modifier = Modifier.fillMaxSize()
                    )
                    Text(
                        text = "${progress.percentage}%",
                        color = FridaGoldLight,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = progress.stage,
                    color = FridaGoldLight,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                LinearProgressIndicator(
                    progress = { progress.progressFraction },
                    color = FridaGold,
                    trackColor = FridaCard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                )

                if (progress.detail.isNotBlank()) {
                    Text(
                        text = progress.detail,
                        color = FridaTextSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun AudioTrimAndUploadDialog(
    uri: Uri,
    onDismiss: () -> Unit,
    onUpload: (title: String, artist: String, startMs: Long, endMs: Long, totalDurationMs: Long) -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("مقطع موسيقي جديد") }
    var artist by remember { mutableStateOf("فريدا") }
    var totalDurationMs by remember { mutableLongStateOf(60000L) }
    var startMs by remember { mutableLongStateOf(0L) }
    var endMs by remember { mutableLongStateOf(60000L) }
    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(Unit) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (dur != null && dur > 0) {
                totalDurationMs = dur
                endMs = dur.coerceAtMost(120000L)
            }
            val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            if (!metaTitle.isNullOrBlank()) title = metaTitle
            val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            if (!metaArtist.isNullOrBlank()) artist = metaArtist
            retriever.release()
        } catch (e: Exception) {
            // non-fatal
        }

        onDispose {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    fun togglePlay() {
        if (isPlaying) {
            mediaPlayer?.pause()
            isPlaying = false
        } else {
            try {
                if (mediaPlayer == null) {
                    mediaPlayer = MediaPlayer.create(context, uri)?.apply {
                        setOnCompletionListener { isPlaying = false }
                    }
                }
                mediaPlayer?.seekTo(startMs.toInt())
                mediaPlayer?.start()
                isPlaying = true
            } catch (e: Exception) {
                // non-fatal
            }
        }
    }

    Dialog(onDismissRequest = {
        mediaPlayer?.stop()
        onDismiss()
    }) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = FridaCardElevated,
            border = BorderStroke(1.5.dp, FridaGold),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "قص ورفع مقطع موسيقي",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = FridaGoldLight
                    )
                    IconButton(onClick = {
                        mediaPlayer?.stop()
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = FridaTextTertiary)
                    }
                }

                // Audio Info Fields
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("عنوان المقطع الموسيقي") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = FridaGold,
                        unfocusedBorderColor = FridaBorder
                    )
                )

                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("اسم المنشد / الفنان") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = FridaGold,
                        unfocusedBorderColor = FridaBorder
                    )
                )

                // Trimming controls
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = FridaCard,
                    border = BorderStroke(1.dp, FridaBorderGold.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "أداة تحديد وقص المقطع",
                                color = FridaGold,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            val snippetDuration = ((endMs - startMs) / 1000).coerceAtLeast(1)
                            Text(
                                text = "المدة: %02d:%02d".format(snippetDuration / 60, snippetDuration % 60),
                                color = FridaGoldLight,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }

                        // Start time slider
                        Column {
                            val startSec = startMs / 1000
                            Text(
                                text = "وقت البداية: %02d:%02d".format(startSec / 60, startSec % 60),
                                color = FridaTextSecondary,
                                fontSize = 12.sp
                            )
                            Slider(
                                value = startMs.toFloat(),
                                onValueChange = {
                                    startMs = it.toLong().coerceAtMost(endMs - 1000L)
                                    mediaPlayer?.seekTo(startMs.toInt())
                                },
                                valueRange = 0f..totalDurationMs.toFloat().coerceAtLeast(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = FridaGold,
                                    activeTrackColor = FridaGold
                                )
                            )
                        }

                        // End time slider
                        Column {
                            val endSec = endMs / 1000
                            Text(
                                text = "وقت النهاية: %02d:%02d".format(endSec / 60, endSec % 60),
                                color = FridaTextSecondary,
                                fontSize = 12.sp
                            )
                            Slider(
                                value = endMs.toFloat(),
                                onValueChange = {
                                    endMs = it.toLong().coerceAtLeast(startMs + 1000L)
                                },
                                valueRange = 0f..totalDurationMs.toFloat().coerceAtLeast(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = FridaGoldLight,
                                    activeTrackColor = FridaGoldLight
                                )
                            )
                        }

                        // Preview Audio Button
                        OutlinedButton(
                            onClick = { togglePlay() },
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, FridaGold),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = FridaGoldLight)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isPlaying) "إيقاف المعاينة" else "استماع للمقطع المقصوص")
                        }
                    }
                }

                // Submit button
                FridaGoldButton(
                    text = "قص ورفع المقطع وحفظه",
                    icon = Icons.Default.CloudUpload,
                    onClick = {
                        mediaPlayer?.stop()
                        onUpload(title, artist, startMs, endMs, totalDurationMs)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun ReviewItemCard(
    review: Review,
    onApprove: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd", Locale("ar")) }
    val formattedDate = remember(review.createdAt) {
        review.createdAt?.toDate()?.let { dateFormat.format(it) } ?: ""
    }

    LuxuryCard(
        isHighlighted = !review.approved
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = review.userName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = FridaGoldLight
                )
                if (formattedDate.isNotBlank()) {
                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = FridaTextTertiary
                    )
                }
            }

            // Status badge
            if (review.approved) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = FridaGreenContainer,
                    border = BorderStroke(1.dp, FridaGreen)
                ) {
                    Text(
                        text = "معتمد ومنشور",
                        color = FridaGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = FridaAmberContainer,
                    border = BorderStroke(1.dp, FridaAmber)
                ) {
                    Text(
                        text = "بانتظار الاعتماد",
                        color = FridaAmber,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Rating Stars
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 1..5) {
                Icon(
                    imageVector = if (i <= review.rating) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = null,
                    tint = if (i <= review.rating) FridaGold else FridaTextTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Comment
        Text(
            text = "\"${review.comment}\"",
            style = MaterialTheme.typography.bodyMedium,
            color = FridaTextPrimary
        )

        if (review.invitationSlug.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "مرتبط بالدعوة: /i/${review.invitationSlug}",
                style = MaterialTheme.typography.labelSmall,
                color = FridaTextTertiary
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!review.approved) {
                FridaGoldButton(
                    text = "اعتماد ونشر التقييم",
                    icon = Icons.Default.Check,
                    onClick = onApprove,
                    modifier = Modifier.weight(1f)
                )
            }

            FridaOutlinedButton(
                text = "حذف التقييم",
                icon = Icons.Default.Delete,
                borderColor = FridaRed,
                textColor = FridaRed,
                onClick = onDelete,
                modifier = if (!review.approved) Modifier.weight(1f) else Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun AudioTrackItemCard(
    track: AudioTrack,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onDelete: () -> Unit
) {
    LuxuryCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                // Play/Pause button
                IconButton(
                    onClick = onTogglePlay,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (isPlaying) FridaGold else FridaGoldContainer,
                        contentColor = if (isPlaying) FridaBlack else FridaGold
                    ),
                    modifier = Modifier
                        .size(44.dp)
                        .border(1.dp, FridaGold, RoundedCornerShape(10.dp))
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "إيقاف مؤقت" else "تشغيل المعاينة",
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = FridaTextPrimary
                    )
                    Text(
                        text = "${track.artist} • ${track.duration}",
                        style = MaterialTheme.typography.bodySmall,
                        color = FridaTextSecondary
                    )
                    Text(
                        text = "R2 Key: ${track.key}",
                        style = MaterialTheme.typography.labelSmall,
                        color = FridaTextTertiary
                    )
                }
            }

            IconButton(
                onClick = onDelete,
                colors = IconButtonDefaults.iconButtonColors(contentColor = FridaRed)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteForever,
                    contentDescription = "حذف من R2",
                    tint = FridaRed
                )
            }
        }
    }
}
