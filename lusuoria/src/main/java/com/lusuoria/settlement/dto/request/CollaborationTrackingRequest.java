package com.lusuoria.settlement.dto.request;

import com.lusuoria.settlement.enums.CollaborationProgress;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.util.Date;

@Data
public class CollaborationTrackingRequest {
    private Long id;

    private Long brandId;

    // teamName / countryMarket 不在请求中传入，由后端根据 accountName 从红人库自动填充快照

    @NotBlank(message = "红人社媒完整名字不能为空")
    private String accountName;

    /** 合作平台，前端多选后用换行符拼接 */
    private String platform;

    private String demandContent;

    private String publishLink;

    private Date publishDate;

    private CollaborationProgress progress;

    private String clientOrderId;

    private String clientPaymentBatch;

    // 敏感字段
    private String influencerCost;
    private String clientPrice;

    /**
     * 当订单ID从一个值改为另一个值时，前端二次确认后置 true，
     * 后端据此删除旧订单ID对应的项目订单，再用新订单ID生成新的
     */
    private Boolean confirmOrderIdChange = false;
}
