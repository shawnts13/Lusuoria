package com.lusuoria.settlement.service.impl;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.config.InfluencerTeamCache;
import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.entity.TeamContract;
import com.lusuoria.settlement.entity.InfluencerPayment;
import com.lusuoria.settlement.entity.InfluencerRequirement;
import com.lusuoria.settlement.entity.InfluencerTeam;
import com.lusuoria.settlement.entity.PendingApproval;
import com.lusuoria.settlement.entity.ProgressReminder;
import com.lusuoria.settlement.entity.ProgressReminderDetail;
import com.lusuoria.settlement.entity.ReminderAcknowledgement;
import com.lusuoria.settlement.entity.SysUser;
import com.lusuoria.settlement.enums.CollaborationProgress;
import com.lusuoria.settlement.enums.InfluencerPaymentProgress;
import com.lusuoria.settlement.enums.InfluencerPaymentStatus;
import com.lusuoria.settlement.enums.OverdueUrgency;
import com.lusuoria.settlement.enums.PaymentCycleType;
import com.lusuoria.settlement.enums.PendingApprovalCategory;
import com.lusuoria.settlement.enums.PendingApprovalStatus;
import com.lusuoria.settlement.enums.ReminderCategory;
import com.lusuoria.settlement.enums.ReminderUrgency;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import com.lusuoria.settlement.repository.TeamContractRepository;
import com.lusuoria.settlement.repository.InfluencerPaymentRepository;
import com.lusuoria.settlement.repository.InfluencerRepository;
import com.lusuoria.settlement.repository.InfluencerRequirementRepository;
import com.lusuoria.settlement.repository.PendingApprovalRepository;
import com.lusuoria.settlement.repository.ProgressReminderDetailRepository;
import com.lusuoria.settlement.repository.ProgressReminderRepository;
import com.lusuoria.settlement.repository.ReminderAcknowledgementRepository;
import com.lusuoria.settlement.repository.SysUserRepository;
import com.lusuoria.settlement.util.EmployeeRoleUtil;
import com.lusuoria.settlement.util.MultiValueUtil;
import com.lusuoria.settlement.util.RoleUtil;
import com.lusuoria.settlement.util.WorkdayUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
 * COLLAB_PAYMENT_DUE：覆盖按红人成本阈值分档、月结两种品牌方付款周期，把命中的红人合作
 * 跟踪记录按离最迟结款日的天数分成三档，每档生成一条汇总（笔数）+ 一批 ProgressReminderDetail
 * 明细快照（2026-08 起月结品牌方也按这套逻辑处理，见 runCollabPaymentDue 的说明；原来单独的
 * BRAND_MONTH_END_PAYMENT_DUE 类别——按品牌方+月份汇总、不排除已结部分、没有下钻明细——
 * 已被这次改动取代并删除）。
 *
 * 受众目前只有"管理层"这一个员工角色（Employee.role，注意不是 SysUser.role——判断谁能看到
 * 用的是登录账号关联的员工角色，跟登录账号本身是 ADMIN 还是 STAFF 无关）。（这段是老注释，
 * 后续陆续加了 FINANCE_ROLE/LEGAL_ROLE/EMPLOYEE_OWNED_CATEGORIES 好几种受众口径，没有回来
 * 更新这里；2026-08-16 新增的 DELETE_REQUEST_PENDING/PROGRESS_ROLLBACK_PENDING 是目前唯一
 * 反过来的例外——"管理层"这个员工角色反而看不到，必须登录账号本身是 ADMIN，见
 * ADMIN_ONLY_CATEGORIES 的注释。）
 */
@Service
public class ProgressReminderService {

    private static final Logger log = LoggerFactory.getLogger(ProgressReminderService.class);

    private static final String MANAGEMENT_ROLE = "管理层";
    private static final String FINANCE_ROLE = "财务";
    /** 2026-07 新增：合同相关提醒（无论是不是自己名下的项目负责人/执行人员）法务全部可见 */
    private static final String LEGAL_ROLE = "法务";
    private static final int[] CHECKPOINT_HOURS = {12, 18, 22};
    /** 一年签一次合同：到期前多少天开始提醒 */
    private static final int CONTRACT_EXPIRY_WINDOW_DAYS = 30;

    /** 2026-07 新增：PM_EXECUTOR_PROGRESS_STALL 里"3个工作日未流转"提醒的状态集合
     * （INFLUENCER_ORDERED 单独按5个工作日，PENDING_INFLUENCER_ORDER 单独按4个工作日
     * [2026-08-17 新增]，见 stallThreshold()） */
    private static final Set<CollaborationProgress> PM_EXECUTOR_3DAY_STATES = EnumSet.of(
            CollaborationProgress.PENDING_CLIENT_BRIEF, CollaborationProgress.CONTRACT_SENT,
            CollaborationProgress.SHOOTING_GUIDE_SENT, CollaborationProgress.PENDING_DRAFT,
            CollaborationProgress.PENDING_REVISION, CollaborationProgress.PENDING_PUBLISH);

    /** "结款后更新提示内容"手动触发范围 */
    private static final Set<ReminderCategory> PAYMENT_CATEGORIES = EnumSet.of(
            ReminderCategory.COLLAB_PAYMENT_DUE,
            ReminderCategory.INFLUENCER_PAYMENT_DUE, ReminderCategory.INFLUENCER_PAYMENT_RECEIPT_OVERDUE);
    /** "项目流转后更新提示内容"支持"标记已处理"的范围（2026-07 新增）——这4类都有可靠的
     * "业务记录是否已经变化"时间戳，能判断一条旧的"标记已处理"快照是否已经过期。
     * 注意：这个集合同时也是 ACKNOWLEDGEABLE_CATEGORIES 的来源（见下方），不要往这里加
     * CONTRACT_EXPIRING_SOON——那一类没有可靠的时间戳，不支持"标记已处理"，手动触发范围见
     * PROJECT_FLOW_RECOMPUTE_CATEGORIES */
    private static final Set<ReminderCategory> PROJECT_FLOW_CATEGORIES = EnumSet.of(
            ReminderCategory.PM_EXECUTOR_PROGRESS_STALL, ReminderCategory.FINANCE_PROGRESS_STALL,
            ReminderCategory.REQUIREMENT_INVOICE_OVERDUE, ReminderCategory.REQUIREMENT_CONTRACT_OVERDUE);
    /** "项目流转后更新提示内容"手动触发范围（2026-08 起把 CONTRACT_EXPIRING_SOON 也纳入——
     * 之前这一类严格只在每天3点主批次里跑，改完代码之后要等到第二天3点才能验证效果，体验很差；
     * 现在允许通过这个按钮立即重算。CONTRACT_EXPIRING_SOON 依然不支持"标记已处理"（见上面
     * PROJECT_FLOW_CATEGORIES 的注释），所以这里单独维护一个集合，不能直接在
     * PROJECT_FLOW_CATEGORIES 里加，否则会连带让它变得"可标记已处理"） */
    /** 2026-08-16 新增：DELETE_REQUEST_PENDING/PROGRESS_ROLLBACK_PENDING/
     * EXECUTOR_COST_MODIFY_PENDING 这3类不分档（存在未处理事项就一直提醒，不按天数升级），
     * 跟用户确认过的设计，所以手动重算范围直接照抄 PROJECT_FLOW_CATEGORIES 的老几类，同样不
     * 加进 PROJECT_FLOW_CATEGORIES（也就不支持"标记已处理"——这3类天然靠审核事项本身被
     * 同意/拒绝之后消失，不需要"标记已处理"这层）。 */
    private static final Set<ReminderCategory> PROJECT_FLOW_RECOMPUTE_CATEGORIES;
    static {
        Set<ReminderCategory> s = EnumSet.copyOf(PROJECT_FLOW_CATEGORIES);
        s.add(ReminderCategory.CONTRACT_EXPIRING_SOON);
        s.add(ReminderCategory.DELETE_REQUEST_PENDING);
        s.add(ReminderCategory.PROGRESS_ROLLBACK_PENDING);
        s.add(ReminderCategory.EXECUTOR_COST_MODIFY_PENDING);
        PROJECT_FLOW_RECOMPUTE_CATEGORIES = Collections.unmodifiableSet(s);
    }
    /** 按具体项目负责人/涉及执行人员定向可见的类别（2026-07 新增 CONTRACT_EXPIRING_SOON，
     * 2026-08 新增 FINANCE_PROGRESS_STALL——注意 FINANCE_PROGRESS_STALL 这一类是"混合"的：
     * 同一类别下既有 audienceEmployeeId=null 的"财务角色整体可见"卡片（见
     * saveFinanceStallReminder），也有 audienceEmployeeId=具体项目负责人 的"按人定向"卡片
     * （见 saveFinancePmStallReminder）——resolveVisibleReminders() 靠这里按人扫一遍时，
     * audienceEmployeeId=null 的行天然不会被任何具体 employeeId 匹配上，不影响财务角色的
     * 那份可见性（走的是另一条 FINANCE_ROLE 分支）；isViewingAsInvolvedExecutor() 已经对
     * audienceEmployeeId=null 的情况做了短路，不会误把财务当成"顺带涉及的执行人员"。
     * 2026-08-16 新增 EXECUTOR_COST_MODIFY_PENDING：按目标记录的项目负责人（targetProjectManagerId）
     * 定向生成，走跟其它几类完全一样的机制——管理层/ADMIN 全量可见，负责人本人按 audienceEmployeeId
     * 命中，不涉及"执行人员顺带可见"（这条提醒本身跟执行人员无关，不设置 involvedEmployeeIds）。 */
    private static final Set<ReminderCategory> EMPLOYEE_OWNED_CATEGORIES = EnumSet.of(
            ReminderCategory.PM_EXECUTOR_PROGRESS_STALL, ReminderCategory.REQUIREMENT_INVOICE_OVERDUE,
            ReminderCategory.REQUIREMENT_CONTRACT_OVERDUE, ReminderCategory.CONTRACT_EXPIRING_SOON,
            ReminderCategory.FINANCE_PROGRESS_STALL, ReminderCategory.EXECUTOR_COST_MODIFY_PENDING);
    /** 合同相关提醒（法务全量可见，不按具体项目负责人过滤） */
    private static final Set<ReminderCategory> CONTRACT_CATEGORIES = EnumSet.of(
            ReminderCategory.REQUIREMENT_CONTRACT_OVERDUE, ReminderCategory.CONTRACT_EXPIRING_SOON);
    /**
     * 2026-08-16 新增：DELETE_REQUEST_PENDING/PROGRESS_ROLLBACK_PENDING 只有登录账号本身是
     * ADMIN（SysUser.role）的人能审核（PendingApprovalService.assertCanResolve()），不是
     * Employee.role="管理层"能决定的——这两个是不同维度的权限，"管理层"员工角色不一定对应
     * ADMIN 登录账号。跟用户确认过：这两类提醒的受众要严格收窄到"登录账号本身是 ADMIN"，
     * 即使是管理层也不能靠 hasFullReminderVisibility() 的"管理层全量可见"兜底看到——
     * 否则会出现"管理层看到提醒卡片、点进去却发现自己根本没权限处理"的体验问题。
     * 见 hasFullVisibilityFor()/resolveVisibleReminders() 里对这个集合的特殊处理。
     */
    private static final Set<ReminderCategory> ADMIN_ONLY_CATEGORIES = EnumSet.of(
            ReminderCategory.DELETE_REQUEST_PENDING, ReminderCategory.PROGRESS_ROLLBACK_PENDING);

    @Autowired private ProgressReminderRepository reminderRepo;
    @Autowired private ProgressReminderDetailRepository detailRepo;
    @Autowired private CollaborationTrackingRepository trackingRepo;
    @Autowired private InfluencerRepository influencerRepo;
    @Autowired private TeamContractRepository teamContractRepo;
    @Autowired private InfluencerRequirementRepository requirementRepo;
    @Autowired private InfluencerRequirementService requirementService;
    @Autowired private InfluencerPaymentRepository influencerPaymentRepo;
    @Autowired private InfluencerPaymentService influencerPaymentService;
    @Autowired private ReminderAcknowledgementRepository ackRepo;
    @Autowired private PendingApprovalRepository pendingApprovalRepo;
    @Autowired private BrandCache brandCache;
    @Autowired private InfluencerTeamCache teamCache;
    @Autowired private EmployeeCache employeeCache;
    @Autowired private SysUserRepository sysUserRepo;
    @Autowired private com.lusuoria.settlement.config.SysUserCache sysUserCache;
    @Autowired private EmployeeRoleUtil employeeRoleUtil;
    @Autowired private com.lusuoria.settlement.config.ReminderThresholdCache thresholdCache;

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
            runInfluencerPaymentDue(today, batchDate);
            runInfluencerPaymentReceiptOverdue(today, batchDate);
            // runPmExecutorProgressStall/runFinanceProgressStall/runContractExpiringSoon 都要用到
            // "全部未删除的红人合作跟踪记录"这份全表数据——2026-08-16 之前是各自独立查一次，
            // 同一次跑批里对 collaboration_tracking 做了3次全表扫描；这张表只会越来越大，
            // 3次全表扫描叠加 runRequirementInvoiceOverdue/runRequirementContractOverdue（另外
            // 修复过的 N+1）在同一个 @Transactional 方法里顺序执行，会一直占着 Render 免费层
            // 只有3个连接的连接池中的一个——现在改成只查一次，三个方法共用同一份内存列表
            List<CollaborationTracking> allTracking = trackingRepo.findByIsDeletedFalse();
            runPmExecutorProgressStall(today, batchDate, allTracking);
            runFinanceProgressStall(today, batchDate, allTracking);
            runRequirementInvoiceOverdue(today, batchDate);
            runRequirementContractOverdue(today, batchDate);
            runContractExpiringSoon(today, batchDate, allTracking);
            runDeleteRequestPending(batchDate);
            runProgressRollbackPending(batchDate);
            runExecutorCostModifyPending(batchDate);
        } catch (RuntimeException e) {
            // GlobalExceptionHandler 只会把异常包成 400 返回给前端，不会打印堆栈，
            // 排查问题时看不到具体原因，这里手动记一下，方便去 Render 日志里查
            log.error("进度提醒跑批失败：{}", e.toString(), e);
            throw e;
        }
    }

    /**
     * "标记已处理"记录的每日清理（2026-07 新增，凌晨3点10分，紧跟在主批次后面）。
     * reminder_acknowledgements 只在用户点"标记已处理"时写入，从不被 runBatch() 触碰，
     * 会无限累积——这里把"已经不可能再派上用场"的记录删掉：
     *   - 对应的业务记录（合作跟踪/需求）已经不存在了；
     *   - 或者已经不再符合这一类提醒的候选条件了（比如进度已经到了终态、Invoice已经传了、
     *     需求被倒退拉回100%以下、品牌方配置改成不需要invoice了）；
     *   - 或者业务记录的时间戳（progressChangedAt/completedAt）已经比标记时的快照更新——
     *     时间戳只会前进不会倒退，一旦前进过一次，这条旧快照就永远不可能再匹配上了。
     * 满足以上任一条件就说明这条标记已经不再有意义（不管当时是不是还在生效），直接删除。
     */
    @Scheduled(cron = "0 10 3 * * *")
    @Transactional
    public void cleanupAcknowledgements() {
        try {
            List<ReminderAcknowledgement> all = ackRepo.findAll();
            if (all.isEmpty()) return;

            Set<Long> trackingTargetIds = new HashSet<>();
            Set<Long> requirementTargetIds = new HashSet<>();
            for (ReminderAcknowledgement ack : all) {
                if (isRequirementBasedCategory(ack.getCategory())) {
                    requirementTargetIds.add(ack.getTargetId());
                } else {
                    trackingTargetIds.add(ack.getTargetId());
                }
            }
            Map<Long, CollaborationTracking> trackingById = new HashMap<>();
            if (!trackingTargetIds.isEmpty()) {
                for (CollaborationTracking t : trackingRepo.findAllById(trackingTargetIds)) trackingById.put(t.getId(), t);
            }
            Map<Long, InfluencerRequirement> requirementById = new HashMap<>();
            if (!requirementTargetIds.isEmpty()) {
                for (InfluencerRequirement r : requirementRepo.findAllById(requirementTargetIds)) requirementById.put(r.getId(), r);
            }

            List<Long> staleIds = new ArrayList<>();
            for (ReminderAcknowledgement ack : all) {
                boolean stale = isRequirementBasedCategory(ack.getCategory())
                        ? isRequirementAckStale(ack, requirementById.get(ack.getTargetId()))
                        : isTrackingAckStale(ack, trackingById.get(ack.getTargetId()));
                if (stale) staleIds.add(ack.getId());
            }
            if (!staleIds.isEmpty()) {
                ackRepo.deleteAllByIdInBatch(staleIds);
                log.info("清理了 {} 条已失效的提醒标记", staleIds.size());
            }
        } catch (RuntimeException e) {
            log.error("提醒标记清理失败：{}", e.toString(), e);
            throw e;
        }
    }

    /** 判断一条"红人合作跟踪"相关的已读标记是否已经失效（记录被删/进度不再满足条件/进度在标记之后又变了），失效的会在 cleanupAcknowledgements 里被清理掉，让提醒重新出现 */
    private boolean isTrackingAckStale(ReminderAcknowledgement ack, CollaborationTracking t) {
        if (t == null || Boolean.TRUE.equals(t.getIsDeleted())) return true;
        boolean stillCandidate = ack.getCategory() == ReminderCategory.PM_EXECUTOR_PROGRESS_STALL
                ? stallThreshold(t.getProgress()) != null
                : isFinanceStallCandidate(t.getProgress());
        if (!stillCandidate || t.getProgressChangedAt() == null) return true;
        return t.getProgressChangedAt().after(ack.getSnapshotChangedAt());
    }

    /** REQUIREMENT_INVOICE_OVERDUE/REQUIREMENT_CONTRACT_OVERDUE 都是按"需求"定位（targetId=requirementId），
     * 其余（PM_EXECUTOR_PROGRESS_STALL/FINANCE_PROGRESS_STALL）按"红人合作跟踪"定位（targetId=trackingId） */
    private boolean isRequirementBasedCategory(ReminderCategory category) {
        return category == ReminderCategory.REQUIREMENT_INVOICE_OVERDUE
                || category == ReminderCategory.REQUIREMENT_CONTRACT_OVERDUE;
    }

    /** 判断一条"需求"相关（Invoice/合同逾期）的已读标记是否已经失效（需求被删/未完成/该补的链接已经补上/品牌方规则变了导致不再需要），逻辑跟 isTrackingAckStale 对称 */
    private boolean isRequirementAckStale(ReminderAcknowledgement ack, InfluencerRequirement r) {
        if (r == null || Boolean.TRUE.equals(r.getIsDeleted())) return true;
        if (r.getCompletedAt() == null) return true;
        boolean isContract = ack.getCategory() == ReminderCategory.REQUIREMENT_CONTRACT_OVERDUE;
        if (isContract ? r.getContractLink() != null : r.getInvoiceLink() != null) return true;
        Brand brand = r.getBrandId() != null ? brandCache.findById(r.getBrandId()) : null;
        if (isContract) {
            InfluencerTeam team = r.getTeamId() != null ? teamCache.findById(r.getTeamId()) : null;
            if (!InfluencerTeam.isPerRequirementContract(brand, team)) return true;
        } else if (brand != null && !brand.requiresInvoiceUpload()) {
            return true;
        }
        return r.getCompletedAt().after(ack.getSnapshotChangedAt());
    }

    /**
     * "结款后更新提示内容"手动触发（2026-07 起只重算 COLLAB_PAYMENT_DUE 这一类，
     * 2026-07 再新增 INFLUENCER_PAYMENT_DUE，不影响
     * PM_EXECUTOR_PROGRESS_STALL/FINANCE_PROGRESS_STALL/REQUIREMENT_INVOICE_OVERDUE 当天
     * 已经算好的数据）。
     */
    @Transactional
    public void runPaymentBatches() {
        try {
            clearCategories(PAYMENT_CATEGORIES);
            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            Date batchDate = toDate(today);
            runCollabPaymentDue(today, batchDate);
            runInfluencerPaymentDue(today, batchDate);
            runInfluencerPaymentReceiptOverdue(today, batchDate);
        } catch (RuntimeException e) {
            log.error("进度提醒（结款类）手动重算失败：{}", e.toString(), e);
            throw e;
        }
    }

    /**
     * "项目流转后更新提示内容"手动触发（2026-07 新增，2026-07 新增合同上传逾期，2026-08 新增
     * 合同即将到期，2026-08-16 新增删除审核/进度倒退审核/执行成本修改审核这3类待处理提醒）：
     * 重算 PM_EXECUTOR_PROGRESS_STALL/FINANCE_PROGRESS_STALL/REQUIREMENT_INVOICE_OVERDUE/
     * REQUIREMENT_CONTRACT_OVERDUE/CONTRACT_EXPIRING_SOON/DELETE_REQUEST_PENDING/
     * PROGRESS_ROLLBACK_PENDING/EXECUTOR_COST_MODIFY_PENDING 这8类，不影响两类"临近结款"提醒
     * 当天已经算好的数据。CONTRACT_EXPIRING_SOON 本来严格只在每天3点主批次里跑，2026-08 起也
     * 允许这里手动触发——之前改完团队合同相关的逻辑要等到第二天3点才能验证效果，体验很差；
     * 纳入之后不影响它"不支持标记已处理"的既有约束（见 PROJECT_FLOW_RECOMPUTE_CATEGORIES
     * 的注释，新增的3类审核待处理提醒同样不支持"标记已处理"）。
     */
    @Transactional
    public void runProjectFlowBatches() {
        try {
            clearCategories(PROJECT_FLOW_RECOMPUTE_CATEGORIES);
            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            Date batchDate = toDate(today);
            // 见 runBatch() 里同一处改动的说明：这3个方法共用同一份全表数据，只查一次
            List<CollaborationTracking> allTracking = trackingRepo.findByIsDeletedFalse();
            runPmExecutorProgressStall(today, batchDate, allTracking);
            runFinanceProgressStall(today, batchDate, allTracking);
            runRequirementInvoiceOverdue(today, batchDate);
            runRequirementContractOverdue(today, batchDate);
            runContractExpiringSoon(today, batchDate, allTracking);
            runDeleteRequestPending(batchDate);
            runProgressRollbackPending(batchDate);
            runExecutorCostModifyPending(batchDate);
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

    /**
     * Part A：品牌方付款周期=按红人成本阈值分档，或=月结。
     *
     * 2026-08 起按品牌方是否需要invoice分两套口径计算，跟 InfluencerPaymentService.
     * computeCycleInfo 保持一致（实际计算也共用同一个 InfluencerRequirementService.
     * fetchPaymentInfo，不各自维护一份聚合逻辑，避免两边口径跑偏）：
     *   - 需要invoice：一次结款只能对应一个需求（一张invoice），所以只有"需求完成进度"=100%
     *     的需求才参与提醒；阈值分档用整个需求的实际可结款成本（不含折损），起算点是
     *     "需求完成进度达到100%的时间"，不是视频发布时间。同一需求下每条视频各生成一行明细
     *     （粒度跟"选择涉及的红人视频项目"弹窗一致），但共享同一组 cycleDays/deadlineDate。
     *     "折损"状态的记录本身不生成提醒（不会真正付款，提醒没有意义）。
     *   - 不需要invoice：保持原来的按单笔成本+发布时间计算，行为不变。
     *
     * 2026-08 新增第三档，覆盖月结（MONTH_END）品牌方：这类品牌方允许一个需求分批结款，
     * 不要求需求整体完成，所以按单条记录（不按需求）处理，跟"不需要invoice"那档同一个粒度。
     * 没有真实"对账日期"可用（这条记录还没被拉进任何结款批次），用"视频发布日期所在月份的
     * 最后一个工作日"模拟对账日（跟 InfluencerRequirementService.estimateDeadlineForSorting()/
     * "去结款"自动预填对账日期用的是同一套口径），再加上品牌方配置的"月底对账日后N天内结款"，
     * 算出模拟的最迟结款日。"折损"同样不提醒。
     */
    private void runCollabPaymentDue(LocalDate today, Date batchDate) {
        Map<Long, Brand> costThresholdBrands = new HashMap<>();
        Map<Long, Brand> monthEndBrands = new HashMap<>();
        for (Brand b : brandCache.getAll()) {
            if (b.getPaymentCycleType() == PaymentCycleType.COST_THRESHOLD
                    && b.getCostThresholdAmount() != null
                    && b.getDaysWithinThreshold() != null
                    && b.getDaysAboveThreshold() != null) {
                costThresholdBrands.put(b.getId(), b);
            } else if (b.getPaymentCycleType() == PaymentCycleType.MONTH_END
                    && b.getDaysAfterMonthEnd() != null) {
                monthEndBrands.put(b.getId(), b);
            }
        }
        if (costThresholdBrands.isEmpty() && monthEndBrands.isEmpty()) return;

        List<CollaborationTracking> allCandidates = new ArrayList<>();
        for (CollaborationTracking t : trackingRepo.findByIsDeletedFalse()) {
            if (t.getBrandId() == null) continue;
            if (!costThresholdBrands.containsKey(t.getBrandId()) && !monthEndBrands.containsKey(t.getBrandId())) continue;
            if (t.getPublishDate() == null) continue;
            if (t.getInfluencerPaymentProgress() != null && t.getInfluencerPaymentProgress().isIncludedInBatch()) continue;
            allCandidates.add(t);
        }
        if (allCandidates.isEmpty()) return;

        List<CollaborationTracking> perItemCandidates = new ArrayList<>();
        List<CollaborationTracking> perRequirementCandidates = new ArrayList<>();
        List<CollaborationTracking> monthEndCandidates = new ArrayList<>();
        for (CollaborationTracking t : allCandidates) {
            Brand brand = costThresholdBrands.get(t.getBrandId());
            if (brand != null) {
                if (brand.requiresInvoiceUpload()) {
                    perRequirementCandidates.add(t);
                } else if (t.getInfluencerCost() != null) { // 没有成本没法判断走哪个天数档位，跳过
                    perItemCandidates.add(t);
                }
            } else if (t.getProgress() != CollaborationProgress.DELAYED) { // 折损不会真正付款，不提醒
                monthEndCandidates.add(t);
            }
        }
        if (perItemCandidates.isEmpty() && perRequirementCandidates.isEmpty() && monthEndCandidates.isEmpty()) return;

        List<String> requirementNos = perRequirementCandidates.stream()
                .map(CollaborationTracking::getInternalRequirementNo).collect(Collectors.toList());
        Map<String, InfluencerRequirementService.RequirementPaymentInfo> requirementByNo =
                requirementService.fetchPaymentInfo(requirementNos);

        // 批量查红人账号名，避免逐条查库
        Set<Long> influencerIds = new HashSet<>();
        for (CollaborationTracking t : allCandidates) {
            if (t.getInfluencerId() != null) influencerIds.add(t.getInfluencerId());
        }
        Map<Long, String> accountNameById = new HashMap<>();
        if (!influencerIds.isEmpty()) {
            for (Influencer inf : influencerRepo.findAllById(influencerIds)) {
                accountNameById.put(inf.getId(), inf.getAccountName());
            }
        }

        int overdueMaxDays = thresholdCache.getInt(ReminderCategory.COLLAB_PAYMENT_DUE, "TIER_OVERDUE_MAX_DAYS", 0);
        int nearMaxDays = thresholdCache.getInt(ReminderCategory.COLLAB_PAYMENT_DUE, "TIER_NEAR_MAX_DAYS", 3);
        int windowMaxDays = thresholdCache.getInt(ReminderCategory.COLLAB_PAYMENT_DUE, "TIER_WINDOW_MAX_DAYS", 7);
        Map<ReminderUrgency, List<ProgressReminderDetail>> byUrgency = new EnumMap<>(ReminderUrgency.class);

        for (CollaborationTracking t : perItemCandidates) {
            Brand brand = costThresholdBrands.get(t.getBrandId());
            int cycleDays = t.getInfluencerCost().compareTo(brand.getCostThresholdAmount()) <= 0
                    ? brand.getDaysWithinThreshold() : brand.getDaysAboveThreshold();
            LocalDate deadlineLocalDate = toLocalDate(t.getPublishDate()).plusDays(cycleDays);
            addCollabPaymentDueDetail(byUrgency, today, overdueMaxDays, nearMaxDays, windowMaxDays,
                    t, brand, accountNameById, cycleDays, deadlineLocalDate, null);
        }

        for (CollaborationTracking t : perRequirementCandidates) {
            if (t.getProgress() == CollaborationProgress.DELAYED) continue; // 折损不会真正付款，不提醒
            String reqNo = t.getInternalRequirementNo();
            InfluencerRequirementService.RequirementPaymentInfo info = reqNo != null ? requirementByNo.get(reqNo) : null;
            if (info == null || !info.isComplete() || info.payableCost == null || info.completedAt == null) continue;
            Brand brand = costThresholdBrands.get(t.getBrandId());
            int cycleDays = info.payableCost.compareTo(brand.getCostThresholdAmount()) <= 0
                    ? brand.getDaysWithinThreshold() : brand.getDaysAboveThreshold();
            LocalDate deadlineLocalDate = toLocalDate(info.completedAt).plusDays(cycleDays);
            addCollabPaymentDueDetail(byUrgency, today, overdueMaxDays, nearMaxDays, windowMaxDays,
                    t, brand, accountNameById, cycleDays, deadlineLocalDate, info.completedAt);
        }

        // 月结品牌方：按单条记录处理，用"视频发布月份的最后一个工作日"模拟对账日期
        // （这条记录还没被拉进任何结款批次，没有真实对账日期可用），cycleDays 复用成
        // "月底对账日后N天内结款"这个配置值，跟前端"结款周期"列（N天）的展示口径保持一致。
        for (CollaborationTracking t : monthEndCandidates) {
            Brand brand = monthEndBrands.get(t.getBrandId());
            LocalDate publishMonthEnd = YearMonth.from(toLocalDate(t.getPublishDate())).atEndOfMonth();
            LocalDate simulatedReconcileDate = WorkdayUtil.lastWorkdayOnOrBefore(publishMonthEnd);
            int cycleDays = brand.getDaysAfterMonthEnd();
            LocalDate deadlineLocalDate = simulatedReconcileDate.plusDays(cycleDays);
            addCollabPaymentDueDetail(byUrgency, today, overdueMaxDays, nearMaxDays, windowMaxDays,
                    t, brand, accountNameById, cycleDays, deadlineLocalDate, null);
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
            // 严重度已经用单独的彩色标签展示在卡片上了（见前端 ProgressReminderCardList.vue
            // 的 urgencyLabel），标题文字里不需要再重复一遍"3-7天"这种档位描述
            reminder.setTitle(details.size() + "笔临近结款的红人合作跟踪记录");
            reminder = reminderRepo.save(reminder);

            for (ProgressReminderDetail d : details) d.setReminderId(reminder.getId());
            detailRepo.saveAll(details);
        }
    }

    /** runCollabPaymentDue 的公共收尾：算严重度分档、组装明细行，两条路径（单笔/按需求）共用 */
    private void addCollabPaymentDueDetail(Map<ReminderUrgency, List<ProgressReminderDetail>> byUrgency,
            LocalDate today, int overdueMaxDays, int nearMaxDays, int windowMaxDays,
            CollaborationTracking t, Brand brand, Map<Long, String> accountNameById,
            int cycleDays, LocalDate deadlineLocalDate, Date requirementCompletedAt) {
        long daysRemaining = ChronoUnit.DAYS.between(today, deadlineLocalDate);
        ReminderUrgency urgency = ReminderUrgency.fromDaysRemaining(daysRemaining, overdueMaxDays, nearMaxDays, windowMaxDays);
        if (urgency == null) return; // 超过窗口天数，暂时不用提醒

        ProgressReminderDetail detail = new ProgressReminderDetail();
        detail.setIsDeleted(false);
        detail.setTrackingId(t.getId());
        detail.setInternalProjectNo(t.getInternalProjectNo());
        detail.setInternalRequirementNo(t.getInternalRequirementNo());
        detail.setRequirementCompletedAt(requirementCompletedAt);
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
        detail.setOverdueDays((int) Math.max(0, -daysRemaining));
        detail.setPaymentProgressLabel(t.getInfluencerPaymentProgress() != null
                ? t.getInfluencerPaymentProgress().getLabel() : null);

        byUrgency.computeIfAbsent(urgency, k -> new ArrayList<>()).add(detail);
    }

    /**
     * Part B2（2026-07 新增）：红人结款临近付款日——跟 Part A（COLLAB_PAYMENT_DUE）是完全不同
     * 的两件事，不要混淆：Part A 是"红人合作跟踪"记录还没被纳入任何结款批次时，按品牌方付款
     * 周期算出的"应该在哪天之前发起结款"；这里是已经发起的"红人结款"记录（跨多个红人合作跟踪
     * 记录的一个批次）本身还没实际付款，按这条结款记录自己的"预计付款日"算是否临近。
     *
     * 候选：paymentStatus=PENDING（待付款）且 expectedPaymentDate 有值（没填预计付款日的没法
     * 判断，跳过）。分档口径完全复用 ReminderUrgency.fromDaysRemaining（跟 Part A 同一套
     * 3-7天/1-3天/0天或已超期），超过7天不提醒。受众固定"管理层"，跟 Part A 一致。
     *
     * 明细字段复用 ProgressReminderDetail 已有列（见该实体类各字段注释里 INFLUENCER_PAYMENT_DUE
     * 的说明），不新增数据库列：trackingId 占位取这条结款记录下第一条关联的红人合作跟踪记录 id
     * （这条结款记录必然至少关联一条，否则不可能有合作数量/应付金额）；requirementId/
     * internalRequirementNo 复用存这条结款记录自己的 id/结款单号，供前端"查看详情"跳转到
     * "红人结款"模块按结款单号定位。
     */
    private void runInfluencerPaymentDue(LocalDate today, Date batchDate) {
        List<InfluencerPayment> candidates = influencerPaymentRepo.findByIsDeletedFalse().stream()
                .filter(p -> p.getPaymentStatus() == InfluencerPaymentStatus.PENDING)
                .filter(p -> p.getExpectedPaymentDate() != null)
                .collect(Collectors.toList());
        if (candidates.isEmpty()) return;
        influencerPaymentService.attachTeamIds(candidates); // 补上瞬态字段 teamIds，供拼团队名用

        // 批量预加载这批候选结款记录关联的红人合作跟踪记录，避免下面循环里逐条查库
        List<Long> candidateIds = candidates.stream().map(InfluencerPayment::getId).collect(Collectors.toList());
        Map<Long, List<CollaborationTracking>> linkedByPaymentId = new HashMap<>();
        for (CollaborationTracking t : trackingRepo.findByInfluencerPaymentIdInAndIsDeletedFalse(candidateIds)) {
            linkedByPaymentId.computeIfAbsent(t.getInfluencerPaymentId(), k -> new ArrayList<>()).add(t);
        }

        int overdueMaxDays = thresholdCache.getInt(ReminderCategory.INFLUENCER_PAYMENT_DUE, "TIER_OVERDUE_MAX_DAYS", 0);
        int nearMaxDays = thresholdCache.getInt(ReminderCategory.INFLUENCER_PAYMENT_DUE, "TIER_NEAR_MAX_DAYS", 3);
        int windowMaxDays = thresholdCache.getInt(ReminderCategory.INFLUENCER_PAYMENT_DUE, "TIER_WINDOW_MAX_DAYS", 7);
        Map<ReminderUrgency, List<ProgressReminderDetail>> byUrgency = new EnumMap<>(ReminderUrgency.class);
        for (InfluencerPayment p : candidates) {
            LocalDate deadlineLocalDate = toLocalDate(p.getExpectedPaymentDate());
            long daysRemaining = ChronoUnit.DAYS.between(today, deadlineLocalDate);
            ReminderUrgency urgency = ReminderUrgency.fromDaysRemaining(daysRemaining, overdueMaxDays, nearMaxDays, windowMaxDays);
            if (urgency == null) continue; // 超过7天，暂时不用提醒

            List<CollaborationTracking> linked = linkedByPaymentId.getOrDefault(p.getId(), Collections.emptyList());
            if (linked.isEmpty()) continue; // 理论上不会发生（有合作数量/应付金额说明必然关联了记录），防御性跳过

            ProgressReminderDetail detail = new ProgressReminderDetail();
            detail.setIsDeleted(false);
            detail.setTrackingId(linked.get(0).getId());
            detail.setBrandName(p.getBrand() != null ? p.getBrand().getName() : null);
            detail.setTeamName(joinTeamNames(p.getTeamIds()));
            detail.setDemandContent(formatSettlementMonthLabel(p.getSettlementMonth()));
            detail.setInfluencerCost(p.getPayableAmount());
            detail.setProgressLabel(p.getPaymentStatus().getLabel());
            detail.setPublishDate(p.getReconcileDate());
            detail.setCycleDays(p.getCooperationQuantity() != null ? p.getCooperationQuantity() : 0);
            detail.setDeadlineDate(p.getExpectedPaymentDate());
            detail.setOverdueDays((int) Math.max(0, -daysRemaining));
            detail.setRequirementId(p.getId());
            detail.setInternalRequirementNo(p.getPaymentNo());
            // 2026-08-17 新增：前端"待处理-红人结款临近付款日"详情要展示这条结款记录具体涉及
            // 哪几个红人视频项目/分属哪几个内部需求编号，供核对用
            detail.setInvolvedProjectNos(joinDistinctSorted(linked.stream().map(CollaborationTracking::getInternalProjectNo)));
            detail.setInvolvedRequirementNos(joinDistinctSorted(linked.stream().map(CollaborationTracking::getInternalRequirementNo)));

            byUrgency.computeIfAbsent(urgency, k -> new ArrayList<>()).add(detail);
        }

        for (ReminderUrgency urgency : ReminderUrgency.values()) {
            List<ProgressReminderDetail> details = byUrgency.get(urgency);
            if (details == null || details.isEmpty()) continue;

            ProgressReminder reminder = new ProgressReminder();
            reminder.setIsDeleted(false);
            reminder.setBatchDate(batchDate);
            reminder.setCategory(ReminderCategory.INFLUENCER_PAYMENT_DUE);
            reminder.setUrgency(urgency);
            reminder.setAudienceEmployeeRole(MANAGEMENT_ROLE);
            reminder.setCount(details.size());
            reminder.setTitle(details.size() + "笔临近付款日的红人结款记录");
            reminder = reminderRepo.save(reminder);

            for (ProgressReminderDetail d : details) d.setReminderId(reminder.getId());
            detailRepo.saveAll(details);
        }
    }

    /** 把一批可能重复/为空的字符串去重、排序后按 MultiValueUtil 约定的换行分隔拼接（照抄
     *  InfluencerRequirementService.canonicalPlatform 的做法），全部为空时返回 null（渲染成
     *  "—"，不是一个只有分隔符的空字符串）。2026-08-17 新增，供
     *  runInfluencerPaymentDue() 拼"涉及的红人视频项目"/"涉及的内部需求编号"用。 */
    private String joinDistinctSorted(java.util.stream.Stream<String> values) {
        List<String> sorted = values.filter(v -> v != null && !v.trim().isEmpty())
                .map(String::trim).distinct().sorted().collect(Collectors.toList());
        return sorted.isEmpty() ? null : String.join("\n", sorted);
    }

    /** 结款记录涉及的团队名拼接展示（"、"分隔），null 代表"不涉及团队"跳过不显示；全部为空时返回 null */
    private String joinTeamNames(List<Long> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) return null;
        List<String> names = new ArrayList<>();
        for (Long id : teamIds) {
            if (id == null) continue;
            InfluencerTeam team = teamCache.findById(id);
            if (team != null) names.add(team.getName());
        }
        return names.isEmpty() ? null : String.join("、", names);
    }

    /** "yyyyMM" -> "2026年07月"，供明细展示用（2026-07-28 起去掉"结算月份："前缀，前端表格
     * 本身已有"结算月份"这一列标题，行内容再重复一遍前缀是多余的） */
    private String formatSettlementMonthLabel(String yyyyMM) {
        if (yyyyMM == null || yyyyMM.length() != 6) return null;
        return yyyyMM.substring(0, 4) + "年" + yyyyMM.substring(4) + "月";
    }

    /**
     * 红人结款上传发票逾期（2026-08 新增，"上传发票"功能配套提醒）：只提醒"涉及公对公发票"的
     * 品牌方-团队组合，付款状态=已付款、且还没上传发票（receiptLink 为空）的结款记录，
     * 计时起点是"实际付款日"（用户确认：钱付了，发票才该来）——付款状态还是"待付款"的记录
     * 不计入（哪怕"上传发票"按钮本身不受付款状态限制，逾期提醒只在钱付了之后才有意义）。
     * 阈值口径完全参照 REQUIREMENT_CONTRACT_OVERDUE（OverdueUrgency 三档，工作日计算）。
     * 受众固定"财务"（管理层按 hasFullReminderVisibility 全量可见，不需要单独再判一次）。
     */
    private void runInfluencerPaymentReceiptOverdue(LocalDate today, Date batchDate) {
        List<InfluencerPayment> candidates = influencerPaymentRepo.findByIsDeletedFalse().stream()
                .filter(p -> p.getPaymentStatus() == InfluencerPaymentStatus.PAID)
                .filter(p -> p.getActualPaymentDate() != null)
                .filter(p -> p.getReceiptLink() == null || p.getReceiptLink().trim().isEmpty())
                .collect(Collectors.toList());
        if (candidates.isEmpty()) return;
        influencerPaymentService.attachTeamIds(candidates);

        List<Long> candidateIds = candidates.stream().map(InfluencerPayment::getId).collect(Collectors.toList());
        Map<Long, List<CollaborationTracking>> linkedByPaymentId = new HashMap<>();
        for (CollaborationTracking t : trackingRepo.findByInfluencerPaymentIdInAndIsDeletedFalse(candidateIds)) {
            linkedByPaymentId.computeIfAbsent(t.getInfluencerPaymentId(), k -> new ArrayList<>()).add(t);
        }

        int overdueThreshold = thresholdCache.getInt(ReminderCategory.INFLUENCER_PAYMENT_RECEIPT_OVERDUE, "OVERDUE_THRESHOLD", 14);
        int mildMaxDays = thresholdCache.getInt(ReminderCategory.INFLUENCER_PAYMENT_RECEIPT_OVERDUE, "TIER_MILD_MAX_DAYS", 3);
        int moderateMaxDays = thresholdCache.getInt(ReminderCategory.INFLUENCER_PAYMENT_RECEIPT_OVERDUE, "TIER_MODERATE_MAX_DAYS", 7);

        Map<OverdueUrgency, List<ProgressReminderDetail>> byUrgency = new EnumMap<>(OverdueUrgency.class);
        for (InfluencerPayment p : candidates) {
            // 2026-08 起改成读创建时落库的快照（InfluencerPayment.involvesCorporateInvoice），
            // 不再按当前团队/品牌方配置现算——避免管理层事后调整团队的发票配置，追溯影响历史
            // "已付款"记录该不该提醒补发票，见该字段的类注释
            if (!Boolean.TRUE.equals(p.getInvolvesCorporateInvoice())) continue;
            Brand brand = p.getBrandId() != null ? brandCache.findById(p.getBrandId()) : null;

            int workdays = WorkdayUtil.countWeekdaysInclusive(toLocalDate(p.getActualPaymentDate()), today);
            int overdueDays = workdays - overdueThreshold;
            OverdueUrgency urgency = OverdueUrgency.fromOverdueDays(overdueDays, mildMaxDays, moderateMaxDays);
            if (urgency == null) continue;

            List<CollaborationTracking> linked = linkedByPaymentId.getOrDefault(p.getId(), Collections.emptyList());
            if (linked.isEmpty()) continue; // 理论上不会发生，防御性跳过

            ProgressReminderDetail detail = new ProgressReminderDetail();
            detail.setIsDeleted(false);
            detail.setTrackingId(linked.get(0).getId());
            detail.setBrandName(brand != null ? brand.getName() : null);
            detail.setTeamName(joinTeamNames(p.getTeamIds()));
            detail.setDemandContent(formatSettlementMonthLabel(p.getSettlementMonth()));
            detail.setInfluencerCost(p.getPayableAmount());
            detail.setProgressLabel(p.getPaymentStatus().getLabel());
            detail.setPublishDate(p.getActualPaymentDate());
            detail.setCycleDays(p.getCooperationQuantity() != null ? p.getCooperationQuantity() : 0);
            detail.setDeadlineDate(p.getActualPaymentDate());
            detail.setOverdueDays(overdueDays);
            detail.setThresholdDays(overdueThreshold);
            detail.setRequirementId(p.getId());
            detail.setInternalRequirementNo(p.getPaymentNo());

            byUrgency.computeIfAbsent(urgency, k -> new ArrayList<>()).add(detail);
        }

        for (OverdueUrgency urgency : OverdueUrgency.values()) {
            List<ProgressReminderDetail> details = byUrgency.get(urgency);
            if (details == null || details.isEmpty()) continue;

            ProgressReminder reminder = new ProgressReminder();
            reminder.setIsDeleted(false);
            reminder.setBatchDate(batchDate);
            reminder.setCategory(ReminderCategory.INFLUENCER_PAYMENT_RECEIPT_OVERDUE);
            // urgency 是历史 NOT NULL 列，这一类没有实际展示意义，占位填 OVERDUE，
            // 真正的颜色判断前端按 overdueUrgency 读（跟 saveStallReminder 同一套约定）
            reminder.setUrgency(ReminderUrgency.OVERDUE);
            reminder.setOverdueUrgency(urgency);
            reminder.setAudienceEmployeeRole(FINANCE_ROLE);
            reminder.setCount(details.size());
            reminder.setTitle(details.size() + "笔结款记录已付款后长时间未上传发票");
            reminder = reminderRepo.save(reminder);

            for (ProgressReminderDetail d : details) d.setReminderId(reminder.getId());
            detailRepo.saveAll(details);
        }
    }

    /**
     * Part C（2026-07 新增，2026-07 修正）：红人合作跟踪记录的"主责人"始终是项目负责人——
     * 执行人员不是另一个"主责人"，所以这一类现在统一只按项目负责人归类（不再单独给执行人员
     * 生成一条"执行人员"卡片，那样会导致同一条记录在管理层的全量视角下被算两遍，看起来像
     * 重复数据）。执行人员依然能看到这条卡片（因为他们负责执行其中一部分），只是卡片始终
     * 以"项目负责人-XX-手下的"命名，查看详情时执行人员看到的明细会按自己实际执行的那部分
     * 动态过滤（见 filterToMyExecutorRecords）。
     *
     * "待红人下单"阈值4工作日（2026-08-17 新增），"红人已下单"阈值5工作日，其余6个中间状态
     * 阈值3工作日；已发布未结算及以后的终态、或折损，不算滞留。没有项目负责人的记录（理论上
     * 不该出现）直接跳过——没有主责人就没法归类。
     * 没有 progressChangedAt（老数据，从没触发过一次这个字段的维护逻辑）的记录也跳过，
     * 避免上线当天把所有历史记录都误判成"长期未流转"。
     */
    private void runPmExecutorProgressStall(LocalDate today, Date batchDate, List<CollaborationTracking> all) {
        Map<Long, String> accountNameById = buildAccountNameIndex(all);

        int mildMaxDays = thresholdCache.getInt(ReminderCategory.PM_EXECUTOR_PROGRESS_STALL, "TIER_MILD_MAX_DAYS", 3);
        int moderateMaxDays = thresholdCache.getInt(ReminderCategory.PM_EXECUTOR_PROGRESS_STALL, "TIER_MODERATE_MAX_DAYS", 7);

        Map<String, List<ProgressReminderDetail>> byKey = new LinkedHashMap<>();
        Map<String, Long> pmIdByKey = new HashMap<>();
        Map<String, OverdueUrgency> urgencyByKey = new HashMap<>();
        Map<String, Set<Long>> involvedByKey = new HashMap<>();

        for (CollaborationTracking t : all) {
            Integer threshold = stallThreshold(t.getProgress());
            if (threshold == null || t.getProgressChangedAt() == null || t.getProjectManagerId() == null) continue;
            int workdays = WorkdayUtil.countWeekdaysInclusive(toLocalDate(t.getProgressChangedAt()), today);
            int overdueDays = workdays - threshold;
            OverdueUrgency urgency = OverdueUrgency.fromOverdueDays(overdueDays, mildMaxDays, moderateMaxDays);
            if (urgency == null) continue;

            Set<Long> involvedExecutor = (t.getExecutorId() != null && !t.getExecutorId().equals(t.getProjectManagerId()))
                    ? Collections.singleton(t.getExecutorId()) : Collections.emptySet();
            addToOwnerBucket(byKey, pmIdByKey, urgencyByKey, involvedByKey,
                    t.getProjectManagerId(), urgency,
                    buildStallDetail(t, accountNameById, overdueDays, threshold), involvedExecutor);
        }

        for (Map.Entry<String, List<ProgressReminderDetail>> entry : byKey.entrySet()) {
            String key = entry.getKey();
            saveStallReminder(batchDate, ReminderCategory.PM_EXECUTOR_PROGRESS_STALL,
                    pmIdByKey.get(key), "项目负责人", urgencyByKey.get(key), entry.getValue(),
                    "笔视频项目进度长时间未流转", involvedByKey.get(key));
        }
    }

    /** 按项目负责人归类的通用累加器，PM_EXECUTOR_PROGRESS_STALL/REQUIREMENT_INVOICE_OVERDUE 共用 */
    private void addToOwnerBucket(Map<String, List<ProgressReminderDetail>> byKey, Map<String, Long> pmIdByKey,
                                    Map<String, OverdueUrgency> urgencyByKey, Map<String, Set<Long>> involvedByKey,
                                    Long projectManagerId, OverdueUrgency urgency, ProgressReminderDetail detail,
                                    Set<Long> involvedExecutorIds) {
        String key = projectManagerId + "|" + urgency.name();
        byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(detail);
        pmIdByKey.put(key, projectManagerId);
        urgencyByKey.put(key, urgency);
        if (involvedExecutorIds != null && !involvedExecutorIds.isEmpty()) {
            involvedByKey.computeIfAbsent(key, k -> new LinkedHashSet<>()).addAll(involvedExecutorIds);
        }
    }

    /** PENDING_INFLUENCER_ORDER（待红人下单，2026-08-17 新增）/INFLUENCER_ORDERED（红人已下单）
     * 各自单独一档阈值；6个中间状态另一档阈值；其余（含终态）不生成提醒。三档阈值都可在
     * "进度提醒阈值维护"里改，默认值分别是4/5/3工作日 */
    private Integer stallThreshold(CollaborationProgress progress) {
        if (progress == CollaborationProgress.PENDING_INFLUENCER_ORDER) {
            return thresholdCache.getInt(ReminderCategory.PM_EXECUTOR_PROGRESS_STALL, "STALL_THRESHOLD_PENDING_ORDER", 4);
        }
        if (progress == CollaborationProgress.INFLUENCER_ORDERED) {
            return thresholdCache.getInt(ReminderCategory.PM_EXECUTOR_PROGRESS_STALL, "STALL_THRESHOLD_ORDERED", 5);
        }
        if (progress != null && PM_EXECUTOR_3DAY_STATES.contains(progress)) {
            return thresholdCache.getInt(ReminderCategory.PM_EXECUTOR_PROGRESS_STALL, "STALL_THRESHOLD_MID", 3);
        }
        return null;
    }

    /** 财务视角的滞留候选状态：已发布（未结算）/已加入客户未结算列表，到了客户已结算就不算了 */
    private boolean isFinanceStallCandidate(CollaborationProgress progress) {
        return progress == CollaborationProgress.PUBLISHED_UNSETTLED
                || progress == CollaborationProgress.JOINED_CLIENT_UNSETTLED_LIST;
    }

    /**
     * Part D（2026-07 新增，2026-07 修正两次，2026-08 再次放宽）：财务视角，"已发布（未结算）"/
     * "已加入客户未结算列表"长时间没到"客户已结算"，阈值统一14工作日。财务按角色整体可见
     * （audienceEmployeeRole="财务"），不做按人定向。
     *
     * 按"视频项目进度"和严重度两个维度分桶——两个阶段分开报数，不合并成一句笼统的提醒，
     * 财务能一眼看出是卡在"已发布（未结算）"还是"已加入客户未结算列表"没往下流转。
     *
     * 严重度判定 2026-07 改成"临近阈值"模式，跟"临近结款"提醒（ReminderUrgency）用同一套
     * 档位，不再是"超出阈值之后才算"：距离14个工作日阈值还有4-7天=3-7天档（绿），还有1-3天=
     * 1-3天档（橙），到了/超过阈值=0天或已超期档（红）；还剩8天以上不提醒。之前用的
     * OverdueUrgency 是"超出阈值之后才分档"（1-3/4-7/8+天超出），这里改用 ReminderUrgency
     * 本身就是"距离阈值还有几天"的语义，直接复用即可，不需要另外发明一套。
     *
     * 2026-08 新增（Shawn 反馈）：财务在推进这两个阶段时经常需要项目负责人/执行人员配合
     * （比如催红人补invoice、核对信息），不能只有财务自己看得到——这里额外按项目负责人归类
     * （跟 runPmExecutorProgressStall 同一套"按人定向"机制：ProgressReminder.audienceEmployeeId
     * = 项目负责人，涉及的执行人员通过 involvedEmployeeIds 顺带获得可见性），生成一批独立的
     * 定向卡片，跟财务角色整体可见的那批卡片并存、互不影响——不合并、不拆分角色整体可见的
     * 那批（财务视角仍然按进度阶段分两张卡）。不提醒给"IT后勤"（IT后勤不是这条记录的负责人/
     * 执行人员，没有直接关联）。两批卡片指向同一批底层记录，"标记已处理"按 (category,
     * trackingId) 定位，两边共用同一份状态，不会各自为政。
     */
    private void runFinanceProgressStall(LocalDate today, Date batchDate, List<CollaborationTracking> all) {
        Map<Long, String> accountNameById = buildAccountNameIndex(all);

        int stallThreshold = thresholdCache.getInt(ReminderCategory.FINANCE_PROGRESS_STALL, "STALL_THRESHOLD", 14);
        int overdueMaxDays = thresholdCache.getInt(ReminderCategory.FINANCE_PROGRESS_STALL, "TIER_OVERDUE_MAX_DAYS", 0);
        int nearMaxDays = thresholdCache.getInt(ReminderCategory.FINANCE_PROGRESS_STALL, "TIER_NEAR_MAX_DAYS", 3);
        int windowMaxDays = thresholdCache.getInt(ReminderCategory.FINANCE_PROGRESS_STALL, "TIER_WINDOW_MAX_DAYS", 7);

        Map<CollaborationProgress, Map<ReminderUrgency, List<ProgressReminderDetail>>> byProgressAndUrgency
                = new EnumMap<>(CollaborationProgress.class);

        // 按项目负责人归类的累加器（跟 addToOwnerBucket 是同一个思路，只是这里用 ReminderUrgency
        // 而不是 OverdueUrgency，addToOwnerBucket 类型写死了 OverdueUrgency，不方便直接复用）
        Map<String, List<ProgressReminderDetail>> byPmKey = new LinkedHashMap<>();
        Map<String, Long> pmIdByKey = new HashMap<>();
        Map<String, ReminderUrgency> pmUrgencyByKey = new HashMap<>();
        Map<String, Set<Long>> pmInvolvedByKey = new HashMap<>();

        for (CollaborationTracking t : all) {
            if (!isFinanceStallCandidate(t.getProgress()) || t.getProgressChangedAt() == null) continue;
            int workdays = WorkdayUtil.countWeekdaysInclusive(toLocalDate(t.getProgressChangedAt()), today);
            int daysRemaining = stallThreshold - workdays; // 正数=离阈值还有几天，0或负数=已到/超过阈值
            ReminderUrgency urgency = ReminderUrgency.fromDaysRemaining(daysRemaining, overdueMaxDays, nearMaxDays, windowMaxDays);
            if (urgency == null) continue;
            int overdueDays = Math.max(0, -daysRemaining); // 明细"超出天数"列用，还没到阈值时是0
            byProgressAndUrgency
                    .computeIfAbsent(t.getProgress(), k -> new EnumMap<>(ReminderUrgency.class))
                    .computeIfAbsent(urgency, k -> new ArrayList<>())
                    .add(buildStallDetail(t, accountNameById, overdueDays, stallThreshold));

            // 项目负责人定向的这份需要一份独立的 detail 对象——同一个 ProgressReminderDetail
            // 实例不能同时属于两条不同的 ProgressReminder（reminderId 是落库时才回填的外键，
            // 后写的一次会覆盖前面那次），所以这里用 buildStallDetail() 重新构建一份，不能
            // 复用上面那份
            if (t.getProjectManagerId() != null) {
                Set<Long> involvedExecutor = (t.getExecutorId() != null && !t.getExecutorId().equals(t.getProjectManagerId()))
                        ? Collections.singleton(t.getExecutorId()) : Collections.emptySet();
                String key = t.getProjectManagerId() + "|" + urgency.name();
                byPmKey.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(buildStallDetail(t, accountNameById, overdueDays, stallThreshold));
                pmIdByKey.put(key, t.getProjectManagerId());
                pmUrgencyByKey.put(key, urgency);
                if (!involvedExecutor.isEmpty()) {
                    pmInvolvedByKey.computeIfAbsent(key, k -> new LinkedHashSet<>()).addAll(involvedExecutor);
                }
            }
        }

        for (Map.Entry<CollaborationProgress, Map<ReminderUrgency, List<ProgressReminderDetail>>> progressEntry
                : byProgressAndUrgency.entrySet()) {
            String titleSuffix = "笔视频项目进度长时间在“" + progressEntry.getKey().getLabel() + "”未流转";
            for (ReminderUrgency urgency : ReminderUrgency.values()) {
                List<ProgressReminderDetail> details = progressEntry.getValue().get(urgency);
                if (details == null || details.isEmpty()) continue;
                saveFinanceStallReminder(batchDate, ReminderCategory.FINANCE_PROGRESS_STALL,
                        FINANCE_ROLE, urgency, details, titleSuffix);
            }
        }

        // 项目负责人定向卡片：不像财务视角那样按进度阶段拆两张卡——项目负责人视角只关心
        // "我名下有几笔卡在财务这边没结算"，不需要区分具体卡在哪个阶段
        for (Map.Entry<String, List<ProgressReminderDetail>> entry : byPmKey.entrySet()) {
            String key = entry.getKey();
            saveFinancePmStallReminder(batchDate, pmIdByKey.get(key), pmUrgencyByKey.get(key),
                    entry.getValue(), pmInvolvedByKey.get(key));
        }
    }

    /** runFinanceProgressStall() 的项目负责人定向卡片专用，跟 saveFinanceStallReminder()
     * 共用 ReminderUrgency 语义，但按具体项目负责人（audienceEmployeeId）+ 涉及的执行人员
     * （involvedEmployeeIds）定向，标题格式仿 saveStallReminder() 的"项目负责人-XX-手下的"。 */
    private void saveFinancePmStallReminder(Date batchDate, Long audienceEmployeeId, ReminderUrgency urgency,
                                              List<ProgressReminderDetail> details, Set<Long> involvedExecutorIds) {
        ProgressReminder reminder = new ProgressReminder();
        reminder.setIsDeleted(false);
        reminder.setBatchDate(batchDate);
        reminder.setCategory(ReminderCategory.FINANCE_PROGRESS_STALL);
        reminder.setUrgency(urgency);
        reminder.setAudienceEmployeeRole("项目负责人");
        reminder.setAudienceEmployeeId(audienceEmployeeId);
        if (involvedExecutorIds != null && !involvedExecutorIds.isEmpty()) {
            reminder.setInvolvedEmployeeIds(involvedExecutorIds.stream()
                    .map(String::valueOf).collect(Collectors.joining("\n")));
        }
        reminder.setCount(details.size());
        Employee emp = employeeCache.findById(audienceEmployeeId);
        String empName = emp != null ? emp.getName() : ("员工#" + audienceEmployeeId);
        reminder.setTitle("项目负责人-" + empName + "-手下的" + details.size() + "笔视频项目进度长时间未结算");
        reminder = reminderRepo.save(reminder);
        for (ProgressReminderDetail d : details) d.setReminderId(reminder.getId());
        detailRepo.saveAll(details);
    }

    /**
     * 跟 saveStallReminder 类似，但用"临近结款"那一套 ReminderUrgency（而不是 OverdueUrgency），
     * 目前只有 runFinanceProgressStall 用——财务视角的进度滞留提醒 2026-07 改成"临近阈值"模式，
     * 需要真正的 urgency 值（不是占位），其余两类"进度滞留-项目"/"Invoice逾期"仍然是"超出阈值
     * 之后才算"，继续走 saveStallReminder/OverdueUrgency，不受影响。
     */
    private void saveFinanceStallReminder(Date batchDate, ReminderCategory category, String audienceRoleLabel,
                                            ReminderUrgency urgency, List<ProgressReminderDetail> details,
                                            String titleSuffix) {
        ProgressReminder reminder = new ProgressReminder();
        reminder.setIsDeleted(false);
        reminder.setBatchDate(batchDate);
        reminder.setCategory(category);
        reminder.setUrgency(urgency);
        reminder.setAudienceEmployeeRole(audienceRoleLabel);
        reminder.setCount(details.size());
        reminder.setTitle(details.size() + titleSuffix);
        reminder = reminderRepo.save(reminder);
        for (ProgressReminderDetail d : details) d.setReminderId(reminder.getId());
        detailRepo.saveAll(details);
    }

    /**
     * Part E（2026-07 新增，2026-07 修正）：需求完成进度100%后长时间未上传Invoice。只提醒
     * "涉及invoice上传"的品牌方；阈值5工作日，基准时间是 InfluencerRequirement.completedAt。
     * 同 Part C 的修正：按需求关联的合作跟踪记录的项目负责人归类（不再单独按执行人员归类），
     * 一个需求下如果关联的视频分属不同项目负责人，各自的负责人都各生成一条（每个人对自己
     * 负责的那部分确实负有责任，不算重复）；同一负责人名下涉及的执行人员汇总进这个负责人的
     * "涉及员工"集合，供执行人员本人也能看到（但查看详情时明细会按自己实际执行的那部分过滤）。
     */
    private void runRequirementInvoiceOverdue(LocalDate today, Date batchDate) {
        List<InfluencerRequirement> candidates = requirementRepo.findByIsDeletedFalseAndCompletedAtIsNotNullAndInvoiceLinkIsNull();
        if (candidates.isEmpty()) return;

        // 2026-08-16 修复：之前在下面的循环里逐条需求查一次关联的合作跟踪记录，是个随"完成后
        // 长时间未上传Invoice的需求数"线性增长的 N+1——这批候选是只会越攒越多、直到有人补上
        // Invoice才会减少的存量数据，不会自然清零，此方法又是"项目流转后更新提示内容"手动重算
        // 按钮会同步触发的调用，慢到超出网关/浏览器超时就会表现成"点了没反应、报网络连接失败"，
        // 后台还没来得及报错（见 CollaborationTrackingRepository.
        // findByInternalRequirementNoInAndIsDeletedFalse 的说明）。改成一次性批量查回来，按
        // internalRequirementNo 分组，循环里只读内存 Map，不再逐条查库。
        List<String> candidateNos = candidates.stream()
                .map(InfluencerRequirement::getInternalRequirementNo)
                .filter(java.util.Objects::nonNull).collect(Collectors.toList());
        Map<String, List<CollaborationTracking>> linkedByNo = trackingRepo
                .findByInternalRequirementNoInAndIsDeletedFalse(candidateNos).stream()
                .collect(Collectors.groupingBy(CollaborationTracking::getInternalRequirementNo));
        // 同一趟顺手把 buildRequirementOverdueDetail 需要的红人账号名也批量查好（原来是
        // 逐条需求单独查一次 influencerRepo.findById()，同一类 N+1，见该方法的说明）
        Map<Long, String> accountNameByInfluencerId = buildAccountNameIndexForRequirements(candidates);

        Map<String, List<ProgressReminderDetail>> byKey = new LinkedHashMap<>();
        Map<String, Long> pmIdByKey = new HashMap<>();
        Map<String, OverdueUrgency> urgencyByKey = new HashMap<>();
        Map<String, Set<Long>> involvedByKey = new HashMap<>();

        int overdueThreshold = thresholdCache.getInt(ReminderCategory.REQUIREMENT_INVOICE_OVERDUE, "OVERDUE_THRESHOLD", 5);
        int mildMaxDays = thresholdCache.getInt(ReminderCategory.REQUIREMENT_INVOICE_OVERDUE, "TIER_MILD_MAX_DAYS", 3);
        int moderateMaxDays = thresholdCache.getInt(ReminderCategory.REQUIREMENT_INVOICE_OVERDUE, "TIER_MODERATE_MAX_DAYS", 7);

        for (InfluencerRequirement r : candidates) {
            Brand brand = r.getBrandId() != null ? brandCache.findById(r.getBrandId()) : null;
            if (brand != null && !brand.requiresInvoiceUpload()) continue; // 不涉及invoice的品牌方不提醒
            int workdays = WorkdayUtil.countWeekdaysInclusive(toLocalDate(r.getCompletedAt()), today);
            int overdueDays = workdays - overdueThreshold;
            OverdueUrgency urgency = OverdueUrgency.fromOverdueDays(overdueDays, mildMaxDays, moderateMaxDays);
            if (urgency == null) continue;

            List<CollaborationTracking> linked = linkedByNo.getOrDefault(r.getInternalRequirementNo(), Collections.emptyList());
            if (linked.isEmpty()) continue; // 理论上不会发生（completedAt本身就是靠这些记录算出来的），防御性跳过
            Long placeholderTrackingId = linked.get(0).getId();

            // 按项目负责人分组，汇总每个负责人名下涉及的执行人员
            Map<Long, Set<Long>> executorsByPm = new LinkedHashMap<>();
            for (CollaborationTracking t : linked) {
                if (t.getProjectManagerId() == null) continue;
                Set<Long> execs = executorsByPm.computeIfAbsent(t.getProjectManagerId(), k -> new LinkedHashSet<>());
                if (t.getExecutorId() != null && !t.getExecutorId().equals(t.getProjectManagerId())) {
                    execs.add(t.getExecutorId());
                }
            }
            for (Map.Entry<Long, Set<Long>> pmEntry : executorsByPm.entrySet()) {
                addToOwnerBucket(byKey, pmIdByKey, urgencyByKey, involvedByKey,
                        pmEntry.getKey(), urgency,
                        buildRequirementOverdueDetail(r, brand, placeholderTrackingId, overdueDays, overdueThreshold, accountNameByInfluencerId),
                        pmEntry.getValue());
            }
        }

        for (Map.Entry<String, List<ProgressReminderDetail>> entry : byKey.entrySet()) {
            String key = entry.getKey();
            saveStallReminder(batchDate, ReminderCategory.REQUIREMENT_INVOICE_OVERDUE,
                    pmIdByKey.get(key), "项目负责人", urgencyByKey.get(key), entry.getValue(),
                    "个需求完成后长时间未上传Invoice", involvedByKey.get(key));
        }
    }

    /**
     * Part F（2026-07 新增，2026-07 改成团队优先/品牌方兜底）：需求完成进度100%后长时间未上传
     * 合同——只针对"每次需求签一次合同"的场景（{@link InfluencerTeam#isPerRequirementContract}：
     * 先看需求关联的团队有没有覆盖设置，没有就退回品牌方级别配置）；"一年签一次合同"的场景暂时
     * 不做这类提醒（那种场景改由团队级维护年度合同，不是每个需求单独催，见
     * runContractExpiringSoon）。阈值14工作日，分组/归类逻辑完全跟 Part E（Invoice逾期）一致，
     * 按需求关联的合作跟踪记录的项目负责人归类。
     */
    private void runRequirementContractOverdue(LocalDate today, Date batchDate) {
        List<InfluencerRequirement> candidates =
                requirementRepo.findByIsDeletedFalseAndCompletedAtIsNotNullAndContractLinkIsNull();
        if (candidates.isEmpty()) return;

        // 2026-08-16 修复：同 runRequirementInvoiceOverdue 的说明——之前逐条需求查一次关联的
        // 合作跟踪记录，是随候选需求数线性增长的 N+1，改成批量查回来按 internalRequirementNo 分组
        List<String> candidateNos = candidates.stream()
                .map(InfluencerRequirement::getInternalRequirementNo)
                .filter(java.util.Objects::nonNull).collect(Collectors.toList());
        Map<String, List<CollaborationTracking>> linkedByNo = trackingRepo
                .findByInternalRequirementNoInAndIsDeletedFalse(candidateNos).stream()
                .collect(Collectors.groupingBy(CollaborationTracking::getInternalRequirementNo));
        Map<Long, String> accountNameByInfluencerId = buildAccountNameIndexForRequirements(candidates);

        Map<String, List<ProgressReminderDetail>> byKey = new LinkedHashMap<>();
        Map<String, Long> pmIdByKey = new HashMap<>();
        Map<String, OverdueUrgency> urgencyByKey = new HashMap<>();
        Map<String, Set<Long>> involvedByKey = new HashMap<>();

        int overdueThreshold = thresholdCache.getInt(ReminderCategory.REQUIREMENT_CONTRACT_OVERDUE, "OVERDUE_THRESHOLD", 14);
        int mildMaxDays = thresholdCache.getInt(ReminderCategory.REQUIREMENT_CONTRACT_OVERDUE, "TIER_MILD_MAX_DAYS", 3);
        int moderateMaxDays = thresholdCache.getInt(ReminderCategory.REQUIREMENT_CONTRACT_OVERDUE, "TIER_MODERATE_MAX_DAYS", 7);

        for (InfluencerRequirement r : candidates) {
            Brand brand = r.getBrandId() != null ? brandCache.findById(r.getBrandId()) : null;
            InfluencerTeam team = r.getTeamId() != null ? teamCache.findById(r.getTeamId()) : null;
            if (!InfluencerTeam.isPerRequirementContract(brand, team)) continue; // 一年签一次合同不在这提醒
            int workdays = WorkdayUtil.countWeekdaysInclusive(toLocalDate(r.getCompletedAt()), today);
            int overdueDays = workdays - overdueThreshold;
            OverdueUrgency urgency = OverdueUrgency.fromOverdueDays(overdueDays, mildMaxDays, moderateMaxDays);
            if (urgency == null) continue;

            List<CollaborationTracking> linked = linkedByNo.getOrDefault(r.getInternalRequirementNo(), Collections.emptyList());
            if (linked.isEmpty()) continue; // 理论上不会发生，防御性跳过
            Long placeholderTrackingId = linked.get(0).getId();

            Map<Long, Set<Long>> executorsByPm = new LinkedHashMap<>();
            for (CollaborationTracking t : linked) {
                if (t.getProjectManagerId() == null) continue;
                Set<Long> execs = executorsByPm.computeIfAbsent(t.getProjectManagerId(), k -> new LinkedHashSet<>());
                if (t.getExecutorId() != null && !t.getExecutorId().equals(t.getProjectManagerId())) {
                    execs.add(t.getExecutorId());
                }
            }
            for (Map.Entry<Long, Set<Long>> pmEntry : executorsByPm.entrySet()) {
                addToOwnerBucket(byKey, pmIdByKey, urgencyByKey, involvedByKey,
                        pmEntry.getKey(), urgency,
                        buildRequirementOverdueDetail(r, brand, placeholderTrackingId, overdueDays, overdueThreshold, accountNameByInfluencerId),
                        pmEntry.getValue());
            }
        }

        for (Map.Entry<String, List<ProgressReminderDetail>> entry : byKey.entrySet()) {
            String key = entry.getKey();
            saveStallReminder(batchDate, ReminderCategory.REQUIREMENT_CONTRACT_OVERDUE,
                    pmIdByKey.get(key), "项目负责人", urgencyByKey.get(key), entry.getValue(),
                    "个需求完成后长时间未上传合同", involvedByKey.get(key));
        }
    }

    /**
     * Part G（2026-07 新增，2026-08 改造）：品牌方/团队"一年签一次合同"场景下，合同即将到期/
     * 已过期的提醒。候选范围按"当前存在合作关系"判断（不是按某次需求是否完结）——只要
     * (品牌方,团队) 这个组合下有任意一条未删除的合作跟踪记录（不管挂在哪个红人名下），且这个
     * 组合的合同签订周期是"一年签一次"（InfluencerTeam.isPerRequirementContract 判定为
     * false），就纳入候选；不管底下有多少个红人、多少条视频/跟踪记录，只按这个组合整体判断
     * 一次、生成一条提醒明细。
     *
     * 2026-08 起：合同从"挂在红人身上"改成"团队级"（TeamContract，团队下所有红人共用同一份
     * 合同，不再各自维护）——去重维度相应从"红人+品牌方+团队"三元组收窄成"品牌方+团队"二元组，
     * 同一团队下不同红人不再各自出一条明细。到期日优先级：这个 (品牌方,团队) 组合"当前"的
     * TeamContract（可能有多条历史合同，取 endDate 最大的一条）> 团队的兜底默认有效期
     * （InfluencerTeam.defaultContractEndDate）> 都没有则跳过（没数据没法判断）。
     *
     * 到期前30天开始提醒，复用 ReminderUrgency 这个枚举类型（跟"进度滞留-财务"一样，只是借用
     * 类型和红/橙/绿三个颜色值，语义换成这里自定义的0/14/30天窗口，不是原来的0/3/7天）——
     * 前端对 CONTRACT_EXPIRING_SOON 这个类别单独换了一套label/color映射（黄/橙/红），
     * 见 ProgressReminderCardList.vue。
     *
     * 涉及的项目负责人各自一张卡（跟 Part E/F 一致），执行人员通过 involvedEmployeeIds 获得
     * 可见性。这一类不支持"标记已处理"——TeamContract 不是 BaseEntity，没有 updatedAt，
     * 没有可靠的"业务记录是否已经变化"时间戳可用。每天3点主批次会自动跑一次，2026-08 起
     * 也能通过"项目流转后更新提示内容"手动立即触发（见 PROJECT_FLOW_RECOMPUTE_CATEGORIES）。
     */
    private void runContractExpiringSoon(LocalDate today, Date batchDate, List<CollaborationTracking> all) {
        // 按 (品牌方,团队) 二元组去重成候选合同关系（不看进度/是否完结、不看具体是哪个红人）
        Map<String, List<CollaborationTracking>> byBrandTeam = new LinkedHashMap<>();
        for (CollaborationTracking t : all) {
            if (t.getInfluencerId() == null || t.getBrandId() == null) continue;
            String key = t.getBrandId() + "|" + (t.getTeamId() != null ? t.getTeamId() : -1L);
            byBrandTeam.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }
        if (byBrandTeam.isEmpty()) return;

        // 批量查这批 (品牌方,团队) 组合的团队级合同，避免逐个组合查库；同一组合下可能有多条
        // 历史合同，取 endDate 最大的一条作为"当前"合同
        Set<Long> teamIds = new HashSet<>();
        Set<Long> brandIdsWithoutTeam = new HashSet<>();
        for (List<CollaborationTracking> group : byBrandTeam.values()) {
            CollaborationTracking sample = group.get(0);
            if (sample.getTeamId() != null) teamIds.add(sample.getTeamId());
            else brandIdsWithoutTeam.add(sample.getBrandId());
        }
        List<TeamContract> candidateContracts = new ArrayList<>();
        if (!teamIds.isEmpty()) candidateContracts.addAll(teamContractRepo.findByTeamIdIn(new ArrayList<>(teamIds)));
        if (!brandIdsWithoutTeam.isEmpty()) {
            candidateContracts.addAll(teamContractRepo.findByBrandIdInAndTeamIdIsNull(new ArrayList<>(brandIdsWithoutTeam)));
        }
        Map<String, TeamContract> currentContractByBrandTeam = new HashMap<>();
        for (TeamContract c : candidateContracts) {
            String key = c.getBrandId() + "|" + (c.getTeamId() != null ? c.getTeamId() : -1L);
            TeamContract existing = currentContractByBrandTeam.get(key);
            if (existing == null || c.getEndDate().after(existing.getEndDate())) {
                currentContractByBrandTeam.put(key, c);
            }
        }

        Map<String, List<ProgressReminderDetail>> byKey = new LinkedHashMap<>();
        Map<String, Long> pmIdByKey = new HashMap<>();
        Map<String, ReminderUrgency> urgencyByKey = new HashMap<>();
        Map<String, Set<Long>> involvedByKey = new HashMap<>();

        int expiryWindowDays = thresholdCache.getInt(ReminderCategory.CONTRACT_EXPIRING_SOON, "EXPIRY_WINDOW_DAYS", CONTRACT_EXPIRY_WINDOW_DAYS);
        int overdueMaxDays = thresholdCache.getInt(ReminderCategory.CONTRACT_EXPIRING_SOON, "TIER_OVERDUE_MAX_DAYS", 0);
        int nearMaxDays = thresholdCache.getInt(ReminderCategory.CONTRACT_EXPIRING_SOON, "TIER_NEAR_MAX_DAYS", 14);

        for (Map.Entry<String, List<CollaborationTracking>> brandTeamEntry : byBrandTeam.entrySet()) {
            List<CollaborationTracking> group = brandTeamEntry.getValue();
            CollaborationTracking sample = group.get(0);
            Brand brand = brandCache.findById(sample.getBrandId());
            InfluencerTeam team = sample.getTeamId() != null ? teamCache.findById(sample.getTeamId()) : null;
            if (InfluencerTeam.isPerRequirementContract(brand, team)) continue; // 只处理"一年签一次"的组合

            TeamContract current = currentContractByBrandTeam.get(brandTeamEntry.getKey());
            Date endDate;
            String sourceLabel;
            if (current != null) {
                endDate = current.getEndDate();
                sourceLabel = "按团队合同判断";
            } else if (team != null && team.getDefaultContractEndDate() != null) {
                endDate = team.getDefaultContractEndDate();
                sourceLabel = "按团队兜底默认有效期判断";
            } else {
                continue; // 没有任何数据来源，没法判断，跳过
            }

            long daysRemaining = ChronoUnit.DAYS.between(today, toLocalDate(endDate));
            ReminderUrgency urgency = contractExpiryUrgency(daysRemaining, expiryWindowDays, overdueMaxDays, nearMaxDays);
            if (urgency == null) continue;

            Map<Long, Set<Long>> executorsByPm = new LinkedHashMap<>();
            for (CollaborationTracking t : group) {
                if (t.getProjectManagerId() == null) continue;
                Set<Long> execs = executorsByPm.computeIfAbsent(t.getProjectManagerId(), k -> new LinkedHashSet<>());
                if (t.getExecutorId() != null && !t.getExecutorId().equals(t.getProjectManagerId())) {
                    execs.add(t.getExecutorId());
                }
            }
            for (Map.Entry<Long, Set<Long>> pmEntry : executorsByPm.entrySet()) {
                ProgressReminderDetail detail = new ProgressReminderDetail();
                detail.setIsDeleted(false);
                detail.setTrackingId(sample.getId());
                detail.setBrandName(brand != null ? brand.getName() : null);
                detail.setTeamName(team != null ? team.getName() : null);
                detail.setDemandContent(sourceLabel);
                detail.setCycleDays(expiryWindowDays);
                detail.setDeadlineDate(endDate);
                detail.setOverdueDays((int) Math.max(0, -daysRemaining));
                // 2026-08 起"查看详情"跳转到"品牌方/红人团队管理"-"管理团队"的团队级合同入口，
                // 按品牌方+团队定位，不再是某个具体红人：requirementId 复用存 teamId（该品牌方
                // 没有团队层时为空），internalRequirementNo 复用存 brandId（字符串形式），
                // 前端 goToDetail() 靠这两个字段拼跳转链接
                detail.setRequirementId(sample.getTeamId());
                detail.setInternalRequirementNo(String.valueOf(sample.getBrandId()));

                String key = pmEntry.getKey() + "|" + urgency.name();
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(detail);
                pmIdByKey.put(key, pmEntry.getKey());
                urgencyByKey.put(key, urgency);
                if (!pmEntry.getValue().isEmpty()) {
                    involvedByKey.computeIfAbsent(key, k -> new LinkedHashSet<>()).addAll(pmEntry.getValue());
                }
            }
        }

        for (Map.Entry<String, List<ProgressReminderDetail>> entry : byKey.entrySet()) {
            String key = entry.getKey();
            saveContractExpiryReminder(batchDate, pmIdByKey.get(key), urgencyByKey.get(key),
                    entry.getValue(), involvedByKey.get(key));
        }
    }

    /** 到期前 windowDays 天开始提醒：overdueMaxDays天或已过期=OVERDUE，overdueMaxDays+1~nearMaxDays天=NEAR，
     * nearMaxDays+1~windowDays天=UPCOMING（借用 ReminderUrgency 类型，语义/窗口不是原来的
     * 0/3/7天，前端按类别单独映射颜色/文案）。windowDays/overdueMaxDays/nearMaxDays 默认30/0/14，
     * 可在"进度提醒阈值维护"里改 */
    private ReminderUrgency contractExpiryUrgency(long daysRemaining, int windowDays, int overdueMaxDays, int nearMaxDays) {
        if (daysRemaining > windowDays) return null;
        if (daysRemaining <= overdueMaxDays) return ReminderUrgency.OVERDUE;
        if (daysRemaining <= nearMaxDays) return ReminderUrgency.NEAR;
        return ReminderUrgency.UPCOMING;
    }

    private void saveContractExpiryReminder(Date batchDate, Long audienceEmployeeId, ReminderUrgency urgency,
                                              List<ProgressReminderDetail> details, Set<Long> involvedExecutorIds) {
        ProgressReminder reminder = new ProgressReminder();
        reminder.setIsDeleted(false);
        reminder.setBatchDate(batchDate);
        reminder.setCategory(ReminderCategory.CONTRACT_EXPIRING_SOON);
        reminder.setUrgency(urgency);
        reminder.setAudienceEmployeeRole("项目负责人");
        reminder.setAudienceEmployeeId(audienceEmployeeId);
        if (involvedExecutorIds != null && !involvedExecutorIds.isEmpty()) {
            reminder.setInvolvedEmployeeIds(involvedExecutorIds.stream()
                    .map(String::valueOf).collect(Collectors.joining("\n")));
        }
        reminder.setCount(details.size());
        Employee emp = audienceEmployeeId != null ? employeeCache.findById(audienceEmployeeId) : null;
        String empName = emp != null ? emp.getName() : ("员工#" + audienceEmployeeId);
        reminder.setTitle("项目负责人-" + empName + "-手下的" + details.size() + "个品牌方团队，合同签订周期即将到期或已到期，请跟进续签");
        reminder = reminderRepo.save(reminder);
        for (ProgressReminderDetail d : details) d.setReminderId(reminder.getId());
        detailRepo.saveAll(details);
    }

    /**
     * Part H（2026-08-16 新增）：存在未处理的"删除审核"（PendingApprovalCategory.DELETE_REQUEST）。
     * 不分档、不按具体人定向——一条汇总卡片，只有 ADMIN 账号能看到（见 ADMIN_ONLY_CATEGORIES）。
     * 不生成 ProgressReminderDetail 明细行：具体每一条申请要处理，直接去"待处理"页面下方那张
     * ADMIN 专属的审批表格操作（本来就有同意/拒绝按钮），这里的卡片只起"提醒你该去看看了"的
     * 作用，不重复造一套只读的详情弹窗。
     */
    private void runDeleteRequestPending(Date batchDate) {
        savePendingApprovalReminder(batchDate, ReminderCategory.DELETE_REQUEST_PENDING,
                PendingApprovalCategory.DELETE_REQUEST, "删除申请");
    }

    /**
     * Part I（2026-08-16 新增）：存在未处理的"视频项目进度倒退审核"（PendingApprovalCategory.
     * PROGRESS_ROLLBACK）。跟 runDeleteRequestPending 完全同一套规则（ADMIN 专属、不分档、
     * 无明细）。
     */
    private void runProgressRollbackPending(Date batchDate) {
        savePendingApprovalReminder(batchDate, ReminderCategory.PROGRESS_ROLLBACK_PENDING,
                PendingApprovalCategory.PROGRESS_ROLLBACK, "视频项目进度倒退申请");
    }

    /** runDeleteRequestPending/runProgressRollbackPending 共用：按类别数一下还有多少条
     *  待审核事项，有就生成一条汇总卡片（ADMIN 专属，见类别上的 ADMIN_ONLY_CATEGORIES）。 */
    private void savePendingApprovalReminder(Date batchDate, ReminderCategory reminderCategory,
                                              PendingApprovalCategory approvalCategory, String noun) {
        long pendingCount = pendingApprovalRepo.countByCategoryAndStatus(approvalCategory, PendingApprovalStatus.PENDING);
        if (pendingCount == 0) return;
        ProgressReminder reminder = new ProgressReminder();
        reminder.setIsDeleted(false);
        reminder.setBatchDate(batchDate);
        reminder.setCategory(reminderCategory);
        reminder.setUrgency(ReminderUrgency.OVERDUE);
        // 只作展示用途，真正的可见性判断走 ADMIN_ONLY_CATEGORIES（见该常量注释），这里填"ADMIN"
        // 只是满足 audience_employee_role 这个历史 NOT NULL 列，不代表按这个字符串做权限判断
        reminder.setAudienceEmployeeRole("ADMIN");
        reminder.setCount((int) pendingCount);
        reminder.setTitle("有 " + pendingCount + " 条" + noun + "等待审核，请及时处理");
        reminderRepo.save(reminder);
    }

    /**
     * Part J（2026-08-16 新增）：存在未处理的"内部执行成本修改审核"（PendingApprovalCategory.
     * EXECUTOR_COST_MODIFY）——审核人是该记录的项目负责人本人，不是 ADMIN（见
     * PendingApprovalService.assertCanResolve()），所以按 targetProjectManagerId 分组，每个
     * 负责人名下有几条未处理的就各自生成一条卡片，走 EMPLOYEE_OWNED_CATEGORIES 那套"管理层/
     * ADMIN 全量可见 + 负责人本人按 audienceEmployeeId 命中"的既有受众机制，不需要像
     * DELETE_REQUEST_PENDING 那样单独收窄。不分档、不生成明细（理由同 runDeleteRequestPending——
     * 具体处理走 MyExecutorCostApprovalList.vue 现成的"待我审核"入口）。
     */
    private void runExecutorCostModifyPending(Date batchDate) {
        List<PendingApproval> pending = pendingApprovalRepo.findByCategoryAndStatus(
                PendingApprovalCategory.EXECUTOR_COST_MODIFY, PendingApprovalStatus.PENDING);
        if (pending.isEmpty()) return;

        Map<Long, Integer> countByPm = new LinkedHashMap<>();
        for (PendingApproval p : pending) {
            if (p.getTargetProjectManagerId() == null) continue; // 防御性跳过，理论上不会发生
            countByPm.merge(p.getTargetProjectManagerId(), 1, Integer::sum);
        }
        for (Map.Entry<Long, Integer> entry : countByPm.entrySet()) {
            Long pmId = entry.getKey();
            int count = entry.getValue();
            ProgressReminder reminder = new ProgressReminder();
            reminder.setIsDeleted(false);
            reminder.setBatchDate(batchDate);
            reminder.setCategory(ReminderCategory.EXECUTOR_COST_MODIFY_PENDING);
            reminder.setUrgency(ReminderUrgency.OVERDUE);
            reminder.setAudienceEmployeeRole("项目负责人");
            reminder.setAudienceEmployeeId(pmId);
            reminder.setCount(count);
            Employee emp = employeeCache.findById(pmId);
            String empName = emp != null ? emp.getName() : ("员工#" + pmId);
            reminder.setTitle("项目负责人-" + empName + "-有 " + count + " 条内部执行成本修改申请等待你审核");
            reminderRepo.save(reminder);
        }
    }

    // ---- Part C/D/E/F/G 共用的小工具 ----

    private Map<Long, String> buildAccountNameIndex(List<CollaborationTracking> list) {
        Set<Long> influencerIds = new HashSet<>();
        for (CollaborationTracking t : list) if (t.getInfluencerId() != null) influencerIds.add(t.getInfluencerId());
        Map<Long, String> result = new HashMap<>();
        if (!influencerIds.isEmpty()) {
            for (Influencer inf : influencerRepo.findAllById(influencerIds)) result.put(inf.getId(), inf.getAccountName());
        }
        return result;
    }

    /** buildRequirementOverdueDetail 专用的批量版（2026-08-16 新增），道理跟上面的
     *  buildAccountNameIndex 完全一样，只是来源列表类型不同（需求而不是合作跟踪记录） */
    private Map<Long, String> buildAccountNameIndexForRequirements(List<InfluencerRequirement> list) {
        Set<Long> influencerIds = new HashSet<>();
        for (InfluencerRequirement r : list) if (r.getInfluencerId() != null) influencerIds.add(r.getInfluencerId());
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
        // cycleDays/deadlineDate 是历史 NOT NULL 列，这两类没有"结款周期"这个概念，借用来存
        // "提醒阈值工作日数"/"进度最近变化的日期"（deadlineDate 只用于明细排序，不再展示成列）
        detail.setCycleDays(thresholdWorkdays);
        detail.setDeadlineDate(toDate(toLocalDate(t.getProgressChangedAt())));
        detail.setOverdueDays(overdueDays);
        detail.setPlatform(t.getPlatform());
        detail.setVideoTypeLabel(t.getVideoType() != null ? t.getVideoType().getLabel() : null);
        detail.setClientPrice(t.getClientPrice());
        Employee executor = t.getExecutorId() != null ? employeeCache.findById(t.getExecutorId()) : null;
        detail.setExecutorName(executor != null ? executor.getName() : null);
        return detail;
    }

    private ProgressReminderDetail buildRequirementOverdueDetail(InfluencerRequirement r, Brand brand,
                                                                    Long placeholderTrackingId, int overdueDays,
                                                                    int thresholdDays, Map<Long, String> accountNameByInfluencerId) {
        ProgressReminderDetail detail = new ProgressReminderDetail();
        detail.setIsDeleted(false);
        // trackingId 是历史 NOT NULL 列，这一类没有单一对应的合作跟踪记录，随便挑该需求下
        // 一条关联记录的 id 占位；"查看详情"实际跳转按下面的 requirementId/internalRequirementNo
        detail.setTrackingId(placeholderTrackingId);
        detail.setBrandName(brand != null ? brand.getName() : null);
        InfluencerTeam team = r.getTeamId() != null ? teamCache.findById(r.getTeamId()) : null;
        detail.setTeamName(team != null ? team.getName() : null);
        // 2026-08-16 修复：之前这里用 influencerRepo.findById() 逐条查，是又一个随候选需求数
        // 线性增长的 N+1（见 runRequirementInvoiceOverdue/runRequirementContractOverdue 顶部
        // 的说明），改成调用方批量查好传进来
        detail.setAccountName(r.getInfluencerId() != null ? accountNameByInfluencerId.get(r.getInfluencerId()) : null);
        // cycleDays 这里复用成"需求条目总数"（不是天数）；deadlineDate 只用于明细排序，不展示成列；
        // influencerCost/clientPrice 复用成"需求总成本"/"需求客户合作总价格"（不是单条视频的）
        detail.setCycleDays(r.getTotalItemCount() != null ? r.getTotalItemCount() : 0);
        detail.setDeadlineDate(toDate(toLocalDate(r.getCompletedAt())));
        detail.setOverdueDays(overdueDays);
        detail.setThresholdDays(thresholdDays);
        detail.setInfluencerCost(r.getTotalInfluencerCost());
        detail.setClientPrice(r.getTotalClientPrice());
        detail.setRequirementId(r.getId());
        detail.setInternalRequirementNo(r.getInternalRequirementNo());
        return detail;
    }

    private void saveStallReminder(Date batchDate, ReminderCategory category, Long audienceEmployeeId,
                                     String audienceRoleLabel, OverdueUrgency urgency,
                                     List<ProgressReminderDetail> details, String titleSuffix,
                                     Set<Long> involvedExecutorIds) {
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
        if (involvedExecutorIds != null && !involvedExecutorIds.isEmpty()) {
            reminder.setInvolvedEmployeeIds(involvedExecutorIds.stream()
                    .map(String::valueOf).collect(Collectors.joining("\n")));
        }
        reminder.setCount(details.size());
        // 按项目负责人定向的这两类，管理层/ADMIN 是"全量可见"（看到所有项目负责人的卡片混在
        // 一起，不是只看自己的），标题里必须带上具体是谁的、以及是"谁手下的"，不用"作为XX"这种
        // 口吻——红人合作跟踪的主责人始终是项目负责人，不该暗示执行人员是另一个主责人：
        // "项目负责人-陈洁-手下的2笔视频项目进度长时间未流转"
        // 严重度已经用单独的彩色标签展示在卡片上了（ProgressReminderCardList.vue 的
        // urgencyLabel），标题文字里不需要再重复一遍"3-7天"这种档位描述（之前财务视角这里
        // 会拼出"3-7天：18笔..."，跟旁边的严重度标签重复）
        String prefix;
        if (audienceEmployeeId != null) {
            Employee emp = employeeCache.findById(audienceEmployeeId);
            String empName = emp != null ? emp.getName() : ("员工#" + audienceEmployeeId);
            prefix = audienceRoleLabel + "-" + empName + "-手下的";
        } else {
            prefix = "";
        }
        reminder.setTitle(prefix + details.size() + titleSuffix);
        reminder = reminderRepo.save(reminder);
        for (ProgressReminderDetail d : details) d.setReminderId(reminder.getId());
        detailRepo.saveAll(details);
    }

    // ============ 查询（供 Controller 用） ============

    /**
     * 当前登录账号能看到的提醒列表，按 category、urgency（老两类 + FINANCE_PROGRESS_STALL
     * 走 ReminderUrgency"临近阈值"语义，PM_EXECUTOR_PROGRESS_STALL/REQUIREMENT_INVOICE_OVERDUE
     * 走 OverdueUrgency"超出阈值"语义）排序（2026-07 泛化，不再是"非管理层直接返回空列表"）：
     *   - ADMIN 或 员工角色=管理层 → 全部提醒（老两类 + 新三类，全部，不按人过滤）——
     *     保持管理层原有可见范围不变，只是新3类现在也对他们可见。
     *   - 员工角色=财务 → 额外看到 FINANCE_PROGRESS_STALL。
     *   - 有 employeeId（任何角色）→ 额外看到"我是项目负责人"或"我是这条记录涉及的执行
     *     人员之一"的行（覆盖 PM_EXECUTOR_PROGRESS_STALL/REQUIREMENT_INVOICE_OVERDUE，
     *     2026-07 起这两类统一只按项目负责人生成卡片，执行人员通过 involvedEmployeeIds
     *     获得同一张卡片的可见性，不再单独生成"执行人员"卡片——见 runPmExecutorProgressStall
     *     顶部注释，避免管理层的全量视角里同一条记录被算两遍）。
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

    /** 排序用的紧急度取值：逾期类提醒（overdueUrgency）优先于普通停滞类（urgency），都没有则当作最低紧急度 */
    private int urgencyOrdinal(ProgressReminder r) {
        if (r.getOverdueUrgency() != null) return r.getOverdueUrgency().ordinal();
        return r.getUrgency() != null ? r.getUrgency().ordinal() : 0;
    }

    /** 按当前登录账号的角色/权限，筛出这个人能看到的提醒列表（全量可见 vs 按角色/员工定向可见，见类注释权限矩阵） */
    private List<ProgressReminder> resolveVisibleReminders() {
        if (hasFullReminderVisibility()) {
            List<ProgressReminder> all = new ArrayList<>(reminderRepo.findAllByIsDeletedFalse());
            // ADMIN_ONLY_CATEGORIES：即使是"管理层"全量可见，登录账号本身不是 ADMIN 就看不到
            // 这两类——见该常量注释，避免"看到卡片但点进去发现自己无权处理"
            if (!RoleUtil.isAdmin()) {
                all.removeIf(r -> ADMIN_ONLY_CATEGORIES.contains(r.getCategory()));
            }
            return all;
        }
        List<ProgressReminder> result = new ArrayList<>();
        String employeeRole = employeeRoleUtil.getCurrentEmployeeRole();
        if (FINANCE_ROLE.equals(employeeRole)) {
            result.addAll(reminderRepo.findByAudienceEmployeeRole(FINANCE_ROLE));
        }
        // 2026-07 新增：法务全量可见合同相关提醒（合同上传逾期 + 合同即将到期），不按具体
        // 项目负责人/是否涉及执行人员过滤——这两类跟法务的职责直接相关，不是"顺带看到"
        if (LEGAL_ROLE.equals(employeeRole)) {
            result.addAll(reminderRepo.findByCategoryIn(CONTRACT_CATEGORIES));
        }
        Long employeeId = employeeRoleUtil.getCurrentEmployeeId();
        if (employeeId != null) {
            String idStr = String.valueOf(employeeId);
            for (ProgressReminder r : reminderRepo.findByCategoryIn(EMPLOYEE_OWNED_CATEGORIES)) {
                boolean isOwner = employeeId.equals(r.getAudienceEmployeeId());
                boolean isInvolvedExecutor = MultiValueUtil.splitMulti(r.getInvolvedEmployeeIds()).contains(idStr);
                if (isOwner || isInvolvedExecutor) result.add(r);
            }
        }
        return result;
    }

    /** ADMIN 或 员工角色=管理层：能看到全部提醒（老两类 + 新三类），不按具体员工/角色过滤 */
    private boolean hasFullReminderVisibility() {
        return RoleUtil.isAdmin() || isCurrentUserManagement();
    }

    /**
     * 某个类别的"全量可见"判定（2026-07 泛化）：ADMIN/管理层对所有类别都是全量可见；
     * 法务对合同相关这两类（CONTRACT_CATEGORIES）也是全量可见，不按具体项目负责人/是否
     * 涉及执行人员过滤——法务不是"顺带看到"，是这两类提醒本来就该完整给他们看。
     *
     * 注意：这里只控制"要不要按具体项目负责人/涉及执行人员过滤明细"，不要因为某个角色对
     * 某个类别是"全量可见"就想当然把它加进这个方法——FINANCE_PROGRESS_STALL 对财务也是
     * 全量可见，但它属于"按角色整体授予可见性"（跟 EMPLOYEE_OWNED_CATEGORIES 那种"按具体
     * 项目负责人/执行人员"定向可见是两回事，见 listDetails 里 EMPLOYEE_OWNED_CATEGORIES 判断），
     * 混进这个方法会连带跳过 markAcknowledged（"标记已处理"状态计算），导致财务这边"标记已
     * 处理"的行永远显示不出已处理状态——2026-07-30 曾经这样改过又改回来，教训见 listDetails 注释。
     */
    private boolean hasFullVisibilityFor(ReminderCategory category) {
        // ADMIN_ONLY_CATEGORIES：无视"管理层全量可见"这条老规则，严格只看登录账号本身是不是
        // ADMIN——这两类只有 ADMIN 能审核（见该常量注释），提前 return 短路掉下面的
        // hasFullReminderVisibility() 判断
        if (ADMIN_ONLY_CATEGORIES.contains(category)) return RoleUtil.isAdmin();
        if (hasFullReminderVisibility()) return true;
        return CONTRACT_CATEGORIES.contains(category) && LEGAL_ROLE.equals(employeeRoleUtil.getCurrentEmployeeRole());
    }

    /**
     * 某条提醒的明细，按离最迟结款日的接近程度/超期天数排序（两种排序方向巧合共用同一列）。
     * 如果当前登录人不是这条卡片的项目负责人本人（而是作为"涉及的执行人员"看到这张卡片），
     * 明细会额外按自己实际执行的那部分过滤——卡片本身不拆分，但执行人员点进去只看自己相关的。
     *
     * 2026-07-30 修复：按"是否涉及执行人员"过滤明细这件事，只对 EMPLOYEE_OWNED_CATEGORIES
     * （按具体项目负责人 audienceEmployeeId 定向生成、执行人员通过 involvedEmployeeIds 顺带
     * 可见的那几类）有意义——之前没有这个限制，财务查看 FINANCE_PROGRESS_STALL（audienceEmployeeId
     * 恒为 null，属于"按角色整体授予可见性"，不是按人定向）时，isViewingAsInvolvedExecutor
     * 会误判财务"不是这条卡片的负责人本人、只是顺带涉及的执行人员"，再用
     * filterToMyExecutorRecords 按"记录的执行人员是不是我"过滤——财务永远不可能是任何一条
     * 记录的执行人员，过滤结果永远是空列表，表现为卡片写着"44笔"、点进详情却是0条。
     */
    @Transactional(readOnly = true)
    public List<ProgressReminderDetail> listDetails(Long reminderId) {
        ProgressReminder reminder = reminderRepo.findById(reminderId).orElse(null);
        if (reminder == null || !canViewReminder(reminder)) {
            return Collections.emptyList();
        }
        List<ProgressReminderDetail> details = detailRepo.findByReminderIdOrderByDeadlineDateAsc(reminderId);
        if (!hasFullVisibilityFor(reminder.getCategory())) {
            if (ACKNOWLEDGEABLE_CATEGORIES.contains(reminder.getCategory())) {
                markAcknowledged(reminder.getCategory(), details);
            }
            if (EMPLOYEE_OWNED_CATEGORIES.contains(reminder.getCategory()) && isViewingAsInvolvedExecutor(reminder)) {
                details = filterToMyExecutorRecords(reminder.getCategory(), details);
            }
        }
        return details;
    }

    /**
     * 当前登录人不是这条卡片的项目负责人本人，只是作为"涉及的执行人员"看到。
     *
     * audienceEmployeeId == null 时直接短路返回 false（2026-08 补充这个guard，防止重犯
     * 2026-07-30 那次教训——FINANCE_PROGRESS_STALL 这一类现在混了"财务角色整体可见"
     * （audienceEmployeeId 恒为 null）和"项目负责人按人定向"两种卡片，前者压根没有具体的
     * "负责人"，不适用"我是不是负责人本人"这套判断；不加这个guard 的话，任何非空 employeeId
     * 都会被判定成"不是负责人、只是涉及的执行人员"，再走 filterToMyExecutorRecords 按"记录的
     * 执行人员是不是我"过滤——财务永远不是任何记录的执行人员，过滤结果永远是空列表，表现为
     * 卡片写着有N笔、点进详情却是0条）。
     */
    private boolean isViewingAsInvolvedExecutor(ProgressReminder r) {
        if (r.getAudienceEmployeeId() == null) return false;
        Long employeeId = employeeRoleUtil.getCurrentEmployeeId();
        return employeeId != null && !employeeId.equals(r.getAudienceEmployeeId());
    }

    /**
     * 把明细过滤到"我实际是执行人员"的那部分——PM_EXECUTOR_PROGRESS_STALL 直接看该合作跟踪
     * 记录当前的执行人员是不是我；REQUIREMENT_INVOICE_OVERDUE 看这个需求关联的合作跟踪记录里
     * 有没有我作为执行人员的（只要有一条就算，因为这条提醒本身是按"需求"整体展示的）。
     */
    private List<ProgressReminderDetail> filterToMyExecutorRecords(ReminderCategory category, List<ProgressReminderDetail> details) {
        Long employeeId = employeeRoleUtil.getCurrentEmployeeId();
        if (employeeId == null || details.isEmpty()) return Collections.emptyList();

        if (isRequirementBasedCategory(category)) {
            return details.stream().filter(d -> {
                if (d.getInternalRequirementNo() == null) return false;
                List<CollaborationTracking> linked =
                        trackingRepo.findByInternalRequirementNoAndIsDeletedFalse(d.getInternalRequirementNo());
                return linked.stream().anyMatch(t -> employeeId.equals(t.getExecutorId()));
            }).collect(Collectors.toList());
        }

        // CONTRACT_EXPIRING_SOON：trackingId 只是这个 (品牌方,团队) 组合下随便挑的一条占位记录
        // （见 runContractExpiringSoon，2026-08 起去重维度是品牌方+团队，不再看具体红人），
        // 不能只看这一条的执行人员是不是我——要把这条占位记录还原成完整的 (品牌方,团队)
        // 二元组，再看这个组合下所有未删除的合作跟踪记录（不限红人）里有没有我作为执行人员的
        // （只要有一条就算，因为这条提醒本身是按这个组合整体展示的）
        if (category == ReminderCategory.CONTRACT_EXPIRING_SOON) {
            List<Long> sampleIds = details.stream().map(ProgressReminderDetail::getTrackingId)
                    .filter(Objects::nonNull).distinct().collect(Collectors.toList());
            if (sampleIds.isEmpty()) return Collections.emptyList();
            Map<Long, CollaborationTracking> sampleById = new HashMap<>();
            for (CollaborationTracking t : trackingRepo.findAllById(sampleIds)) sampleById.put(t.getId(), t);
            List<CollaborationTracking> allActive = trackingRepo.findByIsDeletedFalse();
            return details.stream().filter(d -> {
                CollaborationTracking sample = sampleById.get(d.getTrackingId());
                if (sample == null) return false;
                return allActive.stream()
                        .anyMatch(t -> Objects.equals(t.getBrandId(), sample.getBrandId())
                                && Objects.equals(t.getTeamId(), sample.getTeamId())
                                && employeeId.equals(t.getExecutorId()));
            }).collect(Collectors.toList());
        }

        List<Long> trackingIds = details.stream().map(ProgressReminderDetail::getTrackingId)
                .filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (trackingIds.isEmpty()) return Collections.emptyList();
        Map<Long, CollaborationTracking> trackingById = new HashMap<>();
        for (CollaborationTracking t : trackingRepo.findAllById(trackingIds)) trackingById.put(t.getId(), t);
        return details.stream().filter(d -> {
            CollaborationTracking t = trackingById.get(d.getTrackingId());
            return t != null && employeeId.equals(t.getExecutorId());
        }).collect(Collectors.toList());
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

    /**
     * 取消"标记已处理"（2026-07 新增，防误点）：直接把这条标记硬删除（这张表的既有约定就是
     * 硬删除，见 cleanupAcknowledgements 用的也是 deleteAllByIdInBatch，不是软删），下次
     * listDetails() 就不会再把这一行标成 acknowledged。找不到对应标记时静默忽略（没什么好
     * 取消的，不算错误）。
     */
    @Transactional
    public void unacknowledge(ReminderCategory category, Long targetId) {
        Long employeeId = employeeRoleUtil.getCurrentEmployeeId();
        if (employeeId == null) {
            throw new RuntimeException("当前账号未关联员工，无法取消标记");
        }
        ackRepo.findByCategoryAndTargetIdAndEmployeeId(category, targetId, employeeId)
                .ifPresent(ack -> ackRepo.deleteById(ack.getId()));
    }

    /** REQUIREMENT_INVOICE_OVERDUE/REQUIREMENT_CONTRACT_OVERDUE 用 completedAt，其余（trackingId 定位）用 progressChangedAt */
    private Date resolveCurrentChangedAt(ReminderCategory category, Long targetId) {
        if (isRequirementBasedCategory(category)) {
            return requirementRepo.findById(targetId).map(InfluencerRequirement::getCompletedAt).orElse(null);
        }
        return trackingRepo.findById(targetId).map(CollaborationTracking::getProgressChangedAt).orElse(null);
    }

    /**
     * 给当前登录人已经标记"已处理"、且标记之后业务记录时间戳没有变化（说明情况还没变，标记
     * 仍然有效）的明细行打上 acknowledged=true（不再从列表里移除——2026-07 起改成"仍然展示，
     * 前端变灰 + 标'已标记为已处理'"，原因是过滤掉会导致主卡片标题的笔数（跑批时固定算好的，
     * 不受标记影响）跟点进详情看到的笔数对不上）。标记之后时间戳真的往前走了（说明情况已经
     * 变了）的，标记自动失效，acknowledged 保持 null/false，正常展示不变灰。
     */
    private void markAcknowledged(ReminderCategory category, List<ProgressReminderDetail> details) {
        Long employeeId = employeeRoleUtil.getCurrentEmployeeId();
        if (employeeId == null || details.isEmpty()) return;

        boolean requirementBased = isRequirementBasedCategory(category);
        List<Long> targetIds = details.stream()
                .map(d -> requirementBased ? d.getRequirementId() : d.getTrackingId())
                .filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (targetIds.isEmpty()) return;

        Map<Long, Date> snapshotByTarget = ackRepo.findByCategoryAndEmployeeIdAndTargetIdIn(category, employeeId, targetIds)
                .stream().collect(Collectors.toMap(ReminderAcknowledgement::getTargetId, ReminderAcknowledgement::getSnapshotChangedAt));
        if (snapshotByTarget.isEmpty()) return;

        for (ProgressReminderDetail d : details) {
            Long targetId = requirementBased ? d.getRequirementId() : d.getTrackingId();
            Date snapshot = snapshotByTarget.get(targetId);
            if (snapshot == null) continue;
            Date currentChangedAt = resolveCurrentChangedAt(category, targetId);
            // 标记之后业务记录的时间戳真的往前走了，说明标记已经失效，不算已处理；否则仍然算已处理
            boolean stillAcknowledged = !(currentChangedAt != null && currentChangedAt.after(snapshot));
            if (stillAcknowledged) d.setAcknowledged(true);
        }
    }

    /** 当前登录账号是否有权看到某一条已生成的提醒卡片：全量可见分类，或者是这条卡片指定的负责人/涉及执行人员之一，或者角色匹配受众角色 */
    private boolean canViewReminder(ProgressReminder r) {
        if (hasFullVisibilityFor(r.getCategory())) return true;
        if (r.getAudienceEmployeeId() != null) {
            Long empId = employeeRoleUtil.getCurrentEmployeeId();
            if (empId == null) return false;
            if (empId.equals(r.getAudienceEmployeeId())) return true;
            // 我不是这条卡片的项目负责人本人，但可能是"涉及的执行人员"之一
            return MultiValueUtil.splitMulti(r.getInvolvedEmployeeIds()).contains(String.valueOf(empId));
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
        // 2026-08-17 性能修复：改走 SysUserCache；旧代码：
        // sysUserRepo.findByUsernameAndIsDeletedFalse(RoleUtil.getCurrentUsername()).orElse(null)
        SysUser user = sysUserCache.findByUsername(RoleUtil.getCurrentUsername());
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
        // 2026-08-17 性能修复：改走 SysUserCache；旧代码：
        // sysUserRepo.findByUsernameAndIsDeletedFalse(RoleUtil.getCurrentUsername()).orElse(null)
        SysUser user = sysUserCache.findByUsername(RoleUtil.getCurrentUsername());
        if (user == null) return false;
        Date latestCheckpoint = latestPassedCheckpoint();
        return user.getLastSeenReminderPopupAt() == null || user.getLastSeenReminderPopupAt().before(latestCheckpoint);
    }

    /** 用户点了弹窗上的按钮（跳转待处理/我知道了）后调用，更新"最后看到弹窗"的时间戳 */
    @Transactional
    public void markPopupSeen() {
        // 这里是写操作，必须查活库拿一个能 save() 的实体，不能用 SysUserCache 里那份共享对象
        SysUser user = sysUserRepo.findByUsernameAndIsDeletedFalse(RoleUtil.getCurrentUsername()).orElse(null);
        if (user == null) return;
        user.setLastSeenReminderPopupAt(new Date());
        sysUserRepo.save(user);
        // 2026-08-17 新增：写完刷新缓存，不然要等最多4小时定时刷新才会反映到 SysUserCache，
        // 期间 shouldShowPopup() 读到的还是旧的 lastSeenReminderPopupAt，可能被误判成"还没看过"
        // 又弹一次
        sysUserCache.refresh();
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

    /** toLocalDate 的反向转换：LocalDate -> Date（按 JVM 默认时区/北京时间的当天 00:00 取具体时刻） */
    private Date toDate(LocalDate d) {
        return Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }
}
