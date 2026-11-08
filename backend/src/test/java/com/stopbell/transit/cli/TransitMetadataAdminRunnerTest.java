package com.stopbell.transit.cli;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.service.BusMetadataBootstrapService;
import com.stopbell.transit.service.TransitMetadataChangeApprovalService;
import com.stopbell.transit.service.TransitMetadataUnsafeChangeReviewService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

class TransitMetadataAdminRunnerTest {
    private final TransitMetadataChangeApprovalService approvals = mock(TransitMetadataChangeApprovalService.class);
    private final TransitMetadataUnsafeChangeReviewService reviews = mock(TransitMetadataUnsafeChangeReviewService.class);
    private final BusMetadataBootstrapService bootstrap = mock(BusMetadataBootstrapService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Environment environment = mock(Environment.class);
    private final ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
    private final TransitMetadataAdminRunner runner = new TransitMetadataAdminRunner(approvals, reviews, bootstrap, jdbc, environment, context);

    @Test @DisplayName("적용 명령은 승인 ID와 명시적 확인 없이 실행되지 않는다")
    void apply_requires_explicit_confirmation_and_approval() {
        when(environment.acceptsProfiles(any(org.springframework.core.env.Profiles.class))).thenReturn(true);
        assertThatThrownBy(() -> runner.run(args("--metadata.command=apply", "--approval=1", "--provider=TAGO")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(bootstrap);
        assertThatThrownBy(() -> runner.run(args("--metadata.command=apply", "--confirm=APPLY", "--provider=TAGO")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(bootstrap);
        runner.run(args("--metadata.command=apply", "--confirm=APPLY", "--provider=TAGO", "--approval=1"));
        verify(bootstrap).bootstrapWithApproval(TransitProvider.TAGO, 1);
    }
    @Test @DisplayName("승인 명령은 검토 내용을 표시하고 두 지문과 최종 확인을 요구한다")
    void approve_requires_complete_inputs_and_confirmation() {
        when(environment.acceptsProfiles(any(org.springframework.core.env.Profiles.class))).thenReturn(true);
        when(jdbc.queryForMap(anyString(), anyLong())).thenReturn(Map.of("status", "FAILED"));
        when(reviews.find(1, 0, 10)).thenReturn(new TransitMetadataUnsafeChangeReviewService.Page(1, TransitProvider.TAGO, "x", 1, List.of()));
        when(reviews.find(1, 0, 1)).thenReturn(new TransitMetadataUnsafeChangeReviewService.Page(1, TransitProvider.TAGO, "x", 1, List.of()));
        assertThatThrownBy(() -> runner.run(args("--metadata.command=approve", "--run=1", "--size=10"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runner.run(args("--metadata.command=approve", "--run=1", "--size=10", "--confirm=APPROVE"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(approvals);
        verify(jdbc, times(2)).queryForMap(anyString(), eq(1L));
    }
    @Test @DisplayName("비웹 로컬 실행 확인이 없으면 관리 명령이 실행되지 않는다")
    void refuses_non_local_or_web_invocations() {
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments("--metadata.command=status", "--approval=1"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(approvals, reviews, bootstrap, jdbc);
    }
    private static DefaultApplicationArguments args(String... command) {
        var values = new java.util.ArrayList<>(List.of("--spring.main.web-application-type=none", "--transit.metadata.admin.enabled=true"));
        values.addAll(List.of(command));
        return new DefaultApplicationArguments(values.toArray(String[]::new));
    }
}
