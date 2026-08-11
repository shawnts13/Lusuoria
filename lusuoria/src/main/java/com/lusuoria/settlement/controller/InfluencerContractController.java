package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.InfluencerTeamCache;
import com.lusuoria.settlement.dto.request.InfluencerContractRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.entity.InfluencerContract;
import com.lusuoria.settlement.entity.InfluencerTeam;
import com.lusuoria.settlement.repository.InfluencerContractRepository;
import com.lusuoria.settlement.repository.InfluencerRepository;
import com.lusuoria.settlement.util.EmployeeRoleUtil;
import com.lusuoria.settlement.util.RoleUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 红人已签署合同（"红人管理"编辑弹窗里的"已签署合同"区块）。
 * 一个红人可以有多条，按 (品牌方,团队) 各自维护任意有效期区间，"新增"/"编辑"各自独立操作，
 * 不走整份表单的 save()。
 */
@RestController
@RequestMapping("/api/influencer-contracts")
public class InfluencerContractController {

    @Autowired private InfluencerContractRepository contractRepo;
    @Autowired private InfluencerRepository influencerRepo;
    @Autowired private BrandCache brandCache;
    @Autowired private InfluencerTeamCache teamCache;
    @Autowired private EmployeeRoleUtil employeeRoleUtil;

    // SimpleDateFormat 本身不是线程安全的，Controller 是单例，并发请求共用同一个 static 实例
    // 会导致日期格式化结果错乱甚至抛异常——改成 ThreadLocal，每个请求线程各用各的实例
    private static final ThreadLocal<SimpleDateFormat> DATE_FMT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));

    /** 某个红人的合同列表（有效期起始日期倒序），供"红人管理"编辑弹窗展示 */
    @GetMapping("/by-influencer/{influencerId}")
    public ApiResponse<List<InfluencerContract>> byInfluencer(@PathVariable Long influencerId) {
        return ApiResponse.success(contractRepo.findByInfluencerIdOrderByStartDateDesc(influencerId));
    }

    /**
     * 批量按红人 id 取合同，返回 influencerId -> 该红人名下的全部合同列表。
     * 供"红人需求管理"列表页按每条需求自己的品牌方/团队/需求月份，交叉核对该红人在这个
     * 品牌方这个团队下是否已经有一条"需求月份落在有效期内"的合同（品牌方"一年签一次合同"场景），
     * 具体的品牌方/团队/日期区间匹配逻辑在前端做，这里只负责一次性把数据都吐出来，避免逐条查库。
     */
    @GetMapping("/by-influencer-ids")
    public ApiResponse<Map<Long, List<InfluencerContract>>> byInfluencerIds(@RequestParam List<Long> ids) {
        Map<Long, List<InfluencerContract>> result = new HashMap<>();
        for (InfluencerContract c : contractRepo.findByInfluencerIdIn(ids)) {
            result.computeIfAbsent(c.getInfluencerId(), k -> new java.util.ArrayList<>()).add(c);
        }
        return ApiResponse.success(result);
    }

    @PostMapping
    public ApiResponse<InfluencerContract> create(@Valid @RequestBody InfluencerContractRequest req) {
        assertCanManageContracts();
        Influencer influencer = influencerRepo.findByIdAndIsDeletedFalse(req.getInfluencerId())
                .orElseThrow(() -> new RuntimeException("红人不存在：" + req.getInfluencerId()));
        Brand brand = resolveBrand(req.getBrandId());
        InfluencerTeam team = resolveTeam(req.getTeamId());
        validateDateRange(req);
        rejectOverlap(req, null);

        InfluencerContract contract = new InfluencerContract();
        contract.setInfluencer(influencer);
        contract.setBrand(brand);
        contract.setTeam(team);
        contract.setStartDate(req.getStartDate());
        contract.setEndDate(req.getEndDate());
        contract.setContractLink(req.getContractLink());
        return ApiResponse.success(contractRepo.save(contract));
    }

    @PutMapping("/{id}")
    public ApiResponse<InfluencerContract> update(@PathVariable Long id, @Valid @RequestBody InfluencerContractRequest req) {
        assertCanManageContracts();
        InfluencerContract contract = contractRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("合同记录不存在：" + id));
        Brand brand = resolveBrand(req.getBrandId());
        InfluencerTeam team = resolveTeam(req.getTeamId());
        validateDateRange(req);
        rejectOverlap(req, id);

        contract.setBrand(brand);
        contract.setTeam(team);
        contract.setStartDate(req.getStartDate());
        contract.setEndDate(req.getEndDate());
        contract.setContractLink(req.getContractLink());
        return ApiResponse.success(contractRepo.save(contract));
    }

    /**
     * 硬删除（不是软删除）：这张表本身就没有 isDeleted 字段、没走软删除那一套（见
     * InfluencerContract 类注释），合同记录本来就是"新增一条/编辑一条"的独立 CRUD，
     * 删除就直接把数据库行删掉，方便手动清理很久以前（比如2年前）的历史合同。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        assertCanManageContracts();
        if (!contractRepo.existsById(id)) {
            throw new RuntimeException("合同记录不存在：" + id);
        }
        contractRepo.deleteById(id);
        return ApiResponse.success();
    }

    /**
     * 2026-08 新增：谁能维护"已签署合同"——原来固定 @PreAuthorize("hasAnyRole('ADMIN','STAFF')")，
     * 现在额外放开给员工角色="法务"的账号（哪怕 SysUser 角色是只读的 AUDITOR，见"账号管理"角色
     * 标签"财务/法务"）。Employee.role 不在 JWT 里，没法直接写在 @PreAuthorize 的 SpEL 表达式上，
     * 所以改成方法内部手动判断，其余合同相关的这三个写接口保持完全一样的权限口径。
     */
    private void assertCanManageContracts() {
        if (RoleUtil.canWrite()) return;
        if ("法务".equals(employeeRoleUtil.getCurrentEmployeeRole())) return;
        throw new RuntimeException("无权限维护红人已签署合同信息");
    }

    private Brand resolveBrand(Long brandId) {
        Brand brand = brandCache.findById(brandId);
        if (brand == null) throw new RuntimeException("品牌方不存在：" + brandId);
        if (brand.isPerRequirementContract()) {
            throw new RuntimeException("该品牌方是\"一次需求签一次合同\"，请在红人需求管理处上传合同");
        }
        return brand;
    }

    private InfluencerTeam resolveTeam(Long teamId) {
        if (teamId == null) return null;
        InfluencerTeam team = teamCache.findById(teamId);
        if (team == null) throw new RuntimeException("红人团队不存在：" + teamId);
        return team;
    }

    private void validateDateRange(InfluencerContractRequest req) {
        if (req.getStartDate().after(req.getEndDate())) {
            throw new RuntimeException("合同生效日期不能晚于失效日期");
        }
    }

    private void rejectOverlap(InfluencerContractRequest req, Long excludeId) {
        List<InfluencerContract> overlapping = contractRepo.findOverlapping(
                req.getInfluencerId(), req.getBrandId(), req.getTeamId(),
                req.getStartDate(), req.getEndDate(), excludeId);
        if (!overlapping.isEmpty()) {
            String ranges = overlapping.stream()
                    .map(c -> DATE_FMT.get().format(c.getStartDate()) + " 至 " + DATE_FMT.get().format(c.getEndDate()))
                    .collect(Collectors.joining("、"));
            throw new RuntimeException("该红人在这个品牌方/团队下，已有有效期重叠的合同记录（" + ranges + "），请先编辑或调整已有记录");
        }
    }
}
