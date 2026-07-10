package com.lusuoria.settlement.service.impl;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.config.InfluencerTeamCache;
import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.entity.InfluencerTeam;
import com.lusuoria.settlement.entity.ProgressReminder;
import com.lusuoria.settlement.entity.ProgressReminderDetail;
import com.lusuoria.settlement.entity.SysUser;
import com.lusuoria.settlement.enums.InfluencerPaymentProgress;
import com.lusuoria.settlement.enums.PaymentCycleType;
import com.lusuoria.settlement.enums.ReminderCategory;
import com.lusuoria.settlement.enums.ReminderUrgency;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import com.lusuoria.settlement.repository.InfluencerRepository;
import com.lusuoria.settlement.repository.ProgressReminderDetailRepository;
import com.lusuoria.settlement.repository.ProgressReminderRepository;
import com.lusuoria.settlement.repository.SysUserRepository;
import com.lusuoria.settlement.util.RoleUtil;
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
    private static final int[] CHECKPOINT_HOURS = {12, 18, 22};
    /** 品牌方月结回溯月份数的技术兜底（不是业务规则）：纯粹防止极端脏数据导致死循环 */
    private static final int MONTH_END_LOOKBACK_SAFETY_CAP = 36;

    @Autowired private ProgressReminderRepository reminderRepo;
    @Autowired private ProgressReminderDetailRepository detailRepo;
    @Autowired private CollaborationTrackingRepository trackingRepo;
    @Autowired private InfluencerRepository influencerRepo;
    @Autowired private BrandCache brandCache;
    @Autowired private InfluencerTeamCache teamCache;
    @Autowired private EmployeeCache employeeCache;
    @Autowired private SysUserRepository sysUserRepo;

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
        } catch (RuntimeException e) {
            // GlobalExceptionHandler 只会把异常包成 400 返回给前端，不会打印堆栈，
            // 排查问题时看不到具体原因，这里手动记一下，方便去 Render 日志里查
            log.error("进度提醒跑批失败：{}", e.toString(), e);
            throw e;
        }
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
            if (t.getInfluencerPaymentProgress() == InfluencerPaymentProgress.INCLUDED_IN_PAYMENT_BATCH) continue;
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

    // ============ 查询（供 Controller 用） ============

    /** 当前登录账号能看到的提醒列表，按"已超期 -> 1-3天 -> 3-7天"、category 排序 */
    @Transactional(readOnly = true)
    public List<ProgressReminder> listForCurrentUser() {
        if (!isCurrentUserManagement()) return Collections.emptyList();
        List<ProgressReminder> list = new ArrayList<>(reminderRepo.findByAudienceEmployeeRole(MANAGEMENT_ROLE));
        list.sort(Comparator
                .comparing((ProgressReminder r) -> r.getCategory().ordinal())
                .thenComparing(r -> r.getUrgency().ordinal()));
        return list;
    }

    /** 某条提醒的明细（仅 COLLAB_PAYMENT_DUE 有意义），按离最迟结款日的接近程度排序 */
    @Transactional(readOnly = true)
    public List<ProgressReminderDetail> listDetails(Long reminderId) {
        if (!isCurrentUserManagement()) return Collections.emptyList();
        ProgressReminder reminder = reminderRepo.findById(reminderId).orElse(null);
        if (reminder == null || !MANAGEMENT_ROLE.equals(reminder.getAudienceEmployeeRole())) {
            return Collections.emptyList();
        }
        return detailRepo.findByReminderIdOrderByDeadlineDateAsc(reminderId);
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
     * 判断当前登录账号今天是否应该弹出"进度提醒"弹窗：
     * 北京时间每天12点/18点/22点三个节点，只要 lastSeenReminderPopupAt 早于
     * "最近一个已经过去的节点时刻"，就应该弹（不管是今天没消费过、还是连续几天没登录漏掉的）。
     */
    @Transactional(readOnly = true)
    public boolean shouldShowPopup() {
        if (!isCurrentUserManagement()) return false;
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
