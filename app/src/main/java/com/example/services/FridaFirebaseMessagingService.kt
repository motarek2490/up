package com.example.services

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.FirebaseProvider
import com.example.data.repository.FcmRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FridaFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FridaFCM"
        const val CHANNEL_ID = "frida_admin_notifications"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Refreshed FCM Token: $token")
        val user = FirebaseProvider.auth.currentUser
        if (user != null) {
            CoroutineScope(Dispatchers.IO).launch {
                FcmRepository().saveToken(user.uid, token)
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "From: ${remoteMessage.from}")

        val data = remoteMessage.data
        val orderId = data["orderId"]
        val invitationId = data["invitationId"]

        val title = remoteMessage.notification?.title ?: data["title"] ?: "طلب جديد في فريدا"
        val body = remoteMessage.notification?.body ?: data["body"] ?: "يوجد طلب جديد بانتظار المراجعة والاعتماد"

        sendNotification(title, body, orderId, invitationId)
    }

    private fun sendNotification(
        title: String,
        body: String,
        orderId: String?,
        invitationId: String?
    ) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (!orderId.isNullOrBlank()) {
                putExtra("orderId", orderId)
                putExtra("EXTRA_ORDER_ID", orderId)
            }
            if (!invitationId.isNullOrBlank()) {
                putExtra("invitationId", invitationId)
                putExtra("EXTRA_INVITATION_ID", invitationId)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
    }
}
