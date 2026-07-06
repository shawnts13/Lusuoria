package com.lusuoria.settlement.dto.response;

import com.lusuoria.settlement.entity.InfluencerBrandTeamView;
import lombok.Data;

import java.util.List;

/**
 * 红人精简信息 - 供项目订单/合作跟踪/打款等模块的红人选择下拉框使用。
 * 只包含下拉框实际用到的字段（id/名称/国家市场/关联的品牌方-团队对），
 * 不像完整 Influencer 实体那样携带 notes、contacts、links、成本等大字段，
 * 减少查询和序列化开销。
 */
@Data
public class InfluencerSimpleResponse {
    private Long id;
    private String accountName;
    private String countryMarket;
    /** 关联的"品牌方-团队"对：红人合作跟踪选了品牌方之后，前端据此决定团队怎么选/要不要选 */
    private List<InfluencerBrandTeamView> brandTeamPairs;
}
