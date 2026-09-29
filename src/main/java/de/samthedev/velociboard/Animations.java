package de.samthedev.velociboard;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Animations {
    private static final Pattern REFERENCE = Pattern.compile("<animation:([a-z][a-z0-9_]*)>");
    private final Map<String, Animation> animations;
    private final long startedAt = System.nanoTime();

    private Animations(Map<String, Animation> animations) {
        this.animations = animations;
    }

    static Animations load(Path file) {
        Map<?, ?> values = BoardConfig.readYaml(file);
        Map<String, Animation> loaded = new HashMap<>();
        for (var entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String name) || !name.matches("[a-z][a-z0-9_]*")) {
                throw new IllegalArgumentException("animations.yml: animation names must use lowercase letters, numbers and underscores");
            }
            if (!(entry.getValue() instanceof Map<?, ?> settings)) {
                throw invalid(name, "must be a mapping");
            }
            Object interval = settings.get("interval");
            if (!(interval instanceof Number milliseconds) || milliseconds.longValue() < 50
                    || milliseconds.longValue() > 3_600_000
                    || milliseconds.doubleValue() != milliseconds.longValue()) {
                throw invalid(name + ".interval", "must be whole milliseconds between 50 and 3600000");
            }
            Object modeValue = settings.get("mode");
            if (!(modeValue instanceof String modeText)
                    || !(modeText.equals("loop") || modeText.equals("bounce"))) {
                throw invalid(name + ".mode", "must be loop or bounce");
            }
            Object framesValue = settings.get("frames");
            if (!(framesValue instanceof List<?> frames) || frames.isEmpty() || frames.size() > 100
                    || frames.stream().anyMatch(frame -> !(frame instanceof String))) {
                throw invalid(name + ".frames", "must have 1 to 100 text frames");
            }
            loaded.put(name, new Animation(milliseconds.longValue() * 1_000_000,
                    modeText.equals("bounce"), frames.stream().map(String.class::cast).toList()));
        }
        return new Animations(Map.copyOf(loaded));
    }

    synchronized boolean tick() {
        return tickAt(System.nanoTime() - startedAt);
    }

    synchronized boolean tickAt(long elapsed) {
        boolean changed = false;
        for (Animation animation : animations.values()) {
            changed |= animation.tick(elapsed);
        }
        return changed;
    }

    synchronized String apply(String text) {
        Matcher matcher = REFERENCE.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Animation animation = animations.get(matcher.group(1));
            if (animation == null) {
                continue;
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(animation.current()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    void validate(String boardId, String setting, String text) {
        Matcher matcher = REFERENCE.matcher(text);
        while (matcher.find()) {
            if (!animations.containsKey(matcher.group(1))) {
                throw new IllegalArgumentException("scoreboards/" + boardId + ".yml: '" + setting
                        + "' references unknown animation '" + matcher.group(1) + "'");
            }
        }
    }

    private static IllegalArgumentException invalid(String setting, String issue) {
        return new IllegalArgumentException("animations.yml: '" + setting + "' " + issue);
    }

    private static final class Animation {
        private final long intervalNanos;
        private final boolean bounce;
        private final List<String> frames;
        private int index;

        private Animation(long intervalNanos, boolean bounce, List<String> frames) {
            this.intervalNanos = intervalNanos;
            this.bounce = bounce;
            this.frames = frames;
        }

        private boolean tick(long elapsed) {
            long step = elapsed / intervalNanos;
            int cycle = bounce && frames.size() > 1 ? frames.size() * 2 - 2 : frames.size();
            int position = (int) (step % cycle);
            int next = bounce && position >= frames.size() ? cycle - position : position;
            if (next == index) {
                return false;
            }
            index = next;
            return true;
        }

        private String current() {
            return frames.get(index);
        }
    }
}
