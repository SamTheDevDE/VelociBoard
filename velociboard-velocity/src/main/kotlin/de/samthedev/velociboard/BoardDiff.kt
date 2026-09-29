package de.samthedev.velociboard

import net.kyori.adventure.text.Component

data class BoardDiff(val titleChanged: Boolean, val removed: List<Int>, val updated: List<LineUpdate>) {
    fun titleChanged() = titleChanged
    fun removed() = removed
    fun updated() = updated

    data class LineUpdate(val slot: Int, val score: Int, val textChanged: Boolean, val scoreChanged: Boolean) {
        fun slot() = slot
        fun score() = score
        fun textChanged() = textChanged
        fun scoreChanged() = scoreChanged
    }

    companion object {
        @JvmStatic
        fun between(oldTitle: Component?, oldLines: List<Component>?, title: Component, lines: List<Component>): BoardDiff {
            val oldSize = oldLines?.size ?: 15
            val removed = (lines.size until oldSize).toList()
            val updated = lines.indices.mapNotNull { index ->
                val newLine = oldLines == null || index >= oldLines.size
                val textChanged = newLine || oldLines!![index] != lines[index]
                val scoreChanged = newLine || oldLines!!.size != lines.size
                if (textChanged || scoreChanged) LineUpdate(index, lines.size - index, textChanged, scoreChanged) else null
            }
            return BoardDiff(oldTitle != title, removed, updated)
        }

        @JvmStatic
        fun holder(slot: Int) = "vb_$slot"
    }
}
