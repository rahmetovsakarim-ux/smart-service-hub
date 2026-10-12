package kz.smarthub.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import kz.smarthub.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end through the real Spring Security filter chain, controllers, services and an in-memory H2 database
 * (profile "h2" also loads the demo accounts). The helper methods behave like the browser SPA: they read the
 * XSRF-TOKEN cookie and echo it in the X-XSRF-TOKEN header.
 */
// own in-memory database, so test classes with different Spring contexts never share or drop each other's data
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:flowtest;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class ApiFlowIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private UserRepository users;

    // ---------------------------------------------------------------- helpers

    private Cookie xsrfCookie() throws Exception {
        MvcResult r = mvc.perform(get("/api/catalog/districts")).andExpect(status().isOk()).andReturn();
        Cookie cookie = r.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).as("the server must hand out a CSRF cookie").isNotNull();
        return cookie;
    }

    private ResultActions send(MockHttpServletRequestBuilder request, MockHttpSession session, Object body) throws Exception {
        Cookie xsrf = xsrfCookie();
        request.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue());
        if (session != null) {
            request.session(session);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MockHttpSession login(String login, String password) throws Exception {
        MvcResult r = send(post("/api/auth/login"), null, Map.of("login", login, "password", password))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private String workerEmail(long workerId) {
        return users.findById(workerId).orElseThrow().getEmail();
    }

    // ---------------------------------------------------------------- access control

    @Test
    void protectedEndpointsRequireLogin() throws Exception {
        mvc.perform(get("/api/client/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void theCatalogIsPublic() throws Exception {
        mvc.perform(get("/api/catalog/services")).andExpect(status().isOk());
        mvc.perform(get("/api/catalog/categories")).andExpect(status().isOk());
    }

    @Test
    void stateChangingRequestsWithoutACsrfTokenAreRefused() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("login", "client@hub.kz", "password", "Client123!"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void rolesAreSeparated() throws Exception {
        MockHttpSession client = login("client@hub.kz", "Client123!");
        MockHttpSession worker = login("worker1@hub.kz", "Worker123!");
        MockHttpSession admin = login("admin@hub.kz", "Admin123!");

        mvc.perform(get("/api/admin/stats").session(client)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/stats").session(worker)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/stats").session(admin)).andExpect(status().isOk());
        mvc.perform(get("/api/worker/orders").session(client)).andExpect(status().isForbidden());
        mvc.perform(get("/api/client/orders").session(worker)).andExpect(status().isForbidden());
    }

    @Test
    void wrongPasswordsAreRefusedAndRepeatedFailuresAreThrottled() throws Exception {
        String victim = "throttle-test@hub.kz"; // unknown account: failures are still counted per ip + login
        for (int i = 0; i < 5; i++) {
            send(post("/api/auth/login"), null, Map.of("login", victim, "password", "wrong-" + i))
                    .andExpect(status().isUnauthorized());
        }
        send(post("/api/auth/login"), null, Map.of("login", victim, "password", "wrong-again"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void loginRotatesAnExistingSessionId() throws Exception {
        MockHttpSession planted = new MockHttpSession();
        planted.setAttribute("anything", "x"); // an anonymous session that exists before the login
        String before = planted.getId();

        Cookie xsrf = xsrfCookie();
        MvcResult r = mvc.perform(post("/api/auth/login").session(planted).cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("login", "client@hub.kz", "password", "Client123!"))))
                .andExpect(status().isOk()).andReturn();

        assertThat(r.getRequest().getSession(false).getId()).isNotEqualTo(before);
    }

    // ---------------------------------------------------------------- the whole order lifecycle

    @Test
    void anOrderGoesFromBookingToPaymentAndReview() throws Exception {
        MockHttpSession client = login("client@hub.kz", "Client123!");

        // pick the demo "refrigerator repair" service (category Ремонт)
        JsonNode catalog = json.readTree(mvc.perform(get("/api/catalog/services")).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        long serviceId = -1;
        for (JsonNode s : catalog) {
            if ("Ремонт".equals(s.get("categoryNameRu").asText())) {
                serviceId = s.get("id").asLong();
                break;
            }
        }
        assertThat(serviceId).isPositive();

        // 1) the client books; the system assigns a specialist automatically
        LocalDateTime when = LocalDateTime.now().plusDays(30).withHour(10).withMinute(0).withSecond(0).withNano(0);
        JsonNode created = read(send(post("/api/client/orders"), client, Map.of(
                "serviceId", serviceId, "address", "Abay 1", "district", "CENTER",
                "scheduledTime", when.toString(), "note", "integration test"))
                .andExpect(status().isCreated()));
        long orderId = created.get("id").asLong();
        assertThat(created.get("status").asText()).isEqualTo("NEW");
        assertThat(created.get("workerId").isNull()).as("a repair specialist is free in 30 days").isFalse();

        MockHttpSession worker = login(workerEmail(created.get("workerId").asLong()), "Worker123!");

        // 2) paying or reviewing before completion is refused
        send(post("/api/client/orders/" + orderId + "/pay"), client, Map.of("cardNumber", "4242424242424242"))
                .andExpect(status().isBadRequest());

        // 3) statuses cannot be skipped
        send(post("/api/worker/orders/" + orderId + "/status"), worker, Map.of("status", "COMPLETED"))
                .andExpect(status().isBadRequest());

        // 4) the specialist moves the order along the chain
        send(post("/api/worker/orders/" + orderId + "/accept"), worker, null).andExpect(status().isOk());
        send(post("/api/worker/orders/" + orderId + "/status"), worker, Map.of("status", "IN_PROGRESS")).andExpect(status().isOk());
        JsonNode done = read(send(post("/api/worker/orders/" + orderId + "/status"), worker, Map.of("status", "COMPLETED"))
                .andExpect(status().isOk()));
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");

        // 5) a stranger cannot see or pay the order
        String phone = "+7700" + String.format("%07d", Math.floorMod(System.nanoTime(), 10_000_000L));
        send(post("/api/auth/register"), null, Map.of("fullName", "Other Client", "phone", phone,
                "password", "secret123", "role", "CLIENT")).andExpect(status().isCreated());
        MockHttpSession stranger = login(phone, "secret123");
        mvc.perform(get("/api/orders/" + orderId).session(stranger)).andExpect(status().isForbidden());
        send(post("/api/client/orders/" + orderId + "/pay"), stranger, Map.of("cardNumber", "4242424242424242"))
                .andExpect(status().isForbidden());

        // 6) payment: a declined card can be retried, a successful one can only happen once
        JsonNode declined = read(send(post("/api/client/orders/" + orderId + "/pay"), client, Map.of("cardNumber", "4000000000000002"))
                .andExpect(status().isOk()));
        assertThat(declined.get("status").asText()).isEqualTo("FAILED");

        JsonNode paid = read(send(post("/api/client/orders/" + orderId + "/pay"), client, Map.of("cardNumber", "4242424242424242"))
                .andExpect(status().isOk()));
        assertThat(paid.get("status").asText()).isEqualTo("PAID");

        send(post("/api/client/orders/" + orderId + "/pay"), client, Map.of("cardNumber", "4242424242424242"))
                .andExpect(status().isConflict());

        // 7) review: once only
        send(post("/api/client/orders/" + orderId + "/review"), client, Map.of("rating", 5, "comment", "great"))
                .andExpect(status().isCreated());
        send(post("/api/client/orders/" + orderId + "/review"), client, Map.of("rating", 4, "comment", "again"))
                .andExpect(status().isConflict());

        // 8) the final picture
        JsonNode finalOrder = json.readTree(mvc.perform(get("/api/orders/" + orderId).session(client)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(finalOrder.get("paid").asBoolean()).isTrue();
        assertThat(finalOrder.get("reviewed").asBoolean()).isTrue();
        assertThat(finalOrder.get("history").size()).isGreaterThanOrEqualTo(4);
    }
}
