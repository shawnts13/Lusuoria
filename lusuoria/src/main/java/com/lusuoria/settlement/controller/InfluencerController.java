package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.DomainCache;
import com.lusuoria.settlement.config.DomainSyncService;
import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.config.InfluencerCache;
import com.lusuoria.settlement.config.InfluencerTeamCache;
import com.lusuoria.settlement.dto.request.InfluencerRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.dto.response.InfluencerSimpleResponse;
import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.entity.ImportBatch;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.entity.InfluencerBrandTeam;
import com.lusuoria.settlement.entity.InfluencerBrandTeamView;
import com.lusuoria.settlement.entity.InfluencerTeam;
import com.lusuoria.settlement.enums.ProjectType;
import com.lusuoria.settlement.excel.InfluencerExcelHandler;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import com.lusuoria.settlement.repository.ImportBatchRepository;
import com.lusuoria.settlement.repository.InfluencerBrandTeamRepository;
import com.lusuoria.settlement.repository.InfluencerRepository;
import com.lusuoria.settlement.util.RoleUtil;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/influencers")
public class InfluencerController {

    @Autowired private InfluencerRepository influencerRepo;
    @Autowired private InfluencerBrandTeamRepository influencerBrandTeamRepo;
    @Autowired private InfluencerExcelHandler excelHandler;
    @Autowired private CollaborationTrackingRepository trackingRepo;
    @Autowired private BrandCache brandCache;
    @Autowired private EmployeeCache employeeCache;
    @Autowired private DomainCache domainCache;
    @Autowired private InfluencerTeamCache teamCache;
    @Autowired private InfluencerCache influencerCache;
    @Autowired private DomainSyncService domainSyncService;
    @Autowired private ImportBatchRepository importBatchRepo;

    // Google Drive 合同上传页面地址（后续在此配置）
    private static final String CONTRACT_UPLOAD_URL = "";

    @GetMapping
    public ApiResponse<Page<Influencer>> list(
            @RequestParam(required = false) ProjectType influencerType,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String countryMarket,
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) Long teamId,
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
                influencerType, platform, countryMarket, brandId, teamId,
                followerMin, followerMax, keyword, pageable);
        attachBrandTeamPairs(result.getContent());
        if (!RoleUtil.canViewBaselineFinancials()) {
            return ApiResponse.success(result.map(this::maskSensitive));
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/simple")
    public ApiResponse<List<InfluencerSimpleResponse>> simpleList() {
        return ApiResponse.success(influencerCache.getAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<Influencer> getById(@PathVariable Long id) {
        Influencer inf = influencerRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("红人不存在"));
        attachBrandTeamPairs(Collections.singletonList(inf));
        if (!RoleUtil.canViewBaselineFinancials()) return ApiResponse.success(maskSensitive(inf));
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
        attachBrandTeamPairs(list);
        excelHandler.export(list, RoleUtil.canViewBaselineFinancials(), response);
    }

    @GetMapping("/import/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        excelHandler.downloadTemplate(RoleUtil.canViewBaselineFinancials(), response);
    }

    @PostMapping("/import/excel")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<Long> importExcel(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) return ApiResponse.error(400, "请选择要上传的文件");
        String fn = file.getOriginalFilename();
        if (fn == null || (!fn.endsWith(".xlsx") && !fn.endsWith(".xls")))
            return ApiResponse.error(400, "只支持 .xlsx 或 .xls 格式");

        // 导入改成异步了（可以在"导入历史"页面查看进度和结果，跟红人合作跟踪模块一致）：
        // 立即建一条"导入批次"记录、马上把 id 返回给前端，实际的导入过程丢到后台线程慢慢跑
        ImportBatch batch = new ImportBatch();
        batch.setModule("INFLUENCER");
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
     * 红人合作次数：原来统计的是"已生成项目订单"的数量，2026-07 随"项目订单"模块废弃
     * 改成直接统计红人合作跟踪记录数（口径更直观：有多少条合作记录就是合作了多少次，
     * 不再要求必须填了"客户方的项目订单"才算数）。
     */
    @PostMapping("/project-counts")
    public ApiResponse<Map<Long, Long>> projectCounts(@RequestBody List<Long> influencerIds) {
        Map<Long, Long> result = new java.util.LinkedHashMap<Long, Long>();
        for (Long id : influencerIds) result.put(id, 0L);
        trackingRepo.countByInfluencerIds(influencerIds)
                .forEach(row -> result.put((Long) row[0], (Long) row[1]));
        return ApiResponse.success(result);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    @Transactional
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
        inf.setFollowerPerson(req.getFollowerPerson());
        inf.setNotes(req.getNotes());

        // 敏感字段只有有权限的角色才能修改
        if (RoleUtil.canViewBaselineFinancials()) {
            inf.setInfluencerCost(req.getInfluencerCost());
            inf.setAdSpendCost(req.getAdSpendCost());
            inf.setCopyrightCost(req.getCopyrightCost());
        }

        Influencer saved = influencerRepo.save(inf);

        // "品牌方-团队"关联：只处理真正变化的部分，不再"全删再插"
        // - 现有关联里，本次没提交的，软删除（isDeleted=true），保留记录本身
        // - 本次提交的里，之前没关联过的，新插入一条
        // - 本次提交的里，之前关联过但被移除过的（isDeleted=true），直接复活（isDeleted=false），
        //   不能真的插入新行，因为 (influencer_id, brand_id, team_id) 上有唯一约束，插入会跟旧行撞上
        // - 本次提交的里，本来就还关联着的，完全不动（不产生任何写库操作）
        List<InfluencerBrandTeam> existingRels = influencerBrandTeamRepo.findByInfluencerId(saved.getId());
        // key: brandId + "|" + teamId（teamId 可能为空，用 -1 占位区分"没配团队"这种关联）
        Map<String, InfluencerBrandTeam> existingByKey = new HashMap<String, InfluencerBrandTeam>();
        for (InfluencerBrandTeam rel : existingRels) {
            existingByKey.put(pairKey(rel.getBrandId(), rel.getTeamId()), rel);
        }

        Set<String> newKeys = new HashSet<String>();
        List<InfluencerRequest.BrandTeamPair> pairs = req.getBrandTeamPairs() != null
                ? req.getBrandTeamPairs() : Collections.<InfluencerRequest.BrandTeamPair>emptyList();
        for (InfluencerRequest.BrandTeamPair p : pairs) {
            if (p.getBrandId() == null) continue;
            Brand brand = brandCache.findById(p.getBrandId());
            if (brand == null) throw new RuntimeException("品牌方不存在：" + p.getBrandId());
            if (p.getTeamId() != null && teamCache.findById(p.getTeamId()) == null) {
                throw new RuntimeException("团队不存在：" + p.getTeamId());
            }
            newKeys.add(pairKey(p.getBrandId(), p.getTeamId()));
        }

        // 移除：现有有效关联里，不在本次提交列表中的
        for (InfluencerBrandTeam rel : existingRels) {
            if (!Boolean.TRUE.equals(rel.getIsDeleted())
                    && !newKeys.contains(pairKey(rel.getBrandId(), rel.getTeamId()))) {
                rel.setIsDeleted(true);
                influencerBrandTeamRepo.save(rel);
            }
        }
        // 新增/复活：本次提交列表里，之前不存在或已被软删除的
        for (InfluencerRequest.BrandTeamPair p : pairs) {
            if (p.getBrandId() == null) continue;
            String key = pairKey(p.getBrandId(), p.getTeamId());
            InfluencerBrandTeam rel = existingByKey.get(key);
            if (rel == null) {
                rel = new InfluencerBrandTeam();
                rel.setInfluencerId(saved.getId());
                rel.setBrandId(p.getBrandId());
                rel.setTeamId(p.getTeamId());
                rel.setIsDeleted(false);
                influencerBrandTeamRepo.save(rel);
            } else if (Boolean.TRUE.equals(rel.getIsDeleted())) {
                rel.setIsDeleted(false);
                influencerBrandTeamRepo.save(rel);
            }
            // else：已经是有效关联，不用动
        }

        domainSyncService.sync();
        influencerCache.refresh();
        attachBrandTeamPairs(Collections.singletonList(saved));
        return ApiResponse.success(saved);
    }

    /** 品牌方-团队 对的去重 key，teamId 为空时用 -1 占位（区分"这个品牌下没配团队"这种关联） */
    private String pairKey(Long brandId, Long teamId) {
        return brandId + "|" + (teamId != null ? teamId : -1L);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        Influencer inf = influencerRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("红人不存在"));
        inf.setIsDeleted(true);
        influencerRepo.save(inf);
        domainSyncService.sync();
        influencerCache.refresh();
        return ApiResponse.success();
    }

    /** 批量给一批红人填充关联的"品牌方-团队"对（避免逐条 N+1 查询） */
    private void attachBrandTeamPairs(List<Influencer> list) {
        if (list == null || list.isEmpty()) return;
        List<Long> ids = list.stream().map(Influencer::getId).collect(Collectors.toList());
        List<InfluencerBrandTeam> rels = influencerBrandTeamRepo.findByInfluencerIdIn(ids);
        Map<Long, List<InfluencerBrandTeamView>> byInfluencer = new HashMap<Long, List<InfluencerBrandTeamView>>();
        for (InfluencerBrandTeam rel : rels) {
            Brand brand = brandCache.findById(rel.getBrandId());
            InfluencerTeam team = teamCache.findById(rel.getTeamId());
            InfluencerBrandTeamView view = new InfluencerBrandTeamView(
                    rel.getBrandId(), brand != null ? brand.getName() : null,
                    rel.getTeamId(), team != null ? team.getName() : null);
            byInfluencer.computeIfAbsent(rel.getInfluencerId(), k -> new ArrayList<InfluencerBrandTeamView>()).add(view);
        }
        for (Influencer inf : list) {
            inf.setBrandTeamPairs(byInfluencer.getOrDefault(inf.getId(), Collections.<InfluencerBrandTeamView>emptyList()));
        }
    }

    private Influencer maskSensitive(Influencer inf) {
        Influencer copy = new Influencer();
        BeanUtils.copyProperties(inf, copy);
        copy.setInfluencerCost(null);
        copy.setAdSpendCost(null);
        copy.setCopyrightCost(null);
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
