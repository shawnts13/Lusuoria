package com.lusuoria.settlement.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 数据看板下钻明细响应
 *
 * 用于"视频项目数量""客户合作价格""红人成本""项目毛利""负责人提成合计"
 * 点击后弹出的按维度拆分明细。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardDrilldownResponse {

    /** 当前展示币种：USD 或 RMB */
    private String currency;

    private ExchangeRateInfo exchangeRateInfo;

    /** 拆分明细行 */
    private List<DrilldownRow> rows;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DrilldownRow {
        /** 维度名称，如品牌方名、团队名、红人账号、负责人姓名、红人类型标签 */
        private String dimensionLabel;

        /** 维度类型，前端可用于分组展示，如 "brand" / "team" / "account" / "manager" / "type" */
        private String dimensionType;

        /** 视频数量（仅"视频项目数量"下钻使用，其余为 null） */
        private Long videoCount;

        /** 金额值（仅金额类下钻使用，视频数量下钻为 null） */
        private BigDecimal amount;
    }
}
