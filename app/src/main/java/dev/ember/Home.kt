package dev.ember

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** The home screen: a clock, a favourites row, the TV's inputs and every app, all driven by the remote's D-pad. */
class Home : Activity() {

    private class Input(val id: String, val label: String, val state: Int) {
        val plugged get() = state != TvInputManager.INPUT_STATE_DISCONNECTED
    }

    private lateinit var favRow: LinearLayout
    private lateinit var favHint: TextView
    private lateinit var grid: LinearLayout
    private lateinit var inputLabel: View
    private lateinit var inputRow: LinearLayout
    private var cols = 5
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

    /** A change made in Settings (while Home is hidden) rebuilds Home when it comes back. Held: prefs keep listeners weakly. */
    private var stale = false
    private var shown = false
    private val prefsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> if (!shown) stale = true }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val p = prefs
        // TV overscan-safe margins (Android TV guidelines: 48dp sides, 27dp top and bottom).
        val sideMargin = dp(48)
        val gap = dp(16)
        cols = p.int(COLS)
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
            background()?.let { addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
            if (p[BACKGROUND] != "plain") {
                // Dims the picture most behind the clock and the app rows, so legends and banners stay readable.
                val a = p.int(DIM) * 255 / 100
                fun dim(f: Float) = (a * f).toInt().coerceAtMost(255) shl 24 or 0x1E1E1C
                addView(View(context).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(dim(1.4f), dim(0.7f), dim(1.7f)))
                }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
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
        p.registerOnSharedPreferenceChangeListener(prefsChanged)
        render(null)
    }

    override fun onResume() {
        super.onResume()
        shown = true
        if (stale) { stale = false; recreate() }
    }

    override fun onPause() {
        shown = false
        super.onPause()
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsChanged)
        tvInputs?.unregisterCallback(inputsChanged)
        unregisterReceiver(packagesChanged)
        super.onDestroy()
    }

    // Home has nowhere to go back to.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}

    // The remote's search key, where it has one, searches the apps.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_SEARCH) { search(); true } else super.onKeyDown(keyCode, event)

    /** The picture behind everything: the built-in one, the user's own (Settings → Background), or none on plain. */
    private fun background(): View? {
        val own = File(filesDir, BACKGROUND_FILE)
        val bitmap = when (prefs[BACKGROUND]) {
            "plain" -> return null
            "image" -> if (own.exists()) decodeScreenSized(own) else null
            else -> null
        }
        return ImageView(this).apply {
            if (bitmap != null) setImageBitmap(bitmap) else setImageResource(R.drawable.background)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
    }

    /** [f] decoded at about screen size: a 4000 px photo at full size would hold ~60 MB on a 2 GB TV. */
    private fun decodeScreenSized(f: File) = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        o.inSampleSize = sampleSize(maxOf(o.outWidth, o.outHeight), resources.displayMetrics.widthPixels)
        o.inJustDecodeBounds = false
        BitmapFactory.decodeFile(f.path, o)
    }.getOrNull()

    private fun header() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        val clock = prefs[CLOCK]
        if (clock != "off") addView(TextClock(context).apply {
            // TextClock picks the TV's own 12/24-hour setting; a fixed choice sets both formats alike.
            format12Hour = if (clock == "24") "HH:mm" else "h:mm"
            format24Hour = if (clock == "12") "h:mm" else "HH:mm"
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f)
            letterSpacing = -0.02f
            setTextColor(getColor(R.color.legend))
        })
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(pill(R.string.search) { search() })
        addView(pill(R.string.settings) { open(Intent(context, EmberSettings::class.java)) },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(16) })
    }

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

    /** The apps Home shows: all but the hidden ones. */
    private fun shownApps(): List<App> = prefs.list("hidden").toSet().let { hidden -> launchables().filter { it.pkg !in hidden } }

    /** Rebuilds every section and puts focus back on [focusTag] ("f:pkg", "i:input" or "a:pkg") if it still exists. */
    private fun render(focusTag: String?) {
        val p = prefs
        val inputs = if (p.on(INPUTS)) loadInputs().filter { it.plugged || p.on(EMPTY_PORTS) } else emptyList()
        inputRow.removeAllViews()
        inputs.forEachIndexed { i, input ->
            inputRow.addView(inputTile(input), LinearLayout.LayoutParams(tileW, tileH).apply { if (i > 0) marginStart = dp(16) })
        }
        (if (inputs.isEmpty()) View.GONE else View.VISIBLE).let { inputLabel.visibility = it; inputRow.visibility = it }

        val apps = shownApps()
        val byPkg = apps.associateBy { it.pkg }
        val favList = p.list("favs").filter { it in byPkg }

        favRow.removeAllViews()
        favList.forEachIndexed { i, pkg ->
            favRow.addView(tile(byPkg.getValue(pkg), "f:"), LinearLayout.LayoutParams(tileW, tileH).apply { if (i > 0) marginStart = dp(16) })
        }
        favHint.visibility = if (favList.isEmpty()) View.VISIBLE else View.GONE

        grid.removeAllViews()
        apps.chunked(cols).forEach { chunk ->
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

    /** The shared 16:9 key: raised, rounded, accent edge and a small lift on focus. */
    private fun key(tag: String, description: String) = FrameLayout(this).apply {
        this.tag = tag
        isFocusable = true
        contentDescription = description
        val radius = dp(12).toFloat()
        background = GradientDrawable().apply { cornerRadius = radius; setColor(getColor(R.color.raised)) }
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
                setColorFilter(getColor(R.color.legend))
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
        // A banner says the app's own name; a renamed app shows its icon and the new name instead.
        if (app.banner != null && app.label == app.title) {
            addView(ImageView(context).apply {
                setImageDrawable(app.banner)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        } else {
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
        val p = prefs
        val list = p.list("favs").toMutableList()
        val at = list.indexOf(app.pkg)
        fun favs(v: List<String>, focus: String?) { p.putList("favs", v); render(focus) }
        val items = mutableListOf<Pair<Int, () -> Unit>>()
        if (at < 0) {
            items += R.string.add_favourite to { favs(list + app.pkg, "f:" + app.pkg) }
        } else {
            if (at > 0) items += R.string.move_left to { favs(list.apply { add(at - 1, removeAt(at)) }, "f:" + app.pkg) }
            if (at < list.size - 1) items += R.string.move_right to { favs(list.apply { add(at + 1, removeAt(at)) }, "f:" + app.pkg) }
            items += R.string.remove_favourite to { favs(list - app.pkg, if (section == "a:") "a:" + app.pkg else null) }
        }
        items += R.string.rename to { rename(app, section) }
        // Hidden apps come back from Settings → Hidden apps.
        items += R.string.hide to { p.putList("hidden", p.list("hidden") + app.pkg); render(null) }
        val pkgUri = Uri.fromParts("package", app.pkg, null)
        items += R.string.app_info to { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)) }
        items += R.string.uninstall to { open(Intent(Intent.ACTION_DELETE, pkgUri)) }

        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(items.map { getString(it.first) }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    /** An empty name puts the app's own back. */
    private fun rename(app: App, section: String) {
        val field = EditText(this).apply { setText(app.label); setSelectAllOnFocus(true); isSingleLine = true }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(field) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = field.text.toString().trim()
                prefs.edit().apply { if (name.isEmpty() || name == app.title) remove("label:${app.pkg}") else putString("label:${app.pkg}", name) }.apply()
                render(section + app.pkg)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Vulcan's drawer search for the remote: type, then press down into the best matches. */
    private fun search() {
        val apps = shownApps()
        var matches = apps
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, apps.map { it.label }.toMutableList())
        val field = EditText(this).apply { hint = getString(R.string.search_hint); isSingleLine = true }
        val results = ListView(this).apply { this.adapter = adapter }
        val dialog = AlertDialog.Builder(this)
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(16), dp(24), 0)
                addView(field)
                addView(results, LinearLayout.LayoutParams(MATCH_PARENT, dp(320)))
            })
            .create()
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable) {
                val q = s.toString()
                matches = if (q.isBlank()) apps
                    else apps.map { it to matchScore(it.label, q) }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
                adapter.clear(); adapter.addAll(matches.map { it.label })
            }
        })
        // OK on the keyboard opens the best match.
        field.setOnEditorActionListener { _, _, _ -> matches.firstOrNull()?.let { dialog.dismiss(); launch(it.pkg) }; true }
        results.setOnItemClickListener { _, _, i, _ -> dialog.dismiss(); launch(matches[i].pkg) }
        dialog.show()
    }
}

/** Where Settings → Background → Own picture keeps its copy. */
const val BACKGROUND_FILE = "background"

/** The largest power-of-two sample size that keeps a [side]-pixel image at least [target] pixels. */
fun sampleSize(side: Int, target: Int): Int = Integer.highestOneBit((side / target.coerceAtLeast(1)).coerceAtLeast(1))
