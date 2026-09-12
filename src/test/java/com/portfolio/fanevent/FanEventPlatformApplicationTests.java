package com.portfolio.fanevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.fanevent.catalog.application.CatalogCommandService;
import com.portfolio.fanevent.catalog.domain.EventStatus;
import com.portfolio.fanevent.catalog.domain.EventType;
import com.portfolio.fanevent.catalog.domain.InventoryType;
import com.portfolio.fanevent.member.domain.Member;
import com.portfolio.fanevent.member.infrastructure.MemberRepository;
import com.portfolio.fanevent.outbox.application.OutboxPublisher;
import com.portfolio.fanevent.outbox.infrastructure.ReservationAuditOutboxHandler;
import com.portfolio.fanevent.payment.application.PaymentGateway;
import com.portfolio.fanevent.reservation.application.ReservationExpirationService;
import com.portfolio.fanevent.support.persistence.QBaseEntity;
import com.portfolio.fanevent.support.security.JwtProperties;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
		"spring.jpa.properties.hibernate.generate_statistics=true",
		"app.reservation.expiration.initial-delay=PT1H",
		"app.outbox.initial-delay=PT1H"
})
@Testcontainers
@AutoConfigureMockMvc
class FanEventPlatformApplicationTests {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRESQL =
			new PostgreSQLContainer<>("postgres:16-alpine");

	@Container
	@ServiceConnection(name = "redis")
	static final GenericContainer<?> REDIS = new GenericContainer<>(
			DockerImageName.parse("redis:7.4-alpine"))
			.withExposedPorts(6379);

	@Autowired
	private JPAQueryFactory queryFactory;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private CatalogCommandService catalogCommandService;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private JwtProperties jwtProperties;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private ReservationExpirationService expirationService;

	@Autowired
	private OutboxPublisher outboxPublisher;

	@MockitoSpyBean
	private PaymentGateway paymentGateway;

	@MockitoSpyBean
	private ReservationAuditOutboxHandler reservationAuditOutboxHandler;

	@Test
	void contextLoadsWithQuerydslAndMigratedSchema() {
		assertThat(queryFactory).isNotNull();
		assertThat(QBaseEntity.baseEntity).isNotNull();

		Integer tableCount = jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM information_schema.tables
				  WHERE table_schema = 'public'
				    AND table_name IN (
				    'members', 'artists', 'events', 'event_sessions', 'sellable_inventory',
				    'reservations', 'reservation_items', 'idempotency_requests',
				    'outbox_events', 'consumed_outbox_events', 'audit_logs'
				  )
				""", Integer.class);

		assertThat(tableCount).isEqualTo(11);
	}

	@Test
	void healthEndpointIsPublic() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void signupNormalizesEmailStoresBcryptAndRejectsDuplicates() throws Exception {
		String unique = UUID.randomUUID().toString();
		String email = unique + "@example.com";
		String rawPassword = "secure-password";

		mockMvc.perform(post("/api/auth/signup")
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"email", email.toUpperCase(),
						"password", rawPassword,
						"name", "테스트 회원"))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.role").value("USER"));

		Member saved = memberRepository.findByEmail(email).orElseThrow();
		assertThat(saved.getPasswordHash()).startsWith("$2").doesNotContain(rawPassword);
		assertThat(passwordEncoder.matches(rawPassword, saved.getPasswordHash())).isTrue();

		mockMvc.perform(post("/api/auth/signup")
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"email", email,
						"password", "another-password",
						"name", "중복 회원"))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
	}

	@Test
	void loginIssuesJwtAndUserCanReadOwnProfileButCannotUseAdminApi() throws Exception {
		String email = signupUniqueMember("일반 회원");
		String accessToken = login(email, "secure-password");

		mockMvc.perform(get("/api/members/me")
				.header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.role").value("USER"));

		mockMvc.perform(post("/api/admin/artists")
				.header("Authorization", "Bearer " + accessToken)
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "USER DENIED " + UUID.randomUUID()))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
	}

	@Test
	void adminJwtCanUseAdminApiAndInvalidCredentialsHaveSameResponse() throws Exception {
		String email = signupUniqueMember("관리자 후보");
		jdbcTemplate.update("UPDATE members SET role = 'ADMIN' WHERE email = ?", email);
		String adminToken = login(email, "secure-password");

		mockMvc.perform(post("/api/admin/artists")
				.header("Authorization", "Bearer " + adminToken)
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "JWT ADMIN " + UUID.randomUUID()))))
				.andExpect(status().isCreated());

		for (Map<String, String> credentials : List.of(
				Map.of("email", email, "password", "wrong-password"),
				Map.of("email", "missing-" + email, "password", "wrong-password"))) {
			mockMvc.perform(post("/api/auth/login")
					.contentType(APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(credentials)))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
					.andExpect(jsonPath("$.message").value("이메일 또는 비밀번호가 올바르지 않습니다."));
		}
	}

	@Test
	void expiredAndTamperedJwtAreRejected() throws Exception {
		String email = signupUniqueMember("토큰 검증 회원");
		Member member = memberRepository.findByEmail(email).orElseThrow();
		String validToken = login(email, "secure-password");
		int signatureStart = validToken.lastIndexOf('.') + 1;
		int changeIndex = signatureStart + 3;
		char replacement = validToken.charAt(changeIndex) == 'A' ? 'B' : 'A';
		String tamperedToken = validToken.substring(0, changeIndex)
				+ replacement
				+ validToken.substring(changeIndex + 1);

		mockMvc.perform(get("/api/members/me")
				.header("Authorization", "Bearer " + tamperedToken))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

		Instant now = Instant.now();
		JwtClaimsSet expiredClaims = JwtClaimsSet.builder()
				.issuer(jwtProperties.issuer())
				.issuedAt(now.minus(10, ChronoUnit.MINUTES))
				.expiresAt(now.minus(5, ChronoUnit.MINUTES))
				.subject(member.getId().toString())
				.claim("email", email)
				.claim("roles", List.of("USER"))
				.build();
		String expiredToken = jwtEncoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(MacAlgorithm.HS256).build(),
				expiredClaims)).getTokenValue();

		mockMvc.perform(get("/api/members/me")
				.header("Authorization", "Bearer " + expiredToken))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
	}

	@Test
	void authenticatedMemberCreatesPendingReservationAndInventoryIsDecremented() throws Exception {
		String email = signupUniqueMember("예약 회원");
		String accessToken = login(email, "secure-password");
		Long inventoryId = createOnSaleInventory(5, new BigDecimal("12000.00"));

		mockMvc.perform(post("/api/reservations")
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"items", List.of(Map.of(
								"inventoryId", inventoryId,
								"quantity", 2))))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.totalAmount").value(24000.00))
				.andExpect(jsonPath("$.expiresAt").exists())
				.andExpect(jsonPath("$.items[0].inventoryId").value(inventoryId))
				.andExpect(jsonPath("$.items[0].quantity").value(2))
				.andExpect(jsonPath("$.items[0].unitPrice").value(12000.00));

		Integer available = jdbcTemplate.queryForObject(
				"SELECT available_quantity FROM sellable_inventory WHERE id = ?",
				Integer.class,
				inventoryId);
		Integer reservationCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM reservations WHERE status = 'PENDING' AND total_amount = 24000.00",
				Integer.class);
		assertThat(available).isEqualTo(3);
		assertThat(reservationCount).isEqualTo(1);
	}

	@Test
	void sameIdempotencyKeyAndRequestReplaysReservationWithoutDecrementingStockAgain() throws Exception {
		String accessToken = login(signupUniqueMember("멱등 재생 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(5, new BigDecimal("12000.00"));
		String idempotencyKey = UUID.randomUUID().toString();
		String requestBody = reservationRequest(inventoryId, 2);

		Set<Long> reservationIds = new HashSet<>();
		for (int attempt = 0; attempt < 2; attempt++) {
			String responseBody = mockMvc.perform(post("/api/reservations")
					.header("Authorization", "Bearer " + accessToken)
					.header("Idempotency-Key", idempotencyKey)
					.contentType(APPLICATION_JSON)
					.content(requestBody))
					.andExpect(status().isCreated())
					.andReturn().getResponse().getContentAsString();
			reservationIds.add(responseId(responseBody));
		}

		assertThat(reservationIds).hasSize(1);
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(3);
		assertThat(reservationCountForInventory(inventoryId)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT response_body IS NOT NULL FROM idempotency_requests WHERE idempotency_key = ?",
				Boolean.class,
				idempotencyKey)).isTrue();
	}

	@Test
	void sameIdempotencyKeyWithDifferentReservationRequestIsRejected() throws Exception {
		String accessToken = login(signupUniqueMember("멱등 충돌 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(5, new BigDecimal("14000.00"));
		String idempotencyKey = UUID.randomUUID().toString();

		mockMvc.perform(post("/api/reservations")
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", idempotencyKey)
				.contentType(APPLICATION_JSON)
				.content(reservationRequest(inventoryId, 1)))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/reservations")
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", idempotencyKey)
				.contentType(APPLICATION_JSON)
				.content(reservationRequest(inventoryId, 2)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

		assertThat(inventoryQuantity(inventoryId)).isEqualTo(4);
		assertThat(reservationCountForInventory(inventoryId)).isEqualTo(1);
	}

	@Test
	void sameConfirmationKeyWithDifferentPaymentTokenIsRejected() throws Exception {
		String accessToken = login(signupUniqueMember("확정 멱등 충돌 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(3, new BigDecimal("16000.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);
		String idempotencyKey = UUID.randomUUID().toString();

		mockMvc.perform(post("/api/reservations/{reservationId}/confirm", reservationId)
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", idempotencyKey)
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("paymentToken", "mock-approved"))))
				.andExpect(status().isOk());

		mockMvc.perform(post("/api/reservations/{reservationId}/confirm", reservationId)
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", idempotencyKey)
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("paymentToken", "another-token"))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

		verify(paymentGateway, times(1)).authorize(
				eq(reservationId), eq(new BigDecimal("16000.00")), eq("mock-approved"));
	}

	@Test
	void concurrentSameIdempotencyRequestsCreateOnlyOneReservation() throws Exception {
		String accessToken = login(signupUniqueMember("동시 멱등 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(10, new BigDecimal("17000.00"));
		String idempotencyKey = UUID.randomUUID().toString();
		String requestBody = reservationRequest(inventoryId, 2);
		int requestCount = 6;
		ExecutorService executor = Executors.newFixedThreadPool(requestCount);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<String>> responses = new ArrayList<>();

		try {
			for (int request = 0; request < requestCount; request++) {
				responses.add(executor.submit(() -> {
					start.await();
					return mockMvc.perform(post("/api/reservations")
							.header("Authorization", "Bearer " + accessToken)
							.header("Idempotency-Key", idempotencyKey)
							.contentType(APPLICATION_JSON)
							.content(requestBody))
							.andExpect(status().isCreated())
							.andReturn().getResponse().getContentAsString();
				}));
			}
			start.countDown();

			Set<Long> reservationIds = new HashSet<>();
			for (Future<String> response : responses) {
				reservationIds.add(responseId(response.get(15, TimeUnit.SECONDS)));
			}
			assertThat(reservationIds).hasSize(1);
		} finally {
			executor.shutdownNow();
		}

		assertThat(inventoryQuantity(inventoryId)).isEqualTo(8);
		assertThat(reservationCountForInventory(inventoryId)).isEqualTo(1);
	}

	@Test
	void comparesStockConcurrencyStrategiesWithoutOverselling() throws Exception {
		StockConcurrencyExperiment experiment = new StockConcurrencyExperiment(
				jdbcTemplate, transactionManager);
		List<StockConcurrencyExperiment.Result> results = new ArrayList<>();

		for (StockConcurrencyExperiment.Strategy strategy
				: StockConcurrencyExperiment.Strategy.values()) {
			Long inventoryId = createOnSaleInventory(
					StockConcurrencyExperiment.INITIAL_STOCK,
					new BigDecimal("19000.00"));
			StockConcurrencyExperiment.Result result = experiment.run(strategy, inventoryId);
			results.add(result);

			assertThat(result.requests()).isEqualTo(StockConcurrencyExperiment.REQUEST_COUNT);
			assertThat(result.successes()).isEqualTo(StockConcurrencyExperiment.INITIAL_STOCK);
			assertThat(result.finalStock()).isZero();
			assertThat(result.successes() + result.finalStock())
					.isEqualTo(StockConcurrencyExperiment.INITIAL_STOCK);
		}

		results.forEach(result -> System.out.printf(
				"STOCK_CONCURRENCY_RESULT strategy=%s requests=%d successes=%d conflicts=%d "
						+ "soldOut=%d conflictRate=%.2f%% tps=%.2f p95Ms=%.2f%n",
				result.strategy(),
				result.requests(),
				result.successes(),
				result.conflicts(),
				result.soldOut(),
				result.conflictRate(),
				result.tps(),
				result.p95Millis()));
	}

	@Test
	void expiredReservationReturnsInventoryOnlyOnceAcrossRepeatedRuns() throws Exception {
		String accessToken = login(signupUniqueMember("만료 중복 실행 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(5, new BigDecimal("20000.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 2);
		makeReservationExpired(reservationId, 30);

		assertThat(expirationService.expireNextBatch(1)).isEqualTo(1);
		expirationService.expireNextBatch(1);

		assertThat(reservationStatus(reservationId)).isEqualTo("EXPIRED");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT expired_at IS NOT NULL FROM reservations WHERE id = ?",
				Boolean.class,
				reservationId)).isTrue();
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(5);
	}

	@Test
	void concurrentExpirationWorkersReturnSameReservationInventoryOnce() throws Exception {
		String accessToken = login(signupUniqueMember("만료 동시 실행 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(6, new BigDecimal("21000.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 2);
		makeReservationExpired(reservationId, 40);
		int workerCount = 6;
		ExecutorService executor = Executors.newFixedThreadPool(workerCount);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> futures = new ArrayList<>();

		try {
			for (int worker = 0; worker < workerCount; worker++) {
				futures.add(executor.submit(() -> {
					start.await();
					return expirationService.expireNextBatch(1);
				}));
			}
			start.countDown();
			for (Future<Integer> future : futures) {
				future.get(15, TimeUnit.SECONDS);
			}
		} finally {
			executor.shutdownNow();
		}

		assertThat(reservationStatus(reservationId)).isEqualTo("EXPIRED");
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(6);
	}

	@Test
	void rolledBackExpirationIsPickedUpAndCompletedOnNextRun() throws Exception {
		String accessToken = login(signupUniqueMember("만료 재실행 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(4, new BigDecimal("23000.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);
		makeReservationExpired(reservationId, 50);
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

		transactionTemplate.executeWithoutResult(status -> {
			assertThat(expirationService.expireNextBatch(1)).isEqualTo(1);
			status.setRollbackOnly();
		});

		assertThat(reservationStatus(reservationId)).isEqualTo("PENDING");
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(3);
		assertThat(outboxCount(reservationId, "RESERVATION_EXPIRED")).isZero();
		assertThat(expirationService.expireNextBatch(1)).isEqualTo(1);
		assertThat(reservationStatus(reservationId)).isEqualTo("EXPIRED");
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(4);
		assertThat(outboxCount(reservationId, "RESERVATION_EXPIRED")).isEqualTo(1);
	}

	@Test
	void outboxRedeliveryAppliesDatabaseSideEffectOnlyOnce() throws Exception {
		markExistingOutboxPublished();
		String accessToken = login(signupUniqueMember("Outbox 중복 소비 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(3, new BigDecimal("24000.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);
		confirmReservation(accessToken, reservationId);
		UUID eventId = outboxEventId(reservationId, "RESERVATION_CONFIRMED");

		assertThat(outboxPublisher.publishNextBatch()).isEqualTo(1);
		assertThat(auditCount(reservationId)).isEqualTo(1);
		assertThat(consumptionCount(eventId)).isEqualTo(1);

		jdbcTemplate.update("""
				UPDATE outbox_events
				SET status = 'PROCESSING',
				    published_at = NULL,
				    available_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
				WHERE id = ?
				""", eventId);
		assertThat(outboxPublisher.publishNextBatch()).isEqualTo(1);

		assertThat(auditCount(reservationId)).isEqualTo(1);
		assertThat(consumptionCount(eventId)).isEqualTo(1);
		assertThat(outboxStatus(eventId)).isEqualTo("PUBLISHED");
	}

	@Test
	void failedOutboxConsumerRollsBackReceiptAndSucceedsOnRetry() throws Exception {
		markExistingOutboxPublished();
		String accessToken = login(signupUniqueMember("Outbox 재시도 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(3, new BigDecimal("25000.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);
		confirmReservation(accessToken, reservationId);
		UUID eventId = outboxEventId(reservationId, "RESERVATION_CONFIRMED");
		doThrow(new IllegalStateException("테스트 소비 실패"))
				.doCallRealMethod()
				.when(reservationAuditOutboxHandler).handle(any());

		assertThat(outboxPublisher.publishNextBatch()).isZero();
		Map<String, Object> failed = jdbcTemplate.queryForMap(
				"SELECT status, attempts, last_error FROM outbox_events WHERE id = ?", eventId);
		assertThat(failed.get("status")).isEqualTo("FAILED");
		assertThat(((Number) failed.get("attempts")).intValue()).isEqualTo(1);
		assertThat(failed.get("last_error")).isEqualTo("테스트 소비 실패");
		assertThat(consumptionCount(eventId)).isZero();
		assertThat(auditCount(reservationId)).isZero();

		jdbcTemplate.update(
				"UPDATE outbox_events SET available_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?",
				eventId);
		assertThat(outboxPublisher.publishNextBatch()).isEqualTo(1);
		assertThat(outboxStatus(eventId)).isEqualTo("PUBLISHED");
		assertThat(consumptionCount(eventId)).isEqualTo(1);
		assertThat(auditCount(reservationId)).isEqualTo(1);
	}

	@Test
	void insufficientStockRollsBackAllInventoryChangesAndReservationWrites() throws Exception {
		String email = signupUniqueMember("롤백 회원");
		String accessToken = login(email, "secure-password");
		Long enoughInventoryId = createOnSaleInventory(5, new BigDecimal("10000.00"));
		Long insufficientInventoryId = createOnSaleInventory(1, new BigDecimal("20000.00"));
		Integer reservationsBefore = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM reservations", Integer.class);
		Integer itemsBefore = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM reservation_items", Integer.class);

		mockMvc.perform(post("/api/reservations")
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"items", List.of(
								Map.of("inventoryId", enoughInventoryId, "quantity", 2),
								Map.of("inventoryId", insufficientInventoryId, "quantity", 2))))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

		Map<String, Object> quantities = jdbcTemplate.queryForMap("""
				SELECT
				  max(CASE WHEN id = ? THEN available_quantity END) AS enough_quantity,
				  max(CASE WHEN id = ? THEN available_quantity END) AS insufficient_quantity
				FROM sellable_inventory
				WHERE id IN (?, ?)
				""", enoughInventoryId, insufficientInventoryId, enoughInventoryId, insufficientInventoryId);
		assertThat(((Number) quantities.get("enough_quantity")).intValue()).isEqualTo(5);
		assertThat(((Number) quantities.get("insufficient_quantity")).intValue()).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM reservations", Integer.class))
				.isEqualTo(reservationsBefore);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM reservation_items", Integer.class))
				.isEqualTo(itemsBefore);
	}

	@Test
	void reservationRequiresAuthenticationAndOnSaleEvent() throws Exception {
		Long inventoryId = createInventory(3, new BigDecimal("15000.00"), false);

		mockMvc.perform(post("/api/reservations")
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"items", List.of(Map.of("inventoryId", inventoryId, "quantity", 1))))))
				.andExpect(status().isUnauthorized());

		String email = signupUniqueMember("판매 상태 회원");
		String accessToken = login(email, "secure-password");
		mockMvc.perform(post("/api/reservations")
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"items", List.of(Map.of("inventoryId", inventoryId, "quantity", 1))))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

		assertThat(jdbcTemplate.queryForObject(
				"SELECT available_quantity FROM sellable_inventory WHERE id = ?",
				Integer.class,
				inventoryId)).isEqualTo(3);
	}

	@Test
	void approvedPaymentConfirmsReservationOnlyOnce() throws Exception {
		String accessToken = login(signupUniqueMember("확정 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(5, new BigDecimal("18000.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 2);
		String idempotencyKey = UUID.randomUUID().toString();

		for (int attempt = 0; attempt < 2; attempt++) {
			mockMvc.perform(post("/api/reservations/{reservationId}/confirm", reservationId)
					.header("Authorization", "Bearer " + accessToken)
					.header("Idempotency-Key", idempotencyKey)
					.contentType(APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of(
							"paymentToken", "mock-approved"))))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.status").value("CONFIRMED"));
		}

		verify(paymentGateway, times(1)).authorize(
				eq(reservationId), eq(new BigDecimal("36000.00")), eq("mock-approved"));
		Map<String, Object> stored = jdbcTemplate.queryForMap(
				"SELECT status, confirmed_at, version FROM reservations WHERE id = ?",
				reservationId);
		assertThat(stored.get("status")).isEqualTo("CONFIRMED");
		assertThat(stored.get("confirmed_at")).isNotNull();
		assertThat(((Number) stored.get("version")).longValue()).isEqualTo(1L);
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(3);
		assertThat(outboxCount(reservationId, "RESERVATION_CONFIRMED")).isEqualTo(1);
	}

	@Test
	void declinedPaymentKeepsPendingReservationAndHeldInventory() throws Exception {
		String accessToken = login(signupUniqueMember("결제 실패 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(4, new BigDecimal("22000.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);

		mockMvc.perform(post("/api/reservations/{reservationId}/confirm", reservationId)
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"paymentToken", "mock-declined"))))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("PAYMENT_DECLINED"));

		Map<String, Object> stored = jdbcTemplate.queryForMap(
				"SELECT status, confirmed_at, version FROM reservations WHERE id = ?",
				reservationId);
		assertThat(stored.get("status")).isEqualTo("PENDING");
		assertThat(stored.get("confirmed_at")).isNull();
		assertThat(((Number) stored.get("version")).longValue()).isZero();
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(3);
		assertThat(outboxCount(reservationId, "RESERVATION_CONFIRMED")).isZero();
	}

	@Test
	void pendingCancellationReturnsInventoryOnceWithoutRefund() throws Exception {
		String accessToken = login(signupUniqueMember("선점 취소 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(5, new BigDecimal("13000.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 2);

		cancelTwice(accessToken, reservationId);

		verify(paymentGateway, never()).refund(eq(reservationId), any(BigDecimal.class));
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(5);
		Map<String, Object> stored = jdbcTemplate.queryForMap(
				"SELECT status, cancelled_at, version FROM reservations WHERE id = ?",
				reservationId);
		assertThat(stored.get("status")).isEqualTo("CANCELLED");
		assertThat(stored.get("cancelled_at")).isNotNull();
		assertThat(((Number) stored.get("version")).longValue()).isEqualTo(1L);
		assertThat(outboxCount(reservationId, "RESERVATION_CANCELLED")).isEqualTo(1);
	}

	@Test
	void confirmedCancellationRefundsAndReturnsInventoryOnlyOnce() throws Exception {
		String accessToken = login(signupUniqueMember("확정 취소 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(5, new BigDecimal("25000.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 2);
		mockMvc.perform(post("/api/reservations/{reservationId}/confirm", reservationId)
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"paymentToken", "mock-approved"))))
				.andExpect(status().isOk());

		cancelTwice(accessToken, reservationId);

		verify(paymentGateway, times(1)).refund(
				eq(reservationId), eq(new BigDecimal("50000.00")));
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(5);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT status FROM reservations WHERE id = ?", String.class, reservationId))
				.isEqualTo("CANCELLED");
		assertThat(outboxCount(reservationId, "RESERVATION_CANCELLED")).isEqualTo(1);
	}

	@Test
	void confirmationRejectsExpiredReservationAndOtherMember() throws Exception {
		String ownerToken = login(signupUniqueMember("예약 소유자"), "secure-password");
		String otherToken = login(signupUniqueMember("다른 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(3, new BigDecimal("30000.00"));
		Long reservationId = holdReservation(ownerToken, inventoryId, 1);

		mockMvc.perform(post("/api/reservations/{reservationId}/confirm", reservationId)
				.header("Authorization", "Bearer " + otherToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"paymentToken", "mock-approved"))))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

		jdbcTemplate.update(
				"UPDATE reservations SET expires_at = ? WHERE id = ?",
				java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)),
				reservationId);
		mockMvc.perform(post("/api/reservations/{reservationId}/confirm", reservationId)
				.header("Authorization", "Bearer " + ownerToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"paymentToken", "mock-approved"))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

		verify(paymentGateway, never()).authorize(
				eq(reservationId), any(BigDecimal.class), eq("mock-approved"));
		assertThat(jdbcTemplate.queryForObject(
				"SELECT status FROM reservations WHERE id = ?", String.class, reservationId))
				.isEqualTo("PENDING");
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(2);
	}

	@Test
	@WithMockUser(roles = "USER")
	void adminCatalogApiRejectsUserRole() throws Exception {
		mockMvc.perform(post("/api/admin/artists")
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "IVE"))))
				.andExpect(status().isForbidden());
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void adminCreatesArtistEventSessionAndInventory() throws Exception {
		Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

		Long artistId = responseId(mockMvc.perform(post("/api/admin/artists")
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"name", "MONSTA X TEST",
						"description", "관리자 API 테스트"))))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString());

		Long eventId = responseId(mockMvc.perform(post("/api/admin/events")
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"artistId", artistId,
						"title", "FAN CONCERT",
						"description", "관리자 API 생성 이벤트",
						"type", "FAN_MEETING",
						"salesStartAt", now.minus(1, ChronoUnit.DAYS).toString(),
						"salesEndAt", now.plus(30, ChronoUnit.DAYS).toString()))))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString());

		Long sessionId = responseId(mockMvc.perform(post("/api/admin/event-sessions")
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"eventId", eventId,
						"name", "1회차",
						"venue", "SEOUL",
						"startsAt", now.plus(40, ChronoUnit.DAYS).toString(),
						"salesStartAt", now.minus(1, ChronoUnit.DAYS).toString(),
						"salesEndAt", now.plus(30, ChronoUnit.DAYS).toString()))))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString());

		Long inventoryId = responseId(mockMvc.perform(post("/api/admin/inventory")
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"eventSessionId", sessionId,
						"type", "GENERAL_ADMISSION",
						"name", "일반 입장권",
						"price", 55000,
						"quantity", 100))))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString());

		assertThat(inventoryId).isPositive();

		mockMvc.perform(put("/api/admin/events/{eventId}", eventId)
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"title", "FAN CONCERT UPDATED",
						"description", "수정된 이벤트",
						"type", "FAN_MEETING",
						"salesStartAt", now.minus(2, ChronoUnit.DAYS).toString(),
						"salesEndAt", now.plus(31, ChronoUnit.DAYS).toString()))))
				.andExpect(status().isNoContent());

		mockMvc.perform(put("/api/admin/event-sessions/{sessionId}", sessionId)
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"name", "1회차 수정",
						"venue", "SEOUL ARENA",
						"startsAt", now.plus(41, ChronoUnit.DAYS).toString(),
						"salesStartAt", now.minus(2, ChronoUnit.DAYS).toString(),
						"salesEndAt", now.plus(31, ChronoUnit.DAYS).toString()))))
				.andExpect(status().isNoContent());

		mockMvc.perform(put("/api/admin/inventory/{inventoryId}", inventoryId)
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"type", "GENERAL_ADMISSION",
						"name", "일반 입장권 수정",
						"price", 60000,
						"quantity", 120))))
				.andExpect(status().isNoContent());

		mockMvc.perform(patch("/api/admin/events/{eventId}/status", eventId)
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("status", "PUBLISHED"))))
				.andExpect(status().isNoContent());

		mockMvc.perform(patch("/api/admin/events/{eventId}/status", eventId)
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("status", "ON_SALE"))))
				.andExpect(status().isNoContent());

		mockMvc.perform(patch("/api/admin/events/{eventId}/status", eventId)
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("status", "ON_SALE"))))
				.andExpect(status().isNoContent());

		mockMvc.perform(put("/api/admin/inventory/{inventoryId}", inventoryId)
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"type", "GENERAL_ADMISSION",
						"name", "판매 중 변경 시도",
						"price", 65000,
						"quantity", 130))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

		mockMvc.perform(patch("/api/admin/events/{eventId}/status", eventId)
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("status", "DRAFT"))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
	}

	@Test
	void publicEventSearchUsesOnlyPublicStatusesAndDynamicKeyword() throws Exception {
		Long artistId = catalogCommandService.createArtist("STARSHIP TEST", "통합 테스트 아티스트");
		Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		Long publicEventId = catalogCommandService.createEvent(
				artistId,
				"WORLD TOUR SEOUL",
				"공개 이벤트",
				EventType.CONCERT,
				now.minus(1, ChronoUnit.DAYS),
				now.plus(30, ChronoUnit.DAYS));
		Long sessionId = catalogCommandService.createSession(
				publicEventId,
				"서울 1회차",
				"SEOUL ARENA",
				now.plus(40, ChronoUnit.DAYS),
				now.minus(1, ChronoUnit.DAYS),
				now.plus(30, ChronoUnit.DAYS));
		catalogCommandService.createInventory(
				sessionId,
				com.portfolio.fanevent.catalog.domain.InventoryType.GENERAL_ADMISSION,
				"스탠딩",
				new java.math.BigDecimal("99000.00"),
				100);
		catalogCommandService.changeEventStatus(publicEventId, EventStatus.PUBLISHED);
		catalogCommandService.createEvent(
				artistId,
				"SECRET REHEARSAL",
				"비공개 이벤트",
				EventType.CONCERT,
				now.minus(1, ChronoUnit.DAYS),
				now.plus(30, ChronoUnit.DAYS));

		SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
		sessionFactory.getStatistics().clear();

		mockMvc.perform(get("/api/events").param("keyword", "tour"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].title").value("WORLD TOUR SEOUL"))
				.andExpect(jsonPath("$.content[0].artistName").value("STARSHIP TEST"));

		assertThat(sessionFactory.getStatistics().getPrepareStatementCount()).isEqualTo(2);

		sessionFactory.getStatistics().clear();
		mockMvc.perform(get("/api/events/{eventId}", publicEventId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("WORLD TOUR SEOUL"))
				.andExpect(jsonPath("$.sessions[0].name").value("서울 1회차"))
				.andExpect(jsonPath("$.sessions[0].inventory[0].name").value("스탠딩"))
				.andExpect(jsonPath("$.sessions[0].inventory[0].availableQuantity").value(100));
		assertThat(sessionFactory.getStatistics().getPrepareStatementCount()).isEqualTo(2);

		String queryPlan = String.join("\n", jdbcTemplate.queryForList("""
				EXPLAIN (ANALYZE, BUFFERS)
				SELECT e.id, e.title, e.event_type, e.status, a.id, a.name,
				       e.sales_start_at, e.sales_end_at
				FROM events e
				JOIN artists a ON a.id = e.artist_id
				WHERE e.status IN ('PUBLISHED', 'ON_SALE')
				  AND lower(e.title) LIKE '%tour%'
				ORDER BY e.sales_start_at DESC, e.id DESC
				LIMIT 20
				""", String.class));
		assertThat(queryPlan)
				.contains("Planning Time")
				.contains("Execution Time")
				.contains("Buffers");
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void adminReservationAndInventorySearchUseStableProjectionQueries() throws Exception {
		String email = signupUniqueMember("관리자 조회 회원");
		String accessToken = login(email, "secure-password");
		Long memberId = memberRepository.findByEmail(email).orElseThrow().getId();
		Long inventoryId = createOnSaleInventory(20, new BigDecimal("27000.00"));
		Long eventId = jdbcTemplate.queryForObject("""
				SELECT session.event_id
				FROM sellable_inventory inventory
				JOIN event_sessions session ON session.id = inventory.event_session_id
				WHERE inventory.id = ?
				""", Long.class, inventoryId);

		Long firstReservationId = holdReservation(accessToken, inventoryId, 2);
		Long secondReservationId = holdReservation(accessToken, inventoryId, 3);
		confirmReservation(accessToken, firstReservationId);

		SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
		sessionFactory.getStatistics().clear();
		mockMvc.perform(get("/api/admin/reservations")
				.param("memberId", memberId.toString())
				.param("eventId", eventId.toString())
				.param("size", "10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.content[0].reservationId").value(secondReservationId))
				.andExpect(jsonPath("$.content[0].totalQuantity").value(3))
				.andExpect(jsonPath("$.content[1].reservationId").value(firstReservationId))
				.andExpect(jsonPath("$.content[1].status").value("CONFIRMED"));
		assertThat(sessionFactory.getStatistics().getPrepareStatementCount()).isEqualTo(2);

		sessionFactory.getStatistics().clear();
		mockMvc.perform(get("/api/admin/inventory")
				.param("eventId", eventId.toString())
				.param("availableQuantityLoe", "15")
				.param("soldOut", "false"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].inventoryId").value(inventoryId))
				.andExpect(jsonPath("$.content[0].availableQuantity").value(15))
				.andExpect(jsonPath("$.content[0].reservedQuantity").value(5));
		assertThat(sessionFactory.getStatistics().getPrepareStatementCount()).isEqualTo(2);

		mockMvc.perform(get("/api/admin/reservations/summary"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalReservations").isNumber())
				.andExpect(jsonPath("$.confirmedSalesAmount").isNumber())
				.andExpect(jsonPath("$.statusCounts.CONFIRMED").isNumber());
	}

	@Test
	@Transactional
	void adminReservationQueryPlanUsesStatusCreatedIndexOnLargeDataset() throws Exception {
		String email = signupUniqueMember("실행 계획 회원");
		Long memberId = memberRepository.findByEmail(email).orElseThrow().getId();
		Long inventoryId = createOnSaleInventory(10, new BigDecimal("1000.00"));

		jdbcTemplate.update("""
				INSERT INTO reservations (
				    member_id, status, total_amount, expires_at, version, created_at, updated_at)
				SELECT ?,
				       CASE WHEN sequence_number % 1000 = 0 THEN 'FAILED' ELSE 'CONFIRMED' END,
				       1000.00,
				       CURRENT_TIMESTAMP + INTERVAL '1 hour',
				       0,
				       CURRENT_TIMESTAMP - (sequence_number || ' milliseconds')::interval,
				       CURRENT_TIMESTAMP
				FROM generate_series(1, 10000) sequence_number
				""", memberId);
		jdbcTemplate.update("""
				INSERT INTO reservation_items (
				    reservation_id, inventory_id, quantity, unit_price, created_at)
				SELECT reservation.id, ?, 1, 1000.00, CURRENT_TIMESTAMP
				FROM reservations reservation
				WHERE reservation.member_id = ? AND reservation.total_amount = 1000.00
				""", inventoryId, memberId);
		jdbcTemplate.execute("ANALYZE reservations");
		jdbcTemplate.execute("ANALYZE reservation_items");

		String queryPlan = String.join("\n", jdbcTemplate.queryForList("""
				EXPLAIN (ANALYZE, BUFFERS)
				SELECT reservation.id, member.email, reservation.status,
				       reservation.total_amount, reservation.expires_at,
				       reservation.created_at, count(DISTINCT item.id), sum(item.quantity)
				FROM reservations reservation
				JOIN members member ON member.id = reservation.member_id
				JOIN reservation_items item ON item.reservation_id = reservation.id
				JOIN sellable_inventory inventory ON inventory.id = item.inventory_id
				JOIN event_sessions session ON session.id = inventory.event_session_id
				JOIN events event ON event.id = session.event_id
				WHERE reservation.status = 'FAILED'
				GROUP BY reservation.id, member.id
				ORDER BY reservation.created_at DESC, reservation.id DESC
				LIMIT 20
				""", String.class));

		assertThat(queryPlan)
				.contains("idx_reservations_status_created")
				.contains("Execution Time")
				.contains("Buffers");
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void adminReservationCursorPaginationHasNoDuplicatesAndUsesOneQueryPerPage() throws Exception {
		String email = signupUniqueMember("커서 조회 회원");
		String accessToken = login(email, "secure-password");
		Long memberId = memberRepository.findByEmail(email).orElseThrow().getId();
		Long inventoryId = createOnSaleInventory(10, new BigDecimal("19000.00"));
		Long firstId = holdReservation(accessToken, inventoryId, 1);
		Long secondId = holdReservation(accessToken, inventoryId, 1);
		Long thirdId = holdReservation(accessToken, inventoryId, 1);

		SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
		sessionFactory.getStatistics().clear();
		String firstPageBody = mockMvc.perform(get("/api/admin/reservations/cursor")
				.param("memberId", memberId.toString())
				.param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.hasNext").value(true))
				.andExpect(jsonPath("$.nextCursor").isNotEmpty())
				.andExpect(jsonPath("$.content[0].reservationId").value(thirdId))
				.andExpect(jsonPath("$.content[1].reservationId").value(secondId))
				.andReturn().getResponse().getContentAsString();
		assertThat(sessionFactory.getStatistics().getPrepareStatementCount()).isEqualTo(1);

		String nextCursor = objectMapper.readTree(firstPageBody).get("nextCursor").asText();
		sessionFactory.getStatistics().clear();
		mockMvc.perform(get("/api/admin/reservations/cursor")
				.param("memberId", memberId.toString())
				.param("size", "2")
				.param("cursor", nextCursor))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.hasNext").value(false))
				.andExpect(jsonPath("$.nextCursor").doesNotExist())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].reservationId").value(firstId));
		assertThat(sessionFactory.getStatistics().getPrepareStatementCount()).isEqualTo(1);

		mockMvc.perform(get("/api/admin/reservations/cursor")
				.param("cursor", "invalid-cursor"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	@Transactional
	void comparesDeepOffsetAndCursorPaginationOnSameDataset() throws Exception {
		String email = signupUniqueMember("커서 성능 회원");
		Long memberId = memberRepository.findByEmail(email).orElseThrow().getId();
		jdbcTemplate.update("""
				INSERT INTO reservations (
				    member_id, status, total_amount, expires_at, version, created_at, updated_at)
				SELECT ?, 'CONFIRMED', 1000.00,
				       CURRENT_TIMESTAMP + INTERVAL '1 hour', 0,
				       CURRENT_TIMESTAMP - (sequence_number || ' milliseconds')::interval,
				       CURRENT_TIMESTAMP
				FROM generate_series(1, 50000) sequence_number
				""", memberId);
		jdbcTemplate.execute("ANALYZE reservations");

		CursorPaginationExperiment.Comparison result =
				new CursorPaginationExperiment(jdbcTemplate).run(memberId);
		System.out.printf(
				"CURSOR_BENCHMARK offset[p50=%.3fms,p95=%.3fms,p99=%.3fms] "
						+ "cursor[p50=%.3fms,p95=%.3fms,p99=%.3fms]%n",
				result.offset().p50Millis(),
				result.offset().p95Millis(),
				result.offset().p99Millis(),
				result.cursor().p50Millis(),
				result.cursor().p95Millis(),
				result.cursor().p99Millis());

		assertThat(result.cursor().p50Millis()).isLessThan(result.offset().p50Millis());
		assertThat(result.cursor().p95Millis()).isLessThan(result.offset().p95Millis());
		assertThat(result.cursor().p99Millis()).isLessThan(result.offset().p99Millis());

		Map<String, Object> boundary = jdbcTemplate.queryForMap("""
				SELECT created_at, id FROM reservations
				WHERE member_id = ? AND status = 'CONFIRMED'
				ORDER BY created_at DESC, id DESC OFFSET 49000 LIMIT 1
				""", memberId);
		String offsetPlan = String.join("\n", jdbcTemplate.queryForList("""
				EXPLAIN (ANALYZE, BUFFERS)
				SELECT id, created_at FROM reservations
				WHERE member_id = ? AND status = 'CONFIRMED'
				ORDER BY created_at DESC, id DESC OFFSET 49000 LIMIT 20
				""", String.class, memberId));
		String cursorPlan = String.join("\n", jdbcTemplate.queryForList("""
				EXPLAIN (ANALYZE, BUFFERS)
				SELECT id, created_at FROM reservations
				WHERE member_id = ? AND status = 'CONFIRMED'
				  AND (created_at, id) < (?, ?)
				ORDER BY created_at DESC, id DESC LIMIT 20
				""", String.class,
				memberId,
				boundary.get("created_at"),
				boundary.get("id")));
		assertThat(offsetPlan).contains("Index Scan").contains("Execution Time").contains("Buffers");
		assertThat(cursorPlan)
				.contains("idx_reservations_member_created")
				.contains("ROW(created_at, id) <")
				.contains("Execution Time")
				.contains("Buffers");
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void publicEventDetailCacheHitsAndInvalidatesAfterCommit() throws Exception {
		String unique = UUID.randomUUID().toString();
		Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		Long artistId = catalogCommandService.createArtist("캐시 아티스트 " + unique, "캐시 테스트");
		Long eventId = catalogCommandService.createEvent(
				artistId,
				"캐시 전 제목",
				"캐시 테스트",
				EventType.CONCERT,
				now.minus(1, ChronoUnit.DAYS),
				now.plus(30, ChronoUnit.DAYS));
		Long sessionId = catalogCommandService.createSession(
				eventId,
				"캐시 회차",
				"SEOUL",
				now.plus(40, ChronoUnit.DAYS),
				now.minus(1, ChronoUnit.DAYS),
				now.plus(30, ChronoUnit.DAYS));
		catalogCommandService.createInventory(
				sessionId,
				InventoryType.GENERAL_ADMISSION,
				"캐시 좌석",
				new BigDecimal("33000.00"),
				100);
		catalogCommandService.changeEventStatus(eventId, EventStatus.PUBLISHED);

		SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
		sessionFactory.getStatistics().clear();
		mockMvc.perform(get("/api/events/{eventId}", eventId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("캐시 전 제목"));
		assertThat(sessionFactory.getStatistics().getPrepareStatementCount()).isEqualTo(2);
		assertThat(redisTemplate.getExpire("cache:event-detail:v1:" + eventId, TimeUnit.SECONDS))
				.isBetween(1L, 300L);

		sessionFactory.getStatistics().clear();
		mockMvc.perform(get("/api/events/{eventId}", eventId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("캐시 전 제목"));
		assertThat(sessionFactory.getStatistics().getPrepareStatementCount()).isZero();

		catalogCommandService.updateEvent(
				eventId,
				"캐시 무효화 후 제목",
				"캐시 테스트",
				EventType.CONCERT,
				now.minus(1, ChronoUnit.DAYS),
				now.plus(30, ChronoUnit.DAYS));
		assertThat(redisTemplate.hasKey("cache:event-detail:v1:" + eventId)).isFalse();
		sessionFactory.getStatistics().clear();
		mockMvc.perform(get("/api/events/{eventId}", eventId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("캐시 무효화 후 제목"));
		assertThat(sessionFactory.getStatistics().getPrepareStatementCount()).isEqualTo(2);
	}

	@Test
	void reservationHoldRateLimitReturns429WithRetryAfter() throws Exception {
		String email = signupUniqueMember("속도 제한 회원");
		String accessToken = login(email, "secure-password");
		Long inventoryId = createOnSaleInventory(30, new BigDecimal("12000.00"));

		for (int request = 0; request < 20; request++) {
			mockMvc.perform(post("/api/reservations")
					.header("Authorization", "Bearer " + accessToken)
					.header("Idempotency-Key", UUID.randomUUID().toString())
					.contentType(APPLICATION_JSON)
					.content(reservationRequest(inventoryId, 1)))
					.andExpect(status().isCreated());
		}

		mockMvc.perform(post("/api/reservations")
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(APPLICATION_JSON)
				.content(reservationRequest(inventoryId, 1)))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(jsonPath("$.code").value("RESERVATION_RATE_LIMITED"));

		assertThat(inventoryQuantity(inventoryId)).isEqualTo(10);
		assertThat(reservationCountForInventory(inventoryId)).isEqualTo(20);
	}

	private Long responseId(String responseBody) throws Exception {
		return objectMapper.readTree(responseBody).get("id").asLong();
	}

	private String signupUniqueMember(String name) throws Exception {
		String email = UUID.randomUUID() + "@example.com";
		mockMvc.perform(post("/api/auth/signup")
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"email", email,
						"password", "secure-password",
						"name", name))))
				.andExpect(status().isCreated());
		return email;
	}

	private String login(String email, String password) throws Exception {
		String responseBody = mockMvc.perform(post("/api/auth/login")
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"email", email,
						"password", password))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").value(1800))
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(responseBody).get("accessToken").asText();
	}

	private Long createOnSaleInventory(int quantity, BigDecimal price) {
		return createInventory(quantity, price, true);
	}

	private Long createInventory(int quantity, BigDecimal price, boolean onSale) {
		String unique = UUID.randomUUID().toString();
		Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		Long artistId = catalogCommandService.createArtist("예약 아티스트 " + unique, "예약 테스트");
		Long eventId = catalogCommandService.createEvent(
				artistId,
				"예약 이벤트 " + unique,
				"예약 통합 테스트",
				EventType.CONCERT,
				now.minus(1, ChronoUnit.DAYS),
				now.plus(30, ChronoUnit.DAYS));
		Long sessionId = catalogCommandService.createSession(
				eventId,
				"1회차",
				"SEOUL ARENA",
				now.plus(40, ChronoUnit.DAYS),
				now.minus(1, ChronoUnit.DAYS),
				now.plus(30, ChronoUnit.DAYS));
		Long inventoryId = catalogCommandService.createInventory(
				sessionId,
				InventoryType.GENERAL_ADMISSION,
				"일반 입장권",
				price,
				quantity);
		catalogCommandService.changeEventStatus(eventId, EventStatus.PUBLISHED);
		if (onSale) {
			catalogCommandService.changeEventStatus(eventId, EventStatus.ON_SALE);
		}
		return inventoryId;
	}

	private Long holdReservation(String accessToken, Long inventoryId, int quantity) throws Exception {
		String responseBody = mockMvc.perform(post("/api/reservations")
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"items", List.of(Map.of(
								"inventoryId", inventoryId,
								"quantity", quantity))))))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return responseId(responseBody);
	}

	private void confirmReservation(String accessToken, Long reservationId) throws Exception {
		mockMvc.perform(post("/api/reservations/{reservationId}/confirm", reservationId)
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("paymentToken", "mock-approved"))))
				.andExpect(status().isOk());
	}

	private String reservationRequest(Long inventoryId, int quantity) throws Exception {
		return objectMapper.writeValueAsString(Map.of(
				"items", List.of(Map.of(
						"inventoryId", inventoryId,
						"quantity", quantity))));
	}

	private int reservationCountForInventory(Long inventoryId) {
		return jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM reservation_items
				WHERE inventory_id = ?
				""", Integer.class, inventoryId);
	}

	private void cancelTwice(String accessToken, Long reservationId) throws Exception {
		for (int attempt = 0; attempt < 2; attempt++) {
			mockMvc.perform(post("/api/reservations/{reservationId}/cancel", reservationId)
					.header("Authorization", "Bearer " + accessToken))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.status").value("CANCELLED"));
		}
	}

	private int inventoryQuantity(Long inventoryId) {
		return jdbcTemplate.queryForObject(
				"SELECT available_quantity FROM sellable_inventory WHERE id = ?",
				Integer.class,
				inventoryId);
	}

	private void makeReservationExpired(Long reservationId, int daysAgo) {
		jdbcTemplate.update(
				"UPDATE reservations SET expires_at = ? WHERE id = ?",
				java.sql.Timestamp.from(Instant.now().minus(daysAgo, ChronoUnit.DAYS)),
				reservationId);
	}

	private String reservationStatus(Long reservationId) {
		return jdbcTemplate.queryForObject(
				"SELECT status FROM reservations WHERE id = ?",
				String.class,
				reservationId);
	}

	private int outboxCount(Long reservationId, String eventType) {
		return jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM outbox_events
				WHERE aggregate_type = 'RESERVATION'
				  AND aggregate_id = ?
				  AND event_type = ?
				""", Integer.class, reservationId.toString(), eventType);
	}

	private UUID outboxEventId(Long reservationId, String eventType) {
		return jdbcTemplate.queryForObject("""
				SELECT id
				FROM outbox_events
				WHERE aggregate_id = ? AND event_type = ?
				ORDER BY created_at DESC
				LIMIT 1
				""", UUID.class, reservationId.toString(), eventType);
	}

	private String outboxStatus(UUID eventId) {
		return jdbcTemplate.queryForObject(
				"SELECT status FROM outbox_events WHERE id = ?", String.class, eventId);
	}

	private int consumptionCount(UUID eventId) {
		return jdbcTemplate.queryForObject(
				"SELECT count(*) FROM consumed_outbox_events WHERE event_id = ?",
				Integer.class,
				eventId);
	}

	private int auditCount(Long reservationId) {
		return jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM audit_logs
				WHERE action = 'RESERVATION_CONFIRMED'
				  AND target_id = ?
				  AND details ->> 'reservationId' = ?
				""", Integer.class, reservationId.toString(), reservationId.toString());
	}

	private void markExistingOutboxPublished() {
		jdbcTemplate.update("""
				UPDATE outbox_events
				SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP
				WHERE status <> 'PUBLISHED'
				""");
	}

}
