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
 * 只能在这里做（红人管理表单里原来的内联新建入口已经收紧掉，见 InfluencerFormModal；2026-08
 * 起红人 Excel 导入 InfluencerExcelHandler 遇到不存在的团队名也不再自动创建，改成报行错误，
 * 统一收紧成"只有这一个新建入口"）。
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

    /** 能不能新增/编辑/删除红人团队——员工角色="管理层" */
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

        // name 数据库层面有唯一约束，不认软删除——之前新建只查未软删除的记录
        // （existsByNameAndIsDeletedFalse），删除某团队后再用同名重新添加会在这一步放行、
        // 真正 insert 时才撞唯一键报错（2026-08 修复）。改成不限 isDeleted 查一次
        // （teamRepo.findByName，之前就已经加好了但一直没接上）：命中已软删除的同名记录时
        // 复活它，命中未删除的记录、或编辑改名撞上别的团队（不管对方是否已软删除）都直接拦下
        String name = req.getName().trim();
        InfluencerTeam team;
        if (req.getId() != null) {
            team = teamRepo.findById(req.getId())
                    .filter(t -> !Boolean.TRUE.equals(t.getIsDeleted()))
                    .orElseThrow(() -> new RuntimeException("团队不存在"));
            if (!name.equals(team.getName())) {
                teamRepo.findByName(name).ifPresent(existing -> {
                    throw new RuntimeException("团队名称已存在：" + name);
                });
            }
        } else {
            InfluencerTeam existing = teamRepo.findByName(name).orElse(null);
            if (existing != null && Boolean.TRUE.equals(existing.getIsDeleted())) {
                team = existing;
                team.setIsDeleted(false);
            } else if (existing != null) {
                throw new RuntimeException("团队名称已存在：" + name);
            } else {
                team = new InfluencerTeam();
                team.setIsDeleted(false);
            }
        }
        team.setName(name);
        team.setBrandId(req.getBrandId());
        // 2026-08-21 修复：forcePerRequirementContract 之前强制用 Boolean.TRUE.equals(...) 转成
        // 纯布尔值——这个字段原本只支持"品牌方一年一签→团队覆盖成每需求一签"单方向，null 和
        // false 在旧逻辑下效果完全等价（都是"不覆盖"），所以强转成 false 当时是无害的。现在改成
        // 双向覆盖（InfluencerTeam.isPerRequirementContract()），false 变成了一个有意义的独立
        // 状态（"强制：一年签一次合同"），必须原样透传 null/true/false，不能再强转——跟下面
        // involvesCorporateInvoice 是同一个道理（那个字段当初就设计对了，这次照抄它的写法）
        team.setForcePerRequirementContract(req.getForcePerRequirementContract());
        team.setDefaultContractEndDate(req.getDefaultContractEndDate());
        // 三态字段，原样透传，不能用 Boolean.TRUE.equals(...) 强转，否则"跟随默认"这个状态就没法表达了
        team.setInvolvesCorporateInvoice(req.getInvolvesCorporateInvoice());
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
