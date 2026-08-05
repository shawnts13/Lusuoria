package com.lusuoria.settlement.service.impl;

import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.entity.PendingApproval;
import com.lusuoria.settlement.enums.CollaborationProgress;
import com.lusuoria.settlement.enums.InfluencerPaymentProgress;
import com.lusuoria.settlement.enums.PendingApprovalCategory;
import com.lusuoria.settlement.enums.PendingApprovalModule;
import com.lusuoria.settlement.enums.PendingApprovalStatus;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 待处理事项 - 业务逻辑
 *
 * 只依赖 Repository，不依赖 CollaborationTrackingService，
 * 避免"删除要经过审核 -> 审核通过要执行删除"这个链路形成 Service 之间的循环依赖。
 *
 * 目前有两种类别，同一条业务记录上可能同时存在两种互不相关的"待审核"事项，
 * 所有按目标记录查/判重的方法都必须带上 category 条件，不能只按 targetModule+targetId 查
 * （否则会把"删除审核"和"进度倒退审核"混在一起误判）。
 *
 * 2026-07："项目订单"模块整体废弃，target_module 现在实际上只会是
 * COLLABORATION_TRACKING 一种（PROJECT_ORDER 枚举值也一并移除了）。
 */
@Service
public class PendingApprovalService {

    @Autowired private PendingApprovalRepository pendingApprovalRepo;
    @Autowired private CollaborationTrackingRepository trackingRepo;
    @Autowired private InfluencerRequirementService requirementService;
    @Autowired private ProfitCalculator profitCalculator;

    /**
     * 发起删除申请。如果这条记录已经有一条"待审核"的删除申请，直接复用（不重复创建）。
     */
    @Transactional
    public PendingApproval requestDelete(PendingApprovalModule module, Long targetId,
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
    public PendingApproval requestProgressRollback(Long trackingId, String internalProjectNo, String summary,
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
    public PendingApproval requestExecutorCostModify(Long trackingId, String internalProjectNo, String summary,
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

        if (p.getCategory() == PendingApprovalCategory.PROGRESS_ROLLBACK) {
            executeProgressRollback(p);
        } else if (p.getCategory() == PendingApprovalCategory.EXECUTOR_COST_MODIFY) {
            executeExecutorCostModify(p);
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
            profitCalculator.calculate(t);
        }
        trackingRepo.save(t);
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
