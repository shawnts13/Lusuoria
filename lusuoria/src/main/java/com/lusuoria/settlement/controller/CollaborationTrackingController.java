package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.dto.request.CollaborationTrackingRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.enums.CollaborationProgress;
import com.lusuoria.settlement.enums.VideoType;
import com.lusuoria.settlement.excel.CollaborationTrackingExcelHandler;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import com.lusuoria.settlement.service.impl.CollaborationTrackingService;
import com.lusuoria.settlement.util.RoleUtil;
import org.springframework.beans.BeanUtils;
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

/**
 * 红人合作跟踪
 *
 * 特殊响应码：
 *   4090 - 订单ID变更需二次确认（前端弹确认框，确认后带 confirmOrderIdChange=true 重试）
 *   4091 - 去重命中（前端提示，不重试）
 */
@RestController
@RequestMapping("/api/collaboration-trackings")
public class CollaborationTrackingController {

    public static final int CODE_ORDER_ID_CONFIRM = 4090;
    public static final int CODE_DUPLICATE        = 4091;

    @Autowired private CollaborationTrackingRepository trackingRepo;
    @Autowired private CollaborationTrackingService trackingService;
    @Autowired private CollaborationTrackingExcelHandler excelHandler;
    @Autowired private BrandCache brandCache;

    @GetMapping
    public ApiResponse<Page<CollaborationTracking>> list(
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) String teamName,
            @RequestParam(required = false) String countryMarket,
            @RequestParam(required = false) String accountName,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) CollaborationProgress progress,
            @RequestParam(required = false) VideoType videoType,
            @RequestParam(required = false) String clientOrderId,
            @RequestParam(required = false) String clientPaymentBatch,
            @RequestParam(required = false) Long projectManagerId,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.max(1, Math.min(size, 200));
        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(Sort.Direction.ASC, sortBy)
                : Sort.by(Sort.Direction.DESC, sortBy);
        PageRequest pageable = PageRequest.of(page, size, sort);
        Page<CollaborationTracking> result = trackingRepo.findByFilters(
                brandId, teamName, countryMarket, accountName, platform,
                progress, videoType, clientOrderId, clientPaymentBatch, projectManagerId, pageable);
        if (!RoleUtil.canViewSensitiveFields()) {
            return ApiResponse.success(result.map(this::maskSensitive));
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<CollaborationTracking> getById(@PathVariable Long id) {
        CollaborationTracking t = trackingRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("跟踪记录不存在"));
        if (!RoleUtil.canViewSensitiveFields()) t = maskSensitive(t);
        return ApiResponse.success(t);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<CollaborationTracking> save(@Valid @RequestBody CollaborationTrackingRequest req) {
        try {
            CollaborationTracking saved = trackingService.save(req);
            CollaborationTracking out = RoleUtil.canViewSensitiveFields() ? saved : maskSensitive(saved);
            return ApiResponse.success(out);
        } catch (CollaborationTrackingService.OrderIdChangeConfirmRequired e) {
            return ApiResponse.error(CODE_ORDER_ID_CONFIRM, e.getMessage());
        } catch (CollaborationTrackingService.DuplicateTrackingException e) {
            return ApiResponse.error(CODE_DUPLICATE, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        trackingService.delete(id);
        return ApiResponse.success();
    }

    // ============ Excel ============
    @GetMapping("/export/excel")
    public void exportExcel(
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) String teamName,
            @RequestParam(required = false) String countryMarket,
            @RequestParam(required = false) String accountName,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) CollaborationProgress progress,
            @RequestParam(required = false) VideoType videoType,
            @RequestParam(required = false) String clientOrderId,
            @RequestParam(required = false) String clientPaymentBatch,
            @RequestParam(required = false) Long projectManagerId,
            HttpServletResponse response) throws IOException {
        // 导出按当前筛选条件，取全部（不分页）
        PageRequest all = PageRequest.of(0, Integer.MAX_VALUE, Sort.by(Sort.Direction.DESC, "id"));
        List<CollaborationTracking> list = trackingRepo.findByFilters(
                brandId, teamName, countryMarket, accountName, platform,
                progress, videoType, clientOrderId, clientPaymentBatch, projectManagerId, all).getContent();
        excelHandler.export(list, RoleUtil.canViewSensitiveFields(), response);
    }

    @GetMapping("/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        excelHandler.downloadTemplate(RoleUtil.canViewSensitiveFields(), response);
    }

    @PostMapping("/import/excel")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<List<String>> importExcel(@RequestParam("file") MultipartFile file) throws IOException {
        return ApiResponse.success(excelHandler.importData(file, RoleUtil.canViewSensitiveFields()));
    }

    private CollaborationTracking maskSensitive(CollaborationTracking t) {
        CollaborationTracking copy = new CollaborationTracking();
        BeanUtils.copyProperties(t, copy);
        copy.setInfluencerCost(null);
        copy.setClientPrice(null);
        return copy;
    }
}
