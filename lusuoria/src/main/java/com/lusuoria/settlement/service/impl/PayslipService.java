package com.lusuoria.settlement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.dto.response.ExchangeRateInfo;
import com.lusuoria.settlement.dto.response.PayslipDetailResponse;
import com.lusuoria.settlement.dto.response.PayslipDimensionRow;
import com.lusuoria.settlement.dto.response.PayslipRowResponse;
import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.entity.CommissionBonusTier;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.entity.ExecutorPayRateTier;
import com.lusuoria.settlement.entity.ExecutorWageConfirmation;
import com.lusuoria.settlement.entity.Payslip;
import com.lusuoria.settlement.enums.CollaborationProgress;
import com.lusuoria.settlement.enums.VideoType;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import com.lusuoria.settlement.repository.EmployeeRepository;
import com.lusuoria.settlement.repository.ExecutorPayRateTierRepository;
import com.lusuoria.settlement.repository.ExecutorWageConfirmationRepository;
import com.lusuoria.settlement.repository.PayslipRepository;
import com.lusuoria.settlement.util.EmployeeRoleUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
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
 *   - 管理层：项目毛利/可分配利润/负责人提成/内部其他员工成本/公司利润是公司整体口径；
 *     "内部执行人力成本"只算管理层自己名下（projectManagerId=管理层自己）的执行人员工资，
 *     其他项目负责人名下的执行人员是那位项目负责人自己发工资，不计入这一项。
 *     再扣掉当月所有"已确认"的其他员工的阶梯Bonus+奖金（这两项本身不在上述公式里）。
 *
 * 性能：
 *   1. 管理层视角的"工资单列表"整月合作跟踪记录只查一次，在内存里按项目负责人/执行人员
 *      分组一次算完所有人（见 {@link #batchComputeCommissionRoles}），不再按员工循环查。
 *   2. 月度汇率（ExchangeRateCache）在一次请求里只查一次——员工数量再多，也只有一次
 *      exchangeRateService.getRateForMonth() 调用，通过参数把 rate/liveRateInfo 一路传下去，
 *      不在 resolveDisplay/toDisplayResponse/buildProjectManagerDetail 等每员工都会调用一次的
 *      方法里各自再查一遍（那样是另一种隐蔽的 N+1，员工一多同样会明显变慢）。
 */
@Service
public class PayslipService {

    private static final Logger log = LoggerFactory.getLogger(PayslipService.class);
    private static final int SCALE = 2;
    private static final Set<String> FIXED_SALARY_ROLES = new HashSet<>(Arrays.asList("财务", "IT后勤"));
    /** 工资单列表默认展示顺序（2026-07 新增）：按角色分组展示，不然混排看着乱；角色内部再按姓名排序 */
    private static final List<String> ROLE_DISPLAY_ORDER =
            Arrays.asList("项目负责人", "执行人员", "财务", "IT后勤", "法务");

    @Autowired private PayslipRepository payslipRepo;
    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private EmployeeCache employeeCache;
    @Autowired private CollaborationTrackingRepository trackingRepo;
    @Autowired private DashboardStatsService dashboardStatsService;
    @Autowired private CommissionBonusService commissionBonusService;
    @Autowired private ExchangeRateService exchangeRateService;
    @Autowired private EmployeeRoleUtil employeeRoleUtil;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ExecutorWageConfirmationRepository wageConfirmationRepo;
    @Autowired private ExecutorPayRateTierRepository executorPayRateTierRepo;

    // ================= 对外主入口 =================

    /**
     * 明细弹窗 + "我的工资单"：单个员工，允许各自查一次（不是列表场景，没有 N+1 问题）。
     * 非 readOnly：财务/IT后勤当月第一次被查看时可能顺带自动确认（见 {@link #autoConfirmFixedSalaryIfMissing}）。
     */
    @Transactional
    public PayslipDetailResponse detail(Long employeeId, String yearMonth, String currency) {
        Employee emp = employeeRepo.findByIdAndIsDeletedFalse(employeeId)
                .orElseThrow(() -> new RuntimeException("员工不存在"));
        ExchangeRateInfo liveRateInfo = exchangeRateService.getRateForMonth(yearMonth);
        Payslip p = payslipRepo.findByEmployeeIdAndYearMonthAndIsDeletedFalse(employeeId, yearMonth).orElse(null);
        p = autoConfirmFixedSalaryIfMissing(emp, yearMonth, p, liveRateInfo.getUsdToCny());
        return resolveDisplay(emp, yearMonth, currency, null, p, liveRateInfo);
    }

    /**
     * 管理层视角的员工列表（不含管理层自己，见 {@link #managementRow}）。
     * 整月合作跟踪记录、整月工资单确认状态、月度汇率各只查一次，员工数量再多也不会变成
     * N 次查询——2026-08-10 修复：这句话之前不成立，"执行人员"这个角色每一行都会在
     * toRowResponse() 里各自调一次 resolveExecutorPmConfirmStatus()（算"确认"按钮要不要
     * 禁用），那个方法内部独立查询本月合作记录/管理层员工id/执行人员工资确认状态三样东西，
     * 完全没有复用 batchComputeCommissionRoles() 已经查好的同一批数据，是一处隐蔽的 N+1
     * （随执行人员人数线性增长，不是"这个月有没有数据"决定的，Shawn 反馈"切到工资单要转好久，
     * 哪怕这个月没什么数据"就是这个问题——慢的是"公司总共有几个执行人员"，不是"这个月忙不忙"）。
     * 现在改成跟 commissionRoleEmployees 共用同一份 orders/confirmationByManagerId，本月所有
     * 执行人员的 blockedReason 提前批量算好存进 execStatusById，再传给 toRowResponse()，
     * 不在循环里现查。
     */
    @Transactional
    public List<PayslipRowResponse> listForMonth(String yearMonth, String roleFilter, String currency) {
        ExchangeRateInfo liveRateInfo = exchangeRateService.getRateForMonth(yearMonth);
        BigDecimal rate = liveRateInfo.getUsdToCny();

        List<Employee> employees = employeeRepo.findByIsDeletedFalseOrderByNameAsc().stream()
                .filter(e -> e.getResignDate() == null)
                .filter(e -> !"管理层".equals(e.getRole()))
                .filter(e -> !isBeforeHireMonth(e, yearMonth))
                .filter(e -> roleFilter == null || roleFilter.trim().isEmpty() || matchesRoleFilter(e.getRole(), roleFilter))
                .sorted(Comparator.comparingInt((Employee e) -> {
                    int idx = ROLE_DISPLAY_ORDER.indexOf(e.getRole());
                    return idx < 0 ? ROLE_DISPLAY_ORDER.size() : idx;
                }).thenComparing(Employee::getName))
                .collect(Collectors.toList());

        List<Employee> commissionRoleEmployees = employees.stream()
                .filter(e -> "项目负责人".equals(e.getRole()) || "执行人员".equals(e.getRole()))
                .collect(Collectors.toList());
        List<CollaborationTracking> orders = excludeDamaged(trackingRepo.findByPublishMonth(yearMonth));
        Map<Long, Map<Long, ExecutorWageConfirmation>> confirmationByManagerId = fetchWageConfirmations(yearMonth);
        Map<Long, PayslipDetailResponse> liveMap =
                batchComputeCommissionRoles(commissionRoleEmployees, rate, orders, confirmationByManagerId);

        // 本月所有执行人员的"确认"按钮拦截原因批量算好（见上面方法注释），不在下面的行循环里
        // 逐个现查——跟 batchComputeCommissionRoles 共用同一份 orders/confirmationByManagerId，
        // 管理层员工id列表也只单独查这一次
        Set<Long> managementIds = employeeRepo.findByRoleAndIsDeletedFalse("管理层").stream()
                .map(Employee::getId).collect(Collectors.toSet());
        Map<Long, ExecutorPmConfirmStatus> execStatusById = new HashMap<>();
        for (Employee e : commissionRoleEmployees) {
            if ("执行人员".equals(e.getRole())) {
                execStatusById.put(e.getId(),
                        resolveExecutorPmConfirmStatus(e.getId(), orders, managementIds, confirmationByManagerId));
            }
        }

        Map<Long, Payslip> payslipByEmployeeId = payslipRepo.findByYearMonthAndIsDeletedFalse(yearMonth).stream()
                .collect(Collectors.toMap(Payslip::getEmployeeId, p -> p, (a, b) -> a));
        // 财务/IT后勤：当月还没有工资单记录的，默认直接确认（固定月薪，奖金设置不常发生，
        // 不需要每月都手动点一次"确认"，要改的话照旧先"取消确认"）
        for (Employee e : employees) {
            if (FIXED_SALARY_ROLES.contains(e.getRole()) && !payslipByEmployeeId.containsKey(e.getId())) {
                payslipByEmployeeId.put(e.getId(), autoConfirmFixedSalaryIfMissing(e, yearMonth, null, rate));
            }
        }

        List<PayslipRowResponse> result = new ArrayList<>();
        for (Employee e : employees) {
            result.add(toRowResponse(e, yearMonth, currency, liveMap.get(e.getId()), payslipByEmployeeId.get(e.getId()),
                    liveRateInfo, execStatusById.get(e.getId())));
        }
        return result;
    }

    @Transactional
    public PayslipRowResponse managementRow(String yearMonth, String currency) {
        Employee mgmt = employeeCache.findManagementEmployee();
        if (mgmt == null) throw new RuntimeException("系统里还没有配置角色为\"管理层\"的员工");
        ExchangeRateInfo liveRateInfo = exchangeRateService.getRateForMonth(yearMonth);
        Payslip p = payslipRepo.findByEmployeeIdAndYearMonthAndIsDeletedFalse(mgmt.getId(), yearMonth).orElse(null);
        return toRowResponse(mgmt, yearMonth, currency, null, p, liveRateInfo);
    }

    // ================= 手动维护字段 =================

    @Transactional
    public void setExtraBonus(Long employeeId, String yearMonth, BigDecimal amount, String currency) {
        Employee emp = employeeRepo.findByIdAndIsDeletedFalse(employeeId)
                .orElseThrow(() -> new RuntimeException("员工不存在"));
        Payslip p = payslipRepo.findByEmployeeIdAndYearMonthAndIsDeletedFalse(employeeId, yearMonth).orElse(null);
        boolean isNew = p == null;
        if (isNew) {
            p = Payslip.builder().employeeId(employeeId).yearMonth(yearMonth).confirmed(false).finalConfirmed(false).build();
        } else {
            // 执行人员现在也有整体确认状态了（2026-07 新增管理层最终确认），奖金冻结规则
            // 统一跟其他角色一致：确认后要改奖金必须先取消确认
            requireUnconfirmed(p);
        }
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
        // 财务/IT后勤：这是当月第一次涉及这条工资单记录（比如管理层还没看过这个月就直接先设了
        // 奖金），默认直接确认，不需要另外再点一次"确认"
        if (isNew && FIXED_SALARY_ROLES.contains(emp.getRole())) {
            BigDecimal rate = exchangeRateService.getRateForMonth(yearMonth).getUsdToCny();
            applyConfirmedSnapshot(emp, yearMonth, p, null, rate);
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

    /**
     * 执行人员工资单状态标签 + 主表格"确认"按钮的最终口径（2026-07-28 明确澄清，同日四次
     * 修正后定稿——上一版"管理层随时可确认、按钮永不禁用"是错的，这次改回来）：
     *
     * 管理层对每一个执行人员都"涉及确认"这件事——不管这个执行人员这个月有没有归在管理层
     * 名下的合作跟踪记录，管理层最终都要确认（哪怕只是奖金）。但**如果管理层这个月确实是这个
     * 执行人员的相关项目负责人之一（欠这个执行人员一份薪酬）**，就必须先在"管理层手下执行
     * 人员工资"把这份薪酬确认过，主表格这一行的"确认"按钮才能点——这个前置顺序不能跳过。
     * 只有当管理层这个月压根不是这个执行人员的相关项目负责人（没有薪酬要发，只有奖金）时，
     * 按钮才不受任何限制、随时可点。
     *
     * 状态标签是另一件事，跟按钮能不能点分开算：
     * - "预计"（橙色）：管理层自己还没确认（如果管理层是相关项目负责人，指还没在"管理层
     *   手下执行人员工资"确认；如果不是，则是"这个月涉及的项目负责人还没都确认"）。
     * - "待其他项目负责人确认"（黄色）：管理层自己那部分已经确认了，但还有其他项目负责人
     *   没确认。
     * - "已确认"（绿色）：管理层自己那部分确认了，且所有其他项目负责人也都确认了。
     */
    private ExecutorPmConfirmStatus resolveExecutorPmConfirmStatus(Long executorId, String yearMonth) {
        return resolveExecutorPmConfirmStatus(executorId,
                excludeDamaged(trackingRepo.findByPublishMonth(yearMonth)),
                employeeRepo.findByRoleAndIsDeletedFalse("管理层").stream()
                        .map(Employee::getId).collect(Collectors.toSet()),
                fetchWageConfirmations(yearMonth));
    }

    /**
     * 2026-08-10 新增（性能优化）：调用方已经查好本月合作记录/管理层员工id/执行人员工资确认
     * 状态时用这个重载，跳过重复查询——listForMonth() 批量算全部执行人员行的 blockedReason
     * 时用这个，避免"每个执行人员各查一次"的 N+1（见 listForMonth() 方法注释）。逻辑跟上面
     * 无 cache 的版本完全一样。
     */
    private ExecutorPmConfirmStatus resolveExecutorPmConfirmStatus(Long executorId, List<CollaborationTracking> orders,
                                                                     Set<Long> managementIds,
                                                                     Map<Long, Map<Long, ExecutorWageConfirmation>> confirmations) {
        Set<Long> pmIds = new LinkedHashSet<>();
        for (CollaborationTracking o : orders) {
            if (executorId.equals(o.getExecutorId()) && o.getProjectManagerId() != null) {
                pmIds.add(o.getProjectManagerId());
            }
        }
        if (pmIds.isEmpty()) return new ExecutorPmConfirmStatus(true, false, null, false);
        boolean allConfirmed = true;
        boolean anyConfirmed = false;
        boolean managementIsParty = false;
        boolean selfConfirmed = true;
        for (Long pmId : pmIds) {
            ExecutorWageConfirmation c = confirmations.getOrDefault(pmId, Collections.emptyMap()).get(executorId);
            boolean confirmed = c != null && Boolean.TRUE.equals(c.getConfirmed());
            if (!confirmed) allConfirmed = false;
            if (confirmed) anyConfirmed = true;
            if (managementIds.contains(pmId)) {
                managementIsParty = true;
                if (!confirmed) selfConfirmed = false;
            }
        }
        if (!managementIsParty) selfConfirmed = false; // 没有"自己那份"，不算"自己已确认"
        String blockedReason = (managementIsParty && !selfConfirmed)
                ? "管理层还没有在\"管理层手下执行人员工资\"确认这个执行人员的薪酬，无法进行最终确认"
                : null;
        return new ExecutorPmConfirmStatus(allConfirmed, selfConfirmed, blockedReason, anyConfirmed);
    }

    @lombok.Value
    private static class ExecutorPmConfirmStatus {
        /** 是否这个月涉及的所有项目负责人都确认过了——驱动状态标签，跟 blockedReason 无关 */
        boolean allConfirmed;
        /** 管理层自己那部分（若管理层是相关项目负责人之一）是否已确认——驱动状态标签的中间态 */
        boolean selfConfirmed;
        /** 非空时表示主表格"确认"按钮应被禁用（仅当管理层自己有未确认的那部分时才非空） */
        String blockedReason;
        /** 这个月涉及的项目负责人里，是否至少有一个已经确认了这个执行人员的薪酬（2026-07-29
         * 新增）——recomputeFinality 回退 finalConfirmed 时用来判断要不要连带把执行人员自己的
         * confirmed 也退回：只有当"一个都没确认"（回到起点）时才连带回退，只要还有任意一个
         * 项目负责人的确认还在，就不该假装管理层完全没确认过，应该展示"待其他项目负责人确认"。 */
        boolean anyConfirmed;
    }

    /**
     * 2026-08 新增（性能优化用）：涉及"管理层"这个角色的写操作请求内部共享的月度数据缓存——
     * 本月合作记录/执行人员工资确认状态/全体在职员工列表这三份数据，原来会被
     * managementBlockReason()、recomputeFinality()→allOwnExecutorWagesConfirmed()、
     * applyFinalSnapshot()→computeManagement() 各自独立查询一遍，同一份数据在一次点击里被
     * 重复查了两次，是管理层相关操作明显比其他角色慢的主要原因（其他角色不需要汇总全公司
     * 数据，没有这层重复）。这个缓存只在触发到管理层的调用链内部构造、传递，不是跨请求共享
     * 的状态——PayslipService 是单例 bean，不能用实例字段做请求级缓存，必须像这样显式当参数
     * 传递。目前两条调用链会用到：(a) confirm()——管理层点自己那份工资单的"确认"；
     * (b) confirmExecutorWages()/unconfirmExecutorWages()——manager 恰好是管理层时（"确认
     * 执行人员薪酬"这个按钮，管理层作为项目负责人确认/取消确认自己名下某个执行人员的工资），
     * 2026-08-10 补的，之前只顾上了 (a)，Shawn 反馈这个按钮也一样慢才发现漏了这一条。
     * manager 不是管理层的场景（最常见——普通项目负责人确认执行人员工资）不建这份缓存，因为
     * recomputeFinality 的"项目负责人"分支不消费 cache，白建只会平白多花3次查询。
     */
    private static final class MonthDataCache {
        final List<CollaborationTracking> orders;
        final Map<Long, Map<Long, ExecutorWageConfirmation>> wageConfirmations;
        final List<Employee> allEmployees;
        MonthDataCache(List<CollaborationTracking> orders,
                       Map<Long, Map<Long, ExecutorWageConfirmation>> wageConfirmations,
                       List<Employee> allEmployees) {
            this.orders = orders;
            this.wageConfirmations = wageConfirmations;
            this.allEmployees = allEmployees;
        }
    }

    @Transactional
    public void confirm(Long employeeId, String yearMonth) {
        Employee emp = employeeRepo.findByIdAndIsDeletedFalse(employeeId)
                .orElseThrow(() -> new RuntimeException("员工不存在"));
        BigDecimal rate = exchangeRateService.getRateForMonth(yearMonth).getUsdToCny();
        MonthDataCache cache = null;
        if ("管理层".equals(emp.getRole())) {
            // 见 MonthDataCache 类注释：这三份数据下面 managementBlockReason()/recomputeFinality()/
            // computeManagement() 都要用到，这里只查一次、全程复用
            cache = new MonthDataCache(
                    excludeDamaged(trackingRepo.findByPublishMonth(yearMonth)),
                    fetchWageConfirmations(yearMonth),
                    employeeRepo.findByIsDeletedFalseOrderByNameAsc());
            String blocked = managementBlockReason(yearMonth, employeeId, rate, cache.allEmployees);
            if (blocked != null) throw new RuntimeException(blocked);
        }
        if ("执行人员".equals(emp.getRole())) {
            ExecutorPmConfirmStatus status = resolveExecutorPmConfirmStatus(employeeId, yearMonth);
            if (status.getBlockedReason() != null) throw new RuntimeException(status.getBlockedReason());
        }
        Payslip p = getOrCreateForWrite(employeeId, yearMonth);
        // 项目负责人/执行人员/管理层：这里只是"管理层自己那部分"确认，是否到达最终版（连带
        // 冻结快照）交给 recomputeFinality 另外判定，不在这里无条件冻结——否则相关的执行人员
        // 工资确认还没全部到位时就提前把数据锁死了。管理层本人也可能是某些执行人员的项目
        // 负责人（"管理层手下执行人员工资"卡片），2026-08 起纳入同一套判定，不再无条件冻结
        // （之前的问题：管理层确认整体工资单后，即使后续在"手下执行人员工资"取消确认了某个
        // 执行人员，管理层自己这一行也不会退回非最终版，导致 /detail 一直吐旧快照）。
        // 其余角色（财务/IT后勤/法务）没有这层下游依赖，点确认即最终版，照旧走
        // applyConfirmedSnapshot 立即冻结。
        if ("项目负责人".equals(emp.getRole()) || "执行人员".equals(emp.getRole()) || "管理层".equals(emp.getRole())) {
            p.setEmployeeRole(emp.getRole());
            p.setConfirmed(true);
            p.setConfirmedAt(new Date());
            p.setConfirmedByEmployeeId(employeeRoleUtil.getCurrentEmployeeId());
            payslipRepo.save(p);
            recomputeFinality(emp, yearMonth, rate, cache);
        } else {
            applyConfirmedSnapshot(emp, yearMonth, p, employeeRoleUtil.getCurrentEmployeeId(), rate);
            payslipRepo.save(p);
        }
    }

    @Transactional
    public void unconfirm(Long employeeId, String yearMonth) {
        Payslip p = payslipRepo.findByEmployeeIdAndYearMonthAndIsDeletedFalse(employeeId, yearMonth)
                .orElseThrow(() -> new RuntimeException("该月工资单还没有确认过，无需取消确认"));
        p.setConfirmed(false);
        // 取消确认连带清掉最终版标记（跟 detailJson 一样"只翻标志不清空"的约定不适用于这个
        // 布尔字段本身——它就是"是否最终版"这件事的当前状态，下次重新确认会重新判定）
        p.setFinalConfirmed(false);
        payslipRepo.save(p);
    }

    /**
     * 项目负责人自己确认"应发给名下某一个执行人员的工资"——这一层的确认状态
     * （{@link ExecutorWageConfirmation}）跟管理层对这个项目负责人自己那份工资单
     * （{@link Payslip}）的确认/取消确认完全独立，互不阻塞、互不影响。2026-07 起改成按
     * 执行人员单独确认（不再是这个项目负责人当月名下所有执行人员一次性打包确认），
     * get-or-create 一行 (managerId, executorId, yearMonth) 记录，写入当前实时数据的
     * 快照后置 confirmed=true。这个动作同时影响这个执行人员、这个项目负责人两个人各自的
     * "是否最终版"判定（见 recomputeFinality），所以最后要对两边都重新判定一次。
     */
    @Transactional
    public void confirmExecutorWages(Long managerId, Long executorId, String yearMonth) {
        Employee manager = employeeRepo.findByIdAndIsDeletedFalse(managerId)
                .orElseThrow(() -> new RuntimeException("员工不存在"));
        Employee executor = employeeRepo.findByIdAndIsDeletedFalse(executorId)
                .orElseThrow(() -> new RuntimeException("执行人员不存在"));
        List<CollaborationTracking> orders = excludeDamaged(trackingRepo.findByPublishMonth(yearMonth));
        List<CollaborationTracking> ordersForPair = groupByManagerThenExecutor(orders)
                .getOrDefault(managerId, Collections.emptyMap())
                .getOrDefault(executorId, Collections.emptyList());
        List<PayslipDimensionRow> rows = buildDimensionRowsForOrders(ordersForPair);
        Map<String, List<ExecutorPayRateTier>> tiersByType =
                fetchTiersByExecutorAndType(managerId).getOrDefault(executorId, Collections.emptyMap());
        sortExecutorRowsByTier(tiersByType, rows);
        for (PayslipDimensionRow r : rows) {
            r.setExecutorId(executorId);
            r.setExecutorName(employeeNameOf(executorId));
        }
        rows = withTierSummaries(tiersByType, rows);

        ExecutorWageConfirmation confirmation = wageConfirmationRepo
                .findByManagerIdAndExecutorIdAndYearMonthAndIsDeletedFalse(managerId, executorId, yearMonth)
                .orElseGet(() -> ExecutorWageConfirmation.builder()
                        .managerId(managerId).executorId(executorId).yearMonth(yearMonth).confirmed(false).build());
        confirmation.setDetailJson(writeExecutorWageSnapshot(rows));
        confirmation.setConfirmed(true);
        confirmation.setConfirmedAt(new Date());
        wageConfirmationRepo.save(confirmation);

        BigDecimal rate = exchangeRateService.getRateForMonth(yearMonth).getUsdToCny();
        // 2026-08-10 修复：这个按钮（"确认执行人员薪酬"）在 manager 是管理层的场景下，会
        // 触发下面 recomputeFinality(manager,...) 走进跟 confirm()（管理层点自己那份工资单
        // 的"确认"）同样昂贵的 computeManagement() 路径——之前这里没有像 confirm() 那样传
        // MonthDataCache，本月合作记录（其实上面已经查过存在 orders 里了）/执行人员工资确认
        // 状态/全体员工列表在一次点击里被重复查询，是这个按钮同样"要等一小会"的原因。这里
        // 复用同一套缓存机制，只在 manager 真的是管理层时才现建一份传下去——manager 是普通
        // 项目负责人时 recomputeFinality 的那个分支不消费 cache，白建一份没有意义，只会给
        // 最常见的"普通项目负责人确认执行人员工资"场景平白多花3次查询，所以按角色判断，不是
        // 无条件都建。
        MonthDataCache cache = "管理层".equals(manager.getRole())
                ? new MonthDataCache(orders, fetchWageConfirmations(yearMonth), employeeRepo.findByIsDeletedFalseOrderByNameAsc())
                : null;
        recomputeFinality(executor, yearMonth, rate, cache);
        recomputeFinality(manager, yearMonth, rate, cache);
    }

    /**
     * 取消确认只翻 confirmed 标志，detailJson 保留不清空——下次重新确认会覆盖，跟 Payslip 同一套
     * 约定。同样要对这个执行人员、这个项目负责人两边重新判定"是否最终版"（多半会从最终版退回去）。
     */
    @Transactional
    public void unconfirmExecutorWages(Long managerId, Long executorId, String yearMonth) {
        ExecutorWageConfirmation confirmation = wageConfirmationRepo
                .findByManagerIdAndExecutorIdAndYearMonthAndIsDeletedFalse(managerId, executorId, yearMonth)
                .orElseThrow(() -> new RuntimeException("该月这个执行人员的工资还没有确认过，无需取消确认"));
        confirmation.setConfirmed(false);
        wageConfirmationRepo.save(confirmation);

        Employee manager = employeeRepo.findByIdAndIsDeletedFalse(managerId).orElse(null);
        Employee executor = employeeRepo.findByIdAndIsDeletedFalse(executorId).orElse(null);
        BigDecimal rate = exchangeRateService.getRateForMonth(yearMonth).getUsdToCny();
        // 同 confirmExecutorWages() 的性能修复（2026-08-10）：manager 是管理层时才建缓存，
        // 避免最常见的"普通项目负责人取消确认执行人员工资"场景平白多花查询
        MonthDataCache cache = (manager != null && "管理层".equals(manager.getRole()))
                ? new MonthDataCache(excludeDamaged(trackingRepo.findByPublishMonth(yearMonth)),
                        fetchWageConfirmations(yearMonth), employeeRepo.findByIsDeletedFalseOrderByNameAsc())
                : null;
        if (executor != null) recomputeFinality(executor, yearMonth, rate, cache);
        if (manager != null) recomputeFinality(manager, yearMonth, rate, cache);
    }

    private String writeExecutorWageSnapshot(List<PayslipDimensionRow> rows) {
        try {
            return objectMapper.writeValueAsString(rows);
        } catch (Exception e) {
            throw new RuntimeException("执行人员工资快照序列化失败", e);
        }
    }

    // ================= 按角色计算：单个员工（明细弹窗/我的工资单/确认时用） =================

    private PayslipDetailResponse computeLive(Employee emp, String yearMonth, Payslip draft, BigDecimal rate) {
        String role = emp.getRole();
        if ("项目负责人".equals(role)) return computeProjectManager(emp, yearMonth, rate);
        if ("执行人员".equals(role)) return computeExecutor(emp, yearMonth);
        if (FIXED_SALARY_ROLES.contains(role)) return computeFixedSalary(emp);
        if ("法务".equals(role)) return computeLegal(draft);
        if ("管理层".equals(role)) return computeManagement(emp, yearMonth, rate);
        throw new RuntimeException("该角色暂不支持工资单：" + role);
    }

    private PayslipDetailResponse computeProjectManager(Employee emp, String yearMonth, BigDecimal rate) {
        List<CollaborationTracking> orders = excludeDamaged(trackingRepo.findByPublishMonth(yearMonth));
        Map<Long, Map<Long, List<CollaborationTracking>>> byPmThenExec = groupByManagerThenExecutor(orders);
        Map<Long, Map<Long, ExecutorWageConfirmation>> confirmationByManagerId = fetchWageConfirmations(yearMonth);
        Map<String, PayslipDimensionRow> grouped = new LinkedHashMap<>();
        BigDecimal totalCommission = BigDecimal.ZERO;
        for (CollaborationTracking o : orders) {
            if (!emp.getId().equals(o.getProjectManagerId())) continue;
            DashboardStatsService.Computed c = dashboardStatsService.compute(o);
            totalCommission = totalCommission.add(c.commissionAmount);
            accumulatePmRow(grouped, o, c);
        }
        List<CommissionBonusTier> tiers = commissionBonusService
                .findTiersByEmployeeIds(Collections.singletonList(emp.getId()))
                .getOrDefault(emp.getId(), Collections.emptyList());
        return buildProjectManagerDetail(emp, new ArrayList<>(grouped.values()), totalCommission, rate, tiers,
                byPmThenExec, confirmationByManagerId);
    }

    private PayslipDetailResponse computeExecutor(Employee emp, String yearMonth) {
        List<CollaborationTracking> orders = excludeDamaged(trackingRepo.findByPublishMonth(yearMonth));
        Map<Long, Map<Long, List<CollaborationTracking>>> byPmThenExec = groupByManagerThenExecutor(orders);
        Map<Long, Map<Long, ExecutorWageConfirmation>> confirmationByManagerId = fetchWageConfirmations(yearMonth);
        return buildExecutorCrossManagerDetail(emp.getId(), byPmThenExec, confirmationByManagerId);
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

    private PayslipDetailResponse computeManagement(Employee mgmt, String yearMonth, BigDecimal rate) {
        return computeManagement(mgmt, yearMonth, rate,
                excludeDamaged(trackingRepo.findByPublishMonth(yearMonth)),
                fetchWageConfirmations(yearMonth),
                employeeRepo.findByIsDeletedFalseOrderByNameAsc());
    }

    /**
     * 2026-08 新增（性能优化，见 MonthDataCache 类注释）：管理层确认这条调用链传入已经查好的
     * 本月合作记录/执行人员工资确认状态/全体员工列表，跳过下面原本各自独立的三次查询。
     * 计算逻辑本身跟原来完全一样，只是数据来源从"方法内部现查"改成"调用方传入"。
     */
    private PayslipDetailResponse computeManagement(Employee mgmt, String yearMonth, BigDecimal rate,
                                                      MonthDataCache cache) {
        return computeManagement(mgmt, yearMonth, rate, cache.orders, cache.wageConfirmations, cache.allEmployees);
    }

    private PayslipDetailResponse computeManagement(Employee mgmt, String yearMonth, BigDecimal rate,
                                                      List<CollaborationTracking> orders,
                                                      Map<Long, Map<Long, ExecutorWageConfirmation>> confirmationByManagerIdParam,
                                                      List<Employee> allEmployees) {
        Map<String, PayslipDimensionRow> grouped = new LinkedHashMap<>();
        BigDecimal totalGrossProfit = BigDecimal.ZERO;
        BigDecimal totalDistributable = BigDecimal.ZERO;
        BigDecimal totalCommission = BigDecimal.ZERO;
        BigDecimal totalExecCostUsd = BigDecimal.ZERO;
        BigDecimal totalCompanyProfitUsd = BigDecimal.ZERO;
        // "负责人提成合计（含Bonus）"按项目负责人拆分用（2026-08-10 新增，见下面
        // commissionBreakdownRows 的组装）：管理层自己的订单不算在内——那部分订单产生的是
        // "内部执行人力成本"（体现在 execOrdersUnderMgmt/executorWageRows 那边），不是"负责人
        // 提成"，管理层本人也不参与提成阶梯Bonus（跟 DashboardStatsService.drilldownCommission()
        // "管理层这个特殊项目负责人整行剔除"是同一个口径）。
        Map<Long, BigDecimal> commissionByManager = new LinkedHashMap<>();

        for (CollaborationTracking o : orders) {
            DashboardStatsService.Computed c = dashboardStatsService.compute(o);
            totalGrossProfit = totalGrossProfit.add(c.grossProfit);
            totalDistributable = totalDistributable.add(c.distributableProfit);
            totalCommission = totalCommission.add(c.commissionAmount);
            // "内部执行人力成本"（显示用）= grossProfit − distributableProfit：这正是 compute()
            // 内部算 distributable 时实际扣掉的那笔执行成本（美金，已经按这条记录自己的汇率换算、
            // 已经只在 isManagementOrder 即项目负责人=管理层本人时非零，其余记录这里自然是0）。
            // 不要拿人民币原始值另外求和、最后用当月统一汇率再转换一次——那样是两条独立的换算/
            // 四舍五入路径，各记录汇率不完全一致、或者"先转换再入账"和"先入账再转换"的舍入顺序
            // 不同，加总后可能跟"可分配利润"里实际扣掉的数字对不上几分钱，导致管理层这里展示的
            // 公式手动算出来的公司利润跟系统给的结果有小数点差异。这样写保证跟真正的利润计算链
            // 完全是同一套数字，用户拿页面上任意几个数字手算公式一定能对上。
            totalExecCostUsd = totalExecCostUsd.add(c.grossProfit.subtract(c.distributableProfit));
            totalCompanyProfitUsd = totalCompanyProfitUsd.add(c.companyProfit);

            if (o.getProjectManagerId() != null && !o.getProjectManagerId().equals(mgmt.getId())) {
                commissionByManager.merge(o.getProjectManagerId(), c.commissionAmount, BigDecimal::add);
            }

            String brandName = dashboardStatsService.brandNameOf(o.getBrandId());
            String teamName = dashboardStatsService.teamNameOf(o.getTeam());
            PayslipDimensionRow row = grouped.computeIfAbsent(brandName + "|" + teamName, k ->
                    PayslipDimensionRow.builder().brandName(brandName).teamName(teamName)
                            .videoCount(0L).amount(BigDecimal.ZERO).amount2(BigDecimal.ZERO).isSummaryRow(false).build());
            row.setVideoCount(row.getVideoCount() + 1);
            row.setAmount(row.getAmount().add(c.clientPrice));
            row.setAmount2(row.getAmount2().add(c.influencerCost));
        }
        List<PayslipDimensionRow> rows = new ArrayList<>(grouped.values());
        rows.sort((a, b) -> b.getVideoCount().compareTo(a.getVideoCount()));
        rows.add(buildSummaryRow(rows));

        // 当月所有"已确认"的其他员工：阶梯Bonus + 奖金 + 法务当月工资，都要从公司利润里扣掉
        // （上面这套公式本身只扣了内部执行成本/负责人提成，没扣这三项）。法务工资挪到这里
        // 一起处理是 2026-08-10 修复：之前"内部其他员工成本"只累加了财务/IT后勤固定月薪，
        // 完全漏了法务——数据看板那边（DashboardStatsService.getSummary()）2026-07 起已经把
        // 法务当月工资（Payslip.legalSalaryRmb）计入"内部其他员工成本"，但这边没有同步改，
        // 导致管理层确认某个月后，数据看板算出来的公司利润比工资单这边少（少扣的法务工资部分
        // 让工资单的利润显得偏高），Shawn 手动比对两边公式发现的。法务工资只在这里、从
        // othersConfirmed 里取（employeeRole 字段是 confirm() 时顺带存的，不用再单独查一次
        // 员工角色）——法务不涉及"手下执行人员工资"那套下游依赖，只要 confirmed 就是
        // finalConfirmed，所以能跟阶梯Bonus/奖金用同一批 othersConfirmed 数据源，不需要额外查询。
        List<Payslip> othersConfirmed = payslipRepo
                .findByYearMonthAndFinalConfirmedTrueAndIsDeletedFalseAndEmployeeIdNot(yearMonth, mgmt.getId());
        BigDecimal tierBonusTotalUsd = BigDecimal.ZERO;
        BigDecimal extraBonusTotalUsd = BigDecimal.ZERO;
        // 每个项目负责人自己的阶梯Bonus（2026-08-10 新增，供 commissionBreakdownRows 用）——
        // 只有已确认（finalConfirmed，即在 othersConfirmed 里）的项目负责人才有值可读，没确认的
        // 在下面组装明细行时按 null 处理（前端显示"—"，不是误导性的 0.00）
        Map<Long, BigDecimal> tierBonusByManager = new HashMap<>();
        // "内部其他员工成本"按人拆分（2026-08-10 新增，供 otherStaffCostBreakdownRows 用），
        // 跟下面 otherStaffCostRmb 汇总数字用的是完全同一套判断条件，只是多存一份明细
        List<PayslipDimensionRow> otherStaffCostBreakdownRows = new ArrayList<>();
        // 内部其他员工成本：财务/IT后勤固定月薪合计（人民币）+ 法务当月工资，换算成美金扣减。
        // 财务/IT后勤这部分同样要按入职时间过滤（2026-08 修复，跟 listForMonth()/
        // managementBlockReason() 保持一致口径）——不然入职月份晚于 yearMonth 的财务/IT后勤
        // 员工，固定月薪会被提前算进更早月份的公司利润扣减项里，把那些月份的公司利润少算了。
        BigDecimal otherStaffCostRmb = BigDecimal.ZERO;
        for (Employee e : allEmployees) {
            if (FIXED_SALARY_ROLES.contains(e.getRole()) && !isBeforeHireMonth(e, yearMonth)) {
                BigDecimal salary = dashboardStatsService.safe(e.getFixedMonthlySalary());
                otherStaffCostRmb = otherStaffCostRmb.add(salary);
                otherStaffCostBreakdownRows.add(PayslipDimensionRow.builder()
                        .brandName(e.getRole() + " - " + e.getName())
                        .amount(salary.setScale(SCALE, RoundingMode.HALF_UP))
                        .isSummaryRow(false).build());
            }
        }
        for (Payslip other : othersConfirmed) {
            PayslipDetailResponse snap = readSnapshot(other);
            if (snap.getTierBonusAmount() != null) {
                tierBonusTotalUsd = tierBonusTotalUsd.add(snap.getTierBonusAmount());
                tierBonusByManager.put(other.getEmployeeId(), snap.getTierBonusAmount());
            }
            if (other.getExtraBonusAmount() != null) {
                boolean isRmb = "RMB".equals(other.getExtraBonusCurrency());
                BigDecimal usd = isRmb
                        ? (rate != null && rate.compareTo(BigDecimal.ZERO) > 0
                                ? other.getExtraBonusAmount().divide(rate, SCALE, RoundingMode.HALF_UP)
                                : BigDecimal.ZERO)
                        : other.getExtraBonusAmount();
                extraBonusTotalUsd = extraBonusTotalUsd.add(usd);
            }
            if ("法务".equals(other.getEmployeeRole()) && other.getLegalSalaryRmb() != null) {
                otherStaffCostRmb = otherStaffCostRmb.add(other.getLegalSalaryRmb());
                otherStaffCostBreakdownRows.add(PayslipDimensionRow.builder()
                        .brandName("法务 - " + employeeNameOf(other.getEmployeeId()))
                        .amount(other.getLegalSalaryRmb().setScale(SCALE, RoundingMode.HALF_UP))
                        .isSummaryRow(false).build());
            }
        }
        otherStaffCostBreakdownRows.add(buildSummaryRow(otherStaffCostBreakdownRows));

        BigDecimal otherStaffCostUsd = dashboardStatsService.convertFromRmb(otherStaffCostRmb, rate, false);
        BigDecimal execCostUsd = totalExecCostUsd.setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal companyProfitBeforePayouts = totalCompanyProfitUsd.subtract(otherStaffCostUsd);

        BigDecimal managerCommissionTotal = totalCommission.add(tierBonusTotalUsd);
        BigDecimal companyProfit = companyProfitBeforePayouts.subtract(tierBonusTotalUsd).subtract(extraBonusTotalUsd);

        // "负责人提成合计（含Bonus）"明细行组装（2026-08-10 新增）：按负责人姓名排序，
        // amount=原始提成，amount2=阶梯Bonus（未确认/没配置为 null），profit=两者之和，
        // 跟顶部"负责人提成合计（含Bonus）"这一个数字的构成完全对应，方便管理层核对
        List<Long> commissionManagerIds = new ArrayList<>(commissionByManager.keySet());
        commissionManagerIds.sort(Comparator.comparing(this::employeeNameOf, Comparator.nullsLast(Comparator.naturalOrder())));
        List<PayslipDimensionRow> commissionBreakdownRows = new ArrayList<>();
        for (Long pmId : commissionManagerIds) {
            BigDecimal commission = commissionByManager.get(pmId).setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal bonus = tierBonusByManager.get(pmId);
            BigDecimal total = commission.add(bonus != null ? bonus : BigDecimal.ZERO);
            commissionBreakdownRows.add(PayslipDimensionRow.builder()
                    .brandName(employeeNameOf(pmId))
                    .amount(commission)
                    .amount2(bonus)
                    .profit(total)
                    .isSummaryRow(false).build());
        }
        commissionBreakdownRows.add(buildSummaryRow(commissionBreakdownRows));

        // ===== 管理层作为"特殊的项目负责人"，也要能按执行人员单独确认自己名下
        // （projectManagerId=管理层本人）执行人员的工资——用跟普通项目负责人完全一样的一套
        // 确认机制（ExecutorWageConfirmation，managerId=管理层自己的员工id），只是入口挪到
        // "工资单"主页面管理层自己那张卡片下面单独一块，2026-07 新增。跟这个方法上面算
        // "内部执行人力成本"用的是同一批 orders，但这里要按"项目负责人→执行人员"分组才能
        // 拆出明细行给前端展示。 =====
        Map<Long, Map<Long, List<CollaborationTracking>>> byPmThenExec = groupByManagerThenExecutor(orders);
        Map<Long, List<CollaborationTracking>> execOrdersUnderMgmt =
                byPmThenExec.getOrDefault(mgmt.getId(), Collections.emptyMap());
        Map<Long, ExecutorWageConfirmation> confirmationsForMgmt =
                confirmationByManagerIdParam.getOrDefault(mgmt.getId(), Collections.emptyMap());
        ExecutorWageDetail ownWageDetail = buildExecutorWageRows(mgmt.getId(), execOrdersUnderMgmt, confirmationsForMgmt);
        boolean ownAllExecutorsConfirmed = !execOrdersUnderMgmt.isEmpty()
                && execOrdersUnderMgmt.keySet().stream().allMatch(execId -> {
                    ExecutorWageConfirmation c = confirmationsForMgmt.get(execId);
                    return c != null && Boolean.TRUE.equals(c.getConfirmed());
                });

        return PayslipDetailResponse.builder()
                .type("MANAGEMENT")
                .rows(rows)
                .grossProfit(totalGrossProfit.setScale(SCALE, RoundingMode.HALF_UP))
                .distributableProfit(totalDistributable.setScale(SCALE, RoundingMode.HALF_UP))
                .managerCommissionTotal(managerCommissionTotal.setScale(SCALE, RoundingMode.HALF_UP))
                .executorPayTotal(execCostUsd)
                .otherStaffCost(otherStaffCostUsd)
                .extraBonusPayoutTotal(extraBonusTotalUsd.setScale(SCALE, RoundingMode.HALF_UP))
                .companyProfit(companyProfit.setScale(SCALE, RoundingMode.HALF_UP))
                .executorWageRows(ownWageDetail.rows)
                .executorWageTotal(ownWageDetail.total.setScale(SCALE, RoundingMode.HALF_UP))
                .executorWageConfirmed(ownAllExecutorsConfirmed)
                .commissionBreakdownRows(commissionBreakdownRows)
                .otherStaffCostBreakdownRows(otherStaffCostBreakdownRows)
                .build();
    }

    // ================= 按角色计算：批量（工资单列表页用，整月记录只查一次） =================

    /**
     * 项目负责人/执行人员批量实时预览：整月合作跟踪记录/执行人员工资确认状态由调用方
     * （listForMonth()）传入，不在这里各自查一次——2026-08-10 起 listForMonth() 还要用同一份
     * orders/confirmationByManagerId 批量算执行人员的"确认"按钮拦截原因（见 listForMonth()
     * 方法注释），两处共用同一份数据才能真正做到"整月记录只查一次"。
     */
    private Map<Long, PayslipDetailResponse> batchComputeCommissionRoles(
            List<Employee> employees, BigDecimal rate,
            List<CollaborationTracking> orders, Map<Long, Map<Long, ExecutorWageConfirmation>> confirmationByManagerId) {
        Map<Long, Employee> pmById = new HashMap<>();
        Map<Long, Employee> execById = new HashMap<>();
        for (Employee e : employees) {
            if ("项目负责人".equals(e.getRole())) pmById.put(e.getId(), e);
            else if ("执行人员".equals(e.getRole())) execById.put(e.getId(), e);
        }
        Map<Long, PayslipDetailResponse> result = new HashMap<>();
        if (pmById.isEmpty() && execById.isEmpty()) return result;

        // 按"项目负责人→执行人员"两层分组一次建好，项目负责人视角（自己名下执行人员薪酬明细）
        // 和执行人员视角（自己在每个项目负责人名下挣了多少）共用同一份分组结果，不重复扫描订单。
        Map<Long, Map<Long, List<CollaborationTracking>>> byPmThenExec = groupByManagerThenExecutor(orders);

        Map<Long, Map<String, PayslipDimensionRow>> pmGrouped = new HashMap<>();
        Map<Long, BigDecimal> pmCommission = new HashMap<>();

        for (CollaborationTracking o : orders) {
            Long mgrId = o.getProjectManagerId();
            if (mgrId != null && pmById.containsKey(mgrId)) {
                DashboardStatsService.Computed c = dashboardStatsService.compute(o);
                pmCommission.merge(mgrId, c.commissionAmount, BigDecimal::add);
                accumulatePmRow(pmGrouped.computeIfAbsent(mgrId, k -> new LinkedHashMap<>()), o, c);
            }
        }

        // 所有项目负责人的阶梯Bonus配置一次性查完，不再每人各自查一次（那样是另一处 N+1）
        Map<Long, List<CommissionBonusTier>> tiersByPmId =
                commissionBonusService.findTiersByEmployeeIds(new ArrayList<>(pmById.keySet()));
        for (Employee e : pmById.values()) {
            List<PayslipDimensionRow> rows = new ArrayList<>(
                    pmGrouped.getOrDefault(e.getId(), Collections.emptyMap()).values());
            List<CommissionBonusTier> tiers = tiersByPmId.getOrDefault(e.getId(), Collections.emptyList());
            result.put(e.getId(), buildProjectManagerDetail(
                    e, rows, pmCommission.getOrDefault(e.getId(), BigDecimal.ZERO), rate, tiers,
                    byPmThenExec, confirmationByManagerId));
        }
        for (Employee e : execById.values()) {
            result.put(e.getId(), buildExecutorCrossManagerDetail(e.getId(), byPmThenExec, confirmationByManagerId));
        }
        return result;
    }

    /**
     * 工资单口径统一排除"折损"（DELAYED）的记录（2026-07 新增，跟数据看板保持一致）：
     * 折损代表这笔视频项目因异常原因终止，不该计入任何人的提成/执行人员薪酬/公司利润。
     * findByPublishMonth 本身是通用查询（进度提醒/汇率维护等模块也在用"当月未删除记录"这个
     * 语义，仓储层不能加这个过滤），所以在这里统一过滤，每个直接调用 findByPublishMonth 的地方
     * 取数后都要立即调用，不要漏了某一个角色的计算路径。
     */
    private List<CollaborationTracking> excludeDamaged(List<CollaborationTracking> orders) {
        return orders.stream()
                .filter(o -> o.getProgress() != CollaborationProgress.DELAYED)
                .collect(Collectors.toList());
    }

    /** 整月合作跟踪记录按"项目负责人 → 执行人员 → 该组的记录"两层分组，两个视角共用 */
    private Map<Long, Map<Long, List<CollaborationTracking>>> groupByManagerThenExecutor(List<CollaborationTracking> orders) {
        Map<Long, Map<Long, List<CollaborationTracking>>> result = new LinkedHashMap<>();
        for (CollaborationTracking o : orders) {
            Long pmId = o.getProjectManagerId();
            Long execId = o.getExecutorId();
            if (pmId == null || execId == null) continue;
            result.computeIfAbsent(pmId, k -> new LinkedHashMap<>())
                    .computeIfAbsent(execId, k -> new ArrayList<>())
                    .add(o);
        }
        return result;
    }

    /**
     * managerId -> executorId -> 确认记录（2026-07 起按执行人员单独确认，不再是每个项目负责人
     * 一条）。改造之前的历史记录 executorId 为 NULL，这里直接跳过——那批记录代表的是"整批打包
     * 确认"这个已经废弃的语义，不应该被新逻辑当成任何一个具体执行人员的确认状态来用。
     */
    private Map<Long, Map<Long, ExecutorWageConfirmation>> fetchWageConfirmations(String yearMonth) {
        Map<Long, Map<Long, ExecutorWageConfirmation>> result = new HashMap<>();
        for (ExecutorWageConfirmation c : wageConfirmationRepo.findByYearMonthAndIsDeletedFalse(yearMonth)) {
            if (c.getExecutorId() == null) continue;
            result.computeIfAbsent(c.getManagerId(), k -> new HashMap<>()).put(c.getExecutorId(), c);
        }
        return result;
    }

    // ================= 维度行累加 / 组装（单个员工、批量两条路径共用） =================

    /**
     * 2026-08 新增：连带累加红人成本（amount2）、利润（profit=grossProfit），供项目负责人工资单
     * 详情展示提成金额的计算依据——不能只看合计的提成金额，得让项目负责人看得到"利润是怎么
     * 算出来的"。三个数字都取自同一个 Computed，跟 totalCommission 用的是同一份计算结果，
     * 不会出现"分别现算导致对不上"的问题。
     */
    private void accumulatePmRow(Map<String, PayslipDimensionRow> grouped, CollaborationTracking o,
                                  DashboardStatsService.Computed c) {
        String brandName = dashboardStatsService.brandNameOf(o.getBrandId());
        String teamName = dashboardStatsService.teamNameOf(o.getTeam());
        PayslipDimensionRow row = grouped.computeIfAbsent(brandName + "|" + teamName, k ->
                PayslipDimensionRow.builder().brandName(brandName).teamName(teamName)
                        .videoCount(0L).amount(BigDecimal.ZERO).amount2(BigDecimal.ZERO)
                        .profit(BigDecimal.ZERO).isSummaryRow(false).build());
        row.setVideoCount(row.getVideoCount() + 1);
        row.setAmount(row.getAmount().add(c.clientPrice));
        row.setAmount2(row.getAmount2().add(c.influencerCost));
        row.setProfit(row.getProfit().add(c.grossProfit));
    }

    /**
     * 2026-07 起分组 key 里加了单价（payRmb）：同一个"品牌方+团队+视频类型"组合，如果当月
     * 实际单价不一致（说明这批视频横跨了梯度分档的边界，比如前25条按¥20/条、第26条起按
     * ¥15/条），会拆成多行，每行都是单一单价，配合"单价"列 + 后面追加的"梯度小结"行，
     * 项目负责人才能看出"为什么这几条钱不一样多"，而不是一个笼统的合计数字掩盖了差异。
     */
    private void accumulateExecutorRow(Map<String, PayslipDimensionRow> grouped, CollaborationTracking o, BigDecimal payRmb) {
        String brandName = dashboardStatsService.brandNameOf(o.getBrandId());
        String teamName = dashboardStatsService.teamNameOf(o.getTeam());
        VideoType vt = o.getVideoType();
        String videoTypeKey = vt != null ? vt.name() : null;
        String key = videoTypeKey + "|" + brandName + "|" + teamName + "|" + payRmb.stripTrailingZeros().toPlainString();
        PayslipDimensionRow row = grouped.computeIfAbsent(key, k ->
                PayslipDimensionRow.builder().brandName(brandName).teamName(teamName)
                        .videoType(videoTypeKey)
                        .videoTypeLabel(vt != null ? vt.getLabel() : "未填写视频类型")
                        .videoCount(0L).amount(BigDecimal.ZERO).unitPrice(payRmb).isSummaryRow(false).build());
        row.setVideoCount(row.getVideoCount() + 1);
        row.setAmount(row.getAmount().add(payRmb));
    }

    private PayslipDetailResponse buildProjectManagerDetail(Employee emp, List<PayslipDimensionRow> rowsNoSummary,
                                                             BigDecimal totalCommission, BigDecimal rate,
                                                             List<CommissionBonusTier> tiers,
                                                             Map<Long, Map<Long, List<CollaborationTracking>>> byPmThenExec,
                                                             Map<Long, Map<Long, ExecutorWageConfirmation>> confirmationByManagerId) {
        List<PayslipDimensionRow> rows = new ArrayList<>(rowsNoSummary);
        rows.sort((a, b) -> b.getVideoCount().compareTo(a.getVideoCount()));
        rows.add(buildSummaryRow(rows));

        // tiers 为空=没配置阶梯=不展示该行（null）；配置了但没达标，computeBonusFromTiers 返回0，正常展示
        boolean bonusTierConfigured = emp.getBonusTierCurrency() != null && !tiers.isEmpty();
        BigDecimal tierBonus = bonusTierConfigured
                ? commissionBonusService.computeBonusFromTiers(emp, tiers, totalCommission, rate)
                : null;
        // 命中档位的 bonus 比例，跟"提成比例"并排展示（2026-07-28 新增）；没命中任何档位
        // （提成总额落在配置区间之外）时为 null，此时 tierBonus 恒为0，不需要展示比例
        CommissionBonusTier matchedTier = bonusTierConfigured
                ? commissionBonusService.findMatchedTier(emp, tiers, totalCommission, rate)
                : null;
        BigDecimal tierBonusRate = matchedTier != null ? matchedTier.getBonusRate() : null;

        // ===== 应发给自己名下执行人员的工资：2026-07 起按执行人员单独确认——每个执行人员
        // 独立判断"这个人confirm过了没"，confirm过的那个人用冻结快照，没confirm的那个人按
        // 现有记录实时算，两种状态可以在同一张表里混着展示（跟管理层确认自己那份工资、
        // 执行人员各自被哪个项目负责人确认，是完全独立的确认状态） =====
        Map<Long, List<CollaborationTracking>> execOrdersUnderPm =
                byPmThenExec.getOrDefault(emp.getId(), Collections.emptyMap());
        Map<Long, ExecutorWageConfirmation> confirmationsForThisManager =
                confirmationByManagerId.getOrDefault(emp.getId(), Collections.emptyMap());
        ExecutorWageDetail wageDetail = buildExecutorWageRows(emp.getId(), execOrdersUnderPm, confirmationsForThisManager);
        boolean allExecutorsConfirmed = !execOrdersUnderPm.isEmpty()
                && execOrdersUnderPm.keySet().stream().allMatch(execId -> {
                    ExecutorWageConfirmation c = confirmationsForThisManager.get(execId);
                    return c != null && Boolean.TRUE.equals(c.getConfirmed());
                });

        return PayslipDetailResponse.builder()
                .type("PROJECT_MANAGER")
                .rows(rows)
                .commissionRate(emp.getDefaultCommissionRate())
                .baseAmount(totalCommission.setScale(SCALE, RoundingMode.HALF_UP))
                .tierBonusAmount(tierBonus)
                .tierBonusRate(tierBonusRate)
                .executorWageRows(wageDetail.rows)
                .executorWageTotal(wageDetail.total.setScale(SCALE, RoundingMode.HALF_UP))
                .executorWageConfirmed(allExecutorsConfirmed)
                .build();
    }

    /**
     * 项目负责人/管理层视角"应发给执行人员的工资"：按执行人员分组，每个执行人员独立判断——
     * 这个执行人员的工资这个项目负责人已经confirm过就用冻结快照，没confirm就按当前记录实时算，
     * 组内明细+小计（小计行 groupConfirmed 标注这个执行人员是不是已经confirm，供前端在这一行
     * 放"确认/取消确认"这个执行人员工资的按钮），最后整体汇总。2026-07 起改成按执行人员单独
     * 确认后，同一张表里可能同时存在"已确认"和"预计"两种状态的执行人员分组。
     * 没有任何执行人员时（execOrdersUnderPm 为空）不追加汇总行——保持 rows 真正为空，
     * 这样前端"这个人当月是否涉及执行人员"的判断（rows 是否非空）才不会永远被这一行汇总
     * 行污染成"非空"。
     */
    private ExecutorWageDetail buildExecutorWageRows(Long managerId, Map<Long, List<CollaborationTracking>> execOrdersUnderPm,
                                                       Map<Long, ExecutorWageConfirmation> confirmationsForThisManager) {
        List<Long> execIds = new ArrayList<>(execOrdersUnderPm.keySet());
        execIds.sort(Comparator.comparing(this::employeeNameOf, Comparator.nullsLast(Comparator.naturalOrder())));
        // 这个负责人名下所有执行人员的费率梯度一次查完，下面循环里按 execId 从内存取，不再
        // 每个执行人员各查一次库（见 fetchTiersByExecutorAndType 方法注释）
        Map<Long, Map<String, List<ExecutorPayRateTier>>> tiersByExecThenType = fetchTiersByExecutorAndType(managerId);

        List<PayslipDimensionRow> displayRows = new ArrayList<>();
        List<PayslipDimensionRow> allDetail = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Long execId : execIds) {
            String execName = employeeNameOf(execId);
            ExecutorWageConfirmation confirmation =
                    confirmationsForThisManager == null ? null : confirmationsForThisManager.get(execId);
            boolean execConfirmed = confirmation != null && Boolean.TRUE.equals(confirmation.getConfirmed());
            List<PayslipDimensionRow> groupRows;
            if (execConfirmed) {
                groupRows = readExecutorWageSnapshotRows(confirmation);
            } else {
                Map<String, List<ExecutorPayRateTier>> tiersByType =
                        tiersByExecThenType.getOrDefault(execId, Collections.emptyMap());
                groupRows = buildDimensionRowsForOrders(execOrdersUnderPm.get(execId));
                sortExecutorRowsByTier(tiersByType, groupRows);
                for (PayslipDimensionRow r : groupRows) {
                    r.setExecutorId(execId);
                    r.setExecutorName(execName);
                }
                groupRows = withTierSummaries(tiersByType, groupRows);
            }
            PayslipDimensionRow subtotal = sumRowsAsSubtotal(groupRows);
            subtotal.setBrandName(execName + " 小计");
            subtotal.setExecutorId(execId);
            subtotal.setExecutorName(execName);
            subtotal.setGroupConfirmed(execConfirmed);
            total = total.add(subtotal.getAmount());
            displayRows.addAll(groupRows);
            displayRows.add(subtotal);
            allDetail.addAll(groupRows);
        }
        if (!execIds.isEmpty()) {
            displayRows.add(buildSummaryRow(allDetail));
        }
        return new ExecutorWageDetail(displayRows, total);
    }

    /**
     * 执行人员本人视角：按项目负责人分组，每组独立判断——那个项目负责人已经确认了执行人员工资
     * 就用冻结快照里属于这个执行人员的那部分行，没确认就按当前记录实时算这一组。加总所有
     * 项目负责人这一组得到执行人员自己的总工资。这是执行人员这一侧唯一的"混合状态"入口。
     */
    private PayslipDetailResponse buildExecutorCrossManagerDetail(Long execId,
                                                                   Map<Long, Map<Long, List<CollaborationTracking>>> byPmThenExec,
                                                                   Map<Long, Map<Long, ExecutorWageConfirmation>> confirmationByManagerId) {
        List<Long> pmIds = new ArrayList<>();
        for (Map.Entry<Long, Map<Long, List<CollaborationTracking>>> e : byPmThenExec.entrySet()) {
            if (e.getValue().containsKey(execId)) pmIds.add(e.getKey());
        }
        pmIds.sort(Comparator.comparing(this::employeeNameOf, Comparator.nullsLast(Comparator.naturalOrder())));
        // 这个执行人员涉及的所有项目负责人的费率梯度一次性批量查完，下面循环里按
        // (pmId,execId) 从内存取，不再每个项目负责人各查一次库（见 fetchTiersByManagerThenExecutorAndType）
        Map<Long, Map<Long, Map<String, List<ExecutorPayRateTier>>>> tiersByManagerThenExecThenType =
                fetchTiersByManagerThenExecutorAndType(pmIds);

        List<PayslipDimensionRow> displayRows = new ArrayList<>();
        List<PayslipDimensionRow> allDetail = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Long pmId : pmIds) {
            String pmName = employeeNameOf(pmId);
            ExecutorWageConfirmation confirmation = confirmationByManagerId
                    .getOrDefault(pmId, Collections.emptyMap()).get(execId);
            boolean groupConfirmed = confirmation != null && Boolean.TRUE.equals(confirmation.getConfirmed());
            List<PayslipDimensionRow> groupRows;
            if (groupConfirmed) {
                // 2026-07 起每条确认记录只对应一个执行人员，快照本身就已经是这个执行人员的
                // 完整明细，不再需要从"整批打包"的快照里按 execId 过滤
                groupRows = readExecutorWageSnapshotRows(confirmation);
            } else {
                List<CollaborationTracking> ordersForPair = byPmThenExec.get(pmId).get(execId);
                Map<String, List<ExecutorPayRateTier>> tiersByType = tiersByManagerThenExecThenType
                        .getOrDefault(pmId, Collections.emptyMap()).getOrDefault(execId, Collections.emptyMap());
                groupRows = buildDimensionRowsForOrders(ordersForPair);
                sortExecutorRowsByTier(tiersByType, groupRows);
                groupRows = withTierSummaries(tiersByType, groupRows);
            }
            for (PayslipDimensionRow r : groupRows) r.setProjectManagerName(pmName);

            PayslipDimensionRow subtotal = sumRowsAsSubtotal(groupRows);
            subtotal.setBrandName(pmName + " 小计");
            subtotal.setProjectManagerName(pmName);
            subtotal.setGroupConfirmed(groupConfirmed);
            total = total.add(subtotal.getAmount());
            displayRows.addAll(groupRows);
            displayRows.add(subtotal);
            allDetail.addAll(groupRows);
        }
        displayRows.add(buildSummaryRow(allDetail));

        return PayslipDetailResponse.builder()
                .type("EXECUTOR")
                .rows(displayRows)
                .baseAmount(total.setScale(SCALE, RoundingMode.HALF_UP))
                .build();
    }

    private List<PayslipDimensionRow> buildDimensionRowsForOrders(List<CollaborationTracking> orders) {
        Map<String, PayslipDimensionRow> grouped = new LinkedHashMap<>();
        if (orders != null) {
            for (CollaborationTracking o : orders) {
                BigDecimal payRmb = dashboardStatsService.safe(o.getInternalExecutionCost());
                accumulateExecutorRow(grouped, o, payRmb);
            }
        }
        return new ArrayList<>(grouped.values());
    }

    /**
     * 2026-07-28 排序：视频类型分组按该类型本月视频总数倒序排列（Shawn 反馈：不应该按枚举声明
     * 顺序固定分组先后，而是视频数量多的类型分组排前面）→ 组内按梯度档位（按 ExecutorPayRateTier
     * 配置的 minCount 升序，即梯度1/2/3...）→ 视频数倒序 → 品牌方/团队（仅剩的兜底，保证同一档位内
     * 视频数也相同时排序稳定）。同一档位配置只有0/1档，即"每条固定价"或没配置梯度的视频类型，走
     * tierRankForUnitPrice 兜底逻辑，效果等同于不分档、组内直接按视频数倒序。
     */
    /** 单个负责人名下配置的全部执行人员费率梯度一次查完，按 executorId→videoType 分组存进内存，
     *  供 {@link #sortExecutorRowsByTier}/{@link #withTierSummaries} 查表用，不再各自查库。 */
    private Map<Long, Map<String, List<ExecutorPayRateTier>>> fetchTiersByExecutorAndType(Long managerId) {
        Map<Long, Map<String, List<ExecutorPayRateTier>>> result = new HashMap<>();
        for (ExecutorPayRateTier t : executorPayRateTierRepo.findByManagerIdAndIsDeletedFalseOrderByMinCountAsc(managerId)) {
            result.computeIfAbsent(t.getExecutorId(), k -> new HashMap<>())
                    .computeIfAbsent(t.getVideoType().name(), k -> new ArrayList<>())
                    .add(t);
        }
        return result;
    }

    /** 一批负责人（执行人员视角跨多个项目负责人时用）版本，按 managerId→executorId→videoType
     *  分组；findByManagerIdInAndIsDeletedFalse 本身不带排序，这里补上按 minCount 升序，
     *  跟上面单负责人版本（数据库层 OrderByMinCountAsc）的顺序保持一致。 */
    private Map<Long, Map<Long, Map<String, List<ExecutorPayRateTier>>>> fetchTiersByManagerThenExecutorAndType(
            Collection<Long> managerIds) {
        Map<Long, Map<Long, Map<String, List<ExecutorPayRateTier>>>> result = new HashMap<>();
        for (ExecutorPayRateTier t : executorPayRateTierRepo.findByManagerIdInAndIsDeletedFalse(managerIds)) {
            result.computeIfAbsent(t.getManagerId(), k -> new HashMap<>())
                    .computeIfAbsent(t.getExecutorId(), k -> new HashMap<>())
                    .computeIfAbsent(t.getVideoType().name(), k -> new ArrayList<>())
                    .add(t);
        }
        for (Map<Long, Map<String, List<ExecutorPayRateTier>>> byExec : result.values()) {
            for (Map<String, List<ExecutorPayRateTier>> byType : byExec.values()) {
                for (List<ExecutorPayRateTier> tiers : byType.values()) {
                    tiers.sort(Comparator.comparing(ExecutorPayRateTier::getMinCount));
                }
            }
        }
        return result;
    }

    /**
     * 2026-08-13 性能修复：这个方法（以及紧跟着调用的 {@link #withTierSummaries}）之前各自独立
     * 按 (managerId,executorId,videoType) 现查一次 executorPayRateTierRepo——同一对 (manager,
     * executor) 会被查两次（排序一次、判断要不要加梯度小结行再查一次），而且这整套逻辑在工资单
     * 列表页（{@link #listForMonth}）里每个项目负责人名下的每个执行人员都要走一遍：负责人越多、
     * 名下执行人员/涉及的视频类型越多，查询次数就跟着线性甚至更快地增长——跟 InfluencerController
     * 那次 DomainSyncService 问题不是同一段代码，但同一类"单看一次调用不贵，整页所有人加起来就
     * 很贵"的 N+1，是 Shawn 反馈"工资单页面刷新很久"的另一个成因。现在改成调用方（
     * {@link #fetchTiersByExecutorAndType}/{@link #fetchTiersByManagerThenExecutorAndType}）
     * 按 managerId（或一批 managerId）一次性把梯度配置整个查出来传进来，这两个方法都只做内存
     * 里的 Map 查找，不再各自查库。
     */
    private void sortExecutorRowsByTier(Map<String, List<ExecutorPayRateTier>> tiersByType, List<PayslipDimensionRow> rows) {
        Map<PayslipDimensionRow, Integer> tierRankByRow = new IdentityHashMap<>();
        for (PayslipDimensionRow r : rows) {
            List<ExecutorPayRateTier> tiers = tiersByType.getOrDefault(r.getVideoType(), Collections.emptyList());
            tierRankByRow.put(r, tierRankForUnitPrice(tiers, r.getUnitPrice()));
        }
        Map<String, Long> totalCountByType = new HashMap<>();
        for (PayslipDimensionRow r : rows) {
            totalCountByType.merge(r.getVideoType(), r.getVideoCount() != null ? r.getVideoCount() : 0L, Long::sum);
        }
        rows.sort((a, b) -> {
            long groupCmp = totalCountByType.getOrDefault(b.getVideoType(), 0L)
                    - totalCountByType.getOrDefault(a.getVideoType(), 0L);
            if (groupCmp != 0) return groupCmp > 0 ? 1 : -1;
            int ta = videoTypeOrdinal(a.getVideoType());
            int tb = videoTypeOrdinal(b.getVideoType());
            if (ta != tb) return Integer.compare(ta, tb);
            int rankCmp = Integer.compare(tierRankByRow.get(a), tierRankByRow.get(b));
            if (rankCmp != 0) return rankCmp;
            int countCmp = b.getVideoCount().compareTo(a.getVideoCount());
            if (countCmp != 0) return countCmp;
            int brandCmp = safeCompare(a.getBrandName(), b.getBrandName());
            if (brandCmp != 0) return brandCmp;
            return safeCompare(a.getTeamName(), b.getTeamName());
        });
    }

    /** 这一行的单价属于第几档梯度（0=梯度1，1=梯度2...），没匹配上任何配置档位（临时改价/历史
     * 数据/没配置梯度）时排在已知档位后面，效果上等同于跟其他没匹配上的行放在同一"档"里 */
    private int tierRankForUnitPrice(List<ExecutorPayRateTier> tiers, BigDecimal unitPrice) {
        if (unitPrice == null) return tiers.size();
        for (int i = 0; i < tiers.size(); i++) {
            if (tiers.get(i).getRate().compareTo(unitPrice) == 0) return i;
        }
        return tiers.size();
    }

    /** null 安全的字符串比较（品牌方/团队名可能为空，比如红人没关联团队） */
    private int safeCompare(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return a.compareTo(b);
    }

    /**
     * 在已经排好序的执行人员薪酬明细行里，按视频类型分段插入"梯度小结"行（2026-07 新增）：
     * 只有这个视频类型配置了2档以上真正意义上的梯度（不是"每条固定价"那种单档）才插入，
     * 每一档配了多少条按这一段明细行里"单价等于这一档单价"的行数求和——这批明细行已经是
     * 按 accumulateExecutorRow 的单价分组拆开的，同一档的所有明细行单价必然一致，加总不会
     * 漏掉也不会重复。传入的 rows 必须已经按 sortExecutorRowsByTier 排过序（同一视频类型的行
     * 是连续的一段），否则这里按"视频类型变化"切分段落的逻辑会不对。
     */
    private List<PayslipDimensionRow> withTierSummaries(Map<String, List<ExecutorPayRateTier>> tiersByType,
                                                          List<PayslipDimensionRow> sortedRows) {
        if (sortedRows.isEmpty()) return sortedRows;
        List<PayslipDimensionRow> result = new ArrayList<>();
        String currentType = null;
        List<PayslipDimensionRow> currentTypeRows = new ArrayList<>();
        for (PayslipDimensionRow row : sortedRows) {
            if (!Objects.equals(row.getVideoType(), currentType)) {
                appendTierSummaryIfNeeded(tiersByType, currentType, currentTypeRows, result);
                currentType = row.getVideoType();
                currentTypeRows = new ArrayList<>();
            }
            result.add(row);
            currentTypeRows.add(row);
        }
        appendTierSummaryIfNeeded(tiersByType, currentType, currentTypeRows, result);
        return result;
    }

    private void appendTierSummaryIfNeeded(Map<String, List<ExecutorPayRateTier>> tiersByType, String videoTypeKey,
                                            List<PayslipDimensionRow> rowsForType, List<PayslipDimensionRow> result) {
        if (videoTypeKey == null || rowsForType.isEmpty()) return;
        List<ExecutorPayRateTier> tiers = tiersByType.getOrDefault(videoTypeKey, Collections.emptyList());
        // 只有1档=每条固定价，不存在"跨档"的问题，不需要这行说明
        if (tiers.size() <= 1) return;

        StringBuilder sb = new StringBuilder("梯度小结：");
        for (int i = 0; i < tiers.size(); i++) {
            ExecutorPayRateTier tier = tiers.get(i);
            String rangeLabel = tier.getMaxCount() == null
                    ? tier.getMinCount() + "条+"
                    : (tier.getMinCount().equals(tier.getMaxCount())
                        ? "第" + tier.getMinCount() + "条" : tier.getMinCount() + "-" + tier.getMaxCount() + "条");
            long countInTier = rowsForType.stream()
                    .filter(r -> r.getUnitPrice() != null && r.getUnitPrice().compareTo(tier.getRate()) == 0)
                    .mapToLong(r -> r.getVideoCount() != null ? r.getVideoCount() : 0)
                    .sum();
            if (i > 0) sb.append("；");
            sb.append(rangeLabel).append("¥").append(fmtTierAmount(tier.getRate())).append("/条（本月").append(countInTier).append("条）");
        }
        result.add(PayslipDimensionRow.builder()
                .brandName(sb.toString())
                .videoType(videoTypeKey)
                .videoCount(0L)
                .amount(BigDecimal.ZERO)
                .isTierSummaryRow(true)
                .build());
    }

    private String fmtTierAmount(BigDecimal v) {
        return v == null ? "0.00" : v.setScale(SCALE, RoundingMode.HALF_UP).toString();
    }

    /** 一组明细行的小计（区别于 buildSummaryRow 那个整体汇总行，isGroupSubtotal=true） */
    private PayslipDimensionRow sumRowsAsSubtotal(List<PayslipDimensionRow> rows) {
        long count = 0;
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal amount2 = BigDecimal.ZERO;
        for (PayslipDimensionRow r : rows) {
            count += r.getVideoCount() != null ? r.getVideoCount() : 0;
            amount = amount.add(r.getAmount() != null ? r.getAmount() : BigDecimal.ZERO);
            amount2 = amount2.add(r.getAmount2() != null ? r.getAmount2() : BigDecimal.ZERO);
        }
        return PayslipDimensionRow.builder()
                .videoCount(count)
                .amount(amount.setScale(SCALE, RoundingMode.HALF_UP))
                .amount2(amount2.setScale(SCALE, RoundingMode.HALF_UP))
                .isGroupSubtotal(true)
                .build();
    }

    private String employeeNameOf(Long employeeId) {
        if (employeeId == null) return null;
        Employee e = employeeCache.findById(employeeId);
        return e != null ? e.getName() : "未知员工";
    }

    private List<PayslipDimensionRow> readExecutorWageSnapshotRows(ExecutorWageConfirmation confirmation) {
        try {
            return objectMapper.readValue(confirmation.getDetailJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, PayslipDimensionRow.class));
        } catch (Exception e) {
            throw new RuntimeException("执行人员工资快照反序列化失败", e);
        }
    }

    private BigDecimal sumSnapshotTotal(List<PayslipDimensionRow> rows) {
        for (PayslipDimensionRow r : rows) {
            if (Boolean.TRUE.equals(r.getIsSummaryRow())) return r.getAmount();
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (PayslipDimensionRow r : rows) {
            if (!Boolean.TRUE.equals(r.getIsSummaryRow()) && !Boolean.TRUE.equals(r.getIsGroupSubtotal())) {
                sum = sum.add(r.getAmount() != null ? r.getAmount() : BigDecimal.ZERO);
            }
        }
        return sum.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** 项目负责人"应发给执行人员的工资"计算结果：分组明细+小计+汇总的展示行，以及总额（人民币原值） */
    private static class ExecutorWageDetail {
        final List<PayslipDimensionRow> rows;
        final BigDecimal total;
        ExecutorWageDetail(List<PayslipDimensionRow> rows, BigDecimal total) {
            this.rows = rows;
            this.total = total;
        }
    }

    private int videoTypeOrdinal(String name) {
        if (name == null) return Integer.MAX_VALUE;
        try {
            return VideoType.valueOf(name).ordinal();
        } catch (IllegalArgumentException e) {
            // 同上：name 理论上必然是合法枚举名，走到这里说明数据有问题
            log.warn("排序时 VideoType.valueOf 失败，未知视频类型：{}", name);
            return Integer.MAX_VALUE;
        }
    }

    private PayslipDimensionRow buildSummaryRow(List<PayslipDimensionRow> rows) {
        long count = 0;
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal amount2 = BigDecimal.ZERO;
        // profit 只有 PROJECT_MANAGER 的明细行会赋值（见 accumulatePmRow），其余类型的行这个
        // 字段恒为 null，按0处理即可——这里统一汇总不区分调用方是哪个类型，无害
        BigDecimal profit = BigDecimal.ZERO;
        for (PayslipDimensionRow r : rows) {
            count += r.getVideoCount() != null ? r.getVideoCount() : 0;
            amount = amount.add(r.getAmount() != null ? r.getAmount() : BigDecimal.ZERO);
            amount2 = amount2.add(r.getAmount2() != null ? r.getAmount2() : BigDecimal.ZERO);
            profit = profit.add(r.getProfit() != null ? r.getProfit() : BigDecimal.ZERO);
        }
        return PayslipDimensionRow.builder()
                .brandName("汇总").videoCount(count)
                .amount(amount.setScale(SCALE, RoundingMode.HALF_UP))
                .amount2(amount2.setScale(SCALE, RoundingMode.HALF_UP))
                .profit(profit.setScale(SCALE, RoundingMode.HALF_UP))
                .isSummaryRow(true)
                .build();
    }

    // ================= 展示层：换算成请求币种，现算总工资 =================

    /**
     * 已确认走快照，未确认实时算（优先用调用方批量算好的 precomputedLive，没有才现查现算）。
     * liveRateInfo 由调用方在整个请求里只查一次，这里不再重复查汇率。
     */
    private PayslipDetailResponse resolveDisplay(Employee emp, String yearMonth, String currency,
                                                  PayslipDetailResponse precomputedLive, Payslip p,
                                                  ExchangeRateInfo liveRateInfo) {
        boolean toRmb = "RMB".equalsIgnoreCase(currency);
        // 2026-07-28 起、2026-08 扩展到管理层：是否使用冻结快照看 finalConfirmed，不是
        // confirmed——项目负责人/执行人员/管理层，点了"确认"（confirmed=true）之后，只要相关的
        // 执行人员工资确认还没全部到位（finalConfirmed 仍是 false），这里依然展示实时数据，
        // 不提前冻结（见 recomputeFinality）。其余角色（财务/IT后勤/法务）confirmed/finalConfirmed
        // 恒等，行为不变。
        if (p != null && Boolean.TRUE.equals(p.getFinalConfirmed())) {
            BigDecimal snapshotRate = p.getExchangeRateSnapshot();
            ExchangeRateInfo snapshotRateInfo = ExchangeRateInfo.builder()
                    .yearMonth(yearMonth).usdToCny(snapshotRate).isMissing(snapshotRate == null).build();
            return toDisplayResponse(readSnapshot(p), p, snapshotRate, toRmb, true, snapshotRateInfo);
        }
        BigDecimal rate = liveRateInfo.getUsdToCny();
        PayslipDetailResponse live = precomputedLive != null ? precomputedLive : computeLive(emp, yearMonth, p, rate);
        return toDisplayResponse(live, p, rate, toRmb, false, liveRateInfo);
    }

    private PayslipDetailResponse toDisplayResponse(PayslipDetailResponse src, Payslip draft, BigDecimal rate,
                                                     boolean toRmb, boolean confirmed, ExchangeRateInfo rateInfo) {
        String type = src.getType();
        boolean rowsAreRmb = "EXECUTOR".equals(type);
        boolean baseIsRmb = "EXECUTOR".equals(type) || "FIXED_SALARY".equals(type) || "LEGAL".equals(type);

        List<PayslipDimensionRow> convertedRows = convertDimensionRows(src.getRows(), rowsAreRmb, rate, toRmb);

        BigDecimal baseAmount = convertAmount(src.getBaseAmount(), baseIsRmb, rate, toRmb);
        BigDecimal tierBonus = convertAmount(src.getTierBonusAmount(), false, rate, toRmb);
        BigDecimal grossProfit = convertAmount(src.getGrossProfit(), false, rate, toRmb);
        BigDecimal distributable = convertAmount(src.getDistributableProfit(), false, rate, toRmb);
        BigDecimal managerCommissionTotal = convertAmount(src.getManagerCommissionTotal(), false, rate, toRmb);
        BigDecimal executorPayTotal = convertAmount(src.getExecutorPayTotal(), false, rate, toRmb);
        BigDecimal otherStaffCost = convertAmount(src.getOtherStaffCost(), false, rate, toRmb);
        BigDecimal extraBonusPayoutTotal = convertAmount(src.getExtraBonusPayoutTotal(), false, rate, toRmb);
        BigDecimal companyProfit = convertAmount(src.getCompanyProfit(), false, rate, toRmb);

        BigDecimal extraBonus = null;
        if (draft != null && draft.getExtraBonusAmount() != null) {
            boolean extraIsRmb = "RMB".equals(draft.getExtraBonusCurrency());
            extraBonus = convertAmount(draft.getExtraBonusAmount(), extraIsRmb, rate, toRmb);
        }

        BigDecimal total = "MANAGEMENT".equals(type) ? companyProfit : safeAdd(safeAdd(baseAmount, tierBonus), extraBonus);

        // ===== 项目负责人 + 管理层（作为"特殊项目负责人"）专属：应发给执行人员的工资
        // （人民币原值）换算。finalNetWage（最终净得工资）只有项目负责人有意义——管理层的
        // "总工资"本来就是公司利润，不存在"扣掉付给执行人员的钱之后还剩多少归自己"这个概念，
        // 所以 finalNetWage 依然只在 isProjectManager 时计算，不能用 showsExecutorWages 那个
        // 更宽的判断，否则 finalNetWage 会算出一个没有业务意义的"公司利润减执行人力成本"数字。
        // finalNetWage 用"两边都换算成请求币种之后再相减"，不是分别独立算好各自的美金/人民币值
        // 再各自四舍五入然后相减——这样用户拿页面上"总工资"和"应发给执行人员的工资"两个已经
        // 展示出来的数字手动相减，一定能跟这里算出来的 finalNetWage 完全对上。
        boolean isProjectManager = "PROJECT_MANAGER".equals(type);
        boolean isManagement = "MANAGEMENT".equals(type);
        boolean showsExecutorWages = isProjectManager || isManagement;
        List<PayslipDimensionRow> convertedExecutorWageRows = showsExecutorWages
                ? convertDimensionRows(src.getExecutorWageRows(), true, rate, toRmb) : null;
        BigDecimal executorWageTotal = showsExecutorWages
                ? convertAmount(src.getExecutorWageTotal(), true, rate, toRmb) : null;
        BigDecimal finalNetWage = isProjectManager && total != null && executorWageTotal != null
                ? total.subtract(executorWageTotal).setScale(SCALE, RoundingMode.HALF_UP) : null;

        // 管理层专属明细拆分（2026-08-10 新增，见 PayslipDetailResponse 字段注释）：
        // commissionBreakdownRows 存的原始值是美金（跟 managerCommissionTotal 一致），
        // otherStaffCostBreakdownRows 存的原始值是人民币（跟 executorWageRows 一致）
        List<PayslipDimensionRow> convertedCommissionBreakdownRows = isManagement
                ? convertDimensionRows(src.getCommissionBreakdownRows(), false, rate, toRmb) : null;
        List<PayslipDimensionRow> convertedOtherStaffCostBreakdownRows = isManagement
                ? convertDimensionRows(src.getOtherStaffCostBreakdownRows(), true, rate, toRmb) : null;

        return PayslipDetailResponse.builder()
                .type(type).rows(convertedRows)
                .commissionRate(src.getCommissionRate())
                .baseAmount(baseAmount).tierBonusAmount(tierBonus).tierBonusRate(src.getTierBonusRate())
                .extraBonusAmount(extraBonus)
                .extraBonusAmountNative(draft != null ? draft.getExtraBonusAmount() : null)
                .extraBonusCurrencyNative(draft != null ? draft.getExtraBonusCurrency() : null)
                .totalAmount(total)
                .executorWageRows(convertedExecutorWageRows)
                .executorWageTotal(executorWageTotal)
                .executorWageConfirmed(showsExecutorWages ? src.getExecutorWageConfirmed() : null)
                .finalNetWage(finalNetWage)
                .grossProfit(grossProfit).distributableProfit(distributable)
                .managerCommissionTotal(managerCommissionTotal).executorPayTotal(executorPayTotal)
                .otherStaffCost(otherStaffCost).extraBonusPayoutTotal(extraBonusPayoutTotal)
                .companyProfit(companyProfit)
                .commissionBreakdownRows(convertedCommissionBreakdownRows)
                .otherStaffCostBreakdownRows(convertedOtherStaffCostBreakdownRows)
                .currency(toRmb ? "RMB" : "USD").confirmed(confirmed)
                .ownActionConfirmed(draft != null && Boolean.TRUE.equals(draft.getConfirmed()))
                .exchangeRateInfo(rateInfo)
                .build();
    }

    private List<PayslipDimensionRow> convertDimensionRows(List<PayslipDimensionRow> src, boolean amountIsRmb,
                                                            BigDecimal rate, boolean toRmb) {
        if (src == null) return null;
        List<PayslipDimensionRow> result = new ArrayList<>();
        for (PayslipDimensionRow r : src) {
            result.add(PayslipDimensionRow.builder()
                    .brandName(r.getBrandName()).teamName(r.getTeamName())
                    .videoType(r.getVideoType()).videoTypeLabel(r.getVideoTypeLabel())
                    .videoCount(r.getVideoCount())
                    .amount(convertAmount(r.getAmount(), amountIsRmb, rate, toRmb))
                    .amount2(convertAmount(r.getAmount2(), false, rate, toRmb))
                    .profit(convertAmount(r.getProfit(), false, rate, toRmb))
                    .unitPrice(convertAmount(r.getUnitPrice(), amountIsRmb, rate, toRmb))
                    .isSummaryRow(r.getIsSummaryRow())
                    .isTierSummaryRow(r.getIsTierSummaryRow())
                    .projectManagerName(r.getProjectManagerName())
                    .executorId(r.getExecutorId())
                    .executorName(r.getExecutorName())
                    .isGroupSubtotal(r.getIsGroupSubtotal())
                    .groupConfirmed(r.getGroupConfirmed())
                    .build());
        }
        return result;
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

    /**
     * 2026-08 新增：该员工在"员工管理"标注的"入职时间"，是否晚于（按月比较）当前查询的这个
     * 月份——是的话这个月的工资单列表不该包含这条员工记录（入职之前不该有工资单）。
     * 没标注入职时间（历史遗留员工）时不过滤，保持原来的行为，不强行拦掉查不到入职时间的人。
     *
     * 用字符串比较而不是 Date 比较，是因为只关心"月份"这个粒度，不关心具体哪一天入职——
     * 同一个月入职的，这个月照常有工资单，不做按天折算。格式必须是 yyyyMM（不带"-"），
     * 跟这个模块/CollaborationTracking 那边 publishMonth 的口径统一——前端月份选择器
     * value-format 就是 "YYYYMM"（见 PayslipListPage.vue），Payslip.yearMonth 这一列
     * 存的也是这个格式。2026-08 刚上线时这里手滑写成了 "yyyy-MM"，导致字符串比较整个失效
     * （"202606".compareTo("2026-07") 因为 '0' 的 ASCII 比 '-' 大，永远判定不成立），
     * 7月入职的员工照样出现在6月工资单列表里——这里改回 yyyyMM 就是修这个问题。
     */
    private boolean isBeforeHireMonth(Employee e, String yearMonth) {
        if (e.getHireDate() == null) return false;
        String hireMonth = new SimpleDateFormat("yyyyMM").format(e.getHireDate());
        return yearMonth.compareTo(hireMonth) < 0;
    }

    private PayslipRowResponse toRowResponse(Employee emp, String yearMonth, String currency,
                                              PayslipDetailResponse precomputedLive, Payslip payslip,
                                              ExchangeRateInfo liveRateInfo) {
        return toRowResponse(emp, yearMonth, currency, precomputedLive, payslip, liveRateInfo, null);
    }

    /**
     * 2026-08-10 新增 precomputedExecStatus 参数（性能优化）：listForMonth() 批量算好全部
     * 执行人员的 ExecutorPmConfirmStatus 后传进来，跳过下面"执行人员"分支现查一次的动作；
     * 非 null 时只对角色="执行人员"的行生效，其他角色/managementRow() 那条单行调用路径继续
     * 传 null、行为完全不变。
     */
    private PayslipRowResponse toRowResponse(Employee emp, String yearMonth, String currency,
                                              PayslipDetailResponse precomputedLive, Payslip payslip,
                                              ExchangeRateInfo liveRateInfo, ExecutorPmConfirmStatus precomputedExecStatus) {
        PayslipDetailResponse d = resolveDisplay(emp, yearMonth, currency, precomputedLive, payslip, liveRateInfo);
        Long videoCount = null;
        if (("项目负责人".equals(emp.getRole()) || "执行人员".equals(emp.getRole()))
                && d.getRows() != null && !d.getRows().isEmpty()) {
            videoCount = d.getRows().get(d.getRows().size() - 1).getVideoCount();
        }
        Boolean legalSalarySet = "法务".equals(emp.getRole()) ? (payslip != null && payslip.getLegalSalaryRmb() != null) : null;
        String blockedReason;
        if ("管理层".equals(emp.getRole())) {
            blockedReason = managementBlockReason(yearMonth, emp.getId(), liveRateInfo.getUsdToCny());
        } else if ("执行人员".equals(emp.getRole())) {
            ExecutorPmConfirmStatus status = precomputedExecStatus != null
                    ? precomputedExecStatus : resolveExecutorPmConfirmStatus(emp.getId(), yearMonth);
            blockedReason = status.getBlockedReason();
        } else {
            blockedReason = null;
        }
        return PayslipRowResponse.builder()
                .employeeId(emp.getId()).employeeName(emp.getName()).employeeRole(emp.getRole())
                .confirmed(d.getConfirmed())
                .ownActionConfirmed(d.getOwnActionConfirmed())
                .videoCount(videoCount)
                .baseAmount(d.getBaseAmount())
                .tierBonusAmount(d.getTierBonusAmount())
                .extraBonusAmount(d.getExtraBonusAmount())
                .extraBonusAmountNative(d.getExtraBonusAmountNative())
                .extraBonusCurrencyNative(d.getExtraBonusCurrencyNative())
                .totalAmount(d.getTotalAmount())
                .legalSalarySet(legalSalarySet)
                .blockedReason(blockedReason)
                .grossProfit(d.getGrossProfit())
                .distributableProfit(d.getDistributableProfit())
                .managerCommissionTotal(d.getManagerCommissionTotal())
                .executorPayTotal(d.getExecutorPayTotal())
                .otherStaffCost(d.getOtherStaffCost())
                .extraBonusPayoutTotal(d.getExtraBonusPayoutTotal())
                .executorWageConfirmed(d.getExecutorWageConfirmed())
                .hasExecutorWageWork("项目负责人".equals(emp.getRole())
                        && d.getExecutorWageRows() != null && !d.getExecutorWageRows().isEmpty())
                .build();
    }

    /**
     * 管理层确认前置校验：当月所有在职、非管理层的员工都必须已经是"最终版"（finalConfirmed，
     * 不只是 confirmed），否则返回拦截文案。原因：管理层自己的"公司利润"计算要扣掉当月所有
     * 其他员工已确认的阶梯Bonus+奖金（见 computeManagement 里 othersConfirmed 那段），而
     * 项目负责人/执行人员的阶梯Bonus 是从冻结快照里读的——只有 finalConfirmed=true 时快照才
     * 是有效数据，如果只要求 confirmed 就放行，公司利润会在别人还没到最终版时被提前锁死成
     * 一个不完整的数字。财务/IT后勤当月还没有工资单记录的，先在这里自动确认一下再判断——
     * 不然如果这个检查在"工资单列表"那次自动确认之前先跑到（两个请求并发时可能发生），会
     * 误判成"还没确认"。当月工资单确认状态只查一次（不按员工循环查），只在"管理层这一行"
     * 触发，请求量本身就是 O(1)，不受员工数量影响。
     */
    private String managementBlockReason(String yearMonth, Long managementEmployeeId, BigDecimal rate) {
        return managementBlockReason(yearMonth, managementEmployeeId, rate,
                employeeRepo.findByIsDeletedFalseOrderByNameAsc());
    }

    /**
     * 2026-08 新增（性能优化，见 MonthDataCache 类注释）：调用方已经查好全体在职员工列表时用
     * 这个重载，跳过重复查询；逻辑跟上面无 cache 的版本完全一样。
     */
    private String managementBlockReason(String yearMonth, Long managementEmployeeId, BigDecimal rate,
                                          List<Employee> allEmployees) {
        // 这批"其他员工"的范围必须跟 listForMonth() 给管理层展示的"手下员工列表"完全一致——
        // 之前这里漏了入职时间过滤（2026-08 修复）：listForMonth() 已经把入职月份晚于
        // yearMonth 的员工过滤掉了（管理层压根看不到、也确认不了这个人这个月的工资单），
        // 但这里独立查了一遍全部在职员工、没有同步做这个过滤，导致一个还没入职到这个月的
        // 新员工，会被误当成"没确认"卡住管理层自己的确认按钮——即便管理层能看到的每一个
        // "手下员工"其实都已经确认过了。
        List<Employee> activeOthers = allEmployees.stream()
                .filter(e -> e.getResignDate() == null)
                .filter(e -> !e.getId().equals(managementEmployeeId))
                .filter(e -> !"管理层".equals(e.getRole()))
                .filter(e -> !isBeforeHireMonth(e, yearMonth))
                .collect(Collectors.toList());
        if (activeOthers.isEmpty()) return null;

        Map<Long, Payslip> payslipByEmployeeId = payslipRepo.findByYearMonthAndIsDeletedFalse(yearMonth).stream()
                .collect(Collectors.toMap(Payslip::getEmployeeId, p -> p, (a, b) -> a));
        for (Employee e : activeOthers) {
            Payslip p = payslipByEmployeeId.get(e.getId());
            if (p == null) {
                p = autoConfirmFixedSalaryIfMissing(e, yearMonth, null, rate);
            }
            if (p == null || !Boolean.TRUE.equals(p.getFinalConfirmed())) {
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
                        .employeeId(employeeId).yearMonth(yearMonth).confirmed(false).finalConfirmed(false).build()));
    }

    /**
     * 财务/IT后勤：当月还没有工资单记录时（existing 为 null）默认直接确认——这两个角色是
     * 固定月薪，奖金设置不常发生，不需要每月手动点一次"确认"；要改的话跟其他角色一样，
     * 先"取消确认"再编辑。confirmedByEmployeeId 传 null 表示是系统自动确认，不是某个具体
     * 管理层账号手动点的。非固定月薪角色、或 existing 已存在（不管确认与否，都不覆盖已有
     * 记录/已有的人为操作状态）时原样返回 existing。
     */
    private Payslip autoConfirmFixedSalaryIfMissing(Employee emp, String yearMonth, Payslip existing, BigDecimal rate) {
        if (existing != null || !FIXED_SALARY_ROLES.contains(emp.getRole())) return existing;
        Payslip p = Payslip.builder().employeeId(emp.getId()).yearMonth(yearMonth).confirmed(false).finalConfirmed(false).build();
        applyConfirmedSnapshot(emp, yearMonth, p, null, rate);
        return payslipRepo.save(p);
    }

    /**
     * 计算当前实时数据、写入确认快照并把 confirmed/finalConfirmed 置 true，不负责 save（调用方
     * 决定何时落库）。只用于没有"下游执行人员工资"依赖的角色（财务/IT后勤/法务），这些角色
     * 点确认即最终版；项目负责人/执行人员/管理层走 confirm() + recomputeFinality()，不调用
     * 这个方法（避免在还没到最终版之前就提前冻结快照——管理层本人也可能是"手下执行人员工资"
     * 的相关项目负责人，2026-08 起纳入同一套判定，不再无条件立即冻结）。
     */
    private void applyConfirmedSnapshot(Employee emp, String yearMonth, Payslip p, Long confirmedByEmployeeId, BigDecimal rate) {
        PayslipDetailResponse live = computeLive(emp, yearMonth, p, rate);
        p.setEmployeeRole(emp.getRole());
        p.setDetailJson(writeSnapshot(live));
        p.setExchangeRateSnapshot(rate);
        p.setConfirmed(true);
        p.setFinalConfirmed(true);
        p.setConfirmedAt(new Date());
        p.setConfirmedByEmployeeId(confirmedByEmployeeId);
    }

    /**
     * 计算当前实时数据、写入"最终版"快照并把 finalConfirmed 置 true，不动 confirmed/confirmedAt/
     * confirmedByEmployeeId（那些是管理层自己确认动作的时间戳，不该被这里的自动结算覆盖），
     * 不负责 save。用于项目负责人/执行人员/管理层这三个角色，在 confirm()/confirmExecutorWages()/
     * unconfirmExecutorWages() 之后由 recomputeFinality() 调用。cache 非 null（仅管理层确认这条
     * 调用链会传）时直接调用 computeManagement() 的缓存重载，跳过 computeLive() 的通用分发，
     * 避免管理层这里重新查一遍本月合作记录/执行人员工资确认状态/全体员工列表（见 MonthDataCache
     * 类注释）；其他角色或 cache 为 null 时行为完全不变，走原来的 computeLive() 分发。
     */
    private void applyFinalSnapshot(Employee emp, String yearMonth, Payslip p, BigDecimal rate, MonthDataCache cache) {
        PayslipDetailResponse live = (cache != null && "管理层".equals(emp.getRole()))
                ? computeManagement(emp, yearMonth, rate, cache)
                : computeLive(emp, yearMonth, p, rate);
        p.setEmployeeRole(emp.getRole());
        p.setDetailJson(writeSnapshot(live));
        p.setExchangeRateSnapshot(rate);
        p.setFinalConfirmed(true);
    }

    /**
     * 项目负责人/执行人员专属：某个员工的"是否到达最终版"重新判定——在这两类会触发这个判定的
     * 动作之后调用：(a) confirm()/unconfirm()（管理层点自己那部分）(b) confirmExecutorWages()/
     * unconfirmExecutorWages()（会同时影响一个执行人员和一个项目负责人两个人的最终版判定，
     * 调用方要对两边都各调一次）。没有 Payslip 记录（管理层压根还没点过确认）时直接跳过。
     *
     * 2026-07-28 起、2026-07-29 修正：已经是最终版的，一旦因为下游（执行人员工资/手下执行
     * 人员工资）确认被撤销而不再满足"最终版"条件，finalConfirmed 总是要降级，但 confirmed
     * （本人自己点过的"确认"动作）要不要连带回退，两个角色不一样，不能一刀切：
     *   - 执行人员：这个月涉及的项目负责人（含管理层自己作为负责人那份）里，只要还有任意一个
     *     确认还在，就不该整体回退成"预计（等待管理层确认）"——那样会让人误以为管理层压根
     *     没确认过，实际上管理层那份确认还在，只是还有"其他项目负责人"没确认，应该展示
     *     "待其他项目负责人确认"。只有当涉及的项目负责人这个月全部都退回未确认（回到彻底
     *     没人确认过的起点）时，才连带把 confirmed 也退回、重新显示"确认"按钮
     *     （2026-07-28 那次 Shawn 反馈的场景就是这种：执行人员只涉及管理层一个负责人，
     *     管理层唯一的确认被撤销后自然就是"一个都没确认"）。
     *   - 项目负责人：confirmed 代表"管理层是否已经确认过这个项目负责人自己的提成"，
     *     这跟"这个项目负责人有没有确认好名下执行人员的工资"是两件独立的事，任何时候都不该
     *     被后者的变化连带回退——2026-07-29 修复：之前误把执行人员那套"连带回退"逻辑无差别
     *     地也用到了项目负责人身上，导致项目负责人自己在"手下执行人员工资"取消确认某个
     *     执行人员后，管理层对这个项目负责人本身提成的确认状态也被错误地清空，主表格错误地
     *     显示"预计（等待管理层确认）"+"确认"按钮，正确的应该是"等待项目负责人确认其执行
     *     人员工资"（confirmed 保持不变，只是 finalConfirmed 降级）。
     *   - 管理层：跟项目负责人同一套道理——管理层本人也可能是某些执行人员的项目负责人（见
     *     "管理层手下执行人员工资"卡片），confirmed 代表"管理层点没点自己整体工资单的确认"，
     *     跟"手下执行人员工资是否都确认完"是两件独立的事，同样任何时候都不连带回退 confirmed，
     *     只降级 finalConfirmed（2026-08 新增；此前管理层被归在"其余角色"里 shouldBeFinal
     *     恒为 true，导致这条回退路径对管理层永远走不到——管理层确认整体工资单后，哪怕后续
     *     在"手下执行人员工资"取消确认某个执行人员，管理层这一行也不会退回非最终版，
     *     /detail 会一直吐旧的冻结快照给"管理层手下执行人员工资"那张卡片，看起来像没生效）。
     */
    private void recomputeFinality(Employee emp, String yearMonth, BigDecimal rate) {
        recomputeFinality(emp, yearMonth, rate, null);
    }

    /**
     * 2026-08 新增 cache 参数（性能优化，见 MonthDataCache 类注释）：非 null 时只有管理层
     * 确认这条调用链会传入，用来跳过下面 allOwnExecutorWagesConfirmed()/applyFinalSnapshot()
     * 对本月合作记录/执行人员工资确认状态的重复查询；其他调用方（confirmExecutorWages()/
     * unconfirmExecutorWages()，处理的是项目负责人/执行人员分支）继续传 null，行为完全不变。
     */
    private void recomputeFinality(Employee emp, String yearMonth, BigDecimal rate, MonthDataCache cache) {
        Payslip p = payslipRepo.findByEmployeeIdAndYearMonthAndIsDeletedFalse(emp.getId(), yearMonth).orElse(null);
        if (p == null) return;
        if (!Boolean.TRUE.equals(p.getConfirmed())) {
            if (Boolean.TRUE.equals(p.getFinalConfirmed())) {
                p.setFinalConfirmed(false);
                payslipRepo.save(p);
            }
            return;
        }
        boolean shouldBeFinal;
        boolean resetOwnConfirmOnRollback = false;
        if ("执行人员".equals(emp.getRole())) {
            ExecutorPmConfirmStatus status = resolveExecutorPmConfirmStatus(emp.getId(), yearMonth);
            shouldBeFinal = status.isAllConfirmed();
            resetOwnConfirmOnRollback = !status.isAnyConfirmed();
        } else if ("项目负责人".equals(emp.getRole())) {
            shouldBeFinal = allOwnExecutorWagesConfirmed(emp.getId(), yearMonth);
            // PM 的 confirmed 从不级联回退：PM 自己名下执行人员工资确认没到位，不会拦住管理层
            // confirmRow(PM) 这个按钮（toRowResponse 里 PM 的 blockedReason 恒为 null），
            // 两件事从设计上就是互不设限的，没有"前置条件"关系需要撤销。
        } else if ("管理层".equals(emp.getRole())) {
            shouldBeFinal = cache != null
                    ? allOwnExecutorWagesConfirmed(emp.getId(), cache.orders, cache.wageConfirmations)
                    : allOwnExecutorWagesConfirmed(emp.getId(), yearMonth);
            // 2026-08 修复：管理层不能照抄 PM 的"从不回退"——管理层自己"手下执行人员工资"没
            // 全部确认时，那些执行人员就到不了 finalConfirmed，而 managementBlockReason() 要求
            // "所有其他员工都 finalConfirmed" 才放行管理层自己的"确认"按钮，所以
            // allOwnExecutorWagesConfirmed==true 其实是管理层自己"确认"按钮当初能点亮的一个
            // （间接）前置条件，跟 PM 的情况正好相反。这个前置条件一旦被打破（比如又跑去"手下
            // 执行人员工资"取消确认了某一个），管理层自己的 confirmed 也要跟着回退，界面才会
            // 正确显示"确认"（禁用+提示"请先确认其他员工的工资单"），而不是继续显示一个其实已经
            // 不再成立的"取消确认"。这里没有执行人员那种"多个负责人各自confirm、只要还有一个在
            // 就不整体回退"的部分抵免场景——allOwnExecutorWagesConfirmed 就是管理层自己一个人
            // 的单一判断，所以无条件回退，不用像执行人员那样再判一次 anyConfirmed。
            resetOwnConfirmOnRollback = true;
        } else {
            shouldBeFinal = true;
        }
        if (shouldBeFinal && !Boolean.TRUE.equals(p.getFinalConfirmed())) {
            applyFinalSnapshot(emp, yearMonth, p, rate, cache);
            payslipRepo.save(p);
        } else if (!shouldBeFinal && Boolean.TRUE.equals(p.getFinalConfirmed())) {
            p.setFinalConfirmed(false);
            if (resetOwnConfirmOnRollback) p.setConfirmed(false);
            payslipRepo.save(p);
        }
    }

    /**
     * 这个项目负责人当月名下所有执行人员的"手下执行人员工资"确认是否都已完成（
     * ExecutorWageConfirmation(managerId=这个PM, executorId=*, yearMonth)）。名下这个月压根
     * 没有执行人员记录时，视为"没有下游义务"，返回 true。
     */
    private boolean allOwnExecutorWagesConfirmed(Long managerId, String yearMonth) {
        return allOwnExecutorWagesConfirmed(managerId,
                excludeDamaged(trackingRepo.findByPublishMonth(yearMonth)), fetchWageConfirmations(yearMonth));
    }

    /**
     * 2026-08 新增（性能优化，见 MonthDataCache 类注释）：调用方已经查好本月合作记录/执行人员
     * 工资确认状态时用这个重载，跳过重复查询；逻辑跟上面无 cache 的版本完全一样。
     */
    private boolean allOwnExecutorWagesConfirmed(Long managerId, List<CollaborationTracking> orders,
                                                   Map<Long, Map<Long, ExecutorWageConfirmation>> wageConfirmations) {
        Set<Long> execIds = new LinkedHashSet<>();
        for (CollaborationTracking o : orders) {
            if (managerId.equals(o.getProjectManagerId()) && o.getExecutorId() != null) {
                execIds.add(o.getExecutorId());
            }
        }
        if (execIds.isEmpty()) return true;
        Map<Long, ExecutorWageConfirmation> confirmationsForThisManager =
                wageConfirmations.getOrDefault(managerId, Collections.emptyMap());
        for (Long execId : execIds) {
            ExecutorWageConfirmation c = confirmationsForThisManager.get(execId);
            if (c == null || !Boolean.TRUE.equals(c.getConfirmed())) return false;
        }
        return true;
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
