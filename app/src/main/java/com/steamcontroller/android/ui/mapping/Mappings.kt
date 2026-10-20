package com.steamcontroller.android.ui.mapping

import android.content.Context
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.input.DEFAULT_ACTION_LAYER
import com.steamcontroller.android.input.DEFAULT_DESKTOP_MAPPING
import com.steamcontroller.android.input.DEFAULT_MAPPING
import com.steamcontroller.android.input.SteamButton
import com.steamcontroller.android.input.XboxTarget

/** The three mapping tables the controller page edits. */
enum class Layer(val title: String) {
    GAMEPAD("Gamepad"),
    DESKTOP("Desktop"),
    ACTION("Action layer"),
}

/**
 * One place that reads and writes a mapping for a layer, so the UI never branches on the layer.
 * In the action layer NONE means "falls through to the gamepad mapping".
 */
object Mappings {
    fun editable(layer: Layer) = true

    fun get(ctx: Context, layer: Layer, b: SteamButton): XboxTarget = when (layer) {
        Layer.GAMEPAD -> Prefs.getMapping(ctx, b)
        Layer.DESKTOP -> Prefs.getDesktopMapping(ctx, b)
        Layer.ACTION -> Prefs.getActionLayerMapping(ctx, b)
    }

    fun set(ctx: Context, layer: Layer, b: SteamButton, t: XboxTarget) {
        when (layer) {
            Layer.GAMEPAD -> Prefs.setMapping(ctx, b, t)
            Layer.DESKTOP -> Prefs.setDesktopMapping(ctx, b, t)
            Layer.ACTION -> Prefs.setActionLayerMapping(ctx, b, t)
        }
    }

    fun default(layer: Layer, b: SteamButton): XboxTarget = when (layer) {
        Layer.GAMEPAD -> DEFAULT_MAPPING[b]
        Layer.DESKTOP -> DEFAULT_DESKTOP_MAPPING[b]
        Layer.ACTION -> DEFAULT_ACTION_LAYER[b]
    } ?: XboxTarget.NONE

    fun reset(ctx: Context, layer: Layer) {
        when (layer) {
            Layer.GAMEPAD -> Prefs.resetMappings(ctx)
            Layer.DESKTOP -> Prefs.resetDesktopMappings(ctx)
            Layer.ACTION -> Prefs.resetActionLayerMappings(ctx)
        }
    }

    /** The button that opens the action layer when held (mapped to a "layer on hold" target), if any. */
    fun layerKey(ctx: Context): SteamButton? = SteamButton.values().firstOrNull {
        val t = Prefs.getMapping(ctx, it)
        t == XboxTarget.GUIDE_LAYER || t == XboxTarget.GFN_LAYER
    }

    /** What each layer can emit; the same filters the runtime honours. */
    fun allowed(layer: Layer, t: XboxTarget): Boolean = when (layer) {
        Layer.DESKTOP -> t == XboxTarget.NONE || t.keyBit >= 0 || t == XboxTarget.SCREENSHOT
        // A layer key inside the layer does nothing.
        Layer.ACTION -> t.keyBit < 16 && t != XboxTarget.GUIDE_LAYER && t != XboxTarget.GFN_LAYER
        Layer.GAMEPAD -> t.keyBit < 16
    }

    class Group(val title: String, val targets: List<XboxTarget>)

    fun groups(layer: Layer): List<Group> {
        val gamepad = listOf(
            XboxTarget.A, XboxTarget.B, XboxTarget.X, XboxTarget.Y, XboxTarget.LB, XboxTarget.RB,
            XboxTarget.LT_TRIGGER, XboxTarget.RT_TRIGGER, XboxTarget.THUMBL, XboxTarget.THUMBR,
            XboxTarget.SELECT, XboxTarget.START, XboxTarget.MODE,
        )
        val system = listOf(
            XboxTarget.START_HOLD, XboxTarget.GFN_LAYER, XboxTarget.GUIDE_LAYER, XboxTarget.SCREENSHOT,
        )
        val keys = listOf(
            XboxTarget.KB_VOLUME_UP, XboxTarget.KB_VOLUME_DOWN, XboxTarget.KB_PLAY_PAUSE, XboxTarget.KB_HOME,
            XboxTarget.KB_BACK, XboxTarget.KB_MENU, XboxTarget.KB_DPAD_CENTER, XboxTarget.KB_ENTER,
            XboxTarget.KB_ESCAPE, XboxTarget.KB_TAB, XboxTarget.KB_SPACE, XboxTarget.KB_BACKSPACE,
            XboxTarget.KB_UP, XboxTarget.KB_DOWN, XboxTarget.KB_LEFT, XboxTarget.KB_RIGHT,
        )
        val mouse = listOf(XboxTarget.MOUSE_LEFT, XboxTarget.MOUSE_RIGHT, XboxTarget.MOUSE_MIDDLE)
        val listed = (gamepad + system + keys + mouse + XboxTarget.NONE).toSet()
        // Anything added to XboxTarget later still shows up instead of silently disappearing.
        val other = XboxTarget.values().filter { it !in listed }
        return listOf(
            Group("Gamepad", gamepad),
            Group("GeForce NOW & system", system),
            Group("Keyboard & media", keys),
            Group("Mouse", mouse),
            Group("Other", other),
            Group("Off", listOf(XboxTarget.NONE)),
        ).map { g -> Group(g.title, g.targets.filter { allowed(layer, it) }) }.filter { it.targets.isNotEmpty() }
    }
}

/** Display names: short capitals for callout chips, sentence case for the editor list. */
object Names {
    fun chip(t: XboxTarget): String = when (t) {
        XboxTarget.NONE -> "Unassigned"
        XboxTarget.A, XboxTarget.B, XboxTarget.X, XboxTarget.Y, XboxTarget.LB, XboxTarget.RB -> t.name
        XboxTarget.SELECT -> "View"
        XboxTarget.START -> "Menu"
        XboxTarget.MODE -> "Guide"
        XboxTarget.THUMBL -> "L3"
        XboxTarget.THUMBR -> "R3"
        XboxTarget.LT_TRIGGER -> "LT"
        XboxTarget.RT_TRIGGER -> "RT"
        XboxTarget.KB_UP -> "Key up"
        XboxTarget.KB_DOWN -> "Key down"
        XboxTarget.KB_LEFT -> "Key left"
        XboxTarget.KB_RIGHT -> "Key right"
        XboxTarget.KB_VOLUME_UP -> "Volume +"
        XboxTarget.KB_VOLUME_DOWN -> "Volume −"
        XboxTarget.KB_PLAY_PAUSE -> "Play / pause"
        XboxTarget.KB_BACK -> "Back"
        XboxTarget.KB_HOME -> "Home"
        XboxTarget.KB_ENTER -> "Enter"
        XboxTarget.KB_DPAD_CENTER -> "Select"
        XboxTarget.KB_ESCAPE -> "Esc"
        XboxTarget.KB_TAB -> "Tab"
        XboxTarget.KB_SPACE -> "Space"
        XboxTarget.KB_BACKSPACE -> "Backspace"
        XboxTarget.KB_MENU -> "Context menu"
        XboxTarget.MOUSE_LEFT -> "Left click"
        XboxTarget.MOUSE_RIGHT -> "Right click"
        XboxTarget.MOUSE_MIDDLE -> "Middle click"
        XboxTarget.SCREENSHOT -> "Screenshot"
        XboxTarget.GUIDE_LAYER -> "Guide + layer"
        XboxTarget.START_HOLD -> "GFN overlay"
        XboxTarget.GFN_LAYER -> "GFN + layer"
        // Merge-safe: a target added to XboxTarget later still gets a name.
        else -> t.displayName
    }

    fun full(t: XboxTarget): String = when (t) {
        XboxTarget.NONE -> "Unassigned"
        XboxTarget.LT_TRIGGER -> "LT · full pull"
        XboxTarget.RT_TRIGGER -> "RT · full pull"
        XboxTarget.SELECT -> "View / Select"
        XboxTarget.START -> "Menu / Start"
        XboxTarget.MODE -> "Guide (Home)"
        XboxTarget.THUMBL -> "L3 · left stick click"
        XboxTarget.THUMBR -> "R3 · right stick click"
        XboxTarget.START_HOLD -> "GFN overlay"
        XboxTarget.GFN_LAYER -> "GFN overlay · layer on hold"
        XboxTarget.GUIDE_LAYER -> "Guide · layer on hold"
        XboxTarget.SCREENSHOT -> "Screenshot to Gamehub"
        XboxTarget.KB_DPAD_CENTER -> "Select (D-pad centre)"
        else -> chip(t)
    }

    /** One line under the name in the editor, only where the name alone is not enough. */
    fun hint(t: XboxTarget): String? = when (t) {
        XboxTarget.START_HOLD -> "Synthesises a long press of Start, which opens GeForce NOW's overlay"
        XboxTarget.GFN_LAYER -> "Tap for the GFN overlay, hold for the action layer"
        XboxTarget.GUIDE_LAYER -> "Tap for Guide, hold for the action layer"
        XboxTarget.SCREENSHOT -> "Captures the screen and queues it for Gamehub"
        XboxTarget.LT_TRIGGER, XboxTarget.RT_TRIGGER -> "Forces the trigger axis to full while held"
        XboxTarget.KB_DPAD_CENTER -> "Clicks the focused key on the TV keyboard"
        else -> null
    }

    /** Short source label for the badge inside a chip. */
    fun source(id: String): String = when (id) {
        "QUICK_ACCESS" -> "QAM"
        "GRIP_LT" -> "LG"
        "GRIP_RT" -> "RG"
        "DPAD" -> "D-pad"
        "LPAD" -> "L pad"
        "RPAD" -> "R pad"
        else -> id
    }

    /** Title in the editor sheet. */
    fun sourceTitle(id: String): String = when (id) {
        "A", "B", "X", "Y" -> "$id button"
        "LB" -> "Left bumper"
        "RB" -> "Right bumper"
        "LT" -> "Left trigger"
        "RT" -> "Right trigger"
        "LS" -> "Left stick click"
        "RS" -> "Right stick click"
        "MENU" -> "Menu button"
        "VIEW" -> "View button"
        "STEAM" -> "Steam button"
        "QUICK_ACCESS" -> "Quick access button"
        "L4", "L5", "R4", "R5" -> "$id back paddle"
        "GRIP_LT" -> "Left grip"
        "GRIP_RT" -> "Right grip"
        "DPAD" -> "D-pad"
        "LPAD" -> "Left trackpad"
        "RPAD" -> "Right trackpad"
        else -> id
    }
}
