package ch.ciuin.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

@Serializable
internal enum class ModelVariant { S, M, L }

internal data class ModelInfo(
    val variant: ModelVariant,
    val sizeMb: Double,
    val accuracy: Double,
    val parameterCount: Int,
    val unavailableReason: String?,
) {
    val available: Boolean get() = unavailableReason == null
}

internal class ModelCatalog(
    private val settings: SettingsController,
    private val available: Set<ModelVariant>,
    initialVariants: List<ModelInfo> = emptyList(),
    private val runningVariant: () -> ModelVariant? = { null },
) {
    var variants by mutableStateOf(initialVariants)
        private set

    val selected: ModelVariant get() = settings.modelVariant
    val active: ModelVariant? get() = runningVariant()

    fun select(variant: ModelVariant) {
        if (variant in available && variant != ModelVariant.L) settings.selectModel(variant)
    }

    fun load(scope: CoroutineScope) {
        scope.launch {
            variants = withContext(Dispatchers.Default) {
                try {
                    parseModelResults(modelResultsJson, available)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }
    }
}

internal fun parseModelResults(json: String, available: Set<ModelVariant>): List<ModelInfo> {
    val format = Json { ignoreUnknownKeys = true }
    val results = format.parseToJsonElement(json).jsonObject
    return ModelVariant.entries.map { variant ->
        val result = format.decodeFromJsonElement<ModelResult>(results.getValue("audio-student-${variant.name.lowercase()}"))
        val reason = when {
            variant == ModelVariant.L -> "Not available yet"
            variant !in available -> "Audio classification is unavailable on this device"
            else -> null
        }
        ModelInfo(variant, result.sizeMb, result.accuracy, result.params, reason)
    }
}

@Serializable
private data class ModelResult(@SerialName("size_mb") val sizeMb: Double, val accuracy: Double, val params: Int)
