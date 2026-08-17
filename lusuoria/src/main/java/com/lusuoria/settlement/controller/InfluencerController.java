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
    @Autowired private com.lusuoria.settlement.repository.InfluencerRequirementRepository requirementRepo;
    @Autowired private BrandCache brandCache;
    @Autowired private EmployeeCache employeeCache;
    @Autowired private DomainCache domainCache;
    @Autowired private InfluencerTeamCache teamCache;
    @Autowired private InfluencerCache influencerCache;
    @Autowired private DomainSyncService domainSyncService;
    @Autowired private ImportBatchRepository importBatchRepo;

    /**
     * 2026-07 新增默认排序规则：不管用户选的排序字段是什么，"合作中项目"有值的红人排最前，
     * 其次是"已完结项目"有值的，其余按用户选择的排序垫底——这个优先级没法用数据库分页直接
     * 表达（分页游标反映不了"是否有合作中/已完结项目"这种额外计算出来的属性，也不确定
     * "在 ORDER BY 里塞相关子查询"在这套 Hibernate/Postgres 组合下是否被稳定支持）。
     *
     * 2026-07-28 性能重写：早期实现是按筛选条件把命中的红人整批查出"完整实体"
     * （PageRequest.of(0, Integer.MAX_VALUE, sort)），导致每次翻页/筛选都把整张筛选结果集的
     * 完整字段（含 notes/links 这类大字段）都拉一遍，是红人管理列表页载入变慢的根因。
     * 现在改成：先只查 id（轻量投影，findIdsByFilters），用一条合并 SQL
     * （countActiveAndCompletedByInfluencerIds）批量算出这批 id 里谁有合作中/已完结项目，
     * 在内存里按这两级优先级对 id 重排、切出当前页，最后只对"这一页"的 id 才去查完整实体
     * ——数据规模仍然是"一个红人库的 id 列表"，不是完整实体，代价可以接受；真正体积大的数据
     * （完整实体）永远只查当前页这一小撮。
     */
    @GetMapping
    public ApiResponse<Page<Influencer>> list(
            @RequestParam(required = false) ProjectType influencerType,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String countryMarket,
            @RequestParam(required = false) String domain,
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
        List<Long> allIds = influencerRepo.findIdsByFilters(
                influencerType, platform, countryMarket, domain, brandId, teamId,
                followerMin, followerMax, keyword, sort);
        List<Long> sortedIds = reorderIdsByProjectPriority(allIds);

        int total = sortedIds.size();
        int fromIndex = Math.min(page * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<Long> pageIds = sortedIds.subList(fromIndex, toIndex);

        List<Influencer> byId = influencerRepo.findAllById(pageIds);
        Map<Long, Influencer> byIdMap = byId.stream()
                .collect(Collectors.toMap(Influencer::getId, inf -> inf));
        List<Influencer> pageContent = pageIds.stream()
                .map(byIdMap::get).filter(java.util.Objects::nonNull).collect(Collectors.toList());
        attachBrandTeamPairs(pageContent);
        Page<Influencer> result = new org.springframework.data.domain.PageImpl<Influencer>(
                pageContent, PageRequest.of(page, size, sort), total);
        if (!RoleUtil.canViewBaselineFinancials()) {
            return ApiResponse.success(result.map(this::maskSensitive));
        }
        return ApiResponse.success(result);
    }

    /** "合作中项目"有值的排最前，其次"已完结项目"有值的，其余保持原有相对顺序（稳定排序） */
    private List<Long> reorderIdsByProjectPriority(List<Long> ids) {
        if (ids.isEmpty()) return ids;
        Set<Long> activeIds = new HashSet<Long>();
        Set<Long> completedIds = new HashSet<Long>();
        trackingRepo.countActiveAndCompletedByInfluencerIds(ids).forEach(row -> {
            Long influencerId = (Long) row[0];
            if (((Number) row[1]).longValue() > 0) activeIds.add(influencerId);
            if (((Number) row[2]).longValue() > 0) completedIds.add(influencerId);
        });
        return ids.stream()
                .sorted(Comparator.comparingInt(id -> projectPriority(id, activeIds, completedIds)))
                .collect(Collectors.toList());
    }

    /** 排序权重：有合作中项目的红人排最前（0），只有已完结项目的其次（1），都没有的排最后（2） */
    private int projectPriority(Long influencerId, Set<Long> activeIds, Set<Long> completedIds) {
        if (activeIds.contains(influencerId)) return 0;
        if (completedIds.contains(influencerId)) return 1;
        return 2;
    }

    /** 红人精简列表（id/账号名/国家市场/品牌方-团队关联，走 InfluencerCache），供各模块的红人选择下拉框用 */
    @GetMapping("/simple")
    public ApiResponse<List<InfluencerSimpleResponse>> simpleList() {
        return ApiResponse.success(influencerCache.getAll());
    }

    /** 单个红人完整档案详情（不走缓存，字段比 simpleList 全得多），非财务可见角色会脱敏部分字段 */
    @GetMapping("/{id}")
    public ApiResponse<Influencer> getById(@PathVariable Long id) {
        Influencer inf = influencerRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("红人不存在"));
        attachBrandTeamPairs(Collections.singletonList(inf));
        if (!RoleUtil.canViewBaselineFinancials()) return ApiResponse.success(maskSensitive(inf));
        return ApiResponse.success(inf);
    }

    /**
     * 2026-08 修复（Shawn 反馈，红人合作跟踪/红人需求管理导出同一类问题）：之前这里只认
     * influencerType 一个筛选条件，列表页其余的筛选（平台/国家市场/领域/品牌方/团队/粉丝量
     * 区间/关键词）导出时全部被忽略，导致筛选完再导出，导出的还是不受这些筛选影响的数据。
     * 改成跟 list() 一样走 findIdsByFilters，取全部匹配 id 后按同一个排序取出来，只是不做
     * list() 那个"合作中/已完结项目优先"的分页专属重排——导出是拿走全部数据，不需要那层
     * 只服务于"翻页浏览体验"的排序。
     */
    @GetMapping("/export/excel")
    public void exportExcel(@RequestParam(required = false) ProjectType influencerType,
                            @RequestParam(required = false) String platform,
                            @RequestParam(required = false) String countryMarket,
                            @RequestParam(required = false) String domain,
                            @RequestParam(required = false) Long brandId,
                            @RequestParam(required = false) Long teamId,
                            @RequestParam(required = false) Long followerMin,
                            @RequestParam(required = false) Long followerMax,
                            @RequestParam(required = false) String keyword,
                            HttpServletResponse response) throws IOException {
        List<Long> ids = influencerRepo.findIdsByFilters(
                influencerType, platform, countryMarket, domain, brandId, teamId,
                followerMin, followerMax, keyword, Sort.by(Sort.Direction.ASC, "accountName"));
        Map<Long, Influencer> byId = influencerRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(Influencer::getId, inf -> inf));
        List<Influencer> list = ids.stream().map(byId::get)
                .filter(java.util.Objects::nonNull).collect(Collectors.toList());
        attachBrandTeamPairs(list);
        excelHandler.export(list, RoleUtil.canViewBaselineFinancials(), response);
    }

    /** 下载红人 Excel 批量导入模板 */
    @GetMapping("/import/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        excelHandler.downloadTemplate(RoleUtil.canViewBaselineFinancials(), response);
    }

    /** Excel 批量导入红人（同步执行，不像红人合作跟踪那样走异步——红人这边数据量小，不会超时） */
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
     * "合作中项目"（视频项目进度不是"客户已结算"也不是"折损"）+ "已完结项目"（进度="客户已结算"）
     * 各自的数量。2026-07 由原来单一的"合作项目"（原来统计的是"已生成项目订单"的数量，
     * "项目订单"模块废弃后改成直接统计合作跟踪记录数）拆成这两个口径。
     */
    @PostMapping("/project-counts")
    public ApiResponse<Map<Long, ProjectCountResponse>> projectCounts(@RequestBody List<Long> influencerIds) {
        Map<Long, ProjectCountResponse> result = new java.util.LinkedHashMap<Long, ProjectCountResponse>();
        for (Long id : influencerIds) result.put(id, new ProjectCountResponse(0L, 0L));
        trackingRepo.countActiveAndCompletedByInfluencerIds(influencerIds).forEach(row -> {
            ProjectCountResponse r = result.get((Long) row[0]);
            r.setActiveCount(((Number) row[1]).longValue());
            r.setCompletedCount(((Number) row[2]).longValue());
        });
        return ApiResponse.success(result);
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class ProjectCountResponse {
        private Long activeCount;
        private Long completedCount;
    }

    /**
     * 新建/编辑红人（req.getId() 为空即新建）。同时按增量 diff 处理"品牌方-团队"关联（不是
     * 全删再插，见下方注释），保存后刷新 InfluencerCache，"所属领域"有变化时才触发 DomainSyncService
     * 全表扫描（见 domainsBeforeSave/domainsAfterSave 那段判断）。
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    @Transactional
    public ApiResponse<Influencer> save(@Valid @RequestBody InfluencerRequest req) {
        // "品牌方-团队"至少要选一个（2026-08 新增，Shawn 反馈）：没有任何品牌关联的红人在
        // 别的模块（红人合作跟踪新建、红人需求管理等）里选不了品牌方，是个没法正常使用的
        // 半成品数据，新建/编辑都拦住，不能保存。团队本身仍然可以为空（该品牌下没配团队），
        // 只要求至少有一条 brandId 有值的记录。
        boolean hasAtLeastOneBrand = req.getBrandTeamPairs() != null
                && req.getBrandTeamPairs().stream().anyMatch(p -> p.getBrandId() != null);
        if (!hasAtLeastOneBrand) {
            throw new RuntimeException("请至少选择一个\"品牌方-团队\"");
        }

        Influencer inf;
        if (req.getId() != null) {
            inf = influencerRepo.findByIdAndIsDeletedFalse(req.getId())
                    .orElseThrow(() -> new RuntimeException("红人不存在"));
        } else {
            inf = null; // 占位，下面根据是否命中同名的已软删除记录决定"新建"还是"复活"
        }
        // 红人社媒完整名字忽略大小写判重：account_name 在数据库层面有唯一约束（大小写敏感，
        // 之前出现过"JohnDoe"和"johndoe"被当成两个不同红人分别插入的情况，这里手动加一层
        // 忽略大小写的校验），且这个约束不认"软删除"——一个红人被软删除后，这个名字在数据库里
        // 仍然被那一行占用着，之前这里只查未软删除的记录，导致"删除某红人后再用同名重新添加"
        // 会在校验这一步放行、但真正 insert 时直接撞唯一键，报出一个用户看不懂的"数据处理失败"
        // （2026-08 修复）。现在改成不限 isDeleted 查一次：命中已软删除的同名记录时，新建
        // 走"复活"分支——原地复用那一行（连带它名下没被清理掉的品牌方-团队关联/合同等历史
        // 数据一起复活，比插入一条全新的孤儿数据更合理），不再插入新行；命中未删除的记录，
        // 或者是"编辑改名"撞上了别的红人（不管对方是否已软删除），都跟以前一样直接拦下——
        // 编辑场景没法把两条不同的红人身份合并成一条，只能报错让用户换个名字
        Influencer sameNameAny = influencerRepo.findByAccountNameIgnoreCase(req.getAccountName())
                .filter(existing -> req.getId() == null || !existing.getId().equals(req.getId()))
                .orElse(null);
        if (sameNameAny != null) {
            boolean sameNameIsDeleted = Boolean.TRUE.equals(sameNameAny.getIsDeleted());
            if (req.getId() == null && sameNameIsDeleted) {
                inf = sameNameAny;
                inf.setIsDeleted(false);
            } else {
                throw new RuntimeException("红人社媒完整名字 [" + req.getAccountName()
                        + "] 与" + (sameNameIsDeleted ? "已删除的红人" : "现有红人") + "「"
                        + sameNameAny.getAccountName() + "」重复（忽略大小写），不能重复添加"
                        + (sameNameIsDeleted ? "，如需恢复请联系管理员处理该历史记录" : ""));
            }
        }
        if (inf == null) {
            inf = new Influencer();
            inf.setIsDeleted(false);
        }
        // 2026-08 性能修复：domainSyncService.sync() 会把 influencers 整表扫一遍算"哪些领域
        // 还在用"，之前不管这次保存有没有改"所属领域"都无条件跑一遍——红人表越大，"保存"按钮
        // 就越慢，是这个接口卡顿的主因。这条记录的所属领域没变时，sync() 必然是个空操作（没有
        // 领域被新增，也不会有领域因为这条记录而失去最后一个使用者），所以只在真的变了的时候
        // 才跑，下面 setDomains 之后再比较新旧值
        String domainsBeforeSave = inf.getDomains();

        inf.setInfluencerType(req.getInfluencerType());
        inf.setAccountName(req.getAccountName());
        inf.setCountryMarket(req.getCountryMarket());
        inf.setPlatform(req.getPlatform());
        String domainsAfterSave = listToStr(req.getDomains(), "\n");
        inf.setDomains(domainsAfterSave);
        inf.setFollowerCount(req.getFollowerCount());
        inf.setLinks(listToStr(req.getLinks(), "\n"));
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
        //
        // 2026-08-17 性能修复：下面移除/新增/复活这三段各自原来在 for 循环里每处理一条关联就单独
        // 调一次 influencerBrandTeamRepo.save(rel)（旧代码保留在各自循环体内的注释里），改成把
        // 要保存的 rel 收集进同一个 toSave 列表，三段循环结束后（attachBrandTeamPairs 重新查询前）
        // 统一调一次 influencerBrandTeamRepo.saveAll(toSave)。三段各自"什么情况该软删/新插/复活"
        // 的判断逻辑不变，且三段之间不存在"这段要看到上一段落库结果"的依赖（判断条件全部依赖
        // 循环开始前已经算好的 existingRels/existingByKey/newKeys/pairs，互相独立），批量保存不
        // 影响判断结果。
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

        List<InfluencerBrandTeam> toSave = new ArrayList<InfluencerBrandTeam>();
        // 移除：现有有效关联里，不在本次提交列表中的
        for (InfluencerBrandTeam rel : existingRels) {
            if (!Boolean.TRUE.equals(rel.getIsDeleted())
                    && !newKeys.contains(pairKey(rel.getBrandId(), rel.getTeamId()))) {
                rel.setIsDeleted(true);
                toSave.add(rel);
                /* ===== 旧代码：influencerBrandTeamRepo.save(rel); （2026-08-17 停用，改成统一
                 * saveAll，按 Shawn 要求保留对比，不要直接删）===== */
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
                toSave.add(rel);
                /* ===== 旧代码：influencerBrandTeamRepo.save(rel); ===== */
            } else if (Boolean.TRUE.equals(rel.getIsDeleted())) {
                rel.setIsDeleted(false);
                toSave.add(rel);
                /* ===== 旧代码：influencerBrandTeamRepo.save(rel); ===== */
            }
            // else：已经是有效关联，不用动
        }
        if (!toSave.isEmpty()) influencerBrandTeamRepo.saveAll(toSave);

        // 所属领域没变就跳过整表扫描，见上面 domainsBeforeSave 处的说明
        if (!java.util.Objects.equals(domainsBeforeSave, domainsAfterSave)) {
            domainSyncService.sync();
        }
        influencerCache.refresh();
        attachBrandTeamPairs(Collections.singletonList(saved));
        return ApiResponse.success(saved);
    }

    /** 品牌方-团队 对的去重 key，teamId 为空时用 -1 占位（区分"这个品牌下没配团队"这种关联） */
    private String pairKey(Long brandId, Long teamId) {
        return brandId + "|" + (teamId != null ? teamId : -1L);
    }

    /** 软删除红人；名下还有未软删的合作跟踪/需求记录时直接拒绝（见下方拦截校验），刷新 InfluencerCache */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        Influencer inf = influencerRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("红人不存在"));
        // 删除前拦截校验（2026-08 新增）：这个红人名下只要还有未软删的红人合作跟踪/红人需求
        // 记录（不分进度/状态），就不允许删除，避免这些记录的 influencerId 变成悬空引用——
        // 之前这里没有任何校验，删了以后关联记录上的红人名字会因为缓存/查询按
        // isDeleted=false 过滤而"消失"，用户毫无感知
        long trackingCount = trackingRepo.countByInfluencerIdAndIsDeletedFalse(id);
        long requirementCount = requirementRepo.countByInfluencerIdAndIsDeletedFalse(id);
        if (trackingCount > 0 || requirementCount > 0) {
            throw new RuntimeException("该红人名下还有 " + trackingCount + " 条红人合作跟踪记录、"
                    + requirementCount + " 条红人需求记录未删除，无法删除，请先处理这些关联记录");
        }
        inf.setIsDeleted(true);
        influencerRepo.save(inf);
        // 只有这条被删的记录本身带了所属领域时，才可能有领域因此失去最后一个使用者，
        // 才需要跑整表扫描；没有领域字段的记录删除必然是空操作
        if (inf.getDomains() != null && !inf.getDomains().trim().isEmpty()) {
            domainSyncService.sync();
        }
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

    /** 非财务可见角色看红人详情时，把三个成本字段清空再返回（克隆一份，不改原对象） */
    private Influencer maskSensitive(Influencer inf) {
        Influencer copy = new Influencer();
        BeanUtils.copyProperties(inf, copy);
        copy.setInfluencerCost(null);
        copy.setAdSpendCost(null);
        copy.setCopyrightCost(null);
        return copy;
    }

    /** 把字符串列表拼成一个用 sep 分隔的字符串（跳过空/空白项），全部为空则返回 null */
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
