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

@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private EmployeeCache employeeCache;

    @GetMapping
    public ApiResponse<List<Employee>> list(@RequestParam(required = false) String role) {
        if (role != null && !role.isEmpty())
            return ApiResponse.success(employeeRepo.findByRoleAndIsDeletedFalse(role));
        return ApiResponse.success(employeeRepo.findByIsDeletedFalseOrderByNameAsc());
    }

    @GetMapping("/{id}")
    public ApiResponse<Employee> getById(@PathVariable Long id) {
        return ApiResponse.success(employeeRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("员工不存在")));
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
        employee.setEmail(req.getEmail());
        employee.setDefaultCommissionRate(req.getDefaultCommissionRate());
        employee.setNotes(req.getNotes());
        Employee saved = employeeRepo.save(employee);
        employeeCache.refresh();  // 立即更新缓存
        return ApiResponse.success(saved);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        Employee emp = employeeRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("员工不存在"));
        emp.setIsDeleted(true);
        employeeRepo.save(emp);
        employeeCache.refresh();  // 立即更新缓存
        return ApiResponse.success();
    }
}
