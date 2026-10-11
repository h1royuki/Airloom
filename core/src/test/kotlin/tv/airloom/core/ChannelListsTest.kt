package tv.airloom.core

import org.junit.Assert.*
import org.junit.Test

class ChannelListsTest {
    private val a = Channel("a", "p", "A", "fixture://a", order = 0, favouriteOrder = 2)
    private val b = Channel("b", "p", "B", "fixture://b", order = 1, favouriteOrder = 1)
    private val c = Channel("c", "p", "C", "fixture://c", order = 2, favouriteOrder = 0)
    @Test fun scopeIsolatesSystemListsAndProviderCategoryWithSameName() {
        assertNotEquals(ChannelListScope("p", ChannelListKind.CATEGORY, "Все каналы"), ChannelLists.scope("p", "Все каналы"))
        assertNotEquals(ChannelLists.scope("p", "Кино"), ChannelLists.scope("p", "Избранное"))
        assertNull(ChannelLists.scope("p", "Последние"))
    }
    @Test fun pinsAndCustomOrderPrecedeNewChannels() {
        val scope = ChannelLists.scope("p", "Кино")
        assertEquals(listOf(c, b, a), ChannelLists.ordered(listOf(a,b,c), scope,
            listOf(ChannelListPreference("c", pinned = true), ChannelListPreference("b", position = 0))))
        assertEquals(listOf(c,b,a), ChannelLists.ordered(listOf(a,b,c), ChannelLists.scope("p", "Избранное"), emptyList()))
    }
    @Test fun movementCannotCrossGroupsAndPreservesHiddenSlots() {
        assertEquals(listOf("pin", "a", "hidden", "b"), ChannelLists.moved(listOf("pin", "a", "hidden", "b"), "a", -1, setOf("a","b")))
        assertEquals(listOf("pin", "b", "hidden", "a"), ChannelLists.moved(listOf("pin", "a", "hidden", "b"), "a", 1, setOf("a","b")))
    }
}
