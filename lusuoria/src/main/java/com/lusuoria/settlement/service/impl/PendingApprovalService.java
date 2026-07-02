package com.lusuoria.settlement.service.impl;

import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.entity.PendingApproval;
import com.lusuoria.settlement.entity.ProjectOrder;
import com.lusuoria.settlement.enums.PendingApprovalCategory;
import com.lusuoria.settlement.enums.PendingApprovalModule;
import com.lusuoria.settlement.enums.PendingApprovalStatus;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import com.lusuoria.settlement.repository.PendingApprovalRepository;
import com.lusuoria.settlement.repository.ProjectOrderRepository;
import com.lusuoria.settlement.util.RoleUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/**
 * 待处理事项 - 业务逻辑
 *
 * 只依赖 Repository，不依赖 ProjectOrderServiceImpl / CollaborationTrackingService，
 * 避免"删除要经过审核 -> 审核通过要执行删除"这个链路形成 Service 之间的循环依赖。
 */
@Service
public class PendingApprovalService {

    @Autowired private PendingApprovalRepository pendingApprovalRepo;
    @Autowired private ProjectOrderRepository projectOrderRepo;
    @Autowired private CollaborationTrackingRepository trackingRepo;

    /**
     * 发起删除申请。如果这条记录已经有一条"待审核"的删除申请，直接复用（不重复创建）。
     */
    @Transactional
    public PendingApproval requestDelete(PendingApprovalModule module, Long targetId,
                                          String internalProjectNo, String summary, String reason) {
        return pendingApprovalRepo
                .findByTargetModuleAndTargetIdAndStatus(module, targetId, PendingApprovalStatus.PENDING)
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
                    return pendingApprovalRepo.save(p);
                });
    }

    /** 某条业务记录当前是否有一条"待审核"的删除申请 */
    public boolean hasPendingDeleteRequest(PendingApprovalModule module, Long targetId) {
        return pendingApprovalRepo.existsByTargetModuleAndTargetIdAndStatus(
                module, targetId, PendingApprovalStatus.PENDING);
    }

    /** 某个模块下，哪些记录当前有"待审核"的删除申请（供列表页批量标记"审核中"用） */
    public List<Long> findPendingTargetIds(PendingApprovalModule module) {
        return pendingApprovalRepo.findPendingTargetIds(module);
    }

    @Transactional(readOnly = true)
    public Page<PendingApproval> listPending(PendingApprovalCategory category, Pageable pageable) {
        return pendingApprovalRepo.findPending(category, pageable);
    }

    /** 同意：真正执行删除，并处理关联清理 */
    @Transactional
    public PendingApproval approve(Long id) {
        PendingApproval p = pendingApprovalRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("待处理事项不存在：" + id));
        if (p.getStatus() != PendingApprovalStatus.PENDING) {
            throw new RuntimeException("这条事项已经处理过了（当前状态：" + p.getStatus().getLabel() + "）");
        }

        if (p.getTargetModule() == PendingApprovalModule.PROJECT_ORDER) {
            executeProjectOrderDeletion(p.getTargetId());
        } else {
            executeTrackingDeletion(p.getTargetId());
        }

        p.setStatus(PendingApprovalStatus.APPROVED);
        p.setResolvedBy(RoleUtil.getCurrentUsername());
        p.setResolvedAt(new Date());
        return pendingApprovalRepo.save(p);
    }

    /** 拒绝：记录原样保留，不做任何改动 */
    @Transactional
    public PendingApproval reject(Long id, String note) {
        PendingApproval p = pendingApprovalRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("待处理事项不存在：" + id));
        if (p.getStatus() != PendingApprovalStatus.PENDING) {
            throw new RuntimeException("这条事项已经处理过了（当前状态：" + p.getStatus().getLabel() + "）");
        }
        p.setStatus(PendingApprovalStatus.REJECTED);
        p.setResolvedBy(RoleUtil.getCurrentUsername());
        p.setResolvedAt(new Date());
        p.setResolutionNote(note);
        return pendingApprovalRepo.save(p);
    }

    /** 真正删除项目订单，并释放关联跟踪记录的订单引用（让它能重新编辑订单号） */
    private void executeProjectOrderDeletion(Long orderId) {
        ProjectOrder order = projectOrderRepo.findByIdAndIsDeletedFalse(orderId)
                .orElseThrow(() -> new RuntimeException("项目订单不存在或已被删除：" + orderId));
        order.setIsDeleted(true);
        // 让出内部项目编号：这个编号来自跟踪记录，跟踪记录的编号永久不变，
        // 如果之后又给这条跟踪记录重新生成了一条项目订单，新订单会想复用同一个编号，
        // 但这条旧订单还物理存在（软删除），不让号会撞数据库唯一约束
        if (order.getInternalProjectNo() != null) {
            order.setInternalProjectNo(order.getInternalProjectNo() + "-DEL" + order.getId());
        }
        projectOrderRepo.save(order);

        // 找到引用这条订单的跟踪记录（正常只有一条），清空订单引用，让它恢复可编辑
        List<CollaborationTracking> refs = trackingRepo.findByGeneratedProjectOrderId(orderId);
        for (CollaborationTracking t : refs) {
            t.setGeneratedProjectOrderId(null);
            t.setClientOrderId(null);
            trackingRepo.save(t);
        }
    }

    /** 真正删除红人合作跟踪记录（不级联删除已生成的项目订单——订单一旦成立独立存在） */
    private void executeTrackingDeletion(Long trackingId) {
        CollaborationTracking t = trackingRepo.findByIdAndIsDeletedFalse(trackingId)
                .orElseThrow(() -> new RuntimeException("跟踪记录不存在或已被删除：" + trackingId));
        t.setIsDeleted(true);
        trackingRepo.save(t);
    }
}
