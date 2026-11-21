package ch.ciuin.shared.personal

import ch.ciuin.shared.model.FeatureVector
import ch.ciuin.shared.model.TrainingExample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.exp

/** On-device logistic regression with an in-memory buffer of the most recent examples. */
class LogisticPersonalModel internal constructor(
    preset: PersonalPreset,
    private val capacity: Int,
    examples: List<TrainingExample> = emptyList(),
) : PersonalModel {
    constructor(preset: PersonalPreset = PersonalPreset.AUTISM_FRIENDLY) : this(preset, MAX_EXAMPLES)

    /** Restores the model from examples in recording order, oldest first. */
    constructor(preset: PersonalPreset, examples: List<TrainingExample>) : this(preset, MAX_EXAMPLES, examples)

    init {
        require(capacity > 0) { "Personal model capacity must be positive" }
    }

    private val prior = preset.parameters()
    private val initialState = ModelState(emptyList(), prior)
    // Publish the fitted weights and their training buffer together.
    private val state = MutableStateFlow(
        if (examples.isEmpty()) initialState else {
            val buffer = examples.takeLast(capacity).map(::trainingSample)
            ModelState(buffer, fit(buffer))
        },
    )

    internal val theta: DoubleArray get() = state.value.theta.copyOf()

    override fun score(f: FeatureVector): Double = probability(state.value.theta, featurize(f))

    override fun update(e: TrainingExample) {
        val example = trainingSample(e)
        state.update { current ->
            val start = if (current.examples.size == capacity) 1 else 0
            val examples = current.examples.subList(start, current.examples.size) + example
            ModelState(examples, fit(examples))
        }
    }

    /** Drops the examples and restores the selected preset. */
    fun reset() {
        state.value = initialState
    }

    private fun fit(examples: List<Example>): DoubleArray {
        val positives = examples.count { it.label == 1 }
        val negatives = examples.size - positives
        val positiveWeight = if (positives == 0) {
            1.0
        } else {
            (negatives.toDouble() / positives).coerceIn(1.0, MAX_POS_WEIGHT)
        }
        val totalWeight = negatives + positives * positiveWeight
        val weights = prior.copyOf()
        val gradient = DoubleArray(weights.size)

        repeat(STEPS) {
            gradient.fill(0.0)
            for ((features, label) in examples) {
                val exampleWeight = if (label == 1) positiveWeight else 1.0
                val residual = exampleWeight * (probability(weights, features) - label)
                gradient[0] += residual
                for (index in features.indices) {
                    gradient[index + 1] += residual * features[index]
                }
            }
            for (index in weights.indices) {
                val regularization = L2 * (weights[index] - prior[index])
                weights[index] -= LEARNING_RATE * (gradient[index] / totalWeight + regularization)
            }
        }
        return weights
    }

    private data class Example(val features: DoubleArray, val label: Int)
    private class ModelState(val examples: List<Example>, val theta: DoubleArray)

    companion object {
        const val MAX_EXAMPLES = 200
        private const val STEPS = 60
        private const val LEARNING_RATE = 1.0
        private const val L2 = 0.01
        private const val MAX_POS_WEIGHT = 3.0

        private fun trainingSample(example: TrainingExample): Example {
            require(example.label == 0 || example.label == 1) { "Personal model labels must be 0 or 1" }
            return Example(featurize(example.features), example.label)
        }

        private fun probability(theta: DoubleArray, features: DoubleArray): Double {
            var logit = theta[0]
            for (index in features.indices) {
                logit += theta[index + 1] * features[index]
            }
            return 1.0 / (1.0 + exp(-logit))
        }
    }
}
