package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.dto.request.EmployeeRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.CommissionBonusTier;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.excel.EmployeeExcelHandler;
import com.lusuoria.settlement.repository.CommissionBonusTierRepository;
import com.lusuoria.settlement.repository.EmployeeRepository;
import com.lusuoria.settlement.util.EmployeeRoleUtil;
import com.lusuoria.settlement.util.RoleUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private EmployeeCache employeeCache;
    @Autowired private EmployeeExcelHandler excelHandler;
    @Autowired private CommissionBonusTierRepository bonusTierRepo;
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
    public ApiResponse<List<com.lusuoria.settlement.entity.CommissionBonusTier>> getBonusTiers(@PathVariable Long id) {
        return ApiResponse.success(bonusTierRepo.findByEmployeeIdAndIsDeletedFalseOrderByMinAmountAsc(id));
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

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<Employee> save(@Valid @RequestBody EmployeeRequest req) {
        assertCanManageEmployees();
        Employee employee;
        if (req.getId() != null) {
            employee = employeeRepo.findByIdAndIsDeletedFalse(req.getId())
                    .orElseThrow(() -> new RuntimeException("员工不存在"));
        } else {
            employee = new Employee();
            employee.setIsDeleted(false);
        }
        employee.setName(req.getName());
        employee.setRole(req.getRole());
        // email 为空字符串时存 null，避免违反唯一约束（PostgreSQL 空字符串不等于 null）
        String email = req.getEmail();
        employee.setEmail(email != null && !email.trim().isEmpty() ? email.trim() : null);
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
        excelHandler.export(list, response);
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
