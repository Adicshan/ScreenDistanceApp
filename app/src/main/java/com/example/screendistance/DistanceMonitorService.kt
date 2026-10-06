package com.example.screendistance

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.math.roundToInt

class DistanceMonitorService : LifecycleService() {
    companion object {
        const val ACTION_START = "com.example.screendistance.START_MONITOR"
        const val ACTION_STOP = "com.example.screendistance.STOP_MONITOR"
        const val CHANNEL_ID = "screen_sense_monitor"
        const val NOTIFICATION_ID = 35
        const val ALERT_THRESHOLD_CM = 30f
        const val CALIBRATION_DISTANCE_CM = 35f
        const val ALERT_COOLDOWN_MS = 5000L
    }

    private var referenceFaceWidthPx = 0f
    private var lastAlert = 0L
    private var lastDistance = 0f
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: com.google.mlkit.vision.face.FaceDetector? = null

    override fun onCreate() {
        super.onCreate()
        referenceFaceWidthPx = getSharedPreferences("screen_sense", MODE_PRIVATE)
            .getFloat("reference_width", 0f)
        createNotificationChannel()
        startAsForeground()
        startMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return Service.START_STICKY
    }

    private fun startAsForeground() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("ScreenSense is active")
            .setContentText("Checking your screen distance")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen distance monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps ScreenSense monitoring your viewing distance" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startMonitoring() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val options = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .build()
                detector = FaceDetection.getClient(options)

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { proxy ->
                    val media = proxy.image
                    if (media == null) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    detector?.process(image)
                        ?.addOnSuccessListener { faces ->
                            val face = faces.maxByOrNull { it.boundingBox.width() }
                            if (face != null && referenceFaceWidthPx > 0f) {
                                val width = face.boundingBox.width().toFloat()
                                if (width > 0f) {
                                    val distance = CALIBRATION_DISTANCE_CM * referenceFaceWidthPx / width
                                    lastDistance = distance
                                    if (distance < ALERT_THRESHOLD_CM &&
                                        SystemClock.elapsedRealtime() - lastAlert >= ALERT_COOLDOWN_MS) {
                                        vibrate()
                                        lastAlert = SystemClock.elapsedRealtime()
                                    }
                                }
                            }
                        }
                        ?.addOnCompleteListener { proxy.close() }
                }

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (_: Exception) {
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(
            VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        detector?.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)
}
