package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.InfluencerTeamCache;
import com.lusuoria.settlement.dto.request.TeamContractRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.Brand;
import com.lusuoria.settlement.entity.InfluencerTeam;
import com.lusuoria.settlement.entity.TeamContract;
import com.lusuoria.settlement.repository.TeamContractRepository;
import com.lusuoria.settlement.util.EmployeeRoleUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 品牌方/团队级已签署合同（2026-08 新增，"品牌方/红人团队管理"-"管理团队"里"上传合同"用，
 * 替代原来挂在红人身上的 InfluencerContract）。一个 (品牌方,团队) 组合可以有多条，按有效期
 * 区间各自独立维护，"新增"/"编辑"各自独立操作，不走整份表单的 save()。
 */
@RestController
@RequestMapping("/api/team-contracts")
public class TeamContractController {

    @Autowired private TeamContractRepository contractRepo;
    @Autowired private BrandCache brandCache;
    @Autowired private InfluencerTeamCache teamCache;
    @Autowired private EmployeeRoleUtil employeeRoleUtil;

    // SimpleDateFormat 本身不是线程安全的，Controller 是单例，并发请求共用同一个 static 实例
    // 会导致日期格式化结果错乱甚至抛异常——改成 ThreadLocal，每个请求线程各用各的实例
    private static final ThreadLocal<SimpleDateFormat> DATE_FMT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));

    /** 某个团队的合同列表，供"管理团队"弹窗展示 */
    @GetMapping("/by-team/{teamId}")
    public ApiResponse<List<TeamContract>> byTeam(@PathVariable Long teamId) {
        return ApiResponse.success(contractRepo.findByTeamIdOrderByStartDateDesc(teamId));
    }

    /** 该品牌方下"没有团队"的合同列表（该品牌方本身没有团队层的场景，见 TeamContract 类注释） */
    @GetMapping("/by-brand-no-team/{brandId}")
    public ApiResponse<List<TeamContract>> byBrandNoTeam(@PathVariable Long brandId) {
        return ApiResponse.success(contractRepo.findByBrandIdAndTeamIdIsNullOrderByStartDateDesc(brandId));
    }

    /**
     * 批量按团队 id 取合同，返回 teamId -> 该团队名下的全部合同列表。供"红人需求管理"列表页
     * 按每条需求自己的品牌方/团队/需求月份，交叉核对这个品牌方这个团队是否已经有一条"需求月份
     * 落在有效期内"的合同（品牌方"一年签一次合同"场景），具体的日期区间匹配逻辑在前端做，
     * 这里只负责一次性把数据都吐出来，避免逐条查库。
     */
    @GetMapping("/by-team-ids")
    public ApiResponse<Map<Long, List<TeamContract>>> byTeamIds(@RequestParam List<Long> ids) {
        Map<Long, List<TeamContract>> result = new HashMap<>();
        for (TeamContract c : contractRepo.findByTeamIdIn(ids)) {
            result.computeIfAbsent(c.getTeamId(), k -> new java.util.ArrayList<>()).add(c);
        }
        return ApiResponse.success(result);
    }

    /** 批量按品牌方 id 取"该品牌方下没有团队"的合同，跟 byTeamIds 是互补的两半，同样的用途 */
    @GetMapping("/by-brand-ids-no-team")
    public ApiResponse<Map<Long, List<TeamContract>>> byBrandIdsNoTeam(@RequestParam List<Long> ids) {
        Map<Long, List<TeamContract>> result = new HashMap<>();
        for (TeamContract c : contractRepo.findByBrandIdInAndTeamIdIsNull(ids)) {
            result.computeIfAbsent(c.getBrandId(), k -> new java.util.ArrayList<>()).add(c);
        }
        return ApiResponse.success(result);
    }

    /** 新增一条(品牌方,团队)的合同记录，校验有效期区间合法+不跟同一(品牌方,团队)下已有合同重叠 */
    @PostMapping
    public ApiResponse<TeamContract> create(@Valid @RequestBody TeamContractRequest req) {
        // 权限校验 -> 查品牌方/团队（走缓存，顺带拦截误用）-> 日期区间合法性 -> 跟已有合同
        // 重叠校验，四个校验全部通过才往下走，任何一步失败都直接抛异常中止（本类下方
        // 各私有方法，方法名即校验内容，见各自定义处的注释）
        assertCanManageContracts();
        Brand brand = resolveBrand(req.getBrandId());
        InfluencerTeam team = resolveTeam(req.getTeamId());
        validateDateRange(req);
        rejectOverlap(req, null);

        TeamContract contract = new TeamContract();
        contract.setBrand(brand);
        contract.setTeam(team);
        contract.setStartDate(req.getStartDate());
        contract.setEndDate(req.getEndDate());
        contract.setContractLink(req.getContractLink());
        return ApiResponse.success(contractRepo.save(contract));
    }

    /** 编辑一条合同记录，同样校验有效期区间合法+不跟同(品牌方,团队)下其它合同重叠（排除自己） */
    @PutMapping("/{id}")
    public ApiResponse<TeamContract> update(@PathVariable Long id, @Valid @RequestBody TeamContractRequest req) {
        assertCanManageContracts();
        TeamContract contract = contractRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("合同记录不存在：" + id));
        // 跟 create() 同一套校验序列（查品牌方/团队 -> 日期区间 -> 重叠校验），rejectOverlap
        // 多传一个 excludeId=id，排除自己不跟自己算重叠
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
     * TeamContract 类注释），合同记录本来就是"新增一条/编辑一条"的独立 CRUD，删除就直接把
     * 数据库行删掉，方便手动清理很久以前（比如2年前）的历史合同。
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

    /** 谁能维护团队级合同：项目负责人/执行人员/法务/管理层/IT后勤这5个员工角色，不含财务
     * （见 EmployeeRoleUtil.canManageTeamContracts 的注释）。 */
    private void assertCanManageContracts() {
        if (employeeRoleUtil.canManageTeamContracts()) return;
        throw new RuntimeException("无权限维护团队合同信息");
    }

    /** 查品牌方（走缓存）+ 拦下"该品牌方走按需求签合同、不该走团队级合同"这种误用 */
    private Brand resolveBrand(Long brandId) {
        Brand brand = brandCache.findById(brandId);
        if (brand == null) throw new RuntimeException("品牌方不存在：" + brandId);
        if (brand.isPerRequirementContract()) {
            throw new RuntimeException("该品牌方是\"一次需求签一次合同\"，请在红人需求管理处上传合同");
        }
        return brand;
    }

    /** 查团队（走缓存），teamId 为空代表"该品牌方下没配团队"这种合法情况，直接返回 null */
    private InfluencerTeam resolveTeam(Long teamId) {
        if (teamId == null) return null;
        InfluencerTeam team = teamCache.findById(teamId);
        if (team == null) throw new RuntimeException("红人团队不存在：" + teamId);
        return team;
    }

    /** 生效日期不能晚于失效日期 */
    private void validateDateRange(TeamContractRequest req) {
        if (req.getStartDate().after(req.getEndDate())) {
            throw new RuntimeException("合同生效日期不能晚于失效日期");
        }
    }

    /** 同一(品牌方,团队)下不能有两条有效期重叠的合同，excludeId 编辑时排除自己 */
    private void rejectOverlap(TeamContractRequest req, Long excludeId) {
        List<TeamContract> overlapping = contractRepo.findOverlapping(
                req.getBrandId(), req.getTeamId(), req.getStartDate(), req.getEndDate(), excludeId);
        if (!overlapping.isEmpty()) {
            String ranges = overlapping.stream()
                    .map(c -> DATE_FMT.get().format(c.getStartDate()) + " 至 " + DATE_FMT.get().format(c.getEndDate()))
                    .collect(Collectors.joining("、"));
            throw new RuntimeException("该品牌方/团队下，已有有效期重叠的合同记录（" + ranges + "），请先编辑或调整已有记录");
        }
    }
}
