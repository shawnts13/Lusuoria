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

    private String teamName;         // 单个团队

    @NotBlank(message = "红人ID不能为空")
    private String accountName;

    private Long   brandId;          // 关联品牌方
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
    private String paymentCycle;
    private String followerPerson;

    // 敏感字段
    private String influencerCost;
    private String clientPrice;

    private String notes;
}
