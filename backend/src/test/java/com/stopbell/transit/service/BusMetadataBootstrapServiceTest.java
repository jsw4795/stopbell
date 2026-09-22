package com.stopbell.transit.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.metadata.SeoulCsvMetadataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

class BusMetadataBootstrapServiceTest {

    @Test
    @DisplayName("source parser 검증 실패 시 provider cleanup 경로를 호출하지 않는다")
    void skip_sync_when_source_validation_fails() {
        SeoulCsvMetadataSource parser = new SeoulCsvMetadataSource(
                resource("노선ID,다른노선명,노선유형,거리\n"),
                resource("노선ID,노드ID,링크거리누계,정류장순번\n"),
                resource("정류장ID,정류장명,정류장유형,정류장번호,좌표X,좌표Y,BIT설치여부\n")
        );
        BusMetadataSource source = new BusMetadataSource() {
            @Override
            public TransitProvider provider() {
                return TransitProvider.SEOUL_BUS;
            }

            @Override
            public CompleteBusMetadataSnapshot fetchCompleteSnapshot() {
                parser.fetchCompleteSourceSnapshot();
                throw new AssertionError("Invalid source parser result must not reach provider conversion");
            }
        };
        BusMetadataSyncService syncService = mock(BusMetadataSyncService.class);
        BusMetadataBootstrapService bootstrapService = new BusMetadataBootstrapService(List.of(source), syncService);

        assertThatThrownBy(() -> bootstrapService.bootstrap(TransitProvider.SEOUL_BUS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("headers do not match");

        verifyNoInteractions(syncService);
    }

    private ByteArrayResource resource(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }
}
