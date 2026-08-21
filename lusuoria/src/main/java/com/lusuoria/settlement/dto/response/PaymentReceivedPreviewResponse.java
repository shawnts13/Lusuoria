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
 * 前端应该直接展示报错文案、不渲染分组明细——命中范围里只要有一条记录"客户方付款批次"是空的，
 * 就说明筛选条件很可能选错了，见 CollaborationTrackingController 上这两个接口的说明。
 */
@Data
public class PaymentReceivedPreviewResponse {
    private boolean hasEmptyPaymentBatch;
    private int totalCount;
    private BigDecimal totalClientPrice;
    private List<Group> groups;

    /** 按"客户方付款批次"分组后的一行 */
    @Data
    public static class Group {
        private String clientPaymentBatch;
        private int count;
        private BigDecimal totalClientPrice;
    }
}
