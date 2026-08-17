package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.config.EmployeeCache;
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
    @Autowired private com.lusuoria.settlement.config.SysUserCache sysUserCache;
    @Autowired private EmployeeRepository employeeRepo;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EmployeeCache employeeCache;

    /** "账号管理"页面列表，仅 ADMIN 可见——登录账号（sys_users）不是员工，一个账号可以关联一个员工 */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<UserResponse>> list() {
        // 2026-08-17 性能修复：改走 SysUserCache；旧代码：userRepo.findByIsDeletedFalseOrderByUsernameAsc()
        List<SysUser> users = sysUserCache.getAll();
        return ApiResponse.success(users.stream().map(this::toResponse).collect(Collectors.toList()));
    }

    /**
     * 新建登录账号。username 唯一约束不认软删除，命中同名已软删除账号时直接复活那一行（见下方
     * 注释），不是插入新行；可选关联一个员工（一个员工只能绑定一个账号，见下方校验）。
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<UserResponse> create(@Valid @RequestBody UserCreateRequest req) {
        if (req.getPassword() == null || req.getPassword().isEmpty()) {
            throw new RuntimeException("新建账号时密码不能为空");
        }

        // username 数据库层面有唯一约束，不认软删除——之前只查未软删除的记录
        // （existsByUsernameAndIsDeletedFalse），账号被软删除后用同一个用户名重新新建会在
        // 这一步放行、真正 insert 时才撞唯一键报错（2026-08 修复）。改成不限 isDeleted 查
        // 一次：命中已软删除的同名账号时复活它（沿用旧账号id，原地复用，不插入新行），命中
        // 未删除的账号才拦下
        SysUser existing = userRepo.findByUsername(req.getUsername()).orElse(null);
        SysUser user;
        if (existing != null && Boolean.TRUE.equals(existing.getIsDeleted())) {
            user = existing;
            user.setIsDeleted(false);
            user.setLastSeenReminderPopupAt(null); // 当成一个全新账号，不带旧账号的历史弹窗状态
        } else if (existing != null) {
            throw new RuntimeException("用户名已存在：" + req.getUsername());
        } else {
            user = new SysUser();
            user.setIsDeleted(false);
        }
        user.setUsername(req.getUsername());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setRole(req.getRole());
        user.setEnabled(req.getEnabled() != null ? req.getEnabled() : true);

        if (req.getEmployeeId() != null) {
            // 走缓存，不查库
            Employee emp = employeeCache.findById(req.getEmployeeId());
            if (emp == null) throw new RuntimeException("员工不存在：" + req.getEmployeeId());
            if (userRepo.findByEmployeeIdAndIsDeletedFalse(req.getEmployeeId()).isPresent()) {
                throw new RuntimeException("该员工已经绑定了其他账号，一个员工只能绑定一个账号");
            }
            user.setEmployee(emp);
        } else {
            user.setEmployee(null); // 复活旧账号时不带旧的员工绑定，除非本次请求重新指定
        }

        UserResponse resp = toResponse(userRepo.save(user));
        sysUserCache.refresh(); // 2026-08-17 新增：写完立刻刷新，不然要等最多4小时才反映到缓存
        return ApiResponse.success(resp);
    }

    /** 编辑登录账号（用户名/角色/启用状态/密码/关联员工，密码留空表示不改） */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<UserResponse> update(@PathVariable Long id,
                                            @Valid @RequestBody UserCreateRequest req) {
        SysUser user = userRepo.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        // 改名撞上别的账号（不管对方是否已软删除）都要拦下——username 唯一约束不认软删除，
        // 不拦的话真正 save() 时会撞唯一键报错（2026-08 修复，跟新建账号那边同一个根因）
        if (!user.getUsername().equals(req.getUsername())) {
            userRepo.findByUsername(req.getUsername()).ifPresent(existing -> {
                throw new RuntimeException("用户名已存在：" + req.getUsername());
            });
        }

        user.setUsername(req.getUsername());
        user.setRole(req.getRole());
        if (req.getEnabled() != null) user.setEnabled(req.getEnabled());

        if (req.getPassword() != null && !req.getPassword().isEmpty()) {
            user.setPassword(passwordEncoder.encode(req.getPassword()));
        }

        if (req.getEmployeeId() != null) {
            // 走缓存，不查库
            Employee emp = employeeCache.findById(req.getEmployeeId());
            if (emp == null) throw new RuntimeException("员工不存在：" + req.getEmployeeId());
            SysUser existingBinding = userRepo.findByEmployeeIdAndIsDeletedFalse(req.getEmployeeId()).orElse(null);
            if (existingBinding != null && !existingBinding.getId().equals(user.getId())) {
                throw new RuntimeException("该员工已经绑定了其他账号，一个员工只能绑定一个账号");
            }
            user.setEmployee(emp);
        } else {
            user.setEmployee(null);
        }

        UserResponse resp = toResponse(userRepo.save(user));
        sysUserCache.refresh(); // 2026-08-17 新增：写完立刻刷新，不然要等最多4小时才反映到缓存
        return ApiResponse.success(resp);
    }

    /** 启用/禁用账号（翻转 enabled）。注意：已签发的 JWT 不受影响，禁用不会立刻踢掉已登录的会话，
     *  要等 token 自然过期（登录时校验 enabled 的是 Spring Security UserDetailsServiceImpl，只在
     *  登录那一刻查一次，之后请求都是无状态校验 JWT，见 SecurityConfig.jwtAuthFilter）。 */
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
        UserResponse resp = toResponse(userRepo.save(user));
        sysUserCache.refresh(); // 2026-08-17 新增：写完立刻刷新，不然要等最多4小时才反映到缓存
        return ApiResponse.success(resp);
    }

    /** 软删除账号，不能删自己正在用的这个账号 */
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
        sysUserCache.refresh(); // 2026-08-17 新增：写完立刻刷新，不然要等最多4小时才反映到缓存
        return ApiResponse.success();
    }

    /** 当前登录账号自己改密码（不需要 ADMIN 权限，谁都能改自己的） */
    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        SysUser user = userRepo.findByUsernameAndIsDeletedFalse(auth.getName())
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        user.setPassword(passwordEncoder.encode(req.getNewPassword()));
        userRepo.save(user);
        sysUserCache.refresh(); // 2026-08-17 新增：写完立刻刷新，不然要等最多4小时才反映到缓存
        return ApiResponse.success();
    }

    /** 当前登录账号自己的信息（右上角账号菜单用） */
    @GetMapping("/me")
    public ApiResponse<UserResponse> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // 2026-08-17 性能修复：纯只读查询，改走 SysUserCache；旧代码：
        // userRepo.findByUsernameAndIsDeletedFalse(auth.getName()).orElseThrow(...)
        SysUser user = sysUserCache.findByUsername(auth.getName());
        if (user == null) throw new RuntimeException("用户不存在");
        return ApiResponse.success(toResponse(user));
    }

    // ===== 转换 =====
    private UserResponse toResponse(SysUser u) {
        UserResponse r = new UserResponse();
        r.setId(u.getId());
        r.setUsername(u.getUsername());

        // 用 employeeId 字段（直接读列，不触发懒加载）查缓存，零额外 SQL
        Employee emp = employeeCache.findById(u.getEmployeeId());
        r.setDisplayName(emp != null ? emp.getName() : u.getUsername());
        r.setRole(u.getRole());
        r.setRoleLabel(roleLabel(u.getRole()));
        r.setEnabled(u.getEnabled());
        r.setCreatedAt(u.getCreatedAt());
        r.setUpdatedAt(u.getUpdatedAt());
        if (emp != null) {
            r.setEmployeeId(emp.getId());
            r.setEmployeeName(emp.getName());
        }
        return r;
    }

    /** SysUser.role（ADMIN/STAFF/AUDITOR/GUEST）转中文展示标签 */
    private String roleLabel(String role) {
        if ("ADMIN".equals(role))   return "管理员";
        if ("STAFF".equals(role))   return "普通员工";
        // 法务岗位目前也是配 AUDITOR 这个 SysUser 角色（只读+导出，跟财务权限档位一致），
        // 单纯显示"财务"容易让法务同事误会自己的账号权限档位设错了，标签改成"财务/法务"
        if ("AUDITOR".equals(role)) return "财务/法务";
        if ("GUEST".equals(role))   return "访客";
        return role;
    }
}
