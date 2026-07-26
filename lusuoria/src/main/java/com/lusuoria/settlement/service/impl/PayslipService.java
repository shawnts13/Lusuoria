package com.lusuoria.settlement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.dto.response.DashboardSummaryResponse;
import com.lusuoria.settlement.dto.response.ExchangeRateInfo;
import com.lusuoria.settlement.dto.response.PayslipDetailResponse;
import com.lusuoria.settlement.dto.response.PayslipDimensionRow;
import com.lusuoria.settlement.dto.response.PayslipRowResponse;
import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.entity.Payslip;
import com.lusuoria.settlement.enums.VideoType;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import com.lusuoria.settlement.repository.EmployeeRepository;
import com.lusuoria.settlement.repository.PayslipRepository;
import com.lusuoria.settlement.util.EmployeeRoleUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 工资单：按员工按月组织现有薪酬计算能力（提成/阶梯Bonus/执行人员薪酬/固定月薪），
 * 加上"确认"这个动作——确认前是实时预估（跟着合作跟踪数据变化），确认后冻结成快照。
 *
 * 核心口径：
 *   - 项目负责人：提成 = 复用 DashboardStatsService.compute() 同一份公式按记录求和，
 *     不是简单的 Employee.defaultCommissionRate × 可分配利润（每条记录自己的 commissionRate/
 *     exchangeRate 可能有例外，必须逐条算）。
 *   - 执行人员：薪酬 = 名下记录 internalExecutionCost 原始值求和（人民币），不看这条记录是不是
 *     "管理层负责"——那个只影响"是否冲减公司利润"，不影响执行人员自己该拿多少钱。
 *   - 管理层：复用 DashboardStatsService.getSummary() 的项目毛利/可分配利润/负责人提成/
 *     内部执行人力成本/内部其他员工成本/公司利润，再扣掉当月所有"已确认"的其他员工的
 *     阶梯Bonus+奖金（这两项在 getSummary 里原本没有被扣减）。
 */
@Service
public class PayslipService {

    private static final int SCALE = 2;
    private static final Set<String> FIXED_SALARY_ROLES = new HashSet<>(Arrays.asList("财务", "IT后勤"));

    @Autowired private PayslipRepository payslipRepo;
    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private EmployeeCache employeeCache;
    @Autowired private CollaborationTrackingRepository trackingRepo;
    @Autowired private DashboardStatsService dashboardStatsService;
    @Autowired private CommissionBonusService commissionBonusService;
    @Autowired private ExchangeRateService exchangeRateService;
    @Autowired private EmployeeRoleUtil employeeRoleUtil;
    @Autowired private ObjectMapper objectMapper;

    // ================= 对外主入口 =================

    /** 明细弹窗 + 列表行都基于这一个方法：已确认走快照，未确认实时算，最后统一按 currency 换算 */
    @Transactional(readOnly = true)
    public PayslipDetailResponse detail(Long employeeId, String yearMonth, String currency) {
        Employee emp = employeeRepo.findByIdAndIsDeletedFalse(employeeId)
                .orElseThrow(() -> new RuntimeException("员工不存在"));
        Payslip p = payslipRepo.findByEmployeeIdAndYearMonthAndIsDeletedFalse(employeeId, yearMonth).orElse(null);
        boolean toRmb = "RMB".equalsIgnoreCase(currency);

        if (p != null && Boolean.TRUE.equals(p.getConfirmed())) {
            PayslipDetailResponse snapshot = readSnapshot(p);
            return toDisplayResponse(snapshot, p, p.getExchangeRateSnapshot(), toRmb, true, yearMonth);
        }

        PayslipDetailResponse live = computeLive(emp, yearMonth, p);
        BigDecimal rate = dashboardStatsService.rateForRange(yearMonth);
        return toDisplayResponse(live, p, rate, toRmb, false, yearMonth);
    }

    @Transactional(readOnly = true)
    public List<PayslipRowResponse> listForMonth(String yearMonth, String roleFilter, String currency) {
        List<Employee> employees = employeeRepo.findByIsDeletedFalseOrderByNameAsc().stream()
                .filter(e -> e.getResignDate() == null)
                .filter(e -> !"管理层".equals(e.getRole()))
                .filter(e -> roleFilter == null || roleFilter.trim().isEmpty() || matchesRoleFilter(e.getRole(), roleFilter))
                .collect(Collectors.toList());
        List<PayslipRowResponse> result = new ArrayList<>();
        for (Employee e : employees) {
            result.add(toRowResponse(e, yearMonth, currency));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public PayslipRowResponse managementRow(String yearMonth, String currency) {
        Employee mgmt = employeeCache.findManagementEmployee();
        if (mgmt == null) throw new RuntimeException("系统里还没有配置角色为\"管理层\"的员工");
        return toRowResponse(mgmt, yearMonth, currency);
    }

    // ================= 手动维护字段 =================

    @Transactional
    public void setExtraBonus(Long employeeId, String yearMonth, BigDecimal amount, String currency) {
        Payslip p = getOrCreateForWrite(employeeId, yearMonth);
        requireUnconfirmed(p);
        if (amount == null) {
            p.setExtraBonusAmount(null);
            p.setExtraBonusCurrency(null);
        } else {
            if (!"USD".equals(currency) && !"RMB".equals(currency)) {
                throw new RuntimeException("奖金币种必须是 USD 或 RMB");
            }
            p.setExtraBonusAmount(amount);
            p.setExtraBonusCurrency(currency);
        }
        payslipRepo.save(p);
    }

    @Transactional
    public void setLegalSalary(Long employeeId, String yearMonth, BigDecimal amountRmb) {
        Employee emp = employeeRepo.findByIdAndIsDeletedFalse(employeeId)
                .orElseThrow(() -> new RuntimeException("员工不存在"));
        if (!"法务".equals(emp.getRole())) throw new RuntimeException("只有法务角色才能设置本月工资");
        Payslip p = getOrCreateForWrite(employeeId, yearMonth);
        requireUnconfirmed(p);
        p.setLegalSalaryRmb(amountRmb);
        payslipRepo.save(p);
    }

    @Transactional
    public void confirm(Long employeeId, String yearMonth) {
        Employee emp = employeeRepo.findByIdAndIsDeletedFalse(employeeId)
                .orElseThrow(() -> new RuntimeException("员工不存在"));
        if ("管理层".equals(emp.getRole())) {
            String blocked = managementBlockReason(yearMonth, employeeId);
            if (blocked != null) throw new RuntimeException(blocked);
        }
        Payslip p = getOrCreateForWrite(employeeId, yearMonth);
        PayslipDetailResponse live = computeLive(emp, yearMonth, p);
        p.setEmployeeRole(emp.getRole());
        p.setDetailJson(writeSnapshot(live));
        p.setExchangeRateSnapshot(dashboardStatsService.rateForRange(yearMonth));
        p.setConfirmed(true);
        p.setConfirmedAt(new Date());
        p.setConfirmedByEmployeeId(employeeRoleUtil.getCurrentEmployeeId());
        payslipRepo.save(p);
    }

    @Transactional
    public void unconfirm(Long employeeId, String yearMonth) {
        Payslip p = payslipRepo.findByEmployeeIdAndYearMonthAndIsDeletedFalse(employeeId, yearMonth)
                .orElseThrow(() -> new RuntimeException("该月工资单还没有确认过，无需取消确认"));
        p.setConfirmed(false);
        payslipRepo.save(p);
    }

    // ================= 按角色计算（原始计价币种，不做汇率换算） =================

    private PayslipDetailResponse computeLive(Employee emp, String yearMonth, Payslip draft) {
        String role = emp.getRole();
        if ("项目负责人".equals(role)) return computeProjectManager(emp, yearMonth);
        if ("执行人员".equals(role)) return computeExecutor(emp, yearMonth);
        if (FIXED_SALARY_ROLES.contains(role)) return computeFixedSalary(emp);
        if ("法务".equals(role)) return computeLegal(draft);
        if ("管理层".equals(role)) return computeManagement(emp, yearMonth);
        throw new RuntimeException("该角色暂不支持工资单：" + role);
    }

    private PayslipDetailResponse computeProjectManager(Employee emp, String yearMonth) {
        List<CollaborationTracking> orders = trackingRepo.findByPublishMonth(yearMonth);
        Map<String, PayslipDimensionRow> grouped = new LinkedHashMap<>();
        BigDecimal totalCommission = BigDecimal.ZERO;

        for (CollaborationTracking o : orders) {
            if (!emp.getId().equals(o.getProjectManagerId())) continue;
            DashboardStatsService.Computed c = dashboardStatsService.compute(o);
            totalCommission = totalCommission.add(c.commissionAmount);

            String brandName = dashboardStatsService.brandNameOf(o.getBrandId());
            String teamName = dashboardStatsService.teamNameOf(o.getTeam());
            PayslipDimensionRow row = grouped.computeIfAbsent(brandName + "|" + teamName, k ->
                    PayslipDimensionRow.builder().brandName(brandName).teamName(teamName)
                            .videoCount(0L).amount(BigDecimal.ZERO).isSummaryRow(false).build());
            row.setVideoCount(row.getVideoCount() + 1);
            row.setAmount(row.getAmount().add(c.clientPrice));
        }

        List<PayslipDimensionRow> rows = new ArrayList<>(grouped.values());
        rows.sort((a, b) -> b.getVideoCount().compareTo(a.getVideoCount()));
        rows.add(buildSummaryRow(rows));

        BigDecimal tierBonus = commissionBonusService.hasBonusTierConfigured(emp)
                ? commissionBonusService.computeBonus(emp, totalCommission, dashboardStatsService.rateForRange(yearMonth))
                : null;

        return PayslipDetailResponse.builder()
                .type("PROJECT_MANAGER")
                .rows(rows)
                .commissionRate(emp.getDefaultCommissionRate())
                .baseAmount(totalCommission.setScale(SCALE, RoundingMode.HALF_UP))
                .tierBonusAmount(tierBonus)
                .build();
    }

    private PayslipDetailResponse computeExecutor(Employee emp, String yearMonth) {
        List<CollaborationTracking> orders = trackingRepo.findByPublishMonth(yearMonth);
        Map<String, PayslipDimensionRow> grouped = new LinkedHashMap<>();
        BigDecimal totalPayRmb = BigDecimal.ZERO;

        for (CollaborationTracking o : orders) {
            if (!emp.getId().equals(o.getExecutorId())) continue;
            // 执行人员该拿多少钱只看这条记录自己填的内部执行成本，不看项目负责人是不是"管理层"
            // （那个只影响这笔钱是否冲减公司利润，见 ProfitCalculator.isManagementOrder）
            BigDecimal payRmb = dashboardStatsService.safe(o.getInternalExecutionCost());
            totalPayRmb = totalPayRmb.add(payRmb);

            String brandName = dashboardStatsService.brandNameOf(o.getBrandId());
            String teamName = dashboardStatsService.teamNameOf(o.getTeam());
            VideoType vt = o.getVideoType();
            String videoTypeKey = vt != null ? vt.name() : null;
            String key = videoTypeKey + "|" + brandName + "|" + teamName;
            PayslipDimensionRow row = grouped.computeIfAbsent(key, k ->
                    PayslipDimensionRow.builder().brandName(brandName).teamName(teamName)
                            .videoType(videoTypeKey)
                            .videoTypeLabel(vt != null ? vt.getLabel() : "未填写视频类型")
                            .videoCount(0L).amount(BigDecimal.ZERO).isSummaryRow(false).build());
            row.setVideoCount(row.getVideoCount() + 1);
            row.setAmount(row.getAmount().add(payRmb));
        }

        List<PayslipDimensionRow> rows = new ArrayList<>(grouped.values());
        // 先按视频类型分组（枚举声明顺序），组内按视频数降序——体现旧素材重发的梯度价规则
        rows.sort((a, b) -> {
            int ta = videoTypeOrdinal(a.getVideoType());
            int tb = videoTypeOrdinal(b.getVideoType());
            return ta != tb ? Integer.compare(ta, tb) : b.getVideoCount().compareTo(a.getVideoCount());
        });
        rows.add(buildSummaryRow(rows));

        return PayslipDetailResponse.builder()
                .type("EXECUTOR")
                .rows(rows)
                .baseAmount(totalPayRmb.setScale(SCALE, RoundingMode.HALF_UP))
                .build();
    }

    private int videoTypeOrdinal(String name) {
        if (name == null) return Integer.MAX_VALUE;
        try {
            return VideoType.valueOf(name).ordinal();
        } catch (IllegalArgumentException e) {
            return Integer.MAX_VALUE;
        }
    }

    private PayslipDetailResponse computeFixedSalary(Employee emp) {
        return PayslipDetailResponse.builder()
                .type("FIXED_SALARY")
                .rows(new ArrayList<>())
                .baseAmount(dashboardStatsService.safe(emp.getFixedMonthlySalary()))
                .build();
    }

    /** 法务本月工资完全靠管理层手动录入，草稿态就存在 Payslip.legalSalaryRmb 上，还没录入时显示 0 */
    private PayslipDetailResponse computeLegal(Payslip draft) {
        BigDecimal salary = draft != null ? draft.getLegalSalaryRmb() : null;
        return PayslipDetailResponse.builder()
                .type("LEGAL")
                .rows(new ArrayList<>())
                .baseAmount(salary != null ? salary : BigDecimal.ZERO)
                .build();
    }

    private PayslipDetailResponse computeManagement(Employee mgmt, String yearMonth) {
        DashboardSummaryResponse summary = dashboardStatsService.getSummary(yearMonth, "USD");

        List<CollaborationTracking> orders = trackingRepo.findByPublishMonth(yearMonth);
        Map<String, PayslipDimensionRow> grouped = new LinkedHashMap<>();
        for (CollaborationTracking o : orders) {
            String brandName = dashboardStatsService.brandNameOf(o.getBrandId());
            String teamName = dashboardStatsService.teamNameOf(o.getTeam());
            PayslipDimensionRow row = grouped.computeIfAbsent(brandName + "|" + teamName, k ->
                    PayslipDimensionRow.builder().brandName(brandName).teamName(teamName)
                            .videoCount(0L).amount(BigDecimal.ZERO).amount2(BigDecimal.ZERO).isSummaryRow(false).build());
            row.setVideoCount(row.getVideoCount() + 1);
            row.setAmount(row.getAmount().add(dashboardStatsService.safe(o.getClientPrice())));
            row.setAmount2(row.getAmount2().add(dashboardStatsService.safe(o.getInfluencerCost())));
        }
        List<PayslipDimensionRow> rows = new ArrayList<>(grouped.values());
        rows.sort((a, b) -> b.getVideoCount().compareTo(a.getVideoCount()));
        rows.add(buildSummaryRow(rows));

        // 当月所有"已确认"的其他员工：阶梯Bonus + 奖金，都要从公司利润里扣掉
        // （getSummary() 本身只扣了内部执行成本/负责人提成/内部其他员工成本，没扣这两项）
        List<Payslip> othersConfirmed = payslipRepo
                .findByYearMonthAndConfirmedTrueAndIsDeletedFalseAndEmployeeIdNot(yearMonth, mgmt.getId());
        BigDecimal rate = dashboardStatsService.rateForRange(yearMonth);
        BigDecimal tierBonusTotalUsd = BigDecimal.ZERO;
        BigDecimal extraBonusTotalUsd = BigDecimal.ZERO;
        for (Payslip other : othersConfirmed) {
            PayslipDetailResponse snap = readSnapshot(other);
            if (snap.getTierBonusAmount() != null) tierBonusTotalUsd = tierBonusTotalUsd.add(snap.getTierBonusAmount());
            if (other.getExtraBonusAmount() != null) {
                boolean isRmb = "RMB".equals(other.getExtraBonusCurrency());
                BigDecimal usd = isRmb
                        ? (rate != null && rate.compareTo(BigDecimal.ZERO) > 0
                                ? other.getExtraBonusAmount().divide(rate, SCALE, RoundingMode.HALF_UP)
                                : BigDecimal.ZERO)
                        : other.getExtraBonusAmount();
                extraBonusTotalUsd = extraBonusTotalUsd.add(usd);
            }
        }

        BigDecimal managerCommissionTotal = dashboardStatsService.safe(summary.getTotalCommissionAmount()).add(tierBonusTotalUsd);
        BigDecimal companyProfit = dashboardStatsService.safe(summary.getTotalCompanyProfit())
                .subtract(tierBonusTotalUsd).subtract(extraBonusTotalUsd);

        return PayslipDetailResponse.builder()
                .type("MANAGEMENT")
                .rows(rows)
                .grossProfit(summary.getTotalGrossProfit())
                .distributableProfit(summary.getTotalDistributableProfit())
                .managerCommissionTotal(managerCommissionTotal.setScale(SCALE, RoundingMode.HALF_UP))
                .executorPayTotal(summary.getTotalInternalExecutionCost())
                .otherStaffCost(summary.getTotalOtherStaffCost())
                .companyProfit(companyProfit.setScale(SCALE, RoundingMode.HALF_UP))
                .build();
    }

    private PayslipDimensionRow buildSummaryRow(List<PayslipDimensionRow> rows) {
        long count = 0;
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal amount2 = BigDecimal.ZERO;
        for (PayslipDimensionRow r : rows) {
            count += r.getVideoCount() != null ? r.getVideoCount() : 0;
            amount = amount.add(r.getAmount() != null ? r.getAmount() : BigDecimal.ZERO);
            amount2 = amount2.add(r.getAmount2() != null ? r.getAmount2() : BigDecimal.ZERO);
        }
        return PayslipDimensionRow.builder()
                .brandName("汇总").videoCount(count)
                .amount(amount.setScale(SCALE, RoundingMode.HALF_UP))
                .amount2(amount2.setScale(SCALE, RoundingMode.HALF_UP))
                .isSummaryRow(true)
                .build();
    }

    // ================= 展示层：换算成请求币种，现算总工资 =================

    private PayslipDetailResponse toDisplayResponse(PayslipDetailResponse src, Payslip draft, BigDecimal rate,
                                                     boolean toRmb, boolean confirmed, String yearMonth) {
        String type = src.getType();
        boolean rowsAreRmb = "EXECUTOR".equals(type);
        boolean baseIsRmb = "EXECUTOR".equals(type) || "FIXED_SALARY".equals(type) || "LEGAL".equals(type);

        List<PayslipDimensionRow> convertedRows = null;
        if (src.getRows() != null) {
            convertedRows = new ArrayList<>();
            for (PayslipDimensionRow r : src.getRows()) {
                convertedRows.add(PayslipDimensionRow.builder()
                        .brandName(r.getBrandName()).teamName(r.getTeamName())
                        .videoType(r.getVideoType()).videoTypeLabel(r.getVideoTypeLabel())
                        .videoCount(r.getVideoCount())
                        .amount(convertAmount(r.getAmount(), rowsAreRmb, rate, toRmb))
                        .amount2(convertAmount(r.getAmount2(), false, rate, toRmb))
                        .isSummaryRow(r.getIsSummaryRow())
                        .build());
            }
        }

        BigDecimal baseAmount = convertAmount(src.getBaseAmount(), baseIsRmb, rate, toRmb);
        BigDecimal tierBonus = convertAmount(src.getTierBonusAmount(), false, rate, toRmb);
        BigDecimal grossProfit = convertAmount(src.getGrossProfit(), false, rate, toRmb);
        BigDecimal distributable = convertAmount(src.getDistributableProfit(), false, rate, toRmb);
        BigDecimal managerCommissionTotal = convertAmount(src.getManagerCommissionTotal(), false, rate, toRmb);
        BigDecimal executorPayTotal = convertAmount(src.getExecutorPayTotal(), false, rate, toRmb);
        BigDecimal otherStaffCost = convertAmount(src.getOtherStaffCost(), false, rate, toRmb);
        BigDecimal companyProfit = convertAmount(src.getCompanyProfit(), false, rate, toRmb);

        BigDecimal extraBonus = null;
        if (draft != null && draft.getExtraBonusAmount() != null) {
            boolean extraIsRmb = "RMB".equals(draft.getExtraBonusCurrency());
            extraBonus = convertAmount(draft.getExtraBonusAmount(), extraIsRmb, rate, toRmb);
        }

        BigDecimal total = "MANAGEMENT".equals(type) ? companyProfit : safeAdd(safeAdd(baseAmount, tierBonus), extraBonus);

        ExchangeRateInfo rateInfo = confirmed
                ? ExchangeRateInfo.builder().yearMonth(yearMonth).usdToCny(rate).isMissing(rate == null).build()
                : exchangeRateService.getRateForMonth(yearMonth);

        return PayslipDetailResponse.builder()
                .type(type).rows(convertedRows)
                .commissionRate(src.getCommissionRate())
                .baseAmount(baseAmount).tierBonusAmount(tierBonus).extraBonusAmount(extraBonus)
                .extraBonusAmountNative(draft != null ? draft.getExtraBonusAmount() : null)
                .extraBonusCurrencyNative(draft != null ? draft.getExtraBonusCurrency() : null)
                .totalAmount(total)
                .grossProfit(grossProfit).distributableProfit(distributable)
                .managerCommissionTotal(managerCommissionTotal).executorPayTotal(executorPayTotal)
                .otherStaffCost(otherStaffCost).companyProfit(companyProfit)
                .currency(toRmb ? "RMB" : "USD").confirmed(confirmed)
                .exchangeRateInfo(rateInfo)
                .build();
    }

    private BigDecimal convertAmount(BigDecimal amount, boolean isRmbNative, BigDecimal rate, boolean toRmb) {
        if (amount == null) return null;
        return isRmbNative
                ? dashboardStatsService.convertFromRmb(amount, rate, toRmb)
                : dashboardStatsService.convert(amount, rate, toRmb);
    }

    private BigDecimal safeAdd(BigDecimal a, BigDecimal b) {
        BigDecimal x = a != null ? a : BigDecimal.ZERO;
        BigDecimal y = b != null ? b : BigDecimal.ZERO;
        return x.add(y).setScale(SCALE, RoundingMode.HALF_UP);
    }

    // ================= 列表行 / 管理层确认前置校验 =================

    private boolean matchesRoleFilter(String role, String filter) {
        if ("财务和IT后勤".equals(filter)) return FIXED_SALARY_ROLES.contains(role);
        return filter.equals(role);
    }

    private PayslipRowResponse toRowResponse(Employee emp, String yearMonth, String currency) {
        PayslipDetailResponse d = detail(emp.getId(), yearMonth, currency);
        Long videoCount = null;
        if (("项目负责人".equals(emp.getRole()) || "执行人员".equals(emp.getRole()))
                && d.getRows() != null && !d.getRows().isEmpty()) {
            videoCount = d.getRows().get(d.getRows().size() - 1).getVideoCount();
        }
        Payslip p = payslipRepo.findByEmployeeIdAndYearMonthAndIsDeletedFalse(emp.getId(), yearMonth).orElse(null);
        Boolean legalSalarySet = "法务".equals(emp.getRole()) ? (p != null && p.getLegalSalaryRmb() != null) : null;
        String blockedReason = "管理层".equals(emp.getRole()) ? managementBlockReason(yearMonth, emp.getId()) : null;

        return PayslipRowResponse.builder()
                .employeeId(emp.getId()).employeeName(emp.getName()).employeeRole(emp.getRole())
                .confirmed(d.getConfirmed())
                .videoCount(videoCount)
                .baseAmount(d.getBaseAmount())
                .tierBonusAmount(d.getTierBonusAmount())
                .extraBonusAmount(d.getExtraBonusAmount())
                .extraBonusAmountNative(d.getExtraBonusAmountNative())
                .extraBonusCurrencyNative(d.getExtraBonusCurrencyNative())
                .totalAmount(d.getTotalAmount())
                .legalSalarySet(legalSalarySet)
                .blockedReason(blockedReason)
                .build();
    }

    /** 管理层确认前置校验：当月所有在职、非管理层的员工都必须已确认，否则返回拦截文案 */
    private String managementBlockReason(String yearMonth, Long managementEmployeeId) {
        List<Employee> activeOthers = employeeRepo.findByIsDeletedFalseOrderByNameAsc().stream()
                .filter(e -> e.getResignDate() == null)
                .filter(e -> !e.getId().equals(managementEmployeeId))
                .filter(e -> !"管理层".equals(e.getRole()))
                .collect(Collectors.toList());
        for (Employee e : activeOthers) {
            Payslip p = payslipRepo.findByEmployeeIdAndYearMonthAndIsDeletedFalse(e.getId(), yearMonth).orElse(null);
            if (p == null || !Boolean.TRUE.equals(p.getConfirmed())) {
                return "请先确认其他员工的工资单后再确认管理层工资单";
            }
        }
        return null;
    }

    // ================= 草稿行 / 快照序列化 =================

    private void requireUnconfirmed(Payslip p) {
        if (Boolean.TRUE.equals(p.getConfirmed())) {
            throw new RuntimeException("请先取消确认后再修改");
        }
    }

    private Payslip getOrCreateForWrite(Long employeeId, String yearMonth) {
        return payslipRepo.findByEmployeeIdAndYearMonthAndIsDeletedFalse(employeeId, yearMonth)
                .orElseGet(() -> payslipRepo.save(Payslip.builder()
                        .employeeId(employeeId).yearMonth(yearMonth).confirmed(false).build()));
    }

    private String writeSnapshot(PayslipDetailResponse detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            throw new RuntimeException("工资单快照序列化失败", e);
        }
    }

    private PayslipDetailResponse readSnapshot(Payslip p) {
        try {
            return objectMapper.readValue(p.getDetailJson(), PayslipDetailResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("工资单快照反序列化失败", e);
        }
    }
}
