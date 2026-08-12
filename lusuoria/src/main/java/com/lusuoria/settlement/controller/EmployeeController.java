package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.dto.request.EmployeeRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.CommissionBonusTier;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.entity.ExecutorPayRateTier;
import com.lusuoria.settlement.excel.EmployeeExcelHandler;
import com.lusuoria.settlement.repository.CommissionBonusTierRepository;
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
    @Autowired private ExecutorPayRateTierRepository executorPayRateTierRepo;
    @Autowired private EmployeeRoleUtil employeeRoleUtil;

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
        return ApiResponse.success(bonusTierRepo.findByEmployeeIdAndIsDeletedFalseOrderByMinAmountAsc(id));
    }

    /**
     * 批量按员工 id 取 bonus 阶梯，返回 employeeId -> 阶梯列表。供"员工管理"列表页
     * 直接展示每个项目负责人/管理层已配置的 bonus 规则，避免逐行调用 by-id 接口。
     */
    @GetMapping("/bonus-tiers")
    public ApiResponse<Map<Long, List<CommissionBonusTier>>> getBonusTiersBulk(@RequestParam List<Long> employeeIds) {
        Map<Long, List<CommissionBonusTier>> result = new HashMap<>();
        for (CommissionBonusTier t : bonusTierRepo.findByEmployeeIdInAndIsDeletedFalseOrderByMinAmountAsc(employeeIds)) {
            result.computeIfAbsent(t.getEmployeeId(), k -> new java.util.ArrayList<>()).add(t);
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

        employee.setNotes(req.getNotes());

        Employee saved = employeeRepo.save(employee);

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
        return ApiResponse.success(saved);
    }

    @GetMapping("/export/excel")
    public void exportExcel(@RequestParam(required = false) String role, HttpServletResponse response) throws IOException {
        List<Employee> allEmployees = employeeCache.getAll();
        List<Employee> list = (role != null && !role.isEmpty())
                ? allEmployees.stream().filter(emp -> role.equals(emp.getRole())).collect(Collectors.toList())
                : allEmployees;

        List<Long> ids = list.stream().map(Employee::getId).collect(Collectors.toList());
        Map<Long, List<CommissionBonusTier>> bonusTiersByEmployeeId = new HashMap<>();
        if (!ids.isEmpty()) {
            for (CommissionBonusTier t : bonusTierRepo.findByEmployeeIdInAndIsDeletedFalseOrderByMinAmountAsc(ids)) {
                bonusTiersByEmployeeId.computeIfAbsent(t.getEmployeeId(), k -> new ArrayList<>()).add(t);
            }
        }

        // 执行人员薪资标准：只导出"管理层"这份配置，跟列表页"薪资信息"列展示的口径一致
        // （见 EmployeeExcelHandler 类注释），不是把每个不同项目负责人各自配的费率都汇总进来
        Map<Long, List<ExecutorPayRateTier>> executorRatesByExecutorId = new HashMap<>();
        Employee management = employeeCache.findManagementEmployee();
        if (management != null) {
            for (ExecutorPayRateTier t : executorPayRateTierRepo
                    .findByManagerIdAndIsDeletedFalseOrderByMinCountAsc(management.getId())) {
                executorRatesByExecutorId.computeIfAbsent(t.getExecutorId(), k -> new ArrayList<>()).add(t);
            }
        }

        excelHandler.export(list, bonusTiersByEmployeeId, executorRatesByExecutorId, response);
    }

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
