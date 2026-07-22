package com.lusuoria.settlement.dto.request;

import lombok.Data;

import java.util.List;

@Data
public class LinkLegacyTrackingsRequest {
    private String internalRequirementNo;
    private List<Long> trackingIds;
}
