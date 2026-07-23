package com.lusuoria.settlement.service.impl;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.InfluencerTeamCache;
import com.lusuoria.settlement.dto.request.InfluencerRequirementItemRequest;
import com.lusuoria.settlement.dto.request.InfluencerRequirementRequest;
import com.lusuoria.settlement.dto.response.InfluencerRequirementItemResponse;
import com.lusuoria.settlement.dto.response.LegacyTrackingCandidateResponse;
import com.lusuoria.settlement.dto.response.RequirementContentParseResponse;
import com.lusuoria.settlement.dto.response.RequirementTrackingSummaryResponse;
import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.entity.InfluencerBrandTeam;
import com.lusuoria.settlement.entity.InfluencerRequirement;
import com.lusuoria.settlement.entity.InfluencerRequirementItem;
import com.lusuoria.settlement.entity.InfluencerTeam;
import com.lusuoria.settlement.enums.InfluencerPaymentProgress;
import com.lusuoria.settlement.enums.VideoType;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import com.lusuoria.settlement.repository.InfluencerBrandTeamRepository;
import com.lusuoria.settlement.repository.InfluencerRepository;
import com.lusuoria.settlement.repository.InfluencerRequirementItemRepository;
import com.lusuoria.settlement.repository.InfluencerRequirementRepository;
import com.lusuoria.settlement.util.MultiValueUtil;
import com.lusuoria.settlement.util.RequirementContentParser;
import com.lusuoria.settlement.util.RequirementNoAllocator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
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
    @Autowired private RequirementNoAllocator noAllocator;
    @Autowired private RequirementContentParser contentParser;
    @Autowired private CollaborationTrackingRepository trackingRepo;

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

        boolean isNew = requirement.getId() == null;

        applyItems(requirement, req.getItems());

        if (isNew) {
            String brandName = requirement.getBrand() != null ? requirement.getBrand().getName() : null;
            String teamName = requirement.getTeam() != null ? requirement.getTeam().getName() : null;
            requirement.setInternalRequirementNo(
                    noAllocator.allocate(brandName, teamName, requirement.getRequirementMonth(), influencer.getAccountName()));
        }

        return requirementRepo.save(requirement);
    }

    /**
     * 增量协调条目：按 id 匹配已有条目做更新，没有 id 的是新增，请求里没出现的已有条目视为删除——
     * 但已经有关联的合作跟踪记录（fulfilledCount > 0）的条目不允许删除/改动 videoType 或
     * platform（数量/单价允许改，因为不影响"这是哪个组合"的判定），保证已经实施的记录不会
     * 变成孤儿。同一需求内 (videoType, 排序后platform) 允许重复（2026-07 取消唯一限制——
     * 同样的视频类型/平台组合，实际上可能有不同的视频单价，需要拆成多个条目分别记录），
     * validateTrackingLinkage 里做名额校验时会把同组合的多个条目名额加总。
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
                        || !java.util.Objects.equals(item.getPlatform(), platform))) {
                    throw new RuntimeException("该条目已经有关联的红人合作跟踪记录，不能修改项目视频类型/合作平台");
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

    /** 某个需求下每个条目 id 已经关联了多少条红人合作跟踪记录（不看 progress 状态） */
    private Map<Long, Integer> fulfilledCountByItemId(InfluencerRequirement requirement) {
        List<CollaborationTracking> linked =
                trackingRepo.findByInternalRequirementNoAndIsDeletedFalse(requirement.getInternalRequirementNo());
        Map<Long, Integer> result = new HashMap<>();
        for (InfluencerRequirementItem item : requirement.getItems()) {
            if (item.getId() == null) continue;
            int count = (int) linked.stream()
                    .filter(t -> t.getVideoType() == item.getVideoType()
                            && java.util.Objects.equals(canonicalTrackingPlatform(t.getPlatform()), item.getPlatform()))
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

    /** "关联红人需求"选择器第一步：某个红人名下"需求完成进度"未满的需求 */
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
            if (completed < total) incomplete.add(r);
        }
        return incomplete;
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

    /**
     * 维护 completedAt（需求完成进度达到100%的时间，2026-07 新增，供"Invoice逾期"提醒批次用）。
     * 由 CollaborationTrackingService 在每次某条关联记录的 progress 真正发生变化后调用。
     * 达到100%且当前为空 → 设置成当前时间；没达到100%但当前有值（说明是被"进度倒退"审批
     * 通过拉回去的）→ 清空。internalRequirementNo 为空或查不到需求时直接忽略。
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
        if (isComplete && requirement.getCompletedAt() == null) {
            requirement.setCompletedAt(new Date());
            requirementRepo.save(requirement);
        } else if (!isComplete && requirement.getCompletedAt() != null) {
            requirement.setCompletedAt(null);
            requirementRepo.save(requirement);
        }
    }

    /** 需求完成进度点击详情：这条需求下所有已关联的红人合作跟踪记录（含折损） */
    @Transactional(readOnly = true)
    public List<RequirementTrackingSummaryResponse> progressDetail(Long requirementId) {
        InfluencerRequirement requirement = requirementRepo.findByIdAndIsDeletedFalse(requirementId)
                .orElseThrow(() -> new RuntimeException("需求记录不存在：" + requirementId));
        List<CollaborationTracking> linked =
                trackingRepo.findByInternalRequirementNoAndIsDeletedFalse(requirement.getInternalRequirementNo());
        List<RequirementTrackingSummaryResponse> result = new ArrayList<>();
        for (CollaborationTracking t : linked) {
            RequirementTrackingSummaryResponse r = new RequirementTrackingSummaryResponse();
            r.setTrackingId(t.getId());
            r.setInternalProjectNo(t.getInternalProjectNo());
            r.setVideoTypeLabel(t.getVideoType() != null ? t.getVideoType().getLabel() : null);
            r.setPlatform(t.getPlatform());
            r.setDemandContent(t.getDemandContent());
            r.setProgressLabel(t.getProgress() != null ? t.getProgress().getLabel() : null);
            result.add(r);
        }
        return result;
    }

    public RequirementContentParseResponse parseContent(String content) {
        return contentParser.parse(content);
    }

    /**
     * "存量记录关联需求"第三步候选查询：这个红人名下还没关联任何需求的记录里，
     * 品牌方-团队/项目视频类型/合作平台/红人视频制作与发布成本/客户合作价格都跟需求（以及
     * 需求某个条目）匹配的。同一个红人可能对应多个品牌方-团队组合，只按红人筛会把其他
     * 品牌方-团队下巧合金额一致的记录也混进来，所以必须先卡死品牌方-团队，2026-07 加上。
     * 只是筛出候选给用户挑选，真正的名额/一致性校验在确认关联时（linkLegacyTrackings）走
     * validateTrackingLinkage，这里不重复做名额判断。
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
        List<CollaborationTracking> unlinked =
                trackingRepo.findByInfluencerIdAndInternalRequirementNoIsNullAndIsDeletedFalse(influencerId);

        List<LegacyTrackingCandidateResponse> result = new ArrayList<>();
        for (CollaborationTracking t : unlinked) {
            if (!java.util.Objects.equals(t.getBrandId(), requirement.getBrandId())
                    || !java.util.Objects.equals(t.getTeamId(), requirement.getTeamId())) {
                continue;
            }
            String canonicalPlatform = canonicalTrackingPlatform(t.getPlatform());
            boolean matched = items.stream().anyMatch(item ->
                    item.getVideoType() == t.getVideoType()
                    && java.util.Objects.equals(item.getPlatform(), canonicalPlatform)
                    && amountsEqual(item.getInfluencerUnitCostPrice(), t.getInfluencerCost())
                    && amountsEqual(item.getClientUnitPrice(), t.getClientPrice()));
            if (!matched) continue;
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
     * （红人/品牌方/团队/项目视频类型/合作平台是否匹配、该需求条目还有没有剩余名额），
     * 任何一条不满足都抛异常、整批回滚——同一事务内前面几条已经落库的关联，后面几条的
     * 名额校验能看到，天然实现"这一批加起来超量也会被拦下"。
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
                    t.getVideoType(), t.getPlatform(), null);
            t.setInternalRequirementNo(internalRequirementNo);
            trackingRepo.save(t);
        }
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
     * "红人合作跟踪"记录（按 internalRequirementNo）的红人结款进度改成"红人已提供invoice"——
     * 已经纳入结款批次的记录（isSystemManagedOnly）跳过，不破坏批次联动。
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
            if (t.getInfluencerPaymentProgress() != null && t.getInfluencerPaymentProgress().isSystemManagedOnly()) {
                continue;
            }
            t.setInfluencerPaymentProgress(InfluencerPaymentProgress.INVOICE_PROVIDED);
            toUpdate.add(t);
        }
        if (!toUpdate.isEmpty()) trackingRepo.saveAll(toUpdate);

        return saved;
    }

    /**
     * "红人合作跟踪"关联需求校验：内部需求编号存在、红人/品牌方/团队跟需求一致、
     * videoType+platform 匹配需求里的某个条目且该条目还有剩余名额（excludeTrackingId 排除自身，
     * 编辑已关联记录时不把自己算进"已占用"）。供 CollaborationTrackingService（表单保存/
     * 状态流转）和 Excel 导入共用，任何一处不满足都抛异常，调用方决定怎么呈现给用户。
     */
    @Transactional(readOnly = true)
    public void validateTrackingLinkage(String internalRequirementNo, Long influencerId, Long brandId, Long teamId,
                                         VideoType videoType, String platform, Long excludeTrackingId) {
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
        // 同一 (videoType, platform) 组合允许拆成多个条目（比如同组合不同视频单价），
        // 名额校验按组合汇总所有匹配条目的 videoCount 之和，不再假设组合唯一
        List<InfluencerRequirementItem> matched = items.stream()
                .filter(i -> i.getVideoType() == videoType && java.util.Objects.equals(i.getPlatform(), canonicalPlatform))
                .collect(Collectors.toList());
        if (matched.isEmpty()) {
            throw new RuntimeException("这条记录的项目视频类型/合作平台，在内部需求编号 [" + internalRequirementNo + "] 里找不到匹配的需求条目");
        }
        int totalCapacity = matched.stream().mapToInt(InfluencerRequirementItem::getVideoCount).sum();

        List<CollaborationTracking> linked = trackingRepo.findByInternalRequirementNoAndIsDeletedFalse(internalRequirementNo);
        long usedCount = linked.stream()
                .filter(t -> !java.util.Objects.equals(t.getId(), excludeTrackingId))
                .filter(t -> t.getVideoType() == videoType && java.util.Objects.equals(canonicalTrackingPlatform(t.getPlatform()), canonicalPlatform))
                .count();
        if (usedCount >= totalCapacity) {
            throw new RuntimeException("「" + videoType.getLabel() + "-" + canonicalPlatform.replace("\n", "、")
                    + "」这个需求条目已经没有剩余名额（" + totalCapacity + "条已全部安排）");
        }
    }
}
