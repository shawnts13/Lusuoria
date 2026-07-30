package com.lusuoria.settlement.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 数据看板维度交叉透视响应（2026-07 新增，年度报告用）
 *
 * 只支持预设的几个精选维度组合（品牌方×国家/市场、品牌方×合作平台、红人团队×国家/市场），
 * 不做通用N维透视。用嵌套的行/列标签数组 + 稀疏 cell 列表，而不是像其他下钻那样拼接成一个
 * 字符串 key——热力图渲染需要干净独立的行列轴，拼接字符串再解析回两个轴不可靠（品牌方/团队/
 * 平台名称本身可能包含分隔符）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardPivotResponse {

    /** 当前展示币种：USD 或 RMB */
    private String currency;

    private ExchangeRateInfo exchangeRateInfo;

    /** 行标签（按行合计降序） */
    private List<String> rowLabels;

    /** 列标签（按列合计降序） */
    private List<String> colLabels;

    /** 稀疏列表，只包含实际有数据的 (行,列) 组合 */
    private List<PivotCell> cells;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PivotCell {
        private String rowLabel;
        private String colLabel;
        private Long videoCount;
        private BigDecimal clientPrice;
        private BigDecimal grossProfit;
        private BigDecimal companyProfit;
    }
}
