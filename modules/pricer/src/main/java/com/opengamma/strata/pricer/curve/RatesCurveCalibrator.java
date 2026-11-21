/*
 * Copyright (C) 2015 - present by OpenGamma Inc. and the OpenGamma group of companies
 *
 * Please see distribution for license.
 */
package com.opengamma.strata.pricer.curve;

import static com.opengamma.strata.collect.Guavate.filtering;
import static com.opengamma.strata.collect.Guavate.toImmutableList;
import static com.opengamma.strata.collect.Guavate.toImmutableMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.opengamma.strata.basics.ReferenceData;
import com.opengamma.strata.basics.index.Index;
import com.opengamma.strata.collect.ArgChecker;
import com.opengamma.strata.collect.Messages;
import com.opengamma.strata.collect.array.DoubleArray;
import com.opengamma.strata.collect.array.DoubleMatrix;
import com.opengamma.strata.collect.timeseries.LocalDateDoubleTimeSeries;
import com.opengamma.strata.data.MarketData;
import com.opengamma.strata.data.MarketDataFxRateProvider;
import com.opengamma.strata.market.curve.CurveName;
import com.opengamma.strata.market.curve.CurveNode;
import com.opengamma.strata.market.curve.CurveParameterSize;
import com.opengamma.strata.market.curve.JacobianCalibrationMatrix;
import com.opengamma.strata.market.curve.RatesCurveGroupDefinition;
import com.opengamma.strata.market.observable.IndexQuoteId;
import com.opengamma.strata.math.MathException;
import com.opengamma.strata.math.impl.linearalgebra.SVDecompositionCommons;
import com.opengamma.strata.math.impl.linearalgebra.SVDecompositionResult;
import com.opengamma.strata.math.impl.matrix.CommonsMatrixAlgebra;
import com.opengamma.strata.math.impl.matrix.MatrixAlgebra;
import com.opengamma.strata.math.rootfind.NewtonVectorRootFinder;
import com.opengamma.strata.pricer.rate.ImmutableRatesProvider;
import com.opengamma.strata.product.ResolvedTrade;

/**
 * Curve calibrator for rates curves.
 * <p>
 * This calibrator takes an abstract curve definition and produces real curves.
 * <p>
 * Curves are calibrated in groups or one or more curves.
 * In addition, more than one group may be calibrated together.
 * <p>
 * Each curve is defined using two or more {@linkplain CurveNode nodes}.
 * Each node primarily defines enough information to produce a reference trade.
 * Calibration involves pricing, and re-pricing, these trades to find the best fit
 * using a root finder.
 * <p>
 * Once calibrated, the curves are then available for use.
 * Each node in the curve definition becomes a parameter in the matching output curve.
 */
public final class RatesCurveCalibrator {

  /**
   * The standard curve calibrator.
   */
  private static final RatesCurveCalibrator STANDARD =
      RatesCurveCalibrator.of(1e-9, 1e-9, 1000, CalibrationMeasures.PAR_SPREAD, CalibrationMeasures.PRESENT_VALUE);
  /**
   * The matrix algebra used for matrix inversion.
   */
  private static final MatrixAlgebra MATRIX_ALGEBRA = new CommonsMatrixAlgebra();

  /**
   * The root finder used for curve calibration.
   */
  private final NewtonVectorRootFinder rootFinder;
  /**
   * The calibration measures.
   * This is used to compute the function for which the root is found.
   */
  private final CalibrationMeasures measures;
  /**
   * The present value measures.
   * This is used to compute the present value sensitivity to market quotes stored in the metadata.
   */
  private final CalibrationMeasures pvMeasures;
  // Absolute tolerance of the built-in root finder, or zero for a supplied root finder.
  private final double splitTolerance;

  //-------------------------------------------------------------------------
  /**
   * The standard curve calibrator.
   * <p>
   * This uses the standard tolerance of 1e-9, a maximum of 1000 steps.
   * The default {@link CalibrationMeasures#PAR_SPREAD} measures are used.
   *
   * @return the standard curve calibrator
   */
  public static RatesCurveCalibrator standard() {
    return RatesCurveCalibrator.STANDARD;
  }

  /**
   * Obtains an instance specifying tolerances to use.
   * <p>
   * This uses a Broyden root finder.
   * The standard {@link CalibrationMeasures#PAR_SPREAD} and {@link CalibrationMeasures#PRESENT_VALUE} measures are used.
   *
   * @param toleranceAbs  the absolute tolerance
   * @param toleranceRel  the relative tolerance
   * @param stepMaximum  the maximum steps
   * @return the curve calibrator
   */
  public static RatesCurveCalibrator of(
      double toleranceAbs,
      double toleranceRel,
      int stepMaximum) {

    return of(toleranceAbs, toleranceRel, stepMaximum, CalibrationMeasures.PAR_SPREAD, CalibrationMeasures.PRESENT_VALUE);
  }

  /**
   * Obtains an instance specifying tolerances and measures to use.
   * <p>
   * This uses a Broyden root finder.
   * The standard {@link CalibrationMeasures#PRESENT_VALUE} measures are used.
   *
   * @param toleranceAbs  the absolute tolerance
   * @param toleranceRel  the relative tolerance
   * @param stepMaximum  the maximum steps
   * @param measures  the calibration measures, used to compute the function for which the root is found
   * @return the curve calibrator
   */
  public static RatesCurveCalibrator of(
      double toleranceAbs,
      double toleranceRel,
      int stepMaximum,
      CalibrationMeasures measures) {

    return of(toleranceAbs, toleranceRel, stepMaximum, measures, CalibrationMeasures.PRESENT_VALUE);
  }

  /**
   * Obtains an instance specifying tolerances and measures to use.
   * <p>
   * This uses a Broyden root finder.
   *
   * @param toleranceAbs  the absolute tolerance
   * @param toleranceRel  the relative tolerance
   * @param stepMaximum  the maximum steps
   * @param measures  the calibration measures, used to compute the function for which the root is found
   * @param pvMeasures  the present value measures, used to compute the present value sensitivity to market quotes 
   *   stored in the metadata
   * @return the curve calibrator
   */
  public static RatesCurveCalibrator of(
      double toleranceAbs,
      double toleranceRel,
      int stepMaximum,
      CalibrationMeasures measures,
      CalibrationMeasures pvMeasures) {

    NewtonVectorRootFinder rootFinder = NewtonVectorRootFinder.broyden(toleranceAbs, toleranceRel, stepMaximum);
    return new RatesCurveCalibrator(rootFinder, measures, pvMeasures, toleranceAbs);
  }

  /**
   * Obtains an instance specifying the measures to use.
   *
   * @param rootFinder  the root finder to use
   * @param measures  the calibration measures, used to compute the function for which the root is found
   * @param pvMeasures  the present value measures, used to compute the present value sensitivity to market quotes 
   *   stored in the metadata
   * @return the curve calibrator
   */
  public static RatesCurveCalibrator of(
      NewtonVectorRootFinder rootFinder,
      CalibrationMeasures measures,
      CalibrationMeasures pvMeasures) {

    return new RatesCurveCalibrator(rootFinder, measures, pvMeasures, 0d);
  }

  //-------------------------------------------------------------------------
  // restricted constructor
  private RatesCurveCalibrator(
      NewtonVectorRootFinder rootFinder,
      CalibrationMeasures measures,
      CalibrationMeasures pvMeasures,
      double splitTolerance) {

    this.rootFinder = ArgChecker.notNull(rootFinder, "rootFinder");
    this.measures = ArgChecker.notNull(measures, "measures");
    this.pvMeasures = ArgChecker.notNull(pvMeasures, "pvMeasures");
    this.splitTolerance = splitTolerance;
  }

  //-------------------------------------------------------------------------
  /**
   * Gets the measures.
   * 
   * @return the measures
   */
  public CalibrationMeasures getMeasures() {
    return measures;
  }

  //-------------------------------------------------------------------------
  /**
   * Calibrates a single curve group, containing one or more curves.
   * <p>
   * The calibration is defined using {@link RatesCurveGroupDefinition}.
   * Observable market data, time-series and FX are also needed to complete the calibration.
   * The valuation date is defined by the market data.
   * <p>
   * The Jacobian matrices are computed and stored in curve metadata.
   * Proven independent currency blocks may be calibrated separately with the standard
   * calibration measures. The Jacobian matrices retain the full calibration order.
   *
   * @param curveGroupDefn  the curve group definition
   * @param marketData  the market data required to build a trade for the instrument, including time-series
   * @param refData  the reference data, used to resolve the trades
   * @return the rates provider resulting from the calibration
   */
  public ImmutableRatesProvider calibrate(
      RatesCurveGroupDefinition curveGroupDefn,
      MarketData marketData,
      ReferenceData refData) {

    Map<Index, LocalDateDoubleTimeSeries> timeSeries = marketData.getTimeSeriesIds().stream()
        .flatMap(filtering(IndexQuoteId.class))
        .collect(toImmutableMap(id -> id.getIndex(), id -> marketData.getTimeSeries(id)));
    ImmutableRatesProvider knownData = ImmutableRatesProvider.builder(marketData.getValuationDate())
        .fxRateProvider(MarketDataFxRateProvider.of(marketData))
        .timeSeries(timeSeries)
        .build();
    boolean allowSplit = splitTolerance > 0d && measures == CalibrationMeasures.PAR_SPREAD &&
        pvMeasures == CalibrationMeasures.PRESENT_VALUE;
    return calibrate(ImmutableList.of(curveGroupDefn), knownData, marketData, refData, allowSplit);
  }

  /**
   * Calibrates a list of curve groups, each containing one or more curves.
   * <p>
   * The calibration is defined using a list of {@link RatesCurveGroupDefinition}.
   * Observable market data and existing known data are also needed to complete the calibration.
   * <p>
   * A curve must only exist in one group.
   *
   * @param allGroupDefns  the curve group definitions
   * @param knownData  the starting data for the calibration
   * @param marketData  the market data required to build a trade for the instrument
   * @param refData  the reference data, used to resolve the trades
   * @return the rates provider resulting from the calibration
   */
  public ImmutableRatesProvider calibrate(
      List<RatesCurveGroupDefinition> allGroupDefns,
      ImmutableRatesProvider knownData,
      MarketData marketData,
      ReferenceData refData) {

    return calibrate(allGroupDefns, knownData, marketData, refData, false);
  }

  private ImmutableRatesProvider calibrate(
      List<RatesCurveGroupDefinition> allGroupDefns,
      ImmutableRatesProvider knownData,
      MarketData marketData,
      ReferenceData refData,
      boolean allowSplit) {

    // this method effectively takes one CurveGroupDefinition
    // the list is a split of the definition, not multiple independent definitions

    if (!knownData.getValuationDate().equals(marketData.getValuationDate())) {
      throw new IllegalArgumentException(Messages.format(
          "Valuation dates do not match: {} and {}", knownData.getValuationDate(), marketData.getValuationDate()));
    }
    // perform calibration one group at a time, building up the result by mutating these variables
    ImmutableRatesProvider providerCombined = knownData;
    ImmutableList<CurveParameterSize> orderPrev = ImmutableList.of();
    ImmutableMap<CurveName, JacobianCalibrationMatrix> jacobians = ImmutableMap.of();
    for (RatesCurveGroupDefinition groupDefn : allGroupDefns) {
      if (groupDefn.getEntries().isEmpty()) {
        continue;
      }
      RatesCurveGroupDefinition groupDefnBound =
          groupDefn.bindTimeSeries(knownData.getValuationDate(), knownData.getTimeSeries());
      // combine all data in the group into flat lists
      ImmutableList<ResolvedTrade> trades = groupDefnBound.resolvedTrades(marketData, refData);
      ImmutableList<Double> initialGuesses = groupDefnBound.initialGuesses(marketData);
      ImmutableList<CurveParameterSize> orderGroup = toOrder(groupDefnBound);
      ImmutableList<CurveParameterSize> orderPrevAndGroup = ImmutableList.<CurveParameterSize>builder()
          .addAll(orderPrev)
          .addAll(orderGroup)
          .build();

      // calibrate
      RatesProviderGenerator providerGenerator = ImmutableRatesProviderGenerator.of(providerCombined, groupDefnBound, refData);
      if (allowSplit && orderGroup.size() > 1) {
        List<List<Integer>> blocks = RatesCurveCalibrationBlocks.find(groupDefnBound, trades);
        if (!blocks.isEmpty()) {
          ImmutableRatesProvider splitProvider =
              calibrateBlocks(groupDefnBound, knownData, refData, providerGenerator, trades, initialGuesses, orderGroup, blocks);
          if (splitProvider != null) {
            return splitProvider;
          }
        }
      }
      DoubleArray calibratedGroupParams = calibrateGroup(providerGenerator, trades, initialGuesses, orderGroup);
      ImmutableRatesProvider calibratedProvider = providerGenerator.generate(calibratedGroupParams);

      // use calibration to build Jacobian matrices
      if (groupDefnBound.isComputeJacobian()) {
        jacobians = updateJacobiansForGroup(
            calibratedProvider, trades, orderGroup, orderPrev, orderPrevAndGroup, jacobians);
      }
      ImmutableMap<CurveName, DoubleArray> sensitivityToMarketQuote = ImmutableMap.of();
      if (groupDefnBound.isComputePvSensitivityToMarketQuote()) {
        ImmutableRatesProvider providerWithJacobian = providerGenerator.generate(calibratedGroupParams, jacobians);
        sensitivityToMarketQuote = sensitivityToMarketQuoteForGroup(providerWithJacobian, trades, orderGroup);
      }
      orderPrev = orderPrevAndGroup;

      // use Jacobians to build output curves
      providerCombined = providerGenerator.generate(calibratedGroupParams, jacobians, sensitivityToMarketQuote);
    }
    // return the calibrated provider
    return providerCombined;
  }

  // Calibrate each independent block, then put its parameters and Jacobian columns
  // back in the order of the original group.
  private ImmutableRatesProvider calibrateBlocks(
      RatesCurveGroupDefinition definition,
      ImmutableRatesProvider knownData,
      ReferenceData refData,
      RatesProviderGenerator fullGenerator,
      ImmutableList<ResolvedTrade> trades,
      ImmutableList<Double> initialGuesses,
      ImmutableList<CurveParameterSize> order,
      List<List<Integer>> blocks) {

    int[] offsets = new int[order.size() + 1];
    for (int i = 0; i < order.size(); i++) {
      offsets[i + 1] = offsets[i] + order.get(i).getParameterCount();
    }
    int size = offsets[order.size()];
    double[] parameters = new double[size];
    Builder<CurveName, JacobianCalibrationMatrix> jacobians = ImmutableMap.builder();
    double minSingular = Double.POSITIVE_INFINITY;
    double maxSingular = 0d;
    SVDecompositionCommons svd = new SVDecompositionCommons();
    try {
      for (List<Integer> block : blocks) {
        RatesCurveGroupDefinition blockDefinition = definition.withCurveDefinitions(
            block.stream().map(definition.getCurveDefinitions()::get).collect(toImmutableList()));
        RatesProviderGenerator generator = ImmutableRatesProviderGenerator.of(knownData, blockDefinition, refData);
        ImmutableList<CurveParameterSize> blockOrder = block.stream().map(order::get).collect(toImmutableList());
        ImmutableList.Builder<ResolvedTrade> blockTradesBuilder = ImmutableList.builder();
        ImmutableList.Builder<Double> guesses = ImmutableList.builder();
        List<Integer> positions = new ArrayList<>();
        for (int curve : block) {
          blockTradesBuilder.addAll(trades.subList(offsets[curve], offsets[curve + 1]));
          guesses.addAll(initialGuesses.subList(offsets[curve], offsets[curve + 1]));
          for (int position = offsets[curve]; position < offsets[curve + 1]; position++) {
            positions.add(position);
          }
        }
        ImmutableList<ResolvedTrade> blockTrades = blockTradesBuilder.build();
        DoubleArray blockParameters = calibrateGroup(generator, blockTrades, guesses.build(), blockOrder);
        for (int i = 0; i < positions.size(); i++) {
          parameters[positions.get(i)] = blockParameters.get(i);
        }

        ImmutableRatesProvider provider = generator.generate(blockParameters);
        int blockSize = positions.size();
        SVDecompositionResult blockSvd = svd.apply(derivatives(blockTrades, provider, blockOrder, blockSize));
        double[] singularValues = blockSvd.getSingularValues();
        maxSingular = Math.max(maxSingular, singularValues[0]);
        minSingular = Math.min(minSingular, singularValues[singularValues.length - 1]);
        if (definition.isComputeJacobian()) {
          DoubleMatrix inverse = blockSvd.solve(DoubleMatrix.identity(blockSize));
          int row = 0;
          for (CurveParameterSize curve : blockOrder) {
            double[][] values = new double[curve.getParameterCount()][size];
            for (double[] curveRow : values) {
              for (int column = 0; column < blockSize; column++) {
                curveRow[positions.get(column)] = inverse.get(row, column);
              }
              row++;
            }
            jacobians.put(curve.getName(), JacobianCalibrationMatrix.of(order, DoubleMatrix.ofUnsafe(values)));
          }
        }
      }
      // Allow a margin around the dense solver's SVD rank cutoff.
      double cutoff = Math.max(Math.sqrt(Double.MIN_NORMAL), 8d * size * Math.ulp(maxSingular));
      if (!(minSingular > cutoff)) {
        return null;
      }
      DoubleArray allParameters = DoubleArray.ofUnsafe(parameters);
      ImmutableMap<CurveName, JacobianCalibrationMatrix> jacobianMap = jacobians.build();
      ImmutableRatesProvider provider = fullGenerator.generate(allParameters, jacobianMap);
      double residualSquared = 0d;
      for (ResolvedTrade trade : trades) {
        double residual = measures.value(trade, provider);
        residualSquared += residual * residual;
      }
      if (!(Math.sqrt(residualSquared) < splitTolerance)) {
        return null;
      }
      if (definition.isComputePvSensitivityToMarketQuote()) {
        ImmutableMap<CurveName, DoubleArray> sensitivities = sensitivityToMarketQuoteForGroup(provider, trades, order);
        return fullGenerator.generate(allParameters, jacobianMap, sensitivities);
      }
      return provider;
    } catch (MathException | IllegalArgumentException ex) {
      // Retry the joint solve if any part of the split calibration fails.
      return null;
    }
  }

  //-------------------------------------------------------------------------
  // converts a definition to the curve order list
  private static ImmutableList<CurveParameterSize> toOrder(RatesCurveGroupDefinition groupDefn) {
    return groupDefn.getCurveDefinitions().stream().map(def -> def.toCurveParameterSize()).collect(toImmutableList());
  }

  //-------------------------------------------------------------------------
  // calibrates a single group
  private DoubleArray calibrateGroup(
      RatesProviderGenerator providerGenerator,
      ImmutableList<ResolvedTrade> trades,
      ImmutableList<Double> initialGuesses,
      ImmutableList<CurveParameterSize> curveOrder) {

    // setup for calibration
    Function<DoubleArray, DoubleArray> valueCalculator = new CalibrationValue(trades, measures, providerGenerator);
    Function<DoubleArray, DoubleMatrix> derivativeCalculator =
        new CalibrationDerivative(trades, measures, providerGenerator, curveOrder);

    // calibrate
    DoubleArray initialGuess = DoubleArray.copyOf(initialGuesses);
    return rootFinder.findRoot(valueCalculator, derivativeCalculator, initialGuess);
  }

  //-------------------------------------------------------------------------
  // calculates the Jacobian and builds the result, called once per group
  // this uses, but does not alter, data from previous groups
  private ImmutableMap<CurveName, JacobianCalibrationMatrix> updateJacobiansForGroup(
      ImmutableRatesProvider provider,
      ImmutableList<ResolvedTrade> trades,
      ImmutableList<CurveParameterSize> orderGroup,
      ImmutableList<CurveParameterSize> orderPrev,
      ImmutableList<CurveParameterSize> orderAll,
      ImmutableMap<CurveName, JacobianCalibrationMatrix> jacobians) {

    // sensitivity to all parameters in the stated order
    int totalParamsAll = orderAll.stream().mapToInt(e -> e.getParameterCount()).sum();
    DoubleMatrix res = derivatives(trades, provider, orderAll, totalParamsAll);

    // jacobian direct
    int nbTrades = trades.size();
    int totParamsGroup = orderGroup.stream().mapToInt(e -> e.getParameterCount()).sum();
    int totParamsPrev = totalParamsAll - totParamsGroup;
    DoubleMatrix pDmCurMatrix = jacobianDirect(res, nbTrades, totParamsGroup, totParamsPrev);

    // jacobian indirect: when totalParamsPrevious > 0
    DoubleMatrix pDmPrev = jacobianIndirect(
        res, pDmCurMatrix, nbTrades, totParamsGroup, totParamsPrev, orderPrev, jacobians);

    // add to the map of jacobians, one entry for each curve in this group
    ImmutableMap.Builder<CurveName, JacobianCalibrationMatrix> jacobianBuilder = ImmutableMap.builder();
    jacobianBuilder.putAll(jacobians);
    int startIndex = 0;
    for (CurveParameterSize order : orderGroup) {
      int paramCount = order.getParameterCount();
      double[][] pDmCurveArray = new double[paramCount][totalParamsAll];
      // copy data for previous groups
      if (totParamsPrev > 0) {
        for (int p = 0; p < paramCount; p++) {
          System.arraycopy(pDmPrev.rowArray(startIndex + p), 0, pDmCurveArray[p], 0, totParamsPrev);
        }
      }
      // copy data for this group
      for (int p = 0; p < paramCount; p++) {
        System.arraycopy(pDmCurMatrix.rowArray(startIndex + p), 0, pDmCurveArray[p], totParamsPrev, totParamsGroup);
      }
      // build final Jacobian matrix
      DoubleMatrix pDmCurveMatrix = DoubleMatrix.ofUnsafe(pDmCurveArray);
      jacobianBuilder.put(order.getName(), JacobianCalibrationMatrix.of(orderAll, pDmCurveMatrix));
      startIndex += paramCount;
    }
    return jacobianBuilder.build();
  }

  //-------------------------------------------------------------------------
  private ImmutableMap<CurveName, DoubleArray> sensitivityToMarketQuoteForGroup(
      ImmutableRatesProvider provider,
      ImmutableList<ResolvedTrade> trades,
      ImmutableList<CurveParameterSize> orderGroup) {

    Builder<CurveName, DoubleArray> mqsGroup = new Builder<>();
    int nodeIndex = 0;
    for (CurveParameterSize cps : orderGroup) {
      int nbParameters = cps.getParameterCount();
      double[] mqsCurve = new double[nbParameters];
      for (int looptrade = 0; looptrade < nbParameters; looptrade++) {
        DoubleArray mqsNode = pvMeasures.derivative(trades.get(nodeIndex), provider, orderGroup);
        mqsCurve[looptrade] = mqsNode.get(nodeIndex);
        nodeIndex++;
      }
      mqsGroup.put(cps.getName(), DoubleArray.ofUnsafe(mqsCurve));
    }
    return mqsGroup.build();
  }

  // calculate the derivatives
  private DoubleMatrix derivatives(
      ImmutableList<ResolvedTrade> trades,
      ImmutableRatesProvider provider,
      ImmutableList<CurveParameterSize> orderAll,
      int totalParamsAll) {

    return DoubleMatrix.ofArrayObjects(
        trades.size(),
        totalParamsAll,
        i -> measures.derivative(trades.get(i), provider, orderAll));
  }

  // jacobian direct, for the current group
  private static DoubleMatrix jacobianDirect(
      DoubleMatrix res,
      int nbTrades,
      int totalParamsGroup,
      int totalParamsPrevious) {

    double[][] direct = new double[totalParamsGroup][totalParamsGroup];
    for (int i = 0; i < nbTrades; i++) {
      System.arraycopy(res.rowArray(i), totalParamsPrevious, direct[i], 0, totalParamsGroup);
    }
    return MATRIX_ALGEBRA.getInverse(DoubleMatrix.copyOf(direct));
  }

  // jacobian indirect, merging groups
  private static DoubleMatrix jacobianIndirect(
      DoubleMatrix res,
      DoubleMatrix pDmCurrentMatrix,
      int nbTrades,
      int totalParamsGroup,
      int totalParamsPrevious,
      ImmutableList<CurveParameterSize> orderPrevious,
      ImmutableMap<CurveName, JacobianCalibrationMatrix> jacobiansPrevious) {

    if (totalParamsPrevious == 0) {
      return DoubleMatrix.EMPTY;
    }
    double[][] nonDirect = new double[totalParamsGroup][totalParamsPrevious];
    for (int i = 0; i < nbTrades; i++) {
      System.arraycopy(res.rowArray(i), 0, nonDirect[i], 0, totalParamsPrevious);
    }
    DoubleMatrix pDpPreviousMatrix = (DoubleMatrix) MATRIX_ALGEBRA.scale(
        MATRIX_ALGEBRA.multiply(pDmCurrentMatrix, DoubleMatrix.copyOf(nonDirect)), -1d);
    // all curves: order and size
    int[] startIndexBefore = new int[orderPrevious.size()];
    for (int i = 1; i < orderPrevious.size(); i++) {
      startIndexBefore[i] = startIndexBefore[i - 1] + orderPrevious.get(i - 1).getParameterCount();
    }
    // transition Matrix: all curves from previous groups
    double[][] transition = new double[totalParamsPrevious][totalParamsPrevious];
    for (int i = 0; i < orderPrevious.size(); i++) {
      int paramCountOuter = orderPrevious.get(i).getParameterCount();
      JacobianCalibrationMatrix thisInfo = jacobiansPrevious.get(orderPrevious.get(i).getName());
      DoubleMatrix thisMatrix = thisInfo.getJacobianMatrix();
      int startIndexInner = 0;
      for (int j = 0; j < orderPrevious.size(); j++) {
        int paramCountInner = orderPrevious.get(j).getParameterCount();
        if (thisInfo.containsCurve(orderPrevious.get(j).getName())) { // If not, the matrix stays with 0
          for (int k = 0; k < paramCountOuter; k++) {
            System.arraycopy(
                thisMatrix.rowArray(k),
                startIndexInner,
                transition[startIndexBefore[i] + k],
                startIndexBefore[j],
                paramCountInner);
          }
        }
        startIndexInner += paramCountInner;
      }
    }
    DoubleMatrix transitionMatrix = DoubleMatrix.copyOf(transition);
    return (DoubleMatrix) MATRIX_ALGEBRA.multiply(pDpPreviousMatrix, transitionMatrix);
  }

  //-------------------------------------------------------------------------
  @Override
  public String toString() {
    return Messages.format("CurveCalibrator[{}]", measures);
  }

}
