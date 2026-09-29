package de.samthedev.velociboard;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;

record BoardDiff(boolean titleChanged, List<Integer> removed, List<LineUpdate> updated) {
    static BoardDiff between(Component oldTitle, List<Component> oldLines,
            Component title, List<Component> lines) {
        int oldSize = oldLines == null ? 15 : oldLines.size();
        List<Integer> removed = new ArrayList<>();
        for (int index = lines.size(); index < oldSize; index++) {
            removed.add(index);
        }
        List<LineUpdate> updated = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            boolean newLine = oldLines == null || index >= oldLines.size();
            boolean textChanged = newLine || !oldLines.get(index).equals(lines.get(index));
            boolean scoreChanged = newLine || oldLines.size() != lines.size();
            if (textChanged || scoreChanged) {
                updated.add(new LineUpdate(index, lines.size() - index, textChanged, scoreChanged));
            }
        }
        return new BoardDiff(oldTitle == null || !oldTitle.equals(title), List.copyOf(removed), List.copyOf(updated));
    }

    static String holder(int slot) {
        return "vb_" + slot;
    }

    record LineUpdate(int slot, int score, boolean textChanged, boolean scoreChanged) {
    }
}
