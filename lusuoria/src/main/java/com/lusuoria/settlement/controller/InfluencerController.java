package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.DomainCache;
import com.lusuoria.settlement.config.DomainSyncService;
import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.config.InfluencerTeamCache;
import com.lusuoria.settlement.dto.request.InfluencerRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.enums.ProjectType;
import com.lusuoria.settlement.excel.InfluencerExcelHandler;
import com.lusuoria.settlement.repository.InfluencerRepository;
import com.lusuoria.settlement.repository.ProjectOrderRepository;
import com.lusuoria.settlement.util.RoleUtil;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/influencers")
public class InfluencerController {

    @Autowired private InfluencerRepository influencerRepo;
    @Autowired private InfluencerExcelHandler excelHandler;
    @Autowired private ProjectOrderRepository projectOrderRepo;
    @Autowired private BrandCache brandCache;
    @Autowired private EmployeeCache employeeCache;
    @Autowired private DomainCache domainCache;
    @Autowired private InfluencerTeamCache teamCache;
    @Autowired private DomainSyncService domainSyncService;

    // Google Drive 合同上传页面地址（后续在此配置）
    private static final String CONTRACT_UPLOAD_URL = "";

    @GetMapping
    public ApiResponse<Page<Influencer>> list(
            @RequestParam(required = false) ProjectType influencerType,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String countryMarket,
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) String teamName,
            @RequestParam(required = false) Long followerMin,
            @RequestParam(required = false) Long followerMax,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "accountName") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.max(1, Math.min(size, 200));
        Sort sort = sortDir.equalsIgnoreCase("desc")
                ? Sort.by(Sort.Direction.DESC, sortBy)
                : Sort.by(Sort.Direction.ASC,  sortBy);
        PageRequest pageable = PageRequest.of(page, size, sort);
        Page<Influencer> result = influencerRepo.findByFilters(
                influencerType, platform, countryMarket, brandId, teamName,
                followerMin, followerMax, keyword, pageable);
        if (!RoleUtil.canViewSensitiveFields()) {
            return ApiResponse.success(result.map(this::maskSensitive));
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/simple")
    public ApiResponse<List<Influencer>> simpleList() {
        List<Influencer> list = influencerRepo.findByIsDeletedFalseOrderByAccountNameAsc();
        if (!RoleUtil.canViewSensitiveFields()) {
            return ApiResponse.success(list.stream().map(this::maskSensitive).collect(Collectors.toList()));
        }
        return ApiResponse.success(list);
    }

    @GetMapping("/{id}")
    public ApiResponse<Influencer> getById(@PathVariable Long id) {
        Influencer inf = influencerRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("红人不存在"));
        if (!RoleUtil.canViewSensitiveFields()) return ApiResponse.success(maskSensitive(inf));
        return ApiResponse.success(inf);
    }

    /** 获取合同上传页面地址 */
    @GetMapping("/contract-upload-url")
    public ApiResponse<String> contractUploadUrl() {
        return ApiResponse.success(CONTRACT_UPLOAD_URL);
    }

    @GetMapping("/export/excel")
    public void exportExcel(@RequestParam(required = false) ProjectType influencerType,
                            HttpServletResponse response) throws IOException {
        List<Influencer> list = influencerType != null
                ? influencerRepo.findByInfluencerTypeAndIsDeletedFalse(influencerType)
                : influencerRepo.findByIsDeletedFalseOrderByAccountNameAsc();
        excelHandler.export(list, RoleUtil.canViewSensitiveFields(), response);
    }

    @GetMapping("/import/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        excelHandler.downloadTemplate(RoleUtil.canViewSensitiveFields(), response);
    }

    @PostMapping("/import/excel")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<List<String>> importExcel(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) return ApiResponse.error(400, "请选择要上传的文件");
        String fn = file.getOriginalFilename();
        if (fn == null || (!fn.endsWith(".xlsx") && !fn.endsWith(".xls")))
            return ApiResponse.error(400, "只支持 .xlsx 或 .xls 格式");
        List<String> result = excelHandler.importData(file, RoleUtil.canViewSensitiveFields());
        domainSyncService.sync();
        return ApiResponse.success(result);
    }

    @PostMapping("/project-counts")
    public ApiResponse<Map<Long, Long>> projectCounts(@RequestBody List<Long> influencerIds) {
        Map<Long, Long> result = new java.util.LinkedHashMap<Long, Long>();
        for (Long id : influencerIds) result.put(id, 0L);
        projectOrderRepo.countByInfluencerIds(influencerIds)
                .forEach(row -> result.put((Long) row[0], (Long) row[1]));
        return ApiResponse.success(result);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<Influencer> save(@Valid @RequestBody InfluencerRequest req) {
        Influencer inf;
        if (req.getId() != null) {
            inf = influencerRepo.findByIdAndIsDeletedFalse(req.getId())
                    .orElseThrow(() -> new RuntimeException("红人不存在"));
        } else {
            inf = new Influencer();
            inf.setIsDeleted(false);
        }
        inf.setInfluencerType(req.getInfluencerType());
        // 保存红人时自动把新团队名注册到 influencer_teams 表
        if (req.getTeamName() != null && !req.getTeamName().trim().isEmpty()) {
            teamCache.getOrCreate(req.getTeamName().trim());
        }
        inf.setTeamName(req.getTeamName());
        inf.setAccountName(req.getAccountName());
        inf.setCountryMarket(req.getCountryMarket());
        inf.setPlatform(req.getPlatform());
        inf.setDomains(listToStr(req.getDomains(), "\n"));
        inf.setFollowerCount(req.getFollowerCount());
        inf.setLinks(listToStr(req.getLinks(), "\n"));
        inf.setCasesLinks(listToStr(req.getCasesLinks(), "\n"));
        inf.setContractLink(req.getContractLink());
        inf.setEmail(req.getEmail());
        inf.setContacts(req.getContacts());
        inf.setContactStatus(req.getContactStatus());
        inf.setPaymentCycle(req.getPaymentCycle());
        inf.setFollowerPerson(req.getFollowerPerson());
        inf.setNotes(req.getNotes());

        // 关联品牌方
        if (req.getBrandId() != null) {
            Brand brand = brandCache.findById(req.getBrandId());
            if (brand == null) throw new RuntimeException("品牌方不存在：" + req.getBrandId());
            inf.setBrand(brand);
        } else {
            inf.setBrand(null);
        }

        // 敏感字段只有有权限的角色才能修改
        if (RoleUtil.canViewSensitiveFields()) {
            inf.setInfluencerCost(req.getInfluencerCost());
            inf.setClientPrice(req.getClientPrice());
        }

        Influencer saved = influencerRepo.save(inf);
        domainSyncService.sync();
        return ApiResponse.success(saved);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        Influencer inf = influencerRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("红人不存在"));
        inf.setIsDeleted(true);
        influencerRepo.save(inf);
        domainSyncService.sync();
        return ApiResponse.success();
    }

    private Influencer maskSensitive(Influencer inf) {
        Influencer copy = new Influencer();
        BeanUtils.copyProperties(inf, copy);
        copy.setInfluencerCost(null);
        copy.setClientPrice(null);
        return copy;
    }

    private String listToStr(List<String> list, String sep) {
        if (list == null || list.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (s != null && !s.trim().isEmpty()) {
                if (sb.length() > 0) sb.append(sep);
                sb.append(s.trim());
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }
}
