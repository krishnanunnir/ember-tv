package dev.ember

import java.text.Normalizer

// ---- App search matching, from Vulcan's drawer search. Pure logic, tested in SearchTest. ----

/** Lowercase, without accents or punctuation: "Wi-Fi" and "wifi", "Café" and "cafe" are the same word. */
fun searchForm(s: String): String {
    val l = s.lowercase()
    val n = if (l.all { it.code < 128 }) l else MARKS.replace(Normalizer.normalize(l, Normalizer.Form.NFD), "")
    return NON_WORD.replace(n, "").trim()
}

private val MARKS = Regex("\\p{Mn}+")
private val NON_WORD = Regex("[^\\p{L}\\p{N}\\s]")
private val SPACES = Regex("\\s+")

/**
 * How well [label] matches the query [q], higher is better, 0 none: the label starts with it (5), a word does (4),
 * it's inside (3), the words' initials start with it ("yt", YouTube TV: 2), or a word starts with it but for one slip
 * (two in a query of 7 or more letters: 1). Remote keyboards make slips common.
 */
fun matchScore(label: String, q: String): Int {
    val l = searchForm(label); val s = searchForm(q)
    if (s.isEmpty()) return 0
    val words = l.split(SPACES).filter { it.isNotEmpty() }
    return when {
        l.startsWith(s) -> 5
        words.any { it.startsWith(s) } -> 4
        s in l -> 3
        s.length >= 2 && words.joinToString("") { it.take(1) }.startsWith(s) -> 2
        s.length >= 4 && words.any { w -> nearPrefix(w, s, if (s.length >= 7) 2 else 1) } -> 1
        else -> 0
    }
}

private fun nearPrefix(word: String, s: String, max: Int): Boolean =
    (s.length - max..s.length + max).any { n -> n in 1..word.length && editDistance(word.substring(0, n), s) <= max }

/** Optimal string alignment distance: insertions, deletions, substitutions and adjacent swaps. */
fun editDistance(a: String, b: String): Int {
    val d = Array(a.length + 1) { i -> IntArray(b.length + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
    for (i in 1..a.length) for (j in 1..b.length) {
        val cost = if (a[i - 1] == b[j - 1]) 0 else 1
        d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
        if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
    }
    return d[a.length][b.length]
}
