package dev.ember

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogicTest {
    @Test fun searchRanksMatches() {
        assertEquals(5, matchScore("YouTube", "you"))
        assertEquals(4, matchScore("Prime Video", "vid"))
        assertEquals(3, matchScore("Netflix", "flix"))
        assertEquals(2, matchScore("YouTube Kids", "yk"))
        assertEquals(1, matchScore("Spotify", "sptoi")) // one swap off
        assertEquals(0, matchScore("Spotify", "zzz"))
        assertEquals(5, matchScore("Café TV", "cafe"))
    }

    @Test fun iconPackComponents() {
        assertEquals("com.a/com.a.Main", parseComponent("ComponentInfo{com.a/.Main}"))
        assertEquals("com.a/x.Main", parseComponent("ComponentInfo{com.a/x.Main}"))
        assertNull(parseComponent("ComponentInfo{}"))
    }

    @Test fun backupRoundTrip() {
        val prefs = mapOf("favs" to "a,b", "dim" to "30", "label:a" to "Films", "count" to 3)
        assertEquals(mapOf("favs" to "a,b", "dim" to "30", "label:a" to "Films"), restoreJson(backupJson(prefs)))
    }

    @Test(expected = IllegalArgumentException::class) fun foreignFileRefused() { restoreJson("""{"cols":"5"}""") }

    @Test fun buttonTextReadsOnAccent() {
        assertEquals(0xFF1C1B19.toInt(), onColor(0xFFE8721C.toInt())) // signal orange: dark text
        assertEquals(0xFF1C1B19.toInt(), onColor(0xFFFFFFFF.toInt()))
        assertEquals(-1, onColor(0xFF3949AB.toInt())) // indigo: white text
    }

    @Test fun iconTint() {
        val red = 0xFFFF0000.toInt()
        assertEquals(red, averageOpaque(intArrayOf(red, red, 0x00000000)))
        assertNull(averageOpaque(intArrayOf(0x10FFFFFF)))
        assertEquals(0xFF7F0000.toInt(), shade(red, 0.5f, 0xFF000000.toInt()))
    }

    @Test fun sampleSizeKeepsScreenWidth() {
        assertEquals(2, sampleSize(4000, 1920))
        assertEquals(1, sampleSize(1920, 1920))
        assertEquals(1, sampleSize(800, 1920))
    }
}
