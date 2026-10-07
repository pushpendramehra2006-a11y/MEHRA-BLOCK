package com.mehra.block

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class YouTubeAdBlockService : AccessibilityService() {
    private lateinit var audioManager: AudioManager
    private var isMuted = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.contains("com.google.android.youtube") == true) {
            val rootNode = rootInActiveWindow ?: return
            val buttons = rootNode.findAccessibilityNodeInfosByText("Skip") + rootNode.findAccessibilityNodeInfosByText("Skip Ad")
            for (btn in buttons) {
                if (btn.isClickable) {
                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    unmuteAudio()
                    return
                }
            }
            val adNodes = rootNode.findAccessibilityNodeInfosByText("Ad")
            if (adNodes.any { it.text?.toString()?.trim() == "Ad" }) muteAudio() else unmuteAudio()
        }
    }

    private fun muteAudio() {
        if (!isMuted) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            isMuted = true
        }
    }

    private fun unmuteAudio() {
        if (isMuted) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            isMuted = false
        }
    }

    override fun onInterrupt() { unmuteAudio() }
}
