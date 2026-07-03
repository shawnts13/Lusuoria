package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.dto.response.DashboardDrilldownResponse;
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

    /** 顶部汇总卡片 */
    @GetMapping("/summary")
    public ApiResponse<DashboardSummaryResponse> summary(
            @RequestParam String yearMonth,
            @RequestParam(defaultValue = "USD") String currency) {
        if (!RoleUtil.canViewSensitiveFields()) {
            return ApiResponse.error(403, "无权限查看财务数据");
        }
        return ApiResponse.success(dashboardStatsService.getSummary(yearMonth, currency));
    }

    /** 右上角汇率信息（单独查询，供前端独立刷新使用） */
    @GetMapping("/exchange-rate")
    public ApiResponse<ExchangeRateInfo> exchangeRate(@RequestParam String yearMonth) {
        return ApiResponse.success(exchangeRateService.getRateForMonth(yearMonth));
    }

    /** 下钻：视频项目数量，按品牌方+红人类型拆分，或按"项目视频发布时间"月份拆分（dimension: brand_type|publish_month） */
    @GetMapping("/drilldown/video-count")
    public ApiResponse<DashboardDrilldownResponse> drilldownVideoCount(
            @RequestParam String startMonth,
            @RequestParam String endMonth,
            @RequestParam(defaultValue = "brand_type") String dimension) {
        return ApiResponse.success(dashboardStatsService.drilldownVideoCount(startMonth, endMonth, dimension));
    }

    /** 下钻：客户合作价格，按品牌方+红人类型拆分 */
    @GetMapping("/drilldown/client-price")
    public ApiResponse<DashboardDrilldownResponse> drilldownClientPrice(
            @RequestParam String startMonth,
            @RequestParam String endMonth,
            @RequestParam(defaultValue = "USD") String currency) {
        if (!RoleUtil.canViewSensitiveFields()) {
            return ApiResponse.error(403, "无权限查看财务数据");
        }
        return ApiResponse.success(dashboardStatsService.drilldownClientPrice(startMonth, endMonth, currency));
    }

    /** 下钻：红人成本，按品牌方/团队/账号/类型拆分（dimension: brand|team|account|type） */
    @GetMapping("/drilldown/influencer-cost")
    public ApiResponse<DashboardDrilldownResponse> drilldownInfluencerCost(
            @RequestParam String startMonth,
            @RequestParam String endMonth,
            @RequestParam(defaultValue = "USD") String currency,
            @RequestParam(defaultValue = "brand") String dimension) {
        if (!RoleUtil.canViewSensitiveFields()) {
            return ApiResponse.error(403, "无权限查看财务数据");
        }
        return ApiResponse.success(
                dashboardStatsService.drilldownInfluencerCost(startMonth, endMonth, currency, dimension));
    }

    /** 下钻：项目毛利，按品牌方/团队/账号/类型拆分（dimension: brand|team|account|type） */
    @GetMapping("/drilldown/gross-profit")
    public ApiResponse<DashboardDrilldownResponse> drilldownGrossProfit(
            @RequestParam String startMonth,
            @RequestParam String endMonth,
            @RequestParam(defaultValue = "USD") String currency,
            @RequestParam(defaultValue = "brand") String dimension) {
        if (!RoleUtil.canViewSensitiveFields()) {
            return ApiResponse.error(403, "无权限查看财务数据");
        }
        return ApiResponse.success(
                dashboardStatsService.drilldownGrossProfit(startMonth, endMonth, currency, dimension));
    }

    /** 下钻：负责人提成合计，按负责人拆分 */
    @GetMapping("/drilldown/commission")
    public ApiResponse<DashboardDrilldownResponse> drilldownCommission(
            @RequestParam String startMonth,
            @RequestParam String endMonth,
            @RequestParam(defaultValue = "USD") String currency) {
        if (!RoleUtil.canViewSensitiveFields()) {
            return ApiResponse.error(403, "无权限查看财务数据");
        }
        return ApiResponse.success(dashboardStatsService.drilldownCommission(startMonth, endMonth, currency));
    }
}
