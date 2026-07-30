package com.lusuoria.settlement.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 员工个人趋势响应（2026-07 新增，仅年度报告用）
 *
 * 按项目负责人或执行人员，返回逐月的理论数据（基于 CollaborationTracking 现算，不对账工资单
 * 实际确认/发放金额）。后端按月循环拼装成一次返回，避免前端拆成"负责人数×月份数"的大量并发
 * 请求打满数据库连接池。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardManagerTrendResponse {

    /** 当前展示币种：USD 或 RMB */
    private String currency;

    /** 区间内逐月的月份列表（yyyyMM），每个 ManagerSeries.monthly 都跟这个数组等长同序 */
    private List<String> months;

    private List<ManagerSeries> series;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ManagerSeries {
        private String managerName;

        /** 与 months 等长同序，没数据的月份补 0，不是留空 */
        private List<MonthlyMetric> monthly;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class MonthlyMetric {
            private String yearMonth;
            private Long videoCount;
            private BigDecimal clientPrice;
            private BigDecimal grossProfit;
            private BigDecimal companyProfit;
            /** role=manager 时才有意义，role=executor 时恒为 0 */
            private BigDecimal commissionAmount;
            /** role=executor 时才有意义，role=manager 时恒为 0 */
            private BigDecimal internalExecutionCost;
        }
    }
}
