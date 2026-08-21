package com.lusuoria.settlement.entity;

import javax.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.Date;

@Entity
@Table(name = "employees")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Employee extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;                     // 姓名

    @Column(name = "role")
    private String role;                     // 角色：项目负责人 / 执行人员 / 管理层 / 财务 / 法务 / IT后勤

    @Column(name = "email", unique = true)
    private String email;

    @Column(name = "contact_phone")
    private String contactPhone;             // 联系电话

    @Temporal(TemporalType.DATE)
    @Column(name = "hire_date")
    private Date hireDate;                   // 入职时间

    /** 离职时间：为空表示在职，前端展示为"-" */
    @Temporal(TemporalType.DATE)
    @Column(name = "resign_date")
    private Date resignDate;                 // 离职时间

    // 默认提成比例（可在项目层面覆盖）——仅"项目负责人"“管理层”维护
    @Column(name = "default_commission_rate", precision = 5, scale = 4)
    private BigDecimal defaultCommissionRate; // 如 0.25 表示 25%

    /**
     * 提成 bonus 阶梯判档用的币种（"RMB"/"USD"），仅"项目负责人"“管理层”维护，
     * 配合 {@link CommissionBonusTier} 一起使用：档位的最低/最高金额按这个币种理解，
     * 判档时把当月提成总额（美金）按当月汇率换算成这个币种再比对。为空表示未配置 bonus 阶梯。
     */
    @Column(name = "bonus_tier_currency")
    private String bonusTierCurrency;

    // ===== 固定月薪（人民币）——仅"财务”“IT后勤”维护 =====
    @Column(name = "fixed_monthly_salary", precision = 12, scale = 2)
    private BigDecimal fixedMonthlySalary;

    /**
     * "成为项目管理员的时间"（2026-08-21 新增，"项目管理员"角色需求）：非空表示这个员工同时
     * 具备"项目管理员"身份——这是叠加在 role 之上的正交属性，不是 role 本身的一个取值。只允许
     * role="项目负责人" 的员工设置（管理层不允许，因为管理层本身就已经包含了这些权限），由
     * EmployeeController.save() 校验。用于工资单/数据看板按月计算时判断"这个月要不要把项目
     * 管理员固定月薪计入这个员工"——只有 >= 这个日期所在月份才计入，之前的月份/已确认的工资单
     * 不受这个字段影响（即使之后改这个日期，已确认的工资单也不会跟着变，见 PayslipService）。
     */
    @Temporal(TemporalType.DATE)
    @Column(name = "project_admin_since")
    private Date projectAdminSince;

    /** 项目管理员固定月薪（人民币），只有 projectAdminSince 不为空时才有意义，非项目管理员留空 */
    @Column(name = "project_admin_fixed_monthly_salary", precision = 12, scale = 2)
    private BigDecimal projectAdminFixedMonthlySalary;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}