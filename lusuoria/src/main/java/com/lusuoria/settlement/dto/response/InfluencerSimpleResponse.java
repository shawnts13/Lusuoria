package com.lusuoria.settlement.dto.response;

import lombok.Data;

import java.util.List;

/**
 * 红人精简信息 - 供项目订单/合作跟踪/打款等模块的红人选择下拉框使用。
 * 只包含下拉框实际用到的字段（id/名称/团队/国家市场/关联品牌），
 * 不像完整 Influencer 实体那样携带 notes、contacts、links、成本等大字段，
 * 减少查询和序列化开销。
 */
@Data
public class InfluencerSimpleResponse {
    private Long id;
    private String accountName;
    private String teamName;
    private String countryMarket;
    private List<Long> brandIds;
}
