package io.github.ultron03.tokenallowance.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

		service.release(reservation);

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
}
