package dev.ember

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
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
import android.animation.LayoutTransition
import android.text.format.DateFormat
import java.util.Locale
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** The home screen: a top bar, a favourites row, the TV's inputs and every app, all driven by the remote's D-pad. */
class Home : Activity() {

    private class Input(val id: String, val label: String, val state: Int) {
        val plugged get() = state != TvInputManager.INPUT_STATE_DISCONNECTED
    }

    private lateinit var favRow: LinearLayout
    private lateinit var favScroll: HorizontalScrollView
    private lateinit var favHint: TextView
    private lateinit var caption: LinearLayout
    private lateinit var captionName: TextView
    private lateinit var grid: LinearLayout
    private lateinit var inputLabel: View
    private lateinit var inputRow: LinearLayout
    private lateinit var allLabel: TextView
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

        favRow = LinearLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(0, dp(6), 0, dp(6)) }
        favHint = legend(getString(R.string.no_favourites), 13f, R.color.legend_secondary)
        favScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(favRow)
            setOnScrollChangeListener { _, _, _, _, _ -> placeCaption() }
        }
        // The focused favourite's name sits in a fixed-height line, so moving along the row moves nothing below it.
        captionName = legend("", 13f).oneLine().apply { typeface = MEDIUM }
        caption = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.INVISIBLE
            setPadding(dp(2), dp(4), 0, 0)
            addView(captionName)
            addView(legend(getString(R.string.hold_for_options), 9f, R.color.legend_secondary))
        }
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        inputLabel = heading(R.string.inputs)
        inputRow = LinearLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(0, dp(4), 0, dp(4)) }
        allLabel = heading(R.string.all_apps)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(sideMargin, dp(24), sideMargin, dp(27))
            addView(header())
            addView(heading(R.string.favourites))
            addView(favHint)
            addView(favScroll)
            addView(caption, LinearLayout.LayoutParams(tileW, dp(34)))
            addView(inputLabel)
            addView(inputRow)
            addView(allLabel)
            addView(grid)
        }
        setContentView(FrameLayout(this).apply {
            background()?.let { addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
            if (p[BACKGROUND] != "plain") addView(View(context).apply { background = dimming(p.int(DIM)) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
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
            "image" -> if (own.exists()) decodeSized(own, resources.displayMetrics.widthPixels) else null
            else -> null
        }
        return ImageView(this).apply {
            if (bitmap != null) setImageBitmap(bitmap) else setImageResource(R.drawable.background)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
    }

    /** The date on the left; Search, Settings and the time on the right. */
    private fun header() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(32)
        clipChildren = false
        layoutTransition = slide() // a key's name opening slides its neighbour along instead of jumping it
        addView(TextClock(context).apply {
            DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM").let { format12Hour = it; format24Hour = it }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.legend_on_picture))
            onPicture()
        })
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(iconKey(R.drawable.ic_search, R.string.search) { search() })
        addView(iconKey(R.drawable.ic_tune, R.string.settings) { open(Intent(context, EmberSettings::class.java)) },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        val clock = prefs[CLOCK]
        if (clock != "off") addView(TextClock(context).apply {
            // TextClock picks the TV's own 12/24-hour setting; a fixed choice sets both formats alike.
            format12Hour = if (clock == "24") "HH:mm" else "h:mm"
            format24Hour = if (clock == "12") "h:mm" else "HH:mm"
            typeface = LIGHT
            fontFeatureSettings = "tnum" // even-width digits, so the clock doesn't twitch each minute
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(getColor(R.color.legend))
            onPicture()
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(16) })
    }

    /** A soft drop shadow that keeps text readable on a bright own picture. */
    private fun TextView.onPicture() = setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(), 0x73000000)

    /** Size changes animate (160 ms, no delay) instead of snapping. */
    private fun slide() = LayoutTransition().apply {
        enableTransitionType(LayoutTransition.CHANGING)
        setDuration(160)
        listOf(LayoutTransition.APPEARING, LayoutTransition.DISAPPEARING, LayoutTransition.CHANGE_APPEARING,
            LayoutTransition.CHANGE_DISAPPEARING, LayoutTransition.CHANGING).forEach { setStartDelay(it, 0) }
    }

    /** A round icon key whose name opens out beside the icon while the remote is on it. */
    private fun iconKey(iconId: Int, nameId: Int, onClick: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        contentDescription = getString(nameId)
        minimumHeight = dp(28); minimumWidth = dp(28)
        setPadding(dp(7), 0, dp(7), 0)
        background = keyBackground(14, 0xB8363632.toInt())
        layoutTransition = slide()
        addView(icon(iconId), LinearLayout.LayoutParams(dp(14), dp(14)))
        val name = legend(getString(nameId), 11f).apply { typeface = MEDIUM; visibility = View.GONE; setPadding(dp(6), 0, dp(4), 0) }
        addView(name)
        lift() // the same lift and accent glow as the tiles
        setOnFocusChangeListener { v, has -> v.animateFocus(has); name.visibility = if (has) View.VISIBLE else View.GONE }
        setOnClickListener { onClick() }
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
            inputRow.addView(inputKey(input), LinearLayout.LayoutParams(tileW, dp(48)).apply { if (i > 0) marginStart = dp(16) })
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
        favScroll.visibility = if (favList.isEmpty()) View.GONE else View.VISIBLE
        caption.visibility = if (favList.isEmpty()) View.GONE else View.INVISIBLE

        allLabel.text = getString(R.string.all_apps) + "  ·  " + apps.size
        grid.removeAllViews()
        apps.chunked(cols).forEach { chunk ->
            grid.addView(LinearLayout(this).apply {
                clipChildren = false
                chunk.forEachIndexed { i, app ->
                    addView(tile(app, "a:"), LinearLayout.LayoutParams(tileW, tileH).apply { if (i > 0) marginStart = dp(16) })
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(8) })
        }

        val target = focusTag?.let { window.decorView.findViewWithTag<View>(it) }
            ?: favRow.getChildAt(0) ?: (grid.getChildAt(0) as? ViewGroup)?.getChildAt(0)
        target?.requestFocus()
    }

    /** Lines the caption up under the focused favourite. */
    private fun placeCaption() {
        val f = currentFocus?.takeIf { it.parent === favRow } ?: return
        caption.translationX = (f.left - favScroll.scrollX).toFloat()
    }

    /** A plugged-in device: its name and whether it is on, as a wide key. */
    private fun inputKey(input: Input): View = LinearLayout(this).apply {
        tag = "i:" + input.id
        isFocusable = true
        contentDescription = input.label
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(12), 0)
        background = if (input.plugged) rounded(0xD62A2A28.toInt(), 11)
            else rounded(0x801C1B19.toInt(), 11).apply { setStroke(dp(1), 0x24F2F0EB) }
        foreground = focusRing(11)
        lift()
        addView(icon(R.drawable.ic_input_hdmi), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(legend(input.label, 11f).oneLine().apply { typeface = MEDIUM })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                val (status, dot) = when (input.state) {
                    TvInputManager.INPUT_STATE_CONNECTED -> R.string.input_connected to 0xFF6FCF8E.toInt()
                    TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> R.string.input_standby to 0xFFE8B04A.toInt()
                    else -> R.string.input_empty to 0xFF77746C.toInt()
                }
                addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dot) } },
                    LinearLayout.LayoutParams(dp(5), dp(5)).apply { marginEnd = dp(4) })
                addView(legend(getString(status), 9f, R.color.legend_secondary))
            })
        })
        // The TV's own input player shows the picture, as with Google TV's Inputs menu.
        setOnClickListener { open(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.id))) }
    }

    private fun tile(app: App, section: String): View = appTile(app, section + app.pkg).apply {
        if (section == "f:") setOnFocusChangeListener { v, has ->
            v.animateFocus(has)
            caption.visibility = if (has) View.VISIBLE else View.INVISIBLE
            if (has) { captionName.text = app.label; v.post { placeCaption() } }
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

    /** The app menu: a panel from the right with the app's banner and what can be done with it. */
    private fun menu(app: App, section: String) {
        val p = prefs
        val list = p.list("favs").toMutableList()
        val at = list.indexOf(app.pkg)
        fun favs(v: List<String>, focus: String?) { p.putList("favs", v); render(focus) }
        val items = mutableListOf<Triple<Int, Int, () -> Unit>>()
        if (at < 0) {
            items += Triple(R.drawable.ic_star, R.string.add_favourite) { favs(list + app.pkg, "f:" + app.pkg) }
        } else {
            if (at > 0) items += Triple(R.drawable.ic_arrow_left, R.string.move_left) { favs(list.apply { add(at - 1, removeAt(at)) }, "f:" + app.pkg) }
            if (at < list.size - 1) items += Triple(R.drawable.ic_arrow_right, R.string.move_right) { favs(list.apply { add(at + 1, removeAt(at)) }, "f:" + app.pkg) }
            items += Triple(R.drawable.ic_star_off, R.string.remove_favourite) { favs(list - app.pkg, if (section == "a:") "a:" + app.pkg else null) }
        }
        items += Triple(R.drawable.ic_edit, R.string.rename) { rename(app, section) }
        // Hidden apps come back from Settings → Hidden apps.
        items += Triple(R.drawable.ic_hide, R.string.hide) { p.putList("hidden", p.list("hidden") + app.pkg); render(null) }
        val pkgUri = Uri.fromParts("package", app.pkg, null)
        items += Triple(R.drawable.ic_info, R.string.app_info) { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)) }
        items += Triple(R.drawable.ic_delete, R.string.uninstall) { open(Intent(Intent.ACTION_DELETE, pkgUri)) }

        lateinit var panel: Dialog
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(32), dp(28), dp(32))
            addView(FrameLayout(context).apply {
                background = rounded(tint(app), 11)
                clipToOutline = true
                addView(tileFace(app, 64, 18f), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }, LinearLayout.LayoutParams(MATCH_PARENT, dp(160)))
            addView(legend(app.label, 20f).oneLine().apply { typeface = MEDIUM; setPadding(0, dp(14), 0, dp(2)) })
            addView(legend(if (at < 0) getString(R.string.not_in_favourites) else getString(R.string.in_favourites, at + 1, list.size),
                10f, R.color.legend_secondary).apply { setPadding(0, 0, 0, dp(12)) })
            items.forEach { (iconId, text, action) ->
                addView(actionRow(iconId, getString(text), if (text == R.string.uninstall) R.color.danger else R.color.legend) {
                    panel.dismiss(); action()
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(3) })
            }
        }
        panel = sidePanel(340, ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(content) })
        panel.show()
        content.getChildAt(3)?.requestFocus() // the first action; a TV dialog otherwise starts with nothing lit
    }

    /** A card at the top, so the TV's keyboard fits below it. An empty name puts the app's own back. */
    private fun rename(app: App, section: String) {
        val dialog = Dialog(this)
        fun save(name: String) {
            prefs.edit().apply { if (name.isEmpty() || name == app.title) remove("label:${app.pkg}") else putString("label:${app.pkg}", name) }.apply()
            dialog.dismiss()
            render(section + app.pkg)
        }
        val field = EditText(this).apply {
            setText(app.label)
            setSelectAllOnFocus(true)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(getColor(R.color.legend))
            setPadding(dp(14), 0, dp(14), 0)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), rounded(getColor(R.color.field), 10).apply { setStroke(dp(2), accent) })
                addState(intArrayOf(), rounded(getColor(R.color.field), 10))
            }
            setOnEditorActionListener { v, _, _ -> save(v.text.toString().trim()); true }
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            background = rounded(getColor(R.color.housing), 16)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(FrameLayout(context).apply {
                    background = rounded(tint(app), 7)
                    clipToOutline = true
                    addView(tileFace(app, 20, 9f), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                }, LinearLayout.LayoutParams(dp(80), dp(45)).apply { marginEnd = dp(12) })
                addView(legend(getString(R.string.rename_title, app.label), 20f).oneLine().apply { typeface = MEDIUM })
            })
            addView(legend(getString(R.string.rename_label), 10f, R.color.legend_secondary).apply { setPadding(0, dp(14), 0, dp(6)) })
            addView(field, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))
            addView(legend(getString(R.string.rename_hint), 10f, R.color.legend_secondary).apply { setPadding(0, dp(10), 0, dp(14)) })
            addView(LinearLayout(context).apply {
                clipChildren = false
                addView(button(getString(R.string.save), true) { save(field.text.toString().trim()) })
                addView(button(getString(R.string.cancel), false) { dialog.dismiss() },
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
            })
        })
        dialog.window!!.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.TOP)
            setLayout(dp(480), WRAP_CONTENT)
            attributes = attributes.apply { y = dp(48) }
            setDimAmount(0.74f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
        dialog.show()
    }

    /** Vulcan's drawer search for the remote, full screen: type, then press down into the best matches. */
    private fun search() {
        val apps = shownApps()
        var matches = apps
        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        val count = legend("", 10f, R.color.legend_secondary)
        val title = heading(R.string.best_matches)
        val row = LinearLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(0, dp(8), 0, dp(8)) }
        val none = legend(getString(R.string.no_matches), 13f, R.color.legend_secondary)
        val w = tileW * 6 / 5
        fun show(q: String) {
            matches = if (q.isBlank()) apps
                else apps.map { it to matchScore(it.label, q) }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
            count.text = resources.getQuantityString(R.plurals.app_count, matches.size, matches.size)
            title.setText(if (q.isBlank()) R.string.all_apps else R.string.best_matches)
            none.visibility = if (matches.isEmpty()) View.VISIBLE else View.GONE
            row.removeAllViews()
            matches.take(12).forEachIndexed { i, app ->
                row.addView(appTile(app, "s:" + app.pkg).apply {
                    setOnClickListener { dialog.dismiss(); launch(app.pkg) }
                }, LinearLayout.LayoutParams(w, w * 9 / 16).apply { if (i > 0) marginStart = dp(16) })
            }
        }
        val field = EditText(this).apply {
            hint = getString(R.string.search_hint)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(getColor(R.color.legend))
            setHintTextColor(getColor(R.color.legend_secondary))
            background = null
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable) = show(s.toString())
            })
            // OK on the keyboard opens the best match.
            setOnEditorActionListener { _, _, _ -> matches.firstOrNull()?.let { dialog.dismiss(); launch(it.pkg) }; true }
        }
        dialog.setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(36), dp(48), 0)
            background = ColorDrawable(0xF21C1B19.toInt())
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), 0, dp(20), 0)
                background = rounded(getColor(R.color.housing), 28).apply { setStroke(dp(2), accent) }
                addView(icon(R.drawable.ic_search, getColor(R.color.legend_secondary)), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
                addView(field, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(count)
            }, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)))
            addView(title.apply { setPadding(0, dp(22), 0, dp(4)) })
            addView(none)
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                clipChildren = false
                clipToPadding = false
                addView(row)
            })
            addView(legend(getString(R.string.search_help), 10f, R.color.legend_secondary).apply { setPadding(0, dp(16), 0, 0) })
        })
        dialog.window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        show("")
        dialog.show()
    }
}

/** Where Settings → Background → Own picture keeps its copy. */
const val BACKGROUND_FILE = "background"

/** The largest power-of-two sample size that keeps a [side]-pixel image at least [target] pixels. */
fun sampleSize(side: Int, target: Int): Int = Integer.highestOneBit((side / target.coerceAtLeast(1)).coerceAtLeast(1))

/** [f] decoded at about [width] pixels wide: a 4000 px photo at full size would hold ~60 MB on a 2 GB TV. */
fun decodeSized(f: File, width: Int) = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    o.inSampleSize = sampleSize(maxOf(o.outWidth, o.outHeight), width)
    o.inJustDecodeBounds = false
    BitmapFactory.decodeFile(f.path, o)
}.getOrNull()
