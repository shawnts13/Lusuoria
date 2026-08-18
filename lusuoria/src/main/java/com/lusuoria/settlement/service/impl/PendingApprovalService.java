package com.lusuoria.settlement.service.impl;

import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.entity.ExchangeRateCache;
import com.lusuoria.settlement.entity.InfluencerRequirement;
import com.lusuoria.settlement.entity.PendingApproval;
import com.lusuoria.settlement.enums.CollaborationProgress;
import com.lusuoria.settlement.enums.InfluencerPaymentProgress;
import com.lusuoria.settlement.enums.PendingApprovalCategory;
import com.lusuoria.settlement.enums.PendingApprovalModule;
import com.lusuoria.settlement.enums.PendingApprovalStatus;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import com.lusuoria.settlement.repository.ExchangeRateCacheRepository;
import com.lusuoria.settlement.repository.InfluencerRequirementRepository;
import com.lusuoria.settlement.repository.PendingApprovalRepository;
import com.lusuoria.settlement.util.MultiValueUtil;
import com.lusuoria.settlement.util.ProfitCalculator;
import com.lusuoria.settlement.util.RoleUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 待处理事项 - 业务逻辑
 *
 * 只依赖 Repository（CollaborationTrackingRepository/InfluencerRequirementRepository/
 * ExchangeRateCacheRepository），不依赖 CollaborationTrackingService，避免"删除要经过审核 ->
 * 审核通过要执行删除"这个链路形成 Service 之间的循环依赖——同样的原因，两个模块各自的
 * requestDelete() 也都不调用本 Service，而是直接操作 PendingApprovalRepository 现场复刻
 * 一份等价逻辑（见 InfluencerRequirementService.requestDelete() 的注释）；
 * executeExecutorCostModify() 里的汇率自动回填同理，复制了一份
 * CollaborationTrackingService.fillMissingExchangeRateFromCache()，不是注入调用。本 Service
 * 反过来依赖了 InfluencerRequirementService（只用它的 refreshCompletedAt()），所以这个方向
 * 不能颠倒。
 *
 * 目前有两种类别，同一条业务记录上可能同时存在两种互不相关的"待审核"事项，
 * 所有按目标记录查/判重的方法都必须带上 category 条件，不能只按 targetModule+targetId 查
 * （否则会把"删除审核"和"进度倒退审核"混在一起误判）。
 *
 * 2026-07："项目订单"模块整体废弃，PROJECT_ORDER 枚举值一并移除。
 * 2026-08 新增 INFLUENCER_REQUIREMENT 模块：target_module 现在有 COLLABORATION_TRACKING/
 * INFLUENCER_REQUIREMENT 两种，PROGRESS_ROLLBACK/EXECUTOR_COST_MODIFY 这两个类别仍然只有
 * COLLABORATION_TRACKING 用得到，DELETE_REQUEST 两个模块都会用。
 */
@Service
public class PendingApprovalService {

    @Autowired private PendingApprovalRepository pendingApprovalRepo;
    @Autowired private CollaborationTrackingRepository trackingRepo;
    @Autowired private InfluencerRequirementRepository requirementRepo;
    @Autowired private InfluencerRequirementService requirementService;
    @Autowired private ProfitCalculator profitCalculator;
    @Autowired private ExchangeRateCacheRepository exchangeRateCacheRepo;
    @Autowired private com.lusuoria.settlement.config.ExchangeRateLookupCache rateLookupCache;

    /**
     * 发起删除申请。如果这条记录已经有一条"待审核"的删除申请，直接复用（不重复创建）。
     *
     * 2026-08 修复：下面这三个 request*() 方法都是"先查一遍这条记录有没有待审核事项，没有就
     * 新建"，查和建中间没有加锁，pending_approvals 表也没有唯一约束兜底——快速连续点两次
     * 按钮，或者两个人几乎同时操作同一条记录，理论上能建出两条内容重复的待审核事项。加
     * synchronized 后同一时刻只有一个线程能执行这三个方法中的任意一个，从根上堵住这个竞态；
     * Render 部署是单实例（免费版没有多实例/水平扩展），JVM 级别的锁就足够覆盖生产环境的
     * 实际拓扑，不需要引入数据库锁或分布式锁这类更重的方案。这里没有细分锁粒度（比如只锁同一
     * 条目标记录），是因为这几个方法本身调用频率很低（都是用户主动点按钮触发，不是批量/高频
     * 路径），粗粒度地把整个服务的这三个方法串行化，实际性能影响可以忽略。
     */
    @Transactional
    public synchronized PendingApproval requestDelete(PendingApprovalModule module, Long targetId,
                                          String internalProjectNo, String summary, String reason) {
        return pendingApprovalRepo
                .findByTargetModuleAndTargetIdAndCategoryAndStatus(
                        module, targetId, PendingApprovalCategory.DELETE_REQUEST, PendingApprovalStatus.PENDING)
                .orElseGet(() -> {
                    PendingApproval p = new PendingApproval();
                    p.setCategory(PendingApprovalCategory.DELETE_REQUEST);
                    p.setTargetModule(module);
                    p.setTargetId(targetId);
                    p.setTargetInternalProjectNo(internalProjectNo);
                    p.setTargetSummary(summary);
                    p.setReason(reason);
                    p.setRequestedBy(RoleUtil.getCurrentUsername());
                    p.setStatus(PendingApprovalStatus.PENDING);
                    snapshotOwner(p, targetId);
                    return pendingApprovalRepo.save(p);
                });
    }

    /**
     * 发起那一刻快照目标记录的项目负责人/执行人员 id（2026-07 新增），供"处理结果通知"
     * 判断谁能看到这条通知——用快照而不是实时查询，避免记录后续换了负责人导致通知对不上人。
     */
    private void snapshotOwner(PendingApproval p, Long trackingId) {
        trackingRepo.findByIdAndIsDeletedFalse(trackingId).ifPresent(t -> {
            p.setTargetProjectManagerId(t.getProjectManagerId());
            p.setTargetExecutorId(t.getExecutorId());
        });
    }

    /**
     * 发起"视频项目进度倒退"申请（目前只有红人合作跟踪模块用得到）。
     * 如果这条记录已经有一条"待审核"的倒退申请，直接复用（不重复创建、不覆盖已有申请的内容）。
     *
     * @param requestedProgress        申请当时想要改成的"视频项目进度"（枚举 name）
     * @param requestedPaymentProgress 申请当时想要改成的"红人结款进度"（枚举 name，通常是 null——
     *                                 倒退到不满足前置条件的状态后，红人结款进度理应清空）
     */
    @Transactional
    public synchronized PendingApproval requestProgressRollback(Long trackingId, String internalProjectNo, String summary,
                                                     String reason, CollaborationProgress requestedProgress,
                                                     InfluencerPaymentProgress requestedPaymentProgress) {
        return pendingApprovalRepo
                .findByTargetModuleAndTargetIdAndCategoryAndStatus(
                        PendingApprovalModule.COLLABORATION_TRACKING, trackingId,
                        PendingApprovalCategory.PROGRESS_ROLLBACK, PendingApprovalStatus.PENDING)
                .orElseGet(() -> {
                    PendingApproval p = new PendingApproval();
                    p.setCategory(PendingApprovalCategory.PROGRESS_ROLLBACK);
                    p.setTargetModule(PendingApprovalModule.COLLABORATION_TRACKING);
                    p.setTargetId(trackingId);
                    p.setTargetInternalProjectNo(internalProjectNo);
                    p.setTargetSummary(summary);
                    p.setReason(reason);
                    p.setRequestedBy(RoleUtil.getCurrentUsername());
                    p.setStatus(PendingApprovalStatus.PENDING);
                    p.setRequestedProgress(requestedProgress != null ? requestedProgress.name() : null);
                    p.setRequestedPaymentProgress(requestedPaymentProgress != null ? requestedPaymentProgress.name() : null);
                    snapshotOwner(p, trackingId);
                    return pendingApprovalRepo.save(p);
                });
    }

    /**
     * 发起"内部执行成本二次修改"审核（2026-07 新增，只有红人合作跟踪模块用得到）。
     * 审核人是该记录的项目负责人本人，不是 ADMIN——见 approve()/reject() 里的 assertCanResolve()。
     * 如果这条记录已经有一条"待审核"的修改申请，直接复用（不重复创建、不覆盖已有申请的内容，
     * 跟删除审核/进度倒退审核同一套"去重"约定）。
     */
    @Transactional
    public synchronized PendingApproval requestExecutorCostModify(Long trackingId, String internalProjectNo, String summary,
                                                       BigDecimal previousAmount, Boolean previousNotApplicable,
                                                       BigDecimal requestedAmount, boolean requestedNotApplicable) {
        return pendingApprovalRepo
                .findByTargetModuleAndTargetIdAndCategoryAndStatus(
                        PendingApprovalModule.COLLABORATION_TRACKING, trackingId,
                        PendingApprovalCategory.EXECUTOR_COST_MODIFY, PendingApprovalStatus.PENDING)
                .orElseGet(() -> {
                    PendingApproval p = new PendingApproval();
                    p.setCategory(PendingApprovalCategory.EXECUTOR_COST_MODIFY);
                    p.setTargetModule(PendingApprovalModule.COLLABORATION_TRACKING);
                    p.setTargetId(trackingId);
                    p.setTargetInternalProjectNo(internalProjectNo);
                    p.setTargetSummary(summary);
                    p.setReason(describeExecutorCostChange(previousAmount, previousNotApplicable, requestedAmount, requestedNotApplicable));
                    p.setRequestedBy(RoleUtil.getCurrentUsername());
                    p.setStatus(PendingApprovalStatus.PENDING);
                    p.setPreviousExecutorCostAmount(previousAmount);
                    p.setPreviousExecutorCostNotApplicable(previousNotApplicable);
                    p.setRequestedExecutorCostAmount(requestedNotApplicable ? null : requestedAmount);
                    p.setRequestedExecutorCostNotApplicable(requestedNotApplicable);
                    snapshotOwner(p, trackingId);
                    return pendingApprovalRepo.save(p);
                });
    }

    private String describeExecutorCostChange(BigDecimal prevAmount, Boolean prevNotApplicable,
                                                BigDecimal newAmount, boolean newNotApplicable) {
        String from = Boolean.TRUE.equals(prevNotApplicable) ? "不涉及执行人员" : "¥" + fmtAmount(prevAmount);
        String to = newNotApplicable ? "不涉及执行人员" : "¥" + fmtAmount(newAmount);
        return "内部执行成本由 " + from + " 改为 " + to;
    }

    /** 金额格式化为两位小数字符串，拼接进审批说明文案（describeXxxChange）里用 */
    private String fmtAmount(BigDecimal v) {
        return v == null ? "0.00" : v.setScale(2, RoundingMode.HALF_UP).toString();
    }

    /** 某条业务记录当前是否有一条"待审核"的删除申请 */
    public boolean hasPendingDeleteRequest(PendingApprovalModule module, Long targetId) {
        return pendingApprovalRepo.existsByTargetModuleAndTargetIdAndCategoryAndStatus(
                module, targetId, PendingApprovalCategory.DELETE_REQUEST, PendingApprovalStatus.PENDING);
    }

    /** 某条业务记录当前是否有一条"待审核"的视频项目进度倒退申请 */
    public boolean hasPendingProgressRollbackRequest(PendingApprovalModule module, Long targetId) {
        return pendingApprovalRepo.existsByTargetModuleAndTargetIdAndCategoryAndStatus(
                module, targetId, PendingApprovalCategory.PROGRESS_ROLLBACK, PendingApprovalStatus.PENDING);
    }

    /** 某个模块下，哪些记录当前有"待审核"的删除申请（供列表页批量标记"审核中"用） */
    public List<Long> findPendingTargetIds(PendingApprovalModule module) {
        return pendingApprovalRepo.findPendingTargetIds(module, PendingApprovalCategory.DELETE_REQUEST);
    }

    /** 某个模块下，哪些记录当前有"待审核"的进度倒退申请（供列表页批量标记"审核中"用） */
    public List<Long> findPendingProgressRollbackTargetIds(PendingApprovalModule module) {
        return pendingApprovalRepo.findPendingTargetIds(module, PendingApprovalCategory.PROGRESS_ROLLBACK);
    }

    /** 某个模块下，哪些记录当前有"待审核"的内部执行成本修改申请（供列表页批量标记"修改审核中"用） */
    public List<Long> findPendingExecutorCostModifyTargetIds(PendingApprovalModule module) {
        return pendingApprovalRepo.findPendingTargetIds(module, PendingApprovalCategory.EXECUTOR_COST_MODIFY);
    }

    /** "待处理"模块列表页：按分类（删除申请/进度倒退/执行成本修改）分页查待审核记录 */
    @Transactional(readOnly = true)
    public Page<PendingApproval> listPending(PendingApprovalCategory category, Pageable pageable) {
        return pendingApprovalRepo.findPending(category, pageable);
    }

    /**
     * "待我审核"（2026-07 新增，EXECUTOR_COST_MODIFY 专属）：当前登录账号作为项目负责人，
     * 名下待自己审核的内部执行成本修改申请。没有关联员工时返回空列表。
     */
    @Transactional(readOnly = true)
    public List<PendingApproval> listMyApprovalQueue(Long employeeId) {
        if (employeeId == null) return Collections.emptyList();
        return pendingApprovalRepo.findMyApprovalQueue(employeeId, PendingApprovalCategory.EXECUTOR_COST_MODIFY);
    }

    /**
     * 谁能审核这条待处理事项：DELETE_REQUEST/PROGRESS_ROLLBACK 只有 ADMIN 能处理（沿用原规则）；
     * EXECUTOR_COST_MODIFY 只有该记录的项目负责人本人能处理，ADMIN 不能代替
     * （2026-07 新增，跟"设置执行成本"本身"管理层提交修改也要走审核、不享受直接生效特权"
     * 这条规则保持一致——审核权同样不给管理层/ADMIN 兜底）。
     */
    private void assertCanResolve(PendingApproval p, Long currentEmployeeId) {
        if (p.getCategory() == PendingApprovalCategory.EXECUTOR_COST_MODIFY) {
            if (currentEmployeeId == null || !currentEmployeeId.equals(p.getTargetProjectManagerId())) {
                throw new RuntimeException("只有该记录的项目负责人本人可以审核这条内部执行成本修改申请");
            }
        } else if (!RoleUtil.isAdmin()) {
            throw new RuntimeException("无权限处理这条待处理事项");
        }
    }

    /** 同意：按类别真正执行对应的改动 */
    @Transactional
    public PendingApproval approve(Long id, Long currentEmployeeId) {
        PendingApproval p = pendingApprovalRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("待处理事项不存在：" + id));
        if (p.getStatus() != PendingApprovalStatus.PENDING) {
            throw new RuntimeException("这条事项已经处理过了（当前状态：" + p.getStatus().getLabel() + "）");
        }
        assertCanResolve(p, currentEmployeeId);

        // 按类别+目标模块分派到对应的"真正执行"私有方法（本类下方，各自有完整说明），
        // 这四个分支互斥，一条待处理事项只会落进其中一个
        if (p.getCategory() == PendingApprovalCategory.PROGRESS_ROLLBACK) {
            executeProgressRollback(p);
        } else if (p.getCategory() == PendingApprovalCategory.EXECUTOR_COST_MODIFY) {
            executeExecutorCostModify(p);
        } else if (p.getTargetModule() == PendingApprovalModule.INFLUENCER_REQUIREMENT) {
            executeRequirementDeletion(p.getTargetId());
        } else {
            executeTrackingDeletion(p.getTargetId());
        }

        p.setStatus(PendingApprovalStatus.APPROVED);
        p.setResolvedBy(RoleUtil.getCurrentUsername());
        p.setResolvedAt(new Date());
        return pendingApprovalRepo.save(p);
    }

    /** 拒绝：记录原样保留，不做任何改动（对所有类别都一样） */
    @Transactional
    public PendingApproval reject(Long id, String note, Long currentEmployeeId) {
        PendingApproval p = pendingApprovalRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("待处理事项不存在：" + id));
        if (p.getStatus() != PendingApprovalStatus.PENDING) {
            throw new RuntimeException("这条事项已经处理过了（当前状态：" + p.getStatus().getLabel() + "）");
        }
        assertCanResolve(p, currentEmployeeId);
        p.setStatus(PendingApprovalStatus.REJECTED);
        p.setResolvedBy(RoleUtil.getCurrentUsername());
        p.setResolvedAt(new Date());
        p.setResolutionNote(note);
        return pendingApprovalRepo.save(p);
    }

    /**
     * 真正删除红人合作跟踪记录（"项目订单"模块已废弃，不再需要级联清理任何关联订单）。
     *
     * 2026-08 修复：old_material_source_link_normalized 这一列在数据库层面是全表唯一约束，
     * 但这个约束不认"软删除"——只要值一样，哪怕占用它的那一行已经软删除了，插入新行照样会
     * 被数据库拦下来，而应用层的查重逻辑（CollaborationTrackingService 的两处
     * findOldMaterialLinkOwner）统一按 isDeleted=false 过滤，会认为这个链接"没人占用了"，
     * 放行到插入才真正撞车，报出用户看不懂的原始 SQL 异常。按 Shawn 确认的口径——记录删除后，
     * 它占用的旧素材链接应该释放、允许被别的记录复用——这里删除时顺带清空这两个字段，
     * 从源头上让软删除的行不再占用这个唯一约束的槽位，不需要在查重逻辑那边特殊处理
     * "跟软删除记录冲突"这种情况。
     */
    private void executeTrackingDeletion(Long trackingId) {
        CollaborationTracking t = trackingRepo.findByIdAndIsDeletedFalse(trackingId)
                .orElseThrow(() -> new RuntimeException("跟踪记录不存在或已被删除：" + trackingId));
        t.setIsDeleted(true);
        t.setOldMaterialSourceLink(null);
        t.setOldMaterialSourceLinkNormalized(null);
        trackingRepo.save(t);
    }

    /**
     * 真正删除红人需求管理记录（2026-08 新增，跟"红人合作跟踪"的删除审核机制保持一致）。
     * 有关联的红人合作跟踪记录时不允许删除——发起申请那一刻
     * （InfluencerRequirementService.requestDelete()）已经查过一次，这里审核通过、真正执行
     * 删除前再查一次兜底，防止申请提交之后、ADMIN 审批之前这段时间里，又有人新建了关联到
     * 这条需求的合作跟踪记录（一旦命中，直接抛异常，事务回滚，这条待处理事项仍然停在
     * PENDING，不会被误判成"已同意"）。
     */
    private void executeRequirementDeletion(Long requirementId) {
        InfluencerRequirement r = requirementRepo.findByIdAndIsDeletedFalse(requirementId)
                .orElseThrow(() -> new RuntimeException("需求记录不存在或已被删除：" + requirementId));
        List<CollaborationTracking> linked =
                trackingRepo.findByInternalRequirementNoAndIsDeletedFalse(r.getInternalRequirementNo());
        if (!linked.isEmpty()) {
            throw new RuntimeException("该需求已经有关联的红人合作跟踪记录，不能删除");
        }
        r.setIsDeleted(true);
        requirementRepo.save(r);
    }

    /**
     * 真正执行"视频项目进度倒退"：把目标记录的进度/红人结款进度改成申请当时提交的值。
     * 只有这里（审核通过）才会真正落地，申请提交那一刻并不会改动目标记录。
     */
    private void executeProgressRollback(PendingApproval p) {
        CollaborationTracking t = trackingRepo.findByIdAndIsDeletedFalse(p.getTargetId())
                .orElseThrow(() -> new RuntimeException("跟踪记录不存在或已被删除：" + p.getTargetId()));
        if (p.getRequestedProgress() != null) {
            t.setProgress(CollaborationProgress.valueOf(p.getRequestedProgress()));
            // 倒退本身就是把 progress 真正改成别的值，一定要刷新"进度最近更新时间"
            // （供进度滞留提醒批次用），跟 CollaborationTrackingService 的口径保持一致
            t.setProgressChangedAt(new Date());
        }
        t.setInfluencerPaymentProgress(
                p.getRequestedPaymentProgress() != null
                        ? InfluencerPaymentProgress.valueOf(p.getRequestedPaymentProgress())
                        : null);
        trackingRepo.save(t);
        if (p.getRequestedProgress() != null && t.getInternalRequirementNo() != null) {
            requirementService.refreshCompletedAt(t.getInternalRequirementNo());
        }
    }

    /**
     * 真正执行"内部执行成本二次修改"（2026-07 新增）：把目标记录的内部执行成本/
     * "不涉及执行人员"标记改成申请当时提交的值，并按 ProfitCalculator 重新计算下游的
     * 毛利/可分配利润/提成/公司利润。只有这里（审核通过）才会真正落地，申请提交那一刻
     * 并不会改动目标记录。不复用 CollaborationTrackingService.setExecutorCost()——那个方法
     * 依赖本 Service 做审核判定，为避免循环依赖，这里直接在本 Service 内完成同样的落地逻辑。
     */
    private void executeExecutorCostModify(PendingApproval p) {
        CollaborationTracking t = trackingRepo.findByIdAndIsDeletedFalse(p.getTargetId())
                .orElseThrow(() -> new RuntimeException("跟踪记录不存在或已被删除：" + p.getTargetId()));
        if (Boolean.TRUE.equals(p.getRequestedExecutorCostNotApplicable())) {
            t.setExecutorCostNotApplicable(true);
        } else {
            t.setExecutorCostNotApplicable(false);
            t.setInternalExecutionCost(p.getRequestedExecutorCostAmount());
            // 申请提交时就是按系统梯度现算的值（见 CollaborationTrackingService.setExecutorCost()
            // 的"非首次修改"分支），不是 ADMIN 手动特批，标记成 false
            t.setExecutorCostOverridden(false);
            // 汇率缺失自动回填（2026-08 新增，Shawn 反馈）：这条记录如果是老数据、汇率一直是
            // 0/空（比如踩过 doSave() 那个已修复的 bug），走到这里重算利润前先按发布月份从
            // 汇率维护回填一次，不然重算出来的公司利润（人民币）还是0。逻辑跟
            // CollaborationTrackingService.fillMissingExchangeRateFromCache() 完全一样，这里
            // 复制一份而不是注入 CollaborationTrackingService 来调用——本类头顶注释已经解释过
            // 为什么不能依赖 CollaborationTrackingService（CollaborationTrackingService 已经
            // 依赖了本类，反过来注入会形成 Service 间循环依赖，Spring Boot 2.6+ 默认在启动时
            // 直接报错，不是运行时才发现）。
            fillMissingExchangeRateFromCache(t);
            profitCalculator.calculate(t);
        }
        trackingRepo.save(t);
    }

    /** 跟 CollaborationTrackingService.fillMissingExchangeRateFromCache() 是同一份逻辑，
     * 复制过来专供 executeExecutorCostModify() 用——不能反过来注入 CollaborationTrackingService
     * 调用它，见本类头顶的循环依赖说明。 */
    private boolean fillMissingExchangeRateFromCache(CollaborationTracking t) {
        boolean exchangeRateInvalid = t.getExchangeRate() == null
                || t.getExchangeRate().compareTo(BigDecimal.ZERO) <= 0;
        if (!exchangeRateInvalid || t.getPublishDate() == null) return false;
        // 2026-08-17 性能修复：改走 ExchangeRateLookupCache；旧代码：
        // exchangeRateCacheRepo.findByYearMonth(...).orElse(null)
        ExchangeRateCache cache = rateLookupCache
                .findByYearMonth(new SimpleDateFormat("yyyyMM").format(t.getPublishDate()));
        if (cache != null && cache.getUsdToCny() != null
                && cache.getUsdToCny().compareTo(BigDecimal.ZERO) > 0) {
            t.setExchangeRate(cache.getUsdToCny());
            return true;
        }
        return false;
    }

    /**
     * "确认删除"（2026-07 起是真正的数据库硬删除）：项目负责人/执行人员在自己的"处理结果
     * 通知"列表里点击后调用，先记这个员工自己已经点过；只有 targetProjectManagerId/
     * targetExecutorId 里非空的这几个人都点过之后，才会真正把这行 PendingApproval 从
     * 数据库删掉——避免一方先点了删除，另一方还没来得及看就丢了这条通知。
     * 还没凑齐时只是记一下"这个员工点过了"，不影响其他共同受众各自独立的查看状态。
     */
    @Transactional
    public void dismiss(Long id, Long employeeId) {
        PendingApproval p = pendingApprovalRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("待处理事项不存在：" + id));
        boolean isOwner = employeeId != null
                && (employeeId.equals(p.getTargetProjectManagerId()) || employeeId.equals(p.getTargetExecutorId()));
        if (!isOwner) {
            throw new RuntimeException("只有该记录的项目负责人或执行人员可以确认删除这条通知");
        }
        List<String> dismissed = new ArrayList<>(MultiValueUtil.splitMulti(p.getDismissedByEmployeeIds()));
        String idStr = String.valueOf(employeeId);
        if (!dismissed.contains(idStr)) {
            dismissed.add(idStr);
            p.setDismissedByEmployeeIds(String.join("\n", dismissed));
        }
        boolean allCleared = (p.getTargetProjectManagerId() == null
                        || dismissed.contains(String.valueOf(p.getTargetProjectManagerId())))
                && (p.getTargetExecutorId() == null
                        || dismissed.contains(String.valueOf(p.getTargetExecutorId())));
        if (allCleared) {
            pendingApprovalRepo.delete(p);
        } else {
            pendingApprovalRepo.save(p);
        }
    }

    /**
     * "处理结果通知"列表（2026-07 新增）：某个员工作为项目负责人/执行人员、已经处理完
     * （同意/拒绝）、且自己还没点过"确认删除"的事项。量级小，不分页。
     */
    @Transactional(readOnly = true)
    public List<PendingApproval> listMyNotifications(Long employeeId) {
        if (employeeId == null) return Collections.emptyList();
        String idStr = String.valueOf(employeeId);
        return pendingApprovalRepo.findResolvedForEmployee(employeeId).stream()
                .filter(p -> !MultiValueUtil.splitMulti(p.getDismissedByEmployeeIds()).contains(idStr))
                .collect(Collectors.toList());
    }
}
