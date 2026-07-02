package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.dto.request.RejectApprovalRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.PendingApproval;
import com.lusuoria.settlement.enums.PendingApprovalCategory;
import com.lusuoria.settlement.service.impl.PendingApprovalService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 待处理事项。目前只有 ADMIN 能看到、能审核（因为唯一的类别"删除审核"就是给管理员审核用的）。
 */
@RestController
@RequestMapping("/api/pending-approvals")
@PreAuthorize("hasRole('ADMIN')")
public class PendingApprovalController {

    @Autowired private PendingApprovalService pendingApprovalService;

    @GetMapping
    public ApiResponse<Page<PendingApproval>> list(
            @RequestParam(required = false) PendingApprovalCategory category,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.success(pendingApprovalService.listPending(category, pageable));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<PendingApproval> approve(@PathVariable Long id) {
        return ApiResponse.success(pendingApprovalService.approve(id));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<PendingApproval> reject(@PathVariable Long id, @RequestBody(required = false) RejectApprovalRequest req) {
        String note = req != null ? req.getNote() : null;
        return ApiResponse.success(pendingApprovalService.reject(id, note));
    }
}
