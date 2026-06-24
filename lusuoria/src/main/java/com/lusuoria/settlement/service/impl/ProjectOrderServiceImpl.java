package com.lusuoria.settlement.service.impl;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.dto.request.ProjectOrderRequest;
import com.lusuoria.settlement.dto.response.MonthlySummaryResponse;
import com.lusuoria.settlement.dto.response.ProjectOrderResponse;
import com.lusuoria.settlement.entity.*;
import com.lusuoria.settlement.enums.ClientStatus;
import com.lusuoria.settlement.enums.InternalSettlementStatus;
import com.lusuoria.settlement.enums.ProjectType;
import com.lusuoria.settlement.excel.ProjectOrderExcelHandler;
import com.lusuoria.settlement.repository.*;
import com.lusuoria.settlement.service.ProjectOrderService;
import com.lusuoria.settlement.util.ProfitCalculator;
import com.lusuoria.settlement.util.ProjectNoGenerator;
import com.lusuoria.settlement.util.RoleUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
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
    @Autowired private ProfitCalculator profitCalculator;
    @Autowired private ProjectNoGenerator projectNoGenerator;
    @Autowired private ProjectOrderExcelHandler excelHandler;

    @Override
    public ProjectOrderResponse save(ProjectOrderRequest req) {
        ProjectOrder order;
        if (req.getId() != null) {
            order = projectOrderRepo.findByIdAndIsDeletedFalse(req.getId())
                    .orElseThrow(() -> new RuntimeException("项目订单不存在：" + req.getId()));
        } else {
            order = new ProjectOrder();
            order.setIsDeleted(false);
        }

        order.setProjectMonth(req.getProjectMonth());
        order.setProjectType(req.getProjectType());
        order.setClientOrderNo(req.getClientOrderNo());
        order.setCooperationContent(req.getCooperationContent());
        order.setIsOwnResource(Boolean.TRUE.equals(req.getIsOwnResource()));

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
            if (req.getCommissionRate() == null && manager.getDefaultCommissionRate() != null) {
                order.setCommissionRate(manager.getDefaultCommissionRate());
            }
        }
        if (req.getCommissionRate() != null) {
            order.setCommissionRate(req.getCommissionRate());
        }

        order.setClientPrice(req.getClientPrice());
        order.setExchangeRate(req.getExchangeRate());
        order.setInfluencerCost(req.getInfluencerCost());
        order.setOtherExternalCost(req.getOtherExternalCost());
        order.setInternalExecutionCost(req.getInternalExecutionCost());

        if (req.getClientStatus() != null) order.setClientStatus(req.getClientStatus());
        if (req.getInternalStatus() != null) order.setInternalStatus(req.getInternalStatus());
        order.setContractSigned(req.getContractSigned());
        order.setExpectedReceiptDate(req.getExpectedReceiptDate());
        order.setActualReceiptDate(req.getActualReceiptDate());
        order.setReceivedAmount(req.getReceivedAmount());
        order.setNotes(req.getNotes());

        profitCalculator.calculate(order);

        if (req.getId() == null) {
            String accountName = order.getInfluencer() != null
                    ? order.getInfluencer().getAccountName() : "general";
            long count = projectOrderRepo.countByBrandAndMonth(brand.getId(), req.getProjectMonth());
            order.setInternalProjectNo(
                    projectNoGenerator.generate(brand.getName(), req.getProjectMonth(), accountName, count));
        }

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
    public Page<ProjectOrderResponse> list(Long brandId, String projectMonth, ProjectType projectType,
                                           ClientStatus clientStatus, InternalSettlementStatus internalStatus,
                                           Long influencerId, String accountName, Long projectManagerId,
                                           String keyword, Pageable pageable) {
        return projectOrderRepo
                .findByFilters(brandId, projectMonth, projectType, clientStatus, internalStatus,
                        influencerId, accountName, projectManagerId, keyword, pageable)
                .map(this::toResponse);
    }

    @Override
    public void delete(Long id) {
        ProjectOrder order = projectOrderRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("项目订单不存在：" + id));
        order.setIsDeleted(true);
        projectOrderRepo.save(order);
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
    public void exportExcel(String projectMonth, HttpServletResponse response) throws IOException {
        List<ProjectOrder> orders;
        if (projectMonth != null && !projectMonth.isEmpty()) {
            orders = projectOrderRepo.findByProjectMonth(projectMonth);
        } else {
            orders = projectOrderRepo.findAll().stream()
                    .filter(o -> !Boolean.TRUE.equals(o.getIsDeleted()))
                    .collect(Collectors.toList());
        }
        // 传入当前角色是否可见敏感字段
        excelHandler.export(orders, RoleUtil.canViewSensitiveFields(), response);
    }

    @Override
    public List<String> importExcel(MultipartFile file) throws IOException {
        return excelHandler.importData(file, this);
    }

    // ===== 转换（自动根据当前角色脱敏）=====
    private ProjectOrderResponse toResponse(ProjectOrder o) {
        boolean canView = RoleUtil.canViewSensitiveFields();

        ProjectOrderResponse r = new ProjectOrderResponse();
        r.setId(o.getId());
        r.setInternalProjectNo(o.getInternalProjectNo());
        r.setClientOrderNo(o.getClientOrderNo());
        r.setProjectMonth(o.getProjectMonth());
        r.setProjectType(o.getProjectType());
        r.setProjectTypeLabel(o.getProjectType() != null ? o.getProjectType().getLabel() : null);
        r.setCooperationContent(o.getCooperationContent());
        r.setIsOwnResource(o.getIsOwnResource());

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

        // 非敏感成本字段（所有角色可见）
        r.setCurrency("美元");
        r.setExchangeRate(o.getExchangeRate());
        r.setInfluencerCost(o.getInfluencerCost());

        // 敏感字段：仅 ADMIN / AUDITOR 可见
        if (canView) {
            r.setClientPrice(o.getClientPrice());
            r.setRmbRevenue(o.getRmbRevenue());

            r.setOtherExternalCost(o.getOtherExternalCost());
            r.setInternalExecutionCost(o.getInternalExecutionCost());

            r.setGrossProfit(o.getGrossProfit());
            r.setDistributableProfit(o.getDistributableProfit());
            r.setCommissionRate(o.getCommissionRate());
            r.setCommissionAmount(o.getCommissionAmount());
            r.setCompanyNetProfit(o.getCompanyNetProfit());
        }
        // 非敏感字段（所有角色可见）
        r.setClientStatus(o.getClientStatus());
        r.setClientStatusLabel(o.getClientStatus() != null ? o.getClientStatus().getLabel() : null);
        r.setContractSigned(o.getContractSigned());
        r.setExpectedReceiptDate(o.getExpectedReceiptDate());
        r.setActualReceiptDate(o.getActualReceiptDate());
        r.setReceivedAmount(o.getReceivedAmount());

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
