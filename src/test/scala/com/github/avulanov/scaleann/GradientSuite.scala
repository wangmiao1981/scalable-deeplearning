/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.avulanov.scaleann

import com.github.avulanov.tensor.DenseTensor
import org.apache.spark.ml.util.SparkTestContext
import org.apache.spark.mllib.linalg.Vectors

import AnnTypes._
import org.scalatest.FunSuite

class GradientSuite extends FunSuite with SparkTestContext {

  test("Gradient computation against numerical differentiation") {
    val x = DenseTensor[Double](Array(1.0, 1.0, 1.0), Array(3, 1))
    val input = new Tensor(Array(1.0, 1.0, 1.0), Array(3, 1))
    // output must contain zeros and one 1 for SoftMax
    val target = new Tensor(Array(0.0, 1.0), Array(2, 1))
    val topology = FeedForwardTopology.multiLayerPerceptron(Array(3, 4, 2), softmaxOnTop = false)
    val layersWithErrors = Seq(
      new SigmoidLayerWithSquaredError(),
      new SoftmaxLayerWithCrossEntropyLoss(),
      new SigmoidLayerWithCrossEntropyLoss(),
      new EmptyLayerWithSquaredError()
    )
    // check all layers that provide loss computation
    // 1) compute loss and gradient given the model and initial weights
    // 2) modify weights with small number epsilon (per dimension i)
    // 3) compute new loss
    // 4) ((newLoss - loss) / epsilon) should be close to the i-th component of the gradient
    for (layerWithError <- layersWithErrors) {
      topology.layers(topology.layers.length - 1) = layerWithError
      val model = topology.model(seed = 12L)
      val weights = model.weights.toArray
      val numWeights = weights.size
      val gradient = new Tensor(Array(numWeights))//Vectors.dense(Array.fill[Double](numWeights)(0.0))
      val loss = model.computeGradient(input, target, gradient, 1)
      val eps = 1e-4
      var i = 0
      val tol = 1e-4
      while (i < numWeights) {
        val originalValue = weights(i)
        weights(i) += eps
        val newModel = topology.model(Vectors.dense(weights))
        val newLoss = computeLoss(input, target, newModel)
        val derivativeEstimate = (newLoss - loss) / eps
        assert(math.abs(gradient.value(i) - derivativeEstimate) < tol, "Layer failed gradient check: " +
          layerWithError.getClass)
        weights(i) = originalValue
        i += 1
      }
    }
  }

  private def computeLoss(input: Tensor, target: Tensor, model: TopologyModel): Double = {
    val outputs = model.forward(input)
    model.layerModels.last match {
      case layerWithLoss: LossFunction =>
        layerWithLoss.loss(outputs.last, target, new Tensor(target.shape))
      case _ =>
        throw new UnsupportedOperationException("Top layer is required to have loss." +
          " Failed layer:" + model.layerModels.last.getClass)
    }
  }
}