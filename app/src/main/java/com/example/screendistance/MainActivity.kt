package com.example.screendistance

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    private lateinit var distanceText: TextView
    private lateinit var statusText: TextView
    private lateinit var calibrate: Button
    private lateinit var monitorButton: Button
    private var cameraProvider: ProcessCameraProvider? = null
    private var referenceFaceWidthPx = 0f
    private var latestFaceWidthPx = 0f
    private val calibrationDistanceCm = 35f
    private val alertThresholdCm = 30f
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
        monitorButton = findViewById(R.id.monitor)

        referenceFaceWidthPx = getSharedPreferences("screen_sense", MODE_PRIVATE).getFloat("reference_width", 0f)
        if (referenceFaceWidthPx > 0f) statusText.text = "Calibration saved ✓"

        calibrate.setOnClickListener {
            if (latestFaceWidthPx > 0f) {
                referenceFaceWidthPx = latestFaceWidthPx
                getSharedPreferences("screen_sense", MODE_PRIVATE).edit()
                    .putFloat("reference_width", referenceFaceWidthPx).apply()
                statusText.text = "Calibrated at 35 cm ✓"
                distanceText.text = "35 cm"
            } else {
                statusText.text = "Face not detected — look at the camera"
            }
        }

        monitorButton.setOnClickListener { startMonitoringService() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startMonitoringService() {
        if (referenceFaceWidthPx <= 0f) {
            statusText.text = "Calibrate at 35 cm first"
            return
        }
        // Release the activity camera BEFORE starting the service. Otherwise the two
        // camera clients can race for the front camera and the service may fail silently.
        cameraProvider?.unbindAll()
        cameraProvider = null
        statusText.text = "Starting background monitor…"
        monitorButton.isEnabled = false
        val intent = Intent(this, DistanceMonitorService::class.java).setAction(DistanceMonitorService.ACTION_START)
        try {
            ContextCompat.startForegroundService(this, intent)
            Handler(Looper.getMainLooper()).postDelayed({
                statusText.text = "Monitoring ON • alert below 30 cm"
                monitorButton.isEnabled = true
            }, 1200)
        } catch (e: Exception) {
            monitorButton.isEnabled = true
            statusText.text = "Could not start monitor: ${e.javaClass.simpleName}"
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val previewUseCase = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                val options = com.google.mlkit.vision.face.FaceDetectorOptions.Builder()
                    .setPerformanceMode(com.google.mlkit.vision.face.FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()
                val detector = com.google.mlkit.vision.face.FaceDetection.getClient(options)
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { proxy ->
                    val media = proxy.image
                    if (media == null) { proxy.close(); return@setAnalyzer }
                    val image = com.google.mlkit.vision.common.InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    detector.process(image).addOnSuccessListener { faces ->
                        val face = faces.maxByOrNull { it.boundingBox.width() }
                        if (face != null) {
                            latestFaceWidthPx = face.boundingBox.width().toFloat()
                            if (referenceFaceWidthPx > 0f && latestFaceWidthPx > 0f) {
                                val distance = calibrationDistanceCm * referenceFaceWidthPx / latestFaceWidthPx
                                val rounded = distance.roundToInt().coerceIn(5, 300)
                                distanceText.text = "$rounded cm"
                                statusText.text = if (distance < alertThresholdCm) "⚠ TOO CLOSE — MOVE AWAY" else "✓ SAFE DISTANCE"
                                statusText.setTextColor(ContextCompat.getColor(this, if (distance < alertThresholdCm) R.color.red else R.color.green))
                            }
                        } else {
                            distanceText.text = "-- cm"
                            statusText.text = "Face not detected"
                        }
                    }.addOnCompleteListener { proxy.close() }
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, previewUseCase, analysis)
            } catch (e: Exception) { statusText.text = "Camera error: ${e.javaClass.simpleName}" }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        super.onDestroy()
    }
}
