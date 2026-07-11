package com.lusuoria.settlement.dto.request;

import com.lusuoria.settlement.enums.InfluencerContactStatus;
import com.lusuoria.settlement.enums.ProjectType;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.List;

@Data
public class InfluencerRequest {
    private Long id;

    @NotNull(message = "红人类型不能为空")
    private ProjectType influencerType;

    @NotBlank(message = "红人社媒完整名字不能为空")
    private String accountName;

    /** 品牌方-团队 对列表（一个红人可以有多个对，同一品牌方下也可以有多个不同团队，团队可为空） */
    private List<BrandTeamPair> brandTeamPairs;
    private String countryMarket;    // 服务国家/市场
    private String platform;

    /** 所属领域列表，前端传 List，后端存换行符分隔字符串 */
    private List<String> domains;

    private Long   followerCount;
    private List<String> links;
    private List<String> casesLinks;
    private String contractLink;     // 已签署合同链接

    private String email;

    /**
     * 联系方式列表
     * 前端传 [{"type":"phone","value":"xxx"}, ...]
     */
    private String contacts;         // 直接传 JSON 字符串

    private InfluencerContactStatus contactStatus;
    private String followerPerson;

    // 敏感字段
    private String influencerCost;   // 红人视频制作与发布成本（美金）
    private String adSpendCost;      // 视频投流成本（美金）
    private String copyrightCost;    // 视频版权成本（美金）

    private String notes;

    /** 一个"品牌方-团队"对，teamId 可为空 */
    @Data
    public static class BrandTeamPair {
        private Long brandId;
        private Long teamId;
    }
}
