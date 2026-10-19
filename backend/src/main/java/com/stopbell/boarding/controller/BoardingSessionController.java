package com.stopbell.boarding.controller;

import java.util.List;

import com.stopbell.boarding.dto.BoardingSessionResponse;
import com.stopbell.boarding.dto.CancelBoardingSessionRequest;
import com.stopbell.boarding.dto.CreateBoardingSessionRequest;
import com.stopbell.boarding.dto.ReconfirmBoardingSessionRequest;
import com.stopbell.boarding.service.BoardingSessionService;
import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.user.auth.service.JwtTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.TransactionException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/boarding-sessions")
public class BoardingSessionController {
    private static final Logger log = LoggerFactory.getLogger(BoardingSessionController.class);
    private final BoardingSessionService service;
    private final JwtTokenService jwtTokens;

    public BoardingSessionController(BoardingSessionService service, JwtTokenService jwtTokens) {
        this.service = service;
        this.jwtTokens = jwtTokens;
    }

    @PostMapping
    public ResponseEntity<BoardingSessionResponse> create(@AuthenticationPrincipal Jwt jwt,
            @RequestBody CreateBoardingSessionRequest request) {
        var result = service.create(jwtTokens.extractUserId(jwt), request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.response());
    }

    @GetMapping("/{sessionId}")
    public BoardingSessionResponse find(@AuthenticationPrincipal Jwt jwt, @PathVariable Long sessionId) {
        return service.find(jwtTokens.extractUserId(jwt), sessionId);
    }

    @GetMapping
    public List<BoardingSessionResponse> find(@AuthenticationPrincipal Jwt jwt,
            @RequestParam MultiValueMap<String, String> query) {
        if (query.size() != 1 || query.values().stream().anyMatch(values -> values.size() != 1)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        if (query.containsKey("scope") && "current".equals(query.getFirst("scope"))) {
            return service.findCurrent(jwtTokens.extractUserId(jwt));
        }
        if (query.containsKey("clientRequestId")) {
            return service.findRequest(jwtTokens.extractUserId(jwt), query.getFirst("clientRequestId"));
        }
        throw new ApiException(ErrorCode.INVALID_REQUEST);
    }

    @PostMapping("/{sessionId}/reconfirm")
    public BoardingSessionResponse reconfirm(@AuthenticationPrincipal Jwt jwt, @PathVariable Long sessionId,
            @RequestBody ReconfirmBoardingSessionRequest request) {
        return service.reconfirm(jwtTokens.extractUserId(jwt), sessionId, request);
    }

    @PostMapping("/{sessionId}/cancel")
    public BoardingSessionResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable Long sessionId,
            @RequestBody CancelBoardingSessionRequest request) {
        return service.cancel(jwtTokens.extractUserId(jwt), sessionId, request.expectedRevision());
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<Void> persistenceFailure(Exception exception) {
        log.error("Boarding persistence failed: {}", exception.getClass().getSimpleName());
        return ResponseEntity.internalServerError().build();
    }
}
