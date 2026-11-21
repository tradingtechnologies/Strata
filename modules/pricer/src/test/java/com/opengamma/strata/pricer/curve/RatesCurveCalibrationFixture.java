/*
 * Copyright (C) 2015 - present by OpenGamma Inc. and the OpenGamma group of companies
 *
 * Please see distribution for license.
 */
package com.opengamma.strata.pricer.curve;

import static com.opengamma.strata.basics.date.DayCounts.ACT_365F;
import static com.opengamma.strata.collect.Guavate.filtering;
import static com.opengamma.strata.collect.Guavate.toImmutableMap;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.opengamma.strata.basics.ReferenceData;
import com.opengamma.strata.basics.StandardId;
import com.opengamma.strata.basics.currency.Currency;
import com.opengamma.strata.basics.currency.CurrencyPair;
import com.opengamma.strata.basics.currency.FxRate;
import com.opengamma.strata.basics.date.BusinessDayAdjustment;
import com.opengamma.strata.basics.date.DaysAdjustment;
import com.opengamma.strata.basics.index.Index;
import com.opengamma.strata.collect.timeseries.LocalDateDoubleTimeSeries;
import com.opengamma.strata.data.FxRateId;
import com.opengamma.strata.data.ImmutableMarketData;
import com.opengamma.strata.data.ImmutableMarketDataBuilder;
import com.opengamma.strata.data.MarketDataFxRateProvider;
import com.opengamma.strata.market.ValueType;
import com.opengamma.strata.market.curve.CurveGroupName;
import com.opengamma.strata.market.curve.CurveName;
import com.opengamma.strata.market.curve.CurveNode;
import com.opengamma.strata.market.curve.InterpolatedNodalCurveDefinition;
import com.opengamma.strata.market.curve.RatesCurveGroupDefinition;
import com.opengamma.strata.market.curve.RatesCurveGroupDefinitionBuilder;
import com.opengamma.strata.market.curve.interpolator.CurveExtrapolators;
import com.opengamma.strata.market.curve.interpolator.CurveInterpolators;
import com.opengamma.strata.market.curve.node.FxSwapCurveNode;
import com.opengamma.strata.market.curve.node.TermDepositCurveNode;
import com.opengamma.strata.market.observable.IndexQuoteId;
import com.opengamma.strata.market.observable.QuoteId;
import com.opengamma.strata.pricer.rate.ImmutableRatesProvider;
import com.opengamma.strata.product.common.BuySell;
import com.opengamma.strata.product.deposit.ResolvedTermDeposit;
import com.opengamma.strata.product.deposit.type.ImmutableTermDepositConvention;
import com.opengamma.strata.product.deposit.type.TermDepositConvention;
import com.opengamma.strata.product.deposit.type.TermDepositTemplate;
import com.opengamma.strata.product.fx.type.FxSwapConvention;
import com.opengamma.strata.product.fx.type.FxSwapTemplate;
import com.opengamma.strata.product.fx.type.ImmutableFxSwapConvention;

/** Deterministic, synthetic inputs shared by the calibration tests and benchmark. */
final class RatesCurveCalibrationFixture {

  private static final LocalDate DATE = LocalDate.of(2024, 1, 16);
  private static final ImmutableList<Currency> CURRENCIES =
      ImmutableList.of(Currency.USD, Currency.EUR, Currency.GBP, Currency.JPY, Currency.CHF, Currency.CAD);

  private final RatesCurveGroupDefinition group;
  private final ImmutableMarketData marketData;
  private final ReferenceData refData = ReferenceData.standard();
  private final ImmutableList<QuoteId> quoteIds;
  private final ImmutableList<Currency> currencies;

  static RatesCurveCalibrationFixture of(int currencyCount, int nodesPerCurrency, boolean connected) {
    ImmutableList<Currency> currencies = CURRENCIES.subList(0, currencyCount);
    RatesCurveGroupDefinitionBuilder group = RatesCurveGroupDefinition.builder().name(CurveGroupName.of("Synthetic"));
    ImmutableMarketDataBuilder marketData = ImmutableMarketData.builder(DATE);
    ImmutableList.Builder<QuoteId> quotes = ImmutableList.builder();
    for (int ccyIndex = 0; ccyIndex < currencyCount; ccyIndex++) {
      Currency currency = currencies.get(ccyIndex);
      boolean useFx = connected && ccyIndex > 0;
      CurrencyPair pair = useFx ? CurrencyPair.of(currencies.get(0), currency).toConventional() : null;
      double spot = 1d + 0.125d * ccyIndex;
      FxSwapConvention fxConvention = null;
      if (useFx) {
        marketData.addValue(FxRateId.of(pair), FxRate.of(pair, spot));
        fxConvention = ImmutableFxSwapConvention.of(pair, DaysAdjustment.NONE, BusinessDayAdjustment.NONE);
      }
      List<CurveNode> nodes = new ArrayList<>();
      for (int nodeIndex = 1; nodeIndex <= nodesPerCurrency; nodeIndex++) {
        Period period = Period.ofMonths(nodeIndex);
        double tenor = ACT_365F.yearFraction(DATE, DATE.plus(period));
        QuoteId quote = QuoteId.of(StandardId.of("Synthetic", currency + "-" + nodeIndex));
        quotes.add(quote);
        if (useFx) {
          double rateDifference = rate(currencies.indexOf(pair.getCounter())) - rate(currencies.indexOf(pair.getBase()));
          marketData.addValue(quote, spot * Math.expm1(rateDifference * tenor));
          nodes.add(FxSwapCurveNode.of(FxSwapTemplate.of(period, fxConvention), quote));
        } else {
          marketData.addValue(quote, Math.expm1(rate(ccyIndex) * tenor) / tenor);
          nodes.add(TermDepositCurveNode.of(TermDepositTemplate.of(period, depositConvention(currency)), quote));
        }
      }
      group.addDiscountCurve(curve(currency.toString(), nodes), currency);
    }
    return new RatesCurveCalibrationFixture(group.build(), marketData.build(), quotes.build(), currencies);
  }

  RatesCurveCalibrationFixture(
      RatesCurveGroupDefinition group,
      ImmutableMarketData marketData,
      ImmutableList<QuoteId> quoteIds,
      ImmutableList<Currency> currencies) {

    this.group = group;
    this.marketData = marketData;
    this.quoteIds = quoteIds;
    this.currencies = currencies;
  }

  RatesCurveGroupDefinition group() {
    return group;
  }

  ImmutableMarketData marketData() {
    return marketData;
  }

  ReferenceData refData() {
    return refData;
  }

  ImmutableList<QuoteId> quoteIds() {
    return quoteIds;
  }

  ImmutableList<Currency> currencies() {
    return currencies;
  }

  ImmutableRatesProvider baseline(RatesCurveCalibrator calibrator) {
    return baseline(calibrator, marketData);
  }

  ImmutableRatesProvider baseline(RatesCurveCalibrator calibrator, ImmutableMarketData data) {
    Map<Index, LocalDateDoubleTimeSeries> timeSeries = data.getTimeSeriesIds().stream()
        .flatMap(filtering(IndexQuoteId.class))
        .collect(toImmutableMap(id -> id.getIndex(), id -> data.getTimeSeries(id)));
    ImmutableRatesProvider knownData = ImmutableRatesProvider.builder(data.getValuationDate())
        .fxRateProvider(MarketDataFxRateProvider.of(data))
        .timeSeries(timeSeries)
        .build();
    return calibrator.calibrate(ImmutableList.of(group), knownData, data, refData);
  }

  ImmutableRatesProvider candidate(RatesCurveCalibrator calibrator) {
    return calibrator.calibrate(group, marketData, refData);
  }

  ImmutableMarketData bumped(QuoteId quote, double shift) {
    return marketData.toBuilder().addValue(quote, marketData.getValue(quote) + shift).build();
  }

  ResolvedTermDeposit probe(Currency currency) {
    return depositConvention(currency)
        .createTrade(DATE, Period.ofMonths(1).plusDays(12), BuySell.BUY, 1_000_000d, 0.032d, refData)
        .getProduct().resolve(refData);
  }

  static InterpolatedNodalCurveDefinition curve(String name, List<CurveNode> nodes) {
    return InterpolatedNodalCurveDefinition.builder()
        .name(CurveName.of(name))
        .xValueType(ValueType.YEAR_FRACTION)
        .yValueType(ValueType.ZERO_RATE)
        .dayCount(ACT_365F)
        .interpolator(CurveInterpolators.LINEAR)
        .extrapolatorLeft(CurveExtrapolators.FLAT)
        .extrapolatorRight(CurveExtrapolators.FLAT)
        .nodes(nodes)
        .build();
  }

  private static TermDepositConvention depositConvention(Currency currency) {
    return ImmutableTermDepositConvention.of(
        "Synthetic-" + currency, currency, BusinessDayAdjustment.NONE, ACT_365F, DaysAdjustment.NONE);
  }

  static double rate(int currencyIndex) {
    return 0.01d + 0.002d * currencyIndex;
  }

}
