package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.dto.request.ContractLinkRequest;
import com.lusuoria.settlement.dto.request.DeleteRequestReasonRequest;
import com.lusuoria.settlement.dto.request.InfluencerRequirementRequest;
import com.lusuoria.settlement.dto.request.InvoiceLinkRequest;
import com.lusuoria.settlement.dto.request.LinkLegacyTrackingsRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.dto.response.InfluencerRequirementItemResponse;
import com.lusuoria.settlement.dto.response.LegacyTrackingCandidateResponse;
import com.lusuoria.settlement.dto.response.RequirementContentParseResponse;
import com.lusuoria.settlement.dto.response.RequirementTrackingSummaryResponse;
import com.lusuoria.settlement.entity.InfluencerRequirement;
import com.lusuoria.settlement.entity.PendingApproval;
import com.lusuoria.settlement.enums.PendingApprovalCategory;
import com.lusuoria.settlement.enums.PendingApprovalModule;
import com.lusuoria.settlement.excel.InfluencerRequirementExcelHandler;
import com.lusuoria.settlement.repository.InfluencerRequirementRepository;
import com.lusuoria.settlement.repository.PendingApprovalRepository;
import com.lusuoria.settlement.service.impl.InfluencerRequirementService;
import com.lusuoria.settlement.util.PaymentAccessUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 红人需求管理
 */
@RestController
@RequestMapping("/api/influencer-requirements")
public class InfluencerRequirementController {

    @Autowired private InfluencerRequirementRepository requirementRepo;
    @Autowired private InfluencerRequirementService requirementService;
    @Autowired private InfluencerRequirementExcelHandler excelHandler;
    @Autowired private PaymentAccessUtil paymentAccessUtil;
    @Autowired private PendingApprovalRepository pendingApprovalRepo;

    @GetMapping
    public ApiResponse<Page<InfluencerRequirement>> list(
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String accountName,
            @RequestParam(required = false) String requirementMonth,
            @RequestParam(required = false) String internalRequirementNo,
            // 需求完成月份（'yyyyMM'，2026-08 新增）：按 completedAt 落在这个月筛选，见
            // completedMonthRange() 的换算逻辑
            @RequestParam(required = false) String completedMonth,
            @RequestParam(defaultValue = "false") boolean onlyIncomplete,
            @RequestParam(defaultValue = "false") boolean onlyMissingInvoice,
            @RequestParam(defaultValue = "false") boolean onlyMissingContract,
            @RequestParam(defaultValue = "false") boolean onlyUnsettled,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.max(1, Math.min(size, 200));
        String sortProperty = "accountName".equals(sortBy) ? "influencer.accountName" : sortBy;
        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(Sort.Direction.ASC, sortProperty)
                : Sort.by(Sort.Direction.DESC, sortProperty);
        PageRequest pageable = PageRequest.of(page, size, sort);
        String completedMonthParam = (completedMonth == null || completedMonth.trim().isEmpty())
                ? null : completedMonth.trim();

        // "查看未完成的需求"/"查看未上传invoice的需求"/"查看未上传合同的需求"/"查看未结款的需求"：
        // 四个开关互斥（前端只会传其中一个true），都走单独的全量查询+内存筛选+手动分页，见各自方法的注释
        Page<InfluencerRequirement> result;
        if (onlyIncomplete) {
            result = requirementService.pageIncomplete(
                    brandId, teamId, accountName, requirementMonth, internalRequirementNo,
                    completedMonthParam, pageable);
        } else if (onlyMissingInvoice) {
            result = requirementService.pageMissingInvoice(
                    brandId, teamId, accountName, requirementMonth, internalRequirementNo,
                    completedMonthParam, pageable);
        } else if (onlyMissingContract) {
            result = requirementService.pageMissingContract(
                    brandId, teamId, accountName, requirementMonth, internalRequirementNo,
                    completedMonthParam, pageable);
        } else if (onlyUnsettled) {
            // "查看未结款的需求"（2026-08 新增）：只有员工角色=管理层才能看，前端按钮本身也只对
            // 管理层展示，这里是后端兜底——跟"红人结款"模块权限判定同一套（PaymentAccessUtil），
            // 因为这个筛选本质上是给管理层安排结款用的
            if (!paymentAccessUtil.canManage()) return ApiResponse.error(403, "无权限查看未结款的需求");
            result = requirementService.pageUnsettled(
                    brandId, teamId, accountName, requirementMonth, internalRequirementNo,
                    completedMonthParam, pageable);
        } else if ("id".equals(sortBy)) {
            // "全部需求"视角下的默认排序（前端没有点过别的列头，还是初始的按 id）：未完成的排在
            // 已完成的前面，见 listIncompleteFirst 的注释。用户主动点了别的列头排序时不做这层重排。
            result = requirementService.listIncompleteFirst(
                    brandId, teamId, accountName, requirementMonth, internalRequirementNo,
                    completedMonthParam, pageable);
        } else {
            result = requirementRepo.findByFilters(
                    brandId, teamId, accountName, requirementMonth, internalRequirementNo,
                    completedMonthParam, pageable);
            // "需求完成进度"分子、"已建立跟踪记录数"（"新建合作跟踪"按钮判断用）批量查出来再赋值，
            // 避免逐条查库——上面几个分支各自的 service 方法内部已经做过这一步，只有这条默认
            // 路径（findByFilters 直查）还需要在这里补上
            List<String> nos = result.getContent().stream()
                    .map(InfluencerRequirement::getInternalRequirementNo).collect(Collectors.toList());
            Map<String, Integer> completedByNo = requirementService.completedCountByNos(nos);
            Map<String, Integer> establishedByNo = requirementService.establishedCountByNos(nos);
            result.forEach(r -> {
                r.setCompletedCount(completedByNo.getOrDefault(r.getInternalRequirementNo(), 0));
                r.setEstablishedCount(establishedByNo.getOrDefault(r.getInternalRequirementNo(), 0));
            });
        }

        // 批量标记"当前是否有待审核的删除申请"（2026-08 新增，跟红人合作跟踪一样），不管走的是
        // 上面哪个分支都要补这一层，所以放在所有分支汇合之后统一处理一次，不重复写六遍
        Set<Long> pendingDeleteIds = new HashSet<>(pendingApprovalRepo.findPendingTargetIds(
                PendingApprovalModule.INFLUENCER_REQUIREMENT, PendingApprovalCategory.DELETE_REQUEST));
        result.forEach(r -> r.setHasPendingDeleteRequest(pendingDeleteIds.contains(r.getId())));

        return ApiResponse.success(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<InfluencerRequirement> getById(@PathVariable Long id) {
        InfluencerRequirement r = requirementRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("需求记录不存在"));
        Map<String, Integer> completedByNo = requirementService.completedCountByNos(
                java.util.Collections.singletonList(r.getInternalRequirementNo()));
        r.setCompletedCount(completedByNo.getOrDefault(r.getInternalRequirementNo(), 0));
        r.setHasPendingDeleteRequest(pendingApprovalRepo.existsByTargetModuleAndTargetIdAndCategoryAndStatus(
                PendingApprovalModule.INFLUENCER_REQUIREMENT, r.getId(),
                PendingApprovalCategory.DELETE_REQUEST, com.lusuoria.settlement.enums.PendingApprovalStatus.PENDING));
        return ApiResponse.success(r);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<InfluencerRequirement> save(@Valid @RequestBody InfluencerRequirementRequest req) {
        return ApiResponse.success(requirementService.save(req));
    }

    /**
     * 发起删除申请（不直接删除）：填写删除原因后生成一条"待处理"审核事项，
     * 由 ADMIN 在"待处理"模块同意后才真正删除。2026-08 起跟"红人合作跟踪"保持一致，
     * 不再是直接 DELETE。
     */
    @PostMapping("/{id}/delete-request")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<PendingApproval> requestDelete(
            @PathVariable Long id, @Valid @RequestBody DeleteRequestReasonRequest req) {
        return ApiResponse.success(requirementService.requestDelete(id, req.getReason()));
    }

    @GetMapping("/{id}/items")
    public ApiResponse<List<InfluencerRequirementItemResponse>> items(@PathVariable Long id) {
        return ApiResponse.success(requirementService.listItems(id));
    }

    /** "关联红人需求"选择器第一步：某个红人名下"需求完成进度"未满的需求 */
    @GetMapping("/by-influencer/{influencerId}")
    public ApiResponse<List<InfluencerRequirement>> byInfluencer(@PathVariable Long influencerId) {
        return ApiResponse.success(requirementService.listIncompleteByInfluencer(influencerId));
    }

    @GetMapping("/{id}/progress-detail")
    public ApiResponse<List<RequirementTrackingSummaryResponse>> progressDetail(@PathVariable Long id) {
        return ApiResponse.success(requirementService.progressDetail(id));
    }

    /** "提取需求内容"：纯文本进来，解析出结构化草稿返回，不落库 */
    @PostMapping("/parse-content")
    public ApiResponse<RequirementContentParseResponse> parseContent(@RequestBody ParseContentRequest req) {
        return ApiResponse.success(requirementService.parseContent(req.getContent()));
    }

    @lombok.Data
    public static class ParseContentRequest {
        private String content;
    }

    /** "存量记录关联需求"第三步候选：某个红人下还没关联需求、且跟需求条目匹配的红人合作跟踪记录 */
    @GetMapping("/legacy-candidates")
    public ApiResponse<List<LegacyTrackingCandidateResponse>> legacyCandidates(
            @RequestParam Long influencerId, @RequestParam String internalRequirementNo) {
        return ApiResponse.success(requirementService.findLegacyCandidates(influencerId, internalRequirementNo));
    }

    /** "存量记录关联需求"确认：批量给选中的存量红人合作跟踪记录写上内部需求编号 */
    @PostMapping("/link-legacy")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<Void> linkLegacy(@RequestBody LinkLegacyTrackingsRequest req) {
        requirementService.linkLegacyTrackings(req.getInternalRequirementNo(), req.getTrackingIds());
        return ApiResponse.success();
    }

    /**
     * "重新计算需求完成时间"（仅 ADMIN，善后用）。用途见
     * InfluencerRequirementService.recomputeAllCompletedAt() 的说明——主要是给
     * "存量记录关联需求"历史上漏调 refreshCompletedAt() 导致的遗留数据做一次性修复。
     */
    @PostMapping("/recompute-completed-at")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<String> recomputeCompletedAt() {
        return ApiResponse.success(requirementService.recomputeAllCompletedAt());
    }

    /**
     * 上传/修改 Invoice 链接：品牌方不需要 invoice、或需求完成进度未满 100% 时后端会拒绝
     * （前端按钮已经按同样的条件禁用，这里是兜底）。成功后级联更新对应"红人合作跟踪"记录的
     * 红人结款进度，见 InfluencerRequirementService.uploadInvoiceLink()。
     */
    @PostMapping("/{id}/invoice-link")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<InfluencerRequirement> uploadInvoiceLink(
            @PathVariable Long id, @Valid @RequestBody InvoiceLinkRequest req) {
        return ApiResponse.success(requirementService.uploadInvoiceLink(id, req.getInvoiceLink()));
    }

    /**
     * 上传/修改合同链接：只针对品牌方"每次需求签一次合同"的场景，一年签一次合同的品牌方
     * 后端会拒绝（前端按钮正常情况下也不会出现，这里是兜底），见
     * InfluencerRequirementService.uploadContractLink()。
     */
    @PostMapping("/{id}/contract-link")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<InfluencerRequirement> uploadContractLink(
            @PathVariable Long id, @Valid @RequestBody ContractLinkRequest req) {
        return ApiResponse.success(requirementService.uploadContractLink(id, req.getContractLink()));
    }

    @GetMapping("/export/excel")
    public void exportExcel(
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String accountName,
            @RequestParam(required = false) String requirementMonth,
            @RequestParam(required = false) String internalRequirementNo,
            @RequestParam(required = false) String completedMonth,
            HttpServletResponse response) throws IOException {
        PageRequest all = PageRequest.of(0, Integer.MAX_VALUE, Sort.by(Sort.Direction.DESC, "id"));
        String completedMonthParam = (completedMonth == null || completedMonth.trim().isEmpty())
                ? null : completedMonth.trim();
        List<InfluencerRequirement> list = requirementRepo.findByFilters(
                brandId, teamId, accountName, requirementMonth, internalRequirementNo,
                completedMonthParam, all).getContent();
        // "需求完成进度"分子是瞬态字段，findByFilters 查出来的实体不会自动带上，导出前批量补一次
        // （口径/写法跟 InfluencerRequirementService 分页接口给列表页用的完全一致）
        List<String> nos = list.stream().map(InfluencerRequirement::getInternalRequirementNo)
                .filter(java.util.Objects::nonNull).collect(Collectors.toList());
        Map<String, Integer> completedByNo = requirementService.completedCountByNos(nos);
        for (InfluencerRequirement r : list) {
            r.setCompletedCount(completedByNo.getOrDefault(r.getInternalRequirementNo(), 0));
        }
        excelHandler.export(list, response);
    }
}
