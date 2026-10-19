package com.stopbell.transit.controller;

import com.stopbell.transit.dto.BoardingCandidatesResponse;
import com.stopbell.transit.service.BoardingCandidateService;
import com.stopbell.user.auth.service.JwtTokenService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BoardingCandidateController {
    private final BoardingCandidateService service;
    private final JwtTokenService jwtTokenService;

    public BoardingCandidateController(BoardingCandidateService service, JwtTokenService jwtTokenService) {
        this.service = service;
        this.jwtTokenService = jwtTokenService;
    }

    @GetMapping("/api/v1/bus-routes/{routeId}/boarding-candidates")
    public BoardingCandidatesResponse find(
            @AuthenticationPrincipal Jwt jwt, @PathVariable Long routeId,
            @RequestParam Long targetStopOccurrenceId,
            @RequestParam(required = false) Long boardingStopOccurrenceId
    ) {
        return service.find(jwtTokenService.extractUserId(jwt), routeId,
                targetStopOccurrenceId, boardingStopOccurrenceId);
    }
}
