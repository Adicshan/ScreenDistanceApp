package com.example.screendistance

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    private lateinit var distanceText: TextView
    private lateinit var statusText: TextView
    private lateinit var calibrate: Button
    private var referenceFaceWidthPx = 0f
    private val thresholdCm = 35f
    private var lastAlert = 0L

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else statusText.text = "Camera permission is required"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        distanceText = findViewById(R.id.distance)
        statusText = findViewById(R.id.status)
        calibrate = findViewById(R.id.calibrate)

        calibrate.setOnClickListener {
            if (latestFaceWidthPx > 0f) {
                referenceFaceWidthPx = latestFaceWidthPx
                getPreferences(MODE_PRIVATE).edit().putFloat("reference_width", referenceFaceWidthPx).apply()
                statusText.text = "Calibrated ✓"
            } else statusText.text = "Face not detected — look at the camera"
        }
        referenceFaceWidthPx = getPreferences(MODE_PRIVATE).getFloat("reference_width", 0f)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private var latestFaceWidthPx = 0f

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val previewUseCase = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val options = FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()
            val detector = FaceDetection.getClient(options)
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { proxy ->
                val media = proxy.image
                if (media == null) { proxy.close(); return@setAnalyzer }
                val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                detector.process(image).addOnSuccessListener { faces ->
                    val face = faces.maxByOrNull { it.boundingBox.width() }
                    if (face != null) {
                        latestFaceWidthPx = face.boundingBox.width().toFloat()
                        if (referenceFaceWidthPx > 0f) {
                            val distance = thresholdCm * referenceFaceWidthPx / latestFaceWidthPx
                            updateDistance(distance)
                        } else {
                            distanceText.text = "-- cm"
                            statusText.text = "Tap calibration at 35 cm"
                        }
                    } else {
                        distanceText.text = "-- cm"
                        statusText.text = "Face not detected"
                    }
                }.addOnCompleteListener { proxy.close() }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, previewUseCase, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateDistance(cm: Float) {
        val rounded = cm.roundToInt().coerceIn(5, 300)
        distanceText.text = "$rounded cm"
        if (cm < thresholdCm) {
            statusText.text = "⚠ MOVE PHONE AWAY"
            statusText.setTextColor(ContextCompat.getColor(this, R.color.red))
            if (SystemClock.elapsedRealtime() - lastAlert > 3000) {
                vibrate()
                lastAlert = SystemClock.elapsedRealtime()
            }
        } else {
            statusText.text = "✓ GOOD DISTANCE"
            statusText.setTextColor(ContextCompat.getColor(this, R.color.green))
        }
    }

    private fun vibrate() {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else @Suppress("DEPRECATION") { getSystemService(VIBRATOR_SERVICE) as Vibrator }
        vibrator.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
