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
import com.lusuoria.settlement.entity.ExchangeRateCache;
import com.lusuoria.settlement.entity.ExecutorPayRateTier;
import com.lusuoria.settlement.entity.ExecutorWageConfirmation;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.entity.PendingApproval;
import com.lusuoria.settlement.enums.CollaborationProgress;
import com.lusuoria.settlement.enums.InfluencerPaymentProgress;
import com.lusuoria.settlement.enums.PendingApprovalModule;
import com.lusuoria.settlement.enums.VideoType;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import com.lusuoria.settlement.repository.ExchangeRateCacheRepository;
import com.lusuoria.settlement.repository.ExecutorPayRateTierRepository;
import com.lusuoria.settlement.repository.ExecutorWageConfirmationRepository;
import com.lusuoria.settlement.repository.InfluencerBrandTeamRepository;
import com.lusuoria.settlement.repository.InfluencerRepository;
import com.lusuoria.settlement.config.InfluencerTeamCache;
import com.lusuoria.settlement.entity.InfluencerBrandTeam;
import com.lusuoria.settlement.util.ProjectNoAllocator;
import com.lusuoria.settlement.util.ProjectNoGenerator;
import com.lusuoria.settlement.util.MultiValueUtil;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
    @Autowired private InfluencerRequirementService requirementService;
    @Autowired private com.lusuoria.settlement.util.EmployeeRoleUtil employeeRoleUtil;
    @Autowired private ExecutorPayRateTierRepository executorPayRateTierRepo;
    @Autowired private com.lusuoria.settlement.config.ExecutorPayRateTierCache executorPayRateTierCache;
    @Autowired private ExecutorWageConfirmationRepository wageConfirmationRepo;
    @Autowired private ExchangeRateCacheRepository exchangeRateCacheRepo;
    @Autowired private com.lusuoria.settlement.config.ExchangeRateLookupCache rateLookupCache;

    /**
     * 自己的懒加载代理引用（2026-08 新增，见 save()/createBatch() 上"内部项目编号并发冲突重试"
     * 的注释）：重试时必须经过 Spring 事务代理重新调用、开一个全新事务，直接 this.xxx(...) 是
     * 同一个 bean 内部自调用，会绕开代理导致 @Transactional 失效、失败事务也不会被正确清理。
     */
    @Autowired @org.springframework.context.annotation.Lazy private CollaborationTrackingService self;

    /** 内部项目编号并发分配冲突时最多重试几次——两人几乎同时给同一"品牌方/团队/月份"新建
     *  记录才会撞上，重试一两次基本必中，留够余量设成 3 次 */
    private static final int PROJECT_NO_CONFLICT_MAX_RETRY = 3;

    /** "状态流转"到这9个前期制作流程状态（待客户出brief~已发布未结算，即"已加入客户未结算
     * 列表"/"客户已结算"/"折损"以外的全部状态；2026-08-17 新增"待红人下单"后从8个变成9个）时，
     * 同步提供"客户方的项目订单"输入框、可选填写（2026-08 新增，Shawn 要求：拿到订单号就顺手
     * 登记，不用等到"已加入客户未结算列表"那一步才能填）。跟下面 JOINED_CLIENT_UNSETTLED_LIST/
     * SETTLED 那条"按品牌方配置强制要求填写"的规则是两回事——这里永远不强制、永远不会因为
     * 空值报错。 */
    private static final Set<CollaborationProgress> EARLY_STAGE_CLIENT_ORDER_ID_PROGRESSES = java.util.EnumSet.of(
            CollaborationProgress.PENDING_CLIENT_BRIEF, CollaborationProgress.CONTRACT_SENT,
            CollaborationProgress.PENDING_INFLUENCER_ORDER, CollaborationProgress.INFLUENCER_ORDERED,
            CollaborationProgress.SHOOTING_GUIDE_SENT,
            CollaborationProgress.PENDING_DRAFT, CollaborationProgress.PENDING_REVISION,
            CollaborationProgress.PENDING_PUBLISH, CollaborationProgress.PUBLISHED_UNSETTLED);

    /** 这次 DataIntegrityViolationException 是不是 internal_project_no 唯一约束冲突导致的
     *  （ProjectNoAllocator.allocate() 的并发竞态，见其类注释）——只有这种情况才值得自动重试，
     *  别的唯一约束冲突（比如"采买旧视频原链接"查重）重试也没用，应该照样把错误抛给用户 */
    private boolean isProjectNoConflict(org.springframework.dao.DataIntegrityViolationException e) {
        Throwable root = e.getMostSpecificCause();
        String msg = root != null ? root.getMessage() : e.getMessage();
        return msg != null && msg.toLowerCase().contains("internal_project_no");
    }

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
        /** 这个归一化后的旧素材链接如果已经被别的记录占用，返回占用它的那条记录（excludeId
         * 排除自身），没被占用返回 null——2026-08 起返回整条记录而不是单纯的 boolean，
         * 是为了报错文案能带上占用者的内部项目编号，方便核对是不是真的重复采买 */
        CollaborationTracking findOldMaterialLinkOwner(String normalizedLink, Long excludeId);
        /** 分配一个没被占用的内部项目编号 */
        String allocateInternalProjectNo(String brandName, String teamName, String month, String accountName);
    }

    /** 单条保存用：每一步都直接查数据库，简单可靠 */
    private class DbLookupContext implements LookupContext {
        // 下面 4 个方法的语义见 LookupContext 接口上的注释；这里是"单条保存"版实现，直接查数据库
        public List<InfluencerBrandTeam> getTeamOptions(Long influencerId, Long brandId) {
            return influencerBrandTeamRepo.findByInfluencerIdAndBrandId(influencerId, brandId);
        }
        public CollaborationTracking findDuplicate(Long influencerId, String publishLink, Date publishDate, Long excludeId) {
            List<CollaborationTracking> dups = trackingRepo.findDuplicates(influencerId, publishLink, publishDate, excludeId);
            return dups.isEmpty() ? null : dups.get(0);
        }
        public CollaborationTracking findOldMaterialLinkOwner(String normalizedLink, Long excludeId) {
            List<CollaborationTracking> hits = trackingRepo.findByOldMaterialSourceLinkNormalized(normalizedLink, excludeId);
            return hits.isEmpty() ? null : hits.get(0);
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
        /** 内部项目编号 -> 存量记录（2026-08 新增，Excel 导入"精确匹配更新哪一条"专用，
         * 见 CollaborationTrackingExcelHandler 对"内部项目编号"列的处理） */
        public Map<String, CollaborationTracking> projectNoLookup = new HashMap<>();
        /** 内部需求编号 -> 该需求编号下、这批红人名下的存量记录列表（2026-08 新增，"内部需求编号+
         * 红人+合作平台+需求内容+两个金额（+发布时间，如果有）"这条不依赖内部项目编号的匹配路径
         * 专用，见 CollaborationTrackingExcelHandler.matchByComposite() —— 同一需求条目名额>1时
         * 会有多条记录共享完全相同的组合，所以这里存的是列表，具体怎么从候选里挑出唯一一条交给
         * 调用方） */
        public Map<String, List<CollaborationTracking>> requirementNoIndex = new HashMap<>();
        /** 归一化后的旧素材链接 -> 占用这个链接的跟踪记录（2026-08 起存整条记录而不是只存 id，
         * 报重复采买错误时要带上占用者的内部项目编号） */
        public Map<String, CollaborationTracking> normalizedLinkOwner = new HashMap<>();
        /** 已经被使用的内部项目编号（分配新编号时会持续往里加，避免同批文件内部撞号） */
        public Set<String> usedProjectNos = new HashSet<>();

        private final ProjectNoGenerator generator;
        public BulkLookupContext(ProjectNoGenerator generator) { this.generator = generator; }

        // 下面 4 个方法的语义见 LookupContext 接口上的注释；这里是"批量导入"版实现，全部查内存索引
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
        public CollaborationTracking findOldMaterialLinkOwner(String normalizedLink, Long excludeId) {
            CollaborationTracking owner = normalizedLinkOwner.get(normalizedLink);
            return (owner != null && !java.util.Objects.equals(owner.getId(), excludeId)) ? owner : null;
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

        /** 查重索引 dedupIndex 的 key：红人+发布链接+发布时间戳拼接，跟单条保存路径 trackingRepo.findDuplicates 判重口径一致 */
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
    public CollaborationTracking save(CollaborationTrackingRequest req) {
        return save(req, false);
    }

    /**
     * 2026-08 修复：只有"新建"才会现场生成一个新的内部项目编号（ProjectNoAllocator.allocate()，
     * 见其类注释）——那套分配逻辑是"先查一遍当前已用到几号，再挑一个没被占用的"，中间没有加锁，
     * 两个人几乎同时给同一个品牌方/团队/月份新建记录时，理论上会都读到同一个"还没被占用"的候选
     * 编号。数据库对 internal_project_no 有唯一约束兜底，不会真的产生重复编号，但会导致其中
     * 一次保存被数据库拒绝（DataIntegrityViolationException，GlobalExceptionHandler 兜底成一句
     * "数据处理失败，请稍后重试"），用户看不懂为什么好端端保存不了。这里遇到这种冲突就自动重试
     * ——重新走一遍 saveTransactional() 会重新查一次数据库现有编号数量，基本一次就能避开刚才
     * 撞上的那个编号，不需要用户自己再点一次保存。编辑已有记录（req.getId() != null）不会分配
     * 新编号，没有这个冲突场景，直接走一次即可，不进重试循环。
     *
     * 这个方法本身不能是 @Transactional——重试要靠"上一次失败的事务已经完整回滚、下一次是全新
     * 事务重新查库"，如果这层也包在同一个事务里，第一次失败后事务已经被标记 rollback-only，
     * 后面根本没法继续用同一个事务重试。真正的读写逻辑都在 saveTransactional() 里，每次调用
     * 通过 self 代理走一个全新事务。
     */
    public CollaborationTracking save(CollaborationTrackingRequest req, boolean allowStatusUpdateOnEdit) {
        if (req.getId() != null) {
            return self.saveTransactional(req, allowStatusUpdateOnEdit);
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return self.saveTransactional(req, allowStatusUpdateOnEdit);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                if (attempt >= PROJECT_NO_CONFLICT_MAX_RETRY || !isProjectNoConflict(e)) throw e;
            }
        }
    }

    /** save() 真正的读写逻辑：查红人/查已有记录，走 doSave() 落库；必须经 self 代理调用才能让每次重试都拿到全新事务 */
    @Transactional
    public CollaborationTracking saveTransactional(CollaborationTrackingRequest req, boolean allowStatusUpdateOnEdit) {
        Influencer influencer = influencerRepo.findByIdAndIsDeletedFalse(req.getInfluencerId())
                .orElseThrow(() -> new RuntimeException("红人不存在：" + req.getInfluencerId()));
        CollaborationTracking existing = req.getId() != null
                ? trackingRepo.findByIdAndIsDeletedFalse(req.getId())
                        .orElseThrow(() -> new RuntimeException("跟踪记录不存在：" + req.getId()))
                : null;
        // 单条保存走的是当前 HTTP 请求线程，SecurityContext 是完整的，这里直接现场取就是准的
        return doSave(req, allowStatusUpdateOnEdit, influencer, existing, new DbLookupContext(),
                fieldVisibility.resolve().isFull(),
                RoleUtil.isAdmin() || "管理层".equals(employeeRoleUtil.getCurrentEmployeeRole()),
                employeeRoleUtil.getCurrentEmployeeId());
    }

    /**
     * "复制"批量新建专用：一次性提交多条视频项目，整批在一个事务里逐条走 doSave()。
     * 只允许新建（每条 req.id 必须为空）。落库前先用 validateBatchLinkage() 按需求条目分组
     * 预检这一批一共想新建几条、够不够名额，报错文案能精确说明"本次试图新建N条XX类型的
     * 记录，超出了...限制"；之后逐条走 doSave() 时如果还有其他校验失败（查重命中等）
     * 同样整批回滚。
     *
     * 2026-08 修复：同 save() 上方的注释——批量新建每一条都会现场分配一个新的内部项目编号，
     * 同样可能撞上 ProjectNoAllocator 的并发竞态，这里同样自动重试整批（重试时会重新查库，
     * 整批都用得上更新后的"已用编号"状态，比只重试撞车的那一条更简单也更安全）。
     */
    public List<CollaborationTracking> createBatch(List<CollaborationTrackingRequest> reqs) {
        for (int attempt = 1; ; attempt++) {
            try {
                return self.createBatchTransactional(reqs);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                if (attempt >= PROJECT_NO_CONFLICT_MAX_RETRY || !isProjectNoConflict(e)) throw e;
            }
        }
    }

    /** createBatch() 真正的读写逻辑：先校验整批的需求名额（validateBatchLinkage），再逐条 doSave()，同一红人的重复查询走本地 map 去重 */
    @Transactional
    public List<CollaborationTracking> createBatchTransactional(List<CollaborationTrackingRequest> reqs) {
        requirementService.validateBatchLinkage(reqs);
        // 同样是当前 HTTP 请求线程，一次性现场取，不用每条都重新查
        boolean isFullAccess = fieldVisibility.resolve().isFull();
        boolean isAdminOrManagement = RoleUtil.isAdmin() || "管理层".equals(employeeRoleUtil.getCurrentEmployeeRole());
        Long currentEmployeeId = employeeRoleUtil.getCurrentEmployeeId();
        List<CollaborationTracking> results = new ArrayList<>();
        // 2026-08-17 性能修复：原来每个 req 都单独查一次红人（旧代码保留在下面注释里）。"批量新建"
        // 常见用法是同一个红人一次提交好几个视频项目（"复制当前窗口"就是复制上一个 pane 的红人），
        // 也允许每个 pane 选不同红人，所以不能保证整批都是同一个 influencerId，改成按 influencerId
        // 去重缓存：第一次查到的存进 influencerCache，后面遇到相同 id 直接从 map 取，不同 id 该查
        // 还是查，不影响"允许每个视频项目选不同红人"这个能力，只是省掉同一红人的重复查询。
        // doSave() 内部不会修改传进去的 influencer 对象（只读），多个 req 共用同一个 Influencer
        // 实例是安全的。
        Map<Long, Influencer> influencerCache = new HashMap<>();
        for (CollaborationTrackingRequest req : reqs) {
            if (req.getId() != null) {
                throw new RuntimeException("批量新建不支持编辑已有记录");
            }
            Influencer influencer = influencerCache.computeIfAbsent(req.getInfluencerId(), id ->
                    influencerRepo.findByIdAndIsDeletedFalse(id)
                            .orElseThrow(() -> new RuntimeException("红人不存在：" + id)));
            /* ===== 旧代码（2026-08-17 停用，按 Shawn 要求保留对比，不要直接删）=====
            Influencer influencer = influencerRepo.findByIdAndIsDeletedFalse(req.getInfluencerId())
                    .orElseThrow(() -> new RuntimeException("红人不存在：" + req.getInfluencerId()));
            ===== 旧代码结束 ===== */
            results.add(doSave(req, false, influencer, null, new DbLookupContext(), isFullAccess,
                    isAdminOrManagement, currentEmployeeId));
        }
        return results;
    }

    /**
     * Excel 批量导入专用：红人、（如果是更新）已有记录、查重/编号分配用的数据，
     * 全部由调用方（CollaborationTrackingExcelHandler）提前批量查好传进来，
     * 这里不再逐行查数据库，只有最后落库这一步是真正的数据库写入。
     *
     * canSetFinanceSettlementProgress/isAdminOrManagement/currentEmployeeId：调用方（Controller）
     * 必须在派发异步导入任务*之前*、还在真正的 HTTP 请求线程里就把这几个值算好传进来——异步
     * 导入线程池（AsyncConfig.importTaskExecutor）没有配置 SecurityContext 传递，这里如果现场
     * 解析登录态只会拿到空的，会被误判成匿名/最低权限档（2026-08 修复的 bug，见
     * requireFinanceForSettlementProgress() 和 doSave() 里"内部执行人员"那段校验的注释）。
     */
    @Transactional
    public CollaborationTracking saveBulk(CollaborationTrackingRequest req, Influencer influencer,
                                           CollaborationTracking existingOrNull, BulkLookupContext ctx,
                                           boolean canSetFinanceSettlementProgress,
                                           boolean isAdminOrManagement, Long currentEmployeeId) {
        CollaborationTracking saved = doSave(req, true, influencer, existingOrNull, ctx, canSetFinanceSettlementProgress,
                isAdminOrManagement, currentEmployeeId);
        // 增量维护批量上下文的索引，供同一批文件里后面的行查重/判断编号占用
        if (saved.getPublishLink() != null && saved.getPublishDate() != null) {
            ctx.dedupIndex.put(
                    BulkLookupContext.dedupKey(saved.getInfluencerId(), saved.getPublishLink(), saved.getPublishDate()),
                    saved);
        }
        if (saved.getOldMaterialSourceLinkNormalized() != null) {
            ctx.normalizedLinkOwner.put(saved.getOldMaterialSourceLinkNormalized(), saved);
        }
        return saved;
    }

    /**
     * 保存的核心逻辑，单条保存和批量导入共用，保证两条路径的校验规则完全一致。
     * 查重/团队校验/编号分配这几步"要问数据库的事"通过 ctx 抽象出去，
     * 不直接在这里查表，具体走数据库还是走内存，由调用方传的 ctx 决定。
     *
     * canSetFinanceSettlementProgress/isAdminOrManagement/currentEmployeeId：都是调用方现场
     * 算好传进来的"当前操作人是谁/什么权限"，不在这里面重新解析登录态——Excel 批量导入走的是
     * 没有 SecurityContext 的异步线程，这里如果现场取会一直被误判成匿名/最低权限档，
     * 见 saveBulk() 的说明。
     */
    private CollaborationTracking doSave(CollaborationTrackingRequest req, boolean allowStatusUpdateOnEdit,
                                          Influencer influencer, CollaborationTracking existingOrNull,
                                          LookupContext ctx, boolean canSetFinanceSettlementProgress,
                                          boolean isAdminOrManagement, Long currentEmployeeId) {
        CollaborationTracking tracking;

        if (existingOrNull != null) {
            tracking = existingOrNull;
        } else {
            tracking = new CollaborationTracking();
            tracking.setIsDeleted(false);
        }

        // 团队 / 国家：teamName 这个红人库单团队字段已经被"品牌方-团队"关联对取代，不再使用
        tracking.setInfluencer(influencer);

        // 服务国家/市场：2026-07 起红人库改为多选，这里跟红人团队一样的"单个自动填/多个手动选"
        // 规则——选中的红人只维护了 1 个服务国家/市场时，不管请求传了什么，直接采用这唯一的选项；
        // 维护了多个时，请求必须明确指定其中一个合法值，否则拒绝（一条视频/一次合作只涉及
        // 一个国家/市场，不像红人库本身允许维护多个）
        tracking.setCountryMarket(MultiValueUtil.resolveSingleChoice(
                influencer.getCountryMarket(), req.getCountryMarket(), "服务国家/市场"));

        // 品牌方：必须是该红人在红人模块里已关联的"品牌方-团队"对里出现过的品牌方
        // （不管那个品牌方下有没有配团队，只要有关联记录就算数）
        //
        // 2026-08-15 修复：以前 req.getBrandId() 为空时这里只是悄悄 tracking.setBrand(null)
        // 放过，不报错——单条表单虽然有"红人只有1个品牌方选项时自动带入"的前端兜底，但没有把
        // "品牌方"标成必填项；Excel 导入那边"品牌方"列留空时同样什么都不做（这一点原本是照抄
        // "红人团队"留空即合法的处理方式，但品牌方从来没有"留空也合法"这回事，团队才有）。
        // 两边任一个疏漏被撞上，就会出现品牌方/红人团队全是空、内部项目编号里品牌方那一段被
        // 拼成字面量"null"的脏记录（StringBuilder.append(null) 的 Java 行为，ProjectNoGenerator.
        // buildPrefix() 直接 sb.append(brandName) 没有做 null 判断）。这里改成强制非空，
        // 同时覆盖新建和编辑两种场景——编辑/Excel 重新导入时如果"品牌方"列被误清空，以前会把
        // 这条记录本来正确的品牌方悄悄清掉，现在同样会被这里拦下来，不只是新建时的问题。
        if (req.getBrandId() == null) {
            throw new RuntimeException("请选择品牌方");
        }
        Brand brand = brandCache.findById(req.getBrandId());
        if (brand == null) throw new RuntimeException("品牌方不存在：" + req.getBrandId());
        List<InfluencerBrandTeam> teamOptions = ctx.getTeamOptions(influencer.getId(), req.getBrandId());
        if (teamOptions.isEmpty()) {
            throw new RuntimeException("品牌方 [" + brand.getName() + "] 未在红人模块中关联到该红人，"
                    + "请先在红人模块维护后再选择");
        }
        tracking.setBrand(brand);

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

        // 视频发布链接是否有变化（仅单条编辑场景关心，用于下面"编辑时自动流转"的判断；
        // 新建/Excel导入都不算"编辑"，不参与这个判断）
        String oldPublishLink = existingOrNull != null ? existingOrNull.getPublishLink() : null;
        String newPublishLink = emptyToNull(req.getPublishLink());
        tracking.setPublishLink(newPublishLink);

        boolean isBulkImport = ctx instanceof BulkLookupContext;
        boolean isSingleEdit = existingOrNull != null && !isBulkImport;
        boolean publishLinkChanged = isSingleEdit && !java.util.Objects.equals(oldPublishLink, newPublishLink);

        // 视频发布时间：2026-08 起不再限定"仅 ADMIN 能编辑"——这条限制是 2026-07-10 那次
        // 引入"状态流转自动补填发布时间"机制时顺带加上的附带限制，并不是一条独立需求；
        // 现在改成所有角色都能在编辑表单里直接填/改这个字段，跟"视频发布链接"权限对齐。
        tracking.setPublishDate(req.getPublishDate());

        // 2026-07：单条编辑时，如果这次改动涉及"视频发布链接"，说明视频事实上已经发布了——
        // 不管是哪个角色在编辑、也不管"视频项目进度"锁在哪个阶段，都自动：
        //   1. 把"视频发布时间"覆盖成今天（不依赖角色能不能手填这个字段，覆盖掉上面刚设置的值）；
        //   2. 如果当前进度还没到"已发布（未结算）"，直接把进度自动流转过去，不需要再手动走一次
        //      "状态流转"功能；进度已经在这个阶段或更后面（已加入客户未结算列表/客户已结算）的，
        //      不倒退、不覆盖。
        // 如果链接被改成空了，视频发布时间也跟着清空（链接和发布时间强绑定，避免留下一个
        // 跟"未发布"矛盾的发布时间）。
        boolean autoTransitionedToPublished = false;
        // 进度真正发生变化时才刷新"进度最近更新时间"（供进度滞留提醒批次用），跟 updateStatus()
        // 保持一致的口径
        boolean progressChangedInDoSave = false;
        if (publishLinkChanged) {
            if (newPublishLink != null) {
                tracking.setPublishDate(new Date());
                if (tracking.getProgress() == null || !tracking.getProgress().allowsPaymentProgress()) {
                    tracking.setProgress(CollaborationProgress.PUBLISHED_UNSETTLED);
                    tracking.setProgressChangedAt(new Date());
                    progressChangedInDoSave = true;
                    autoTransitionedToPublished = true;
                    // 首次进入"已发布（未结算）"：红人结款进度按品牌方是否需要 invoice 自动判定，
                    // 不需要用户手动选（这个分支只在红人结款进度当前还没有值时触发，直接覆盖安全）
                    tracking.setInfluencerPaymentProgress(resolveInitialPaymentProgress(tracking.getBrand()));
                }
            } else {
                tracking.setPublishDate(null);
            }
        }

        // 进度：新建时，或明确允许编辑时更新状态（Excel 导入更新分支），才从请求体取值；
        // 普通编辑表单（allowStatusUpdateOnEdit=false）忽略请求体里的 progress，保留数据库原值
        // （上面"编辑时自动流转"只发生在单条编辑场景，跟这里的分支互斥，不会冲突）
        if (existingOrNull == null || allowStatusUpdateOnEdit) {
            CollaborationProgress oldProgressBeforeSet = tracking.getProgress();
            requireFinanceForSettlementProgress(tracking.getProgress(), req.getProgress(), canSetFinanceSettlementProgress);
            tracking.setProgress(req.getProgress());
            if (!java.util.Objects.equals(oldProgressBeforeSet, req.getProgress())) {
                tracking.setProgressChangedAt(new Date());
                progressChangedInDoSave = true;
            }
        }

        // 2026-07 新增：新建单条记录时（不含 Excel 批量导入，那边在 CollaborationTrackingExcelHandler
        // 里逐行做自己的校验），"视频发布时间"/"视频项目进度"/"视频发布链接"三者必须保持一致，
        // 不允许出现"进度已经是已发布/已结算但没填发布时间或发布链接"、也不允许"填了发布时间但
        // 进度还停留在早期阶段"这种自相矛盾的组合——跟"状态流转"（updateStatus()）对已有记录
        // 强制的规则口径一致，堵住"新建时直接选一个后期进度、绕开状态流转校验"这个口子。
        boolean isSingleCreate = existingOrNull == null && !isBulkImport;
        if (isSingleCreate) {
            CollaborationProgress newProgress = tracking.getProgress();
            boolean qualifies = newProgress != null && newProgress.allowsPaymentProgress();
            boolean hasPublishDate = tracking.getPublishDate() != null;
            if (qualifies || hasPublishDate) {
                if (newPublishLink == null) {
                    throw new RuntimeException("视频项目进度为\"已发布（未结算）\"及以后阶段、或者已填写视频发布"
                            + "时间时，必须先填写视频发布链接");
                }
                if (!qualifies) {
                    throw new RuntimeException("已经填写了视频发布时间，视频项目进度必须是\"已发布（未结算）\"/"
                            + "\"已加入客户未结算列表\"/\"客户已结算\"其中之一");
                }
                if (!hasPublishDate) {
                    tracking.setPublishDate(new Date());
                }
            }
        }

        // 客户方的项目订单 / 客户方付款批次：品牌方标记"涉及"时（Brand.requiresClientOrderId()/
        // requiresClientPaymentBatch()），进度落在对应阶段就必须已经填了值——2026-08 新增，
        // 跟 updateStatus() 是同一条业务规则，这里覆盖"新建/Excel导入时进度直接就是达标阶段"
        // 这种不经过状态流转的场景。故意嵌在跟"进度"赋值同一个 if 块里（只有新建、或明确允许
        // 编辑时更新状态的 Excel 导入更新分支才会进来）——普通编辑表单不会碰进度，如果这里不
        // 加这层限制，存量的、当初进度已经落在这两个阶段但还没填这两个字段的历史记录，
        // 会因为这条新规则连备注这种不相关字段都改不了，一直卡到有人补上这两个字段为止，
        // 跟"内部执行成本"那条硬性校验只在真正改动时触发是同一个道理。
        if (existingOrNull == null || allowStatusUpdateOnEdit) {
            CollaborationProgress finalProgress = tracking.getProgress();
            boolean requiresOrderIdOnSave = tracking.getBrand() == null || tracking.getBrand().requiresClientOrderId();
            boolean requiresPaymentBatchOnSave = tracking.getBrand() == null || tracking.getBrand().requiresClientPaymentBatch();
            if ((finalProgress == CollaborationProgress.JOINED_CLIENT_UNSETTLED_LIST || finalProgress == CollaborationProgress.SETTLED)
                    && requiresOrderIdOnSave
                    && (req.getClientOrderId() == null || req.getClientOrderId().trim().isEmpty())) {
                throw new RuntimeException("该品牌方涉及\"客户方的项目订单\"，视频项目进度为\""
                        + finalProgress.getLabel() + "\"时必须填写\"客户方的项目订单\"");
            }
            if (finalProgress == CollaborationProgress.SETTLED
                    && requiresPaymentBatchOnSave
                    && (req.getClientPaymentBatch() == null || req.getClientPaymentBatch().trim().isEmpty())) {
                throw new RuntimeException("该品牌方涉及\"客户方付款批次\"，视频项目进度为\"客户已结算\"时必须填写\"客户方付款批次\"");
            }
        }

        // 红人结款进度：2026-08 起完全由系统控制，单条保存/Excel 批量导入都不再接受请求体里的值
        // （前端表单已经把这个下拉框整个去掉，Excel 模板也去掉了这一列——即便有人手填了这一列
        // 重新上传，CollaborationTrackingExcelHandler 也不会再读它）。这里唯一要做的：这次保存后
        // "进度"落在达标阶段（已发布(未结算)/已加入客户未结算列表/客户已结算），但这个字段当前
        // 还没有值时，按品牌方是否需要 invoice 自动补一个初始值——跟 updateStatus() 里"首次进入
        // 已发布(未结算)"用的是同一套 resolveInitialPaymentProgress() 逻辑，覆盖的是"新建/Excel
        // 新增时进度直接就是达标阶段"（跳过前期制作流程直接补录历史数据）这种两个自动触发点
        // （doSave() 里的"编辑时自动流转"、updateStatus() 里的"状态流转"）都覆盖不到的场景。
        // 已经有值的记录（不管是系统批次值还是之前自动赋的值）不会被这里改动或清空。
        if (tracking.getInfluencerPaymentProgress() == null
                && tracking.getProgress() != null && tracking.getProgress().allowsPaymentProgress()) {
            tracking.setInfluencerPaymentProgress(resolveInitialPaymentProgress(tracking.getBrand()));
        }
        // 校验"执行人员+视频类型必须已配置费率梯度"要用到改动前的视频类型，必须在这里
        // （覆盖成新值之前）捕获，用法跟下面 oldProjectManagerId/oldExecutorId 是同一个道理
        VideoType oldVideoType = existingOrNull != null ? existingOrNull.getVideoType() : null;
        tracking.setVideoType(req.getVideoType());
        tracking.setClientPaymentBatch(req.getClientPaymentBatch());
        tracking.setNotes(req.getNotes());

        // 关联的"红人需求管理"内部需求编号：填了值就校验红人/品牌方/团队/项目视频类型/合作平台/
        // 成本/价格是否精确匹配到需求里的某个条目、且该条目还有剩余名额（excludeId 排除自身，
        // 编辑已关联记录时不把自己算进"已占用"）。这里必须传 req 里的成本/价格（即将保存的新值），
        // 不能传 tracking 当前的值——此时 tracking.influencerCost/clientPrice 还没被下面的赋值
        // 更新，仍是编辑前的旧值
        String requirementNo = emptyToNull(req.getInternalRequirementNo());
        if (requirementNo != null) {
            requirementService.validateTrackingLinkage(requirementNo, influencer.getId(),
                    tracking.getBrand() != null ? tracking.getBrand().getId() : null,
                    tracking.getTeam() != null ? tracking.getTeam().getId() : null,
                    tracking.getVideoType(), tracking.getPlatform(),
                    req.getInfluencerCost(), req.getClientPrice(),
                    existingOrNull != null ? existingOrNull.getId() : null);
        }
        tracking.setInternalRequirementNo(requirementNo);

        // ---- 采买旧视频的原链接：只有"旧素材重发"才允许填，且全表唯一（归一化后比较） ----
        String sourceLink = emptyToNull(req.getOldMaterialSourceLink());
        if (sourceLink != null && req.getVideoType() != VideoType.OLD_MATERIAL_REPOST) {
            throw new RuntimeException("只有\"项目视频类型\"为\"旧素材重发\"时才能填写\"采买旧视频的原链接\"");
        }
        String normalizedLink = UrlNormalizer.normalize(sourceLink);
        if (normalizedLink != null) {
            CollaborationTracking linkOwner = ctx.findOldMaterialLinkOwner(
                    normalizedLink, existingOrNull != null ? existingOrNull.getId() : null);
            if (linkOwner != null) {
                throw new DuplicateOldMaterialLinkException(
                        "旧素材已采买过（内部项目编号：" + linkOwner.getInternalProjectNo() + "），请确认是否重复采买");
            }
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

        // 权限校验用：捕获改动前（编辑场景）的项目负责人/执行人员 id，必须在下面两块赋值
        // 逻辑执行之前捕获，避免"同一次请求里先把自己设成项目负责人、再改执行人员"绕开校验
        Long oldProjectManagerId = existingOrNull != null ? existingOrNull.getProjectManagerId() : null;
        Long oldExecutorId = existingOrNull != null ? existingOrNull.getExecutorId() : null;

        // 项目负责人
        if (req.getProjectManagerId() != null) {
            Employee manager = employeeCache.findById(req.getProjectManagerId());
            if (manager == null) throw new RuntimeException("项目负责人不存在：" + req.getProjectManagerId());
            tracking.setProjectManager(manager);
        } else {
            tracking.setProjectManager(null);
        }

        // 内部执行人员：只有编辑且这个字段真的变了时才校验（新建记录不受限，还没有"该记录负责人"
        // 这个概念）——ADMIN、员工角色=管理层、或改动前的项目负责人本人才能改，其余人报错。
        // isAdminOrManagement/currentEmployeeId 由调用方现场传入，不在这里现场调
        // RoleUtil.isAdmin()/employeeRoleUtil.get*()——原因跟 requireFinanceForSettlementProgress()
        // 一样：doSave() 走 Excel 批量导入这条路径时是在没有 SecurityContext 的异步线程里执行的，
        // 现场解析永远拿到空登录态（2026-08 修复，同一次一起改的 bug）
        if (existingOrNull != null && !java.util.Objects.equals(oldExecutorId, req.getExecutorId())) {
            boolean isOwnManager = currentEmployeeId != null && currentEmployeeId.equals(oldProjectManagerId);
            if (!isAdminOrManagement && !isOwnManager) {
                throw new RuntimeException("只有该记录的项目负责人或管理层可以更改内部执行人员");
            }
        }
        if (req.getExecutorId() != null) {
            Employee executor = employeeCache.findById(req.getExecutorId());
            if (executor == null) throw new RuntimeException("内部执行人员不存在：" + req.getExecutorId());
            tracking.setExecutor(executor);
        } else {
            tracking.setExecutor(null);
        }

        // 内部执行人员 + 项目视频类型：这个组合必须已经在"执行人员管理"配置过费率梯度才能保存
        // （2026-08 新增，Shawn 反馈）——内部执行成本现在完全由系统按梯度价自动算，缺配置的
        // 组合系统根本算不出该给多少钱，不能让这种记录先保存下来再说，Excel 导入走的也是这个
        // doSave()，同一条规则自动覆盖，不需要在 Excel handler 那边单独再判断一次。
        // 只在这次保存"新指定/改动"了执行人员或视频类型的那一刻才校验——已经保存过的历史
        // 记录，哪怕这个组合缺配置，只要这次没有改动这两个字段，仍然可以正常编辑其他字段
        // （比如改备注），不会被这条新规则卡住，避免大量存量数据从此以后动弹不得。
        // "折损"记录不参与利润/执行成本核算，豁免这条规则。
        // 用 tracking.getExecutor()/getProjectManager() 取 id 而不是 getExecutorId()/
        // getProjectManagerId()——后两个是 insertable=false/updatable=false 的镜像列，
        // 刚 setExecutor()/setProjectManager() 之后不会立刻反映新值，跟上面 fvCtx 那段
        // isOwnExecutor/isOwnManager 判断用的是同一个道理（见那段注释旁边的写法）。
        //
        // 2026-08 二次调整（Shawn 反馈）：新指定/改动执行人员时，顺带直接把系统按梯度算出来的
        // 内部执行成本存好，不再要求另外打开"内部执行成本"弹窗手动点一次——那个入口因此
        // 从列表页去掉了（弹窗本身还在，只在自动触发/"不涉及执行人员"这两种场景下用得到）。
        Long newProjectManagerId = tracking.getProjectManager() != null ? tracking.getProjectManager().getId() : null;
        Long newExecutorId = tracking.getExecutor() != null ? tracking.getExecutor().getId() : null;
        boolean executorOrVideoTypeChanged = !java.util.Objects.equals(oldExecutorId, newExecutorId)
                || oldVideoType != tracking.getVideoType();
        if (executorOrVideoTypeChanged && newExecutorId != null && tracking.getVideoType() != null
                && tracking.getProgress() != CollaborationProgress.DELAYED) {
            if (!hasExecutorPayRateConfigured(newProjectManagerId, newExecutorId, tracking.getVideoType())) {
                throw new RuntimeException("内部执行人员 [" + tracking.getExecutor().getName()
                        + "] 在项目负责人名下还没有配置\"" + tracking.getVideoType().getLabel()
                        + "\"这个视频类型的薪资费率梯度，无法保存，请先在\"员工管理\"/\"执行人员管理\"配置后再保存这条记录");
            }
            // 新指定/改动执行人员这一步顺带自动算成本，"不涉及执行人员"这个标记本来就跟
            // "指定了一个真实执行人员"互斥，这里改动了执行人员说明用户明确要安排人，
            // 先把这个标记复位，不然会跟下面 fvCtx 那段 isOwnExecutor 判断以外的展示逻辑打架
            tracking.setExecutorCostNotApplicable(false);
            if (!autoComputeExecutorCostOnAssignment(tracking, tracking.getExecutor(),
                    existingOrNull != null ? existingOrNull.getId() : null)) {
                throw new RuntimeException("内部执行人员 [" + tracking.getExecutor().getName()
                        + "] 在项目负责人名下配置的\"" + tracking.getVideoType().getLabel()
                        + "\"费率梯度覆盖不到这个月第几条这个数字，请先去\"员工管理\"/\"执行人员管理\"补充档位配置后再保存这条记录");
            }
        } else if (newExecutorId != null && tracking.getVideoType() != null
                && tracking.getProgress() != CollaborationProgress.DELAYED
                && tracking.getInternalExecutionCost() == null
                && !Boolean.TRUE.equals(tracking.getExecutorCostOverridden())
                && !Boolean.TRUE.equals(tracking.getExecutorCostNotApplicable())) {
            // 2026-08 修复（Shawn 反馈"有些记录本该有内部执行成本却是空的"）：这次保存没有改动
            // 执行人员/视频类型，但这条记录的内部执行成本目前还是空的——典型场景是"先指定执行
            // 人员（当时还没有发布时间，autoComputeExecutorCostOnAssignment() 算不出来，静默
            // 跳过）、后续单独补上发布时间"：单条编辑表单分两步填是这样，Excel 批量导入更常见——
            // 第一次导入先建档（还没发布，没有发布时间/链接），后面等视频真正发布了再导入一次
            // 只更新发布时间/链接，这一行没有重新指定执行人员，"编辑时自动流转到已发布"那段逻辑
            // 弹的"设置内部执行成本"提示也只在单条编辑场景生效（needExecutorCost 是前端弹窗用的
            // 瞬态字段，批量导入没有交互界面可弹），批量导入完全没有别的地方会补这个值，只能靠
            // 管理层/项目负责人/执行人员自己想起来点"批量计算执行成本"才能补上——现在只要这次
            // 保存已经凑齐了发布时间等全部条件，顺手在这里也补一次，不用等用户想起来。
            // 跟上面那个分支的关键区别：这里是"顺手补"，不是用户主动改动执行人员触发的操作，
            // 所以配置缺失/超出梯度覆盖范围时静默跳过（沿用 autoComputeExecutorCostOnAssignment()
            // 的软失败语义），不抛异常打断这次保存——用户可能只是想改个备注，不该被一个他们
            // 完全没意识到在触发的校验挡住。
            autoComputeExecutorCostOnAssignment(tracking, tracking.getExecutor(),
                    existingOrNull != null ? existingOrNull.getId() : null);
        }

        // ===== 2026-07 从"项目订单"模块迁移过来的成本/利润字段，写权限沿用原来的分级规则 =====
        // 当前登录账号的字段可见/可编辑等级（FULL / 项目负责人 / 执行人员 / 基础），下面多处要用
        ProjectFieldVisibility.Context fvCtx = fieldVisibility.resolve();
        boolean isOwnManager = fvCtx.employeeId != null && tracking.getProjectManager() != null
                && fvCtx.employeeId.equals(tracking.getProjectManager().getId());
        boolean isOwnExecutor = fvCtx.employeeId != null && tracking.getExecutor() != null
                && fvCtx.employeeId.equals(tracking.getExecutor().getId());

        // 提成比例：2026-07 起不再受字段可见性档位（fvCtx.isFull()）限制——之前那样写，导致
        // 项目负责人自己新建记录（fvCtx=PROJECT_MANAGER，不是 FULL）、以及异步Excel导入
        // （后台线程丢失登录态，fvCtx 永远降级成最低档）这两种场景，提成比例永远填不进去、
        // 一直是 null，是一个已确认的历史 bug（还有一种情况是"管理层"/"财务"这类 fvCtx=FULL
        // 但前端字段对他们不可见的账号，表单会静默提交没被编辑过的默认值0，被当成"显式传了0"
        // 直接落库，永久盖掉本该自动带入的默认值）。
        //
        // 新规则：
        //   - 只要这条记录有项目负责人，提成比例就必须有值，不允许静默留空/留0——新建、或者
        //     这次保存换了项目负责人时，自动同步这位负责人当前在"员工管理"配置的默认提成比例；
        //     负责人还没配置默认提成比例时直接报错拒绝保存（前端会在提交前做同样的检查提前拦截，
        //     这里是后端兜底，双重保险）。
        //   - 项目负责人没变的情况下（普通编辑其他字段）不touch这个字段，不会覆盖已有值，
        //     也不会尝试"顺便修一下"历史上已经错误的数据——已有的错误数据需要单独排查/修复，
        //     不通过这里的编辑动作顺带静默改掉。
        //   - 显式传入一个跟默认值不同的自定义提成比例，仍然只有 ADMIN 能做（RoleUtil.canEditCommission()，
        //     语义跟前端"提成比例"字段只对 ADMIN 可见可编辑保持一致），不再用范围更宽、
        //     会把"管理层"/"财务" STAFF 也算进去的 fvCtx.isFull()。
        if (tracking.getProjectManager() != null
                && (existingOrNull == null || !java.util.Objects.equals(oldProjectManagerId, req.getProjectManagerId()))) {
            // 新建，或者这次保存换了项目负责人：不管请求里有没有带显式的提成比例，都先确认这位
            // 负责人配置了默认提成比例——没配置就直接拒绝保存，避免碰巧把上一个负责人的提成比例
            // 或者表单里没刷新的旧值当成这条记录的提成比例存下去
            Employee manager = tracking.getProjectManager();
            if (manager.getDefaultCommissionRate() == null) {
                throw new RuntimeException("项目负责人\"" + manager.getName()
                        + "\"还没有在\"员工管理\"配置默认提成比例，请先配置后再保存这条记录");
            }
            java.math.BigDecimal explicit = (req.getCommissionRate() != null && RoleUtil.canEditCommission())
                    ? req.getCommissionRate() : null;
            tracking.setCommissionRate(explicit != null ? explicit : manager.getDefaultCommissionRate());
        } else if (req.getCommissionRate() != null && RoleUtil.canEditCommission()) {
            // 项目负责人没变：只有 ADMIN 手动改提成比例才生效，不做默认值校验/兜底
            tracking.setCommissionRate(req.getCommissionRate());
        }

        // 汇率仅 ADMIN 可修改：非 ADMIN 提交的 exchangeRate 会被忽略，保留数据库原值
        if (RoleUtil.canEditExchangeRate()) {
            tracking.setExchangeRate(req.getExchangeRate());
        }
        // 汇率缺失自动回填（2026-08 修复，Shawn 反馈：很多 Excel 批量导入/非 ADMIN 编辑保存
        // 的记录，视频已发布、发布月份在"汇率维护"里也配置了汇率，但汇率字段一直是0/空）——
        // 根因是上面这条 canEditExchangeRate() 的 ADMIN-only 限制：Excel 导入是异步线程，
        // SecurityContext 是空的，RoleUtil.canEditExchangeRate() 恒为 false；非 ADMIN 的
        // 项目负责人/执行人员通过"编辑"表单保存同样进不了这个分支——两种情况下 exchangeRate
        // 都会一直停留在 null/0，此前这里没有任何兜底，只能靠"重新计算利润"手动按钮才会修好。
        // 这里补上跟 updateStatus()/recomputeAllProfits() 同一套自动回填逻辑：只在汇率缺失/
        // 异常且发布时间所在月份已经配置了汇率时才回填，不影响 ADMIN 已经手动填对的值。
        fillMissingExchangeRateFromCache(tracking);

        // 其他外部成本：按角色 + 是否本人负责/执行 决定能不能改
        // （不满足条件时忽略请求体里的值，保留数据库原值，不报错，简单地"改了也不生效"）
        if (fvCtx.isFull() || (fvCtx.tier == ProjectFieldVisibility.Tier.PROJECT_MANAGER && isOwnManager)) {
            tracking.setOtherExternalCost(req.getOtherExternalCost());
            tracking.setOtherExternalCostNote(req.getOtherExternalCostNote());
        }

        // 内部执行成本：2026-08 起完全由系统按费率梯度自动算（见 setExecutorCost()），编辑表单
        // 这条路径收窄成仅 ADMIN 能手动特批一个跟梯度不一致的值（人情价/历史遗留特殊安排），
        // 不再看 fvCtx/isOwnManager/isOwnExecutor——那套"项目负责人/执行人员自己能改自己相关
        // 记录"的规则是给"手动填多少钱"这件事设计的，现在正常路径根本不该手动填了，继续对
        // 非 ADMIN 开放这个入口会绕开梯度计算。手动特批的记录标记 executorCostOverridden=true，
        // "重新计算利润"批量重算时会跳过、不覆盖这个值，见 recomputeAllProfits()。
        if (req.getInternalExecutionCost() != null && RoleUtil.isAdmin()) {
            tracking.setInternalExecutionCost(req.getInternalExecutionCost());
            tracking.setExecutorCostOverridden(true);
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
        // 编辑时自动流转到"已发布（未结算）"，等价于走了一次状态流转，同样需要考虑要不要
        // 弹出"设置内部执行成本"——除非这条记录已经明确确认过"不涉及内部执行人员"
        if (autoTransitionedToPublished && !Boolean.TRUE.equals(saved.getExecutorCostNotApplicable())) {
            saved.setNeedExecutorCost(true);
        }
        if (progressChangedInDoSave && saved.getInternalRequirementNo() != null) {
            requirementService.refreshCompletedAt(saved.getInternalRequirementNo());
        }
        return saved;
    }

    /**
     * 发起删除申请（不直接删除）。返回创建的待处理事项。
     */
    @Transactional
    public PendingApproval requestDelete(Long id, String reason) {
        CollaborationTracking tracking = trackingRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("跟踪记录不存在：" + id));
        assertOwnerOrAdmin(tracking, "发起删除申请");
        String summary = (tracking.getBrand() != null ? tracking.getBrand().getName() : "未知品牌")
                + " - " + (tracking.getInfluencer() != null ? tracking.getInfluencer().getAccountName() : "未知红人");
        return pendingApprovalService.requestDelete(
                PendingApprovalModule.COLLABORATION_TRACKING, id,
                tracking.getInternalProjectNo(), summary, reason);
    }

    /**
     * "删除"、"视频项目进度倒退"这两个操作收窄成只能由该记录的项目负责人/执行人员发起，
     * ADMIN 不受此限（2026-07 新增）。
     */
    private void assertOwnerOrAdmin(CollaborationTracking tracking, String actionLabel) {
        if (RoleUtil.isAdmin()) return;
        Long currentEmpId = employeeRoleUtil.getCurrentEmployeeId();
        boolean isOwner = currentEmpId != null
                && (currentEmpId.equals(tracking.getProjectManagerId()) || currentEmpId.equals(tracking.getExecutorId()));
        if (!isOwner) {
            throw new RuntimeException("只有该记录的项目负责人或执行人员可以" + actionLabel);
        }
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

        CollaborationProgress oldProgress = t.getProgress();
        CollaborationProgress newProgress = req.getProgress();
        // 红人结款进度不再由"状态流转"请求手动指定（2026-07 起），默认保持数据库现有值不变，
        // 只有下面"首次进入已发布（未结算）"这一处会覆盖成系统自动判定的初始值；
        // "进度倒退需要清空红人结款进度"这个约束由 isRollback 分支（提交审核）和审核通过时的
        // executeProgressRollback() 负责，不需要也不能靠这里校验请求里传的值来维护
        InfluencerPaymentProgress newPayment = t.getInfluencerPaymentProgress();

        // 2026-07 新增：非 ADMIN/STAFF 的账号（@PreAuthorize 现在也放行 AUDITOR 了）想调这个
        // 接口，必须是员工角色="财务"或"管理层"才行——AUDITOR 只是"全字段只读+导出"的通用
        // SysUser 角色，可能被分配给财务，也可能被分配给法务/其他只读岗位，不能只凭
        // "SysUser角色是AUDITOR"就当成财务放行，必须再看关联员工的业务角色，避免法务这类
        // 同样是 AUDITOR 的账号被误放行。财务角色本身也只能在已经进入结算区间的三个阶段
        // （已发布未结算/已加入客户未结算列表/客户已结算）之间流转，不能碰前期制作流程的状态
        // 或"折损"。删除/进度倒退这两个操作走 assertOwnerOrAdmin，财务本来就不是记录的项目
        // 负责人/执行人员，天然会被那边挡掉，这里不用重复处理。
        // 2026-08 起：项目负责人/执行人员/IT后勤如果账号也是 AUDITOR 档（比如只给只读+状态
        // 流转、没有常规写权限），同样放行——跟下面 requireFinanceForSettlementProgress() 放宽
        // 的是同一条业务规则，见 EmployeeRoleUtil.canSetSettlementProgressExtraRole()。
        boolean isAdminOrStaff = "ADMIN".equals(RoleUtil.getCurrentRole()) || "STAFF".equals(RoleUtil.getCurrentRole());
        if (!isAdminOrStaff) {
            String currentEmployeeRole = employeeRoleUtil.getCurrentEmployeeRole();
            boolean isFinanceOrManagement = "财务".equals(currentEmployeeRole) || "管理层".equals(currentEmployeeRole);
            if (!isFinanceOrManagement && !employeeRoleUtil.canSetSettlementProgressExtraRole()) {
                throw new RuntimeException("无权限执行此操作");
            }
            boolean withinSettlementZone = oldProgress != null && oldProgress.allowsPaymentProgress()
                    && newProgress != null && newProgress.allowsPaymentProgress();
            if (!withinSettlementZone) {
                throw new RuntimeException("财务账号只能在\"已发布（未结算）\"、\"已加入客户未结算列表\"、"
                        + "\"客户已结算\"这三个阶段之间流转视频项目进度");
            }
        }

        // "已加入客户未结算列表"/"客户已结算"这两个状态只能由财务/管理层流转进入，
        // 普通员工（项目负责人/执行人员/基础权限）只能流转到"已发布（未结算）"和"折损"这两个终态。
        // 只拦截真正的变动——原样提交回去（值没变）不受影响，跟下面 isSystemManagedChange 的
        // 放行原则保持一致。2026-08 起：项目负责人/执行人员/IT后勤也放行（Shawn 反馈），
        // 见 EmployeeRoleUtil.canSetSettlementProgressExtraRole()
        requireFinanceForSettlementProgress(oldProgress, newProgress,
                fieldVisibility.resolve().isFull() || employeeRoleUtil.canSetSettlementProgressExtraRole());

        // 倒退检测：红人结款进度当前已有值 + 视频项目进度当前满足条件 + 这次要改成不满足条件的
        // 另一个状态 —— 这种改动需要走管理员审核，不能直接生效。这条路径本身已经是一个受控动作
        // （必须填写理由 + 管理员审核才会真正落地，审核通过时红人结款进度固定清空，不看这次请求
        // 传的 newPayment），所以不受下面"系统状态只能内部设置"限制的约束——那条限制针对的是
        // "直接选中/直接改动"这种没有审核环节的操作。
        // "折损"永远不算倒退（2026-07 修复）：折损代表这条记录彻底作废，跟"进度倒退"想防的
        // "误操作/需要复核才能撤回已完成的结算进度"是两回事，不该被同一套审核流程拦住；
        // 改成通过下面的 isEnteringDelayed 分支直接生效，只额外要求填一下备注
        boolean isRollback = newProgress != CollaborationProgress.DELAYED
                && t.getInfluencerPaymentProgress() != null
                && t.getProgress() != null && t.getProgress().allowsPaymentProgress()
                && newProgress != null && !newProgress.allowsPaymentProgress()
                && newProgress != t.getProgress();

        CollaborationStatusUpdateResult result = new CollaborationStatusUpdateResult();

        if (isRollback) {
            assertOwnerOrAdmin(t, "发起进度倒退申请");
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

        // 首次进入"已发布（未结算）"：红人结款进度改成系统按品牌方是否需要 invoice 自动判定的值，
        // 不看前端这次提交了什么——避免用户还要在这个转瞬即逝的时间点手动选一次
        boolean enteringPublishedUnsettled = newProgress == CollaborationProgress.PUBLISHED_UNSETTLED
                && oldProgress != CollaborationProgress.PUBLISHED_UNSETTLED;
        if (enteringPublishedUnsettled) {
            newPayment = resolveInitialPaymentProgress(t.getBrand());
        }

        // "已纳入红人结款批次"这两个状态只能由红人结款模块内部设置，状态流转接口的"直接生效"这条
        // 路径不接受手动改动——既不能改成这两个值，也不能把记录从这两个值手动改离开（那样会跟
        // 红人结款那边的批次记录对不上）。值没变（弹窗没碰这个字段、原样提交回去）不算，放行，
        // 不然已经纳入批次的记录会连"视频项目进度"都没法再通过状态流转调整
        if (isSystemManagedChange(t.getInfluencerPaymentProgress(), newPayment)) {
            throw new RuntimeException(InfluencerPaymentProgress.SYSTEM_MANAGED_ERROR);
        }

        // 2026-07 起：目标进度是"已发布（未结算）"/"已加入客户未结算列表"/"客户已结算"这三个
        // 阶段之一时，必须已经有视频发布时间和视频发布链接——不再像以前那样只在"首次进入"这个
        // 时间点校验、且缺发布时间就静默帮填"今天"。现在只要这次操作真的把进度改成了这三个阶段
        // 之一（不管是不是首次进入，哪怕是在这三个阶段之间来回流转），缺发布时间或者发布链接
        // 任意一个就直接拒绝，提示操作人先通过"编辑"功能把这两个字段填好再回来流转——这两个
        // 字段代表"视频事实上已经发布"，不能是空的，也不该是系统随手垫的"今天"。
        // 只在进度真的发生变化时才校验——原样提交回去（进度没变，比如历史遗留的问题记录被
        // 打开又原样保存）不受这条限制影响，不然这类历史数据会连状态流转弹窗都打不开/保存不了，
        // 需要单独走别的渠道修复，不该被这里的校验卡死。
        // 2026-08 起：前端 CollaborationStatusModal 只在流转到"已发布（未结算）"时展示这两个
        // 输入框，"已加入客户未结算列表"/"客户已结算"不再重复问一遍（避免每次流转都要把前面
        // 阶段的字段再填一次）——但下面这条后端校验本身不放松：这两个字段只要缺，不管目标是三个
        // 阶段里的哪一个都拒绝，作为"没经过已发布（未结算）就被直接跳过去"这种边界情况的兜底
        // （此时前端会检测到记录缺这两个字段，重新把输入框露出来，不会出现"报错但没地方填"的情况）
        boolean progressActuallyChangedForPublishCheck = !java.util.Objects.equals(oldProgress, newProgress);
        if (progressActuallyChangedForPublishCheck && newProgress != null && newProgress.allowsPaymentProgress()) {
            // 2026-08 新增：这次请求如果带了视频发布链接/视频发布时间，直接写入这条记录——
            // 专门为"状态流转到已发布未结算等三个阶段"这个动作开的口子，不受 doSave()（编辑表单）
            // 那边"仅 ADMIN 能改视频发布时间"的角色限制约束，见 CollaborationTrackingStatusRequest
            // 里 publishLink/publishDate 两个字段的说明。财务角色到不了这里（卡在上面
            // withinSettlementZone 那关），所以实际能用这个口子的就是管理层/项目负责人/执行人员
            // 等普通员工账号，符合"这个状态所有非财务角色都能自己操作"的预期。
            String oldPublishLinkForDup = t.getPublishLink();
            Date oldPublishDateForDup = t.getPublishDate();
            String reqPublishLink = req.getPublishLink() != null ? req.getPublishLink().trim() : null;
            if (reqPublishLink != null && !reqPublishLink.isEmpty()) {
                t.setPublishLink(reqPublishLink);
            }
            if (req.getPublishDate() != null) {
                t.setPublishDate(req.getPublishDate());
            }

            boolean missingLink = t.getPublishLink() == null || t.getPublishLink().trim().isEmpty();
            boolean missingDate = t.getPublishDate() == null;
            if (missingLink || missingDate) {
                String missingFields = missingLink && missingDate ? "视频发布时间和视频发布链接"
                        : missingLink ? "视频发布链接" : "视频发布时间";
                throw new RuntimeException("该记录还没有填写" + missingFields + "，无法流转到\""
                        + newProgress.getLabel() + "\"，请先在上方填写后再保存");
            }
            // 这次状态流转真的改动了发布链接/发布时间时，跟 doSave() 同一套去重规则查一遍——
            // 避免通过这个新口子把同一条视频重复录入两次（比如误触了两条不同的跟踪记录，
            // 都填了同一个发布链接+同一天）
            boolean publishInfoChangedForDup = !java.util.Objects.equals(oldPublishLinkForDup, t.getPublishLink())
                    || !java.util.Objects.equals(oldPublishDateForDup, t.getPublishDate());
            if (publishInfoChangedForDup) {
                List<CollaborationTracking> dups = trackingRepo.findDuplicates(
                        t.getInfluencerId(), t.getPublishLink(), t.getPublishDate(), t.getId());
                if (!dups.isEmpty()) {
                    throw new RuntimeException("已存在相同记录：红人在 "
                            + new SimpleDateFormat("yyyy-MM-dd").format(t.getPublishDate())
                            + " 发布的该链接已录入，无法重复添加");
                }
            }
        }

        // 流转到"折损"：同步要求填备注，直接更新到这条记录上（2026-07 新增）。不看是不是真的发生了
        // 变化——已经是"折损"的记录也可以借这个弹窗更新备注内容，所以只要这次提交的目标是"折损"就校验
        if (newProgress == CollaborationProgress.DELAYED) {
            if (req.getNotes() == null || req.getNotes().trim().isEmpty()) {
                throw new RuntimeException("流转到\"折损\"状态需要填写备注");
            }
            t.setNotes(req.getNotes().trim());
        }

        // 客户方的项目订单——前期制作流程这8个状态（待客户出brief~已发布未结算）可选填写
        // （2026-08 新增）：有值就更新，没值就保持原样，永远不校验/不报错，纯粹是"拿到订单号
        // 就顺手登记"，跟下面"已加入客户未结算列表"/"客户已结算"那条强制要求的规则完全独立
        if (EARLY_STAGE_CLIENT_ORDER_ID_PROGRESSES.contains(newProgress)) {
            String earlyStageClientOrderId = req.getClientOrderId() != null ? req.getClientOrderId().trim() : null;
            if (earlyStageClientOrderId != null && !earlyStageClientOrderId.isEmpty()) {
                t.setClientOrderId(earlyStageClientOrderId);
            }
        }

        // 客户方的项目订单：品牌方涉及这个字段时（Brand.requiresClientOrderId()），流转到
        // "已加入客户未结算列表"/"客户已结算"需要同步填写，直接更新到这条记录上（2026-08 新增）。
        // 不看是不是真的发生了变化——已经在这两个状态之一的记录也可以借这个弹窗补填/改这个字段，
        // 所以只要这次提交的目标落在这两个状态就校验
        boolean requiresClientOrderId = t.getBrand() == null || t.getBrand().requiresClientOrderId();
        if ((newProgress == CollaborationProgress.JOINED_CLIENT_UNSETTLED_LIST || newProgress == CollaborationProgress.SETTLED)
                && requiresClientOrderId) {
            if (req.getClientOrderId() == null || req.getClientOrderId().trim().isEmpty()) {
                throw new RuntimeException("该品牌方涉及\"客户方的项目订单\"，流转到\""
                        + newProgress.getLabel() + "\"状态需要填写客户方的项目订单");
            }
            t.setClientOrderId(req.getClientOrderId().trim());
        }

        // 客户方付款批次：品牌方涉及这个字段时（Brand.requiresClientPaymentBatch()），流转到
        // "客户已结算"需要同步填写，直接更新到这条记录上（2026-07 新增；2026-08 起从"仅限
        // 财务角色"改成按品牌方配置判断——能走到这个分支的只有财务/管理层/ADMIN，
        // 跟 requireFinanceForSettlementProgress() 判定的"能不能流转进来"是两条独立的规则，
        // 这里管的是"填没填这个字段"，不重复判断角色）
        boolean requiresClientPaymentBatch = t.getBrand() == null || t.getBrand().requiresClientPaymentBatch();
        if (newProgress == CollaborationProgress.SETTLED && requiresClientPaymentBatch) {
            if (req.getClientPaymentBatch() == null || req.getClientPaymentBatch().trim().isEmpty()) {
                throw new RuntimeException("该品牌方涉及\"客户方付款批次\"，流转到\"客户已结算\"状态需要填写客户方付款批次单号");
            }
            t.setClientPaymentBatch(req.getClientPaymentBatch().trim());
        }

        // 汇率自动补全 + 利润重算（2026-08 修复）：以前 updateStatus() 这条路径全程不会重新算
        // 利润，也不会像 recomputeAllProfits()（"重新计算利润"按钮）那样在汇率缺失时自动按
        // 发布月份从"汇率维护"回填——汇率字段本身仅 ADMIN 能在"编辑"表单里手动填
        // （见上面 doSave() 的 canEditExchangeRate() 判断），导致执行人员/项目负责人这类非
        // ADMIN 账号走状态流转把进度推进到"已发布（未结算）"、顺手在这个弹窗里填了视频发布
        // 时间后，这条记录的汇率仍然是0/空，公司利润（人民币）跟着一起是0，得等有人手动点
        // "重新计算利润"才会修好。现在改成：只要这条记录汇率缺失/非法、且发布时间所在月份
        // 已经在"汇率维护"里配置了汇率，这里就当场自动回填（见 fillMissingExchangeRateFromCache()，
        // doSave()/recomputeAllProfits() 共用同一份逻辑）；并且不管这次操作有没有牵涉发布
        // 信息，每次状态流转都重新跑一遍利润计算，跟 doSave()"每次保存都重新算一遍"保持一致，
        // 不再依赖"重新计算利润"这个手动按钮兜底
        fillMissingExchangeRateFromCache(t);
        profitCalculator.calculate(t);

        // 进度真正变化时才刷新"进度最近更新时间"（供进度滞留提醒批次用），原样提交回去
        // （值没变）不算变化，不刷新
        boolean progressActuallyChanged = !java.util.Objects.equals(oldProgress, newProgress);
        if (progressActuallyChanged) {
            t.setProgressChangedAt(new Date());
        }
        t.setProgress(newProgress);
        t.setInfluencerPaymentProgress(newPayment);
        CollaborationTracking saved = trackingRepo.save(t);
        result.setTracking(saved);
        result.setPendingApproval(false);

        if (progressActuallyChanged && saved.getInternalRequirementNo() != null) {
            requirementService.refreshCompletedAt(saved.getInternalRequirementNo());
        }

        // 内部执行成本设置流程触发（2026-07 调整）：只在这次流转的目标明确是"已发布（未结算）"
        // ——项目负责人视角下唯一需要考虑执行人员成本的终态——且是从别的状态第一次流转进来时
        // 才弹一次；不再看这条记录内部执行成本是不是已经有值，哪怕之前在编辑表单里误填过金额，
        // 到了这个状态也应该重新弹一次确认，只有已经明确点过"不涉及内部执行人员"的记录才不再弹。
        // "已加入客户未结算列表"/"客户已结算"这两个财务专属状态不会触发这个弹窗
        // （财务流转到这两个状态时不涉及执行人员的设置）。
        if (enteringPublishedUnsettled && !Boolean.TRUE.equals(saved.getExecutorCostNotApplicable())) {
            result.setNeedExecutorCost(true);
        }
        return result;
    }

    /**
     * 首次进入"已发布（未结算）"时，红人结款进度按品牌方是否需要 invoice 自动判定：
     * 需要 invoice（null 或 true）→ 待红人发送invoice；显式不需要 → 待结款（不涉及invoice）。
     */
    private InfluencerPaymentProgress resolveInitialPaymentProgress(Brand brand) {
        return (brand == null || brand.requiresInvoiceUpload())
                ? InfluencerPaymentProgress.PENDING_INVOICE
                : InfluencerPaymentProgress.PENDING_SETTLEMENT_NO_INVOICE;
    }

    /**
     * "已加入客户未结算列表"/"客户已结算"这两个状态只能由财务/管理层（ProjectFieldVisibility
     * 判定为 FULL 层级：ADMIN，或关联员工角色是"财务"/"管理层"的 STAFF 账号）流转进入；
     * 2026-08 起放宽：员工角色是"项目负责人"/"执行人员"/"IT后勤"的账号也放行（Shawn 反馈，
     * 见 EmployeeRoleUtil.canSetSettlementProgressExtraRole()）。其余角色（基础权限的 STAFF）
     * 只能流转到"已发布（未结算）"和"折损"这两个终态。只拦截真正把值改成这两个状态之一的
     * 操作，原样提交回去（值没变，比如 Excel 重新导入同一批已经在这两个状态的记录）不受影响。
     *
     * canSetSettlementProgress（原参数名 isFull，2026-08 改名以反映放宽后的实际语义——不再
     * 单纯是"财务字段可见性"）由调用方现场传入，不在这里自己调
     * fieldVisibility.resolve()/employeeRoleUtil——2026-08 修复：之前在这里现场解析，
     * doSave() 走 Excel 批量导入这条路径时是在没有 SecurityContext 的异步线程
     * （AsyncConfig.importTaskExecutor）里执行的，现场解析永远拿到匿名/空登录态，被误判成
     * 最低权限档，导致哪怕是 ADMIN/管理层账号发起的导入，只要有一行目标进度是这两个状态
     * 就会被无条件拒绝（"仅能由财务/管理层设置"）。updateStatus() 走的是正常的 HTTP 请求
     * 线程，调用方现场取值就是准的，不受这个问题影响。
     */
    private void requireFinanceForSettlementProgress(CollaborationProgress oldProgress, CollaborationProgress newProgress,
                                                       boolean canSetSettlementProgress) {
        boolean isSettlementProgress = newProgress == CollaborationProgress.JOINED_CLIENT_UNSETTLED_LIST
                || newProgress == CollaborationProgress.SETTLED;
        if (isSettlementProgress && newProgress != oldProgress && !canSetSettlementProgress) {
            throw new RuntimeException("\"已加入客户未结算列表\"/\"客户已结算\"这两个状态仅能由财务/管理层/"
                    + "项目负责人/执行人员/IT后勤设置");
        }
    }

    /**
     * 内部执行成本弹窗用：根据执行人员的费率档位，算出这笔记录默认应该填多少钱，
     * 以及算出这个金额的依据说明。只读计算，不修改任何数据。
     * 2026-07 从 ProjectOrderServiceImpl 迁移过来，逻辑完全不变，只是计算对象换成了
     * CollaborationTracking，月份口径从"项目视频发布时间"改叫"发布时间"（本来就是同一个字段）。
     *
     * 2026-07 再次调整：四个项目视频类型（实拍新视频/实拍新图片/AI新素材/旧素材重发）统一走
     * 同一套"按当月累计条数分档"的梯度结构（{@link ExecutorPayRateTier}），"每条固定价"只是
     * 恰好只配置了一档、且不封顶。"第几条"是按这个执行人员在这个发布月份下、这个项目负责人
     * 名下、这个具体视频类型，已经赋值过内部执行成本的记录数量 + 1 来算的——没赋值的记录不算数。
     */
    @Transactional(readOnly = true)
    public ExecutorCostSuggestionResponse suggestExecutorCost(Long id, Long executorIdOverride) {
        CollaborationTracking t = trackingRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("跟踪记录不存在：" + id));
        ExecutorCostSuggestionResponse resp = new ExecutorCostSuggestionResponse();

        // 弹窗允许在这条记录还没落库执行人员的情况下，现场选一个人来算建议金额，
        // 所以优先用调用方传入的 executorIdOverride，没传才退回这条记录数据库里已存的值
        Long executorId = executorIdOverride != null ? executorIdOverride : t.getExecutorId();
        if (executorId == null) {
            resp.setBreakdown("该记录没有内部执行人员，无需设置内部执行成本");
            return resp;
        }
        Employee executor = employeeCache.findById(executorId);
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

        List<CollaborationTracking> costedAllTypes = trackingRepo.findCostedOrdersForExecutorAndManager(
                executor.getId(), t.getProjectManagerId(), month).stream()
                // 重新设置已经设置过的记录时，这条记录自己也会落在"已赋值成本"的查询结果里——
                // 必须把它自己排除掉，当作"还没设置过"来算，不然笔数/位次/本月共N笔的统计
                // 会把这一笔算两遍（用户反馈：改过一次后再点，笔数会多算1）
                .filter(c -> !c.getId().equals(t.getId()))
                .collect(Collectors.toList());

        // 关键业务规则（2026-07 改）：内部执行成本是不是按费率梯度算，取决于这条记录的项目
        // 负责人有没有在"执行人员管理"/"员工管理"给这个执行人员的这个具体视频类型配置过梯度——
        //   - 配置过：按梯度自动算出建议金额（是否冲减公司利润仍然只看项目负责人是不是
        //     "管理层"，见 ProfitCalculator.isManagementOrder()，这里不受影响）
        //   - 没配置过：默认给 null 纯手填，红字提示先去配置（同一个执行人员可能配置了
        //     "实拍新视频"但没配置"AI新素材"，所以必须精确到这条记录的具体视频类型判断）
        // 2026-08-17 性能修复：改走 ExecutorPayRateTierCache；旧代码：
        // executorPayRateTierRepo.findByManagerIdAndExecutorIdAndVideoTypeAndIsDeletedFalseOrderByMinCountAsc(
        //         t.getProjectManagerId(), executor.getId(), videoType)
        List<ExecutorPayRateTier> tiers = executorPayRateTierCache.find(t.getProjectManagerId(), executor.getId(), videoType);

        if (tiers.isEmpty()) {
            resp.setSuggestedAmount(null);
            resp.setRateBasedSuggestion(false);
            resp.setNoRateConfigured(true);
            Map<VideoType, Long> countByType = new java.util.EnumMap<>(VideoType.class);
            for (CollaborationTracking c : costedAllTypes) {
                if (c.getVideoType() != null) countByType.merge(c.getVideoType(), 1L, Long::sum);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("该项目负责人尚未在\"执行人员管理\"模块为其设置\"").append(videoType.getLabel())
                    .append("\"的薪资费率梯度，无法保存——请先去\"员工管理\"/\"执行人员管理\"配置好这个视频类型的费率梯度后再回来选择执行人员。");
            // 换行：上面这句是"怎么办"，下面是"当前月的结算情况"，两句性质不同，挤一起容易看花
            sb.append("\n").append(monthLabel).append("该执行人员已为你结算：");
            if (countByType.isEmpty()) {
                sb.append("暂无记录");
            } else {
                List<String> parts = new ArrayList<>();
                for (Map.Entry<VideoType, Long> e : countByType.entrySet()) {
                    parts.add(e.getKey().getLabel() + " " + e.getValue() + " 笔");
                }
                sb.append(String.join("、", parts));
            }
            sb.append("，共计 ").append(costedAllTypes.size()).append(" 笔。");
            resp.setBreakdown(sb.toString());
            return resp;
        }

        List<CollaborationTracking> costedSameType = costedAllTypes.stream()
                .filter(c -> c.getVideoType() == videoType)
                .collect(Collectors.toList());

        // 这条记录本身已经设置过内部执行成本（重新打开弹窗编辑老记录）：展示的应该是"当前实际
        // 保存的金额"，不是重新按梯度推算的建议值——梯度配置事后可能变过，重新推算的数字不一定
        // 等于当初实际保存的，用推算值反而会误导（Shawn 反馈：文案应该说明"目前已设置成"而不是
        // 当成一个新的"建议"来措辞）
        boolean alreadySet = t.getInternalExecutionCost() != null;

        // 位次/笔数计算（2026-08 修复）：上面已经把这条记录自己从 costedSameType 里排除掉了
        // （见 costedAllTypes 那处 filter 的注释），但不能简单"排除自己后的数量 + 1"当成
        // "最新一笔"来算位次——这个假设只在"这条记录恰好是这个月排在最后一个被设置成本的"
        // 时才成立。如果这条记录不是排在最后（比如后来又有别的、排在它之前的记录被设置了
        // 成本），"排除自己数量+1"会把它错误地推到序列末尾，导致"已经处理了111笔...该笔
        // (第112笔)"这种笔数被多算1的错误——不管这条记录实际排第几，永远显示成"最后一笔"
        // （用户反馈：已经设置过执行成本的记录，笔数会多算1）。
        // 正确做法：用它在"同类型已赋值成本名单（含它自己）"里的真实位次，也就是
        // "排在它之前的同类型已赋值记录数 + 1"。
        // "排在它之前"用 publishDate 判断（同一天用 id 兜底），不能再用 id——findCostedOrdersForExecutorAndManager
        // 已经改成按 publishDate 排序了，原因见那条查询的注释：id 只是"创建/导入顺序"，
        // 不是"发布顺序"，批量导入的历史数据尤其容易错位，且用 id 判断"排在它之前"这个位次
        // 本身还是会随着"后来又有别的、id 更小的记录被设置成本"而漂移，只是漂移方向不再永远
        // 把自己推到最后而已，并没有真正稳定下来。发布时间是每条记录自己就带着、不会因为
        // 别的记录变化而改变的客观事实，才是真正稳定的排位依据。
        // 还没设置过成本的记录（走 else 分支）本来就不在 costedSameType 里，"排除自己数量+1"
        // 对这种情况仍然是对的（它会成为下一个被处理的，也就是当前笔数+1）
        int countSoFar;
        int position;
        if (alreadySet) {
            countSoFar = (int) costedSameType.stream().filter(c -> isBeforeInCostOrder(c, t)).count();
            position = countSoFar + 1;
        } else {
            countSoFar = costedSameType.size();
            position = countSoFar + 1;
        }
        ExecutorPayRateTier matched = findMatchingTier(tiers, position);

        if (alreadySet) {
            resp.setRateBasedSuggestion(false);
            resp.setAlreadySet(true);
            resp.setSuggestedAmount(t.getInternalExecutionCost());
            // 文案措辞（2026-08 二次修复）：alreadySet 时这条记录本身就是"已经处理"的一部分，
            // 不是额外新增的一笔——"已经处理了{countSoFar}笔...该笔(第{position}笔)"这种"前面N笔
            // + 这一笔是第N+1笔"的框架，只在"这笔是全新待处理"时才自然（还没处理过，这笔会成为
            // 下一笔）。已经处理过的记录应该用"共处理了{总笔数，含自己}笔，这笔排第{position}位"
            // 来表述，两个数字才不会互相矛盾（比如这个月只有这一笔时，之前是"已经处理了0笔...
            // 该笔第1笔"，笔数和"这是第1笔"对不上，容易让人以为笔数还是算错了；改成"共处理了1笔，
            // 这笔排第1位"就是自洽的）。总笔数 = 排除自己的同类型名单大小 + 1（把自己加回去）
            int totalSameType = costedSameType.size() + 1;
            resp.setBreakdown(monthLabel + "该执行人员共处理了" + totalSameType + "笔" + videoType.getLabel()
                    + "记录，这笔排第" + position + "位：目前已设置成 ¥" + fmtAmount(t.getInternalExecutionCost()));
        } else if (matched == null) {
            // 配置了梯度，但档位没有覆盖到"这个月第几条"这个数字（比如只配了1-50条，这已经是第51条，
            // 又没有配一档"51及以上"兜底）——算不出来，不允许保存
            resp.setRateBasedSuggestion(true);
            resp.setOutOfRange(true);
            resp.setSuggestedAmount(null);
            resp.setBreakdown(monthLabel + "该执行人员已经处理了" + countSoFar + "笔" + videoType.getLabel()
                    + "记录，该笔(第" + position + "笔)超出了当前配置的梯度覆盖范围，无法保存——"
                    + "请先去\"员工管理\"/\"执行人员管理\"补充覆盖这个条数的档位");
        } else {
            resp.setRateBasedSuggestion(true);
            java.math.BigDecimal rate = capAdjustedRate(matched, costedSameType);
            String rangeLabel = tiers.size() > 1 ? "(" + tierRangeLabel(matched) + ")" : "";
            resp.setSuggestedAmount(rate);
            // 命中的档位配置了当月封顶金额、且这个类型当月累计已经花完了这笔预算：rate 会被
            // capAdjustedRate() 截成 0，这不是"算不出来"，是正常业务结果，要跟用户说清楚
            // 为什么是 0，不能让他们以为系统坏了
            boolean cappedAtZero = matched.getMonthlyCap() != null && rate.compareTo(java.math.BigDecimal.ZERO) == 0;
            resp.setCappedAtZero(cappedAtZero);
            String suffix = cappedAtZero
                    ? "：¥0.00（已达到当月封顶金额 ¥" + fmtAmount(matched.getMonthlyCap()) + "，本条不再计费）"
                    : "：¥" + fmtAmount(rate);
            resp.setBreakdown(monthLabel + "该执行人员已经处理了" + countSoFar + "笔" + videoType.getLabel()
                    + "记录，该笔(第" + position + "笔)" + rangeLabel + suffix);
        }

        // 3f：特殊薪酬实时推算——按当前梯度重新推算这个月该负责人+该执行人员所有已确认金额
        // 的记录"应该是多少钱"，跟实际填的值对不上的归入"特殊薪酬"，纯查询时计算，不落库
        String specialPayNote = computeSpecialPayNote(costedAllTypes, t.getProjectManagerId(), executor.getId());
        if (specialPayNote != null) {
            // 换行而不是拼一个空格：这两句是不同性质的信息（本次建议金额 vs 本月历史特殊薪酬提示），
            // 挤在同一段容易看花，前端这个字段是按 white-space:pre-line 展示的，\n 会真正换行
            resp.setBreakdown(resp.getBreakdown() + "\n" + specialPayNote);
        }
        return resp;
    }

    /**
     * doSave() 专用（2026-08 新增）：编辑表单/Excel 新指定或改动了执行人员时，顺带按费率梯度
     * 自动算出这一笔的内部执行成本，直接设到 tracking 上（不落库，doSave() 后面统一
     * trackingRepo.save()）。跟 suggestExecutorCost() 走的是同一套"第几条"排位、封顶逻辑，
     * 但这里操作的是内存里还没落库的 tracking 本身（新建场景甚至还没有 id），不能像
     * suggestExecutorCost() 那样通过 id 重新查一遍，所以单独写一份、直接用查询结果现算。
     * 调用方必须先确认 tiers 非空（hasExecutorPayRateConfigured）——这里只处理"发布时间
     * 是否已填"和"位次是否在配置的档位覆盖范围内"。
     *
     * @param excludeId 这条记录本身如果已经落库过（编辑场景），传它的 id，从"已赋值成本的
     *                  同类记录"名单里排除自己；新建场景传 null
     * @return false 表示配置了梯度、但档位覆盖不到这个月第几条这个数字，调用方应该报错拒绝
     *         保存；发布时间还没填时无法计算"第几条"，直接跳过不设置，仍返回 true（这不是
     *         "算不出来"的错误，只是现在还不到能算的时候，等填了发布时间后这个方法会在
     *         下一次保存时重新触发——只要那次保存里 executorId/videoType 也跟着变了；如果
     *         只是单独补发布时间、executor/videoType 都没变，"编辑时自动流转到已发布"那段
     *         逻辑会把 progress 带到 PUBLISHED_UNSETTLED，走 updateStatus()/自动弹窗兜底
     */
    private boolean autoComputeExecutorCostOnAssignment(CollaborationTracking tracking, Employee executor, Long excludeId) {
        if (tracking.getPublishDate() == null) return true;
        // 2026-08-17 性能修复：改走 ExecutorPayRateTierCache；旧代码：
        // executorPayRateTierRepo.findByManagerIdAndExecutorIdAndVideoTypeAndIsDeletedFalseOrderByMinCountAsc(
        //         tracking.getProjectManager().getId(), executor.getId(), tracking.getVideoType())
        List<ExecutorPayRateTier> tiers = executorPayRateTierCache.find(
                tracking.getProjectManager().getId(), executor.getId(), tracking.getVideoType());
        if (tiers.isEmpty()) return true; // 调用方已经校验过，这里只是防御
        String month = new SimpleDateFormat("yyyyMM").format(tracking.getPublishDate());
        List<CollaborationTracking> costedAllTypes = trackingRepo.findCostedOrdersForExecutorAndManager(
                        executor.getId(), tracking.getProjectManager().getId(), month).stream()
                .filter(c -> excludeId == null || !c.getId().equals(excludeId))
                .collect(Collectors.toList());
        List<CollaborationTracking> costedSameType = costedAllTypes.stream()
                .filter(c -> c.getVideoType() == tracking.getVideoType())
                .collect(Collectors.toList());
        int position = costedSameType.size() + 1;
        ExecutorPayRateTier matched = findMatchingTier(tiers, position);
        if (matched == null) return false;
        tracking.setInternalExecutionCost(capAdjustedRate(matched, costedSameType));
        tracking.setExecutorCostOverridden(false);
        tracking.setExecutorCostEverSet(true);
        return true;
    }

    /**
     * "第几条"排位用：a 是否排在 b 之前——按 publishDate 比较，同一天（或极少数缺失发布时间
     * 的脏数据兜底场景）用 id 比较，保证结果确定。必须跟 findCostedOrdersForExecutorAndManager
     * 查询用的排序规则完全一致，否则这里现算出来的位次会跟那条查询返回的列表顺序对不上。
     */
    private boolean isBeforeInCostOrder(CollaborationTracking a, CollaborationTracking b) {
        Date da = a.getPublishDate();
        Date db = b.getPublishDate();
        if (da != null && db != null && !da.equals(db)) {
            return da.before(db);
        }
        if (da == null && db != null) return true;   // 缺失发布时间的排最前，纯兜底，正常业务流程走不到
        if (da != null && db == null) return false;
        return a.getId() < b.getId();
    }

    /** 按"这个月第几条"找到覆盖这个数字的档位，tiers 必须已经按 minCount 升序排好 */
    private ExecutorPayRateTier findMatchingTier(List<ExecutorPayRateTier> tiers, int position) {
        for (ExecutorPayRateTier tier : tiers) {
            if (position >= tier.getMinCount() && (tier.getMaxCount() == null || position <= tier.getMaxCount())) {
                return tier;
            }
        }
        return null;
    }

    /** 档位范围展示文案，如 "1-50"、"101及以上"、单条档位就是数字本身 */
    private String tierRangeLabel(ExecutorPayRateTier tier) {
        if (tier.getMaxCount() == null) return tier.getMinCount() + "及以上";
        if (tier.getMinCount().equals(tier.getMaxCount())) return String.valueOf(tier.getMinCount());
        return tier.getMinCount() + "-" + tier.getMaxCount();
    }

    /**
     * 命中档位后的实际单价：只有"不封顶数量"的那一档（maxCount 为空）才可能配置了当月封顶金额。
     *
     * 2026-08 修复（Shawn 反馈）：封顶金额指的是"这个视频类型"当月的累计封顶，不是"这一档"
     * 单独的累计封顶——一个视频类型不管拆了几档、这一笔落在哪一档，只要这个类型当月累计挣到的
     * 钱到了封顶金额，后面属于这一档（能设置封顶金额的只有最后这一档）的记录就不再计费。
     * 之前的写法只统计 sameTypeBefore 里"位次落在这一档范围内"的记录，如果同一个类型配置了
     * 多档（比如 1-50 一个价、51 及以上另一个价+封顶），前面那些档位的收入不会计入封顶累计，
     * 封顶金额实际上只在管这一档自己的收入，跟"这个类型当月封顶"的本意不符。
     * 现在改成不分档位、对 sameTypeBefore 里这个类型当月已经赋值过内部执行成本的全部记录求和。
     * sameTypeBefore 是"这一笔之前"同类型已经赋值过内部执行成本的记录（按发布时间升序，
     * 不含这一笔本身）。
     */
    private java.math.BigDecimal capAdjustedRate(ExecutorPayRateTier tier, List<CollaborationTracking> sameTypeBefore) {
        java.math.BigDecimal rate = tier.getRate();
        if (tier.getMonthlyCap() == null || rate == null) return rate;
        java.math.BigDecimal earnedSoFar = java.math.BigDecimal.ZERO;
        for (CollaborationTracking c : sameTypeBefore) {
            java.math.BigDecimal cost = c.getInternalExecutionCost();
            if (cost != null) earnedSoFar = earnedSoFar.add(cost);
        }
        java.math.BigDecimal remaining = tier.getMonthlyCap().subtract(earnedSoFar);
        if (remaining.compareTo(java.math.BigDecimal.ZERO) < 0) remaining = java.math.BigDecimal.ZERO;
        return rate.compareTo(remaining) > 0 ? remaining : rate;
    }

    /**
     * 3f：按当前梯度重新推算 costedAllTypes（该月该负责人+该执行人员所有已确认内部执行成本的
     * 记录，按 id 升序，所有视频类型混在一起）里每一笔"应该是多少钱"，跟实际填的值不一致的
     * 记为"特殊薪酬"。每个视频类型分别按自己的梯度、自己的排位重新算，没配置梯度的类型跳过
     * （无法比对）。
     * @return 没有任何已确认记录、或没有任何不一致时返回 null（没什么好说明的）
     */
    private String computeSpecialPayNote(List<CollaborationTracking> costedAllTypes, Long managerId, Long executorId) {
        if (costedAllTypes.isEmpty()) return null;
        Map<VideoType, List<CollaborationTracking>> byType = new java.util.EnumMap<>(VideoType.class);
        for (CollaborationTracking c : costedAllTypes) {
            if (c.getVideoType() != null) {
                byType.computeIfAbsent(c.getVideoType(), k -> new ArrayList<>()).add(c);
            }
        }

        int specialCount = 0;
        java.math.BigDecimal specialTotal = java.math.BigDecimal.ZERO;
        for (Map.Entry<VideoType, List<CollaborationTracking>> entry : byType.entrySet()) {
            // 2026-08-17 性能修复：改走 ExecutorPayRateTierCache；旧代码：
            // executorPayRateTierRepo.findByManagerIdAndExecutorIdAndVideoTypeAndIsDeletedFalseOrderByMinCountAsc(
            //         managerId, executorId, entry.getKey())
            List<ExecutorPayRateTier> tiers = executorPayRateTierCache.find(managerId, executorId, entry.getKey());
            if (tiers.isEmpty()) continue; // 这个类型没配置梯度，无法比对，跳过

            List<CollaborationTracking> sameType = entry.getValue();
            for (int idx = 0; idx < sameType.size(); idx++) {
                CollaborationTracking c = sameType.get(idx);
                java.math.BigDecimal actual = c.getInternalExecutionCost();
                if (actual == null) continue;
                int position = idx + 1;
                ExecutorPayRateTier matched = findMatchingTier(tiers, position);
                if (matched == null) continue; // 档位没覆盖到这个数字，无法比对
                java.math.BigDecimal expected = capAdjustedRate(matched, sameType.subList(0, idx));
                if (expected != null && expected.compareTo(actual) != 0) {
                    specialCount++;
                    specialTotal = specialTotal.add(actual);
                }
            }
        }
        if (specialCount == 0) return null;
        return "本月共 " + costedAllTypes.size() + " 笔已确认工资，其中 " + specialCount + " 笔（合计 ¥"
                + fmtAmount(specialTotal) + "）为特殊薪酬（与当前梯度价不符）。";
    }

    /** 金额格式化为两位小数字符串，拼接进"本月共X笔已确认工资..."这类提示文案里用 */
    private String fmtAmount(java.math.BigDecimal v) {
        return v == null ? "0.00" : v.setScale(2, java.math.RoundingMode.HALF_UP).toString();
    }

    /**
     * 保存内部执行成本（跟状态流转分开，是独立的一步操作）。
     * notApplicable=true：确认"不涉及内部执行人员"，忽略 executorId，只置标记位，
     * 以后这条记录不会再自动弹出这个弹窗（后续如果真的需要，直接去编辑表单手动设置）。
     * notApplicable=false：executorId 非空时顺带把执行人员也定下来（弹窗允许在这条记录
     * 还没选执行人员的情况下现场选人）；executorId 和记录里已有的执行人员必须至少有一个非空，
     * 否则拒绝。
     *
     * 2026-08 起金额不再由调用方传入——完全按 suggestExecutorCost() 同一套费率梯度逻辑现场
     * 算出来，算不出来（没配置费率梯度，或配置了但档位覆盖不到这个月第几条）直接拒绝保存，
     * 不再退回"手动填"；算出来是 0（命中了配置了当月封顶金额的档位、这个视频类型当月预算
     * 花完了）是正常业务结果，照样保存。这里落库的值 executorCostOverridden 恒为 false——
     * 唯一能把这个字段设成手动特批值的入口是 CollaborationTrackingService.doSave()（ADMIN
     * 在"编辑"表单里改），不是这个方法。
     *
     * "二次修改需审核"（2026-07 新增）：这条记录的内部执行成本只要成功保存过一次
     * （executorCostEverSet=true，不管那一次是金额还是"不涉及执行人员"），之后任何改动
     * 都算"非首次修改"——除非操作人正是这条记录的项目负责人本人，否则不会直接生效，而是
     * 提交一条待审核事项，由该记录的项目负责人在"待处理"模块同意后才真正落地
     * （管理层/ADMIN 提交修改也不例外，不享受直接生效特权）。返回结果用
     * ExecutorCostUpdateResult 区分这两种情况，跟"状态流转"里"进度倒退需要审核"的
     * CollaborationStatusUpdateResult 是同一个思路。这条路径下执行人员早就定下来了
     * （isFirstTime=false 意味着已经成功保存过一次），不接受这次请求顺带改动执行人员本身，
     * 直接用记录当前已有的执行人员现算金额。
     *
     * 2026-08 修复：isFirstTime 是"读一次 executorCostEverSet 就当场决定要不要走审核"，中间没有
     * 加锁——两次并发的 setExecutorCost() 调用（比如误触发两次提交）理论上可能都读到
     * executorCostEverSet=false，都判定成"首次"从而都绕开审核直接生效。加 synchronized，跟
     * PendingApprovalService 那几个 request*() 方法同样的道理：Render 是单实例部署，JVM 锁
     * 就够用；这个方法调用频率低（项目负责人手动操作），粗粒度串行化没有实际性能影响。
     */
    @Transactional
    public synchronized com.lusuoria.settlement.dto.response.ExecutorCostUpdateResult setExecutorCost(
            Long id, Long executorId, boolean notApplicable) {
        CollaborationTracking t = trackingRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("跟踪记录不存在：" + id));

        com.lusuoria.settlement.dto.response.ExecutorCostUpdateResult result =
                new com.lusuoria.settlement.dto.response.ExecutorCostUpdateResult();

        boolean isFirstTime = !Boolean.TRUE.equals(t.getExecutorCostEverSet());
        Long currentEmployeeId = employeeRoleUtil.getCurrentEmployeeId();
        boolean isProjectManagerSelf = currentEmployeeId != null && currentEmployeeId.equals(t.getProjectManagerId());

        if (!isFirstTime && !isProjectManagerSelf) {
            // 非首次修改、操作人又不是这条记录的项目负责人本人：不直接生效，提交审核。
            if (t.getProjectManagerId() == null) {
                throw new RuntimeException("该记录还没有项目负责人，无法提交内部执行成本修改审核，请先在编辑表单里设置项目负责人");
            }
            java.math.BigDecimal requestedAmount = null;
            if (!notApplicable) {
                if (t.getExecutor() == null) {
                    throw new RuntimeException("该记录还没有内部执行人员，无法提交修改审核");
                }
                ExecutorCostSuggestionResponse suggestion = suggestExecutorCost(t.getId(), t.getExecutor().getId());
                if (suggestion.getSuggestedAmount() == null) {
                    throw new RuntimeException(suggestion.getBreakdown());
                }
                requestedAmount = suggestion.getSuggestedAmount();
            }
            // 2026-08 修复（Shawn 反馈"待我审核"里出现过"内部执行成本由¥80.00改为¥80.00"）：
            // 现算出来的建议金额（或 notApplicable 状态）如果跟这条记录当前的值完全一样，
            // 说明费率梯度配置自上次保存以来没有实质变化，这次触发只是"重新点了一下"，不构成
            // 真正的修改，不应该走审核流程去麻烦项目负责人确认一条"改了等于没改"的申请——
            // 直接按现状返回，不落库、不创建待审核事项。BigDecimal 用 compareTo 而不是 equals
            // 比较，避免 80.00 和 80.0 这种 scale 不同但数值相等的情况被误判成"有变化"。
            boolean amountUnchanged = notApplicable
                    ? Boolean.TRUE.equals(t.getExecutorCostNotApplicable())
                    : !Boolean.TRUE.equals(t.getExecutorCostNotApplicable())
                        && requestedAmount != null && t.getInternalExecutionCost() != null
                        && requestedAmount.compareTo(t.getInternalExecutionCost()) == 0;
            if (amountUnchanged) {
                result.setTracking(t);
                result.setPendingApproval(false);
                return result;
            }
            String summary = (t.getBrand() != null ? t.getBrand().getName() : "未知品牌")
                    + " - " + (t.getInfluencer() != null ? t.getInfluencer().getAccountName() : "未知红人");
            pendingApprovalService.requestExecutorCostModify(
                    t.getId(), t.getInternalProjectNo(), summary,
                    t.getInternalExecutionCost(), t.getExecutorCostNotApplicable(),
                    requestedAmount, notApplicable);
            result.setTracking(t);
            result.setPendingApproval(true);
            return result;
        }

        if (notApplicable) {
            t.setExecutorCostNotApplicable(true);
        } else {
            if (executorId != null) {
                Employee executor = employeeCache.findById(executorId);
                if (executor == null) throw new RuntimeException("内部执行人员不存在：" + executorId);
                t.setExecutor(executor);
            }
            if (t.getExecutor() == null) {
                throw new RuntimeException("请先选择内部执行人员，或选择\"不涉及执行人员\"");
            }
            ExecutorCostSuggestionResponse suggestion = suggestExecutorCost(t.getId(), t.getExecutor().getId());
            if (suggestion.getSuggestedAmount() == null) {
                throw new RuntimeException(suggestion.getBreakdown());
            }
            t.setExecutorCostNotApplicable(false);
            t.setInternalExecutionCost(suggestion.getSuggestedAmount());
            t.setExecutorCostOverridden(false);
            // 内部执行成本变了，项目毛利往下的可分配利润/负责人提成/公司利润这些都要跟着重新算一遍
            profitCalculator.calculate(t);
        }
        t.setExecutorCostEverSet(true);
        CollaborationTracking saved = trackingRepo.save(t);
        result.setTracking(saved);
        result.setPendingApproval(false);
        return result;
    }

    /**
     * 解除这条记录跟"红人需求管理"内部需求编号的关联（误关联到别的需求时用，不需要重新走
     * 完整的编辑表单）。只清空 internalRequirementNo 这一个字段，不影响其他任何字段；
     * 权限跟"删除"/"进度倒退"一致，收窄成该记录的项目负责人/执行人员或 ADMIN 才能操作——
     * 解绑跟删除/倒退一样是纠正性质的破坏操作，不应该被无关的人随便点。
     * 解绑后原需求的"需求完成进度"分子会变化，调用 refreshCompletedAt 保持同步
     * （如果这条记录本来占着某个需求条目的名额，解绑后名额也就腾出来了）。
     */
    @Transactional
    public CollaborationTracking unlinkRequirement(Long id) {
        CollaborationTracking t = trackingRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("跟踪记录不存在：" + id));
        assertOwnerOrAdmin(t, "解除需求关联");
        String oldRequirementNo = t.getInternalRequirementNo();
        if (oldRequirementNo == null) {
            throw new RuntimeException("这条记录当前没有关联任何内部需求编号");
        }
        t.setInternalRequirementNo(null);
        CollaborationTracking saved = trackingRepo.save(t);
        requirementService.refreshCompletedAt(oldRequirementNo);
        return saved;
    }

    /**
     * 这个 (项目负责人/管理层, 执行人员, 视频类型) 组合是否已经配置过费率梯度——跟
     * ExecutorPayRateController.check() 走的是同一张表/同一个查询条件，这里额外暴露给
     * Excel 批量导入用（导入不经过那个 REST 接口，需要在服务层直接复用同一条判断规则）。
     */
    public boolean hasExecutorPayRateConfigured(Long managerId, Long executorId, VideoType videoType) {
        // 2026-08-17 性能修复：改走 ExecutorPayRateTierCache；旧代码：
        // executorPayRateTierRepo.findByManagerIdAndExecutorIdAndVideoTypeAndIsDeletedFalseOrderByMinCountAsc(managerId, executorId, videoType)
        return !executorPayRateTierCache.find(managerId, executorId, videoType).isEmpty();
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
     * 2026-07 新增：顺带修复汇率缺失/异常（null 或 ≤0，汇率不可能合法地是0）的记录——这类
     * 记录大多是因为"汇率维护"保存某个月份汇率的时候，这条记录还没有发布时间（发布时间是
     * 后补的），当时没被那次批量覆盖覆盖到（见 ExchangeRateService.saveRate()，那个方法只
     * 覆盖"当时"已经落在这个月的记录）。这里按记录自己的发布时间反查"汇率维护"里对应月份
     * 是否已经配置了汇率，配置了就直接回填；对应月份汇率压根没配置过，没法自动判断该用哪个
     * 汇率，跳过不动，计入"仍然缺失"数量里，由调用方决定要不要提示。
     *
     * 2026-08-12 修复（Shawn 反馈）：还没有发布时间的记录（视频压根还没发布）一律不参与这段
     * 汇率检查——没有发布时间就没有"应该用哪个月份的汇率"这回事，这不是"汇率缺失异常"，是
     * "视频还没发布"这个正常状态，不该被算进"仍然缺失"的异常数量里、也不该提示用户去"补
     * 发布时间"（发布时间不是想补就能补的，视频确实还没发布）。只有已发布却查不到汇率的，
     * 才是真正需要处理的异常。
     *
     * 2026-08 新增：顺带按费率梯度重新计算所有符合条件记录的内部执行成本——内部执行成本
     * 2026-08 起完全由系统按梯度价算（见 setExecutorCost()），存量数据里有不少是当初手动填的、
     * 或者是旧的排位算法（按 id 顺序而不是发布时间）算出来的，跟现在的规则对不上，需要一次性
     * 批量按新规则重新计算一遍。按 (项目负责人,执行人员,视频类型,发布月份) 分组，组内按发布
     * 时间从早到晚依次计算，保证"当月封顶金额"的累计口径跟实际保存时完全一致——不能把这个
     * 组合下的记录混在一起各自独立算，那样"当月已经花了多少"这个累计数字会算错。
     * 不会动的记录：
     *   - executorCostOverridden=true（ADMIN 在编辑表单手动特批的值，见 doSave()）——尊重
     *     这个特批，不覆盖；但它已经花的钱仍然计入同组后续记录的当月封顶累计，不会凭空
     *     多出预算
     *   - 这个 (项目负责人,执行人员,月份) 组合已经在"手下执行人员工资"确认过（2026-08-10
     *     新增，Shawn 反馈后加的保护）——确认动作有自己独立的冻结快照
     *     （ExecutorWageConfirmation.detailJson），底层这条记录的内部执行成本如果被这里悄悄
     *     改动，快照不会跟着变，表面上看不出异常，但这条确认一旦之后因为任何别的原因被取消
     *     确认再重新确认，就会直接读到被批量重算改过的新值，而且完全绕开"内部执行成本二次
     *     修改需要项目负责人审批"那套流程（那套流程只挡得住手动编辑，挡不住这个批量按钮）。
     *     同样只跳过"改动"，不跳过"占用当月封顶预算/排位"，跟 executorCostOverridden 处理方式
     *     一致
     *   - 已确认"不涉及内部执行人员"、或"折损"记录（本来就不参与执行成本核算）
     *   - 缺执行人员/视频类型/发布时间/项目负责人任意一项的（没法归组计算）
     *   - 这个 (项目负责人,执行人员,视频类型) 组合完全没有配置过费率梯度的——整组跳过，
     *     不强行清零、也不中断整个操作，汇总在返回文案里告诉调用方跳过了哪些组合，
     *     需要去补配置或者手动处理
     *
     * @return 一句人类可读的处理结果摘要，包含扫描总数、利润数值实际发生变化的条数、自动补上
     *         汇率的条数、仍然缺失的条数、内部执行成本实际被改动的条数、因缺费率梯度配置跳过的
     *         组合——"实际变化/实际被改动"这两个条数（2026-08-10 起）只统计新算出来的值真的
     *         跟原值不一样的记录，不是"过了一遍计算逻辑"的条数，避免让人误以为改动范围比实际大
     */
    @Transactional
    public String recomputeAllProfits() {
        List<CollaborationTracking> all = trackingRepo.findByIsDeletedFalse();

        // ---- 内部执行成本：按费率梯度分组重新计算，必须先算完再进下面的利润重算循环，
        // 这样 profitCalculator.calculate() 才能用上更新后的金额。分组/逐组重算的逻辑抽到了
        // buildExecCostGroups()/processExecCostGroups()，跟"批量计算执行成本"（项目负责人/
        // 执行人员/管理层自助按钮，见 recomputeExecutorCostsScoped()）共用同一份，这里传
        // t -> true 表示不限定范围（ADMIN 的这个按钮本来就是全表处理）。
        Map<String, List<CollaborationTracking>> execCostGroups = buildExecCostGroups(all);
        Set<String> confirmedWageKeys = buildConfirmedWageKeys();
        ExecCostRecomputeResult execResult = new ExecCostRecomputeResult();
        processExecCostGroups(execCostGroups, confirmedWageKeys, t -> true, execResult);
        int recomputedExecutorCostCount = execResult.recomputedCount;
        int overriddenSkippedCount = execResult.overriddenSkippedCount;
        int outOfRangeSkippedCount = execResult.outOfRangeSkippedCount;
        int noRateSkippedCount = execResult.noRateSkippedCount;
        Set<String> noRateSkippedCombos = execResult.noRateSkippedCombos;

        int fixedExchangeRateCount = 0;
        int stillMissingExchangeRateCount = 0;
        int actuallyChangedProfitCount = 0;
        for (CollaborationTracking t : all) {
            // 2026-08-12 修复（Shawn 反馈）：视频还没发布（没有发布时间）的记录，汇率是0/空
            // 本来就是正常状态——压根没有"应该用哪个月份的汇率"这回事，不算汇率异常，不该被
            // 计进 stillMissingExchangeRateCount 让用户误以为要去"补发布时间"或"配置汇率"才能
            // 修复；只有已经发布（有发布时间）却仍然查不到有效汇率的，才是真正需要处理的异常。
            boolean rateInvalid = t.getPublishDate() != null
                    && (t.getExchangeRate() == null || t.getExchangeRate().compareTo(java.math.BigDecimal.ZERO) <= 0);
            if (rateInvalid) {
                if (fillMissingExchangeRateFromCache(t)) {
                    fixedExchangeRateCount++;
                } else {
                    stillMissingExchangeRateCount++;
                }
            }
            // 2026-08-10 修复（Shawn 反馈）：只统计利润相关字段真的算出不同值的记录条数——
            // profitCalculator.calculate() 本身对每条记录都会跑一遍（哪怕汇率/内部执行成本
            // 压根没变，重算出来的毛利/可分配利润/提成/公司利润跟原值也会完全一样），之前直接
            // 用 all.size()（扫描总数）当"已重新计算"的条数，表达的其实是"检查了多少条"，不是
            // "改动了多少条"，容易让人误以为这次操作动了很多记录。这里記下重算前的快照，
            // 重算完再逐个字段比对（BigDecimal 用 compareTo，避免精度表示不同被误判成"变了"）。
            java.math.BigDecimal beforeGross = t.getGrossProfit();
            java.math.BigDecimal beforeDistributable = t.getDistributableProfit();
            java.math.BigDecimal beforeCommission = t.getCommissionAmount();
            java.math.BigDecimal beforeCompanyProfit = t.getCompanyNetProfit();
            java.math.BigDecimal beforeRmbRevenue = t.getRmbRevenue();
            profitCalculator.calculate(t);
            boolean profitChanged = bigDecimalChanged(beforeGross, t.getGrossProfit())
                    || bigDecimalChanged(beforeDistributable, t.getDistributableProfit())
                    || bigDecimalChanged(beforeCommission, t.getCommissionAmount())
                    || bigDecimalChanged(beforeCompanyProfit, t.getCompanyNetProfit())
                    || bigDecimalChanged(beforeRmbRevenue, t.getRmbRevenue());
            if (profitChanged) actuallyChangedProfitCount++;
            trackingRepo.save(t);
        }

        StringBuilder msg = new StringBuilder("本次扫描 " + all.size() + " 条记录，其中 "
                + actuallyChangedProfitCount + " 条利润数值发生了变化并已更新");
        if (fixedExchangeRateCount > 0) {
            msg.append("（含 ").append(fixedExchangeRateCount)
               .append(" 条因为汇率异常——视频已发布，但汇率是0或缺失——已按发布时间所在月份在"
                       + "\"汇率维护\"里配置的汇率自动修复）");
        }
        if (stillMissingExchangeRateCount > 0) {
            msg.append("；另有 ").append(stillMissingExchangeRateCount)
               .append(" 条记录已发布，但发布时间所在月份还没有在\"汇率维护\"配置汇率，汇率仍然是0或缺失——"
                       + "请先配置好对应月份的汇率，再点一次\"重新计算利润\"修复");
        }
        if (fixedExchangeRateCount == 0 && stillMissingExchangeRateCount == 0) {
            msg.append("；没有发现汇率异常（0或缺失）的记录（视频还没发布的记录本来就不需要汇率，不算异常）");
        }

        msg.append("\n内部执行成本：已按费率梯度重新计算 ").append(recomputedExecutorCostCount).append(" 条");
        if (overriddenSkippedCount > 0) {
            msg.append("；").append(overriddenSkippedCount).append(" 条是 ADMIN 手动特批的值，未覆盖");
        }
        if (outOfRangeSkippedCount > 0) {
            msg.append("；").append(outOfRangeSkippedCount)
               .append(" 条超出了当前配置的梯度覆盖范围，未改动，需要去补充档位配置");
        }
        if (noRateSkippedCount > 0) {
            msg.append("；另有 ").append(noRateSkippedCount)
               .append(" 条因为以下组合还没配置过费率梯度、跳过未处理：")
               .append(String.join("、", noRateSkippedCombos))
               .append("，需要先去\"员工管理\"/\"执行人员管理\"配置后再点一次\"重新计算利润\"补算");
        }
        return msg.toString();
    }

    /**
     * 两个金额是否"实际不同"——recomputeAllProfits() 统计"真的被改动的条数"专用。用 compareTo
     * 而不是 equals，避免 100.00 和 100.0 这种精度表示不同、数值其实相等的情况被误判成"变了"；
     * 一 null 一非 null 也算变了（null 通常代表"从没算过"，回填了具体值当然算改动）。
     */
    private boolean bigDecimalChanged(java.math.BigDecimal before, java.math.BigDecimal after) {
        if (before == null && after == null) return false;
        if (before == null || after == null) return true;
        return before.compareTo(after) != 0;
    }

    /**
     * 汇率缺失/异常（null 或 &lt;=0）时，按发布时间所在月份从"汇率维护"（ExchangeRateCache）
     * 自动回填——doSave()/updateStatus()/recomputeAllProfits() 三处共用（2026-08 抽取，此前
     * 三处各自维护一份几乎一样的代码）。只在有发布时间、且该月份已经配置了有效汇率
     * （非null、大于0）时才回填；发布时间为空（还没发布，没有"该用哪个月份汇率"这回事）或
     * 汇率本身已经是合法值时，直接跳过，不做任何改动。
     *
     * 返回 true 表示这次真的回填了一个新值（原来缺失/异常，现在补上了），调用方可选用这个
     * 返回值统计"修复了几条"（如 recomputeAllProfits()），也可以完全不管返回值、单纯当兜底
     * 调用（如 doSave()/updateStatus()）。
     */
    private boolean fillMissingExchangeRateFromCache(CollaborationTracking t) {
        boolean exchangeRateInvalid = t.getExchangeRate() == null
                || t.getExchangeRate().compareTo(java.math.BigDecimal.ZERO) <= 0;
        if (!exchangeRateInvalid || t.getPublishDate() == null) return false;
        // 2026-08-17 性能修复：改走 ExchangeRateLookupCache；旧代码：
        // exchangeRateCacheRepo.findByYearMonth(...).orElse(null)
        ExchangeRateCache cache = rateLookupCache
                .findByYearMonth(new SimpleDateFormat("yyyyMM").format(t.getPublishDate()));
        if (cache != null && cache.getUsdToCny() != null
                && cache.getUsdToCny().compareTo(java.math.BigDecimal.ZERO) > 0) {
            t.setExchangeRate(cache.getUsdToCny());
            return true;
        }
        return false;
    }

    /**
     * 按 (项目负责人,执行人员,视频类型,发布月份) 分组——recomputeAllProfits()（"重新计算利润"，
     * ADMIN 全表）和 recomputeExecutorCostsScoped()（"批量计算执行成本"，项目负责人/执行人员/
     * 管理层自助按钮，范围各不相同）共用同一份分组规则，改一处两边都生效。
     */
    private Map<String, List<CollaborationTracking>> buildExecCostGroups(List<CollaborationTracking> all) {
        SimpleDateFormat monthFormat = new SimpleDateFormat("yyyyMM");
        Map<String, List<CollaborationTracking>> execCostGroups = new LinkedHashMap<>();
        for (CollaborationTracking t : all) {
            if (t.getExecutor() == null || t.getVideoType() == null || t.getPublishDate() == null
                    || t.getProjectManager() == null) continue;
            if (t.getProgress() == CollaborationProgress.DELAYED) continue;
            if (Boolean.TRUE.equals(t.getExecutorCostNotApplicable())) continue;
            String month = monthFormat.format(t.getPublishDate());
            String key = t.getProjectManager().getId() + "|" + t.getExecutor().getId() + "|" + t.getVideoType() + "|" + month;
            execCostGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }
        return execCostGroups;
    }

    /**
     * 已经在"手下执行人员工资"确认过的 (项目负责人,执行人员,月份) 组合——批量重算内部执行成本
     * 时（不管是 ADMIN 的全表按钮还是项目负责人/执行人员/管理层的自助按钮）都不能悄悄改动这些
     * 组合下的记录，理由见 recomputeAllProfits() 方法注释。
     */
    private Set<String> buildConfirmedWageKeys() {
        Set<String> confirmedWageKeys = new HashSet<>();
        for (ExecutorWageConfirmation c : wageConfirmationRepo.findByConfirmedTrueAndIsDeletedFalse()) {
            if (c.getExecutorId() == null) continue; // 改造前"整批打包确认"的历史记录，不参与判断
            confirmedWageKeys.add(c.getManagerId() + "|" + c.getExecutorId() + "|" + c.getYearMonth());
        }
        return confirmedWageKeys;
    }

    /** processExecCostGroups() 的统计结果 + 实际处理到的记录列表（供调用方决定后续要不要顺带刷新利润字段） */
    private static class ExecCostRecomputeResult {
        List<CollaborationTracking> inScopeRecords = new ArrayList<>();
        int recomputedCount = 0;
        int overriddenSkippedCount = 0;
        int outOfRangeSkippedCount = 0;
        int noRateSkippedCount = 0;
        Set<String> noRateSkippedCombos = new java.util.TreeSet<>();
    }

    /**
     * 按分组逐组重新计算内部执行成本，scopeFilter 决定哪些组属于本次调用的范围——组内的
     * (项目负责人,执行人员) 是固定的，用组内第一条记录判断整组是否在范围内即可，不在范围内的
     * 组整组跳过（不影响其它组的排位/封顶累计，因为分组本身已经按 项目负责人+执行人员+视频
     * 类型+月份 相互隔离）。
     *
     * 在范围内的组，不管这一组最终有没有实际改动内部执行成本（比如缺费率梯度配置整组跳过、
     * 或者组内某条记录本身就是被 ADMIN 特批/工资已确认），组内所有记录都会被收进
     * result.inScopeRecords——调用方用这个列表决定要不要顺带刷新利润相关字段（"这是我自己
     * 负责/执行的记录"这个范围判断，跟"这条记录的执行成本这次有没有真的被改"是两件事）。
     */
    private void processExecCostGroups(Map<String, List<CollaborationTracking>> execCostGroups,
                                        Set<String> confirmedWageKeys,
                                        java.util.function.Predicate<CollaborationTracking> scopeFilter,
                                        ExecCostRecomputeResult result) {
        SimpleDateFormat monthFormat = new SimpleDateFormat("yyyyMM");
        // 2026-08-17 性能修复第二版：这里原来（第一版，2026-08-17 当天早些时候）是"批量查一次
        // executorPayRateTierRepo.findByManagerIdInAndIsDeletedFalse 再在 Java 里建索引"，比最早
        // "每个分组单独查一次"好，但仍然是每次调用这个方法都要查一次库。现在 ExecutorPayRateTierCache
        // 已经把全表数据常驻内存并在写入后主动 refresh()，这一段批量预查+建索引的代码不再需要，
        // 直接在下面循环里调 executorPayRateTierCache.find(...) 就是内存读取，索性删掉这段、
        // 两版旧代码都留档在下面注释里。
        /* ===== 旧代码·第一版（批量预查询版，2026-08-17 停用，按 Shawn 要求保留对比，不要直接删）=====
        Set<Long> allManagerIds = new HashSet<>();
        for (List<CollaborationTracking> group : execCostGroups.values()) {
            CollaborationTracking anyInGroup = group.get(0);
            if (anyInGroup.getProjectManager() != null) allManagerIds.add(anyInGroup.getProjectManager().getId());
        }
        Map<String, List<ExecutorPayRateTier>> tiersByKey = new HashMap<>();
        if (!allManagerIds.isEmpty()) {
            for (ExecutorPayRateTier tier : executorPayRateTierRepo.findByManagerIdInAndIsDeletedFalse(allManagerIds)) {
                String tierKey = tier.getManagerId() + "|" + tier.getExecutorId() + "|" + tier.getVideoType();
                tiersByKey.computeIfAbsent(tierKey, k -> new ArrayList<>()).add(tier);
            }
            for (List<ExecutorPayRateTier> tierList : tiersByKey.values()) {
                tierList.sort(java.util.Comparator.comparing(ExecutorPayRateTier::getMinCount));
            }
        }
        ===== 旧代码·第一版结束 ===== */

        for (List<CollaborationTracking> group : execCostGroups.values()) {
            group.sort((a, b) -> {
                int cmp = a.getPublishDate().compareTo(b.getPublishDate());
                if (cmp != 0) return cmp;
                return a.getId().compareTo(b.getId());
            });
            CollaborationTracking first = group.get(0);
            if (!scopeFilter.test(first)) continue;
            result.inScopeRecords.addAll(group);

            String groupMonth = monthFormat.format(first.getPublishDate());
            List<ExecutorPayRateTier> tiers = executorPayRateTierCache.find(
                    first.getProjectManager().getId(), first.getExecutor().getId(), first.getVideoType());
            /* ===== 旧代码·第二版（批量预查询后查内存索引，2026-08-17 停用，按 Shawn 要求保留
             * 对比，不要直接删）=====
            String tierKey = first.getProjectManager().getId() + "|" + first.getExecutor().getId() + "|" + first.getVideoType();
            List<ExecutorPayRateTier> tiers = tiersByKey.getOrDefault(tierKey, Collections.emptyList());
            ===== 旧代码·第二版结束 =====
             * ===== 旧代码·第一版（每分组单独查库，2026-08-17 停用，按 Shawn 要求保留对比）=====
            List<ExecutorPayRateTier> tiers = executorPayRateTierRepo
                    .findByManagerIdAndExecutorIdAndVideoTypeAndIsDeletedFalseOrderByMinCountAsc(
                            first.getProjectManager().getId(), first.getExecutor().getId(), first.getVideoType());
            ===== 旧代码·第一版结束 ===== */
            if (tiers.isEmpty()) {
                result.noRateSkippedCount += group.size();
                result.noRateSkippedCombos.add(first.getExecutor().getName() + " / " + first.getVideoType().getLabel());
                continue;
            }
            boolean groupWageConfirmed = confirmedWageKeys.contains(
                    first.getProjectManager().getId() + "|" + first.getExecutor().getId() + "|" + groupMonth);
            List<CollaborationTracking> processed = new ArrayList<>();
            for (CollaborationTracking t : group) {
                if (Boolean.TRUE.equals(t.getExecutorCostOverridden())) {
                    result.overriddenSkippedCount++;
                    processed.add(t); // 已花的钱仍然占用这个类型当月的封顶预算
                    continue;
                }
                // 已确认工资的月份：跳过改动，但不计数、不在结果摘要里提——Shawn 反馈这条提示
                // 没必要展示（2026-08-10）。跳过本身的保护逻辑不变，见 buildConfirmedWageKeys()
                // 的注释；这里同样占用当月封顶预算/排位，不跳过 processed.add()
                if (groupWageConfirmed) {
                    processed.add(t);
                    continue;
                }
                int position = processed.size() + 1;
                ExecutorPayRateTier matched = findMatchingTier(tiers, position);
                if (matched == null) {
                    result.outOfRangeSkippedCount++;
                } else {
                    // 只有算出来的新值真的跟原值不一样才计入"已重新计算"的条数，见
                    // bigDecimalChanged() 的说明
                    java.math.BigDecimal before = t.getInternalExecutionCost();
                    java.math.BigDecimal after = capAdjustedRate(matched, processed);
                    t.setInternalExecutionCost(after);
                    if (bigDecimalChanged(before, after)) result.recomputedCount++;
                }
                processed.add(t);
            }
        }
    }

    /**
     * "批量计算执行成本"按钮（红人合作跟踪列表页，仅项目负责人/执行人员/管理层可见）：
     * 复用 recomputeAllProfits() 里"内部执行成本按费率梯度重新计算"那一段逻辑，但范围严格限定
     * 在当前登录员工自己身上，不是全表——
     *   - 管理层：等同于"特殊的项目负责人"，不限定，处理全部记录（对应 ADMIN 全表按钮里
     *     这一部分的行为，但仅限执行成本，不含 ADMIN 专属的汇率自动修复）
     *   - 项目负责人：只处理 projectManagerId = 自己 的记录，不会碰其他项目负责人名下的
     *   - 执行人员：只处理 executorId = 自己 的记录，不管这条记录挂在哪个项目负责人名下
     * 已在"手下执行人员工资"确认过的月份、以及 ADMIN 手动特批（executorCostOverridden）的值，
     * 同样不会被覆盖——这两条跳过规则是 processExecCostGroups() 内置的，跟 ADMIN 按钮完全一致。
     *
     * 执行成本改了之后，顺带对本次范围内的记录调用 ProfitCalculator.calculate() 刷新项目毛利/
     * 可分配利润/提成/公司利润这些下游字段，避免执行成本改了、利润字段却还是旧值这种不一致
     * （2026-08 Shawn 明确要求）。但不做 ADMIN 按钮那种"汇率缺失自动按月份回填"——项目负责人/
     * 执行人员没有编辑汇率的权限（RoleUtil.canEditExchangeRate() 仅 ADMIN），遇到汇率缺失/异常
     * 的记录只统计条数、提示联系管理层处理，不擅自回填。
     *
     * 2026-08-12 起，返回给前端的提示文案里不出现任何"利润"字样（Shawn 明确要求）——项目负责人/
     * 执行人员本来就看不到毛利/可分配利润/提成/公司利润这些字段（ProjectFieldVisibility），
     * 提示里提这些他们看不到的东西没有意义；汇率异常这类情况也只报"汇率缺失或异常"这个原因
     * 本身，不牵扯"公司利润不准确"这种利润相关的措辞。字段本身仍然照常刷新，只是不写进提示文案。
     *
     * @param employeeRole 当前登录账号关联的员工角色（EmployeeRoleUtil.getCurrentEmployeeRole()），
     *                      必须是"管理层"/"项目负责人"/"执行人员"之一，否则视为越权直接拒绝——
     *                      前端按钮虽然只对这三种角色展示，但这里必须服务端再校验一遍，不能只靠
     *                      前端隐藏按钮
     * @param employeeId   当前登录账号关联的员工 id（EmployeeRoleUtil.getCurrentEmployeeId()），
     *                      管理层以外的角色必须非空，否则无法确定范围
     */
    @Transactional
    public String recomputeExecutorCostsScoped(String employeeRole, Long employeeId) {
        boolean isManagement = "管理层".equals(employeeRole);
        boolean isProjectManager = "项目负责人".equals(employeeRole);
        boolean isExecutor = "执行人员".equals(employeeRole);
        if (!isManagement && !isProjectManager && !isExecutor) {
            throw new RuntimeException("当前账号无权限执行\"批量计算执行成本\"");
        }
        if (!isManagement && employeeId == null) {
            throw new RuntimeException("当前账号未关联员工，无法确定计算范围");
        }

        java.util.function.Predicate<CollaborationTracking> scopeFilter;
        if (isManagement) {
            scopeFilter = t -> true;
        } else if (isProjectManager) {
            scopeFilter = t -> t.getProjectManager() != null && employeeId.equals(t.getProjectManager().getId());
        } else {
            scopeFilter = t -> t.getExecutor() != null && employeeId.equals(t.getExecutor().getId());
        }

        List<CollaborationTracking> all = trackingRepo.findByIsDeletedFalse();
        Map<String, List<CollaborationTracking>> execCostGroups = buildExecCostGroups(all);
        Set<String> confirmedWageKeys = buildConfirmedWageKeys();
        ExecCostRecomputeResult result = new ExecCostRecomputeResult();
        processExecCostGroups(execCostGroups, confirmedWageKeys, scopeFilter, result);

        if (result.inScopeRecords.isEmpty()) {
            return "没有找到符合条件的记录，无需计算";
        }

        // 执行成本改完之后，顺带刷新这批记录的下游字段（含利润相关字段，但不写进下面的提示
        // 文案——见方法注释）
        //
        // 注：这里不需要像 recomputeAllProfits() 那样额外排除"还没发布"的记录——
        // result.inScopeRecords 完全来自 buildExecCostGroups()，而那边的分组条件本来就要求
        // t.getPublishDate() != null，所以走到这里的记录一定已经发布，下面统计到的
        // "汇率缺失/异常"天然就是真正的异常，不会误伤还没发布的记录。
        int exchangeRateMissingCount = 0;
        for (CollaborationTracking t : result.inScopeRecords) {
            boolean rateInvalid = t.getExchangeRate() == null
                    || t.getExchangeRate().compareTo(java.math.BigDecimal.ZERO) <= 0;
            if (rateInvalid) exchangeRateMissingCount++;
            profitCalculator.calculate(t);
        }
        trackingRepo.saveAll(result.inScopeRecords);

        StringBuilder msg = new StringBuilder("本次扫描 " + result.inScopeRecords.size()
                + " 条记录，内部执行成本按费率梯度重新计算 " + result.recomputedCount + " 条");
        if (result.overriddenSkippedCount > 0) {
            msg.append("；").append(result.overriddenSkippedCount).append(" 条是 ADMIN 手动特批的值，未覆盖");
        }
        if (result.outOfRangeSkippedCount > 0) {
            msg.append("；").append(result.outOfRangeSkippedCount)
               .append(" 条超出了当前配置的梯度覆盖范围，未改动，请联系管理层补充档位配置");
        }
        if (result.noRateSkippedCount > 0) {
            msg.append("；另有 ").append(result.noRateSkippedCount)
               .append(" 条因为以下组合还没配置过费率梯度、跳过未处理：")
               .append(String.join("、", result.noRateSkippedCombos))
               .append("，请联系管理层配置后再重新计算");
        }
        if (exchangeRateMissingCount > 0) {
            msg.append("；另有 ").append(exchangeRateMissingCount)
               .append(" 条记录汇率缺失或异常，请联系管理层在\"汇率维护\"里补充配置后重新计算");
        }
        return msg.toString();
    }

    /** 空白字符串统一转成 null，避免落库时把空字符串跟"未填写"混淆 */
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
