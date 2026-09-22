package com.stopbell.transit.metadata;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.service.BusMetadataSource;
import com.stopbell.transit.service.BusRouteMetadataSnapshot;
import com.stopbell.transit.service.BusStopOccurrenceMetadataSnapshot;
import com.stopbell.transit.service.CompleteBusMetadataSnapshot;

public final class TagoMetadataSource implements BusMetadataSource {
    // The 31 codes returned for Gyeonggi by the documented TAGO PoC; no prefix/name heuristic is used.
    private static final Set<String> GYEONGGI_CODES = Set.of("31010","31020","31030","31040","31050","31060","31070","31080","31090","31100","31110","31120","31130","31140","31150","31160","31170","31180","31190","31200","31210","31220","31230","31240","31250","31260","31270","31280","31350","31370","31380");
    private final TagoMetadataClient client; private final long requestIntervalMillis; private final Set<String> gyeonggiCodes;
    public TagoMetadataSource(TagoMetadataClient client, long requestIntervalMillis) { this(client, requestIntervalMillis, GYEONGGI_CODES); }
    TagoMetadataSource(TagoMetadataClient client, long requestIntervalMillis, Set<String> gyeonggiCodes) { this.client=client; this.requestIntervalMillis=requestIntervalMillis; this.gyeonggiCodes=Set.copyOf(gyeonggiCodes); }
    @Override public TransitProvider provider() { return TransitProvider.TAGO; }
    @Override public CompleteBusMetadataSnapshot fetchCompleteSnapshot() {
        Set<String> discovered = new HashSet<>(); for (TagoMetadataClient.City city : pages(p -> client.cityCodes(p))) { if (city.citycode()==null) throw new IllegalStateException("TAGO city code missing"); discovered.add(city.citycode()); }
        if (!discovered.containsAll(gyeonggiCodes)) throw new IllegalStateException("TAGO city discovery did not verify every Gyeonggi city code");
        List<BusRouteMetadataSnapshot> snapshots=new ArrayList<>(); Set<String> routeIds=new HashSet<>();
        for(String city:gyeonggiCodes) for(TagoMetadataClient.Route route:pages(p->client.routes(city,p))) { required(route.routeid()); required(route.routeno()); if(!routeIds.add(route.routeid())) throw new IllegalStateException("TAGO duplicate route identity"); List<BusStopOccurrenceMetadataSnapshot> stops=new ArrayList<>(); for(TagoMetadataClient.Stop stop:pages(p->client.routeStops(city,route.routeid(),p))) stops.add(new BusStopOccurrenceMetadataSnapshot(required(stop.nodeid()),required(stop.nodenm()),stop.gpslati(),stop.gpslong(),required(stop.nodeord()))); if(stops.isEmpty()) throw new IllegalStateException("TAGO route has no stops"); snapshots.add(new BusRouteMetadataSnapshot(provider(),route.routeid(),route.routeno(),city,stops)); }
        return new CompleteBusMetadataSnapshot(provider(), snapshots);
    }
    private <T> List<T> pages(java.util.function.IntFunction<TagoMetadataClient.Page<T>> fetch) { List<T> all=new ArrayList<>(); int page=1,total=-1; while(total<0||all.size()<total){ pace(); TagoMetadataClient.Page<T> result=fetch.apply(page++); if(result.totalCount()<0||result.pageNo()!=page-1||result.items()==null) throw new IllegalStateException("TAGO pagination protocol failure"); if(total<0)total=result.totalCount(); else if(total!=result.totalCount())throw new IllegalStateException("TAGO pagination total changed"); if(result.items().isEmpty()&&all.size()<total)throw new IllegalStateException("TAGO pagination incomplete"); all.addAll(result.items()); if(all.size()>total)throw new IllegalStateException("TAGO pagination overflow"); } return all; }
    private void pace(){ if(requestIntervalMillis<=0)return; try{Thread.sleep(requestIntervalMillis);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("TAGO sync interrupted",e);} }
    private static String required(String value){if(value==null||value.isBlank())throw new IllegalStateException("TAGO required field missing");return value;} private static int required(Integer value){if(value==null||value<=0)throw new IllegalStateException("TAGO stop order missing");return value;}
}
