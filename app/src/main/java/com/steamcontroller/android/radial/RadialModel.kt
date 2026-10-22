package com.steamcontroller.android.radial

import org.json.JSONArray
import org.json.JSONObject

/**
 * Radial menus on the trackpads, as the shared contract (contracts/radial-v1.md) describes them:
 * one optional menu per pad in each profile's `radial` key, edited on the web Controller page and
 * synced with the rest of the config. Everything here is plain data; [RadialParser] builds it.
 */
data class RadialConfig(val left: Menu?, val right: Menu?) {
    fun menu(left: Boolean): Menu? = if (left) this.left else right
    val isEmpty: Boolean get() = left == null && right == null

    /** Whether any slice (or macro step) presses keyboard keys: then the radial keyboard is needed. */
    val usesKeys: Boolean get() = listOfNotNull(left, right).any { m -> m.slices.any { it.action.usesKeys } }

    /** Every icon id the menus show, for prefetching. */
    val iconIds: Set<String> get() = listOfNotNull(left, right).flatMap { m -> m.slices.mapNotNull { it.icon } }.toSet()

    companion object {
        val EMPTY = RadialConfig(null, null)
    }
}

data class Menu(
    val slices: List<Slice>,
    /** 0.15..0.7 of the pad radius; inside it nothing is highlighted. */
    val deadzone: Float = RadialParser.DEFAULT_DEADZONE,
    /** Degrees: slice 0 is centred this far clockwise from 12 o'clock. */
    val rotation: Float = 0f,
    val haptics: Boolean = true,
)

data class Slice(
    val id: String,
    val label: String,
    /** `gi/<author>/<name>`, or null for label only. */
    val icon: String?,
    /** ARGB, or null for the default (white, Steam-blue highlight). */
    val color: Int?,
    val action: Action,
)

enum class Mode { TAP, HOLD, TOGGLE }

/** The buttons a slice can press on the virtual Xbox pad. */
enum class PadButton { A, B, X, Y, LB, RB, LT, RT, LS, RS, START, BACK, GUIDE, DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT }

sealed class Action {
    abstract val usesKeys: Boolean

    data class Button(val button: PadButton, val mode: Mode) : Action() {
        override val usesKeys get() = false
    }

    /** [keys] are HID usages in press order (modifiers first); released in reverse. */
    data class Key(val names: List<String>, val keys: List<Int>, val mode: Mode) : Action() {
        override val usesKeys get() = true
    }

    data class Macro(val steps: List<Step>) : Action() {
        override val usesKeys get() = steps.any { it is Step.Keys }
    }
}

enum class StepAct { TAP, DOWN, UP }

sealed class Step {
    data class Button(val button: PadButton, val act: StepAct) : Step()
    data class Keys(val keys: List<Int>, val act: StepAct) : Step()
    data class Wait(val ms: Int) : Step()
}

/** The result of reading a profile's `radial` key: the usable menus and why anything was dropped. */
data class ParsedRadial(val config: RadialConfig, val problems: List<String>)

/**
 * Reads and validates the contract's JSON. Unknown keys are ignored (forward compatibility); a
 * menu that breaks a rule is skipped as a whole and named in [ParsedRadial.problems] (the service
 * writes those to Diagnostics). A bad icon id or colour only drops that detail.
 */
object RadialParser {
    const val VERSION = 1
    const val MIN_SLICES = 2
    const val MAX_SLICES = 12
    const val DEFAULT_DEADZONE = 0.35f
    const val MIN_DEADZONE = 0.15f
    const val MAX_DEADZONE = 0.7f
    const val MAX_LABEL = 16
    const val MAX_KEYS = 4

    private val SLICE_ID = Regex("[a-z0-9_-]{1,16}")
    val ICON_ID = Regex("gi/[a-z0-9][a-z0-9-]{0,63}/[a-z0-9][a-z0-9-]{0,95}")
    private val COLOR = Regex("#[0-9A-Fa-f]{6}")

    private class Invalid(msg: String) : Exception(msg)

    /** [radial] is the profile's `radial` value (absent or null both mean no menus). */
    fun parse(radial: Any?): ParsedRadial {
        val problems = ArrayList<String>()
        val obj = radial as? JSONObject ?: run {
            if (radial != null && radial != JSONObject.NULL) problems += "radial is not an object; ignored"
            return ParsedRadial(RadialConfig.EMPTY, problems)
        }
        fun side(key: String): Menu? {
            val v = obj.opt(key)
            if (v == null || v == JSONObject.NULL) return null
            return try {
                menu(v as? JSONObject ?: throw Invalid("not an object"), problems, key)
            } catch (e: Invalid) {
                problems += "$key menu skipped: ${e.message}"
                null
            } catch (e: Exception) {
                problems += "$key menu skipped: ${e.javaClass.simpleName} ${e.message}"
                null
            }
        }
        return ParsedRadial(RadialConfig(side("left"), side("right")), problems)
    }

    /**
     * Which menus apply (contract v1.1 amendment 4): the active profile's `radial` when it has
     * one (an explicit null pad there means no menu on that pad, no fallback); otherwise — no
     * `radial` key on the profile, or no game profile active — the defaults in
     * `liveSettings.radial`.
     */
    fun resolve(profileRadial: String?, defaults: String?): ParsedRadial =
        if (profileRadial != null) parse(profileRadial)
        else parse(defaults).let { p -> p.copy(problems = p.problems.map { "defaults: $it" }) }

    /** Parses `{"left":…,"right":…}` from a string; malformed JSON gives an empty config. */
    fun parse(json: String?): ParsedRadial {
        if (json.isNullOrBlank()) return ParsedRadial(RadialConfig.EMPTY, emptyList())
        return try {
            parse(JSONObject(json))
        } catch (e: Exception) {
            ParsedRadial(RadialConfig.EMPTY, listOf("radial is not valid JSON; ignored"))
        }
    }

    private fun menu(o: JSONObject, problems: MutableList<String>, side: String): Menu {
        val v = o.opt("v")
        if (v != null && v != JSONObject.NULL) {
            val n = (v as? Number)?.toInt() ?: throw Invalid("v is not a number")
            if (n != VERSION) throw Invalid("version $n is not supported (this app reads v$VERSION)")
        }
        val arr = o.optJSONArray("slices") ?: throw Invalid("no slices")
        if (arr.length() !in MIN_SLICES..MAX_SLICES) throw Invalid("${arr.length()} slices (allowed $MIN_SLICES..$MAX_SLICES)")
        val deadzone = number(o, "deadzone", DEFAULT_DEADZONE.toDouble()).toFloat()
        if (deadzone.isNaN() || deadzone < MIN_DEADZONE || deadzone > MAX_DEADZONE) throw Invalid("deadzone $deadzone outside $MIN_DEADZONE..$MAX_DEADZONE")
        val rotation = number(o, "rotation", 0.0).toFloat()
        if (rotation.isNaN() || rotation < -180f || rotation > 180f) throw Invalid("rotation $rotation outside -180..180")
        val haptics = o.opt("haptics") as? Boolean ?: true
        val ids = HashSet<String>()
        val slices = (0 until arr.length()).map { i ->
            val s = arr.opt(i) as? JSONObject ?: throw Invalid("slice ${i + 1} is not an object")
            slice(s, i, ids, problems, side)
        }
        return Menu(slices, deadzone, rotation, haptics)
    }

    private fun slice(o: JSONObject, i: Int, ids: MutableSet<String>, problems: MutableList<String>, side: String): Slice {
        val where = "slice ${i + 1}"
        val id = o.opt("id") as? String ?: throw Invalid("$where has no id")
        if (!SLICE_ID.matches(id)) throw Invalid("$where id \"$id\" is not [a-z0-9_-]{1,16}")
        if (!ids.add(id)) throw Invalid("$where id \"$id\" is used twice")
        val label = when (val l = o.opt("label")) {
            null, JSONObject.NULL -> ""
            is String -> l
            else -> throw Invalid("$where label is not text")
        }
        if (label.codePointCount(0, label.length) > MAX_LABEL) throw Invalid("$where label is longer than $MAX_LABEL characters")
        var icon = when (val ic = o.opt("icon")) {
            null, JSONObject.NULL -> null
            is String -> ic
            else -> { problems += "$side $where: icon is not text; shown without it"; null }
        }
        if (icon != null && !ICON_ID.matches(icon)) {
            problems += "$side $where: icon \"$icon\" is not gi/<author>/<name>; shown without it"
            icon = null
        }
        if (icon == null && label.isBlank()) throw Invalid("$where has neither label nor icon")
        val color = when (val c = o.opt("color")) {
            null, JSONObject.NULL -> null
            is String -> if (COLOR.matches(c)) (0xFF000000.toInt() or c.substring(1).toInt(16)) else {
                problems += "$side $where: colour \"$c\" is not #RRGGBB; default used"; null
            }
            else -> { problems += "$side $where: colour is not text; default used"; null }
        }
        val action = action(o.optJSONObject("action") ?: throw Invalid("$where has no action"), where)
        return Slice(id, label, icon, color, action)
    }

    private fun action(o: JSONObject, where: String): Action = when (val type = o.opt("type")) {
        "button" -> Action.Button(button(o.opt("button"), where), mode(o.opt("mode"), where))
        "key" -> {
            val names = keyNames(o.opt("keys"), where)
            Action.Key(names, names.map { RadialKeys.usage(it)!! }, mode(o.opt("mode"), where))
        }
        "macro" -> Action.Macro(steps(o.optJSONArray("steps") ?: throw Invalid("$where macro has no steps"), where))
        else -> throw Invalid("$where action type \"$type\" is not button, key or macro")
    }

    private fun button(v: Any?, where: String): PadButton {
        val name = v as? String ?: throw Invalid("$where has no button")
        return PadButton.values().firstOrNull { it.name == name } ?: throw Invalid("$where button \"$name\" is not a pad button")
    }

    private fun mode(v: Any?, where: String): Mode = when (v) {
        null, JSONObject.NULL, "tap" -> Mode.TAP
        "hold" -> Mode.HOLD
        "toggle" -> Mode.TOGGLE
        else -> throw Invalid("$where mode \"$v\" is not tap, hold or toggle")
    }

    private fun act(v: Any?, where: String): StepAct = when (v) {
        null, JSONObject.NULL, "tap" -> StepAct.TAP
        "down" -> StepAct.DOWN
        "up" -> StepAct.UP
        else -> throw Invalid("$where act \"$v\" is not tap, down or up")
    }

    private fun keyNames(v: Any?, where: String): List<String> {
        val arr = v as? JSONArray ?: throw Invalid("$where has no keys")
        if (arr.length() !in 1..MAX_KEYS) throw Invalid("$where has ${arr.length()} keys (allowed 1..$MAX_KEYS)")
        val names = (0 until arr.length()).map { arr.opt(it) as? String ?: throw Invalid("$where key ${it + 1} is not text") }
        for (n in names) if (RadialKeys.usage(n) == null) throw Invalid("$where key \"$n\" is not a key name")
        if (names.toSet().size != names.size) throw Invalid("$where names a key twice")
        // Modifiers first: once an ordinary key is named, no modifier may follow.
        val firstPlain = names.indexOfFirst { !RadialKeys.isModifier(it) }
        if (firstPlain >= 0 && names.drop(firstPlain).any { RadialKeys.isModifier(it) }) throw Invalid("$where lists a modifier after a key (modifiers first)")
        return names
    }

    private fun steps(arr: JSONArray, where: String): List<Step> {
        if (arr.length() !in 1..MacroPlan.MAX_STEPS) throw Invalid("$where macro has ${arr.length()} steps (allowed 1..${MacroPlan.MAX_STEPS})")
        val steps = (0 until arr.length()).map { i ->
            val s = arr.opt(i) as? JSONObject ?: throw Invalid("$where step ${i + 1} is not an object")
            val w = "$where step ${i + 1}"
            val kinds = listOf("button", "keys", "wait").count { s.has(it) }
            if (kinds != 1) throw Invalid("$w must have exactly one of button, keys, wait")
            when {
                s.has("wait") -> {
                    val ms = (s.opt("wait") as? Number)?.toDouble() ?: throw Invalid("$w wait is not a number")
                    if (ms != Math.floor(ms) || ms < MacroPlan.MIN_WAIT_MS || ms > MacroPlan.MAX_WAIT_MS) throw Invalid("$w wait $ms outside ${MacroPlan.MIN_WAIT_MS}..${MacroPlan.MAX_WAIT_MS} ms")
                    Step.Wait(ms.toInt())
                }
                s.has("button") -> Step.Button(button(s.opt("button"), w), act(s.opt("act"), w))
                else -> Step.Keys(keyNames(s.opt("keys"), w).map { RadialKeys.usage(it)!! }, act(s.opt("act"), w))
            }
        }
        val total = MacroPlan.durationMs(steps)
        if (total > MacroPlan.MAX_TOTAL_MS) throw Invalid("$where macro lasts $total ms (at most ${MacroPlan.MAX_TOTAL_MS})")
        return steps
    }

    private fun number(o: JSONObject, key: String, default: Double): Double {
        val v = o.opt(key)
        if (v == null || v == JSONObject.NULL) return default
        return (v as? Number)?.toDouble() ?: throw Invalid("$key is not a number")
    }
}

/** Plain-text descriptions for the settings screen's read-only view. */
object RadialDescribe {
    fun mode(m: Mode) = m.name.lowercase()

    fun action(a: Action): String = when (a) {
        is Action.Button -> "${a.button.name} · ${mode(a.mode)}"
        is Action.Key -> "${a.names.joinToString("+")} · ${mode(a.mode)}"
        is Action.Macro -> "macro · ${a.steps.size} steps · ${"%.1f".format(java.util.Locale.ROOT, MacroPlan.durationMs(a.steps) / 1000.0)} s"
    }

    fun menu(m: Menu): String = buildString {
        m.slices.forEachIndexed { i, s ->
            if (i > 0) append('\n')
            append(i + 1).append(". ").append(s.label.ifBlank { s.icon ?: s.id }).append("  —  ").append(action(s.action))
        }
    }

    fun summary(c: RadialConfig): String =
        "L " + (c.left?.slices?.size?.toString() ?: "off") + "  ·  R " + (c.right?.slices?.size?.toString() ?: "off")
}
