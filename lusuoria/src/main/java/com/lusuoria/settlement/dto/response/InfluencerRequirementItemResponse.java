package com.lusuoria.settlement.dto.response;

import com.lusuoria.settlement.enums.VideoType;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 需求条目响应：GET /{id}/items 用于展示/编辑；"关联红人需求"选择器第二步额外用到
 * fulfilledCount（不看 CollaborationTracking 的 progress 状态，只要关联了就算"已实施"）。
 */
@Data
public class InfluencerRequirementItemResponse {
    private Long id;
    private VideoType videoType;
    private String videoTypeLabel;
    /** 合作平台，换行分隔（已按字典序排序存储） */
    private String platform;
    private Integer videoCount;
    private BigDecimal clientUnitPrice;
    private BigDecimal influencerUnitCostPrice;

    /** 已关联到这条需求且 videoType+platform 跟这个条目匹配的红人合作跟踪记录数（不看 progress） */
    private Integer fulfilledCount;
}
