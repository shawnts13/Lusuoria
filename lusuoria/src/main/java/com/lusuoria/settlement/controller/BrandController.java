package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.InfluencerTeamCache;
import com.lusuoria.settlement.dto.request.BrandRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.dto.response.BrandTeamOption;
import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.entity.InfluencerTeam;
import com.lusuoria.settlement.excel.BrandExcelHandler;
import com.lusuoria.settlement.repository.BrandRepository;
import com.lusuoria.settlement.repository.InfluencerBrandTeamRepository;
import com.lusuoria.settlement.util.EmployeeRoleUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 品牌方管理。查询类接口（list/getById/export/team-options）对所有登录角色开放——
 * 红人合作跟踪、红人结款等模块的下拉框/筛选都要读品牌方这份基础数据，不能锁死。
 * 新增/编辑/删除这几个写操作严格按员工角色限制，只有"管理层"能做（2026-07 起，
 * 不再是 ADMIN/STAFF 这种 SysUser.role 判断，参照红人结款模块 PaymentAccessUtil 的同款设计）。
 * （2026-07-30 起去掉了 Excel 导入/下载模板功能，只保留导出——数据量不大，改成都走
 * 页面手动新增/编辑，不再需要批量导入这条路径。）
 */
@RestController
@RequestMapping("/api/brands")
public class BrandController {

    @Autowired private BrandRepository brandRepo;
    @Autowired private BrandExcelHandler excelHandler;
    @Autowired private BrandCache brandCache;
    @Autowired private InfluencerBrandTeamRepository influencerBrandTeamRepo;
    @Autowired private InfluencerTeamCache teamCache;
    @Autowired private EmployeeRoleUtil employeeRoleUtil;

    private boolean canManage() {
        return "管理层".equals(employeeRoleUtil.getCurrentEmployeeRole());
    }

    @GetMapping
    public ApiResponse<List<Brand>> list() {
        return ApiResponse.success(brandCache.getAll());
    }

    /**
     * 该品牌方下（不限具体红人）出现过的团队选项，供"红人结款"新建结款记录时
     * "先选品牌方，再选该品牌方下的红人团队"级联选择用。
     */
    @GetMapping("/{id}/team-options")
    public ApiResponse<List<BrandTeamOption>> teamOptions(@PathVariable Long id) {
        List<BrandTeamOption> result = new ArrayList<>();
        boolean hasNoTeamOption = false;
        for (Long teamId : influencerBrandTeamRepo.findDistinctTeamIdsByBrandId(id)) {
            if (teamId == null) {
                hasNoTeamOption = true;
                continue;
            }
            InfluencerTeam team = teamCache.findById(teamId);
            if (team != null) result.add(new BrandTeamOption(team.getId(), team.getName()));
        }
        result.sort((a, b) -> a.getTeamName().compareTo(b.getTeamName()));
        if (hasNoTeamOption) result.add(new BrandTeamOption(null, null));
        return ApiResponse.success(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<Brand> getById(@PathVariable Long id) {
        Brand brand = brandCache.findById(id);
        if (brand == null) throw new RuntimeException("品牌方不存在");
        return ApiResponse.success(brand);
    }

    @GetMapping("/export/excel")
    public void exportExcel(HttpServletResponse response) throws IOException {
        excelHandler.export(brandCache.getAll(), response);
    }

    @PostMapping
    public ApiResponse<Brand> save(@Valid @RequestBody BrandRequest req) {
        if (!canManage()) return ApiResponse.error(403, req.getId() == null ? "无权限新增品牌方" : "无权限编辑品牌方");
        Brand brand;
        if (req.getId() != null) {
            brand = brandRepo.findByIdAndIsDeletedFalse(req.getId())
                    .orElseThrow(() -> new RuntimeException("品牌方不存在"));
        } else {
            if (brandRepo.existsByNameAndIsDeletedFalse(req.getName()))
                throw new RuntimeException("品牌方名称已存在：" + req.getName());
            brand = new Brand();
            brand.setIsDeleted(false);
        }
        brand.setName(req.getName());
        brand.setCountryMarket(req.getCountryMarket());
        brand.setContactPerson(req.getContactPerson());
        brand.setSettlementCurrency(req.getSettlementCurrency());
        brand.setPaymentCycleType(req.getPaymentCycleType());
        brand.setCostThresholdAmount(req.getCostThresholdAmount());
        brand.setDaysWithinThreshold(req.getDaysWithinThreshold());
        brand.setDaysAboveThreshold(req.getDaysAboveThreshold());
        brand.setDaysAfterMonthEnd(req.getDaysAfterMonthEnd());
        brand.setNotes(req.getNotes());
        brand.setRequiresInvoice(req.getRequiresInvoice());
        brand.setContractCycleType(req.getContractCycleType());
        brand.setInvolvesClientOrderId(req.getInvolvesClientOrderId());
        brand.setInvolvesClientPaymentBatch(req.getInvolvesClientPaymentBatch());
        Brand saved = brandRepo.save(brand);
        brandCache.refresh();
        return ApiResponse.success(saved);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        if (!canManage()) return ApiResponse.error(403, "无权限删除品牌方");
        Brand brand = brandRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("品牌方不存在"));
        brand.setIsDeleted(true);
        brandRepo.save(brand);
        brandCache.refresh();
        return ApiResponse.success();
    }
}
