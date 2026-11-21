/*
 * Copyright (C) 2015 - present by OpenGamma Inc. and the OpenGamma group of companies
 *
 * Please see distribution for license.
 */
package com.opengamma.strata.pricer.curve;

import static com.opengamma.strata.collect.Guavate.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.opengamma.strata.basics.StandardId;
import com.opengamma.strata.basics.currency.Currency;
import com.opengamma.strata.basics.currency.CurrencyAmount;
import com.opengamma.strata.basics.date.Tenor;
import com.opengamma.strata.basics.index.FxIndexObservation;
import com.opengamma.strata.basics.index.FxIndices;
import com.opengamma.strata.basics.index.IborIndex;
import com.opengamma.strata.basics.index.ImmutableIborIndex;
import com.opengamma.strata.collect.array.DoubleArray;
import com.opengamma.strata.data.ImmutableMarketDataBuilder;
import com.opengamma.strata.market.curve.Curve;
import com.opengamma.strata.market.curve.CurveDefinition;
import com.opengamma.strata.market.curve.CurveInfoType;
import com.opengamma.strata.market.curve.CurveNode;
import com.opengamma.strata.market.curve.CurveParameterSize;
import com.opengamma.strata.market.curve.InterpolatedNodalCurveDefinition;
import com.opengamma.strata.market.curve.JacobianCalibrationMatrix;
import com.opengamma.strata.market.curve.RatesCurveGroupDefinition;
import com.opengamma.strata.market.curve.RatesCurveGroupDefinitionBuilder;
import com.opengamma.strata.market.curve.interpolator.BoundCurveInterpolator;
import com.opengamma.strata.market.curve.interpolator.CurveInterpolator;
import com.opengamma.strata.market.curve.interpolator.CurveInterpolators;
import com.opengamma.strata.market.curve.node.FixedIborSwapCurveNode;
import com.opengamma.strata.market.curve.node.FixedOvernightSwapCurveNode;
import com.opengamma.strata.market.observable.QuoteId;
import com.opengamma.strata.market.param.CurrencyParameterSensitivities;
import com.opengamma.strata.market.param.CurrencyParameterSensitivity;
import com.opengamma.strata.market.sensitivity.PointSensitivities;
import com.opengamma.strata.math.rootfind.NewtonVectorRootFinder;
import com.opengamma.strata.pricer.deposit.DiscountingTermDepositProductPricer;
import com.opengamma.strata.pricer.rate.ImmutableRatesProvider;
import com.opengamma.strata.pricer.sensitivity.MarketQuoteSensitivityCalculator;
import com.opengamma.strata.pricer.swap.DiscountingSwapProductPricer;
import com.opengamma.strata.product.ResolvedTrade;
import com.opengamma.strata.product.common.BuySell;
import com.opengamma.strata.product.deposit.ResolvedTermDeposit;
import com.opengamma.strata.product.swap.FxResetNotionalExchange;
import com.opengamma.strata.product.swap.ResolvedSwap;
import com.opengamma.strata.product.swap.ResolvedSwapLeg;
import com.opengamma.strata.product.swap.ResolvedSwapTrade;
import com.opengamma.strata.product.swap.type.FixedIborSwapConvention;
import com.opengamma.strata.product.swap.type.FixedIborSwapConventions;
import com.opengamma.strata.product.swap.type.FixedIborSwapTemplate;
import com.opengamma.strata.product.swap.type.FixedOvernightSwapConvention;
import com.opengamma.strata.product.swap.type.FixedOvernightSwapConventions;
import com.opengamma.strata.product.swap.type.FixedOvernightSwapTemplate;
import com.opengamma.strata.product.swap.type.ImmutableFixedIborSwapConvention;

/** Tests graph-block calibration against the original solve and quote bumps. */
public class RatesCurveCalibratorBlocksTest {

  private static final RatesCurveCalibrator CALIBRATOR = RatesCurveCalibrator.of(1e-13, 1e-13, 1000);
  private static final DiscountingTermDepositProductPricer DEPOSIT_PRICER = DiscountingTermDepositProductPricer.DEFAULT;
  private static final DiscountingSwapProductPricer SWAP_PRICER = DiscountingSwapProductPricer.DEFAULT;
  private static final FixedIborSwapConvention USD_CONVENTION = FixedIborSwapConventions.USD_FIXED_6M_LIBOR_3M;
  private static final double BUMP = 1e-5;
  private static final double ONE_BP = 1e-4;

  @Test
  public void independentCurrencies() {
    RatesCurveCalibrationFixture fixture = RatesCurveCalibrationFixture.of(3, 4, false);
    assertThat(blocks(fixture)).containsExactly(ImmutableList.of(0), ImmutableList.of(1), ImmutableList.of(2));
    checkCalibration("independent", fixture, null);
  }

  @Test
  public void currenciesConnectedByFxSwaps() {
    RatesCurveCalibrationFixture fixture = RatesCurveCalibrationFixture.of(3, 4, true);
    assertThat(blocks(fixture)).isEmpty();
    assertThat(fixture.candidate(CALIBRATOR)).isEqualTo(fixture.baseline(CALIBRATOR));
    checkCalibration("FX-connected", fixture, null);
  }

  @Test
  public void interleavedDiscountAndForwardCurves() {
    RatesCurveCalibrationFixture fixture = interleavedFixture();
    ResolvedSwap probe = FixedIborSwapTemplate.of(Period.ofMonths(2), Tenor.TENOR_2Y, USD_CONVENTION)
        .createTrade(fixture.marketData().getValuationDate(), BuySell.BUY, 1_000_000d, 0.032d, fixture.refData())
        .resolve(fixture.refData())
        .getProduct();
    assertThat(blocks(fixture)).containsExactly(ImmutableList.of(0, 2), ImmutableList.of(1, 3));
    checkCalibration("interleaved", fixture, probe);
  }

  @Test
  public void fxResetPaymentEventDisablesSplitting() {
    RatesCurveCalibrationFixture fixture = interleavedFixture();
    List<ResolvedTrade> trades = new ArrayList<>(fixture.group().resolvedTrades(fixture.marketData(), fixture.refData()));
    int firstUsdSwap = fixture.group().getCurveDefinitions().subList(0, 2).stream()
        .mapToInt(CurveDefinition::getParameterCount)
        .sum();
    ResolvedSwapTrade swap = (ResolvedSwapTrade) trades.get(firstUsdSwap);
    LocalDate fixing = fixture.marketData().getValuationDate().plusMonths(1);
    FxIndexObservation observation = FxIndexObservation.of(FxIndices.EUR_USD_WM, fixing, fixture.refData());
    FxResetNotionalExchange event = FxResetNotionalExchange.of(
        CurrencyAmount.of(Currency.EUR, 1d), observation.getMaturityDate(), observation);
    List<ResolvedSwapLeg> legs = new ArrayList<>(swap.getProduct().getLegs());
    ResolvedSwapLeg leg = legs.get(0);
    legs.set(0, leg.toBuilder().paymentEvents(event).build());
    ResolvedSwap withFxReset = ResolvedSwap.builder().legs(legs).build();
    assertThat(withFxReset.allPaymentCurrencies()).containsExactly(Currency.USD);
    assertThat(withFxReset.allIndices()).isEqualTo(swap.getProduct().allIndices());
    trades.set(firstUsdSwap, swap.toBuilder().product(withFxReset).build());
    assertThat(RatesCurveCalibrationBlocks.find(fixture.group(), trades)).isEmpty();
  }

  @Test
  public void forwardIndexKeyOwnedByAnotherCurrencyDisablesSplitting() {
    RatesCurveCalibrationFixture fixture = interleavedFixture();
    List<CurveDefinition> definitions = fixture.group().getCurveDefinitions();
    FixedIborSwapCurveNode usdNode = (FixedIborSwapCurveNode) definitions.get(2).getNodes().get(0);
    IborIndex eurIndex = FixedIborSwapConventions.EUR_FIXED_1Y_EURIBOR_3M.getFloatingLeg().getIndex();
    ImmutableIborIndex usdIndex = (ImmutableIborIndex) USD_CONVENTION.getFloatingLeg().getIndex();
    IborIndex usdIndexWithEurKey = usdIndex.toBuilder().name(eurIndex.getName()).build();
    FixedIborSwapConvention convention = ImmutableFixedIborSwapConvention.of(
        USD_CONVENTION.getName(),
        USD_CONVENTION.getFixedLeg(),
        USD_CONVENTION.getFloatingLeg().toBuilder().index(usdIndexWithEurKey).build(),
        USD_CONVENTION.getSpotDateOffset());
    FixedIborSwapCurveNode node = usdNode.toBuilder()
        .template(usdNode.getTemplate().toBuilder().convention(convention).build())
        .build();
    ResolvedSwapTrade trade = node.resolvedTrade(1d, fixture.marketData(), fixture.refData());
    List<ResolvedTrade> trades = new ArrayList<>(fixture.group().resolvedTrades(fixture.marketData(), fixture.refData()));
    int firstUsdSwap = definitions.get(0).getParameterCount() + definitions.get(1).getParameterCount();
    trades.set(firstUsdSwap, trade);

    assertThat(usdIndexWithEurKey.getCurrency()).isEqualTo(Currency.USD);
    assertThat(trade.getProduct().allPaymentCurrencies()).containsExactly(Currency.USD);
    assertThat(trade.getProduct().allIndices()).containsExactly(eurIndex);
    assertThat(RatesCurveCalibrationBlocks.find(fixture.group(), trades)).isEmpty();
  }

  private static RatesCurveCalibrationFixture interleavedFixture() {
    RatesCurveCalibrationFixture fixture = RatesCurveCalibrationFixture.of(2, 3, false);
    RatesCurveGroupDefinitionBuilder group = fixture.group().toBuilder().computePvSensitivityToMarketQuote(true);
    ImmutableMarketDataBuilder data = fixture.marketData().toBuilder();
    ImmutableList.Builder<QuoteId> quoteIds = ImmutableList.<QuoteId>builder().addAll(fixture.quoteIds());
    FixedIborSwapConvention[] conventions = {
        USD_CONVENTION, FixedIborSwapConventions.EUR_FIXED_1Y_EURIBOR_3M};
    for (FixedIborSwapConvention convention : conventions) {
      List<CurveNode> nodes = new ArrayList<>();
      for (int year = 1; year <= 2; year++) {
        QuoteId quote = QuoteId.of(StandardId.of("Synthetic", convention.getName() + "-" + year));
        data.addValue(quote, 0.02d + 0.005d * year);
        quoteIds.add(quote);
        nodes.add(FixedIborSwapCurveNode.of(FixedIborSwapTemplate.of(Tenor.ofYears(year), convention), quote));
      }
      group.addForwardCurve(
          RatesCurveCalibrationFixture.curve(convention.getName(), nodes), convention.getFloatingLeg().getIndex());
    }
    return new RatesCurveCalibrationFixture(group.build(), data.build(), quoteIds.build(), fixture.currencies());
  }

  @Test
  public void customNodeUsesOriginalSolver() {
    RatesCurveCalibrationFixture fixture = RatesCurveCalibrationFixture.of(3, 4, false);
    CurveDefinition original = fixture.group().getCurveDefinitions().get(0);
    List<CurveNode> nodes = new ArrayList<>(original.getNodes());
    nodes.set(0, mock(CurveNode.class, delegatesTo(nodes.get(0))));
    List<CurveDefinition> definitions = new ArrayList<>(fixture.group().getCurveDefinitions());
    definitions.set(0, RatesCurveCalibrationFixture.curve(original.getName().toString(), nodes));
    RatesCurveGroupDefinition group = fixture.group().withCurveDefinitions(definitions);
    RatesCurveCalibrationFixture custom =
        new RatesCurveCalibrationFixture(group, fixture.marketData(), fixture.quoteIds(), fixture.currencies());
    assertThat(blocks(custom)).isEmpty();
    assertThat(custom.candidate(CALIBRATOR)).isEqualTo(custom.baseline(CALIBRATOR));
  }

  @Test
  public void customInterpolatorUsesOriginalSolver() {
    RatesCurveCalibrationFixture fixture = RatesCurveCalibrationFixture.of(3, 4, false);
    List<CurveDefinition> definitions = new ArrayList<>(fixture.group().getCurveDefinitions());
    InterpolatedNodalCurveDefinition original = (InterpolatedNodalCurveDefinition) definitions.get(0);
    CurveInterpolator interpolator = new CurveInterpolator() {
      @Override
      public BoundCurveInterpolator bind(DoubleArray xValues, DoubleArray yValues) {
        return CurveInterpolators.LINEAR.bind(xValues, yValues);
      }

      @Override
      public String getName() {
        return CurveInterpolators.LINEAR.getName();
      }
    };
    definitions.set(0, original.toBuilder().interpolator(interpolator).build());
    RatesCurveGroupDefinition group = fixture.group().withCurveDefinitions(definitions);
    RatesCurveCalibrationFixture custom =
        new RatesCurveCalibrationFixture(group, fixture.marketData(), fixture.quoteIds(), fixture.currencies());

    assertThat(blocks(custom)).isEmpty();
    assertThat(custom.candidate(CALIBRATOR)).isEqualTo(custom.baseline(CALIBRATOR));
  }

  @Test
  public void discountCurveSharedByCurrenciesUsesOriginalSolver() {
    RatesCurveCalibrationFixture fixture = RatesCurveCalibrationFixture.of(3, 4, false);
    CurveDefinition usd = fixture.group().getCurveDefinitions().get(0);
    RatesCurveGroupDefinition group = fixture.group().toBuilder().addDiscountCurve(usd, Currency.USD, Currency.JPY).build();
    RatesCurveCalibrationFixture shared =
        new RatesCurveCalibrationFixture(group, fixture.marketData(), fixture.quoteIds(), fixture.currencies());
    assertThat(blocks(shared)).isEmpty();
    assertThat(shared.candidate(CALIBRATOR)).isEqualTo(shared.baseline(CALIBRATOR));
  }

  @Test
  public void suppliedRootFinderReceivesTheFullSystem() {
    RatesCurveCalibrationFixture fixture = RatesCurveCalibrationFixture.of(3, 4, false);
    NewtonVectorRootFinder customFinder =
        mock(NewtonVectorRootFinder.class, delegatesTo(NewtonVectorRootFinder.broyden(1e-13, 1e-13, 1000)));
    RatesCurveCalibrator customCalibrator =
        RatesCurveCalibrator.of(customFinder, CalibrationMeasures.PAR_SPREAD, CalibrationMeasures.PRESENT_VALUE);
    assertThat(fixture.candidate(customCalibrator)).isEqualTo(fixture.baseline(CALIBRATOR));
    verify(customFinder).findRoot(any(), any(), argThat(start -> start.size() == fixture.quoteIds().size()));
  }

  @Test
  public void jacobianCanBeDisabled() {
    RatesCurveCalibrationFixture fixture = RatesCurveCalibrationFixture.of(2, 4, false);
    RatesCurveCalibrationFixture noJacobian = new RatesCurveCalibrationFixture(
        fixture.group().toBuilder().computeJacobian(false).build(),
        fixture.marketData(),
        fixture.quoteIds(),
        fixture.currencies());
    ImmutableRatesProvider candidate = noJacobian.candidate(CALIBRATOR);
    ImmutableRatesProvider baseline = noJacobian.baseline(CALIBRATOR);
    for (Currency currency : fixture.currencies()) {
      Curve curve = candidate.getDiscountCurves().get(currency);
      assertThat(curve.getMetadata().findInfo(CurveInfoType.JACOBIAN)).isEmpty();
      for (int i = 0; i < curve.getParameterCount(); i++) {
        assertThat(curve.getParameter(i)).isCloseTo(baseline.getDiscountCurves().get(currency).getParameter(i), offset(2e-11));
      }
    }
  }

  @Test
  public void independentFixedOvernightSwaps() {
    RatesCurveCalibrationFixture deposits = RatesCurveCalibrationFixture.of(2, 3, false);
    RatesCurveGroupDefinitionBuilder group = deposits.group().toBuilder().computePvSensitivityToMarketQuote(true);
    ImmutableMarketDataBuilder data = deposits.marketData().toBuilder();
    ImmutableList.Builder<QuoteId> quoteIds = ImmutableList.<QuoteId>builder().addAll(deposits.quoteIds());
    FixedOvernightSwapConvention usdConvention = FixedOvernightSwapConventions.USD_FIXED_1Y_SOFR_OIS;
    FixedOvernightSwapConvention[] conventions = {usdConvention, FixedOvernightSwapConventions.EUR_FIXED_1Y_ESTR_OIS};
    for (FixedOvernightSwapConvention convention : conventions) {
      List<CurveNode> nodes = new ArrayList<>();
      for (int year = 1; year <= 2; year++) {
        QuoteId quote = QuoteId.of(StandardId.of("Synthetic", convention.getName() + "-" + year));
        data.addValue(quote, 0.02d + 0.005d * year);
        quoteIds.add(quote);
        nodes.add(FixedOvernightSwapCurveNode.of(FixedOvernightSwapTemplate.of(Tenor.ofYears(year), convention), quote));
      }
      group.addForwardCurve(
          RatesCurveCalibrationFixture.curve(convention.getName(), nodes), convention.getFloatingLeg().getIndex());
    }
    RatesCurveCalibrationFixture fixture =
        new RatesCurveCalibrationFixture(group.build(), data.build(), quoteIds.build(), deposits.currencies());
    ResolvedSwap probe = FixedOvernightSwapTemplate.of(Period.ofMonths(2), Tenor.TENOR_2Y, usdConvention)
        .createTrade(fixture.marketData().getValuationDate(), BuySell.BUY, 1_000_000d, 0.032d, fixture.refData())
        .resolve(fixture.refData())
        .getProduct();

    assertThat(blocks(fixture)).containsExactly(ImmutableList.of(0, 2), ImmutableList.of(1, 3));
    checkCalibration("independent OIS", fixture, probe);
  }

  private static void checkCalibration(String scenario, RatesCurveCalibrationFixture fixture, ResolvedSwap swapProbe) {
    ImmutableRatesProvider candidate = fixture.candidate(CALIBRATOR);
    ImmutableRatesProvider baseline = fixture.baseline(CALIBRATOR);
    List<CurveDefinition> definitions = fixture.group().getCurveDefinitions();
    List<CurveParameterSize> globalOrder =
        definitions.stream().map(CurveDefinition::toCurveParameterSize).collect(toImmutableList());
    List<ResolvedTrade> instruments = fixture.group().resolvedTrades(fixture.marketData(), fixture.refData());
    double maxParSpread = 0d;
    double maxPvDifference = 0d;
    double maxAnalyticDepositPvError = 0d;
    double maxJacobianDifference = 0d;
    double maxPv01Difference = 0d;
    for (ResolvedTrade trade : instruments) {
      double parSpread = CALIBRATOR.getMeasures().value(trade, candidate);
      assertThat(parSpread).isCloseTo(0d, offset(2e-11));
      maxParSpread = Math.max(maxParSpread, Math.abs(parSpread));
    }

    List<ResolvedTermDeposit> probes = new ArrayList<>();
    List<CurrencyParameterSensitivities> risks = new ArrayList<>();
    for (Currency currency : fixture.currencies()) {
      ResolvedTermDeposit probe = fixture.probe(currency);
      probes.add(probe);
      double pv = DEPOSIT_PRICER.presentValue(probe, candidate).getAmount();
      double original = DEPOSIT_PRICER.presentValue(probe, baseline).getAmount();
      assertThat(pv).isCloseTo(original, offset(2e-5));
      maxPvDifference = Math.max(maxPvDifference, Math.abs(pv - original));
      double zeroRate = RatesCurveCalibrationFixture.rate(fixture.currencies().indexOf(currency));
      double discount = Math.exp(-zeroRate * probe.getYearFraction());
      double exactPv = (probe.getNotional() + probe.getInterest()) * discount - probe.getNotional();
      assertThat(pv).isCloseTo(exactPv, offset(2e-5));
      maxAnalyticDepositPvError = Math.max(maxAnalyticDepositPvError, Math.abs(pv - exactPv));
      risks.add(marketQuoteRisk(candidate, DEPOSIT_PRICER.presentValueSensitivity(probe, candidate)));
    }
    CurrencyParameterSensitivities swapRisk = CurrencyParameterSensitivities.empty();
    if (swapProbe != null) {
      double pv = SWAP_PRICER.presentValue(swapProbe, candidate).getAmount(Currency.USD).getAmount();
      double original = SWAP_PRICER.presentValue(swapProbe, baseline).getAmount(Currency.USD).getAmount();
      assertThat(pv).isCloseTo(original, offset(2e-5));
      maxPvDifference = Math.max(maxPvDifference, Math.abs(pv - original));
      swapRisk = marketQuoteRisk(candidate, SWAP_PRICER.presentValueSensitivity(swapProbe, candidate).build());
    }

    int quoteIndex = 0;
    for (CurveParameterSize quoteCurve : globalOrder) {
      for (int quoteParameter = 0; quoteParameter < quoteCurve.getParameterCount(); quoteParameter++) {
        QuoteId quote = fixture.quoteIds().get(quoteIndex);
        ImmutableRatesProvider up = fixture.baseline(CALIBRATOR, fixture.bumped(quote, BUMP));
        ImmutableRatesProvider down = fixture.baseline(CALIBRATOR, fixture.bumped(quote, -BUMP));
        for (CurveDefinition definition : definitions) {
          Curve curve = candidate.findData(definition.getName()).get();
          Curve oldCurve = baseline.findData(definition.getName()).get();
          Curve upCurve = up.findData(definition.getName()).get();
          Curve downCurve = down.findData(definition.getName()).get();
          JacobianCalibrationMatrix jacobian = curve.getMetadata().getInfo(CurveInfoType.JACOBIAN);
          JacobianCalibrationMatrix oldJacobian = oldCurve.getMetadata().getInfo(CurveInfoType.JACOBIAN);
          assertThat(jacobian.getOrder()).containsExactlyElementsOf(globalOrder);
          if (fixture.group().isComputePvSensitivityToMarketQuote()) {
            assertThat(curve.getMetadata().getInfo(CurveInfoType.PV_SENSITIVITY_TO_MARKET_QUOTE)
                .equalWithTolerance(oldCurve.getMetadata().getInfo(CurveInfoType.PV_SENSITIVITY_TO_MARKET_QUOTE), 2e-11))
                .isTrue();
          }
          for (int parameter = 0; parameter < curve.getParameterCount(); parameter++) {
            double fd = (upCurve.getParameter(parameter) - downCurve.getParameter(parameter)) / (2d * BUMP);
            double value = jacobian.getJacobianMatrix().get(parameter, quoteIndex);
            assertThat(value).as("Jacobian for %s parameter %s, quote %s", curve.getName(), parameter, quote)
                .isCloseTo(fd, offset(2e-7));
            assertThat(value).isCloseTo(oldJacobian.getJacobianMatrix().get(parameter, quoteIndex), offset(2e-10));
            maxJacobianDifference = Math.max(maxJacobianDifference, Math.abs(value - fd));
          }
        }
        for (int i = 0; i < probes.size(); i++) {
          ResolvedTermDeposit probe = probes.get(i);
          double fd = (DEPOSIT_PRICER.presentValue(probe, up).getAmount() - DEPOSIT_PRICER.presentValue(probe, down).getAmount())
              * ONE_BP / (2d * BUMP);
          double value = pv01(risks.get(i), quoteCurve, quoteParameter, probe.getCurrency());
          assertThat(value).as("Deposit PV01 for %s, quote %s", probe.getCurrency(), quote).isCloseTo(fd, offset(2e-5));
          maxPv01Difference = Math.max(maxPv01Difference, Math.abs(value - fd));
        }
        if (swapProbe != null) {
          double pvUp = SWAP_PRICER.presentValue(swapProbe, up).getAmount(Currency.USD).getAmount();
          double pvDown = SWAP_PRICER.presentValue(swapProbe, down).getAmount(Currency.USD).getAmount();
          double fd = (pvUp - pvDown) * ONE_BP / (2d * BUMP);
          double value = pv01(swapRisk, quoteCurve, quoteParameter, Currency.USD);
          assertThat(value).as("Swap PV01 for quote %s", quote).isCloseTo(fd, offset(2e-5));
          maxPv01Difference = Math.max(maxPv01Difference, Math.abs(value - fd));
        }
        quoteIndex++;
      }
    }
    System.out.printf(Locale.ROOT, "%s quotes=%d maxParSpread=%.3g maxAbsPvDelta=%.3g " +
        "maxAbsAnalyticDepositPvError=%.3g maxAbsJacobianFiniteDifferenceError=%.3g maxAbsPv01FiniteDifferenceError=%.3g%n",
        scenario, quoteIndex, maxParSpread, maxPvDifference, maxAnalyticDepositPvError, maxJacobianDifference,
        maxPv01Difference);
  }

  private static List<List<Integer>> blocks(RatesCurveCalibrationFixture fixture) {
    return RatesCurveCalibrationBlocks.find(
        fixture.group(), fixture.group().resolvedTrades(fixture.marketData(), fixture.refData()));
  }

  private static CurrencyParameterSensitivities marketQuoteRisk(
      ImmutableRatesProvider provider,
      PointSensitivities points) {

    return MarketQuoteSensitivityCalculator.DEFAULT.sensitivity(provider.parameterSensitivity(points), provider);
  }

  private static double pv01(
      CurrencyParameterSensitivities risk,
      CurveParameterSize quoteCurve,
      int parameter,
      Currency currency) {

    return risk.findSensitivity(quoteCurve.getName(), currency)
        .map(CurrencyParameterSensitivity::getSensitivity)
        .map(vector -> vector.get(parameter) * ONE_BP)
        .orElse(0d);
  }

}
