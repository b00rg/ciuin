package ch.ciuin.shared.cascade

import kotlinx.coroutines.flow.StateFlow

/** Cascade listener stages (todo.md §0). */
enum class CascadeStage {
    /** Every 1 s: dB level + spectral flux, no model. */
    STAGE_0,

    /** Smallest variant that fits the device, on a 1 s window. */
    STAGE_1,

    /** Larger variant + camera features if the app is open. */
    STAGE_2,
}

/** Per-stage time and inference counts: the battery/frugality evidence. */
data class CascadeStats(
    val millisInStage: Map<CascadeStage, Long>,
    val inferenceCount: Map<CascadeStage, Int>,
)

interface CascadeListener {
    val stage: StateFlow<CascadeStage>

    /** Feed one audio window; the listener decides whether to escalate. */
    fun onAudioWindow(window: FloatArray)

    fun stats(): CascadeStats

    fun start()

    fun stop()

    /** Invoked when stage 2 crosses the alert threshold. */
    fun setAlertHandler(handler: (pOverwhelm: Double) -> Unit)
}
