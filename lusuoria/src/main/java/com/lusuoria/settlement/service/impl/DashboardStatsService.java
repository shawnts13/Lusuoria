package com.lusuoria.settlement.service.impl;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.dto.response.DashboardDrilldownResponse;
import com.lusuoria.settlement.dto.response.DashboardSummaryResponse;
import com.lusuoria.settlement.dto.response.ExchangeRateInfo;
import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.entity.InfluencerTeam;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据看板统计服务
 *
 * 2026-07："项目订单"模块整体废弃，看板统计的数据来源从 ProjectOrder 换成了
 * CollaborationTracking，成本/利润这些字段也是从那边迁移过来的同一批字段，公式不变。
 * 月份口径统一改成按"发布时间"（原来还分"项目建立月份"和"项目视频发布时间"两种口径，
 * 前者已经随着项目订单模块一起废弃，现在只有一种口径，所有统计都按发布时间来）。
 *
 * 所有金额数字均为动态计算，不依赖 CollaborationTracking 表里预存的 gross_profit 等字段，
 * 保证公式调整后看板数字始终与最新业务口径一致。
 *
 * 核心公式（与 ProfitCalculator 保持一致）：
 *   红人成本 = 直填值（不分红人类型，一律取录入的实际值）
 *   项目毛利 = 客户合作价格 - 红人成本 - 其他外部成本
 *   可分配利润 = 项目毛利 - 内部执行成本
 *   负责人提成 = 可分配利润 × 提成比例
 *   公司利润 = 客户合作价格 - 红人成本 - 其他外部成本 - 内部执行成本 - 负责人提成
 *            （等价于：可分配利润 - 负责人提成）
 *
 * 币种换算：看板/下钻请求统一传入 currency=USD|RMB，所有金额按"看板查看月份"
 * 对应的统一汇率（ExchangeRateService 提供）换算后返回，不使用每条记录各自的汇率。
 */
@Service
@org.springframework.transaction.annotation.Transactional(readOnly = true)
public class DashboardStatsService {

    private static final int SCALE = 2;

    @Autowired private CollaborationTrackingRepository trackingRepo;
    @Autowired private BrandCache brandCache;
    @Autowired private EmployeeCache employeeCache;
    @Autowired private ExchangeRateService exchangeRateService;
    @Autowired private com.lusuoria.settlement.util.ProfitCalculator profitCalculator;

    // ============ 顶部汇总 ============

    /**
     * @param yearMonth 看板查看的月份，格式 yyyyMM
     * @param currency  USD 或 RMB
     */
    public DashboardSummaryResponse getSummary(String yearMonth, String currency) {
        ExchangeRateInfo rateInfo = exchangeRateService.getRateForMonth(yearMonth);
        BigDecimal rate = rateInfo.getUsdToCny();

        // "视频项目数量"及本月汇总数据，统一按"发布时间"取
        List<CollaborationTracking> orders = trackingRepo.findByPublishMonth(yearMonth);
        long videoCount = orders.size();

        BigDecimal totalClientPrice = BigDecimal.ZERO;
        BigDecimal totalInfluencerCost = BigDecimal.ZERO;
        BigDecimal totalOtherCost = BigDecimal.ZERO;
        BigDecimal totalExecCost = BigDecimal.ZERO;
        BigDecimal totalGrossProfit = BigDecimal.ZERO;
        BigDecimal totalDistributable = BigDecimal.ZERO;
        BigDecimal totalCommission = BigDecimal.ZERO;
        BigDecimal totalCompanyProfit = BigDecimal.ZERO;

        for (CollaborationTracking o : orders) {
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

        // 内部其他员工成本：财务、IT后勤这两个角色的固定月薪（不跟具体记录挂钩，
        // 员工管理里维护的是"月薪"，这里就是这一个月的固定支出），要从公司利润里扣掉
        BigDecimal totalOtherStaffCostRmb = otherStaffCostRmb(1);
        BigDecimal totalOtherStaffCostUsd = (rate != null && rate.compareTo(BigDecimal.ZERO) > 0)
                ? totalOtherStaffCostRmb.divide(rate, SCALE, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        totalCompanyProfit = totalCompanyProfit.subtract(totalOtherStaffCostUsd);

        boolean toRmb = "RMB".equalsIgnoreCase(currency);
        return DashboardSummaryResponse.builder()
                .videoProjectCount(videoCount)
                .totalClientPrice(convert(totalClientPrice, rate, toRmb))
                .totalInfluencerCost(convert(totalInfluencerCost, rate, toRmb))
                .totalOtherExternalCost(convertFromRmb(totalOtherCost, rate, toRmb))
                .totalInternalExecutionCost(convertFromRmb(totalExecCost, rate, toRmb))
                .totalOtherStaffCost(convertFromRmb(totalOtherStaffCostRmb, rate, toRmb))
                .totalGrossProfit(convert(totalGrossProfit, rate, toRmb))
                .totalDistributableProfit(convert(totalDistributable, rate, toRmb))
                .totalCommissionAmount(convert(totalCommission, rate, toRmb))
                .totalCompanyProfit(convert(totalCompanyProfit, rate, toRmb))
                .currency(toRmb ? "RMB" : "USD")
                .exchangeRateInfo(rateInfo)
                .build();
    }

    /** 财务、IT后勤角色目前是固定月薪，跟具体记录无关；法务角色薪资方案还没设计，暂不计入 */
    private static final java.util.Set<String> OTHER_STAFF_ROLES =
            new java.util.HashSet<>(java.util.Arrays.asList("财务", "IT后勤"));

    private List<Employee> otherStaffEmployees() {
        List<Employee> result = new ArrayList<>();
        for (Employee e : employeeCache.getAll()) {
            if (OTHER_STAFF_ROLES.contains(e.getRole())) result.add(e);
        }
        return result;
    }

    /** 财务+IT后勤全部员工的固定月薪合计（人民币），乘以月份数（月份范围下钻会用到） */
    private BigDecimal otherStaffCostRmb(int monthCount) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Employee e : otherStaffEmployees()) {
            sum = sum.add(safe(e.getFixedMonthlySalary()));
        }
        return sum.multiply(BigDecimal.valueOf(monthCount));
    }

    /** 起止月份（yyyyMM）之间一共跨了多少个月，闭区间，比如 202601~202603 = 3 */
    private int monthCountBetween(String startMonth, String endMonth) {
        int startY = Integer.parseInt(startMonth.substring(0, 4));
        int startM = Integer.parseInt(startMonth.substring(4));
        int endY = Integer.parseInt(endMonth.substring(0, 4));
        int endM = Integer.parseInt(endMonth.substring(4));
        int count = (endY - startY) * 12 + (endM - startM) + 1;
        return Math.max(count, 1);
    }

    // ============ 下钻：内部其他员工成本（按"员工角色-姓名"） ============
    // 注意：这个成本压根不来自 CollaborationTracking，是财务/IT后勤这些员工的固定月薪，
    // 按查询的月份范围跨了几个月来乘算，不需要遍历记录数据

    public DashboardDrilldownResponse drilldownOtherStaffCost(String startMonth, String endMonth, String currency) {
        BigDecimal rate = rateForRange(endMonth);
        boolean toRmb = "RMB".equalsIgnoreCase(currency);
        int monthCount = monthCountBetween(startMonth, endMonth);

        List<DashboardDrilldownResponse.DrilldownRow> rows = new ArrayList<>();
        for (Employee e : otherStaffEmployees()) {
            BigDecimal amountRmb = safe(e.getFixedMonthlySalary()).multiply(BigDecimal.valueOf(monthCount));
            rows.add(DashboardDrilldownResponse.DrilldownRow.builder()
                    .dimensionLabel(e.getRole() + " - " + e.getName())
                    .dimensionType("role_name")
                    .videoCount(1L) // 这里借用这个字段表示"人数"，一条记录=一个人
                    .amount(convertFromRmb(amountRmb, rate, toRmb))
                    .build());
        }
        rows.sort((a, b) -> b.getAmount().compareTo(a.getAmount()));

        return DashboardDrilldownResponse.builder()
                .currency(toRmb ? "RMB" : "USD")
                .exchangeRateInfo(exchangeRateService.getRateForMonth(endMonth))
                .rows(rows)
                .build();
    }

    // ============ 下钻：视频项目数量（按品牌方 + 红人类型） ============

    public DashboardDrilldownResponse drilldownVideoCount(String startMonth, String endMonth, String dimension) {
        List<CollaborationTracking> orders = trackingRepo.findByPublishMonthBetween(startMonth, endMonth);

        Map<String, Long> grouped = new LinkedHashMap<>();
        String dimensionType;

        if ("publish_month".equals(dimension)) {
            // 按"发布时间"所在月份分组
            dimensionType = "publish_month";
            java.text.SimpleDateFormat monthFmt = new java.text.SimpleDateFormat("yyyy-MM");
            for (CollaborationTracking o : orders) {
                String key = o.getPublishDate() != null
                        ? monthFmt.format(o.getPublishDate()) : "未填写视频发布时间";
                grouped.merge(key, 1L, Long::sum);
            }
        } else {
            // 默认：按品牌方 + 红人团队分组（没关联团队的记录，团队部分留空，展示成"品牌方 - "）
            dimensionType = "brand_team";
            for (CollaborationTracking o : orders) {
                String brandName = brandNameOf(o.getBrandId());
                String teamLabel = teamNameOf(o.getTeam());
                String key = brandName + "|" + teamLabel;
                grouped.merge(key, 1L, Long::sum);
            }
        }

        List<DashboardDrilldownResponse.DrilldownRow> rows = new ArrayList<>();
        for (Map.Entry<String, Long> e : grouped.entrySet()) {
            String label = "brand_team".equals(dimensionType)
                    ? String.join(" - ", e.getKey().split("\\|", 2))
                    : e.getKey();
            rows.add(DashboardDrilldownResponse.DrilldownRow.builder()
                    .dimensionLabel(label)
                    .dimensionType(dimensionType)
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

    // ============ 下钻：客户合作价格（按品牌方 + 红人团队） ============

    public DashboardDrilldownResponse drilldownClientPrice(String startMonth, String endMonth, String currency) {
        return drilldownAmountByBrandAndTeam(startMonth, endMonth, currency, c -> c.clientPrice);
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

    // ============ 下钻：公司利润（美金/人民币，品牌方/团队/账号/类型/品牌方-团队 可切换） ============

    public DashboardDrilldownResponse drilldownCompanyProfit(String startMonth, String endMonth,
                                                              String currency, String dimension) {
        return drilldownAmountByDimension(startMonth, endMonth, currency, dimension, c -> c.companyProfit);
    }

    // ============ 下钻：内部执行人力成本（按项目负责人，或项目负责人-品牌方-团队） ============
    // 注意：这个字段本身是人民币，跟其他美元计价的字段方向相反，不能复用 drilldownAmountByDimension
    // （那个用的是 convert()，是按"输入是美元"来处理的），这里单独写一份用 convertFromRmb()。
    // 这里故意统计的是"所有已填的内部执行成本"原始总和，不区分是不是影响公司利润
    // （跟看板最上面那个汇总数字口径一致——那个数字本身不受"是否管理层"这条规则影响，
    // 这个下钻明细只是把那个总数字拆开来看，口径也应该保持一致）。

    public DashboardDrilldownResponse drilldownExecutionCost(String startMonth, String endMonth,
                                                              String currency, String dimension) {
        List<CollaborationTracking> orders = trackingRepo.findByPublishMonthBetween(startMonth, endMonth);
        BigDecimal rate = rateForRange(endMonth);
        boolean toRmb = "RMB".equalsIgnoreCase(currency);

        Map<String, BigDecimal> grouped = new LinkedHashMap<>();
        Map<String, Long> counted = new LinkedHashMap<>();
        for (CollaborationTracking o : orders) {
            BigDecimal execCostRmb = safe(o.getInternalExecutionCost());
            String key;
            switch (dimension) {
                case "manager_brand_team":
                    key = managerNameOf(o.getProjectManagerId()) + " - " + brandNameOf(o.getBrandId())
                            + " - " + teamNameOf(o.getTeam());
                    break;
                case "manager_executor":
                    key = managerNameOf(o.getProjectManagerId()) + " - " + executorNameOf(o.getExecutorId());
                    break;
                default: // manager
                    key = managerNameOf(o.getProjectManagerId());
            }
            grouped.merge(key, execCostRmb, BigDecimal::add);
            // 笔数只统计实际填了内部执行成本的记录，跟金额是不是0保持同一个口径
            if (execCostRmb.compareTo(BigDecimal.ZERO) > 0) counted.merge(key, 1L, Long::sum);
        }
        // 金额是0的不用展示——比如某个项目负责人压根没有任何执行人员记录，
        // 分组出来是"负责人 - 未指定执行人员：¥0"，这种没有意义，过滤掉
        grouped.entrySet().removeIf(e -> e.getValue() == null || e.getValue().compareTo(BigDecimal.ZERO) == 0);

        List<DashboardDrilldownResponse.DrilldownRow> rows = grouped.entrySet().stream()
                .map(e -> DashboardDrilldownResponse.DrilldownRow.builder()
                        .dimensionLabel(e.getKey())
                        .dimensionType(dimension)
                        .videoCount(counted.get(e.getKey()))
                        .amount(convertFromRmb(e.getValue(), rate, toRmb))
                        .build())
                .sorted((a, b) -> b.getAmount().compareTo(a.getAmount()))
                .collect(Collectors.toList());

        return DashboardDrilldownResponse.builder()
                .currency(toRmb ? "RMB" : "USD")
                .exchangeRateInfo(exchangeRateService.getRateForMonth(endMonth))
                .rows(rows)
                .build();
    }

    // ============ 下钻：负责人提成合计（仅按负责人） ============

    public DashboardDrilldownResponse drilldownCommission(String startMonth, String endMonth, String currency) {
        List<CollaborationTracking> orders = trackingRepo.findByPublishMonthBetween(startMonth, endMonth);
        BigDecimal rate = rateForRange(endMonth);
        boolean toRmb = "RMB".equalsIgnoreCase(currency);

        Map<String, BigDecimal> grouped = new LinkedHashMap<>();
        Map<String, Long> counted = new LinkedHashMap<>();
        for (CollaborationTracking o : orders) {
            Computed c = compute(o);
            String managerName = managerNameOf(o.getProjectManagerId());
            grouped.merge(managerName, c.commissionAmount, BigDecimal::add);
            counted.merge(managerName, 1L, Long::sum);
        }

        List<DashboardDrilldownResponse.DrilldownRow> rows = grouped.entrySet().stream()
                .map(e -> DashboardDrilldownResponse.DrilldownRow.builder()
                        .dimensionLabel(e.getKey())
                        .dimensionType("manager")
                        .videoCount(counted.get(e.getKey()))
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

    // ============ 通用：按品牌方 + 红人团队 拆分金额 ============

    private DashboardDrilldownResponse drilldownAmountByBrandAndTeam(
            String startMonth, String endMonth, String currency,
            java.util.function.Function<Computed, BigDecimal> extractor) {

        List<CollaborationTracking> orders = trackingRepo.findByPublishMonthBetween(startMonth, endMonth);
        BigDecimal rate = rateForRange(endMonth);
        boolean toRmb = "RMB".equalsIgnoreCase(currency);

        Map<String, BigDecimal> grouped = new LinkedHashMap<>();
        Map<String, Long> counted = new LinkedHashMap<>();
        for (CollaborationTracking o : orders) {
            Computed c = compute(o);
            String brandName = brandNameOf(o.getBrandId());
            String teamLabel = teamNameOf(o.getTeam());
            String key = brandName + "|" + teamLabel;
            grouped.merge(key, extractor.apply(c), BigDecimal::add);
            counted.merge(key, 1L, Long::sum);
        }

        List<DashboardDrilldownResponse.DrilldownRow> rows = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : grouped.entrySet()) {
            String[] parts = e.getKey().split("\\|", 2);
            rows.add(DashboardDrilldownResponse.DrilldownRow.builder()
                    .dimensionLabel(parts[0] + " - " + parts[1])
                    .dimensionType("brand_team")
                    .videoCount(counted.get(e.getKey()))
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

        List<CollaborationTracking> orders = trackingRepo.findByPublishMonthBetween(startMonth, endMonth);
        BigDecimal rate = rateForRange(endMonth);
        boolean toRmb = "RMB".equalsIgnoreCase(currency);

        Map<String, BigDecimal> grouped = new LinkedHashMap<>();
        Map<String, Long> counted = new LinkedHashMap<>();
        for (CollaborationTracking o : orders) {
            Computed c = compute(o);
            String key;
            switch (dimension) {
                case "team":
                    key = teamDisplayName(o.getTeam());
                    break;
                case "account":
                    key = o.getInfluencer() != null ? o.getInfluencer().getAccountName() : "未知账号";
                    break;
                case "type":
                    key = (o.getInfluencer() != null && o.getInfluencer().getInfluencerType() != null)
                            ? o.getInfluencer().getInfluencerType().getLabel() : "未知类型";
                    break;
                case "brand_team":
                    key = brandNameOf(o.getBrandId()) + " - " + teamNameOf(o.getTeam());
                    break;
                case "manager":
                    key = managerNameOf(o.getProjectManagerId());
                    break;
                case "manager_brand_team":
                    key = managerNameOf(o.getProjectManagerId()) + " - " + brandNameOf(o.getBrandId())
                            + " - " + teamNameOf(o.getTeam());
                    break;
                default: // brand
                    key = brandNameOf(o.getBrandId());
            }
            grouped.merge(key, extractor.apply(c), BigDecimal::add);
            counted.merge(key, 1L, Long::sum);
        }

        List<DashboardDrilldownResponse.DrilldownRow> rows = grouped.entrySet().stream()
                .map(e -> DashboardDrilldownResponse.DrilldownRow.builder()
                        .dimensionLabel(e.getKey())
                        .dimensionType(dimension)
                        .videoCount(counted.get(e.getKey()))
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

    /** 计算一条红人合作跟踪记录的各项金额，逻辑与 ProfitCalculator 保持一致 */
    private Computed compute(CollaborationTracking o) {
        BigDecimal clientPrice  = safe(o.getClientPrice());
        BigDecimal otherCostRmb = safe(o.getOtherExternalCost());
        BigDecimal execCostRmb  = safe(o.getInternalExecutionCost());
        BigDecimal rate         = safe(o.getCommissionRate());
        BigDecimal orderRate    = safe(o.getExchangeRate());

        // 其他外部成本、内部执行成本这两个字段填的是人民币，客户合作价格/项目毛利
        // 都是美元计价，不能直接相减，要先按这条记录自己的汇率换算成美元再参与后面的计算
        // （Computed 结构体里仍然保留人民币原值，供"其他外部成本合计"这类单独汇总展示用，
        // 不要跟这里参与利润计算用的美元换算值搞混）
        BigDecimal otherCostUsd = orderRate.compareTo(BigDecimal.ZERO) > 0
                ? otherCostRmb.divide(orderRate, SCALE, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal execCostUsdRaw = orderRate.compareTo(BigDecimal.ZERO) > 0
                ? execCostRmb.divide(orderRate, SCALE, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        // 内部执行成本只有项目负责人是"管理层"的时候才真的从利润里扣，规则跟 ProfitCalculator 一致
        BigDecimal execCostUsd = profitCalculator.isManagementOrder(o) ? execCostUsdRaw : BigDecimal.ZERO;

        // 红人成本：不分红人类型，一律取录入的实际值
        BigDecimal influencerCost = safe(o.getInfluencerCost());

        BigDecimal grossProfit = clientPrice.subtract(influencerCost).subtract(otherCostUsd)
                .setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal distributable = grossProfit.subtract(execCostUsd).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal commission = distributable.multiply(rate).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal companyProfit = distributable.subtract(commission).setScale(SCALE, RoundingMode.HALF_UP);

        Computed c = new Computed();
        c.clientPrice = clientPrice;
        c.influencerCost = influencerCost;
        c.otherExternalCost = otherCostRmb;
        c.internalExecutionCost = execCostRmb;
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

    private String executorNameOf(Long executorId) {
        if (executorId == null) return "未指定执行人员";
        Employee e = employeeCache.findById(executorId);
        return e != null ? e.getName() : "未知执行人员";
    }

    /** 用于"品牌方 - 团队"这类拼接展示：没有团队时留空，拼出来是"品牌方 - "，团队部分直接占空 */
    private String teamNameOf(InfluencerTeam team) {
        if (team == null || team.getName() == null || team.getName().trim().isEmpty()) return "";
        return team.getName();
    }

    /** 用于单独按"红人团队"下钻展示：没有团队时显示明确提示语，而不是留空 */
    private String teamDisplayName(InfluencerTeam team) {
        String name = teamNameOf(team);
        return name.isEmpty() ? "（红人无所属团队）" : name;
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

    /**
     * 其他外部成本、内部执行成本合计这两个字段本身就是人民币原值（不是美元），
     * 换算方向跟 convert() 正好相反：要人民币就直接原样返回，要美元才需要除以汇率。
     */
    private BigDecimal convertFromRmb(BigDecimal rmbAmount, BigDecimal rate, boolean toRmb) {
        if (rmbAmount == null) rmbAmount = BigDecimal.ZERO;
        if (toRmb || rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
            return rmbAmount.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return rmbAmount.divide(rate, SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
