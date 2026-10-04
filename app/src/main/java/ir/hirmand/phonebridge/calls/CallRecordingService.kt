package ir.hirmand.phonebridge.calls

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ir.hirmand.phonebridge.R
import ir.hirmand.phonebridge.data.AppPrefs
import ir.hirmand.phonebridge.sync.SyncScheduler
import java.io.File

class CallRecordingService : Service() {
    companion object {
        const val ACTION_ENABLE_MONITOR = "ir.hirmand.phonebridge.calls.ENABLE_MONITOR"
        const val ACTION_START = "ir.hirmand.phonebridge.calls.START_RECORDING"
        const val ACTION_STOP_RECORDING = "ir.hirmand.phonebridge.calls.STOP_RECORDING"
        const val ACTION_DISABLE = "ir.hirmand.phonebridge.calls.DISABLE_RECORDING"
        const val EXTRA_DIRECTION = "direction"
        private const val CHANNEL_ID = "call_recording"
        private const val NOTIFICATION_ID = 2407
        private const val MAX_RECORDING_BYTES = 25L * 1024L * 1024L
    }

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var startedAt = 0L
    private var direction = "unknown"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (AppPrefs(this).callRecordingEnabled) {
            runCatching { startMonitoring() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENABLE_MONITOR -> startMonitoring()
            ACTION_START -> startRecording(intent.getStringExtra(EXTRA_DIRECTION) ?: "unknown")
            ACTION_STOP_RECORDING -> stopRecording(stopService = false)
            ACTION_DISABLE -> stopRecording(stopService = true)
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        val prefs = AppPrefs(this)
        if (!prefs.callRecordingEnabled) {
            stopSelf()
            return
        }
        val notification = buildNotification("ضبط تماس آماده است؛ منتظر شروع تماس")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        prefs.lastCallRecordingStatus = "ضبط تماس آماده است؛ اعلان فعال روی گوشی نمایش داده می‌شود"
    }

    private fun startRecording(requestedDirection: String) {
        if (recorder != null) return
        val prefs = AppPrefs(this)
        if (!prefs.callRecordingEnabled) return stopSelf()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            prefs.lastCallRecordingStatus = "مجوز میکروفون برای ضبط تماس وجود ندارد"
            return stopSelf()
        }

        runCatching {
            val notification = buildNotification("در حال ضبط تماس · ${directionLabel(requestedDirection)}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            val dir = File(filesDir, "call-recordings").apply { mkdirs() }
            val file = File(dir, "call-${System.currentTimeMillis()}.m4a")
            val mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(64000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            recorder = mediaRecorder
            recordingFile = file
            startedAt = System.currentTimeMillis()
            direction = when (requestedDirection) {
                "incoming", "outgoing" -> requestedDirection
                else -> "unknown"
            }
            prefs.lastCallRecordingStatus =
                "ضبط فعال است · ${directionLabel(direction)} · اعلان ضبط روی گوشی نمایش داده می‌شود"
        }.onFailure { error ->
            prefs.lastCallRecordingStatus =
                "ضبط این تماس توسط دستگاه پشتیبانی نشد: ${error.javaClass.simpleName}"
            recorder?.runCatching { reset(); release() }
            recorder = null
            recordingFile = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopRecording(stopService: Boolean) {
        val current = recorder ?: run {
            if (stopService) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        val prefs = AppPrefs(this)
        val file = recordingFile
        val start = startedAt
        val finalDirection = direction

        recorder = null
        recordingFile = null
        try {
            current.stop()
        } catch (_: RuntimeException) {
            file?.delete()
            prefs.lastCallRecordingStatus = "فایل ضبط تماس ناقص بود و حذف شد"
            current.runCatching { reset() }
            if (stopService) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        } finally {
            current.release()
        }

        val size = file?.length() ?: 0L
        if (file == null || !file.exists() || size <= 0L || size > MAX_RECORDING_BYTES) {
            file?.delete()
            prefs.lastCallRecordingStatus = "فایل ضبط تماس قابل ارسال نبود"
        } else {
            val ended = System.currentTimeMillis()
            prefs.addPendingCallRecording(
                org.json.JSONObject()
                    .put("path", file.absolutePath)
                    .put("startedAt", start)
                    .put("endedAt", ended)
                    .put("durationSeconds", ((ended - start) / 1000L).coerceAtLeast(0L))
                    .put("direction", finalDirection)
                    .put("mimeType", "audio/mp4")
                    .put("name", file.name)
                    .put("sizeBytes", size)
                    .put("uploadAttempts", 0)
            )
            prefs.lastCallRecordingStatus =
                "تماس ضبط شد · ${size / 1024} KB · در صف ارسال امن به سرور"
            SyncScheduler.enqueue(this)
        }
        if (stopService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            val notification = buildNotification("ضبط تماس آماده است؛ منتظر شروع تماس")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun directionLabel(value: String): String = when (value) {
        "incoming" -> "ورودی"
        "outgoing" -> "خروجی"
        else -> "نامشخص"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ضبط تماس Phone Bridge",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "اعلان قابل‌مشاهده هنگام ضبط تماس"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Phone Bridge · ضبط تماس")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()

    override fun onDestroy() {
        recorder?.runCatching {
            stop()
            release()
        }
        recorder = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
