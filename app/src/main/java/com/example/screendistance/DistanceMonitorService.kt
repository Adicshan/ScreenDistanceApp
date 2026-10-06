package com.example.screendistance

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions

class DistanceMonitorService : LifecycleService() {
    companion object {
        const val ACTION_START = "com.example.screendistance.START_MONITOR"
        const val ACTION_STOP = "com.example.screendistance.STOP_MONITOR"
        const val CHANNEL_ID = "screen_sense_monitor"
        const val NOTIFICATION_ID = 35
        const val ALERT_THRESHOLD_CM = 30f
        const val CALIBRATION_DISTANCE_CM = 35f
        const val ALERT_COOLDOWN_MS = 5000L
        const val TOO_CLOSE_CONFIRM_MS = 1500L
    }

    private var referenceFaceWidthPx = 0f
    private var lastAlert = 0L
    private var tooCloseSince = 0L
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: FaceDetector? = null

    override fun onCreate() {
        super.onCreate()
        referenceFaceWidthPx = getSharedPreferences("screen_sense", MODE_PRIVATE).getFloat("reference_width", 0f)
        createNotificationChannel()
        startAsForeground()
        if (referenceFaceWidthPx > 0f) startMonitoring() else stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    private fun startAsForeground() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("ScreenSense — Monitoring active")
            .setContentText("Distance alert below 30 cm")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Screen distance monitoring", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startMonitoring() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build())
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { proxy ->
                    val media = proxy.image
                    if (media == null) { proxy.close(); return@setAnalyzer }
                    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    detector!!.process(image).addOnSuccessListener { faces ->
                        val face = faces.maxByOrNull { it.boundingBox.width() }
                        if (face != null && referenceFaceWidthPx > 0f) {
                            val width = face.boundingBox.width().toFloat()
                            val distance = CALIBRATION_DISTANCE_CM * referenceFaceWidthPx / width
                            val now = SystemClock.elapsedRealtime()
                            if (distance < ALERT_THRESHOLD_CM) {
                                if (tooCloseSince == 0L) tooCloseSince = now
                                if (now - tooCloseSince >= TOO_CLOSE_CONFIRM_MS && now - lastAlert >= ALERT_COOLDOWN_MS) {
                                    vibrate()
                                    lastAlert = now
                                }
                            } else tooCloseSince = 0L
                        } else tooCloseSince = 0L
                    }.addOnCompleteListener { proxy.close() }
                }
                // Activity releases its camera before starting this service.
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        detector?.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)
}
