package com.stopbell.common.error;

import com.stopbell.user.auth.exception.InvalidRefreshTokenException;
import com.stopbell.user.auth.exception.InvalidSocialCredentialException;
import com.stopbell.user.auth.exception.SocialIdentityVerificationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodValidation(HandlerMethodValidationException exception) {
        // Input constraints are 400; return-value validation is an internal 500 failure.
        return ResponseEntity.status(exception.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiErrorResponse.forStatus(exception.getStatusCode().value()));
    }

    @ExceptionHandler({InvalidSocialCredentialException.class, InvalidRefreshTokenException.class})
    public ResponseEntity<ApiErrorResponse> handleAuthenticationFailure(Exception exception) {
        return authenticationError(ErrorCode.UNAUTHENTICATED);
    }

    @ExceptionHandler(SocialIdentityVerificationException.class)
    public ResponseEntity<ApiErrorResponse> handleSocialIdentityUnavailable(SocialIdentityVerificationException exception) {
        return authenticationError(ErrorCode.SOCIAL_IDENTITY_UNAVAILABLE);
    }

    private ResponseEntity<ApiErrorResponse> authenticationError(ErrorCode code) {
        return ResponseEntity.status(code.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(code.name(), code.getMessage()));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(new ApiErrorResponse(errorCode.name(), exception.getMessage()));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(Exception exception) {
        ErrorCode errorCode = ErrorCode.INVALID_REQUEST;
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(new ApiErrorResponse(errorCode.name(), errorCode.getMessage()));
    }
}
