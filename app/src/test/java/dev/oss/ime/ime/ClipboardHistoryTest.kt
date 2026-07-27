package dev.oss.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardHistoryTest {

    @Test
    fun mostRecentComesFirst() {
        val history = ClipboardHistory()
        history.add("one")
        history.add("two")
        assertEquals(listOf("two", "one"), history.items())
    }

    /** Copying the same thing twice should move it up, not fill the strip with duplicates. */
    @Test
    fun recopyingMovesToFrontWithoutDuplicating() {
        val history = ClipboardHistory()
        history.add("a")
        history.add("b")
        history.add("a")
        assertEquals(listOf("a", "b"), history.items())
    }

    @Test
    fun oldestIsDroppedAtTheLimit() {
        val history = ClipboardHistory(limit = 3)
        for (t in listOf("1", "2", "3", "4")) history.add(t)
        assertEquals(listOf("4", "3", "2"), history.items())
    }

    @Test
    fun emptyTextIsIgnored() {
        val history = ClipboardHistory()
        history.add("")
        assertEquals(emptyList<String>(), history.items())
    }

    @Test
    fun clearEmptiesIt() {
        val history = ClipboardHistory()
        history.add("x")
        history.clear()
        assertEquals(emptyList<String>(), history.items())
    }
}
