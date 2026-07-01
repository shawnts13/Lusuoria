package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.dto.request.ProjectOrderRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.dto.response.MonthlySummaryResponse;
import com.lusuoria.settlement.dto.response.ProjectOrderResponse;
import com.lusuoria.settlement.enums.ClientStatus;
import com.lusuoria.settlement.enums.InternalSettlementStatus;
import com.lusuoria.settlement.enums.ProjectType;
import com.lusuoria.settlement.enums.VideoType;
import com.lusuoria.settlement.excel.ProjectOrderExcelHandler;
import com.lusuoria.settlement.service.ProjectOrderService;
import com.lusuoria.settlement.util.RoleUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectOrderController {

    @Autowired private ProjectOrderService projectOrderService;
    @Autowired private ProjectOrderExcelHandler excelHandler;

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

    /**
     * 下载导入模板：按当前角色决定模板是否包含敏感列
     * ADMIN / AUDITOR → 含完整列（包含收入/利润/提成）
     * STAFF / GUEST   → 仅包含非敏感列
     */
    @GetMapping("/import/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        excelHandler.downloadTemplate(RoleUtil.canViewSensitiveFields(), response);
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

    @PostMapping("/import/excel")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<List<String>> importExcel(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) return ApiResponse.error(400, "请选择要上传的文件");
        String fn = file.getOriginalFilename();
        if (fn == null || (!fn.endsWith(".xlsx") && !fn.endsWith(".xls")))
            return ApiResponse.error(400, "只支持 .xlsx 或 .xls 格式");
        return ApiResponse.success(projectOrderService.importExcel(file));
    }
}
