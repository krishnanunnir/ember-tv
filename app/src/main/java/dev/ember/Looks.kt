package dev.ember

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * One setting, as in Vulcan's Looks: stored value strings in [values], shown as the string resources [names], or as
 * the untranslated [shown] (numbers, percentages) where there are none.
 */
class Choice(
    val key: String, val title: Int, val default: String,
    val values: List<String>, val names: List<Int> = emptyList(), val shown: List<String> = values,
)

fun Context.choiceName(c: Choice, i: Int): String = c.names.getOrNull(i)?.let(::getString) ?: c.shown.getOrElse(i) { "" }

private fun onOff(key: String, title: Int) = Choice(key, title, "on", listOf("on", "off"), listOf(R.string.on, R.string.off))

/** Vulcan's colour list, led by Ember's own signal orange. */
val COLOR_VALUES = listOf("#E8721C", "#FFFFFF", "#E53935", "#FB8C00", "#FDD835", "#43A047", "#00897B", "#1E88E5", "#3949AB", "#8E24AA", "#D81B60")
private val COLOR_NAMES = listOf(R.string.color_signal, R.string.color_white, R.string.color_red, R.string.color_orange,
    R.string.color_yellow, R.string.color_green, R.string.color_teal, R.string.color_blue, R.string.color_indigo,
    R.string.color_purple, R.string.color_pink)

val BACKGROUND = Choice("background", R.string.set_background, "picture", listOf("picture", "plain", "image"),
    listOf(R.string.bg_picture, R.string.bg_plain, R.string.bg_image))
val DIM = (0..90 step 10).map { "$it" }.let { Choice("dim", R.string.set_dim, "50", it, shown = it.map { v -> "$v%" }) }
val ACCENT = Choice("accent", R.string.set_accent, COLOR_VALUES[0], COLOR_VALUES, COLOR_NAMES)
val COLS = Choice("cols", R.string.set_cols, "5", listOf("4", "5", "6"))
val CLOCK = Choice("clock", R.string.set_clock, "system", listOf("system", "12", "24", "off"),
    listOf(R.string.clock_system, R.string.clock_12, R.string.clock_24, R.string.off))
val INPUTS = onOff("inputs", R.string.set_inputs)
// Empty ports are hidden unless asked for: three "Nothing plugged in" keys are clutter.
val EMPTY_PORTS = Choice("emptyPorts", R.string.set_empty_ports, "off", listOf("on", "off"), listOf(R.string.on, R.string.off))

val Context.prefs: SharedPreferences get() = getSharedPreferences("ember", Context.MODE_PRIVATE)
operator fun SharedPreferences.get(c: Choice): String = getString(c.key, c.default)!!.takeIf { it in c.values } ?: c.default
fun SharedPreferences.int(c: Choice) = this[c].toInt()
fun SharedPreferences.on(c: Choice) = this[c] == "on"

/** Packages in a comma-joined preference ("favs", "hidden"), in order. */
fun SharedPreferences.list(key: String) = getString(key, "")!!.split(',').filter { it.isNotEmpty() }
fun SharedPreferences.putList(key: String, v: List<String>) = edit().putString(key, v.joinToString(",")).apply()
/** The user's own name for [pkg], or null. */
fun SharedPreferences.label(pkg: String) = getString("label:$pkg", null)?.takeIf { it.isNotBlank() }

/** Every setting, favourite, hidden app and name as one JSON object. Ember stores strings only. */
fun backupJson(all: Map<String, *>): String =
    JSONObject().apply { put("ember", 1); all.forEach { (k, v) -> if (v is String) put(k, v) } }.toString(2)

/** The preferences a [backupJson] text holds; anything else (another app's file, a newer format) throws. */
fun restoreJson(text: String): Map<String, String> {
    val o = JSONObject(text)
    require(o.optInt("ember") == 1) { "Not an Ember backup" }
    return o.keys().asSequence().filter { it != "ember" }.associateWith { o.getString(it) }
}
