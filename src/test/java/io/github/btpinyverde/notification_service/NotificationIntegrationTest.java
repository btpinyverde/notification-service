package io.github.btpinyverde.notification_service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class NotificationIntegrationTest extends PostgresTestSupport {
    private static final String KEY = "test-key";
    private static final ConcurrentLinkedQueue<Delivery> DELIVERIES = new ConcurrentLinkedQueue<>();
    private static final HttpServer RECEIVER = startReceiver();


    @DynamicPropertySource
    static void applicationProperties(DynamicPropertyRegistry registry) {
        registry.add("notifications.api-key", () -> KEY);
        registry.add("notifications.service-targets", () -> target("/sent") + "," + target("/unavailable"));
        registry.add("notifications.worker.poll-ms", () -> 50);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @AfterAll
    static void stopReceiver() {
        RECEIVER.stop(0);
    }

    @Test
    void rejectsMissingOrWrongKeyAndReportsValidationErrors() throws Exception {
        mvc.perform(post("/notifications").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/notifications").header(SecurityConfig.HEADER, "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/notifications").header(SecurityConfig.HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'recipient')]").isNotEmpty())
                .andExpect(jsonPath("$.errors[?(@.field == 'priority')]").isNotEmpty());
    }

    @Test
    void dispatchesToLogAndToHttpWithTheSameStableId() throws Exception {
        UUID log = enqueue("LOG", "diagnostics");
        UUID http = enqueue("SERVICE", target("/sent"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertState(log, "SENT", 1);
            assertState(http, "SENT", 1);
        });

        var deliveries = DELIVERIES.stream().filter(delivery -> http.toString().equals(delivery.key())).toList();
        assertThat(deliveries).hasSize(1);
        var delivery = deliveries.get(0);
        assertThat(delivery.method()).isEqualTo("POST");
        assertThat(json.readTree(delivery.body()).get("id").asText()).isEqualTo(http.toString());
    }

    @Test
    void retriesAndRecordsFailureWhileTheApiKeepsAcceptingJobs() throws Exception {
        UUID failing = enqueue("SERVICE", target("/unavailable"));
        await().atMost(Duration.ofSeconds(10)).until(() ->
                DELIVERIES.stream().anyMatch(delivery -> failing.toString().equals(delivery.key())));

        UUID healthy = enqueue("LOG", "diagnostics");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertState(healthy, "SENT", 1);
            assertState(failing, "FAILED", 3);
        });

        assertThat(DELIVERIES.stream().filter(delivery -> failing.toString().equals(delivery.key())).count())
                .isEqualTo(3);
        mvc.perform(get("/notifications/{id}", failing).header(SecurityConfig.HEADER, KEY))
                .andExpect(jsonPath("$.lastError").value("HTTP_503"));
    }

    @Test
    void recoversExpiredReservationsAndStopsWhenTheAttemptBudgetIsExhausted() {
        UUID recoverable = expiredClaim(1);
        UUID exhausted = expiredClaim(3);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertState(recoverable, "SENT", 2);
            assertState(exhausted, "FAILED", 3);
        });
        assertThat(jdbc.queryForObject("SELECT last_error FROM notifications WHERE id = ?",
                String.class, exhausted)).isEqualTo("LEASE_EXPIRED");
    }

    private UUID expiredClaim(int attempts) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications
                    (id, recipient, channel, subject, body, priority, metadata, status, attempts, claim_token, lease_until)
                VALUES (?, 'diagnostics', 'LOG', 'expired', 'test', 'HIGH', '{}',
                        'PROCESSING', ?, ?, now() - interval '1 second')
                """, id, attempts, UUID.randomUUID());
        return id;
    }

    private UUID enqueue(String channel, String recipient) throws Exception {
        var response = mvc.perform(post("/notifications").header(SecurityConfig.HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(request(channel, recipient)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse();
        JsonNode result = json.readTree(response.getContentAsString());
        UUID id = UUID.fromString(result.get("id").asText());
        assertThat(response.getHeader("Location")).isEqualTo("/notifications/" + id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE id = ?", Long.class, id)).isEqualTo(1L);
        return id;
    }

    private String request(String channel, String recipient) throws Exception {
        return json.writeValueAsString(Map.of("recipient", recipient, "channel", channel, "subject", "test",
                "body", "body", "priority", "MEDIUM", "metadata", Map.of("source", "test")));
    }

    private void assertState(UUID id, String state, int attempts) throws Exception {
        mvc.perform(get("/notifications/{id}", id).header(SecurityConfig.HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(state))
                .andExpect(jsonPath("$.attempts").value(attempts));
    }

    private static String target(String path) {
        return "http://127.0.0.1:" + RECEIVER.getAddress().getPort() + path;
    }

    private static HttpServer startReceiver() {
        try {
            var receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            receiver.createContext("/", exchange -> {
                try (exchange) {
                    DELIVERIES.add(new Delivery(exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                            exchange.getRequestMethod(), exchange.getRequestBody().readAllBytes()));
                    int status = "/sent".equals(exchange.getRequestURI().getPath()) ? 204 : 503;
                    exchange.sendResponseHeaders(status, -1);
                }
            });
            receiver.start();
            return receiver;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    record Delivery(String key, String method, byte[] body) {}
}
