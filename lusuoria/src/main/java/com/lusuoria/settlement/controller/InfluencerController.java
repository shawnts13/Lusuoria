package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.dto.request.InfluencerRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.enums.ProjectType;
import com.lusuoria.settlement.excel.InfluencerExcelHandler;
import com.lusuoria.settlement.repository.InfluencerRepository;
import com.lusuoria.settlement.repository.ProjectOrderRepository;
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
import java.util.Map;

@RestController
@RequestMapping("/api/influencers")
public class InfluencerController {

    @Autowired private InfluencerRepository influencerRepo;
    @Autowired private InfluencerExcelHandler excelHandler;
    @Autowired private ProjectOrderRepository projectOrderRepo;

    /** 分页查询（支持红人团队、平台筛选） */
    @GetMapping
    public ApiResponse<Page<Influencer>> list(
            @RequestParam(required = false) ProjectType influencerType,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String countryMarket,
            @RequestParam(required = false) String teamName,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.max(1, Math.min(size, 200));
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "accountName"));
        Page<Influencer> result = influencerRepo.findByFilters(
                influencerType, platform, countryMarket, teamName, keyword, pageable);
        if (!RoleUtil.canViewSensitiveFields()) {
            // 用 map 生成新对象，不修改 JPA 管理的 Entity 本身
            return ApiResponse.success(result.map(this::maskSensitive));
        }
        return ApiResponse.success(result);
    }

    /** 简单列表（供其他模块下拉选择用，不分页） */
    @GetMapping("/simple")
    public ApiResponse<List<Influencer>> simpleList() {
        List<Influencer> list = influencerRepo.findByIsDeletedFalseOrderByAccountNameAsc();
        if (!RoleUtil.canViewSensitiveFields()) {
            return ApiResponse.success(list.stream().map(this::maskSensitive)
                    .collect(java.util.stream.Collectors.toList()));
        }
        return ApiResponse.success(list);
    }

    @GetMapping("/{id}")
    public ApiResponse<Influencer> getById(@PathVariable Long id) {
        Influencer inf = influencerRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("红人不存在"));
        if (!RoleUtil.canViewSensitiveFields()) {
            return ApiResponse.success(maskSensitive(inf));
        }
        return ApiResponse.success(inf);
    }

    /** 导出 */
    @GetMapping("/export/excel")
    public void exportExcel(@RequestParam(required = false) ProjectType influencerType,
                            HttpServletResponse response) throws IOException {
        List<Influencer> list = influencerType != null
                ? influencerRepo.findByInfluencerTypeAndIsDeletedFalse(influencerType)
                : influencerRepo.findByIsDeletedFalseOrderByAccountNameAsc();
        excelHandler.export(list, RoleUtil.canViewSensitiveFields(), response);
    }

    /** 下载导入模板 */
    @GetMapping("/import/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        excelHandler.downloadTemplate(RoleUtil.canViewSensitiveFields(), response);
    }

    /** 导入 */
    @PostMapping("/import/excel")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<List<String>> importExcel(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) return ApiResponse.error(400, "请选择要上传的文件");
        String fn = file.getOriginalFilename();
        if (fn == null || (!fn.endsWith(".xlsx") && !fn.endsWith(".xls")))
            return ApiResponse.error(400, "只支持 .xlsx 或 .xls 格式");
        return ApiResponse.success(excelHandler.importData(file, RoleUtil.canViewSensitiveFields()));
    }

    /** 新建/更新 */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<Influencer> save(@Valid @RequestBody InfluencerRequest req) {
        Influencer influencer;
        if (req.getId() != null) {
            influencer = influencerRepo.findByIdAndIsDeletedFalse(req.getId())
                    .orElseThrow(() -> new RuntimeException("红人不存在"));
        } else {
            influencer = new Influencer();
            influencer.setIsDeleted(false);
        }
        influencer.setInfluencerType(req.getInfluencerType());
        influencer.setTeamNames(listToStr(req.getTeamNames()));
        influencer.setAccountName(req.getAccountName());
        influencer.setCountryMarket(req.getCountryMarket());
        influencer.setPlatform(req.getPlatform());
        influencer.setDomain(req.getDomain());
        influencer.setFollowerCount(req.getFollowerCount());
        influencer.setLinks(listToStr(req.getLinks()));
        influencer.setCasesLinks(listToStr(req.getCasesLinks()));
        influencer.setEmail(req.getEmail());
        influencer.setContactStatus(req.getContactStatus());
        influencer.setPaymentCycle(req.getPaymentCycle());
        influencer.setFollowerPerson(req.getFollowerPerson());
        influencer.setNotes(req.getNotes());

        // 敏感字段只有有权限的角色才能修改
        if (RoleUtil.canViewSensitiveFields()) {
            influencer.setInfluencerCost(req.getInfluencerCost());
            influencer.setClientPrice(req.getClientPrice());
        }

        return ApiResponse.success(influencerRepo.save(influencer));
    }

    /**
     * 批量查询红人的合作项目数量（一条 SQL）
     * 前端传当前页的 influencer id 列表
     * 返回：{ influencerId: count, ... }
     */
    @PostMapping("/project-counts")
    public ApiResponse<Map<Long, Long>> projectCounts(@RequestBody List<Long> influencerIds) {
        Map<Long, Long> result = new java.util.LinkedHashMap<Long, Long>();
        // 先把所有 id 初始化为 0（没有项目的红人不会出现在查询结果里）
        for (Long id : influencerIds) result.put(id, 0L);
        // 一条 SQL 批量查有项目的红人
        projectOrderRepo.countByInfluencerIds(influencerIds)
                .forEach(row -> result.put((Long) row[0], (Long) row[1]));
        return ApiResponse.success(result);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        Influencer inf = influencerRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("红人不存在"));
        inf.setIsDeleted(true);
        influencerRepo.save(inf);
        return ApiResponse.success();
    }

    /**
     * 返回一个浅拷贝，将敏感字段置空
     * 不修改 JPA 管理的原始 Entity，避免意外触发 Hibernate dirty-check update
     */
    private Influencer maskSensitive(Influencer inf) {
        Influencer copy = new Influencer();
        org.springframework.beans.BeanUtils.copyProperties(inf, copy);
        copy.setInfluencerCost(null);
        copy.setClientPrice(null);
        return copy;
    }

    // 列转逗号分隔字符串
    private String listToStr(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (s != null && !s.trim().isEmpty()) {
                if (sb.length() > 0) sb.append(",");
                sb.append(s.trim());
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }
}
