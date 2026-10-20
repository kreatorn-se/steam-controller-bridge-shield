package com.steamcontroller.android.parser

import android.util.Log

/**
 * Dedicated battery/charge report (id 0x43). Two distinct physical sources feed this:
 *  - USB (puck slot or cable): a 15-byte report every few seconds, laid out as Valve's
 *    TritonBatteryStatus_t (SDL3 SDL_hidapi_steam_triton.c, Linux hid-steam
 *    steam_ibex_battery_status): [0x43, chargeState, batteryLevel %, battery mV (le16),
 *    system mV, input mV, current, input current, temperature]. Matches the earlier hardware
 *    capture (byte[2] pinned at 100 while charging).
 *  - BLE: BluetoothHidManager extracts the percent from the 14-byte 100f6c78 characteristic and
 *    re-prefixes a compact synthetic [0x43, percent, 0x00]; no charge state on that path.
 *
 * [chargeState] is Valve's EChargeState (0 reset, 1 discharging, 2 charging, 3 validating the
 * source, 4 charging done) or -1 when the report does not carry it.
 */
data class BatteryStatus(
    val percent: Int,
    val chargeState: Int,
) {
    /** On external power (charging, validating the charger, or full); null when unknown. */
    val charging: Boolean?
        get() =
            when (chargeState) {
                CHARGE_DISCHARGING -> false
                CHARGE_CHARGING, CHARGE_SRC_VALIDATE, CHARGE_DONE -> true
                else -> null
            }

    companion object {
        const val CHARGE_DISCHARGING = 1
        const val CHARGE_CHARGING = 2
        const val CHARGE_SRC_VALIDATE = 3
        const val CHARGE_DONE = 4
    }
}

object SteamReportParser {
    private const val TAG = "SteamReportParser"
    private const val REPORT_STATE_USB = 0x42
    private const val REPORT_STATE_BLE = 0x45
    private const val MIN_REPORT_LEN = 40
    private const val REPORT_BATTERY_STATUS = 0x43
    private const val USB_BATTERY_REPORT_LEN = 15

    fun parseBatteryStatus(report: ByteArray): BatteryStatus? {
        if (report.size < 3) return null
        if ((report[0].toInt() and 0xFF) != REPORT_BATTERY_STATUS) return null
        return if (report.size >= USB_BATTERY_REPORT_LEN) {
            BatteryStatus(
                percent = (report[2].toInt() and 0xFF).coerceAtMost(100),
                chargeState = report[1].toInt() and 0xFF,
            )
        } else {
            BatteryStatus(percent = (report[1].toInt() and 0xFF).coerceAtMost(100), chargeState = -1)
        }
    }

    /**
     * Null when this is not a state report at all - too short, or an id other than the USB
     * 0x42 and BLE 0x45 state reports. That
     * distinction matters: callers must not substitute a blank state for it, because a blank
     * state means "every axis centred, no buttons held", which is a real thing to say and not a
     * safe stand-in for "this report had no state in it".
     */
    fun parse(report: ByteArray): SteamControllerState? {
        if (report.size < MIN_REPORT_LEN) return null
        val id = report[0].toInt() and 0xFF
        if (id != REPORT_STATE_USB && id != REPORT_STATE_BLE) {
            Log.v(TAG, "Unexpected report ID: 0x${id.toString(16)}")
            return null
        }
        return doParse(report)
    }

    private fun doParse(report: ByteArray): SteamControllerState {
        // byte[1] = sequence counter (rolling 0-255), ignored
        // buttons span bytes 2-5 (4 bytes = 32 bits)
        val buttons =
            (report[2].toInt() and 0xFF) or
                ((report[3].toInt() and 0xFF) shl 8) or
                ((report[4].toInt() and 0xFF) shl 16) or
                ((report[5].toInt() and 0xFF) shl 24)

        return SteamControllerState(
            buttons = buttons,
            leftTrigger = readUInt16LE(report, 6),
            rightTrigger = readUInt16LE(report, 8),
            leftJoyX = readInt16LE(report, 10),
            leftJoyY = readInt16LE(report, 12),
            rightJoyX = readInt16LE(report, 14),
            rightJoyY = readInt16LE(report, 16),
            leftPadX = readInt16LE(report, 18),
            leftPadY = readInt16LE(report, 20),
            rightPadX = readInt16LE(report, 24),
            rightPadY = readInt16LE(report, 26),
            quatW = readInt16LE(report, 32),
            quatX = readInt16LE(report, 34),
            quatY = readInt16LE(report, 36),
            quatZ = readInt16LE(report, 38),
        )
    }

    private fun readInt16LE(
        buf: ByteArray,
        offset: Int,
    ): Short {
        val lo = buf[offset].toInt() and 0xFF
        val hi = buf[offset + 1].toInt() and 0xFF
        return ((hi shl 8) or lo).toShort()
    }

    private fun readUInt16LE(
        buf: ByteArray,
        offset: Int,
    ): Int = readInt16LE(buf, offset).toInt() and 0xFFFF
}
