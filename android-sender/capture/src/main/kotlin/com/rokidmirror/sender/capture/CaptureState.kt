package com.rokidmirror.sender.capture

import android.content.Intent
import com.rokidmirror.protocol.control.ErrorCode

/** Geometry of the captured content as reported by Android. */
data class CaptureGeometry(val width: Int, val height: Int, val densityDpi: Int, val rotationDegrees: Int = 0)

sealed class CaptureState {
    object Idle : CaptureState()
    object AwaitingPermission : CaptureState()
    data class Active(val source: CaptureGeometry, val virtualDisplay: CaptureGeometry, val contentVisible: Boolean = true) : CaptureState()
    data class Stopped(val reason: String) : CaptureState()
    data class Error(val code: ErrorCode, val details: String?) : CaptureState()
}

/** The system consent intent the activity must launch with `startActivityForResult`. */
class CaptureRequest(val intent: Intent, val singleAppChoiceOffered: Boolean)

/** Handle for one projection session. Exactly one VirtualDisplay exists per session. */
interface CaptureSession {
    val geometry: CaptureGeometry
}

/** Which system picker to offer. WHOLE_DISPLAY forces entire-screen on Android 14+. */
enum class CaptureMode { WHOLE_DISPLAY, USER_CHOICE }
