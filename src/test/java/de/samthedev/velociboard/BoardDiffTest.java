package de.samthedev.velociboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class BoardDiffTest {
    private final Component title = Component.text("Board");

    @Test
    void unchangedStateSendsNothing() {
        List<Component> lines = List.of(Component.text("A"), Component.text("B"));
        BoardDiff diff = BoardDiff.between(title, lines, title, lines);
        assertFalse(diff.titleChanged());
        assertTrue(diff.removed().isEmpty());
        assertTrue(diff.updated().isEmpty());
    }

    @Test
    void changesOnlyTheAffectedLine() {
        List<Component> oldLines = List.of(Component.text("A"), Component.text("B"));
        List<Component> newLines = List.of(Component.text("A"), Component.text("C"));
        BoardDiff diff = BoardDiff.between(title, oldLines, title, newLines);
        assertEquals(1, diff.updated().size());
        assertEquals(1, diff.updated().getFirst().slot());
        assertTrue(diff.updated().getFirst().textChanged());
        assertFalse(diff.updated().getFirst().scoreChanged());
    }

    @Test
    void titleChangeDoesNotTouchLines() {
        List<Component> lines = List.of(Component.text("A"));
        BoardDiff diff = BoardDiff.between(title, lines, Component.text("New title"), lines);
        assertTrue(diff.titleChanged());
        assertTrue(diff.updated().isEmpty());
    }

    @Test
    void removesConditionalLinesAndKeepsDuplicateTextDistinct() {
        Component blank = Component.empty();
        List<Component> oldLines = List.of(blank, Component.text("conditional"), blank);
        List<Component> newLines = List.of(blank, blank);
        BoardDiff diff = BoardDiff.between(title, oldLines, title, newLines);
        assertEquals(List.of(2), diff.removed());
        assertEquals(2, diff.updated().size());
        assertEquals(2, diff.updated().getFirst().score());
        assertEquals(1, diff.updated().get(1).score());
        assertFalse(BoardDiff.holder(0).equals(BoardDiff.holder(1)));
    }
}
