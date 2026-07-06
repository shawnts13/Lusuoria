package com.lusuoria.settlement.service.impl;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.dto.request.CollaborationTrackingRequest;
import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.entity.PendingApproval;
import com.lusuoria.settlement.entity.ProjectOrder;
import com.lusuoria.settlement.enums.PendingApprovalModule;
import com.lusuoria.settlement.enums.VideoType;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import com.lusuoria.settlement.repository.InfluencerBrandTeamRepository;
import com.lusuoria.settlement.repository.InfluencerRepository;
import com.lusuoria.settlement.repository.ProjectOrderRepository;
import com.lusuoria.settlement.config.InfluencerTeamCache;
import com.lusuoria.settlement.entity.InfluencerBrandTeam;
import com.lusuoria.settlement.util.ProjectNoAllocator;
import com.lusuoria.settlement.util.ProfitCalculator;
import com.lusuoria.settlement.util.RoleUtil;
import com.lusuoria.settlement.util.UrlNormalizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

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
        CollaborationTracking tracking;
        String oldOrderId = null;

        if (req.getId() != null) {
            tracking = trackingRepo.findByIdAndIsDeletedFalse(req.getId())
                    .orElseThrow(() -> new RuntimeException("跟踪记录不存在：" + req.getId()));
            oldOrderId = tracking.getClientOrderId();
        } else {
            tracking = new CollaborationTracking();
            tracking.setIsDeleted(false);
        }

        // 红人必须存在于红人库（按 id 关联，不再按名字文本查找——红人改名不受影响）
        Influencer influencer = influencerRepo.findByIdAndIsDeletedFalse(req.getInfluencerId())
                .orElseThrow(() -> new RuntimeException("红人不存在：" + req.getInfluencerId()));

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
            teamOptions = influencerBrandTeamRepo.findByInfluencerIdAndBrandId(influencer.getId(), req.getBrandId());
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
        if (req.getId() == null || allowStatusUpdateOnEdit) {
            tracking.setProgress(req.getProgress());
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
        if (normalizedLink != null) {
            List<CollaborationTracking> dup = trackingRepo.findByOldMaterialSourceLinkNormalized(
                    normalizedLink, req.getId());
            if (!dup.isEmpty()) {
                throw new DuplicateOldMaterialLinkException("旧素材已采买过，请确认是否重复采买");
            }
        }
        tracking.setOldMaterialSourceLink(sourceLink);
        tracking.setOldMaterialSourceLinkNormalized(normalizedLink);

        // 内部项目编号：仅新建时生成一次，前端不可编辑，此后永久不变
        // （月份固定用"创建时间当月"，不随后续填写的发布时间变化）
        if (req.getId() == null) {
            String brandName = tracking.getBrand() != null ? tracking.getBrand().getName() : null;
            String createMonth = new SimpleDateFormat("yyyyMM").format(new Date());
            tracking.setInternalProjectNo(
                    projectNoAllocator.allocate(brandName, createMonth, influencer.getAccountName()));
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
            List<CollaborationTracking> dups = trackingRepo.findDuplicates(
                    influencer.getId(), tracking.getPublishLink(),
                    tracking.getPublishDate(), req.getId());
            if (!dups.isEmpty()) {
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
