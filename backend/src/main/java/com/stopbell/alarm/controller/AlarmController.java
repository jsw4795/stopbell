package com.stopbell.alarm.controller;

import java.util.List;

import com.stopbell.alarm.dto.AlarmResponse;
import com.stopbell.alarm.dto.CreateAlarmRequest;
import com.stopbell.alarm.service.AlarmService;
import com.stopbell.user.auth.service.JwtTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AlarmController {

    private final AlarmService alarmService;
    private final JwtTokenService jwtTokenService;

    public AlarmController(AlarmService alarmService, JwtTokenService jwtTokenService) {
        this.alarmService = alarmService;
        this.jwtTokenService = jwtTokenService;
    }

    @PostMapping("/api/v1/alarms")
    @ResponseStatus(HttpStatus.CREATED)
    public AlarmResponse create(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateAlarmRequest request) {
        return alarmService.create(jwtTokenService.extractUserId(jwt), request);
    }

    @GetMapping("/api/v1/alarms")
    public List<AlarmResponse> findAll(@AuthenticationPrincipal Jwt jwt) {
        return alarmService.findAll(jwtTokenService.extractUserId(jwt));
    }
}
