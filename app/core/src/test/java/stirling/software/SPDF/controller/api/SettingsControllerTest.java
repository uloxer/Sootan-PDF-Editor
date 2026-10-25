package stirling.software.SPDF.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import stirling.software.SPDF.config.EndpointConfiguration;
import stirling.software.common.model.ApplicationProperties;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SettingsControllerTest {

    @Mock private ApplicationProperties applicationProperties;
    @Mock private EndpointConfiguration endpointConfiguration;
    @Mock private ApplicationProperties.System system;

    private SettingsController settingsController;

    @BeforeEach
    void setUp() {
        settingsController = new SettingsController(applicationProperties, endpointConfiguration);
    }

    @Nested
    @DisplayName("getDisabledEndpoints (get-endpoints-status)")
    class GetDisabledEndpoints {

        @Test
        @DisplayName("returns 200 OK with the endpoint status map from EndpointConfiguration")
        void returnsEndpointStatuses() {
            Map<String, Boolean> statuses = new ConcurrentHashMap<>();
            statuses.put("merge-pdfs", Boolean.TRUE);
            statuses.put("remove-blanks", Boolean.FALSE);
            when(endpointConfiguration.getEndpointStatuses()).thenReturn(statuses);

            ResponseEntity<Map<String, Boolean>> response =
                    settingsController.getDisabledEndpoints();

            assertNotNull(response);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertSame(statuses, response.getBody());
            assertEquals(Boolean.TRUE, response.getBody().get("merge-pdfs"));
            assertEquals(Boolean.FALSE, response.getBody().get("remove-blanks"));
            verify(endpointConfiguration).getEndpointStatuses();
        }

        @Test
        @DisplayName("returns 200 OK with an empty map when no statuses are configured")
        void returnsEmptyMap() {
            Map<String, Boolean> statuses = new HashMap<>();
            when(endpointConfiguration.getEndpointStatuses()).thenReturn(statuses);

            ResponseEntity<Map<String, Boolean>> response =
                    settingsController.getDisabledEndpoints();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            assertTrue(response.getBody().isEmpty());
        }
    }
}
