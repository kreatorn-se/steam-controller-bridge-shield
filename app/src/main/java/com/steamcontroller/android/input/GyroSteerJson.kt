package com.steamcontroller.android.input

import org.json.JSONObject

/** Profile JSON for [GyroSteerTuning], stored under the profile's `steer` key. */
object GyroSteerJson {
    private const val ENABLED = "enabled"
    private const val ACTIVATION = "activation"
    private const val AXIS = "axis"
    private const val LOCK = "lockDeg"
    private const val DEADZONE = "deadzoneDeg"
    private const val CURVE = "curve"
    private const val SMOOTHING = "smoothing"
    private const val INVERT = "invert"

    fun toJson(t: GyroSteerTuning): JSONObject = JSONObject().apply {
        put(ENABLED, t.enabled)
        put(ACTIVATION, t.activation.id)
        put(AXIS, t.axis.id)
        put(LOCK, t.lockDeg.toDouble())
        put(DEADZONE, t.deadzoneDeg.toDouble())
        put(CURVE, t.curve.toDouble())
        put(SMOOTHING, t.smoothing.toDouble())
        put(INVERT, t.invert)
    }

    fun fromJson(o: JSONObject?): GyroSteerTuning {
        val d = GyroSteerTuning()
        if (o == null) return d
        return GyroSteerTuning(
            enabled = o.optBoolean(ENABLED, d.enabled),
            activation = SteerActivation.fromId(o.optInt(ACTIVATION, d.activation.id)),
            axis = SteerAxis.fromId(o.optInt(AXIS, d.axis.id)),
            lockDeg = o.optDouble(LOCK, d.lockDeg.toDouble()).toFloat(),
            deadzoneDeg = o.optDouble(DEADZONE, d.deadzoneDeg.toDouble()).toFloat(),
            curve = o.optDouble(CURVE, d.curve.toDouble()).toFloat(),
            smoothing = o.optDouble(SMOOTHING, d.smoothing.toDouble()).toFloat(),
            invert = o.optBoolean(INVERT, d.invert),
        ).clamped()
    }

    fun parse(json: String?): GyroSteerTuning =
        if (json.isNullOrBlank()) GyroSteerTuning() else try {
            fromJson(JSONObject(json))
        } catch (_: Exception) {
            GyroSteerTuning()
        }
}
