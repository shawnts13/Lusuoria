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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Autowired private InfluencerPaymentRepository paymentRepo;
    @Autowired private InfluencerPaymentTeamRepository paymentTeamRepo;
    @Autowired private CollaborationTrackingRepository trackingRepo;
    @Autowired private BrandRepository brandRepo;
    @Autowired private InfluencerTeamRepository teamRepo;
    @Autowired private InfluencerRepository influencerRepo;
    @Autowired private ExchangeRateCacheRepository exchangeRateCacheRepo;
    @Autowired private BrandCache brandCache;
    @Autowired private InfluencerTeamCache teamCache;
    @Autowired private PaymentNoGenerator paymentNoGenerator;

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

        YearMonth reconcileMonth = reconcileDate != null ? YearMonth.from(toLocalDate(reconcileDate)) : null;

        List<PaymentCandidateItem> result = new ArrayList<>();
        for (CollaborationTracking t : list) {
            PaymentCandidateItem item = new PaymentCandidateItem();
            item.setTrackingId(t.getId());
            item.setInternalProjectNo(t.getInternalProjectNo());
            item.setBrandName(brand != null ? brand.getName() : null);
            InfluencerTeam team = t.getTeamId() != null ? teamCache.findById(t.getTeamId()) : null;
            item.setTeamName(team != null ? team.getName() : null);
            item.setAccountName(accountNameById.get(t.getInfluencerId()));
            item.setDemandContent(t.getDemandContent());
            item.setInfluencerCost(t.getInfluencerCost());
            item.setProgressLabel(t.getProgress() != null ? t.getProgress().getLabel() : null);
            item.setPaymentProgressLabel(t.getInfluencerPaymentProgress() != null ? t.getInfluencerPaymentProgress().getLabel() : null);
            item.setPublishDate(t.getPublishDate());
            item.setInvoiceWarning(t.getInfluencerPaymentProgress() == InfluencerPaymentProgress.PENDING_INVOICE);

            if (brand != null) {
                CycleInfo cycleInfo = computeCycleInfo(t, brand);
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
        result.sort(Comparator.comparing(PaymentCandidateItem::getDeadlineDate,
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
     */
    private CycleInfo computeCycleInfo(CollaborationTracking t, Brand brand) {
        CycleInfo info = new CycleInfo();
        if (brand.getPaymentCycleType() == null || t.getPublishDate() == null) return info;
        LocalDate publishLocalDate = toLocalDate(t.getPublishDate());

        if (brand.getPaymentCycleType() == PaymentCycleType.COST_THRESHOLD) {
            if (brand.getCostThresholdAmount() == null || brand.getDaysWithinThreshold() == null
                    || brand.getDaysAboveThreshold() == null || t.getInfluencerCost() == null) return info;
            int cycleDays = t.getInfluencerCost().compareTo(brand.getCostThresholdAmount()) <= 0
                    ? brand.getDaysWithinThreshold() : brand.getDaysAboveThreshold();
            info.cycleDays = cycleDays;
            info.deadlineDate = toDate(publishLocalDate.plusDays(cycleDays));
        } else if (brand.getPaymentCycleType() == PaymentCycleType.MONTH_END) {
            if (brand.getDaysAfterMonthEnd() == null) return info;
            LocalDate monthEnd = YearMonth.from(publishLocalDate).atEndOfMonth();
            info.cycleDays = brand.getDaysAfterMonthEnd();
            info.deadlineDate = toDate(monthEnd.plusDays(brand.getDaysAfterMonthEnd()));
        }
        return info;
    }

    // ============ 新建/编辑/删除/状态流转 ============

    @Transactional
    public InfluencerPayment create(InfluencerPaymentRequest req) {
        Brand brand = brandRepo.findByIdAndIsDeletedFalse(req.getBrandId())
                .orElseThrow(() -> new RuntimeException("品牌方不存在"));

        // teamIds 可能包含 null 元素（代表"不选团队"也在这次结款范围内）
        List<Long> realTeamIds = new ArrayList<>();
        boolean includeNoTeam = false;
        for (Long teamId : req.getTeamIds()) {
            if (teamId == null) includeNoTeam = true; else realTeamIds.add(teamId);
        }
        for (Long teamId : realTeamIds) {
            if (!teamRepo.findById(teamId).isPresent()) throw new RuntimeException("红人团队不存在：" + teamId);
        }

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

        // 汇率创建时不接受前端手填，按结算月份自动从汇率维护取（取不到就留空）
        exchangeRateCacheRepo.findByYearMonth(req.getSettlementMonth())
                .map(ExchangeRateCache::getUsdToCny)
                .ifPresent(payment::setExchangeRate);
        recomputeRmb(payment);

        String prefix = paymentNoGenerator.buildPrefix(brand.getName(), req.getSettlementMonth());
        long seq = paymentRepo.countByPaymentNoPrefix(prefix + "%");
        payment.setPaymentNo(paymentNoGenerator.generate(brand.getName(), req.getSettlementMonth(), seq));

        payment = paymentRepo.save(payment);
        linkItems(payment, items);
        saveTeamScope(payment.getId(), req.getTeamIds());
        return payment;
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
        payment.setReconcileDate(req.getReconcileDate());
        payment.setExpectedPaymentDate(req.getExpectedPaymentDate());
        payment.setNotes(req.getNotes());

        // 汇率：编辑时管理层可强制修改；不传就保留原值
        if (req.getExchangeRate() != null) {
            payment.setExchangeRate(req.getExchangeRate());
        }
        recomputeRmb(payment);

        return paymentRepo.save(payment);
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
        return paymentRepo.save(payment);
    }

    @Transactional
    public void delete(Long id) {
        InfluencerPayment payment = paymentRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("结款记录不存在"));
        unlinkItems(trackingRepo.findByInfluencerPaymentIdAndIsDeletedFalse(id));
        List<InfluencerPaymentTeam> scopeRows = paymentTeamRepo.findByInfluencerPaymentIdAndIsDeletedFalse(id);
        for (InfluencerPaymentTeam row : scopeRows) row.setIsDeleted(true);
        paymentTeamRepo.saveAll(scopeRows);
        payment.setIsDeleted(true);
        paymentRepo.save(payment);
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
            boolean alreadyBatched = t.getInfluencerPaymentProgress() == InfluencerPaymentProgress.INCLUDED_IN_PAYMENT_BATCH;
            boolean belongsToThisPayment = excludePaymentId != null && excludePaymentId.equals(t.getInfluencerPaymentId());
            if (alreadyBatched && !belongsToThisPayment) {
                throw new RuntimeException("勾选的记录里有已被其他结款批次纳入的条目：" + t.getInternalProjectNo());
            }
        }
        return items;
    }

    private void linkItems(InfluencerPayment payment, List<CollaborationTracking> items) {
        if (items.isEmpty()) return;
        for (CollaborationTracking t : items) {
            t.setPreBatchPaymentProgress(t.getInfluencerPaymentProgress());
            t.setInfluencerPaymentProgress(InfluencerPaymentProgress.INCLUDED_IN_PAYMENT_BATCH);
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
