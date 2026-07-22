package com.lusuoria.settlement.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * "存量记录关联需求"第三步候选列表：某个红人名下还没关联任何需求、且
 * 项目视频类型/合作平台/红人视频制作与发布成本/客户合作价格都跟需求某个条目匹配的
 * 红人合作跟踪记录。
 */
@Data
public class LegacyTrackingCandidateResponse {
    private Long trackingId;
    private String internalProjectNo;
    private String videoTypeLabel;
    private String platform;
    private BigDecimal influencerCost;
    private BigDecimal clientPrice;
    private Date publishDate;
    private String demandContent;
}
