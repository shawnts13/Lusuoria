package com.lusuoria.settlement.dto.request;

import com.lusuoria.settlement.enums.CollaborationProgress;
import com.lusuoria.settlement.enums.InfluencerPaymentProgress;
import com.lusuoria.settlement.enums.VideoType;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * "批量标记为已收到客户回款"专用请求（2026-08-21 新增）：预览接口
 * （POST /api/collaboration-trackings/payment-received/preview）和确认执行接口
 * （POST /api/collaboration-trackings/payment-received/confirm）共用同一个请求体。
 *
 * 筛选条件字段跟 CollaborationTrackingController.list() 的 @RequestParam 逐个对应
 * （字段名/语义完全一致，改了记得两边一起改）——前端直接把"红人合作跟踪"列表页当前的
 * filters 对象整个传过来，保证这个按钮操作的范围就是列表页当前"共XX条"命中的那批记录，
 * 而不是当前分页展示的这一页，见 CollaborationTrackingService.findAllMatchingFilters()。
 */
@Data
public class MarkPaymentReceivedRequest {
    private Long brandId;
    private Long teamId;
    private String countryMarket;
    private String accountName;
    private Long influencerId;
    private String platform;
    private List<CollaborationProgress> progress;
    private List<InfluencerPaymentProgress> influencerPaymentProgress;
    private List<VideoType> videoType;
    private String videoMonth;
    private String videoDateStart;
    private String videoDateEnd;
    private String internalProjectNo;
    private String internalRequirementNo;
    private String clientOrderId;
    private String clientPaymentBatch;
    private List<Long> projectManagerId;
    private boolean onlyIncomplete;
    private boolean onlyUnpublished;
    private boolean onlyMissingRequirementNo;

    /**
     * 收到回款日期：预览接口不需要（预览只关心命中范围，不需要这个值），确认执行接口必填。
     * 前端日期选择控件默认填当天，允许人工改成别的日期。
     */
    private Date clientPaymentReceivedDate;
}
