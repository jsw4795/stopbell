package com.stopbell.boarding.service;

import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.transit.service.BoardingCandidateMetadataService;
import com.stopbell.transit.service.BoardingCandidateReferenceStore;
import com.stopbell.transit.service.BoardingCandidateService;
import org.springframework.stereotype.Service;

@Service
public class BoardingSessionStartValidator {
    private final BoardingCandidateReferenceStore references;
    private final BoardingCandidateMetadataService metadata;
    private final BoardingCandidateService candidates;

    public BoardingSessionStartValidator(BoardingCandidateReferenceStore references,
            BoardingCandidateMetadataService metadata, BoardingCandidateService candidates) {
        this.references = references;
        this.metadata = metadata;
        this.candidates = candidates;
    }

    public VerifiedBoardingStart validate(Long userId, String candidateRef, String confirmedVehicleNumber) {
        var original = references.resolve(candidateRef, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.BOARDING_CANDIDATE_CHANGED));
        var context = original.context();
        var current = metadata.read(context.routeId(), context.targetOccurrenceId(), context.boardingOccurrenceId());
        candidates.revalidateSelected(original, current, confirmedVehicleNumber);
        // Current metadata has no verified alightability classification. A direct arrival at
        // the progress occurrence does not prove alighting is allowed at the selected target.
        // Keep UNKNOWN unsupported until an approved evidence source supplies that classification.
        throw new ApiException(ErrorCode.BOARDING_TRACKING_UNSUPPORTED);
    }
}
