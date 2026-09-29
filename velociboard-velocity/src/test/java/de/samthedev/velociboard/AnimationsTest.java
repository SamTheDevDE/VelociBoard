package de.samthedev.velociboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnimationsTest {
    @TempDir
    Path directory;

    @Test
    void loopsAndBounces() throws Exception {
        Path file = directory.resolve("animations.yml");
        Files.writeString(file, """
                looped:
                  interval: 250
                  mode: loop
                  frames: [A, B, C]
                bounced:
                  interval: 250
                  mode: bounce
                  frames: [A, B, C]
                """);
        Animations animations = Animations.load(file);
        assertEquals("A A", animations.apply("<animation:looped> <animation:bounced>"));
        assertTrue(animations.tickAt(500_000_000));
        assertEquals("C C", animations.apply("<animation:looped> <animation:bounced>"));
        assertTrue(animations.tickAt(750_000_000));
        assertEquals("A B", animations.apply("<animation:looped> <animation:bounced>"));
        assertTrue(animations.tickAt(1_000_000_000));
        assertEquals("B A", animations.apply("<animation:looped> <animation:bounced>"));
    }

    @Test
    void rejectsUnknownReferences() throws Exception {
        Path file = directory.resolve("animations.yml");
        Files.writeString(file, "title:\n  interval: 250\n  mode: loop\n  frames: [A]\n");
        Animations animations = Animations.load(file);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> animations.validate("default", "title", "<animation:missing>"));
        assertTrue(error.getMessage().contains("scoreboards/default.yml: 'title'"));
    }
}
