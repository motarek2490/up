package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.data.FirebaseProvider
import com.example.data.model.AudioTrack
import com.example.data.model.Review
import com.example.util.AudioTrimmer
import com.example.util.WorkerUrlValidator
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

class ReviewsAudioRepository {
    private val firestore get() = FirebaseProvider.firestore
    private val auth get() = FirebaseProvider.auth
    private val storage get() = FirebaseProvider.storage
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "ReviewsAudioRepository"

        // Raw bytes limit for inline (Base64) storage in Firestore (Firestore doc limit is 1 MiB)
        private const val MAX_INLINE_AUDIO_BYTES = 600 * 1024
    }

    fun getReviewsFlow(): Flow<List<Review>> = callbackFlow {
        if (auth.currentUser == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        val query = firestore.collection("reviews").limit(100)

        val registration = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                if (error.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    Log.e(TAG, "Error fetching reviews", error)
                }
                trySend(emptyList())
                return@addSnapshotListener
            }

            if (snapshot != null) {
                val reviews = snapshot.documents.mapNotNull { doc ->
                    try {
                        Review.fromDoc(doc)
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping unreadable review ${doc.id}: ${e.message}")
                        null
                    }
                }.sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
                trySend(reviews)
            }
        }

        awaitClose { registration.remove() }
    }.flowOn(Dispatchers.IO)

    suspend fun approveReview(reviewId: String) = withContext(Dispatchers.IO) {
        firestore.collection("reviews").document(reviewId).update("approved", true).await()
    }

    suspend fun deleteReview(reviewId: String) = withContext(Dispatchers.IO) {
        firestore.collection("reviews").document(reviewId).delete().await()
    }

    fun getAudioTracksFlow(): Flow<List<AudioTrack>> = callbackFlow {
        if (auth.currentUser == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        // Query music_library first
        val musicLibQuery = firestore.collection("music_library").limit(100)
        val audioTracksQuery = firestore.collection("audio_tracks").limit(100)

        val listenerRegistration = musicLibQuery.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "Error fetching music_library: ${error.message}")
            }
            val tracksFromMusicLib = snapshot?.documents?.mapNotNull { doc -> AudioTrack.fromDoc(doc) } ?: emptyList()

            if (tracksFromMusicLib.isNotEmpty()) {
                trySend(tracksFromMusicLib.sortedByDescending { it.createdAt?.toDate()?.time ?: 0L })
            } else {
                // Fallback / secondary fetch from audio_tracks
                audioTracksQuery.get().addOnSuccessListener { secondarySnapshot ->
                    val secondaryTracks = secondarySnapshot.documents.mapNotNull { doc -> AudioTrack.fromDoc(doc) }
                    trySend(secondaryTracks.sortedByDescending { it.createdAt?.toDate()?.time ?: 0L })
                }.addOnFailureListener {
                    trySend(emptyList())
                }
            }
        }

        awaitClose { listenerRegistration.remove() }
    }.flowOn(Dispatchers.IO)

    /**
     * Deletes an audio track from Cloudflare R2 Worker and Firebase Storage,
     * then deletes from Firestore collections (music_library and audio_tracks).
     */
    suspend fun deleteAudioTrack(key: String, workerBaseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val user = auth.currentUser ?: throw IllegalStateException("يجب تسجيل الدخول كمدير أولاً")

        // 1. Delete from Cloudflare Worker R2 if configured
        try {
            val tokenResult = user.getIdToken(true).await()
            val idToken = tokenResult.token
            if (!idToken.isNullOrBlank() && workerBaseUrl.isNotBlank()) {
                val sanitizedBaseUrl = WorkerUrlValidator.validateAndSanitize(workerBaseUrl)
                val normalizedKey = when {
                    key.contains("/audio/") -> key.substring(key.indexOf("/audio/") + 1)
                    key.startsWith("/") -> key.substring(1)
                    else -> key
                }.let { if (!it.startsWith("audio/")) "audio/$it" else it }
                val encodedKey = Uri.encode(normalizedKey)
                val url = "$sanitizedBaseUrl/api/audio/$encodedKey"

                val request = Request.Builder()
                    .url(url)
                    .delete()
                    .addHeader("Authorization", "Bearer $idToken")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    Log.d(TAG, "Worker delete audio response code: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Worker audio delete note: ${e.message}")
        }

        // 2. Delete from Firebase Storage if present
        try {
            val storageRef = storage.reference.child("audio/$key")
            storageRef.delete().await()
            Log.d(TAG, "Deleted audio from Firebase Storage: $key")
        } catch (ignored: Exception) {}

        // 3. Delete from Firestore collections
        val docId = key.replace("/", "_")
        try {
            firestore.collection("music_library").document(docId).delete().await()
            firestore.collection("audio_tracks").document(docId).delete().await()
            Log.d(TAG, "Firestore audio track doc deleted: $docId")
        } catch (e: Exception) {
            Log.w(TAG, "Firestore audio track doc delete skipped: $docId", e)
        }
        true
    }

    /**
     * Trims audio safely via AudioTrimmer with high-quality AAC compression,
     * uploads to Cloud Storage (Cloudflare R2 Worker -> Firebase Storage -> Firestore fallback),
     * and saves track metadata to Firestore collections (music_library & audio_tracks atomically via batch).
     */
    suspend fun trimAndUploadAudioTrack(
        context: Context,
        audioUri: Uri,
        title: String,
        artist: String,
        startMs: Long,
        endMs: Long,
        totalDurationMs: Long,
        workerBaseUrl: String,
        onProgress: (stage: String, percent: Int, progressFraction: Float, detail: String) -> Unit
    ): AudioTrack = withContext(Dispatchers.IO) {
        val user = auth.currentUser ?: throw IllegalStateException("يجب تسجيل الدخول بحساب المدير أولاً")

        // 1. Perform safe audio trimming and compression (High Quality AAC / M4A)
        onProgress("ضغط ومعالجة المقطع الصوتي بجودة عالية...", 15, 0.15f, "جاري تحويل الصوت إلى صيغة AAC عالية النقاء...")
        val trimmed = AudioTrimmer.trimAudio(
            context = context,
            audioUri = audioUri,
            startMs = startMs,
            endMs = endMs,
            totalDurationMs = totalDurationMs
        )

        val tempFile = trimmed.file
        try {
            val totalSize = tempFile.length()
            if (totalSize <= 0) {
                throw IllegalStateException("ملف الصوت المعالج فارغ")
            }

            val min = trimmed.durationSeconds / 60
            val sec = trimmed.durationSeconds % 60
            val calculatedDuration = String.format(Locale.US, "%02d:%02d", min, sec)

            val cleanTitle = title.trim().replace(Regex("[^a-zA-Z0-9_\\u0600-\\u06FF-]"), "_").take(25)
            val key = "audio_${System.currentTimeMillis()}_$cleanTitle.${trimmed.extension}"

            onProgress("تجهيز المقطع للرفع السحابي...", 35, 0.35f, "حجم المقطع بعد الضغط: ${formatBytes(totalSize)}")

            var publicUrl: String? = null
            // Every failed strategy is recorded WITH its real reason (HTTP status + body, or exception),
            // so the final error tells the admin exactly what was rejected and by whom.
            val failures = mutableListOf<String>()

            // Strategy 1: Cloudflare R2 Storage via Cloudflare Worker (Primary)
            if (workerBaseUrl.isNotBlank()) {
                try {
                    onProgress("الرفع إلى مساحة التخزين Cloudflare R2...", 40, 0.40f, "الاتصال بـ Cloudflare Worker...")
                    val sanitizedBaseUrl = WorkerUrlValidator.validateAndSanitize(workerBaseUrl)
                    val tokenResult = user.getIdToken(true).await()
                    val idToken = tokenResult.token ?: throw IllegalStateException("تعذر استخراج توكن المصادقة الإدارية")

                    val uploadUrl = "$sanitizedBaseUrl/api/audio/upload"
                    val mediaType = (trimmed.mimeType ?: "audio/mp4").toMediaTypeOrNull()

                    val progressFileBody = object : RequestBody() {
                        override fun contentType() = mediaType
                        override fun contentLength() = totalSize
                        override fun writeTo(sink: BufferedSink) {
                            val buffer = ByteArray(16384)
                            val fis = FileInputStream(tempFile)
                            fis.use { input ->
                                var bytesWritten = 0L
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    sink.write(buffer, 0, read)
                                    bytesWritten += read
                                    val uploadFraction = (bytesWritten.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f)
                                    val overallPercent = 40 + (uploadFraction * 35).toInt()
                                    val detail = "${formatBytes(bytesWritten)} من ${formatBytes(totalSize)}"
                                    onProgress("الرفع إلى Cloudflare R2...", overallPercent, 0.40f + (uploadFraction * 0.35f), detail)
                                }
                            }
                        }
                    }

                    // `key` already ends with the extension (it used to be appended twice: x.m4a.m4a).
                    val filename = key
                    val multipartBody = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", filename, progressFileBody)
                        .addFormDataPart("type", "library")
                        .addFormDataPart("userId", user.uid)
                        .addFormDataPart("trackId", key)
                        .addFormDataPart("customId", key)
                        .build()

                    val request = Request.Builder()
                        .url(uploadUrl)
                        .post(multipartBody)
                        .addHeader("Authorization", "Bearer $idToken")
                        .build()

                    val uploadResult = httpClient.newCall(request).execute().use { response ->
                        val respBody = response.body?.string() ?: ""
                        Log.d(TAG, "Worker upload HTTP ${response.code}: $respBody")
                        if (response.isSuccessful) {
                            try {
                                val json = org.json.JSONObject(respBody)
                                val returnedUrl = json.optString("publicUrl").ifBlank { json.optString("url") }
                                if (returnedUrl.startsWith("http")) {
                                    returnedUrl
                                } else if (returnedUrl.isNotBlank()) {
                                    "$sanitizedBaseUrl/${returnedUrl.trimStart('/')}"
                                } else {
                                    val returnedKey = json.optString("key", key)
                                    "$sanitizedBaseUrl/$returnedKey"
                                }
                            } catch (jsonErr: Exception) {
                                "$sanitizedBaseUrl/audio/$key"
                            }
                        } else {
                            Log.w(TAG, "Worker upload returned HTTP ${response.code}: $respBody")
                            failures += "Cloudflare Worker رفض الرفع: HTTP ${response.code} ${respBody.take(300)}"
                            null
                        }
                    }

                    if (!uploadResult.isNullOrBlank()) {
                        publicUrl = uploadResult
                        Log.i(TAG, "Audio successfully uploaded to Cloudflare R2 via Worker: $publicUrl")
                    }
                } catch (workerError: Exception) {
                    Log.w(TAG, "Cloudflare Worker R2 attempt note: ${workerError.message}", workerError)
                    failures += "Cloudflare Worker: ${workerError.javaClass.simpleName}: ${workerError.localizedMessage}"
                }
            } else {
                failures += "رابط الـ Worker غير مضبوط (CLOUDFLARE_WORKER_URL / الإعدادات)"
            }

            // Strategy 2: Firebase Storage Direct Cloud Upload (High-Speed Reliable Fallback)
            if (publicUrl == null) {
                try {
                    onProgress("الرفع إلى مساحة التخزين السحابية Firebase Storage...", 75, 0.75f, "جاري رفع الملف إلى التخزين السحابي...")
                    val storageRef = storage.reference.child("audio/$key")
                    val metadata = StorageMetadata.Builder()
                        .setContentType(trimmed.mimeType)
                        .setCustomMetadata("title", title)
                        .setCustomMetadata("artist", artist)
                        .build()

                    storageRef.putFile(Uri.fromFile(tempFile), metadata).await()
                    val downloadUri = storageRef.downloadUrl.await()
                    publicUrl = downloadUri.toString()
                    Log.i(TAG, "Audio successfully uploaded to Firebase Storage: $publicUrl")
                } catch (storageErr: Exception) {
                    Log.w(TAG, "Firebase Storage upload attempt note: ${storageErr.message}", storageErr)
                    failures += "Firebase Storage: ${storageErr.javaClass.simpleName}: ${storageErr.localizedMessage}"
                }
            }

            // Strategy 3: Self-Healing Direct Firestore Storage (For smaller compressed clips)
            if (publicUrl == null) {
                onProgress("حفظ المقطع في قاعدة بيانات فريدا...", 85, 0.85f, "جاري الحفظ المباشر في قاعدة البيانات...")
                if (totalSize > MAX_INLINE_AUDIO_BYTES) {
                    val reason = failures.joinToString(" | ").ifBlank { "سبب غير معروف" }
                    throw IllegalStateException(
                        "تعذر رفع المقطع (${formatBytes(totalSize)}). الأسباب: $reason"
                    )
                }
                try {
                    val base64Data = android.util.Base64.encodeToString(tempFile.readBytes(), android.util.Base64.NO_WRAP)
                    publicUrl = "data:${trimmed.mimeType};base64,$base64Data"
                    Log.i(TAG, "Stored audio inline in Firestore as Data URI: $totalSize bytes")
                } catch (dataErr: Exception) {
                    Log.e(TAG, "Direct storage conversion failed", dataErr)
                    val combinedMsg = (failures + (dataErr.localizedMessage ?: "تعذر إكمال الرفع")).joinToString(" | ")
                    throw IllegalStateException("فشل رفع المقطع الصوتي إلى سيرفرات التخزين: $combinedMsg")
                }
            }

            val finalPublicUrl = publicUrl ?: throw IllegalStateException("لم يتم استلام رابط الملف السحابي")

            // Save to Firestore music_library and audio_tracks collections atomically via batch
            onProgress("تسجيل المقطع في مكتبة الموسيقى...", 95, 0.95f, "جاري إدراج المستند في Firestore...")
            val firestoreDocId = key.replace("/", "_")
            val musicData = hashMapOf(
                "id" to firestoreDocId,
                "key" to key,
                "title" to title.ifBlank { "مقطع موسيقي جديد" },
                "artist" to artist.ifBlank { "FRIDA Royal Orchestra" },
                "duration" to calculatedDuration,
                "audioUrl" to finalPublicUrl,
                "url" to finalPublicUrl,
                "previewUrl" to finalPublicUrl,
                "category" to "royal",
                "isActive" to true,
                "isDefault" to false,
                "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )

            try {
                firestore.batch()
                    .set(firestore.collection("music_library").document(firestoreDocId), musicData)
                    .set(firestore.collection("audio_tracks").document(firestoreDocId), musicData)
                    .commit()
                    .await()
            } catch (fsError: Exception) {
                Log.e(TAG, "Firestore write failed for audio track", fsError)
                throw IllegalStateException("تم رفع الملف لكن تعذر تسجيله في قاعدة البيانات: ${fsError.localizedMessage}")
            }

            onProgress("اكتمل بنجاح!", 100, 1.0f, "تم قص وضغط ورفع وحفظ المقطع الموسيقي بنجاح")

            AudioTrack(
                key = key,
                title = title.ifBlank { "مقطع موسيقي جديد" },
                artist = artist.ifBlank { "FRIDA Royal Orchestra" },
                duration = calculatedDuration,
                url = finalPublicUrl,
                previewUrl = finalPublicUrl,
                isActive = true,
                isDefault = false
            )
        } finally {
            if (tempFile.exists()) {
                try {
                    tempFile.delete()
                } catch (e: Exception) {
                    Log.w(TAG, "Temp audio file cleanup failed: ${e.message}")
                }
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
            else -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
        }
    }
}
