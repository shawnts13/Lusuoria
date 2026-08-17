package com.lusuoria.settlement.service.impl;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.InfluencerTeamCache;
import com.lusuoria.settlement.dto.request.InfluencerPaymentRequest;
import com.lusuoria.settlement.dto.request.InfluencerPaymentStatusRequest;
import com.lusuoria.settlement.dto.response.PaymentCandidateItem;
import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.entity.ExchangeRateCache;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.entity.InfluencerPayment;
import com.lusuoria.settlement.entity.InfluencerPaymentTeam;
import com.lusuoria.settlement.entity.InfluencerTeam;
import com.lusuoria.settlement.enums.InfluencerPaymentProgress;
import com.lusuoria.settlement.enums.InfluencerPaymentStatus;
import com.lusuoria.settlement.enums.PaymentCycleType;
import com.lusuoria.settlement.repository.BrandRepository;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import com.lusuoria.settlement.repository.ExchangeRateCacheRepository;
import com.lusuoria.settlement.repository.InfluencerPaymentRepository;
import com.lusuoria.settlement.repository.InfluencerPaymentTeamRepository;
import com.lusuoria.settlement.repository.InfluencerRepository;
import com.lusuoria.settlement.repository.InfluencerTeamRepository;
import com.lusuoria.settlement.util.PaymentNoGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 红人结款 - 业务逻辑。
 *
 * 权限判定（PaymentAccessUtil.canView/canManage）在 Controller 层做，这里只管业务规则，
 * 跟 CollaborationTrackingService/ProgressReminderService 的分工一致。
 */
@Service
public class InfluencerPaymentService {

    private static final Logger log = LoggerFactory.getLogger(InfluencerPaymentService.class);

    @Autowired private InfluencerPaymentRepository paymentRepo;
    @Autowired private InfluencerPaymentTeamRepository paymentTeamRepo;
    @Autowired private CollaborationTrackingRepository trackingRepo;
    @Autowired private BrandRepository brandRepo;
    @Autowired private InfluencerTeamRepository teamRepo;
    @Autowired private InfluencerRepository influencerRepo;
    @Autowired private InfluencerRequirementService requirementService;
    @Autowired private ExchangeRateCacheRepository exchangeRateCacheRepo;
    @Autowired private com.lusuoria.settlement.config.ExchangeRateLookupCache rateLookupCache;
    @Autowired private BrandCache brandCache;
    @Autowired private InfluencerTeamCache teamCache;
    @Autowired private PaymentNoGenerator paymentNoGenerator;

    /**
     * 一次性回填"是否涉及公对公发票"快照（2026-08 新增，随 InfluencerPayment.involvesCorporateInvoice
     * 这个字段一起上线）：这个字段上线之前创建的存量结款记录（待付款+已付款都有）是 null，
     * 回填口径是"用当前的品牌方/团队配置现算"——这是唯一能拿到的值，没有更早的历史配置可以
     * 参照，见该字段的类注释。启动时自动跑，只处理字段还是 null 的行（findByInvolvesCorporateInvoiceIsNull），
     * 处理过一次之后这些行就不会再被这条查询选中，天然幂等，可以放心每次启动都执行，不需要
     * 手动触发、也不需要以后再把这段代码删掉。
     *
     * 故意不加 @Transactional：@PostConstruct 回调是容器在这个 bean 完成初始化的过程中直接调用
     * 原始实例的方法，此时 @Transactional 的动态代理还没包上去，加了也不会生效（反而误导）。
     * 这里唯一真正落库的操作是最后一行 paymentRepo.saveAll()——Spring Data JPA 的 saveAll()
     * 本身就是一次独立的事务性调用，这一批回填要么整体成功要么整体失败，不需要额外包一层。
     */
    @PostConstruct
    public void backfillInvolvesCorporateInvoiceSnapshot() {
        List<InfluencerPayment> toBackfill = paymentRepo.findByInvolvesCorporateInvoiceIsNull();
        if (toBackfill.isEmpty()) return;
        attachTeamIds(toBackfill);
        for (InfluencerPayment p : toBackfill) {
            Brand brand = brandCache.findById(p.getBrandId());
            p.setInvolvesCorporateInvoice(resolveInvolvesCorporateInvoice(brand, p.getTeamIds()));
        }
        paymentRepo.saveAll(toBackfill);
        log.info("已回填 {} 条红人结款记录的\"是否涉及公对公发票\"快照", toBackfill.size());
    }

    // ============ 查询：选择弹窗 ============

    /**
     * @param teamIds 要包含的团队 id（不含"不选团队"这一项，那个用 includeNoTeam 单独表示）
     */
    @Transactional(readOnly = true)
    public List<PaymentCandidateItem> listCandidates(
            Long brandId, List<Long> teamIds, boolean includeNoTeam, Date reconcileDate) {
        Brand brand = brandCache.findById(brandId);
        if (brand == null) throw new RuntimeException("品牌方不存在");
        // JPQL 的 IN 子句不能绑定空集合，传个占位不存在的 id，交给 includeNoTeam 单独决定是否命中"没有团队"的记录
        List<Long> safeTeamIds = (teamIds != null && !teamIds.isEmpty()) ? teamIds : Collections.singletonList(-1L);
        List<CollaborationTracking> candidates = trackingRepo.findPaymentCandidatesByTeams(brandId, safeTeamIds, includeNoTeam);
        return buildItems(candidates, brand, reconcileDate);
    }

    /** 批量填充结款记录的"涉及团队"瞬态字段（列表/详情/导出用），避免逐条查询 */
    public void attachTeamIds(List<InfluencerPayment> payments) {
        if (payments.isEmpty()) return;
        List<Long> paymentIds = payments.stream().map(InfluencerPayment::getId).collect(Collectors.toList());
        Map<Long, List<Long>> byPaymentId = new HashMap<>();
        for (InfluencerPaymentTeam row : paymentTeamRepo.findByInfluencerPaymentIdIn(paymentIds)) {
            byPaymentId.computeIfAbsent(row.getInfluencerPaymentId(), k -> new ArrayList<>()).add(row.getTeamId());
        }
        for (InfluencerPayment p : payments) {
            p.setTeamIds(byPaymentId.getOrDefault(p.getId(), Collections.emptyList()));
        }
    }

    @Transactional(readOnly = true)
    public List<PaymentCandidateItem> listItems(Long paymentId) {
        InfluencerPayment payment = paymentRepo.findByIdAndIsDeletedFalse(paymentId)
                .orElseThrow(() -> new RuntimeException("结款记录不存在"));
        Brand brand = brandCache.findById(payment.getBrandId());
        List<CollaborationTracking> items = trackingRepo.findByInfluencerPaymentIdAndIsDeletedFalse(paymentId);
        Map<Long, CollaborationTracking> byId = new HashMap<>();
        for (CollaborationTracking t : items) byId.put(t.getId(), t);

        List<PaymentCandidateItem> result = buildItems(items, brand, null);
        // 已纳入批次的记录 progress 恒为 INCLUDED_IN_PAYMENT_BATCH，用快照值判断纳入时是不是还没给invoice。
        // buildItems 内部会重新排序，不能假设 result 跟 items 顺序一一对应，按 trackingId 关联查找
        for (PaymentCandidateItem item : result) {
            CollaborationTracking t = byId.get(item.getTrackingId());
            InfluencerPaymentProgress preBatch = t != null ? t.getPreBatchPaymentProgress() : null;
            item.setInvoiceWarning(preBatch == InfluencerPaymentProgress.PENDING_INVOICE);
            item.setPaymentProgressLabel(preBatch != null ? preBatch.getLabel() : null);
            item.setPaymentProgress(preBatch != null ? preBatch.name() : null);
        }
        return result;
    }

    private List<PaymentCandidateItem> buildItems(List<CollaborationTracking> list, Brand brand, Date reconcileDate) {
        if (list.isEmpty()) return new ArrayList<>();

        Set<Long> influencerIds = new HashSet<>();
        for (CollaborationTracking t : list) if (t.getInfluencerId() != null) influencerIds.add(t.getInfluencerId());
        Map<Long, String> accountNameById = new HashMap<>();
        if (!influencerIds.isEmpty()) {
            for (Influencer inf : influencerRepo.findAllById(influencerIds)) {
                accountNameById.put(inf.getId(), inf.getAccountName());
            }
        }

        List<String> requirementNos = list.stream().map(CollaborationTracking::getInternalRequirementNo)
                .collect(Collectors.toList());
        Map<String, InfluencerRequirementService.RequirementPaymentInfo> requirementByNo =
                requirementService.fetchPaymentInfo(requirementNos);

        YearMonth reconcileMonth = reconcileDate != null ? YearMonth.from(toLocalDate(reconcileDate)) : null;

        List<PaymentCandidateItem> result = new ArrayList<>();
        for (CollaborationTracking t : list) {
            PaymentCandidateItem item = new PaymentCandidateItem();
            item.setTrackingId(t.getId());
            item.setInternalProjectNo(t.getInternalProjectNo());
            item.setBrandName(brand != null ? brand.getName() : null);
            InfluencerTeam team = t.getTeamId() != null ? teamCache.findById(t.getTeamId()) : null;
            item.setTeamName(team != null ? team.getName() : null);
            item.setTeamId(t.getTeamId());
            item.setAccountName(accountNameById.get(t.getInfluencerId()));
            item.setInternalRequirementNo(t.getInternalRequirementNo());
            item.setDemandContent(t.getDemandContent());
            item.setInfluencerCost(t.getInfluencerCost());
            item.setProgressLabel(t.getProgress() != null ? t.getProgress().getLabel() : null);
            item.setProgress(t.getProgress() != null ? t.getProgress().name() : null);
            item.setPaymentProgressLabel(t.getInfluencerPaymentProgress() != null ? t.getInfluencerPaymentProgress().getLabel() : null);
            item.setPaymentProgress(t.getInfluencerPaymentProgress() != null ? t.getInfluencerPaymentProgress().name() : null);
            item.setPublishDate(t.getPublishDate());
            item.setInvoiceWarning(t.getInfluencerPaymentProgress() == InfluencerPaymentProgress.PENDING_INVOICE);

            InfluencerRequirementService.RequirementPaymentInfo requirement = t.getInternalRequirementNo() != null
                    ? requirementByNo.get(t.getInternalRequirementNo()) : null;
            if (requirement != null) {
                item.setRequirementCompletedCount(requirement.completedCount);
                item.setRequirementTotalItemCount(requirement.totalItemCount);
                item.setRequirementPayableCost(requirement.payableCost);
                item.setRequirementCompletedAt(requirement.completedAt);
                item.setRequirementDelayedCount(requirement.delayedCount);
                item.setRequirementDelayedCost(requirement.delayedCost);
            }

            if (brand != null) {
                CycleInfo cycleInfo = computeCycleInfo(t, brand, requirement);
                item.setCycleDays(cycleInfo.cycleDays);
                item.setDeadlineDate(cycleInfo.deadlineDate);
            }

            boolean defaultChecked = false;
            if (brand != null && brand.getPaymentCycleType() == PaymentCycleType.MONTH_END
                    && reconcileMonth != null && t.getPublishDate() != null) {
                defaultChecked = YearMonth.from(toLocalDate(t.getPublishDate())).equals(reconcileMonth);
            }
            item.setDefaultChecked(defaultChecked);

            result.add(item);
        }
        // 默认按红人社媒完整名字排序，方便管理层按红人扫视整批候选记录
        // （前端另外支持按这个维度筛选，筛选前的默认顺序也是这个；需要invoice+按需求分组选择的
        // 品牌方，前端会按需求编号重新分组排序，这里的默认顺序只是兜底）
        result.sort(Comparator.comparing(PaymentCandidateItem::getAccountName,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }

    private static class CycleInfo {
        Integer cycleDays;
        Date deadlineDate;
    }

    /**
     * 结款周期/最迟结款日计算，复用 ProgressReminderService 里已验证过的两段公式
     * （COST_THRESHOLD 按单笔成本分档、MONTH_END 按发布月份月底+固定天数），
     * 只是从"按品牌+月份汇总"改成套到每一行候选记录上。
     *
     * 2026-08 起：需要invoice的品牌方（brand.requiresInvoiceUpload()==true）走 COST_THRESHOLD
     * 时，改成按"这条记录关联的需求"的实际可结款成本（requirement.payableCost，已排除折损）
     * 分档、从"需求完成进度达到100%的时间"（requirement.completedAt）起算天数——因为这类品牌方
     * 一次结款只能对应一个需求（一张invoice），阈值判断/结款周期理应看整个需求而不是单笔视频。
     * requirement 为 null（没关联需求，或还没100%完成导致 completedAt 还是空）时退化返回空信息，
     * 跟"品牌未配置周期规则"一样在候选列表里显示"—"。不需要invoice的 COST_THRESHOLD 品牌方
     * （目前没有，但字段本身允许）保留原来按单笔成本+发布时间计算，行为不变。
     *
     * 2026-08 修正：这里最初直接用 InfluencerRequirement.totalInfluencerCost（需求创建时按
     * 单价×数量算的计划总成本），但那个值不会因为某条关联记录后来被判"折损"而减少——折损代表
     * 那笔视频不会真正付款，用计划总成本算阈值/预计付款日会算多。现在改成从
     * InfluencerRequirementService.RequirementPaymentInfo.payableCost（底层是
     * sumPayableCostByRequirementNos，只加总非折损的终态记录）取值，天然排除折损部分。折损
     * 条目本身也不会出现在"候选列表"里（红人结款进度恒为空，见
     * CollaborationProgress.allowsPaymentProgress()），所以"应付金额"这类由勾选记录直接加总
     * 的字段本来就没有这个问题，出问题的只有这个阈值判断。
     * 2026-08 起 ProgressReminderService 的"红人合作跟踪临近结款"（COLLAB_PAYMENT_DUE）
     * 需要invoice的分支也已经改用同一个 InfluencerRequirementService.fetchPaymentInfo()，
     * 两边口径保证一致，不要再各自维护一份需求成本聚合逻辑。
     */
    private CycleInfo computeCycleInfo(CollaborationTracking t, Brand brand,
                                        InfluencerRequirementService.RequirementPaymentInfo requirement) {
        CycleInfo info = new CycleInfo();
        if (brand.getPaymentCycleType() == null) return info;

        if (brand.getPaymentCycleType() == PaymentCycleType.COST_THRESHOLD) {
            if (brand.getCostThresholdAmount() == null || brand.getDaysWithinThreshold() == null
                    || brand.getDaysAboveThreshold() == null) return info;
            if (brand.requiresInvoiceUpload()) {
                if (requirement == null || requirement.payableCost == null || requirement.completedAt == null) return info;
                int cycleDays = requirement.payableCost.compareTo(brand.getCostThresholdAmount()) <= 0
                        ? brand.getDaysWithinThreshold() : brand.getDaysAboveThreshold();
                info.cycleDays = cycleDays;
                info.deadlineDate = toDate(toLocalDate(requirement.completedAt).plusDays(cycleDays));
                return info;
            }
            if (t.getPublishDate() == null || t.getInfluencerCost() == null) return info;
            int cycleDays = t.getInfluencerCost().compareTo(brand.getCostThresholdAmount()) <= 0
                    ? brand.getDaysWithinThreshold() : brand.getDaysAboveThreshold();
            info.cycleDays = cycleDays;
            info.deadlineDate = toDate(toLocalDate(t.getPublishDate()).plusDays(cycleDays));
        } else if (brand.getPaymentCycleType() == PaymentCycleType.MONTH_END) {
            if (t.getPublishDate() == null || brand.getDaysAfterMonthEnd() == null) return info;
            LocalDate monthEnd = YearMonth.from(toLocalDate(t.getPublishDate())).atEndOfMonth();
            info.cycleDays = brand.getDaysAfterMonthEnd();
            info.deadlineDate = toDate(monthEnd.plusDays(brand.getDaysAfterMonthEnd()));
        }
        return info;
    }

    // ============ 新建/编辑/删除/状态流转 ============

    @Transactional
    public InfluencerPayment create(InfluencerPaymentRequest req) {
        // 2026-08-17 性能修复：这两处原来直接查库（旧代码保留在下面注释里），但都是纯只读校验
        // （brand/team 在这个方法里只用来读字段判断，从不 save），改成跟本文件其它地方
        // （91/106/131/174/381/554/620 行）一样走 brandCache/teamCache——这两个缓存在
        // BrandController/InfluencerTeamController 的新增/编辑/删除里都会调用 refresh()，正常
        // 操作下不存在"缓存明显滞后于数据库"的窗口。
        Brand brand = brandCache.findById(req.getBrandId());
        if (brand == null) throw new RuntimeException("品牌方不存在");
        /* ===== 旧代码（2026-08-17 停用，按 Shawn 要求保留对比，不要直接删）=====
        Brand brand = brandRepo.findByIdAndIsDeletedFalse(req.getBrandId())
                .orElseThrow(() -> new RuntimeException("品牌方不存在"));
        ===== 旧代码结束 ===== */

        // teamIds 可能包含 null 元素（代表"不选团队"也在这次结款范围内）
        List<Long> realTeamIds = new ArrayList<>();
        boolean includeNoTeam = false;
        for (Long teamId : req.getTeamIds()) {
            if (teamId == null) includeNoTeam = true; else realTeamIds.add(teamId);
        }
        if (!realTeamIds.isEmpty()) {
            for (Long teamId : realTeamIds) {
                if (teamCache.findById(teamId) == null) throw new RuntimeException("红人团队不存在：" + teamId);
            }
            /* ===== 旧代码（2026-08-17 停用，按 Shawn 要求保留对比，不要直接删）=====
            Set<Long> foundTeamIds = new HashSet<>();
            for (InfluencerTeam t : teamRepo.findAllById(realTeamIds)) foundTeamIds.add(t.getId());
            for (Long teamId : realTeamIds) {
                if (!foundTeamIds.contains(teamId)) throw new RuntimeException("红人团队不存在：" + teamId);
            }
            ===== 旧代码结束 ===== */
        }

        validateInvoiceTeamExclusivity(brand, realTeamIds, includeNoTeam);
        validateReconcileDateIfNeeded(brand, req.getReconcileDate());

        List<CollaborationTracking> items = loadAndValidateItems(
                req.getCollaborationTrackingIds(), req.getBrandId(), realTeamIds, includeNoTeam, null);

        InfluencerPayment payment = new InfluencerPayment();
        payment.setIsDeleted(false);
        payment.setBrand(brand);
        payment.setSettlementMonth(req.getSettlementMonth());
        payment.setReconcileDate(req.getReconcileDate());
        payment.setExpectedPaymentDate(req.getExpectedPaymentDate());
        payment.setNotes(req.getNotes());
        payment.setCurrency("USD");
        InfluencerPaymentStatus initialStatus = req.getPaymentStatus() != null
                ? req.getPaymentStatus() : InfluencerPaymentStatus.PENDING;
        payment.setPaymentStatus(initialStatus);
        if (initialStatus == InfluencerPaymentStatus.PAID) {
            if (req.getActualPaymentDate() == null) throw new RuntimeException("付款状态为已付款时，请选择实际付款日");
            payment.setActualPaymentDate(req.getActualPaymentDate());
        }
        payment.setCooperationQuantity(items.size());
        payment.setPayableAmount(sumCost(items));
        payment.setInvolvedRequirementNos(computeInvolvedRequirementNos(items));
        // "是否涉及公对公发票"在创建这一刻按当前团队/品牌方配置现算一次，此后冻结成快照，
        // 不再跟着团队配置的后续改动联动——团队范围创建后本来就不可再改（update() 的限制），
        // 详见 InfluencerPayment.involvesCorporateInvoice 字段注释
        payment.setInvolvesCorporateInvoice(resolveInvolvesCorporateInvoice(brand, req.getTeamIds()));

        // 汇率创建时不接受前端手填，按结算月份自动从汇率维护取（取不到就留空）
        // 2026-08-17 性能修复：改走 ExchangeRateLookupCache；旧代码：
        // exchangeRateCacheRepo.findByYearMonth(req.getSettlementMonth())
        //         .map(ExchangeRateCache::getUsdToCny).ifPresent(payment::setExchangeRate)
        ExchangeRateCache rateCache = rateLookupCache.findByYearMonth(req.getSettlementMonth());
        if (rateCache != null) payment.setExchangeRate(rateCache.getUsdToCny());
        recomputeRmb(payment);

        String prefix = paymentNoGenerator.buildPrefix(brand.getName(), req.getSettlementMonth());
        long seq = paymentRepo.countByPaymentNoPrefix(prefix + "%");
        payment.setPaymentNo(paymentNoGenerator.generate(brand.getName(), req.getSettlementMonth(), seq));

        payment = paymentRepo.save(payment);
        linkItems(payment, items);
        saveTeamScope(payment.getId(), req.getTeamIds());
        refreshSettlementStatusFor(items);
        return payment;
    }

    /** 按 internalRequirementNo 去重后逐个调用 InfluencerRequirementService.refreshSettlementStatus() */
    private void refreshSettlementStatusFor(List<CollaborationTracking> items) {
        Set<String> reqNos = items.stream().map(CollaborationTracking::getInternalRequirementNo)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        for (String reqNo : reqNos) requirementService.refreshSettlementStatus(reqNo);
    }

    /** 创建时选定的团队范围落库，去重后一个团队（或"不选团队"）存一行 */
    private void saveTeamScope(Long paymentId, List<Long> teamIds) {
        List<InfluencerPaymentTeam> rows = new ArrayList<>();
        for (Long teamId : new LinkedHashSet<>(teamIds)) {
            InfluencerPaymentTeam row = new InfluencerPaymentTeam();
            row.setIsDeleted(false);
            row.setInfluencerPaymentId(paymentId);
            row.setTeamId(teamId);
            rows.add(row);
        }
        paymentTeamRepo.saveAll(rows);
    }

    @Transactional
    public InfluencerPayment update(Long id, InfluencerPaymentRequest req) {
        InfluencerPayment payment = paymentRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("结款记录不存在"));

        // 团队范围创建后不可再改，用持久化的范围校验，忽略请求体里的 teamIds
        List<Long> realTeamIds = new ArrayList<>();
        boolean includeNoTeam = false;
        for (InfluencerPaymentTeam row : paymentTeamRepo.findByInfluencerPaymentIdAndIsDeletedFalse(id)) {
            if (row.getTeamId() == null) includeNoTeam = true; else realTeamIds.add(row.getTeamId());
        }

        validateReconcileDateIfNeeded(brandCache.findById(payment.getBrandId()), req.getReconcileDate());

        List<CollaborationTracking> newItems = loadAndValidateItems(
                req.getCollaborationTrackingIds(), payment.getBrandId(), realTeamIds, includeNoTeam, payment.getId());
        List<CollaborationTracking> currentItems = trackingRepo.findByInfluencerPaymentIdAndIsDeletedFalse(payment.getId());

        Set<Long> newIds = newItems.stream().map(CollaborationTracking::getId).collect(Collectors.toSet());
        Set<Long> currentIds = currentItems.stream().map(CollaborationTracking::getId).collect(Collectors.toSet());

        // 已付款：汇率/对账日期/预计付款日/备注仍可编辑，但涉及的红人视频项目不能再调整
        if (payment.getPaymentStatus() == InfluencerPaymentStatus.PAID && !newIds.equals(currentIds)) {
            throw new RuntimeException("付款状态为\"已付款\"，无法调整涉及的红人视频项目");
        }

        List<CollaborationTracking> toUnlink = currentItems.stream()
                .filter(t -> !newIds.contains(t.getId())).collect(Collectors.toList());
        List<CollaborationTracking> toLink = newItems.stream()
                .filter(t -> !currentIds.contains(t.getId())).collect(Collectors.toList());

        unlinkItems(toUnlink);
        linkItems(payment, toLink);

        payment.setCooperationQuantity(newItems.size());
        payment.setPayableAmount(sumCost(newItems));
        payment.setInvolvedRequirementNos(computeInvolvedRequirementNos(newItems));
        payment.setReconcileDate(req.getReconcileDate());
        payment.setExpectedPaymentDate(req.getExpectedPaymentDate());
        payment.setNotes(req.getNotes());

        // 汇率：编辑时管理层可强制修改；不传就保留原值
        if (req.getExchangeRate() != null) {
            payment.setExchangeRate(req.getExchangeRate());
        }
        recomputeRmb(payment);

        InfluencerPayment saved = paymentRepo.save(payment);
        // 结款状态要同时覆盖"移出批次的旧记录"和"新纳入的记录"各自涉及的需求编号——
        // 移出的那些可能因此要从"已添加结款记录"退回空，新纳入的要补上
        List<CollaborationTracking> affected = new ArrayList<>(toUnlink);
        affected.addAll(newItems);
        refreshSettlementStatusFor(affected);
        return saved;
    }

    @Transactional
    public InfluencerPayment updateStatus(Long id, InfluencerPaymentStatusRequest req) {
        InfluencerPayment payment = paymentRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("结款记录不存在"));
        if (req.getPaymentStatus() == InfluencerPaymentStatus.PAID) {
            if (req.getActualPaymentDate() == null) throw new RuntimeException("请选择实际付款日");
            payment.setActualPaymentDate(req.getActualPaymentDate());
        } else {
            // 倒退（已付款 -> 待付款）：实际付款日一并清空
            payment.setActualPaymentDate(null);
        }
        payment.setPaymentStatus(req.getPaymentStatus());
        InfluencerPayment saved = paymentRepo.save(payment);
        // 付款状态在"待付款"/"已付款"之间流转，直接影响这批记录关联的需求"结款状态"该显示哪个值
        refreshSettlementStatusForNos(payment.getInvolvedRequirementNos());
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        InfluencerPayment payment = paymentRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("结款记录不存在"));
        String involvedNos = payment.getInvolvedRequirementNos();
        unlinkItems(trackingRepo.findByInfluencerPaymentIdAndIsDeletedFalse(id));
        List<InfluencerPaymentTeam> scopeRows = paymentTeamRepo.findByInfluencerPaymentIdAndIsDeletedFalse(id);
        for (InfluencerPaymentTeam row : scopeRows) row.setIsDeleted(true);
        paymentTeamRepo.saveAll(scopeRows);
        payment.setIsDeleted(true);
        paymentRepo.save(payment);
        // 整条结款记录被删除，涉及的需求要重新判定"结款状态"（这批记录已经在上面 unlinkItems()
        // 里恢复成纳入批次前的红人结款进度了，删除后应该退回空、或者退回它们在其他批次里的状态）
        refreshSettlementStatusForNos(involvedNos);
    }

    /** involvedRequirementNos 是换行拼接的多值文本，拆开后逐个刷新（跟 MultiValueUtil 同样的约定） */
    private void refreshSettlementStatusForNos(String involvedRequirementNos) {
        if (involvedRequirementNos == null || involvedRequirementNos.trim().isEmpty()) return;
        for (String reqNo : involvedRequirementNos.split("\n")) {
            if (!reqNo.trim().isEmpty()) requirementService.refreshSettlementStatus(reqNo.trim());
        }
    }

    // ============ 内部辅助 ============

    private List<CollaborationTracking> loadAndValidateItems(
            List<Long> ids, Long brandId, List<Long> realTeamIds, boolean includeNoTeam, Long excludePaymentId) {
        if (ids == null || ids.isEmpty()) throw new RuntimeException("请至少选择一条涉及的红人视频项目");
        List<CollaborationTracking> items = trackingRepo.findByIdInAndIsDeletedFalse(ids);
        if (items.size() != new HashSet<>(ids).size()) {
            throw new RuntimeException("部分红人合作跟踪记录不存在或已被删除");
        }
        for (CollaborationTracking t : items) {
            if (!brandId.equals(t.getBrandId())) {
                throw new RuntimeException("勾选的记录里有不属于所选品牌方的条目：" + t.getInternalProjectNo());
            }
            boolean teamMatches = t.getTeamId() == null ? includeNoTeam : realTeamIds.contains(t.getTeamId());
            if (!teamMatches) {
                throw new RuntimeException("勾选的记录里有不属于所选团队范围的条目：" + t.getInternalProjectNo());
            }
            boolean alreadyBatched = t.getInfluencerPaymentProgress() != null && t.getInfluencerPaymentProgress().isIncludedInBatch();
            boolean belongsToThisPayment = excludePaymentId != null && excludePaymentId.equals(t.getInfluencerPaymentId());
            if (alreadyBatched && !belongsToThisPayment) {
                throw new RuntimeException("勾选的记录里有已被其他结款批次纳入的条目：" + t.getInternalProjectNo());
            }
        }
        validateSingleRequirementIfNeeded(items, brandId);
        validateNoPartialRequirement(items, excludePaymentId);
        return items;
    }

    /**
     * 通用校验（2026-08 新增，不限品牌方付款周期类型）：同一个内部需求编号下的记录不能被拆分到
     * 多个结款批次里——如果这次提交的记录里包含某个需求编号的部分记录，但这个需求还有其他
     * "可结款"（红人结款进度非空，即视频项目进度已经到达允许结款的终态）、且没有纳入本次
     * 结款批次（不属于正在编辑的这条结款记录本身）的记录没有一并勾选，直接拒绝。
     *
     * 前端"选择涉及的红人视频项目"弹窗已经把"勾选一条就带上同一需求下其他候选记录"这个交互
     * 做成所有品牌方通用（不再只是"按红人成本阈值分档+需要invoice"专属），正常操作不会触发
     * 这条校验，这里是服务端兜底，防止绕过前端直接调接口。
     *
     * 已经属于"其他"结款批次的同需求记录不算"遗漏"——那条记录纳入那个批次时，如果这条规则
     * 还没上线，可能本来就是历史遗留的拆分，这里只管"这一次提交本身是否完整"，不追溯修正历史。
     */
    // 2026-08-17 性能修复：原来按 reqNo 逐个查库（旧代码保留在下面注释里），改成用仓库里现成的
    // 批量方法 findByInternalRequirementNoInAndIsDeletedFalse(List<String>)（ProgressReminderService/
    // InfluencerRequirementService 已经在用同一个方法）一次性取回本批次涉及的全部需求编号下的
    // 候选兄弟记录，再按 reqNo 分组，循环内的判断逻辑（谁算"遗漏"、谁不算）完全不变。
    private void validateNoPartialRequirement(List<CollaborationTracking> items, Long excludePaymentId) {
        Set<String> reqNos = items.stream().map(CollaborationTracking::getInternalRequirementNo)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (reqNos.isEmpty()) return;
        Set<Long> submittedIds = items.stream().map(CollaborationTracking::getId).collect(Collectors.toSet());
        List<CollaborationTracking> allSiblings =
                trackingRepo.findByInternalRequirementNoInAndIsDeletedFalse(new ArrayList<>(reqNos));
        Map<String, List<CollaborationTracking>> siblingsByReqNo = allSiblings.stream()
                .collect(Collectors.groupingBy(CollaborationTracking::getInternalRequirementNo));
        for (String reqNo : reqNos) {
            for (CollaborationTracking sibling : siblingsByReqNo.getOrDefault(reqNo, Collections.emptyList())) {
                if (submittedIds.contains(sibling.getId())) continue;
                if (sibling.getInfluencerPaymentProgress() == null) continue; // 还没到能结款的阶段，不算"遗漏"
                boolean belongsToThisPayment = excludePaymentId != null && excludePaymentId.equals(sibling.getInfluencerPaymentId());
                if (belongsToThisPayment) continue;
                if (sibling.getInfluencerPaymentProgress().isIncludedInBatch()) continue; // 已经在其他批次里，历史数据不追溯
                throw new RuntimeException("内部需求编号 [" + reqNo + "] 下还有其他可结款的记录（"
                        + sibling.getInternalProjectNo() + "）没有一起勾选，同一个需求的记录不能拆分到不同的结款批次里");
            }
        }
        /* ===== 旧代码（2026-08-17 停用，按 Shawn 要求保留对比，不要直接删）=====
        for (String reqNo : reqNos) {
            for (CollaborationTracking sibling : trackingRepo.findByInternalRequirementNoAndIsDeletedFalse(reqNo)) {
                if (submittedIds.contains(sibling.getId())) continue;
                if (sibling.getInfluencerPaymentProgress() == null) continue; // 还没到能结款的阶段，不算"遗漏"
                boolean belongsToThisPayment = excludePaymentId != null && excludePaymentId.equals(sibling.getInfluencerPaymentId());
                if (belongsToThisPayment) continue;
                if (sibling.getInfluencerPaymentProgress().isIncludedInBatch()) continue; // 已经在其他批次里，历史数据不追溯
                throw new RuntimeException("内部需求编号 [" + reqNo + "] 下还有其他可结款的记录（"
                        + sibling.getInternalProjectNo() + "）没有一起勾选，同一个需求的记录不能拆分到不同的结款批次里");
            }
        }
        ===== 旧代码结束 ===== */
    }

    /**
     * 需要invoice 且按红人成本阈值分档的品牌方：一条结款记录只能对应一个内部需求编号（一张
     * invoice），且这个需求必须"需求完成进度"=100%才能结款——前端"选择涉及的红人视频项目"
     * 弹窗已经通过置灰+禁用勾选实现了这个限制，这里是服务端兜底校验，防止绕过前端直接调接口。
     * 其余品牌方（月结、或未来其它不需要invoice的阈值分档品牌方）不受此限制。
     */
    private void validateSingleRequirementIfNeeded(List<CollaborationTracking> items, Long brandId) {
        Brand brand = brandCache.findById(brandId);
        if (brand == null || brand.getPaymentCycleType() != PaymentCycleType.COST_THRESHOLD
                || !brand.requiresInvoiceUpload()) {
            return;
        }
        Set<String> reqNos = items.stream().map(CollaborationTracking::getInternalRequirementNo)
                .collect(Collectors.toSet());
        if (reqNos.size() != 1 || reqNos.contains(null)) {
            throw new RuntimeException("该品牌方每条结款记录只能对应一个内部需求编号（一张invoice）");
        }
        String reqNo = reqNos.iterator().next();
        InfluencerRequirementService.RequirementPaymentInfo info = requirementService
                .fetchPaymentInfo(Collections.singletonList(reqNo)).get(reqNo);
        if (info == null) throw new RuntimeException("需求不存在：" + reqNo);
        if (!info.isComplete()) {
            throw new RuntimeException("该需求尚未整体完成，暂时无法结款：" + reqNo);
        }
    }

    /**
     * 品牌方付款周期=月结（MONTH_END）时，"对账日期"是判断结款日的必要依据
     * （expectedPaymentDate = 对账日期所在月底 + daysAfterMonthEnd，见 computeCycleInfo/
     * PaymentFormModal.vue 的 brandCycleHint/tryAutoFillExpectedPaymentDate），必填；
     * 其余付款周期（按红人成本阈值分档等）不依赖对账日期，保持可选，跟前端校验保持一致
     * （2026-08 新增，新建/编辑都要校验——对账日期不像团队范围那样创建后锁定，编辑时仍可改）。
     */
    private void validateReconcileDateIfNeeded(Brand brand, Date reconcileDate) {
        if (brand != null && brand.getPaymentCycleType() == PaymentCycleType.MONTH_END && reconcileDate == null) {
            throw new RuntimeException("该品牌方为月结，请填写对账日期");
        }
    }

    /**
     * "涉及公对公发票"的团队不能跟其他团队合并结算（2026-08 新增，"上传发票"功能配套规则）：
     * 只要这次选定的团队范围里，有任意一个团队（或"不选团队"落回品牌方默认值）解析出
     * "涉及公对公发票"，那这次结款记录的团队范围就只能是这一个团队/这一项，不能再选其他团队
     * （不管其他团队涉不涉及发票）——避免一条结款记录横跨"要开发票"和"不用开发票"两种场景，
     * 导致"上传发票"这个动作语义不清。这个改动不涉及存量数据（上线时红人结款模块还没有
     * 正式录入数据），也不影响 update()——团队范围创建后就锁定，不会再触发这条校验。
     */
    // 2026-08-17 性能修复：下面原来查库（旧代码保留在注释里），改成跟同一个类里紧挨着的
    // resolveInvolvesCorporateInvoice()（本方法的姐妹方法，同样在 create() 流程里被调用）一样走
    // teamCache.findById，两处判断的都是同一批 realTeamIds 涉不涉及发票，没道理一处查库一处查缓存。
    private void validateInvoiceTeamExclusivity(Brand brand, List<Long> realTeamIds, boolean includeNoTeam) {
        int totalScopeCount = realTeamIds.size() + (includeNoTeam ? 1 : 0);
        if (totalScopeCount <= 1) return;
        boolean anyInvoiceInvolved = includeNoTeam && InfluencerTeam.involvesCorporateInvoice(brand, null);
        if (!anyInvoiceInvolved) {
            for (Long teamId : realTeamIds) {
                InfluencerTeam team = teamCache.findById(teamId);
                if (InfluencerTeam.involvesCorporateInvoice(brand, team)) { anyInvoiceInvolved = true; break; }
            }
            /* ===== 旧代码（2026-08-17 停用，按 Shawn 要求保留对比，不要直接删）=====
            for (InfluencerTeam team : teamRepo.findAllById(realTeamIds)) {
                if (InfluencerTeam.involvesCorporateInvoice(brand, team)) { anyInvoiceInvolved = true; break; }
            }
            ===== 旧代码结束 ===== */
        }
        if (anyInvoiceInvolved) {
            throw new RuntimeException("勾选的团队里有涉及公对公发票的团队，这类团队的结款记录不能跟其他团队合并结算，请单独结款");
        }
    }

    /**
     * 按当前品牌方/团队配置现算"是否涉及公对公发票"——2026-08 起只在两个地方用：
     * 1. create() 里给新记录的快照字段赋初值；
     * 2. backfillInvolvesCorporateInvoiceSnapshot() 给历史存量记录一次性回填快照。
     * 除了这两处，其余所有"这条结款记录到底涉不涉及发票"的判断一律读
     * InfluencerPayment.involvesCorporateInvoice 这个已经落库的快照，不要再现算——现算的话，
     * 后续团队配置一改，历史"已付款"记录也会被追溯影响，这正是这个快照字段要避免的问题
     * （见该字段的类注释）。
     */
    private boolean resolveInvolvesCorporateInvoice(Brand brand, List<Long> teamIds) {
        if (teamIds == null) return false;
        for (Long teamId : teamIds) {
            InfluencerTeam team = teamId != null ? teamCache.findById(teamId) : null;
            if (InfluencerTeam.involvesCorporateInvoice(brand, team)) return true;
        }
        return false;
    }

    /** "上传发票"：把公对公发票的 Google Drive 链接存到这条结款记录上，纯记录用途，不触发任何联动 */
    @Transactional
    public InfluencerPayment uploadReceiptLink(Long id, String receiptLink) {
        InfluencerPayment payment = paymentRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("结款记录不存在"));
        if (!Boolean.TRUE.equals(payment.getInvolvesCorporateInvoice())) {
            throw new RuntimeException("该结款记录不涉及公对公发票，无需上传");
        }
        payment.setReceiptLink(receiptLink);
        return paymentRepo.save(payment);
    }

    /**
     * "查看未上传发票的记录"按钮专用（2026-08 新增）：跟列表页 findByFilters 走同一套筛选条件，
     * 只额外要求这条结款记录涉及公对公发票（读 involvesCorporateInvoice 快照，不现算，见其
     * 字段注释）、且还没上传发票（receiptLink为空）。没法下推到 SQL WHERE，只能先按其余条件
     * 查出来，在内存里筛选+手动分页（这个模块数据量不大，跟"红人需求管理"那几个"查看未上传XX"
     * 按钮同一个套路）。
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<InfluencerPayment> pageMissingReceipt(
            String settlementMonth, Long brandId, boolean filterByTeam, List<Long> matchingIds,
            boolean filterByReqNo, List<Long> reqMatchingIds, InfluencerPaymentStatus paymentStatus,
            String paymentNo, org.springframework.data.domain.Pageable pageable) {
        List<InfluencerPayment> all = paymentRepo.findByFiltersNoPaging(
                settlementMonth, brandId, filterByTeam, matchingIds, filterByReqNo, reqMatchingIds,
                paymentStatus, paymentNo, pageable.getSort());
        // 涉不涉及发票已经改成读快照字段，不再需要 teamIds 参与判断，但列表页"红人团队"列展示
        // 仍然要靠这个批量填充（Controller 的普通分页分支也是同一套 attachTeamIds，这里保持一致）
        attachTeamIds(all);
        List<InfluencerPayment> missing = new ArrayList<>();
        for (InfluencerPayment p : all) {
            if (p.getReceiptLink() != null && !p.getReceiptLink().trim().isEmpty()) continue;
            if (!Boolean.TRUE.equals(p.getInvolvesCorporateInvoice())) continue;
            missing.add(p);
        }
        int total = missing.size();
        int start = Math.min((int) pageable.getOffset(), total);
        int end = Math.min(start + pageable.getPageSize(), total);
        List<InfluencerPayment> pageContent = missing.subList(start, end);
        return new org.springframework.data.domain.PageImpl<>(pageContent, pageable, total);
    }

    /**
     * 纳入批次时，原状态是"待红人发送invoice"的记录改成专门的"已纳入红人结款批次（缺少invoice）"，
     * 而不是笼统的"已纳入红人结款批次"——避免这个信息被悄悄抹掉，管理层日后还能一眼看出
     * 这条记录当初纳入结款批次时其实还没拿到 invoice。
     */
    private void linkItems(InfluencerPayment payment, List<CollaborationTracking> items) {
        if (items.isEmpty()) return;
        for (CollaborationTracking t : items) {
            t.setPreBatchPaymentProgress(t.getInfluencerPaymentProgress());
            t.setInfluencerPaymentProgress(t.getInfluencerPaymentProgress() == InfluencerPaymentProgress.PENDING_INVOICE
                    ? InfluencerPaymentProgress.INCLUDED_IN_PAYMENT_BATCH_MISSING_INVOICE
                    : InfluencerPaymentProgress.INCLUDED_IN_PAYMENT_BATCH);
            t.setInfluencerPayment(payment);
        }
        trackingRepo.saveAll(items);
    }

    private void unlinkItems(List<CollaborationTracking> items) {
        if (items.isEmpty()) return;
        for (CollaborationTracking t : items) {
            t.setInfluencerPaymentProgress(t.getPreBatchPaymentProgress());
            t.setPreBatchPaymentProgress(null);
            t.setInfluencerPayment(null);
        }
        trackingRepo.saveAll(items);
    }

    private BigDecimal sumCost(List<CollaborationTracking> items) {
        BigDecimal sum = BigDecimal.ZERO;
        for (CollaborationTracking t : items) {
            if (t.getInfluencerCost() != null) sum = sum.add(t.getInfluencerCost());
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    /** 涉及的内部需求编号：勾选条目里出现过的编号去重、排序后换行拼接；一个都没有时返回 null */
    private String computeInvolvedRequirementNos(List<CollaborationTracking> items) {
        Set<String> nos = new java.util.TreeSet<>();
        for (CollaborationTracking t : items) {
            if (t.getInternalRequirementNo() != null && !t.getInternalRequirementNo().trim().isEmpty()) {
                nos.add(t.getInternalRequirementNo().trim());
            }
        }
        return nos.isEmpty() ? null : String.join("\n", nos);
    }

    private void recomputeRmb(InfluencerPayment payment) {
        if (payment.getExchangeRate() != null && payment.getPayableAmount() != null) {
            payment.setRmbAmount(payment.getPayableAmount().multiply(payment.getExchangeRate()).setScale(2, RoundingMode.HALF_UP));
        } else {
            payment.setRmbAmount(null);
        }
    }

    /**
     * 不能用 date.toInstant()——Hibernate 给 @Temporal(DATE) 字段赋的运行时对象实际是
     * java.sql.Date，而 java.sql.Date.toInstant() 会抛 UnsupportedOperationException
     * （同样的坑 ProgressReminderService.toLocalDate 也是这么绕开的）。
     */
    private LocalDate toLocalDate(Date date) {
        return new java.sql.Date(date.getTime()).toLocalDate();
    }

    private Date toDate(LocalDate localDate) {
        return java.sql.Date.valueOf(localDate);
    }
}
