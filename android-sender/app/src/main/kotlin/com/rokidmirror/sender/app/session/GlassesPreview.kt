package com.rokidmirror.sender.app.session

import android.graphics.Bitmap

/** A snapshot of the glasses' display, with what it actually shows. */
class GlassesPreview(val bitmap: Bitmap, val kind: String, val receivedNs: Long) {
    /** True when the snapshot carries the camera view rather than just the overlay. */
    val isCamera: Boolean get() = kind == "CAMERA"
}
