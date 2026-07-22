package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.dto.request.EmployeeRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.excel.EmployeeExcelHandler;
import com.lusuoria.settlement.repository.EmployeeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
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

    // 角色分组：不同角色只能维护各自适用的薪资字段，避免脏数据
    private static final Set<String> COMMISSION_ROLES = new HashSet<String>(Arrays.asList("项目负责人", "管理层"));
    private static final Set<String> FIXED_SALARY_ROLES = new HashSet<String>(Arrays.asList("财务", "IT后勤"));
    private static final String EXECUTOR_ROLE = "执行人员";

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Employee> save(@Valid @RequestBody EmployeeRequest req) {
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
        if (COMMISSION_ROLES.contains(role)) {
            employee.setDefaultCommissionRate(req.getDefaultCommissionRate());
            employee.setFixedMonthlySalary(null);
            clearExecutorRates(employee);
        } else if (FIXED_SALARY_ROLES.contains(role)) {
            employee.setFixedMonthlySalary(req.getFixedMonthlySalary());
            employee.setDefaultCommissionRate(null);
            clearExecutorRates(employee);
        } else if (EXECUTOR_ROLE.equals(role)) {
            employee.setRateRealShotNew(req.getRateRealShotNew());
            employee.setRateAiNewMaterial(req.getRateAiNewMaterial());
            employee.setRateOldMaterialTier1(req.getRateOldMaterialTier1());
            employee.setRateOldMaterialTier2(req.getRateOldMaterialTier2());
            employee.setRateOldMaterialTier3(req.getRateOldMaterialTier3());
            employee.setOldMaterialMonthlyCap(req.getOldMaterialMonthlyCap());
            employee.setDefaultCommissionRate(null);
            employee.setFixedMonthlySalary(null);
        } else {
            // 其他角色（如"法务"，薪资规则待补充）：暂不维护任何薪资字段
            employee.setDefaultCommissionRate(null);
            employee.setFixedMonthlySalary(null);
            clearExecutorRates(employee);
        }

        employee.setNotes(req.getNotes());

        Employee saved = employeeRepo.save(employee);
        employeeCache.refresh();
        return ApiResponse.success(saved);
    }

    private void clearExecutorRates(Employee employee) {
        employee.setRateRealShotNew(null);
        employee.setRateAiNewMaterial(null);
        employee.setRateOldMaterialTier1(null);
        employee.setRateOldMaterialTier2(null);
        employee.setRateOldMaterialTier3(null);
        employee.setOldMaterialMonthlyCap(null);
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
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        Employee emp = employeeRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("员工不存在"));
        emp.setIsDeleted(true);
        employeeRepo.save(emp);
        employeeCache.refresh();
        return ApiResponse.success();
    }
}
