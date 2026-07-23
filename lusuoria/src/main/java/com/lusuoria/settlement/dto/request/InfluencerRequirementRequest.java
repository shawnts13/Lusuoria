package com.lusuoria.settlement.dto.request;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.util.List;

@Data
public class InfluencerRequirementRequest {
    private Long id;

    @NotNull(message = "请选择红人")
    private Long influencerId;

    private Long brandId;

    private Long teamId;

    /**
     * 服务国家/市场：红人只维护了 0/1 个时可以不传（后端自动采用）；维护了多个时必须传其中一个。
     */
    private String countryMarket;

    /** 需求月份（yyyyMM），新建默认当月，可改 */
    private String requirementMonth;

    private String fullRequirementContent;

    private String notes;

    /** 涉及的红人需求条目：整份替换（service 层按 id 做增量增删改，见 InfluencerRequirementService） */
    private List<InfluencerRequirementItemRequest> items;
}
