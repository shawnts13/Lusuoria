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

    /**
     * 获取员工列表（完全走缓存）
     */
    @GetMapping
    public ApiResponse<List<Employee>> list(@RequestParam(required = false) String role) {
        // 1. 先从缓存中获取所有在职员工（这里拿到的已经是按姓名排好序的）
        List<Employee> allEmployees = employeeCache.getAll();

        // 2. 如果传了 role 参数，直接在内存中用 Stream 流进行过滤，避免查数据库
        if (role != null && !role.isEmpty()) {
            List<Employee> filtered = allEmployees.stream()
                    .filter(emp -> role.equals(emp.getRole()))
                    .collect(Collectors.toList());
            return ApiResponse.success(filtered);
        }

        // 3. 没传 role 则直接返回全量缓存
        return ApiResponse.success(allEmployees);
    }

    /**
     * 根据 ID 获取员工（完全走缓存）
     */
    @GetMapping("/{id}")
    public ApiResponse<Employee> getById(@PathVariable Long id) {
        // 直接从缓存中查找
        Employee employee = employeeCache.findById(id);

        // 如果缓存里没有，说明员工不存在（或者被删除了）
        if (employee == null) {
            throw new RuntimeException("员工不存在");
        }

        return ApiResponse.success(employee);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Employee> save(@Valid @RequestBody EmployeeRequest req) {
        Employee employee;
        if (req.getId() != null) {
            // 写操作（修改）：依然需要先查数据库确认数据存在
            employee = employeeRepo.findByIdAndIsDeletedFalse(req.getId())
                    .orElseThrow(() -> new RuntimeException("员工不存在"));
        } else {
            // 写操作（新增）
            employee = new Employee();
            employee.setIsDeleted(false);
        }
        employee.setName(req.getName());
        employee.setRole(req.getRole());
        employee.setEmail(req.getEmail());
        employee.setDefaultCommissionRate(req.getDefaultCommissionRate());
        employee.setNotes(req.getNotes());

        Employee saved = employeeRepo.save(employee);

        // 关键：数据落库成功后，立刻刷新本地缓存，保证下次读到最新数据
        employeeCache.refresh();
        return ApiResponse.success(saved);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        // 写操作（删除）：需要操作数据库
        Employee emp = employeeRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("员工不存在"));
        emp.setIsDeleted(true);
        employeeRepo.save(emp);

        // 关键：逻辑删除成功后，立刻刷新本地缓存
        employeeCache.refresh();
        return ApiResponse.success();
    }
}