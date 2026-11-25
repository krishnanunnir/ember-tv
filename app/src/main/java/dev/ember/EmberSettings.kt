package dev.ember

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Ember's own settings: sections on the left, their keys on the right, all D-pad driven. */
class EmberSettings : Activity() {

    private class Section(val title: Int, val icon: Int, val build: (() -> Unit)?)

    private val sections = listOf(
        Section(R.string.set_tv, R.drawable.ic_tv, null),
        Section(R.string.sec_look, R.drawable.ic_palette) { look() },
        Section(R.string.sec_home, R.drawable.ic_home) { homeSection() },
        Section(R.string.sec_home_button, R.drawable.ic_home_button) { homeButton() },
        Section(R.string.sec_backup, R.drawable.ic_backup) { backupSection() },
        Section(R.string.sec_about, R.drawable.ic_info) { about() },
    )
    private var current = 1
    private val navItems = mutableListOf<LinearLayout>()
    private lateinit var pane: LinearLayout

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(36), dp(20), dp(36))
            setBackgroundColor(getColor(R.color.nav))
            addView(legend(getString(R.string.settings_title), 28f).apply { typeface = LIGHT },
                LinearLayout.LayoutParams(WRAP_CONTENT, dp(52)))
            sections.forEachIndexed { i, s -> addView(navItem(i, s), LinearLayout.LayoutParams(MATCH_PARENT, dp(38)).apply { topMargin = dp(4) }) }
        }
        pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(36), dp(48), dp(36))
            clipChildren = false
            clipToPadding = false
        }
        setContentView(LinearLayout(this).apply {
            addView(nav, LinearLayout.LayoutParams(dp(280), MATCH_PARENT))
            addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; clipChildren = false; addView(pane) },
                LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        })
        navItems[current].requestFocus()
    }

    // Rebuilt each time: the Home button watcher may have been switched on in Accessibility meanwhile.
    override fun onResume() {
        super.onResume()
        build(currentFocus?.tag as? String)
    }

    private fun navItem(i: Int, s: Section) = LinearLayout(this).apply {
        id = View.generateViewId()
        isFocusable = true
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(12), 0)
        background = keyBackground(9, Color.TRANSPARENT)
        val ic = icon(s.icon, getColor(R.color.legend_secondary))
        val text = legend(getString(s.title), 13f, R.color.legend_secondary)
        addView(ic, LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(10) })
        addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (s.build == null) addView(icon(R.drawable.ic_open, getColor(R.color.legend_secondary)), LinearLayout.LayoutParams(dp(11), dp(11)))
        tag = "nav$i"
        // Moving onto a section shows it; TV settings is a door to the TV's own settings instead.
        setOnFocusChangeListener { _, has -> if (has && s.build != null && current != i) { current = i; build(null) } }
        setOnClickListener { if (s.build == null) open(Intent(Settings.ACTION_SETTINGS)) else pane.getChildAt(1)?.requestFocus() }
        // Right always enters the section at its first row, not whichever row sits nearest.
        setOnKeyListener { _, code, e ->
            code == KeyEvent.KEYCODE_DPAD_RIGHT && e.action == KeyEvent.ACTION_DOWN && s.build != null && pane.getChildAt(1)?.requestFocus() == true
        }
        navItems += this
    }

    /** Shows the current section and puts focus back on [focus] if it is there. */
    private fun build(focus: String?) {
        navItems.forEachIndexed { i, v ->
            val on = i == current
            v.isSelected = on
            (v.getChildAt(0) as ImageView).setColorFilter(if (on) accent else getColor(R.color.legend_secondary))
            (v.getChildAt(1) as TextView).apply {
                setTextColor(getColor(if (on) R.color.legend else R.color.legend_secondary))
                typeface = if (on) MEDIUM else android.graphics.Typeface.DEFAULT
            }
        }
        pane.removeAllViews()
        pane.addView(legend(getString(sections[current].title), 20f).apply { gravity = Gravity.CENTER_VERTICAL },
            LinearLayout.LayoutParams(WRAP_CONTENT, dp(32)).apply { bottomMargin = dp(12) })
        sections[current].build!!()
        focus?.let { pane.findViewWithTag<View>(it) }?.requestFocus()
    }

    /** A key: title and its value on the left, [trailing] on the right. Left goes back to the section list. */
    private fun row(tag: String, title: Int, value: String, trailing: View? = null, into: LinearLayout = pane, onClick: () -> Unit) =
        LinearLayout(this).apply {
            this.tag = tag
            isFocusable = true
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = keyBackground(11)
            nextFocusLeftId = navItems[current].id
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(legend(getString(title), 14f))
                addView(legend(value, 10.5f, R.color.legend_secondary).apply { setPadding(0, dp(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            trailing?.let { addView(it) }
            setOnClickListener { onClick() }
            into.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(7) })
        }

    private fun chevron() = icon(R.drawable.ic_chevron_right, getColor(R.color.legend_secondary)).apply { layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)) }

    private fun choice(c: Choice, into: LinearLayout = pane, trailing: View? = null, pick: (String) -> Unit = { set(c, it) }) {
        val at = c.values.indexOf(prefs[c])
        row(c.key, c.title, choiceName(c, at), trailing, into) {
            AlertDialog.Builder(this)
                .setTitle(c.title)
                .setSingleChoiceItems(c.values.indices.map { choiceName(c, it) }.toTypedArray(), at) { d, i -> d.dismiss(); pick(c.values[i]) }
                .show()
        }
    }

    private fun set(c: Choice, v: String) { prefs.edit().putString(c.key, v).apply(); build(c.key) }

    // ---- Look: the keys on the left, a live preview of Home on the right. ----

    private fun look() {
        val keys = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val p = prefs
        row(BACKGROUND.key, BACKGROUND.title, choiceName(BACKGROUND, BACKGROUND.values.indexOf(p[BACKGROUND])),
            thumb(p[BACKGROUND]).apply { layoutParams = LinearLayout.LayoutParams(dp(72), dp(40)) }, keys) { backgroundPanel() }
        if (p[BACKGROUND] != "plain") dimRow(keys)
        row(ACCENT.key, ACCENT.title, choiceName(ACCENT, ACCENT.values.indexOf(p[ACCENT])), View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent); setStroke(dp(1), 0x66F2F0EB) }
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        }, keys) { accentPanel() }
        iconPackRow(keys)

        pane.addView(LinearLayout(this).apply {
            addView(keys, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(preview(), LinearLayout.LayoutParams(dp(224), dp(126)))
                addView(legend(getString(R.string.preview_caption), 10f, R.color.legend_secondary).apply { setPadding(0, dp(7), 0, 0) })
            }, LinearLayout.LayoutParams(dp(224), WRAP_CONTENT).apply { marginStart = dp(24) })
        })
    }

    /** Dimming changes in place with left and right; at 0 left goes back to the sections. */
    private fun dimRow(into: LinearLayout) {
        val v = prefs.int(DIM)
        val trailing = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(icon(R.drawable.ic_chevron_left, getColor(R.color.legend_secondary)), LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(6) })
            DIM.values.forEach { step ->
                addView(View(context).apply { background = rounded(if (step.toInt() <= v) getColor(R.color.legend) else 0xFF4A4A45.toInt(), 1.5) },
                    LinearLayout.LayoutParams(dp(5), dp(14)).apply { marginEnd = dp(2) })
            }
            addView(legend("$v%", 12f).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(34), WRAP_CONTENT))
            addView(icon(R.drawable.ic_chevron_right, getColor(R.color.legend_secondary)), LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginStart = dp(6) })
        }
        row(DIM.key, DIM.title, getString(R.string.dim_info), trailing, into) {}.setOnKeyListener { _, code, e ->
            val step = when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> -10; KeyEvent.KEYCODE_DPAD_RIGHT -> 10; else -> return@setOnKeyListener false }
            val next = v + step
            if (next !in 0..90) return@setOnKeyListener step > 0 // past 90 stays; below 0 goes left
            if (e.action == KeyEvent.ACTION_DOWN) set(DIM, "$next")
            true
        }
    }

    /** Home in miniature: the background, its dimming and a focused tile in the accent colour. */
    private fun preview() = FrameLayout(this).apply {
        val p = prefs
        background = rounded(getColor(R.color.housing), 10)
        clipToOutline = true
        if (p[BACKGROUND] != "plain") {
            addView(thumb(p[BACKGROUND]), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(View(context).apply { background = dimming(p.int(DIM)) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        addView(TextClock(context).apply {
            format12Hour = "h:mm"; format24Hour = "HH:mm"
            typeface = LIGHT
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
            setTextColor(getColor(R.color.legend))
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(11), 0) })
        addView(LinearLayout(context).apply {
            clipChildren = false
            listOf(0xFF4B3F72, 0xFF1F5A63, 0xFF7A2E2E, 0xFF6B5420).forEachIndexed { i, c ->
                addView(View(context).apply {
                    background = rounded(c.toInt(), 3).apply { if (i == 0) setStroke(dp(1.5), accent) }
                    if (i == 0) { scaleX = 1.06f; scaleY = 1.06f }
                }, LinearLayout.LayoutParams(dp(40), dp(22)).apply { if (i > 0) marginStart = dp(4) })
            }
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(11), dp(28), 0, 0) })
        addView(LinearLayout(context).apply {
            listOf(0xFF24475E, 0xFF2D4E6E, 0xFF1F5A63, 0xFF1F4D3F, 0xFF6B5420).forEachIndexed { i, c ->
                addView(View(context).apply { background = rounded(c.toInt(), 2) },
                    LinearLayout.LayoutParams(0, dp(22), 1f).apply { if (i > 0) marginStart = dp(4) })
            }
        }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(11), dp(70), dp(11), 0) })
    }

    /** A small picture of background [which]: the built-in picture, the own one, or the plain colour. */
    private fun thumb(which: String): View = when (which) {
        "plain" -> View(this).apply { background = rounded(getColor(R.color.housing), 6).apply { setStroke(dp(1), 0x1FFFFFFF) } }
        else -> ImageView(this).apply {
            val own = File(filesDir, BACKGROUND_FILE).takeIf { which == "image" && it.exists() }?.let { decodeSized(it, dp(224)) }
            if (own != null) setImageBitmap(own) else setImageResource(R.drawable.background)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Color.TRANSPARENT, 6)
            clipToOutline = true
        }
    }

    private fun panelTitle(title: Int, info: Int?) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(legend(getString(title), 22f))
        info?.let { addView(legend(getString(it), 10.5f, R.color.legend_secondary).apply { setPadding(0, dp(4), 0, 0) }) }
        setPadding(0, 0, 0, dp(18))
    }

    /** Background: three cards, the one in use ticked. */
    private fun backgroundPanel() {
        lateinit var panel: Dialog
        val now = prefs[BACKGROUND]
        val canPick = hasPicker(Intent.ACTION_OPEN_DOCUMENT, "image/*")
        val cards = LinearLayout(this).apply { clipChildren = false }
        BACKGROUND.values.forEachIndexed { i, v ->
            cards.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                tag = "bg:$v"
                setPadding(dp(8), dp(8), dp(8), dp(10))
                background = keyBackground(11, 0xFF2E2E2B.toInt())
                lift()
                addView(if (v == "image" && !File(filesDir, BACKGROUND_FILE).exists()) FrameLayout(context).apply {
                    background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setStroke(dp(1), 0xFF6E6B64.toInt(), dp(4).toFloat(), dp(3).toFloat()) }
                    addView(icon(R.drawable.ic_image, getColor(R.color.legend_secondary)), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                } else thumb(v), LinearLayout.LayoutParams(MATCH_PARENT, dp(70)))
                addView(legend(choiceName(BACKGROUND, i), 12f).apply { typeface = MEDIUM; setPadding(0, dp(8), 0, dp(2)) })
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    if (v == now) addView(icon(R.drawable.ic_check), LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(4) })
                    addView(legend(getString(when {
                        v == now -> R.string.bg_in_use
                        v == "picture" -> R.string.bg_picture_info
                        v == "plain" -> R.string.bg_plain_info
                        canPick -> R.string.bg_image_info
                        else -> R.string.bg_no_picker
                    }), 9f, if (v == now) R.color.legend else R.color.legend_secondary))
                })
                setOnClickListener { panel.dismiss(); if (v == "image") pickImage() else set(BACKGROUND, v) }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(12) })
        }
        panel = sidePanel(500, LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(dp(32), dp(36), dp(32), dp(36))
            addView(panelTitle(R.string.set_background, null))
            addView(cards)
        })
        panel.show()
        cards.findViewWithTag<View>("bg:$now")?.requestFocus()
    }

    /** Accent colour: a swatch grid; the preview tile takes the colour the remote is on. */
    private fun accentPanel() {
        lateinit var panel: Dialog
        val now = prefs[ACCENT]
        val sample = View(this).apply { background = rounded(0xFF4B3F72.toInt(), 9).apply { setStroke(dp(2), accent) } }
        fun ring(c: Int) = (sample.background as GradientDrawable).setStroke(dp(2), c)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ACCENT.values.chunked(4).forEachIndexed { r, rowValues ->
            grid.addView(LinearLayout(this).apply {
                rowValues.forEachIndexed { j, hex ->
                    val c = Color.parseColor(hex)
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        isFocusable = true
                        tag = "ac:$hex"
                        setPadding(0, dp(6), 0, dp(6))
                        foreground = focusRing(12)
                        addView(FrameLayout(context).apply {
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL; setColor(c)
                                if (hex == now) setStroke(dp(2), getColor(R.color.legend)) else setStroke(dp(1), 0x1FFFFFFF)
                            }
                            if (hex == now) addView(icon(R.drawable.ic_check, onColor(c)), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                        }, LinearLayout.LayoutParams(dp(44), dp(44)))
                        addView(legend(choiceName(ACCENT, r * 4 + j), 9.5f).oneLine().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(4), dp(6), dp(4), 0) })
                        setOnFocusChangeListener { _, has -> if (has) ring(c) }
                        setOnClickListener { panel.dismiss(); set(ACCENT, hex) }
                    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                }
                repeat(4 - rowValues.size) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        panel = sidePanel(380, LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(36), dp(32), dp(36))
            addView(panelTitle(R.string.set_accent, R.string.accent_info))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = rounded(getColor(R.color.field), 12)
                addView(sample, LinearLayout.LayoutParams(dp(140), dp(79)))
                addView(View(context).apply { background = rounded(0xFF1F5A63.toInt(), 8) },
                    LinearLayout.LayoutParams(dp(100), dp(56)).apply { marginStart = dp(12) })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
            addView(grid)
        })
        panel.show()
        grid.findViewWithTag<View>("ac:$now")?.requestFocus()
    }

    private fun iconPackRow(into: LinearLayout) {
        val packs = listOf("" to getString(R.string.icon_pack_none)) + iconPacks(this)
        val current = prefs.getString("iconPack", "")
        val at = packs.indexOfFirst { it.first == current }.coerceAtLeast(0)
        row("iconPack", R.string.set_icon_pack, packs[at].second + " · " + getString(R.string.set_icon_pack_info), chevron(), into) {
            AlertDialog.Builder(this)
                .setTitle(R.string.set_icon_pack)
                .setSingleChoiceItems(packs.map { it.second }.toTypedArray(), at) { d, i ->
                    d.dismiss(); prefs.edit().putString("iconPack", packs[i].first).apply(); build("iconPack")
                }
                .show()
        }
    }

    // ---- The other sections. ----

    private fun homeSection() {
        choice(CLOCK)
        choice(COLS)
        choice(INPUTS)
        if (prefs.on(INPUTS)) choice(EMPTY_PORTS)
        val hidden = prefs.list("hidden").size
        row("hidden", R.string.set_hidden, resources.getQuantityString(R.plurals.hidden_count, hidden, hidden), chevron()) { hiddenApps() }
    }

    private fun homeButton() =
        row("watcher", R.string.set_watcher, getString(if (watcherOn()) R.string.watcher_on else R.string.watcher_off), chevron()) {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

    private fun backupSection() {
        row("backup", R.string.set_backup, getString(R.string.set_backup_info)) { backup() }
        row("restore", R.string.set_restore, getString(R.string.set_restore_info)) { restore() }
    }

    private fun about() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        row("about", R.string.set_version, getString(R.string.about_info, version)) { notices() }
    }

    /** Every app as a tile; hidden ones grey with a badge. OK shows or hides one at once. */
    private fun hiddenApps() {
        val apps = launchables()
        val hidden = prefs.list("hidden").toMutableSet()
        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        val summary = legend("", 12f, R.color.legend_secondary)
        fun count() { summary.text = getString(R.string.hidden_summary, hidden.size, apps.size) }
        count()
        val cols = 5
        val w = (resources.displayMetrics.widthPixels - dp(96) - dp(16) * (cols - 1)) / cols
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        apps.chunked(cols).forEach { chunk ->
            grid.addView(LinearLayout(this).apply {
                clipChildren = false
                chunk.forEachIndexed { i, app ->
                    val caption = legend("", 10f, R.color.legend_secondary)
                    val badge = LinearLayout(this@EmberSettings).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(7), dp(3), dp(8), dp(3))
                        background = rounded(0xCC12110F.toInt(), 10)
                        addView(icon(R.drawable.ic_hide), LinearLayout.LayoutParams(dp(11), dp(11)).apply { marginEnd = dp(4) })
                        addView(legend(getString(R.string.hidden_badge), 9.5f).apply { typeface = MEDIUM })
                    }
                    val tile = appTile(app, "h:" + app.pkg).apply {
                        addView(badge, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(7), dp(7), 0) })
                    }
                    fun show() {
                        val off = app.pkg in hidden
                        badge.visibility = if (off) View.VISIBLE else View.GONE
                        tile.dimmed(off && !tile.hasFocus())
                        caption.setText(if (off) R.string.hidden_from_home else R.string.on_home)
                    }
                    tile.setOnFocusChangeListener { v, has -> v.animateFocus(has); show() }
                    tile.setOnClickListener {
                        if (!hidden.remove(app.pkg)) hidden += app.pkg
                        prefs.putList("hidden", hidden.toList()); show(); count()
                    }
                    show()
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        clipChildren = false
                        addView(tile, LinearLayout.LayoutParams(w, w * 9 / 16))
                        addView(caption.apply { setPadding(dp(2), dp(7), 0, 0) })
                    }, LinearLayout.LayoutParams(w, WRAP_CONTENT).apply { if (i > 0) marginStart = dp(16) })
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(18) })
        }
        dialog.setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            setBackgroundColor(getColor(R.color.housing))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false // the focused tile grows past the padding edge
                setPadding(dp(48), dp(36), dp(48), dp(36))
                addView(LinearLayout(context).apply {
                    gravity = Gravity.BOTTOM
                    addView(legend(getString(R.string.set_hidden), 28f).apply { typeface = LIGHT }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(summary)
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(20) })
                addView(grid)
                addView(legend(getString(R.string.hidden_footer), 11f, R.color.legend_secondary))
            })
        })
        dialog.setOnDismissListener { build("hidden") }
        dialog.show()
        grid.findViewWithTag<View>("h:" + apps.firstOrNull()?.pkg)?.requestFocus()
    }

    private fun watcherOn() = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?.split(':')?.any { it.startsWith("$packageName/") } == true

    private fun notices() = AlertDialog.Builder(this)
        .setTitle(R.string.set_version)
        .setMessage(getString(R.string.licence) + "\n\n" + resources.openRawResource(R.raw.third_party_notices).bufferedReader().use { it.readText() })
        .setPositiveButton(android.R.string.ok, null)
        .show()

    private fun open(i: Intent) {
        try { startActivity(i) } catch (e: ActivityNotFoundException) { toast(R.string.cant_open) }
    }

    private fun toast(id: Int, vararg args: Any) = Toast.makeText(this, getString(id, *args), Toast.LENGTH_LONG).show()

    // ---- Files: the own background picture, backup and restore. Many TVs have no file picker; then a fixed file is used. ----

    /** Where backup and restore go on a TV without a file picker (readable with adb, no permission needed). */
    private val fallback get() = File(getExternalFilesDir(null), "ember-backup.json")

    // Android TV answers with a stub that only shows "You don't have an app that can do this": treat it as none.
    private fun hasPicker(action: String, type: String): Boolean {
        val by = packageManager.resolveActivity(Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType(type), 0)?.activityInfo?.packageName
        return by != null && by != "com.android.tv.frameworkpackagestubs"
    }

    private fun document(action: String, type: String, req: Int, title: String? = null, noPicker: () -> Unit) {
        if (!hasPicker(action, type)) return noPicker()
        val i = Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType(type)
        title?.let { i.putExtra(Intent.EXTRA_TITLE, it) }
        try { startActivityForResult(i, req) } catch (e: ActivityNotFoundException) { noPicker() }
    }

    private fun pickImage() = document(Intent.ACTION_OPEN_DOCUMENT, "image/*", REQ_IMAGE) { toast(R.string.no_picker_image) }

    private fun backup() = document(Intent.ACTION_CREATE_DOCUMENT, "application/json", REQ_BACKUP, "ember-backup.json") {
        runCatching { fallback.writeText(backupJson(prefs.all)) }
            .onSuccess { toast(R.string.backup_saved, fallback.path) }.onFailure { toast(R.string.backup_failed) }
    }

    private fun restore() = document(Intent.ACTION_OPEN_DOCUMENT, "application/json", REQ_RESTORE) {
        if (fallback.exists()) apply(fallback.readText()) else toast(R.string.no_backup, fallback.path)
    }

    private fun apply(text: String) {
        runCatching { restoreJson(text) }
            .onSuccess { values -> prefs.edit().clear().apply { values.forEach { (k, v) -> putString(k, v) } }.apply(); toast(R.string.restored); build(null) }
            .onFailure { toast(R.string.restore_failed) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, result: Int, data: Intent?) {
        super.onActivityResult(req, result, data)
        val uri: Uri = data?.data?.takeIf { result == RESULT_OK } ?: return
        runCatching {
            when (req) {
                REQ_IMAGE -> {
                    // Copied beside the old picture first, so a failed copy leaves the old one in place.
                    val part = File(filesDir, "$BACKGROUND_FILE.part")
                    try {
                        contentResolver.openInputStream(uri)!!.use { input -> part.outputStream().use { input.copyAtMost(it, MAX_IMAGE) } }
                        check(part.renameTo(File(filesDir, BACKGROUND_FILE)))
                    } finally { part.delete() }
                    set(BACKGROUND, "image")
                }
                REQ_BACKUP -> { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(backupJson(prefs.all).toByteArray()) }; toast(R.string.backup_done) }
                REQ_RESTORE -> apply(contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
            }
        }.onFailure { toast(if (req == REQ_IMAGE) R.string.image_failed else R.string.backup_failed) }
    }

    private companion object {
        const val REQ_IMAGE = 1
        const val REQ_BACKUP = 2
        const val REQ_RESTORE = 3
        /** A picture bigger than this is refused rather than filling the TV's small storage. */
        const val MAX_IMAGE = 25L * 1024 * 1024
    }
}

/** Copies at most [max] bytes, as Vulcan's BackupImport; more throws, and the caller drops the copy. */
fun java.io.InputStream.copyAtMost(out: java.io.OutputStream, max: Long) {
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = read(buf); if (n < 0) return
        total += n
        check(total <= max) { "Too large" }
        out.write(buf, 0, n)
    }
}
