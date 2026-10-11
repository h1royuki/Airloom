package tv.airloom.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgrammeSearchPatternTest {
    @Test fun casedLettersBecomeSetsInAnyAlphabet() {
        assertEquals("*[нН][оО][вВ]*", globContaining("Нов"))
        assertEquals("*[aA][bB]*", globContaining("aB"))
    }
    @Test fun digitsSpacesAndPunctuationStayLiteral() {
        assertEquals("*[тТ][вВ] 24.*", globContaining("ТВ 24."))
    }
    @Test fun globWildcardsAreEscaped() {
        assertEquals("*[*][?][[]]%_*", globContaining("*?[]%_"))
    }
}
