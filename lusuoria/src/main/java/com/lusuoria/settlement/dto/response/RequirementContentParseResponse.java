package com.lusuoria.settlement.dto.response;

import com.lusuoria.settlement.enums.VideoType;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * "提取需求内容"解析结果。
 */
@Data
public class RequirementContentParseResponse {
    private Long influencerId;
    private String accountName;

    private List<ParsedItem> items;

    @Data
    public static class ParsedItem {
        /** 识别到的项目视频类型，识别不出来时留空，让用户手动选 */
        private VideoType videoType;
        /** 识别到的合作平台（多选） */
        private List<String> platform;
        private Integer videoCount;
        private BigDecimal clientUnitPrice;
        private BigDecimal influencerUnitCostPrice;
    }
}
