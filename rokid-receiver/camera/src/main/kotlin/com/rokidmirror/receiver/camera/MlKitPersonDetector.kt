package com.rokidmirror.receiver.camera

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.rokidmirror.receiver.telemetry.ReceiverLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Finds people with an on-device face detector whose model ships inside the APK: no network, no
 * Google Play services, and no frame ever leaves the glasses. Nothing is recognised or stored;
 * the detector reports where a face is and the result is discarded with the frame.
 *
 * It finds people who are facing the camera. Someone turned away will not be bracketed, which is
 * a property of face detection, not a bug.
 */
class MlKitPersonDetector : PersonDetector {
    private companion object { const val TAG = "Vision"; const val TIMEOUT_MS = 800L }

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.08f)
            .build(),
    )

    private var reusable: Bitmap? = null

    override val description: String get() = "on-device face detection"

    override fun detect(frame: VisionFrame): List<DetectedPerson> {
        val bitmap = bitmapFor(frame)
        bitmap.setPixels(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        // Rotation is handed to the detector rather than applied to the pixels, so the boxes come
        // back in the same orientation the renderer draws.
        val image = InputImage.fromBitmap(bitmap, frame.rotationDegrees)
        val rotated = frame.rotationDegrees % 180 != 0
        val frameWidth = (if (rotated) frame.height else frame.width).toFloat()
        val frameHeight = (if (rotated) frame.width else frame.height).toFloat()

        val results = ArrayList<DetectedPerson>(4)
        val done = CountDownLatch(1)
        detector.process(image)
            .addOnSuccessListener { faces ->
                for (face in faces) {
                    val box = face.boundingBox
                    results += PersonBox.fromFace(
                        left = box.left / frameWidth,
                        top = box.top / frameHeight,
                        right = box.right / frameWidth,
                        bottom = box.bottom / frameHeight,
                    )
                }
                done.countDown()
            }
            .addOnFailureListener {
                ReceiverLog.w(TAG, "detect_failed", "error" to it.message)
                done.countDown()
            }
        if (!done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            ReceiverLog.w(TAG, "detect_timeout")
            return emptyList()
        }
        return results
    }

    private fun bitmapFor(frame: VisionFrame): Bitmap {
        val existing = reusable
        if (existing != null && existing.width == frame.width && existing.height == frame.height && !existing.isRecycled) return existing
        existing?.recycle()
        return Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888).also { reusable = it }
    }

    override fun close() {
        runCatching { detector.close() }
        reusable?.recycle()
        reusable = null
    }
}
