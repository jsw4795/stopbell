package com.stopbell.transit.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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

    @Test
    @DisplayName("경기도 allowlist는 여주시 31320을 사용하고 과거 31280은 요청하지 않는다")
    void uses_current_yeoju_city_code() {
        TagoMetadataClient client=Mockito.mock(TagoMetadataClient.class); TagoMetadataSource source=new TagoMetadataSource(client,0);
        when(client.cityCodes()).thenReturn(currentGyeonggiCities());
        when(client.routes(anyString(),anyInt())).thenReturn(page(List.of(),0,1));
        when(client.routes("31320",1)).thenReturn(page(List.of(new TagoMetadataClient.Route("yeoju","100")),1,1));
        when(client.routeStops("31320","yeoju",1)).thenReturn(page(List.of(new TagoMetadataClient.Stop("stop","여주",1,null,null)),1,1));

        assertThat(source.fetchCompleteSnapshot().routes()).singleElement()
                .satisfies(route -> assertThat(route.cityCode()).isEqualTo("31320"));

        verify(client).routes("31320",1);
        verify(client,never()).routes("31280",1);
    }

    private static List<TagoMetadataClient.City> currentGyeonggiCities(){return List.of(
            new TagoMetadataClient.City("31010","수원시"),new TagoMetadataClient.City("31020","성남시"),new TagoMetadataClient.City("31030","의정부시"),new TagoMetadataClient.City("31040","안양시"),new TagoMetadataClient.City("31050","부천시"),new TagoMetadataClient.City("31060","광명시"),new TagoMetadataClient.City("31070","평택시"),new TagoMetadataClient.City("31080","동두천시"),new TagoMetadataClient.City("31090","안산시"),new TagoMetadataClient.City("31100","고양시"),new TagoMetadataClient.City("31110","과천시"),new TagoMetadataClient.City("31120","구리시"),new TagoMetadataClient.City("31130","남양주시"),new TagoMetadataClient.City("31140","오산시"),new TagoMetadataClient.City("31150","시흥시"),new TagoMetadataClient.City("31160","군포시"),new TagoMetadataClient.City("31170","의왕시"),new TagoMetadataClient.City("31180","하남시"),new TagoMetadataClient.City("31190","용인시"),new TagoMetadataClient.City("31200","파주시"),new TagoMetadataClient.City("31210","이천시"),new TagoMetadataClient.City("31220","안성시"),new TagoMetadataClient.City("31230","김포시"),new TagoMetadataClient.City("31240","화성시"),new TagoMetadataClient.City("31250","광주시"),new TagoMetadataClient.City("31260","양주시"),new TagoMetadataClient.City("31270","포천시"),new TagoMetadataClient.City("31320","여주시"),new TagoMetadataClient.City("31350","연천군"),new TagoMetadataClient.City("31370","가평군"),new TagoMetadataClient.City("31380","양평군"));}

    private static <T> TagoMetadataClient.Page<T> page(List<T> items,int total,int number){return new TagoMetadataClient.Page<>(items,total,number);}
}
