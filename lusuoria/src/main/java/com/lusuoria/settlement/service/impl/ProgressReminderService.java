package com.lusuoria.settlement.service.impl;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.config.InfluencerTeamCache;
import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.entity.InfluencerRequirement;
import com.lusuoria.settlement.entity.InfluencerTeam;
import com.lusuoria.settlement.entity.ProgressReminder;
import com.lusuoria.settlement.entity.ProgressReminderDetail;
import com.lusuoria.settlement.entity.ReminderAcknowledgement;
import com.lusuoria.settlement.entity.SysUser;
import com.lusuoria.settlement.enums.CollaborationProgress;
import com.lusuoria.settlement.enums.InfluencerPaymentProgress;
import com.lusuoria.settlement.enums.OverdueUrgency;
import com.lusuoria.settlement.enums.PaymentCycleType;
import com.lusuoria.settlement.enums.ReminderCategory;
import com.lusuoria.settlement.enums.ReminderUrgency;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import com.lusuoria.settlement.repository.InfluencerRepository;
import com.lusuoria.settlement.repository.InfluencerRequirementRepository;
import com.lusuoria.settlement.repository.ProgressReminderDetailRepository;
import com.lusuoria.settlement.repository.ProgressReminderRepository;
import com.lusuoria.settlement.repository.ReminderAcknowledgementRepository;
import com.lusuoria.settlement.repository.SysUserRepository;
import com.lusuoria.settlement.util.EmployeeRoleUtil;
import com.lusuoria.settlement.util.RoleUtil;
import com.lusuoria.settlement.util.WorkdayUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 进度提醒 - 跑批与查询逻辑（2026-07 新增）。
 *
 * 每天北京时间凌晨3点跑批一次（也可以由"管理层"在页面上点"结款后更新提示内容"手动触发，
 * 见 runBatch()）：先清空 progress_reminders / progress_reminder_details 里的全部旧数据，
 * 再重新算一遍插入，所以这两张表任何时刻都只保存"最新一次跑批"的结果，不会跨天累积。
 *
 * 两类提醒（ReminderCategory）：
 *   COLLAB_PAYMENT_DUE          - 品牌方付款周期=按红人成本阈值分档：把命中的红人合作跟踪记录
 *                                  按离最迟结款日的天数分成三档，每档生成一条汇总（笔数）+
 *                                  一批 ProgressReminderDetail 明细快照。
 *   BRAND_MONTH_END_PAYMENT_DUE - 品牌方付款周期=月底对账日后N天结款：按品牌方+结算月份
 *                                  生成一条消息，本身就是完整文案，没有下钻明细。
 *
 * 受众目前只有"管理层"这一个员工角色（Employee.role，注意不是 SysUser.role——判断谁能看到
 * 用的是登录账号关联的员工角色，跟登录账号本身是 ADMIN 还是 STAFF 无关）。
 */
@Service
public class ProgressReminderService {

    private static final Logger log = LoggerFactory.getLogger(ProgressReminderService.class);

    private static final String MANAGEMENT_ROLE = "管理层";
    private static final String FINANCE_ROLE = "财务";
    private static final int[] CHECKPOINT_HOURS = {12, 18, 22};
    /** 品牌方月结回溯月份数的技术兜底（不是业务规则）：纯粹防止极端脏数据导致死循环 */
    private static final int MONTH_END_LOOKBACK_SAFETY_CAP = 36;

    /** 2026-07 新增：PM_EXECUTOR_PROGRESS_STALL 里"3个工作日未流转"提醒的状态集合
     * （INFLUENCER_ORDERED 单独按5个工作日，见 stallThreshold()） */
    private static final Set<CollaborationProgress> PM_EXECUTOR_3DAY_STATES = EnumSet.of(
            CollaborationProgress.PENDING_CLIENT_BRIEF, CollaborationProgress.CONTRACT_SENT,
            CollaborationProgress.SHOOTING_GUIDE_SENT, CollaborationProgress.PENDING_DRAFT,
            CollaborationProgress.PENDING_REVISION, CollaborationProgress.PENDING_PUBLISH);

    /** "结款后更新提示内容"手动触发范围 */
    private static final Set<ReminderCategory> PAYMENT_CATEGORIES = EnumSet.of(
            ReminderCategory.COLLAB_PAYMENT_DUE, ReminderCategory.BRAND_MONTH_END_PAYMENT_DUE);
    /** "项目流转后更新提示内容"手动触发范围（2026-07 新增） */
    private static final Set<ReminderCategory> PROJECT_FLOW_CATEGORIES = EnumSet.of(
            ReminderCategory.PM_EXECUTOR_PROGRESS_STALL, ReminderCategory.FINANCE_PROGRESS_STALL,
            ReminderCategory.REQUIREMENT_INVOICE_OVERDUE);

    @Autowired private ProgressReminderRepository reminderRepo;
    @Autowired private ProgressReminderDetailRepository detailRepo;
    @Autowired private CollaborationTrackingRepository trackingRepo;
    @Autowired private InfluencerRepository influencerRepo;
    @Autowired private InfluencerRequirementRepository requirementRepo;
    @Autowired private ReminderAcknowledgementRepository ackRepo;
    @Autowired private BrandCache brandCache;
    @Autowired private InfluencerTeamCache teamCache;
    @Autowired private EmployeeCache employeeCache;
    @Autowired private SysUserRepository sysUserRepo;
    @Autowired private EmployeeRoleUtil employeeRoleUtil;

    // ============ 跑批 ============

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void runBatch() {
        try {
            detailRepo.deleteAllInBatch();
            reminderRepo.deleteAllInBatch();

            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            Date batchDate = toDate(today);

            runCollabPaymentDue(today, batchDate);
            runBrandMonthEndPaymentDue(today, batchDate);
            runPmExecutorProgressStall(today, batchDate);
            runFinanceProgressStall(today, batchDate);
            runRequirementInvoiceOverdue(today, batchDate);
        } catch (RuntimeException e) {
            // GlobalExceptionHandler 只会把异常包成 400 返回给前端，不会打印堆栈，
            // 排查问题时看不到具体原因，这里手动记一下，方便去 Render 日志里查
            log.error("进度提醒跑批失败：{}", e.toString(), e);
            throw e;
        }
    }

    /**
     * "结款后更新提示内容"手动触发（2026-07 起改成只重算 COLLAB_PAYMENT_DUE/
     * BRAND_MONTH_END_PAYMENT_DUE 这两类，不影响 PM_EXECUTOR_PROGRESS_STALL/
     * FINANCE_PROGRESS_STALL/REQUIREMENT_INVOICE_OVERDUE 当天已经算好的数据）。
     */
    @Transactional
    public void runPaymentBatches() {
        try {
            clearCategories(PAYMENT_CATEGORIES);
            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            Date batchDate = toDate(today);
            runCollabPaymentDue(today, batchDate);
            runBrandMonthEndPaymentDue(today, batchDate);
        } catch (RuntimeException e) {
            log.error("进度提醒（结款类）手动重算失败：{}", e.toString(), e);
            throw e;
        }
    }

    /**
     * "项目流转后更新提示内容"手动触发（2026-07 新增）：只重算 PM_EXECUTOR_PROGRESS_STALL/
     * FINANCE_PROGRESS_STALL/REQUIREMENT_INVOICE_OVERDUE 这3类，不影响两类"临近结款"提醒
     * 当天已经算好的数据。
     */
    @Transactional
    public void runProjectFlowBatches() {
        try {
            clearCategories(PROJECT_FLOW_CATEGORIES);
            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            Date batchDate = toDate(today);
            runPmExecutorProgressStall(today, batchDate);
            runFinanceProgressStall(today, batchDate);
            runRequirementInvoiceOverdue(today, batchDate);
        } catch (RuntimeException e) {
            log.error("进度提醒（项目流转类）手动重算失败：{}", e.toString(), e);
            throw e;
        }
    }

    /** 清空指定几个类别当前的 ProgressReminder + 对应明细行，不动其它类别 */
    private void clearCategories(Set<ReminderCategory> categories) {
        List<Long> reminderIds = reminderRepo.findByCategoryIn(categories).stream()
                .map(ProgressReminder::getId).collect(Collectors.toList());
        if (!reminderIds.isEmpty()) {
            detailRepo.deleteByReminderIdIn(reminderIds);
        }
        reminderRepo.deleteByCategoryIn(categories);
    }

    /** Part A：品牌方付款周期=按红人成本阈值分档 */
    private void runCollabPaymentDue(LocalDate today, Date batchDate) {
        Map<Long, Brand> qualifyingBrands = new HashMap<>();
        for (Brand b : brandCache.getAll()) {
            if (b.getPaymentCycleType() == PaymentCycleType.COST_THRESHOLD
                    && b.getCostThresholdAmount() != null
                    && b.getDaysWithinThreshold() != null
                    && b.getDaysAboveThreshold() != null) {
                qualifyingBrands.put(b.getId(), b);
            }
        }
        if (qualifyingBrands.isEmpty()) return;

        List<CollaborationTracking> candidates = new ArrayList<>();
        for (CollaborationTracking t : trackingRepo.findByIsDeletedFalse()) {
            if (t.getBrandId() == null || !qualifyingBrands.containsKey(t.getBrandId())) continue;
            if (t.getPublishDate() == null) continue;
            if (t.getInfluencerPaymentProgress() != null && t.getInfluencerPaymentProgress().isIncludedInBatch()) continue;
            if (t.getInfluencerCost() == null) continue; // 没有成本没法判断走哪个天数档位，跳过
            candidates.add(t);
        }
        if (candidates.isEmpty()) return;

        // 批量查红人账号名，避免逐条查库
        Set<Long> influencerIds = new HashSet<>();
        for (CollaborationTracking t : candidates) {
            if (t.getInfluencerId() != null) influencerIds.add(t.getInfluencerId());
        }
        Map<Long, String> accountNameById = new HashMap<>();
        if (!influencerIds.isEmpty()) {
            for (Influencer inf : influencerRepo.findAllById(influencerIds)) {
                accountNameById.put(inf.getId(), inf.getAccountName());
            }
        }

        Map<ReminderUrgency, List<ProgressReminderDetail>> byUrgency = new EnumMap<>(ReminderUrgency.class);
        for (CollaborationTracking t : candidates) {
            Brand brand = qualifyingBrands.get(t.getBrandId());
            int cycleDays = t.getInfluencerCost().compareTo(brand.getCostThresholdAmount()) <= 0
                    ? brand.getDaysWithinThreshold() : brand.getDaysAboveThreshold();

            LocalDate publishLocalDate = toLocalDate(t.getPublishDate());
            LocalDate deadlineLocalDate = publishLocalDate.plusDays(cycleDays);
            long daysRemaining = ChronoUnit.DAYS.between(today, deadlineLocalDate);
            ReminderUrgency urgency = ReminderUrgency.fromDaysRemaining(daysRemaining);
            if (urgency == null) continue; // 超过7天，暂时不用提醒

            ProgressReminderDetail detail = new ProgressReminderDetail();
            detail.setIsDeleted(false);
            detail.setTrackingId(t.getId());
            detail.setInternalProjectNo(t.getInternalProjectNo());
            detail.setBrandName(brand.getName());
            InfluencerTeam team = t.getTeamId() != null ? teamCache.findById(t.getTeamId()) : null;
            detail.setTeamName(team != null ? team.getName() : null);
            detail.setAccountName(accountNameById.get(t.getInfluencerId()));
            detail.setDemandContent(t.getDemandContent());
            detail.setInfluencerCost(t.getInfluencerCost());
            detail.setProgressLabel(t.getProgress() != null ? t.getProgress().getLabel() : null);
            detail.setPublishDate(t.getPublishDate());
            detail.setCycleDays(cycleDays);
            detail.setDeadlineDate(toDate(deadlineLocalDate));

            byUrgency.computeIfAbsent(urgency, k -> new ArrayList<>()).add(detail);
        }

        for (ReminderUrgency urgency : ReminderUrgency.values()) {
            List<ProgressReminderDetail> details = byUrgency.get(urgency);
            if (details == null || details.isEmpty()) continue;

            ProgressReminder reminder = new ProgressReminder();
            reminder.setIsDeleted(false);
            reminder.setBatchDate(batchDate);
            reminder.setCategory(ReminderCategory.COLLAB_PAYMENT_DUE);
            reminder.setUrgency(urgency);
            reminder.setAudienceEmployeeRole(MANAGEMENT_ROLE);
            reminder.setCount(details.size());
            reminder.setTitle(urgency.getLabel() + "：" + details.size() + "笔临近结款的红人合作跟踪记录");
            reminder = reminderRepo.save(reminder);

            for (ProgressReminderDetail d : details) d.setReminderId(reminder.getId());
            detailRepo.saveAll(details);
        }
    }

    /**
     * Part B：品牌方付款周期=月底对账日后N天结款。
     * 从"最近一个已完整结束的月份"往前逐月回溯，每个月单独判断是否落在提醒区间内
     * （已超期 / 1-3天 / 3-7天），一旦某个月完全没有红人成本数据就停止往前找
     * （视为再往前也不会有需要结算的数据）。没有"标记已处理"的概念，只要没打款、
     * 且当月有成本数据，就会一直提醒下去——这是产品明确认可的行为：这张表每天
     * 跑批整体重建，不会导致提醒堆积。
     */
    private void runBrandMonthEndPaymentDue(LocalDate today, Date batchDate) {
        List<Brand> qualifyingBrands = new ArrayList<>();
        for (Brand b : brandCache.getAll()) {
            if (b.getPaymentCycleType() == PaymentCycleType.MONTH_END && b.getDaysAfterMonthEnd() != null) {
                qualifyingBrands.add(b);
            }
        }
        if (qualifyingBrands.isEmpty()) return;

        DateTimeFormatter monthFmt = DateTimeFormatter.ofPattern("yyyyMM");
        List<ProgressReminder> toSave = new ArrayList<>();

        for (Brand brand : qualifyingBrands) {
            YearMonth candidate = YearMonth.from(today).minusMonths(1); // 最近一个已完整结束的月份
            int guard = 0;
            while (guard < MONTH_END_LOOKBACK_SAFETY_CAP) {
                String monthStr = candidate.format(monthFmt);
                BigDecimal totalCost = BigDecimal.ZERO;
                for (CollaborationTracking t : trackingRepo.findByPublishMonth(monthStr)) {
                    if (brand.getId().equals(t.getBrandId()) && t.getInfluencerCost() != null) {
                        totalCost = totalCost.add(t.getInfluencerCost());
                    }
                }
                if (totalCost.compareTo(BigDecimal.ZERO) == 0) break; // 再往前没有数据了，停止回溯

                LocalDate monthEnd = candidate.atEndOfMonth();
                LocalDate deadlineLocalDate = monthEnd.plusDays(brand.getDaysAfterMonthEnd());
                long daysRemaining = ChronoUnit.DAYS.between(today, deadlineLocalDate);
                ReminderUrgency urgency = ReminderUrgency.fromDaysRemaining(daysRemaining);
                if (urgency != null) {
                    BigDecimal roundedCost = totalCost.setScale(2, RoundingMode.HALF_UP);
                    ProgressReminder reminder = new ProgressReminder();
                    reminder.setIsDeleted(false);
                    reminder.setBatchDate(batchDate);
                    reminder.setCategory(ReminderCategory.BRAND_MONTH_END_PAYMENT_DUE);
                    reminder.setUrgency(urgency);
                    reminder.setAudienceEmployeeRole(MANAGEMENT_ROLE);
                    reminder.setBrandId(brand.getId());
                    reminder.setBrandName(brand.getName());
                    reminder.setSettlementMonth(monthStr);
                    reminder.setTotalCostAmount(roundedCost);
                    reminder.setDeadlineDate(toDate(deadlineLocalDate));
                    reminder.setDaysRemaining((int) daysRemaining);
                    reminder.setTitle(buildMonthEndTitle(
                            brand.getName(), candidate.getMonthValue(), roundedCost, deadlineLocalDate, daysRemaining));
                    toSave.add(reminder);
                }
                candidate = candidate.minusMonths(1);
                guard++;
            }
        }
        reminderRepo.saveAll(toSave);
    }

    private String buildMonthEndTitle(String brandName, int monthValue, BigDecimal totalCost,
                                       LocalDate deadline, long daysRemaining) {
        String deadlineStr = deadline.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String remainingPart = daysRemaining > 0
                ? "还剩下" + daysRemaining + "天"
                : "已超期" + Math.abs(daysRemaining) + "天";
        return "距离" + brandName + "，" + monthValue + "月底对账后（金额共"
                + totalCost.toPlainString() + "美元）最迟结款日（" + deadlineStr + "），" + remainingPart;
    }

    /**
     * Part C（2026-07 新增）：项目负责人/执行人员视角，视频项目进度长时间未流转。
     * "红人已下单"阈值5工作日，其余6个中间状态阈值3工作日；已发布未结算及以后的终态、
     * 或折损，不算滞留。同一条记录如果负责人和执行人员是不同人，两边各生成一条（按各自的
     * 身份分开归类，不合并）；没有 progressChangedAt（老数据，从没触发过一次这个字段的
     * 维护逻辑）的记录直接跳过，避免上线当天把所有历史记录都误判成"长期未流转"。
     */
    private void runPmExecutorProgressStall(LocalDate today, Date batchDate) {
        List<CollaborationTracking> all = trackingRepo.findByIsDeletedFalse();
        Map<Long, String> accountNameById = buildAccountNameIndex(all);

        Map<String, List<ProgressReminderDetail>> byKey = new LinkedHashMap<>();
        Map<String, Long> employeeIdByKey = new HashMap<>();
        Map<String, String> roleLabelByKey = new HashMap<>();
        Map<String, OverdueUrgency> urgencyByKey = new HashMap<>();

        for (CollaborationTracking t : all) {
            Integer threshold = stallThreshold(t.getProgress());
            if (threshold == null || t.getProgressChangedAt() == null) continue;
            int workdays = WorkdayUtil.countWeekdaysInclusive(toLocalDate(t.getProgressChangedAt()), today);
            int overdueDays = workdays - threshold;
            OverdueUrgency urgency = OverdueUrgency.fromOverdueDays(overdueDays);
            if (urgency == null) continue;

            if (t.getProjectManagerId() != null) {
                addToStallBucket(byKey, employeeIdByKey, roleLabelByKey, urgencyByKey,
                        t.getProjectManagerId(), "项目负责人", urgency,
                        buildStallDetail(t, accountNameById, overdueDays, threshold));
            }
            if (t.getExecutorId() != null) {
                addToStallBucket(byKey, employeeIdByKey, roleLabelByKey, urgencyByKey,
                        t.getExecutorId(), "执行人员", urgency,
                        buildStallDetail(t, accountNameById, overdueDays, threshold));
            }
        }

        for (Map.Entry<String, List<ProgressReminderDetail>> entry : byKey.entrySet()) {
            saveStallReminder(batchDate, ReminderCategory.PM_EXECUTOR_PROGRESS_STALL,
                    employeeIdByKey.get(entry.getKey()), roleLabelByKey.get(entry.getKey()),
                    urgencyByKey.get(entry.getKey()), entry.getValue(),
                    "笔视频项目进度长时间未流转");
        }
    }

    /** INFLUENCER_ORDERED（红人已下单）=5工作日；6个中间状态=3工作日；其余（含终态）不生成提醒 */
    private Integer stallThreshold(CollaborationProgress progress) {
        if (progress == CollaborationProgress.INFLUENCER_ORDERED) return 5;
        if (progress != null && PM_EXECUTOR_3DAY_STATES.contains(progress)) return 3;
        return null;
    }

    /**
     * Part D（2026-07 新增）：财务视角，"已发布（未结算）"/"已加入客户未结算列表"长时间没到
     * "客户已结算"，阈值统一14工作日。目前只有1个财务，按角色整体可见（audienceEmployeeRole=
     * "财务"），不做按人定向。
     */
    private void runFinanceProgressStall(LocalDate today, Date batchDate) {
        List<CollaborationTracking> all = trackingRepo.findByIsDeletedFalse();
        Map<Long, String> accountNameById = buildAccountNameIndex(all);

        Map<OverdueUrgency, List<ProgressReminderDetail>> byUrgency = new EnumMap<>(OverdueUrgency.class);
        for (CollaborationTracking t : all) {
            CollaborationProgress p = t.getProgress();
            boolean isFinanceStage = p == CollaborationProgress.PUBLISHED_UNSETTLED
                    || p == CollaborationProgress.JOINED_CLIENT_UNSETTLED_LIST;
            if (!isFinanceStage || t.getProgressChangedAt() == null) continue;
            int workdays = WorkdayUtil.countWeekdaysInclusive(toLocalDate(t.getProgressChangedAt()), today);
            int overdueDays = workdays - 14;
            OverdueUrgency urgency = OverdueUrgency.fromOverdueDays(overdueDays);
            if (urgency == null) continue;
            byUrgency.computeIfAbsent(urgency, k -> new ArrayList<>())
                    .add(buildStallDetail(t, accountNameById, overdueDays, 14));
        }

        for (OverdueUrgency urgency : OverdueUrgency.values()) {
            List<ProgressReminderDetail> details = byUrgency.get(urgency);
            if (details == null || details.isEmpty()) continue;
            saveStallReminder(batchDate, ReminderCategory.FINANCE_PROGRESS_STALL,
                    null, FINANCE_ROLE, urgency, details, "笔视频项目进度长时间未到客户已结算");
        }
    }

    /**
     * Part E（2026-07 新增）：需求完成进度100%后长时间未上传Invoice。只提醒"涉及invoice上传"
     * 的品牌方；阈值5工作日，基准时间是 InfluencerRequirement.completedAt。按需求关联的所有
     * 合作跟踪记录，取项目负责人/执行人员的去重集合，每个员工各生成一条。
     */
    private void runRequirementInvoiceOverdue(LocalDate today, Date batchDate) {
        List<InfluencerRequirement> candidates = requirementRepo.findByIsDeletedFalseAndCompletedAtIsNotNullAndInvoiceLinkIsNull();
        if (candidates.isEmpty()) return;

        Map<String, List<ProgressReminderDetail>> byKey = new LinkedHashMap<>();
        Map<String, Long> employeeIdByKey = new HashMap<>();
        Map<String, String> roleLabelByKey = new HashMap<>();
        Map<String, OverdueUrgency> urgencyByKey = new HashMap<>();

        for (InfluencerRequirement r : candidates) {
            Brand brand = r.getBrandId() != null ? brandCache.findById(r.getBrandId()) : null;
            if (brand != null && !brand.requiresInvoiceUpload()) continue; // 不涉及invoice的品牌方不提醒
            int workdays = WorkdayUtil.countWeekdaysInclusive(toLocalDate(r.getCompletedAt()), today);
            int overdueDays = workdays - 5;
            OverdueUrgency urgency = OverdueUrgency.fromOverdueDays(overdueDays);
            if (urgency == null) continue;

            List<CollaborationTracking> linked =
                    trackingRepo.findByInternalRequirementNoAndIsDeletedFalse(r.getInternalRequirementNo());
            if (linked.isEmpty()) continue; // 理论上不会发生（completedAt本身就是靠这些记录算出来的），防御性跳过
            Long placeholderTrackingId = linked.get(0).getId();

            Map<Long, String> roleLabelByEmployee = new LinkedHashMap<>();
            for (CollaborationTracking t : linked) {
                if (t.getProjectManagerId() != null) roleLabelByEmployee.putIfAbsent(t.getProjectManagerId(), "项目负责人");
                if (t.getExecutorId() != null) roleLabelByEmployee.putIfAbsent(t.getExecutorId(), "执行人员");
            }
            for (Map.Entry<Long, String> ownerEntry : roleLabelByEmployee.entrySet()) {
                addToStallBucket(byKey, employeeIdByKey, roleLabelByKey, urgencyByKey,
                        ownerEntry.getKey(), ownerEntry.getValue(), urgency,
                        buildRequirementOverdueDetail(r, brand, placeholderTrackingId, overdueDays));
            }
        }

        for (Map.Entry<String, List<ProgressReminderDetail>> entry : byKey.entrySet()) {
            saveStallReminder(batchDate, ReminderCategory.REQUIREMENT_INVOICE_OVERDUE,
                    employeeIdByKey.get(entry.getKey()), roleLabelByKey.get(entry.getKey()),
                    urgencyByKey.get(entry.getKey()), entry.getValue(),
                    "个需求完成后长时间未上传Invoice");
        }
    }

    // ---- Part C/D/E 共用的小工具 ----

    private Map<Long, String> buildAccountNameIndex(List<CollaborationTracking> list) {
        Set<Long> influencerIds = new HashSet<>();
        for (CollaborationTracking t : list) if (t.getInfluencerId() != null) influencerIds.add(t.getInfluencerId());
        Map<Long, String> result = new HashMap<>();
        if (!influencerIds.isEmpty()) {
            for (Influencer inf : influencerRepo.findAllById(influencerIds)) result.put(inf.getId(), inf.getAccountName());
        }
        return result;
    }

    private ProgressReminderDetail buildStallDetail(CollaborationTracking t, Map<Long, String> accountNameById,
                                                       int overdueDays, int thresholdWorkdays) {
        ProgressReminderDetail detail = new ProgressReminderDetail();
        detail.setIsDeleted(false);
        detail.setTrackingId(t.getId());
        detail.setInternalProjectNo(t.getInternalProjectNo());
        Brand brand = t.getBrandId() != null ? brandCache.findById(t.getBrandId()) : null;
        detail.setBrandName(brand != null ? brand.getName() : null);
        InfluencerTeam team = t.getTeamId() != null ? teamCache.findById(t.getTeamId()) : null;
        detail.setTeamName(team != null ? team.getName() : null);
        detail.setAccountName(accountNameById.get(t.getInfluencerId()));
        detail.setDemandContent(t.getDemandContent());
        detail.setInfluencerCost(t.getInfluencerCost());
        detail.setProgressLabel(t.getProgress() != null ? t.getProgress().getLabel() : null);
        detail.setPublishDate(t.getPublishDate());
        // cycleDays/deadlineDate 是历史 NOT NULL 列，新类别没有"结款周期"这个概念，
        // 借用来存"阈值工作日数"/"进度最近变化的日期"，真正展示用的是下面的 overdueDays
        detail.setCycleDays(thresholdWorkdays);
        detail.setDeadlineDate(toDate(toLocalDate(t.getProgressChangedAt())));
        detail.setOverdueDays(overdueDays);
        return detail;
    }

    private ProgressReminderDetail buildRequirementOverdueDetail(InfluencerRequirement r, Brand brand,
                                                                    Long placeholderTrackingId, int overdueDays) {
        ProgressReminderDetail detail = new ProgressReminderDetail();
        detail.setIsDeleted(false);
        // trackingId 是历史 NOT NULL 列，这一类没有单一对应的合作跟踪记录，随便挑该需求下
        // 一条关联记录的 id 占位；"查看详情"实际跳转按下面的 requirementId/internalRequirementNo
        detail.setTrackingId(placeholderTrackingId);
        detail.setBrandName(brand != null ? brand.getName() : null);
        InfluencerTeam team = r.getTeamId() != null ? teamCache.findById(r.getTeamId()) : null;
        detail.setTeamName(team != null ? team.getName() : null);
        Influencer inf = r.getInfluencerId() != null ? influencerRepo.findById(r.getInfluencerId()).orElse(null) : null;
        detail.setAccountName(inf != null ? inf.getAccountName() : null);
        detail.setCycleDays(5);
        detail.setDeadlineDate(toDate(toLocalDate(r.getCompletedAt())));
        detail.setOverdueDays(overdueDays);
        detail.setRequirementId(r.getId());
        detail.setInternalRequirementNo(r.getInternalRequirementNo());
        return detail;
    }

    private void addToStallBucket(Map<String, List<ProgressReminderDetail>> byKey, Map<String, Long> employeeIdByKey,
                                    Map<String, String> roleLabelByKey, Map<String, OverdueUrgency> urgencyByKey,
                                    Long employeeId, String roleLabel, OverdueUrgency urgency, ProgressReminderDetail detail) {
        String key = employeeId + "|" + roleLabel + "|" + urgency.name();
        byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(detail);
        employeeIdByKey.put(key, employeeId);
        roleLabelByKey.put(key, roleLabel);
        urgencyByKey.put(key, urgency);
    }

    private void saveStallReminder(Date batchDate, ReminderCategory category, Long audienceEmployeeId,
                                     String audienceRoleLabel, OverdueUrgency urgency,
                                     List<ProgressReminderDetail> details, String titleSuffix) {
        ProgressReminder reminder = new ProgressReminder();
        reminder.setIsDeleted(false);
        reminder.setBatchDate(batchDate);
        reminder.setCategory(category);
        // urgency 是历史 NOT NULL 列，新类别没有实际展示意义，占位填 OVERDUE，真正的颜色判断
        // 前端按 overdueUrgency 读
        reminder.setUrgency(ReminderUrgency.OVERDUE);
        reminder.setOverdueUrgency(urgency);
        reminder.setAudienceEmployeeRole(audienceRoleLabel);
        reminder.setAudienceEmployeeId(audienceEmployeeId);
        reminder.setCount(details.size());
        String prefix = audienceEmployeeId != null ? "作为" + audienceRoleLabel + "：" : urgency.getLabel() + "：";
        reminder.setTitle(prefix + details.size() + titleSuffix);
        reminder = reminderRepo.save(reminder);
        for (ProgressReminderDetail d : details) d.setReminderId(reminder.getId());
        detailRepo.saveAll(details);
    }

    // ============ 查询（供 Controller 用） ============

    /**
     * 当前登录账号能看到的提醒列表，按 category、urgency（老两类走 ReminderUrgency，
     * 新三类走 OverdueUrgency）排序（2026-07 泛化，不再是"非管理层直接返回空列表"）：
     *   - ADMIN 或 员工角色=管理层 → 全部提醒（老两类 + 新三类，全部，不按人过滤）——
     *     保持管理层原有可见范围不变，只是新3类现在也对他们可见。
     *   - 员工角色=财务 → 额外看到 FINANCE_PROGRESS_STALL。
     *   - 有 employeeId（任何角色）→ 额外看到 audienceEmployeeId=自己 的行
     *     （覆盖 PM_EXECUTOR_PROGRESS_STALL/REQUIREMENT_INVOICE_OVERDUE）。
     *   - 都不满足（没关联员工，或访客）→ 空列表。
     */
    @Transactional(readOnly = true)
    public List<ProgressReminder> listForCurrentUser() {
        List<ProgressReminder> list = resolveVisibleReminders();
        list.sort(Comparator
                .comparing((ProgressReminder r) -> r.getCategory().ordinal())
                .thenComparing(this::urgencyOrdinal));
        return list;
    }

    private int urgencyOrdinal(ProgressReminder r) {
        if (r.getOverdueUrgency() != null) return r.getOverdueUrgency().ordinal();
        return r.getUrgency() != null ? r.getUrgency().ordinal() : 0;
    }

    private List<ProgressReminder> resolveVisibleReminders() {
        if (hasFullReminderVisibility()) {
            return new ArrayList<>(reminderRepo.findAllByIsDeletedFalse());
        }
        List<ProgressReminder> result = new ArrayList<>();
        String employeeRole = employeeRoleUtil.getCurrentEmployeeRole();
        if (FINANCE_ROLE.equals(employeeRole)) {
            result.addAll(reminderRepo.findByAudienceEmployeeRole(FINANCE_ROLE));
        }
        Long employeeId = employeeRoleUtil.getCurrentEmployeeId();
        if (employeeId != null) {
            result.addAll(reminderRepo.findByAudienceEmployeeId(employeeId));
        }
        return result;
    }

    /** ADMIN 或 员工角色=管理层：能看到全部提醒（老两类 + 新三类），不按具体员工/角色过滤 */
    private boolean hasFullReminderVisibility() {
        return RoleUtil.isAdmin() || isCurrentUserManagement();
    }

    /** 某条提醒的明细，按离最迟结款日的接近程度/超期天数排序（两种排序方向巧合共用同一列） */
    @Transactional(readOnly = true)
    public List<ProgressReminderDetail> listDetails(Long reminderId) {
        ProgressReminder reminder = reminderRepo.findById(reminderId).orElse(null);
        if (reminder == null || !canViewReminder(reminder)) {
            return Collections.emptyList();
        }
        List<ProgressReminderDetail> details = detailRepo.findByReminderIdOrderByDeadlineDateAsc(reminderId);
        if (ACKNOWLEDGEABLE_CATEGORIES.contains(reminder.getCategory()) && !hasFullReminderVisibility()) {
            details = filterAcknowledged(reminder.getCategory(), details);
        }
        return details;
    }

    // ============ 标记已处理（2026-07 新增） ============

    private static final Set<ReminderCategory> ACKNOWLEDGEABLE_CATEGORIES = PROJECT_FLOW_CATEGORIES;

    /**
     * 标记这条提醒对应的业务记录为"已处理"：只影响当前登录人自己后续还看不看得到这条提醒，
     * 不影响其他共同受众（比如同一条记录的项目负责人和执行人员各自独立标记）；不影响
     * ADMIN/管理层的完整视角（他们本来就不受这个过滤逻辑影响）。
     *
     * 业务记录的进度真正发生变化后（progressChangedAt/completedAt 前进），这条标记会自动
     * 失效——不需要手动清理，也不会被每天/每次手动重算的批次清空（这张表完全独立于
     * progress_reminders/progress_reminder_details，那两张表怎么重建都不会碰到它）。
     */
    @Transactional
    public void acknowledge(ReminderCategory category, Long targetId) {
        if (!ACKNOWLEDGEABLE_CATEGORIES.contains(category)) {
            throw new RuntimeException("这类提醒不支持标记已处理");
        }
        Long employeeId = employeeRoleUtil.getCurrentEmployeeId();
        if (employeeId == null) {
            throw new RuntimeException("当前账号未关联员工，无法标记已处理");
        }
        Date snapshot = resolveCurrentChangedAt(category, targetId);
        ReminderAcknowledgement ack = ackRepo
                .findByCategoryAndTargetIdAndEmployeeId(category, targetId, employeeId)
                .orElseGet(ReminderAcknowledgement::new);
        ack.setIsDeleted(false);
        ack.setCategory(category);
        ack.setTargetId(targetId);
        ack.setEmployeeId(employeeId);
        ack.setSnapshotChangedAt(snapshot);
        ack.setAcknowledgedAt(new Date());
        ackRepo.save(ack);
    }

    /** REQUIREMENT_INVOICE_OVERDUE 用 completedAt，其余（trackingId 定位）用 progressChangedAt */
    private Date resolveCurrentChangedAt(ReminderCategory category, Long targetId) {
        if (category == ReminderCategory.REQUIREMENT_INVOICE_OVERDUE) {
            return requirementRepo.findById(targetId).map(InfluencerRequirement::getCompletedAt).orElse(null);
        }
        return trackingRepo.findById(targetId).map(CollaborationTracking::getProgressChangedAt).orElse(null);
    }

    /**
     * 过滤掉当前登录人已经标记"已处理"、且标记之后业务记录时间戳没有变化（说明情况还没变，
     * 应该继续隐藏）的明细行；标记之后时间戳变了（真的推进了）的，标记自动失效，正常展示。
     */
    private List<ProgressReminderDetail> filterAcknowledged(ReminderCategory category, List<ProgressReminderDetail> details) {
        Long employeeId = employeeRoleUtil.getCurrentEmployeeId();
        if (employeeId == null || details.isEmpty()) return details;

        boolean requirementBased = category == ReminderCategory.REQUIREMENT_INVOICE_OVERDUE;
        List<Long> targetIds = details.stream()
                .map(d -> requirementBased ? d.getRequirementId() : d.getTrackingId())
                .filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (targetIds.isEmpty()) return details;

        Map<Long, Date> snapshotByTarget = ackRepo.findByCategoryAndEmployeeIdAndTargetIdIn(category, employeeId, targetIds)
                .stream().collect(Collectors.toMap(ReminderAcknowledgement::getTargetId, ReminderAcknowledgement::getSnapshotChangedAt));
        if (snapshotByTarget.isEmpty()) return details;

        List<ProgressReminderDetail> result = new ArrayList<>();
        for (ProgressReminderDetail d : details) {
            Long targetId = requirementBased ? d.getRequirementId() : d.getTrackingId();
            Date snapshot = snapshotByTarget.get(targetId);
            if (snapshot == null) {
                result.add(d);
                continue;
            }
            Date currentChangedAt = resolveCurrentChangedAt(category, targetId);
            // 标记之后业务记录的时间戳真的往前走了，说明标记已经失效，恢复展示；否则继续隐藏
            if (currentChangedAt != null && currentChangedAt.after(snapshot)) {
                result.add(d);
            }
        }
        return result;
    }

    private boolean canViewReminder(ProgressReminder r) {
        if (hasFullReminderVisibility()) return true;
        if (r.getAudienceEmployeeId() != null) {
            Long empId = employeeRoleUtil.getCurrentEmployeeId();
            return empId != null && empId.equals(r.getAudienceEmployeeId());
        }
        String role = employeeRoleUtil.getCurrentEmployeeRole();
        return role != null && role.equals(r.getAudienceEmployeeRole());
    }

    // ============ 受众判定 ============

    /** 判断某个员工 id 是不是"管理层"（跟 SysUser.role 无关，看的是关联的员工角色） */
    public boolean isManagementEmployee(Long employeeId) {
        if (employeeId == null) return false;
        Employee emp = employeeCache.findById(employeeId);
        return emp != null && MANAGEMENT_ROLE.equals(emp.getRole());
    }

    /** 当前登录账号是否是"管理层"受众 */
    public boolean isCurrentUserManagement() {
        SysUser user = sysUserRepo.findByUsernameAndIsDeletedFalse(RoleUtil.getCurrentUsername()).orElse(null);
        return user != null && isManagementEmployee(user.getEmployeeId());
    }

    // ============ 登录弹窗 ============

    /**
     * 判断当前登录账号今天是否应该弹出"进度提醒"弹窗：先看有没有任何自己能看到的提醒
     * （2026-07 起不再局限于管理层——任何角色只要 listForCurrentUser() 非空就参与弹窗机制），
     * 再看北京时间每天12点/18点/22点三个节点，lastSeenReminderPopupAt 是否早于
     * "最近一个已经过去的节点时刻"（不管是今天没消费过、还是连续几天没登录漏掉的）。
     */
    @Transactional(readOnly = true)
    public boolean shouldShowPopup() {
        if (listForCurrentUser().isEmpty()) return false;
        SysUser user = sysUserRepo.findByUsernameAndIsDeletedFalse(RoleUtil.getCurrentUsername()).orElse(null);
        if (user == null) return false;
        Date latestCheckpoint = latestPassedCheckpoint();
        return user.getLastSeenReminderPopupAt() == null || user.getLastSeenReminderPopupAt().before(latestCheckpoint);
    }

    /** 用户点了弹窗上的按钮（跳转待处理/我知道了）后调用，更新"最后看到弹窗"的时间戳 */
    @Transactional
    public void markPopupSeen() {
        SysUser user = sysUserRepo.findByUsernameAndIsDeletedFalse(RoleUtil.getCurrentUsername()).orElse(null);
        if (user == null) return;
        user.setLastSeenReminderPopupAt(new Date());
        sysUserRepo.save(user);
    }

    /** 今天12点/18点/22点里，最近一个已经过去的时刻；如果今天还没到12点，取昨天22点 */
    private Date latestPassedCheckpoint() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        ZonedDateTime latest = null;
        for (int hour : CHECKPOINT_HOURS) {
            ZonedDateTime cp = now.toLocalDate().atStartOfDay(ZoneId.systemDefault()).withHour(hour);
            if (!cp.isAfter(now)) latest = cp;
        }
        if (latest == null) {
            latest = now.toLocalDate().minusDays(1).atStartOfDay(ZoneId.systemDefault()).withHour(22);
        }
        return Date.from(latest.toInstant());
    }

    // ============ 日期工具 ============

    /**
     * @Temporal(TemporalType.DATE) 字段（比如 publishDate）落库读出来时，JDBC 驱动给的实际运行时类型
     * 是 java.sql.Date（java.util.Date 的子类），而 java.sql.Date 把 toInstant() 重写成了直接抛
     * UnsupportedOperationException（因为纯日期没有时分秒，语义上转不成一个具体时刻）——
     * 不能直接 d.toInstant()。这里统一先包一层 new java.sql.Date(d.getTime())，用它自带的
     * toLocalDate()（按 JVM 默认时区取年月日，已经是北京时间）来转换，不会有这个问题，
     * 不管传进来的实际是 java.util.Date 还是 java.sql.Date 都能正常工作。
     */
    private LocalDate toLocalDate(Date d) {
        return new java.sql.Date(d.getTime()).toLocalDate();
    }

    private Date toDate(LocalDate d) {
        return Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }
}
