package com.steamcontroller.android.radial

/**
 * The contract's key names and their HID keyboard-page usages (HID Usage Tables, chapter 10), and
 * the allowlist the shell service checks every keyboard report against: only usages a radial menu
 * can name ever reach the virtual keyboard.
 */
object RadialKeys {
    private val USAGES: Map<String, Int> = buildMap {
        for (i in 0 until 26) put(('A' + i).toString(), 0x04 + i)
        // 1..9 then 0, as on the keyboard page.
        for (i in 1..9) put(i.toString(), 0x1E + i - 1)
        put("0", 0x27)
        for (i in 1..12) put("F$i", 0x3A + i - 1)
        put("ENTER", 0x28)
        put("ESC", 0x29)
        put("BACKSPACE", 0x2A)
        put("TAB", 0x2B)
        put("SPACE", 0x2C)
        put("MINUS", 0x2D)
        put("EQUAL", 0x2E)
        put("LBRACKET", 0x2F)
        put("RBRACKET", 0x30)
        put("BACKSLASH", 0x31)
        put("SEMICOLON", 0x33)
        put("APOSTROPHE", 0x34)
        put("GRAVE", 0x35)
        put("COMMA", 0x36)
        put("PERIOD", 0x37)
        put("SLASH", 0x38)
        put("CAPSLOCK", 0x39)
        put("INSERT", 0x49)
        put("HOME", 0x4A)
        put("PAGEUP", 0x4B)
        put("DELETE", 0x4C)
        put("END", 0x4D)
        put("PAGEDOWN", 0x4E)
        put("RIGHT", 0x4F)
        put("LEFT", 0x50)
        put("DOWN", 0x51)
        put("UP", 0x52)
        put("KPSLASH", 0x54)
        put("KPASTERISK", 0x55)
        put("KPMINUS", 0x56)
        put("KPPLUS", 0x57)
        put("KPENTER", 0x58)
        for (i in 1..9) put("KP$i", 0x59 + i - 1)
        put("KP0", 0x62)
        put("LCTRL", 0xE0)
        put("LSHIFT", 0xE1)
        put("LALT", 0xE2)
        put("LMETA", 0xE3)
        put("RCTRL", 0xE4)
        put("RSHIFT", 0xE5)
        put("RALT", 0xE6)
        put("RMETA", 0xE7)
    }

    /** Every name the contract allows. */
    val NAMES: Set<String> get() = USAGES.keys

    /** The ordinary (non-modifier) usages a report may carry in its key slots. */
    val ALLOWED_USAGES: Set<Int> = USAGES.values.filter { it < 0xE0 }.toSet()

    fun usage(name: String): Int? = USAGES[name]

    fun isModifier(name: String): Boolean = (USAGES[name] ?: 0) >= 0xE0
    fun isModifierUsage(usage: Int): Boolean = usage in 0xE0..0xE7

    /** The modifier byte's bit for a modifier usage (E0 → bit 0 … E7 → bit 7). */
    fun modifierBit(usage: Int): Int = 1 shl (usage - 0xE0)

    /**
     * The boot report for a set of held usages, in the order they went down: modifiers become
     * bits, the first six other keys fill the slots. Returns (modifiers, keys).
     */
    fun report(held: List<Int>): Pair<Int, IntArray> {
        var mods = 0
        val keys = ArrayList<Int>(6)
        for (u in held) {
            if (isModifierUsage(u)) mods = mods or modifierBit(u)
            else if (keys.size < 6 && u !in keys) keys += u
        }
        return mods to keys.toIntArray()
    }

    /** The shell service's check: a byte of modifiers and at most six allowed key usages. */
    fun reportAllowed(modifiers: Int, keys: IntArray): Boolean =
        modifiers in 0..0xFF && keys.size <= 6 && keys.all { it in ALLOWED_USAGES }
}
