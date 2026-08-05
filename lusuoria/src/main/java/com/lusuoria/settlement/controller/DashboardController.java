package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.dto.response.DashboardDrilldownResponse;
import com.lusuoria.settlement.dto.response.DashboardManagerTrendResponse;
import com.lusuoria.settlement.dto.response.DashboardPivotResponse;
import com.lusuoria.settlement.dto.response.DashboardRangeSummaryResponse;
import com.lusuoria.settlement.dto.response.DashboardSummaryResponse;
import com.lusuoria.settlement.dto.response.ExchangeRateInfo;
import com.lusuoria.settlement.service.impl.DashboardStatsService;
import com.lusuoria.settlement.service.impl.ExchangeRateService;
import com.lusuoria.settlement.util.RoleUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 数据看板接口
 *
 * 所有金额相关接口仅 ADMIN / AUDITOR 可访问敏感数据（与项目订单模块权限一致）；
 * 视频项目数量本身不属于敏感财务数据，对所有登录角色开放。
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    @Autowired private DashboardStatsService dashboardStatsService;
    @Autowired private ExchangeRateService exchangeRateService;

    /**
     * 顶部汇总卡片。yearMonth（按月）和 startDate+endDate（2026-08 新增，"视频发布日期"筛选，
     * 按天）二选一——前端保证同一次请求只传其中一套，这里按"有日期就用日期"的优先级处理，
     * 两个都没传才报错（不会出现"两个都传了到底听谁的"这种歧义，因为前端的月份/日期筛选
     * 本来就是互斥的，选一个会清空另一个）。
     */
    @GetMapping("/summary")
    public ApiResponse<DashboardSummaryResponse> summary(
            @RequestParam(required = false) String yearMonth,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "USD") String currency) {
        if (!RoleUtil.canViewSensitiveFields()) {
            return ApiResponse.error(403, "无权限查看财务数据");
        }
        if (startDate != null && endDate != null) {
            return ApiResponse.success(dashboardStatsService.getSummaryByDateRange(startDate, endDate, currency));
        }
        if (yearMonth == null) {
            return ApiResponse.error(400, "请传入 yearMonth 或 startDate+endDate");
        }
        return ApiResponse.success(dashboardStatsService.getSummary(yearMonth, currency));
    }

    /** 右上角汇率信息（单独查询，供前端独立刷新使用） */
    @GetMapping("/exchange-rate")
    public ApiResponse<ExchangeRateInfo> exchangeRate(@RequestParam String yearMonth) {
        return ApiResponse.success(exchangeRateService.getRateForMonth(yearMonth));
    }

    /**
     * 区间汇总（2026-07 新增，年度报告/同比用）：返回区间内逐月汇总 + 合计，每月各自按当月汇率
     * 换算后再相加。仅适用于连续的日历月范围（如整年 Jan-Dec）；双月对比这种两个不一定相邻的
     * 月份，请分别调用 /summary，不要传到这里。
     */
    @GetMapping("/range-summary")
    public ApiResponse<DashboardRangeSummaryResponse> rangeSummary(
            @RequestParam String startMonth,
            @RequestParam String endMonth,
            @RequestParam(defaultValue = "USD") String currency) {
        if (!RoleUtil.canViewSensitiveFields()) {
            return ApiResponse.error(403, "无权限查看财务数据");
        }
        return ApiResponse.success(dashboardStatsService.getRangeSummary(startMonth, endMonth, currency));
    }

    /**
     * 下钻：视频项目数量（dimension: brand|brand_team(默认)|manager|executor|publish_month|
     * countryMarket|platform，后两个是2026-07新增，platform 一条记录可能同时计入多个平台）。
     * startMonth+endMonth（按月）和 startDate+endDate（2026-08新增，按天）二选一，见 /summary 说明。
     */
    @GetMapping("/drilldown/video-count")
    public ApiResponse<DashboardDrilldownResponse> drilldownVideoCount(
            @RequestParam(required = false) String startMonth,
            @RequestParam(required = false) String endMonth,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "brand_type") String dimension) {
        if (startMonth == null && (startDate == null || endDate == null)) {
            return ApiResponse.error(400, "请传入 startMonth+endMonth 或 startDate+endDate");
        }
        return ApiResponse.success(
                dashboardStatsService.drilldownVideoCount(startMonth, endMonth, startDate, endDate, dimension));
    }

    /**
     * 下钻：客户合作价格（dimension: brand|brand_team(默认)|manager|countryMarket|platform，
     * 后两个是2026-07新增）。startMonth+endMonth 和 startDate+endDate 二选一，见 /summary 说明。
     */
    @GetMapping("/drilldown/client-price")
    public ApiResponse<DashboardDrilldownResponse> drilldownClientPrice(
            @RequestParam(required = false) String startMonth,
            @RequestParam(required = false) String endMonth,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "USD") String currency,
            @RequestParam(defaultValue = "brand_team") String dimension) {
        if (!RoleUtil.canViewSensitiveFields()) {
            return ApiResponse.error(403, "无权限查看财务数据");
        }
        if (startMonth == null && (startDate == null || endDate == null)) {
            return ApiResponse.error(400, "请传入 startMonth+endMonth 或 startDate+endDate");
        }
        return ApiResponse.success(
                dashboardStatsService.drilldownClientPrice(startMonth, endMonth, startDate, endDate, currency, dimension));
    }

    /**
     * 下钻：客户已回款总金额（2026-08 新增，维度选项跟"客户合作价格"完全一样，只是多过滤
     * 视频项目进度=客户已结算）。startMonth+endMonth 和 startDate+endDate 二选一，见 /summary 说明。
     */
    @GetMapping("/drilldown/client-settled-amount")
    public ApiResponse<DashboardDrilldownResponse> drilldownClientSettledAmount(
            @RequestParam(required = false) String startMonth,
            @RequestParam(required = false) String endMonth,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "USD") String currency,
            @RequestParam(defaultValue = "brand_team") String dimension) {
        if (!RoleUtil.canViewSensitiveFields()) {
            return ApiResponse.error(403, "无权限查看财务数据");
        }
        if (startMonth == null && (startDate == null || endDate == null)) {
            return ApiResponse.error(400, "请传入 startMonth+endMonth 或 startDate+endDate");
        }
        return ApiResponse.success(
                dashboardStatsService.drilldownClientSettledAmount(startMonth, endMonth, startDate, endDate, currency, dimension));
    }

    /**
     * 下钻：红人成本（dimension: brand(默认)|team|account|type|countryMarket|platform，
     * 后两个是2026-07新增）。startMonth+endMonth 和 startDate+endDate 二选一，见 /summary 说明。
     */
    @GetMapping("/drilldown/influencer-cost")
    public ApiResponse<DashboardDrilldownResponse> drilldownInfluencerCost(
            @RequestParam(required = false) String startMonth,
            @RequestParam(required = false) String endMonth,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "USD") String currency,
            @RequestParam(defaultValue = "brand") String dimension) {
        if (!RoleUtil.canViewSensitiveFields()) {
            return ApiResponse.error(403, "无权限查看财务数据");
        }
        if (startMonth == null && (startDate == null || endDate == null)) {
            return ApiResponse.error(400, "请传入 startMonth+endMonth 或 startDate+endDate");
        }
        return ApiResponse.success(
                dashboardStatsService.drilldownInfluencerCost(startMonth, endMonth, startDate, endDate, currency, dimension));
    }

    /**
     * 下钻：项目毛利（dimension: brand(默认)|team|account|type|brand_team|manager|
     * manager_brand_team|countryMarket|platform，后两个是2026-07新增）。
     * startMonth+endMonth 和 startDate+endDate 二选一，见 /summary 说明。
     */
    @GetMapping("/drilldown/gross-profit")
    public ApiResponse<DashboardDrilldownResponse> drilldownGrossProfit(
            @RequestParam(required = false) String startMonth,
            @RequestParam(required = false) String endMonth,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "USD") String currency,
            @RequestParam(defaultValue = "brand") String dimension) {
        if (!RoleUtil.canViewSensitiveFields()) {
            return ApiResponse.error(403, "无权限查看财务数据");
        }
        if (startMonth == null && (startDate == null || endDate == null)) {
            return ApiResponse.error(400, "请传入 startMonth+endMonth 或 startDate+endDate");
        }
        return ApiResponse.success(
                dashboardStatsService.drilldownGrossProfit(startMonth, endMonth, startDate, endDate, currency, dimension));
    }

    /**
     * 下钻：公司利润（dimension: brand(默认)|team|account|type|brand_team|manager|
     * manager_brand_team|countryMarket|platform，后两个是2026-07新增）。
     * startMonth+endMonth 和 startDate+endDate 二选一，见 /summary 说明。
     */
    @GetMapping("/drilldown/company-profit")
    public ApiResponse<DashboardDrilldownResponse> drilldownCompanyProfit(
            @RequestParam(required = false) String startMonth,
            @RequestParam(required = false) String endMonth,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "USD") String currency,
            @RequestParam(defaultValue = "brand") String dimension) {
        if (!RoleUtil.canViewSensitiveFields()) {
            return ApiResponse.error(403, "无权限查看财务数据");
        }
        if (startMonth == null && (startDate == null || endDate == null)) {
            return ApiResponse.error(400, "请传入 startMonth+endMonth 或 startDate+endDate");
        }
        return ApiResponse.success(
                dashboardStatsService.drilldownCompanyProfit(startMonth, endMonth, startDate, endDate, currency, dimension));
    }

    /**
     * 下钻：内部执行人力成本，按项目负责人，或项目负责人-品牌方-团队 可切换。
     * startMonth+endMonth 和 startDate+endDate 二选一，见 /summary 说明。
     */
    @GetMapping("/drilldown/execution-cost")
    public ApiResponse<DashboardDrilldownResponse> drilldownExecutionCost(
            @RequestParam(required = false) String startMonth,
            @RequestParam(required = false) String endMonth,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "USD") String currency,
            @RequestParam(defaultValue = "manager") String dimension) {
        if (!RoleUtil.canViewSensitiveFields()) {
            return ApiResponse.error(403, "无权限查看财务数据");
        }
        if (startMonth == null && (startDate == null || endDate == null)) {
            return ApiResponse.error(400, "请传入 startMonth+endMonth 或 startDate+endDate");
        }
        return ApiResponse.success(
                dashboardStatsService.drilldownExecutionCost(startMonth, endMonth, startDate, endDate, currency, dimension));
    }

    /** 下钻：内部其他员工成本，按"员工角色-姓名"（财务/IT后勤这些固定月薪的角色） */
    @GetMapping("/drilldown/other-staff-cost")
    public ApiResponse<DashboardDrilldownResponse> drilldownOtherStaffCost(
            @RequestParam String startMonth,
            @RequestParam String endMonth,
            @RequestParam(defaultValue = "USD") String currency) {
        if (!RoleUtil.canViewSensitiveFields()) {
            return ApiResponse.error(403, "无权限查看财务数据");
        }
        return ApiResponse.success(
                dashboardStatsService.drilldownOtherStaffCost(startMonth, endMonth, currency));
    }

    /**
     * 下钻：负责人提成合计，按负责人拆分。
     * startMonth+endMonth 和 startDate+endDate 二选一，见 /summary 说明。
     */
    @GetMapping("/drilldown/commission")
    public ApiResponse<DashboardDrilldownResponse> drilldownCommission(
            @RequestParam(required = false) String startMonth,
            @RequestParam(required = false) String endMonth,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "USD") String currency) {
        if (!RoleUtil.canViewSensitiveFields()) {
            return ApiResponse.error(403, "无权限查看财务数据");
        }
        if (startMonth == null && (startDate == null || endDate == null)) {
            return ApiResponse.error(400, "请传入 startMonth+endMonth 或 startDate+endDate");
        }
        return ApiResponse.success(
                dashboardStatsService.drilldownCommission(startMonth, endMonth, startDate, endDate, currency));
    }

    /** 下钻：奖金（Payslip.extraBonusAmount），按员工拆分（2026-07 新增） */
    @GetMapping("/drilldown/extra-bonus")
    public ApiResponse<DashboardDrilldownResponse> drilldownExtraBonus(
            @RequestParam String startMonth,
            @RequestParam String endMonth,
            @RequestParam(defaultValue = "USD") String currency) {
        if (!RoleUtil.canViewSensitiveFields()) {
            return ApiResponse.error(403, "无权限查看财务数据");
        }
        return ApiResponse.success(dashboardStatsService.drilldownExtraBonus(startMonth, endMonth, currency));
    }

    /**
     * 维度交叉透视（2026-07 新增，年度报告用）：只支持预设的几个组合——
     * rowDimension/colDimension: brand+countryMarket | brand+platform | team+countryMarket
     */
    @GetMapping("/pivot")
    public ApiResponse<DashboardPivotResponse> pivot(
            @RequestParam String startMonth,
            @RequestParam String endMonth,
            @RequestParam(defaultValue = "USD") String currency,
            @RequestParam String rowDimension,
            @RequestParam String colDimension) {
        if (!RoleUtil.canViewSensitiveFields()) {
            return ApiResponse.error(403, "无权限查看财务数据");
        }
        return ApiResponse.success(
                dashboardStatsService.drilldownPivot(startMonth, endMonth, currency, rowDimension, colDimension));
    }

    /**
     * 员工个人趋势（2026-07 新增，仅年度报告用）：按项目负责人或执行人员，返回逐月理论数据，
     * 一次HTTP调用内后端按月循环，避免前端拆成大量并发请求打满数据库连接池。
     */
    @GetMapping("/manager-trend")
    public ApiResponse<DashboardManagerTrendResponse> managerTrend(
            @RequestParam String startMonth,
            @RequestParam String endMonth,
            @RequestParam(defaultValue = "USD") String currency,
            @RequestParam(defaultValue = "manager") String role) {
        if (!RoleUtil.canViewSensitiveFields()) {
            return ApiResponse.error(403, "无权限查看财务数据");
        }
        return ApiResponse.success(dashboardStatsService.getManagerTrend(startMonth, endMonth, currency, role));
    }
}
