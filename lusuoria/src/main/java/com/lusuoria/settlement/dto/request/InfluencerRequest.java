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

    /** 红人团队列表，前端传 List，后端存逗号分隔字符串 */
    private List<String> teamNames;

    @NotBlank(message = "红人ID不能为空")
    private String accountName;

    private String countryMarket;
    private String platform;
    private String domain;
    private Long   followerCount;

    /** 主页链接列表 */
    private List<String> links;

    /** 合作案例链接列表 */
    private List<String> casesLinks;

    private String email;
    private InfluencerContactStatus contactStatus;
    private String paymentCycle;
    private String followerPerson;

    // 敏感字段（ADMIN / AUDITOR）
    private String influencerCost;
    private String clientPrice;

    private String notes;
}
