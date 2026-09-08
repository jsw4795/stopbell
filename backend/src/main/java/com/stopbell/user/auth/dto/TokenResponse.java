package com.stopbell.user.auth.dto;

public record TokenResponse(String accessToken, String refreshToken) {
}
