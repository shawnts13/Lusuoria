package com.lusuoria.settlement.util;

import com.lusuoria.settlement.config.EmployeeCache;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.entity.SysUser;
import com.lusuoria.settlement.repository.SysUserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 项目订单/红人合作跟踪 - 字段级可见性判定。
 *
 * 权限矩阵（2026-07 调整）：
 *  - ADMIN、或 STAFF 关联的员工角色是"管理层"/"财务" -> FULL：所有字段（含利润/提成）都能看、能改
 *  - AUDITOR -> AUDITOR_VIEW：全部字段只读可见（不变）
 *  - STAFF 关联的员工角色是"项目负责人" -> PROJECT_MANAGER：
 *      其他外部成本/内部执行成本，仅自己负责的项目条目可见可改，其余显示"—"；
 *      提成比例/提成金额，仅自己负责的项目条目只读可见，其余显示"—"；
 *      可分配利润/项目毛利，完全不可见（无论是不是自己的项目）
 *  - STAFF 关联的员工角色是"执行人员" -> EXECUTOR：
 *      内部执行成本，仅自己执行的项目条目可见可改，其余显示"—"；
 *      其他外部成本、提成比例、提成金额、可分配利润、项目毛利，完全不可见
 *  - STAFF 关联"IT后勤"/"法务"、或未关联员工、或角色不属于以上任何一种 -> BASELINE：
 *      只能看到"红人成本/客户合作价格/已到账金额"这3个基础字段，其余成本/利润/提成字段完全不可见
 *  - GUEST -> 沿用原逻辑：基础字段也不可见
 */
@Component
public class ProjectFieldVisibility {

    @Autowired private SysUserRepository sysUserRepo;
    @Autowired private EmployeeCache employeeCache;

    public enum Tier { FULL, AUDITOR_VIEW, PROJECT_MANAGER, EXECUTOR, BASELINE, GUEST }

    public static class Context {
        public Tier tier;
        public Long employeeId;   // 当前登录账号关联的员工id（行级比对用），可能为 null

        public boolean isFull() { return tier == Tier.FULL || tier == Tier.AUDITOR_VIEW; }
    }

    /** 计算当前登录用户的权限等级 */
    public Context resolve() {
        Context ctx = new Context();
        String role = RoleUtil.getCurrentRole();

        if ("ADMIN".equals(role)) { ctx.tier = Tier.FULL; return ctx; }
        if ("AUDITOR".equals(role)) { ctx.tier = Tier.AUDITOR_VIEW; return ctx; }
        if ("GUEST".equals(role)) { ctx.tier = Tier.GUEST; return ctx; }

        // STAFF：看登录账号关联的员工角色
        SysUser user = sysUserRepo.findByUsernameAndIsDeletedFalse(RoleUtil.getCurrentUsername()).orElse(null);
        Long employeeId = user != null ? user.getEmployeeId() : null;
        ctx.employeeId = employeeId;

        Employee emp = employeeId != null ? employeeCache.findById(employeeId) : null;
        String empRole = emp != null ? emp.getRole() : null;

        if ("管理层".equals(empRole) || "财务".equals(empRole)) {
            ctx.tier = Tier.FULL;
        } else if ("项目负责人".equals(empRole)) {
            ctx.tier = Tier.PROJECT_MANAGER;
        } else if ("执行人员".equals(empRole)) {
            ctx.tier = Tier.EXECUTOR;
        } else {
            // IT后勤 / 法务 / 未关联员工 / 其他角色
            ctx.tier = Tier.BASELINE;
        }
        return ctx;
    }
}
