package com.lusuoria.settlement.dto.request;

import com.lusuoria.settlement.enums.CollaborationProgress;
import lombok.Data;

/**
 * "状态流转"专用请求：只包含状态相关字段。
 * 配合前端的"状态流转"弹窗使用，弹窗里只展示这几个字段，从物理上避免误改其他字段。
 *
 * 2026-07 起不再接收 influencerPaymentProgress——红人结款进度改成完全由系统流转
 * （首次进入"已发布（未结算）"时自动判定初始值、"红人需求管理"上传Invoice、
 * "红人结款"模块纳入/移出批次），不再允许通过"状态流转"手动设置，
 * 见 CollaborationTrackingService.updateStatus()。
 */
@Data
public class CollaborationTrackingStatusRequest {
    private CollaborationProgress progress;

    /**
     * 倒退原因：只有当"视频项目进度"要从符合条件的状态改回不符合条件的状态、
     * 且当前记录"红人结款进度"已经有值时才需要填写——这种改动不会立即生效，
     * 而是提交一条待审核事项，由管理员在"待处理"里同意后才真正生效。
     * 其他正常的状态流转不需要填这个字段。
     */
    private String reason;

    /**
     * 备注：只有当 progress 是"折损"时才需要填写，直接覆盖更新到这条记录的 notes 字段
     * （2026-07 新增，见 CollaborationTrackingService.updateStatus()）。
     */
    private String notes;

    /**
     * 客户方付款批次单号：只有当 progress 是"客户已结算"、且操作人员工角色是"财务"时才需要填写，
     * 直接覆盖更新到这条记录的 clientPaymentBatch 字段（2026-07 新增）。
     */
    private String clientPaymentBatch;
}
