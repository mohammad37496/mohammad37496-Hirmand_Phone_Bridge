package ir.hirmand.phonebridge.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import ir.hirmand.phonebridge.data.AppPrefs

class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val prefs = AppPrefs(context)
        if (!prefs.callRecordingEnabled) return
        if (
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            prefs.lastCallRecordingStatus = "ضبط تماس فعال است اما مجوزهای لازم صادر نشده‌اند"
            return
        }

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val action = when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                prefs.callDirectionHint = "incoming"
                null
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                val direction = if (prefs.callDirectionHint == "incoming") "incoming" else "outgoing"
                prefs.callDirectionHint = ""
                Intent(context, CallRecordingService::class.java)
                    .setAction(CallRecordingService.ACTION_START)
                    .putExtra(CallRecordingService.EXTRA_DIRECTION, direction)
            }
            TelephonyManager.EXTRA_STATE_IDLE ->
                Intent(context, CallRecordingService::class.java)
                    .setAction(CallRecordingService.ACTION_STOP_RECORDING)
            else -> null
        } ?: return

        runCatching {
            context.startService(action)
        }.onFailure {
            prefs.lastCallRecordingStatus = "شروع سرویس ضبط تماس ممکن نشد"
        }
    }
}
