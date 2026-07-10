package com.lusuoria.settlement.service.impl;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.dto.request.CollaborationTrackingRequest;
import com.lusuoria.settlement.dto.request.CollaborationTrackingStatusRequest;
import com.lusuoria.settlement.dto.response.CollaborationStatusUpdateResult;
import com.lusuoria.settlement.dto.response.ExecutorCostSuggestionResponse;
import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.entity.PendingApproval;
import com.lusuoria.settlement.enums.CollaborationProgress;
import com.lusuoria.settlement.enums.InfluencerPaymentProgress;
import com.lusuoria.settlement.enums.PendingApprovalModule;
import com.lusuoria.settlement.enums.VideoType;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import com.lusuoria.settlement.repository.InfluencerBrandTeamRepository;
import com.lusuoria.settlement.repository.InfluencerRepository;
import com.lusuoria.settlement.config.InfluencerTeamCache;
import com.lusuoria.settlement.entity.InfluencerBrandTeam;
import com.lusuoria.settlement.util.ProjectNoAllocator;
import com.lusuoria.settlement.util.ProjectNoGenerator;
import com.lusuoria.settlement.util.ProfitCalculator;
import com.lusuoria.settlement.util.ProjectFieldVisibility;
import com.lusuoria.settlement.util.RoleUtil;
import com.lusuoria.settlement.util.UrlNormalizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 红人合作跟踪 - 业务逻辑
 *
 * 关键逻辑：
 *  1. 保存时从红人库拷贝 teamName / countryMarket 快照
 *  2. 去重：influencerId + publishLink + publishDate 三者完全相同视为重复（链接和日期均空时不去重）
 *  3. 删除：不直接删除，而是生成一条"待处理"审核事项，
 *     由 ADMIN 在"待处理"模块里同意后才真正执行删除（见 PendingApprovalService）。
 *  4. 2026-07："项目订单"模块整体废弃，原来那边的成本/利润字段（其他外部成本、
 *     内部执行成本、项目毛利、可分配利润、提成、公司利润等）以及对应的权限分级
 *     （ProjectFieldVisibility）、"设置内部执行成本"流程，全部搬到了这个模块里。
 *     "客户方的项目订单"字段不再触发任何自动生成/联动逻辑，纯粹是一个录入字段。
 *
 * 性能说明（2026-07）：
 *  单条保存（表单编辑）跟 Excel 批量导入，校验规则必须完全一致，所以核心逻辑抽成了
 *  doSave()，查重/编号分配这些需要"问数据库"的步骤，通过 LookupContext 这层接口
 *  抽象出去——单条保存用 DbLookupContext（直接查库，简单可靠，反正只有一条不在乎性能），
 *  Excel 批量导入用 BulkLookupContext（数据提前一次性批量查好放内存里，几百行导入时
 *  不会每一行都跟数据库来回好几次，这是之前导入几百行经常"网络连接失败"卡超时的根因）。
 */
@Service
public class CollaborationTrackingService {

    @Autowired private CollaborationTrackingRepository trackingRepo;
    @Autowired private InfluencerRepository influencerRepo;
    @Autowired private InfluencerBrandTeamRepository influencerBrandTeamRepo;
    @Autowired private InfluencerTeamCache teamCache;
    @Autowired private BrandCache brandCache;
    @Autowired private EmployeeCache employeeCache;
    @Autowired private ProjectNoAllocator projectNoAllocator;
    @Autowired private ProjectNoGenerator projectNoGenerator;
    @Autowired private ProfitCalculator profitCalculator;
    @Autowired private PendingApprovalService pendingApprovalService;
    @Autowired private ProjectFieldVisibility fieldVisibility;

    /** 自定义异常：去重命中 */
    public static class DuplicateTrackingException extends RuntimeException {
        public DuplicateTrackingException(String msg) { super(msg); }
    }

    /** 自定义异常：采买旧视频的原链接查重命中 */
    public static class DuplicateOldMaterialLinkException extends RuntimeException {
        public DuplicateOldMaterialLinkException(String msg) { super(msg); }
    }

    /**
     * 保存时需要"问数据库"的几件事，抽象成接口。
     * 单条保存和批量导入的校验规则完全一样，只是这几件事去哪问的方式不同。
     */
    private interface LookupContext {
        /** 某个红人在某个品牌方下的"品牌方-团队"关联记录 */
        List<InfluencerBrandTeam> getTeamOptions(Long influencerId, Long brandId);
        /** 查重命中的那一条（没命中返回 null），excludeId 用于编辑时排除自身 */
        CollaborationTracking findDuplicate(Long influencerId, String publishLink, Date publishDate, Long excludeId);
        /** 这个归一化后的旧素材链接是否已经被别的记录占用（excludeId 排除自身） */
        boolean isOldMaterialLinkTaken(String normalizedLink, Long excludeId);
        /** 分配一个没被占用的内部项目编号 */
        String allocateInternalProjectNo(String brandName, String teamName, String month, String accountName);
    }

    /** 单条保存用：每一步都直接查数据库，简单可靠 */
    private class DbLookupContext implements LookupContext {
        public List<InfluencerBrandTeam> getTeamOptions(Long influencerId, Long brandId) {
            return influencerBrandTeamRepo.findByInfluencerIdAndBrandId(influencerId, brandId);
        }
        public CollaborationTracking findDuplicate(Long influencerId, String publishLink, Date publishDate, Long excludeId) {
            List<CollaborationTracking> dups = trackingRepo.findDuplicates(influencerId, publishLink, publishDate, excludeId);
            return dups.isEmpty() ? null : dups.get(0);
        }
        public boolean isOldMaterialLinkTaken(String normalizedLink, Long excludeId) {
            return !trackingRepo.findByOldMaterialSourceLinkNormalized(normalizedLink, excludeId).isEmpty();
        }
        public String allocateInternalProjectNo(String brandName, String teamName, String month, String accountName) {
            return projectNoAllocator.allocate(brandName, teamName, month, accountName);
        }
    }

    /**
     * Excel 批量导入用：数据提前一次性批量查好放内存里，导入循环里不再逐行查数据库。
     * 由 CollaborationTrackingExcelHandler 在导入开始前统一预加载好，然后每一行调用
     * saveBulk() 时传进来复用；saveBulk() 内部还会往这个上下文的索引里增量更新
     * （比如这一行刚生成的内部项目编号要记进"已用编号"集合，避免同一批文件里后面
     * 的行分配到重复编号）。
     */
    public static class BulkLookupContext implements LookupContext {
        /** influencerId -> brandId -> 该红人在该品牌方下的团队关联记录 */
        public Map<Long, Map<Long, List<InfluencerBrandTeam>>> brandTeamMap = new HashMap<>();
        /** 查重索引：key 见 dedupKey()，value 是已存在的跟踪记录 */
        public Map<String, CollaborationTracking> dedupIndex = new HashMap<>();
        /** 归一化后的旧素材链接 -> 占用这个链接的跟踪记录 id */
        public Map<String, Long> normalizedLinkOwner = new HashMap<>();
        /** 已经被使用的内部项目编号（分配新编号时会持续往里加，避免同批文件内部撞号） */
        public Set<String> usedProjectNos = new HashSet<>();

        private final ProjectNoGenerator generator;
        public BulkLookupContext(ProjectNoGenerator generator) { this.generator = generator; }

        @Override
        public List<InfluencerBrandTeam> getTeamOptions(Long influencerId, Long brandId) {
            return brandTeamMap.getOrDefault(influencerId, Collections.emptyMap())
                    .getOrDefault(brandId, Collections.emptyList());
        }

        @Override
        public CollaborationTracking findDuplicate(Long influencerId, String publishLink, Date publishDate, Long excludeId) {
            CollaborationTracking hit = dedupIndex.get(dedupKey(influencerId, publishLink, publishDate));
            if (hit == null) return null;
            return java.util.Objects.equals(hit.getId(), excludeId) ? null : hit;
        }

        @Override
        public boolean isOldMaterialLinkTaken(String normalizedLink, Long excludeId) {
            Long ownerId = normalizedLinkOwner.get(normalizedLink);
            return ownerId != null && !ownerId.equals(excludeId);
        }

        @Override
        public String allocateInternalProjectNo(String brandName, String teamName, String month, String accountName) {
            String prefix = generator.buildPrefix(brandName, teamName, month, accountName);
            long count = 0;
            for (String s : usedProjectNos) if (s.startsWith(prefix)) count++;
            String candidate;
            do {
                candidate = generator.generate(brandName, teamName, month, accountName, count);
                count++;
            } while (usedProjectNos.contains(candidate));
            usedProjectNos.add(candidate);
            return candidate;
        }

        public static String dedupKey(Long influencerId, String publishLink, Date publishDate) {
            return influencerId + "|" + publishLink + "|" + (publishDate != null ? publishDate.getTime() : "");
        }
    }

    /**
     * @param allowStatusUpdateOnEdit 编辑已有记录时是否允许同时更新"进度"。
     *        默认（单条编辑表单）为 false —— 状态只能通过"状态流转"接口改，
     *        编辑表单里状态字段是锁死的，防止误操作。
     *        Excel 导入命中查重、走"更新已有记录"分支时传 true —— 允许连带把状态也更新了。
     */
    @Transactional
    public CollaborationTracking save(CollaborationTrackingRequest req) {
        return save(req, false);
    }

    @Transactional
    public CollaborationTracking save(CollaborationTrackingRequest req, boolean allowStatusUpdateOnEdit) {
        Influencer influencer = influencerRepo.findByIdAndIsDeletedFalse(req.getInfluencerId())
                .orElseThrow(() -> new RuntimeException("红人不存在：" + req.getInfluencerId()));
        CollaborationTracking existing = req.getId() != null
                ? trackingRepo.findByIdAndIsDeletedFalse(req.getId())
                        .orElseThrow(() -> new RuntimeException("跟踪记录不存在：" + req.getId()))
                : null;
        return doSave(req, allowStatusUpdateOnEdit, influencer, existing, new DbLookupContext());
    }

    /**
     * Excel 批量导入专用：红人、（如果是更新）已有记录、查重/编号分配用的数据，
     * 全部由调用方（CollaborationTrackingExcelHandler）提前批量查好传进来，
     * 这里不再逐行查数据库，只有最后落库这一步是真正的数据库写入。
     */
    @Transactional
    public CollaborationTracking saveBulk(CollaborationTrackingRequest req, Influencer influencer,
                                           CollaborationTracking existingOrNull, BulkLookupContext ctx) {
        CollaborationTracking saved = doSave(req, true, influencer, existingOrNull, ctx);
        // 增量维护批量上下文的索引，供同一批文件里后面的行查重/判断编号占用
        if (saved.getPublishLink() != null && saved.getPublishDate() != null) {
            ctx.dedupIndex.put(
                    BulkLookupContext.dedupKey(saved.getInfluencerId(), saved.getPublishLink(), saved.getPublishDate()),
                    saved);
        }
        if (saved.getOldMaterialSourceLinkNormalized() != null) {
            ctx.normalizedLinkOwner.put(saved.getOldMaterialSourceLinkNormalized(), saved.getId());
        }
        return saved;
    }

    /**
     * 保存的核心逻辑，单条保存和批量导入共用，保证两条路径的校验规则完全一致。
     * 查重/团队校验/编号分配这几步"要问数据库的事"通过 ctx 抽象出去，
     * 不直接在这里查表，具体走数据库还是走内存，由调用方传的 ctx 决定。
     */
    private CollaborationTracking doSave(CollaborationTrackingRequest req, boolean allowStatusUpdateOnEdit,
                                          Influencer influencer, CollaborationTracking existingOrNull,
                                          LookupContext ctx) {
        CollaborationTracking tracking;

        if (existingOrNull != null) {
            tracking = existingOrNull;
        } else {
            tracking = new CollaborationTracking();
            tracking.setIsDeleted(false);
        }

        // 团队 / 国家：拷贝快照（不随红人库变）——countryMarket 仍是红人库的快照，
        // teamName 这个红人库单团队字段已经被"品牌方-团队"关联对取代，不再使用
        tracking.setInfluencer(influencer);
        tracking.setCountryMarket(influencer.getCountryMarket());

        // 品牌方：必须是该红人在红人模块里已关联的"品牌方-团队"对里出现过的品牌方
        // （不管那个品牌方下有没有配团队，只要有关联记录就算数）
        List<InfluencerBrandTeam> teamOptions = null;
        if (req.getBrandId() != null) {
            Brand brand = brandCache.findById(req.getBrandId());
            if (brand == null) throw new RuntimeException("品牌方不存在：" + req.getBrandId());
            teamOptions = ctx.getTeamOptions(influencer.getId(), req.getBrandId());
            if (teamOptions.isEmpty()) {
                throw new RuntimeException("品牌方 [" + brand.getName() + "] 未在红人模块中关联到该红人，"
                        + "请先在红人模块维护后再选择");
            }
            tracking.setBrand(brand);
        } else {
            tracking.setBrand(null);
        }

        // 红人团队：跟着选中的品牌方级联决定，不再是红人库里那个单一的团队字段
        // - 没选品牌方：团队肯定为空
        // - 该品牌方下这个红人没配团队：团队必须为空，前端应该直接禁用团队选择
        // - 该品牌方下只有 1 个团队选项：不管请求里传的是什么，直接采用这唯一的选项
        // - 该品牌方下有多个团队选项（包括"有团队"和"没配团队"这两种都算一个选项）：
        //   请求里必须明确指定其中一个，且必须能对上，对不上就报错
        if (teamOptions == null || teamOptions.isEmpty()) {
            if (req.getTeamId() != null) {
                throw new RuntimeException("请先选择品牌方，或该红人在此品牌方下没有关联任何团队");
            }
            tracking.setTeam(null);
        } else if (teamOptions.size() == 1) {
            Long onlyTeamId = teamOptions.get(0).getTeamId();
            tracking.setTeam(onlyTeamId != null ? teamCache.findById(onlyTeamId) : null);
        } else {
            boolean matched = teamOptions.stream()
                    .anyMatch(t -> java.util.Objects.equals(t.getTeamId(), req.getTeamId()));
            if (!matched) {
                throw new RuntimeException("该红人在品牌方 [" + tracking.getBrand().getName()
                        + "] 下关联了多个团队，请明确选择其中一个团队");
            }
            tracking.setTeam(req.getTeamId() != null ? teamCache.findById(req.getTeamId()) : null);
        }

        tracking.setPlatform(req.getPlatform());
        tracking.setDemandContent(req.getDemandContent());
        tracking.setPublishLink(emptyToNull(req.getPublishLink()));

        // 视频发布时间：Excel 批量导入允许直接填写（是否符合"视频项目进度"前置条件由
        // CollaborationTrackingExcelHandler 逐行校验报错，这里不重复校验）；单条保存
        // （新建/编辑表单提交）只有 ADMIN 能编辑，其他角色提交的值直接忽略、保留数据库原值——
        // 其他角色只能通过"状态流转"在进度进入符合条件的状态时被系统自动填上，见 updateStatus()
        boolean isBulkImport = ctx instanceof BulkLookupContext;
        if (isBulkImport || RoleUtil.canEditPublishDate()) {
            tracking.setPublishDate(req.getPublishDate());
        }
        // 进度：新建时，或明确允许编辑时更新状态（Excel 导入更新分支），才从请求体取值；
        // 普通编辑表单（allowStatusUpdateOnEdit=false）忽略请求体里的 progress，保留数据库原值
        if (existingOrNull == null || allowStatusUpdateOnEdit) {
            tracking.setProgress(req.getProgress());
        }

        // 红人结款进度：跟"进度"字段一样的编辑权限规则——只有新建，或明确允许时（Excel 导入更新分支）
        // 才能改；且只有上面刚设置好的"进度"达到前置条件（已发布(未结算)/已加入客户未结算列表/
        // 客户已结算）时，才允许设置这个字段的值，否则直接拒绝（Excel 导入报错文案见 handler，
        // 这里是单条保存/批量落库共用的最终防线）
        if (existingOrNull == null || allowStatusUpdateOnEdit) {
            InfluencerPaymentProgress newPayment = req.getInfluencerPaymentProgress();
            // 拒绝任何会改动这两个系统值的操作——不管是"改成"这两个值，还是记录当前已经是
            // 这两个值之一、想手动"改离开"（那样会跟红人结款那边的批次记录对不上）。
            // 唯一放行的例外：值压根没变（比如 Excel 重新导入同一批已经纳入结款批次的记录，
            // 或者这次保存根本没碰这个字段），不然已纳入批次的记录会连别的字段都没法再更新了
            if (isSystemManagedChange(tracking.getInfluencerPaymentProgress(), newPayment)) {
                throw new RuntimeException(InfluencerPaymentProgress.SYSTEM_MANAGED_ERROR);
            }
            if (newPayment != null && (tracking.getProgress() == null || !tracking.getProgress().allowsPaymentProgress())) {
                throw new RuntimeException(InfluencerPaymentProgress.PRECONDITION_ERROR);
            }
            tracking.setInfluencerPaymentProgress(newPayment);
        }
        tracking.setVideoType(req.getVideoType());
        tracking.setClientPaymentBatch(req.getClientPaymentBatch());
        tracking.setNotes(req.getNotes());

        // ---- 采买旧视频的原链接：只有"旧素材重发"才允许填，且全表唯一（归一化后比较） ----
        String sourceLink = emptyToNull(req.getOldMaterialSourceLink());
        if (sourceLink != null && req.getVideoType() != VideoType.OLD_MATERIAL_REPOST) {
            throw new RuntimeException("只有\"项目视频类型\"为\"旧素材重发\"时才能填写\"采买旧视频的原链接\"");
        }
        String normalizedLink = UrlNormalizer.normalize(sourceLink);
        if (normalizedLink != null && ctx.isOldMaterialLinkTaken(normalizedLink, existingOrNull != null ? existingOrNull.getId() : null)) {
            throw new DuplicateOldMaterialLinkException("旧素材已采买过，请确认是否重复采买");
        }
        tracking.setOldMaterialSourceLink(sourceLink);
        tracking.setOldMaterialSourceLinkNormalized(normalizedLink);

        // 内部项目编号：仅新建时生成一次，前端不可编辑，此后永久不变
        // （月份固定用"创建时间当月"，不随后续填写的发布时间变化）
        if (existingOrNull == null) {
            String brandName = tracking.getBrand() != null ? tracking.getBrand().getName() : null;
            String teamName = tracking.getTeam() != null ? tracking.getTeam().getName() : null;
            String createMonth = new SimpleDateFormat("yyyyMM").format(new Date());
            tracking.setInternalProjectNo(
                    ctx.allocateInternalProjectNo(brandName, teamName, createMonth, influencer.getAccountName()));
        }

        // 项目负责人
        if (req.getProjectManagerId() != null) {
            Employee manager = employeeCache.findById(req.getProjectManagerId());
            if (manager == null) throw new RuntimeException("项目负责人不存在：" + req.getProjectManagerId());
            tracking.setProjectManager(manager);
        } else {
            tracking.setProjectManager(null);
        }

        // 内部执行人员
        if (req.getExecutorId() != null) {
            Employee executor = employeeCache.findById(req.getExecutorId());
            if (executor == null) throw new RuntimeException("内部执行人员不存在：" + req.getExecutorId());
            tracking.setExecutor(executor);
        } else {
            tracking.setExecutor(null);
        }

        // ===== 2026-07 从"项目订单"模块迁移过来的成本/利润字段，写权限沿用原来的分级规则 =====
        // 当前登录账号的字段可见/可编辑等级（FULL / 项目负责人 / 执行人员 / 基础），下面多处要用
        ProjectFieldVisibility.Context fvCtx = fieldVisibility.resolve();
        boolean isOwnManager = fvCtx.employeeId != null && tracking.getProjectManager() != null
                && fvCtx.employeeId.equals(tracking.getProjectManager().getId());
        boolean isOwnExecutor = fvCtx.employeeId != null && tracking.getExecutor() != null
                && fvCtx.employeeId.equals(tracking.getExecutor().getId());

        // 提成比例：换了新的项目负责人、且请求体没带提成比例时，自动带入该负责人的默认提成比例
        if (req.getProjectManagerId() != null && req.getCommissionRate() == null && fvCtx.isFull()) {
            Employee manager = employeeCache.findById(req.getProjectManagerId());
            if (manager != null && manager.getDefaultCommissionRate() != null) {
                tracking.setCommissionRate(manager.getDefaultCommissionRate());
            }
        }
        if (req.getCommissionRate() != null && fvCtx.isFull()) {
            tracking.setCommissionRate(req.getCommissionRate());
        }

        // 汇率仅 ADMIN 可修改：非 ADMIN 提交的 exchangeRate 会被忽略，保留数据库原值
        if (RoleUtil.canEditExchangeRate()) {
            tracking.setExchangeRate(req.getExchangeRate());
        }

        // 其他外部成本 / 内部执行成本：按角色 + 是否本人负责/执行 决定能不能改
        // （不满足条件时忽略请求体里的值，保留数据库原值，不报错，简单地"改了也不生效"）
        if (fvCtx.isFull() || (fvCtx.tier == ProjectFieldVisibility.Tier.PROJECT_MANAGER && isOwnManager)) {
            tracking.setOtherExternalCost(req.getOtherExternalCost());
        }
        if (fvCtx.isFull()
                || (fvCtx.tier == ProjectFieldVisibility.Tier.PROJECT_MANAGER && isOwnManager)
                || (fvCtx.tier == ProjectFieldVisibility.Tier.EXECUTOR && isOwnExecutor)) {
            tracking.setInternalExecutionCost(req.getInternalExecutionCost());
        }

        if (RoleUtil.canViewBaselineFinancials()) {
            tracking.setInfluencerCost(req.getInfluencerCost());
            tracking.setClientPrice(req.getClientPrice());
        }

        // 毛利/可分配利润/提成金额/公司利润这些自动计算字段，每次保存都重新算一遍，
        // 保证跟上面刚写入的红人成本/客户价/汇率/成本/提成比例这些原始值同步
        profitCalculator.calculate(tracking);

        // ---- 去重判断（仅当链接和日期都非空时）----
        if (tracking.getPublishLink() != null && tracking.getPublishDate() != null) {
            CollaborationTracking dup = ctx.findDuplicate(
                    influencer.getId(), tracking.getPublishLink(), tracking.getPublishDate(),
                    existingOrNull != null ? existingOrNull.getId() : null);
            if (dup != null) {
                throw new DuplicateTrackingException(
                        "已存在相同记录：红人 [" + influencer.getAccountName()
                        + "] 在 " + new SimpleDateFormat("yyyy-MM-dd").format(tracking.getPublishDate())
                        + " 发布的该链接已录入，无法重复添加");
            }
        }

        // "客户方的项目订单"现在就是一个普通的录入字段：随着"项目订单"模块整体废弃，
        // 不再触发任何自动生成/联动逻辑，也不再限制怎么改
        tracking.setClientOrderId(emptyToNull(req.getClientOrderId()));

        CollaborationTracking saved = trackingRepo.save(tracking);
        return saved;
    }

    /**
     * 发起删除申请（不直接删除）。返回创建的待处理事项。
     */
    @Transactional
    public PendingApproval requestDelete(Long id, String reason) {
        CollaborationTracking tracking = trackingRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("跟踪记录不存在：" + id));
        String summary = (tracking.getBrand() != null ? tracking.getBrand().getName() : "未知品牌")
                + " - " + (tracking.getInfluencer() != null ? tracking.getInfluencer().getAccountName() : "未知红人");
        return pendingApprovalService.requestDelete(
                PendingApprovalModule.COLLABORATION_TRACKING, id,
                tracking.getInternalProjectNo(), summary, reason);
    }

    /**
     * "状态流转"：修改"视频项目进度"/"红人结款进度"这两个字段。
     *
     * 正常情况下直接生效。但如果同时满足：
     *   1. 当前记录"红人结款进度"已经有值；
     *   2. 当前"视频项目进度"已经达到前置条件（allowsPaymentProgress()==true）；
     *   3. 这次要把"视频项目进度"改成不满足前置条件的另一个状态（倒退）；
     * 这种改动不允许直接生效，必须提交一条"待审核"事项，由管理员在"待处理"模块同意后
     * 才真正落地——同意时红人结款进度会一并清空（不满足前置条件就不该再有值）。
     */
    @Transactional
    public CollaborationStatusUpdateResult updateStatus(Long id, CollaborationTrackingStatusRequest req) {
        CollaborationTracking t = trackingRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("跟踪记录不存在：" + id));

        CollaborationProgress newProgress = req.getProgress();
        InfluencerPaymentProgress newPayment = req.getInfluencerPaymentProgress();

        // 基本前置条件校验：想设置红人结款进度的值，视频项目进度必须已经达到要求
        if (newPayment != null && (newProgress == null || !newProgress.allowsPaymentProgress())) {
            throw new RuntimeException(InfluencerPaymentProgress.PRECONDITION_ERROR);
        }

        // 倒退检测：红人结款进度当前已有值 + 视频项目进度当前满足条件 + 这次要改成不满足条件的
        // 另一个状态 —— 这种改动需要走管理员审核，不能直接生效。这条路径本身已经是一个受控动作
        // （必须填写理由 + 管理员审核才会真正落地，审核通过时红人结款进度固定清空，不看这次请求
        // 传的 newPayment），所以不受下面"系统状态只能内部设置"限制的约束——那条限制针对的是
        // "直接选中/直接改动"这种没有审核环节的操作
        boolean isRollback = t.getInfluencerPaymentProgress() != null
                && t.getProgress() != null && t.getProgress().allowsPaymentProgress()
                && newProgress != null && !newProgress.allowsPaymentProgress()
                && newProgress != t.getProgress();

        CollaborationStatusUpdateResult result = new CollaborationStatusUpdateResult();

        if (isRollback) {
            if (req.getReason() == null || req.getReason().trim().isEmpty()) {
                throw new RuntimeException("该记录\"红人结款进度\"已有值，视频项目进度要改回不满足前置条件的状态"
                        + "需要管理员审核，请填写原因");
            }
            String summary = (t.getBrand() != null ? t.getBrand().getName() : "未知品牌")
                    + " - " + (t.getInfluencer() != null ? t.getInfluencer().getAccountName() : "未知红人");
            // 倒退到不满足前置条件的状态后，红人结款进度理应清空，不管这次请求里传的是什么，
            // 审核通过时一律按"清空"处理，保持"不满足条件就不该有值"的约束
            pendingApprovalService.requestProgressRollback(
                    t.getId(), t.getInternalProjectNo(), summary, req.getReason(),
                    newProgress, null);
            result.setTracking(t);
            result.setPendingApproval(true);
            return result;
        }

        // "已纳入红人结款批次"这两个状态只能由红人结款模块内部设置，状态流转接口的"直接生效"这条
        // 路径不接受手动改动——既不能改成这两个值，也不能把记录从这两个值手动改离开（那样会跟
        // 红人结款那边的批次记录对不上）。值没变（弹窗没碰这个字段、原样提交回去）不算，放行，
        // 不然已经纳入批次的记录会连"视频项目进度"都没法再通过状态流转调整
        if (isSystemManagedChange(t.getInfluencerPaymentProgress(), newPayment)) {
            throw new RuntimeException(InfluencerPaymentProgress.SYSTEM_MANAGED_ERROR);
        }

        // 视频发布时间自动填写：视频项目进度这次是从"不满足前置条件"变为"满足前置条件"
        // （首次进入已发布(未结算)/已加入客户未结算列表/客户已结算这三个阶段），且当前还没有
        // 视频发布时间时，系统自动填上当天日期（年月日，JVM 默认时区已固定为北京时间）。
        // 只在当前为空时才填，避免已经有值（管理员手动填过，或者之前已经自动填过）的记录
        // 在这三个阶段之间来回流转时被反复覆盖成"今天"，抹掉真实的发布日期。
        boolean enteringPublishedZone = newProgress != null && newProgress.allowsPaymentProgress()
                && (t.getProgress() == null || !t.getProgress().allowsPaymentProgress());
        if (enteringPublishedZone && t.getPublishDate() == null) {
            t.setPublishDate(new Date());
        }

        t.setProgress(newProgress);
        t.setInfluencerPaymentProgress(newPayment);
        CollaborationTracking saved = trackingRepo.save(t);
        result.setTracking(saved);
        result.setPendingApproval(false);

        // 内部执行成本设置流程触发：视频项目进度改成达到前置条件的状态、或者红人结款进度
        // 被设置/修改了值，这两种情况下，如果这条记录有内部执行人员、且还没设置过内部执行成本，
        // 就需要触发一次"设置内部执行成本"，由前端弹窗让用户确认/填写金额
        // （已经设置过的不会再触发，那种情况直接去编辑表单或用手动入口改这个金额字段）
        boolean triggered = (newProgress != null && newProgress.allowsPaymentProgress()) || newPayment != null;
        if (triggered && saved.getExecutorId() != null && saved.getInternalExecutionCost() == null) {
            result.setNeedExecutorCost(true);
        }
        return result;
    }

    /**
     * 内部执行成本弹窗用：根据执行人员的费率档位，算出这笔记录默认应该填多少钱，
     * 以及算出这个金额的依据说明。只读计算，不修改任何数据。
     * 2026-07 从 ProjectOrderServiceImpl 迁移过来，逻辑完全不变，只是计算对象换成了
     * CollaborationTracking，月份口径从"项目视频发布时间"改叫"发布时间"（本来就是同一个字段）。
     *
     * 旧素材重发的分档规则（跟员工管理里维护的费率字段说明完全一致）：
     *   第1-50条一个价，第51-100条一个价，第101条及以上一个价，且第101条以上
     *   这部分的当月累计金额有封顶。"第几条"是按这个执行人员在这个发布月份下，
     *   已经赋值过内部执行成本的旧素材重发记录数量 + 1 来算的——没赋值的记录不算数。
     */
    @Transactional(readOnly = true)
    public ExecutorCostSuggestionResponse suggestExecutorCost(Long id) {
        CollaborationTracking t = trackingRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("跟踪记录不存在：" + id));
        ExecutorCostSuggestionResponse resp = new ExecutorCostSuggestionResponse();

        if (t.getExecutorId() == null) {
            resp.setBreakdown("该记录没有内部执行人员，无需设置内部执行成本");
            return resp;
        }
        Employee executor = employeeCache.findById(t.getExecutorId());
        if (executor == null) {
            resp.setBreakdown("执行人员信息不存在，请手动填写金额");
            return resp;
        }
        if (t.getPublishDate() == null) {
            resp.setBreakdown("该记录尚未填写\"视频发布时间\"，无法按月计算，请手动填写金额");
            return resp;
        }
        if (t.getProjectManagerId() == null) {
            resp.setBreakdown("该记录尚未填写\"项目负责人\"，无法判断计算规则，请手动填写金额");
            return resp;
        }
        VideoType videoType = t.getVideoType();
        if (videoType == null) {
            resp.setBreakdown("该记录尚未填写\"项目视频类型\"，无法计算，请手动填写金额");
            return resp;
        }

        String month = new SimpleDateFormat("yyyyMM").format(t.getPublishDate());
        String monthLabel = Integer.parseInt(month.substring(4)) + "月";

        // 关键业务规则：内部执行成本是不是按员工管理里维护的费率梯度算，取决于这条记录
        // 的项目负责人是不是"管理层"（目前系统里只有一个人是管理层）——
        //   - 是管理层：按费率梯度自动算出建议金额，这部分成本会影响公司利润
        //   - 不是管理层：说明这个执行人员的工资是这个项目负责人自己掏钱付的，
        //     系统不知道他们之间是怎么谈的价格，默认给0让他自己填，只是告诉他
        //     "这个执行人员这个月已经帮你结算过多少笔、分别是什么类型"作为参考，
        //     这部分金额不会影响公司利润（在 ProfitCalculator 里已经处理）
        if (!profitCalculator.isManagementOrder(t)) {
            resp.setSuggestedAmount(java.math.BigDecimal.ZERO);
            resp.setRateBasedSuggestion(false);
            List<CollaborationTracking> costed = trackingRepo.findCostedOrdersForExecutorAndManager(
                    executor.getId(), t.getProjectManagerId(), month);
            Map<VideoType, Long> countByType = new java.util.EnumMap<>(VideoType.class);
            for (CollaborationTracking c : costed) {
                if (c.getVideoType() != null) countByType.merge(c.getVideoType(), 1L, Long::sum);
            }
            StringBuilder sb = new StringBuilder();
            sb.append(monthLabel).append("该执行人员已为你结算：");
            if (countByType.isEmpty()) {
                sb.append("暂无记录");
            } else {
                List<String> parts = new ArrayList<>();
                for (Map.Entry<VideoType, Long> e : countByType.entrySet()) {
                    parts.add(e.getKey().getLabel() + " " + e.getValue() + " 笔");
                }
                sb.append(String.join("、", parts));
            }
            sb.append("，共计 ").append(costed.size()).append(" 笔。");
            sb.append("工资由你自行支付和约定，请手动填写金额。");
            resp.setBreakdown(sb.toString());
            return resp;
        }

        resp.setRateBasedSuggestion(true);
        switch (videoType) {
            case REAL_SHOT_NEW: {
                java.math.BigDecimal rate = executor.getRateRealShotNew();
                resp.setSuggestedAmount(rate);
                resp.setBreakdown(monthLabel + "该执行人员处理实拍新视频：¥" + fmtAmount(rate));
                break;
            }
            case REAL_SHOT_NEW_PHOTO: {
                resp.setSuggestedAmount(null);
                resp.setBreakdown("\"实拍新图片\"这个视频类型目前还没有配置对应的费率，请手动填写金额");
                break;
            }
            case AI_NEW_MATERIAL: {
                java.math.BigDecimal rate = executor.getRateAiNewMaterial();
                resp.setSuggestedAmount(rate);
                resp.setBreakdown(monthLabel + "该执行人员处理AI新素材：¥" + fmtAmount(rate));
                break;
            }
            case OLD_MATERIAL_REPOST: {
                List<CollaborationTracking> costed = trackingRepo
                        .findCostedOldMaterialOrdersForExecutor(executor.getId(), t.getProjectManagerId(), month);
                int countSoFar = costed.size();
                int thisOrderNumber = countSoFar + 1;
                java.math.BigDecimal rate;
                String tierLabel;
                if (thisOrderNumber <= 50) {
                    rate = executor.getRateOldMaterialTier1();
                    tierLabel = "1-50";
                } else if (thisOrderNumber <= 100) {
                    rate = executor.getRateOldMaterialTier2();
                    tierLabel = "51-100";
                } else {
                    rate = executor.getRateOldMaterialTier3();
                    tierLabel = "101及以上";
                    java.math.BigDecimal cap = executor.getOldMaterialMonthlyCap();
                    if (cap != null && rate != null) {
                        // 累计"第101条及以上"这部分已经挣到的钱（在这批已赋值的记录里，
                        // 排在第101个及以后的才算，前100个属于tier1/tier2，不计入这个封顶）
                        java.math.BigDecimal tier3EarnedSoFar = java.math.BigDecimal.ZERO;
                        for (int idx = 100; idx < costed.size(); idx++) {
                            java.math.BigDecimal c = costed.get(idx).getInternalExecutionCost();
                            if (c != null) tier3EarnedSoFar = tier3EarnedSoFar.add(c);
                        }
                        java.math.BigDecimal remaining = cap.subtract(tier3EarnedSoFar);
                        if (remaining.compareTo(java.math.BigDecimal.ZERO) < 0) remaining = java.math.BigDecimal.ZERO;
                        if (rate.compareTo(remaining) > 0) rate = remaining;
                    }
                }
                resp.setSuggestedAmount(rate);
                resp.setBreakdown(monthLabel + "该执行人员已经处理了" + countSoFar + "笔旧素材重发记录，该笔(第"
                        + thisOrderNumber + "笔)旧素材重发(" + tierLabel + ")：¥" + fmtAmount(rate));
                break;
            }
        }
        return resp;
    }

    private String fmtAmount(java.math.BigDecimal v) {
        return v == null ? "0.00" : v.setScale(2, java.math.RoundingMode.HALF_UP).toString();
    }

    /** 保存内部执行成本（跟状态流转分开，是独立的一步操作） */
    @Transactional
    public CollaborationTracking setExecutorCost(Long id, java.math.BigDecimal amount) {
        CollaborationTracking t = trackingRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("跟踪记录不存在：" + id));
        t.setInternalExecutionCost(amount);
        // 内部执行成本变了，项目毛利往下的可分配利润/负责人提成/公司利润这些都要跟着重新算一遍
        profitCalculator.calculate(t);
        return trackingRepo.save(t);
    }

    /**
     * 批量重新计算所有未删除记录的毛利/可分配利润/提成/公司利润这些自动计算字段。
     *
     * 用途：项目毛利这些字段是"保存时计算好存进数据库"的，不是每次读取都现算——正常情况下
     * 每次保存都会重新算一遍，跟红人成本/客户合作价格这些原始值保持同步；但如果有人绕过
     * 系统直接在数据库里改了 influencer_cost/client_price 之类的原始值（没有走保存接口），
     * 存好的毛利等字段就不会跟着更新，直到这条记录下次被保存。这个方法就是用来处理这种
     * "数据库改了原始值、但没走保存流程"之后的手动补算，仅 ADMIN 可调用。
     *
     * @return 实际处理的记录数
     */
    @Transactional
    public int recomputeAllProfits() {
        List<CollaborationTracking> all = trackingRepo.findByIsDeletedFalse();
        for (CollaborationTracking t : all) {
            profitCalculator.calculate(t);
            trackingRepo.save(t);
        }
        return all.size();
    }

    private String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    /**
     * 是否是一次涉及"已纳入红人结款批次"这两个系统值的手动改动——不管是改成这两个值之一，
     * 还是记录当前就是这两个值之一、想手动改离开，都算；值没变（不管是不是系统值）不算。
     * 这两个值只能由 InfluencerPaymentService 内部直接操作实体来设置/清空。
     */
    private boolean isSystemManagedChange(InfluencerPaymentProgress current, InfluencerPaymentProgress next) {
        if (next == current) return false;
        boolean currentManaged = current != null && current.isSystemManagedOnly();
        boolean nextManaged = next != null && next.isSystemManagedOnly();
        return currentManaged || nextManaged;
    }
}
