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
import com.lusuoria.settlement.entity.Payslip;
import com.lusuoria.settlement.enums.CollaborationProgress;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import com.lusuoria.settlement.repository.PayslipRepository;
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
    @Autowired private CommissionBonusService commissionBonusService;
    @Autowired private PayslipRepository payslipRepo;

    // ============ 顶部汇总 ============

    /**
     * @param yearMonth 看板查看的月份，格式 yyyyMM
     * @param currency  USD 或 RMB
     */
    public DashboardSummaryResponse getSummary(String yearMonth, String currency) {
        ExchangeRateInfo rateInfo = exchangeRateService.getRateForMonth(yearMonth);
        BigDecimal rate = rateInfo.getUsdToCny();

        // "视频项目数量"及本月汇总数据，统一按"发布时间"取。"折损"的记录不计入任何金额统计
        // （项目毛利/公司利润/执行成本/提成等），但"视频项目数量"这个计数本身仍然按总数展示，
        // 只是额外标注其中有几笔是折损（前端据此显示"XX笔（其中X笔为折损）"），不能让折损记录
        // 从视频数量这个计数里彻底消失——用户需要看到这批记录真实存在过。
        List<CollaborationTracking> allOrders = trackingRepo.findByPublishMonth(yearMonth);
        long videoCount = allOrders.size();
        long damagedVideoCount = allOrders.stream()
                .filter(o -> o.getProgress() == CollaborationProgress.DELAYED).count();
        List<CollaborationTracking> orders = excludeDamaged(allOrders);

        BigDecimal totalClientPrice = BigDecimal.ZERO;
        BigDecimal totalInfluencerCost = BigDecimal.ZERO;
        BigDecimal totalOtherCost = BigDecimal.ZERO;
        BigDecimal totalExecCost = BigDecimal.ZERO;
        BigDecimal totalExecCostForProfitUsd = BigDecimal.ZERO;
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
            totalExecCostForProfitUsd = totalExecCostForProfitUsd.add(c.internalExecutionCostForProfitUsd);
            totalGrossProfit    = totalGrossProfit.add(c.grossProfit);
            totalDistributable  = totalDistributable.add(c.distributableProfit);
            totalCommission     = totalCommission.add(c.commissionAmount);
            totalCompanyProfit  = totalCompanyProfit.add(c.companyProfit);
        }

        // 内部其他员工成本：财务、IT后勤这两个角色的固定月薪（不跟具体记录挂钩，员工管理里
        // 维护的是"月薪"，这里就是这一个月的固定支出）+ 法务当月的工资（管理层每月手动在
        // "工资单"模块设置，不是固定月薪，设置了就计入，没设置就是0），都要从公司利润里扣掉
        BigDecimal totalOtherStaffCostRmb = otherStaffCostRmb(1).add(legalStaffCostRmb(yearMonth, yearMonth));
        BigDecimal totalOtherStaffCostUsd = (rate != null && rate.compareTo(BigDecimal.ZERO) > 0)
                ? totalOtherStaffCostRmb.divide(rate, SCALE, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        totalCompanyProfit = totalCompanyProfit.subtract(totalOtherStaffCostUsd);

        // 奖金（Payslip.extraBonusAmount，任何角色都可能设置，2026-07 新增计入公司利润扣减）：
        // 跟"内部其他员工成本"是两回事，也要从公司利润里扣掉——工资单模块（PayslipService.
        // computeManagement）早就是这么算的，看板这里之前漏了，导致看板"公司利润"比工资单里
        // 实际的公司利润偏高
        BigDecimal totalExtraBonusUsd = extraBonusTotalUsd(yearMonth, rate);
        totalCompanyProfit = totalCompanyProfit.subtract(totalExtraBonusUsd);

        boolean toRmb = "RMB".equalsIgnoreCase(currency);
        return DashboardSummaryResponse.builder()
                .videoProjectCount(videoCount)
                .damagedVideoProjectCount(damagedVideoCount)
                .totalClientPrice(convert(totalClientPrice, rate, toRmb))
                .totalInfluencerCost(convert(totalInfluencerCost, rate, toRmb))
                .totalOtherExternalCost(convertFromRmb(totalOtherCost, rate, toRmb))
                .totalInternalExecutionCost(convertFromRmb(totalExecCost, rate, toRmb))
                .totalInternalExecutionCostForProfit(convert(totalExecCostForProfitUsd, rate, toRmb))
                .totalOtherStaffCost(convertFromRmb(totalOtherStaffCostRmb, rate, toRmb))
                .totalExtraBonus(convert(totalExtraBonusUsd, rate, toRmb))
                .totalGrossProfit(convert(totalGrossProfit, rate, toRmb))
                .totalDistributableProfit(convert(totalDistributable, rate, toRmb))
                .totalCommissionAmount(convert(totalCommission, rate, toRmb))
                .totalCompanyProfit(convert(totalCompanyProfit, rate, toRmb))
                .currency(toRmb ? "RMB" : "USD")
                .exchangeRateInfo(rateInfo)
                .build();
    }

    /**
     * 当月所有员工"奖金"（Payslip.extraBonusAmount）合计，换算成美金——跟 PayslipService 里
     * 汇总"其他员工已确认的奖金"用的是同一套换算口径（RMB 按当月汇率换算成美金，汇率无效时
     * 保守按 0 算，不是任何角色专属，管理层给谁设置了都算）。这里不要求 Payslip 已确认/
     * 已是最终版——看板是"预计"性质的数字，只要设置了就算，不像 PayslipService 那边要等
     * 所有相关方都确认完才把这个人算进"其他人已确认"的合计。
     */
    private BigDecimal extraBonusTotalUsd(String yearMonth, BigDecimal rate) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Payslip p : payslipRepo.findByYearMonthAndIsDeletedFalse(yearMonth)) {
            if (p.getExtraBonusAmount() == null) continue;
            boolean isRmb = "RMB".equals(p.getExtraBonusCurrency());
            BigDecimal usd = isRmb
                    ? (rate != null && rate.compareTo(BigDecimal.ZERO) > 0
                        ? p.getExtraBonusAmount().divide(rate, SCALE, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO)
                    : p.getExtraBonusAmount();
            sum = sum.add(usd);
        }
        return sum;
    }

    /**
     * 财务、IT后勤角色是固定月薪，跟具体记录无关，走 otherStaffEmployees()/otherStaffCostRmb()
     * 这条路径。法务角色（2026-07 起管理层设置了当月工资就计入）走单独的 legalStaffCostRmb()
     * 路径，两者共同构成"内部其他员工成本"，不合并进这个角色集合是因为取数方式完全不同
     * （固定值 vs 每月手动录入的 Payslip.legalSalaryRmb）。
     */
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

    /** 起止月份（yyyyMM）之间逐月展开的列表，闭区间，比如 202601~202603 = [202601, 202602, 202603] */
    private List<String> monthsBetween(String startMonth, String endMonth) {
        List<String> result = new ArrayList<>();
        int y = Integer.parseInt(startMonth.substring(0, 4));
        int m = Integer.parseInt(startMonth.substring(4));
        int endY = Integer.parseInt(endMonth.substring(0, 4));
        int endM = Integer.parseInt(endMonth.substring(4));
        while (y < endY || (y == endY && m <= endM)) {
            result.add(String.format("%04d%02d", y, m));
            m++;
            if (m > 12) { m = 1; y++; }
        }
        return result;
    }

    /**
     * 法务角色薪资是管理层每月手动在"工资单"模块录入的（Payslip.legalSalaryRmb），不是像
     * 财务/IT后勤那样的固定月薪，不能简单"单月金额 × 月份数"相乘——要逐月查 Payslip 表，
     * 设置了就计入当月，没设置的月份就是 0。返回 employeeId -> 这个法务在整个月份范围内的
     * 薪资合计（人民币），供 getSummary（单月）和 drilldownOtherStaffCost（按人拆分）复用。
     */
    private Map<Long, BigDecimal> legalStaffCostRmbByEmployee(String startMonth, String endMonth) {
        Set<Long> legalEmployeeIds = new HashSet<>();
        for (Employee e : employeeCache.getAll()) {
            if ("法务".equals(e.getRole())) legalEmployeeIds.add(e.getId());
        }
        Map<Long, BigDecimal> result = new HashMap<>();
        if (legalEmployeeIds.isEmpty()) return result;
        for (String month : monthsBetween(startMonth, endMonth)) {
            for (Payslip p : payslipRepo.findByYearMonthAndIsDeletedFalse(month)) {
                if (legalEmployeeIds.contains(p.getEmployeeId()) && p.getLegalSalaryRmb() != null) {
                    result.merge(p.getEmployeeId(), p.getLegalSalaryRmb(), BigDecimal::add);
                }
            }
        }
        return result;
    }

    /** 法务全体在整个月份范围内的薪资合计（人民币），供只需要总数、不需要按人拆分的场景用 */
    private BigDecimal legalStaffCostRmb(String startMonth, String endMonth) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : legalStaffCostRmbByEmployee(startMonth, endMonth).values()) {
            sum = sum.add(v);
        }
        return sum;
    }

    // ============ 下钻：内部其他员工成本（按"员工角色-姓名"） ============
    // 注意：财务/IT后勤这部分成本压根不来自 CollaborationTracking，是固定月薪，按查询的月份
    // 范围跨了几个月来乘算；法务这部分是管理层每月手动录入的实际值，逐月查 Payslip 表求和，
    // 两种角色的取数方式不一样，不能用同一套乘法逻辑。

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
        // 2026-07 起：法务全员都要展示（不管这个月/这段范围管理层有没有在"工资单"模块设置过
        // 工资），之前只遍历 legalStaffCostRmbByEmployee 的返回结果——那个 map 只包含"至少有
        // 一个月被设置过"的法务，从没被设置过的法务会整行消失，容易让人误以为"这个法务这个月
        // 没有成本"而不是"管理层还没录入"。amount=null 配合 dimensionType=legal_unset，
        // 前端据此显示"待管理层在工资单模块设置法务当月工资"这句提示，而不是金额
        Map<Long, BigDecimal> legalTotalsRmb = legalStaffCostRmbByEmployee(startMonth, endMonth);
        for (Employee e : employeeCache.getAll()) {
            if (!"法务".equals(e.getRole())) continue;
            BigDecimal totalRmb = legalTotalsRmb.get(e.getId());
            boolean hasAmount = totalRmb != null && totalRmb.compareTo(BigDecimal.ZERO) > 0;
            rows.add(DashboardDrilldownResponse.DrilldownRow.builder()
                    .dimensionLabel("法务 - " + e.getName())
                    .dimensionType(hasAmount ? "role_name" : "legal_unset")
                    .videoCount(1L)
                    .amount(hasAmount ? convertFromRmb(totalRmb, rate, toRmb) : null)
                    .build());
        }
        rows.sort((a, b) -> {
            if (a.getAmount() == null && b.getAmount() == null) return 0;
            if (a.getAmount() == null) return 1;
            if (b.getAmount() == null) return -1;
            return b.getAmount().compareTo(a.getAmount());
        });

        return DashboardDrilldownResponse.builder()
                .currency(toRmb ? "RMB" : "USD")
                .exchangeRateInfo(exchangeRateService.getRateForMonth(endMonth))
                .rows(rows)
                .build();
    }

    // ============ 下钻：视频项目数量（按品牌方 + 红人类型） ============

    public DashboardDrilldownResponse drilldownVideoCount(String startMonth, String endMonth, String dimension) {
        List<CollaborationTracking> allOrders = trackingRepo.findByPublishMonthBetween(startMonth, endMonth);
        List<CollaborationTracking> orders = excludeDamaged(allOrders);
        long damagedCount = allOrders.size() - orders.size();

        Map<String, Long> grouped = new LinkedHashMap<>();
        String dimensionType;

        if ("brand".equals(dimension)) {
            // 按品牌方分组（2026-07 新增，不带团队维度，比默认的 brand_team 更粗一档）
            dimensionType = "brand";
            for (CollaborationTracking o : orders) {
                grouped.merge(brandNameOf(o.getBrandId()), 1L, Long::sum);
            }
        } else if ("publish_month".equals(dimension)) {
            // 按"发布时间"所在月份分组
            dimensionType = "publish_month";
            java.text.SimpleDateFormat monthFmt = new java.text.SimpleDateFormat("yyyy-MM");
            for (CollaborationTracking o : orders) {
                String key = o.getPublishDate() != null
                        ? monthFmt.format(o.getPublishDate()) : "未填写视频发布时间";
                grouped.merge(key, 1L, Long::sum);
            }
        } else if ("manager".equals(dimension)) {
            // 按项目负责人分组
            dimensionType = "manager";
            for (CollaborationTracking o : orders) {
                grouped.merge(managerNameOf(o.getProjectManagerId()), 1L, Long::sum);
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

        // "折损"的记录不参与上面任何维度分组（不该算进某个品牌方/团队/负责人头上），
        // 但仍然要让用户看到这批记录的存在——另起一行放在最后，不参与排序竞争
        if (damagedCount > 0) {
            rows.add(DashboardDrilldownResponse.DrilldownRow.builder()
                    .dimensionLabel("折损（不计入其他统计）")
                    .dimensionType(dimensionType)
                    .videoCount(damagedCount)
                    .build());
        }

        return DashboardDrilldownResponse.builder()
                .currency(null)
                .exchangeRateInfo(null)
                .rows(rows)
                .build();
    }

    // ============ 下钻：客户合作价格（按品牌方/红人团队，或按项目负责人） ============

    public DashboardDrilldownResponse drilldownClientPrice(String startMonth, String endMonth,
                                                            String currency, String dimension) {
        return drilldownAmountByDimension(startMonth, endMonth, currency, dimension, c -> c.clientPrice);
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
        List<CollaborationTracking> orders = excludeDamaged(trackingRepo.findByPublishMonthBetween(startMonth, endMonth));
        BigDecimal rate = rateForRange(endMonth);
        boolean toRmb = "RMB".equalsIgnoreCase(currency);

        Map<String, BigDecimal> grouped = new LinkedHashMap<>();
        Map<String, Long> counted = new LinkedHashMap<>();
        // "按项目负责人/品牌方/红人团队"这一档要求同一个负责人的行排在一起（见下面排序），
        // 光靠拼好的 key 字符串排不出"先按负责人分组"的效果（字符串本身混着品牌/团队），
        // 单独记一份 key -> 负责人姓名，排序时用
        Map<String, String> managerNameByKey = new LinkedHashMap<>();
        for (CollaborationTracking o : orders) {
            BigDecimal execCostRmb = safe(o.getInternalExecutionCost());
            String managerName = managerNameOf(o.getProjectManagerId());
            String key;
            switch (dimension) {
                case "manager_brand_team":
                    key = managerName + " - " + brandNameOf(o.getBrandId())
                            + " - " + teamNameOf(o.getTeam());
                    break;
                case "manager_executor":
                    key = managerName + " - " + executorNameOf(o.getExecutorId());
                    break;
                default: // manager
                    key = managerName;
            }
            // 项目负责人不是"管理层"时，这部分执行成本不影响公司利润（见 ProfitCalculator.
            // isManagementOrder），维度标签上加个后缀提醒查看的人不要误以为这些钱扣了公司利润
            if (!profitCalculator.isManagementOrder(o)) {
                key = key + "（不影响公司利润）";
            }
            grouped.merge(key, execCostRmb, BigDecimal::add);
            managerNameByKey.putIfAbsent(key, managerName);
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
                .collect(Collectors.toList());
        // "按项目负责人/品牌方/红人团队"：同一负责人的行分在一起（按负责人姓名排），组内再按
        // 金额倒序；其余维度维持原来的纯金额倒序不变
        if ("manager_brand_team".equals(dimension)) {
            rows.sort(Comparator
                    .<DashboardDrilldownResponse.DrilldownRow, String>comparing(
                            r -> managerNameByKey.getOrDefault(r.getDimensionLabel(), ""))
                    .thenComparing(Comparator.comparing(DashboardDrilldownResponse.DrilldownRow::getAmount).reversed()));
        } else {
            rows.sort((a, b) -> b.getAmount().compareTo(a.getAmount()));
        }

        return DashboardDrilldownResponse.builder()
                .currency(toRmb ? "RMB" : "USD")
                .exchangeRateInfo(exchangeRateService.getRateForMonth(endMonth))
                .rows(rows)
                .build();
    }

    // ============ 下钻：负责人提成合计（仅按负责人） ============

    /** {@link #drilldownCommission} 分组用：projectManagerId 为 null（未指定负责人）时的占位 key */
    private static final Long NO_MANAGER_KEY = -1L;

    /**
     * 2026-07 新增：提成明细面板要把"管理层"这个特殊项目负责人整行剔除（他提成固定是 0，
     * 且不参与 bonus 阶梯），其他下钻面板（客户合作价格/红人成本等）不受影响，继续按
     * managerNameOf() 用姓名字符串分组，不跟这里共用。
     */
    public DashboardDrilldownResponse drilldownCommission(String startMonth, String endMonth, String currency) {
        List<CollaborationTracking> orders = excludeDamaged(trackingRepo.findByPublishMonthBetween(startMonth, endMonth));
        BigDecimal rate = rateForRange(endMonth);
        boolean toRmb = "RMB".equalsIgnoreCase(currency);

        Map<Long, BigDecimal> grouped = new LinkedHashMap<>();
        Map<Long, Long> counted = new LinkedHashMap<>();
        for (CollaborationTracking o : orders) {
            Computed c = compute(o);
            Long managerId = o.getProjectManagerId() != null ? o.getProjectManagerId() : NO_MANAGER_KEY;
            grouped.merge(managerId, c.commissionAmount, BigDecimal::add);
            counted.merge(managerId, 1L, Long::sum);
        }

        List<DashboardDrilldownResponse.DrilldownRow> rows = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> e : grouped.entrySet()) {
            Long managerId = e.getKey();
            Employee manager = managerId.equals(NO_MANAGER_KEY) ? null : employeeCache.findById(managerId);
            // 管理层这个特殊项目负责人整行剔除：他提成固定是0，不参与 bonus 阶梯
            if (manager != null && "管理层".equals(manager.getRole())) continue;

            BigDecimal commissionUsd = e.getValue();
            // 2026-07 新增：这个负责人压根没在"员工管理"配置 bonus 阶梯规则时，bonus 列显示"-"
            // （前端 fmtAmount 对 null 就是显示"—"），跟"配置了规则但没达标/规则算出来正好是0"
            // 区分开——后者应该老老实实显示 0.00，不能也显示"-"
            boolean hasBonusRule = commissionBonusService.hasBonusTierConfigured(manager);
            BigDecimal bonusUsd = hasBonusRule ? commissionBonusService.computeBonus(manager, commissionUsd, rate) : null;
            BigDecimal totalUsd = commissionUsd.add(bonusUsd != null ? bonusUsd : BigDecimal.ZERO);
            String label = managerId.equals(NO_MANAGER_KEY) ? "未指定负责人"
                    : (manager != null ? manager.getName() : "未知负责人");
            rows.add(DashboardDrilldownResponse.DrilldownRow.builder()
                    .dimensionLabel(label)
                    .dimensionType("manager")
                    .videoCount(counted.get(managerId))
                    .amount(convert(commissionUsd, rate, toRmb))
                    .bonusAmount(bonusUsd != null ? convert(bonusUsd, rate, toRmb) : null)
                    .totalAmount(convert(totalUsd, rate, toRmb))
                    .build());
        }
        rows.sort((a, b) -> b.getTotalAmount().compareTo(a.getTotalAmount()));

        return DashboardDrilldownResponse.builder()
                .currency(toRmb ? "RMB" : "USD")
                .exchangeRateInfo(exchangeRateService.getRateForMonth(endMonth))
                .rows(rows)
                .build();
    }

    // ============ 下钻：奖金（Payslip.extraBonusAmount，按员工，2026-07 新增） ============
    // 只有"顶部汇总"总数 > 0 时前端才会显示这张卡片/允许点进来，逐月查 Payslip 表——
    // 每个月单独换算成美金再相加（每月汇率可能不同，不能先把不同月份的原始值直接相加
    // 再统一换算，那样汇率是错的），逻辑跟 legalStaffCostRmbByEmployee 是同一个思路，
    // 只是这里换算方向是"转成美金"而不是保留人民币原值。

    public DashboardDrilldownResponse drilldownExtraBonus(String startMonth, String endMonth, String currency) {
        BigDecimal rate = rateForRange(endMonth);
        boolean toRmb = "RMB".equalsIgnoreCase(currency);

        Map<Long, BigDecimal> totalUsdByEmployee = new LinkedHashMap<>();
        for (String month : monthsBetween(startMonth, endMonth)) {
            BigDecimal monthRate = exchangeRateService.getRateForMonth(month).getUsdToCny();
            for (Payslip p : payslipRepo.findByYearMonthAndIsDeletedFalse(month)) {
                if (p.getExtraBonusAmount() == null) continue;
                boolean isRmb = "RMB".equals(p.getExtraBonusCurrency());
                BigDecimal usd = isRmb
                        ? (monthRate != null && monthRate.compareTo(BigDecimal.ZERO) > 0
                            ? p.getExtraBonusAmount().divide(monthRate, SCALE, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO)
                        : p.getExtraBonusAmount();
                totalUsdByEmployee.merge(p.getEmployeeId(), usd, BigDecimal::add);
            }
        }

        List<DashboardDrilldownResponse.DrilldownRow> rows = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> e : totalUsdByEmployee.entrySet()) {
            if (e.getValue().compareTo(BigDecimal.ZERO) == 0) continue;
            Employee emp = employeeCache.findById(e.getKey());
            rows.add(DashboardDrilldownResponse.DrilldownRow.builder()
                    .dimensionLabel(emp != null ? emp.getName() : ("员工#" + e.getKey()))
                    .dimensionType("employee")
                    .videoCount(1L)
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

    // ============ 通用：按品牌方/团队/账号/类型/项目负责人 拆分金额 ============

    private DashboardDrilldownResponse drilldownAmountByDimension(
            String startMonth, String endMonth, String currency, String dimension,
            java.util.function.Function<Computed, BigDecimal> extractor) {

        List<CollaborationTracking> orders = excludeDamaged(trackingRepo.findByPublishMonthBetween(startMonth, endMonth));
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

    /**
     * 计算一条红人合作跟踪记录的各项金额，逻辑与 ProfitCalculator 保持一致。
     * 包级可见（非 private）：PayslipService 计算项目负责人提成/管理层公司利润时复用同一份公式，
     * 不能只用 Employee.defaultCommissionRate 简单相乘——提成要按这条记录自己的 commissionRate
     * 和 exchangeRate 算，跟这里完全一致。
     */
    Computed compute(CollaborationTracking o) {
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
        c.internalExecutionCostForProfitUsd = execCostUsd;
        c.grossProfit = grossProfit;
        c.distributableProfit = distributable;
        c.commissionAmount = commission;
        c.companyProfit = companyProfit;
        return c;
    }

    static class Computed {
        BigDecimal clientPrice;
        BigDecimal influencerCost;
        BigDecimal otherExternalCost;
        /** "内部执行人力成本"展示用总数——不区分是不是影响公司利润，所有已填的执行成本原始值（人民币） */
        BigDecimal internalExecutionCost;
        /**
         * 2026-07 新增：真正参与"公司利润"公式计算的那部分执行成本（美金，只有项目负责人是
         * "管理层"的记录才非零，见 ProfitCalculator.isManagementOrder）。之前"公司利润"公式
         * 展示时错误地复用了上面 internalExecutionCost 那个未筛选的总数，导致公式里几项加减
         * 对不上（虽然 companyProfit 本身算出来是对的）——公式展示要用这个字段，不是
         * internalExecutionCost。工资单模块（PayslipService）算公司利润时用的就是这个口径。
         */
        BigDecimal internalExecutionCostForProfitUsd;
        BigDecimal grossProfit;
        BigDecimal distributableProfit;
        BigDecimal commissionAmount;
        BigDecimal companyProfit;
    }

    String brandNameOf(Long brandId) {
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
    String teamNameOf(InfluencerTeam team) {
        if (team == null || team.getName() == null || team.getName().trim().isEmpty()) return "";
        return team.getName();
    }

    /** 用于单独按"红人团队"下钻展示：没有团队时显示明确提示语，而不是留空 */
    private String teamDisplayName(InfluencerTeam team) {
        String name = teamNameOf(team);
        return name.isEmpty() ? "（红人无所属团队）" : name;
    }

    /** 下钻接口统一用范围终止月份对应的汇率（即查看的最新月份的"上月最后工作日"汇率） */
    BigDecimal rateForRange(String endMonth) {
        return exchangeRateService.getRateForMonth(endMonth).getUsdToCny();
    }

    BigDecimal convert(BigDecimal usdAmount, BigDecimal rate, boolean toRmb) {
        if (usdAmount == null) usdAmount = BigDecimal.ZERO;
        if (!toRmb || rate == null) return usdAmount.setScale(SCALE, RoundingMode.HALF_UP);
        return usdAmount.multiply(rate).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 其他外部成本、内部执行成本合计这两个字段本身就是人民币原值（不是美元），
     * 换算方向跟 convert() 正好相反：要人民币就直接原样返回，要美元才需要除以汇率。
     */
    BigDecimal convertFromRmb(BigDecimal rmbAmount, BigDecimal rate, boolean toRmb) {
        if (rmbAmount == null) rmbAmount = BigDecimal.ZERO;
        if (toRmb || rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
            return rmbAmount.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return rmbAmount.divide(rate, SCALE, RoundingMode.HALF_UP);
    }

    BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * 数据看板统计口径统一排除"折损"（DELAYED）的记录：折损代表这笔视频项目因异常原因终止，
     * 不是真实发生的业务，客户合作价格/红人成本/项目毛利/内部执行成本/负责人提成/公司利润
     * 这些看板数字（含顶部汇总和所有下钻明细）都不应该把这些记录算进去。findByPublishMonth(Between)
     * 这两个仓储方法本身是通用查询（工资单等其他模块也在用同一套"当月未删除记录"语义，仓储层
     * 不能加这个过滤），所以统一在看板服务这一层过滤，取数后立即调用，不要漏了某个下钻口径。
     */
    private List<CollaborationTracking> excludeDamaged(List<CollaborationTracking> orders) {
        return orders.stream()
                .filter(o -> o.getProgress() != CollaborationProgress.DELAYED)
                .collect(Collectors.toList());
    }
}
