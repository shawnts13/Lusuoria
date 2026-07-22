package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.dto.request.InfluencerRequirementRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.dto.response.InfluencerRequirementItemResponse;
import com.lusuoria.settlement.dto.response.RequirementContentParseResponse;
import com.lusuoria.settlement.dto.response.RequirementTrackingSummaryResponse;
import com.lusuoria.settlement.entity.InfluencerRequirement;
import com.lusuoria.settlement.excel.InfluencerRequirementExcelHandler;
import com.lusuoria.settlement.repository.InfluencerRequirementRepository;
import com.lusuoria.settlement.service.impl.InfluencerRequirementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Map;
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

    @GetMapping
    public ApiResponse<Page<InfluencerRequirement>> list(
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String accountName,
            @RequestParam(required = false) String requirementMonth,
            @RequestParam(required = false) String internalRequirementNo,
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
        Page<InfluencerRequirement> result = requirementRepo.findByFilters(
                brandId, teamId, accountName, requirementMonth, internalRequirementNo, pageable);

        // "需求完成进度"分子批量查出来再赋值，避免逐条查库
        List<String> nos = result.getContent().stream()
                .map(InfluencerRequirement::getInternalRequirementNo).collect(Collectors.toList());
        Map<String, Integer> completedByNo = requirementService.completedCountByNos(nos);
        result.forEach(r -> r.setCompletedCount(completedByNo.getOrDefault(r.getInternalRequirementNo(), 0)));

        return ApiResponse.success(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<InfluencerRequirement> getById(@PathVariable Long id) {
        InfluencerRequirement r = requirementRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("需求记录不存在"));
        Map<String, Integer> completedByNo = requirementService.completedCountByNos(
                java.util.Collections.singletonList(r.getInternalRequirementNo()));
        r.setCompletedCount(completedByNo.getOrDefault(r.getInternalRequirementNo(), 0));
        return ApiResponse.success(r);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<InfluencerRequirement> save(@Valid @RequestBody InfluencerRequirementRequest req) {
        return ApiResponse.success(requirementService.save(req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        requirementService.delete(id);
        return ApiResponse.success();
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

    @GetMapping("/export/excel")
    public void exportExcel(
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String accountName,
            @RequestParam(required = false) String requirementMonth,
            @RequestParam(required = false) String internalRequirementNo,
            HttpServletResponse response) throws IOException {
        PageRequest all = PageRequest.of(0, Integer.MAX_VALUE, Sort.by(Sort.Direction.DESC, "id"));
        List<InfluencerRequirement> list = requirementRepo.findByFilters(
                brandId, teamId, accountName, requirementMonth, internalRequirementNo, all).getContent();
        excelHandler.export(list, response);
    }
}
