package dev.ember

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast

/** The home screen: a clock, a favourites row and every app, all driven by the remote's D-pad. */
class Home : Activity() {

    private class App(val pkg: String, val label: String, val banner: Drawable?, val icon: Drawable)
    private class Input(val id: String, val label: String, val state: Int) {
        val plugged get() = state != TvInputManager.INPUT_STATE_DISCONNECTED
    }

    private val prefs by lazy { getSharedPreferences("ember", MODE_PRIVATE) }
    private lateinit var favRow: LinearLayout
    private lateinit var favHint: TextView
    private lateinit var grid: LinearLayout
    private lateinit var inputLabel: View
    private lateinit var inputRow: LinearLayout
    private var tileW = 0
    private var tileH = 0

    /** Null where the device has no TV input framework (phones, some boxes). */
    private val tvInputs by lazy { getSystemService(TV_INPUT_SERVICE) as? TvInputManager }

    private val packagesChanged = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = render(currentFocus?.tag as? String)
    }

    // Plugging a device in or out, or renaming an input, rebuilds the rows.
    private val inputsChanged = object : TvInputManager.TvInputCallback() {
        override fun onInputStateChanged(inputId: String, state: Int) = render(currentFocus?.tag as? String)
        override fun onInputAdded(inputId: String) = render(currentFocus?.tag as? String)
        override fun onInputRemoved(inputId: String) = render(currentFocus?.tag as? String)
        override fun onInputUpdated(inputId: String) = render(currentFocus?.tag as? String)
    }

    private fun dp(v: Number) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun color(id: Int) = getColor(id)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // TV overscan-safe margins (Android TV guidelines: 48dp sides, 27dp top and bottom).
        val sideMargin = dp(48)
        val gap = dp(16)
        val cols = 5
        tileW = (resources.displayMetrics.widthPixels - 2 * sideMargin - gap * (cols - 1)) / cols
        tileH = tileW * 9 / 16

        favRow = LinearLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(0, dp(10), 0, dp(10)) }
        favHint = legend(getString(R.string.no_favourites), 16f, R.color.legend_secondary)
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        inputLabel = label(R.string.inputs)
        inputRow = LinearLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(0, dp(10), 0, dp(10)) }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(sideMargin, dp(27), sideMargin, dp(27))
            addView(header())
            addView(label(R.string.favourites))
            addView(favHint)
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                clipChildren = false
                clipToPadding = false
                addView(favRow)
            })
            addView(inputLabel)
            addView(inputRow)
            addView(label(R.string.all_apps))
            addView(grid)
        }
        setContentView(FrameLayout(this).apply {
            addView(ImageView(context).apply {
                setImageResource(R.drawable.background)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            // Dims the picture most behind the clock and the app rows, so legends and banners stay readable.
            addView(View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0xB31E1E1C.toInt(), 0x591E1E1C, 0xD91E1E1C.toInt()),
                )
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                isFillViewport = true
                clipChildren = false
                addView(column)
            })
        })

        registerReceiver(packagesChanged, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        tvInputs?.registerCallback(inputsChanged, Handler(mainLooper))
        render(null)
    }

    override fun onDestroy() {
        tvInputs?.unregisterCallback(inputsChanged)
        unregisterReceiver(packagesChanged)
        super.onDestroy()
    }

    // Home has nowhere to go back to.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}

    private fun header() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(TextClock(context).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f)
            letterSpacing = -0.02f
            setTextColor(color(R.color.legend))
        })
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(TextView(context).apply {
            text = getString(R.string.settings)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            letterSpacing = 0.12f
            setTextColor(color(R.color.legend))
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = keyBackground(dp(20).toFloat())
            isFocusable = true
            setOnClickListener { open(Intent(Settings.ACTION_SETTINGS)) }
        })
    }

    private fun legend(s: String, sp: Float, colorId: Int) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color(colorId))
    }

    private fun label(id: Int) = legend(getString(id), 13f, R.color.legend_secondary).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.12f
        setPadding(0, dp(22), 0, dp(4))
    }

    /** The raised key look; focus lights the signal-orange edge. */
    private fun keyBackground(radius: Float) = StateListDrawable().apply {
        fun shape(stroke: Boolean) = GradientDrawable().apply {
            cornerRadius = radius
            setColor(color(R.color.raised))
            if (stroke) setStroke(dp(3), color(R.color.signal))
        }
        addState(intArrayOf(android.R.attr.state_focused), shape(true))
        addState(intArrayOf(), shape(false))
    }

    private fun focusRing(radius: Float) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            cornerRadius = radius
            setStroke(dp(3), color(R.color.signal))
        })
    }

    private fun loadApps(): List<App> {
        val pm = packageManager
        val seen = LinkedHashMap<String, App>()
        // TV apps first, so an app with both entries keeps its TV banner; phone-only sideloads still show.
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            for (r in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                val pkg = r.activityInfo.packageName
                if (pkg == packageName || pkg in seen) continue
                seen[pkg] = App(pkg, r.loadLabel(pm).toString(), r.activityInfo.loadBanner(pm), r.loadIcon(pm))
            }
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    private var favs: MutableList<String>
        get() = prefs.getString("favs", "")!!.split(',').filter { it.isNotEmpty() }.toMutableList()
        set(v) = prefs.edit().putString("favs", v.joinToString(",")).apply()

    /**
     * The TV's physical inputs (HDMI, AV), one per port. A device found over HDMI-CEC appears as a child input of its
     * port; its name ("PlayStation 5") beats the port's, but the user's own name for the port beats both.
     */
    private fun loadInputs(): List<Input> {
        val tim = tvInputs ?: return emptyList()
        val all = tim.tvInputList
        return all.filter { it.isPassthroughInput && it.parentId == null }.map { port ->
            val devices = all.filter { it.parentId == port.id }
            val label = port.loadCustomLabel(this)?.toString()?.takeIf { it.isNotBlank() }
                ?: devices.firstOrNull()?.let { (it.loadCustomLabel(this) ?: it.loadLabel(this)).toString() }
                ?: port.loadLabel(this).toString()
            // The best of the port and its devices: CONNECTED (0) < CONNECTED_STANDBY (1) < DISCONNECTED (2).
            Input(port.id, label, (devices + port).minOf { tim.getInputState(it.id) })
        }
    }

    /** Rebuilds every section and puts focus back on [focusTag] ("f:pkg", "i:input" or "a:pkg") if it still exists. */
    private fun render(focusTag: String?) {
        val inputs = loadInputs()
        inputRow.removeAllViews()
        inputs.forEachIndexed { i, input ->
            inputRow.addView(inputTile(input), LinearLayout.LayoutParams(tileW, tileH).apply { if (i > 0) marginStart = dp(16) })
        }
        (if (inputs.isEmpty()) View.GONE else View.VISIBLE).let { inputLabel.visibility = it; inputRow.visibility = it }

        val apps = loadApps()
        val byPkg = apps.associateBy { it.pkg }
        val favList = favs.filter { it in byPkg }

        favRow.removeAllViews()
        favList.forEachIndexed { i, pkg ->
            favRow.addView(tile(byPkg.getValue(pkg), "f:"), LinearLayout.LayoutParams(tileW, tileH).apply { if (i > 0) marginStart = dp(16) })
        }
        favHint.visibility = if (favList.isEmpty()) View.VISIBLE else View.GONE

        grid.removeAllViews()
        apps.chunked(5).forEach { chunk ->
            grid.addView(LinearLayout(this).apply {
                clipChildren = false
                chunk.forEachIndexed { i, app ->
                    addView(tile(app, "a:"), LinearLayout.LayoutParams(tileW, tileH).apply { if (i > 0) marginStart = dp(16) })
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10); bottomMargin = dp(6) })
        }

        val target = focusTag?.let { window.decorView.findViewWithTag<View>(it) }
            ?: favRow.getChildAt(0) ?: (grid.getChildAt(0) as? ViewGroup)?.getChildAt(0)
        target?.requestFocus()
    }

    /** The shared 16:9 key: raised, rounded, orange edge and a small lift on focus. */
    private fun key(tag: String, description: String) = FrameLayout(this).apply {
        this.tag = tag
        isFocusable = true
        contentDescription = description
        val radius = dp(12).toFloat()
        background = GradientDrawable().apply { cornerRadius = radius; setColor(color(R.color.raised)) }
        foreground = focusRing(radius)
        clipToOutline = true
        setOnFocusChangeListener { v, has ->
            v.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).translationZ(if (has) dp(8).toFloat() else 0f).setDuration(120).start()
        }
    }

    private fun inputTile(input: Input): View = key("i:" + input.id, input.label).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            // Empty ports stay in place, dimmed, so HDMI 1–3 keep their order.
            alpha = if (input.plugged) 1f else 0.5f
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_input_hdmi)
                setColorFilter(color(R.color.legend))
            }, LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(legend(input.label, 15f, R.color.legend).apply {
                maxLines = 1
                gravity = Gravity.CENTER
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(8), dp(4), dp(8), 0)
            })
            addView(legend(getString(when (input.state) {
                TvInputManager.INPUT_STATE_CONNECTED -> R.string.input_connected
                TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> R.string.input_standby
                else -> R.string.input_empty
            }), 12f, R.color.legend_secondary))
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // The TV's own input player shows the picture, as with Google TV's Inputs menu.
        setOnClickListener { open(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.id))) }
    }

    private fun tile(app: App, section: String): View = key(section + app.pkg, app.label).apply {
        if (app.banner != null) {
            addView(ImageView(context).apply {
                setImageDrawable(app.banner)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        } else {
            // Phone apps have no banner: their icon and name on the raised key.
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(ImageView(context).apply { setImageDrawable(app.icon) }, LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(legend(app.label, 13f, R.color.legend).apply {
                    maxLines = 1
                    gravity = Gravity.CENTER
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(8), dp(6), dp(8), 0)
                })
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        setOnClickListener { launch(app.pkg) }
        setOnLongClickListener { menu(app, section); true }
    }

    private fun launch(pkg: String) {
        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) toastCantOpen() else open(i)
    }

    private fun open(i: Intent) {
        try { startActivity(i) } catch (e: ActivityNotFoundException) { toastCantOpen() }
    }

    private fun toastCantOpen() = Toast.makeText(this, R.string.cant_open, Toast.LENGTH_SHORT).show()

    private fun menu(app: App, section: String) {
        val list = favs
        val at = list.indexOf(app.pkg)
        val items = mutableListOf<Pair<Int, () -> Unit>>()
        if (at < 0) {
            items += R.string.add_favourite to { favs = (list + app.pkg).toMutableList(); render("f:" + app.pkg) }
        } else {
            if (at > 0) items += R.string.move_left to { favs = list.apply { add(at - 1, removeAt(at)) }; render("f:" + app.pkg) }
            if (at < list.size - 1) items += R.string.move_right to { favs = list.apply { add(at + 1, removeAt(at)) }; render("f:" + app.pkg) }
            items += R.string.remove_favourite to { favs = list.apply { remove(app.pkg) }; render(if (section == "a:") "a:" + app.pkg else null) }
        }
        val pkgUri = Uri.fromParts("package", app.pkg, null)
        items += R.string.app_info to { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)) }
        items += R.string.uninstall to { open(Intent(Intent.ACTION_DELETE, pkgUri)) }

        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(items.map { getString(it.first) }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }
}
