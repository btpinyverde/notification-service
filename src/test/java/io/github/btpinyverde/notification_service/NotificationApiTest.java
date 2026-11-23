package io.github.btpinyverde.notification_service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = NotificationApi.class, properties = "notifications.api-key=test-key")
@Import({SecurityConfig.class, ServiceTargets.class})
class NotificationApiTest {
    private static final String VALID = """
            {"recipient":"demo","channel":"LOG","subject":"Prueba","body":"Hola","priority":"HIGH"}
            """;

    @Autowired MockMvc mvc;
    @MockitoBean Notifications notifications;

    @Test
    void returnsAcceptedWithThePersistedId() throws Exception {
        var id = UUID.randomUUID();
        when(notifications.create(any())).thenReturn(id);

        mvc.perform(request(VALID).header(SecurityConfig.HEADER, "test-key"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/notifications/" + id))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void rejectsAnUnauthenticatedRequestBeforePersistence() throws Exception {
        mvc.perform(request(VALID)).andExpect(status().isUnauthorized());
        mvc.perform(request(VALID).header(SecurityConfig.HEADER, "invalid"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(notifications);
    }

    @Test
    void describesMissingFieldsWithoutPersistingTheRequest() throws Exception {
        mvc.perform(request("{}").header(SecurityConfig.HEADER, "test-key"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'priority')]").isNotEmpty());
        verifyNoInteractions(notifications);
    }

    @Test
    void rejectsNumericEnumValues() throws Exception {
        mvc.perform(request(VALID.replace("\"LOG\"", "0")).header(SecurityConfig.HEADER, "test-key"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(notifications);
    }

    @Test
    void rejectsAnUnconfiguredServiceDestination() throws Exception {
        mvc.perform(request(VALID.replace("\"LOG\"", "\"SERVICE\"")).header(SecurityConfig.HEADER, "test-key"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("recipient debe ser una URL SERVICE habilitada"));
        verifyNoInteractions(notifications);
    }

    private static MockHttpServletRequestBuilder request(String json) {
        return post("/notifications").contentType(MediaType.APPLICATION_JSON).content(json);
    }
}
