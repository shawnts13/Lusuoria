package com.lusuoria.settlement.service.impl;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.config.InfluencerTeamCache;
import com.lusuoria.settlement.dto.request.ProjectOrderRequest;
import com.lusuoria.settlement.dto.response.ExecutorCostSuggestionResponse;
import com.lusuoria.settlement.dto.response.MonthlySummaryResponse;
import com.lusuoria.settlement.dto.response.ProjectOrderResponse;
import com.lusuoria.settlement.entity.*;
import com.lusuoria.settlement.enums.ClientStatus;
import com.lusuoria.settlement.enums.InternalSettlementStatus;
import com.lusuoria.settlement.enums.PendingApprovalModule;
import com.lusuoria.settlement.enums.ProjectType;
import com.lusuoria.settlement.enums.VideoType;
import com.lusuoria.settlement.excel.ProjectOrderExcelHandler;
import com.lusuoria.settlement.repository.*;
import com.lusuoria.settlement.service.ProjectOrderService;
import com.lusuoria.settlement.util.ProfitCalculator;
import com.lusuoria.settlement.util.ProjectFieldVisibility;
import com.lusuoria.settlement.util.RoleUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class ProjectOrderServiceImpl implements ProjectOrderService {

    @Autowired private ProjectOrderRepository projectOrderRepo;
    @Autowired private BrandRepository brandRepo;
    @Autowired private InfluencerRepository influencerRepo;
    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private BrandCache brandCache;
    @Autowired private EmployeeCache employeeCache;
    @Autowired private InfluencerTeamCache teamCache;
    @Autowired private ProfitCalculator profitCalculator;
    @Autowired private ProjectOrderExcelHandler excelHandler;
    @Autowired private PendingApprovalService pendingApprovalService;
    @Autowired private ProjectFieldVisibility fieldVisibility;

    @Override
    public ProjectOrderResponse save(ProjectOrderRequest req) {
        // 项目订单只能由"红人合作跟踪"联动生成（填了"客户方的项目订单"后系统自动新建），
        // 不支持通过这个接口直接新建，避免两边各自生成、内部项目编号不一致
        if (req.getId() == null) {
            throw new RuntimeException("项目订单不支持直接新建，请通过「红人合作跟踪」模块填写"
                    + "「客户方的项目订单」后由系统自动生成");
        }

        ProjectOrder order = projectOrderRepo.findByIdAndIsDeletedFalse(req.getId())
                .orElseThrow(() -> new RuntimeException("项目订单不存在：" + req.getId()));

        // 当前登录账号的字段可见/可编辑等级（FULL / 项目负责人 / 执行人员 / 基础），后面多处要用
        ProjectFieldVisibility.Context ctx = fieldVisibility.resolve();

        order.setProjectMonth(req.getProjectMonth());
        order.setProjectType(req.getProjectType());
        order.setClientOrderNo(req.getClientOrderNo());
        order.setCooperationContent(req.getCooperationContent());
        order.setVideoType(req.getVideoType());

        Brand brand = brandRepo.findByIdAndIsDeletedFalse(req.getBrandId())
                .orElseThrow(() -> new RuntimeException("品牌方不存在：" + req.getBrandId()));
        order.setBrand(brand);

        if (req.getInfluencerId() != null) {
            Influencer influencer = influencerRepo.findByIdAndIsDeletedFalse(req.getInfluencerId())
                    .orElseThrow(() -> new RuntimeException("红人不存在：" + req.getInfluencerId()));
            order.setInfluencer(influencer);
        }

        if (req.getProjectManagerId() != null) {
            Employee manager = employeeRepo.findByIdAndIsDeletedFalse(req.getProjectManagerId())
                    .orElseThrow(() -> new RuntimeException("员工不存在：" + req.getProjectManagerId()));
            order.setProjectManager(manager);
            if (req.getCommissionRate() == null && manager.getDefaultCommissionRate() != null
                    && ctx.isFull()) {
                order.setCommissionRate(manager.getDefaultCommissionRate());
            }
        }
        if (req.getCommissionRate() != null && ctx.isFull()) {
            order.setCommissionRate(req.getCommissionRate());
        }

        order.setClientPrice(req.getClientPrice());
        // 汇率仅 ADMIN 可修改：非 ADMIN 提交的 exchangeRate 会被忽略，
        // 保留数据库原值（新建场景下非 ADMIN 提交的汇率也不会生效，字段为空）
        if (RoleUtil.canEditExchangeRate()) {
            order.setExchangeRate(req.getExchangeRate());
        }
        order.setInfluencerCost(req.getInfluencerCost());

        // 其他外部成本 / 内部执行成本：按角色 + 是否本人负责/执行 决定能不能改
        // （不满足条件时忽略请求体里的值，保留数据库原值，不报错，简单地"改了也不生效"）
        boolean isOwnManager = ctx.employeeId != null && order.getProjectManager() != null
                && ctx.employeeId.equals(order.getProjectManager().getId());
        boolean isOwnExecutor = ctx.employeeId != null && order.getExecutor() != null
                && ctx.employeeId.equals(order.getExecutor().getId());

        if (ctx.isFull() || (ctx.tier == ProjectFieldVisibility.Tier.PROJECT_MANAGER && isOwnManager)) {
            order.setOtherExternalCost(req.getOtherExternalCost());
        }
        if (ctx.isFull()
                || (ctx.tier == ProjectFieldVisibility.Tier.PROJECT_MANAGER && isOwnManager)
                || (ctx.tier == ProjectFieldVisibility.Tier.EXECUTOR && isOwnExecutor)) {
            order.setInternalExecutionCost(req.getInternalExecutionCost());
        }

        // 甲方状态 / 内部状态：项目订单已经不支持"新建"（只能编辑已有记录），
        // 状态只能通过"状态流转"接口（updateStatus）修改，这里的编辑接口
        // 完全不理会请求体里的状态字段，保留数据库原值，防止误操作时顺带把状态也改了
        order.setContractSigned(req.getContractSigned());
        order.setExpectedReceiptDate(req.getExpectedReceiptDate());
        order.setActualReceiptDate(req.getActualReceiptDate());
        order.setReceivedAmount(req.getReceivedAmount());
        order.setNotes(req.getNotes());

        profitCalculator.calculate(order);

        return toResponse(projectOrderRepo.save(order));
    }

    @Override
    @Transactional(readOnly = true)
    public ProjectOrderResponse getById(Long id) {
        return toResponse(projectOrderRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("项目订单不存在：" + id)));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProjectOrderResponse> list(Long brandId, String projectMonth, String videoPublishMonth, ProjectType projectType,
                                           ClientStatus clientStatus, InternalSettlementStatus internalStatus,
                                           VideoType videoType, String internalProjectNo,
                                           Long influencerId, String accountName, Long projectManagerId,
                                           String keyword, Pageable pageable) {
        Page<ProjectOrderResponse> page = projectOrderRepo
                .findByFilters(brandId, projectMonth, videoPublishMonth, projectType, clientStatus, internalStatus, videoType,
                        internalProjectNo, influencerId, accountName, projectManagerId, keyword, pageable)
                .map(this::toResponse);

        // 批量标记"当前是否有待审核的删除申请"，避免逐行查库
        Set<Long> pendingIds = new HashSet<>(pendingApprovalService.findPendingTargetIds(
                PendingApprovalModule.PROJECT_ORDER));
        page.forEach(r -> r.setHasPendingDeleteRequest(pendingIds.contains(r.getId())));

        return page;
    }

    @Override
    public PendingApproval requestDelete(Long id, String reason) {
        ProjectOrder order = projectOrderRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("项目订单不存在：" + id));
        String summary = (order.getBrand() != null ? order.getBrand().getName() : "未知品牌")
                + " - " + (order.getInfluencer() != null ? order.getInfluencer().getAccountName() : "未知红人");
        return pendingApprovalService.requestDelete(
                PendingApprovalModule.PROJECT_ORDER, id, order.getInternalProjectNo(), summary, reason);
    }

    @Override
    @Transactional(readOnly = true)
    public MonthlySummaryResponse getMonthlySummary(String month) {
        List<ProjectOrder> orders = projectOrderRepo.findByProjectMonth(month);

        MonthlySummaryResponse summary = new MonthlySummaryResponse();
        summary.setMonth(month);
        summary.setTotalProjects(orders.size());

        // 汇总数字仅有权限的角色才能看到
        boolean canView = RoleUtil.canViewSensitiveFields();

        BigDecimal totalRevenue    = BigDecimal.ZERO;
        BigDecimal totalRmb        = BigDecimal.ZERO;
        BigDecimal totalInfCost    = BigDecimal.ZERO;
        BigDecimal totalOther      = BigDecimal.ZERO;
        BigDecimal totalExec       = BigDecimal.ZERO;
        BigDecimal totalGross      = BigDecimal.ZERO;
        BigDecimal totalDistrib    = BigDecimal.ZERO;
        BigDecimal totalCommission = BigDecimal.ZERO;
        BigDecimal totalNet        = BigDecimal.ZERO;

        Map<Long, MonthlySummaryResponse.ManagerCommissionItem> managerMap = new LinkedHashMap<Long, MonthlySummaryResponse.ManagerCommissionItem>();

        for (ProjectOrder o : orders) {
            if (canView) {
                totalRevenue    = totalRevenue.add(safe(o.getClientPrice()));
                totalRmb        = totalRmb.add(safe(o.getRmbRevenue()));
                totalInfCost    = totalInfCost.add(safe(o.getInfluencerCost()));
                totalOther      = totalOther.add(safe(o.getOtherExternalCost()));
                totalExec       = totalExec.add(safe(o.getInternalExecutionCost()));
                totalGross      = totalGross.add(safe(o.getGrossProfit()));
                totalDistrib    = totalDistrib.add(safe(o.getDistributableProfit()));
                totalCommission = totalCommission.add(safe(o.getCommissionAmount()));
                totalNet        = totalNet.add(safe(o.getCompanyNetProfit()));
            }

            if (canView && o.getProjectManagerId() != null) {
                Long mid = o.getProjectManagerId();
                Employee mgr = employeeCache.findById(mid);
                MonthlySummaryResponse.ManagerCommissionItem item = managerMap.get(mid);
                if (item == null) {
                    item = new MonthlySummaryResponse.ManagerCommissionItem();
                    item.setManagerId(mid);
                    item.setManagerName(mgr != null ? mgr.getName() : "未知");
                    item.setProjectCount(0);
                    item.setTotalCommission(BigDecimal.ZERO);
                    managerMap.put(mid, item);
                }
                item.setProjectCount(item.getProjectCount() + 1);
                item.setTotalCommission(item.getTotalCommission().add(safe(o.getCommissionAmount())));
            }
        }

        if (canView) {
            summary.setTotalClientRevenue(totalRevenue);
            summary.setTotalRmbRevenue(totalRmb);
            summary.setTotalInfluencerCost(totalInfCost);
            summary.setTotalOtherCost(totalOther);
            summary.setTotalExecCost(totalExec);
            summary.setTotalGrossProfit(totalGross);
            summary.setTotalDistributableProfit(totalDistrib);
            summary.setTotalCommissionAmount(totalCommission);
            summary.setTotalCompanyNetProfit(totalNet);
            summary.setManagerCommissions(new ArrayList<MonthlySummaryResponse.ManagerCommissionItem>(managerMap.values()));
        }

        return summary;
    }

    @Override
    public ProjectOrderResponse approve(Long id) {
        ProjectOrder order = projectOrderRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("项目订单不存在：" + id));
        if (order.getInternalStatus() != InternalSettlementStatus.PENDING_APPROVAL) {
            throw new RuntimeException("当前状态不是「待老板审核」，无法审核通过");
        }
        order.setInternalStatus(InternalSettlementStatus.CONFIRMED);
        return toResponse(projectOrderRepo.save(order));
    }

    @Override
    public ProjectOrderResponse reject(Long id) {
        ProjectOrder order = projectOrderRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("项目订单不存在：" + id));
        if (order.getInternalStatus() != InternalSettlementStatus.PENDING_APPROVAL) {
            throw new RuntimeException("当前状态不是「待老板审核」，无法驳回");
        }
        order.setInternalStatus(InternalSettlementStatus.CALCULATED);
        return toResponse(projectOrderRepo.save(order));
    }

    @Override
    public ProjectOrderResponse updateStatus(Long id, ClientStatus clientStatus, InternalSettlementStatus internalStatus) {
        ProjectOrder order = projectOrderRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("项目订单不存在：" + id));
        order.setClientStatus(clientStatus);
        order.setInternalStatus(internalStatus);
        return toResponse(projectOrderRepo.save(order));
    }

    /**
     * 内部执行成本弹窗用：根据执行人员的费率档位，算出这笔订单默认应该填多少钱，
     * 以及算出这个金额的依据说明。只读计算，不修改任何数据。
     *
     * 旧素材重发的分档规则（跟员工管理里维护的费率字段说明完全一致）：
     *   第1-50条一个价，第51-100条一个价，第101条及以上一个价，且第101条以上
     *   这部分的当月累计金额有封顶。"第几条"是按这个执行人员在这个视频发布月份下，
     *   已经赋值过内部执行成本的旧素材重发订单数量 + 1 来算的——没赋值的订单不算数。
     */
    @Override
    @Transactional(readOnly = true)
    public ExecutorCostSuggestionResponse suggestExecutorCost(Long orderId) {
        ProjectOrder order = projectOrderRepo.findByIdAndIsDeletedFalse(orderId)
                .orElseThrow(() -> new RuntimeException("项目订单不存在：" + orderId));
        ExecutorCostSuggestionResponse resp = new ExecutorCostSuggestionResponse();

        if (order.getExecutorId() == null) {
            resp.setBreakdown("该订单没有内部执行人员，无需设置内部执行成本");
            return resp;
        }
        Employee executor = employeeCache.findById(order.getExecutorId());
        if (executor == null) {
            resp.setBreakdown("执行人员信息不存在，请手动填写金额");
            return resp;
        }
        if (order.getVideoPublishDate() == null) {
            resp.setBreakdown("该订单尚未填写\"项目视频发布时间\"，无法按月计算，请手动填写金额");
            return resp;
        }
        if (order.getProjectManagerId() == null) {
            resp.setBreakdown("该订单尚未填写\"项目负责人\"，无法判断计算规则，请手动填写金额");
            return resp;
        }
        VideoType videoType = order.getVideoType();
        if (videoType == null) {
            resp.setBreakdown("该订单尚未填写\"项目视频类型\"，无法计算，请手动填写金额");
            return resp;
        }

        String month = new SimpleDateFormat("yyyyMM").format(order.getVideoPublishDate());
        String monthLabel = Integer.parseInt(month.substring(4)) + "月";

        // 关键业务规则：内部执行成本是不是按员工管理里维护的费率梯度算，取决于这条订单
        // 的项目负责人是不是"管理层"（目前系统里只有一个人是管理层）——
        //   - 是管理层：按费率梯度自动算出建议金额，这部分成本会影响公司利润
        //   - 不是管理层：说明这个执行人员的工资是这个项目负责人自己掏钱付的，
        //     系统不知道他们之间是怎么谈的价格，默认给0让他自己填，只是告诉他
        //     "这个执行人员这个月已经帮你结算过多少笔、分别是什么类型"作为参考，
        //     这部分金额不会影响公司利润（在 ProfitCalculator 里已经处理）
        if (!profitCalculator.isManagementOrder(order)) {
            resp.setSuggestedAmount(BigDecimal.ZERO);
            List<ProjectOrder> costed = projectOrderRepo.findCostedOrdersForExecutorAndManager(
                    executor.getId(), order.getProjectManagerId(), month);
            Map<VideoType, Long> countByType = new java.util.EnumMap<>(VideoType.class);
            for (ProjectOrder o : costed) {
                if (o.getVideoType() != null) countByType.merge(o.getVideoType(), 1L, Long::sum);
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
            sb.append("该执行人员不是管理层名下的人员，工资由你自行支付和约定，系统不提供参考金额，"
                    + "这笔钱也不会计入公司利润。");
            resp.setBreakdown(sb.toString());
            return resp;
        }

        switch (videoType) {
            case REAL_SHOT_NEW: {
                BigDecimal rate = executor.getRateRealShotNew();
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
                BigDecimal rate = executor.getRateAiNewMaterial();
                resp.setSuggestedAmount(rate);
                resp.setBreakdown(monthLabel + "该执行人员处理AI新素材：¥" + fmtAmount(rate));
                break;
            }
            case OLD_MATERIAL_REPOST: {
                List<ProjectOrder> costed = projectOrderRepo
                        .findCostedOldMaterialOrdersForExecutor(executor.getId(), order.getProjectManagerId(), month);
                int countSoFar = costed.size();
                int thisOrderNumber = countSoFar + 1;
                BigDecimal rate;
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
                    BigDecimal cap = executor.getOldMaterialMonthlyCap();
                    if (cap != null && rate != null) {
                        // 累计"第101条及以上"这部分已经挣到的钱（在这批已赋值的记录里，
                        // 排在第101个及以后的才算，前100个属于tier1/tier2，不计入这个封顶）
                        BigDecimal tier3EarnedSoFar = BigDecimal.ZERO;
                        for (int idx = 100; idx < costed.size(); idx++) {
                            BigDecimal c = costed.get(idx).getInternalExecutionCost();
                            if (c != null) tier3EarnedSoFar = tier3EarnedSoFar.add(c);
                        }
                        BigDecimal remaining = cap.subtract(tier3EarnedSoFar);
                        if (remaining.compareTo(BigDecimal.ZERO) < 0) remaining = BigDecimal.ZERO;
                        if (rate.compareTo(remaining) > 0) rate = remaining;
                    }
                }
                resp.setSuggestedAmount(rate);
                resp.setBreakdown(monthLabel + "该执行人员已经处理了" + countSoFar + "笔旧素材重发订单，该笔(第"
                        + thisOrderNumber + "笔)旧素材重发(" + tierLabel + ")：¥" + fmtAmount(rate));
                break;
            }
        }
        return resp;
    }

    private String fmtAmount(BigDecimal v) {
        return v == null ? "0.00" : v.setScale(2, java.math.RoundingMode.HALF_UP).toString();
    }

    /** 保存内部执行成本（跟状态流转分开，是独立的一步操作） */
    @Override
    @Transactional
    public ProjectOrderResponse setExecutorCost(Long orderId, BigDecimal amount) {
        ProjectOrder order = projectOrderRepo.findByIdAndIsDeletedFalse(orderId)
                .orElseThrow(() -> new RuntimeException("项目订单不存在：" + orderId));
        order.setInternalExecutionCost(amount);
        // 内部执行成本变了，项目毛利往下的可分配利润/负责人提成/公司利润这些都要跟着重新算一遍，
        // 不然数据库里存的还是设置这笔成本之前的旧值（列表页显示的就是这些存好的字段，
        // 编辑页面看到的是前端自己实时算的，两边对不上正是因为这里漏了这一步）
        profitCalculator.calculate(order);
        return toResponse(projectOrderRepo.save(order));
    }

    @Override
    public void exportExcel(String projectMonth, HttpServletResponse response) throws IOException {
        List<ProjectOrder> orders;
        if (projectMonth != null && !projectMonth.isEmpty()) {
            orders = projectOrderRepo.findByProjectMonth(projectMonth);
        } else {
            orders = projectOrderRepo.findAll().stream()
                    .filter(o -> !Boolean.TRUE.equals(o.getIsDeleted()))
                    .collect(Collectors.toList());
        }
        // 导出现在也按行区分权限了：项目负责人/执行人员导出时，不属于自己的行相关字段会显示"脱敏处理"
        excelHandler.export(orders, fieldVisibility.resolve(), response);
    }

    // ===== 转换（自动根据当前角色 + 是否本人负责/执行 脱敏）=====
    private ProjectOrderResponse toResponse(ProjectOrder o) {
        ProjectFieldVisibility.Context ctx = fieldVisibility.resolve();
        boolean isOwnManager = ctx.employeeId != null && o.getProjectManagerId() != null
                && ctx.employeeId.equals(o.getProjectManagerId());
        boolean isOwnExecutor = ctx.employeeId != null && o.getExecutorId() != null
                && ctx.employeeId.equals(o.getExecutorId());
        boolean isManagerTier  = ctx.tier == ProjectFieldVisibility.Tier.PROJECT_MANAGER;
        boolean isExecutorTier = ctx.tier == ProjectFieldVisibility.Tier.EXECUTOR;

        ProjectOrderResponse r = new ProjectOrderResponse();
        r.setId(o.getId());
        r.setInternalProjectNo(o.getInternalProjectNo());
        r.setClientOrderNo(o.getClientOrderNo());
        r.setProjectMonth(o.getProjectMonth());
        r.setVideoPublishDate(o.getVideoPublishDate());
        r.setProjectType(o.getProjectType());
        r.setProjectTypeLabel(o.getProjectType() != null ? o.getProjectType().getLabel() : null);
        r.setCooperationContent(o.getCooperationContent());
        r.setVideoType(o.getVideoType());
        r.setVideoTypeLabel(o.getVideoType() != null ? o.getVideoType().getLabel() : null);

        // brand 和 projectManager 走缓存，influencer 由 @EntityGraph 提前 JOIN 进来
        Brand brand = brandCache.findById(o.getBrandId());
        if (brand != null) {
            r.setBrandId(brand.getId());
            r.setBrandName(brand.getName());
        }
        if (o.getInfluencer() != null) {
            r.setInfluencerId(o.getInfluencer().getId());
            r.setInfluencerTeam(o.getInfluencer().getTeamName());
            r.setInfluencerAccount(o.getInfluencer().getAccountName());
        }
        Employee manager = employeeCache.findById(o.getProjectManagerId());
        if (manager != null) {
            r.setProjectManagerId(manager.getId());
            r.setProjectManagerName(manager.getName());
        }
        Employee executor = employeeCache.findById(o.getExecutorId());
        if (executor != null) {
            r.setExecutorId(executor.getId());
            r.setExecutorName(executor.getName());
        }
        InfluencerTeam team = teamCache.findById(o.getTeamId());
        if (team != null) {
            r.setTeamId(team.getId());
            r.setTeamName(team.getName());
        }

        // ===== 红人成本/客户合作价格/已到账金额：现在是"基础字段"，GUEST 之外都能看 =====
        r.setCurrency("美元");
        r.setExchangeRate(o.getExchangeRate());
        boolean canSeeBaseline = ctx.tier != ProjectFieldVisibility.Tier.GUEST;
        r.setInfluencerCost(canSeeBaseline ? o.getInfluencerCost() : null);
        r.setClientPrice(canSeeBaseline ? o.getClientPrice() : null);

        // ===== 其他外部成本：FULL 都能看；项目负责人仅自己负责的项目能看，其余"—"（null） =====
        if (ctx.isFull() || (isManagerTier && isOwnManager)) {
            r.setOtherExternalCost(o.getOtherExternalCost());
        }

        // ===== 内部执行成本：FULL 都能看；项目负责人/执行人员仅自己的项目能看，其余"—" =====
        if (ctx.isFull() || (isManagerTier && isOwnManager) || (isExecutorTier && isOwnExecutor)) {
            r.setInternalExecutionCost(o.getInternalExecutionCost());
        }

        // ===== 提成比例 + 提成金额：FULL 都能看/改；项目负责人仅自己负责的项目只读可见；执行人员完全不可见 =====
        if (ctx.isFull() || (isManagerTier && isOwnManager)) {
            r.setCommissionRate(o.getCommissionRate());
            r.setCommissionAmount(o.getCommissionAmount());
        }

        // ===== 纯利润字段：可分配利润/项目毛利/公司利润，只有 FULL（ADMIN、财务、管理层）能看 =====
        if (ctx.isFull()) {
            r.setRmbRevenue(o.getRmbRevenue());
            r.setGrossProfit(o.getGrossProfit());
            r.setDistributableProfit(o.getDistributableProfit());
            r.setCompanyNetProfit(o.getCompanyNetProfit());
        }

        // 非敏感字段（所有角色可见）
        r.setClientStatus(o.getClientStatus());
        r.setClientStatusLabel(o.getClientStatus() != null ? o.getClientStatus().getLabel() : null);
        r.setContractSigned(o.getContractSigned());
        r.setExpectedReceiptDate(o.getExpectedReceiptDate());
        r.setActualReceiptDate(o.getActualReceiptDate());
        r.setReceivedAmount(canSeeBaseline ? o.getReceivedAmount() : null);

        r.setInternalStatus(o.getInternalStatus());
        r.setInternalStatusLabel(o.getInternalStatus() != null ? o.getInternalStatus().getLabel() : null);

        r.setNotes(o.getNotes());
        r.setCreatedAt(o.getCreatedAt());
        r.setUpdatedAt(o.getUpdatedAt());
        return r;
    }

    private BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
