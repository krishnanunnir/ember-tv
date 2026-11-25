package dev.ember

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** A launchable app. [label] is the user's own name for it when they gave one, [title] the app's. */
class App(val pkg: String, val cls: String, val title: String, val label: String, val banner: Drawable?, val icon: Drawable)

/** Every launchable app but Ember, by label. TV apps win over phone entries, so an app with both keeps its banner. */
fun Context.launchables(): List<App> {
    val pm = packageManager
    val pack = prefs.getString("iconPack", "")!!.takeIf { it.isNotEmpty() }?.let { loadIconPack(this, it) }
    val seen = LinkedHashMap<String, App>()
    for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
        for (r in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
            val ai = r.activityInfo
            if (ai.packageName == packageName || ai.packageName in seen) continue
            val title = r.loadLabel(pm).toString()
            val icon = pack?.map?.get("${ai.packageName}/${ai.name}")?.let(pack::drawable) ?: r.loadIcon(pm)
            seen[ai.packageName] = App(ai.packageName, ai.name, title, prefs.label(ai.packageName) ?: title, ai.loadBanner(pm), icon)
        }
    }
    return seen.values.sortedBy { it.label.lowercase() }
}

// ---- Icon packs, from Vulcan's Icons.kt. On a TV they only change the apps that have no banner. ----

/** "ComponentInfo{pkg/cls}" from an icon pack's appfilter.xml → "pkg/cls", expanding a relative ".cls". */
fun parseComponent(s: String): String? {
    val inner = s.substringAfter("{", "").substringBefore("}", "")
    val pkg = inner.substringBefore("/", "")
    val cls = inner.substringAfter("/", "")
    if (pkg.isEmpty() || cls.isEmpty()) return null
    return "$pkg/" + if (cls.startsWith(".")) pkg + cls else cls
}

/** The common icon pack theme intents; the manifest's queries name the same ones. */
private val PACK_ACTIONS = listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME")

/** Installed icon packs as package → label. */
fun iconPacks(context: Context): List<Pair<String, String>> {
    val pm = context.packageManager
    return PACK_ACTIONS.flatMap { pm.queryIntentActivities(Intent(it), 0) }
        .distinctBy { it.activityInfo.packageName }
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .sortedBy { it.second.lowercase() }
}

/** An icon pack's appfilter: component → drawable name. */
class IconPack(private val pkg: String, private val res: Resources, val map: Map<String, String>) {
    fun drawable(name: String): Drawable? {
        val id = res.getIdentifier(name, "drawable", pkg)
        return if (id == 0) null else runCatching { res.getDrawable(id, null) }.getOrNull()
    }
}

/** Parsed packs by package and its last update, so each render doesn't parse the XML again. */
private val parsedPacks = HashMap<String, Pair<Long, IconPack>>()

fun loadIconPack(context: Context, pkg: String): IconPack? = runCatching {
    val version = context.packageManager.getPackageInfo(pkg, 0).lastUpdateTime
    parsedPacks[pkg]?.takeIf { it.first == version }?.let { return it.second }
    val res = context.packageManager.getResourcesForApplication(pkg)
    // res/xml/appfilter.xml, or assets/appfilter.xml for packs that ship it there.
    val id = res.getIdentifier("appfilter", "xml", pkg)
    val parser = if (id != 0) res.getXml(id)
        else XmlPullParserFactory.newInstance().newPullParser().apply { setInput(res.assets.open("appfilter.xml"), null) }
    val map = HashMap<String, String>()
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType != XmlPullParser.START_TAG || parser.name != "item") continue
        val c = parseComponent(parser.getAttributeValue(null, "component") ?: continue) ?: continue
        map.putIfAbsent(c, parser.getAttributeValue(null, "drawable") ?: continue)
    }
    IconPack(pkg, res, map).also { parsedPacks[pkg] = version to it }
}.getOrNull()
