package com.lusuoria.settlement.service.impl;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.dto.request.CollaborationTrackingRequest;
import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.entity.ProjectOrder;
import com.lusuoria.settlement.repository.CollaborationTrackingRepository;
import com.lusuoria.settlement.repository.InfluencerBrandRepository;
import com.lusuoria.settlement.repository.InfluencerRepository;
import com.lusuoria.settlement.repository.ProjectOrderRepository;
import com.lusuoria.settlement.util.ProjectNoAllocator;
import com.lusuoria.settlement.util.ProfitCalculator;
import com.lusuoria.settlement.util.RoleUtil;
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
 *  2. 去重：accountName + publishLink + publishDate 三者完全相同视为重复（链接和日期均空时不去重）
 *  3. 订单ID联动项目订单：
 *     - 空 -> 有值：自动新增一条项目订单
 *     - 没变：不重复生成
 *     - 改了（旧值 -> 新值）：需前端 confirmOrderIdChange=true，删旧订单再建新订单
 */
@Service
public class CollaborationTrackingService {

    @Autowired private CollaborationTrackingRepository trackingRepo;
    @Autowired private InfluencerRepository influencerRepo;
    @Autowired private InfluencerBrandRepository influencerBrandRepo;
    @Autowired private ProjectOrderRepository projectOrderRepo;
    @Autowired private BrandCache brandCache;
    @Autowired private EmployeeCache employeeCache;
    @Autowired private ProjectNoAllocator projectNoAllocator;
    @Autowired private ProfitCalculator profitCalculator;
    @Autowired private ExchangeRateService exchangeRateService;

    /** 自定义异常：表示需要前端二次确认订单ID变更 */
    public static class OrderIdChangeConfirmRequired extends RuntimeException {
        public OrderIdChangeConfirmRequired(String msg) { super(msg); }
    }

    /** 自定义异常：去重命中 */
    public static class DuplicateTrackingException extends RuntimeException {
        public DuplicateTrackingException(String msg) { super(msg); }
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

        // 红人必须存在于红人库
        Influencer influencer = influencerRepo.findByAccountNameAndIsDeletedFalse(req.getAccountName().trim())
                .orElseThrow(() -> new RuntimeException("红人社媒完整名字不存在于红人库：" + req.getAccountName()));

        // 团队 / 国家：拷贝快照（不随红人库变）
        tracking.setAccountName(influencer.getAccountName());
        tracking.setTeamName(influencer.getTeamName());
        tracking.setCountryMarket(influencer.getCountryMarket());

        // 品牌方：必须是该红人在红人模块里已关联的品牌方之一
        if (req.getBrandId() != null) {
            Brand brand = brandCache.findById(req.getBrandId());
            if (brand == null) throw new RuntimeException("品牌方不存在：" + req.getBrandId());
            if (!influencerBrandRepo.existsByInfluencerIdAndBrandId(influencer.getId(), req.getBrandId())) {
                throw new RuntimeException("品牌方 [" + brand.getName() + "] 未在红人模块中关联到该红人，"
                        + "请先在红人模块维护后再选择");
            }
            tracking.setBrand(brand);
        } else {
            tracking.setBrand(null);
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

        // 内部项目编号：仅新建时生成一次，前端不可编辑，此后永久不变
        // （月份固定用"创建时间当月"，不随后续填写的发布时间变化）
        if (req.getId() == null) {
            String brandName = tracking.getBrand() != null ? tracking.getBrand().getName() : null;
            String createMonth = new SimpleDateFormat("yyyyMM").format(new Date());
            tracking.setInternalProjectNo(
                    projectNoAllocator.allocate(brandName, createMonth, tracking.getAccountName()));
        }

        // 项目负责人
        if (req.getProjectManagerId() != null) {
            Employee manager = employeeCache.findById(req.getProjectManagerId());
            if (manager == null) throw new RuntimeException("项目负责人不存在：" + req.getProjectManagerId());
            tracking.setProjectManager(manager);
        } else {
            tracking.setProjectManager(null);
        }

        if (RoleUtil.canViewSensitiveFields()) {
            tracking.setInfluencerCost(req.getInfluencerCost());
            tracking.setClientPrice(req.getClientPrice());
        }

        String newOrderId = emptyToNull(req.getClientOrderId());

        // ---- 去重判断（仅当链接和日期都非空时）----
        if (tracking.getPublishLink() != null && tracking.getPublishDate() != null) {
            List<CollaborationTracking> dups = trackingRepo.findDuplicates(
                    tracking.getAccountName(), tracking.getPublishLink(),
                    tracking.getPublishDate(), req.getId());
            if (!dups.isEmpty()) {
                throw new DuplicateTrackingException(
                        "已存在相同记录：红人 [" + tracking.getAccountName()
                        + "] 在 " + new SimpleDateFormat("yyyy-MM-dd").format(tracking.getPublishDate())
                        + " 发布的该链接已录入，无法重复添加");
            }
        }

        // ---- 订单ID联动项目订单 ----
        boolean fromEmptyToValue = (oldOrderId == null && newOrderId != null);
        boolean changedToAnother  = (oldOrderId != null && newOrderId != null && !oldOrderId.equals(newOrderId));
        boolean clearedToEmpty    = (oldOrderId != null && newOrderId == null);

        if (changedToAnother && !Boolean.TRUE.equals(req.getConfirmOrderIdChange())) {
            // 需要前端二次确认
            throw new OrderIdChangeConfirmRequired(
                    "客户方的项目订单已从 [" + oldOrderId + "] 改为 [" + newOrderId
                    + "]，确认后将删除本记录原先生成的项目订单并重新生成，是否继续？");
        }

        tracking.setClientOrderId(newOrderId);

        // 订单ID改了 或 被清空：删除本跟踪记录之前生成的那条项目订单
        if ((changedToAnother || clearedToEmpty) && tracking.getGeneratedProjectOrderId() != null) {
            softDeleteProjectOrderById(tracking.getGeneratedProjectOrderId());
            tracking.setGeneratedProjectOrderId(null);
        }

        CollaborationTracking saved = trackingRepo.save(tracking);

        // 空->有值，或改成了新值：为本条记录生成一条独立的项目订单
        // （同一订单ID允许多条项目订单——每个视频各一条，红人/金额各自独立）
        if (fromEmptyToValue || changedToAnother) {
            Long newOrderPk = createProjectOrderFromTracking(saved, influencer, newOrderId);
            saved.setGeneratedProjectOrderId(newOrderPk);
            saved = trackingRepo.save(saved);
        }

        return saved;
    }

    @Transactional
    public void delete(Long id) {
        CollaborationTracking tracking = trackingRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("跟踪记录不存在：" + id));
        tracking.setIsDeleted(true);
        trackingRepo.save(tracking);
        // 注意：删除跟踪记录时不自动删除已生成的项目订单（订单一旦成立独立存在）
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

    /** 软删除指定 id 的项目订单 */
    private void softDeleteProjectOrderById(Long orderId) {
        if (orderId == null) return;
        projectOrderRepo.findByIdAndIsDeletedFalse(orderId)
                .ifPresent(o -> {
                    o.setIsDeleted(true);
                    // 让出内部项目编号：这个编号来自跟踪记录，跟踪记录的编号永久不变，
                    // 如果客户方订单号又改了、需要重新生成项目订单，新订单会想用同一个编号，
                    // 但这条旧订单还物理存在（软删除），不让号会撞数据库唯一约束
                    if (o.getInternalProjectNo() != null) {
                        o.setInternalProjectNo(o.getInternalProjectNo() + "-DEL" + o.getId());
                    }
                    projectOrderRepo.save(o);
                });
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

    private boolean equalsNullable(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
