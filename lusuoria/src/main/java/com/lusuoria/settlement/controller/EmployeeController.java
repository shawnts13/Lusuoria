package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.config.BrandCache;
import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.config.EmployeeManagedBrandCache;
import com.lusuoria.settlement.dto.request.EmployeeRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.CommissionBonusTier;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.entity.EmployeeManagedBrand;
import com.lusuoria.settlement.entity.ExecutorPayRateTier;
import com.lusuoria.settlement.excel.EmployeeExcelHandler;
import com.lusuoria.settlement.repository.CommissionBonusTierRepository;
import com.lusuoria.settlement.repository.EmployeeManagedBrandRepository;
import com.lusuoria.settlement.repository.EmployeeRepository;
import com.lusuoria.settlement.repository.ExecutorPayRateTierRepository;
import com.lusuoria.settlement.util.EmployeeRoleUtil;
import com.lusuoria.settlement.util.RoleUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private EmployeeCache employeeCache;
    @Autowired private EmployeeExcelHandler excelHandler;
    @Autowired private CommissionBonusTierRepository bonusTierRepo;
    @Autowired private com.lusuoria.settlement.config.CommissionBonusTierCache bonusTierCache;
    @Autowired private ExecutorPayRateTierRepository executorPayRateTierRepo;
    @Autowired private com.lusuoria.settlement.config.ExecutorPayRateTierCache executorPayRateTierCache;
    @Autowired private EmployeeRoleUtil employeeRoleUtil;
    // "项目管理员"角色需求（2026-08-21 新增）：负责管理的品牌方
    @Autowired private EmployeeManagedBrandRepository employeeManagedBrandRepo;
    @Autowired private EmployeeManagedBrandCache employeeManagedBrandCache;
    @Autowired private BrandCache brandCache;

    /** 获取员工列表（完全走缓存） */
    @GetMapping
    public ApiResponse<List<Employee>> list(@RequestParam(required = false) String role) {
        List<Employee> allEmployees = employeeCache.getAll();
        if (role != null && !role.isEmpty()) {
            List<Employee> filtered = allEmployees.stream()
                    .filter(emp -> role.equals(emp.getRole()))
                    .collect(Collectors.toList());
            return ApiResponse.success(filtered);
        }
        return ApiResponse.success(allEmployees);
    }

    /** 根据 ID 获取员工（完全走缓存） */
    @GetMapping("/{id}")
    public ApiResponse<Employee> getById(@PathVariable Long id) {
        Employee employee = employeeCache.findById(id);
        if (employee == null) throw new RuntimeException("员工不存在");
        return ApiResponse.success(employee);
    }

    /**
     * 获取某个项目负责人/管理层配置的提成 bonus 阶梯（编辑表单打开时调用）。
     * 单独开接口而不是挂在 Employee 对象上一起返回，是因为 Employee 走的是内存缓存，
     * 缓存对象是多个请求共享的可变实例，不适合在这上面挂运行时查出来的关联数据。
     */
    @GetMapping("/{id}/bonus-tiers")
    public ApiResponse<List<CommissionBonusTier>> getBonusTiers(@PathVariable Long id) {
        // 2026-08-17 性能修复：改走 CommissionBonusTierCache；旧代码：
        // bonusTierRepo.findByEmployeeIdAndIsDeletedFalseOrderByMinAmountAsc(id)
        return ApiResponse.success(bonusTierCache.find(id));
    }

    /**
     * 批量按员工 id 取 bonus 阶梯，返回 employeeId -> 阶梯列表。供"员工管理"列表页
     * 直接展示每个项目负责人/管理层已配置的 bonus 规则，避免逐行调用 by-id 接口。
     */
    @GetMapping("/bonus-tiers")
    public ApiResponse<Map<Long, List<CommissionBonusTier>>> getBonusTiersBulk(@RequestParam List<Long> employeeIds) {
        // 2026-08-17 性能修复：改走缓存，按 id 逐个从内存取（不是查库）；旧代码：
        // for (CommissionBonusTier t : bonusTierRepo.findByEmployeeIdInAndIsDeletedFalseOrderByMinAmountAsc(employeeIds)) {
        //     result.computeIfAbsent(t.getEmployeeId(), k -> new java.util.ArrayList<>()).add(t);
        // }
        Map<Long, List<CommissionBonusTier>> result = new HashMap<>();
        for (Long id : employeeIds) {
            List<CommissionBonusTier> tiers = bonusTierCache.find(id);
            if (!tiers.isEmpty()) result.put(id, tiers);
        }
        return ApiResponse.success(result);
    }

    /**
     * 某个项目管理员负责的品牌方 id 列表（编辑表单打开时调用，回显已配置的品牌方），完全走缓存。
     * 品牌方名称不在这里解析——前端本身已经有全量品牌方列表（下拉选项数据），拿 id 在前端本地
     * 映射即可，不需要后端多返回一份重复信息，跟 bonus-tiers 只返回原始档位、由前端拼文案是
     * 同一个思路。
     */
    @GetMapping("/{id}/managed-brands")
    public ApiResponse<List<Long>> getManagedBrands(@PathVariable Long id) {
        return ApiResponse.success(new ArrayList<>(employeeManagedBrandCache.findBrandIdsByEmployeeId(id)));
    }

    /** 批量按员工 id 取负责的品牌方，供"员工管理"列表页展示"项目管理员"标签的 tooltip/详情用，避免逐行调用 by-id 接口 */
    @GetMapping("/managed-brands")
    public ApiResponse<Map<Long, List<Long>>> getManagedBrandsBulk(@RequestParam List<Long> employeeIds) {
        Map<Long, List<Long>> result = new HashMap<>();
        for (Long id : employeeIds) {
            Set<Long> brandIds = employeeManagedBrandCache.findBrandIdsByEmployeeId(id);
            if (!brandIds.isEmpty()) result.put(id, new ArrayList<>(brandIds));
        }
        return ApiResponse.success(result);
    }

    // 角色分组：不同角色只能维护各自适用的薪资字段，避免脏数据
    private static final Set<String> COMMISSION_ROLES = new HashSet<String>(Arrays.asList("项目负责人", "管理层"));
    private static final Set<String> FIXED_SALARY_ROLES = new HashSet<String>(Arrays.asList("财务", "IT后勤"));

    /**
     * "员工管理"（新建/编辑/删除）2026-07 起放开给"管理层" STAFF 账号，不再是 ADMIN 独占——
     * 该账号将获得和 ADMIN 一样的完整能力（查看全部员工、删除）。ADMIN 之外的账号，必须关联的
     * 员工角色正好是"管理层"才放行，其余一律拒绝。
     */
    private void assertCanManageEmployees() {
        if (RoleUtil.isAdmin()) return;
        if ("管理层".equals(employeeRoleUtil.getCurrentEmployeeRole())) return;
        throw new RuntimeException("无权限维护员工信息");
    }

    /**
     * bonus 阶梯先删后插，deleteByEmployeeId 这类派生删除方法必须在事务里执行才能真正
     * remove 掉查到的记录，不然会报 "No EntityManager with actual transaction available
     * for current thread" —— 没删过东西的时候（比如第一次保存）不会触发这个报错，直到真的
     * 有档位要删才会暴露出来，所以务必显式声明 @Transactional。
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    @Transactional
    public ApiResponse<Employee> save(@Valid @RequestBody EmployeeRequest req) {
        assertCanManageEmployees();
        // email 为空字符串时存 null，避免违反唯一约束（PostgreSQL 空字符串不等于 null）
        String email = req.getEmail();
        String normalizedEmail = email != null && !email.trim().isEmpty() ? email.trim() : null;

        // email 数据库层面有唯一约束（null 不受影响，可以有多个员工都不填邮箱），不认软删除——
        // 之前这里完全没有判重校验，员工被软删除后同一个邮箱再用来新建员工会直接在 insert
        // 这一步撞唯一键报错，报出一个用户看不懂的"数据处理失败"（2026-08 修复）。
        // 命中已软删除的同邮箱记录时复活它，命中未删除的记录、或编辑改邮箱撞上别的员工
        // （不管对方是否已软删除）都直接拦下报友好错误
        Employee employee;
        if (req.getId() != null) {
            employee = employeeRepo.findByIdAndIsDeletedFalse(req.getId())
                    .orElseThrow(() -> new RuntimeException("员工不存在"));
            if (normalizedEmail != null && !normalizedEmail.equals(employee.getEmail())) {
                employeeRepo.findByEmail(normalizedEmail).ifPresent(existing -> {
                    throw new RuntimeException("邮箱已被使用：" + normalizedEmail);
                });
            }
        } else {
            Employee existing = normalizedEmail != null ? employeeRepo.findByEmail(normalizedEmail).orElse(null) : null;
            if (existing != null && Boolean.TRUE.equals(existing.getIsDeleted())) {
                employee = existing;
                employee.setIsDeleted(false);
            } else if (existing != null) {
                throw new RuntimeException("邮箱已被使用：" + normalizedEmail);
            } else {
                employee = new Employee();
                employee.setIsDeleted(false);
            }
        }
        employee.setName(req.getName());
        employee.setRole(req.getRole());
        employee.setEmail(normalizedEmail);
        employee.setContactPhone(req.getContactPhone());
        employee.setHireDate(req.getHireDate());
        employee.setResignDate(req.getResignDate());

        // 薪资字段按角色分组维护，非本角色适用的字段一律清空，防止脏数据残留
        String role = req.getRole();
        boolean isCommissionRole = COMMISSION_ROLES.contains(role);
        if (isCommissionRole) {
            employee.setDefaultCommissionRate(req.getDefaultCommissionRate());
            employee.setFixedMonthlySalary(null);
            employee.setBonusTierCurrency(req.getBonusTierCurrency());
        } else if (FIXED_SALARY_ROLES.contains(role)) {
            employee.setFixedMonthlySalary(req.getFixedMonthlySalary());
            employee.setDefaultCommissionRate(null);
            employee.setBonusTierCurrency(null);
        } else {
            // 其他角色（含"执行人员"——费率梯度已改由 ExecutorPayRate 按项目负责人独立维护，
            // 不再挂在 Employee 自己身上；"法务"薪资规则待补充）：暂不维护任何薪资字段
            employee.setDefaultCommissionRate(null);
            employee.setFixedMonthlySalary(null);
            employee.setBonusTierCurrency(null);
        }

        // "项目管理员"身份（2026-08-21 新增）：正交叠加在 role 之上的一个独立属性，不是 role
        // 的一个取值——只允许叠加在"项目负责人"角色上（Shawn 明确要求），管理层不允许（管理层
        // 本身已经包含这些权限，不需要也不应该再叠加）。关闭开关、或角色不是项目负责人时，
        // 三个相关字段全部清空，防止脏数据残留，跟上面薪资字段按角色分组维护是同一个思路。
        boolean wantsProjectAdmin = Boolean.TRUE.equals(req.getIsProjectAdmin());
        if (wantsProjectAdmin && !"项目负责人".equals(role)) {
            throw new RuntimeException("\"项目管理员\"身份只能叠加在\"项目负责人\"角色上");
        }
        if (wantsProjectAdmin) {
            if (req.getProjectAdminSince() == null) {
                throw new RuntimeException("请填写\"成为项目管理员的时间\"");
            }
            if (req.getProjectAdminFixedMonthlySalary() == null) {
                throw new RuntimeException("请填写\"项目管理员固定月薪\"");
            }
            employee.setProjectAdminSince(req.getProjectAdminSince());
            employee.setProjectAdminFixedMonthlySalary(req.getProjectAdminFixedMonthlySalary());
        } else {
            employee.setProjectAdminSince(null);
            employee.setProjectAdminFixedMonthlySalary(null);
        }

        employee.setNotes(req.getNotes());

        Employee saved = employeeRepo.save(employee);

        // 负责管理的品牌方（仅"项目管理员"维护）：getOrCreate 式整理——复用已软删的旧关联而不是
        // 硬删重插（避免历史记录被清空、也避免撞 (employee_id, brand_id) 这类潜在唯一约束），
        // 不再需要的关联软删掉；不是项目管理员时视为"负责的品牌方清空"，把现存关联全部软删
        List<EmployeeManagedBrand> existingLinks = employeeManagedBrandRepo.findByEmployeeId(saved.getId());
        Set<Long> requestedBrandIds = wantsProjectAdmin && req.getManagedBrandIds() != null
                ? new HashSet<>(req.getManagedBrandIds()) : Collections.emptySet();
        for (Long brandId : requestedBrandIds) {
            if (brandCache.findById(brandId) == null) throw new RuntimeException("品牌方不存在：" + brandId);
        }
        Set<Long> keptBrandIds = new HashSet<>();
        for (EmployeeManagedBrand link : existingLinks) {
            boolean shouldExist = requestedBrandIds.contains(link.getBrandId());
            if (shouldExist) {
                keptBrandIds.add(link.getBrandId());
                if (Boolean.TRUE.equals(link.getIsDeleted())) {
                    link.setIsDeleted(false);
                    employeeManagedBrandRepo.save(link);
                }
            } else if (!Boolean.TRUE.equals(link.getIsDeleted())) {
                link.setIsDeleted(true);
                employeeManagedBrandRepo.save(link);
            }
        }
        for (Long brandId : requestedBrandIds) {
            if (!keptBrandIds.contains(brandId)) {
                // isDeleted 是 BaseEntity（父类）字段，Lombok @Builder 不会给父类字段生成
                // builder 方法（不是 @SuperBuilder），只能 build() 完之后单独 set
                EmployeeManagedBrand link = EmployeeManagedBrand.builder()
                        .employeeId(saved.getId()).brandId(brandId).build();
                link.setIsDeleted(false);
                employeeManagedBrandRepo.save(link);
            }
        }
        employeeManagedBrandCache.refresh();

        // bonus 阶梯：仅项目负责人/管理层维护，先删后插整批替换；非本角色一律清空阶梯配置
        bonusTierRepo.deleteByEmployeeId(saved.getId());
        if (isCommissionRole && req.getBonusTiers() != null) {
            for (EmployeeRequest.BonusTierItem item : req.getBonusTiers()) {
                if (item.getMinAmount() == null || item.getBonusRate() == null) continue;
                if (item.getMinAmount().compareTo(BigDecimal.ZERO) < 0) {
                    throw new RuntimeException("bonus 阶梯最低金额不能为负数");
                }
                if (item.getMaxAmount() != null && item.getMaxAmount().compareTo(item.getMinAmount()) <= 0) {
                    throw new RuntimeException("bonus 阶梯最高金额必须大于最低金额");
                }
                bonusTierRepo.save(CommissionBonusTier.builder()
                        .employeeId(saved.getId())
                        .minAmount(item.getMinAmount())
                        .maxAmount(item.getMaxAmount())
                        .bonusRate(item.getBonusRate())
                        .build());
            }
        }

        employeeCache.refresh();
        // 2026-08-17 新增：bonus 阶梯写完也要刷新对应缓存，不然要等最多4小时定时刷新才会反映到
        // CommissionBonusTierCache，影响数据看板"提成"下钻/工资单计算等所有读这套配置的地方
        bonusTierCache.refresh();
        return ApiResponse.success(saved);
    }

    /** 员工导出 Excel，role 可选按角色筛选；连带导出 bonus 阶梯、管理层配置的执行人员费率标准 */
    @GetMapping("/export/excel")
    public void exportExcel(@RequestParam(required = false) String role, HttpServletResponse response) throws IOException {
        List<Employee> allEmployees = employeeCache.getAll();
        List<Employee> list = (role != null && !role.isEmpty())
                ? allEmployees.stream().filter(emp -> role.equals(emp.getRole())).collect(Collectors.toList())
                : allEmployees;

        List<Long> ids = list.stream().map(Employee::getId).collect(Collectors.toList());
        // 2026-08-17 性能修复：改走缓存；旧代码：
        // for (CommissionBonusTier t : bonusTierRepo.findByEmployeeIdInAndIsDeletedFalseOrderByMinAmountAsc(ids)) {
        //     bonusTiersByEmployeeId.computeIfAbsent(t.getEmployeeId(), k -> new ArrayList<>()).add(t);
        // }
        Map<Long, List<CommissionBonusTier>> bonusTiersByEmployeeId = new HashMap<>();
        for (Long id : ids) {
            List<CommissionBonusTier> tiers = bonusTierCache.find(id);
            if (!tiers.isEmpty()) bonusTiersByEmployeeId.put(id, tiers);
        }

        // 执行人员薪资标准：只导出"管理层"这份配置，跟列表页"薪资信息"列展示的口径一致
        // （见 EmployeeExcelHandler 类注释），不是把每个不同项目负责人各自配的费率都汇总进来
        Map<Long, List<ExecutorPayRateTier>> executorRatesByExecutorId = new HashMap<>();
        Employee management = employeeCache.findManagementEmployee();
        if (management != null) {
            // 2026-08-17 性能修复：改走 ExecutorPayRateTierCache；旧代码：
            // executorPayRateTierRepo.findByManagerIdAndIsDeletedFalseOrderByMinCountAsc(management.getId())
            for (ExecutorPayRateTier t : executorPayRateTierCache.findByManagerId(management.getId())) {
                executorRatesByExecutorId.computeIfAbsent(t.getExecutorId(), k -> new ArrayList<>()).add(t);
            }
        }

        // 项目管理员负责管理的品牌方名称（2026-08-21 新增）：走缓存拿 brandId 集合，
        // 再逐个 brandId 用 BrandCache.findById() 查名称拼成逗号分隔的字符串，只对
        // projectAdminSince 不为空的员工才有意义，其余员工不用查
        Map<Long, String> managedBrandNamesByEmployeeId = new HashMap<>();
        for (Employee e : list) {
            if (e.getProjectAdminSince() == null) continue;
            List<String> names = employeeManagedBrandCache.findBrandIdsByEmployeeId(e.getId()).stream()
                    .map(brandId -> {
                        com.lusuoria.settlement.entity.Brand b = brandCache.findById(brandId);
                        return b != null ? b.getName() : null;
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());
            if (!names.isEmpty()) managedBrandNamesByEmployeeId.put(e.getId(), String.join("、", names));
        }

        excelHandler.export(list, bonusTiersByEmployeeId, executorRatesByExecutorId, managedBrandNamesByEmployeeId, response);
    }

    /** 软删除员工，写完刷新 EmployeeCache；权限同 save()，见 assertCanManageEmployees() */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        assertCanManageEmployees();
        Employee emp = employeeRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("员工不存在"));
        emp.setIsDeleted(true);
        employeeRepo.save(emp);
        employeeCache.refresh();
        return ApiResponse.success();
    }
}
