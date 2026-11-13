package com.stopbell.boarding.service;

import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.stopbell.boarding.domain.BoardingProgressObservation;
import com.stopbell.boarding.domain.BoardingTrackingSnapshot;
import com.stopbell.boarding.entity.BoardingStateReason;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.mapper.BoardingCandidateMapper;
import com.stopbell.transit.mapper.BoardingCandidateMapper.Vehicle;
import com.stopbell.transit.service.VehiclePollingCycle;

@Component
public class BoardingVehicleTracker {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BoardingVehicleTracker.class);
	
	public BoardingVehicleTracker(
	        @Qualifier("transitMetadataClock") Clock clock) {
	    // 기존 테스트와 Spring DI 호환성을 위해 유지
	}

    public record Input(BoardingProgressObservation observation, BoardingStateReason reason) {
        static Input held(BoardingStateReason reason) { return new Input(null, reason); }
    }

    public Input observe(BoardingTrackingSnapshot session, VehiclePollingCycle cycle) {
        List<Vehicle> roster;
        if (session.provider() == TransitProvider.TAGO) {
            var received = cycle.tago(session.routeId(), session.cityCode());
            var tago = received == null || received.response() == null ? null : received.response().response();
            if (tago == null || tago.body() == null) {
                return unavailable(session, "tago roster body missing");
            }
            var items = tago.body().items();
            roster = items == null || items.item() == null ? List.of() : items.item().stream()
                    .map(item -> BoardingCandidateMapper.tago(item, received.observedAt())).toList();
        } else {
            var received = cycle.roster(session.routeId());
            var response = received == null ? null : received.response();
            if (response == null || response.header() == null) {
                return unavailable(session, "seoul roster header missing");
            }
            if ("4".equals(response.header().headerCd())) {
                roster = List.of();
            } else if (response.body() == null || response.body().itemList() == null) {
                return unavailable(session, "seoul roster body missing");
            } else {
                roster = response.body().itemList().stream()
                        .map(item -> BoardingCandidateMapper.roster(item, received.observedAt())).toList();
            }
        }
        List<Vehicle> matches = roster.stream().filter(vehicle -> session.vehicleId().equals(vehicle.id())).toList();
        if (matches.size() > 1 || roster.stream().anyMatch(vehicle -> session.vehicleNumber().equals(vehicle.number())
                && !session.vehicleId().equals(vehicle.id()))) {
            return Input.held(BoardingStateReason.IDENTITY_CONFLICT);
        }
        if (matches.isEmpty()) {
            if (roster.stream().anyMatch(vehicle -> vehicle.id() == null)) {
                return Input.held(BoardingStateReason.IDENTITY_CONFLICT);
            }
            return Input.held(BoardingStateReason.VEHICLE_MISSING);
        }
        Vehicle vehicle = matches.getFirst();
        if (!session.vehicleNumber().equals(vehicle.number())) {
            return Input.held(BoardingStateReason.IDENTITY_CONFLICT);
        }
        if (vehicle.runEnded()) {
            // isrunyn=0 is explicit negative continuity evidence. Neither missing
            // values nor isrunyn=1 prove the same trip/lap or permit automatic recovery.
            return Input.held(BoardingStateReason.TRAVERSAL_AMBIGUOUS);
        }
        if (session.provider() == TransitProvider.SEOUL_BUS) {
            var received = cycle.detail(session.vehicleId());
            var response = received == null ? null : received.response();
            if (response == null || response.header() == null) {
                return unavailable(session, "seoul detail header missing");
            }
            var items = response.body() == null ? null : response.body().itemList();
            // Roster presence with empty detail is unavailable progress, never vehicle disappearance.
            if ("4".equals(response.header().headerCd()) || items == null || items.isEmpty()) {
                return Input.held(BoardingStateReason.STALE_PROGRESS);
            }
            if (items.size() != 1 || items.getFirst() == null) {
                return Input.held(BoardingStateReason.IDENTITY_CONFLICT);
            }
            Vehicle detail = BoardingCandidateMapper.detail(items.getFirst(), received.observedAt());
            if (!vehicle.id().equals(detail.id()) || !vehicle.number().equals(detail.number())) {
                return Input.held(BoardingStateReason.IDENTITY_CONFLICT);
            }
            vehicle = detail;
        }
        if (session.provider() == TransitProvider.SEOUL_BUS) {
            return moving(session, vehicle);
        }
        if (session.provider() == TransitProvider.TAGO) {
            if (vehicle.order() == null) {
                return Input.held(BoardingStateReason.STALE_PROGRESS);
            }
            int order = vehicle.order();
            boolean orderOnly = vehicle.stopId() == null && session.traversal().occurrences().stream()
                    .filter(occurrence -> occurrence.order() == order).count() == 1;
            if (vehicle.stopId() == null && !orderOnly) {
                return Input.held(BoardingStateReason.TRAVERSAL_AMBIGUOUS);
            }
            return new Input(new BoardingProgressObservation(session.provider(), session.routeId(), session.cityCode(),
                    vehicle, BoardingProgressObservation.Meaning.VERIFIED_REACHED, orderOnly, null, false), null);
        }
        return Input.held(BoardingStateReason.TRAVERSAL_AMBIGUOUS);
    }

    /** Provider가 형식상 성공했지만 필수 구조가 빠진 응답은 빈 차량 목록이 아니라 일시 장애로 취급한다. */
    private static Input unavailable(BoardingTrackingSnapshot session, String detail) {
        log.warn("Boarding provider response unusable: provider={}, reason={}", session.provider(), detail);
        return Input.held(BoardingStateReason.TEMPORARY_PROVIDER_FAILURE);
    }

	private Input moving(BoardingTrackingSnapshot session, Vehicle vehicle) {
		var occurrences = session.traversal().occurrences();

		for (var occurrence : occurrences) {
			if (Integer.valueOf(occurrence.order()).equals(vehicle.order())
					&& occurrence.externalStopId().equals(vehicle.stopId())) {

				return new Input(
						new BoardingProgressObservation(session.provider(), session.routeId(), session.cityCode(),
								vehicle, BoardingProgressObservation.Meaning.VERIFIED_REACHED, false, null, false),
						null);
			}
		}

		// 노드 매핑이 불가능하면 다음 정상 응답에서 자동 재시도
		return Input.held(BoardingStateReason.STALE_PROGRESS);
	}

}
