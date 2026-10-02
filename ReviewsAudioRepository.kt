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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.FileInputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

class ReviewsAudioRepository {
    private val firestore get() = FirebaseProvider.firestore
    private val auth get() = FirebaseProvider.auth

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "ReviewsAudioRepository"

        // Raw bytes limit for inline (Base64) storage. Base64 adds ~33%, and the doc is written to
        // two collections, each of which must stay under Firestore's 1 MiB document limit.
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
     * Deletes an audio track from Firebase Storage and/or Cloudflare R2 Worker,
     * then deletes from Firestore collections (music_library and audio_tracks).
     */
    suspend fun deleteAudioTrack(key: String, workerBaseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val user = auth.currentUser ?: throw IllegalStateException("يجب تسجيل الدخول كمدير أولاً")

        // 1. Delete from Firebase Storage if present
        try {
            FirebaseProvider.storage.reference.child("audio/$key").delete().await()
            Log.d(TAG, "Deleted audio track from Firebase Storage: $key")
        } catch (e: Exception) {
            Log.w(TAG, "Firebase storage audio deletion note: ${e.message}")
        }

        // 2. Delete from Cloudflare Worker if configured
        try {
            val tokenResult = user.getIdToken(false).await()
            val idToken = tokenResult.token
            if (!idToken.isNullOrBlank() && workerBaseUrl.isNotBlank()) {
                val sanitizedBaseUrl = WorkerUrlValidator.validateAndSanitize(workerBaseUrl)
                val encodedKey = Uri.encode(key.trim().trimStart('/'), "/")
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
     * Trims audio safely via AudioTrimmer, uploads to Cloud Storage (Firebase Storage with Cloudflare Worker fallback),
     * and saves track metadata to Firestore collections (music_library & audio_tracks).
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

        // 1. Perform safe audio trimming
        onProgress("معالجة وقص المقطع الصوتي...", 20, 0.20f, "جاري تحضير ملف الصوت والمعالجة...")
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

            onProgress("تجهيز المقطع للرفع السحابي...", 35, 0.35f, "حجم المقطع: ${formatBytes(totalSize)}")

            var publicUrl: String? = null
            var lastError: Exception? = null

            // Strategy 1: Cloudflare R2 Storage via Cloudflare Worker (Primary)
            if (workerBaseUrl.isNotBlank()) {
                try {
                    onProgress("الرفع إلى مساحة التخزين Cloudflare R2...", 40, 0.40f, "الاتصال بـ Cloudflare Worker...")
                    val sanitizedBaseUrl = WorkerUrlValidator.validateAndSanitize(workerBaseUrl)
                    val tokenResult = user.getIdToken(false).await()
                    val idToken = tokenResult.token ?: throw IllegalStateException("تعذر استخراج توكن المصادقة الإدارية")

                    val encodedKey = Uri.encode(key, "/")
                    val uploadUrl = "$sanitizedBaseUrl/api/audio/$encodedKey"
                    val mediaType = trimmed.mimeType.toMediaTypeOrNull()

                    val progressBody = object : RequestBody() {
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
                                    val overallPercent = 40 + (uploadFraction * 50).toInt()
                                    val detail = "${formatBytes(bytesWritten)} من ${formatBytes(totalSize)}"
                                    onProgress("الرفع إلى Cloudflare R2...", overallPercent, 0.40f + (uploadFraction * 0.50f), detail)
                                }
                            }
                        }
                    }

                    val request = Request.Builder()
                        .url(uploadUrl)
                        .put(progressBody)
                        .addHeader("Authorization", "Bearer $idToken")
                        .build()

                    val uploadSucceeded = httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            Log.i(TAG, "Audio successfully uploaded to Cloudflare R2 via Worker: ${response.code}")
                            true
                        } else {
                            val errBody = response.body?.string() ?: ""
                            Log.w(TAG, "Worker upload returned HTTP ${response.code}: $errBody")
                            false
                        }
                    }

                    if (uploadSucceeded) {
                        publicUrl = "$sanitizedBaseUrl/audio/$key"
                    }
                } catch (workerError: Exception) {
                    Log.w(TAG, "Cloudflare Worker R2 attempt note: ${workerError.message}, falling back to Firebase Storage...", workerError)
                }
            }

            // Strategy 2: Firebase Cloud Storage (Reliable secondary storage)
            if (publicUrl == null) {
                try {
                    onProgress("الرفع إلى مساحة التخزين السحابية...", 40, 0.40f, "جاري الرفع إلى Firebase Storage...")
                    val storageRef = FirebaseProvider.storage.reference.child("audio/$key")
                    val metadata = com.google.firebase.storage.StorageMetadata.Builder()
                        .setContentType(trimmed.mimeType)
                        .build()

                    val uploadTask = storageRef.putFile(Uri.fromFile(tempFile), metadata)
                    uploadTask.addOnProgressListener { snapshot ->
                        val transferred = snapshot.bytesTransferred
                        val total = snapshot.totalByteCount
                        if (total > 0) {
                            val uploadFraction = (transferred.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            val overallPercent = 40 + (uploadFraction * 50).toInt()
                            val detail = "${formatBytes(transferred)} من ${formatBytes(total)}"
                            onProgress("جاري رفع المقطع الصوتي...", overallPercent, 0.40f + (uploadFraction * 0.50f), detail)
                        }
                    }

                    uploadTask.await()
                    val downloadUri = storageRef.downloadUrl.await()
                    publicUrl = downloadUri.toString()
                    Log.i(TAG, "Audio uploaded to Firebase Storage successfully: $publicUrl")
                } catch (fbError: Exception) {
                    Log.w(TAG, "Firebase Storage upload error: ${fbError.message}", fbError)
                    lastError = fbError
                }
            }

            // Strategy 3: last-resort inline storage in Firestore (small clips only).
            // The audio is NEVER truncated: a cut-off audio file is corrupt yet would look like a
            // successful upload. If the clip does not fit, fail with a clear, actionable message.
            if (publicUrl == null) {
                onProgress("حفظ المقطع في قاعدة بيانات فريدا...", 75, 0.75f, "جاري الحفظ الآمن في قاعدة البيانات...")
                if (totalSize > MAX_INLINE_AUDIO_BYTES) {
                    val reason = lastError?.localizedMessage ?: "خدمات التخزين غير متاحة حالياً"
                    throw IllegalStateException(
                        "تعذر رفع المقطع إلى التخزين السحابي ($reason)، وحجمه ${formatBytes(totalSize)} " +
                            "أكبر من الحد المسموح للحفظ المباشر (${formatBytes(MAX_INLINE_AUDIO_BYTES.toLong())}). " +
                            "قصّ مقطعاً أقصر أو أعد المحاولة عند توفر الاتصال."
                    )
                }
                try {
                    val base64Data = android.util.Base64.encodeToString(tempFile.readBytes(), android.util.Base64.NO_WRAP)
                    publicUrl = "data:${trimmed.mimeType};base64,$base64Data"
                    Log.i(TAG, "Stored audio inline in Firestore as Data URI: $totalSize bytes")
                } catch (dataErr: Exception) {
                    Log.e(TAG, "Direct storage conversion failed", dataErr)
                    val combinedMsg = lastError?.localizedMessage ?: dataErr.localizedMessage ?: "تعذر إكمال الرفع"
                    throw IllegalStateException("فشل رفع المقطع الصوتي إلى سيرفرات التخزين: $combinedMsg")
                }
            }

            val finalPublicUrl = publicUrl ?: throw IllegalStateException("لم يتم استلام رابط الملف السحابي")

            // Save to Firestore music_library and audio_tracks collections safely
            onProgress("تسجيل المقطع في قاعدة بيانات فريدا...", 95, 0.95f, "جاري إدراج المستند في Firestore...")
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

            // Both collections are written atomically. A failure here must surface as an error:
            // swallowing it would report success for a track that is not registered anywhere.
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

            onProgress("اكتمل بنجاح!", 100, 1.0f, "تم قص ورفع وحفظ المقطع الموسيقي بنجاح")

            AudioTrack(
                key = key,
                title = title.ifBlank { "مقطع موسيقي جديد" },
                artist = artist.ifBlank { "فريدا" },
                duration = calculatedDuration,
                url = finalPublicUrl,
                previewUrl = finalPublicUrl,
                createdAt = com.google.firebase.Timestamp.now()
            )
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
                Log.d(TAG, "Deleted temp audio file: ${tempFile.name}")
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) String.format(Locale.US, "%.1f MB", mb) else String.format(Locale.US, "%.0f KB", kb)
    }
}
