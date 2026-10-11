package tv.airloom.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import tv.airloom.core.CategoryIcon

internal fun CategoryIcon.vector(): ImageVector = when (this) {
    CategoryIcon.TV -> Icons.Outlined.Tv
    CategoryIcon.CINEMA -> Icons.Outlined.Movie
    CategoryIcon.KIDS -> Icons.Outlined.Toys
    CategoryIcon.SPORT -> Icons.Outlined.SportsSoccer
    CategoryIcon.NEWS -> Icons.Outlined.Newspaper
    CategoryIcon.MUSIC -> Icons.Outlined.MusicNote
    CategoryIcon.KNOWLEDGE -> Icons.Outlined.School
    CategoryIcon.NATURE -> Icons.Outlined.Park
    CategoryIcon.ENTERTAINMENT -> Icons.Outlined.Celebration
    CategoryIcon.CULTURE -> Icons.Outlined.TheaterComedy
    CategoryIcon.TRAVEL -> Icons.Outlined.TravelExplore
    CategoryIcon.REGION -> Icons.Outlined.Public
}
internal fun CategoryIcon.label(): String = when (this) {
    CategoryIcon.TV -> "Телевизор"; CategoryIcon.CINEMA -> "Кино"; CategoryIcon.KIDS -> "Детские"
    CategoryIcon.SPORT -> "Спорт"; CategoryIcon.NEWS -> "Новости"; CategoryIcon.MUSIC -> "Музыка"
    CategoryIcon.KNOWLEDGE -> "Познавательные"; CategoryIcon.NATURE -> "Природа"
    CategoryIcon.ENTERTAINMENT -> "Развлечения"; CategoryIcon.CULTURE -> "Культура"
    CategoryIcon.TRAVEL -> "Путешествия"; CategoryIcon.REGION -> "Регионы"
}

@Composable
internal fun CategoryIconPicker(current: String?, choose: (String?) -> Unit) {
    val selected = CategoryIcon.fromId(current)
    val options = listOf<CategoryIcon?>(null) + CategoryIcon.entries
    Column(Modifier.testTag("category_icon_picker"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label("Иконка категории", Modifier.padding(4.dp), size = 13, color = Muted)
        options.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { icon ->
                    val label = icon?.let { "Иконка: ${it.label()}" } ?: "Авто"
                    val vector = icon?.vector() ?: Icons.Outlined.AutoAwesome
                    if (icon == selected) InitialFocus { TvAction(label, it.size(44.dp), selected = true, icon = vector, iconOnly = true) { choose(icon?.id) } }
                    else TvAction(label, Modifier.size(44.dp), icon = vector, iconOnly = true) { choose(icon?.id) }
                }
            }
        }
    }
}
