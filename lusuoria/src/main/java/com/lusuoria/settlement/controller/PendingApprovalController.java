package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.dto.request.RejectApprovalRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.PendingApproval;
import com.lusuoria.settlement.enums.PendingApprovalCategory;
import com.lusuoria.settlement.service.impl.PendingApprovalService;
import com.lusuoria.settlement.util.EmployeeRoleUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 待处理事项。审批队列（list）只有 ADMIN 能看到——审批本身对 DELETE_REQUEST/PROGRESS_ROLLBACK
 * 两类是管理员专属动作。approve/reject 这两个接口 2026-07 起放开给非管理员调用，但仅限于
 * EXECUTOR_COST_MODIFY 类别、且必须是目标记录的项目负责人本人——具体由
 * PendingApprovalService.assertCanResolve() 精确校验，不是这里简单放行了事；
 * 项目负责人自己的审核队列走 /my-approvals，不是完整审批队列。
 * 非管理员通过 /my-notifications + /{id}/dismiss 看自己相关记录的"处理结果通知"
 * （已同意/已拒绝），不是完整审批队列。
 */
@RestController
@RequestMapping("/api/pending-approvals")
public class PendingApprovalController {

    @Autowired private PendingApprovalService pendingApprovalService;
    @Autowired private EmployeeRoleUtil employeeRoleUtil;

    /** ADMIN 视角的完整待审批队列（删除申请/进度倒退申请/内部执行成本修改申请），可按类别筛选 */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Page<PendingApproval>> list(
            @RequestParam(required = false) PendingApprovalCategory category,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.success(pendingApprovalService.listPending(category, pageable));
    }

    /**
     * "待我审核"（2026-07 新增）：当前登录账号作为项目负责人，名下待自己审核的内部执行成本
     * 修改申请。不是完整审批队列——DELETE_REQUEST/PROGRESS_ROLLBACK 不会出现在这里。
     */
    @GetMapping("/my-approvals")
    public ApiResponse<List<PendingApproval>> myApprovals() {
        return ApiResponse.success(pendingApprovalService.listMyApprovalQueue(employeeRoleUtil.getCurrentEmployeeId()));
    }

    /** 同意一条审批（ADMIN 或该记录的项目负责人本人，取决于审批类型，权限校验在 service 层） */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<PendingApproval> approve(@PathVariable Long id) {
        return ApiResponse.success(pendingApprovalService.approve(id, employeeRoleUtil.getCurrentEmployeeId()));
    }

    /** 拒绝一条审批，note 是拒绝理由（可选） */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<PendingApproval> reject(@PathVariable Long id, @RequestBody(required = false) RejectApprovalRequest req) {
        String note = req != null ? req.getNote() : null;
        return ApiResponse.success(pendingApprovalService.reject(id, note, employeeRoleUtil.getCurrentEmployeeId()));
    }

    /**
     * "处理结果通知"（2026-07 新增）：当前登录账号作为项目负责人/执行人员，看自己相关记录
     * 已经被处理（同意/拒绝）、且自己还没确认删除的通知。没有关联员工时返回空列表。
     */
    @GetMapping("/my-notifications")
    public ApiResponse<List<PendingApproval>> myNotifications() {
        return ApiResponse.success(pendingApprovalService.listMyNotifications(employeeRoleUtil.getCurrentEmployeeId()));
    }

    /** "确认删除"（2026-07 起是真正的数据库硬删除，见 Service 层注释）：只有这条记录的项目负责人/执行人员本人能操作 */
    @PostMapping("/{id}/dismiss")
    public ApiResponse<Void> dismiss(@PathVariable Long id) {
        pendingApprovalService.dismiss(id, employeeRoleUtil.getCurrentEmployeeId());
        return ApiResponse.success();
    }
}
