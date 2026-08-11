package com.github.ifeel.uvcpad.bt

/**
 * Represents one of five mouse/scroll speed presets.
 *
 * @param level  Speed level index (1-5)
 * @param mouse  Multiplier applied to mouse-move deltas
 * @param scroll Multiplier applied to scroll deltas
 * @param emoji  Icon displayed in the toolbar for this level
 */
data class SpeedLevel(
    val level: Int,
    val mouse: Float,
    val scroll: Float,
    val emoji: String
) {
    companion object {
        /** All five speed levels, ordered 1..5. Level 4 = original speed (1.0). */
        val LEVELS: List<SpeedLevel> = listOf(
            SpeedLevel(1, 0.4f, 0.4f, "\u0031\uFE0F\u20E3"),  // 1️⃣
            SpeedLevel(2, 0.6f, 0.6f, "\u0032\uFE0F\u20E3"),  // 2️⃣
            SpeedLevel(3, 0.8f, 0.8f, "\u0033\uFE0F\u20E3"),  // 3️⃣
            SpeedLevel(4, 1.0f, 1.0f, "\u0034\uFE0F\u20E3"),  // 4️⃣
            SpeedLevel(5, 1.2f, 1.2f, "\u0035\uFE0F\u20E3"),  // 5️⃣
        )

        /** Default level (4) = original speed 1.0. */
        val DEFAULT: SpeedLevel = LEVELS[3]

        /** Look up a SpeedLevel by its 1-based index. */
        fun forLevel(level: Int): SpeedLevel =
            LEVELS.firstOrNull { it.level == level } ?: DEFAULT
    }
}
