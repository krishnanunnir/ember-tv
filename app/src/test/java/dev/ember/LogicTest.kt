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

    @Test fun sampleSizeKeepsScreenWidth() {
        assertEquals(2, sampleSize(4000, 1920))
        assertEquals(1, sampleSize(1920, 1920))
        assertEquals(1, sampleSize(800, 1920))
    }
}
