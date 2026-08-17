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
    @Autowired private com.lusuoria.settlement.service.impl.ProgressReminderService progressReminderService;

    /**
     * 登录：校验用户名密码（Spring Security AuthenticationManager，内部走
     * UserDetailsServiceImpl 查 sys_users），成功后签发 JWT，同时把前端要用的一批账号相关信息
     * （显示名、是否管理层、结款模块权限等）一起算好塞进返回体，省得前端登录后还要再发好几个
     * 请求才能拼出完整的账号状态。
     */
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        Authentication auth = authManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.getUsername(), req.getPassword()));

        // 理论上走到这里 user 必然存在（上面 authManager.authenticate 已经验证过），这里只是防御性兜底；
        // 消息统一成跟登录失败一样的模糊提示，不额外泄露信息
        SysUser user = userRepo.findByUsernameAndIsDeletedFalse(req.getUsername())
                .orElseThrow(() -> new RuntimeException("用户名或密码错误"));

        String token = jwtUtil.generateToken(user.getUsername(), user.getRole());

        // 用 employeeId 查缓存，不触发懒加载
        com.lusuoria.settlement.entity.Employee emp = employeeCache.findById(user.getEmployeeId());
        String displayName = (emp != null) ? emp.getName() : user.getUsername();

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("token",       token);
        result.put("username",    user.getUsername());
        result.put("displayName", displayName);
        result.put("role",        user.getRole());
        // 供前端"新建红人合作跟踪时把项目负责人默认填成自己"这类场景使用，未关联员工时为 null
        result.put("employeeId",  user.getEmployeeId());
        // "进度提醒"功能受众：看登录账号关联的员工角色是不是"管理层"，跟登录账号本身的
        // ADMIN/STAFF/AUDITOR/GUEST 角色无关（见 ProgressReminderService.isManagementEmployee）
        result.put("isManagement", progressReminderService.isManagementEmployee(user.getEmployeeId()));
        // "红人结款"模块受众：登录账号关联的员工角色（管理层/财务/法务才能看到，仅管理层能新增），
        // 同样跟 SysUser.role 无关，见 PaymentAccessUtil
        result.put("employeeRole", emp != null ? emp.getRole() : null);

        return ApiResponse.success(result);
    }

}