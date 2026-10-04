package ir.hirmand.phonebridge.sync

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ir.hirmand.phonebridge.data.AppPrefs
import ir.hirmand.phonebridge.data.EndpointPolicy
import ir.hirmand.phonebridge.data.LocalQueueDb
import ir.hirmand.phonebridge.data.PhoneDataCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object {
        private const val MAX_BATCH_SIZE = 20
        private const val MAX_ITEMS_PER_RUN = 200
        private const val MAX_QUEUE_ATTEMPTS = 5
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = AppPrefs(applicationContext)
        val endpoint = prefs.endpoint.trim()
        if (endpoint.isBlank() || prefs.token.isBlank()) return@withContext Result.failure()
        if (!EndpointPolicy.isAllowed(endpoint)) return@withContext Result.failure()

        val db = LocalQueueDb(applicationContext)
        val collector = PhoneDataCollector(applicationContext)

        try {
            // File uploads are deduplicated server-side by device + SHA-256.
            uploadSelectedFiles(prefs, endpoint)

            var processed = 0

            while (processed < MAX_ITEMS_PER_RUN) {
                val batch = db.peek(MAX_BATCH_SIZE)

                if (batch.isEmpty()) {
                    // Do not create newer snapshots while an older packet is
                    // waiting for its per-item backoff window.
                    if (db.count() > 0) {
                        return@withContext Result.retry()
                    }

                    // Delta mode: if no meaningful module data changed since the
                    // last successful snapshot, send only a tiny heartbeat.
                    val snapshot = collector.collect(prefs)
                    val snapshotHash = snapshotHash(snapshot)

                    if (snapshotHash == prefs.lastSnapshotHash) {
                        when (sendHeartbeat(prefs, endpoint, snapshotHash)) {
                            HeartbeatResult.SUCCESS -> {
                                prefs.lastSuccessfulSyncAt = System.currentTimeMillis()
                                return@withContext Result.success()
                            }
                            HeartbeatResult.AUTH_FAILURE -> return@withContext Result.failure()
                            HeartbeatResult.RETRY -> return@withContext Result.retry()
                            HeartbeatResult.PERMANENT -> return@withContext Result.failure()
                        }
                    }

                    snapshot.put("snapshotHash", snapshotHash)
                    db.enqueue(snapshot.toString())
                    continue
                }

                for (item in batch) {
                    val body = item.payload.toRequestBody(
                        "application/json; charset=utf-8".toMediaType()
                    )
                    val request = Request.Builder()
                        .url(endpoint)
                        .post(body)
                        .header("Authorization", "Bearer ${prefs.token}")
                        .header("X-Hirmand-Device-Id", prefs.installId)
                        .build()

                    client.newCall(request).execute().use { response ->
                        when {
                            response.isSuccessful -> {
                                val sentHash = runCatching {
                                    org.json.JSONObject(item.payload).optString("snapshotHash")
                                }.getOrDefault("")
                                db.delete(item.id)
                                if (sentHash.isNotBlank()) prefs.lastSnapshotHash = sentHash
                            }

                            response.code == 401 || response.code == 403 -> {
                                // Keep the packet so re-registration can resend it.
                                return@withContext Result.failure()
                            }

                            response.code == 408 || response.code == 429 || response.code >= 500 -> {
                                val deadLettered = db.markFailure(
                                    item.id,
                                    "HTTP ${response.code}",
                                    MAX_QUEUE_ATTEMPTS,
                                )
                                if (!deadLettered) return@withContext Result.retry()
                            }

                            response.code in 400..499 -> {
                                // Other 4xx errors are permanent for this packet.
                                db.markFailure(
                                    item.id,
                                    "HTTP ${response.code}",
                                    maxAttempts = 1,
                                )
                            }

                            else -> {
                                val deadLettered = db.markFailure(
                                    item.id,
                                    "HTTP ${response.code}",
                                    MAX_QUEUE_ATTEMPTS,
                                )
                                if (!deadLettered) return@withContext Result.retry()
                            }
                        }
                    }
                }

                processed += batch.size
            }

            // Safety cap reached; continue with the remaining queue later.
            Result.retry()
        } catch (_: PermanentFileUploadException) {
            Result.failure()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private enum class HeartbeatResult { SUCCESS, AUTH_FAILURE, RETRY, PERMANENT }

    private fun snapshotHash(payload: org.json.JSONObject): String {
        val copy = org.json.JSONObject(payload.toString()).apply {
            remove("sentAt")
            remove("syncId")
            remove("deviceStats")
            remove("snapshotHash")
        }
        val canonical = canonicalize(copy)
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun canonicalize(value: Any?): String = when (value) {
        null, org.json.JSONObject.NULL -> "null"
        is org.json.JSONObject -> {
            value.keys().asSequence().toList().sorted().joinToString(
                prefix = "{",
                postfix = "}",
                separator = ",",
            ) { key -> org.json.JSONObject.quote(key) + ":" + canonicalize(value.opt(key)) }
        }
        is org.json.JSONArray -> {
            (0 until value.length()).joinToString(
                prefix = "[",
                postfix = "]",
                separator = ",",
            ) { index -> canonicalize(value.opt(index)) }
        }
        is Number, is Boolean -> value.toString()
        else -> org.json.JSONObject.quote(value.toString())
    }

    private fun collectHeartbeatStats(): org.json.JSONObject {
        val batteryManager = applicationContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val battery = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }

        val stat = StatFs(Environment.getDataDirectory().path)
        val storageAvailable = stat.availableBytes
        val storageTotal = stat.totalBytes

        val memoryManager = applicationContext.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        memoryManager?.getMemoryInfo(memoryInfo)

        val charging = applicationContext.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            ?.let { status ->
                val value = status.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                value == BatteryManager.BATTERY_STATUS_CHARGING || value == BatteryManager.BATTERY_STATUS_FULL
            }

        return org.json.JSONObject()
            .put("batteryPercent", battery ?: org.json.JSONObject.NULL)
            .put("batteryCharging", charging ?: org.json.JSONObject.NULL)
            .put("storageAvailableBytes", storageAvailable)
            .put("storageTotalBytes", storageTotal)
            .put("ramAvailableBytes", memoryInfo.availMem)
            .put("ramTotalBytes", memoryInfo.totalMem)
    }

    private fun sendHeartbeat(
        prefs: AppPrefs,
        endpoint: String,
        snapshotHash: String,
    ): HeartbeatResult {
        val queue = LocalQueueDb(applicationContext)
        val body = org.json.JSONObject()
            .put("snapshotHash", snapshotHash)
            .put("deviceStats", collectHeartbeatStats())
            .put(
                "queue",
                org.json.JSONObject()
                    .put("queued", queue.count())
                    .put("deadLetters", queue.countDeadLetters())
                    .put("reportedAt", System.currentTimeMillis())
            )
            .put(
                "device",
                org.json.JSONObject()
                    .put("id", prefs.installId)
                    .put("name", prefs.deviceName)
                    .put("manufacturer", android.os.Build.MANUFACTURER)
                    .put("model", android.os.Build.MODEL)
                    .put("androidVersion", android.os.Build.VERSION.RELEASE ?: "unknown")
                    .put("sdkInt", android.os.Build.VERSION.SDK_INT),
            )

        val request = Request.Builder()
            .url(endpoint.trimEnd('/') + "/heartbeat")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Authorization", "Bearer ${prefs.token}")
            .header("X-Hirmand-Device-Id", prefs.installId)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> HeartbeatResult.SUCCESS
                    response.code == 401 || response.code == 403 -> HeartbeatResult.AUTH_FAILURE
                    response.code == 408 || response.code == 429 || response.code >= 500 -> HeartbeatResult.RETRY
                    response.code in 400..499 -> HeartbeatResult.PERMANENT
                    else -> HeartbeatResult.RETRY
                }
            }
        } catch (_: Exception) {
            HeartbeatResult.RETRY
        }
    }

    private class PermanentFileUploadException(message: String) : Exception(message)

    private suspend fun uploadSelectedFiles(prefs: AppPrefs, endpoint: String) {
        val uploadEndpoint = endpoint.trimEnd('/') + "/files"
        val files = prefs.selectedFiles()
        val resolver = applicationContext.contentResolver
        val maxPerFile = 8L * 1024L * 1024L

        for (file in files) {
            if (file.optBoolean("policyBlocked", false)) continue
            val uri = file.optString("uri").takeIf { it.isNotBlank() } ?: continue
            val declaredSize = file.optLong("sizeBytes", 0L)
            if (declaredSize > maxPerFile) continue
            val bytes = resolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() } ?: continue
            if (bytes.isEmpty() || bytes.size > maxPerFile) continue

            val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (sha256 == file.optString("lastUploadedHash")) continue

            val encodedName = Base64.getEncoder().encodeToString(
                file.optString("name", "file").toByteArray(Charsets.UTF_8)
            )
            val body = bytes.toRequestBody(
                (file.optString("mimeType", "application/octet-stream")).toMediaType()
            )
            val request = Request.Builder()
                .url(uploadEndpoint)
                .post(body)
                .header("Authorization", "Bearer ${prefs.token}")
                .header("X-Hirmand-Device-Id", prefs.installId)
                .header("X-Hirmand-File-Name", encodedName)
                .header("X-Hirmand-File-Sha256", sha256)
                .header("X-Hirmand-File-Size", bytes.size.toString())
                .header("X-Hirmand-File-Mime", file.optString("mimeType", "application/octet-stream"))
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val responseJson = runCatching {
                            org.json.JSONObject(response.body?.string().orEmpty())
                        }.getOrNull()
                        prefs.markSelectedFileUploaded(
                            uri,
                            sha256,
                            responseJson?.optString("fileId")
                        )
                    }

                    response.code == 401 ->
                        throw PermanentFileUploadException("file-auth-401")

                    response.code == 403 -> {
                        prefs.markSelectedFileBlocked(uri, "server-policy")
                        Unit
                    }

                    response.code == 408 || response.code == 429 || response.code >= 500 ->
                        throw IllegalStateException("temporary-file-upload-${response.code}")

                    response.code in 400..499 ->
                        throw PermanentFileUploadException("file-upload-${response.code}")

                    else ->
                        throw IllegalStateException("file-upload-${response.code}")
                }
            }
        }
    }
}
