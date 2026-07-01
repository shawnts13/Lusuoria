package com.lusuoria.settlement.service.impl;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.dto.response.DashboardDrilldownResponse;
import com.lusuoria.settlement.dto.response.DashboardSummaryResponse;
import com.lusuoria.settlement.dto.response.ExchangeRateInfo;
import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.entity.ProjectOrder;
import com.lusuoria.settlement.enums.ProjectType;
import com.lusuoria.settlement.repository.ProjectOrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据看板统计服务
 *
 * 所有金额数字均为动态计算，不依赖 ProjectOrder 表里预存的 gross_profit 等字段，
 * 保证公式调整后看板数字始终与最新业务口径一致。
 *
 * 核心公式（与 ProfitCalculator 保持一致）：
 *   红人成本（中国红人）= 客户合作价格 × 65%，（海外红人）= 直填值
 *   项目毛利 = 客户合作价格 - 红人成本 - 其他外部成本
 *   可分配利润 = 项目毛利 - 内部执行成本
 *   负责人提成 = 可分配利润 × 提成比例
 *   公司利润 = 客户合作价格 - 红人成本 - 其他外部成本 - 内部执行成本 - 负责人提成
 *            （等价于：可分配利润 - 负责人提成）
 *
 * 币种换算：看板/下钻请求统一传入 currency=USD|RMB，所有金额按"看板查看月份"
 * 对应的统一汇率（ExchangeRateService 提供）换算后返回，不使用每条订单各自的汇率。
 */
@Service
public class DashboardStatsService {

    private static final BigDecimal CHINA_COST_RATIO = new BigDecimal("0.65");
    private static final int SCALE = 2;

    @Autowired private ProjectOrderRepository projectOrderRepo;
    @Autowired private BrandCache brandCache;
    @Autowired private EmployeeCache employeeCache;
    @Autowired private ExchangeRateService exchangeRateService;

    // ============ 顶部汇总 ============

    /**
     * @param yearMonth 看板查看的月份，格式 yyyyMM
     * @param currency  USD 或 RMB
     */
    public DashboardSummaryResponse getSummary(String yearMonth, String currency) {
        ExchangeRateInfo rateInfo = exchangeRateService.getRateForMonth(yearMonth);
        BigDecimal rate = rateInfo.getUsdToCny();

        List<ProjectOrder> orders = projectOrderRepo.findByProjectMonth(yearMonth);
        long videoCount = orders.size();

        BigDecimal totalClientPrice = BigDecimal.ZERO;
        BigDecimal totalInfluencerCost = BigDecimal.ZERO;
        BigDecimal totalOtherCost = BigDecimal.ZERO;
        BigDecimal totalExecCost = BigDecimal.ZERO;
        BigDecimal totalGrossProfit = BigDecimal.ZERO;
        BigDecimal totalDistributable = BigDecimal.ZERO;
        BigDecimal totalCommission = BigDecimal.ZERO;
        BigDecimal totalCompanyProfit = BigDecimal.ZERO;

        for (ProjectOrder o : orders) {
            Computed c = compute(o);
            totalClientPrice    = totalClientPrice.add(c.clientPrice);
            totalInfluencerCost = totalInfluencerCost.add(c.influencerCost);
            totalOtherCost      = totalOtherCost.add(c.otherExternalCost);
            totalExecCost       = totalExecCost.add(c.internalExecutionCost);
            totalGrossProfit    = totalGrossProfit.add(c.grossProfit);
            totalDistributable  = totalDistributable.add(c.distributableProfit);
            totalCommission     = totalCommission.add(c.commissionAmount);
            totalCompanyProfit  = totalCompanyProfit.add(c.companyProfit);
        }

        boolean toRmb = "RMB".equalsIgnoreCase(currency);
        return DashboardSummaryResponse.builder()
                .videoProjectCount(videoCount)
                .totalClientPrice(convert(totalClientPrice, rate, toRmb))
                .totalInfluencerCost(convert(totalInfluencerCost, rate, toRmb))
                .totalOtherExternalCost(convert(totalOtherCost, rate, toRmb))
                .totalInternalExecutionCost(convert(totalExecCost, rate, toRmb))
                .totalGrossProfit(convert(totalGrossProfit, rate, toRmb))
                .totalDistributableProfit(convert(totalDistributable, rate, toRmb))
                .totalCommissionAmount(convert(totalCommission, rate, toRmb))
                .totalCompanyProfit(convert(totalCompanyProfit, rate, toRmb))
                .currency(toRmb ? "RMB" : "USD")
                .exchangeRateInfo(rateInfo)
                .build();
    }

    // ============ 下钻：视频项目数量（按品牌方 + 红人类型） ============

    public DashboardDrilldownResponse drilldownVideoCount(String startMonth, String endMonth) {
        List<ProjectOrder> orders = projectOrderRepo.findByProjectMonthBetween(startMonth, endMonth);

        // key: 品牌名 + "|" + 项目类型标签
        Map<String, Long> grouped = new LinkedHashMap<>();
        for (ProjectOrder o : orders) {
            String brandName = brandNameOf(o.getBrandId());
            String typeLabel = o.getProjectType() != null ? o.getProjectType().getLabel() : "未知类型";
            String key = brandName + "|" + typeLabel;
            grouped.merge(key, 1L, Long::sum);
        }

        List<DashboardDrilldownResponse.DrilldownRow> rows = new ArrayList<>();
        for (Map.Entry<String, Long> e : grouped.entrySet()) {
            String[] parts = e.getKey().split("\\|", 2);
            rows.add(DashboardDrilldownResponse.DrilldownRow.builder()
                    .dimensionLabel(parts[0] + " - " + parts[1])
                    .dimensionType("brand_type")
                    .videoCount(e.getValue())
                    .build());
        }
        rows.sort((a, b) -> Long.compare(b.getVideoCount(), a.getVideoCount()));

        return DashboardDrilldownResponse.builder()
                .currency(null)
                .exchangeRateInfo(null)
                .rows(rows)
                .build();
    }

    // ============ 下钻：客户合作价格（按品牌方 + 红人类型） ============

    public DashboardDrilldownResponse drilldownClientPrice(String startMonth, String endMonth, String currency) {
        return drilldownAmountByBrandAndType(startMonth, endMonth, currency, c -> c.clientPrice);
    }

    // ============ 下钻：红人成本（按品牌方/团队/账号/类型） ============

    public DashboardDrilldownResponse drilldownInfluencerCost(String startMonth, String endMonth,
                                                               String currency, String dimension) {
        return drilldownAmountByDimension(startMonth, endMonth, currency, dimension, c -> c.influencerCost);
    }

    // ============ 下钻：项目毛利（按品牌方/团队/账号/类型） ============

    public DashboardDrilldownResponse drilldownGrossProfit(String startMonth, String endMonth,
                                                            String currency, String dimension) {
        return drilldownAmountByDimension(startMonth, endMonth, currency, dimension, c -> c.grossProfit);
    }

    // ============ 下钻：负责人提成合计（仅按负责人） ============

    public DashboardDrilldownResponse drilldownCommission(String startMonth, String endMonth, String currency) {
        List<ProjectOrder> orders = projectOrderRepo.findByProjectMonthBetween(startMonth, endMonth);
        BigDecimal rate = rateForRange(endMonth);
        boolean toRmb = "RMB".equalsIgnoreCase(currency);

        Map<String, BigDecimal> grouped = new LinkedHashMap<>();
        for (ProjectOrder o : orders) {
            Computed c = compute(o);
            String managerName = managerNameOf(o.getProjectManagerId());
            grouped.merge(managerName, c.commissionAmount, BigDecimal::add);
        }

        List<DashboardDrilldownResponse.DrilldownRow> rows = grouped.entrySet().stream()
                .map(e -> DashboardDrilldownResponse.DrilldownRow.builder()
                        .dimensionLabel(e.getKey())
                        .dimensionType("manager")
                        .amount(convert(e.getValue(), rate, toRmb))
                        .build())
                .sorted((a, b) -> b.getAmount().compareTo(a.getAmount()))
                .collect(Collectors.toList());

        return DashboardDrilldownResponse.builder()
                .currency(toRmb ? "RMB" : "USD")
                .exchangeRateInfo(exchangeRateService.getRateForMonth(endMonth))
                .rows(rows)
                .build();
    }

    // ============ 通用：按品牌方 + 红人类型 拆分金额 ============

    private DashboardDrilldownResponse drilldownAmountByBrandAndType(
            String startMonth, String endMonth, String currency,
            java.util.function.Function<Computed, BigDecimal> extractor) {

        List<ProjectOrder> orders = projectOrderRepo.findByProjectMonthBetween(startMonth, endMonth);
        BigDecimal rate = rateForRange(endMonth);
        boolean toRmb = "RMB".equalsIgnoreCase(currency);

        Map<String, BigDecimal> grouped = new LinkedHashMap<>();
        for (ProjectOrder o : orders) {
            Computed c = compute(o);
            String brandName = brandNameOf(o.getBrandId());
            String typeLabel = o.getProjectType() != null ? o.getProjectType().getLabel() : "未知类型";
            String key = brandName + "|" + typeLabel;
            grouped.merge(key, extractor.apply(c), BigDecimal::add);
        }

        List<DashboardDrilldownResponse.DrilldownRow> rows = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : grouped.entrySet()) {
            String[] parts = e.getKey().split("\\|", 2);
            rows.add(DashboardDrilldownResponse.DrilldownRow.builder()
                    .dimensionLabel(parts[0] + " - " + parts[1])
                    .dimensionType("brand_type")
                    .amount(convert(e.getValue(), rate, toRmb))
                    .build());
        }
        rows.sort((a, b) -> b.getAmount().compareTo(a.getAmount()));

        return DashboardDrilldownResponse.builder()
                .currency(toRmb ? "RMB" : "USD")
                .exchangeRateInfo(exchangeRateService.getRateForMonth(endMonth))
                .rows(rows)
                .build();
    }

    // ============ 通用：按品牌方/团队/账号/类型 拆分金额 ============

    private DashboardDrilldownResponse drilldownAmountByDimension(
            String startMonth, String endMonth, String currency, String dimension,
            java.util.function.Function<Computed, BigDecimal> extractor) {

        List<ProjectOrder> orders = projectOrderRepo.findByProjectMonthBetween(startMonth, endMonth);
        BigDecimal rate = rateForRange(endMonth);
        boolean toRmb = "RMB".equalsIgnoreCase(currency);

        Map<String, BigDecimal> grouped = new LinkedHashMap<>();
        for (ProjectOrder o : orders) {
            Computed c = compute(o);
            String key;
            switch (dimension) {
                case "team":
                    key = teamNameOf(o.getInfluencer());
                    break;
                case "account":
                    key = o.getInfluencer() != null ? o.getInfluencer().getAccountName() : "未知账号";
                    break;
                case "type":
                    key = o.getProjectType() != null ? o.getProjectType().getLabel() : "未知类型";
                    break;
                default: // brand
                    key = brandNameOf(o.getBrandId());
            }
            grouped.merge(key, extractor.apply(c), BigDecimal::add);
        }

        List<DashboardDrilldownResponse.DrilldownRow> rows = grouped.entrySet().stream()
                .map(e -> DashboardDrilldownResponse.DrilldownRow.builder()
                        .dimensionLabel(e.getKey())
                        .dimensionType(dimension)
                        .amount(convert(e.getValue(), rate, toRmb))
                        .build())
                .sorted((a, b) -> b.getAmount().compareTo(a.getAmount()))
                .collect(Collectors.toList());

        return DashboardDrilldownResponse.builder()
                .currency(toRmb ? "RMB" : "USD")
                .exchangeRateInfo(exchangeRateService.getRateForMonth(endMonth))
                .rows(rows)
                .build();
    }

    // ============ 工具方法 ============

    /** 计算一条项目订单的各项金额，逻辑与 ProfitCalculator 保持一致 */
    private Computed compute(ProjectOrder o) {
        BigDecimal clientPrice = safe(o.getClientPrice());
        BigDecimal otherCost   = safe(o.getOtherExternalCost());
        BigDecimal execCost    = safe(o.getInternalExecutionCost());
        BigDecimal rate        = safe(o.getCommissionRate());

        BigDecimal influencerCost;
        if (ProjectType.CHINA_INFLUENCER == o.getProjectType()) {
            influencerCost = clientPrice.multiply(CHINA_COST_RATIO).setScale(SCALE, RoundingMode.HALF_UP);
        } else {
            influencerCost = safe(o.getInfluencerCost());
        }

        BigDecimal grossProfit = clientPrice.subtract(influencerCost).subtract(otherCost)
                .setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal distributable = grossProfit.subtract(execCost).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal commission = distributable.multiply(rate).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal companyProfit = distributable.subtract(commission).setScale(SCALE, RoundingMode.HALF_UP);

        Computed c = new Computed();
        c.clientPrice = clientPrice;
        c.influencerCost = influencerCost;
        c.otherExternalCost = otherCost;
        c.internalExecutionCost = execCost;
        c.grossProfit = grossProfit;
        c.distributableProfit = distributable;
        c.commissionAmount = commission;
        c.companyProfit = companyProfit;
        return c;
    }

    private static class Computed {
        BigDecimal clientPrice;
        BigDecimal influencerCost;
        BigDecimal otherExternalCost;
        BigDecimal internalExecutionCost;
        BigDecimal grossProfit;
        BigDecimal distributableProfit;
        BigDecimal commissionAmount;
        BigDecimal companyProfit;
    }



    private String brandNameOf(Long brandId) {
        if (brandId == null) return "未指定品牌";
        Brand b = brandCache.findById(brandId);
        return b != null ? b.getName() : "未知品牌";
    }

    private String managerNameOf(Long managerId) {
        if (managerId == null) return "未指定负责人";
        Employee e = employeeCache.findById(managerId);
        return e != null ? e.getName() : "未知负责人";
    }

    private String teamNameOf(Influencer inf) {
        if (inf == null || inf.getTeamName() == null || inf.getTeamName().trim().isEmpty()) return "未指定团队";
        return inf.getTeamName();
    }

    /** 下钻接口统一用范围终止月份对应的汇率（即查看的最新月份的"上月最后工作日"汇率） */
    private BigDecimal rateForRange(String endMonth) {
        return exchangeRateService.getRateForMonth(endMonth).getUsdToCny();
    }

    private BigDecimal convert(BigDecimal usdAmount, BigDecimal rate, boolean toRmb) {
        if (usdAmount == null) usdAmount = BigDecimal.ZERO;
        if (!toRmb || rate == null) return usdAmount.setScale(SCALE, RoundingMode.HALF_UP);
        return usdAmount.multiply(rate).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
