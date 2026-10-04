package ir.hirmand.phonebridge.sync

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ir.hirmand.phonebridge.data.AppPrefs
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
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = AppPrefs(applicationContext)
        val endpoint = prefs.endpoint.trim()
        if (endpoint.isBlank() || prefs.token.isBlank()) return@withContext Result.failure()

        val db = LocalQueueDb(applicationContext)
        val collector = PhoneDataCollector(applicationContext)

        try {
            uploadSelectedFiles(prefs, endpoint)
            db.enqueue(collector.collect(prefs).toString())

            for ((id, payload) in db.peek()) {
                val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
                val requestBuilder = Request.Builder().url(endpoint).post(body)
                if (prefs.token.isNotBlank()) requestBuilder.header("Authorization", "Bearer ${prefs.token}")
                val request = requestBuilder.build()
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> Unit
                        response.code == 408 || response.code == 429 || response.code >= 500 -> return@withContext Result.retry()
                        response.code in 400..499 -> return@withContext Result.failure()
                        else -> return@withContext Result.retry()
                    }
                }
                db.delete(id)
            }
            prefs.lastSuccessfulSyncAt = System.currentTimeMillis()
            Result.success()
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
