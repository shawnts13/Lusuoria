package com.lusuoria.settlement.controller;

import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.dto.request.LoginRequest;
import com.lusuoria.settlement.dto.response.ApiResponse;
import com.lusuoria.settlement.entity.SysUser;
import com.lusuoria.settlement.repository.SysUserRepository;
import com.lusuoria.settlement.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired private AuthenticationManager authManager;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private SysUserRepository userRepo;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EmployeeCache employeeCache;

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        Authentication auth = authManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.getUsername(), req.getPassword()));

        SysUser user = userRepo.findByUsernameAndIsDeletedFalse(req.getUsername())
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        String token = jwtUtil.generateToken(user.getUsername(), user.getRole());

        // 用 employeeId 查缓存，不触发懒加载
        com.lusuoria.settlement.entity.Employee emp = employeeCache.findById(user.getEmployeeId());
        String displayName = (emp != null) ? emp.getName() : user.getUsername();

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("token",       token);
        result.put("username",    user.getUsername());
        result.put("displayName", displayName);
        result.put("role",        user.getRole());

        return ApiResponse.success(result);
    }

}