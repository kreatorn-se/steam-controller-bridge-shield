package com.steamcontroller.android.hub

import com.steamcontroller.android.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Thin HTTP client for gamehub.kreatorn.se's device API. Blocking; call off the main thread. */
class GamehubClient(private val baseUrl: String, private val token: String?) {

    class HubException(val status: Int, message: String) : IOException(message)

    sealed class PutResult {
        data class Saved(val rev: Int) : PutResult()
        data class Conflict(val rev: Int, val json: String) : PutResult()
    }

    data class RemoteConfig(val rev: Int, val json: String)

    fun pair(code: String, name: String): Pair<String, String> {
        val body = JSONObject().put("code", code).put("name", name).put("appVersion", BuildConfig.VERSION_NAME)
        val (status, text) = request("POST", "/api/device/pair", json = body.toString(), auth = false)
        if (status != 200) throw HubException(status, errorOf(text) ?: "HTTP $status")
        val o = JSONObject(text)
        return o.getString("deviceId") to o.getString("token")
    }

    fun ping() {
        val (status, text) = request("GET", "/api/device/ping")
        if (status != 200) throw HubException(status, errorOf(text) ?: "HTTP $status")
    }

    /** Returns the hub's config if it is newer than [have], else null. */
    fun getConfig(have: Int, applied: Int): RemoteConfig? {
        val (status, text) = request("GET", "/api/device/config?have=$have&applied=$applied")
        return when (status) {
            204 -> null
            200 -> JSONObject(text).let { RemoteConfig(it.getInt("rev"), it.getString("json")) }
            else -> throw HubException(status, errorOf(text) ?: "HTTP $status")
        }
    }

    fun putConfig(baseRev: Int, json: String): PutResult {
        val body = JSONObject().put("baseRev", baseRev).put("json", json)
        val (status, text) = request("PUT", "/api/device/config", json = body.toString())
        return when (status) {
            200 -> PutResult.Saved(JSONObject(text).getInt("rev"))
            409 -> JSONObject(text).let { PutResult.Conflict(it.getInt("rev"), it.getString("json")) }
            else -> throw HubException(status, errorOf(text) ?: "HTTP $status")
        }
    }

    /** Uploads one capture. Returns the hub's id for it. Safe to retry: the hub dedupes on clientId. */
    fun uploadShot(full: File, preview: ByteArray?, meta: JSONObject): String {
        val boundary = "----gamehub" + UUID.randomUUID().toString().replace("-", "")
        val conn = open("POST", "/api/device/shots", auth = true)
        conn.setRequestProperty("content-type", "multipart/form-data; boundary=$boundary")
        conn.doOutput = true
        val head = ByteArrayOutputStream()
        fun part(name: String, filename: String?, type: String?) {
            head.write("--$boundary\r\n".toByteArray())
            head.write(("content-disposition: form-data; name=\"$name\"" + (filename?.let { "; filename=\"$it\"" } ?: "") + "\r\n").toByteArray())
            if (type != null) head.write("content-type: $type\r\n".toByteArray())
            head.write("\r\n".toByteArray())
        }
        part("meta", null, "application/json")
        head.write(meta.toString().toByteArray())
        head.write("\r\n".toByteArray())
        val previewHead = ByteArrayOutputStream().also { b ->
            if (preview != null) {
                b.write("--$boundary\r\ncontent-disposition: form-data; name=\"preview\"; filename=\"preview.jpg\"\r\ncontent-type: image/jpeg\r\n\r\n".toByteArray())
            }
        }.toByteArray()
        val fullHead = "--$boundary\r\ncontent-disposition: form-data; name=\"full\"; filename=\"${full.name}\"\r\ncontent-type: image/png\r\n\r\n".toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val total = head.size().toLong() + previewHead.size + (preview?.size ?: 0) + (if (preview != null) 2 else 0) +
            fullHead.size + full.length() + tail.size
        conn.setFixedLengthStreamingMode(total)
        DataOutputStream(conn.outputStream.buffered()).use { out ->
            out.write(head.toByteArray())
            if (preview != null) { out.write(previewHead); out.write(preview); out.write("\r\n".toByteArray()) }
            out.write(fullHead)
            full.inputStream().use { it.copyTo(out, 64 * 1024) }
            out.write(tail)
        }
        val status = conn.responseCode
        val text = readBody(conn)
        if (status != 200 && status != 201) throw HubException(status, errorOf(text) ?: "HTTP $status")
        return JSONObject(text).getString("id")
    }

    /** One Enshrouded save file from Steam Cloud (character before world). Safe to repeat. */
    fun putEnshFile(name: String, bytes: ByteArray) {
        val conn = open("PUT", "/api/device/ensh/files/$name", auth = true)
        conn.doOutput = true
        conn.setRequestProperty("content-type", "application/octet-stream")
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
        val status = conn.responseCode
        val text = readBody(conn)
        if (status != 200) throw HubException(status, errorOf(text) ?: "HTTP $status")
    }

    private fun request(method: String, path: String, json: String? = null, auth: Boolean = true): Pair<Int, String> {
        val conn = open(method, path, auth)
        if (json != null) {
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            val bytes = json.toByteArray()
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
        }
        val status = conn.responseCode
        return status to readBody(conn)
    }

    private fun open(method: String, path: String, auth: Boolean): HttpURLConnection {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = false // an Access login redirect means the bypass is missing
        conn.setRequestProperty("x-app-version", BuildConfig.VERSION_NAME)
        conn.setRequestProperty("accept", "application/json")
        if (auth) {
            val t = token ?: throw HubException(401, "not linked")
            conn.setRequestProperty("authorization", "Bearer $t")
        }
        return conn
    }

    private fun readBody(conn: HttpURLConnection): String = try {
        (if (conn.responseCode >= 400) conn.errorStream else conn.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
    } catch (_: IOException) {
        ""
    } finally {
        conn.disconnect()
    }

    private fun errorOf(text: String): String? = try {
        JSONObject(text).optString("error").takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}
