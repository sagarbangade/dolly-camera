package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen() {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    if (cameraPermissionState.status.isGranted) {
        CameraScreen()
    } else {
        LaunchedEffect(Unit) {
            cameraPermissionState.launchPermissionRequest()
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Camera permission is required.")
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun CameraScreen(viewModel: DollyViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { 
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        } 
    }
    
    val videoCapture = remember {
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()
        VideoCapture.withOutput(recorder)
    }

    val recording = remember { mutableStateOf<Recording?>(null) }

    LaunchedEffect(Unit) {
        val cameraProvider = context.getCameraProvider()
        val preview = Preview.Builder().build()
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(executor, FaceAnalyzer(
            onFaceDetected = { face, w, h ->
                viewModel.onFaceDetected(face, w, h)
            },
            onNoFace = {
                viewModel.onNoFace()
            }
        ))

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis,
                videoCapture
            )
            preview.setSurfaceProvider(previewView.surfaceProvider)
            viewModel.cameraControl = camera.cameraControl
            
            val zoomState = camera.cameraInfo.zoomState.value
            if (zoomState != null) {
                viewModel.updateCameraZoomLimits(zoomState.minZoomRatio, zoomState.maxZoomRatio)
            }
        } catch (exc: Exception) {
            Log.e("CameraScreen", "Use case binding failed", exc)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize().clickable {
                viewModel.lockFace()
            }
        )

        if (uiState.detectedFaceBox != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val box = uiState.detectedFaceBox!!
                
                // MLKit Face coordinates are relative to the InputImage.
                // Assuming upright rotation: 
                val imageWidth = uiState.imageWidth.toFloat()
                val imageHeight = uiState.imageHeight.toFloat()
                
                // FILL_CENTER scale math
                val viewRectAspectRatio = size.width / size.height
                val imageAspectRatio = imageWidth / imageHeight
                
                var scale = 1f
                var offsetX = 0f
                var offsetY = 0f
                
                if(viewRectAspectRatio > imageAspectRatio) {
                    // View is wider than image, scale to fit width
                    scale = size.width / imageWidth
                    offsetY = (size.height - imageHeight * scale) / 2
                } else {
                    // View is taller than image, scale to fit height
                    scale = size.height / imageHeight
                    offsetX = (size.width - imageWidth * scale) / 2
                }
                
                val mappedLeft = box.left * scale + offsetX
                val mappedTop = box.top * scale + offsetY
                val mappedRight = box.right * scale + offsetX
                val mappedBottom = box.bottom * scale + offsetY

                val rectWidth = mappedRight - mappedLeft
                val rectHeight = mappedBottom - mappedTop

                val color = when (uiState.status) {
                    CameraStatus.SCANNING -> Color.White
                    CameraStatus.LOCKED, CameraStatus.RECORDING -> Color.Green
                    CameraStatus.SUBJECT_LOST -> Color.Red
                }

                drawRect(
                    color = color,
                    topLeft = Offset(mappedLeft, mappedTop),
                    size = Size(rectWidth, rectHeight),
                    style = Stroke(width = 8f)
                )
            }
        }

        val statusText = when (uiState.status) {
            CameraStatus.SCANNING -> "Scanning"
            CameraStatus.LOCKED -> "Locked"
            CameraStatus.RECORDING -> "Recording"
            CameraStatus.SUBJECT_LOST -> "Subject Lost"
        }

        Text(
            text = statusText,
            color = Color.White,
            modifier = Modifier.padding(48.dp).align(Alignment.TopStart),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = String.format(Locale.US, "%.1fx", uiState.zoomRatio),
            color = Color.White,
            modifier = Modifier.padding(48.dp).align(Alignment.TopEnd),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(48.dp)
                .size(72.dp)
                .background(Color.White, CircleShape)
                .clickable {
                    if (uiState.isRecording) {
                        viewModel.stopRecording()
                        recording.value?.stop()
                        recording.value = null
                    } else if (uiState.status == CameraStatus.LOCKED) {
                        viewModel.startRecording()
                        
                        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                            .format(System.currentTimeMillis())
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                        }
                        val options = MediaStoreOutputOptions
                            .Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                            .setContentValues(contentValues)
                            .build()

                        recording.value = videoCapture.output
                            .prepareRecording(context, options)
                            .start(ContextCompat.getMainExecutor(context)) { recordEvent ->
                                if (recordEvent is VideoRecordEvent.Finalize) {
                                    if (recordEvent.hasError()) {
                                        Log.e("CameraScreen", "Video capture error: ${recordEvent.error}")
                                    } else {
                                        viewModel.stopRecording()
                                    }
                                }
                            }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val innerSize = if (uiState.isRecording) 28.dp else 60.dp
            val innerShape = if (uiState.isRecording) RoundedCornerShape(6.dp) else CircleShape
            Box(
                modifier = Modifier
                    .size(innerSize)
                    .background(Color.Red, innerShape)
            )
        }
    }
}
