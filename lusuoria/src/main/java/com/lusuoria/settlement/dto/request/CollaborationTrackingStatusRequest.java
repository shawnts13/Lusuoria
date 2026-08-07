package com.lusuoria.settlement.dto.request;

import com.lusuoria.settlement.enums.CollaborationProgress;
import lombok.Data;

import java.util.Date;

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
     * 客户方付款批次单号：只有当 progress 是"客户已结算"、且该品牌方涉及客户方付款批次
     * （Brand.requiresClientPaymentBatch()）时才需要填写，直接覆盖更新到这条记录的
     * clientPaymentBatch 字段（2026-07 新增；2026-08 起从"仅限财务角色"改成按品牌方配置判断，
     * 财务/管理层/ADMIN 都受这条规则约束）。
     */
    private String clientPaymentBatch;

    /**
     * 客户方的项目订单：只有当 progress 是"已加入客户未结算列表"/"客户已结算"、且该品牌方
     * 涉及客户方的项目订单（Brand.requiresClientOrderId()）时才需要填写，直接覆盖更新到
     * 这条记录的 clientOrderId 字段（2026-08 新增）。
     */
    private String clientOrderId;

    /**
     * 视频发布链接（2026-08 新增）：只有当目标 progress 是"已发布（未结算）"/"已加入客户未结算
     * 列表"/"客户已结算"这三个阶段之一、且这条记录当前还没有链接时才需要填写。多个链接前端用
     * 换行拼接成一个字符串传过来（跟"编辑"表单 CollaborationFormModal 的 publishLinks 数组
     * 是同一套约定），后端不再拆分校验单条链接格式。
     * 这个字段专门为"状态流转"这个动作开放，不受 doSave()（编辑表单保存）那边"仅 ADMIN 能改
     * 视频发布时间"的角色限制约束——所有能操作到这一步的角色（管理层/项目负责人/执行人员等，
     * 财务除外，财务本来就卡在 withinSettlementZone 那关到不了这个场景）都可以自己在这里把
     * 链接和发布时间填上，见 CollaborationTrackingService.updateStatus()。
     */
    private String publishLink;

    /**
     * 视频发布时间（2026-08 新增）：配合上面 publishLink 一起用，同样是"状态流转"专属的填写口子，
     * 不受编辑表单那边 ADMIN-only 的限制。
     */
    private Date publishDate;
}
