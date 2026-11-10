package com.github.ifeel.uvcpad.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [uvcpad-t0-inject] Pure DSL parsing: legal, illegal, multi-finger, missing fields. */
class GestureSequenceParserTest {

    private fun steps(input: String): List<GestureStep> {
        val result = GestureSequenceParser.parse(input)
        assertTrue("expected success for '$input', got $result", result is ParseResult.Success)
        return (result as ParseResult.Success).steps
    }

    private fun reason(input: String): String {
        val result = GestureSequenceParser.parse(input)
        assertTrue("expected failure for '$input', got $result", result is ParseResult.Failure)
        return (result as ParseResult.Failure).reason
    }

    @Test
    fun parsesSingleFingerSequence() {
        assertEquals(
            listOf(
                GestureStep.Down(0, 10f, 20f),
                GestureStep.Move(0, 30f, 40f),
                GestureStep.Up(0, 30f, 40f)
            ),
            steps("down:10,20;move:30,40;up:30,40")
        )
    }

    @Test
    fun parsesSecondFingerAndSleep() {
        assertEquals(
            listOf(
                GestureStep.Down(0, 1f, 1f),
                GestureStep.Down(1, 5f, 5f),
                GestureStep.Sleep(250),
                GestureStep.Move(1, 6f, 6f),
                GestureStep.Up(1, 6f, 6f),
                GestureStep.Up(0, 1f, 1f)
            ),
            steps("down:1,1;down2:5,5;sleep:250;move2:6,6;up2:6,6;up:1,1")
        )
    }

    @Test
    fun cancelClearsBothPointers() {
        assertEquals(
            listOf(
                GestureStep.Down(0, 1f, 1f),
                GestureStep.Down(1, 2f, 2f),
                GestureStep.Cancel(3f, 3f)
            ),
            steps("down:1,1;down2:2,2;cancel:3,3")
        )
    }

    @Test
    fun rejectsEmptyAndBlankTokens() {
        assertEquals("empty sequence", reason("   "))
        assertEquals("empty step", reason("down:1,1;;up:1,1"))
    }

    @Test
    fun rejectsMissingFieldsAndUnknownVerb() {
        assertTrue(reason("down:1,1;tap:2,2").contains("unknown verb"))
        assertTrue(reason("down:1").contains("bad coordinates"))
        assertTrue(reason("down1,1;up:1,1").contains("missing ':'"))
        assertTrue(reason("down:1,2,3;up:1,2").contains("bad coordinates"))
        assertTrue(reason("down:abc,1;up:1,1").contains("bad coordinates"))
        assertTrue(reason("down:1,1;sleep:xx;up:1,1").contains("sleep needs integer"))
        assertTrue(reason("down:1,1;sleep:-1;up:1,1").contains("sleep must be"))
    }

    @Test
    fun rejectsStructurallyInvalidSequences() {
        assertTrue(reason("move:1,1;down:1,1;up:1,1").contains("move without down"))
        assertTrue(reason("up:1,1").contains("up without down"))
        assertTrue(reason("down:1,1;down:2,2;up:2,2").contains("already down"))
        assertTrue(reason("down2:1,1").contains("needs pointer 0"))
        assertTrue(reason("down:1,1;move2:2,2;up:1,1").contains("move2 without down2"))
        assertTrue(reason("down:1,1;up2:2,2;up:1,1").contains("up2 without down2"))
        assertTrue(reason("down:1,1;move:2,2").contains("still down"))
    }
}
