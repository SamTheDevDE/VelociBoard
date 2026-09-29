package de.samthedev.velociboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AnimationsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun loopsAndBounces() {
        val file = directory.resolve("animations.yml")
        Files.writeString(file, """
            looped:
              interval: 250
              mode: loop
              frames: [A, B, C]
            bounced:
              interval: 250
              mode: bounce
              frames: [A, B, C]
        """.trimIndent())
        val animations = Animations.load(file)
        assertEquals("A A", animations.apply("<animation:looped> <animation:bounced>"))
        assertTrue(animations.tickAt(500_000_000))
        assertEquals("C C", animations.apply("<animation:looped> <animation:bounced>"))
        assertTrue(animations.tickAt(750_000_000))
        assertEquals("A B", animations.apply("<animation:looped> <animation:bounced>"))
        assertTrue(animations.tickAt(1_000_000_000))
        assertEquals("B A", animations.apply("<animation:looped> <animation:bounced>"))
    }

    @Test
    fun rejectsUnknownReferences() {
        val file = directory.resolve("animations.yml")
        Files.writeString(file, "title:\n  interval: 250\n  mode: loop\n  frames: [A]\n")
        val animations = Animations.load(file)
        val error = assertThrows(IllegalArgumentException::class.java) {
            animations.validate("default", "title", "<animation:missing>")
        }
        assertTrue(error.message!!.contains("scoreboards/default.yml: 'title'"))
    }
}
