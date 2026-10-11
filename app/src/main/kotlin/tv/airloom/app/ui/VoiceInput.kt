package tv.airloom.app.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

internal sealed interface VoiceInputResult {
    data class Recognized(val text: String) : VoiceInputResult
    data object Cancelled : VoiceInputResult
    data class Failure(val message: String) : VoiceInputResult
}
internal fun interface VoiceInput { fun launch(receiver: (VoiceInputResult) -> Unit) }

internal fun speechIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Название канала или передачи")
    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)

internal fun speechResult(code: Int, data: Intent?): VoiceInputResult {
    if (code != Activity.RESULT_OK) return VoiceInputResult.Cancelled
    val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull { it.isNotBlank() }?.trim()
    return if (text != null) VoiceInputResult.Recognized(text) else VoiceInputResult.Failure("Не удалось распознать речь. Попробуйте ещё раз.")
}

@Composable
internal fun rememberVoiceInput(): VoiceInput {
    val context = LocalContext.current
    var receiver by remember { mutableStateOf<((VoiceInputResult) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = receiver; receiver = null
        callback?.invoke(speechResult(result.resultCode, result.data))
    }
    DisposableEffect(Unit) { onDispose { receiver = null } }
    return remember(context, launcher) { VoiceInput { callback ->
        val intent = speechIntent()
        try {
            if (intent.resolveActivity(context.packageManager) == null) callback(VoiceInputResult.Failure("Голосовой ввод недоступен на этом устройстве."))
            else { receiver = callback; launcher.launch(intent) }
        } catch (_: ActivityNotFoundException) {
            receiver = null; callback(VoiceInputResult.Failure("Голосовой ввод недоступен на этом устройстве."))
        } catch (_: SecurityException) {
            receiver = null; callback(VoiceInputResult.Failure("Не удалось открыть голосовой ввод."))
        }
    } }
}
