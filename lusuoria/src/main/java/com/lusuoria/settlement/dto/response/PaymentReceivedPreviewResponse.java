package com.lusuoria.settlement.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * "批量标记为已收到客户回款"弹窗打开时的预览数据（2026-08-21 新增）：按"客户方付款批次"分组
 * 展示命中记录数/客户合作总价格（美金），供财务在真正提交前肉眼核对筛选范围对不对，
 * 见 CollaborationTrackingService.markClientPaymentReceivedPreview()。
 *
 * hasEmptyPaymentBatch=true 时 groups/totalCount/totalClientPrice 都不会填（没有意义），
 * 前端应该直接展示报错文案、不渲染分组明细——命中范围里只要有一条记录"该品牌方涉及客户方付款
 * 批次却没填"，就说明筛选条件很可能选错了，见 CollaborationTrackingController 上这两个接口
 * 的说明。"品牌方不涉及客户方付款批次"这个字段本身可以留空的场景不算在内（2026-08-21 起，
 * Shawn 反馈：之前漏了这种品牌方，误把这类记录也当成"填漏了"一并拦下）。
 */
@Data
public class PaymentReceivedPreviewResponse {
    private boolean hasEmptyPaymentBatch;
    private int totalCount;
    private BigDecimal totalClientPrice;
    private List<Group> groups;

    /**
     * 分组后的一行（2026-08-21 起分两种分组口径，见 markClientPaymentReceivedPreview()）：
     *   - 有客户方付款批次的记录：按批次号分组，clientPaymentBatch 是真实批次号；
     *     brandTeamLabel 是这个批次下涉及的全部"品牌方/团队"组合，多个时换行符分隔
     *     （同一批次理论上可能涉及多个品牌方/团队，不能只展示一个）。
     *   - 不涉及客户方付款批次的记录（品牌方没勾选"涉及"）：按"品牌方/团队"分组，一个组合
     *     一行，clientPaymentBatch 固定是字面量"不涉及"，brandTeamLabel 只有一个值（因为
     *     本身就是按它分的组，不会出现多个）。
     */
    @Data
    public static class Group {
        private String clientPaymentBatch;
        /** 这一行涉及的"品牌方/团队"组合，多个时用换行符（\n）分隔 */
        private String brandTeamLabel;
        private int count;
        private BigDecimal totalClientPrice;
    }
}
