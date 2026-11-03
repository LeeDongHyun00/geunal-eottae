package app.geunal.designsystem

import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import org.junit.Assert.assertEquals
import org.junit.Test

class TypeTest {
    private val steps =
        listOf(
            GeunalType.largeTitle,
            GeunalType.title1,
            GeunalType.title2,
            GeunalType.bodyStrong,
            GeunalType.body,
            GeunalType.secondary,
            GeunalType.caption,
            GeunalType.micro,
            GeunalType.buttonSmall,
            GeunalType.pill,
        )

    @Test
    fun everyStepBreaksKoreanBetweenWordsWhateverTheDeviceLanguage() {
        for (style in steps) {
            assertEquals(LineBreak.WordBreak.Phrase, style.lineBreak.wordBreak)
            // Phrase breaking applies only to text Android takes for Korean; without this a phone set to English
            // splits words between syllables.
            assertEquals(LocaleList("ko-KR"), style.localeList)
        }
        // Read after GeunalType, the order that once left the theme's slots null while the two initialised each other.
        assertEquals(GeunalType.body, GeunalTypography.bodyLarge)
    }
}
