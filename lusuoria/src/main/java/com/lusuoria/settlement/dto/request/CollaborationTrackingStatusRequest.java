package com.lusuoria.settlement.dto.request;

import com.lusuoria.settlement.enums.CollaborationProgress;
import com.lusuoria.settlement.enums.InfluencerPaymentProgress;
import lombok.Data;

/**
 * "状态流转"专用请求：只包含状态相关字段。
 * 配合前端的"状态流转"弹窗使用，弹窗里只展示这几个字段，从物理上避免误改其他字段。
 */
@Data
public class CollaborationTrackingStatusRequest {
    private CollaborationProgress progress;

    /** 红人结款进度：默认空，只有 progress 达到前置条件时才允许设置值 */
    private InfluencerPaymentProgress influencerPaymentProgress;

    /**
     * 倒退原因：只有当"视频项目进度"要从符合条件的状态改回不符合条件的状态、
     * 且当前记录"红人结款进度"已经有值时才需要填写——这种改动不会立即生效，
     * 而是提交一条待审核事项，由管理员在"待处理"里同意后才真正生效。
     * 其他正常的状态流转不需要填这个字段。
     */
    private String reason;
}
