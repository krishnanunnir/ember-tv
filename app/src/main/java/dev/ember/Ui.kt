package dev.ember

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.widget.TextView

// ---- The Appliance look shared by Home and Settings. ----

fun Context.dp(v: Number) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

/** The focus edge colour (Settings → Accent colour). */
val Context.accent: Int get() = runCatching { Color.parseColor(prefs[ACCENT]) }.getOrDefault(getColor(R.color.signal))

fun Context.legend(s: CharSequence, sp: Float, colorId: Int) = TextView(this).apply {
    text = s
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(getColor(colorId))
}

/** A small capitalised section title. */
fun Context.label(id: Int) = legend(getString(id), 13f, R.color.legend_secondary).apply {
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    letterSpacing = 0.12f
    setPadding(0, dp(22), 0, dp(4))
}

/** The raised key look; focus lights the accent edge. */
fun Context.keyBackground(radius: Float) = StateListDrawable().apply {
    fun shape(stroke: Boolean) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(getColor(R.color.raised))
        if (stroke) setStroke(dp(3), accent)
    }
    addState(intArrayOf(android.R.attr.state_focused), shape(true))
    addState(intArrayOf(), shape(false))
}

fun Context.focusRing(radius: Float) = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
        cornerRadius = radius
        setStroke(dp(3), accent)
    })
}

/** A pill key with a capitalised legend, as Home's SEARCH and SETTINGS. */
fun Context.pill(id: Int, onClick: () -> Unit) = legend(getString(id), 13f, R.color.legend).apply {
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    letterSpacing = 0.12f
    setPadding(dp(20), dp(10), dp(20), dp(10))
    background = keyBackground(dp(20).toFloat())
    isFocusable = true
    setOnClickListener { onClick() }
}
