package ch.ciuin.app.listener

/**
 * Stage 1 -> 2 rule (cascade.json `stage1_to_2`): escalate a window when stage 1 is unsure,
 * i.e. its top-2 probability margin is below [marginThreshold]. With S's fp32 threshold
 * (0.2204) this escalates ~15% of windows and catches ~27% of S's errors on the val split.
 */
internal class EscalationRule(private val marginThreshold: Double = DEFAULT_MARGIN) {
    fun shouldEscalate(probabilities: FloatArray): Boolean {
        if (probabilities.size < 2) return false
        var top1 = Float.NEGATIVE_INFINITY
        var top2 = Float.NEGATIVE_INFINITY
        for (p in probabilities) {
            if (p > top1) {
                top2 = top1
                top1 = p
            } else if (p > top2) {
                top2 = p
            }
        }
        return top1 - top2 < marginThreshold
    }

    companion object {
        /** Student S, fp32, val split (ml/cascade_rule.py). */
        const val DEFAULT_MARGIN = 0.2204
    }
}
