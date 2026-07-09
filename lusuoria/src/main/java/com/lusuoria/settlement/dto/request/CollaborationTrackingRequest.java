package com.lusuoria.settlement.dto.request;

import com.lusuoria.settlement.enums.CollaborationProgress;
import com.lusuoria.settlement.enums.InfluencerPaymentProgress;
import com.lusuoria.settlement.enums.VideoType;
import lombok.Data;

import javax.validation.constraints.NotNull;
import java.util.Date;

@Data
public class CollaborationTrackingRequest {
    private Long id;

    private Long brandId;

    // teamName / countryMarket 不在请求中传入，由后端根据 influencerId 从红人库自动填充快照

    @NotNull(message = "请选择红人")
    private Long influencerId;

    /**
     * 红人团队 id。跟着 brandId 级联决定：该品牌方下这个红人只有一个团队选项时可以不传
     * （后端会自动采用那唯一的选项）；有多个选项时必须传，且必须是其中一个合法选项。
     */
    private Long teamId;

    /** 合作平台，前端多选后用换行符拼接 */
    private String platform;

    private String demandContent;

    private String publishLink;

    private Date publishDate;

    private CollaborationProgress progress;

    /**
     * 红人结款进度。默认空，只有当上面的 progress 达到前置条件（已发布(未结算)/
     * 已加入客户未结算列表/客户已结算）时才允许设置值，由 service 层统一校验。
     */
    private InfluencerPaymentProgress influencerPaymentProgress;

    /** 项目视频类型：实拍新视频 / 实拍新图片 / AI新素材 / 旧素材重发 */
    private VideoType videoType;

    /** 采买旧视频的原链接（仅"旧素材重发"类型才涉及填写） */
    private String oldMaterialSourceLink;

    private String clientOrderId;

    private String clientPaymentBatch;

    /** 项目负责人（员工 id） */
    private Long projectManagerId;

    /** 内部执行人员 */
    private Long executorId;

    // 敏感字段
    private String influencerCost;
    private String clientPrice;

    /** 备注：记录一些特殊情况 */
    private String notes;
}
