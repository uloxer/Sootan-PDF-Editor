package stirling.software.SPDF.controller.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;

import io.swagger.v3.oas.annotations.Hidden;

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.config.EndpointConfiguration;
import stirling.software.common.annotations.api.SettingsApi;
import stirling.software.common.model.ApplicationProperties;

@SettingsApi
@RequiredArgsConstructor
@Hidden
public class SettingsController {

    private final ApplicationProperties applicationProperties;
    private final EndpointConfiguration endpointConfiguration;

    @GetMapping("/get-endpoints-status")
    @Hidden
    public ResponseEntity<Map<String, Boolean>> getDisabledEndpoints() {
        return ResponseEntity.ok(endpointConfiguration.getEndpointStatuses());
    }
}
