package de.samthedev.velociboard

import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BoardDiffTest {
    private val title = Component.text("Board")

    @Test
    fun unchangedStateSendsNothing() {
        val lines = listOf(Component.text("A"), Component.text("B"))
        val diff = BoardDiff.between(title, lines, title, lines)
        assertFalse(diff.titleChanged)
        assertTrue(diff.removed.isEmpty())
        assertTrue(diff.updated.isEmpty())
    }

    @Test
    fun changesOnlyTheAffectedLine() {
        val diff = BoardDiff.between(title, listOf(Component.text("A"), Component.text("B")),
            title, listOf(Component.text("A"), Component.text("C")))
        assertEquals(1, diff.updated.size)
        assertEquals(1, diff.updated.first().slot)
        assertTrue(diff.updated.first().textChanged)
        assertFalse(diff.updated.first().scoreChanged)
    }

    @Test
    fun titleChangeDoesNotTouchLines() {
        val lines = listOf(Component.text("A"))
        val diff = BoardDiff.between(title, lines, Component.text("New title"), lines)
        assertTrue(diff.titleChanged)
        assertTrue(diff.updated.isEmpty())
    }

    @Test
    fun removesConditionalLinesAndKeepsDuplicateTextDistinct() {
        val blank = Component.empty()
        val diff = BoardDiff.between(title, listOf(blank, Component.text("conditional"), blank),
            title, listOf(blank, blank))
        assertEquals(listOf(2), diff.removed)
        assertEquals(2, diff.updated.size)
        assertEquals(2, diff.updated.first().score)
        assertEquals(1, diff.updated[1].score)
        assertFalse(BoardDiff.holder(0) == BoardDiff.holder(1))
    }
}
