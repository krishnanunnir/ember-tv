package dev.ember

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

// ---- The Appliance look shared by Home and Settings. Sizes follow the design canvas at 1920 px = 960 dp. ----

fun Context.dp(v: Number) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

/** The focus edge colour (Settings → Accent colour). */
val Context.accent: Int get() = runCatching { Color.parseColor(prefs[ACCENT]) }.getOrDefault(getColor(R.color.signal))

val MEDIUM: Typeface get() = Typeface.create("sans-serif-medium", Typeface.NORMAL)
val LIGHT: Typeface get() = Typeface.create("sans-serif-light", Typeface.NORMAL)

fun Context.legend(s: CharSequence, sp: Float, colorId: Int = R.color.legend) = TextView(this).apply {
    text = s
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(getColor(colorId))
}

fun TextView.oneLine() = apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }

/** A section heading in sentence case, as "Favourites" on Home. */
fun Context.heading(id: Int) = legend(getString(id), 13f).apply {
    typeface = MEDIUM
    setPadding(0, dp(18), 0, dp(8))
}

fun Context.icon(id: Int, color: Int = getColor(R.color.legend)) = ImageView(this).apply { setImageResource(id); setColorFilter(color) }

fun Context.rounded(color: Int, radiusDp: Number) = GradientDrawable().apply { cornerRadius = dp(radiusDp).toFloat(); setColor(color) }

/** A key that turns [focused] with a 2 dp accent edge when the remote is on it, and [focused] without the edge when selected. */
fun Context.keyBackground(radiusDp: Number, color: Int = getColor(R.color.row), focused: Int = getColor(R.color.key)) = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_focused), rounded(focused, radiusDp).apply { setStroke(dp(2), accent) })
    addState(intArrayOf(android.R.attr.state_selected), rounded(focused, radiusDp))
    addState(intArrayOf(), rounded(color, radiusDp))
}

fun Context.focusRing(radiusDp: Number) = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(radiusDp).toFloat(); setStroke(dp(2), accent) })
}

/** The tile focus: grows a little and lifts on an accent-coloured glow (the colour needs Android 9; older TVs get a plain shadow). */
fun View.lift() {
    if (Build.VERSION.SDK_INT >= 28) { outlineSpotShadowColor = context.accent; outlineAmbientShadowColor = context.accent }
    setOnFocusChangeListener { v, has -> v.animateFocus(has) }
}

fun View.animateFocus(has: Boolean) {
    val s = if (has) 1.06f else 1f
    animate().scaleX(s).scaleY(s).translationZ(if (has) context.dp(10).toFloat() else 0f).setDuration(120).start()
}

/** The shared 16:9 key: rounded, an accent edge and a lift on focus. */
fun Context.key(tag: String, description: String, color: Int) = FrameLayout(this).apply {
    this.tag = tag
    isFocusable = true
    contentDescription = description
    background = rounded(color, 10)
    foreground = focusRing(10)
    clipToOutline = true
    lift()
}

/** A tile's face: the app's banner, or its icon and name on a colour taken from the icon. */
fun Context.tileFace(app: App, iconDp: Int = 40, sp: Float = 13f): View =
    if (app.banner != null && app.label == app.title) ImageView(this).apply {
        setImageDrawable(app.banner)
        scaleType = ImageView.ScaleType.CENTER_CROP
    } else LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(ImageView(context).apply { setImageDrawable(app.icon) }, LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)))
        addView(legend(app.label, sp).oneLine().apply {
            typeface = MEDIUM
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), 0)
        })
    }

fun Context.appTile(app: App, tag: String) = key(tag, app.label, tint(app)).apply {
    addView(tileFace(app), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
}

private val tints = HashMap<String, Int>()

/** The tile colour for [app]: its icon's average colour, darkened so a light name reads on it. Banners sit on raised grey. */
fun Context.tint(app: App): Int {
    if (app.banner != null && app.label == app.title) return getColor(R.color.raised)
    return tints.getOrPut(app.pkg) {
        runCatching {
            val b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val d = app.icon.constantState?.newDrawable()?.mutate() ?: app.icon
            d.setBounds(0, 0, 8, 8); d.draw(Canvas(b))
            val px = IntArray(64).also { b.getPixels(it, 0, 8, 0, 0, 8, 8) }
            b.recycle()
            averageOpaque(px)?.let { shade(it, 0.42f, getColor(R.color.ground)) }
        }.getOrNull() ?: getColor(R.color.raised)
    }
}

/** The average of the mostly opaque pixels, or null for an icon with none. */
fun averageOpaque(px: IntArray): Int? {
    val solid = px.filter { (it ushr 24) > 200 }
    if (solid.isEmpty()) return null
    fun avg(shift: Int) = solid.sumOf { (it shr shift) and 0xFF } / solid.size
    return (0xFF shl 24) or (avg(16) shl 16) or (avg(8) shl 8) or avg(0)
}

/** [c] mixed with [ground], keeping [keep] of [c]. */
fun shade(c: Int, keep: Float, ground: Int): Int {
    fun ch(shift: Int) = (((c shr shift) and 0xFF) * keep + ((ground shr shift) and 0xFF) * (1 - keep)).toInt()
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** Dark or white text for a button filled with [c], whichever has more contrast (WCAG relative luminance). */
fun onColor(c: Int): Int {
    fun lin(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
    val l = 0.2126 * lin((c shr 16) and 0xFF) + 0.7152 * lin((c shr 8) and 0xFF) + 0.0722 * lin(c and 0xFF)
    // Against #1C1B19 (L ≈ 0.011) dark text wins from about L = 0.18.
    return if (l > 0.18) 0xFF1C1B19.toInt() else -1
}

/** Hidden apps in Settings: grey and darker, the name still readable. */
fun View.dimmed(on: Boolean) {
    if (!on) { setLayerType(View.LAYER_TYPE_NONE, null); return }
    val m = ColorMatrix().apply { setSaturation(0.2f); postConcat(ColorMatrix().apply { setScale(0.6f, 0.6f, 0.6f, 1f) }) }
    setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
}

/** The dimming over the background picture, strongest behind the top bar and the app rows. */
fun dimming(percent: Int): Drawable {
    val a = percent * 255 / 100
    fun dim(f: Float) = (a * f).toInt().coerceAtMost(255) shl 24 or 0x1C1B19
    return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(dim(1.2f), dim(0.6f), dim(1.8f)))
}

/** A panel from the right edge over a dimmed screen, as the app menu and the pickers. */
fun Activity.sidePanel(widthDp: Int, content: View): Dialog = Dialog(this).apply {
    requestWindowFeature(Window.FEATURE_NO_TITLE)
    setContentView(content)
    window!!.apply {
        setGravity(Gravity.END)
        setLayout(dp(widthDp), MATCH_PARENT)
        setBackgroundDrawable(ColorDrawable(getColor(R.color.panel)))
        setDimAmount(0.62f)
    }
}

/** A row in a panel: icon and label, lit with the accent edge when focused. */
fun Context.actionRow(iconId: Int, text: String, colorId: Int = R.color.legend, onClick: () -> Unit) = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL
    isFocusable = true
    minimumHeight = dp(36)
    setPadding(dp(12), 0, dp(12), 0)
    background = keyBackground(9, Color.TRANSPARENT)
    addView(icon(iconId, getColor(colorId)), LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(10) })
    addView(legend(text, 13f, colorId))
    setOnClickListener { onClick() }
}

/** A pill button; [primary] fills it with the accent. */
fun Context.button(text: String, primary: Boolean, onClick: () -> Unit) = legend(text, 12f).apply {
    typeface = MEDIUM
    gravity = Gravity.CENTER
    isFocusable = true
    minHeight = dp(32)
    setPadding(dp(22), 0, dp(22), 0)
    val fill = if (primary) accent else getColor(R.color.key)
    if (primary) setTextColor(onColor(fill))
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(fill, 16).apply { setStroke(dp(2), getColor(R.color.legend)) })
        addState(intArrayOf(), rounded(fill, 16))
    }
    lift() // the edge alone vanishes on a white accent
    setOnClickListener { onClick() }
}
