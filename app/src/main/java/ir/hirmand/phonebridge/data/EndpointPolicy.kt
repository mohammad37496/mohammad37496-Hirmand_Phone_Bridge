package ir.hirmand.phonebridge.data

import java.net.URI

object EndpointPolicy {
    fun isAllowed(endpoint: String): Boolean {
        return try {
            val uri = URI(endpoint.trim())
            if (uri.userInfo != null || uri.host.isNullOrBlank()) return false
            when (uri.scheme?.lowercase()) {
                "https" -> true
                "http" -> isPrivateHost(uri.host.lowercase())
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "10.0.2.2") return true
        if (host.startsWith("192.168.")) return true
        if (host.startsWith("10.")) return true
        if (host.startsWith("172.")) {
            val second = host.removePrefix("172.").substringBefore('.').toIntOrNull()
            return second != null && second in 16..31
        }
        return false
    }
}
