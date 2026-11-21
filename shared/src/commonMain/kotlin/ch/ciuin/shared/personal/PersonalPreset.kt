package ch.ciuin.shared.personal

import ch.ciuin.shared.model.FeatureVector
import ch.ciuin.shared.model.SoundClass
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.ln
import kotlin.math.log10

enum class PersonalPreset(val id: String) {
    AUTISM_FRIENDLY("autism_friendly"),
    SOUND_SENSITIVE("sound_sensitive"),
    LIGHT_SENSITIVE("light_sensitive"),
    CROWD_SENSITIVE("crowd_sensitive");

    internal fun parameters(): DoubleArray {
        val preset = presetWeights.getValue(id)
        return DoubleArray(personalFeatureNames.size + 1) { index ->
            if (index == 0) preset.bias else preset.weights.getValue(personalFeatureNames[index - 1])
        }
    }
}

internal val personalFeatureNames = listOf("noise", "peak") +
    SoundClass.entries.map { "sound_${it.label}" } + listOf("light", "flicker", "crowd")

internal fun featurize(features: FeatureVector): DoubleArray = DoubleArray(personalFeatureNames.size).apply {
    // Missing features contribute no evidence to the existing linear model.
    val dbMean = features.dbMean
    val dbPeak = features.dbPeak
    if (dbMean != null) {
        this[0] = clamp01((dbMean - 35.0) / 50.0)
        if (dbPeak != null) this[1] = clamp01((dbPeak - dbMean) / 30.0)
    }
    for (soundClass in SoundClass.entries) {
        this[2 + soundClass.index] = clamp01(features.sound(soundClass))
    }
    val lightIndex = 2 + SoundClass.COUNT
    features.luxMean?.let { this[lightIndex] = clamp01(log10(it.coerceAtLeast(0.0) + 1.0) / 4.0) }
    features.flickerIndex?.let { this[lightIndex + 1] = clamp01(it) }
    // Crowd slot, in people-equivalents so the preset weights still apply (no retraining): a measured
    // Bluetooth device count (~2 devices per person) wins over a people estimate from the prior.
    val crowd = features.nearbyDevices?.let { it / DEVICES_PER_PERSON } ?: features.peopleCount?.toDouble()
    crowd?.let { this[lightIndex + 2] = clamp01(ln(1.0 + it.coerceAtLeast(0.0)) / ln(31.0)) }
}

/** Rough phones + earbuds + watches per person, to turn a Bluetooth device count into a crowd estimate. */
internal const val DEVICES_PER_PERSON = 2.0

private fun clamp01(value: Double): Double = when {
    value > 1.0 -> 1.0
    value > 0.0 -> value
    else -> 0.0
}

@Serializable
private data class PresetExport(val features: List<String>, val presets: Map<String, PresetWeights>)

@Serializable
private data class PresetWeights(val bias: Double, val weights: Map<String, Double>)

private val presetWeights by lazy {
    val export = Json.decodeFromString<PresetExport>(personalPresetsJson)
    require(export.features == personalFeatureNames) { "Personal preset features do not match the model inputs" }
    export.presets
}
