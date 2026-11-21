/*
 * Copyright (C) 2026 - present by OpenGamma Inc. and the OpenGamma group of companies
 *
 * Please see distribution for license.
 */
package com.opengamma.strata.pricer.curve;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.opengamma.strata.basics.currency.Currency;
import com.opengamma.strata.basics.date.DayCount;
import com.opengamma.strata.basics.index.FloatingRateIndex;
import com.opengamma.strata.basics.index.ImmutableIborIndex;
import com.opengamma.strata.basics.index.ImmutableOvernightIndex;
import com.opengamma.strata.basics.index.Index;
import com.opengamma.strata.market.curve.CurveDefinition;
import com.opengamma.strata.market.curve.CurveNode;
import com.opengamma.strata.market.curve.InterpolatedNodalCurveDefinition;
import com.opengamma.strata.market.curve.RatesCurveGroupDefinition;
import com.opengamma.strata.market.curve.RatesCurveGroupEntry;
import com.opengamma.strata.market.curve.interpolator.CurveExtrapolator;
import com.opengamma.strata.market.curve.interpolator.CurveInterpolator;
import com.opengamma.strata.market.curve.node.FixedIborSwapCurveNode;
import com.opengamma.strata.market.curve.node.FixedOvernightSwapCurveNode;
import com.opengamma.strata.market.curve.node.TermDepositCurveNode;
import com.opengamma.strata.product.ResolvedTrade;
import com.opengamma.strata.product.deposit.ResolvedTermDepositTrade;
import com.opengamma.strata.product.swap.NotionalExchange;
import com.opengamma.strata.product.swap.RatePaymentPeriod;
import com.opengamma.strata.product.swap.ResolvedSwap;
import com.opengamma.strata.product.swap.ResolvedSwapLeg;
import com.opengamma.strata.product.swap.ResolvedSwapTrade;
import com.opengamma.strata.product.swap.SwapPaymentEvent;
import com.opengamma.strata.product.swap.SwapPaymentPeriod;

// Finds conservative currency blocks in the graph of curve usages and calibration trades.
final class RatesCurveCalibrationBlocks {

  private RatesCurveCalibrationBlocks() {
  }

  // Curves in each block are returned as indices in the original bound definition.
  static List<List<Integer>> find(RatesCurveGroupDefinition definition, List<ResolvedTrade> trades) {
    Map<Currency, List<Integer>> blocks = new LinkedHashMap<>();
    Set<Currency> discountCurrencies = new HashSet<>();
    Map<Index, Currency> forwardCurrencies = new HashMap<>();
    List<CurveDefinition> curves = definition.getCurveDefinitions();
    Currency[] currencies = new Currency[curves.size()];
    for (int i = 0; i < curves.size(); i++) {
      CurveDefinition curve = curves.get(i);
      if (!(curve instanceof InterpolatedNodalCurveDefinition) ||
          !usesBuiltInStrategies((InterpolatedNodalCurveDefinition) curve)) {
        return ImmutableList.of();
      }
      RatesCurveGroupEntry entry = definition.findEntry(curve.getName()).get();
      Currency currency = null;
      for (Currency discountCurrency : entry.getDiscountCurrencies()) {
        if (currency != null || !discountCurrencies.add(discountCurrency)) {
          return ImmutableList.of();
        }
        currency = discountCurrency;
      }
      for (Index index : entry.getIndices()) {
        if (!isSupportedRateIndex(index)) {
          return ImmutableList.of();
        }
        Currency indexCurrency = ((FloatingRateIndex) index).getCurrency();
        if ((currency != null && !currency.equals(indexCurrency)) || forwardCurrencies.put(index, indexCurrency) != null) {
          return ImmutableList.of();
        }
        currency = indexCurrency;
      }
      if (currency == null) {
        return ImmutableList.of();
      }
      currencies[i] = currency;
      blocks.computeIfAbsent(currency, ccy -> new ArrayList<>()).add(i);
    }
    if (blocks.size() < 2) {
      return ImmutableList.of();
    }
    int tradeIndex = 0;
    for (int i = 0; i < curves.size(); i++) {
      for (CurveNode node : curves.get(i).getNodes()) {
        if (!isLocalTrade(node, trades.get(tradeIndex++), currencies[i], forwardCurrencies)) {
          return ImmutableList.of();
        }
      }
    }
    return new ArrayList<>(blocks.values());
  }

  private static boolean usesBuiltInStrategies(InterpolatedNodalCurveDefinition curve) {
    // Standard names can be overridden through ExtendedEnum, so names alone do not identify the implementation.
    return isBuiltIn(curve.getInterpolator(), CurveInterpolator.class) &&
        isBuiltIn(curve.getExtrapolatorLeft(), CurveExtrapolator.class) &&
        isBuiltIn(curve.getExtrapolatorRight(), CurveExtrapolator.class) &&
        curve.getDayCount().map(dayCount -> isBuiltIn(dayCount, DayCount.class)).orElse(true);
  }

  private static boolean isBuiltIn(Object strategy, Class<?> standardType) {
    return strategy.getClass().getPackage() == standardType.getPackage();
  }

  private static boolean isSupportedRateIndex(Index index) {
    return (index instanceof ImmutableIborIndex || index instanceof ImmutableOvernightIndex) &&
        isBuiltIn(((FloatingRateIndex) index).getDayCount(), DayCount.class);
  }

  private static boolean isLocalTrade(
      CurveNode node,
      ResolvedTrade trade,
      Currency currency,
      Map<Index, Currency> forwardCurrencies) {

    if (node instanceof TermDepositCurveNode && trade instanceof ResolvedTermDepositTrade) {
      return ((ResolvedTermDepositTrade) trade).getProduct().getCurrency().equals(currency);
    }
    if ((node instanceof FixedIborSwapCurveNode || node instanceof FixedOvernightSwapCurveNode) &&
        trade instanceof ResolvedSwapTrade) {
      return isLocalSwap(((ResolvedSwapTrade) trade).getProduct(), currency, forwardCurrencies);
    }
    return false;
  }

  private static boolean isLocalSwap(ResolvedSwap swap, Currency currency, Map<Index, Currency> forwardCurrencies) {
    if (swap.allPaymentCurrencies().size() != 1 || !swap.allPaymentCurrencies().contains(currency)) {
      return false;
    }
    for (Index index : swap.allIndices()) {
      // The provider looks up forward curves by index equality, which can match distinct instances by name.
      if (!isSupportedRateIndex(index) || !((FloatingRateIndex) index).getCurrency().equals(currency) ||
          !currency.equals(forwardCurrencies.get(index))) {
        return false;
      }
    }
    // allIndices() does not cover payment events such as FX-reset notional exchanges.
    for (ResolvedSwapLeg leg : swap.getLegs()) {
      for (SwapPaymentEvent event : leg.getPaymentEvents()) {
        if (!(event instanceof NotionalExchange) || !event.getCurrency().equals(currency)) {
          return false;
        }
      }
      for (SwapPaymentPeriod period : leg.getPaymentPeriods()) {
        if (!(period instanceof RatePaymentPeriod) || !period.getCurrency().equals(currency) ||
            ((RatePaymentPeriod) period).getFxReset().isPresent()) {
          return false;
        }
      }
    }
    return true;
  }

}
