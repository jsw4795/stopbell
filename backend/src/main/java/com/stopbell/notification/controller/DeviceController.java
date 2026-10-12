package com.stopbell.notification.controller;

import com.stopbell.notification.dto.DeviceResponse;
import com.stopbell.notification.dto.DeviceStateResponse;
import com.stopbell.notification.dto.DisableDeviceRequest;
import com.stopbell.notification.dto.RegisterDeviceRequest;
import com.stopbell.notification.service.DeviceService;
import com.stopbell.user.auth.service.JwtTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DeviceController {

    private static final Logger log = LoggerFactory.getLogger(DeviceController.class);
    private static final String CREDENTIAL_HEADER = "X-Installation-Credential";

    private final DeviceService deviceService;
    private final JwtTokenService jwtTokenService;

    public DeviceController(DeviceService deviceService, JwtTokenService jwtTokenService) {
        this.deviceService = deviceService;
        this.jwtTokenService = jwtTokenService;
    }

    @PostMapping("/api/v1/devices")
    public ResponseEntity<DeviceResponse> register(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = CREDENTIAL_HEADER, required = false) String credential,
            @RequestBody RegisterDeviceRequest request
    ) {
        DeviceService.RegistrationResult result = deviceService.register(
                jwtTokenService.extractUserId(jwt), credential, request
        );
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.response());
    }

    @GetMapping("/api/v1/devices/{installationId}")
    public DeviceStateResponse findState(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = CREDENTIAL_HEADER, required = false) String credential,
            @PathVariable String installationId
    ) {
        return deviceService.findState(jwtTokenService.extractUserId(jwt), credential, installationId);
    }

    @PostMapping("/api/v1/devices/disable")
    public DeviceResponse disable(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = CREDENTIAL_HEADER, required = false) String credential,
            @RequestBody DisableDeviceRequest request
    ) {
        return deviceService.disable(jwtTokenService.extractUserId(jwt), credential, request);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<Void> handlePersistenceFailure(Exception exception) {
        // JDBC exception messages can include raw UUID/FID values. Do not log the cause.
        log.error("Device persistence failed: {}", exception.getClass().getSimpleName());
        return ResponseEntity.internalServerError().build();
    }
}
