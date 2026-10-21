package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingTest {
    @Test fun newestFirstAndThreePerApp() {
        // Netflix reports times for ten shows; Stremio's channel rows all get the time it was last opened.
        val netflix = (1..10).map { 100L - it to "netflix/$it" }
        val stremio = (1..4).map { 95L to "stremio/$it" }
        val nuvio = (1..2).map { 0L to "nuvio/$it" } // never opened from Plume
        val shown = newestPerApp(netflix + stremio + nuvio, 3) { it.substringBefore('/') }
        assertEquals(listOf("netflix/1", "netflix/2", "netflix/3", "stremio/1", "stremio/2", "stremio/3", "nuvio/1", "nuvio/2"), shown)
    }

    @Test fun whatYouStartedComesBeforeSuggestionsWhateverTheUnit() {
        // Wholphin dates what is playing in seconds and its "next episode" suggestions in milliseconds. Sorted on the
        // raw numbers the suggestions win, though they are older (11:52 against 13:14).
        val rows = listOf(
            watchKey(1, 1_792_576_332_167) to "wholphin/suggestion-a",
            watchKey(1, 1_792_576_332_167) to "wholphin/suggestion-b",
            watchKey(0, 1_792_581_271) to "wholphin/ncis",
            watchKey(0, 1_791_758_381) to "wholphin/descendants",
            watchKey(0, 1_791_242_669) to "wholphin/coquin",
            watchKey(0, 1_791_237_906_401) to "moonfin/clochette", // another app, in milliseconds
        )
        val shown = newestPerApp(rows, 3) { it.substringBefore('/') }
        assertEquals(listOf("wholphin/ncis", "wholphin/descendants", "wholphin/coquin", "moonfin/clochette"), shown)
    }

    @Test fun suggestionsStillShowWhenAnAppHasNothingStarted() {
        val rows = listOf(watchKey(1, 1_792_576_332_167) to "netflix/next", watchKey(0, 1_792_581_271) to "wholphin/ncis")
        assertEquals(listOf("wholphin/ncis", "netflix/next"), newestPerApp(rows, 3) { it.substringBefore('/') })
    }
}
