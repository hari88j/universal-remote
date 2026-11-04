package studio.zavi.uniremote.core.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import javax.net.ssl.HttpsURLConnection

class HttpStatusException(val code: Int, message: String) : IOException(message)

/** Minimal blocking HTTP helpers for the TVs' local REST APIs. Call from Dispatchers.IO. */
object Http {
    fun get(url: String, timeoutMs: Int = 4000): String = request("GET", url, null, null, timeoutMs)

    fun post(url: String, body: String = "", contentType: String? = null, timeoutMs: Int = 4000): String =
        request("POST", url, body, contentType, timeoutMs)

    private fun request(method: String, url: String, body: String?, contentType: String?, timeoutMs: Int): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = Tls.trustAllContext.socketFactory
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code !in 200..299) throw HttpStatusException(code, "HTTP $code from $url")
            return text
        } finally {
            conn.disconnect()
        }
    }
}
