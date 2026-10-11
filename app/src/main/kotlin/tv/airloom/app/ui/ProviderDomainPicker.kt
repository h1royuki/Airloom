package tv.airloom.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme

private const val ONLINE = "https://iptv.online"
private const val ME = "https://iptv.me"

/** D-pad friendly inline dropdown. The custom entry is only visible after choosing it. */
@Composable
internal fun ProviderDomainPicker(
    id: String,
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    inheritedLabel: String? = null,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    var returnFocus by remember { mutableStateOf(false) }
    var customSelected by rememberSaveable(id) { mutableStateOf(isCustomDomain(value)) }
    var customDraft by rememberSaveable(id) { mutableStateOf(value.takeIf(::isCustomDomain).orEmpty()) }
    val trigger = remember { FocusRequester() }
    val custom = customSelected || isCustomDomain(value)
    LaunchedEffect(value) {
        if (value == ONLINE || value == ME) customSelected = false
        else if (isCustomDomain(value)) {
            customSelected = true
            if (value != "https://") customDraft = value
        }
    }
    LaunchedEffect(expanded, returnFocus) {
        if (!expanded && returnFocus) {
            withFrameNanos { }
            runCatching { trigger.requestFocus() }
            returnFocus = false
        }
    }
    BackHandler(expanded) { expanded = false; returnFocus = true }
    val selected = when (value) {
        "" -> if (custom) "Свой домен" else inheritedLabel ?: "Выберите домен"
        ONLINE -> "iptv.online"
        ME -> "iptv.me"
        else -> if (value == "https://") "Свой домен" else "Свой домен: $value"
    }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Label(label, size = 12, color = Muted)
        TvAction(selected, modifier.fillMaxWidth().focusRequester(trigger).testTag("domain_picker_$id"),
            icon = Icons.Outlined.ExpandMore, enabled = enabled) { expanded = !expanded }
        if (expanded) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(13.dp))
                .border(1.dp, LocalAccent.current.copy(alpha = .55f), RoundedCornerShape(13.dp)).padding(7.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                fun choose(next: String, isCustom: Boolean = false) { customSelected = isCustom; onChange(next); expanded = false; returnFocus = true }
                inheritedLabel?.let { title -> TvAction(title, Modifier.fillMaxWidth().testTag("domain_option_${id}_inherit"), selected = value.isBlank() && !custom) { choose("") } }
                TvAction("iptv.online", Modifier.fillMaxWidth().testTag("domain_option_${id}_online"), selected = value == ONLINE && !custom) { choose(ONLINE) }
                TvAction("iptv.me", Modifier.fillMaxWidth().testTag("domain_option_${id}_me"), selected = value == ME && !custom) { choose(ME) }
                TvAction("Свой домен", Modifier.fillMaxWidth().testTag("domain_option_${id}_custom"), selected = custom) {
                    choose(customDraft.ifBlank { "https://" }, isCustom = true)
                }
            }
        }
        if (custom) Entry("Свой домен · $label", value, onChange, enabled = enabled)
    }
}

private fun isCustomDomain(value: String) = value.isNotBlank() && value != ONLINE && value != ME
