package com.flowbubble.app.core

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.delay

object TorchController {
    enum class State { ON, OFF, REQUESTED, NO_FLASH, NO_PERMISSION, FAILED }

    data class Result(val state: State, val detail: String? = null)

    suspend fun set(context: Context, enable: Boolean): Result {
        /*
         * Asked before the flash is looked for, because without CAMERA every camera call comes back
         * empty and the app told the user their phone had no flashlight. It has one; the app was not
         * allowed to see it, and that is a different answer with a different fix.
         */
        if (!Permissions.hasPermission(context, Manifest.permission.CAMERA)) {
            return Result(State.NO_PERMISSION, "Android needs your OK before the app may use the flash.")
        }
        val manager = context.getSystemService(CameraManager::class.java)
            ?: return Result(State.NO_FLASH, "This device doesn't expose a camera flash.")
        val id = flashCameraId(manager)
            ?: return Result(State.NO_FLASH, "I couldn't find a camera with a flash on this device.")
        var observed: Boolean? = null
        var unavailable = false
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == id) observed = enabled
            }

            override fun onTorchModeUnavailable(cameraId: String) {
                if (cameraId == id) unavailable = true
            }
        }
        val registered = runCatching {
            manager.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
        }.isSuccess
        if (!registered) {
            return Result(State.REQUESTED, "Android accepted the request but doesn't report torch state back to apps.")
        }
        try {
            runCatching { manager.setTorchMode(id, enable) }.onFailure { error ->
                return Result(
                    State.FAILED,
                    error.message ?: "Android refused to change the flashlight. Another app may be using the camera."
                )
            }
            repeat(12) {
                delay(120)
                if (observed == enable) return Result(if (enable) State.ON else State.OFF)
                if (unavailable) {
                    return Result(
                        State.FAILED,
                        "Android says the flash is unavailable. Another app may be using the camera."
                    )
                }
            }
            return Result(
                State.REQUESTED,
                "Android accepted the request but didn't confirm the new state. A camera app may be holding the flash."
            )
        } finally {
            runCatching { manager.unregisterTorchCallback(callback) }
        }
    }

    fun hasFlash(context: Context): Boolean {
        val manager = context.getSystemService(CameraManager::class.java) ?: return false
        return flashCameraId(manager) != null
    }

    private fun flashCameraId(manager: CameraManager): String? = runCatching {
        val ids = manager.cameraIdList
        ids.firstOrNull { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                characteristics.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()
}
