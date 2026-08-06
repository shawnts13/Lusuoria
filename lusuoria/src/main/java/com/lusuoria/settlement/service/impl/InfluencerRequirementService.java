package com.lusuoria.settlement.service.impl;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.config.InfluencerTeamCache;
import com.lusuoria.settlement.dto.request.CollaborationTrackingRequest;
import com.lusuoria.settlement.dto.request.InfluencerRequirementItemRequest;
import com.lusuoria.settlement.dto.request.InfluencerRequirementRequest;
import com.lusuoria.settlement.dto.response.InfluencerRequirementItemResponse;
import com.lusuoria.settlement.dto.response.LegacyTrackingCandidateResponse;
import com.lusuoria.settlement.dto.response.RequirementContentParseResponse;
import com.lusuoria.settlement.dto.response.RequirementTrackingSummaryResponse;
import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.entity.InfluencerBrandTeam;
import com.lusuoria.settlement.entity.InfluencerContract;
import com.lusuoria.settlement.entity.InfluencerRequirement;
import com.lusuoria.settlement.entity.InfluencerRequirementItem;
import com.lusuoria.settlement.entity.InfluencerPayment;
import com.lusuoria.settlement.entity.InfluencerTeam;
import com.lusuoria.settlement.enums.InfluencerPaymentProgress;
import com.lusuoria.settlement.enums.InfluencerPaymentStatus;
import com.lusuoria.settlement.enums.PaymentCycleType;
import com.lusuoria.settlement.enums.RequirementSettlementStatus;
import com.lusuoria.settlement.enums.VideoType;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import com.lusuoria.settlement.repository.InfluencerBrandTeamRepository;
import com.lusuoria.settlement.repository.InfluencerContractRepository;
import com.lusuoria.settlement.repository.InfluencerPaymentRepository;
import com.lusuoria.settlement.repository.InfluencerRepository;
import com.lusuoria.settlement.repository.InfluencerRequirementItemRepository;
import com.lusuoria.settlement.repository.InfluencerRequirementRepository;
import com.lusuoria.settlement.util.MultiValueUtil;
import com.lusuoria.settlement.util.RequirementContentParser;
import com.lusuoria.settlement.util.RequirementNoAllocator;
import com.lusuoria.settlement.util.WorkdayUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 红人需求管理 - 业务逻辑。
 *
 * "内部需求编号"格式/唯一性分配复用"红人合作跟踪"那一套（ProjectNoGenerator 纯格式化 +
 * RequirementNoAllocator 查这张表判重），品牌方/团队校验规则也跟 CollaborationTrackingService
 * 完全一致（必须是红人在红人模块里已关联的"品牌方-团队"对）。
 */
@Service
public class InfluencerRequirementService {

    @Autowired private InfluencerRequirementRepository requirementRepo;
    @Autowired private InfluencerRequirementItemRepository itemRepo;
    @Autowired private InfluencerRepository influencerRepo;
    @Autowired private InfluencerBrandTeamRepository influencerBrandTeamRepo;
    @Autowired private BrandCache brandCache;
    @Autowired private InfluencerTeamCache teamCache;
    @Autowired private EmployeeCache employeeCache;
    @Autowired private RequirementNoAllocator noAllocator;
    @Autowired private RequirementContentParser contentParser;
    @Autowired private CollaborationTrackingRepository trackingRepo;
    @Autowired private InfluencerContractRepository influencerContractRepo;
    @Autowired private InfluencerPaymentRepository paymentRepo;

    @Transactional
    public InfluencerRequirement save(InfluencerRequirementRequest req) {
        Influencer influencer = influencerRepo.findByIdAndIsDeletedFalse(req.getInfluencerId())
                .orElseThrow(() -> new RuntimeException("红人不存在：" + req.getInfluencerId()));

        InfluencerRequirement requirement;
        if (req.getId() != null) {
            requirement = requirementRepo.findByIdAndIsDeletedFalse(req.getId())
                    .orElseThrow(() -> new RuntimeException("需求记录不存在：" + req.getId()));
        } else {
            requirement = new InfluencerRequirement();
            requirement.setIsDeleted(false);
        }
        requirement.setInfluencer(influencer);

        // 品牌方：必须是该红人在红人模块里已关联的"品牌方-团队"对里出现过的品牌方，跟
        // CollaborationTrackingService.doSave() 的校验规则完全一致
        List<InfluencerBrandTeam> teamOptions = null;
        if (req.getBrandId() != null) {
            Brand brand = brandCache.findById(req.getBrandId());
            if (brand == null) throw new RuntimeException("品牌方不存在：" + req.getBrandId());
            teamOptions = influencerBrandTeamRepo.findByInfluencerIdAndBrandId(influencer.getId(), req.getBrandId());
            if (teamOptions.isEmpty()) {
                throw new RuntimeException("品牌方 [" + brand.getName() + "] 未在红人模块中关联到该红人，"
                        + "请先在红人模块维护后再选择");
            }
            requirement.setBrand(brand);
        } else {
            requirement.setBrand(null);
        }

        if (teamOptions == null || teamOptions.isEmpty()) {
            if (req.getTeamId() != null) {
                throw new RuntimeException("请先选择品牌方，或该红人在此品牌方下没有关联任何团队");
            }
            requirement.setTeam(null);
        } else if (teamOptions.size() == 1) {
            Long onlyTeamId = teamOptions.get(0).getTeamId();
            requirement.setTeam(onlyTeamId != null ? teamCache.findById(onlyTeamId) : null);
        } else {
            boolean matched = teamOptions.stream()
                    .anyMatch(t -> java.util.Objects.equals(t.getTeamId(), req.getTeamId()));
            if (!matched) {
                throw new RuntimeException("该红人在品牌方 [" + requirement.getBrand().getName()
                        + "] 下关联了多个团队，请明确选择其中一个团队");
            }
            requirement.setTeam(req.getTeamId() != null ? teamCache.findById(req.getTeamId()) : null);
        }

        // 服务国家/市场：单选，"单个自动填/多个手动选"规则跟 CollaborationTracking 一致
        requirement.setCountryMarket(MultiValueUtil.resolveSingleChoice(
                influencer.getCountryMarket(), req.getCountryMarket(), "服务国家/市场"));

        requirement.setRequirementMonth(req.getRequirementMonth() != null && !req.getRequirementMonth().trim().isEmpty()
                ? req.getRequirementMonth().trim()
                : new SimpleDateFormat("yyyyMM").format(new Date()));
        requirement.setFullRequirementContent(req.getFullRequirementContent());
        requirement.setNotes(req.getNotes());

        // 默认项目负责人：可选，纯粹给"红人合作跟踪"关联这条需求新建时提供默认候选，
        // 不做角色校验（跟 CollaborationTrackingService.doSave() 对 projectManagerId 的处理一致）
        if (req.getDefaultProjectManagerId() != null) {
            Employee defaultManager = employeeCache.findById(req.getDefaultProjectManagerId());
            if (defaultManager == null) {
                throw new RuntimeException("默认项目负责人不存在：" + req.getDefaultProjectManagerId());
            }
            requirement.setDefaultProjectManager(defaultManager);
        } else {
            requirement.setDefaultProjectManager(null);
        }

        boolean isNew = requirement.getId() == null;

        applyItems(requirement, req.getItems());

        if (isNew) {
            String brandName = requirement.getBrand() != null ? requirement.getBrand().getName() : null;
            String teamName = requirement.getTeam() != null ? requirement.getTeam().getName() : null;
            requirement.setInternalRequirementNo(
                    noAllocator.allocate(brandName, teamName, requirement.getRequirementMonth(), influencer.getAccountName()));
        }

        InfluencerRequirement saved = requirementRepo.save(requirement);
        // applyItems() 可能改了 totalItemCount（比如把条目总数改小了）——如果这一改让"需求完成
        // 进度"跨过或跌破100%，completedAt 要跟着重新判定一次，不能只靠"某条合作跟踪记录进度
        // 变化"这一种触发方式（2026-08 修复：之前编辑需求条目导致 totalItemCount 减少、
        // 从而"被动"达到100%完成的场景，completedAt 永远不会被补上，因为这里从来没调用过
        // refreshCompletedAt）。新建需求（isNew）时这里恒是 no-op（total>0 但 completed 必然
        // 是0，不影响），一并调用不需要额外判断，逻辑更简单。
        refreshCompletedAt(saved.getInternalRequirementNo());
        return saved;
    }

    /**
     * 增量协调条目：按 id 匹配已有条目做更新，没有 id 的是新增，请求里没出现的已有条目视为删除——
     * 但已经有关联的合作跟踪记录（fulfilledCount > 0）的条目不允许删除/改动 videoType、
     * platform 或两个单价（2026-07 起单价也不能改了——同一需求内 (videoType, platform) 允许
     * 重复、靠单价区分是哪个条目之后，单价就是判断"一条记录属于哪个条目"的一部分，已经关联
     * 记录后改单价会导致这些记录跟条目对不上、"已实施"数算错），保证已经实施的记录不会变成
     * 孤儿。同一需求内 (videoType, 排序后platform) 允许重复（取消唯一限制——同样的视频类型/
     * 平台组合，实际上可能有不同的视频单价，需要拆成多个条目分别记录），
     * validateTrackingLinkage 按 (videoType, platform, 两个单价) 精确匹配到具体条目做名额校验。
     */
    private void applyItems(InfluencerRequirement requirement, List<InfluencerRequirementItemRequest> itemReqs) {
        if (itemReqs == null) itemReqs = new ArrayList<>();
        // 防御性兜底：new InfluencerRequirement() 走的是 @NoArgsConstructor，不保证
        // @Builder.Default 的初始值一定生效，这里手动兜底避免空指针
        if (requirement.getItems() == null) requirement.setItems(new ArrayList<>());

        Map<Long, InfluencerRequirementItem> existingById = requirement.getItems().stream()
                .collect(Collectors.toMap(InfluencerRequirementItem::getId, i -> i));
        Map<Long, Integer> fulfilledByItemId = requirement.getId() != null
                ? fulfilledCountByItemId(requirement) : new HashMap<>();

        Set<Long> keptIds = new HashSet<>();
        int totalCount = 0;
        BigDecimal totalClient = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;

        for (InfluencerRequirementItemRequest itemReq : itemReqs) {
            if (itemReq.getVideoType() == null) throw new RuntimeException("请选择项目视频类型");
            if (itemReq.getVideoCount() == null || itemReq.getVideoCount() <= 0) {
                throw new RuntimeException("项目视频数目必须是大于0的整数");
            }
            String platform = canonicalPlatform(itemReq.getPlatform());

            InfluencerRequirementItem item = itemReq.getId() != null ? existingById.get(itemReq.getId()) : null;
            if (item == null) {
                item = new InfluencerRequirementItem();
                item.setRequirement(requirement);
                requirement.getItems().add(item);
            } else {
                keptIds.add(item.getId());
                Integer fulfilled = fulfilledByItemId.getOrDefault(item.getId(), 0);
                if (fulfilled > 0 && (item.getVideoType() != itemReq.getVideoType()
                        || !java.util.Objects.equals(item.getPlatform(), platform)
                        || !amountsEqual(item.getInfluencerUnitCostPrice(), itemReq.getInfluencerUnitCostPrice())
                        || !amountsEqual(item.getClientUnitPrice(), itemReq.getClientUnitPrice()))) {
                    throw new RuntimeException("该条目已经有关联的红人合作跟踪记录，不能修改项目视频类型/合作平台/单价");
                }
                if (fulfilled > itemReq.getVideoCount()) {
                    throw new RuntimeException("该条目已经实施了 " + fulfilled + " 条，项目视频数目不能改得比这个还小");
                }
            }
            item.setVideoType(itemReq.getVideoType());
            item.setPlatform(platform);
            item.setVideoCount(itemReq.getVideoCount());
            item.setClientUnitPrice(itemReq.getClientUnitPrice());
            item.setInfluencerUnitCostPrice(itemReq.getInfluencerUnitCostPrice());

            totalCount += itemReq.getVideoCount();
            if (itemReq.getClientUnitPrice() != null) {
                totalClient = totalClient.add(itemReq.getClientUnitPrice().multiply(BigDecimal.valueOf(itemReq.getVideoCount())));
            }
            if (itemReq.getInfluencerUnitCostPrice() != null) {
                totalCost = totalCost.add(itemReq.getInfluencerUnitCostPrice().multiply(BigDecimal.valueOf(itemReq.getVideoCount())));
            }
        }

        // 请求里没出现的已有条目：没有关联记录的直接移除（orphanRemoval 级联删除）；
        // 已经有关联记录的不允许被"漏掉"，必须报错，不能静默丢弃
        for (InfluencerRequirementItem existing : new ArrayList<>(requirement.getItems())) {
            if (existing.getId() != null && !keptIds.contains(existing.getId())) {
                int fulfilled = fulfilledByItemId.getOrDefault(existing.getId(), 0);
                if (fulfilled > 0) {
                    throw new RuntimeException("「" + existing.getVideoType().getLabel() + "-"
                            + existing.getPlatform().replace("\n", "、") + "」这个条目已经有关联的红人合作跟踪记录，不能删除");
                }
                requirement.getItems().remove(existing);
            }
        }

        requirement.setTotalItemCount(totalCount);
        requirement.setTotalClientPrice(totalClient);
        requirement.setTotalInfluencerCost(totalCost);
    }

    /** 平台多选值按字典序排序后换行拼接，保证判重不受选择顺序影响 */
    private String canonicalPlatform(List<String> platforms) {
        if (platforms == null || platforms.isEmpty()) throw new RuntimeException("请选择合作平台");
        List<String> sorted = platforms.stream().filter(p -> p != null && !p.trim().isEmpty())
                .map(String::trim).distinct().sorted().collect(Collectors.toList());
        if (sorted.isEmpty()) throw new RuntimeException("请选择合作平台");
        return String.join("\n", sorted);
    }

    /**
     * 某个需求下每个条目 id 已经关联了多少条红人合作跟踪记录（不看 progress 状态）。
     * 2026-07：同一需求内允许多个条目共用同一个 (videoType, platform) 组合（价格不同），
     * 所以判断一条记录属于"哪个条目"必须把单价也算进匹配条件，不能只按组合——否则两个
     * 同组合不同价的条目会把同一批记录重复计入各自的"已实施"，出现类似"5条容量的条目
     * 显示实施了6条，1条容量的条目也显示实施了6条"这种同一批记录被两个条目各自算一遍的错误。
     */
    private Map<Long, Integer> fulfilledCountByItemId(InfluencerRequirement requirement) {
        List<CollaborationTracking> linked =
                trackingRepo.findByInternalRequirementNoAndIsDeletedFalse(requirement.getInternalRequirementNo());
        Map<Long, Integer> result = new HashMap<>();
        for (InfluencerRequirementItem item : requirement.getItems()) {
            if (item.getId() == null) continue;
            int count = (int) linked.stream()
                    .filter(t -> t.getVideoType() == item.getVideoType()
                            && java.util.Objects.equals(canonicalTrackingPlatform(t.getPlatform()), item.getPlatform())
                            && amountsEqual(t.getInfluencerCost(), item.getInfluencerUnitCostPrice())
                            && amountsEqual(t.getClientPrice(), item.getClientUnitPrice()))
                    .count();
            result.put(item.getId(), count);
        }
        return result;
    }

    /** 把 CollaborationTracking.platform（原样顺序存的换行串）归一化成跟需求条目一致的排序后形式再比较 */
    private String canonicalTrackingPlatform(String platform) {
        List<String> list = MultiValueUtil.splitMulti(platform);
        return list.isEmpty() ? "" : String.join("\n", list.stream().sorted().collect(Collectors.toList()));
    }

    @Transactional(readOnly = true)
    public List<InfluencerRequirementItemResponse> listItems(Long requirementId) {
        InfluencerRequirement requirement = requirementRepo.findByIdAndIsDeletedFalse(requirementId)
                .orElseThrow(() -> new RuntimeException("需求记录不存在：" + requirementId));
        List<InfluencerRequirementItem> items = itemRepo.findByRequirementIdOrderByIdAsc(requirementId);
        Map<Long, Integer> fulfilled = fulfilledCountByItemId(requirement);
        List<InfluencerRequirementItemResponse> result = new ArrayList<>();
        for (InfluencerRequirementItem i : items) {
            InfluencerRequirementItemResponse r = new InfluencerRequirementItemResponse();
            r.setId(i.getId());
            r.setVideoType(i.getVideoType());
            r.setVideoTypeLabel(i.getVideoType() != null ? i.getVideoType().getLabel() : null);
            r.setPlatform(i.getPlatform());
            r.setVideoCount(i.getVideoCount());
            r.setClientUnitPrice(i.getClientUnitPrice());
            r.setInfluencerUnitCostPrice(i.getInfluencerUnitCostPrice());
            r.setFulfilledCount(fulfilled.getOrDefault(i.getId(), 0));
            result.add(r);
        }
        return result;
    }

    /**
     * "关联红人需求"选择器第一步 / "存量记录关联需求"第二步（byInfluencer 接口共用同一个方法）：
     * 某个红人名下"需求完成进度"未满、且至少还有一个条目有剩余名额的需求。
     *
     * 2026-08 修复：之前只判断 completed<total，没有排除"所有条目名额都已经被现有关联记录
     * 占满"的需求——即使整体还没100%完成（比如已实施的记录里有几条进度还在早期阶段，没到
     * "已发布"及以后），只要每个条目的名额都已经占满，这个需求也没有空间再关联任何新/存量
     * 记录了，继续让它出现在选择器里会误导用户白选一次（"存量记录关联需求"走到第三步必然是
     * 空候选列表，"关联红人需求"走到确认那一步必然被 validateTrackingLinkage 拒绝）。
     * 现在改成逐条目用 fulfilledCountByItemId() 同一套名额校验口径判断"是否还有剩余名额"，
     * 不能只用"已建立跟踪记录总数 established >= totalItemCount"这种粗略的汇总比较——
     * 如果存量脏数据导致某个条目已经超额、另一个条目还有空位，汇总数字可能掩盖后者真的有空位
     * 这个事实。
     */
    @Transactional(readOnly = true)
    public List<InfluencerRequirement> listIncompleteByInfluencer(Long influencerId) {
        List<InfluencerRequirement> all = requirementRepo.findByInfluencerIdAndIsDeletedFalse(influencerId);
        List<String> nos = all.stream().map(InfluencerRequirement::getInternalRequirementNo).collect(Collectors.toList());
        Map<String, Integer> completedByNo = completedCountByNos(nos);
        List<InfluencerRequirement> incomplete = new ArrayList<>();
        for (InfluencerRequirement r : all) {
            int completed = completedByNo.getOrDefault(r.getInternalRequirementNo(), 0);
            r.setCompletedCount(completed);
            int total = r.getTotalItemCount() != null ? r.getTotalItemCount() : 0;
            if (completed < total && hasRemainingCapacity(r)) incomplete.add(r);
        }
        return incomplete;
    }

    /** 这个需求是否至少有一个条目还有剩余名额（未被现有关联记录占满），逐条目精确判断，见 listIncompleteByInfluencer 的说明 */
    private boolean hasRemainingCapacity(InfluencerRequirement requirement) {
        if (requirement.getItems() == null || requirement.getItems().isEmpty()) return false;
        Map<Long, Integer> fulfilledByItemId = fulfilledCountByItemId(requirement);
        for (InfluencerRequirementItem item : requirement.getItems()) {
            int cap = item.getVideoCount() != null ? item.getVideoCount() : 0;
            if (fulfilledByItemId.getOrDefault(item.getId(), 0) < cap) return true;
        }
        return false;
    }

    /**
     * "需求列表页 - 查看未完成的需求"按钮专用：跟 findByFilters 走同一套筛选条件，只是额外
     * 只保留"需求完成进度"没到100%的（含 totalItemCount 为空/0 的情况——一条需求还没有任何
     * 条目，进度显示是0%，也算"未完成"，跟 listIncompleteByInfluencer 那条"completed<total"
     * 的口径略有不同，那个方法是给"关联红人需求"选择器用的，不在这里复用）。
     * completedCount 分子没法下推到 SQL WHERE，只能先查出来后在内存里筛+手动分页——
     * 2026-07-28 起改成先只查轻量投影（id/internalRequirementNo/totalItemCount）筛出
     * 未完成的 id 并分页，完整实体只对"当前页"这一小撮 id 才去查，避免每次翻页/筛选都把
     * 命中的需求全部实体（含 influencer 关联、备注等大字段）都查一遍。
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<InfluencerRequirement> pageIncomplete(
            Long brandId, Long teamId, String accountName, String requirementMonth,
            String internalRequirementNo, String completedMonth,
            org.springframework.data.domain.Pageable pageable) {
        List<Object[]> liteRows = requirementRepo.findLiteProjectionByFilters(
                brandId, teamId, accountName, requirementMonth, internalRequirementNo,
                completedMonth, pageable.getSort());
        List<String> nos = liteRows.stream().map(row -> (String) row[1]).collect(Collectors.toList());
        Map<String, Integer> completedByNo = completedCountByNos(nos);
        Map<String, Integer> establishedByNo = establishedCountByNos(nos);

        List<Long> incompleteIds = new ArrayList<>();
        for (Object[] row : liteRows) {
            Long id = ((Number) row[0]).longValue();
            String no = (String) row[1];
            Integer totalItemCount = (Integer) row[2];
            int completed = completedByNo.getOrDefault(no, 0);
            int total = totalItemCount != null ? totalItemCount : 0;
            boolean isComplete = total > 0 && completed >= total;
            if (!isComplete) incompleteIds.add(id);
        }

        int total = incompleteIds.size();
        int start = Math.min((int) pageable.getOffset(), total);
        int end = Math.min(start + pageable.getPageSize(), total);
        List<Long> pageIds = incompleteIds.subList(start, end);

        Map<Long, InfluencerRequirement> byId = requirementRepo.findAllById(pageIds).stream()
                .collect(Collectors.toMap(InfluencerRequirement::getId, r -> r));
        List<InfluencerRequirement> pageContent = new ArrayList<>();
        for (Long id : pageIds) {
            InfluencerRequirement r = byId.get(id);
            if (r == null) continue;
            r.setCompletedCount(completedByNo.getOrDefault(r.getInternalRequirementNo(), 0));
            r.setEstablishedCount(establishedByNo.getOrDefault(r.getInternalRequirementNo(), 0));
            pageContent.add(r);
        }
        return new org.springframework.data.domain.PageImpl<>(pageContent, pageable, total);
    }

    /**
     * 需求列表页默认排序（2026-07 新增）：不筛选（"全部需求"视角，不是"查看未完成的需求"那个
     * 开关），但未完成的排在已完成的前面——只在前端处于"默认排序"（按 id）时触发，用户主动点了
     * 别的列头排序时不做这层重排（尊重用户的显式排序选择）。"未完成"口径跟 pageIncomplete 完全
     * 一致（total<=0 或 completed<total 都算未完成），稳定分桶排序：未完成的桶内部、已完成的桶
     * 内部，各自仍按请求的排序（这里是 id 倒序）保持相对顺序不变。做法照抄 pageIncomplete——
     * 先轻量投影全量查出来分桶算 id 顺序，完整实体只对"当前页"这一小撮 id 才去查。
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<InfluencerRequirement> listIncompleteFirst(
            Long brandId, Long teamId, String accountName, String requirementMonth,
            String internalRequirementNo, String completedMonth,
            org.springframework.data.domain.Pageable pageable) {
        List<Object[]> liteRows = requirementRepo.findLiteProjectionByFilters(
                brandId, teamId, accountName, requirementMonth, internalRequirementNo,
                completedMonth, pageable.getSort());
        List<String> nos = liteRows.stream().map(row -> (String) row[1]).collect(Collectors.toList());
        Map<String, Integer> completedByNo = completedCountByNos(nos);

        List<Long> incompleteIds = new ArrayList<>();
        List<Long> completeIds = new ArrayList<>();
        for (Object[] row : liteRows) {
            Long id = ((Number) row[0]).longValue();
            String no = (String) row[1];
            Integer totalItemCount = (Integer) row[2];
            int completed = completedByNo.getOrDefault(no, 0);
            int total = totalItemCount != null ? totalItemCount : 0;
            boolean isComplete = total > 0 && completed >= total;
            (isComplete ? completeIds : incompleteIds).add(id);
        }
        List<Long> orderedIds = new ArrayList<>(incompleteIds);
        orderedIds.addAll(completeIds);

        int total = orderedIds.size();
        int start = Math.min((int) pageable.getOffset(), total);
        int end = Math.min(start + pageable.getPageSize(), total);
        List<Long> pageIds = orderedIds.subList(start, end);

        Map<Long, InfluencerRequirement> byId = requirementRepo.findAllById(pageIds).stream()
                .collect(Collectors.toMap(InfluencerRequirement::getId, r -> r));
        Map<String, Integer> establishedByNo = establishedCountByNos(nos);
        List<InfluencerRequirement> pageContent = new ArrayList<>();
        for (Long id : pageIds) {
            InfluencerRequirement r = byId.get(id);
            if (r == null) continue;
            r.setCompletedCount(completedByNo.getOrDefault(r.getInternalRequirementNo(), 0));
            r.setEstablishedCount(establishedByNo.getOrDefault(r.getInternalRequirementNo(), 0));
            pageContent.add(r);
        }
        return new org.springframework.data.domain.PageImpl<>(pageContent, pageable, total);
    }

    /**
     * "需求列表页 - 查看未上传invoice的需求"按钮专用（2026-08 新增）：条件跟 findByFilters
     * 一致，只额外要求需求已完成（completedAt有值）、品牌方涉及invoice上传
     * （Brand.requiresInvoiceUpload()）、且还没上传invoice（invoiceLink为空）——口径完全对齐
     * REQUIREMENT_INVOICE_OVERDUE 提醒批次（ProgressReminderService.runRequirementInvoiceOverdue），
     * 只是不按"超出阈值天数"分档，只要没传就算命中，方便随时自查、不用等提醒真正超出阈值才触发。
     * 这个模块数据量不大，全量查询+内存筛选+手动分页足够用（跟 pageIncomplete 同一个理由）。
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<InfluencerRequirement> pageMissingInvoice(
            Long brandId, Long teamId, String accountName, String requirementMonth,
            String internalRequirementNo, String completedMonth,
            org.springframework.data.domain.Pageable pageable) {
        List<InfluencerRequirement> all = requirementRepo.findByFiltersNoPaging(
                brandId, teamId, accountName, requirementMonth, internalRequirementNo,
                completedMonth, pageable.getSort());
        List<InfluencerRequirement> missing = new ArrayList<>();
        for (InfluencerRequirement r : all) {
            if (r.getCompletedAt() == null) continue;
            if (r.getInvoiceLink() != null && !r.getInvoiceLink().trim().isEmpty()) continue;
            Brand brand = r.getBrandId() != null ? brandCache.findById(r.getBrandId()) : null;
            if (brand != null && !brand.requiresInvoiceUpload()) continue;
            missing.add(r);
        }
        return paginateAndEnrich(missing, pageable);
    }

    /**
     * "需求列表页 - 查看未上传合同的需求"按钮专用（2026-08 新增，2026-08 修复）：条件跟
     * findByFilters 一致，只额外要求需求已完成（completedAt有值），再按这个品牌方/团队组合
     * 是"每次需求签一次合同"还是"一年签一次合同"分两套判断"是否已上传合同"：
     *   - 每次需求签一次合同：看需求自己的 contractLink 是否为空（口径对齐
     *     REQUIREMENT_CONTRACT_OVERDUE 提醒批次，只是不按"超出阈值天数"分档）。
     *   - 一年签一次合同：这类品牌方的合同不走需求自己的 contractLink 字段，而是在"红人管理"
     *     维护（InfluencerContract，按红人+品牌方+团队各自维护有效期区间），前端"合同链接"列
     *     也是这么判断的（见 RequirementListPage.vue 的 matchedInfluencerContract）——
     *     之前这里漏了这一种情况，直接把所有"一年签一次合同"的需求都排除在候选之外，
     *     导致这类品牌方下大量"红人管理处还没上传合同"的已完成需求，点这个按钮完全查不出来。
     *     现在改成查这个红人名下的合同，看有没有一条 (品牌方,团队) 匹配、且有效期区间覆盖
     *     这条需求的"需求月份"，没有匹配上就算"未上传合同"。
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<InfluencerRequirement> pageMissingContract(
            Long brandId, Long teamId, String accountName, String requirementMonth,
            String internalRequirementNo, String completedMonth,
            org.springframework.data.domain.Pageable pageable) {
        List<InfluencerRequirement> all = requirementRepo.findByFiltersNoPaging(
                brandId, teamId, accountName, requirementMonth, internalRequirementNo,
                completedMonth, pageable.getSort());
        List<Long> influencerIds = all.stream().map(InfluencerRequirement::getInfluencerId)
                .filter(java.util.Objects::nonNull).distinct().collect(Collectors.toList());
        Map<Long, List<InfluencerContract>> contractsByInfluencerId = influencerIds.isEmpty()
                ? new HashMap<>()
                : influencerContractRepo.findByInfluencerIdIn(influencerIds).stream()
                        .collect(Collectors.groupingBy(InfluencerContract::getInfluencerId));

        List<InfluencerRequirement> missing = new ArrayList<>();
        for (InfluencerRequirement r : all) {
            if (r.getCompletedAt() == null) continue;
            Brand brand = r.getBrandId() != null ? brandCache.findById(r.getBrandId()) : null;
            InfluencerTeam team = r.getTeamId() != null ? teamCache.findById(r.getTeamId()) : null;
            if (InfluencerTeam.isPerRequirementContract(brand, team)) {
                if (r.getContractLink() != null && !r.getContractLink().trim().isEmpty()) continue;
            } else {
                List<InfluencerContract> contracts = contractsByInfluencerId.getOrDefault(r.getInfluencerId(), Collections.emptyList());
                boolean matched = contracts.stream().anyMatch(c ->
                        java.util.Objects.equals(c.getBrandId(), r.getBrandId())
                        && java.util.Objects.equals(c.getTeamId(), r.getTeamId())
                        && monthOverlapsContractRange(r.getRequirementMonth(), c.getStartDate(), c.getEndDate()));
                if (matched) continue;
            }
            missing.add(r);
        }
        return paginateAndEnrich(missing, pageable);
    }

    /** 需求月份（yyyyMM）是否落在合同有效期区间内，跟前端 RequirementListPage.vue 的
     *  monthOverlapsContractRange 保持完全一致的判定口径 */
    private boolean monthOverlapsContractRange(String yyyyMM, Date startDate, Date endDate) {
        if (yyyyMM == null || yyyyMM.length() < 6 || startDate == null || endDate == null) return false;
        try {
            YearMonth ym = YearMonth.of(Integer.parseInt(yyyyMM.substring(0, 4)), Integer.parseInt(yyyyMM.substring(4, 6)));
            LocalDate monthStart = ym.atDay(1);
            LocalDate monthEnd = ym.atEndOfMonth();
            LocalDate start = toLocalDate(startDate);
            LocalDate end = toLocalDate(endDate);
            return !monthStart.isAfter(end) && !start.isAfter(monthEnd);
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return false;
        }
    }

    /** 不管传进来的实际是 java.util.Date 还是 java.sql.Date 都能正常工作，避免时区转换出偏差 */
    private LocalDate toLocalDate(Date date) {
        return new java.sql.Date(date.getTime()).toLocalDate();
    }

    /**
     * "需求列表页 - 查看未结款的需求"按钮专用（2026-08 新增，仅管理层可见，前端/后端都做了
     * 角色校验）：条件跟 findByFilters 一致，只额外要求这条需求的"结款状态"（settlementStatus）
     * 为空——即还没有任何红人结款记录关联过它，不要求需求必须100%完成（未完成的需求也会
     * 出现在这个列表里，方便管理层提前掌握全貌，只是排序上靠后）。
     *
     * 排序：需求完成进度=100%的排最前；组内再按"预计付款日"从近到远排（越紧迫越靠前），算不出
     * 预计付款日的排最后。这个"预计付款日"是估算值，供管理层判断优先级用，不是红人结款记录里
     * 那个由真实"对账日期"算出来的准确值，具体口径见 estimateDeadlineForSorting()。
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<InfluencerRequirement> pageUnsettled(
            Long brandId, Long teamId, String accountName, String requirementMonth,
            String internalRequirementNo, String completedMonth,
            org.springframework.data.domain.Pageable pageable) {
        List<InfluencerRequirement> all = requirementRepo.findByFiltersNoPaging(
                brandId, teamId, accountName, requirementMonth, internalRequirementNo,
                completedMonth, pageable.getSort());
        List<InfluencerRequirement> unsettled = all.stream()
                .filter(r -> r.getSettlementStatus() == null)
                .collect(Collectors.toList());

        List<String> nos = unsettled.stream().map(InfluencerRequirement::getInternalRequirementNo)
                .filter(java.util.Objects::nonNull).collect(Collectors.toList());
        Map<String, RequirementPaymentInfo> infoByNo = fetchPaymentInfo(nos);

        List<InfluencerRequirement> sorted = unsettled.stream()
                .sorted((a, b) -> {
                    RequirementPaymentInfo infoA = infoByNo.get(a.getInternalRequirementNo());
                    RequirementPaymentInfo infoB = infoByNo.get(b.getInternalRequirementNo());
                    boolean completeA = infoA != null && infoA.isComplete();
                    boolean completeB = infoB != null && infoB.isComplete();
                    if (completeA != completeB) return completeA ? -1 : 1;
                    Date deadlineA = estimateDeadlineForSorting(a, infoA);
                    Date deadlineB = estimateDeadlineForSorting(b, infoB);
                    long millisA = deadlineA != null ? deadlineA.getTime() : Long.MAX_VALUE;
                    long millisB = deadlineB != null ? deadlineB.getTime() : Long.MAX_VALUE;
                    return Long.compare(millisA, millisB);
                })
                .collect(Collectors.toList());

        return paginateAndEnrich(sorted, pageable);
    }

    /**
     * "查看未结款的需求"排序专用的"预计付款日"估算（2026-08 新增，跟用户确认过口径）：
     *   - 品牌方月结（MONTH_END）：这个需求还没有真正的结款记录，也就没有真实的"对账日期"，
     *     用"需求完成时间所在月份的月底最后一个工作日"估算对账日期（财务对账习惯上就是月底
     *     最后一个工作日做），"工作日"沿用 WorkdayUtil 现有定义（只有周六算休息日）。
     *   - 品牌方按红人成本阈值分档 + 需要invoice：直接复用需求完成时间起算，逻辑口径跟
     *     InfluencerPaymentService.computeCycleInfo() 需要invoice的分支完全一致。
     *   - 其余情况（品牌方未配置付款周期规则、需求还没100%完成导致 completedAt 为空、或者
     *     "按成本阈值分档但不需要invoice"这种目前没有实际品牌方使用的组合）：算不出预计付款日，
     *     返回 null，排序时当成"无穷大"排到最后。
     */
    private Date estimateDeadlineForSorting(InfluencerRequirement r, RequirementPaymentInfo info) {
        if (r.getBrandId() == null || r.getCompletedAt() == null) return null;
        Brand brand = brandCache.findById(r.getBrandId());
        if (brand == null || brand.getPaymentCycleType() == null) return null;

        if (brand.getPaymentCycleType() == PaymentCycleType.MONTH_END) {
            if (brand.getDaysAfterMonthEnd() == null) return null;
            LocalDate monthEnd = YearMonth.from(toLocalDate(r.getCompletedAt())).atEndOfMonth();
            LocalDate lastWorkday = WorkdayUtil.lastWorkdayOnOrBefore(monthEnd);
            return java.sql.Date.valueOf(lastWorkday.plusDays(brand.getDaysAfterMonthEnd()));
        }
        if (brand.getPaymentCycleType() == PaymentCycleType.COST_THRESHOLD && brand.requiresInvoiceUpload()) {
            if (info == null || info.payableCost == null || brand.getCostThresholdAmount() == null
                    || brand.getDaysWithinThreshold() == null || brand.getDaysAboveThreshold() == null) return null;
            int days = info.payableCost.compareTo(brand.getCostThresholdAmount()) <= 0
                    ? brand.getDaysWithinThreshold() : brand.getDaysAboveThreshold();
            return java.sql.Date.valueOf(toLocalDate(r.getCompletedAt()).plusDays(days));
        }
        return null;
    }

    /** pageMissingInvoice/pageMissingContract 共用：内存筛选完的结果手动分页，
     * 再批量补齐"需求完成进度"分子/"已建立跟踪记录数"（列表页展示用），避免逐条查库 */
    private org.springframework.data.domain.Page<InfluencerRequirement> paginateAndEnrich(
            List<InfluencerRequirement> filtered, org.springframework.data.domain.Pageable pageable) {
        int total = filtered.size();
        int start = Math.min((int) pageable.getOffset(), total);
        int end = Math.min(start + pageable.getPageSize(), total);
        List<InfluencerRequirement> pageContent = new ArrayList<>(filtered.subList(start, end));
        List<String> nos = pageContent.stream().map(InfluencerRequirement::getInternalRequirementNo).collect(Collectors.toList());
        Map<String, Integer> completedByNo = completedCountByNos(nos);
        Map<String, Integer> establishedByNo = establishedCountByNos(nos);
        for (InfluencerRequirement r : pageContent) {
            r.setCompletedCount(completedByNo.getOrDefault(r.getInternalRequirementNo(), 0));
            r.setEstablishedCount(establishedByNo.getOrDefault(r.getInternalRequirementNo(), 0));
        }
        return new org.springframework.data.domain.PageImpl<>(pageContent, pageable, total);
    }

    /** 需求列表页用：按 internalRequirementNo 批量算"需求完成进度"分子，避免逐条查库 */
    @Transactional(readOnly = true)
    public Map<String, Integer> completedCountByNos(List<String> nos) {
        List<String> nonNull = nos.stream().filter(java.util.Objects::nonNull).collect(Collectors.toList());
        if (nonNull.isEmpty()) return new HashMap<>();
        Map<String, Integer> result = new HashMap<>();
        for (Object[] row : trackingRepo.countCompletedByRequirementNos(nonNull)) {
            result.put((String) row[0], ((Long) row[1]).intValue());
        }
        return result;
    }

    /** 需求列表页"新建合作跟踪"按钮用：按 internalRequirementNo 批量算"已建立跟踪记录数"，避免逐条查库 */
    @Transactional(readOnly = true)
    public Map<String, Integer> establishedCountByNos(List<String> nos) {
        List<String> nonNull = nos.stream().filter(java.util.Objects::nonNull).collect(Collectors.toList());
        if (nonNull.isEmpty()) return new HashMap<>();
        Map<String, Integer> result = new HashMap<>();
        for (Object[] row : trackingRepo.countEstablishedByRequirementNos(nonNull)) {
            result.put((String) row[0], ((Long) row[1]).intValue());
        }
        return result;
    }

    /**
     * 需求维度的"结款相关"汇总信息：需要invoice的品牌方，红人结款模块（InfluencerPaymentService）
     * 和"待处理-进度提醒"里的红人合作跟踪临近结款（ProgressReminderService.runCollabPaymentDue）
     * 都要用同一套口径判断"这个需求能不能结款、阈值怎么分档、起算点是哪天"，2026-08 抽到这里
     * 共用一份实现，避免两边各自查一遍、后续改动时忘了同步导致口径又跑偏（之前就出过一次这个
     * 问题：红人结款那边先改成排除折损，这边提醒模块隔了几天才跟上）。
     */
    public static class RequirementPaymentInfo {
        public Integer completedCount;
        public Integer totalItemCount;
        /** 需求完成进度达到100%的那一刻，阈值分档结款周期天数从这天开始算 */
        public Date completedAt;
        /** 实际可结款成本：已发布(未结算)/已加入客户未结算列表/客户已结算 三个终态之和，不含折损 */
        public BigDecimal payableCost;
        public Integer delayedCount;
        public BigDecimal delayedCost;

        /** total>0 且 completed>=total 才算完成，口径跟"红人需求管理"列表页/前端 requirementComplete() 一致 */
        public boolean isComplete() {
            return totalItemCount != null && totalItemCount > 0
                    && completedCount != null && completedCount >= totalItemCount;
        }
    }

    /** 按 internalRequirementNo 批量取 {@link RequirementPaymentInfo}，避免逐条查库 */
    @Transactional(readOnly = true)
    public Map<String, RequirementPaymentInfo> fetchPaymentInfo(List<String> internalRequirementNos) {
        List<String> nos = internalRequirementNos.stream().filter(java.util.Objects::nonNull)
                .distinct().collect(Collectors.toList());
        if (nos.isEmpty()) return new HashMap<>();

        Map<String, RequirementPaymentInfo> result = new HashMap<>();
        for (InfluencerRequirement r : requirementRepo.findByInternalRequirementNoInAndIsDeletedFalse(nos)) {
            RequirementPaymentInfo info = new RequirementPaymentInfo();
            info.totalItemCount = r.getTotalItemCount();
            info.completedAt = r.getCompletedAt();
            result.put(r.getInternalRequirementNo(), info);
        }
        for (Object[] row : trackingRepo.countCompletedByRequirementNos(nos)) {
            RequirementPaymentInfo info = result.get((String) row[0]);
            if (info != null) info.completedCount = ((Long) row[1]).intValue();
        }
        for (Object[] row : trackingRepo.sumPayableCostByRequirementNos(nos)) {
            RequirementPaymentInfo info = result.get((String) row[0]);
            if (info != null) info.payableCost = (BigDecimal) row[1];
        }
        for (Object[] row : trackingRepo.sumDelayedByRequirementNos(nos)) {
            RequirementPaymentInfo info = result.get((String) row[0]);
            if (info != null) {
                info.delayedCount = ((Long) row[1]).intValue();
                info.delayedCost = (BigDecimal) row[2];
            }
        }
        return result;
    }

    /**
     * 维护 completedAt（供"Invoice逾期"提醒批次/红人结款阈值分档用）。由
     * CollaborationTrackingService 在每次某条关联记录的 progress 真正发生变化后调用；
     * 2026-08 起 save()（本类自己，编辑需求条目导致 totalItemCount 变化时）也会调用——
     * "是否100%完成"不只取决于关联记录的进度，也取决于需求自己的条目总数，两边任意一个变了
     * 都要重新判定一次，不能只覆盖前者。
     *
     * 2026-08 bug 修复：这里之前达到100%时把 completedAt 设成 new Date()（"系统检测到100%
     * 完成的那一刻"），但这个字段真正的语义早就订正成"该需求所有关联记录里最晚的视频发布
     * 时间"了（见下面 recomputeAllCompletedAt() 的说明）——只有那个一次性善后按钮改成了
     * 按这个新语义算，这个增量维护方法一直没跟着改，导致日常新增/完成需求时 completedAt
     * 仍然是"今天"，不是真实的最晚视频发布时间（比如今天触发了100%完成判定，但这条需求
     * 里最晚的视频其实是上个月发布的，completedAt 却显示成今天所在的月份）。现在改成
     * 跟 recomputeAllCompletedAt() 同一套查询/同一个"正确值"，取不到任何发布时间时
     * （理论上不会发生，因为进入终态必须先填视频发布时间，防御性兜底）才退回当前时间。
     *
     * 达到100%时：如果当前值为空、或者不等于这个正确值，就设置/订正成正确值（不只是补空，
     * 已经有值但不准确时也会被订正，比如某条记录的视频发布时间后来被改了）。没达到100%但
     * 当前有值（说明是被"进度倒退"审批通过拉回去的、或者需求条目被改多了）→ 清空。
     * internalRequirementNo 为空或查不到需求时直接忽略。
     */
    @Transactional
    public void refreshCompletedAt(String internalRequirementNo) {
        if (internalRequirementNo == null) return;
        InfluencerRequirement requirement = requirementRepo
                .findByInternalRequirementNoAndIsDeletedFalse(internalRequirementNo).orElse(null);
        if (requirement == null) return;
        int completed = completedCountByNos(java.util.Collections.singletonList(internalRequirementNo))
                .getOrDefault(internalRequirementNo, 0);
        int total = requirement.getTotalItemCount() != null ? requirement.getTotalItemCount() : 0;
        boolean isComplete = total > 0 && completed >= total;
        if (isComplete) {
            Date correctValue = trackingRepo
                    .maxPublishDateByRequirementNos(java.util.Collections.singletonList(internalRequirementNo))
                    .stream().findFirst().map(row -> (Date) row[1]).orElse(new Date());
            // 注意：不能用 .equals() 比较——completedAt 是 @Temporal(TIMESTAMP)，读出来是
            // java.sql.Timestamp；correctValue 来自 publishDate（@Temporal(DATE)），读出来是
            // java.sql.Date。Timestamp.equals() 只要参数不是 Timestamp 类型就恒返回 false
            // （哪怕毫秒值完全相同），用 .equals() 会导致已经正确的值被误判成"不等于"、每次
            // 都重新保存。改成比较 getTime()（都是"自 epoch 以来的毫秒数"）才是真正的等值比较。
            if (requirement.getCompletedAt() == null || requirement.getCompletedAt().getTime() != correctValue.getTime()) {
                requirement.setCompletedAt(correctValue);
                requirementRepo.save(requirement);
            }
        } else if (requirement.getCompletedAt() != null) {
            requirement.setCompletedAt(null);
            requirementRepo.save(requirement);
        }
    }

    /**
     * 维护"结款状态"（2026-08 新增）：不管品牌方付款周期类型，凡是有任意一条红人结款记录
     * 关联了这个内部需求编号下的红人合作跟踪记录，就算"已添加结款记录"；这些结款记录里只要
     * 有任意一条已经是"已付款"，就升级成"已付款"。判断关联关系走
     * CollaborationTracking.influencerPaymentId 这条真正的外键（trackingRepo.
     * findPaymentIdsByRequirementNo），不是靠 InfluencerPayment.involvedRequirementNos
     * 那个换行拼接的冗余文本字段去反查，更可靠。一条结款记录都没有时清空（null，前端展示"-"）。
     *
     * "已付款优先于待付款"只是防止历史遗留数据不一致时的兜底——正常情况下同一个需求的记录
     * 不会被拆分到多个结款批次里（见 InfluencerPaymentService.validateNoPartialRequirement()
     * 这条 2026-08 新增的硬性校验，选择涉及的红人视频项目时同一个需求的记录会作为整体一起
     * 勾选/取消）。
     *
     * 由 InfluencerPaymentService 在新建/编辑（调整了勾选的红人视频项目）/状态流转/删除
     * 结款记录时调用，对受影响的每一个 internalRequirementNo 各调一次。
     */
    @Transactional
    public void refreshSettlementStatus(String internalRequirementNo) {
        if (internalRequirementNo == null || internalRequirementNo.trim().isEmpty()) return;
        InfluencerRequirement requirement = requirementRepo
                .findByInternalRequirementNoAndIsDeletedFalse(internalRequirementNo).orElse(null);
        if (requirement == null) return;

        List<Long> paymentIds = trackingRepo.findPaymentIdsByRequirementNo(internalRequirementNo);
        RequirementSettlementStatus newStatus = null;
        if (!paymentIds.isEmpty()) {
            List<InfluencerPayment> payments = paymentRepo.findAllById(paymentIds).stream()
                    .filter(p -> !Boolean.TRUE.equals(p.getIsDeleted()))
                    .collect(Collectors.toList());
            boolean anyPaid = payments.stream().anyMatch(p -> p.getPaymentStatus() == InfluencerPaymentStatus.PAID);
            boolean anyPending = payments.stream().anyMatch(p -> p.getPaymentStatus() == InfluencerPaymentStatus.PENDING);
            if (anyPaid) newStatus = RequirementSettlementStatus.PAID;
            else if (anyPending) newStatus = RequirementSettlementStatus.ADDED_TO_PAYMENT;
        }
        if (!java.util.Objects.equals(requirement.getSettlementStatus(), newStatus)) {
            requirement.setSettlementStatus(newStatus);
            requirementRepo.save(requirement);
        }
    }

    /**
     * "重新计算需求完成时间"按钮专用（2026-08 新增，善后用，仅 ADMIN）：批量重新判定所有
     * 未删除需求的 completedAt。修复三类历史遗留问题：
     *   1）"存量记录关联需求"（linkLegacyTrackings）历史上曾经漏调 refreshCompletedAt()
     *      导致的遗留数据——关联时红人合作跟踪记录的视频项目进度已经达到"已发布（未结算）"
     *      及以后（完成条件本来就已经满足），但需求完成时间一直没被补上；这个漏洞本身已经
     *      在 linkLegacyTrackings() 里补上调用了，这里是给已经产生的历史数据一次性善后。
     *   2）"需求完成时间"的语义是"该需求所有关联记录里最晚的视频发布时间"，不是"系统检测到
     *      100%完成的那一刻"——已经有值但不等于这个"正确值"的记录也会被订正（不只是补空）。
     *      取不到任何发布时间时（理论上不会发生，防御性兜底）保留原逻辑，退回当前时间。
     *   3）2026-08 bug：refreshCompletedAt() 之前一直没跟上第2点这个语义订正，日常新增/
     *      完成需求时 completedAt 被设成"今天"而不是真实的最晚视频发布时间，导致看到的
     *      需求完成月份对不上系统里任何一条视频的实际发布月份。这个 bug 本身已经在
     *      refreshCompletedAt() 里修复，这里是给 bug 修复之前已经产生的历史脏数据一次性善后。
     * 正常使用不需要点这个（bug 修复后，正常的实时流转过程中 completedAt 已经靠
     * refreshCompletedAt() 增量维护成正确值了）——除非怀疑还有历史数据没订正过，才需要跑一次。
     */
    @Transactional
    public String recomputeAllCompletedAt() {
        List<InfluencerRequirement> all = requirementRepo.findByIsDeletedFalse();
        List<String> nos = all.stream().map(InfluencerRequirement::getInternalRequirementNo).collect(Collectors.toList());
        Map<String, Integer> completedByNo = completedCountByNos(nos);
        Map<String, Date> maxPublishDateByNo = new HashMap<>();
        for (Object[] row : trackingRepo.maxPublishDateByRequirementNos(
                nos.stream().filter(java.util.Objects::nonNull).collect(Collectors.toList()))) {
            maxPublishDateByNo.put((String) row[0], (Date) row[1]);
        }
        List<InfluencerRequirement> toSave = new ArrayList<>();
        int filledCount = 0, correctedCount = 0, clearedCount = 0;
        for (InfluencerRequirement r : all) {
            int completed = completedByNo.getOrDefault(r.getInternalRequirementNo(), 0);
            int total = r.getTotalItemCount() != null ? r.getTotalItemCount() : 0;
            boolean isComplete = total > 0 && completed >= total;
            if (isComplete) {
                Date correctValue = maxPublishDateByNo.getOrDefault(r.getInternalRequirementNo(), new Date());
                if (r.getCompletedAt() == null) {
                    r.setCompletedAt(correctValue);
                    toSave.add(r);
                    filledCount++;
                    // 同上——不能用 .equals()，Timestamp/Date 子类不对称比较会恒为 true，
                    // 导致每次点这个按钮都把已经正确的记录重新判定成"需要订正"，见 refreshCompletedAt() 的注释
                } else if (r.getCompletedAt().getTime() != correctValue.getTime()) {
                    r.setCompletedAt(correctValue);
                    toSave.add(r);
                    correctedCount++;
                }
            } else if (r.getCompletedAt() != null) {
                r.setCompletedAt(null);
                toSave.add(r);
                clearedCount++;
            }
        }
        if (!toSave.isEmpty()) requirementRepo.saveAll(toSave);
        return "共扫描 " + all.size() + " 条需求，补上 " + filledCount + " 条的需求完成时间"
                + (correctedCount > 0 ? "，订正 " + correctedCount + " 条（原时间不等于该需求里最晚的视频发布时间）" : "")
                + (clearedCount > 0 ? "，清空 " + clearedCount + " 条（进度倒退导致不再满足完成条件）" : "") + "。";
    }

    /**
     * 需求完成进度点击详情：这条需求下所有已关联的红人合作跟踪记录（含折损）。
     * "需求条目"这一列（第几个条目）不落库，现算：按 (videoType, platform, 两个单价)
     * 精确匹配到需求条目列表里的第几个（1-based，按条目创建顺序），同一个大需求下
     * 有很多条目时方便一眼看出某条记录具体对应哪个条目——不加这层匹配的话，同一
     * videoType/platform、只是单价不同的多个条目混在一起会分不清楚。
     */
    @Transactional(readOnly = true)
    public List<RequirementTrackingSummaryResponse> progressDetail(Long requirementId) {
        InfluencerRequirement requirement = requirementRepo.findByIdAndIsDeletedFalse(requirementId)
                .orElseThrow(() -> new RuntimeException("需求记录不存在：" + requirementId));
        List<InfluencerRequirementItem> items = itemRepo.findByRequirementIdOrderByIdAsc(requirement.getId());
        List<CollaborationTracking> linked =
                trackingRepo.findByInternalRequirementNoAndIsDeletedFalse(requirement.getInternalRequirementNo());
        List<RequirementTrackingSummaryResponse> result = new ArrayList<>();
        for (CollaborationTracking t : linked) {
            RequirementTrackingSummaryResponse r = new RequirementTrackingSummaryResponse();
            r.setTrackingId(t.getId());
            r.setInternalProjectNo(t.getInternalProjectNo());
            r.setVideoTypeLabel(t.getVideoType() != null ? t.getVideoType().getLabel() : null);
            r.setVideoType(t.getVideoType() != null ? t.getVideoType().name() : null);
            r.setPlatform(t.getPlatform());
            r.setDemandContent(t.getDemandContent());
            r.setProgressLabel(t.getProgress() != null ? t.getProgress().getLabel() : null);
            r.setProgress(t.getProgress() != null ? t.getProgress().name() : null);
            r.setPublishDate(t.getPublishDate());
            r.setItemIndex(matchItemIndex(items, t));
            r.setInfluencerUnitCostPrice(t.getInfluencerCost());
            r.setClientUnitPrice(t.getClientPrice());
            result.add(r);
        }
        return result;
    }

    /** 按 (videoType, platform, 两个单价) 精确匹配，找出这条记录对应需求条目列表里的第几个（1-based） */
    private Integer matchItemIndex(List<InfluencerRequirementItem> items, CollaborationTracking t) {
        String canonicalPlatform = canonicalTrackingPlatform(t.getPlatform());
        for (int i = 0; i < items.size(); i++) {
            InfluencerRequirementItem item = items.get(i);
            if (item.getVideoType() == t.getVideoType()
                    && java.util.Objects.equals(item.getPlatform(), canonicalPlatform)
                    && amountsEqual(item.getInfluencerUnitCostPrice(), t.getInfluencerCost())
                    && amountsEqual(item.getClientUnitPrice(), t.getClientPrice())) {
                return i + 1;
            }
        }
        return null;
    }

    public RequirementContentParseResponse parseContent(String content) {
        return contentParser.parse(content);
    }

    /**
     * "存量记录关联需求"第三步候选查询：这个红人名下还没关联任何需求的记录里，
     * 品牌方-团队/项目视频类型/合作平台/红人视频制作与发布成本/客户合作价格都跟需求（以及
     * 需求某个条目）匹配的。同一个红人可能对应多个品牌方-团队组合，只按红人筛会把其他
     * 品牌方-团队下巧合金额一致的记录也混进来，所以必须先卡死品牌方-团队，2026-07 加上。
     *
     * 2026-08 修复：之前这里只匹配 (videoType, platform, 两个单价) 到具体条目，没有排除
     * "该条目名额已经被占满"的情况——即使需求还没100%完成（比如条目里的其他视频还在实施中，
     * 没到"已发布"及以后），只要匹配到的这个条目本身已经被现有关联记录占满名额，继续把它的
     * 候选记录展示出来也没有意义，用户选中确认时必然会被 validateTrackingLinkage() 拒绝。
     * 现在改成复用 fulfilledCountByItemId() 同一套"名额校验"口径，提前把这些候选过滤掉，
     * 跟确认关联时（linkLegacyTrackings）的判定保持一致，不会出现"能选但选了必然报错"的记录。
     */
    @Transactional(readOnly = true)
    public List<LegacyTrackingCandidateResponse> findLegacyCandidates(Long influencerId, String internalRequirementNo) {
        InfluencerRequirement requirement = requirementRepo
                .findByInternalRequirementNoAndIsDeletedFalse(internalRequirementNo)
                .orElseThrow(() -> new RuntimeException("内部需求编号不存在：" + internalRequirementNo));
        if (!java.util.Objects.equals(requirement.getInfluencerId(), influencerId)) {
            throw new RuntimeException("该内部需求编号不属于这个红人");
        }
        List<InfluencerRequirementItem> items = itemRepo.findByRequirementIdOrderByIdAsc(requirement.getId());
        Map<Long, Integer> fulfilledByItemId = fulfilledCountByItemId(requirement);
        List<CollaborationTracking> unlinked =
                trackingRepo.findByInfluencerIdAndInternalRequirementNoIsNullAndIsDeletedFalse(influencerId);

        List<LegacyTrackingCandidateResponse> result = new ArrayList<>();
        for (CollaborationTracking t : unlinked) {
            if (!java.util.Objects.equals(t.getBrandId(), requirement.getBrandId())
                    || !java.util.Objects.equals(t.getTeamId(), requirement.getTeamId())) {
                continue;
            }
            String canonicalPlatform = canonicalTrackingPlatform(t.getPlatform());
            InfluencerRequirementItem matched = items.stream()
                    .filter(item -> item.getVideoType() == t.getVideoType()
                            && java.util.Objects.equals(item.getPlatform(), canonicalPlatform)
                            && amountsEqual(item.getInfluencerUnitCostPrice(), t.getInfluencerCost())
                            && amountsEqual(item.getClientUnitPrice(), t.getClientPrice()))
                    .findFirst().orElse(null);
            if (matched == null) continue;
            int fulfilled = fulfilledByItemId.getOrDefault(matched.getId(), 0);
            if (fulfilled >= matched.getVideoCount()) continue; // 该条目名额已满，不该出现在候选列表里
            LegacyTrackingCandidateResponse r = new LegacyTrackingCandidateResponse();
            r.setTrackingId(t.getId());
            r.setInternalProjectNo(t.getInternalProjectNo());
            r.setVideoTypeLabel(t.getVideoType() != null ? t.getVideoType().getLabel() : null);
            r.setPlatform(t.getPlatform());
            r.setInfluencerCost(t.getInfluencerCost());
            r.setClientPrice(t.getClientPrice());
            r.setPublishDate(t.getPublishDate());
            r.setDemandContent(t.getDemandContent());
            result.add(r);
        }
        return result;
    }

    private boolean amountsEqual(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return a == b;
        return a.compareTo(b) == 0;
    }

    /**
     * "存量记录关联需求"确认关联：整批在一个事务里逐条走 validateTrackingLinkage()
     * （红人/品牌方/团队/项目视频类型/合作平台/成本/价格是否精确匹配到某个具体条目、该条目
     * 还有没有剩余名额），任何一条不满足都抛异常、整批回滚——同一事务内前面几条已经落库的
     * 关联，后面几条的名额校验能看到，天然实现"这一批加起来超量也会被拦下"。
     */
    @Transactional
    public void linkLegacyTrackings(String internalRequirementNo, List<Long> trackingIds) {
        if (trackingIds == null || trackingIds.isEmpty()) {
            throw new RuntimeException("请至少选择一条红人合作跟踪记录");
        }
        for (Long trackingId : trackingIds) {
            CollaborationTracking t = trackingRepo.findByIdAndIsDeletedFalse(trackingId)
                    .orElseThrow(() -> new RuntimeException("跟踪记录不存在：" + trackingId));
            if (t.getInternalRequirementNo() != null) {
                throw new RuntimeException("记录 [" + t.getInternalProjectNo() + "] 已经关联了内部需求编号 ["
                        + t.getInternalRequirementNo() + "]，不能重复关联");
            }
            validateTrackingLinkage(internalRequirementNo, t.getInfluencerId(), t.getBrandId(), t.getTeamId(),
                    t.getVideoType(), t.getPlatform(), t.getInfluencerCost(), t.getClientPrice(), null);
            t.setInternalRequirementNo(internalRequirementNo);
            trackingRepo.save(t);
        }
        // 2026-08 修复：这里之前漏调 refreshCompletedAt()，导致存量记录关联需求后，即使关联的
        // 记录视频项目进度本身早就达到"已发布（未结算）"及以后（完成条件已经满足），"红人需求
        // 管理"的"需求完成时间"也不会被补上——只有后续这些记录的进度再变化一次时才会顺带触发
        // CollaborationTrackingService 里的调用点，间接被修正。整批关联完之后统一判定一次即可，
        // 不需要在循环内部每条都调用（一批关联的是同一个 internalRequirementNo）。
        refreshCompletedAt(internalRequirementNo);
    }

    @Transactional
    public void delete(Long id) {
        InfluencerRequirement requirement = requirementRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("需求记录不存在：" + id));
        List<CollaborationTracking> linked =
                trackingRepo.findByInternalRequirementNoAndIsDeletedFalse(requirement.getInternalRequirementNo());
        if (!linked.isEmpty()) {
            throw new RuntimeException("该需求已经有关联的红人合作跟踪记录，不能删除");
        }
        requirement.setIsDeleted(true);
        requirementRepo.save(requirement);
    }

    /**
     * 上传/修改 Invoice 链接：该品牌方不需要 invoice 时直接拒绝（正常情况下前端按钮已禁用，
     * 这里是后端兜底）；需要 invoice 时必须"需求完成进度"100%才允许。成功后级联把所有关联的
     * "红人合作跟踪"记录（按 internalRequirementNo）的红人结款进度更新：
     *   - "待红人发送invoice" -> "红人已提供invoice"；
     *   - "已纳入红人结款批次（缺少invoice）" -> "已纳入红人结款批次"（去掉"缺少invoice"标记，
     *     同时把 preBatchPaymentProgress 快照也同步更新成"红人已提供invoice"，避免这条记录
     *     以后被移出结款批次时，误恢复成纳入批次前的"待红人发送invoice"——那个快照是纳入批次
     *     那一刻的状态，invoice 补上之后已经不准确了）；
     *   - 已经是"已纳入红人结款批次"（没有缺invoice问题）的，不需要动。
     */
    @Transactional
    public InfluencerRequirement uploadInvoiceLink(Long requirementId, String invoiceLink) {
        InfluencerRequirement requirement = requirementRepo.findByIdAndIsDeletedFalse(requirementId)
                .orElseThrow(() -> new RuntimeException("需求记录不存在：" + requirementId));

        Brand brand = requirement.getBrandId() != null ? brandCache.findById(requirement.getBrandId()) : null;
        if (brand != null && !brand.requiresInvoiceUpload()) {
            throw new RuntimeException("该品牌方不涉及Invoice上传");
        }

        int completed = completedCountByNos(java.util.Collections.singletonList(requirement.getInternalRequirementNo()))
                .getOrDefault(requirement.getInternalRequirementNo(), 0);
        int total = requirement.getTotalItemCount() != null ? requirement.getTotalItemCount() : 0;
        if (total <= 0 || completed < total) {
            throw new RuntimeException("该需求尚未实施完成，还无需上传Invoice");
        }

        requirement.setInvoiceLink(invoiceLink);
        InfluencerRequirement saved = requirementRepo.save(requirement);

        List<CollaborationTracking> linked =
                trackingRepo.findByInternalRequirementNoAndIsDeletedFalse(requirement.getInternalRequirementNo());
        List<CollaborationTracking> toUpdate = new ArrayList<>();
        for (CollaborationTracking t : linked) {
            InfluencerPaymentProgress current = t.getInfluencerPaymentProgress();
            if (current == InfluencerPaymentProgress.INCLUDED_IN_PAYMENT_BATCH) {
                continue; // 已经在结款批次里且不缺invoice，不需要动
            }
            if (current == InfluencerPaymentProgress.INCLUDED_IN_PAYMENT_BATCH_MISSING_INVOICE) {
                t.setInfluencerPaymentProgress(InfluencerPaymentProgress.INCLUDED_IN_PAYMENT_BATCH);
                t.setPreBatchPaymentProgress(InfluencerPaymentProgress.INVOICE_PROVIDED);
            } else {
                t.setInfluencerPaymentProgress(InfluencerPaymentProgress.INVOICE_PROVIDED);
            }
            toUpdate.add(t);
        }
        if (!toUpdate.isEmpty()) trackingRepo.saveAll(toUpdate);

        return saved;
    }

    /**
     * 上传/修改合同链接（2026-07 新增）：只针对品牌方"每次需求签一次合同"（Brand.isPerRequirementContract()）
     * 的场景，任何时候都可以点击上传/修改，不像 invoice 那样要求需求先100%完成。品牌方"一年签一次
     * 合同"时这个接口直接拒绝——那种品牌方的合同改在"红人管理"维护（InfluencerContract），前端按钮
     * 正常情况下也不会出现，这里是后端兜底。跟 invoice 不同，合同上传不需要联动红人结款进度。
     */
    @Transactional
    public InfluencerRequirement uploadContractLink(Long requirementId, String contractLink) {
        InfluencerRequirement requirement = requirementRepo.findByIdAndIsDeletedFalse(requirementId)
                .orElseThrow(() -> new RuntimeException("需求记录不存在：" + requirementId));

        Brand brand = requirement.getBrandId() != null ? brandCache.findById(requirement.getBrandId()) : null;
        InfluencerTeam team = requirement.getTeamId() != null ? teamCache.findById(requirement.getTeamId()) : null;
        if (!InfluencerTeam.isPerRequirementContract(brand, team)) {
            throw new RuntimeException("该品牌方/团队是一年签一次合同，请在红人管理处上传");
        }

        requirement.setContractLink(contractLink);
        return requirementRepo.save(requirement);
    }

    /**
     * "红人合作跟踪"关联需求校验：内部需求编号存在、红人/品牌方/团队跟需求一致、
     * videoType+platform+两个单价精确匹配需求里的某一个条目且该条目还有剩余名额
     * （excludeTrackingId 排除自身，编辑已关联记录时不把自己算进"已占用"）。供
     * CollaborationTrackingService（表单保存/状态流转）和"存量记录关联需求"共用，任何一处
     * 不满足都抛异常，调用方决定怎么呈现给用户。
     *
     * 2026-07：单价也纳入匹配条件——同一需求内允许多个条目共用同一个 (videoType, platform)
     * 组合（价格不同，比如同样是"新AI素材"，一批单价$50一批单价$80），如果只按组合匹配、
     * 把这些同组合条目的名额加总算一个池子，会导致一条实际价格对应"条目A"的记录，占用的却是
     * "条目A+条目B"合并后的名额池，反过来又会让"已实施"数在两个条目上都算出同一个总数——
     * 也就是本该 5 条容量的条目和 1 条容量的条目，各自都显示"已实施 6 条"这种同一批记录被
     * 两个条目重复计入的错误。单价加入匹配条件后，只要两个条目没有完全相同的单价组合，
     * 就能唯一定位到该记录真正对应哪个条目，恢复按条目精确控制名额。
     */
    @Transactional(readOnly = true)
    public void validateTrackingLinkage(String internalRequirementNo, Long influencerId, Long brandId, Long teamId,
                                         VideoType videoType, String platform,
                                         BigDecimal influencerCost, BigDecimal clientPrice, Long excludeTrackingId) {
        InfluencerRequirement requirement = requirementRepo
                .findByInternalRequirementNoAndIsDeletedFalse(internalRequirementNo)
                .orElseThrow(() -> new RuntimeException("内部需求编号 [" + internalRequirementNo + "] 不存在"));

        if (!java.util.Objects.equals(requirement.getInfluencerId(), influencerId)) {
            throw new RuntimeException("内部需求编号 [" + internalRequirementNo + "] 关联的红人跟这条记录的红人不一致");
        }
        if (!java.util.Objects.equals(requirement.getBrandId(), brandId)) {
            throw new RuntimeException("内部需求编号 [" + internalRequirementNo + "] 关联的品牌方跟这条记录的品牌方不一致");
        }
        if (!java.util.Objects.equals(requirement.getTeamId(), teamId)) {
            throw new RuntimeException("内部需求编号 [" + internalRequirementNo + "] 关联的红人团队跟这条记录的红人团队不一致");
        }

        String canonicalPlatform = canonicalTrackingPlatform(platform);
        List<InfluencerRequirementItem> items = itemRepo.findByRequirementIdOrderByIdAsc(requirement.getId());
        InfluencerRequirementItem matched = items.stream()
                .filter(i -> i.getVideoType() == videoType && java.util.Objects.equals(i.getPlatform(), canonicalPlatform)
                        && amountsEqual(i.getInfluencerUnitCostPrice(), influencerCost)
                        && amountsEqual(i.getClientUnitPrice(), clientPrice))
                .findFirst().orElse(null);
        if (matched == null) {
            throw new RuntimeException("这条记录的项目视频类型/合作平台/成本/价格，在内部需求编号 [" + internalRequirementNo + "] 里找不到匹配的需求条目");
        }

        List<CollaborationTracking> linked = trackingRepo.findByInternalRequirementNoAndIsDeletedFalse(internalRequirementNo);
        long usedCount = linked.stream()
                .filter(t -> !java.util.Objects.equals(t.getId(), excludeTrackingId))
                .filter(t -> t.getVideoType() == videoType && java.util.Objects.equals(canonicalTrackingPlatform(t.getPlatform()), canonicalPlatform)
                        && amountsEqual(t.getInfluencerCost(), influencerCost)
                        && amountsEqual(t.getClientPrice(), clientPrice))
                .count();
        if (usedCount >= matched.getVideoCount()) {
            throw new RuntimeException("「" + videoType.getLabel() + "-" + canonicalPlatform.replace("\n", "、")
                    + "」这个需求条目已经没有剩余名额（" + matched.getVideoCount() + "条已全部安排）");
        }
    }

    /**
     * "新建跟踪"批量新建专用的提前校验：按 (内部需求编号 + 匹配到的具体条目) 分组统计这一批
     * 一共想新建多少条，跟条目剩余名额比较，提前给出精确报错——不这么做的话，只能等到
     * doSave() 逐条走到序号靠后的某一条时才会命中 validateTrackingLinkage() 里的名额校验，
     * 报错文案只知道"这一条超了"，不知道"这一批一共想建几条、超了多少"，不够精确。
     * 没有关联内部需求编号的请求跳过，交给 doSave() 里既有的其他校验处理。
     */
    @Transactional(readOnly = true)
    public void validateBatchLinkage(List<CollaborationTrackingRequest> reqs) {
        Map<String, List<CollaborationTrackingRequest>> byRequirementNo = reqs.stream()
                .filter(r -> r.getInternalRequirementNo() != null && !r.getInternalRequirementNo().trim().isEmpty())
                .collect(Collectors.groupingBy(CollaborationTrackingRequest::getInternalRequirementNo));

        for (Map.Entry<String, List<CollaborationTrackingRequest>> entry : byRequirementNo.entrySet()) {
            String requirementNo = entry.getKey();
            InfluencerRequirement requirement = requirementRepo
                    .findByInternalRequirementNoAndIsDeletedFalse(requirementNo)
                    .orElseThrow(() -> new RuntimeException("内部需求编号 [" + requirementNo + "] 不存在"));
            List<InfluencerRequirementItem> items = itemRepo.findByRequirementIdOrderByIdAsc(requirement.getId());
            List<CollaborationTracking> linked = trackingRepo.findByInternalRequirementNoAndIsDeletedFalse(requirementNo);

            // 按匹配到的具体条目分组，统计这一批试图新建几条
            Map<InfluencerRequirementItem, Integer> attemptCountByItem = new HashMap<>();
            for (CollaborationTrackingRequest req : entry.getValue()) {
                String canonicalPlatform = canonicalTrackingPlatform(req.getPlatform());
                InfluencerRequirementItem matched = items.stream()
                        .filter(i -> i.getVideoType() == req.getVideoType()
                                && java.util.Objects.equals(i.getPlatform(), canonicalPlatform)
                                && amountsEqual(i.getInfluencerUnitCostPrice(), req.getInfluencerCost())
                                && amountsEqual(i.getClientUnitPrice(), req.getClientPrice()))
                        .findFirst().orElse(null);
                // 找不到匹配条目时不在这里报错，交给 doSave() 里的 validateTrackingLinkage() 精确报错
                if (matched != null) attemptCountByItem.merge(matched, 1, Integer::sum);
            }

            for (Map.Entry<InfluencerRequirementItem, Integer> itemEntry : attemptCountByItem.entrySet()) {
                InfluencerRequirementItem item = itemEntry.getKey();
                int attemptCount = itemEntry.getValue();
                long usedCount = linked.stream()
                        .filter(t -> t.getVideoType() == item.getVideoType()
                                && java.util.Objects.equals(canonicalTrackingPlatform(t.getPlatform()), item.getPlatform())
                                && amountsEqual(t.getInfluencerCost(), item.getInfluencerUnitCostPrice())
                                && amountsEqual(t.getClientPrice(), item.getClientUnitPrice()))
                        .count();
                long remaining = item.getVideoCount() - usedCount;
                if (attemptCount > remaining) {
                    throw new RuntimeException("本次试图新建" + attemptCount + "条「" + item.getVideoType().getLabel()
                            + "-" + item.getPlatform().replace("\n", "、") + "」类型的记录，超出了该需求条目"
                            + remaining + "条的名额限制（该条目共" + item.getVideoCount() + "条，已安排" + usedCount + "条）");
                }
            }
        }
    }
}
