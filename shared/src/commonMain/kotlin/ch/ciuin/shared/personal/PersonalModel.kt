package ch.ciuin.shared.personal

import ch.ciuin.shared.model.FeatureVector
import ch.ciuin.shared.model.TrainingExample

/** Per-user trigger model. Stays on the phone, never synced. */
interface PersonalModel {
    /** p_overwhelm in [0, 1]. */
    fun score(f: FeatureVector): Double

    fun update(e: TrainingExample)
}
