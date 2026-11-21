package ch.ciuin.shared.model

/**
 * The 9 audio classes (todo.md §0 and B1). Declaration order = model output index 0..8,
 * and must match `ml/export/labels.yaml`. Don't reorder without retraining/re-exporting.
 *
 * There is deliberately no "quiet" class: quiet = the cascade's stage 0 stayed asleep.
 */
enum class SoundClass(val label: String) {
    SPEECH_CROWD("speech_crowd"),
    MUSIC("music"),
    TRAFFIC("traffic"),
    SIREN("siren"),
    CONSTRUCTION("construction"),
    ALARM_BEEP("alarm_beep"),
    DOG("dog"),
    MACHINERY_HUM("machinery_hum"),
    OTHER("other");

    /** Position in the model's `float32 [1, 9]` output. */
    val index: Int get() = ordinal

    companion object {
        const val COUNT = 9

        fun fromLabel(label: String): SoundClass? = entries.firstOrNull { it.label == label }
    }
}
