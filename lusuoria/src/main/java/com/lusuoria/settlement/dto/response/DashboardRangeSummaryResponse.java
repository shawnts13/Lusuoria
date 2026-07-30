package com.lusuoria.settlement.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 数据看板区间汇总响应（2026-07 新增，年度报告/同比用）
 *
 * 按月循环调用现有单月 {@link DashboardStatsService#getSummary} 后拼装而成——每个月各自按
 * 当月汇率换算好之后再相加，不是整段区间统一按一个汇率换算，跨多个不同汇率月份时更准确。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardRangeSummaryResponse {

    private String startMonth;
    private String endMonth;

    /** 当前展示币种：USD 或 RMB */
    private String currency;

    /** 区间内逐月的汇总，每项都带上自己的 yearMonth，供月度趋势图使用 */
    private List<DashboardSummaryResponse> monthly;

    /** 区间内所有月份相加后的合计（每月已换算好的结果直接相加） */
    private DashboardSummaryResponse total;
}
