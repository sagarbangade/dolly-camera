package com.example

import android.graphics.Rect
import androidx.camera.core.CameraControl
import androidx.lifecycle.ViewModel
import com.google.mlkit.vision.face.Face
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CameraStatus {
    SCANNING,
    LOCKED,
    RECORDING,
    SUBJECT_LOST
}

data class DollyState(
    val status: CameraStatus = CameraStatus.SCANNING,
    val zoomRatio: Float = 1.0f,
    val detectedFaceBox: Rect? = null,
    val imageWidth: Int = 1,
    val imageHeight: Int = 1,
    val isRecording: Boolean = false
)

class DollyViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(DollyState())
    val uiState = _uiState.asStateFlow()

    private var initialBoundingBoxHeight: Float = 0f
    private var initialZoomRatio: Float = 1.0f
    
    private var previousZoom: Float = 1.0f
    
    private var minZoom: Float = 1.0f
    private var maxZoom: Float = 5.0f

    private var missingFramesCount = 0

    var cameraControl: CameraControl? = null

    fun updateCameraZoomLimits(min: Float, max: Float) {
        minZoom = min
        maxZoom = max
    }

    fun onFaceDetected(face: Face, width: Int, height: Int) {
        val currentState = _uiState.value
        
        _uiState.value = currentState.copy(
            detectedFaceBox = face.boundingBox,
            imageWidth = width,
            imageHeight = height
        )

        missingFramesCount = 0

        if (currentState.status == CameraStatus.LOCKED || currentState.status == CameraStatus.RECORDING || currentState.status == CameraStatus.SUBJECT_LOST) {
            
            if (currentState.status == CameraStatus.SUBJECT_LOST) {
                _uiState.value = _uiState.value.copy(
                    status = if (currentState.isRecording) CameraStatus.RECORDING else CameraStatus.LOCKED
                )
            }

            val currentHeight = face.boundingBox.height().toFloat()
            if (currentHeight > 0) {
                val targetZoom = (initialBoundingBoxHeight / currentHeight) * initialZoomRatio
                var smoothZoom = previousZoom + 0.1f * (targetZoom - previousZoom)
                smoothZoom = smoothZoom.coerceIn(minZoom, maxZoom)
                
                previousZoom = smoothZoom
                _uiState.value = _uiState.value.copy(zoomRatio = smoothZoom)
                cameraControl?.setZoomRatio(smoothZoom)
            }
        }
    }

    fun onNoFace() {
        val currentState = _uiState.value
        
        _uiState.value = currentState.copy(detectedFaceBox = null)

        if (currentState.status == CameraStatus.LOCKED || currentState.status == CameraStatus.RECORDING) {
            missingFramesCount++
            if (missingFramesCount >= 60) {
                _uiState.value = _uiState.value.copy(status = CameraStatus.SUBJECT_LOST)
            }
        }
    }

    fun lockFace() {
        val currentState = _uiState.value
        val box = currentState.detectedFaceBox
        if (box != null && (currentState.status == CameraStatus.SCANNING || currentState.status == CameraStatus.SUBJECT_LOST)) {
            val currentHeight = box.height().toFloat()
            if(currentHeight > 0) {
                initialBoundingBoxHeight = currentHeight
                initialZoomRatio = currentState.zoomRatio
                previousZoom = initialZoomRatio
                _uiState.value = currentState.copy(
                    status = CameraStatus.LOCKED
                )
            }
        }
    }

    fun startRecording() {
        if (_uiState.value.status == CameraStatus.LOCKED) {
            _uiState.value = _uiState.value.copy(status = CameraStatus.RECORDING, isRecording = true)
        }
    }

    fun stopRecording() {
        if (_uiState.value.status == CameraStatus.RECORDING || _uiState.value.status == CameraStatus.SUBJECT_LOST) {
            _uiState.value = _uiState.value.copy(status = CameraStatus.LOCKED, isRecording = false)
        }
    }
}
