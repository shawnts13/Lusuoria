package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.dto.request.CollaborationTrackingRequest;
import com.lusuoria.settlement.dto.request.CollaborationTrackingStatusRequest;
import com.lusuoria.settlement.dto.request.DeleteRequestReasonRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.dto.response.CollaborationStatusUpdateResult;
import com.lusuoria.settlement.dto.response.ExecutorCostSuggestionResponse;
import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.entity.ImportBatch;
import com.lusuoria.settlement.entity.PendingApproval;
import com.lusuoria.settlement.enums.CollaborationProgress;
import com.lusuoria.settlement.enums.InfluencerPaymentProgress;
import com.lusuoria.settlement.enums.PendingApprovalCategory;
import com.lusuoria.settlement.enums.PendingApprovalModule;
import com.lusuoria.settlement.enums.VideoType;
import com.lusuoria.settlement.excel.CollaborationTrackingExcelHandler;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import com.lusuoria.settlement.repository.ImportBatchRepository;
import com.lusuoria.settlement.repository.PendingApprovalRepository;
import com.lusuoria.settlement.service.impl.CollaborationTrackingService;
import com.lusuoria.settlement.util.ProjectFieldVisibility;
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
 *   4091 - 去重命中（前端提示，不重试）
 */
@RestController
@RequestMapping("/api/collaboration-trackings")
public class CollaborationTrackingController {

    public static final int CODE_DUPLICATE           = 4091;

    @Autowired private CollaborationTrackingRepository trackingRepo;
    @Autowired private CollaborationTrackingService trackingService;
    @Autowired private CollaborationTrackingExcelHandler excelHandler;
    @Autowired private ImportBatchRepository importBatchRepo;
    @Autowired private BrandCache brandCache;
    @Autowired private PendingApprovalRepository pendingApprovalRepo;
    @Autowired private ProjectFieldVisibility fieldVisibility;

    @GetMapping
    public ApiResponse<Page<CollaborationTracking>> list(
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String countryMarket,
            @RequestParam(required = false) String accountName,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) CollaborationProgress progress,
            @RequestParam(required = false) InfluencerPaymentProgress influencerPaymentProgress,
            @RequestParam(required = false) VideoType videoType,
            @RequestParam(required = false) String videoMonth,
            @RequestParam(required = false) String internalProjectNo,
            @RequestParam(required = false) String internalRequirementNo,
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
                progress, influencerPaymentProgress, videoType, videoMonthParam, internalProjectNo, internalRequirementNo,
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

        // 字段级可见性统一走 ProjectFieldVisibility：FULL（ADMIN/管理层/财务/AUDITOR）看全部，
        // 其余角色（含 GUEST）按分级规则脱敏，applyFieldVisibility 内部已经处理了所有分级
        ProjectFieldVisibility.Context ctx = fieldVisibility.resolve();
        if (!ctx.isFull()) {
            return ApiResponse.success(result.map(t -> applyFieldVisibility(t, ctx)));
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<CollaborationTracking> getById(@PathVariable Long id) {
        CollaborationTracking t = trackingRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("跟踪记录不存在"));
        ProjectFieldVisibility.Context ctx = fieldVisibility.resolve();
        if (!ctx.isFull()) t = applyFieldVisibility(t, ctx);
        return ApiResponse.success(t);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<CollaborationTracking> save(@Valid @RequestBody CollaborationTrackingRequest req) {
        try {
            CollaborationTracking saved = trackingService.save(req);
            ProjectFieldVisibility.Context ctx = fieldVisibility.resolve();
            CollaborationTracking out = ctx.isFull() ? saved : applyFieldVisibility(saved, ctx);
            return ApiResponse.success(out);
        } catch (CollaborationTrackingService.DuplicateTrackingException e) {
            return ApiResponse.error(CODE_DUPLICATE, e.getMessage());
        }
    }

    /**
     * "复制"批量新建：新建弹窗左侧克隆出的多个"视频项目"面板一次性提交，整批在一个事务里
     * 校验+保存，任何一条失败（含关联需求超量）都整批回滚，前端据此保留所有面板不关闭。
     */
    @PostMapping("/batch")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<List<CollaborationTracking>> createBatch(@Valid @RequestBody List<CollaborationTrackingRequest> reqs) {
        List<CollaborationTracking> saved = trackingService.createBatch(reqs);
        ProjectFieldVisibility.Context ctx = fieldVisibility.resolve();
        List<CollaborationTracking> out = ctx.isFull() ? saved
                : saved.stream().map(t -> applyFieldVisibility(t, ctx)).collect(java.util.stream.Collectors.toList());
        return ApiResponse.success(out);
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
        ProjectFieldVisibility.Context ctx = fieldVisibility.resolve();
        if (!ctx.isFull()) {
            result.setTracking(applyFieldVisibility(result.getTracking(), ctx));
        }
        return ApiResponse.success(result);
    }

    /**
     * 内部执行成本弹窗打开时调用：只读，算出默认建议金额 + 算出来的依据说明，不修改任何数据。
     * executorId 可选：弹窗里如果这条记录还没选执行人员、用户现场选了一个人，传这个参数可以
     * 现算这个人的建议金额；不传就退回这条记录数据库里已存的执行人员。
     */
    @GetMapping("/{id}/executor-cost-suggestion")
    public ApiResponse<ExecutorCostSuggestionResponse> suggestExecutorCost(
            @PathVariable Long id, @RequestParam(required = false) Long executorId) {
        return ApiResponse.success(trackingService.suggestExecutorCost(id, executorId));
    }

    /**
     * 内部执行成本弹窗确认时调用：保存金额（或确认"不涉及执行人员"），跟状态流转是分开的两步操作。
     */
    @PatchMapping("/{id}/executor-cost")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<CollaborationTracking> setExecutorCost(
            @PathVariable Long id, @RequestBody ExecutorCostRequest req) {
        CollaborationTracking saved = trackingService.setExecutorCost(
                id, req.getExecutorId(), req.getAmount(), req.isNotApplicable());
        ProjectFieldVisibility.Context ctx = fieldVisibility.resolve();
        CollaborationTracking out = ctx.isFull() ? saved : applyFieldVisibility(saved, ctx);
        return ApiResponse.success(out);
    }

    /** 内部执行成本保存请求体 */
    @lombok.Data
    public static class ExecutorCostRequest {
        private java.math.BigDecimal amount;
        private Long executorId;
        private boolean notApplicable;
    }

    /**
     * 解除跟"红人需求管理"内部需求编号的关联（误关联到别的需求时用）。权限收窄成该记录的
     * 项目负责人/执行人员或 ADMIN，见 CollaborationTrackingService.unlinkRequirement。
     */
    @PatchMapping("/{id}/unlink-requirement")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<CollaborationTracking> unlinkRequirement(@PathVariable Long id) {
        CollaborationTracking saved = trackingService.unlinkRequirement(id);
        ProjectFieldVisibility.Context ctx = fieldVisibility.resolve();
        CollaborationTracking out = ctx.isFull() ? saved : applyFieldVisibility(saved, ctx);
        return ApiResponse.success(out);
    }

    /**
     * 批量重新计算所有记录的毛利/可分配利润/提成/公司利润（仅 ADMIN）。
     * 用途见 CollaborationTrackingService.recomputeAllProfits() 的说明——主要是给"有人绕过
     * 系统直接改了数据库里的红人成本/客户合作价格之类原始值"这种情况做善后。
     */
    @PostMapping("/recompute-profits")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<String> recomputeProfits() {
        int count = trackingService.recomputeAllProfits();
        return ApiResponse.success("已重新计算 " + count + " 条记录");
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
            @RequestParam(required = false) InfluencerPaymentProgress influencerPaymentProgress,
            @RequestParam(required = false) VideoType videoType,
            @RequestParam(required = false) String videoMonth,
            @RequestParam(required = false) String internalProjectNo,
            @RequestParam(required = false) String internalRequirementNo,
            @RequestParam(required = false) String clientOrderId,
            @RequestParam(required = false) String clientPaymentBatch,
            @RequestParam(required = false) Long projectManagerId,
            HttpServletResponse response) throws IOException {
        // 导出按当前筛选条件，取全部（不分页）
        PageRequest all = PageRequest.of(0, Integer.MAX_VALUE, Sort.by(Sort.Direction.DESC, "id"));
        String videoMonthParam = (videoMonth == null || videoMonth.trim().isEmpty()) ? null : videoMonth.trim();
        List<CollaborationTracking> list = trackingRepo.findByFilters(
                brandId, teamId, countryMarket, accountName, platform,
                progress, influencerPaymentProgress, videoType, videoMonthParam, internalProjectNo, internalRequirementNo,
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

    /**
     * 按当前登录账号的字段可见等级，对一条跟踪记录做脱敏（返回一个新副本，不改动原对象）。
     * 规则跟原来"项目订单"模块的 ProjectFieldVisibility 基本一致，2026-07 收紧了一处：
     *   - 红人成本/客户合作价格：GUEST 之外都能看
     *   - 汇率/其他外部成本/外部成本备注：仅 FULL（ADMIN/AUDITOR，或员工角色=管理层/财务）
     *     可见——项目负责人/执行人员一律看不到，不再有"自己负责的记录能看"这个例外
     *     （2026-07 从"项目负责人可见自己记录"改成"完全不可见"，跟前端记账字段收紧保持一致）
     *   - 内部执行成本：FULL 都能看；项目负责人/执行人员仅自己相关的记录能看，其余脱敏
     *   - 提成比例/提成金额：FULL 都能看；项目负责人仅自己负责的记录只读可见，其余脱敏
     *   - 项目毛利/可分配利润/公司利润(美金/人民币)：仅 FULL 可见
     */
    private CollaborationTracking applyFieldVisibility(CollaborationTracking t, ProjectFieldVisibility.Context ctx) {
        CollaborationTracking copy = new CollaborationTracking();
        BeanUtils.copyProperties(t, copy);

        boolean isOwnManager = ctx.employeeId != null && t.getProjectManagerId() != null
                && ctx.employeeId.equals(t.getProjectManagerId());
        boolean isOwnExecutor = ctx.employeeId != null && t.getExecutorId() != null
                && ctx.employeeId.equals(t.getExecutorId());
        boolean isManagerTier  = ctx.tier == ProjectFieldVisibility.Tier.PROJECT_MANAGER;
        boolean isExecutorTier = ctx.tier == ProjectFieldVisibility.Tier.EXECUTOR;

        boolean canSeeBaseline = ctx.tier != ProjectFieldVisibility.Tier.GUEST;
        if (!canSeeBaseline) {
            copy.setInfluencerCost(null);
            copy.setClientPrice(null);
        }

        if (!ctx.isFull()) {
            copy.setExchangeRate(null);
            copy.setOtherExternalCost(null);
            copy.setOtherExternalCostNote(null);
        }

        if (!(ctx.isFull() || (isManagerTier && isOwnManager) || (isExecutorTier && isOwnExecutor))) {
            copy.setInternalExecutionCost(null);
        }

        if (!(ctx.isFull() || (isManagerTier && isOwnManager))) {
            copy.setCommissionRate(null);
            copy.setCommissionAmount(null);
        }

        if (!ctx.isFull()) {
            copy.setGrossProfit(null);
            copy.setDistributableProfit(null);
            copy.setCompanyNetProfit(null);
            copy.setRmbRevenue(null);
        }

        return copy;
    }
}
