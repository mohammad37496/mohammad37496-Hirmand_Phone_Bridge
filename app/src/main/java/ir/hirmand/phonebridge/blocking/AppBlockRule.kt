package ir.hirmand.phonebridge.blocking

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

data class AppBlockRule(
    val id: String,
    val packageName: String,
    val label: String,
    val enabled: Boolean,
    val days: Set<Int>,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val message: String,
) {
    fun isActive(now: ZonedDateTime): Boolean {
        if (!enabled) return false

        val today = now.toLocalDate()
        if (startDate != null && today.isBefore(startDate)) return false
        if (endDate != null && today.isAfter(endDate)) return false

        val day = (now.dayOfWeek.value + 1) % 7 // Saturday=0 ... Friday=6
        val previousDay = (day + 6) % 7
        val time = now.toLocalTime().withSecond(0).withNano(0)

        if (startTime == endTime) return day in days
        if (startTime < endTime) {
            return day in days && !time.isBefore(startTime) && time.isBefore(endTime)
        }

        return (day in days && !time.isBefore(startTime)) ||
            (previousDay in days && time.isBefore(endTime))
    }

    companion object {
        fun fromJson(value: JSONObject): AppBlockRule? {
            val id = value.optString("id").trim()
            val packageName = value.optString("packageName").trim()
            if (id.isBlank() || packageName.isBlank()) return null

            val daysArray = value.optJSONArray("days") ?: JSONArray()
            val days = buildSet {
                for (i in 0 until daysArray.length()) {
                    val day = daysArray.optInt(i, -1)
                    if (day in 0..6) add(day)
                }
            }
            if (days.isEmpty()) return null

            val start = runCatching { LocalTime.parse(value.optString("startTime")) }.getOrNull() ?: return null
            val end = runCatching { LocalTime.parse(value.optString("endTime")) }.getOrNull() ?: return null
            val startDate = value.optString("startDate").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val endDate = value.optString("endDate").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

            return AppBlockRule(
                id = id,
                packageName = packageName,
                label = value.optString("label", packageName).ifBlank { packageName },
                enabled = value.optBoolean("enabled", false),
                days = days,
                startTime = start,
                endTime = end,
                startDate = startDate,
                endDate = endDate,
                message = value.optString("message").trim().take(1000),
            )
        }
    }
}
