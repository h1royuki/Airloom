package tv.airloom.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryIconsTest {
    @Test fun recognizesThreeLanguagesAndNormalizesNames() {
        mapOf(" КИНОЗАЛЫ—UA " to CategoryIcon.CINEMA, "Дитячі" to CategoryIcon.KIDS,
            "Sports RU" to CategoryIcon.SPORT, "Музыкальные" to CategoryIcon.MUSIC,
            "Новини" to CategoryIcon.NEWS, "Documentary" to CategoryIcon.KNOWLEDGE,
            "Природа" to CategoryIcon.NATURE, "Культура" to CategoryIcon.CULTURE,
            "Путешествия" to CategoryIcon.TRAVEL, "Розважальні" to CategoryIcon.ENTERTAINMENT,
            "Беларусь" to CategoryIcon.REGION, "Украинские" to CategoryIcon.REGION,
            "Россия" to CategoryIcon.REGION, "Спорт Россия" to CategoryIcon.SPORT,
            "Познавательные UA" to CategoryIcon.KNOWLEDGE, "Непонятное" to CategoryIcon.TV,
            "Kazakhstan" to CategoryIcon.REGION, "Казахстан" to CategoryIcon.REGION, "Німеччина" to CategoryIcon.REGION, "Audience" to CategoryIcon.TV).forEach { (name, icon) -> assertEquals(name, icon, CategoryIcons.automatic(name)) }
        assertEquals(CategoryIcons.automatic("Все"), CategoryIcons.automatic("Всё"))
    }
    @Test fun overrideAndResetAndUnknownIds() {
        assertEquals(CategoryIcon.NATURE, CategoryIcons.resolve("Кино", "nature"))
        assertEquals(CategoryIcon.TV, CategoryIcons.resolve("Кино", "tv"))
        assertEquals(CategoryIcon.CINEMA, CategoryIcons.resolve("Кино", null))
        assertEquals(CategoryIcon.CINEMA, CategoryIcons.resolve("Кино", "future-icon"))
        assertEquals(12, CategoryIcon.entries.map { it.id }.distinct().size)
    }
}
