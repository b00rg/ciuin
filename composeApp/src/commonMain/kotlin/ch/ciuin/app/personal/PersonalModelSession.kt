package ch.ciuin.app.personal

import ch.ciuin.shared.model.FeatureVector
import ch.ciuin.shared.model.TrainingExample
import ch.ciuin.shared.personal.LogisticPersonalModel
import ch.ciuin.shared.personal.PersonalPreset
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal interface PersonalModelStorage {
    fun read(): String?
    fun write(json: String)
}

internal class PersonalModelSession private constructor(
    val preset: PersonalPreset,
    private val examples: List<TrainingExample>,
) {
    private val model = LogisticPersonalModel(preset, examples)

    val exampleCount: Int get() = examples.size

    fun score(features: FeatureVector): Double = model.score(features)

    fun withPreset(preset: PersonalPreset): PersonalModelSession = PersonalModelSession(preset, examples)

    fun withFeedback(example: TrainingExample): PersonalModelSession =
        PersonalModelSession(preset, (examples + example).takeLast(LogisticPersonalModel.MAX_EXAMPLES))

    fun reset(): PersonalModelSession = PersonalModelSession(preset, emptyList())

    fun encode(): String = json.encodeToString(SavedPersonalModel(preset.id, examples))

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun restore(jsonText: String?): PersonalModelSession {
            if (jsonText == null) return PersonalModelSession(PersonalPreset.AUTISM_FRIENDLY, emptyList())
            val saved = json.decodeFromString<SavedPersonalModel>(jsonText)
            val preset = PersonalPreset.entries.single { it.id == saved.preset }
            return PersonalModelSession(preset, saved.examples.takeLast(LogisticPersonalModel.MAX_EXAMPLES))
        }
    }
}

@Serializable
private data class SavedPersonalModel(val preset: String, val examples: List<TrainingExample>)
