package com.stopbell.user.auth.identity;

import com.stopbell.user.entity.AuthProvider;

public record ExternalIdentity(AuthProvider provider, String providerUserId) {
}
