package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.dto.request.ProjectOrderRequest;
import com.lusuoria.settlement.dto.request.ProjectOrderStatusRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.dto.response.MonthlySummaryResponse;
import com.lusuoria.settlement.dto.response.ProjectOrderResponse;
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
            @RequestParam(required = false) ProjectType projectType,
            @RequestParam(required = false) ClientStatus clientStatus,
            @RequestParam(required = false) InternalSettlementStatus internalStatus,
            @RequestParam(required = false) VideoType videoType,
            @RequestParam(required = false) Long influencerId,
            @RequestParam(required = false) String accountName,
            @RequestParam(required = false) Long projectManagerId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.success(projectOrderService.list(
                brandId, projectMonth, projectType, clientStatus, internalStatus, videoType,
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

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        projectOrderService.delete(id);
        return ApiResponse.success();
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
}
