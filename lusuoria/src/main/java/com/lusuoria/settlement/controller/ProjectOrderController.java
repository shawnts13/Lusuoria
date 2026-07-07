package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.dto.request.DeleteRequestReasonRequest;
import com.lusuoria.settlement.dto.request.ProjectOrderRequest;
import com.lusuoria.settlement.dto.request.ProjectOrderStatusRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.dto.response.ExecutorCostSuggestionResponse;
import com.lusuoria.settlement.dto.response.MonthlySummaryResponse;
import com.lusuoria.settlement.dto.response.ProjectOrderResponse;
import com.lusuoria.settlement.entity.PendingApproval;
import com.lusuoria.settlement.enums.ClientStatus;
import com.lusuoria.settlement.enums.InternalSettlementStatus;
import com.lusuoria.settlement.enums.ProjectType;
import com.lusuoria.settlement.enums.VideoType;
import com.lusuoria.settlement.service.ProjectOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;

@RestController
@RequestMapping("/api/projects")
public class ProjectOrderController {

    @Autowired private ProjectOrderService projectOrderService;

    @GetMapping
    public ApiResponse<Page<ProjectOrderResponse>> list(
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) String projectMonth,
            @RequestParam(required = false) String videoPublishMonth,
            @RequestParam(required = false) ProjectType projectType,
            @RequestParam(required = false) ClientStatus clientStatus,
            @RequestParam(required = false) InternalSettlementStatus internalStatus,
            @RequestParam(required = false) VideoType videoType,
            @RequestParam(required = false) String internalProjectNo,
            @RequestParam(required = false) Long influencerId,
            @RequestParam(required = false) String accountName,
            @RequestParam(required = false) Long projectManagerId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        // influencerAccount 对应关联的红人记录上的字段，不是 ProjectOrder 自己的属性，
        // 排序时要用 JPQL 的关联路径写法（influencer.accountName），不能直接用列名
        String sortProperty = "influencerAccount".equals(sortBy) ? "influencer.accountName" : sortBy;
        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(Sort.Direction.ASC, sortProperty)
                : Sort.by(Sort.Direction.DESC, sortProperty);
        PageRequest pageable = PageRequest.of(page, size, sort);
        return ApiResponse.success(projectOrderService.list(
                brandId, projectMonth, videoPublishMonth, projectType, clientStatus, internalStatus, videoType, internalProjectNo,
                influencerId, accountName, projectManagerId, keyword, pageable));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProjectOrderResponse> getById(@PathVariable Long id) {
        return ApiResponse.success(projectOrderService.getById(id));
    }

    @GetMapping("/summary/monthly")
    public ApiResponse<MonthlySummaryResponse> monthlySummary(@RequestParam String month) {
        return ApiResponse.success(projectOrderService.getMonthlySummary(month));
    }

    /** 导出：按当前角色决定是否包含敏感列 */
    @GetMapping("/export/excel")
    public void exportExcel(@RequestParam(required = false) String projectMonth,
                            HttpServletResponse response) throws IOException {
        projectOrderService.exportExcel(projectMonth, response);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<ProjectOrderResponse> save(@Valid @RequestBody ProjectOrderRequest req) {
        return ApiResponse.success(projectOrderService.save(req));
    }

    /**
     * 发起删除申请（不直接删除）：填写删除原因后生成一条"待处理"审核事项，
     * 由 ADMIN 在"待处理"模块同意后才真正删除。
     */
    @PostMapping("/{id}/delete-request")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<PendingApproval> requestDelete(
            @PathVariable Long id, @Valid @RequestBody DeleteRequestReasonRequest req) {
        return ApiResponse.success(projectOrderService.requestDelete(id, req.getReason()));
    }

    @PatchMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ProjectOrderResponse> approve(@PathVariable Long id) {
        return ApiResponse.success(projectOrderService.approve(id));
    }

    @PatchMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ProjectOrderResponse> reject(@PathVariable Long id) {
        return ApiResponse.success(projectOrderService.reject(id));
    }

    /**
     * 状态流转：只修改甲方状态 + 内部状态，不接收、也不会改动其他任何字段。
     * 配合前端专门的"状态流转"弹窗使用，防止编辑状态时误改其他内容。
     */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<ProjectOrderResponse> updateStatus(
            @PathVariable Long id, @RequestBody ProjectOrderStatusRequest req) {
        return ApiResponse.success(
                projectOrderService.updateStatus(id, req.getClientStatus(), req.getInternalStatus()));
    }

    /**
     * 内部执行成本弹窗打开时调用：只读，算出默认建议金额 + 算出来的依据说明，不修改任何数据。
     */
    @GetMapping("/{id}/executor-cost-suggestion")
    public ApiResponse<ExecutorCostSuggestionResponse> suggestExecutorCost(@PathVariable Long id) {
        return ApiResponse.success(projectOrderService.suggestExecutorCost(id));
    }

    /**
     * 内部执行成本弹窗确认时调用：保存金额，跟状态流转是分开的两步操作。
     */
    @PatchMapping("/{id}/executor-cost")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<ProjectOrderResponse> setExecutorCost(
            @PathVariable Long id, @RequestBody ExecutorCostRequest req) {
        return ApiResponse.success(projectOrderService.setExecutorCost(id, req.getAmount()));
    }

    /** 内部执行成本保存请求体 */
    @lombok.Data
    public static class ExecutorCostRequest {
        private java.math.BigDecimal amount;
    }
}
