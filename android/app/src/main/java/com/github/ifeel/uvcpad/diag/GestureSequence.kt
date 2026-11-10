package com.github.ifeel.uvcpad.diag

/**
 * [uvcpad-t0-inject] One synthetic touch step decoded from the adb gesture DSL.
 *
 * Pointer 0 is the primary finger, pointer 1 the optional second finger.
 */
sealed interface GestureStep {
    data class Down(val pointer: Int, val x: Float, val y: Float) : GestureStep
    data class Move(val pointer: Int, val x: Float, val y: Float) : GestureStep
    data class Up(val pointer: Int, val x: Float, val y: Float) : GestureStep
    data class Cancel(val x: Float, val y: Float) : GestureStep
    data class Sleep(val ms: Long) : GestureStep
}

sealed interface ParseResult {
    data class Success(val steps: List<GestureStep>) : ParseResult
    data class Failure(val reason: String) : ParseResult
}

/**
 * [uvcpad-t0-inject] Pure parser for `down:x,y;move:x,y;up:x,y` style sequences.
 *
 * Grammar:
 * - verbs: `down`, `move`, `up`, `cancel`, `sleep`, plus second-finger `down2`/`move2`/`up2`
 * - coordinates: `x,y` decimal numbers, both >= 0
 * - `sleep:ms`, non-negative integer
 * - steps separated by `;` (empty tokens rejected)
 *
 * Structural validation (beyond syntax): a finger must be down before move/up, the second
 * finger needs the first one down, duplicate downs are rejected, and the sequence must not
 * end with a finger still down. This keeps the injector's MotionEvent stream well-formed.
 */
object GestureSequenceParser {

    fun parse(input: String): ParseResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ParseResult.Failure("empty sequence")
        val tokens = trimmed.split(';')
        if (tokens.any { it.isBlank() }) return ParseResult.Failure("empty step")

        val steps = mutableListOf<GestureStep>()
        var pointer0Down = false
        var pointer1Down = false

        for (raw in tokens) {
            val token = raw.trim()
            val colon = token.indexOf(':')
            if (colon <= 0 || colon == token.length - 1) {
                return ParseResult.Failure("missing ':' or payload in '$token'")
            }
            val verb = token.substring(0, colon).trim().lowercase()
            val payload = token.substring(colon + 1).trim()

            if (verb == "sleep") {
                val ms = payload.toLongOrNull()
                    ?: return ParseResult.Failure("sleep needs integer ms: '$payload'")
                if (ms < 0) return ParseResult.Failure("sleep must be >= 0: '$payload'")
                steps += GestureStep.Sleep(ms)
                continue
            }

            val coords = parseCoords(payload)
                ?: return ParseResult.Failure("bad coordinates in '$token'")
            val (x, y) = coords

            when (verb) {
                "down" -> {
                    if (pointer0Down) return ParseResult.Failure("down while pointer 0 already down")
                    pointer0Down = true
                    steps += GestureStep.Down(0, x, y)
                }
                "move" -> {
                    if (!pointer0Down) return ParseResult.Failure("move without down")
                    steps += GestureStep.Move(0, x, y)
                }
                "up" -> {
                    if (!pointer0Down) return ParseResult.Failure("up without down")
                    pointer0Down = false
                    steps += GestureStep.Up(0, x, y)
                }
                "down2" -> {
                    if (!pointer0Down) return ParseResult.Failure("down2 needs pointer 0 down")
                    if (pointer1Down) return ParseResult.Failure("down2 while pointer 1 already down")
                    pointer1Down = true
                    steps += GestureStep.Down(1, x, y)
                }
                "move2" -> {
                    if (!pointer1Down) return ParseResult.Failure("move2 without down2")
                    steps += GestureStep.Move(1, x, y)
                }
                "up2" -> {
                    if (!pointer1Down) return ParseResult.Failure("up2 without down2")
                    pointer1Down = false
                    steps += GestureStep.Up(1, x, y)
                }
                "cancel" -> {
                    if (!pointer0Down) return ParseResult.Failure("cancel without down")
                    pointer0Down = false
                    pointer1Down = false
                    steps += GestureStep.Cancel(x, y)
                }
                else -> return ParseResult.Failure("unknown verb '$verb'")
            }
        }

        if (pointer0Down || pointer1Down) {
            return ParseResult.Failure("sequence ends with a pointer still down")
        }
        return ParseResult.Success(steps)
    }

    private fun parseCoords(payload: String): Pair<Float, Float>? {
        val parts = payload.split(',')
        if (parts.size != 2) return null
        val x = parts[0].trim().toFloatOrNull() ?: return null
        val y = parts[1].trim().toFloatOrNull() ?: return null
        if (!x.isFinite() || !y.isFinite() || x < 0f || y < 0f) return null
        return x to y
    }
}
