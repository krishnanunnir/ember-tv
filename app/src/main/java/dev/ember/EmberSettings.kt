package dev.ember

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.io.File

/** Ember's own settings: one column of keys, each opening a list of values, all D-pad driven. */
class EmberSettings : Activity() {

    private lateinit var column: LinearLayout

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val side = resources.displayMetrics.widthPixels / 5
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, dp(27), side, dp(27))
        }
        setContentView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(column) })
    }

    // Rebuilt each time: the Home button watcher may have been switched on in Accessibility meanwhile.
    override fun onResume() {
        super.onResume()
        build(currentFocus?.tag as? String)
    }

    private fun build(focus: String?) {
        column.removeAllViews()
        column.addView(legend(getString(R.string.settings_title), 34f, R.color.legend).apply {
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        })
        row("tv", R.string.set_tv, getString(R.string.set_tv_info)) { open(Intent(Settings.ACTION_SETTINGS)) }

        section(R.string.sec_look)
        choice(BACKGROUND) { v -> if (v == "image") pickImage() else set(BACKGROUND, v) }
        if (prefs[BACKGROUND] != "plain") choice(DIM)
        choice(ACCENT)
        iconPackRow()

        section(R.string.sec_home)
        choice(CLOCK)
        choice(COLS)
        choice(INPUTS)
        if (prefs.on(INPUTS)) choice(EMPTY_PORTS)
        val hidden = prefs.list("hidden").size
        row("hidden", R.string.set_hidden, resources.getQuantityString(R.plurals.hidden_count, hidden, hidden)) { hiddenApps() }

        section(R.string.sec_home_button)
        row("watcher", R.string.set_watcher, getString(if (watcherOn()) R.string.watcher_on else R.string.watcher_off)) {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        section(R.string.sec_backup)
        row("backup", R.string.set_backup, getString(R.string.set_backup_info)) { backup() }
        row("restore", R.string.set_restore, getString(R.string.set_restore_info)) { restore() }

        section(R.string.sec_about)
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        row("about", R.string.set_version, getString(R.string.about_info, version)) { notices() }

        (focus?.let { column.findViewWithTag<View>(it) } ?: column.getChildAt(1))?.requestFocus()
    }

    private fun section(id: Int) = column.addView(label(id))

    /** A focusable key: title on the left, its value or a hint underneath. */
    private fun row(tag: String, title: Int, value: String, onClick: () -> Unit) {
        column.addView(LinearLayout(this).apply {
            this.tag = tag
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            background = keyBackground(dp(12).toFloat())
            setPadding(dp(20), dp(12), dp(20), dp(12))
            addView(legend(getString(title), 18f, R.color.legend))
            addView(legend(value, 14f, R.color.legend_secondary))
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun choice(c: Choice, pick: (String) -> Unit = { set(c, it) }) {
        val at = c.values.indexOf(prefs[c])
        row(c.key, c.title, choiceName(c, at)) {
            AlertDialog.Builder(this)
                .setTitle(c.title)
                .setSingleChoiceItems(c.values.indices.map { choiceName(c, it) }.toTypedArray(), at) { d, i -> d.dismiss(); pick(c.values[i]) }
                .show()
        }
    }

    private fun set(c: Choice, v: String) { prefs.edit().putString(c.key, v).apply(); build(c.key) }

    private fun iconPackRow() {
        val packs = listOf("" to getString(R.string.icon_pack_none)) + iconPacks(this)
        val current = prefs.getString("iconPack", "")
        val at = packs.indexOfFirst { it.first == current }.coerceAtLeast(0)
        row("iconPack", R.string.set_icon_pack, packs[at].second + "\n" + getString(R.string.set_icon_pack_info)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.set_icon_pack)
                .setSingleChoiceItems(packs.map { it.second }.toTypedArray(), at) { d, i ->
                    d.dismiss(); prefs.edit().putString("iconPack", packs[i].first).apply(); build("iconPack")
                }
                .show()
        }
    }

    /** Every app, the hidden ones ticked; unticking brings one back to Home. */
    private fun hiddenApps() {
        val apps = launchables()
        val hidden = prefs.list("hidden").toMutableSet()
        AlertDialog.Builder(this)
            .setTitle(R.string.set_hidden)
            .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), apps.map { it.pkg in hidden }.toBooleanArray()) { _, i, on ->
                if (on) hidden += apps[i].pkg else hidden -= apps[i].pkg
            }
            .setPositiveButton(android.R.string.ok) { _, _ -> prefs.putList("hidden", hidden.toList()); build("hidden") }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private fun document(action: String, type: String, req: Int, title: String? = null, noPicker: () -> Unit) {
        val i = Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType(type)
        title?.let { i.putExtra(Intent.EXTRA_TITLE, it) }
        // Android TV answers with a stub that only shows "You don't have an app that can do this": treat it as none.
        val by = packageManager.resolveActivity(i, 0)?.activityInfo?.packageName
        if (by == null || by == "com.android.tv.frameworkpackagestubs") return noPicker()
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
