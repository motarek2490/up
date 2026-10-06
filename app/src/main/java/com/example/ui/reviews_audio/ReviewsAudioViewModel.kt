package com.example.ui.reviews_audio

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AudioTrack
import com.example.data.model.Review
import com.example.data.repository.ReviewsAudioRepository
import com.example.data.repository.SettingsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class AudioProgressState(
    val isProcessing: Boolean = false,
    val stage: String = "",
    val percentage: Int = 0,
    val progressFraction: Float = 0f,
    val detail: String = ""
)

sealed class ReviewAudioActionState {
    data object Idle : ReviewAudioActionState()
    data class Loading(val message: String, val percentage: Int = 0) : ReviewAudioActionState()
    data class Success(val message: String) : ReviewAudioActionState()
    data class Error(val message: String) : ReviewAudioActionState()
}

class ReviewsAudioViewModel(
    private val repository: ReviewsAudioRepository = ReviewsAudioRepository(),
    private val settingsRepository: SettingsRepository = SettingsRepository()
) : ViewModel() {

    val reviews: StateFlow<List<Review>> = repository.getReviewsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val audioTracks: StateFlow<List<AudioTrack>> = repository.getAudioTracksFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val adminConfigState = settingsRepository.getAdminConfigFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.example.data.repository.SettingsDataState.Loading)

    private fun getWorkerUrl(): String {
        val current = (adminConfigState.value as? com.example.data.repository.SettingsDataState.Success)?.data
        return current?.cloudflareWorkerUrl?.takeIf { it.isNotBlank() } ?: com.example.util.AppConfig.defaultWorkerUrl
    }

    private val _actionState = MutableStateFlow<ReviewAudioActionState>(ReviewAudioActionState.Idle)
    val actionState: StateFlow<ReviewAudioActionState> = _actionState.asStateFlow()

    private val _audioProgress = MutableStateFlow(AudioProgressState())
    val audioProgress: StateFlow<AudioProgressState> = _audioProgress.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    fun trimAndUploadAudioTrack(
        context: Context,
        audioUri: Uri,
        title: String,
        artist: String,
        startMs: Long,
        endMs: Long,
        totalDurationMs: Long
    ) {
        viewModelScope.launch {
            _audioProgress.value = AudioProgressState(
                isProcessing = true,
                stage = "بدء معالجة المقطع الصوتي...",
                percentage = 5,
                progressFraction = 0.05f,
                detail = "تجهيز معالج الصوت..."
            )
            try {
                val workerUrl = getWorkerUrl()
                repository.trimAndUploadAudioTrack(
                    context = context,
                    audioUri = audioUri,
                    title = title,
                    artist = artist,
                    startMs = startMs,
                    endMs = endMs,
                    totalDurationMs = totalDurationMs,
                    workerBaseUrl = workerUrl,
                    onProgress = { stage, percent, fraction, detail ->
                        _audioProgress.value = AudioProgressState(
                            isProcessing = true,
                            stage = stage,
                            percentage = percent,
                            progressFraction = fraction,
                            detail = detail
                        )
                    }
                )
                kotlinx.coroutines.delay(600)
                _audioProgress.value = AudioProgressState(isProcessing = false)
                _actionState.value = ReviewAudioActionState.Success("تم قص ورفع وحفظ المقطع الموسيقي بنجاح!")
                _snackbarMessage.emit("تم رفع المقطع الصوتي وحفظه بنجاح")
            } catch (e: Exception) {
                Log.e("ReviewsAudioViewModel", "Error uploading/trimming audio", e)
                _audioProgress.value = AudioProgressState(isProcessing = false)
                _actionState.value = ReviewAudioActionState.Error("فشل رفع المقطع الصوتي: ${e.localizedMessage}")
                _snackbarMessage.emit("خطأ أثناء الرفع: ${e.localizedMessage}")
            }
        }
    }

    fun approveReview(reviewId: String) {
        viewModelScope.launch {
            _actionState.value = ReviewAudioActionState.Loading("جاري اعتماد التقييم...")
            try {
                repository.approveReview(reviewId)
                _actionState.value = ReviewAudioActionState.Success("تم اعتماد التقييم ونشره في الموقع")
                _snackbarMessage.emit("تم اعتماد التقييم بنجاح")
            } catch (e: Exception) {
                Log.e("ReviewsAudioViewModel", "Error approving review", e)
                _actionState.value = ReviewAudioActionState.Error("فشل اعتماد التقييم: ${e.localizedMessage}")
                _snackbarMessage.emit("خطأ أثناء الاعتماد: ${e.localizedMessage}")
            }
        }
    }

    fun deleteReview(reviewId: String) {
        viewModelScope.launch {
            _actionState.value = ReviewAudioActionState.Loading("جاري حذف التقييم...")
            try {
                repository.deleteReview(reviewId)
                _actionState.value = ReviewAudioActionState.Success("تم حذف التقييم")
                _snackbarMessage.emit("تم حذف التقييم بنجاح")
            } catch (e: Exception) {
                Log.e("ReviewsAudioViewModel", "Error deleting review", e)
                _actionState.value = ReviewAudioActionState.Error("فشل حذف التقييم: ${e.localizedMessage}")
                _snackbarMessage.emit("خطأ أثناء الحذف: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Deletes audio file by invoking Cloudflare R2 worker endpoint `DELETE /api/audio/{key}`.
     */
    fun deleteAudioTrack(key: String) {
        viewModelScope.launch {
            _actionState.value = ReviewAudioActionState.Loading("جاري حذف المقطع الصوتي من Cloudflare R2 عبر الـ Worker...")
            try {
                val workerUrl = getWorkerUrl()
                repository.deleteAudioTrack(key, workerUrl)
                _actionState.value = ReviewAudioActionState.Success("تم حذف المقطع الصوتي نهائياً من سيرفر التخزين R2")
                _snackbarMessage.emit("تم حذف المقطع الصوتي بنجاح")
            } catch (e: Exception) {
                Log.e("ReviewsAudioViewModel", "Error deleting audio track", e)
                _actionState.value = ReviewAudioActionState.Error("فشل حذف الملف الصوتي من R2: ${e.localizedMessage}")
                _snackbarMessage.emit("فشل الحذف من R2: ${e.localizedMessage}")
            }
        }
    }

    fun clearActionState() {
        _actionState.value = ReviewAudioActionState.Idle
    }
}
