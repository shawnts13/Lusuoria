package com.lusuoria.settlement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.dto.response.DashboardDrilldownResponse;
import com.lusuoria.settlement.dto.response.DashboardManagerTrendResponse;
import com.lusuoria.settlement.dto.response.DashboardPivotResponse;
import com.lusuoria.settlement.dto.response.DashboardRangeSummaryResponse;
import com.lusuoria.settlement.dto.response.DashboardSummaryResponse;
import com.lusuoria.settlement.dto.response.ExchangeRateInfo;
import com.lusuoria.settlement.dto.response.PayslipDetailResponse;
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
import java.text.SimpleDateFormat;
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
    @Autowired private ObjectMapper objectMapper;

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
        BigDecimal totalClientSettledAmount = BigDecimal.ZERO;
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
            if (o.getProgress() == CollaborationProgress.SETTLED) {
                totalClientSettledAmount = totalClientSettledAmount.add(c.clientPrice);
            }
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
        BigDecimal totalOtherStaffCostRmb = otherStaffCostRmb(java.util.Collections.singletonList(yearMonth))
                .add(legalStaffCostRmb(yearMonth, yearMonth));
        BigDecimal totalOtherStaffCostUsd = (rate != null && rate.compareTo(BigDecimal.ZERO) > 0)
                ? totalOtherStaffCostRmb.divide(rate, SCALE, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        totalCompanyProfit = totalCompanyProfit.subtract(totalOtherStaffCostUsd);

        // 奖金（Payslip.extraBonusAmount，任何角色都可能设置，2026-07 新增计入公司利润扣减）：
        // 跟"内部其他员工成本"是两回事，也要从公司利润里扣掉——工资单模块（PayslipService.
        // computeManagement）早就是这么算的，看板这里之前漏了，导致看板"公司利润"比工资单里
        // 实际的公司利润偏高
        BigDecimal totalExtraBonusUsd = extraBonusTotalUsd(yearMonth, rate);
        totalCompanyProfit = totalCompanyProfit.subtract(totalExtraBonusUsd);

        // 负责人阶梯Bonus（2026-08-10 新增，见 tierBonusTotalUsd() 注释）：并进"负责人提成合计"
        // 一起展示（Shawn 确认按跟工资单一样的"（含Bonus）"口径合并展示，不单独拆一行），
        // 同时也要从公司利润里扣掉——之前完全没算这一项，是看板"公司利润"比工资单偏高的另一个原因
        BigDecimal totalTierBonusUsd = tierBonusTotalUsd(yearMonth);
        totalCommission = totalCommission.add(totalTierBonusUsd);
        totalCompanyProfit = totalCompanyProfit.subtract(totalTierBonusUsd);

        boolean toRmb = "RMB".equalsIgnoreCase(currency);
        return DashboardSummaryResponse.builder()
                .videoProjectCount(videoCount)
                .damagedVideoProjectCount(damagedVideoCount)
                .totalClientPrice(convert(totalClientPrice, rate, toRmb))
                .totalClientSettledAmount(convert(totalClientSettledAmount, rate, toRmb))
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
     * 顶部汇总的"视频发布日期"区间变体（2026-08 新增）：跟 {@link #getSummary} 是同一套字段和
     * 公式，只是记录来源换成按"发布时间"精确到天的区间查询，不要求整月。
     *
     * "内部其他员工成本"（财务/IT后勤固定月薪 + 法务当月工资）和"奖金"（Payslip.extraBonusAmount）
     * 这两项本质是按月设置的，没有"某一天的工资/奖金"这个概念，不按天折算——日期区间覆盖到
     * 哪几个月，就把这几个月的固定成本/奖金整月计入（哪怕区间只覆盖某个月的几天），
     * 跟 {@link #getRangeSummary} 处理跨月区间是同一个思路。展示用汇率固定取区间覆盖到的
     * 最后一个月（跟 sumMonthly 等其他跨月场景保持一致的取法）。
     *
     * @param startDate 起始日期 yyyy-MM-dd
     * @param endDate   截止日期 yyyy-MM-dd（闭区间，含这天）
     * @param currency  USD 或 RMB
     */
    public DashboardSummaryResponse getSummaryByDateRange(String startDate, String endDate, String currency) {
        List<CollaborationTracking> allOrders = trackingRepo.findByPublishDateBetween(startDate, endDate);
        long videoCount = allOrders.size();
        long damagedVideoCount = allOrders.stream()
                .filter(o -> o.getProgress() == CollaborationProgress.DELAYED).count();
        List<CollaborationTracking> orders = excludeDamaged(allOrders);

        BigDecimal totalClientPrice = BigDecimal.ZERO;
        BigDecimal totalClientSettledAmount = BigDecimal.ZERO;
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
            if (o.getProgress() == CollaborationProgress.SETTLED) {
                totalClientSettledAmount = totalClientSettledAmount.add(c.clientPrice);
            }
            totalInfluencerCost = totalInfluencerCost.add(c.influencerCost);
            totalOtherCost      = totalOtherCost.add(c.otherExternalCost);
            totalExecCost       = totalExecCost.add(c.internalExecutionCost);
            totalExecCostForProfitUsd = totalExecCostForProfitUsd.add(c.internalExecutionCostForProfitUsd);
            totalGrossProfit    = totalGrossProfit.add(c.grossProfit);
            totalDistributable  = totalDistributable.add(c.distributableProfit);
            totalCommission     = totalCommission.add(c.commissionAmount);
            totalCompanyProfit  = totalCompanyProfit.add(c.companyProfit);
        }

        String startMonth = monthOf(startDate);
        String endMonth = monthOf(endDate);
        List<String> touchedMonths = monthsBetween(startMonth, endMonth);

        ExchangeRateInfo rateInfo = exchangeRateService.getRateForMonth(endMonth);
        BigDecimal rate = rateInfo.getUsdToCny();

        BigDecimal totalOtherStaffCostRmb = otherStaffCostRmb(touchedMonths).add(legalStaffCostRmb(startMonth, endMonth));
        BigDecimal totalOtherStaffCostUsd = (rate != null && rate.compareTo(BigDecimal.ZERO) > 0)
                ? totalOtherStaffCostRmb.divide(rate, SCALE, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        totalCompanyProfit = totalCompanyProfit.subtract(totalOtherStaffCostUsd);

        BigDecimal totalExtraBonusUsd = BigDecimal.ZERO;
        for (String m : touchedMonths) {
            totalExtraBonusUsd = totalExtraBonusUsd.add(extraBonusTotalUsd(m, rate));
        }
        totalCompanyProfit = totalCompanyProfit.subtract(totalExtraBonusUsd);

        // 负责人阶梯Bonus（2026-08-10 新增，见 tierBonusTotalUsd() 注释及 getSummary() 同一处的
        // 说明）：并进"负责人提成合计"一起展示，同时从公司利润里扣掉，日期区间覆盖到的每个月
        // 各自算好再相加，跟这个方法处理"内部其他员工成本"/"奖金"跨月求和是同一个思路
        BigDecimal totalTierBonusUsd = BigDecimal.ZERO;
        for (String m : touchedMonths) {
            totalTierBonusUsd = totalTierBonusUsd.add(tierBonusTotalUsd(m));
        }
        totalCommission = totalCommission.add(totalTierBonusUsd);
        totalCompanyProfit = totalCompanyProfit.subtract(totalTierBonusUsd);

        boolean toRmb = "RMB".equalsIgnoreCase(currency);
        return DashboardSummaryResponse.builder()
                .videoProjectCount(videoCount)
                .damagedVideoProjectCount(damagedVideoCount)
                .totalClientPrice(convert(totalClientPrice, rate, toRmb))
                .totalClientSettledAmount(convert(totalClientSettledAmount, rate, toRmb))
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
     * 区间汇总（年度报告/同比用，2026-07 新增）：逐月调用现有单月 {@link #getSummary}
     * 后求和——每个月各自按当月汇率换算好之后再相加，不是整段区间统一按一个汇率换算，
     * 天然是"按月折算"的正确做法，不需要额外写汇率逻辑。
     *
     * @param startMonth 起始月份 yyyyMM
     * @param endMonth   截止月份 yyyyMM（闭区间，含这个月）
     * @param currency   USD 或 RMB
     */
    public DashboardRangeSummaryResponse getRangeSummary(String startMonth, String endMonth, String currency) {
        List<String> months = monthsBetween(startMonth, endMonth);
        List<DashboardSummaryResponse> monthly = new ArrayList<>();
        for (String m : months) {
            DashboardSummaryResponse s = getSummary(m, currency);
            s.setYearMonth(m);
            monthly.add(s);
        }
        DashboardSummaryResponse total = sumMonthly(monthly, endMonth);
        boolean toRmb = "RMB".equalsIgnoreCase(currency);
        return DashboardRangeSummaryResponse.builder()
                .startMonth(startMonth)
                .endMonth(endMonth)
                .currency(toRmb ? "RMB" : "USD")
                .monthly(monthly)
                .total(total)
                .build();
    }

    /** {@link #getRangeSummary} 用：把逐月已经换算好的汇总结果逐项相加 */
    private DashboardSummaryResponse sumMonthly(List<DashboardSummaryResponse> monthly, String endMonth) {
        long videoCount = 0;
        long damagedCount = 0;
        BigDecimal clientPrice = BigDecimal.ZERO, influencerCost = BigDecimal.ZERO, otherCost = BigDecimal.ZERO,
                execCost = BigDecimal.ZERO, execCostForProfit = BigDecimal.ZERO, otherStaffCost = BigDecimal.ZERO,
                extraBonus = BigDecimal.ZERO, grossProfit = BigDecimal.ZERO, distributable = BigDecimal.ZERO,
                commission = BigDecimal.ZERO, companyProfit = BigDecimal.ZERO;
        String currency = null;
        for (DashboardSummaryResponse s : monthly) {
            videoCount += s.getVideoProjectCount() != null ? s.getVideoProjectCount() : 0;
            damagedCount += s.getDamagedVideoProjectCount() != null ? s.getDamagedVideoProjectCount() : 0;
            clientPrice = clientPrice.add(safe(s.getTotalClientPrice()));
            influencerCost = influencerCost.add(safe(s.getTotalInfluencerCost()));
            otherCost = otherCost.add(safe(s.getTotalOtherExternalCost()));
            execCost = execCost.add(safe(s.getTotalInternalExecutionCost()));
            execCostForProfit = execCostForProfit.add(safe(s.getTotalInternalExecutionCostForProfit()));
            otherStaffCost = otherStaffCost.add(safe(s.getTotalOtherStaffCost()));
            extraBonus = extraBonus.add(safe(s.getTotalExtraBonus()));
            grossProfit = grossProfit.add(safe(s.getTotalGrossProfit()));
            distributable = distributable.add(safe(s.getTotalDistributableProfit()));
            commission = commission.add(safe(s.getTotalCommissionAmount()));
            companyProfit = companyProfit.add(safe(s.getTotalCompanyProfit()));
            currency = s.getCurrency();
        }
        return DashboardSummaryResponse.builder()
                .videoProjectCount(videoCount)
                .damagedVideoProjectCount(damagedCount)
                .totalClientPrice(clientPrice.setScale(SCALE, RoundingMode.HALF_UP))
                .totalInfluencerCost(influencerCost.setScale(SCALE, RoundingMode.HALF_UP))
                .totalOtherExternalCost(otherCost.setScale(SCALE, RoundingMode.HALF_UP))
                .totalInternalExecutionCost(execCost.setScale(SCALE, RoundingMode.HALF_UP))
                .totalInternalExecutionCostForProfit(execCostForProfit.setScale(SCALE, RoundingMode.HALF_UP))
                .totalOtherStaffCost(otherStaffCost.setScale(SCALE, RoundingMode.HALF_UP))
                .totalExtraBonus(extraBonus.setScale(SCALE, RoundingMode.HALF_UP))
                .totalGrossProfit(grossProfit.setScale(SCALE, RoundingMode.HALF_UP))
                .totalDistributableProfit(distributable.setScale(SCALE, RoundingMode.HALF_UP))
                .totalCommissionAmount(commission.setScale(SCALE, RoundingMode.HALF_UP))
                .totalCompanyProfit(companyProfit.setScale(SCALE, RoundingMode.HALF_UP))
                .currency(currency)
                // 合计本身没有单一汇率概念（每月已经各自折算过了），这里的汇率信息只是给前端展示
                // "当前汇率参考"用，不参与任何计算
                .exchangeRateInfo(exchangeRateService.getRateForMonth(endMonth))
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
     * 当月所有项目负责人/管理层的阶梯Bonus（美金，Payslip.detailJson 快照里的
     * tierBonusAmount）合计——2026-08-10 新增，修复"负责人提成合计"跟工资单模块对不上的问题：
     * PayslipService.computeManagement() 的"负责人提成合计（含Bonus）"= 原始提成 + 这笔阶梯
     * Bonus，看板这边之前完全没算这一项、也没在"公司利润"公式里扣掉，导致管理层确认某个月后，
     * 看板算出来的公司利润比工资单偏高（少扣了这笔真实成本），Shawn 手动比对两边公式发现的。
     *
     * 只统计 finalConfirmed=true 的记录，不像 extraBonusTotalUsd()/legalStaffCostRmb() 那样
     * "预计"性质地读未确认的实时值——这不是本方法自己收紧口径，是这个字段本身的性质决定的：
     * 阶梯Bonus 只有在 Payslip 被确认成最终版时才会按当时锁定的提成金额算出来、写进
     * detailJson 快照（见 PayslipService.applyFinalSnapshot），未确认之前压根没有这个值可读；
     * PayslipService.computeManagement() 自己汇总"其他人的"阶梯Bonus 时（othersConfirmed）
     * 用的也是同一个 finalConfirmed 条件，这里保持一致，不是新引入的限制。
     * tierBonusAmount 快照里已经是美金（见 PayslipDetailResponse 字段注释），不需要再按汇率换算。
     */
    private BigDecimal tierBonusTotalUsd(String yearMonth) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Payslip p : payslipRepo.findByYearMonthAndIsDeletedFalse(yearMonth)) {
            if (!Boolean.TRUE.equals(p.getFinalConfirmed()) || p.getDetailJson() == null) continue;
            try {
                PayslipDetailResponse snap = objectMapper.readValue(p.getDetailJson(), PayslipDetailResponse.class);
                if (snap.getTierBonusAmount() != null) sum = sum.add(snap.getTierBonusAmount());
            } catch (Exception e) {
                // 反序列化失败不该让整个看板汇总接口挂掉——跳过这一条按0处理，不影响其他记录
                // （正常情况下不会走到这里，detailJson 是系统自己写的，格式必然合法）
            }
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

    /**
     * 该员工在给定月份是否还没入职（入职时间没标注时视为一直在职，不过滤）——2026-08 新增，
     * 修复"财务/IT后勤固定月薪 × 月份数"这种简化算法把入职之前的月份也算成本的问题
     * （用户反馈：管理层工资单确认按钮/公司利润被入职时间晚的新员工影响，这里是同一类问题
     * 在数据看板/年度报告/双月对比这边的表现）。跟 PayslipService.isBeforeHireMonth() 是
     * 同一套判断逻辑，两个模块目前没有共享工具类，各自维护一份，规则改动时要两边一起改。
     */
    private boolean isBeforeHireMonth(Employee e, String yearMonth) {
        if (e.getHireDate() == null) return false;
        String hireMonth = new SimpleDateFormat("yyyyMM").format(e.getHireDate());
        return yearMonth.compareTo(hireMonth) < 0;
    }

    /**
     * 财务+IT后勤在给定月份列表内的固定月薪合计（人民币）——逐月判断每个人是否已经入职，
     * 跟法务 legalStaffCostRmb() 是同一个"按月份列表逐月计算"的思路（2026-08 修复）。
     * 之前是"当前月薪 × 月份数"，会把入职之前的月份也按当前在职算进成本，年度报告/
     * 双月对比这类跨月查询尤其容易受影响。
     */
    private BigDecimal otherStaffCostRmb(List<String> months) {
        BigDecimal sum = BigDecimal.ZERO;
        List<Employee> staff = otherStaffEmployees();
        for (String month : months) {
            for (Employee e : staff) {
                if (!isBeforeHireMonth(e, month)) {
                    sum = sum.add(safe(e.getFixedMonthlySalary()));
                }
            }
        }
        return sum;
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

    /** 'yyyy-MM-dd' 取前7位去掉横杠，变成 'yyyyMM'——日期区间筛选换算成月份范围时统一走这个 */
    private String monthOf(String yyyyMmDd) {
        return yyyyMmDd.substring(0, 7).replace("-", "");
    }

    /**
     * 下钻查询的记录来源：日期区间（startDate/endDate 都给了）优先于月份区间——2026-08
     * "视频发布日期"筛选新增。前端保证这两种筛选互斥，不会同时传两套都非空的参数，
     * 这里按"有日期就用日期"的优先级处理，不用额外校验冲突。
     */
    private List<CollaborationTracking> fetchOrdersForPeriod(
            String startMonth, String endMonth, String startDate, String endDate) {
        if (startDate != null && endDate != null) {
            return trackingRepo.findByPublishDateBetween(startDate, endDate);
        }
        return trackingRepo.findByPublishMonthBetween(startMonth, endMonth);
    }

    /**
     * 下钻结果里"展示用汇率对应的月份"：日期区间模式下 endMonth 本身是 null（前端没传），
     * 从 endDate 反推；月份区间模式下 endMonth 直接就是要的值。
     */
    private String effectiveEndMonth(String endMonth, String endDate) {
        return endMonth != null ? endMonth : monthOf(endDate);
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
    // 注意：财务/IT后勤这部分成本压根不来自 CollaborationTracking，是固定月薪，逐月判断入职
    // 状态再计入（2026-08 起不再是简单"月薪 × 月份数"，见 otherStaffCostRmb() 的注释）；
    // 法务这部分是管理层每月手动录入的实际值，逐月查 Payslip 表求和，
    // 两种角色的取数方式不一样，不能用同一套乘法逻辑。

    public DashboardDrilldownResponse drilldownOtherStaffCost(String startMonth, String endMonth, String currency) {
        BigDecimal rate = rateForRange(endMonth);
        boolean toRmb = "RMB".equalsIgnoreCase(currency);
        List<String> months = monthsBetween(startMonth, endMonth);

        List<DashboardDrilldownResponse.DrilldownRow> rows = new ArrayList<>();
        for (Employee e : otherStaffEmployees()) {
            // 按月逐个判断入职状态再计入，不能简单"当前月薪 × 月份数"——那样会把入职之前的
            // 月份也按当前在职算进这个人的成本（2026-08 修复，跟上面 otherStaffCostRmb() 同一
            // 个问题、同一个思路修的）
            long eligibleMonths = months.stream().filter(m -> !isBeforeHireMonth(e, m)).count();
            BigDecimal amountRmb = safe(e.getFixedMonthlySalary()).multiply(BigDecimal.valueOf(eligibleMonths));
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

    public DashboardDrilldownResponse drilldownVideoCount(String startMonth, String endMonth,
                                                           String startDate, String endDate, String dimension) {
        List<CollaborationTracking> allOrders = fetchOrdersForPeriod(startMonth, endMonth, startDate, endDate);
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
        } else if ("executor".equals(dimension)) {
            // 按内部执行人员分组（2026-07 新增，年度报告/双月对比的员工个人数据用）
            dimensionType = "executor";
            for (CollaborationTracking o : orders) {
                grouped.merge(executorNameOf(o.getExecutorId()), 1L, Long::sum);
            }
        } else if ("countryMarket".equals(dimension)) {
            // 按服务国家/市场分组（2026-07 新增，年度报告/双月对比用）
            dimensionType = "countryMarket";
            for (CollaborationTracking o : orders) {
                grouped.merge(countryMarketOf(o.getCountryMarket()), 1L, Long::sum);
            }
        } else if ("platform".equals(dimension)) {
            // 按合作平台分组（2026-07 新增）：一条记录可能同时涉及多个平台，各平台各自计一次，
            // 加总可能超过实际总记录数，属于预期行为，见 splitPlatforms 的注释
            dimensionType = "platform";
            for (CollaborationTracking o : orders) {
                List<String> platforms = splitPlatforms(o.getPlatform());
                if (platforms.isEmpty()) platforms = Collections.singletonList("未指定平台");
                for (String p : platforms) {
                    grouped.merge(p, 1L, Long::sum);
                }
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
                                                            String startDate, String endDate,
                                                            String currency, String dimension) {
        return drilldownAmountByDimension(startMonth, endMonth, startDate, endDate, currency, dimension, c -> c.clientPrice, false);
    }

    // ============ 下钻：客户已回款总金额（同"客户合作价格"维度，只是多过滤 进度=客户已结算） ============

    public DashboardDrilldownResponse drilldownClientSettledAmount(String startMonth, String endMonth,
                                                                     String startDate, String endDate,
                                                                     String currency, String dimension) {
        return drilldownAmountByDimension(startMonth, endMonth, startDate, endDate, currency, dimension, c -> c.clientPrice, true);
    }

    // ============ 下钻：红人成本（按品牌方/团队/账号/类型） ============

    public DashboardDrilldownResponse drilldownInfluencerCost(String startMonth, String endMonth,
                                                               String startDate, String endDate,
                                                               String currency, String dimension) {
        return drilldownAmountByDimension(startMonth, endMonth, startDate, endDate, currency, dimension, c -> c.influencerCost, false);
    }

    // ============ 下钻：项目毛利（按品牌方/团队/账号/类型） ============

    public DashboardDrilldownResponse drilldownGrossProfit(String startMonth, String endMonth,
                                                            String startDate, String endDate,
                                                            String currency, String dimension) {
        return drilldownAmountByDimension(startMonth, endMonth, startDate, endDate, currency, dimension, c -> c.grossProfit, false);
    }

    // ============ 下钻：公司利润（美金/人民币，品牌方/团队/账号/类型/品牌方-团队 可切换） ============

    public DashboardDrilldownResponse drilldownCompanyProfit(String startMonth, String endMonth,
                                                              String startDate, String endDate,
                                                              String currency, String dimension) {
        return drilldownAmountByDimension(startMonth, endMonth, startDate, endDate, currency, dimension, c -> c.companyProfit, false);
    }

    // ============ 下钻：内部执行人力成本（按项目负责人，或项目负责人-品牌方-团队） ============
    // 注意：这个字段本身是人民币，跟其他美元计价的字段方向相反，不能复用 drilldownAmountByDimension
    // （那个用的是 convert()，是按"输入是美元"来处理的），这里单独写一份用 convertFromRmb()。
    // 这里故意统计的是"所有已填的内部执行成本"原始总和，不区分是不是影响公司利润
    // （跟看板最上面那个汇总数字口径一致——那个数字本身不受"是否管理层"这条规则影响，
    // 这个下钻明细只是把那个总数字拆开来看，口径也应该保持一致）。

    public DashboardDrilldownResponse drilldownExecutionCost(String startMonth, String endMonth,
                                                              String startDate, String endDate,
                                                              String currency, String dimension) {
        List<CollaborationTracking> orders = excludeDamaged(fetchOrdersForPeriod(startMonth, endMonth, startDate, endDate));
        boolean toRmb = "RMB".equalsIgnoreCase(currency);
        Map<String, BigDecimal> monthRateCache = buildMonthRateCache(orders);

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
            // 按记录自己所在月份的汇率折算后再汇总（原因同 drilldownAmountByDimension）
            BigDecimal converted = convertFromRmb(execCostRmb, monthRateOf(o, monthRateCache), toRmb);
            grouped.merge(key, converted, BigDecimal::add);
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
                        .amount(e.getValue().setScale(SCALE, RoundingMode.HALF_UP))
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
                .exchangeRateInfo(exchangeRateService.getRateForMonth(effectiveEndMonth(endMonth, endDate)))
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
    public DashboardDrilldownResponse drilldownCommission(String startMonth, String endMonth,
                                                           String startDate, String endDate, String currency) {
        List<CollaborationTracking> orders = excludeDamaged(fetchOrdersForPeriod(startMonth, endMonth, startDate, endDate));
        // bonus 阶梯规则本身按区间末月汇率门槛判定，与逐条记录折算无关，保留不变
        BigDecimal rangeRate = rateForRange(effectiveEndMonth(endMonth, endDate));
        boolean toRmb = "RMB".equalsIgnoreCase(currency);
        Map<String, BigDecimal> monthRateCache = buildMonthRateCache(orders);

        // commissionAmount（美元原值）按记录自己所在月份的汇率折算后再汇总（原因同
        // drilldownAmountByDimension），跟 bonus 阶梯判定用的区间末月汇率是两回事，分开存
        Map<Long, BigDecimal> groupedUsd = new LinkedHashMap<>(); // 汇总用：折算前的美元原值，供 bonus 阶梯计算
        Map<Long, BigDecimal> groupedConverted = new LinkedHashMap<>(); // 展示用：按月折算后的结果
        Map<Long, Long> counted = new LinkedHashMap<>();
        for (CollaborationTracking o : orders) {
            Computed c = compute(o);
            Long managerId = o.getProjectManagerId() != null ? o.getProjectManagerId() : NO_MANAGER_KEY;
            groupedUsd.merge(managerId, c.commissionAmount, BigDecimal::add);
            groupedConverted.merge(managerId, convert(c.commissionAmount, monthRateOf(o, monthRateCache), toRmb), BigDecimal::add);
            counted.merge(managerId, 1L, Long::sum);
        }

        List<DashboardDrilldownResponse.DrilldownRow> rows = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> e : groupedUsd.entrySet()) {
            Long managerId = e.getKey();
            Employee manager = managerId.equals(NO_MANAGER_KEY) ? null : employeeCache.findById(managerId);
            // 管理层这个特殊项目负责人整行剔除：他提成固定是0，不参与 bonus 阶梯
            if (manager != null && "管理层".equals(manager.getRole())) continue;

            BigDecimal commissionUsd = e.getValue();
            BigDecimal commissionConverted = groupedConverted.get(managerId).setScale(SCALE, RoundingMode.HALF_UP);
            // 2026-07 新增：这个负责人压根没在"员工管理"配置 bonus 阶梯规则时，bonus 列显示"-"
            // （前端 fmtAmount 对 null 就是显示"—"），跟"配置了规则但没达标/规则算出来正好是0"
            // 区分开——后者应该老老实实显示 0.00，不能也显示"-"
            boolean hasBonusRule = commissionBonusService.hasBonusTierConfigured(manager);
            BigDecimal bonusUsd = hasBonusRule ? commissionBonusService.computeBonus(manager, commissionUsd, rangeRate) : null;
            BigDecimal bonusConverted = bonusUsd != null ? convert(bonusUsd, rangeRate, toRmb) : null;
            BigDecimal totalConverted = commissionConverted.add(bonusConverted != null ? bonusConverted : BigDecimal.ZERO);
            String label = managerId.equals(NO_MANAGER_KEY) ? "未指定负责人"
                    : (manager != null ? manager.getName() : "未知负责人");
            rows.add(DashboardDrilldownResponse.DrilldownRow.builder()
                    .dimensionLabel(label)
                    .dimensionType("manager")
                    .videoCount(counted.get(managerId))
                    .amount(commissionConverted)
                    .bonusAmount(bonusConverted)
                    .totalAmount(totalConverted)
                    .build());
        }
        rows.sort((a, b) -> b.getTotalAmount().compareTo(a.getTotalAmount()));

        return DashboardDrilldownResponse.builder()
                .currency(toRmb ? "RMB" : "USD")
                .exchangeRateInfo(exchangeRateService.getRateForMonth(effectiveEndMonth(endMonth, endDate)))
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

    // ============ 下钻：维度交叉透视（2026-07 新增，年度报告用） ============
    // 只支持预设的几个精选组合，不做通用N维透视引擎——见 DashboardPivotResponse 类注释

    private static final Set<String> ALLOWED_PIVOT_PAIRS = new HashSet<>(Arrays.asList(
            "brand:countryMarket", "brand:platform", "team:countryMarket"));

    public DashboardPivotResponse drilldownPivot(String startMonth, String endMonth, String currency,
                                                  String rowDimension, String colDimension) {
        String pairKey = rowDimension + ":" + colDimension;
        if (!ALLOWED_PIVOT_PAIRS.contains(pairKey)) {
            throw new RuntimeException("不支持的维度交叉组合: " + rowDimension + " x " + colDimension);
        }
        List<CollaborationTracking> orders = excludeDamaged(trackingRepo.findByPublishMonthBetween(startMonth, endMonth));
        boolean toRmb = "RMB".equalsIgnoreCase(currency);
        Map<String, BigDecimal> monthRateCache = buildMonthRateCache(orders);

        Map<String, BigDecimal> rowTotals = new LinkedHashMap<>();
        Map<String, BigDecimal> colTotals = new LinkedHashMap<>();
        Map<String, PivotAccumulator> cellMap = new LinkedHashMap<>();

        for (CollaborationTracking o : orders) {
            Computed c = compute(o);
            BigDecimal rate = monthRateOf(o, monthRateCache);
            BigDecimal clientPrice = convert(c.clientPrice, rate, toRmb);
            BigDecimal grossProfit = convert(c.grossProfit, rate, toRmb);
            BigDecimal companyProfit = convert(c.companyProfit, rate, toRmb);

            List<String> rowLabelsForRecord = resolvePivotLabels(rowDimension, o);
            List<String> colLabelsForRecord = resolvePivotLabels(colDimension, o);
            for (String row : rowLabelsForRecord) {
                rowTotals.merge(row, clientPrice, BigDecimal::add);
                // key 用控制字符分隔行列标签，避免标签本身含普通分隔符（如" - "）时拼接歧义
                for (String col : colLabelsForRecord) {
                    String key = row + "" + col;
                    PivotAccumulator acc = cellMap.computeIfAbsent(key, k -> new PivotAccumulator(row, col));
                    acc.videoCount += 1;
                    acc.clientPrice = acc.clientPrice.add(clientPrice);
                    acc.grossProfit = acc.grossProfit.add(grossProfit);
                    acc.companyProfit = acc.companyProfit.add(companyProfit);
                }
            }
            for (String col : colLabelsForRecord) {
                colTotals.merge(col, clientPrice, BigDecimal::add);
            }
        }

        List<String> rowLabels = rowTotals.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .map(Map.Entry::getKey).collect(Collectors.toList());
        List<String> colLabels = colTotals.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .map(Map.Entry::getKey).collect(Collectors.toList());
        List<DashboardPivotResponse.PivotCell> cells = cellMap.values().stream()
                .map(acc -> DashboardPivotResponse.PivotCell.builder()
                        .rowLabel(acc.rowLabel)
                        .colLabel(acc.colLabel)
                        .videoCount(acc.videoCount)
                        .clientPrice(acc.clientPrice.setScale(SCALE, RoundingMode.HALF_UP))
                        .grossProfit(acc.grossProfit.setScale(SCALE, RoundingMode.HALF_UP))
                        .companyProfit(acc.companyProfit.setScale(SCALE, RoundingMode.HALF_UP))
                        .build())
                .collect(Collectors.toList());

        return DashboardPivotResponse.builder()
                .currency(toRmb ? "RMB" : "USD")
                .exchangeRateInfo(exchangeRateService.getRateForMonth(endMonth))
                .rowLabels(rowLabels)
                .colLabels(colLabels)
                .cells(cells)
                .build();
    }

    /** {@link #drilldownPivot} 用：单个 (行,列) 组合的累计值 */
    private static class PivotAccumulator {
        final String rowLabel;
        final String colLabel;
        long videoCount = 0;
        BigDecimal clientPrice = BigDecimal.ZERO;
        BigDecimal grossProfit = BigDecimal.ZERO;
        BigDecimal companyProfit = BigDecimal.ZERO;

        PivotAccumulator(String rowLabel, String colLabel) {
            this.rowLabel = rowLabel;
            this.colLabel = colLabel;
        }
    }

    /** {@link #drilldownPivot} 用：把维度值解析成标签列表（platform 是多值，可能 fan-out 到多个） */
    private List<String> resolvePivotLabels(String dimension, CollaborationTracking o) {
        switch (dimension) {
            case "brand":
                return Collections.singletonList(brandNameOf(o.getBrandId()));
            case "team":
                return Collections.singletonList(teamDisplayName(o.getTeam()));
            case "countryMarket":
                return Collections.singletonList(countryMarketOf(o.getCountryMarket()));
            case "platform":
                List<String> platforms = splitPlatforms(o.getPlatform());
                return platforms.isEmpty() ? Collections.singletonList("未指定平台") : platforms;
            default:
                throw new RuntimeException("不支持的透视维度: " + dimension);
        }
    }

    // ============ 员工个人趋势（2026-07 新增，仅年度报告用） ============
    // 按月循环（跟 getRangeSummary 同样的模式），一次HTTP调用内在后端拼好"负责人×月份"矩阵，
    // 避免前端对每个负责人每个月分别发请求（会到上百次，Render 免费层数据库连接池只有3个）。

    public DashboardManagerTrendResponse getManagerTrend(String startMonth, String endMonth,
                                                          String currency, String role) {
        boolean isExecutor = "executor".equals(role);
        boolean toRmb = "RMB".equalsIgnoreCase(currency);
        List<String> months = monthsBetween(startMonth, endMonth);

        Map<Long, String> nameById = new LinkedHashMap<>();
        Map<Long, Map<String, TrendAccumulator>> data = new LinkedHashMap<>();

        for (String month : months) {
            List<CollaborationTracking> orders = excludeDamaged(trackingRepo.findByPublishMonth(month));
            BigDecimal rate = exchangeRateService.getRateForMonth(month).getUsdToCny();
            for (CollaborationTracking o : orders) {
                Long personId = isExecutor ? o.getExecutorId() : o.getProjectManagerId();
                if (personId == null) continue; // 未指定负责人/执行人员的记录不计入个人趋势
                Computed c = compute(o);
                nameById.putIfAbsent(personId, isExecutor ? executorNameOf(personId) : managerNameOf(personId));
                TrendAccumulator acc = data.computeIfAbsent(personId, k -> new LinkedHashMap<>())
                        .computeIfAbsent(month, k -> new TrendAccumulator());
                acc.videoCount += 1;
                acc.clientPrice = acc.clientPrice.add(convert(c.clientPrice, rate, toRmb));
                acc.grossProfit = acc.grossProfit.add(convert(c.grossProfit, rate, toRmb));
                acc.companyProfit = acc.companyProfit.add(convert(c.companyProfit, rate, toRmb));
                acc.commissionAmount = acc.commissionAmount.add(convert(c.commissionAmount, rate, toRmb));
                acc.internalExecutionCost = acc.internalExecutionCost
                        .add(convertFromRmb(safe(o.getInternalExecutionCost()), rate, toRmb));
            }
        }

        // 排序用：personId -> 排序权重（role=manager 用总公司利润，role=executor 用总视频数量），
        // 用 personId 而不是姓名做 key——两个人姓名可能重名，不能用姓名字符串当排序依据的 key
        List<Map.Entry<Long, BigDecimal>> sortWeights = new ArrayList<>();
        Map<Long, DashboardManagerTrendResponse.ManagerSeries> seriesByPersonId = new LinkedHashMap<>();
        for (Map.Entry<Long, Map<String, TrendAccumulator>> e : data.entrySet()) {
            Map<String, TrendAccumulator> byMonth = e.getValue();
            long totalVideoCount = 0;
            BigDecimal totalCompanyProfit = BigDecimal.ZERO;
            List<DashboardManagerTrendResponse.ManagerSeries.MonthlyMetric> monthly = new ArrayList<>();
            for (String month : months) {
                TrendAccumulator acc = byMonth.getOrDefault(month, new TrendAccumulator());
                totalVideoCount += acc.videoCount;
                totalCompanyProfit = totalCompanyProfit.add(acc.companyProfit);
                monthly.add(DashboardManagerTrendResponse.ManagerSeries.MonthlyMetric.builder()
                        .yearMonth(month)
                        .videoCount(acc.videoCount)
                        .clientPrice(acc.clientPrice.setScale(SCALE, RoundingMode.HALF_UP))
                        .grossProfit(acc.grossProfit.setScale(SCALE, RoundingMode.HALF_UP))
                        .companyProfit(acc.companyProfit.setScale(SCALE, RoundingMode.HALF_UP))
                        .commissionAmount(acc.commissionAmount.setScale(SCALE, RoundingMode.HALF_UP))
                        .internalExecutionCost(acc.internalExecutionCost.setScale(SCALE, RoundingMode.HALF_UP))
                        .build());
            }
            // 整个区间内一笔视频项目都没有的人，不用出现在趋势图里
            if (totalVideoCount == 0) continue;
            seriesByPersonId.put(e.getKey(), DashboardManagerTrendResponse.ManagerSeries.builder()
                    .managerName(nameById.get(e.getKey()))
                    .monthly(monthly)
                    .build());
            sortWeights.add(new AbstractMap.SimpleEntry<>(e.getKey(),
                    isExecutor ? BigDecimal.valueOf(totalVideoCount) : totalCompanyProfit));
        }
        // role=manager 按总公司利润降序；role=executor 按总视频数量降序
        sortWeights.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        List<DashboardManagerTrendResponse.ManagerSeries> series = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> w : sortWeights) {
            series.add(seriesByPersonId.get(w.getKey()));
        }

        return DashboardManagerTrendResponse.builder()
                .currency(toRmb ? "RMB" : "USD")
                .months(months)
                .series(series)
                .build();
    }

    /** {@link #getManagerTrend} 用：单个负责人/执行人员在单个月份内的累计值 */
    private static class TrendAccumulator {
        long videoCount = 0;
        BigDecimal clientPrice = BigDecimal.ZERO;
        BigDecimal grossProfit = BigDecimal.ZERO;
        BigDecimal companyProfit = BigDecimal.ZERO;
        BigDecimal commissionAmount = BigDecimal.ZERO;
        BigDecimal internalExecutionCost = BigDecimal.ZERO;
    }

    // ============ 通用：按品牌方/团队/账号/类型/项目负责人 拆分金额 ============

    private DashboardDrilldownResponse drilldownAmountByDimension(
            String startMonth, String endMonth, String startDate, String endDate, String currency, String dimension,
            java.util.function.Function<Computed, BigDecimal> extractor, boolean settledOnly) {

        List<CollaborationTracking> orders = excludeDamaged(fetchOrdersForPeriod(startMonth, endMonth, startDate, endDate));
        // settledOnly=true 供"客户已回款总金额"下钻用：只统计视频项目进度=客户已结算的记录，
        // 维度/口径其余部分完全跟"客户合作价格"下钻一致（复用同一份分组逻辑）
        if (settledOnly) {
            orders = orders.stream().filter(o -> o.getProgress() == CollaborationProgress.SETTLED)
                    .collect(Collectors.toList());
        }
        boolean toRmb = "RMB".equalsIgnoreCase(currency);
        Map<String, BigDecimal> monthRateCache = buildMonthRateCache(orders);

        Map<String, BigDecimal> grouped = new LinkedHashMap<>();
        Map<String, Long> counted = new LinkedHashMap<>();
        for (CollaborationTracking o : orders) {
            Computed c = compute(o);
            // 按记录自己所在月份的汇率折算后再汇总，而不是整段区间统一用一个汇率——
            // 单月查询（startMonth==endMonth）时区间内只有一个月，跟原来行为完全一致；
            // 多月查询时才会体现出差异（更准确）
            BigDecimal recordRate = monthRateOf(o, monthRateCache);

            if ("platform".equals(dimension)) {
                List<String> platforms = splitPlatforms(o.getPlatform());
                if (platforms.isEmpty()) platforms = Collections.singletonList("未指定平台");
                for (String p : platforms) {
                    grouped.merge(p, convert(extractor.apply(c), recordRate, toRmb), BigDecimal::add);
                    counted.merge(p, 1L, Long::sum);
                }
                continue;
            }

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
                case "countryMarket":
                    key = countryMarketOf(o.getCountryMarket());
                    break;
                default: // brand
                    key = brandNameOf(o.getBrandId());
            }
            grouped.merge(key, convert(extractor.apply(c), recordRate, toRmb), BigDecimal::add);
            counted.merge(key, 1L, Long::sum);
        }

        List<DashboardDrilldownResponse.DrilldownRow> rows = grouped.entrySet().stream()
                .map(e -> DashboardDrilldownResponse.DrilldownRow.builder()
                        .dimensionLabel(e.getKey())
                        .dimensionType(dimension)
                        .videoCount(counted.get(e.getKey()))
                        .amount(e.getValue().setScale(SCALE, RoundingMode.HALF_UP))
                        .build())
                .sorted((a, b) -> b.getAmount().compareTo(a.getAmount()))
                .collect(Collectors.toList());

        return DashboardDrilldownResponse.builder()
                .currency(toRmb ? "RMB" : "USD")
                .exchangeRateInfo(exchangeRateService.getRateForMonth(effectiveEndMonth(endMonth, endDate)))
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

    private String monthKeyOf(CollaborationTracking o) {
        return o.getPublishDate() != null
                ? new java.text.SimpleDateFormat("yyyyMM").format(o.getPublishDate())
                : null;
    }

    /**
     * 按月折算精度更高的下钻聚合（drilldownAmountByDimension/drilldownExecutionCost/
     * drilldownCommission/drilldownPivot）要用到每条记录自己所在月份的汇率——2026-07-30
     * 修复：一开始直接对每条记录都调一次 {@link ExchangeRateService#getRateForMonth}，
     * 该方法内部是一次未缓存的数据库查询，年度报告这种一次请求要处理成百上千条记录的场景下
     * 变成了严重的 N+1 查询（一次请求打出成百上千次汇率表查询），把 Render 免费层本来就只有
     * 3 个连接的数据库连接池打满，导致这批接口大面积超时/连接失败。改成请求级别的月份汇率
     * 缓存：一次请求最多只有区间内的月份数（比如整年最多12个）真正查库，同一批记录里同月份
     * 的后续查询全部走内存 map，不再重复打数据库。
     */
    private Map<String, BigDecimal> buildMonthRateCache(List<CollaborationTracking> orders) {
        Map<String, BigDecimal> cache = new HashMap<>();
        for (CollaborationTracking o : orders) {
            String month = monthKeyOf(o);
            if (month == null || cache.containsKey(month)) continue;
            cache.put(month, exchangeRateService.getRateForMonth(month).getUsdToCny());
        }
        return cache;
    }

    /** 某条记录自己所在月份（按发布时间）对应的汇率，从请求级别的缓存里取，不直接查库 */
    private BigDecimal monthRateOf(CollaborationTracking o, Map<String, BigDecimal> monthRateCache) {
        String month = monthKeyOf(o);
        if (month == null) return null;
        return monthRateCache.get(month);
    }

    /** 国家/市场维度展示用：没填时给个明确提示语，而不是留空 */
    private String countryMarketOf(String v) {
        return (v == null || v.trim().isEmpty()) ? "未指定服务国家/市场" : v.trim();
    }

    /**
     * 合作平台是多值字段（换行分隔），一条记录同时算进它涉及的每一个平台维度下——
     * 各平台各自计一次，加总会超过实际总记录数，这是"合作平台"维度分析的预期行为，
     * 跟单值维度（品牌方/团队/国家市场等）不一样，不能直接复用同一套单值 switch 逻辑。
     */
    private List<String> splitPlatforms(String raw) {
        if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();
        return Arrays.stream(raw.split("\\n")).map(String::trim)
                .filter(s -> !s.isEmpty()).distinct().collect(Collectors.toList());
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
