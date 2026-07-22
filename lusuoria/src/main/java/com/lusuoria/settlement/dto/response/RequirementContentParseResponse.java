package com.lusuoria.settlement.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * "提取需求内容"解析结果。videoType 故意不在这里提取——目前的启发式规则识别不出项目视频类型，
 * 一律留空让用户手动选（见 RequirementContentParser 的说明）。
 */
@Data
public class RequirementContentParseResponse {
    private Long influencerId;
    private String accountName;

    private List<ParsedItem> items;

    @Data
    public static class ParsedItem {
        /** 识别到的合作平台（多选） */
        private List<String> platform;
        private Integer videoCount;
        private BigDecimal clientUnitPrice;
        private BigDecimal influencerUnitCostPrice;
    }
}
