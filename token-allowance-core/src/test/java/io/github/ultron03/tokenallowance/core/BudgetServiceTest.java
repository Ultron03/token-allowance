package io.github.ultron03.tokenallowance.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

class BudgetServiceTest {

	private static final Instant START = Instant.parse("2026-10-22T10:00:00Z");

	private static final String MODEL = "small-model";

	private static final ModelPrice PRICE = new ModelPrice(Money.ofDollars("1.00"), Money.ofDollars("2.00"), null,
			null);

	private static final Caller ALICE = new Caller(Map.of(Scope.USER, "alice"), null);

	private static final Caller ALICE_ON_PRO = new Caller(Map.of(Scope.USER, "alice"), "pro");

	private static final Limit USER_DAILY = new Limit(Scope.USER, Window.DAILY, 2000L, Money.ofDollars("1.00"));

	private static final Limit PRO_USER_DAILY = new Limit(Scope.USER, Window.DAILY, 20_000L, Money.ofDollars("10.00"));

	private static final Limit GLOBAL_MONTHLY = new Limit(Scope.GLOBAL, Window.MONTHLY, 10_000L, null);

	private static final BudgetKey ALICE_TODAY = BudgetKey.of(Scope.USER, "alice", Window.DAILY, START,
			ZoneOffset.UTC);

	/** 1,500 tokens: 1,000 input at $1/MTok plus 500 output at $2/MTok, so $0.002. */
	private static final TokenUsage ESTIMATE = new TokenUsage(1000, 500, 0, 0);

	private static final Amount ESTIMATE_AMOUNT = new Amount(1500, Money.ofDollars("0.002"));

	private static final int THREADS = 100;

	/** Fewer users than threads, so each user sends several parallel requests. */
	private static final int USERS = 20;

	private final MutableClock clock = new MutableClock(START);

	private final InMemoryUsageLedger ledger = new InMemoryUsageLedger(this.clock, ZoneOffset.UTC);

	private final RecordingListener listener = new RecordingListener();

	@Test
	void admitsWhenNothingIsSpent() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);

		Decision decision = service.admit(ALICE);

		assertThat(decision).isEqualTo(new Decision.Allowed());
	}

	@Test
	void deniesAdmissionWhenCommittedSpendReachedLimit() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);
		service.settle(ALICE, reservationOf(service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE)), MODEL,
				new TokenUsage(1500, 500, 0, 0));

		Decision decision = service.admit(ALICE);

		assertThat(decision).isEqualTo(new Decision.Denied("user daily budget exceeded", Duration.ofHours(14)));
	}

	@Test
	void admissionIgnoresCallsStillInFlight() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);
		service.reserve(ALICE, RequestLimit.NONE, MODEL, new TokenUsage(1500, 500, 0, 0));

		Decision decision = service.admit(ALICE);

		assertThat(decision).isEqualTo(new Decision.Allowed());
	}

	@Test
	void admitsInObserveModeEvenWhenLimitReached() {
		BudgetService service = service(EnforcementMode.OBSERVE, USER_DAILY);
		service.settle(ALICE, reservationOf(service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE)), MODEL,
				new TokenUsage(1500, 500, 0, 0));

		Decision decision = service.admit(ALICE);

		assertThat(decision).isEqualTo(new Decision.Allowed());
	}

	@Test
	void reservesPricedEstimateWhenUnderLimit() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);

		Decision decision = service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE);

		assertThat(decision).isInstanceOf(Decision.Reserved.class);
		assertThat(this.ledger.current(ALICE_TODAY).reserved()).isEqualTo(ESTIMATE_AMOUNT);
	}

	@Test
	void deniesReservationOverLimitWithRetryAfterUntilWindowEnd() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);
		service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE);

		Decision decision = service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE);

		assertThat(decision).isEqualTo(new Decision.Denied("user daily budget exceeded", Duration.ofHours(14)));
	}

	@Test
	void reservesInObserveModeEvenOverLimitSoSpendIsRecorded() {
		BudgetService service = service(EnforcementMode.OBSERVE, USER_DAILY);
		service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE);

		Decision decision = service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE);

		assertThat(decision).isInstanceOf(Decision.Reserved.class);
		assertThat(this.ledger.current(ALICE_TODAY).reserved().tokens()).isEqualTo(3000);
	}

	@Test
	void appliesGlobalLimitWithoutSubjectId() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY, GLOBAL_MONTHLY);
		BudgetKey globalKey = BudgetKey.of(Scope.GLOBAL, BudgetKey.GLOBAL_SUBJECT, Window.MONTHLY, START,
				ZoneOffset.UTC);

		service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE);

		assertThat(this.ledger.current(globalKey).reserved()).isEqualTo(ESTIMATE_AMOUNT);
	}

	@Test
	void skipsLimitWhoseScopeHasNoSubjectId() {
		Limit tenantDaily = new Limit(Scope.TENANT, Window.DAILY, 1L, null);
		BudgetService service = service(EnforcementMode.ENFORCE, tenantDaily);

		Decision decision = service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE);

		assertThat(reservationOf(decision).keys()).isEmpty();
	}

	@Test
	void reservesNoCostForUncostedModel() {
		PriceCatalog uncosted = new ConfiguredPriceCatalog(Map.of(), UnknownModelPolicy.ALLOW_UNCOSTED, null);
		BudgetService service = new BudgetService(this.ledger, uncosted, List.of(USER_DAILY), EnforcementMode.ENFORCE,
				this.clock, ZoneOffset.UTC);

		service.reserve(ALICE, RequestLimit.NONE, "other-model", ESTIMATE);

		assertThat(this.ledger.current(ALICE_TODAY).reserved()).isEqualTo(new Amount(1500, Money.ZERO));
	}

	@Test
	void throwsForUnknownModelWhenPolicyRejects() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);

		assertThatThrownBy(() -> service.reserve(ALICE, RequestLimit.NONE, "other-model", ESTIMATE))
				.isInstanceOf(UnknownModelException.class);
	}

	@Test
	void settleChargesActualUsage() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);
		Reservation reservation = reservationOf(service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE));

		service.settle(ALICE, reservation, MODEL, new TokenUsage(400, 100, 0, 0));

		assertThat(this.ledger.current(ALICE_TODAY))
				.isEqualTo(new Spend(new Amount(500, Money.ofDollars("0.0006")), Amount.ZERO));
	}

	@Test
	void settleChargesEstimateWhenUsageIsMissing() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);
		Reservation reservation = reservationOf(service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE));

		service.settle(ALICE, reservation, MODEL, TokenUsage.ZERO);

		assertThat(this.ledger.current(ALICE_TODAY)).isEqualTo(new Spend(ESTIMATE_AMOUNT, Amount.ZERO));
	}

	@Test
	void releaseDropsReservationWithoutCharging() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);
		Reservation reservation = reservationOf(service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE));

		service.release(reservation, MODEL);

		assertThat(this.ledger.current(ALICE_TODAY)).isEqualTo(Spend.ZERO);
	}

	@Test
	void budgetResetsInNextWindow() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);
		service.settle(ALICE, reservationOf(service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE)), MODEL,
				new TokenUsage(1500, 500, 0, 0));
		this.clock.advance(Duration.ofDays(1));

		Decision decision = service.admit(ALICE);

		assertThat(decision).isEqualTo(new Decision.Allowed());
	}

	@Test
	void chargesCallToTheWindowItWasReservedIn() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);
		this.clock.advance(Duration.ofHours(13).plusMinutes(59));
		Reservation reservation = reservationOf(service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE));
		this.clock.advance(Duration.ofMinutes(2));

		service.settle(ALICE, reservation, MODEL, new TokenUsage(400, 100, 0, 0));

		BudgetKey aliceTomorrow = BudgetKey.of(Scope.USER, "alice", Window.DAILY, this.clock.instant(),
				ZoneOffset.UTC);
		assertThat(this.ledger.current(ALICE_TODAY).committed().tokens()).isEqualTo(500);
		assertThat(this.ledger.current(aliceTomorrow)).isEqualTo(Spend.ZERO);
	}

	@Test
	void retryAfterFollowsConfiguredZone() {
		ZoneId kolkata = ZoneId.of("Asia/Kolkata");
		InMemoryUsageLedger kolkataLedger = new InMemoryUsageLedger(this.clock, kolkata);
		BudgetService service = new BudgetService(kolkataLedger, rejectingCatalog(), List.of(USER_DAILY),
				EnforcementMode.ENFORCE, this.clock, kolkata);
		service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE);

		// 10:00 UTC is 15:30 in India; the day there ends at 18:30 UTC
		Decision decision = service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE);

		assertThat(decision).isEqualTo(
				new Decision.Denied("user daily budget exceeded", Duration.ofHours(8).plusMinutes(30)));
	}

	@Test
	void retryAfterForMonthlyLimitIsUntilFirstOfNextMonth() {
		BudgetService service = service(EnforcementMode.ENFORCE, GLOBAL_MONTHLY);

		Decision decision = service.reserve(ALICE, RequestLimit.NONE, MODEL, new TokenUsage(10_001, 0, 0, 0));

		Duration untilNovember = Duration.between(START, Instant.parse("2026-11-01T00:00:00Z"));
		assertThat(decision).isEqualTo(new Decision.Denied("global monthly budget exceeded", untilNovember));
	}

	@Test
	void reportsTheFirstConfiguredLimitThatIsExceeded() {
		TokenUsage overBoth = new TokenUsage(20_000, 0, 0, 0);

		Decision userFirst = service(EnforcementMode.ENFORCE, USER_DAILY, GLOBAL_MONTHLY).reserve(ALICE,
				RequestLimit.NONE, MODEL, overBoth);
		Decision globalFirst = service(EnforcementMode.ENFORCE, GLOBAL_MONTHLY, USER_DAILY).reserve(ALICE,
				RequestLimit.NONE, MODEL, overBoth);

		assertThat(((Decision.Denied) userFirst).reason()).isEqualTo("user daily budget exceeded");
		assertThat(((Decision.Denied) globalFirst).reason()).isEqualTo("global monthly budget exceeded");
	}

	@Test
	void deniedReasonDoesNotNameTheSubject() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);

		Decision decision = service.reserve(ALICE, RequestLimit.NONE, MODEL, new TokenUsage(5000, 0, 0, 0));

		assertThat(((Decision.Denied) decision).reason()).doesNotContain("alice");
	}

	@Test
	void deniesWhenPricedEstimateExceedsCostCap() {
		Limit tenthOfACent = new Limit(Scope.USER, Window.DAILY, null, Money.ofDollars("0.001"));
		BudgetService service = service(EnforcementMode.ENFORCE, tenthOfACent);

		Decision decision = service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE);

		assertThat(decision).isInstanceOf(Decision.Denied.class);
	}

	@Test
	void uncostedModelStillCountsTokensAgainstTokenCap() {
		PriceCatalog uncosted = new ConfiguredPriceCatalog(Map.of(), UnknownModelPolicy.ALLOW_UNCOSTED, null);
		BudgetService service = new BudgetService(this.ledger, uncosted, List.of(USER_DAILY), EnforcementMode.ENFORCE,
				this.clock, ZoneOffset.UTC);
		service.reserve(ALICE, RequestLimit.NONE, "other-model", ESTIMATE);

		Decision decision = service.reserve(ALICE, RequestLimit.NONE, "other-model", ESTIMATE);

		assertThat(decision).isInstanceOf(Decision.Denied.class);
	}

	@Test
	void settleChargesDefaultPriceForUnknownModel() {
		PriceCatalog withDefault = new ConfiguredPriceCatalog(Map.of(), UnknownModelPolicy.DEFAULT_PRICE, PRICE);
		BudgetService service = new BudgetService(this.ledger, withDefault, List.of(USER_DAILY),
				EnforcementMode.ENFORCE, this.clock, ZoneOffset.UTC);
		Reservation reservation = reservationOf(service.reserve(ALICE, RequestLimit.NONE, "other-model", ESTIMATE));

		service.settle(ALICE, reservation, "other-model", new TokenUsage(400, 100, 0, 0));

		assertThat(this.ledger.current(ALICE_TODAY).committed())
				.isEqualTo(new Amount(500, Money.ofDollars("0.0006")));
	}

	@Test
	void observeModeReservationOverLimitSettlesNormally() {
		BudgetService service = service(EnforcementMode.OBSERVE, USER_DAILY);
		service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE);
		Reservation overLimit = reservationOf(service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE));

		service.settle(ALICE, overLimit, MODEL, new TokenUsage(400, 100, 0, 0));

		assertThat(this.ledger.current(ALICE_TODAY))
				.isEqualTo(new Spend(new Amount(500, Money.ofDollars("0.0006")), ESTIMATE_AMOUNT));
	}

	@Test
	void allowsEverythingWhenNoLimitsAreConfigured() {
		BudgetService service = service(EnforcementMode.ENFORCE);

		Decision admission = service.admit(ALICE);
		Reservation reservation = reservationOf(service.reserve(ALICE, RequestLimit.NONE, MODEL,
				new TokenUsage(1_000_000, 0, 0, 0)));
		service.settle(ALICE, reservation, MODEL, ESTIMATE);

		assertThat(admission).isEqualTo(new Decision.Allowed());
		assertThat(reservation.keys()).isEmpty();
	}

	@Test
	void parallelRequestsNeverExceedUserOrGlobalLimit() throws Exception {
		Limit globalDaily = new Limit(Scope.GLOBAL, Window.DAILY, 10_000L, null);
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY, globalDaily);
		ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Decision>> results = new ArrayList<>();
		try {
			for (int i = 0; i < THREADS; i++) {
				Caller user = new Caller(Map.of(Scope.USER, "user-" + (i % USERS)), null);
				results.add(pool.submit(() -> {
					start.await();
					return service.reserve(user, RequestLimit.NONE, MODEL, new TokenUsage(1000, 0, 0, 0));
				}));
			}
			start.countDown();
			for (Future<Decision> result : results) {
				result.get();
			}
		}
		finally {
			pool.shutdownNow();
		}

		BudgetKey globalKey = BudgetKey.of(Scope.GLOBAL, BudgetKey.GLOBAL_SUBJECT, Window.DAILY, START, ZoneOffset.UTC);
		assertThat(this.ledger.current(globalKey).reserved().tokens()).isEqualTo(10_000);
		for (int u = 0; u < USERS; u++) {
			BudgetKey userKey = BudgetKey.of(Scope.USER, "user-" + u, Window.DAILY, START, ZoneOffset.UTC);
			assertThat(this.ledger.current(userKey).reserved().tokens()).isLessThanOrEqualTo(2000);
		}
	}

	@Test
	void rejectsTwoLimitsForSameScopeAndWindow() {
		Limit secondUserDaily = new Limit(Scope.USER, Window.DAILY, 5L, null);

		assertThatThrownBy(() -> service(EnforcementMode.ENFORCE, USER_DAILY, secondUserDaily))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void checkRequestAllowsBelowCaps() {
		BudgetService service = requestCapped(EnforcementMode.ENFORCE);

		Decision decision = service.checkRequest(RequestLimit.NONE, 2, Money.ofDollars("0.09"));

		assertThat(decision).isEqualTo(new Decision.Allowed());
	}

	@Test
	void checkRequestDeniesWhenModelCallLimitReached() {
		BudgetService service = requestCapped(EnforcementMode.ENFORCE);

		Decision decision = service.checkRequest(RequestLimit.NONE, 3, Money.ZERO);

		assertThat(decision).isEqualTo(new Decision.Denied("request model call limit reached", Duration.ZERO));
	}

	@Test
	void checkRequestDeniesWhenCostLimitReached() {
		BudgetService service = requestCapped(EnforcementMode.ENFORCE);

		Decision decision = service.checkRequest(RequestLimit.NONE, 1, Money.ofDollars("0.10"));

		assertThat(decision).isEqualTo(new Decision.Denied("request cost limit reached", Duration.ZERO));
	}

	@Test
	void checkRequestAllowsInObserveMode() {
		BudgetService service = requestCapped(EnforcementMode.OBSERVE);

		Decision decision = service.checkRequest(RequestLimit.NONE, 3, Money.ofDollars("0.10"));

		assertThat(decision).isEqualTo(new Decision.Allowed());
	}

	@Test
	void checkRequestAllowsEverythingWithoutCaps() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);

		Decision decision = service.checkRequest(RequestLimit.NONE, 1_000, Money.ofDollars("1000.00"));

		assertThat(decision).isEqualTo(new Decision.Allowed());
	}

	@Test
	void settleReturnsChargedAmount() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);
		Reservation reservation = reservationOf(service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE));

		Amount charged = service.settle(ALICE, reservation, MODEL, new TokenUsage(400, 100, 0, 0));

		assertThat(charged).isEqualTo(new Amount(500, Money.ofDollars("0.0006")));
	}

	@Test
	void settleReturnsEstimateWhenUsageIsMissing() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);
		Reservation reservation = reservationOf(service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE));

		assertThat(service.settle(ALICE, reservation, MODEL, TokenUsage.ZERO)).isEqualTo(ESTIMATE_AMOUNT);
	}

	@Test
	void admitsWhenLedgerUnavailableAndFailOpen() {
		BudgetService service = unavailable(LedgerFailureMode.FAIL_OPEN);

		assertThat(service.admit(ALICE)).isEqualTo(new Decision.Allowed());
	}

	@Test
	void admitThrowsWhenLedgerUnavailableAndFailClosed() {
		BudgetService service = unavailable(LedgerFailureMode.FAIL_CLOSED);

		assertThatThrownBy(() -> service.admit(ALICE)).isInstanceOf(LedgerUnavailableException.class);
	}

	@Test
	void reservesUntrackedEstimateWhenLedgerUnavailableAndFailOpen() {
		BudgetService service = unavailable(LedgerFailureMode.FAIL_OPEN);

		Reservation reservation = reservationOf(service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE));

		assertThat(reservation.amount()).isEqualTo(ESTIMATE_AMOUNT);
		assertThat(reservation.keys()).containsExactly(ALICE_TODAY);
	}

	@Test
	void reserveThrowsWhenLedgerUnavailableAndFailClosed() {
		BudgetService service = unavailable(LedgerFailureMode.FAIL_CLOSED);

		assertThatThrownBy(() -> service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE))
			.isInstanceOf(LedgerUnavailableException.class);
	}

	@Test
	void countsUnknownModelWithoutCostWhenNoCostCapApplies() {
		BudgetService service = service(EnforcementMode.ENFORCE, GLOBAL_MONTHLY);
		BudgetKey globalKey = BudgetKey.of(Scope.GLOBAL, BudgetKey.GLOBAL_SUBJECT, Window.MONTHLY, START,
				ZoneOffset.UTC);

		Reservation reservation = reservationOf(service.reserve(ALICE, RequestLimit.NONE, "other-model", ESTIMATE));
		service.settle(ALICE, reservation, "other-model", new TokenUsage(400, 100, 0, 0));

		assertThat(this.ledger.current(globalKey).committed()).isEqualTo(new Amount(500, Money.ZERO));
	}

	@Test
	void rejectsUnknownModelWhenRequestCostCapApplies() {
		BudgetService service = service(EnforcementMode.ENFORCE, GLOBAL_MONTHLY);
		RequestLimit costCapped = new RequestLimit(null, Money.ofDollars("0.10"));

		assertThatThrownBy(() -> service.reserve(ALICE, costCapped, "other-model", ESTIMATE))
			.isInstanceOf(UnknownModelException.class);
	}

	@Test
	void rejectsUnknownModelEvenWhenLedgerUnavailable() {
		BudgetService service = unavailable(LedgerFailureMode.FAIL_OPEN);

		assertThatThrownBy(() -> service.reserve(ALICE, RequestLimit.NONE, "other-model", ESTIMATE))
			.isInstanceOf(UnknownModelException.class);
	}

	@Test
	void settleReturnsChargeWhenLedgerUnavailable() {
		BudgetService service = unavailable(LedgerFailureMode.FAIL_CLOSED);
		Reservation reservation = new Reservation("r1", Set.of(ALICE_TODAY), ESTIMATE_AMOUNT);

		Amount charged = service.settle(ALICE, reservation, MODEL, new TokenUsage(800, 200, 0, 0));

		assertThat(charged).isEqualTo(new Amount(1000, new Money(1200)));
	}

	@Test
	void releaseDoesNotThrowWhenLedgerUnavailable() {
		BudgetService service = unavailable(LedgerFailureMode.FAIL_CLOSED);
		Reservation reservation = new Reservation("r1", Set.of(ALICE_TODAY), ESTIMATE_AMOUNT);

		assertThatCode(() -> service.release(reservation, MODEL)).doesNotThrowAnyException();
	}

	@Test
	void notifiesAllowedAdmission() {
		BudgetService service = listened(EnforcementMode.ENFORCE, this.ledger, LedgerFailureMode.FAIL_CLOSED);

		service.admit(ALICE);

		assertThat(this.listener.decisions).containsExactly(
				new DecisionMade(DecisionMade.Stage.ADMISSION, DecisionMade.Outcome.ALLOWED, null));
	}

	@Test
	void notifiesDeniedAdmissionWithScopeOfUsedUpBudget() {
		BudgetService service = listened(EnforcementMode.ENFORCE, this.ledger, LedgerFailureMode.FAIL_CLOSED);
		service.settle(ALICE, reservationOf(service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE)), MODEL,
				new TokenUsage(1500, 500, 0, 0));

		service.admit(ALICE);

		assertThat(this.listener.decisions).last()
			.isEqualTo(new DecisionMade(DecisionMade.Stage.ADMISSION, DecisionMade.Outcome.DENIED, Scope.USER));
	}

	@Test
	void notifiesObservedAdmissionInObserveMode() {
		BudgetService service = listened(EnforcementMode.OBSERVE, this.ledger, LedgerFailureMode.FAIL_OPEN);
		service.settle(ALICE, reservationOf(service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE)), MODEL,
				new TokenUsage(1500, 500, 0, 0));

		service.admit(ALICE);

		assertThat(this.listener.decisions).last()
			.isEqualTo(new DecisionMade(DecisionMade.Stage.ADMISSION, DecisionMade.Outcome.OBSERVED, Scope.USER));
	}

	@Test
	void notifiesUnavailableLedgerBeforeFailingClosed() {
		BudgetService service = listened(EnforcementMode.ENFORCE, new UnavailableLedger(),
				LedgerFailureMode.FAIL_CLOSED);

		assertThatThrownBy(() -> service.admit(ALICE)).isInstanceOf(LedgerUnavailableException.class);

		assertThat(this.listener.decisions).containsExactly(
				new DecisionMade(DecisionMade.Stage.ADMISSION, DecisionMade.Outcome.LEDGER_UNAVAILABLE, null));
	}

	@Test
	void notifiesRequestCapOnlyWhenReached() {
		BudgetService service = new BudgetService(this.ledger, rejectingCatalog(),
				new PlanLimits(List.of(USER_DAILY), Map.of()), new RequestLimit(3, null), EnforcementMode.ENFORCE,
				LedgerFailureMode.FAIL_CLOSED, this.clock, ZoneOffset.UTC, List.of(), List.of(this.listener));

		service.checkRequest(RequestLimit.NONE, 2, Money.ZERO);
		service.checkRequest(RequestLimit.NONE, 3, Money.ZERO);

		assertThat(this.listener.decisions).containsExactly(
				new DecisionMade(DecisionMade.Stage.REQUEST, DecisionMade.Outcome.DENIED, null));
	}

	@Test
	void notifiesAllowedThenDeniedReservation() {
		BudgetService service = listened(EnforcementMode.ENFORCE, this.ledger, LedgerFailureMode.FAIL_CLOSED);

		service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE);
		service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE);

		assertThat(this.listener.decisions).containsExactly(
				new DecisionMade(DecisionMade.Stage.RESERVATION, DecisionMade.Outcome.ALLOWED, null),
				new DecisionMade(DecisionMade.Stage.RESERVATION, DecisionMade.Outcome.DENIED, Scope.USER));
	}

	@Test
	void notifiesObservedReservationInObserveMode() {
		BudgetService service = listened(EnforcementMode.OBSERVE, this.ledger, LedgerFailureMode.FAIL_OPEN);

		service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE);
		service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE);

		assertThat(this.listener.decisions).last()
			.isEqualTo(new DecisionMade(DecisionMade.Stage.RESERVATION, DecisionMade.Outcome.OBSERVED, Scope.USER));
	}

	@Test
	void keepsBudgetingWhenListenerThrows() {
		BudgetListener failing = new BudgetListener() {
			@Override
			public void onDecision(DecisionMade event) {
				throw new IllegalStateException("listener bug");
			}

			@Override
			public void onSettled(CallSettled event) {
				throw new IllegalStateException("listener bug");
			}
		};
		BudgetService service = new BudgetService(this.ledger, rejectingCatalog(),
				new PlanLimits(List.of(USER_DAILY), Map.of()), RequestLimit.NONE, EnforcementMode.ENFORCE,
				LedgerFailureMode.FAIL_CLOSED, this.clock, ZoneOffset.UTC, List.of(), List.of(failing, this.listener));

		Reservation reservation = reservationOf(service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE));
		service.settle(ALICE, reservation, MODEL, ESTIMATE);

		assertThat(this.ledger.current(ALICE_TODAY)).isEqualTo(new Spend(ESTIMATE_AMOUNT, Amount.ZERO));
		assertThat(this.listener.decisions).hasSize(1);
		assertThat(this.listener.settled).hasSize(1);
	}

	@Test
	void notifiesChargedCallWithReportedUsage() {
		BudgetService service = listened(EnforcementMode.ENFORCE, this.ledger, LedgerFailureMode.FAIL_CLOSED);
		Reservation reservation = reservationOf(service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE));
		TokenUsage reported = new TokenUsage(800, 200, 0, 0);

		Amount charged = service.settle(ALICE, reservation, MODEL, reported);

		assertThat(this.listener.settled).containsExactly(new CallSettled(MODEL, Set.of(ALICE_TODAY),
				ESTIMATE_AMOUNT, reported, charged, CallSettled.Outcome.CHARGED));
	}

	@Test
	void notifiesEstimateChargedWhenUsageIsMissing() {
		BudgetService service = listened(EnforcementMode.ENFORCE, this.ledger, LedgerFailureMode.FAIL_CLOSED);
		Reservation reservation = reservationOf(service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE));

		service.settle(ALICE, reservation, MODEL, TokenUsage.ZERO);

		assertThat(this.listener.settled).containsExactly(new CallSettled(MODEL, Set.of(ALICE_TODAY),
				ESTIMATE_AMOUNT, TokenUsage.ZERO, ESTIMATE_AMOUNT, CallSettled.Outcome.ESTIMATE_CHARGED));
	}

	@Test
	void notifiesReleasedCallWithoutCharge() {
		BudgetService service = listened(EnforcementMode.ENFORCE, this.ledger, LedgerFailureMode.FAIL_CLOSED);
		Reservation reservation = reservationOf(service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE));

		service.release(reservation, MODEL);

		assertThat(this.listener.settled).containsExactly(new CallSettled(MODEL, Set.of(ALICE_TODAY),
				ESTIMATE_AMOUNT, TokenUsage.ZERO, Amount.ZERO, CallSettled.Outcome.RELEASED));
	}

	@Test
	void notifiesSettledCallEvenWhenLedgerUnavailable() {
		BudgetService service = listened(EnforcementMode.ENFORCE, new UnavailableLedger(),
				LedgerFailureMode.FAIL_CLOSED);
		Reservation reservation = new Reservation("r1", Set.of(ALICE_TODAY), ESTIMATE_AMOUNT);

		service.settle(ALICE, reservation, MODEL, new TokenUsage(800, 200, 0, 0));

		assertThat(this.listener.settled).singleElement()
			.extracting(CallSettled::outcome)
			.isEqualTo(CallSettled.Outcome.CHARGED);
	}

	@Test
	void notifiesWhenCallTakesSpendToThreshold() {
		BudgetService service = alerting(List.of(80), EnforcementMode.ENFORCE, this.ledger);
		Reservation reservation = reservationOf(service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE));

		// 1,600 of 2,000 tokens is 80%; 1,100 input at $1/MTok plus 500 output at $2/MTok is $0.0021.
		service.settle(ALICE, reservation, MODEL, new TokenUsage(1100, 500, 0, 0));

		assertThat(this.listener.thresholds).containsExactly(new BudgetThresholdReached(ALICE_TODAY, USER_DAILY, 80,
				new Amount(1600, Money.ofDollars("0.0021")), Instant.parse("2026-10-23T00:00:00Z")));
	}

	@Test
	void notifiesOnlyTheCallThatCrossesThreshold() {
		BudgetService service = alerting(List.of(80), EnforcementMode.ENFORCE, this.ledger);

		charge(service, 1000);
		charge(service, 600);
		charge(service, 200);

		assertThat(this.listener.thresholds).singleElement()
			.extracting(reached -> reached.committed().tokens())
			.isEqualTo(1600L);
	}

	@Test
	void notifiesAgainInNextWindow() {
		BudgetService service = alerting(List.of(80), EnforcementMode.ENFORCE, this.ledger);

		charge(service, 1600);
		this.clock.advance(Duration.ofDays(1));
		charge(service, 1600);

		assertThat(this.listener.thresholds).extracting(reached -> reached.key().windowStart())
			.containsExactly(Instant.parse("2026-10-22T00:00:00Z"), Instant.parse("2026-10-23T00:00:00Z"));
	}

	@Test
	void notifiesEveryThresholdOneCallPassesInAscendingOrder() {
		BudgetService service = alerting(List.of(100, 80), EnforcementMode.ENFORCE, this.ledger);

		charge(service, 2000);

		assertThat(this.listener.thresholds).extracting(BudgetThresholdReached::percent).containsExactly(80, 100);
	}

	@Test
	void notifiesThresholdsPassedInObserveMode() {
		BudgetService service = alerting(List.of(80, 100), EnforcementMode.OBSERVE, this.ledger);

		charge(service, 1500);
		charge(service, 1500);

		assertThat(this.listener.thresholds).extracting(BudgetThresholdReached::percent).containsExactly(80, 100);
	}

	@Test
	void noThresholdEventWhenLedgerUnavailable() {
		BudgetService service = alerting(List.of(80), EnforcementMode.ENFORCE, new UnavailableLedger());
		Reservation reservation = new Reservation("r1", Set.of(ALICE_TODAY), ESTIMATE_AMOUNT);

		service.settle(ALICE, reservation, MODEL, new TokenUsage(1600, 0, 0, 0));

		assertThat(this.listener.thresholds).isEmpty();
	}

	@Test
	void noThresholdEventWhenSettledTwice() {
		BudgetService service = alerting(List.of(80), EnforcementMode.ENFORCE, this.ledger);
		Reservation reservation = reservationOf(service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE));

		service.settle(ALICE, reservation, MODEL, new TokenUsage(1600, 0, 0, 0));
		service.settle(ALICE, reservation, MODEL, new TokenUsage(1600, 0, 0, 0));

		assertThat(this.listener.thresholds).hasSize(1);
	}

	@Test
	void rejectsThresholdAbove100() {
		assertThatThrownBy(() -> alerting(List.of(101), EnforcementMode.ENFORCE, this.ledger))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("101");
	}

	@Test
	void statusShowsCommittedAndReservedSpendWithResetTime() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);
		service.settle(ALICE, reservationOf(service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE)), MODEL,
				new TokenUsage(800, 200, 0, 0));
		service.reserve(ALICE, RequestLimit.NONE, MODEL, new TokenUsage(500, 250, 0, 0));

		List<BudgetStatus> status = service.statusOf(Scope.USER, "alice", null);

		assertThat(status).containsExactly(new BudgetStatus(USER_DAILY, Instant.parse("2026-10-22T00:00:00Z"),
				Instant.parse("2026-10-23T00:00:00Z"),
				new Spend(new Amount(1000, new Money(1200)), new Amount(750, new Money(1000)))));
	}

	@Test
	void statusListsEveryWindowOfTheScope() {
		Limit userMonthly = new Limit(Scope.USER, Window.MONTHLY, null, Money.ofDollars("5.00"));
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY, userMonthly, GLOBAL_MONTHLY);

		List<BudgetStatus> status = service.statusOf(Scope.USER, "alice", null);

		assertThat(status).extracting(BudgetStatus::limit).containsExactly(USER_DAILY, userMonthly);
	}

	@Test
	void statusIsEmptyForScopeWithoutLimits() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);

		assertThat(service.statusOf(Scope.TENANT, "acme", null)).isEmpty();
	}

	@Test
	void globalStatusIgnoresSubjectId() {
		BudgetService service = service(EnforcementMode.ENFORCE, GLOBAL_MONTHLY);
		service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE);

		List<BudgetStatus> status = service.statusOf(Scope.GLOBAL, "anyone", null);

		assertThat(status).singleElement()
			.extracting(budget -> budget.spend().reserved())
			.isEqualTo(ESTIMATE_AMOUNT);
	}

	@Test
	void upgradedCallerKeepsTodaysSpendUnderTheHigherCap() {
		BudgetService service = planned();
		charge(service, ALICE, 2000);

		assertThat(service.admit(ALICE)).isInstanceOf(Decision.Denied.class);
		assertThat(service.admit(ALICE_ON_PRO)).isEqualTo(new Decision.Allowed());
	}

	@Test
	void reservesAgainstTheCallersPlanCap() {
		BudgetService service = planned();

		Decision decision = service.reserve(ALICE_ON_PRO, RequestLimit.NONE, MODEL, new TokenUsage(5000, 0, 0, 0));

		assertThat(decision).isInstanceOf(Decision.Reserved.class);
	}

	@Test
	void usesLimitsFromCustomResolver() {
		Limit contractCap = new Limit(Scope.TENANT, Window.MONTHLY, null, Money.ofDollars("500"));
		BudgetService service = new BudgetService(this.ledger, rejectingCatalog(), caller -> List.of(contractCap),
				RequestLimit.NONE, EnforcementMode.ENFORCE, LedgerFailureMode.FAIL_CLOSED, this.clock, ZoneOffset.UTC,
				List.of(), List.of());
		Caller acme = new Caller(Map.of(Scope.TENANT, "acme"), null);

		Reservation reservation = reservationOf(service.reserve(acme, RequestLimit.NONE, MODEL, ESTIMATE));

		assertThat(reservation.keys())
			.containsExactly(BudgetKey.of(Scope.TENANT, "acme", Window.MONTHLY, START, ZoneOffset.UTC));
	}

	@Test
	void rejectsResolverResultWithTwoLimitsForSameScopeAndWindow() {
		BudgetService service = new BudgetService(this.ledger, rejectingCatalog(),
				caller -> List.of(USER_DAILY, PRO_USER_DAILY), RequestLimit.NONE, EnforcementMode.ENFORCE,
				LedgerFailureMode.FAIL_CLOSED, this.clock, ZoneOffset.UTC, List.of(), List.of());

		assertThatThrownBy(() -> service.admit(ALICE)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void thresholdIsMeasuredAgainstTheCallersPlanLimit() {
		BudgetService service = planned();

		charge(service, ALICE_ON_PRO, 16_000);

		assertThat(this.listener.thresholds).singleElement()
			.extracting(BudgetThresholdReached::limit)
			.isEqualTo(PRO_USER_DAILY);
	}

	@Test
	void statusShowsThePlansLimit() {
		BudgetService service = planned();

		List<BudgetStatus> status = service.statusOf(Scope.USER, "alice", "pro");

		assertThat(status).extracting(BudgetStatus::limit).containsExactly(PRO_USER_DAILY);
	}

	@Test
	void endpointCapReplacesConfiguredRequestCap() {
		BudgetService service = requestCapped(EnforcementMode.ENFORCE);

		Decision decision = service.checkRequest(new RequestLimit(10, null), 3, Money.ZERO);

		assertThat(decision).isEqualTo(new Decision.Allowed());
	}

	@Test
	void configuredRequestCapAppliesWhereEndpointSetsNone() {
		BudgetService service = requestCapped(EnforcementMode.ENFORCE);

		Decision decision = service.checkRequest(new RequestLimit(10, null), 1, Money.ofDollars("0.10"));

		assertThat(decision).isEqualTo(new Decision.Denied("request cost limit reached", Duration.ZERO));
	}

	@Test
	void endpointCanSetRequestCapTheConfigurationLacks() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);

		Decision decision = service.checkRequest(new RequestLimit(2, null), 2, Money.ZERO);

		assertThat(decision).isEqualTo(new Decision.Denied("request model call limit reached", Duration.ZERO));
	}

	@Test
	void topUpGivesRoomBack() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);
		charge(service, 2000);

		service.topUp(Scope.USER, "alice", new Amount(500, Money.ofDollars("0.0005")));

		assertThat(service.admit(ALICE)).isEqualTo(new Decision.Allowed());
		assertThat(this.ledger.current(ALICE_TODAY).committed()).isEqualTo(new Amount(1500, Money.ofDollars("0.0015")));
	}

	@Test
	void resetClearsCommittedSpendInEveryWindowOfTheSubject() {
		Limit userMonthly = new Limit(Scope.USER, Window.MONTHLY, null, Money.ofDollars("5.00"));
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY, userMonthly);
		charge(service, 2000);

		service.reset(Scope.USER, "alice");

		BudgetKey aliceThisMonth = BudgetKey.of(Scope.USER, "alice", Window.MONTHLY, START, ZoneOffset.UTC);
		assertThat(this.ledger.current(ALICE_TODAY)).isEqualTo(Spend.ZERO);
		assertThat(this.ledger.current(aliceThisMonth)).isEqualTo(Spend.ZERO);
	}

	@Test
	void resetKeepsReservationsOfCallsInFlight() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);
		charge(service, 400);
		service.reserve(ALICE, RequestLimit.NONE, MODEL, ESTIMATE);

		service.reset(Scope.USER, "alice");

		assertThat(this.ledger.current(ALICE_TODAY)).isEqualTo(new Spend(Amount.ZERO, ESTIMATE_AMOUNT));
	}

	private BudgetService listened(EnforcementMode mode, UsageLedger usageLedger, LedgerFailureMode failureMode) {
		return new BudgetService(usageLedger, rejectingCatalog(), new PlanLimits(List.of(USER_DAILY), Map.of()),
				RequestLimit.NONE, mode, failureMode, this.clock, ZoneOffset.UTC, List.of(), List.of(this.listener));
	}

	private BudgetService alerting(List<Integer> thresholds, EnforcementMode mode, UsageLedger usageLedger) {
		return new BudgetService(usageLedger, rejectingCatalog(), new PlanLimits(List.of(USER_DAILY), Map.of()),
				RequestLimit.NONE, mode, LedgerFailureMode.FAIL_OPEN, this.clock, ZoneOffset.UTC, thresholds,
				List.of(this.listener));
	}

	/** Reserves and charges a call of {@code inputTokens} input tokens for alice. */
	private static void charge(BudgetService service, long inputTokens) {
		charge(service, ALICE, inputTokens);
	}

	private static void charge(BudgetService service, Caller caller, long inputTokens) {
		TokenUsage usage = new TokenUsage(inputTokens, 0, 0, 0);
		service.settle(caller, reservationOf(service.reserve(caller, RequestLimit.NONE, MODEL, usage)), MODEL, usage);
	}

	/** Free callers get {@link #USER_DAILY}, pro callers {@link #PRO_USER_DAILY}; thresholds at 80%. */
	private BudgetService planned() {
		return new BudgetService(this.ledger, rejectingCatalog(),
				new PlanLimits(List.of(USER_DAILY), Map.of("pro", List.of(PRO_USER_DAILY))), RequestLimit.NONE,
				EnforcementMode.ENFORCE, LedgerFailureMode.FAIL_CLOSED, this.clock, ZoneOffset.UTC, List.of(80),
				List.of(this.listener));
	}

	private BudgetService requestCapped(EnforcementMode mode) {
		RequestLimit requestLimit = new RequestLimit(3, Money.ofDollars("0.10"));
		return new BudgetService(this.ledger, rejectingCatalog(), List.of(USER_DAILY), requestLimit, mode,
				LedgerFailureMode.defaultFor(mode), this.clock, ZoneOffset.UTC);
	}

	/** Enforce mode on a ledger that is always down, so only the failure mode decides. */
	private BudgetService unavailable(LedgerFailureMode failureMode) {
		return new BudgetService(new UnavailableLedger(), rejectingCatalog(), List.of(USER_DAILY), RequestLimit.NONE,
				EnforcementMode.ENFORCE, failureMode, this.clock, ZoneOffset.UTC);
	}

	private BudgetService service(EnforcementMode mode, Limit... limits) {
		return new BudgetService(this.ledger, rejectingCatalog(), List.of(limits), mode, this.clock, ZoneOffset.UTC);
	}

	private static PriceCatalog rejectingCatalog() {
		return new ConfiguredPriceCatalog(Map.of(MODEL, PRICE), UnknownModelPolicy.REJECT, null);
	}

	private static Reservation reservationOf(Decision decision) {
		assertThat(decision).isInstanceOf(Decision.Reserved.class);
		return ((Decision.Reserved) decision).reservation();
	}

	private static final class UnavailableLedger implements UsageLedger {

		@Override
		public ReservationResult reserve(Map<BudgetKey, Limit> limits, Amount estimate) {
			throw down();
		}

		@Override
		public Reservation reserveUnchecked(Set<BudgetKey> keys, Amount estimate) {
			throw down();
		}

		@Override
		public Map<BudgetKey, Amount> commit(Reservation reservation, Amount actual) {
			throw down();
		}

		@Override
		public void release(Reservation reservation) {
			throw down();
		}

		@Override
		public Spend current(BudgetKey key) {
			throw down();
		}

		@Override
		public void refund(BudgetKey key, Amount amount) {
			throw down();
		}

		private static LedgerUnavailableException down() {
			return new LedgerUnavailableException("store down", new IllegalStateException("connection refused"));
		}
	}

	private static final class RecordingListener implements BudgetListener {

		private final List<DecisionMade> decisions = new ArrayList<>();

		private final List<CallSettled> settled = new ArrayList<>();

		private final List<BudgetThresholdReached> thresholds = new ArrayList<>();

		@Override
		public void onDecision(DecisionMade event) {
			this.decisions.add(event);
		}

		@Override
		public void onSettled(CallSettled event) {
			this.settled.add(event);
		}

		@Override
		public void onThresholdReached(BudgetThresholdReached event) {
			this.thresholds.add(event);
		}
	}
}
