package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.dto.request.CollaborationTrackingRequest;
import com.lusuoria.settlement.dto.request.CollaborationTrackingStatusRequest;
import com.lusuoria.settlement.dto.request.DeleteRequestReasonRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.dto.response.CollaborationStatusUpdateResult;
import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.entity.ImportBatch;
import com.lusuoria.settlement.entity.PendingApproval;
import com.lusuoria.settlement.enums.CollaborationProgress;
import com.lusuoria.settlement.enums.PendingApprovalCategory;
import com.lusuoria.settlement.enums.PendingApprovalModule;
import com.lusuoria.settlement.enums.VideoType;
import com.lusuoria.settlement.excel.CollaborationTrackingExcelHandler;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import com.lusuoria.settlement.repository.ImportBatchRepository;
import com.lusuoria.settlement.repository.PendingApprovalRepository;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 红人合作跟踪
 *
 * 特殊响应码：
 *   4090 - 已存在关联的项目订单，不能改/清空客户方订单号（需要先删除那条项目订单）
 *   4091 - 去重命中（前端提示，不重试）
 */
@RestController
@RequestMapping("/api/collaboration-trackings")
public class CollaborationTrackingController {

    public static final int CODE_LINKED_ORDER_EXISTS = 4090;
    public static final int CODE_DUPLICATE           = 4091;

    @Autowired private CollaborationTrackingRepository trackingRepo;
    @Autowired private CollaborationTrackingService trackingService;
    @Autowired private CollaborationTrackingExcelHandler excelHandler;
    @Autowired private ImportBatchRepository importBatchRepo;
    @Autowired private BrandCache brandCache;
    @Autowired private PendingApprovalRepository pendingApprovalRepo;

    @GetMapping
    public ApiResponse<Page<CollaborationTracking>> list(
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String countryMarket,
            @RequestParam(required = false) String accountName,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) CollaborationProgress progress,
            @RequestParam(required = false) VideoType videoType,
            @RequestParam(required = false) String videoMonth,
            @RequestParam(required = false) String internalProjectNo,
            @RequestParam(required = false) String clientOrderId,
            @RequestParam(required = false) String clientPaymentBatch,
            @RequestParam(required = false) Long projectManagerId,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.max(1, Math.min(size, 200));
        // accountName 是关联的红人记录上的字段，不是本表自己的属性，
        // 排序时要用 JPQL 的关联路径写法（influencer.accountName），不能直接用列名
        String sortProperty = "accountName".equals(sortBy) ? "influencer.accountName" : sortBy;
        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(Sort.Direction.ASC, sortProperty)
                : Sort.by(Sort.Direction.DESC, sortProperty);
        PageRequest pageable = PageRequest.of(page, size, sort);
        String videoMonthParam = (videoMonth == null || videoMonth.trim().isEmpty()) ? null : videoMonth.trim();
        Page<CollaborationTracking> result = trackingRepo.findByFilters(
                brandId, teamId, countryMarket, accountName, platform,
                progress, videoType, videoMonthParam, internalProjectNo,
                clientOrderId, clientPaymentBatch, projectManagerId, pageable);

        // 批量标记"当前是否有待审核的删除申请 / 进度倒退申请"，避免逐行查库
        Set<Long> pendingDeleteIds = new HashSet<>(pendingApprovalRepo.findPendingTargetIds(
                PendingApprovalModule.COLLABORATION_TRACKING, PendingApprovalCategory.DELETE_REQUEST));
        Set<Long> pendingRollbackIds = new HashSet<>(pendingApprovalRepo.findPendingTargetIds(
                PendingApprovalModule.COLLABORATION_TRACKING, PendingApprovalCategory.PROGRESS_ROLLBACK));
        result.forEach(t -> {
            t.setHasPendingDeleteRequest(pendingDeleteIds.contains(t.getId()));
            t.setHasPendingRollbackRequest(pendingRollbackIds.contains(t.getId()));
        });

        if (!RoleUtil.canViewBaselineFinancials()) {
            return ApiResponse.success(result.map(this::maskSensitive));
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<CollaborationTracking> getById(@PathVariable Long id) {
        CollaborationTracking t = trackingRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("跟踪记录不存在"));
        if (!RoleUtil.canViewBaselineFinancials()) t = maskSensitive(t);
        return ApiResponse.success(t);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<CollaborationTracking> save(@Valid @RequestBody CollaborationTrackingRequest req) {
        try {
            CollaborationTracking saved = trackingService.save(req);
            CollaborationTracking out = RoleUtil.canViewBaselineFinancials() ? saved : maskSensitive(saved);
            return ApiResponse.success(out);
        } catch (CollaborationTrackingService.LinkedOrderExistsException e) {
            return ApiResponse.error(CODE_LINKED_ORDER_EXISTS, e.getMessage());
        } catch (CollaborationTrackingService.DuplicateTrackingException e) {
            return ApiResponse.error(CODE_DUPLICATE, e.getMessage());
        }
    }

    /**
     * 发起删除申请（不直接删除）：填写删除原因后生成一条"待处理"审核事项，
     * 由 ADMIN 在"待处理"模块同意后才真正删除。
     */
    @PostMapping("/{id}/delete-request")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<PendingApproval> requestDelete(
            @PathVariable Long id, @Valid @RequestBody DeleteRequestReasonRequest req) {
        return ApiResponse.success(trackingService.requestDelete(id, req.getReason()));
    }

    /**
     * 状态流转：只允许修改"视频项目进度"/"红人结款进度"这两个字段，不接收、也不会改动其他任何字段。
     * 配合前端专门的"状态流转"弹窗使用，防止编辑状态时误改其他内容。
     *
     * 正常情况下改动立即生效；但如果是"视频项目进度"从符合条件的状态倒退回不符合条件的状态、
     * 且当前记录"红人结款进度"已经有值，这种改动不会立即生效，而是提交一条待审核事项，
     * 由管理员在"待处理"模块同意后才真正生效（见 CollaborationTrackingService.updateStatus）。
     */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<CollaborationStatusUpdateResult> updateStatus(
            @PathVariable Long id, @RequestBody CollaborationTrackingStatusRequest req) {
        CollaborationStatusUpdateResult result = trackingService.updateStatus(id, req);
        if (!RoleUtil.canViewBaselineFinancials()) {
            result.setTracking(maskSensitive(result.getTracking()));
        }
        return ApiResponse.success(result);
    }

    // ============ Excel ============
    @GetMapping("/export/excel")
    public void exportExcel(
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String countryMarket,
            @RequestParam(required = false) String accountName,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) CollaborationProgress progress,
            @RequestParam(required = false) VideoType videoType,
            @RequestParam(required = false) String videoMonth,
            @RequestParam(required = false) String internalProjectNo,
            @RequestParam(required = false) String clientOrderId,
            @RequestParam(required = false) String clientPaymentBatch,
            @RequestParam(required = false) Long projectManagerId,
            HttpServletResponse response) throws IOException {
        // 导出按当前筛选条件，取全部（不分页）
        PageRequest all = PageRequest.of(0, Integer.MAX_VALUE, Sort.by(Sort.Direction.DESC, "id"));
        String videoMonthParam = (videoMonth == null || videoMonth.trim().isEmpty()) ? null : videoMonth.trim();
        List<CollaborationTracking> list = trackingRepo.findByFilters(
                brandId, teamId, countryMarket, accountName, platform,
                progress, videoType, videoMonthParam, internalProjectNo,
                clientOrderId, clientPaymentBatch, projectManagerId, all).getContent();
        excelHandler.export(list, RoleUtil.canViewBaselineFinancials(), response);
    }

    @GetMapping("/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        excelHandler.downloadTemplate(RoleUtil.canViewBaselineFinancials(), response);
    }

    @PostMapping("/import/excel")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<Long> importExcel(@RequestParam("file") MultipartFile file) throws IOException {
        // 导入改成异步了：数据量一大（比如几百行），同步等待整个导入跑完很容易撞到
        // 浏览器/反向代理的超时限制。这里立即建一条"导入批次"记录、马上把 id 返回给前端，
        // 实际的导入过程丢到后台线程慢慢跑，前端可以去"导入历史"页面随时查进度和结果。
        ImportBatch batch = new ImportBatch();
        batch.setModule("COLLABORATION_TRACKING");
        batch.setFileName(file.getOriginalFilename());
        batch.setUploadedByName(RoleUtil.getCurrentUsername());
        batch.setStatus("PROCESSING");
        batch.setStartedAt(new java.util.Date());
        batch = importBatchRepo.save(batch);

        byte[] fileBytes = file.getBytes(); // 必须先读成字节数组，HTTP 请求结束后原始文件流就用不了了
        excelHandler.importDataAsync(batch.getId(), fileBytes, RoleUtil.canViewBaselineFinancials());
        return ApiResponse.success(batch.getId());
    }

    private CollaborationTracking maskSensitive(CollaborationTracking t) {
        CollaborationTracking copy = new CollaborationTracking();
        BeanUtils.copyProperties(t, copy);
        copy.setInfluencerCost(null);
        copy.setClientPrice(null);
        return copy;
    }
}
