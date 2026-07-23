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
 * 待处理事项。审批队列（list/approve/reject）只有 ADMIN 能看到、能操作——审批本身是管理员
 * 专属动作，不受"待处理"模块 2026-07 向所有非访客角色开放这个变化的影响。
 * 非管理员通过 /my-notifications + /{id}/dismiss 看自己相关记录的"处理结果通知"
 * （已同意/已拒绝），不是完整审批队列。
 */
@RestController
@RequestMapping("/api/pending-approvals")
public class PendingApprovalController {

    @Autowired private PendingApprovalService pendingApprovalService;
    @Autowired private EmployeeRoleUtil employeeRoleUtil;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Page<PendingApproval>> list(
            @RequestParam(required = false) PendingApprovalCategory category,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.success(pendingApprovalService.listPending(category, pageable));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PendingApproval> approve(@PathVariable Long id) {
        return ApiResponse.success(pendingApprovalService.approve(id));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PendingApproval> reject(@PathVariable Long id, @RequestBody(required = false) RejectApprovalRequest req) {
        String note = req != null ? req.getNote() : null;
        return ApiResponse.success(pendingApprovalService.reject(id, note));
    }

    /**
     * "处理结果通知"（2026-07 新增）：当前登录账号作为项目负责人/执行人员，看自己相关记录
     * 已经被处理（同意/拒绝）、且自己还没确认删除的通知。没有关联员工时返回空列表。
     */
    @GetMapping("/my-notifications")
    public ApiResponse<List<PendingApproval>> myNotifications() {
        return ApiResponse.success(pendingApprovalService.listMyNotifications(employeeRoleUtil.getCurrentEmployeeId()));
    }

    /** "确认删除"（标记已读）：只有这条记录的项目负责人/执行人员本人能操作 */
    @PostMapping("/{id}/dismiss")
    public ApiResponse<Void> dismiss(@PathVariable Long id) {
        pendingApprovalService.dismiss(id, employeeRoleUtil.getCurrentEmployeeId());
        return ApiResponse.success();
    }
}
