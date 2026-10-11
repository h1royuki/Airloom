package tv.airloom.core

import org.junit.Assert.*
import org.junit.Test

class ListManagementTest {
    private val channel = Channel("c", "p", "Канал", "https://example.invalid/live", group = "Кино")
    @Test fun visibilityCombinesBothIndependentFlagsAndPlaylistIdentity() {
        for (own in listOf(false, true)) for (parent in listOf(false, true)) {
            val c = channel.copy(hidden = own)
            val categories = listOf(Category("p", "Кино", 1, hidden = parent))
            assertEquals(own || parent, ListManagement.hidden(c, categories))
            assertTrue(ListManagement.accepts(ListManagement.hidden(c, categories), ListVisibility.ALL))
            assertEquals(!(own || parent), ListManagement.accepts(ListManagement.hidden(c, categories), ListVisibility.VISIBLE))
        }
        assertFalse(ListManagement.hidden(channel, listOf(Category("other", "Кино", 1, hidden = true))))
    }
    @Test fun managementScopesIncludeHiddenAndKeepFavouritesIndependent() {
        val hidden = channel.copy(hidden = true, favourite = true)
        val other = channel.copy(id = "d", group = "Новости")
        val all = listOf(hidden, other, channel.copy(id = "foreign", playlistId = "q"))
        assertEquals(listOf(hidden, other), ListManagement.channels(all, ChannelListScope("p", ChannelListKind.ALL)))
        assertEquals(listOf(hidden), ListManagement.channels(all, ChannelListScope("p", ChannelListKind.FAVOURITES)))
        assertEquals(listOf(hidden), ListManagement.channels(all, ChannelListScope("p", ChannelListKind.CATEGORY, "Кино")))
    }
    @Test fun restoringFocusUsesIdentityAndNearestRemainingNeighbour() {
        val before = listOf("a", "b", "c", "d")
        assertEquals("b", ListManagement.focusAfterRemoval("b", before, listOf("d", "b", "a")))
        assertEquals("d", ListManagement.focusAfterRemoval("b", before, listOf("a", "d")))
        assertEquals("a", ListManagement.focusAfterRemoval("d", before, listOf("a")))
        assertNull(ListManagement.focusAfterRemoval("a", before, emptyList()))
    }
    @Test fun changedOrDuplicateOrderCannotBeCommitted() {
        assertTrue(ListManagement.sameMembers(listOf("b", "a"), listOf("a", "b")))
        assertFalse(ListManagement.sameMembers(listOf("a", "a"), listOf("a")))
        assertFalse(ListManagement.sameMembers(listOf("a", "b"), listOf("a", "c")))
    }
}
