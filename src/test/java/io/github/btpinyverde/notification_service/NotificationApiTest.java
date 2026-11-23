package io.github.btpinyverde.notification_service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
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

@WebMvcTest (NotificationApi.class)
class NotificationApiTest {
    private static final String VALID = """
        {
            "recipient": "demo",
            "channel": "LOG",
            "subject": "Prueba",
            "body": "Hola mundo",
            "priority": "HIGH"
        }
        """;

    @Autowired  MockMvc mvc;
    @MockitoBean Notifications notifications;

    @Test
    void returnsAcceptedWithThePersistedId() throws Exception {
        var id = UUID.randomUUID();
        when(notifications.create(any())).thenReturn(id);
        mvc.perform(request(VALID))
            .andExpect(status().isAccepted())
            .andExpect(header().string("Location", "/notifications/" + id))
            .andExpect (jsonPath("$.id").value(id.toString()))
            .andExpect (jsonPath("$.status").value("PENDING"));
    }

    @Test 
    void rejectsNumericEnumValues() throws Exception {
        mvc.perform(request(VALID.replace("\"LOG\"", "0")))
            .andExpect(status().isBadRequest());
            verifyNoInteractions(notifications);
    }

    private static MockHttpServletRequestBuilder request(String json) {
        return post("/notifications")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json);
    }
    
    
}
