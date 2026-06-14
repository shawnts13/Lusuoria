package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.dto.request.EmployeeRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.repository.EmployeeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private EmployeeCache employeeCache;

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
        employee.setDefaultCommissionRate(req.getDefaultCommissionRate());
        employee.setNotes(req.getNotes());

        Employee saved = employeeRepo.save(employee);
        employeeCache.refresh();
        return ApiResponse.success(saved);
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
