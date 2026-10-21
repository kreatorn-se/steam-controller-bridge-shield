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
        @Volatile var people: List<String>,
        /** Ids of [people], same order (people are tagged by id). */
        @Volatile var peopleIds: List<String> = emptyList(),
        /** Keywords (round 5), sorted by name; empty on a hub without them. */
        @Volatile var keywords: List<String> = emptyList(),
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
        /** Games: when last played or captured (epoch ms) and Steam playtime, for sorting. */
        val last: Long = 0L,
        val playtimeMin: Int = 0,
    )

    data class Achievement(val name: String, val icon: String?, val unlockedAt: Long, val rarity: Double?)

    data class SteamStats(
        val appid: Long,
        val playtimeMin: Int,
        val last2wMin: Int,
        val lastPlayed: Long,
        val achUnlocked: Int,
        val achTotal: Int,
        val recent: List<Achievement>,
        val rarest: List<Achievement>,
    )

    data class SessionInfo(val key: String, val start: Long, val end: Long, val count: Int)

    /** Everything the game hub shows above its captures (GET games/<name>/hub). */
    data class GameHub(
        val game: String,
        val art: Art?,
        /** The header picture: official hero art by default, or a capture Kent chose. */
        val backgroundUrl: String?,
        val backgroundShotId: String?,
        val captures: Int,
        val favourites: Int,
        val sessions: Int,
        val firstAt: Long,
        val lastAt: Long,
        val steam: SteamStats?,
        val years: List<Int>,
        val sources: List<String>,
        val sessionList: List<SessionInfo>,
        /** Keywords on this game's kept captures, most used first (round 5; empty before). */
        val keywords: List<KeywordCount> = emptyList(),
    )

    data class KeywordCount(val name: String, val count: Int)

    /** A keyword from GET /keywords: presets first, in the hub's order. */
    data class Keyword(val name: String, val count: Int, val preset: Boolean)

    /** Someone who can be tagged (GET /people?all=1). */
    data class Person(val id: String, val name: String)

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

    /**
     * [GONE]: a write's target (shot, album, game) is not there any more; refresh and retry.
     * [BUSY]: 429, too many requests (the import's Steam budget, the diag cap): back off.
     */
    enum class Fail { NOT_LINKED, NOT_DEPLOYED, UNAUTHORIZED, OFFLINE, SERVER, GONE, BUSY }

    sealed class Res<out T> {
        data class Ok<T>(val value: T) : Res<T>()
        data class Err(val fail: Fail, val message: String) : Res<Nothing>()

        fun valueOrNull(): T? = (this as? Ok<T>)?.value
    }

    /** Collections the API knows by a fixed name. */
    private val FIXED = setOf("recent", "favourites", "favorites", "patina")

    // ── endpoints ─────────────────────────────────────────────────────────────────────

    /** Network vs parse time of the last [home] call, for the home timing log. */
    @Volatile var lastHomeTiming = ""

    fun home(c: Context): Res<Home> {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val r = get(c, "/api/device/v1/home")
        val t1 = android.os.SystemClock.elapsedRealtime()
        if (r is Res.Ok) {
            val home = try { parseHome(JSONObject(r.value)) } catch (e: Exception) { return Res.Err(Fail.SERVER, "bad home: ${e.message}") }
            lastHomeTiming = "net=${t1 - t0}ms parse=${android.os.SystemClock.elapsedRealtime() - t1}ms bytes=${r.value.length}"
            writeCache(c, "home", r.value)
            return Res.Ok(home)
        }
        return r as Res.Err
    }

    fun cachedHome(c: Context): Home? = readCache(c, "home")?.let { runCatching { parseHome(JSONObject(it)) }.getOrNull() }

    /**
     * [filters]: optional `fav=1`, `year=`, `source=`, `session=` (game collections). [kw]: keywords
     * a capture must all have (`kw=` repeated, round 5).
     */
    fun shots(c: Context, collection: String, before: String? = null, limit: Int = 60, filters: Map<String, String> = emptyMap(), kw: List<String> = emptyList()): Res<ShotPage> {
        val q = StringBuilder("/api/device/v1/shots?c=").append(enc(collection)).append("&limit=").append(limit)
        if (before != null) q.append("&before=").append(enc(before))
        for ((k, v) in filters.toSortedMap()) q.append('&').append(enc(k)).append('=').append(enc(v))
        for (k in kwKey(kw)) q.append("&kw=").append(enc(k))
        val r = get(c, q.toString())
        if (r is Res.Ok) {
            val page = try { parsePage(JSONObject(r.value)) } catch (e: Exception) { return Res.Err(Fail.SERVER, "bad page: ${e.message}") }
            if (before == null) writeCache(c, "c-" + sha1(cacheKey(collection, filters, kw)), r.value)
            return Res.Ok(page)
        }
        return r as Res.Err
    }

    /** Keyword names compare case-insensitively on the hub: one spelling, one order. */
    private fun kwKey(kw: List<String>) = kw.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

    private fun cacheKey(collection: String, filters: Map<String, String>, kw: List<String> = emptyList()): String {
        val parts = filters.toSortedMap().entries.map { it.key + "=" + it.value } + kwKey(kw).map { "kw=" + it.lowercase() }
        return if (parts.isEmpty()) collection else collection + "?" + parts.joinToString("&")
    }

    /** The first page of [collection] as last seen, or null. */
    fun cachedShots(c: Context, collection: String, filters: Map<String, String> = emptyMap(), kw: List<String> = emptyList()): ShotPage? =
        readCache(c, "c-" + sha1(cacheKey(collection, filters, kw)))?.let { runCatching { parsePage(JSONObject(it)) }.getOrNull() }

    // ── game hub and library edits (gamehub branch tv-hub) ────────────────────────────

    private fun seg(s: String) = enc(s)

    fun gameHub(c: Context, game: String): Res<GameHub> {
        val r = get(c, "/api/device/v1/games/" + seg(game) + "/hub")
        if (r is Res.Ok) {
            val h = try { parseGameHub(JSONObject(r.value), game) } catch (e: Exception) { return Res.Err(Fail.SERVER, "bad hub: ${e.message}") }
            writeCache(c, "h-" + sha1(game), r.value)
            return Res.Ok(h)
        }
        return r as Res.Err
    }

    fun cachedGameHub(c: Context, game: String): GameHub? =
        readCache(c, "h-" + sha1(game))?.let { runCatching { parseGameHub(JSONObject(it), game) }.getOrNull() }

    /** Every game, sorted by the hub (recent | captures | playtime | name). */
    fun gamesSorted(c: Context, sort: String): Res<List<Coll>> {
        val r = get(c, "/api/device/v1/games?sort=" + enc(sort))
        if (r is Res.Ok) {
            val items = try { parseList(r.value, "games") { o -> collOf(o, "game") } } catch (e: Exception) { return Res.Err(Fail.SERVER, "bad games: ${e.message}") }
            writeCache(c, "l-games-$sort", r.value)
            return Res.Ok(items)
        }
        return r as Res.Err
    }

    fun cachedGamesSorted(c: Context, sort: String): List<Coll>? =
        readCache(c, "l-games-$sort")?.let { t -> runCatching { parseList(t, "games") { o -> collOf(o, "game") } }.getOrNull() }

    /** [shotId] null goes back to the official hero art. */
    fun setBackground(c: Context, game: String, shotId: String?): Res<Unit> {
        val r = send(c, "POST", "/api/device/v1/games/" + seg(game) + "/background", JSONObject().put("shotId", shotId ?: JSONObject.NULL).toString())
        return if (r is Res.Ok) Res.Ok(Unit) else r as Res.Err
    }

    /** Returns the new album's id. */
    fun createAlbum(c: Context, name: String): Res<String> {
        val r = send(c, "POST", "/api/device/v1/albums", JSONObject().put("name", name).toString())
        if (r !is Res.Ok) return r as Res.Err
        val o = runCatching { JSONObject(r.value) }.getOrNull()
        val id = o?.let { str(it, "id") ?: it.optJSONObject("album")?.let { a -> str(a, "id") } } ?: return Res.Err(Fail.SERVER, "no album id")
        return Res.Ok(id)
    }

    fun addToAlbum(c: Context, albumId: String, shotIds: List<String>): Res<Unit> {
        val r = send(c, "POST", "/api/device/v1/albums/" + seg(albumId) + "/shots", JSONObject().put("add", JSONArray(shotIds)).toString())
        return if (r is Res.Ok) Res.Ok(Unit) else r as Res.Err
    }

    /** Tags by person id (from /people); returns the updated capture when the hub sends it. */
    fun setPeople(c: Context, shotId: String, add: List<String>, remove: List<String>): Res<Shot?> {
        val body = JSONObject().put("add", JSONArray(add)).put("remove", JSONArray(remove))
        val r = send(c, "POST", "/api/device/v1/shots/" + seg(shotId) + "/people", body.toString())
        if (r !is Res.Ok) return r as Res.Err
        return Res.Ok(runCatching { shotOf(JSONObject(r.value)) }.getOrNull())
    }

    /** Everyone who can be tagged, newest tagged first (GET /people?all=1); not cached. */
    fun peopleAll(c: Context): Res<List<Person>> {
        val r = get(c, "/api/device/v1/people?all=1")
        if (r !is Res.Ok) return r as Res.Err
        return try {
            val t = r.value.trim()
            val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).let { it.optJSONArray("people") ?: it.optJSONArray("items") ?: JSONArray() }
            val out = ArrayList<Person>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = str(o, "id") ?: continue
                out.add(Person(id, str(o, "name") ?: id))
            }
            Res.Ok(out)
        } catch (e: Exception) {
            Res.Err(Fail.SERVER, "bad people: ${e.message}")
        }
    }

    /** Every keyword (GET /keywords, a bare array): presets first, then by use. */
    fun keywords(c: Context): Res<List<Keyword>> {
        val r = get(c, "/api/device/v1/keywords")
        if (r !is Res.Ok) return r as Res.Err
        return try {
            val t = r.value.trim()
            val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).let { it.optJSONArray("keywords") ?: it.optJSONArray("items") ?: JSONArray() }
            val out = ArrayList<Keyword>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = str(o, "name") ?: continue
                out.add(Keyword(name, o.optInt("count", 0), o.optBoolean("preset", false)))
            }
            writeCache(c, "keywords", t)
            Res.Ok(out)
        } catch (e: Exception) {
            Res.Err(Fail.SERVER, "bad keywords: ${e.message}")
        }
    }

    /** POST /shots/<id>/keywords; the updated capture when the hub sends it. */
    fun setKeywords(c: Context, shotId: String, add: List<String>, remove: List<String>): Res<Shot?> {
        val body = JSONObject().put("add", JSONArray(add)).put("remove", JSONArray(remove))
        val r = send(c, "POST", "/api/device/v1/shots/" + seg(shotId) + "/keywords", body.toString())
        if (r !is Res.Ok) return r as Res.Err
        return Res.Ok(runCatching { shotOf(JSONObject(r.value)) }.getOrNull())
    }

    // ── import (round 5) ──────────────────────────────────────────────────────────────

    data class ImportProgress(val done: Int, val total: Int, val etaSec: Int?) {
        val running get() = total > done
    }

    data class ImportGame(
        val appid: Long,
        val name: String,
        val cover: String?,
        val logo: String?,
        val total: Int,
        val imported: Int,
        val queued: Int,
        val failed: Int,
        val importing: Boolean,
        val last: Long,
    )

    data class ImportScan(val running: Boolean, val lastDone: Long, val total: Int, val error: String?)

    data class ImportState(
        val enabled: Boolean,
        val reason: String?,
        val scan: ImportScan,
        val progress: ImportProgress,
        val games: List<ImportGame>,
    )

    /** One batch's answer: [claimed] 0 means nothing was ready; [stopped] is Steam pushing back. */
    data class ImportBatch(
        val imported: Int,
        val claimed: Int,
        val failed: Int,
        val stopped: String?,
        val progress: ImportProgress?,
        val games: List<ImportGame>,
    )

    private fun progressOf(o: JSONObject?): ImportProgress? {
        if (o == null) return null
        val eta = if (o.has("etaSec") && !o.isNull("etaSec")) o.optInt("etaSec", -1).takeIf { it >= 0 } else null
        return ImportProgress(o.optInt("done", 0), o.optInt("total", 0), eta)
    }

    private fun importGameOf(o: JSONObject, old: ImportGame? = null): ImportGame? {
        val appid = o.optLong("appid", 0L)
        if (appid <= 0L) return null
        fun int(k: String, d: Int) = if (o.has(k) && !o.isNull(k)) o.optInt(k, d) else d
        return ImportGame(
            appid = appid,
            name = str(o, "name") ?: old?.name ?: str(o, "game") ?: "App $appid",
            cover = str(o, "cover") ?: old?.cover,
            logo = str(o, "logo") ?: old?.logo,
            total = int("total", old?.total ?: 0),
            imported = int("imported", old?.imported ?: 0),
            queued = int("queued", old?.queued ?: 0),
            failed = int("failed", old?.failed ?: 0),
            importing = if (o.has("importing") && !o.isNull("importing")) o.optBoolean("importing") else old?.importing ?: false,
            last = if (o.has("last") && !o.isNull("last")) o.optLong("last", 0L) else old?.last ?: 0L,
        )
    }

    /** Fresh counts from a batch merged into [old] (a batch answer carries only some fields). */
    fun mergeImportGame(old: ImportGame, fresh: ImportGame): ImportGame = old.copy(
        total = fresh.total, imported = fresh.imported, queued = fresh.queued, failed = fresh.failed, importing = fresh.importing,
    )

    private fun importGamesOf(a: JSONArray?): List<ImportGame> {
        if (a == null) return emptyList()
        val out = ArrayList<ImportGame>(a.length())
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { importGameOf(it) }?.let(out::add)
        return out
    }

    fun importState(c: Context): Res<ImportState> {
        val r = get(c, "/api/device/v1/import")
        if (r !is Res.Ok) return r as Res.Err
        return try {
            val o = JSONObject(r.value)
            val sc = o.optJSONObject("scan")
            Res.Ok(ImportState(
                enabled = o.optBoolean("enabled", true),
                reason = str(o, "reason"),
                scan = ImportScan(sc?.optBoolean("running", false) ?: false, sc?.optLong("lastDone", 0L) ?: 0L, sc?.optInt("total", 0) ?: 0, sc?.let { str(it, "error") }),
                progress = progressOf(o.optJSONObject("progress")) ?: ImportProgress(0, 0, null),
                games = importGamesOf(o.optJSONArray("games")),
            ))
        } catch (e: Exception) {
            Res.Err(Fail.SERVER, "bad import: ${e.message}")
        }
    }

    /** Switches one game's import on or off; returns the run's progress. */
    fun setImport(c: Context, appid: Long, on: Boolean): Res<ImportProgress?> {
        val r = send(c, "POST", "/api/device/v1/import/games/$appid", JSONObject().put("import", on).toString())
        if (r !is Res.Ok) return r as Res.Err
        return Res.Ok(runCatching { progressOf(JSONObject(r.value).optJSONObject("progress")) }.getOrNull())
    }

    /** Import all (or, with [on] false, switch every game off). */
    fun importAll(c: Context, on: Boolean = true): Res<ImportProgress?> {
        val body = if (on) JSONObject() else JSONObject().put("import", false)
        val r = send(c, "POST", "/api/device/v1/import/all", body.toString(), routeOnly = true)
        if (r !is Res.Ok) return r as Res.Err
        return Res.Ok(runCatching { progressOf(JSONObject(r.value).optJSONObject("progress")) }.getOrNull())
    }

    /** Starts (or keeps reading) the inventory scan; the scan state after this call. */
    fun importScan(c: Context): Res<ImportScan?> {
        val r = send(c, "POST", "/api/device/v1/import/scan", JSONObject().toString(), routeOnly = true)
        if (r !is Res.Ok) return r as Res.Err
        return Res.Ok(runCatching {
            JSONObject(r.value).optJSONObject("scan")?.let { sc -> ImportScan(sc.optBoolean("running", false), sc.optLong("lastDone", 0L), sc.optInt("total", 0), str(sc, "error")) }
        }.getOrNull())
    }

    /** Imports the next few queued files now (one call at a time; each takes a few seconds). */
    fun importBatch(c: Context): Res<ImportBatch> {
        val r = send(c, "POST", "/api/device/v1/import/batch", JSONObject().toString(), routeOnly = true, readTimeoutMs = 60_000)
        if (r !is Res.Ok) return r as Res.Err
        return try {
            val o = JSONObject(r.value)
            Res.Ok(ImportBatch(
                imported = o.optInt("imported", 0),
                claimed = o.optInt("claimed", 0),
                failed = o.optInt("failed", 0),
                stopped = str(o, "stopped"),
                progress = progressOf(o.optJSONObject("progress")),
                games = importGamesOf(o.optJSONArray("games")),
            ))
        } catch (e: Exception) {
            Res.Err(Fail.SERVER, "bad batch: ${e.message}")
        }
    }

    private fun achievementsOf(a: JSONArray?): List<Achievement> {
        if (a == null) return emptyList()
        val out = ArrayList<Achievement>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val name = str(o, "name") ?: str(o, "displayName") ?: continue
            out.add(Achievement(name, str(o, "icon"), msOf(o.optLong("unlockedAt", 0L)), if (o.has("rarity") && !o.isNull("rarity")) o.optDouble("rarity") else null))
        }
        return out
    }

    /** Epoch ms; seconds (as Steam sends them) are accepted too. */
    private fun msOf(v: Long) = if (v in 1 until 100_000_000_000L) v * 1000L else v

    private fun parseGameHub(o: JSONObject, fallbackName: String): GameHub {
        val stats = o.optJSONObject("stats")
        val bg = o.optJSONObject("background")
        val art = artOf(o.optJSONObject("art"))
        val steam = o.optJSONObject("steam")?.let { st ->
            val ach = st.optJSONObject("achievements")
            SteamStats(
                appid = st.optLong("appid", 0L),
                playtimeMin = st.optInt("playtimeMin", 0),
                last2wMin = st.optInt("last2wMin", 0),
                lastPlayed = msOf(st.optLong("lastPlayed", 0L)),
                achUnlocked = ach?.optInt("unlocked", 0) ?: 0,
                achTotal = ach?.optInt("total", 0) ?: 0,
                recent = achievementsOf(ach?.optJSONArray("recent")),
                rarest = achievementsOf(ach?.optJSONArray("rarest")),
            )
        }
        val f = o.optJSONObject("filters")
        val years = ArrayList<Int>()
        f?.optJSONArray("years")?.let { a -> for (i in 0 until a.length()) a.optInt(i, 0).takeIf { it > 0 }?.let(years::add) }
        val sources = ArrayList<String>()
        f?.optJSONArray("sources")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let(sources::add) }
        val keywords = ArrayList<KeywordCount>()
        f?.optJSONArray("keywords")?.let { a ->
            for (i in 0 until a.length()) {
                val k = a.opt(i)
                when (k) {
                    is JSONObject -> str(k, "name")?.let { keywords.add(KeywordCount(it, k.optInt("count", 0))) }
                    is String -> if (k.isNotBlank()) keywords.add(KeywordCount(k, 0))
                }
            }
        }
        val sessions = ArrayList<SessionInfo>()
        f?.optJSONArray("sessions")?.let { a ->
            for (i in 0 until a.length()) {
                val s = a.optJSONObject(i) ?: continue
                val key = str(s, "key") ?: continue
                sessions.add(SessionInfo(key, s.optLong("start", 0L), s.optLong("end", 0L), s.optInt("count", 0)))
            }
        }
        return GameHub(
            game = str(o, "game") ?: fallbackName,
            art = art,
            backgroundUrl = bg?.let { str(it, "url") } ?: art?.hero,
            backgroundShotId = bg?.let { str(it, "shotId") },
            captures = stats?.optInt("captures", 0) ?: 0,
            favourites = stats?.optInt("favourites", 0) ?: 0,
            sessions = stats?.optInt("sessions", sessions.size) ?: sessions.size,
            firstAt = stats?.optLong("firstAt", 0L) ?: 0L,
            lastAt = stats?.optLong("lastAt", 0L) ?: 0L,
            steam = steam,
            years = years,
            sources = sources,
            sessionList = sessions,
            keywords = keywords,
        )
    }

    fun postDiag(c: Context, json: String): Res<Unit> {
        val r = send(c, "POST", "/api/device/v1/diag", json, routeOnly = true)
        return if (r is Res.Ok) Res.Ok(Unit) else r as Res.Err
    }

    /** What Steam says is running now: (appid, game) or null. */
    data class Now(val appid: Long, val game: String?)

    /** GET /now: the game (null when nothing plays) and a running import (round 5), or null. */
    data class NowStatus(val now: Now?, val importing: ImportProgress?)

    fun now(c: Context): Res<Now?> {
        val r = nowStatus(c)
        return if (r is Res.Ok) Res.Ok(r.value.now) else r as Res.Err
    }

    fun nowStatus(c: Context): Res<NowStatus> {
        val r = get(c, "/api/device/v1/now")
        if (r !is Res.Ok) return r as Res.Err
        // The hub answers the literal `null` when nothing is playing and nothing is importing.
        val t = r.value.trim()
        if (t.isEmpty() || t == "null") return Res.Ok(NowStatus(null, null))
        return try {
            val o = JSONObject(t)
            val n = if (o.has("nowPlaying")) o.optJSONObject("nowPlaying") else o
            // appid null (an import with nothing playing) reads as 0: nothing playing.
            val appid = if (n == null || n.isNull("appid")) 0L else n.optLong("appid", 0L)
            val imp = progressOf(o.optJSONObject("importing"))?.takeIf { it.total > 0 }
            Res.Ok(NowStatus(if (n == null || appid <= 0L) null else Now(appid, str(n, "game")), imp))
        } catch (e: Exception) {
            Res.Err(Fail.SERVER, "bad now: ${e.message}")
        }
    }

    data class SteamApp(val appid: Long, val name: String, val icon: String?, val lastPlayed: Long)

    fun steamApps(c: Context, q: String): Res<List<SteamApp>> {
        val r = get(c, "/api/device/v1/steam/apps?q=" + enc(q))
        if (r !is Res.Ok) return r as Res.Err
        return try {
            val t = r.value.trim()
            val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).let { it.optJSONArray("apps") ?: it.optJSONArray("items") ?: JSONArray() }
            val out = ArrayList<SteamApp>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optLong("appid", 0L)
                if (id <= 0) continue
                out.add(SteamApp(id, str(o, "name") ?: "App $id", str(o, "icon"), msOf(o.optLong("lastPlayed", 0L))))
            }
            Res.Ok(out)
        } catch (e: Exception) {
            Res.Err(Fail.SERVER, "bad apps: ${e.message}")
        }
    }

    /** Features a hub may not have yet: after a 404 the TV hides them for a day, then asks again. */
    object Missing {
        private fun p(c: Context) = c.getSharedPreferences("tv_missing", Context.MODE_PRIVATE)
        fun mark(c: Context, feature: String) = p(c).edit().putLong(feature, System.currentTimeMillis()).apply()
        fun clear(c: Context, feature: String) = p(c).edit().remove(feature).apply()
        fun has(c: Context, feature: String): Boolean = System.currentTimeMillis() - p(c).getLong(feature, 0L) < 86_400_000L
    }

    fun setShot(c: Context, id: String, favorite: Boolean? = null, status: String? = null): Res<Unit> {
        val body = JSONObject()
        if (favorite != null) body.put("favorite", favorite)
        if (status != null) body.put("status", status)
        val r = send(c, "POST", "/api/device/v1/shots/" + enc(id), body.toString())
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
        // A unique temp file per download: two downloads can never write the same file.
        var tmp: File? = null
        return try {
            val full = abs(c, url)
            val own = full.startsWith(base(c) + "/")
            val conn = (if (own) open(c, "GET", full) else openPublic(full)) ?: return false
            conn.readTimeout = 45_000
            conn.setRequestProperty("accept", "image/*")
            try {
                val status = conn.responseCode
                if (own && status == 401) onUnauthorized(c)
                if (status != 200) return false
                val t = File.createTempFile(dest.name, ".part", dest.parentFile)
                tmp = t
                conn.inputStream.use { input -> t.outputStream().use { input.copyTo(it, 64 * 1024) } }
            } finally {
                conn.disconnect()
            }
            val t = tmp ?: return false
            if (t.renameTo(dest)) true else { t.delete(); dest.isFile }
        } catch (_: Throwable) {
            tmp?.delete()
            false
        }
    }

    /** Called (on a worker thread) when the hub refuses the token: the TV forgets its copies. */
    @Volatile var unauthorizedListener: ((Context) -> Unit)? = null

    private fun onUnauthorized(c: Context) {
        unauthorizedListener?.invoke(c.applicationContext)
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
            peopleIds = o.optJSONArray("peopleIds")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { v -> v.isNotBlank() } } } ?: emptyList(),
            keywords = o.optJSONArray("keywords")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    when (val k = a.opt(i)) {
                        is String -> k.takeIf { it.isNotBlank() }
                        is JSONObject -> str(k, "name")
                        else -> null
                    }
                }
            } ?: emptyList(),
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
            last = o.optLong("last", 0L),
            playtimeMin = o.optInt("playtimeMin", o.optJSONObject("steam")?.optInt("playtimeMin", 0) ?: 0),
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

    /**
     * A 404 on a GET (a probe of the read API) means the hub does not have that call yet. A 404 on
     * a write normally means its target is gone (a shot skipped elsewhere, an album deleted):
     * [Fail.GONE], never "update Gamehub". [routeOnly]: a write with no target in its path, so its
     * 404 can only mean the route is missing.
     */
    private fun send(c: Context, method: String, path: String, json: String?, routeOnly: Boolean = false, readTimeoutMs: Int = 0): Res<String> {
        if (GamehubStore.token(c) == null) return Res.Err(Fail.NOT_LINKED, "not linked")
        return try {
            val conn = open(c, method, base(c) + path) ?: return Res.Err(Fail.NOT_LINKED, "not linked")
            if (readTimeoutMs > 0) conn.readTimeout = readTimeoutMs
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
                    status == 404 && (method == "GET" || routeOnly) -> Res.Err(Fail.NOT_DEPLOYED, "HTTP 404")
                    status == 404 -> Res.Err(Fail.GONE, "HTTP 404")
                    status in 300..399 -> Res.Err(Fail.NOT_DEPLOYED, "HTTP $status (Access)")
                    status == 401 -> { onUnauthorized(c); Res.Err(Fail.UNAUTHORIZED, errorOf(text) ?: "HTTP $status") }
                    status == 403 -> Res.Err(Fail.UNAUTHORIZED, errorOf(text) ?: "HTTP $status")
                    status == 429 -> Res.Err(Fail.BUSY, errorOf(text) ?: "HTTP 429")
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
