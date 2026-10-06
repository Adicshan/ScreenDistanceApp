package com.example.screendistance

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
    private lateinit var monitorButton: Button
    private var referenceFaceWidthPx = 0f
    private val calibrationDistanceCm = 35f
    private val alertThresholdCm = 30f
    private var latestFaceWidthPx = 0f

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

        referenceFaceWidthPx = getSharedPreferences("screen_sense", MODE_PRIVATE)
            .getFloat("reference_width", 0f)

        calibrate.setOnClickListener {
            if (latestFaceWidthPx > 0f) {
                referenceFaceWidthPx = latestFaceWidthPx
                getSharedPreferences("screen_sense", MODE_PRIVATE)
                    .edit().putFloat("reference_width", referenceFaceWidthPx).apply()
                statusText.text = "Calibrated at 35 cm ✓"
                startMonitoringService()
            } else {
                statusText.text = "Face not detected — look at the camera"
            }
        }

        monitorButton.setOnClickListener { startMonitoringService() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startMonitoringService() {
        if (referenceFaceWidthPx <= 0f) {
            statusText.text = "Calibrate at 35 cm first"
            return
        }
        val intent = Intent(this, DistanceMonitorService::class.java)
            .setAction(DistanceMonitorService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
        statusText.text = "Background monitoring is ON ✓"
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val previewUseCase = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()
            val detector = FaceDetection.getClient(options)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()

            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { proxy ->
                val media = proxy.image
                if (media == null) { proxy.close(); return@setAnalyzer }
                val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                detector.process(image).addOnSuccessListener { faces ->
                    val face = faces.maxByOrNull { it.boundingBox.width() }
                    if (face != null) {
                        latestFaceWidthPx = face.boundingBox.width().toFloat()
                        if (referenceFaceWidthPx > 0f) {
                            val distance = calibrationDistanceCm * referenceFaceWidthPx / latestFaceWidthPx
                            val rounded = distance.roundToInt().coerceIn(5, 300)
                            distanceText.text = "$rounded cm"
                            if (distance < alertThresholdCm) {
                                statusText.text = "⚠ TOO CLOSE — MOVE AWAY"
                                statusText.setTextColor(ContextCompat.getColor(this, R.color.red))
                            } else {
                                statusText.text = "✓ SAFE DISTANCE"
                                statusText.setTextColor(ContextCompat.getColor(this, R.color.green))
                            }
                        } else {
                            distanceText.text = "-- cm"
                            statusText.text = "Calibrate at 35 cm"
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
}
