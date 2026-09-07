package com.stopbell.user.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GooglePropertiesTest {

    @Test
    @DisplayName("Google Server Client ID가 비어 있으면 설정 생성에 실패한다")
    void reject_blank_server_client_id() {
        assertThatThrownBy(() -> new GoogleProperties(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Google server client ID must not be blank");
    }
}
