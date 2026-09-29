package de.samthedev.velociboard

import java.nio.file.Path

class Animations private constructor(private val animations: Map<String, Animation>) {
    private val startedAt = System.nanoTime()

    @Synchronized
    fun tick(): Boolean = tickAt(System.nanoTime() - startedAt)

    @Synchronized
    fun tickAt(elapsed: Long): Boolean {
        var changed = false
        for (animation in animations.values) changed = animation.tick(elapsed) || changed
        return changed
    }

    @Synchronized
    fun apply(text: String): String = reference.replace(text) { match ->
        animations[match.groupValues[1]]?.current() ?: match.value
    }

    fun validate(boardId: String, setting: String, text: String) {
        for (match in reference.findAll(text)) {
            val name = match.groupValues[1]
            require(name in animations) {
                "scoreboards/$boardId.yml: '$setting' references unknown animation '$name'"
            }
        }
    }

    companion object {
        private val reference = Regex("<animation:([a-z][a-z0-9_]*)>")

        @JvmStatic
        fun load(file: Path): Animations {
            val loaded = HashMap<String, Animation>()
            for ((key, value) in BoardConfig.readYaml(file)) {
                require(key is String && key.matches(Regex("[a-z][a-z0-9_]*"))) {
                    "animations.yml: animation names must use lowercase letters, numbers and underscores"
                }
                require(value is Map<*, *>) { invalid(key, "must be a mapping") }
                val interval = value["interval"]
                require(interval is Number && interval.toLong() in 50..3_600_000
                    && interval.toDouble() == interval.toLong().toDouble()) {
                    invalid("$key.interval", "must be whole milliseconds between 50 and 3600000")
                }
                val mode = value["mode"]
                require(mode == "loop" || mode == "bounce") { invalid("$key.mode", "must be loop or bounce") }
                val frames = value["frames"]
                require(frames is List<*> && frames.size in 1..100 && frames.all { it is String }) {
                    invalid("$key.frames", "must have 1 to 100 text frames")
                }
                loaded[key] = Animation(interval.toLong() * 1_000_000, mode == "bounce", frames.filterIsInstance<String>())
            }
            return Animations(loaded.toMap())
        }

        private fun invalid(setting: String, issue: String) = "animations.yml: '$setting' $issue"
    }

    private class Animation(val intervalNanos: Long, val bounce: Boolean, val frames: List<String>) {
        private var index = 0

        fun tick(elapsed: Long): Boolean {
            val step = elapsed / intervalNanos
            val cycle = if (bounce && frames.size > 1) frames.size * 2 - 2 else frames.size
            val position = (step % cycle).toInt()
            val next = if (bounce && position >= frames.size) cycle - position else position
            if (next == index) return false
            index = next
            return true
        }

        fun current() = frames[index]
    }
}
