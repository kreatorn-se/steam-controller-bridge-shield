package com.steamcontroller.android.hub

import android.content.Context
import com.steamcontroller.android.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * The TV side of Gamehub: the device read API (`/api/device/v1/…`) with the bearer token from
 * [GamehubStore], plus an offline copy of the last home and of the first page of recently viewed
 * collections in filesDir/tv, so the TV opens instantly and still shows something offline.
 *
 * Blocking; call off the main thread. Parsing is lenient: unknown fields are ignored and missing
 * ones fall back, so a hub that is a little ahead or behind does not break the TV.
 */
object GamehubTv {

    // ── model ─────────────────────────────────────────────────────────────────────────

    data class Art(val hero: String?, val logo: String?, val grid: String?) {
        val isEmpty get() = hero == null && logo == null && grid == null
    }

    /** One capture. [favorite] and [status] change in place when the TV edits them. */
    class Shot(
        val id: String,
        val game: String?,
        val capturedAt: Long,
        val tzOffsetMin: Int,
        val width: Int,
        val height: Int,
        @Volatile var favorite: Boolean,
        val source: String?,
        @Volatile var status: String?,
        val people: List<String>,
        val note: String?,
        val previewUrl: String,
        val fullUrl: String,
        val art: Art?,
    ) {
        override fun equals(other: Any?) = other is Shot && other.id == id
        override fun hashCode() = id.hashCode()
    }

    /** A collection tile: a game, album, person or session, opened as `collection` in show mode. */
    data class Coll(
        val collection: String,
        val title: String,
        val count: Int,
        val cover: Shot?,
        val art: Art?,
        val subtitle: String?,
        /** A person's picture (public Steam CDN URL), when there is one. */
        val avatar: String? = null,
    )

    data class Row(
        val id: String,
        val title: String,
        val kind: String,
        val count: Int,
        val cover: Shot?,
        /** The collection the row's captures come from (null for rows of tiles). */
        val collection: String?,
        val shots: List<Shot>,
        val colls: List<Coll>,
    )

    data class NowPlaying(val game: String, val appid: Long, val since: Long, val art: Art?)

    data class Home(val nowPlaying: NowPlaying?, val hero: List<Shot>, val rows: List<Row>, val fetchedAt: Long)

    data class ShotPage(val items: List<Shot>, val next: String?)

    data class Saver(val collection: String, val intervalSec: Int, val shuffle: Boolean)

    enum class Fail { NOT_LINKED, NOT_DEPLOYED, UNAUTHORIZED, OFFLINE, SERVER }

    sealed class Res<out T> {
        data class Ok<T>(val value: T) : Res<T>()
        data class Err(val fail: Fail, val message: String) : Res<Nothing>()

        fun valueOrNull(): T? = (this as? Ok<T>)?.value
    }

    /** Collections the API knows by a fixed name. */
    private val FIXED = setOf("recent", "favourites", "favorites", "patina", "inbox")

    // ── endpoints ─────────────────────────────────────────────────────────────────────

    fun home(c: Context): Res<Home> {
        val r = get(c, "/api/device/v1/home")
        if (r is Res.Ok) {
            val home = try { parseHome(JSONObject(r.value)) } catch (e: Exception) { return Res.Err(Fail.SERVER, "bad home: ${e.message}") }
            writeCache(c, "home", r.value)
            return Res.Ok(home)
        }
        return r as Res.Err
    }

    fun cachedHome(c: Context): Home? = readCache(c, "home")?.let { runCatching { parseHome(JSONObject(it)) }.getOrNull() }

    fun shots(c: Context, collection: String, before: String? = null, limit: Int = 60): Res<ShotPage> {
        val q = StringBuilder("/api/device/v1/shots?c=").append(enc(collection)).append("&limit=").append(limit)
        if (before != null) q.append("&before=").append(enc(before))
        val r = get(c, q.toString())
        if (r is Res.Ok) {
            val page = try { parsePage(JSONObject(r.value)) } catch (e: Exception) { return Res.Err(Fail.SERVER, "bad page: ${e.message}") }
            if (before == null) writeCache(c, "c-" + sha1(collection), r.value)
            return Res.Ok(page)
        }
        return r as Res.Err
    }

    /** The first page of [collection] as last seen, or null. */
    fun cachedShots(c: Context, collection: String): ShotPage? =
        readCache(c, "c-" + sha1(collection))?.let { runCatching { parsePage(JSONObject(it)) }.getOrNull() }

    fun setShot(c: Context, id: String, favorite: Boolean? = null, status: String? = null): Res<Unit> {
        val body = JSONObject()
        if (favorite != null) body.put("favorite", favorite)
        if (status != null) body.put("status", status)
        val r = send(c, "POST", "/api/device/v1/shots/" + enc(id), body.toString())
        return if (r is Res.Ok) Res.Ok(Unit) else r as Res.Err
    }

    /** [game] null keeps the unnamed captures. */
    fun keepAll(c: Context, game: String?): Res<Unit> {
        val r = send(c, "POST", "/api/device/v1/inbox/keep-all", JSONObject().put("game", game ?: JSONObject.NULL).toString())
        return if (r is Res.Ok) Res.Ok(Unit) else r as Res.Err
    }

    fun games(c: Context): Res<List<Coll>> = list(c, "games") { o -> collOf(o, "game") }
    fun albums(c: Context): Res<List<Coll>> = list(c, "albums") { o -> collOf(o, "album") }
    fun people(c: Context): Res<List<Coll>> = list(c, "people") { o -> collOf(o, "person") }

    fun cachedList(c: Context, what: String): List<Coll>? {
        val kind = when (what) { "games" -> "game"; "albums" -> "album"; else -> "person" }
        val text = readCache(c, "l-$what") ?: return null
        return runCatching { parseList(text, what) { o -> collOf(o, kind) } }.getOrNull()
    }

    fun screensaver(c: Context): Res<Saver> {
        val r = get(c, "/api/device/v1/screensaver")
        if (r is Res.Ok) {
            val s = try { parseSaver(JSONObject(r.value)) } catch (e: Exception) { return Res.Err(Fail.SERVER, "bad screensaver: ${e.message}") }
            writeCache(c, "saver", r.value)
            return Res.Ok(s)
        }
        return r as Res.Err
    }

    fun cachedSaver(c: Context): Saver? = readCache(c, "saver")?.let { runCatching { parseSaver(JSONObject(it)) }.getOrNull() }

    private fun parseSaver(o: JSONObject) = Saver(
        collection = o.optString("collection").ifBlank { "favourites" },
        intervalSec = o.optInt("intervalSec", 20).coerceIn(5, 600),
        shuffle = o.optBoolean("shuffle", true),
    )

    private fun list(c: Context, what: String, f: (JSONObject) -> Coll?): Res<List<Coll>> {
        val r = get(c, "/api/device/v1/$what")
        if (r is Res.Ok) {
            val items = try { parseList(r.value, what, f) } catch (e: Exception) { return Res.Err(Fail.SERVER, "bad $what: ${e.message}") }
            writeCache(c, "l-$what", r.value)
            return Res.Ok(items)
        }
        return r as Res.Err
    }

    private fun parseList(text: String, what: String, f: (JSONObject) -> Coll?): List<Coll> {
        val t = text.trim()
        val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).let { it.optJSONArray("items") ?: it.optJSONArray(what) ?: JSONArray() }
        val out = ArrayList<Coll>(arr.length())
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(f)?.let(out::add)
        return out
    }

    // ── URLs and images ───────────────────────────────────────────────────────────────

    fun base(c: Context): String = GamehubStore.url(c).trimEnd('/')

    /** The hub's URLs are relative; images need the bearer token too ([download]). */
    fun abs(c: Context, url: String): String =
        if (url.startsWith("http://") || url.startsWith("https://")) url else base(c) + (if (url.startsWith("/")) url else "/$url")

    /**
     * Streams [url] (relative or absolute) to [dest]. The device token goes only to the hub itself,
     * never to another host (people's avatars come from Steam's CDN). False on any failure.
     */
    fun download(c: Context, url: String, dest: File): Boolean {
        val tmp = File(dest.parentFile, dest.name + ".part")
        return try {
            val full = abs(c, url)
            val own = full.startsWith(base(c) + "/")
            val conn = (if (own) open(c, "GET", full) else openPublic(full)) ?: return false
            conn.readTimeout = 45_000
            conn.setRequestProperty("accept", "image/*")
            try {
                if (conn.responseCode != 200) return false
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
            } finally {
                conn.disconnect()
            }
            tmp.renameTo(dest)
        } catch (_: Throwable) {
            tmp.delete()
            false
        }
    }

    // ── parsing ───────────────────────────────────────────────────────────────────────

    private fun str(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotBlank() }

    private fun artOf(o: JSONObject?): Art? {
        if (o == null) return null
        val a = Art(str(o, "hero"), str(o, "logo"), str(o, "grid"))
        return if (a.isEmpty) null else a
    }

    fun shotOf(o: JSONObject): Shot? {
        val id = str(o, "id") ?: return null
        val preview = str(o, "previewUrl") ?: "/api/device/v1/img/$id/preview"
        val full = str(o, "fullUrl") ?: "/api/device/v1/img/$id/full"
        val people = ArrayList<String>()
        o.optJSONArray("people")?.let { a ->
            for (i in 0 until a.length()) {
                val p = a.opt(i)
                val name = when (p) {
                    is String -> p
                    is JSONObject -> str(p, "name")
                    else -> null
                }
                if (!name.isNullOrBlank()) people.add(name)
            }
        }
        return Shot(
            id = id,
            game = str(o, "game"),
            capturedAt = o.optLong("capturedAt", 0L),
            tzOffsetMin = o.optInt("tzOffsetMin", 0),
            width = o.optInt("width", 0),
            height = o.optInt("height", 0),
            favorite = o.optBoolean("favorite", false) || o.optInt("favorite", 0) == 1,
            source = str(o, "source"),
            status = str(o, "status"),
            people = people,
            note = str(o, "note"),
            previewUrl = preview,
            fullUrl = full,
            art = artOf(o.optJSONObject("art")),
        )
    }

    private fun shotsOf(a: JSONArray?): List<Shot> {
        if (a == null) return emptyList()
        val out = ArrayList<Shot>(a.length())
        for (i in 0 until a.length()) a.optJSONObject(i)?.let(::shotOf)?.let(out::add)
        return out
    }

    /** A tile; [kind] is the collection prefix ("game", "album", "person", "session"). */
    private fun collOf(o: JSONObject, kind: String): Coll? {
        val name = str(o, "name") ?: str(o, "title") ?: str(o, "game")
        val id = str(o, "id") ?: str(o, "key")
        val collection = str(o, "collection") ?: when (kind) {
            "game" -> name?.let { "game:$it" }
            else -> (id ?: name)?.let { "$kind:$it" }
        } ?: return null
        val cover = o.optJSONObject("cover")?.let(::shotOf)
        return Coll(
            collection = collection,
            title = name ?: id ?: collection,
            count = o.optInt("count", 0),
            cover = cover,
            art = artOf(o.optJSONObject("art")) ?: cover?.art,
            subtitle = str(o, "subtitle"),
            avatar = str(o, "avatar"),
        )
    }

    private fun kindPrefix(kind: String): String = when (kind) {
        "games", "game" -> "game"
        "albums", "album" -> "album"
        "people", "person" -> "person"
        "sessions", "session" -> "session"
        else -> kind.trimEnd('s')
    }

    private fun parseRow(o: JSONObject): Row? {
        val id = str(o, "id") ?: return null
        val kind = str(o, "kind") ?: id
        val arr = o.optJSONArray("items") ?: JSONArray()
        val shots = ArrayList<Shot>()
        val colls = ArrayList<Coll>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            // A capture carries its image URLs; anything else is a tile that opens a collection.
            if (it.has("previewUrl") || it.has("capturedAt")) shotOf(it)?.let(shots::add)
            else collOf(it, kindPrefix(kind))?.let(colls::add)
        }
        val collection = str(o, "collection") ?: when {
            colls.isNotEmpty() && shots.isEmpty() -> null
            id in FIXED || id.contains(':') -> id
            kind in FIXED -> kind
            else -> id
        }
        return Row(
            id = id,
            title = str(o, "title") ?: id,
            kind = kind,
            count = o.optInt("count", shots.size + colls.size),
            cover = o.optJSONObject("cover")?.let(::shotOf),
            collection = collection,
            shots = shots,
            colls = colls,
        )
    }

    private fun parseHome(o: JSONObject): Home {
        val np = o.optJSONObject("nowPlaying")?.let { n ->
            val game = str(n, "game") ?: return@let null
            NowPlaying(game, n.optLong("appid", 0L), sinceOf(n), artOf(n.optJSONObject("art")))
        }
        val rows = ArrayList<Row>()
        o.optJSONArray("rows")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(::parseRow)?.let { r -> if (r.shots.isNotEmpty() || r.colls.isNotEmpty()) rows.add(r) } }
        return Home(np, shotsOf(o.optJSONArray("hero")), rows, System.currentTimeMillis())
    }

    /** `since` in epoch ms; seconds are accepted too. */
    private fun sinceOf(n: JSONObject): Long {
        val v = n.optLong("since", 0L)
        return if (v in 1 until 100_000_000_000L) v * 1000L else v
    }

    private fun parsePage(o: JSONObject) = ShotPage(shotsOf(o.optJSONArray("items")), str(o, "next"))

    // ── HTTP ──────────────────────────────────────────────────────────────────────────

    private fun get(c: Context, path: String): Res<String> = send(c, "GET", path, null)

    private fun send(c: Context, method: String, path: String, json: String?): Res<String> {
        if (GamehubStore.token(c) == null) return Res.Err(Fail.NOT_LINKED, "not linked")
        return try {
            val conn = open(c, method, base(c) + path) ?: return Res.Err(Fail.NOT_LINKED, "not linked")
            try {
                if (json != null) {
                    val bytes = json.toByteArray()
                    conn.doOutput = true
                    conn.setRequestProperty("content-type", "application/json")
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { it.write(bytes) }
                }
                val status = conn.responseCode
                val text = try {
                    (if (status >= 400) conn.errorStream else conn.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: IOException) { "" }
                when {
                    status in 200..299 -> Res.Ok(text)
                    // A 404 on the read API means a hub from before the TV app; a redirect means
                    // Cloudflare Access is not letting /api/device/v1 through yet. Both: update Gamehub.
                    status == 404 && method == "GET" -> Res.Err(Fail.NOT_DEPLOYED, "HTTP 404")
                    status in 300..399 -> Res.Err(Fail.NOT_DEPLOYED, "HTTP $status (Access)")
                    status == 401 || status == 403 -> Res.Err(Fail.UNAUTHORIZED, errorOf(text) ?: "HTTP $status")
                    else -> Res.Err(Fail.SERVER, errorOf(text) ?: "HTTP $status")
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            Res.Err(Fail.OFFLINE, e.message ?: "offline")
        } catch (e: Exception) {
            Res.Err(Fail.SERVER, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun open(c: Context, method: String, url: String): HttpURLConnection? {
        val token = GamehubStore.token(c) ?: return null
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("x-app-version", BuildConfig.VERSION_NAME)
        conn.setRequestProperty("accept", "application/json")
        conn.setRequestProperty("authorization", "Bearer $token")
        return conn
    }

    private fun openPublic(url: String): HttpURLConnection? {
        if (!url.startsWith("https://")) return null
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        return conn
    }

    private fun errorOf(text: String): String? = try {
        JSONObject(text).optString("error").takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ── offline cache (filesDir/tv) ───────────────────────────────────────────────────

    private const val MAX_CACHED_FILES = 48

    private fun dir(c: Context) = File(c.filesDir, "tv").apply { mkdirs() }

    private fun writeCache(c: Context, name: String, text: String) {
        try {
            val d = dir(c)
            val tmp = File(d, "$name.tmp")
            tmp.writeText(text)
            tmp.renameTo(File(d, "$name.json"))
            val files = d.listFiles { f -> f.name.startsWith("c-") && f.name.endsWith(".json") } ?: return
            if (files.size > MAX_CACHED_FILES) files.sortedBy { it.lastModified() }.take(files.size - MAX_CACHED_FILES).forEach { it.delete() }
        } catch (_: Throwable) {
        }
    }

    private fun readCache(c: Context, name: String): String? = try {
        File(dir(c), "$name.json").takeIf { it.isFile }?.readText()
    } catch (_: Throwable) {
        null
    }

    /** Forget everything cached (unlink). */
    fun clearCache(c: Context) {
        try { dir(c).listFiles()?.forEach { it.delete() } } catch (_: Throwable) {}
    }

    fun sha1(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append(String.format("%02x", b.toInt() and 0xFF))
        return sb.toString()
    }
}
