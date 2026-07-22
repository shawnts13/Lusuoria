package com.lusuoria.settlement.dto.request;

import com.lusuoria.settlement.enums.VideoType;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class InfluencerRequirementItemRequest {
    /** 已有条目的 id（编辑时用于匹配更新哪一条）；新增条目留空 */
    private Long id;

    private VideoType videoType;

    /** 合作平台（多选），后端按字典序排序后换行拼接存储，判重也按排序后的结果比较 */
    private List<String> platform;

    private Integer videoCount;

    /** 客户合作单价（美金） */
    private BigDecimal clientUnitPrice;

    /** 红人视频制作与发布单价成本（美金） */
    private BigDecimal influencerUnitCostPrice;
}
