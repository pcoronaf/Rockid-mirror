package com.rokidmirror.receiver.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import com.rokidmirror.receiver.telemetry.ReceiverLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera frames for vision mode.
 *
 * It opens the camera through the ordinary Android API and does nothing whatsoever to the
 * device's capture indicator: whatever the glasses show when the camera is live, they keep
 * showing. Frames are read, used for the current picture and dropped; nothing is recorded and
 * nothing leaves the device.
 */
class CameraVisionSource(
    private val context: Context,
    /** Called on the camera thread with the luma plane of each frame. */
    private val onLuma: (luma: ByteArray, width: Int, height: Int, rowStride: Int, rotationDegrees: Int) -> Unit,
) {
    private companion object {
        const val TAG = "Camera"
        val TARGET = Size(640, 480)
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private val running = AtomicBoolean(false)
    private var lumaBuffer = ByteArray(0)

    val isRunning: Boolean get() = running.get()

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** @return failure with a message fit to show on the glasses. */
    fun start(): Result<Unit> {
        if (running.get()) return Result.success(Unit)
        if (!hasPermission()) return Result.failure(SecurityException("camera permission not granted"))
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return Result.failure(IllegalStateException("no camera service"))
        return try {
            val cameraId = chooseCamera(manager) ?: return Result.failure(IllegalStateException("no usable camera"))
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val rotation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val size = chooseSize(characteristics)

            val cameraThread = HandlerThread("vision-camera").apply { start() }
            thread = cameraThread
            val cameraHandler = Handler(cameraThread.looper)
            handler = cameraHandler

            val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            reader = imageReader
            imageReader.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val needed = buffer.remaining()
                    if (lumaBuffer.size < needed) lumaBuffer = ByteArray(needed)
                    buffer.get(lumaBuffer, 0, needed)
                    onLuma(lumaBuffer, image.width, image.height, plane.rowStride, rotation)
                } catch (e: Exception) {
                    ReceiverLog.w(TAG, "frame_failed", "error" to e.message)
                } finally {
                    runCatching { image.close() }
                }
            }, cameraHandler)

            running.set(true)
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    startSession(device, imageReader, cameraHandler)
                }

                override fun onDisconnected(device: CameraDevice) { ReceiverLog.w(TAG, "camera_disconnected"); stop() }

                override fun onError(device: CameraDevice, error: Int) {
                    ReceiverLog.e(TAG, "camera_error", null, "code" to error)
                    stop()
                }
            }, cameraHandler)
            ReceiverLog.i(TAG, "camera_opening", "id" to cameraId, "size" to "${size.width}x${size.height}", "sensorRotation" to rotation)
            Result.success(Unit)
        } catch (e: CameraAccessException) {
            stop()
            Result.failure(e)
        } catch (e: Exception) {
            stop()
            Result.failure(e)
        }
    }

    fun stop() {
        running.set(false)
        runCatching { session?.close() }; session = null
        runCatching { camera?.close() }; camera = null
        runCatching { reader?.close() }; reader = null
        thread?.quitSafely(); thread = null; handler = null
        ReceiverLog.i(TAG, "camera_stopped")
    }

    @Suppress("DEPRECATION")
    private fun startSession(device: CameraDevice, imageReader: ImageReader, cameraHandler: Handler) {
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(imageReader.surface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            device.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    session = configured
                    runCatching { configured.setRepeatingRequest(request.build(), null, cameraHandler) }
                        .onFailure { ReceiverLog.e(TAG, "repeating_request_failed", it) }
                }

                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    ReceiverLog.e(TAG, "session_configure_failed", null)
                    stop()
                }
            }, cameraHandler)
        } catch (e: Exception) {
            ReceiverLog.e(TAG, "session_failed", e)
            stop()
        }
    }

    private fun chooseCamera(manager: CameraManager): String? {
        val ids = manager.cameraIdList
        return ids.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.firstOrNull()
    }

    private fun chooseSize(characteristics: CameraCharacteristics): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return TARGET
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return TARGET
        // Small is the point: enough detail to find a face, cheap enough to run all day.
        return sizes.filter { it.width >= TARGET.width && it.height >= TARGET.height }
            .minByOrNull { it.width.toLong() * it.height } ?: sizes.maxByOrNull { it.width.toLong() * it.height } ?: TARGET
    }
}
