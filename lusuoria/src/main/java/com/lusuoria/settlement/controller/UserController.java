package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.dto.request.ChangePasswordRequest;
import com.lusuoria.settlement.dto.request.UserCreateRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.dto.response.UserResponse;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.entity.SysUser;
import com.lusuoria.settlement.repository.EmployeeRepository;
import com.lusuoria.settlement.repository.SysUserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired private SysUserRepository userRepo;
    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private PasswordEncoder passwordEncoder;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<UserResponse>> list() {
        List<SysUser> users = userRepo.findAll().stream()
                .filter(u -> !Boolean.TRUE.equals(u.getIsDeleted()))
                .collect(Collectors.toList());
        return ApiResponse.success(users.stream().map(this::toResponse).collect(Collectors.toList()));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<UserResponse> create(@Valid @RequestBody UserCreateRequest req) {
        if (userRepo.existsByUsernameAndIsDeletedFalse(req.getUsername())) {
            throw new RuntimeException("用户名已存在：" + req.getUsername());
        }
        if (req.getPassword() == null || req.getPassword().isEmpty()) {
            throw new RuntimeException("新建账号时密码不能为空");
        }

        SysUser user = new SysUser();
        user.setIsDeleted(false);
        user.setUsername(req.getUsername());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setRole(req.getRole());
        user.setEnabled(req.getEnabled() != null ? req.getEnabled() : true);

        if (req.getEmployeeId() != null) {
            Employee emp = employeeRepo.findByIdAndIsDeletedFalse(req.getEmployeeId())
                    .orElseThrow(() -> new RuntimeException("员工不存在：" + req.getEmployeeId()));
            user.setEmployee(emp);
        }

        return ApiResponse.success(toResponse(userRepo.save(user)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<UserResponse> update(@PathVariable Long id,
                                            @Valid @RequestBody UserCreateRequest req) {
        SysUser user = userRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        if (!user.getUsername().equals(req.getUsername())
                && userRepo.existsByUsernameAndIsDeletedFalse(req.getUsername())) {
            throw new RuntimeException("用户名已存在：" + req.getUsername());
        }

        user.setUsername(req.getUsername());
        user.setRole(req.getRole());
        if (req.getEnabled() != null) user.setEnabled(req.getEnabled());

        if (req.getPassword() != null && !req.getPassword().isEmpty()) {
            user.setPassword(passwordEncoder.encode(req.getPassword()));
        }

        if (req.getEmployeeId() != null) {
            Employee emp = employeeRepo.findByIdAndIsDeletedFalse(req.getEmployeeId())
                    .orElseThrow(() -> new RuntimeException("员工不存在：" + req.getEmployeeId()));
            user.setEmployee(emp);
        } else {
            user.setEmployee(null);
        }

        return ApiResponse.success(toResponse(userRepo.save(user)));
    }

    @PatchMapping("/{id}/toggle")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<UserResponse> toggle(@PathVariable Long id) {
        SysUser user = userRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth.getName().equals(user.getUsername())) {
            throw new RuntimeException("不能禁用自己的账号");
        }

        user.setEnabled(!Boolean.TRUE.equals(user.getEnabled()));
        return ApiResponse.success(toResponse(userRepo.save(user)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        SysUser user = userRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth.getName().equals(user.getUsername())) {
            throw new RuntimeException("不能删除自己的账号");
        }

        user.setIsDeleted(true);
        userRepo.save(user);
        return ApiResponse.success();
    }

    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        SysUser user = userRepo.findByUsernameAndIsDeletedFalse(auth.getName())
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        user.setPassword(passwordEncoder.encode(req.getNewPassword()));
        userRepo.save(user);
        return ApiResponse.success();
    }

    @GetMapping("/me")
    public ApiResponse<UserResponse> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        SysUser user = userRepo.findByUsernameAndIsDeletedFalse(auth.getName())
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        return ApiResponse.success(toResponse(user));
    }

    // ===== 转换 =====
    private UserResponse toResponse(SysUser u) {
        UserResponse r = new UserResponse();
        r.setId(u.getId());
        r.setUsername(u.getUsername());
        // 显示名称：优先用关联员工姓名，没有则用用户名
        String displayName = (u.getEmployee() != null && u.getEmployee().getName() != null)
                ? u.getEmployee().getName()
                : u.getUsername();
        r.setDisplayName(displayName);
        r.setRole(u.getRole());
        r.setRoleLabel(roleLabel(u.getRole()));
        r.setEnabled(u.getEnabled());
        r.setCreatedAt(u.getCreatedAt());
        r.setUpdatedAt(u.getUpdatedAt());
        if (u.getEmployee() != null) {
            r.setEmployeeId(u.getEmployee().getId());
            r.setEmployeeName(u.getEmployee().getName());
        }
        return r;
    }

    private String roleLabel(String role) {
        if ("ADMIN".equals(role))   return "管理员";
        if ("STAFF".equals(role))   return "普通员工";
        if ("AUDITOR".equals(role)) return "审计/会计";
        if ("GUEST".equals(role))   return "访客";
        return role;
    }
}
