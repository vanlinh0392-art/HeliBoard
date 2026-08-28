// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.event

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VietnameseTelexCombinerTest {
    @Test
    fun `giowf produces gio with horn and grave tone`() {
        assertEquals("gi\u1EDD", typeSequence("giowf"))
    }

    @Test
    fun `giofw also produces gio with horn and grave tone`() {
        assertEquals("gi\u1EDD", typeSequence("giofw"))
    }

    @Test
    fun `uow families stay valid after stripping diacritics`() {
        assertTrue(VietnameseSyllableValidator.couldBeVietnamese("gi\u01A1"))
        assertTrue(VietnameseSyllableValidator.couldBeVietnamese("gi\u1ECFi"))
        assertTrue(VietnameseSyllableValidator.couldBeVietnamese("h\u01B0\u01A1u"))
        assertTrue(VietnameseSyllableValidator.couldBeVietnamese("r\u01B0\u1EE3u"))
    }

    @Test
    fun `uow sequences keep working in combiner`() {
        assertEquals("h\u01B0\u01A1u", typeSequence("huowu"))
        assertEquals("r\u01B0\u1EE3u", typeSequence("ruowju"))
    }

    @Test
    fun `short invalid abbreviations do not revert to raw keys`() {
        assertEquals("\u0111c", typeSequence("ddc"))
        assertEquals("\u0111cc", typeSequence("ddcc"))
    }

    @Test
    fun `longer invalid words still revert to raw keys`() {
        assertEquals("ddccc", typeSequence("ddccc"))
    }

    @Test
    fun `double s in longer latin words restores raw spelling at word boundary`() {
        assertEquals("pas", typeSequence("pass"))
        assertEquals("pass ", typeSequence("pass "))
        assertEquals("clas", typeSequence("class"))
        assertEquals("class ", typeSequence("class "))
    }

    @Test
    fun `short latin escape still needs double tone key once`() {
        assertEquals("as", typeSequence("ass"))
    }

    @Test
    fun `double s removes acute and keeps a single literal s`() {
        assertEquals("tes", typeSequence("tess"))
        assertEquals("test", typeSequence("tesst"))
    }

    @Test
    fun `repeated tone keys collapse to a single literal letter while composing`() {
        assertEquals("tef", typeSequence("teff"))
        assertEquals("ter", typeSequence("terr"))
        assertEquals("tex", typeSequence("texx"))
        assertEquals("tej", typeSequence("tejj"))
    }

    @Test
    fun `double w restores to a single literal w`() {
        assertEquals("w", typeSequence("ww"))
    }

    @Test
    fun `cursor move keeps telex composing state`() {
        val combiner = VietnameseTelexCombiner()
        typeIntoCombiner(combiner, "luoon")

        val cursorEvent = combiner.processEvent(
            previousEvents = arrayListOf(),
            event = Event.createCursorMovedEvent(2)
        )

        assertTrue(cursorEvent.isCursorMove)
        assertEquals("lu\u00F4n", combiner.combiningStateFeedback.toString())
    }

    @Test
    fun `validator still rejects obvious non vietnamese patterns`() {
        assertFalse(VietnameseSyllableValidator.couldBeVietnamese("bean"))
        assertFalse(VietnameseSyllableValidator.couldBeVietnamese("straw"))
    }

    @Test
    fun `qu and gi words place tone on correct main vowel`() {
        assertEquals("qu\u00E1", typeSequence("quas"))
        assertEquals("qu\u00E2n", typeSequence("quaan"))
        assertEquals("gi\u00FAp", typeSequence("giupj"))
        assertEquals("gi\u00E0", typeSequence("giaf"))
        assertEquals("g\u00EC", typeSequence("gif"))
    }

    @Test
    fun `new style diphthongs place tone on second vowel`() {
        assertEquals("h\u00F2a", typeSequence("hoaf"))
        assertEquals("h\u00F3a", typeSequence("hoas"))
        assertEquals("th\u1EE7y", typeSequence("thuyr"))
    }

    @Test
    fun `uppercase sequences preserve casing with tones and diacritics`() {
        assertEquals("TO\u00C0N", typeSequence("TOANF"))
        assertEquals("\u0110\u01AF\u1EDCNG", typeSequence("DUWOFNG"))
        assertEquals("VI\u1EC6T", typeSequence("VIETJ"))
    }

    @Test
    fun `z key removes tone correctly`() {
        assertEquals("toan", typeSequence("toansz"))
        assertEquals("hoa", typeSequence("hoafz"))
    }

    @Test
    fun `punctuation and space trigger word boundary commit`() {
        assertEquals("to\u00E0n.", typeSequence("toanf."))
        assertEquals("ch\u00E0o!", typeSequence("chaof!"))
        assertEquals("l\u1ED7i ", typeSequence("loix "))
        assertEquals("t\u00F4i l\u00E0 ai ", typeSequence("tooif laf ai "))
    }

    private fun typeSequence(raw: String): String {
        val combiner = VietnameseTelexCombiner()
        return typeIntoCombiner(combiner, raw).append(combiner.combiningStateFeedback).toString()
    }

    private fun typeIntoCombiner(combiner: VietnameseTelexCombiner, raw: String): StringBuilder {
        val committed = StringBuilder()
        raw.forEach { char ->
            var event: Event? = combiner.processEvent(
                previousEvents = arrayListOf(),
                event = Event.createEventForCodePointFromUnknownSource(char.code)
            )
            while (event != null) {
                val textToCommit = event.textToCommit
                if (!textToCommit.isNullOrEmpty()) {
                    committed.append(textToCommit)
                } else if (event.codePoint > 0 && !event.isConsumed && event.keyCode != KeyCode.MULTIPLE_CODE_POINTS) {
                    committed.append(event.codePoint.toChar())
                }
                event = event.nextEvent
            }
        }
        return committed
    }
}
