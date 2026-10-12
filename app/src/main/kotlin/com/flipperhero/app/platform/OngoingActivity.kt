package com.flipperhero.app.platform

import android.content.Context
import android.content.Intent
import android.os.Build
import com.flipperhero.app.service.FlipperForegroundService

/** Starts/updates/stops the ongoing-session notification. Local only — no FCM. */
interface OngoingActivity {
    fun start(text: String)
    fun stop()
}

class ForegroundServiceOngoingActivity(private val context: Context) : OngoingActivity {
    override fun start(text: String) {
        val intent = Intent(context, FlipperForegroundService::class.java)
            .putExtra(FlipperForegroundService.EXTRA_TEXT, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    override fun stop() {
        context.startService(
            Intent(context, FlipperForegroundService::class.java).setAction(FlipperForegroundService.ACTION_STOP),
        )
    }
}
