package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.InfluencerTeamCache;
import com.lusuoria.settlement.dto.request.InfluencerTeamRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.entity.InfluencerTeam;
import com.lusuoria.settlement.repository.InfluencerTeamRepository;
import com.lusuoria.settlement.util.EmployeeRoleUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * 红人团队管理（2026-07 起并入"品牌方/红人团队管理"模块）：团队归属唯一品牌方，新建/编辑
 * 只能在这里做（红人管理表单里原来的内联新建入口已经收紧掉，见 InfluencerFormModal）。
 * 查询接口对所有登录角色开放——合作跟踪、需求管理等模块的下拉框/级联选择都要读团队数据；
 * 新增/编辑/删除只有"管理层"能做，权限判定风格跟 BrandController 保持一致。
 */
@RestController
@RequestMapping("/api/influencer-teams")
public class InfluencerTeamController {

    @Autowired private InfluencerTeamRepository teamRepo;
    @Autowired private InfluencerTeamCache teamCache;
    @Autowired private BrandCache brandCache;
    @Autowired private EmployeeRoleUtil employeeRoleUtil;

    private boolean canManage() {
        return "管理层".equals(employeeRoleUtil.getCurrentEmployeeRole());
    }

    /** 获取所有团队（不区分品牌方，部分老页面的下拉筛选用） */
    @GetMapping
    public ApiResponse<List<InfluencerTeam>> list() {
        return ApiResponse.success(teamCache.getAll());
    }

    /** 某个品牌方下的团队列表（"品牌方/红人团队管理"页面用） */
    @GetMapping("/by-brand/{brandId}")
    public ApiResponse<List<InfluencerTeam>> listByBrand(@PathVariable Long brandId) {
        return ApiResponse.success(teamCache.findByBrandId(brandId));
    }

    /** 新增/编辑团队 */
    @PostMapping
    public ApiResponse<InfluencerTeam> save(@Valid @RequestBody InfluencerTeamRequest req) {
        if (!canManage()) return ApiResponse.error(403, req.getId() == null ? "无权限新增团队" : "无权限编辑团队");
        Brand brand = brandCache.findById(req.getBrandId());
        if (brand == null) throw new RuntimeException("品牌方不存在");

        String name = req.getName().trim();
        InfluencerTeam team;
        if (req.getId() != null) {
            team = teamRepo.findById(req.getId())
                    .filter(t -> !Boolean.TRUE.equals(t.getIsDeleted()))
                    .orElseThrow(() -> new RuntimeException("团队不存在"));
            if (!name.equals(team.getName()) && teamRepo.existsByNameAndIsDeletedFalse(name)) {
                throw new RuntimeException("团队名称已存在：" + name);
            }
        } else {
            if (teamRepo.existsByNameAndIsDeletedFalse(name)) {
                throw new RuntimeException("团队名称已存在：" + name);
            }
            team = new InfluencerTeam();
            team.setIsDeleted(false);
        }
        team.setName(name);
        team.setBrandId(req.getBrandId());
        team.setForcePerRequirementContract(Boolean.TRUE.equals(req.getForcePerRequirementContract()));
        team.setDefaultContractEndDate(req.getDefaultContractEndDate());
        InfluencerTeam saved = teamRepo.save(team);
        teamCache.refresh();
        return ApiResponse.success(saved);
    }

    /** 删除团队（仅管理层） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        if (!canManage()) return ApiResponse.error(403, "无权限删除团队");
        InfluencerTeam team = teamRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("团队不存在"));
        team.setIsDeleted(true);
        teamRepo.save(team);
        teamCache.refresh();
        return ApiResponse.success();
    }
}
