package com.lusuoria.settlement.dto.request;

import com.lusuoria.settlement.enums.CollaborationProgress;
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

    /** 合作平台，前端多选后用换行符拼接 */
    private String platform;

    private String demandContent;

    private String publishLink;

    private Date publishDate;

    private CollaborationProgress progress;

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
}
