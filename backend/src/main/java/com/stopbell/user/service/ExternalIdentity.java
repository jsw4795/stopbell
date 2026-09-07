package com.stopbell.user.service;

import com.stopbell.user.entity.AuthProvider;

public record ExternalIdentity(AuthProvider provider, String providerUserId) {
}
