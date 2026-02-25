package dev.yahaveliyahu.streambridge

import android.R.attr.bitmap
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var captureButton: Button
    private lateinit var backButton: Button
    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalysis: ImageAnalysis? = null
    private val tag = "CameraActivity"

    private var isStreaming = false
    private val streamingScope = CoroutineScope(Dispatchers.Default + Job())


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        previewView = findViewById(R.id.previewView)
        captureButton = findViewById(R.id.captureButton)
        backButton = findViewById(R.id.backButton)

        cameraExecutor = Executors.newSingleThreadExecutor()

        captureButton.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
            } else {
                startStreaming()
            }
        }

        backButton.setOnClickListener {
            finish()
        }

        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        try {
                            if (isStreaming) {
                                processImage(imageProxy)
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "Error processing image", e)
                        } finally {
                            imageProxy.close()
                        }
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e(tag, "Camera binding failed", e)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(imageProxy: ImageProxy) {
        Log.d("CameraActivity", "processImage called. isStreaming=$isStreaming")
        try {
            // Convert ImageProxy to NV21 byte array
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            // Copy Y plane
            yBuffer.get(nv21, 0, ySize)

            // Copy UV planes - interleave U and V
            val uvPixelStride = imageProxy.planes[1].pixelStride
            if (uvPixelStride == 1) {
                // Already interleaved
                vBuffer.get(nv21, ySize, vSize)
            } else {
                // Need to interleave manually
                val uvRowStride = imageProxy.planes[1].rowStride
                val uvWidth = imageProxy.width / 2
                val uvHeight = imageProxy.height / 2

                for (row in 0 until uvHeight) {
                    for (col in 0 until uvWidth) {
                        val vuIndex = ySize + (row * uvWidth + col) * 2
                        val uIndex = row * uvRowStride + col * uvPixelStride
                        val vIndex = row * uvRowStride + col * uvPixelStride

                        nv21[vuIndex] = vBuffer.get(vIndex)
                        nv21[vuIndex + 1] = uBuffer.get(uIndex)
                    }
                }
            }

            // Convert to JPEG
            val yuvImage = YuvImage(
                nv21,
                ImageFormat.NV21,
                imageProxy.width,
                imageProxy.height,
                null
            )

            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                Rect(0, 0, imageProxy.width, imageProxy.height),
                80,
                out
            )

//            // Get rotation and apply it if needed
//            val rotation = imageProxy.imageInfo.rotationDegrees
//
//            val finalBytes = if (rotation != 0) {
//                // Decode JPEG to Bitmap
//                val tempBitmap = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
//
//                // Rotate bitmap
//                val matrix = android.graphics.Matrix()
//                matrix.postRotate(rotation.toFloat())
//                val rotatedBitmap = Bitmap.createBitmap(
//                    tempBitmap, 0, 0,
//                    tempBitmap.width, tempBitmap.height,
//                    matrix, true
//                )
//
//                // Re-compress to JPEG
//                val rotatedOut = ByteArrayOutputStream()
//                rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, rotatedOut)
//
//                // Clean up
//                tempBitmap.recycle()
//                rotatedBitmap.recycle()
//
//                rotatedOut.toByteArray()
//            } else {
//                out.toByteArray()
//            }


            ServerManager.currentCameraFrame = out.toByteArray()
            Log.d("CameraActivity", "Frame saved: ${ServerManager.currentCameraFrame?.size} bytes")

        } catch (e: Exception) {
            Log.e(tag, "Error processing image", e)
        }
    }



    private fun startStreaming() {
        isStreaming = true
        captureButton.text = "Stop Streaming"
        Toast.makeText(this, "Streaming started - PC can now view camera", Toast.LENGTH_SHORT).show()
    }

    private fun stopStreaming() {
        isStreaming = false
        captureButton.text = "Start Streaming"
        ServerManager.currentCameraFrame = null
        Toast.makeText(this, "Streaming stopped", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        streamingScope.cancel()
        cameraExecutor.shutdown()
    }
}
