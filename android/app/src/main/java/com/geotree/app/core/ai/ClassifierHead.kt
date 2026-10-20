package com.geotree.app.core.ai

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The server's `LinearHead.predict_proba` (backend/app/ai/head.py) in float64: L2-normalize the
 * DINOv2 feature, center and scale it, apply the multinomial logistic regression and a stable
 * softmax. Row i of the weights scores [ClassifierHeadSpec.classes] index i.
 */
class ClassifierHead(val spec: ClassifierHeadSpec) {
    private val weights: Array<DoubleArray> = spec.weights.map { it.toDoubleArray() }.toTypedArray()
    private val bias = spec.bias.toDoubleArray()
    private val mean = spec.mean.toDoubleArray()
    private val scale = spec.scale.toDoubleArray()
    val dimension: Int = mean.size

    fun probabilities(features: FloatArray): DoubleArray {
        require(features.size == dimension) { "Expected $dimension features, got ${features.size}." }
        var sumOfSquares = 0.0
        for (v in features) sumOfSquares += v.toDouble() * v.toDouble()
        val norm = max(sqrt(sumOfSquares), 1e-12)
        val x = DoubleArray(dimension) { (features[it].toDouble() / norm - mean[it]) / scale[it] }
        val logits = DoubleArray(weights.size) { row ->
            val w = weights[row]
            var sum = 0.0
            for (i in 0 until dimension) sum += x[i] * w[i]
            sum + bias[row]
        }
        val top = logits.max()
        val exps = DoubleArray(logits.size) { exp(logits[it] - top) }
        val total = exps.sum()
        return DoubleArray(exps.size) { exps[it] / total }
    }
}
