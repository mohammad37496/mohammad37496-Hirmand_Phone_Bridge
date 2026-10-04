package ir.hirmand.phonebridge.sync

import android.content.Context
import android.net.Uri
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

                    // A fresh snapshot is created only after the backlog drains.
                    // This avoids creating duplicate fresh snapshots on retries.
                    db.enqueue(collector.collect(prefs).toString())
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
                                db.delete(item.id)
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

    private class PermanentFileUploadException(message: String) : Exception(message)

    private suspend fun uploadSelectedFiles(prefs: AppPrefs, endpoint: String) {
        val uploadEndpoint = endpoint.trimEnd('/') + "/files"
        val files = prefs.selectedFiles()
        val resolver = applicationContext.contentResolver
        val maxPerFile = 8L * 1024L * 1024L

        for (file in files) {
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

                    response.code == 401 || response.code == 403 ->
                        throw PermanentFileUploadException("file-auth-${response.code}")

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
