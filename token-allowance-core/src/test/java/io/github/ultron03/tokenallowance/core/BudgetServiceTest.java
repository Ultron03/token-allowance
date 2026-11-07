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

	private static final Map<Scope, String> ALICE = Map.of(Scope.USER, "alice");

	private static final Limit USER_DAILY = new Limit(Scope.USER, Window.DAILY, 2000L, Money.ofDollars("1.00"));

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
		service.settle(reservationOf(service.reserve(ALICE, MODEL, ESTIMATE)), MODEL, new TokenUsage(1500, 500, 0, 0));

		Decision decision = service.admit(ALICE);

		assertThat(decision).isEqualTo(new Decision.Denied("user daily budget exceeded", Duration.ofHours(14)));
	}

	@Test
	void admissionIgnoresCallsStillInFlight() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);
		service.reserve(ALICE, MODEL, new TokenUsage(1500, 500, 0, 0));

		Decision decision = service.admit(ALICE);

		assertThat(decision).isEqualTo(new Decision.Allowed());
	}

	@Test
	void admitsInObserveModeEvenWhenLimitReached() {
		BudgetService service = service(EnforcementMode.OBSERVE, USER_DAILY);
		service.settle(reservationOf(service.reserve(ALICE, MODEL, ESTIMATE)), MODEL, new TokenUsage(1500, 500, 0, 0));

		Decision decision = service.admit(ALICE);

		assertThat(decision).isEqualTo(new Decision.Allowed());
	}

	@Test
	void reservesPricedEstimateWhenUnderLimit() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);

		Decision decision = service.reserve(ALICE, MODEL, ESTIMATE);

		assertThat(decision).isInstanceOf(Decision.Reserved.class);
		assertThat(this.ledger.current(ALICE_TODAY).reserved()).isEqualTo(ESTIMATE_AMOUNT);
	}

	@Test
	void deniesReservationOverLimitWithRetryAfterUntilWindowEnd() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);
		service.reserve(ALICE, MODEL, ESTIMATE);

		Decision decision = service.reserve(ALICE, MODEL, ESTIMATE);

		assertThat(decision).isEqualTo(new Decision.Denied("user daily budget exceeded", Duration.ofHours(14)));
	}

	@Test
	void reservesInObserveModeEvenOverLimitSoSpendIsRecorded() {
		BudgetService service = service(EnforcementMode.OBSERVE, USER_DAILY);
		service.reserve(ALICE, MODEL, ESTIMATE);

		Decision decision = service.reserve(ALICE, MODEL, ESTIMATE);

		assertThat(decision).isInstanceOf(Decision.Reserved.class);
		assertThat(this.ledger.current(ALICE_TODAY).reserved().tokens()).isEqualTo(3000);
	}

	@Test
	void appliesGlobalLimitWithoutSubjectId() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY, GLOBAL_MONTHLY);
		BudgetKey globalKey = BudgetKey.of(Scope.GLOBAL, BudgetKey.GLOBAL_SUBJECT, Window.MONTHLY, START,
				ZoneOffset.UTC);

		service.reserve(ALICE, MODEL, ESTIMATE);

		assertThat(this.ledger.current(globalKey).reserved()).isEqualTo(ESTIMATE_AMOUNT);
	}

	@Test
	void skipsLimitWhoseScopeHasNoSubjectId() {
		Limit tenantDaily = new Limit(Scope.TENANT, Window.DAILY, 1L, null);
		BudgetService service = service(EnforcementMode.ENFORCE, tenantDaily);

		Decision decision = service.reserve(ALICE, MODEL, ESTIMATE);

		assertThat(reservationOf(decision).keys()).isEmpty();
	}

	@Test
	void reservesNoCostForUncostedModel() {
		PriceCatalog uncosted = new ConfiguredPriceCatalog(Map.of(), UnknownModelPolicy.ALLOW_UNCOSTED, null);
		BudgetService service = new BudgetService(this.ledger, uncosted, List.of(USER_DAILY), EnforcementMode.ENFORCE,
				this.clock, ZoneOffset.UTC);

		service.reserve(ALICE, "other-model", ESTIMATE);

		assertThat(this.ledger.current(ALICE_TODAY).reserved()).isEqualTo(new Amount(1500, Money.ZERO));
	}

	@Test
	void throwsForUnknownModelWhenPolicyRejects() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);

		assertThatThrownBy(() -> service.reserve(ALICE, "other-model", ESTIMATE))
				.isInstanceOf(UnknownModelException.class);
	}

	@Test
	void settleChargesActualUsage() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);
		Reservation reservation = reservationOf(service.reserve(ALICE, MODEL, ESTIMATE));

		service.settle(reservation, MODEL, new TokenUsage(400, 100, 0, 0));

		assertThat(this.ledger.current(ALICE_TODAY))
				.isEqualTo(new Spend(new Amount(500, Money.ofDollars("0.0006")), Amount.ZERO));
	}

	@Test
	void settleChargesEstimateWhenUsageIsMissing() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);
		Reservation reservation = reservationOf(service.reserve(ALICE, MODEL, ESTIMATE));

		service.settle(reservation, MODEL, TokenUsage.ZERO);

		assertThat(this.ledger.current(ALICE_TODAY)).isEqualTo(new Spend(ESTIMATE_AMOUNT, Amount.ZERO));
	}

	@Test
	void releaseDropsReservationWithoutCharging() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);
		Reservation reservation = reservationOf(service.reserve(ALICE, MODEL, ESTIMATE));

		service.release(reservation, MODEL);

		assertThat(this.ledger.current(ALICE_TODAY)).isEqualTo(Spend.ZERO);
	}

	@Test
	void budgetResetsInNextWindow() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);
		service.settle(reservationOf(service.reserve(ALICE, MODEL, ESTIMATE)), MODEL, new TokenUsage(1500, 500, 0, 0));
		this.clock.advance(Duration.ofDays(1));

		Decision decision = service.admit(ALICE);

		assertThat(decision).isEqualTo(new Decision.Allowed());
	}

	@Test
	void chargesCallToTheWindowItWasReservedIn() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);
		this.clock.advance(Duration.ofHours(13).plusMinutes(59));
		Reservation reservation = reservationOf(service.reserve(ALICE, MODEL, ESTIMATE));
		this.clock.advance(Duration.ofMinutes(2));

		service.settle(reservation, MODEL, new TokenUsage(400, 100, 0, 0));

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
		service.reserve(ALICE, MODEL, ESTIMATE);

		// 10:00 UTC is 15:30 in India; the day there ends at 18:30 UTC
		Decision decision = service.reserve(ALICE, MODEL, ESTIMATE);

		assertThat(decision).isEqualTo(
				new Decision.Denied("user daily budget exceeded", Duration.ofHours(8).plusMinutes(30)));
	}

	@Test
	void retryAfterForMonthlyLimitIsUntilFirstOfNextMonth() {
		BudgetService service = service(EnforcementMode.ENFORCE, GLOBAL_MONTHLY);

		Decision decision = service.reserve(ALICE, MODEL, new TokenUsage(10_001, 0, 0, 0));

		Duration untilNovember = Duration.between(START, Instant.parse("2026-11-01T00:00:00Z"));
		assertThat(decision).isEqualTo(new Decision.Denied("global monthly budget exceeded", untilNovember));
	}

	@Test
	void reportsTheFirstConfiguredLimitThatIsExceeded() {
		TokenUsage overBoth = new TokenUsage(20_000, 0, 0, 0);

		Decision userFirst = service(EnforcementMode.ENFORCE, USER_DAILY, GLOBAL_MONTHLY).reserve(ALICE, MODEL, overBoth);
		Decision globalFirst = service(EnforcementMode.ENFORCE, GLOBAL_MONTHLY, USER_DAILY).reserve(ALICE, MODEL,
				overBoth);

		assertThat(((Decision.Denied) userFirst).reason()).isEqualTo("user daily budget exceeded");
		assertThat(((Decision.Denied) globalFirst).reason()).isEqualTo("global monthly budget exceeded");
	}

	@Test
	void deniedReasonDoesNotNameTheSubject() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);

		Decision decision = service.reserve(ALICE, MODEL, new TokenUsage(5000, 0, 0, 0));

		assertThat(((Decision.Denied) decision).reason()).doesNotContain("alice");
	}

	@Test
	void deniesWhenPricedEstimateExceedsCostCap() {
		Limit tenthOfACent = new Limit(Scope.USER, Window.DAILY, null, Money.ofDollars("0.001"));
		BudgetService service = service(EnforcementMode.ENFORCE, tenthOfACent);

		Decision decision = service.reserve(ALICE, MODEL, ESTIMATE);

		assertThat(decision).isInstanceOf(Decision.Denied.class);
	}

	@Test
	void uncostedModelStillCountsTokensAgainstTokenCap() {
		PriceCatalog uncosted = new ConfiguredPriceCatalog(Map.of(), UnknownModelPolicy.ALLOW_UNCOSTED, null);
		BudgetService service = new BudgetService(this.ledger, uncosted, List.of(USER_DAILY), EnforcementMode.ENFORCE,
				this.clock, ZoneOffset.UTC);
		service.reserve(ALICE, "other-model", ESTIMATE);

		Decision decision = service.reserve(ALICE, "other-model", ESTIMATE);

		assertThat(decision).isInstanceOf(Decision.Denied.class);
	}

	@Test
	void settleChargesDefaultPriceForUnknownModel() {
		PriceCatalog withDefault = new ConfiguredPriceCatalog(Map.of(), UnknownModelPolicy.DEFAULT_PRICE, PRICE);
		BudgetService service = new BudgetService(this.ledger, withDefault, List.of(USER_DAILY),
				EnforcementMode.ENFORCE, this.clock, ZoneOffset.UTC);
		Reservation reservation = reservationOf(service.reserve(ALICE, "other-model", ESTIMATE));

		service.settle(reservation, "other-model", new TokenUsage(400, 100, 0, 0));

		assertThat(this.ledger.current(ALICE_TODAY).committed())
				.isEqualTo(new Amount(500, Money.ofDollars("0.0006")));
	}

	@Test
	void observeModeReservationOverLimitSettlesNormally() {
		BudgetService service = service(EnforcementMode.OBSERVE, USER_DAILY);
		service.reserve(ALICE, MODEL, ESTIMATE);
		Reservation overLimit = reservationOf(service.reserve(ALICE, MODEL, ESTIMATE));

		service.settle(overLimit, MODEL, new TokenUsage(400, 100, 0, 0));

		assertThat(this.ledger.current(ALICE_TODAY))
				.isEqualTo(new Spend(new Amount(500, Money.ofDollars("0.0006")), ESTIMATE_AMOUNT));
	}

	@Test
	void allowsEverythingWhenNoLimitsAreConfigured() {
		BudgetService service = service(EnforcementMode.ENFORCE);

		Decision admission = service.admit(ALICE);
		Reservation reservation = reservationOf(service.reserve(ALICE, MODEL, new TokenUsage(1_000_000, 0, 0, 0)));
		service.settle(reservation, MODEL, ESTIMATE);

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
				Map<Scope, String> user = Map.of(Scope.USER, "user-" + (i % USERS));
				results.add(pool.submit(() -> {
					start.await();
					return service.reserve(user, MODEL, new TokenUsage(1000, 0, 0, 0));
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

		assertThat(service.checkRequest(2, Money.ofDollars("0.09"))).isEqualTo(new Decision.Allowed());
	}

	@Test
	void checkRequestDeniesWhenModelCallLimitReached() {
		BudgetService service = requestCapped(EnforcementMode.ENFORCE);

		Decision decision = service.checkRequest(3, Money.ZERO);

		assertThat(decision).isEqualTo(new Decision.Denied("request model call limit reached", Duration.ZERO));
	}

	@Test
	void checkRequestDeniesWhenCostLimitReached() {
		BudgetService service = requestCapped(EnforcementMode.ENFORCE);

		Decision decision = service.checkRequest(1, Money.ofDollars("0.10"));

		assertThat(decision).isEqualTo(new Decision.Denied("request cost limit reached", Duration.ZERO));
	}

	@Test
	void checkRequestAllowsInObserveMode() {
		BudgetService service = requestCapped(EnforcementMode.OBSERVE);

		assertThat(service.checkRequest(3, Money.ofDollars("0.10"))).isEqualTo(new Decision.Allowed());
	}

	@Test
	void checkRequestAllowsEverythingWithoutCaps() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);

		assertThat(service.checkRequest(1_000, Money.ofDollars("1000.00"))).isEqualTo(new Decision.Allowed());
	}

	@Test
	void settleReturnsChargedAmount() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);
		Reservation reservation = reservationOf(service.reserve(ALICE, MODEL, ESTIMATE));

		Amount charged = service.settle(reservation, MODEL, new TokenUsage(400, 100, 0, 0));

		assertThat(charged).isEqualTo(new Amount(500, Money.ofDollars("0.0006")));
	}

	@Test
	void settleReturnsEstimateWhenUsageIsMissing() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);
		Reservation reservation = reservationOf(service.reserve(ALICE, MODEL, ESTIMATE));

		assertThat(service.settle(reservation, MODEL, TokenUsage.ZERO)).isEqualTo(ESTIMATE_AMOUNT);
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

		Reservation reservation = reservationOf(service.reserve(ALICE, MODEL, ESTIMATE));

		assertThat(reservation.amount()).isEqualTo(ESTIMATE_AMOUNT);
		assertThat(reservation.keys()).containsExactly(ALICE_TODAY);
	}

	@Test
	void reserveThrowsWhenLedgerUnavailableAndFailClosed() {
		BudgetService service = unavailable(LedgerFailureMode.FAIL_CLOSED);

		assertThatThrownBy(() -> service.reserve(ALICE, MODEL, ESTIMATE))
			.isInstanceOf(LedgerUnavailableException.class);
	}

	@Test
	void rejectsUnknownModelEvenWhenLedgerUnavailable() {
		BudgetService service = unavailable(LedgerFailureMode.FAIL_OPEN);

		assertThatThrownBy(() -> service.reserve(ALICE, "other-model", ESTIMATE))
			.isInstanceOf(UnknownModelException.class);
	}

	@Test
	void settleReturnsChargeWhenLedgerUnavailable() {
		BudgetService service = unavailable(LedgerFailureMode.FAIL_CLOSED);
		Reservation reservation = new Reservation("r1", Set.of(ALICE_TODAY), ESTIMATE_AMOUNT);

		Amount charged = service.settle(reservation, MODEL, new TokenUsage(800, 200, 0, 0));

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
		service.settle(reservationOf(service.reserve(ALICE, MODEL, ESTIMATE)), MODEL, new TokenUsage(1500, 500, 0, 0));

		service.admit(ALICE);

		assertThat(this.listener.decisions).last()
			.isEqualTo(new DecisionMade(DecisionMade.Stage.ADMISSION, DecisionMade.Outcome.DENIED, Scope.USER));
	}

	@Test
	void notifiesObservedAdmissionInObserveMode() {
		BudgetService service = listened(EnforcementMode.OBSERVE, this.ledger, LedgerFailureMode.FAIL_OPEN);
		service.settle(reservationOf(service.reserve(ALICE, MODEL, ESTIMATE)), MODEL, new TokenUsage(1500, 500, 0, 0));

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
		BudgetService service = new BudgetService(this.ledger, rejectingCatalog(), List.of(USER_DAILY),
				new RequestLimit(3, null), EnforcementMode.ENFORCE, LedgerFailureMode.FAIL_CLOSED, this.clock,
				ZoneOffset.UTC, List.of(this.listener));

		service.checkRequest(2, Money.ZERO);
		service.checkRequest(3, Money.ZERO);

		assertThat(this.listener.decisions).containsExactly(
				new DecisionMade(DecisionMade.Stage.REQUEST, DecisionMade.Outcome.DENIED, null));
	}

	@Test
	void notifiesAllowedThenDeniedReservation() {
		BudgetService service = listened(EnforcementMode.ENFORCE, this.ledger, LedgerFailureMode.FAIL_CLOSED);

		service.reserve(ALICE, MODEL, ESTIMATE);
		service.reserve(ALICE, MODEL, ESTIMATE);

		assertThat(this.listener.decisions).containsExactly(
				new DecisionMade(DecisionMade.Stage.RESERVATION, DecisionMade.Outcome.ALLOWED, null),
				new DecisionMade(DecisionMade.Stage.RESERVATION, DecisionMade.Outcome.DENIED, Scope.USER));
	}

	@Test
	void notifiesObservedReservationInObserveMode() {
		BudgetService service = listened(EnforcementMode.OBSERVE, this.ledger, LedgerFailureMode.FAIL_OPEN);

		service.reserve(ALICE, MODEL, ESTIMATE);
		service.reserve(ALICE, MODEL, ESTIMATE);

		assertThat(this.listener.decisions).last()
			.isEqualTo(new DecisionMade(DecisionMade.Stage.RESERVATION, DecisionMade.Outcome.OBSERVED, Scope.USER));
	}

	@Test
	void notifiesChargedCallWithReportedUsage() {
		BudgetService service = listened(EnforcementMode.ENFORCE, this.ledger, LedgerFailureMode.FAIL_CLOSED);
		Reservation reservation = reservationOf(service.reserve(ALICE, MODEL, ESTIMATE));
		TokenUsage reported = new TokenUsage(800, 200, 0, 0);

		Amount charged = service.settle(reservation, MODEL, reported);

		assertThat(this.listener.settled).containsExactly(new CallSettled(MODEL, Set.of(ALICE_TODAY),
				ESTIMATE_AMOUNT, reported, charged, CallSettled.Outcome.CHARGED));
	}

	@Test
	void notifiesEstimateChargedWhenUsageIsMissing() {
		BudgetService service = listened(EnforcementMode.ENFORCE, this.ledger, LedgerFailureMode.FAIL_CLOSED);
		Reservation reservation = reservationOf(service.reserve(ALICE, MODEL, ESTIMATE));

		service.settle(reservation, MODEL, TokenUsage.ZERO);

		assertThat(this.listener.settled).containsExactly(new CallSettled(MODEL, Set.of(ALICE_TODAY),
				ESTIMATE_AMOUNT, TokenUsage.ZERO, ESTIMATE_AMOUNT, CallSettled.Outcome.ESTIMATE_CHARGED));
	}

	@Test
	void notifiesReleasedCallWithoutCharge() {
		BudgetService service = listened(EnforcementMode.ENFORCE, this.ledger, LedgerFailureMode.FAIL_CLOSED);
		Reservation reservation = reservationOf(service.reserve(ALICE, MODEL, ESTIMATE));

		service.release(reservation, MODEL);

		assertThat(this.listener.settled).containsExactly(new CallSettled(MODEL, Set.of(ALICE_TODAY),
				ESTIMATE_AMOUNT, TokenUsage.ZERO, Amount.ZERO, CallSettled.Outcome.RELEASED));
	}

	@Test
	void notifiesSettledCallEvenWhenLedgerUnavailable() {
		BudgetService service = listened(EnforcementMode.ENFORCE, new UnavailableLedger(),
				LedgerFailureMode.FAIL_CLOSED);
		Reservation reservation = new Reservation("r1", Set.of(ALICE_TODAY), ESTIMATE_AMOUNT);

		service.settle(reservation, MODEL, new TokenUsage(800, 200, 0, 0));

		assertThat(this.listener.settled).singleElement()
			.extracting(CallSettled::outcome)
			.isEqualTo(CallSettled.Outcome.CHARGED);
	}

	@Test
	void statusShowsCommittedAndReservedSpendWithResetTime() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);
		service.settle(reservationOf(service.reserve(ALICE, MODEL, ESTIMATE)), MODEL, new TokenUsage(800, 200, 0, 0));
		service.reserve(ALICE, MODEL, new TokenUsage(500, 250, 0, 0));

		List<BudgetStatus> status = service.statusOf(Scope.USER, "alice");

		assertThat(status).containsExactly(new BudgetStatus(USER_DAILY, Instant.parse("2026-10-22T00:00:00Z"),
				Instant.parse("2026-10-23T00:00:00Z"),
				new Spend(new Amount(1000, new Money(1200)), new Amount(750, new Money(1000)))));
	}

	@Test
	void statusListsEveryWindowOfTheScope() {
		Limit userMonthly = new Limit(Scope.USER, Window.MONTHLY, null, Money.ofDollars("5.00"));
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY, userMonthly, GLOBAL_MONTHLY);

		List<BudgetStatus> status = service.statusOf(Scope.USER, "alice");

		assertThat(status).extracting(BudgetStatus::limit).containsExactly(USER_DAILY, userMonthly);
	}

	@Test
	void statusIsEmptyForScopeWithoutLimits() {
		BudgetService service = service(EnforcementMode.ENFORCE, USER_DAILY);

		assertThat(service.statusOf(Scope.TENANT, "acme")).isEmpty();
	}

	@Test
	void globalStatusIgnoresSubjectId() {
		BudgetService service = service(EnforcementMode.ENFORCE, GLOBAL_MONTHLY);
		service.reserve(ALICE, MODEL, ESTIMATE);

		List<BudgetStatus> status = service.statusOf(Scope.GLOBAL, "anyone");

		assertThat(status).singleElement()
			.extracting(budget -> budget.spend().reserved())
			.isEqualTo(ESTIMATE_AMOUNT);
	}

	private BudgetService listened(EnforcementMode mode, UsageLedger usageLedger, LedgerFailureMode failureMode) {
		return new BudgetService(usageLedger, rejectingCatalog(), List.of(USER_DAILY), RequestLimit.NONE, mode,
				failureMode, this.clock, ZoneOffset.UTC, List.of(this.listener));
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
		public void commit(Reservation reservation, Amount actual) {
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

		private static LedgerUnavailableException down() {
			return new LedgerUnavailableException("store down", new IllegalStateException("connection refused"));
		}
	}

	private static final class RecordingListener implements BudgetListener {

		private final List<DecisionMade> decisions = new ArrayList<>();

		private final List<CallSettled> settled = new ArrayList<>();

		@Override
		public void onDecision(DecisionMade event) {
			this.decisions.add(event);
		}

		@Override
		public void onSettled(CallSettled event) {
			this.settled.add(event);
		}
	}
}
