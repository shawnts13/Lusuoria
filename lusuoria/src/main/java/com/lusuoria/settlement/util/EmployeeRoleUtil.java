package com.lusuoria.settlement.util;

import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.config.EmployeeManagedBrandCache;
import com.lusuoria.settlement.config.SysUserCache;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.entity.SysUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 当前登录账号关联的员工角色（Employee.role）判定，跟 SysUser.role（ADMIN/STAFF/AUDITOR/GUEST）
 * 无关。多个模块（红人结款、品牌方管理等）的"严格按员工角色限制访问"都基于这个判断，
 * 判定方式仿 ProgressReminderService.isManagementEmployee/isCurrentUserManagement。
 */
@Component
public class EmployeeRoleUtil {

    @Autowired private SysUserCache sysUserCache;
    @Autowired private EmployeeCache employeeCache;
    @Autowired private EmployeeManagedBrandCache employeeManagedBrandCache;

    /** 当前登录账号关联员工的 role，未关联员工时返回 null */
    public String getCurrentEmployeeRole() {
        // 2026-08-17 性能修复：改走 SysUserCache（纯只读查询，不用像写操作那样查活库）；旧代码：
        // sysUserRepo.findByUsernameAndIsDeletedFalse(RoleUtil.getCurrentUsername()).orElse(null)
        SysUser user = sysUserCache.findByUsername(RoleUtil.getCurrentUsername());
        if (user == null || user.getEmployeeId() == null) return null;
        Employee emp = employeeCache.findById(user.getEmployeeId());
        return emp != null ? emp.getRole() : null;
    }

    /**
     * 当前登录账号关联的员工 id（不管 SysUser.role 是什么，ADMIN/AUDITOR 账号只要关联了员工
     * 一样能拿到），未关联员工时返回 null。2026-07 新增，供"该记录的项目负责人/执行人员才能
     * 操作"这类按具体员工 id 判断的权限校验使用（不要跟 ProjectFieldVisibility.Context.employeeId
     * 混用——那个只对 STAFF 生效，语义不一样）。
     */
    public Long getCurrentEmployeeId() {
        // 2026-08-17 性能修复：改走 SysUserCache；旧代码：
        // sysUserRepo.findByUsernameAndIsDeletedFalse(RoleUtil.getCurrentUsername()).orElse(null)
        SysUser user = sysUserCache.findByUsername(RoleUtil.getCurrentUsername());
        return user != null ? user.getEmployeeId() : null;
    }

    /** "已加入客户未结算列表"/"客户已结算"这两个状态流转，2026-08 起从"仅财务/管理层"放宽到
     * 这三个角色也能操作（Shawn 反馈）。 */
    private static final Set<String> SETTLEMENT_PROGRESS_EXTRA_ROLES =
            new HashSet<>(Arrays.asList("项目负责人", "执行人员", "IT后勤"));

    /**
     * 当前登录账号的员工角色是不是"已加入客户未结算列表"/"客户已结算"这两个状态流转权限
     * 新放宽进来的那三个角色之一（项目负责人/执行人员/IT后勤）。跟
     * ProjectFieldVisibility.isFull() 是两个独立维度——isFull 决定的是整条记录的财务字段
     * 可见性（汇率/毛利/提成/公司利润等），这三个角色本身依然看不到这些敏感字段，只是被
     * 额外允许触发这两个状态流转动作本身。调用方需要跟 isFull 用 || 组合，不能单独替代。
     */
    public boolean canSetSettlementProgressExtraRole() {
        return SETTLEMENT_PROGRESS_EXTRA_ROLES.contains(getCurrentEmployeeRole());
    }

    /** "上传/编辑/删除团队级合同"（TeamContract，2026-08 新增，替代原来挂在红人身上的
     * InfluencerContract）允许的员工角色——项目负责人/执行人员/法务/管理层/IT后勤，
     * 不含财务（Shawn 明确"财务管钱不管合同文件"）。这是纯 Employee.role 判断，跟
     * SysUser.role（ADMIN/STAFF/AUDITOR/GUEST）无关——哪怕账号是 GUEST 权限档位，
     * 只要关联的员工角色在这个集合里就能维护团队合同。 */
    private static final Set<String> TEAM_CONTRACT_MANAGE_ROLES =
            new HashSet<>(Arrays.asList("项目负责人", "执行人员", "法务", "管理层", "IT后勤"));

    /** 当前登录账号关联的员工角色是不是能维护团队合同的那5个角色之一，见上面字段注释 */
    public boolean canManageTeamContracts() {
        return TEAM_CONTRACT_MANAGE_ROLES.contains(getCurrentEmployeeRole());
    }

    // ===== "项目管理员"角色相关（2026-08-21 新增）=====
    // 权限权限口径跟"项目负责人"完全一样、外加下面这几个方法覆盖的"部分管理层权限"，
    // 见 Employee.projectAdminSince 字段注释：正交叠加在 role="项目负责人" 之上的一个独立
    // 身份，不是 role 本身的一个取值，所以判断方式是"role=项目负责人 且 projectAdminSince
    // 不为空"，不能只查 role。

    /** 某个员工是不是"项目管理员"——role=项目负责人 且 projectAdminSince 不为空，两个条件缺一不可 */
    public boolean isProjectAdmin(Employee emp) {
        return emp != null && "项目负责人".equals(emp.getRole()) && emp.getProjectAdminSince() != null;
    }

    /** 当前登录账号关联的员工是不是"项目管理员"，调用 employeeCache.findById() 现查当前员工对象后复用上面的判断 */
    public boolean isCurrentUserProjectAdmin() {
        Long employeeId = getCurrentEmployeeId();
        if (employeeId == null) return false;
        return isProjectAdmin(employeeCache.findById(employeeId));
    }

    /**
     * 当前登录账号（若是项目管理员）负责管理的品牌方 id 集合；不是项目管理员或未关联员工时
     * 返回空集合（不是 null）。调用方（待处理审核/进度提醒的可见性判断）应该先用
     * isCurrentUserProjectAdmin() 判断身份，再决定要不要按这个集合做品牌方范围过滤——
     * 空集合本身不能区分"不是项目管理员"和"是项目管理员但还没配置任何品牌方"，语义上都是
     * "看不到任何按品牌方范围放开的记录"，调用方通常不需要关心这个区别。
     */
    public Set<Long> getCurrentEmployeeManagedBrandIds() {
        Long employeeId = getCurrentEmployeeId();
        if (employeeId == null || !isCurrentUserProjectAdmin()) return Collections.emptySet();
        return employeeManagedBrandCache.findBrandIdsByEmployeeId(employeeId);
    }
}
