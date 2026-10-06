package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.data.FirebaseProvider
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FridaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseProvider.initialize(this)
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().isAutoInitEnabled = false
        } catch (e: Exception) {}
        initializeAppCheck()
        createNotificationChannels()

        try {
            val currentUser = FirebaseProvider.auth.currentUser
            if (currentUser != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        com.example.data.repository.FcmRepository().registerCurrentToken()
                    } catch (e: Exception) {
                        Log.w("FridaApplication", "Background FCM token registration non-fatal: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("FridaApplication", "FCM init non-fatal: ${e.message}")
        }
    }

    private fun initializeAppCheck() {
        try {
            val appCheck = FirebaseAppCheck.getInstance()
            if (BuildConfig.DEBUG) {
                try {
                    val debugFactoryClass = Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory")
                    val getInstanceMethod = debugFactoryClass.getMethod("getInstance")
                    val factory = getInstanceMethod.invoke(null) as AppCheckProviderFactory
                    appCheck.installAppCheckProviderFactory(factory)
                    Log.d("FridaApplication", "App Check initialized with DebugAppCheckProviderFactory")
                } catch (e: Exception) {
                    Log.w("FridaApplication", "Debug App Check factory init: ${e.message}")
                }
            } else {
                try {
                    val playIntegrityClass = Class.forName("com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory")
                    val getInstanceMethod = playIntegrityClass.getMethod("getInstance")
                    val factory = getInstanceMethod.invoke(null) as AppCheckProviderFactory
                    appCheck.installAppCheckProviderFactory(factory)
                    Log.d("FridaApplication", "App Check initialized with PlayIntegrityAppCheckProviderFactory")
                } catch (e: Exception) {
                    Log.w("FridaApplication", "Play Integrity App Check factory init: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w("FridaApplication", "App Check initialization non-fatal: ${e.message}")
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "frida_admin_notifications"
            val channelName = "طلبات وإشعارات FRIDA الإدارية"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(channelId, channelName, importance).apply {
                description = "إشعارات الطلبات والدعوات الجديدة لمدير منصة فريدا"
                enableVibration(true)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
