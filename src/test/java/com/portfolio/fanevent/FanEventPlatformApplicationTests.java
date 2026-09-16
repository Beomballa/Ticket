package com.portfolio.fanevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.hamcrest.Matchers.containsString;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.fanevent.admin.application.AdminOutboxService;
import com.portfolio.fanevent.admin.application.OutboxManualRetryRejectedException;
import com.portfolio.fanevent.catalog.application.CatalogCommandService;
import com.portfolio.fanevent.catalog.domain.EventStatus;
import com.portfolio.fanevent.catalog.domain.EventType;
import com.portfolio.fanevent.catalog.domain.InventoryType;
import com.portfolio.fanevent.member.domain.Member;
import com.portfolio.fanevent.member.infrastructure.MemberRepository;
import com.portfolio.fanevent.outbox.application.OutboxPublisher;
import com.portfolio.fanevent.outbox.infrastructure.ReservationAuditOutboxHandler;
import com.portfolio.fanevent.payment.application.PaymentGateway;
import com.portfolio.fanevent.payment.application.PaymentAttemptService;
import com.portfolio.fanevent.payment.application.RefundAttemptService;
import com.portfolio.fanevent.payment.application.AutomaticReconciliationService;
import com.portfolio.fanevent.payment.application.ReconciliationCandidate;
import com.portfolio.fanevent.payment.infrastructure.ReconciliationLeaseRepository;
import com.portfolio.fanevent.payment.webhook.PaymentWebhookInboxStore;
import com.portfolio.fanevent.payment.webhook.PaymentWebhookResultRecorder;
import com.portfolio.fanevent.payment.application.RefundGatewayResult;
import com.portfolio.fanevent.idempotency.application.IdempotencyInProgressException;
import com.portfolio.fanevent.reservation.application.ReservationExpirationService;
import com.portfolio.fanevent.support.observability.ReconciliationBacklogMonitor;
import com.portfolio.fanevent.support.persistence.QBaseEntity;
import com.portfolio.fanevent.support.security.JwtProperties;
import com.portfolio.fanevent.waitingroom.AdmissionTokenException;
import com.portfolio.fanevent.waitingroom.WaitingRoomService;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
		"spring.jpa.properties.hibernate.generate_statistics=true",
		"app.reservation.expiration.initial-delay=PT1H",
		"app.outbox.initial-delay=PT1H",
		"app.payment.reconciliation.initial-delay=PT1H",
		"app.waiting-room.initial-delay=PT1H"
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

	@Autowired
	private AdminOutboxService adminOutboxService;

	@Autowired
	private AutomaticReconciliationService automaticReconciliationService;

	@Autowired
	private ReconciliationBacklogMonitor reconciliationBacklogMonitor;

	@Autowired
	private ReconciliationLeaseRepository reconciliationLeaseRepository;

	@Autowired
	private PaymentWebhookInboxStore paymentWebhookInboxStore;

	@Autowired
	private PaymentWebhookResultRecorder paymentWebhookResultRecorder;

	@Autowired
	private PaymentAttemptService paymentAttemptService;

	@Autowired
	private RefundAttemptService refundAttemptService;

	@Autowired
	private WaitingRoomService waitingRoomService;

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
				    'outbox_events', 'consumed_outbox_events', 'audit_logs',
				    'payment_attempts', 'refund_attempts', 'payment_webhook_inbox',
				    'event_waiting_room_policies'
				  )
				""", Integer.class);

		assertThat(tableCount).isEqualTo(15);
	}

	@Test
	void healthEndpointIsPublic() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void openApiContractSeparatesAudienceAndDocumentsJwtSecurity() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
				.andExpect(jsonPath("$.info.title").value("StagePass API"))
				.andExpect(jsonPath("$.info.version").value("v1"))
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
				.andExpect(jsonPath("$.components.schemas.ApiError.properties.traceId").exists())
				.andExpect(jsonPath("$.components.responses.ApiError.content['application/json'].schema['$ref']")
						.value("#/components/schemas/ApiError"))
				.andExpect(jsonPath("$.components.responses.BadRequest.content['application/json'].examples.VALIDATION_ERROR.value.code")
						.value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.components.responses.Unauthorized.content['application/json'].examples.AUTHENTICATION_REQUIRED.value.code")
						.value("AUTHENTICATION_REQUIRED"))
				.andExpect(jsonPath("$.components.responses.Conflict.content['application/json'].examples.INSUFFICIENT_STOCK.value.code")
						.value("INSUFFICIENT_STOCK"))
				.andExpect(jsonPath("$.components.responses.Conflict.content['application/json'].examples.PAYMENT_RESULT_UNKNOWN.value.code")
						.value("PAYMENT_RESULT_UNKNOWN"))
				.andExpect(jsonPath("$.components.responses.Conflict.content['application/json'].examples.REFUND_RESULT_UNKNOWN.value.code")
						.value("REFUND_RESULT_UNKNOWN"))
				.andExpect(jsonPath("$.components.responses.UnprocessableEntity.content['application/json'].examples.REFUND_DECLINED.value.code")
						.value("REFUND_DECLINED"))
				.andExpect(jsonPath("$.components.responses.TooManyRequests.headers.Retry-After.example").value(60))
				.andExpect(jsonPath("$.paths['/api/events']").exists())
				.andExpect(jsonPath("$.paths['/api/reservations']").exists())
				.andExpect(jsonPath("$.paths['/api/admin/reservations']").exists())
				.andExpect(jsonPath("$.paths['/api/events'].get.responses.default['$ref']")
						.value("#/components/responses/ApiError"))
				.andExpect(jsonPath("$.paths['/api/events/{eventId}'].get.responses['404']['$ref']")
						.value("#/components/responses/NotFound"))
				.andExpect(jsonPath("$.paths['/api/reservations'].post.responses['409']['$ref']")
						.value("#/components/responses/Conflict"))
				.andExpect(jsonPath("$.paths['/api/reservations'].post.responses['429']['$ref']")
						.value("#/components/responses/TooManyRequests"))
				.andExpect(jsonPath("$.paths['/api/reservations/{reservationId}/confirm'].post.responses['422']['$ref']")
						.value("#/components/responses/UnprocessableEntity"))
				.andExpect(jsonPath("$.paths['/api/reservations/{reservationId}/cancel'].post.responses['422']['$ref']")
						.value("#/components/responses/UnprocessableEntity"))
				.andExpect(jsonPath("$.paths['/api/admin/reservations'].get.responses['403']['$ref']")
						.value("#/components/responses/Forbidden"))
				.andExpect(jsonPath("$.paths['/api/admin/payment-attempts/unknown'].get").exists())
				.andExpect(jsonPath("$.paths['/api/admin/payment-attempts/{paymentAttemptId}/reconcile'].post.responses['409']['$ref']")
						.value("#/components/responses/Conflict"))
				.andExpect(jsonPath("$.paths['/api/admin/refund-attempts/unknown'].get").exists())
				.andExpect(jsonPath("$.paths['/api/admin/refund-attempts/{refundAttemptId}/reconcile'].post.responses['409']['$ref']")
						.value("#/components/responses/Conflict"))
				.andExpect(jsonPath("$.paths['/api/payment/webhooks/mock'].post.responses['401']['$ref']")
						.value("#/components/responses/Unauthorized"))
				.andExpect(jsonPath("$.paths['/api/payment/webhooks/mock'].post.responses['409']['$ref']")
						.value("#/components/responses/Conflict"))
				.andExpect(jsonPath("$.paths['/api/admin/payment-webhooks'].get").exists())
				.andExpect(jsonPath("$.paths['/api/admin/payment-webhooks/{eventId}/retry'].post").exists());

		mockMvc.perform(get("/v3/api-docs/admin"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/admin/payment-compensations'].get").exists())
				.andExpect(jsonPath("$.paths['/api/admin/payment-compensations/{attemptId}/retry'].post.responses['409']['$ref']")
						.value("#/components/responses/Conflict"));

		mockMvc.perform(get("/v3/api-docs/public"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/auth/login'].post.security").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/events']").exists())
				.andExpect(jsonPath("$.paths['/api/payment/webhooks/mock'].post.security").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/reservations']").doesNotExist());

		mockMvc.perform(get("/v3/api-docs/member"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/members/me'].get.security[0].bearerAuth").exists())
				.andExpect(jsonPath("$.paths['/api/reservations'].get.security[0].bearerAuth").exists())
				.andExpect(jsonPath("$.paths['/api/admin/reservations']").doesNotExist());

		mockMvc.perform(get("/v3/api-docs/admin"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/admin/reservations'].get.security[0].bearerAuth").exists())
				.andExpect(jsonPath("$.paths['/api/events']").doesNotExist());

		mockMvc.perform(get("/swagger-ui.html"))
				.andExpect(status().is3xxRedirection())
				.andExpect(header().string("Location", containsString("/swagger-ui/index.html")));
	}

	@Test
	void traceIdIsReturnedAndIncludedInApiErrors() throws Exception {
		mockMvc.perform(get("/api/events/{eventId}", Long.MAX_VALUE)
				.header("X-Request-Id", "trace-test-123"))
				.andExpect(status().isNotFound())
				.andExpect(header().string("X-Request-Id", "trace-test-123"))
				.andExpect(jsonPath("$.traceId").value("trace-test-123"));
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
				eq(reservationId), eq(new BigDecimal("16000.00")), eq("mock-approved"), anyString());
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
	@WithMockUser(username = "admin-test", roles = "ADMIN")
	void adminSearchesAndSafelyRetriesExhaustedOutboxFailure() throws Exception {
		markExistingOutboxPublished();
		String accessToken = login(signupUniqueMember("Outbox 관리자 재처리 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(3, new BigDecimal("24500.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);
		confirmReservation(accessToken, reservationId);
		UUID eventId = outboxEventId(reservationId, "RESERVATION_CONFIRMED");
		jdbcTemplate.update("""
				UPDATE outbox_events
				SET status = 'FAILED', attempts = 5,
				    last_error = 'broker unavailable',
				    available_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'
				WHERE id = ?
				""", eventId);

		SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
		sessionFactory.getStatistics().clear();
		mockMvc.perform(get("/api/admin/outbox-events")
				.param("status", "FAILED")
				.param("eventType", "RESERVATION_CONFIRMED")
				.param("aggregateId", reservationId.toString())
				.param("attemptsGoe", "5"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].eventId").value(eventId.toString()))
				.andExpect(jsonPath("$.content[0].attempts").value(5))
				.andExpect(jsonPath("$.content[0].lastError").value("broker unavailable"));
		assertThat(sessionFactory.getStatistics().getPrepareStatementCount()).isEqualTo(2);
		mockMvc.perform(get("/api/admin/outbox-events/exhausted"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].eventId").value(eventId.toString()));

		mockMvc.perform(post("/api/admin/outbox-events/{eventId}/retry", eventId))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.eventId").value(eventId.toString()))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.previousAttempts").value(5));

		Map<String, Object> reset = jdbcTemplate.queryForMap("""
				SELECT status, attempts, last_error
				FROM outbox_events
				WHERE id = ?
				""", eventId);
		assertThat(reset.get("status")).isEqualTo("PENDING");
		assertThat(reset.get("attempts")).isEqualTo(0);
		assertThat(reset.get("last_error")).isNull();
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*) FROM audit_logs
				WHERE action = 'OUTBOX_MANUAL_RETRY' AND target_id = ?
				""", Integer.class, eventId.toString())).isEqualTo(1);

		mockMvc.perform(post("/api/admin/outbox-events/{eventId}/retry", eventId))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("OUTBOX_RETRY_REJECTED"));
		mockMvc.perform(get("/actuator/prometheus"))
				.andExpect(content().string(containsString("fan_event_outbox_manual_retry_total")));
	}

	@Test
	void concurrentOutboxManualRetryAcceptsOnlyOneRequest() throws Exception {
		markExistingOutboxPublished();
		String accessToken = login(signupUniqueMember("Outbox 동시 재처리 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(3, new BigDecimal("24600.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);
		confirmReservation(accessToken, reservationId);
		UUID eventId = outboxEventId(reservationId, "RESERVATION_CONFIRMED");
		jdbcTemplate.update("""
				UPDATE outbox_events
				SET status = 'FAILED', attempts = 5,
				    last_error = 'broker unavailable'
				WHERE id = ?
				""", eventId);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);

		try {
			List<Future<Boolean>> results = List.of(
					executor.submit(() -> retryOutboxAfter(start, eventId)),
					executor.submit(() -> retryOutboxAfter(start, eventId)));
			start.countDown();
			int accepted = 0;
			for (Future<Boolean> result : results) {
				if (result.get(10, TimeUnit.SECONDS)) {
					accepted++;
				}
			}
			assertThat(accepted).isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}

		assertThat(jdbcTemplate.queryForObject(
				"SELECT status FROM outbox_events WHERE id = ?", String.class, eventId))
				.isEqualTo("PENDING");
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*) FROM audit_logs
				WHERE action = 'OUTBOX_MANUAL_RETRY' AND target_id = ?
				""", Integer.class, eventId.toString())).isEqualTo(1);
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
				eq(reservationId), eq(new BigDecimal("36000.00")), eq("mock-approved"), anyString());
		Map<String, Object> stored = jdbcTemplate.queryForMap(
				"SELECT status, confirmed_at, version FROM reservations WHERE id = ?",
				reservationId);
		assertThat(stored.get("status")).isEqualTo("CONFIRMED");
		assertThat(stored.get("confirmed_at")).isNotNull();
		assertThat(((Number) stored.get("version")).longValue()).isEqualTo(1L);
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(3);
		assertThat(outboxCount(reservationId, "RESERVATION_CONFIRMED")).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT status FROM payment_attempts WHERE reservation_id = ?
				""", String.class, reservationId)).isEqualTo("APPROVED");
	}

	@Test
	void paymentGatewayAuthorizationRunsWithoutHoldingDatabaseTransaction() throws Exception {
		String accessToken = login(signupUniqueMember("결제 경계 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(2, new BigDecimal("18100.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);
		AtomicBoolean transactionActiveAtGateway = new AtomicBoolean(true);

		doAnswer(invocation -> {
			transactionActiveAtGateway.set(
					TransactionSynchronizationManager.isActualTransactionActive());
			return invocation.callRealMethod();
		}).when(paymentGateway).authorize(
				eq(reservationId),
				eq(new BigDecimal("18100.00")),
				eq("mock-approved"),
				anyString());

		mockMvc.perform(post("/api/reservations/{reservationId}/confirm", reservationId)
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"paymentToken", "mock-approved"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"));

		assertThat(transactionActiveAtGateway).isFalse();
		assertThat(jdbcTemplate.queryForObject("""
				SELECT status FROM payment_attempts WHERE reservation_id = ?
				""", String.class, reservationId)).isEqualTo("APPROVED");
	}

	@Test
	void concurrentPaymentAttemptCreationIsAtomicAndKeepsFreshRequestInProgress() throws Exception {
		String accessToken = login(signupUniqueMember("결제 원장 경쟁 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(2, new BigDecimal("18200.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);
		String gatewayKey = "concurrent-payment-" + UUID.randomUUID();
		String tokenFingerprint = "a".repeat(64);
		int workers = 6;
		ExecutorService executor = Executors.newFixedThreadPool(workers);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Boolean>> attempts = new ArrayList<>();

		try {
			for (int worker = 0; worker < workers; worker++) {
				attempts.add(executor.submit(() -> {
					start.await();
					try {
						paymentAttemptService.begin(
								reservationId,
								gatewayKey,
								tokenFingerprint,
								new BigDecimal("18200.00"));
						return true;
					} catch (IdempotencyInProgressException exception) {
						return false;
					}
				}));
			}
			start.countDown();
			int owners = 0;
			for (Future<Boolean> attempt : attempts) {
				if (attempt.get(10, TimeUnit.SECONDS)) owners++;
			}
			assertThat(owners).isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}

		Map<String, Object> stored = jdbcTemplate.queryForMap("""
				SELECT status, requested_at, next_reconciliation_at
				FROM payment_attempts WHERE reservation_id = ?
				""", reservationId);
		assertThat(stored.get("status")).isEqualTo("REQUESTED");
		assertThat(stored.get("next_reconciliation_at")).isNull();
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*) FROM payment_attempts WHERE reservation_id = ?
				""", Integer.class, reservationId)).isEqualTo(1);
		mockMvc.perform(get("/actuator/prometheus"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(
						"fan_event_payment_authorization_claim_total")))
				.andExpect(content().string(containsString("result=\"in_progress\"")));
		jdbcTemplate.update(
				"DELETE FROM payment_attempts WHERE reservation_id = ?",
				reservationId);
	}

	@Test
	void staleRequestedPaymentAttemptBecomesUnknownForReconciliation() throws Exception {
		String accessToken = login(signupUniqueMember("결제 원장 복구 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(2, new BigDecimal("18300.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);
		String gatewayKey = "stale-payment-" + UUID.randomUUID();
		String tokenFingerprint = "b".repeat(64);

		paymentAttemptService.begin(
				reservationId,
				gatewayKey,
				tokenFingerprint,
				new BigDecimal("18300.00"));
		jdbcTemplate.update("""
				UPDATE payment_attempts
				SET requested_at = ?, updated_at = ?
				WHERE reservation_id = ?
				""",
				java.sql.Timestamp.from(Instant.now().minus(31, ChronoUnit.SECONDS)),
				java.sql.Timestamp.from(Instant.now().minus(31, ChronoUnit.SECONDS)),
				reservationId);

		assertThat(paymentAttemptService.begin(
				reservationId,
				gatewayKey,
				tokenFingerprint,
				new BigDecimal("18300.00")).getStatus().name()).isEqualTo("UNKNOWN");
		Map<String, Object> recovered = jdbcTemplate.queryForMap("""
				SELECT status, next_reconciliation_at FROM payment_attempts WHERE reservation_id = ?
				""", reservationId);
		assertThat(recovered.get("status")).isEqualTo("UNKNOWN");
		assertThat(recovered.get("next_reconciliation_at")).isNotNull();
		jdbcTemplate.update(
				"DELETE FROM payment_attempts WHERE reservation_id = ?",
				reservationId);
	}

	@Test
	void concurrentDifferentKeysStillCreateOnlyOneUnresolvedPaymentAttempt() throws Exception {
		String accessToken = login(signupUniqueMember("결제 원장 예약 유일성 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(2, new BigDecimal("18350.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<Boolean> first = executor.submit(() -> beginPaymentAttempt(
					start, reservationId, "different-a-" + UUID.randomUUID(), "c".repeat(64), "18350.00"));
			Future<Boolean> second = executor.submit(() -> beginPaymentAttempt(
					start, reservationId, "different-b-" + UUID.randomUUID(), "d".repeat(64), "18350.00"));
			start.countDown();
			assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(true, false);
		} finally {
			executor.shutdownNow();
		}

		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*) FROM payment_attempts
				WHERE reservation_id = ? AND status IN ('REQUESTED', 'UNKNOWN')
				""", Integer.class, reservationId)).isEqualTo(1);
		jdbcTemplate.update(
				"DELETE FROM payment_attempts WHERE reservation_id = ?",
				reservationId);
	}

	@Test
	void concurrentSameConfirmationReturnsInProgressWithoutDuplicatingAuthorization() throws Exception {
		String accessToken = login(signupUniqueMember("동시 결제 확정 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(2, new BigDecimal("18400.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);
		String idempotencyKey = UUID.randomUUID().toString();
		CountDownLatch gatewayEntered = new CountDownLatch(1);
		CountDownLatch releaseGateway = new CountDownLatch(1);

		doAnswer(invocation -> {
			gatewayEntered.countDown();
			if (!releaseGateway.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("동시 확정 테스트의 PG 대기가 시간 안에 해제되지 않았습니다.");
			}
			return invocation.callRealMethod();
		}).when(paymentGateway).authorize(
				eq(reservationId),
				eq(new BigDecimal("18400.00")),
				eq("mock-approved"),
				anyString());

		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<String> first = executor.submit(() -> mockMvc.perform(
					post("/api/reservations/{reservationId}/confirm", reservationId)
							.header("Authorization", "Bearer " + accessToken)
							.header("Idempotency-Key", idempotencyKey)
							.contentType(APPLICATION_JSON)
							.content(objectMapper.writeValueAsString(Map.of(
									"paymentToken", "mock-approved"))))
					.andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString());

			assertThat(gatewayEntered.await(10, TimeUnit.SECONDS)).isTrue();
			mockMvc.perform(post("/api/reservations/{reservationId}/confirm", reservationId)
					.header("Authorization", "Bearer " + accessToken)
					.header("Idempotency-Key", idempotencyKey)
					.contentType(APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of(
							"paymentToken", "mock-approved"))))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("IDEMPOTENCY_REQUEST_IN_PROGRESS"));
			releaseGateway.countDown();
			assertThat(objectMapper.readTree(first.get(10, TimeUnit.SECONDS)).get("status").asText())
					.isEqualTo("CONFIRMED");
		} finally {
			releaseGateway.countDown();
			executor.shutdownNow();
		}

		verify(paymentGateway, times(1)).authorize(
				eq(reservationId),
				eq(new BigDecimal("18400.00")),
				eq("mock-approved"),
				anyString());
		assertThat(outboxCount(reservationId, "RESERVATION_CONFIRMED")).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*) FROM payment_attempts WHERE reservation_id = ?
				""", Integer.class, reservationId)).isEqualTo(1);
	}

	private boolean beginPaymentAttempt(
			CountDownLatch start,
			Long reservationId,
			String gatewayKey,
			String tokenFingerprint,
			String amount
	) throws InterruptedException {
		start.await();
		try {
			paymentAttemptService.begin(
					reservationId,
					gatewayKey,
					tokenFingerprint,
					new BigDecimal(amount));
			return true;
		} catch (IdempotencyInProgressException exception) {
			return false;
		}
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
		assertThat(jdbcTemplate.queryForObject("""
				SELECT status FROM payment_attempts WHERE reservation_id = ?
				""", String.class, reservationId)).isEqualTo("DECLINED");
	}

	@Test
	@WithMockUser(username = "admin-test", roles = "ADMIN")
	void unknownPaymentIsReconciledWithoutDuplicateAuthorization() throws Exception {
		String accessToken = login(signupUniqueMember("결제 대사 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(3, new BigDecimal("27000.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);

		mockMvc.perform(post("/api/reservations/{reservationId}/confirm", reservationId)
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"paymentToken", "mock-timeout-approved"))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PAYMENT_RESULT_UNKNOWN"))
				.andExpect(jsonPath("$.details[0]").value(containsString("paymentAttemptId")));

		Map<String, Object> attempt = jdbcTemplate.queryForMap("""
				SELECT id, status, gateway_idempotency_key, payment_token_fingerprint
				FROM payment_attempts
				WHERE reservation_id = ?
				""", reservationId);
		UUID attemptId = (UUID) attempt.get("id");
		assertThat(attempt.get("status")).isEqualTo("UNKNOWN");
		assertThat(attempt.get("gateway_idempotency_key").toString())
				.doesNotContain("mock-timeout-approved");
		assertThat(attempt.get("payment_token_fingerprint").toString())
				.doesNotContain("mock-timeout-approved");
		assertThat(reservationStatus(reservationId)).isEqualTo("PENDING");

		mockMvc.perform(post("/api/reservations/{reservationId}/confirm", reservationId)
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"paymentToken", "mock-approved"))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PAYMENT_RESULT_UNKNOWN"));
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*) FROM payment_attempts WHERE reservation_id = ?
				""", Integer.class, reservationId)).isEqualTo(1);

		mockMvc.perform(get("/api/admin/payment-attempts/unknown"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].paymentAttemptId").value(attemptId.toString()))
				.andExpect(jsonPath("$.content[0].status").value("UNKNOWN"))
				.andExpect(jsonPath("$.content[0].reconciliationAttempts").value(0))
				.andExpect(jsonPath("$.content[0].nextReconciliationAt").isNotEmpty());

		for (int reconciliation = 0; reconciliation < 2; reconciliation++) {
			mockMvc.perform(post(
					"/api/admin/payment-attempts/{paymentAttemptId}/reconcile", attemptId))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.paymentStatus").value("APPROVED"))
					.andExpect(jsonPath("$.reservationStatus").value("CONFIRMED"))
					.andExpect(jsonPath("$.resolved").value(true));
		}

		verify(paymentGateway, times(1)).authorize(
				eq(reservationId),
				eq(new BigDecimal("27000.00")),
				eq("mock-timeout-approved"),
				anyString());
		verify(paymentGateway, never()).authorize(
				eq(reservationId), any(BigDecimal.class), eq("mock-approved"), anyString());
		assertThat(outboxCount(reservationId, "RESERVATION_CONFIRMED")).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*) FROM audit_logs
				WHERE action = 'PAYMENT_RECONCILED' AND target_id = ?
				""", Integer.class, attemptId.toString())).isEqualTo(1);
		mockMvc.perform(get("/actuator/prometheus"))
				.andExpect(content().string(containsString(
						"fan_event_payment_reconciliation_total")));
	}

	@Test
	@WithMockUser(username = "admin-test", roles = "ADMIN")
	void unknownDeclinedPaymentKeepsReservationPendingAfterReconciliation() throws Exception {
		String accessToken = login(signupUniqueMember("결제 거절 대사 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(2, new BigDecimal("28000.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);

		mockMvc.perform(post("/api/reservations/{reservationId}/confirm", reservationId)
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"paymentToken", "mock-timeout-declined"))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PAYMENT_RESULT_UNKNOWN"));

		UUID attemptId = jdbcTemplate.queryForObject("""
				SELECT id FROM payment_attempts WHERE reservation_id = ?
				""", UUID.class, reservationId);
		mockMvc.perform(post(
				"/api/admin/payment-attempts/{paymentAttemptId}/reconcile", attemptId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paymentStatus").value("DECLINED"))
				.andExpect(jsonPath("$.reservationStatus").value("PENDING"))
				.andExpect(jsonPath("$.resolved").value(true));

		assertThat(reservationStatus(reservationId)).isEqualTo("PENDING");
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(1);
		assertThat(outboxCount(reservationId, "RESERVATION_CONFIRMED")).isZero();
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*) FROM audit_logs
				WHERE action = 'PAYMENT_RECONCILED' AND target_id = ?
				""", Integer.class, attemptId.toString())).isEqualTo(1);
	}

	@Test
	void automaticPaymentReconciliationIsClaimedOnceAcrossConcurrentWorkers() throws Exception {
		String accessToken = login(signupUniqueMember("자동 결제 대사 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(2, new BigDecimal("29000.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);

		mockMvc.perform(post("/api/reservations/{reservationId}/confirm", reservationId)
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"paymentToken", "mock-timeout-approved"))))
				.andExpect(status().isConflict());

		UUID attemptId = jdbcTemplate.queryForObject("""
				SELECT id FROM payment_attempts WHERE reservation_id = ?
				""", UUID.class, reservationId);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Future<Integer> first = executor.submit(() -> {
				start.await();
				return automaticReconciliationService.reconcileNextBatch();
			});
			Future<Integer> second = executor.submit(() -> {
				start.await();
				return automaticReconciliationService.reconcileNextBatch();
			});
			start.countDown();
			assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS))
					.isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}

		assertThat(reservationStatus(reservationId)).isEqualTo("CONFIRMED");
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(1);
		assertThat(outboxCount(reservationId, "RESERVATION_CONFIRMED")).isEqualTo(1);
		Map<String, Object> attempt = jdbcTemplate.queryForMap("""
				SELECT status, reconciliation_attempts, reconciliation_lease_until,
				       next_reconciliation_at
				FROM payment_attempts WHERE id = ?
				""", attemptId);
		assertThat(attempt.get("status")).isEqualTo("APPROVED");
		assertThat(((Number) attempt.get("reconciliation_attempts")).intValue()).isEqualTo(1);
		assertThat(attempt.get("reconciliation_lease_until")).isNull();
		assertThat(attempt.get("next_reconciliation_at")).isNull();
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*) FROM audit_logs
				WHERE action = 'PAYMENT_RECONCILED'
				  AND target_id = ?
				  AND details ->> 'adminSubject' = 'system:auto-reconciliation'
				""", Integer.class, attemptId.toString())).isEqualTo(1);
	}

	@Test
	void automaticPaymentReconciliationConvergesDeclinedResult() throws Exception {
		String accessToken = login(signupUniqueMember("자동 결제 거절 대사 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(2, new BigDecimal("29200.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);

		mockMvc.perform(post("/api/reservations/{reservationId}/confirm", reservationId)
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"paymentToken", "mock-timeout-declined"))))
				.andExpect(status().isConflict());

		assertThat(automaticReconciliationService.reconcileNextBatch()).isEqualTo(1);
		assertThat(reservationStatus(reservationId)).isEqualTo("PENDING");
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT status FROM payment_attempts WHERE reservation_id = ?
				""", String.class, reservationId)).isEqualTo("DECLINED");
		assertThat(outboxCount(reservationId, "RESERVATION_CONFIRMED")).isZero();
	}

	@Test
	void signedPaymentWebhookIsProcessedOnceAndRejectsForgeryAndPayloadConflict() throws Exception {
		String accessToken = login(signupUniqueMember("결제 웹훅 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(2, new BigDecimal("29300.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);

		mockMvc.perform(post("/api/reservations/{reservationId}/confirm", reservationId)
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"paymentToken", "mock-timeout-unknown"))))
				.andExpect(status().isConflict());
		Map<String, Object> attempt = jdbcTemplate.queryForMap("""
				SELECT id, gateway_idempotency_key
				FROM payment_attempts WHERE reservation_id = ?
				""", reservationId);
		String gatewayKey = attempt.get("gateway_idempotency_key").toString();
		String eventId = "payment-event-" + UUID.randomUUID();
		String timestamp = Long.toString(Instant.now().getEpochSecond());
		String body = objectMapper.writeValueAsString(Map.of(
				"eventType", "PAYMENT_AUTHORIZATION_RESULT",
				"gatewayIdempotencyKey", gatewayKey,
				"result", "APPROVED",
				"gatewayReference", "webhook-payment-reference",
				"occurredAt", Instant.now().toString()));

		performWebhook(eventId, timestamp, body, webhookSignature(timestamp, body))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("PROCESSED"))
				.andExpect(jsonPath("$.duplicate").value(false));
		performWebhook(eventId, timestamp, body, webhookSignature(timestamp, body))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("PROCESSED"))
				.andExpect(jsonPath("$.duplicate").value(true));

		String conflictingBody = body.replace("APPROVED", "DECLINED");
		performWebhook(eventId, timestamp, conflictingBody,
				webhookSignature(timestamp, conflictingBody))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("WEBHOOK_EVENT_CONFLICT"));
		performWebhook("forged-" + UUID.randomUUID(), timestamp, body, "v1=00")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));
		String staleTimestamp = Long.toString(Instant.now().minus(10, ChronoUnit.MINUTES)
				.getEpochSecond());
		performWebhook("stale-" + UUID.randomUUID(), staleTimestamp, body,
				webhookSignature(staleTimestamp, body))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("WEBHOOK_TIMESTAMP_EXPIRED"));

		assertThat(reservationStatus(reservationId)).isEqualTo("CONFIRMED");
		assertThat(outboxCount(reservationId, "RESERVATION_CONFIRMED")).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*) FROM payment_webhook_inbox WHERE provider_event_id = ?
				""", Integer.class, eventId)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT attempts FROM payment_webhook_inbox WHERE provider_event_id = ?
				""", Integer.class, eventId)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*) FROM audit_logs
				WHERE action = 'PAYMENT_RECONCILED'
				  AND target_id = ?
				  AND details ->> 'adminSubject' = 'system:pg-webhook'
				""", Integer.class, attempt.get("id").toString())).isEqualTo(1);
		mockMvc.perform(get("/actuator/prometheus"))
				.andExpect(content().string(containsString("fan_event_payment_webhook_total")));
	}

	@Test
	void latePaymentApprovalCreatesAndCompletesCompensationOnceAcrossWorkers() throws Exception {
		String accessToken = login(signupUniqueMember("늦은 승인 보상 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(1, new BigDecimal("29350.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);
		mockMvc.perform(post("/api/reservations/{reservationId}/confirm", reservationId)
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"paymentToken", "mock-timeout-unknown"))))
				.andExpect(status().isConflict());
		makeReservationExpired(reservationId, 1);
		assertThat(expirationService.expireNextBatch(1)).isEqualTo(1);
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(1);

		String gatewayKey = jdbcTemplate.queryForObject("""
				SELECT gateway_idempotency_key FROM payment_attempts WHERE reservation_id = ?
				""", String.class, reservationId);
		String eventId = "late-approval-" + UUID.randomUUID();
		String timestamp = Long.toString(Instant.now().getEpochSecond());
		String body = objectMapper.writeValueAsString(Map.of(
				"eventType", "PAYMENT_AUTHORIZATION_RESULT",
				"gatewayIdempotencyKey", gatewayKey,
				"result", "APPROVED",
				"gatewayReference", "late-payment-reference",
				"occurredAt", Instant.now().toString()));
		performWebhook(eventId, timestamp, body, webhookSignature(timestamp, body))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("PROCESSED"));
		performWebhook(eventId, timestamp, body, webhookSignature(timestamp, body))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.duplicate").value(true));

		assertThat(reservationStatus(reservationId)).isEqualTo("EXPIRED");
		assertThat(jdbcTemplate.queryForObject("""
				SELECT status FROM payment_attempts WHERE reservation_id = ?
				""", String.class, reservationId)).isEqualTo("APPROVED");
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*) FROM refund_attempts
				WHERE reservation_id = ? AND purpose = 'LATE_PAYMENT_COMPENSATION'
				""", Integer.class, reservationId)).isEqualTo(1);

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Future<Integer> first = executor.submit(() -> {
				start.await();
				return automaticReconciliationService.reconcileNextBatch();
			});
			Future<Integer> second = executor.submit(() -> {
				start.await();
				return automaticReconciliationService.reconcileNextBatch();
			});
			start.countDown();
			assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS))
					.isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}

		assertThat(jdbcTemplate.queryForObject("""
				SELECT status FROM refund_attempts WHERE reservation_id = ?
				""", String.class, reservationId)).isEqualTo("SUCCEEDED");
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(1);
		assertThat(outboxCount(reservationId, "RESERVATION_CANCELLED")).isZero();
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*) FROM audit_logs
				WHERE action = 'LATE_PAYMENT_COMPENSATION'
				  AND details ->> 'reservationId' = ?
				""", Integer.class, reservationId.toString())).isEqualTo(1);
	}

	@Test
	void automaticReconciliationCompensatesApprovalFoundAfterExpiration() throws Exception {
		String accessToken = login(signupUniqueMember("자동 늦은 승인 보상 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(1, new BigDecimal("29360.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);
		mockMvc.perform(post("/api/reservations/{reservationId}/confirm", reservationId)
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"paymentToken", "mock-timeout-approved"))))
				.andExpect(status().isConflict());
		makeReservationExpired(reservationId, 1);
		assertThat(expirationService.expireNextBatch(1)).isEqualTo(1);

		assertThat(automaticReconciliationService.reconcileNextBatch()).isEqualTo(2);
		assertThat(reservationStatus(reservationId)).isEqualTo("EXPIRED");
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(1);
		Map<String, Object> ledger = jdbcTemplate.queryForMap("""
				SELECT p.status payment_status, r.status refund_status, r.purpose
				FROM payment_attempts p
				JOIN refund_attempts r ON r.payment_attempt_id = p.id
				WHERE p.reservation_id = ?
				""", reservationId);
		assertThat(ledger.get("payment_status")).isEqualTo("APPROVED");
		assertThat(ledger.get("refund_status")).isEqualTo("SUCCEEDED");
		assertThat(ledger.get("purpose")).isEqualTo("LATE_PAYMENT_COMPENSATION");
		assertThat(outboxCount(reservationId, "RESERVATION_CONFIRMED")).isZero();
		assertThat(outboxCount(reservationId, "RESERVATION_CANCELLED")).isZero();
	}

	@Test
	@WithMockUser(username = "admin-test", roles = "ADMIN")
	void unknownLateApprovalCompensationIsVisibleAndManuallyRecovered() throws Exception {
		String accessToken = login(signupUniqueMember("보상 결과 불명 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(1, new BigDecimal("29370.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);
		mockMvc.perform(post("/api/reservations/{reservationId}/confirm", reservationId)
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"paymentToken", "mock-late-compensation-timeout-unknown"))))
				.andExpect(status().isConflict());
		makeReservationExpired(reservationId, 1);
		assertThat(expirationService.expireNextBatch(1)).isEqualTo(1);

		String gatewayKey = jdbcTemplate.queryForObject("""
				SELECT gateway_idempotency_key FROM payment_attempts WHERE reservation_id = ?
				""", String.class, reservationId);
		String timestamp = Long.toString(Instant.now().getEpochSecond());
		String body = objectMapper.writeValueAsString(Map.of(
				"eventType", "PAYMENT_AUTHORIZATION_RESULT",
				"gatewayIdempotencyKey", gatewayKey,
				"result", "APPROVED",
				"gatewayReference", "late-unknown-reference",
				"occurredAt", Instant.now().toString()));
		performWebhook("late-unknown-" + UUID.randomUUID(), timestamp, body,
				webhookSignature(timestamp, body)).andExpect(status().isAccepted());

		assertThat(automaticReconciliationService.reconcileNextBatch()).isEqualTo(1);
		Map<String, Object> compensation = jdbcTemplate.queryForMap("""
				SELECT id, gateway_idempotency_key, status, reconciliation_attempts
				FROM refund_attempts WHERE reservation_id = ?
				""", reservationId);
		assertThat(compensation.get("status")).isEqualTo("UNKNOWN");
		assertThat(((Number) compensation.get("reconciliation_attempts")).intValue())
				.isEqualTo(1);
		UUID compensationId = (UUID) compensation.get("id");
		mockMvc.perform(get("/api/admin/payment-compensations?page=0&size=20"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].refundAttemptId")
						.value(compensationId.toString()))
				.andExpect(jsonPath("$.content[0].status").value("UNKNOWN"));

		paymentWebhookResultRecorder.recordRefund(
				compensation.get("gateway_idempotency_key").toString(),
				RefundGatewayResult.SUCCEEDED,
				"recovered-compensation-reference");
		mockMvc.perform(post("/api/admin/payment-compensations/{attemptId}/retry", compensationId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("SUCCEEDED"));

		assertThat(reservationStatus(reservationId)).isEqualTo("EXPIRED");
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(1);
		reconciliationBacklogMonitor.refresh();
		mockMvc.perform(get("/actuator/prometheus"))
				.andExpect(content().string(containsString(
						"fan_event_payment_compensation_total")))
				.andExpect(content().string(containsString(
						"fan_event_payment_compensation_backlog")));
	}

	@Test
	@WithMockUser(username = "admin-test", roles = "ADMIN")
	void failedPaymentWebhookIsRetriedFromAdminInbox() throws Exception {
		String accessToken = login(signupUniqueMember("웹훅 재처리 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(1, new BigDecimal("29400.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);

		mockMvc.perform(post("/api/reservations/{reservationId}/confirm", reservationId)
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"paymentToken", "mock-timeout-unknown"))))
				.andExpect(status().isConflict());
		String gatewayKey = jdbcTemplate.queryForObject("""
				SELECT gateway_idempotency_key FROM payment_attempts WHERE reservation_id = ?
				""", String.class, reservationId);
		makeReservationExpired(reservationId, 1);
		String eventId = "retry-event-" + UUID.randomUUID();
		String timestamp = Long.toString(Instant.now().getEpochSecond());
		String body = objectMapper.writeValueAsString(Map.of(
				"eventType", "PAYMENT_AUTHORIZATION_RESULT",
				"gatewayIdempotencyKey", gatewayKey,
				"result", "APPROVED",
				"gatewayReference", "retry-payment-reference",
				"occurredAt", Instant.now().toString()));

		performWebhook(eventId, timestamp, body, webhookSignature(timestamp, body))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("FAILED"));
		UUID inboxId = jdbcTemplate.queryForObject("""
				SELECT id FROM payment_webhook_inbox WHERE provider_event_id = ?
				""", UUID.class, eventId);
		jdbcTemplate.update("""
				UPDATE payment_webhook_inbox
				SET status = 'PROCESSING', processing_lease_until = ?
				WHERE id = ?
				""", java.sql.Timestamp.from(Instant.now().minusSeconds(1)), inboxId);
		int recoveredAttempt = paymentWebhookInboxStore.claim(inboxId);
		assertThat(recoveredAttempt).isEqualTo(2);
		paymentWebhookInboxStore.markProcessed(inboxId, 1);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT status FROM payment_webhook_inbox WHERE id = ?",
				String.class, inboxId)).isEqualTo("PROCESSING");
		paymentWebhookInboxStore.markFailed(inboxId, recoveredAttempt, "worker recovery test");
		jdbcTemplate.update(
				"UPDATE reservations SET expires_at = ? WHERE id = ?",
				java.sql.Timestamp.from(Instant.now().plus(10, ChronoUnit.MINUTES)),
				reservationId);

		mockMvc.perform(get("/api/admin/payment-webhooks?page=0&size=20"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].id").value(inboxId.toString()))
				.andExpect(jsonPath("$.content[0].status").value("FAILED"))
				.andExpect(jsonPath("$.content[0].attempts").value(2));
		mockMvc.perform(post("/api/admin/payment-webhooks/{eventId}/retry", inboxId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PROCESSED"));

		assertThat(reservationStatus(reservationId)).isEqualTo("CONFIRMED");
		assertThat(jdbcTemplate.queryForObject("""
				SELECT attempts FROM payment_webhook_inbox WHERE id = ?
				""", Integer.class, inboxId)).isEqualTo(3);
	}

	@Test
	void automaticPaymentReconciliationUsesBackoffAndRecoversExpiredLease() throws Exception {
		String accessToken = login(signupUniqueMember("자동 대사 재시도 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(2, new BigDecimal("29500.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);

		mockMvc.perform(post("/api/reservations/{reservationId}/confirm", reservationId)
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"paymentToken", "mock-timeout-unknown"))))
				.andExpect(status().isConflict());

		UUID attemptId = jdbcTemplate.queryForObject("""
				SELECT id FROM payment_attempts WHERE reservation_id = ?
				""", UUID.class, reservationId);
		assertThat(automaticReconciliationService.reconcileNextBatch()).isEqualTo(1);
		Map<String, Object> rescheduled = jdbcTemplate.queryForMap("""
				SELECT status, reconciliation_attempts, last_reconciliation_at,
				       next_reconciliation_at, reconciliation_lease_until
				FROM payment_attempts WHERE id = ?
				""", attemptId);
		assertThat(rescheduled.get("status")).isEqualTo("UNKNOWN");
		assertThat(((Number) rescheduled.get("reconciliation_attempts")).intValue()).isEqualTo(1);
		assertThat(rescheduled.get("reconciliation_lease_until")).isNull();
		assertThat((java.sql.Timestamp) rescheduled.get("next_reconciliation_at"))
				.isAfter((java.sql.Timestamp) rescheduled.get("last_reconciliation_at"));
		assertThat(automaticReconciliationService.reconcileNextBatch()).isZero();

		jdbcTemplate.update("""
				UPDATE payment_attempts
				SET next_reconciliation_at = CURRENT_TIMESTAMP - INTERVAL '1 minute',
				    reconciliation_lease_until = CURRENT_TIMESTAMP + INTERVAL '1 minute'
				WHERE id = ?
				""", attemptId);
		automaticReconciliationService.reconcileNextBatch();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT reconciliation_attempts FROM payment_attempts WHERE id = ?",
				Integer.class, attemptId)).isEqualTo(1);

		jdbcTemplate.update("""
				UPDATE payment_attempts
				SET reconciliation_lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
				WHERE id = ?
				""", attemptId);
		assertThat(automaticReconciliationService.reconcileNextBatch()).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT reconciliation_attempts FROM payment_attempts WHERE id = ?",
				Integer.class, attemptId)).isEqualTo(2);

		reconciliationBacklogMonitor.refresh();
		mockMvc.perform(get("/actuator/prometheus"))
				.andExpect(content().string(containsString(
						"fan_event_payment_reconciliation_backlog")))
				.andExpect(content().string(containsString(
						"fan_event_payment_reconciliation_oldest_age_seconds")))
				.andExpect(content().string(containsString(
						"fan_event_reconciliation_automatic_total")));
		resolveUnknownPaymentFixture(attemptId);
	}

	@Test
	void expiredReconciliationWorkerCannotClearNewOwnersLease() throws Exception {
		String accessToken = login(signupUniqueMember("대사 임대 소유권 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(1, new BigDecimal("29700.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);

		mockMvc.perform(post("/api/reservations/{reservationId}/confirm", reservationId)
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"paymentToken", "mock-timeout-unknown"))))
				.andExpect(status().isConflict());

		ReconciliationCandidate staleOwner = reconciliationLeaseRepository
				.claimPayments(1, Duration.ofSeconds(30)).getFirst();
		jdbcTemplate.update("""
				UPDATE payment_attempts
				SET reconciliation_lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
				WHERE id = ?
				""", staleOwner.attemptId());
		ReconciliationCandidate currentOwner = reconciliationLeaseRepository
				.claimPayments(1, Duration.ofSeconds(30)).getFirst();

		reconciliationLeaseRepository.completePayment(staleOwner, Duration.ofSeconds(10));
		Map<String, Object> stillOwned = jdbcTemplate.queryForMap("""
				SELECT reconciliation_attempts, reconciliation_lease_until
				FROM payment_attempts WHERE id = ?
				""", staleOwner.attemptId());
		assertThat(((Number) stillOwned.get("reconciliation_attempts")).intValue()).isEqualTo(2);
		assertThat(stillOwned.get("reconciliation_lease_until")).isNotNull();

		reconciliationLeaseRepository.completePayment(currentOwner, Duration.ofSeconds(10));
		assertThat(jdbcTemplate.queryForObject("""
				SELECT reconciliation_lease_until IS NULL
				FROM payment_attempts WHERE id = ?
				""", Boolean.class, staleOwner.attemptId())).isTrue();
		resolveUnknownPaymentFixture(staleOwner.attemptId());
	}

	@Test
	void pendingCancellationReturnsInventoryOnceWithoutRefund() throws Exception {
		String accessToken = login(signupUniqueMember("선점 취소 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(5, new BigDecimal("13000.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 2);

		cancelTwice(accessToken, reservationId);

		verify(paymentGateway, never()).refund(
				eq(reservationId), any(BigDecimal.class), anyString(), anyString());
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
				eq(reservationId),
				eq(new BigDecimal("50000.00")),
				eq("mock-payment-" + reservationId),
				eq("reservation-refund-" + reservationId));
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(5);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT status FROM reservations WHERE id = ?", String.class, reservationId))
				.isEqualTo("CANCELLED");
		assertThat(outboxCount(reservationId, "RESERVATION_CANCELLED")).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT status FROM refund_attempts WHERE reservation_id = ?
				""", String.class, reservationId)).isEqualTo("SUCCEEDED");
	}

	@Test
	void refundGatewayRunsWithoutHoldingDatabaseTransaction() throws Exception {
		String accessToken = login(signupUniqueMember("환불 트랜잭션 경계 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(2, new BigDecimal("25100.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);
		confirmReservation(accessToken, reservationId, "mock-approved");
		AtomicBoolean transactionActiveAtGateway = new AtomicBoolean(true);

		doAnswer(invocation -> {
			transactionActiveAtGateway.set(
					TransactionSynchronizationManager.isActualTransactionActive());
			return invocation.callRealMethod();
		}).when(paymentGateway).refund(
				eq(reservationId),
				eq(new BigDecimal("25100.00")),
				eq("mock-payment-" + reservationId),
				eq("reservation-refund-" + reservationId));

		mockMvc.perform(post("/api/reservations/{reservationId}/cancel", reservationId)
				.header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"));

		assertThat(transactionActiveAtGateway).isFalse();
	}

	@Test
	void concurrentRefundAttemptCreationIsAtomicAndKeepsFreshRequestInProgress() throws Exception {
		String accessToken = login(signupUniqueMember("환불 원장 경쟁 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(2, new BigDecimal("25200.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);
		confirmReservation(accessToken, reservationId, "mock-approved");
		int workers = 6;
		ExecutorService executor = Executors.newFixedThreadPool(workers);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Boolean>> attempts = new ArrayList<>();

		try {
			for (int worker = 0; worker < workers; worker++) {
				attempts.add(executor.submit(() -> {
					start.await();
					try {
						refundAttemptService.begin(reservationId, new BigDecimal("25200.00"));
						return true;
					} catch (IdempotencyInProgressException exception) {
						return false;
					}
				}));
			}
			start.countDown();
			int owners = 0;
			for (Future<Boolean> attempt : attempts) {
				if (attempt.get(10, TimeUnit.SECONDS)) owners++;
			}
			assertThat(owners).isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}

		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*) FROM refund_attempts WHERE reservation_id = ?
				""", Integer.class, reservationId)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT status FROM refund_attempts WHERE reservation_id = ?
				""", String.class, reservationId)).isEqualTo("REQUESTED");
		mockMvc.perform(get("/actuator/prometheus"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString(
						"fan_event_refund_execution_claim_total")))
				.andExpect(content().string(containsString("result=\"in_progress\"")));
		jdbcTemplate.update("DELETE FROM refund_attempts WHERE reservation_id = ?", reservationId);
	}

	@Test
	void staleRequestedRefundAttemptBecomesUnknownForReconciliation() throws Exception {
		String accessToken = login(signupUniqueMember("환불 원장 복구 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(2, new BigDecimal("25300.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);
		confirmReservation(accessToken, reservationId, "mock-approved");

		refundAttemptService.begin(reservationId, new BigDecimal("25300.00"));
		jdbcTemplate.update("""
				UPDATE refund_attempts
				SET requested_at = ?, updated_at = ?
				WHERE reservation_id = ?
				""",
				java.sql.Timestamp.from(Instant.now().minus(31, ChronoUnit.SECONDS)),
				java.sql.Timestamp.from(Instant.now().minus(31, ChronoUnit.SECONDS)),
				reservationId);

		assertThat(refundAttemptService.begin(
				reservationId, new BigDecimal("25300.00")).getStatus().name())
				.isEqualTo("UNKNOWN");
		Map<String, Object> recovered = jdbcTemplate.queryForMap("""
				SELECT status, next_reconciliation_at
				FROM refund_attempts WHERE reservation_id = ?
				""", reservationId);
		assertThat(recovered.get("status")).isEqualTo("UNKNOWN");
		assertThat(recovered.get("next_reconciliation_at")).isNotNull();
		jdbcTemplate.update("DELETE FROM refund_attempts WHERE reservation_id = ?", reservationId);
	}

	@Test
	void concurrentCancellationReturnsInProgressWithoutDuplicatingRefund() throws Exception {
		String accessToken = login(signupUniqueMember("동시 환불 취소 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(2, new BigDecimal("25400.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);
		confirmReservation(accessToken, reservationId, "mock-approved");
		CountDownLatch gatewayEntered = new CountDownLatch(1);
		CountDownLatch releaseGateway = new CountDownLatch(1);

		doAnswer(invocation -> {
			gatewayEntered.countDown();
			if (!releaseGateway.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("동시 환불 테스트의 PG 대기가 시간 안에 해제되지 않았습니다.");
			}
			return invocation.callRealMethod();
		}).when(paymentGateway).refund(
				eq(reservationId),
				eq(new BigDecimal("25400.00")),
				eq("mock-payment-" + reservationId),
				eq("reservation-refund-" + reservationId));

		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<String> first = executor.submit(() -> mockMvc.perform(
					post("/api/reservations/{reservationId}/cancel", reservationId)
							.header("Authorization", "Bearer " + accessToken))
					.andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString());
			assertThat(gatewayEntered.await(10, TimeUnit.SECONDS)).isTrue();

			mockMvc.perform(post("/api/reservations/{reservationId}/cancel", reservationId)
					.header("Authorization", "Bearer " + accessToken))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("IDEMPOTENCY_REQUEST_IN_PROGRESS"));
			releaseGateway.countDown();
			assertThat(objectMapper.readTree(first.get(10, TimeUnit.SECONDS)).get("status").asText())
					.isEqualTo("CANCELLED");
		} finally {
			releaseGateway.countDown();
			executor.shutdownNow();
		}

		verify(paymentGateway, times(1)).refund(
				eq(reservationId),
				eq(new BigDecimal("25400.00")),
				eq("mock-payment-" + reservationId),
				eq("reservation-refund-" + reservationId));
		assertThat(reservationStatus(reservationId)).isEqualTo("CANCELLED");
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(2);
		assertThat(outboxCount(reservationId, "RESERVATION_CANCELLED")).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*) FROM refund_attempts WHERE reservation_id = ?
				""", Integer.class, reservationId)).isEqualTo(1);
	}

	@Test
	void concurrentCancellationFinalizationReturnsInventoryAndOutboxOnce() throws Exception {
		String accessToken = login(signupUniqueMember("동시 취소 완료 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(2, new BigDecimal("25500.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);
		confirmReservation(accessToken, reservationId, "mock-approved");
		var refundAttempt = refundAttemptService.begin(reservationId, new BigDecimal("25500.00"));
		refundAttemptService.succeed(refundAttempt.getId(), "prepared-refund-reference");
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<String> first = executor.submit(() -> cancelReservationAfter(
					start, accessToken, reservationId));
			Future<String> second = executor.submit(() -> cancelReservationAfter(
					start, accessToken, reservationId));
			start.countDown();
			assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
					.containsOnly("CANCELLED");
		} finally {
			executor.shutdownNow();
		}

		verify(paymentGateway, never()).refund(
				eq(reservationId), any(BigDecimal.class), anyString(), anyString());
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(2);
		assertThat(outboxCount(reservationId, "RESERVATION_CANCELLED")).isEqualTo(1);
	}

	@Test
	void declinedRefundKeepsConfirmedReservationAndHeldInventory() throws Exception {
		String accessToken = login(signupUniqueMember("환불 거절 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(3, new BigDecimal("26000.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);
		confirmReservation(accessToken, reservationId, "mock-refund-declined");

		mockMvc.perform(post("/api/reservations/{reservationId}/cancel", reservationId)
				.header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("REFUND_DECLINED"));

		assertThat(reservationStatus(reservationId)).isEqualTo("CONFIRMED");
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(2);
		assertThat(outboxCount(reservationId, "RESERVATION_CANCELLED")).isZero();
		assertThat(jdbcTemplate.queryForObject("""
				SELECT status FROM refund_attempts WHERE reservation_id = ?
				""", String.class, reservationId)).isEqualTo("DECLINED");
	}

	@Test
	@WithMockUser(username = "admin-test", roles = "ADMIN")
	void unknownSuccessfulRefundIsReconciledWithoutDuplicateRefund() throws Exception {
		String accessToken = login(signupUniqueMember("환불 성공 대사 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(2, new BigDecimal("31000.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);
		confirmReservation(accessToken, reservationId, "mock-refund-timeout-succeeded");

		mockMvc.perform(post("/api/reservations/{reservationId}/cancel", reservationId)
				.header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("REFUND_RESULT_UNKNOWN"))
				.andExpect(jsonPath("$.details[0]").value(containsString("refundAttemptId")));

		UUID attemptId = jdbcTemplate.queryForObject("""
				SELECT id FROM refund_attempts WHERE reservation_id = ?
				""", UUID.class, reservationId);
		assertThat(reservationStatus(reservationId)).isEqualTo("CONFIRMED");
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(1);

		SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
		sessionFactory.getStatistics().clear();
		mockMvc.perform(get("/api/admin/refund-attempts/unknown"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].refundAttemptId").value(attemptId.toString()))
				.andExpect(jsonPath("$.content[0].status").value("UNKNOWN"))
				.andExpect(jsonPath("$.content[0].reconciliationAttempts").value(0))
				.andExpect(jsonPath("$.content[0].nextReconciliationAt").isNotEmpty());
		assertThat(sessionFactory.getStatistics().getPrepareStatementCount()).isEqualTo(2);

		for (int reconciliation = 0; reconciliation < 2; reconciliation++) {
			mockMvc.perform(post(
					"/api/admin/refund-attempts/{refundAttemptId}/reconcile", attemptId))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.refundStatus").value("SUCCEEDED"))
					.andExpect(jsonPath("$.reservationStatus").value("CANCELLED"))
					.andExpect(jsonPath("$.resolved").value(true));
		}

		verify(paymentGateway, times(1)).refund(
				eq(reservationId),
				eq(new BigDecimal("31000.00")),
				eq("mock-payment-" + reservationId),
				eq("reservation-refund-" + reservationId));
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(2);
		assertThat(outboxCount(reservationId, "RESERVATION_CANCELLED")).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*) FROM audit_logs
				WHERE action = 'REFUND_RECONCILED' AND target_id = ?
				""", Integer.class, attemptId.toString())).isEqualTo(1);
		mockMvc.perform(get("/actuator/prometheus"))
				.andExpect(content().string(containsString(
						"fan_event_refund_reconciliation_total")));
	}

	@Test
	@WithMockUser(username = "admin-test", roles = "ADMIN")
	void unknownDeclinedRefundKeepsReservationConfirmedAfterReconciliation() throws Exception {
		String accessToken = login(signupUniqueMember("환불 거절 대사 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(2, new BigDecimal("32000.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);
		confirmReservation(accessToken, reservationId, "mock-refund-timeout-declined");

		mockMvc.perform(post("/api/reservations/{reservationId}/cancel", reservationId)
				.header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("REFUND_RESULT_UNKNOWN"));

		UUID attemptId = jdbcTemplate.queryForObject("""
				SELECT id FROM refund_attempts WHERE reservation_id = ?
				""", UUID.class, reservationId);
		mockMvc.perform(post(
				"/api/admin/refund-attempts/{refundAttemptId}/reconcile", attemptId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.refundStatus").value("DECLINED"))
				.andExpect(jsonPath("$.reservationStatus").value("CONFIRMED"))
				.andExpect(jsonPath("$.resolved").value(true));

		assertThat(reservationStatus(reservationId)).isEqualTo("CONFIRMED");
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(1);
		assertThat(outboxCount(reservationId, "RESERVATION_CANCELLED")).isZero();
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*) FROM audit_logs
				WHERE action = 'REFUND_RECONCILED' AND target_id = ?
				""", Integer.class, attemptId.toString())).isEqualTo(1);
	}

	@Test
	void automaticRefundReconciliationCompletesCancellationOnce() throws Exception {
		String accessToken = login(signupUniqueMember("자동 환불 대사 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(2, new BigDecimal("32500.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);
		confirmReservation(accessToken, reservationId, "mock-refund-timeout-succeeded");

		mockMvc.perform(post("/api/reservations/{reservationId}/cancel", reservationId)
				.header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isConflict());
		UUID attemptId = jdbcTemplate.queryForObject("""
				SELECT id FROM refund_attempts WHERE reservation_id = ?
				""", UUID.class, reservationId);

		assertThat(automaticReconciliationService.reconcileNextBatch()).isEqualTo(1);
		assertThat(reservationStatus(reservationId)).isEqualTo("CANCELLED");
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(2);
		assertThat(outboxCount(reservationId, "RESERVATION_CANCELLED")).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT status FROM refund_attempts WHERE id = ?",
				String.class, attemptId)).isEqualTo("SUCCEEDED");
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*) FROM audit_logs
				WHERE action = 'REFUND_RECONCILED'
				  AND target_id = ?
				  AND details ->> 'adminSubject' = 'system:auto-reconciliation'
				""", Integer.class, attemptId.toString())).isEqualTo(1);
	}

	@Test
	void signedRefundWebhookCompletesCancellationOnce() throws Exception {
		String accessToken = login(signupUniqueMember("환불 웹훅 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(2, new BigDecimal("32600.00"));
		Long reservationId = holdReservation(accessToken, inventoryId, 1);
		confirmReservation(accessToken, reservationId, "mock-refund-timeout-unknown");
		mockMvc.perform(post("/api/reservations/{reservationId}/cancel", reservationId)
				.header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isConflict());
		String gatewayKey = jdbcTemplate.queryForObject("""
				SELECT gateway_idempotency_key FROM refund_attempts WHERE reservation_id = ?
				""", String.class, reservationId);
		String eventId = "refund-event-" + UUID.randomUUID();
		String timestamp = Long.toString(Instant.now().getEpochSecond());
		String body = objectMapper.writeValueAsString(Map.of(
				"eventType", "REFUND_RESULT",
				"gatewayIdempotencyKey", gatewayKey,
				"result", "SUCCEEDED",
				"gatewayReference", "webhook-refund-reference",
				"occurredAt", Instant.now().toString()));

		performWebhook(eventId, timestamp, body, webhookSignature(timestamp, body))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("PROCESSED"));
		performWebhook(eventId, timestamp, body, webhookSignature(timestamp, body))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.duplicate").value(true));

		assertThat(reservationStatus(reservationId)).isEqualTo("CANCELLED");
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(2);
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
				eq(reservationId), any(BigDecimal.class), eq("mock-approved"), anyString());
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
	void memberReservationSearchAndDetailAreOwnedAndUseStableQueryCounts() throws Exception {
		String ownerEmail = signupUniqueMember("내 예약 조회 회원");
		String ownerToken = login(ownerEmail, "secure-password");
		String otherEmail = signupUniqueMember("다른 예약 회원");
		String otherToken = login(otherEmail, "secure-password");
		Long firstInventoryId = createOnSaleInventory(10, new BigDecimal("31000.00"));
		Long secondInventoryId = createOnSaleInventory(10, new BigDecimal("47000.00"));
		Long ownerConfirmedId = holdReservation(ownerToken, firstInventoryId, 2);
		confirmReservation(ownerToken, ownerConfirmedId);
		Long ownerPendingId = holdReservation(ownerToken, secondInventoryId, 1);
		Long otherReservationId = holdReservation(otherToken, firstInventoryId, 1);

		SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
		sessionFactory.getStatistics().clear();
		mockMvc.perform(get("/api/reservations")
				.header("Authorization", "Bearer " + ownerToken)
				.param("createdFrom", Instant.now().minus(1, ChronoUnit.DAYS).toString())
				.param("size", "10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.content[0].reservationId").value(ownerPendingId))
				.andExpect(jsonPath("$.content[0].totalQuantity").value(1))
				.andExpect(jsonPath("$.content[0].eventCount").value(1))
				.andExpect(jsonPath("$.content[0].representativeEventTitle").isString())
				.andExpect(jsonPath("$.content[1].reservationId").value(ownerConfirmedId))
				.andExpect(jsonPath("$.content[1].totalQuantity").value(2));
		assertThat(sessionFactory.getStatistics().getPrepareStatementCount()).isEqualTo(2);

		sessionFactory.getStatistics().clear();
		mockMvc.perform(get("/api/reservations")
				.header("Authorization", "Bearer " + ownerToken)
				.param("status", "CONFIRMED")
				.param("size", "10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].reservationId").value(ownerConfirmedId))
				.andExpect(jsonPath("$.content[0].status").value("CONFIRMED"));
		assertThat(sessionFactory.getStatistics().getPrepareStatementCount()).isEqualTo(2);

		sessionFactory.getStatistics().clear();
		mockMvc.perform(get("/api/reservations/{reservationId}", ownerConfirmedId)
				.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reservationId").value(ownerConfirmedId))
				.andExpect(jsonPath("$.status").value("CONFIRMED"))
				.andExpect(jsonPath("$.totalAmount").value(62000.00))
				.andExpect(jsonPath("$.confirmedAt").isString())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].inventoryId").value(firstInventoryId))
				.andExpect(jsonPath("$.items[0].quantity").value(2))
				.andExpect(jsonPath("$.items[0].unitPrice").value(31000.00))
				.andExpect(jsonPath("$.items[0].lineAmount").value(62000.00))
				.andExpect(jsonPath("$.items[0].eventTitle", containsString("예약 이벤트")))
				.andExpect(jsonPath("$.items[0].eventSessionName").value("1회차"));
		assertThat(sessionFactory.getStatistics().getPrepareStatementCount()).isEqualTo(2);

		mockMvc.perform(get("/api/reservations/{reservationId}", otherReservationId)
				.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("예약을 찾을 수 없습니다."));

		mockMvc.perform(get("/api/reservations")
				.header("Authorization", "Bearer " + otherToken)
				.param("status", "CONFIRMED"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(0));

		mockMvc.perform(get("/api/reservations").param("size", "10"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/reservations")
				.header("Authorization", "Bearer " + ownerToken)
				.param("size", "101"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.size").value(100));
		mockMvc.perform(get("/api/reservations")
				.header("Authorization", "Bearer " + ownerToken)
				.param("createdFrom", "2026-09-14T00:00:00Z")
				.param("createdTo", "2026-09-13T00:00:00Z"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
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
		mockMvc.perform(get("/actuator/prometheus"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("fan_event_cache_requests_total")))
				.andExpect(content().string(containsString("http_server_requests_seconds")));

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
	void reservationInventoryChangesInvalidatePublicEventCache() throws Exception {
		String accessToken = login(signupUniqueMember("예약 캐시 무효화 회원"), "secure-password");
		Long inventoryId = createOnSaleInventory(5, new BigDecimal("34000.00"));
		Long eventId = jdbcTemplate.queryForObject("""
				SELECT session.event_id
				FROM sellable_inventory inventory
				JOIN event_sessions session ON session.id = inventory.event_session_id
				WHERE inventory.id = ?
				""", Long.class, inventoryId);

		mockMvc.perform(get("/api/events/{eventId}", eventId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sessions[0].inventory[0].availableQuantity").value(5));
		assertThat(redisTemplate.hasKey("cache:event-detail:v1:" + eventId)).isTrue();

		Long cancelledReservationId = holdReservation(accessToken, inventoryId, 1);
		assertThat(redisTemplate.hasKey("cache:event-detail:v1:" + eventId)).isFalse();
		mockMvc.perform(get("/api/events/{eventId}", eventId))
				.andExpect(jsonPath("$.sessions[0].inventory[0].availableQuantity").value(4));

		cancelTwice(accessToken, cancelledReservationId);
		assertThat(redisTemplate.hasKey("cache:event-detail:v1:" + eventId)).isFalse();
		mockMvc.perform(get("/api/events/{eventId}", eventId))
				.andExpect(jsonPath("$.sessions[0].inventory[0].availableQuantity").value(5));

		Long expiredReservationId = holdReservation(accessToken, inventoryId, 1);
		mockMvc.perform(get("/api/events/{eventId}", eventId))
				.andExpect(jsonPath("$.sessions[0].inventory[0].availableQuantity").value(4));
		makeReservationExpired(expiredReservationId, 60);
		assertThat(expirationService.expireNextBatch(1)).isEqualTo(1);
		assertThat(redisTemplate.hasKey("cache:event-detail:v1:" + eventId)).isFalse();
		mockMvc.perform(get("/api/events/{eventId}", eventId))
				.andExpect(jsonPath("$.sessions[0].inventory[0].availableQuantity").value(5));
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

	@Test
	void waitingRoomKeepsFirstOrderAdmitsAtomicallyAndRequiresSingleUseToken() throws Exception {
		String email = signupUniqueMember("대기열 회원");
		String accessToken = login(email, "secure-password");
		Long memberId = memberRepository.findByEmail(email).orElseThrow().getId();
		Long inventoryId = createOnSaleInventory(3, new BigDecimal("77000.00"));
		Long eventId = jdbcTemplate.queryForObject("""
				SELECT session.event_id
				FROM sellable_inventory inventory
				JOIN event_sessions session ON session.id = inventory.event_session_id
				WHERE inventory.id = ?
				""", Long.class, inventoryId);
		waitingRoomService.open(eventId);
		Map<String, Object> policy = jdbcTemplate.queryForMap("""
				SELECT enabled, batch_size, active_capacity, admission_ttl_seconds
				FROM event_waiting_room_policies WHERE event_id = ?
				""", eventId);
		assertThat(policy.get("enabled")).isEqualTo(true);
		assertThat(((Number) policy.get("batch_size")).intValue()).isEqualTo(50);

		mockMvc.perform(post("/api/events/{eventId}/waiting-room", eventId)
				.header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("WAITING"))
				.andExpect(jsonPath("$.position").value(1));

		redisTemplate.delete("waiting-room:v1:" + eventId + ":enabled");
		ExecutorService recoveryWorkers = Executors.newFixedThreadPool(2);
		CountDownLatch recover = new CountDownLatch(1);
		Future<Integer> recoveredFirst = recoveryWorkers.submit(() -> {
			recover.await(); return waitingRoomService.reconcileRuntime();
		});
		Future<Integer> recoveredSecond = recoveryWorkers.submit(() -> {
			recover.await(); return waitingRoomService.reconcileRuntime();
		});
		recover.countDown();
		assertThat(recoveredFirst.get(10, TimeUnit.SECONDS)
				+ recoveredSecond.get(10, TimeUnit.SECONDS)).isEqualTo(1);
		recoveryWorkers.shutdownNow();
		assertThat(redisTemplate.hasKey("waiting-room:v1:" + eventId + ":enabled")).isTrue();
		mockMvc.perform(post("/api/events/{eventId}/waiting-room", eventId)
				.header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.position").value(1));

		mockMvc.perform(post("/api/reservations")
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(APPLICATION_JSON)
				.content(reservationRequest(inventoryId, 1)))
				.andExpect(status().isPreconditionRequired())
				.andExpect(jsonPath("$.code").value("ADMISSION_TOKEN_REQUIRED"));

		ExecutorService workers = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		Future<Integer> first = workers.submit(() -> { start.await(); return waitingRoomService.admit(eventId); });
		Future<Integer> second = workers.submit(() -> { start.await(); return waitingRoomService.admit(eventId); });
		start.countDown();
		assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
		workers.shutdownNow();

		String admittedBody = mockMvc.perform(get("/api/events/{eventId}/waiting-room", eventId)
				.header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ADMITTED"))
				.andExpect(jsonPath("$.admissionToken").isString())
				.andReturn().getResponse().getContentAsString();
		String admissionToken = objectMapper.readTree(admittedBody).get("admissionToken").asText();
		String idempotencyKey = UUID.randomUUID().toString();
		waitingRoomService.claim(admissionToken, memberId, eventId, idempotencyKey);
		org.junit.jupiter.api.Assertions.assertThrows(AdmissionTokenException.class,
				() -> waitingRoomService.claim(admissionToken, memberId, eventId, "different-key"));

		mockMvc.perform(post("/api/reservations")
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Admission-Token", admissionToken)
				.contentType(APPLICATION_JSON)
				.content(reservationRequest(inventoryId, 1)))
				.andExpect(status().isCreated());
		assertThat(inventoryQuantity(inventoryId)).isEqualTo(2);
		waitingRoomService.close(eventId);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT enabled FROM event_waiting_room_policies WHERE event_id = ?
				""", Boolean.class, eventId)).isFalse();
		assertThat(redisTemplate.hasKey("waiting-room:v1:" + eventId + ":enabled")).isFalse();
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void adminConfiguresEventSpecificWaitingRoomPolicyAndReadsQuerydslProjection() throws Exception {
		Long inventoryId = createOnSaleInventory(3, new BigDecimal("78000.00"));
		Long eventId = jdbcTemplate.queryForObject("""
				SELECT session.event_id FROM sellable_inventory inventory
				JOIN event_sessions session ON session.id = inventory.event_session_id
				WHERE inventory.id = ?
				""", Long.class, inventoryId);

		mockMvc.perform(put("/api/admin/events/{eventId}/waiting-room", eventId)
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"enabled", true,
						"batchSize", 1,
						"activeCapacity", 1,
						"admissionTtl", "PT10S"))))
				.andExpect(status().isNoContent());

		waitingRoomService.join(eventId, 1001L);
		waitingRoomService.join(eventId, 1002L);
		assertThat(waitingRoomService.admit(eventId)).isEqualTo(1);
		assertThat(waitingRoomService.admit(eventId)).isZero();

		mockMvc.perform(get("/api/admin/waiting-rooms"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].eventId").value(eventId))
				.andExpect(jsonPath("$[0].eventTitle").isString())
				.andExpect(jsonPath("$[0].batchSize").value(1))
				.andExpect(jsonPath("$[0].activeCapacity").value(1))
				.andExpect(jsonPath("$[0].admissionTtlSeconds").value(10))
				.andExpect(jsonPath("$[0].waitingCount").value(1))
				.andExpect(jsonPath("$[0].admittedCount").value(1))
				.andExpect(jsonPath("$[0].redisStatus").value("SYNCHRONIZED"));

		waitingRoomService.close(eventId);
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

	private boolean retryOutboxAfter(CountDownLatch start, UUID eventId) throws InterruptedException {
		start.await();
		try {
			adminOutboxService.retryExhaustedFailure(eventId, "admin-test");
			return true;
		} catch (OutboxManualRetryRejectedException ignored) {
			return false;
		}
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

	private String cancelReservationAfter(
			CountDownLatch start,
			String accessToken,
			Long reservationId
	) throws Exception {
		start.await();
		String body = mockMvc.perform(post("/api/reservations/{reservationId}/cancel", reservationId)
				.header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(body).get("status").asText();
	}

	private void cancelTwice(String accessToken, Long reservationId) throws Exception {
		for (int attempt = 0; attempt < 2; attempt++) {
			mockMvc.perform(post("/api/reservations/{reservationId}/cancel", reservationId)
					.header("Authorization", "Bearer " + accessToken))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.status").value("CANCELLED"));
		}
	}

	private void confirmReservation(
			String accessToken,
			Long reservationId,
			String paymentToken
	) throws Exception {
		mockMvc.perform(post("/api/reservations/{reservationId}/confirm", reservationId)
				.header("Authorization", "Bearer " + accessToken)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"paymentToken", paymentToken))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"));
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

	private void resolveUnknownPaymentFixture(UUID attemptId) {
		jdbcTemplate.update("""
				UPDATE payment_attempts
				SET status = 'DECLINED',
				    resolved_at = CURRENT_TIMESTAMP,
				    next_reconciliation_at = NULL,
				    reconciliation_lease_until = NULL
				WHERE id = ? AND status = 'UNKNOWN'
				""", attemptId);
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

	private ResultActions performWebhook(
			String eventId,
			String timestamp,
			String body,
			String signature
	) throws Exception {
		return mockMvc.perform(post("/api/payment/webhooks/mock")
				.header("X-PG-Event-Id", eventId)
				.header("X-PG-Timestamp", timestamp)
				.header("X-PG-Signature", signature)
				.contentType(APPLICATION_JSON)
				.content(body));
	}

	private String webhookSignature(String timestamp, String body) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(
				"local-webhook-signing-key".getBytes(StandardCharsets.UTF_8),
				"HmacSHA256"));
		byte[] signature = mac.doFinal(
				(timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
		return "v1=" + HexFormat.of().formatHex(signature);
	}

	private void markExistingOutboxPublished() {
		jdbcTemplate.update("""
				UPDATE outbox_events
				SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP
				WHERE status <> 'PUBLISHED'
				""");
	}

}
