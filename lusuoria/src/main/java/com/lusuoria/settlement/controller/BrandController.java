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

    /** 能不能新增/编辑/删除品牌方——员工角色="管理层" */
    private boolean canManage() {
        return "管理层".equals(employeeRoleUtil.getCurrentEmployeeRole());
    }

    /** 品牌方列表（走 BrandCache，不查库），"品牌方/红人团队管理"页面用 */
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

    /** 单个品牌方详情（走 BrandCache） */
    @GetMapping("/{id}")
    public ApiResponse<Brand> getById(@PathVariable Long id) {
        Brand brand = brandCache.findById(id);
        if (brand == null) throw new RuntimeException("品牌方不存在");
        return ApiResponse.success(brand);
    }

    /** 全量品牌方导出 Excel，不带筛选（品牌方列表本身不分页/不筛选，直接导全部） */
    @GetMapping("/export/excel")
    public void exportExcel(HttpServletResponse response) throws IOException {
        excelHandler.export(brandCache.getAll(), response);
    }

    /**
     * 新建/编辑品牌方（req.getId() 为空即新建）。name 唯一约束不认软删除，命中同名已软删除记录时
     * 直接复活那一行（见下方注释），不是插入新行；保存后刷新 BrandCache。
     */
    @PostMapping
    public ApiResponse<Brand> save(@Valid @RequestBody BrandRequest req) {
        if (!canManage()) return ApiResponse.error(403, req.getId() == null ? "无权限新增品牌方" : "无权限编辑品牌方");
        // name 数据库层面有唯一约束，不认软删除——品牌方被软删除后这个名字仍然被那一行占用着，
        // 之前新建只查未软删除的记录（existsByNameAndIsDeletedFalse），删除某品牌方后再用
        // 同名重新添加会在这一步放行、真正 insert 时才撞唯一键报错（2026-08 修复）。改成
        // 不限 isDeleted 查一次：命中已软删除的同名记录时复活它（原地复用，不插入新行），
        // 命中未删除的记录、或编辑改名撞上别的品牌方（不管对方是否已软删除）都直接拦下
        Brand brand;
        if (req.getId() != null) {
            brand = brandRepo.findByIdAndIsDeletedFalse(req.getId())
                    .orElseThrow(() -> new RuntimeException("品牌方不存在"));
            if (!req.getName().equals(brand.getName())) {
                brandRepo.findByName(req.getName()).ifPresent(existing -> {
                    throw new RuntimeException("品牌方名称已存在：" + req.getName());
                });
            }
        } else {
            Brand existing = brandRepo.findByName(req.getName()).orElse(null);
            if (existing != null && Boolean.TRUE.equals(existing.getIsDeleted())) {
                brand = existing;
                brand.setIsDeleted(false);
            } else if (existing != null) {
                throw new RuntimeException("品牌方名称已存在：" + req.getName());
            } else {
                brand = new Brand();
                brand.setIsDeleted(false);
            }
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
        brand.setDefaultInvolvesCorporateInvoice(req.getDefaultInvolvesCorporateInvoice());
        Brand saved = brandRepo.save(brand);
        brandCache.refresh();
        return ApiResponse.success(saved);
    }

    /** 软删除品牌方（isDeleted=true），保存后刷新 BrandCache */
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
