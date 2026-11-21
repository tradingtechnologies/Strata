/*
 * Copyright (C) 2026 - present by OpenGamma Inc. and the OpenGamma group of companies
 *
 * Please see distribution for license.
 */
package com.opengamma.strata.pricer.curve;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.opengamma.strata.pricer.rate.ImmutableRatesProvider;

/**
 * Opt-in same-JVM benchmark for the single-group calibrator entry point.
 */
public class RatesCurveCalibrationBenchmark {

  private static final long BATCH_NANOS = 200_000_000L;
  private static final int MAX_REPETITIONS = 20_000;
  private static final int WARMUP_PAIRS = 6;
  private static final int MEASUREMENT_PAIRS = 8;
  private static final int[][] SIZES = {{2, 8}, {4, 16}, {6, 24}};
  private static volatile ImmutableRatesProvider SINK;

  @Test
  public void benchmark() {
    System.out.printf(Locale.ROOT, "Strata calibration benchmark on Java %s (%s), %s %s, %d processors%n",
        System.getProperty("java.version"), System.getProperty("java.vm.name"), System.getProperty("os.name"),
        System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors());
    System.out.printf(Locale.ROOT, "Warmup pairs: %d, measured pairs: %d, target batch milliseconds: %d%n",
        WARMUP_PAIRS, MEASUREMENT_PAIRS, BATCH_NANOS / 1_000_000);
    System.out.println("batch,scenario,currencies,nodes_per_currency,baseline_ops,candidate_ops," +
        "baseline_us_per_op,candidate_us_per_op,speedup");
    RatesCurveCalibrator calibrator = RatesCurveCalibrator.standard();
    for (int[] size : SIZES) {
      measure(calibrator, size[0], size[1], false);
      measure(calibrator, size[0], size[1], true);
    }
  }

  private static void measure(RatesCurveCalibrator calibrator, int currencies, int nodes, boolean connected) {
    RatesCurveCalibrationFixture fixture = RatesCurveCalibrationFixture.of(currencies, nodes, connected);
    Supplier<ImmutableRatesProvider> baseline = () -> fixture.baseline(calibrator);
    Supplier<ImmutableRatesProvider> candidate = () -> fixture.candidate(calibrator);
    for (int pair = 0; pair < WARMUP_PAIRS; pair++) {
      if (pair % 2 == 0) {
        timeBatch(baseline);
        timeBatch(candidate);
      } else {
        timeBatch(candidate);
        timeBatch(baseline);
      }
    }
    String scenario = connected ? "connected" : "independent";
    double[] baselineTimes = new double[MEASUREMENT_PAIRS];
    double[] candidateTimes = new double[MEASUREMENT_PAIRS];
    double[] speedups = new double[MEASUREMENT_PAIRS];
    for (int pair = 0; pair < MEASUREMENT_PAIRS; pair++) {
      Batch baselineBatch;
      Batch candidateBatch;
      if (pair % 2 == 0) {
        baselineBatch = timeBatch(baseline);
        candidateBatch = timeBatch(candidate);
      } else {
        candidateBatch = timeBatch(candidate);
        baselineBatch = timeBatch(baseline);
      }
      baselineTimes[pair] = baselineBatch.microsPerOperation;
      candidateTimes[pair] = candidateBatch.microsPerOperation;
      speedups[pair] = baselineTimes[pair] / candidateTimes[pair];
      System.out.printf(Locale.ROOT, "%d,%s,%d,%d,%d,%d,%.3f,%.3f,%.4f%n", pair + 1, scenario, currencies,
          nodes, baselineBatch.operations, candidateBatch.operations, baselineTimes[pair], candidateTimes[pair], speedups[pair]);
    }
    System.out.printf(Locale.ROOT, "median,%s,%d,%d,,,%.3f,%.3f,%.4f%n", scenario, currencies,
        nodes, median(baselineTimes), median(candidateTimes), median(speedups));
  }

  private static Batch timeBatch(Supplier<ImmutableRatesProvider> calibrate) {
    long start = System.nanoTime();
    int repetitions = 0;
    long elapsed;
    do {
      SINK = calibrate.get();
      repetitions++;
      elapsed = System.nanoTime() - start;
    } while (repetitions < MAX_REPETITIONS && elapsed < BATCH_NANOS);
    return new Batch(repetitions, elapsed / (1_000d * repetitions));
  }

  private static double median(double[] values) {
    Arrays.sort(values);
    int middle = values.length / 2;
    return (values[middle - 1] + values[middle]) / 2d;
  }

  private static final class Batch {
    private final int operations;
    private final double microsPerOperation;

    private Batch(int operations, double microsPerOperation) {
      this.operations = operations;
      this.microsPerOperation = microsPerOperation;
    }
  }

}
