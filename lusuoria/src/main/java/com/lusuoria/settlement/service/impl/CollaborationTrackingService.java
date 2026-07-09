package com.lusuoria.settlement.service.impl;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.dto.request.CollaborationTrackingRequest;
import com.lusuoria.settlement.dto.request.CollaborationTrackingStatusRequest;
import com.lusuoria.settlement.dto.response.CollaborationStatusUpdateResult;
import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.entity.PendingApproval;
import com.lusuoria.settlement.entity.ProjectOrder;
import com.lusuoria.settlement.enums.CollaborationProgress;
import com.lusuoria.settlement.enums.InfluencerPaymentProgress;
import com.lusuoria.settlement.enums.PendingApprovalModule;
import com.lusuoria.settlement.enums.VideoType;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import com.lusuoria.settlement.repository.InfluencerBrandTeamRepository;
import com.lusuoria.settlement.repository.InfluencerRepository;
import com.lusuoria.settlement.repository.ProjectOrderRepository;
import com.lusuoria.settlement.config.InfluencerTeamCache;
import com.lusuoria.settlement.entity.InfluencerBrandTeam;
import com.lusuoria.settlement.util.ProjectNoAllocator;
import com.lusuoria.settlement.util.ProjectNoGenerator;
import com.lusuoria.settlement.util.ProfitCalculator;
import com.lusuoria.settlement.util.RoleUtil;
import com.lusuoria.settlement.util.UrlNormalizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
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
 *  3. 订单ID联动项目订单：
 *     - 空 -> 有值：自动新增一条项目订单
 *     - 没变：不重复生成
 *     - 改成另一个值 / 清空：如果这条记录已经有关联的项目订单，直接拒绝这次修改，
 *       提示需要先把那条项目订单删除（走审核流程）才能改。不再有"自动删旧建新"的后门，
 *       因为那样等于绕开了项目订单的删除审核。
 *  4. 删除：项目订单、红人合作跟踪都不再直接删除，而是生成一条"待处理"审核事项，
 *     由 ADMIN 在"待处理"模块里同意后才真正执行删除（见 PendingApprovalService）。
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
    @Autowired private ProjectOrderRepository projectOrderRepo;
    @Autowired private BrandCache brandCache;
    @Autowired private EmployeeCache employeeCache;
    @Autowired private ProjectNoAllocator projectNoAllocator;
    @Autowired private ProjectNoGenerator projectNoGenerator;
    @Autowired private ProfitCalculator profitCalculator;
    @Autowired private ExchangeRateService exchangeRateService;
    @Autowired private PendingApprovalService pendingApprovalService;

    /** 自定义异常：去重命中 */
    public static class DuplicateTrackingException extends RuntimeException {
        public DuplicateTrackingException(String msg) { super(msg); }
    }

    /** 自定义异常：已有关联项目订单，不能直接改/清空订单ID */
    public static class LinkedOrderExistsException extends RuntimeException {
        public LinkedOrderExistsException(String msg) { super(msg); }
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
        String allocateInternalProjectNo(String brandName, String month, String accountName);
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
        public String allocateInternalProjectNo(String brandName, String month, String accountName) {
            return projectNoAllocator.allocate(brandName, month, accountName);
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
        public String allocateInternalProjectNo(String brandName, String month, String accountName) {
            String prefix = generator.buildPrefix(brandName, month, accountName);
            long count = 0;
            for (String s : usedProjectNos) if (s.startsWith(prefix)) count++;
            String candidate;
            do {
                candidate = generator.generate(brandName, month, accountName, count);
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
        String oldOrderId = null;

        if (existingOrNull != null) {
            tracking = existingOrNull;
            oldOrderId = tracking.getClientOrderId();
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
        tracking.setPublishDate(req.getPublishDate());
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
            String createMonth = new SimpleDateFormat("yyyyMM").format(new Date());
            tracking.setInternalProjectNo(
                    ctx.allocateInternalProjectNo(brandName, createMonth, influencer.getAccountName()));
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

        if (RoleUtil.canViewBaselineFinancials()) {
            tracking.setInfluencerCost(req.getInfluencerCost());
            tracking.setClientPrice(req.getClientPrice());
        }

        String newOrderId = emptyToNull(req.getClientOrderId());

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

        // ---- 订单ID联动项目订单 ----
        boolean fromEmptyToValue = (oldOrderId == null && newOrderId != null);
        boolean changedToAnother  = (oldOrderId != null && newOrderId != null && !oldOrderId.equals(newOrderId));
        boolean clearedToEmpty    = (oldOrderId != null && newOrderId == null);

        // 已经有关联的项目订单时，不允许改成另一个订单号、也不允许清空——
        // 必须先把那条项目订单删掉（走删除审核流程）才能再改这个字段，
        // 不再有"自动删旧建新"的后门（那样等于绕开了项目订单的删除审核）
        if ((changedToAnother || clearedToEmpty) && tracking.getGeneratedProjectOrderId() != null) {
            ProjectOrder linkedOrder = projectOrderRepo.findByIdAndIsDeletedFalse(tracking.getGeneratedProjectOrderId())
                    .orElse(null);
            String linkedNo = linkedOrder != null ? linkedOrder.getInternalProjectNo() : "（编号未知）";
            throw new LinkedOrderExistsException(
                    "已经存在内部项目编号为 " + linkedNo + " 的项目订单，请先将其删除后再修改");
        }

        tracking.setClientOrderId(newOrderId);

        CollaborationTracking saved = trackingRepo.save(tracking);

        // 空->有值：为本条记录生成一条独立的项目订单
        if (fromEmptyToValue) {
            Long newOrderPk = createProjectOrderFromTracking(saved, influencer, newOrderId);
            saved.setGeneratedProjectOrderId(newOrderPk);
            saved = trackingRepo.save(saved);
        } else if (saved.getGeneratedProjectOrderId() != null) {
            // 已经关联了项目订单：内部执行人员、发布时间(->项目视频发布时间) 每次保存都同步过去
            syncToLinkedOrder(saved);
        }

        return saved;
    }

    /** 把跟踪记录的品牌方、团队、执行人员、发布时间同步到已关联的项目订单（两边共用同一个内部项目编号） */
    private void syncToLinkedOrder(CollaborationTracking t) {
        projectOrderRepo.findByIdAndIsDeletedFalse(t.getGeneratedProjectOrderId()).ifPresent(order -> {
            order.setBrand(t.getBrand());
            order.setTeam(t.getTeam());
            order.setExecutor(t.getExecutor());
            order.setVideoPublishDate(t.getPublishDate());
            projectOrderRepo.save(order);
        });
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
        // 另一个状态 —— 这种改动需要走管理员审核，不能直接生效
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

        t.setProgress(newProgress);
        t.setInfluencerPaymentProgress(newPayment);
        CollaborationTracking saved = trackingRepo.save(t);
        result.setTracking(saved);
        result.setPendingApproval(false);
        return result;
    }

    /**
     * 根据跟踪记录自动新增一条项目订单，返回新订单的主键 id。
     * 同一订单ID允许多条（每个视频各一条），不再按订单号防重复。
     */
    public Long createProjectOrderFromTracking(CollaborationTracking t, Influencer influencer, String clientOrderId) {
        ProjectOrder order = new ProjectOrder();
        order.setIsDeleted(false);
        order.setClientOrderNo(clientOrderId);          // 跟踪表"客户方的项目订单" -> 项目订单"甲方订单"

        // projectMonth：优先用发布时间所在月份，否则用当前月
        Date base = t.getPublishDate() != null ? t.getPublishDate() : new Date();
        String projectMonth = new SimpleDateFormat("yyyyMM").format(base);
        order.setProjectMonth(projectMonth);

        // projectType：跟随红人类型
        order.setProjectType(influencer.getInfluencerType());

        // 品牌方（项目订单要求 NOT NULL，跟踪记录必须有品牌方才能生成）
        if (t.getBrand() == null) {
            throw new RuntimeException("生成项目订单失败：该跟踪记录未填写品牌方");
        }
        order.setBrand(t.getBrand());
        order.setInfluencer(influencer);
        order.setCooperationContent(t.getDemandContent());
        order.setVideoType(t.getVideoType());

        // 项目负责人 + 提成比例（从员工管理里该员工的默认提成比例带入）
        if (t.getProjectManager() != null) {
            order.setProjectManager(t.getProjectManager());
            order.setCommissionRate(t.getProjectManager().getDefaultCommissionRate());
        }

        // 内部执行人员、红人团队、项目视频发布时间：从跟踪记录带过来，以后跟踪记录改了会自动同步
        order.setExecutor(t.getExecutor());
        order.setTeam(t.getTeam());
        order.setVideoPublishDate(t.getPublishDate());

        // 汇率：按"上个月最后一个工作日"的中国银行/Frankfurter 汇率（与数据看板逻辑一致）
        // 有发布日期：用发布日期所在月份；没有发布日期：用当前日期所在月份
        Date rateBaseDate = t.getPublishDate() != null ? t.getPublishDate() : new Date();
        String rateMonth = new SimpleDateFormat("yyyyMM").format(rateBaseDate);
        order.setExchangeRate(exchangeRateService.getRateForMonth(rateMonth).getUsdToCny());

        // 金额：把跟踪记录的成本/客户价带到项目订单（解析成数字，解析失败则留空）
        order.setClientPrice(parseAmount(t.getClientPrice()));
        order.setInfluencerCost(parseAmount(t.getInfluencerCost()));

        // 项目编号：直接复用跟踪记录已生成的编号（跟踪记录新建时就已经分配好了）
        order.setInternalProjectNo(t.getInternalProjectNo());

        // 计算毛利/可分配利润/提成/公司利润（之前漏调用，导致列表显示"—"，
        // 编辑弹窗才显示正常是因为前端实时算了一遍，但数据库里其实是空的）
        profitCalculator.calculate(order);

        ProjectOrder savedOrder = projectOrderRepo.save(order);
        return savedOrder.getId();
    }

    /** 把金额文本解析成 BigDecimal，非数字（如"价格待定"）返回 null */
    private java.math.BigDecimal parseAmount(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            return new java.math.BigDecimal(s.trim().replaceAll(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }
}
