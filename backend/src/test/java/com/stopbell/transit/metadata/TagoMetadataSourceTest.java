package com.stopbell.transit.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TagoMetadataSourceTest {
    @Test
    @DisplayName("TAGO Route와 Route Stop의 모든 페이지를 수집한 뒤 complete snapshot을 만든다")
    void collects_every_route_and_stop_page_before_creating_complete_snapshot() {
        TagoMetadataClient client=Mockito.mock(TagoMetadataClient.class); TagoMetadataSource source=new TagoMetadataSource(client,0, Set.of("31010"));
        when(client.cityCodes()).thenReturn(List.of(new TagoMetadataClient.City("31010","수원시")));
        when(client.routes("31010",1)).thenReturn(page(List.of(new TagoMetadataClient.Route("r1","1")),2,1));
        when(client.routes("31010",2)).thenReturn(page(List.of(new TagoMetadataClient.Route("r2","2")),2,2));
        when(client.routeStops("31010","r1",1)).thenReturn(page(List.of(new TagoMetadataClient.Stop("s1","A",1,null,null)),1,1));
        when(client.routeStops("31010","r2",1)).thenReturn(page(List.of(new TagoMetadataClient.Stop("s2","B",1,null,null)),2,1));
        when(client.routeStops("31010","r2",2)).thenReturn(page(List.of(new TagoMetadataClient.Stop("s3","C",2,null,null)),2,2));
        assertThat(source.fetchCompleteSnapshot().routes()).hasSize(2);
        verify(client).cityCodes();
    }

    @Test
    @DisplayName("TAGO Route Stop의 빈 중간 페이지를 거부한다")
    void rejects_incomplete_stop_pagination() {
        TagoMetadataClient client=Mockito.mock(TagoMetadataClient.class); TagoMetadataSource source=new TagoMetadataSource(client,0, Set.of("31010"));
        when(client.cityCodes()).thenReturn(List.of(new TagoMetadataClient.City("31010","수원시")));
        when(client.routes("31010",1)).thenReturn(page(List.of(new TagoMetadataClient.Route("r1","1")),1,1));
        when(client.routeStops("31010","r1",1)).thenReturn(page(List.of(),1,1));
        assertThatThrownBy(source::fetchCompleteSnapshot).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("TAGO Route 페이지의 totalCount 변경을 거부한다")
    void rejects_route_pagination_with_changed_total() {
        TagoMetadataClient client=Mockito.mock(TagoMetadataClient.class); TagoMetadataSource source=new TagoMetadataSource(client,0, Set.of("31010"));
        when(client.cityCodes()).thenReturn(List.of(new TagoMetadataClient.City("31010","수원시")));
        when(client.routes("31010",1)).thenReturn(page(List.of(new TagoMetadataClient.Route("r1","1")),2,1));
        when(client.routes("31010",2)).thenReturn(page(List.of(new TagoMetadataClient.Route("r2","2")),3,2));

        assertThatThrownBy(source::fetchCompleteSnapshot).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("TAGO Route Stop 페이지의 pageNo 불일치를 거부한다")
    void rejects_stop_pagination_with_unexpected_page_number() {
        TagoMetadataClient client=Mockito.mock(TagoMetadataClient.class); TagoMetadataSource source=new TagoMetadataSource(client,0, Set.of("31010"));
        when(client.cityCodes()).thenReturn(List.of(new TagoMetadataClient.City("31010","수원시")));
        when(client.routes("31010",1)).thenReturn(page(List.of(new TagoMetadataClient.Route("r1","1")),1,1));
        when(client.routeStops("31010","r1",1)).thenReturn(page(List.of(new TagoMetadataClient.Stop("s1","A",1,null,null)),1,2));

        assertThatThrownBy(source::fetchCompleteSnapshot).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("TAGO Route 페이지의 totalCount 초과를 거부한다")
    void rejects_route_pagination_overflow() {
        TagoMetadataClient client=Mockito.mock(TagoMetadataClient.class); TagoMetadataSource source=new TagoMetadataSource(client,0, Set.of("31010"));
        when(client.cityCodes()).thenReturn(List.of(new TagoMetadataClient.City("31010","수원시")));
        when(client.routes("31010",1)).thenReturn(page(List.of(new TagoMetadataClient.Route("r1","1"),new TagoMetadataClient.Route("r2","2")),1,1));

        assertThatThrownBy(source::fetchCompleteSnapshot).isInstanceOf(IllegalStateException.class);
    }
    private static <T> TagoMetadataClient.Page<T> page(List<T> items,int total,int number){return new TagoMetadataClient.Page<>(items,total,number);}
}
