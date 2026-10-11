package tv.airloom.core

import java.text.Normalizer
import java.util.Locale

/** Stable preference IDs; presentation vectors belong to app. */
enum class CategoryIcon(val id: String) {
    TV("tv"), CINEMA("cinema"), KIDS("kids"), SPORT("sport"), NEWS("news"), MUSIC("music"),
    KNOWLEDGE("knowledge"), NATURE("nature"), ENTERTAINMENT("entertainment"), CULTURE("culture"), TRAVEL("travel"), REGION("region");
    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } }
}

object CategoryIcons {
    private fun tokens(name: String) = Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace('ё', 'е')
        .split(Regex("[^\\p{L}\\p{N}]+" )).filter { it.isNotEmpty() }
    private val countries = Locale.getISOCountries().flatMap { country ->
        listOf("ru", "uk", "en").map { language -> tokens(Locale("", country).getDisplayCountry(Locale(language))) }
    }.filter { it.isNotEmpty() }.toSet()
    private val rules = listOf(
        CategoryIcon.KNOWLEDGE to listOf("познав", "пізнав", "документ", "наук", "освіт", "образоват", "document", "science", "education", "knowledge", "discovery"),
        CategoryIcon.KIDS to listOf("детск", "дитяч", "мульт", "kids", "children", "cartoon"),
        CategoryIcon.SPORT to listOf("спорт", "футбол", "sport", "football"),
        CategoryIcon.NEWS to listOf("новост", "новин", "news"),
        CategoryIcon.MUSIC to listOf("музык", "музик", "music"),
        CategoryIcon.CINEMA to listOf("кино", "кіно", "фильм", "фільм", "cinema", "movie", "film"),
        CategoryIcon.NATURE to listOf("природ", "животн", "тварин", "nature", "animal", "wildlife"),
        CategoryIcon.CULTURE to listOf("культур", "театр", "culture", "theatr", "art"),
        CategoryIcon.TRAVEL to listOf("путешеств", "подорож", "туризм", "travel", "tourism"),
        CategoryIcon.ENTERTAINMENT to listOf("развлек", "розваж", "юмор", "гумор", "entertain", "comedy", "humor"),
        CategoryIcon.REGION to listOf("россия", "россий", "росія", "росій", "украин", "україн", "беларус", "білорус", "регион", "регіон", "russia", "ukrain", "belarus", "region", "европ", "європ", "азия", "азія", "europe", "asia", "сша", "usa", "ua", "ru", "by"),
    )
    fun resolve(name: String, override: String? = null): CategoryIcon = CategoryIcon.fromId(override) ?: automatic(name)
    fun automatic(name: String): CategoryIcon {
        val tokens = tokens(name)
        return rules.firstOrNull { (_, prefixes) -> tokens.any { token -> prefixes.any { prefix ->
            if (prefix in listOf("ua", "ru", "by", "art")) token == prefix else token.startsWith(prefix)
        } } }?.first ?: if (countries.any { country -> tokens.windowed(country.size).any { it == country } }) CategoryIcon.REGION else CategoryIcon.TV
    }
}
