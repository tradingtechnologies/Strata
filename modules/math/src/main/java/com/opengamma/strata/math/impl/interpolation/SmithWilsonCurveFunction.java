/*
 * Copyright (C) 2017 - present by OpenGamma Inc. and the OpenGamma group of companies
 *
 * Please see distribution for license.
 */
package com.opengamma.strata.math.impl.interpolation;

import com.opengamma.strata.collect.ArgChecker;
import com.opengamma.strata.collect.array.DoubleArray;
import com.opengamma.strata.math.impl.FunctionUtils;

/**
 * Smith-Wilson curve function.
 * <p>
 * The curve represents the discount factor values as a function of time. 
 * <p>
 * The curve is controlled by {@code omega}, {@code alpha} and {@code weights}: 
 * {@code omega} is related to the ultimate forward rate (UFR) by {@code omega = log(1 + UFR)}, 
 * the {@code alpha} parameter determines the rate of convergence to the UFR, 
 * and the {@code weights} are the parameters to be calibrated to market data.
 */
public class SmithWilsonCurveFunction {

  /**
   * Default implementation with UFR = 4.2%
   */
  public static final SmithWilsonCurveFunction DEFAULT = SmithWilsonCurveFunction.of(0.042);

  /**
   * The omega parameter.
   */
  private final double omega;

  /**
   * Creates an instance with UFR.
   * 
   * @param ufr  the UFR
   * @return the instance
   */
  public static SmithWilsonCurveFunction of(double ufr) {
    return new SmithWilsonCurveFunction(ufr);
  }

  // private constructor
  private SmithWilsonCurveFunction(double ufr) {
    this.omega = Math.log(1d + ufr);
  }

  //-------------------------------------------------------------------------
  /**
   * Computes the gap from the UFR at x value.
   * <p>
   * The {@code nodes} must be sorted in ascending order and coherent to {@code weights}.
   * 
   * @param x  the x value
   * @param alpha  the alpha parameter
   * @param nodes  the nodes
   * @param weights  the weights
   * @return the gap
   */
  public static double gap(double x, double alpha, DoubleArray nodes, DoubleArray weights) {
    int size = nodes.size();
    ArgChecker.isTrue(size == weights.size(), "nodes and weights must be the same size");
    double num = 1d;
    double den = 0d;
    for (int i = 0; i < size; ++i) {
      num += alpha * nodes.get(i) * weights.get(i);
      den += Math.sinh(alpha * nodes.get(i)) * weights.get(i);
    }
    return alpha / Math.abs(1d - num * Math.exp(alpha * x) / den);
  }

  //-------------------------------------------------------------------------
  /**
   * Evaluates the Smith-Wilson curve function at a x value. 
   * <p>
   * The {@code nodes} must be sorted in ascending order and coherent to {@code weights}.
   * 
   * @param x  the x value
   * @param alpha  the alpha parameter
   * @param nodes  the nodes
   * @param weights  the weights
   * @return the value
   */
  public double value(double x, double alpha, DoubleArray nodes, DoubleArray weights) {
    int size = nodes.size();
    ArgChecker.isTrue(size == weights.size(), "nodes and weights must be the same size");
    double res = 1d;
    int bound = x < nodes.get(0) ? 0 : FunctionUtils.getLowerBoundIndex(nodes, x) + 1;
    for (int i = 0; i < bound; ++i) {
      res += weights.get(i) * wilsonFunction(nodes.get(i), x, alpha);
    }
    for (int i = bound; i < size; ++i) {
      res += weights.get(i) * wilsonFunction(x, nodes.get(i), alpha);
    }
    res *= Math.exp(-omega * x);
    return res;
  }

  /**
   * Computes the gradient of the Smith-Wilson curve function at a x value. 
   * <p>
   * The {@code nodes} must be sorted in ascending order and coherent to {@code weights}.
   * 
   * @param x  the x value
   * @param alpha  the alpha parameter
   * @param nodes  the nodes
   * @param weights  the weights
   * @return the gradient
   */
  public double firstDerivative(double x, double alpha, DoubleArray nodes, DoubleArray weights) {
    int size = nodes.size();
    ArgChecker.isTrue(size == weights.size(), "nodes and weights must be the same size");
    double res = -omega;
    int bound = x < nodes.get(0) ? 0 : FunctionUtils.getLowerBoundIndex(nodes, x) + 1;
    for (int i = 0; i < bound; ++i) {
      res += weights.get(i) * wilsonFunctionDerivative(nodes.get(i), x, alpha, false);
    }
    for (int i = bound; i < size; ++i) {
      res += weights.get(i) * wilsonFunctionDerivative(x, nodes.get(i), alpha, true);
    }
    res *= Math.exp(-omega * x);
    return res;
  }

  /**
   * Computes the sensitivity of the Smith-Wilson curve function to weights parameters at a x value. 
   * <p>
   * The {@code nodes} must be sorted in ascending order.
   * 
   * @param x  the x value
   * @param alpha  the alpha parameter
   * @param nodes  the nodes
   * @return the value
   */
  public DoubleArray parameterSensitivity(double x, double alpha, DoubleArray nodes) {
    int size = nodes.size();
    double[] res = new double[size];
    double expOmega = Math.exp(-omega * x);
    int bound = x < nodes.get(0) ? 0 : FunctionUtils.getLowerBoundIndex(nodes, x) + 1;
    for (int i = 0; i < bound; ++i) {
      res[i] = expOmega * wilsonFunction(nodes.get(i), x, alpha);
    }
    for (int i = bound; i < size; ++i) {
      res[i] = expOmega * wilsonFunction(x, nodes.get(i), alpha);
    }
    return DoubleArray.ofUnsafe(res);
  }

  //-------------------------------------------------------------------------
  private static double wilsonFunction(double lo, double hi, double alpha) {
    double scaledLo = alpha * lo;
    if (!useStableKernel(lo, hi, alpha)) {
      return scaledLo - Math.exp(-alpha * hi) * Math.sinh(scaledLo);
    }
    double distance = alpha * (hi - lo);
    double expDistance = Math.exp(-distance);
    return wilsonKernel(scaledLo, distance, expDistance, scaledSinh(scaledLo, expDistance));
  }

  // Includes the derivative of Math.exp(-omega * x).
  private double wilsonFunctionDerivative(double lo, double hi, double alpha, boolean xIsLo) {
    double scaledLo = alpha * lo;
    if (!useStableKernel(lo, hi, alpha)) {
      double expHi = Math.exp(-alpha * hi);
      double sinh = Math.sinh(scaledLo);
      double gradient = xIsLo ? alpha * (1d - expHi * Math.cosh(scaledLo)) : alpha * expHi * sinh;
      return -omega * (scaledLo - expHi * sinh) + gradient;
    }
    double distance = alpha * (hi - lo);
    double expDistance = Math.exp(-distance);
    double sinh = scaledSinh(scaledLo, expDistance);
    // 1 - exp(-alpha * hi) * cosh(alpha * lo) = 1 - exp(-distance) + sinh.
    double gradient = xIsLo ? -Math.expm1(-distance) + sinh : sinh;
    return -omega * wilsonKernel(scaledLo, distance, expDistance, sinh) + alpha * gradient;
  }

  // Keep the original calculation outside the non-negative finite inputs.
  private static boolean useStableKernel(double lo, double hi, double alpha) {
    return lo >= 0d && Double.isFinite(hi) && alpha >= 0d && Double.isFinite(alpha);
  }

  // exp(-alpha * hi) * sinh(alpha * lo), without an overflowing intermediate.
  private static double scaledSinh(double scaledLo, double expDistance) {
    return -0.5d * expDistance * Math.expm1(-2d * scaledLo);
  }

  private static double wilsonKernel(double scaledLo, double distance, double expDistance, double sinh) {
    if (scaledLo < 0.01d) {
      // Half of exp(-2 * scaledLo) - 1 + 2 * scaledLo (terms through degree 8).
      // This avoids cancellation of the linear terms when distance is also small.
      double remainder = scaledLo * scaledLo * (1d + scaledLo * (-2d / 3d + scaledLo *
          (1d / 3d + scaledLo * (-2d / 15d + scaledLo * (2d / 45d + scaledLo * (-4d / 315d + scaledLo / 315d))))));
      return -scaledLo * Math.expm1(-distance) + expDistance * remainder;
    }
    return scaledLo - sinh;
  }

}
