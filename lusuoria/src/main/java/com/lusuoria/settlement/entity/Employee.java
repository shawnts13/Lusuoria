package com.lusuoria.settlement.entity;

import javax.persistence.*;
import lombok.*;

import java.math.BigDecimal;

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

    // 默认提成比例（可在项目层面覆盖）——仅"项目负责人"“管理层”维护
    @Column(name = "default_commission_rate", precision = 5, scale = 4)
    private BigDecimal defaultCommissionRate; // 如 0.25 表示 25%

    // ===== 固定月薪（人民币）——仅"财务”“IT后勤”维护 =====
    @Column(name = "fixed_monthly_salary", precision = 12, scale = 2)
    private BigDecimal fixedMonthlySalary;

    // ===== 执行人员：按项目视频类型/件计算工资，以下5个字段仅"执行人员”维护 =====
    /** 实拍新视频（元/条） */
    @Column(name = "rate_real_shot_new", precision = 10, scale = 2)
    private BigDecimal rateRealShotNew;

    /** AI新素材（元/条） */
    @Column(name = "rate_ai_new_material", precision = 10, scale = 2)
    private BigDecimal rateAiNewMaterial;

    /** 旧素材重发 第1-50条（元/条） */
    @Column(name = "rate_old_material_tier1", precision = 10, scale = 2)
    private BigDecimal rateOldMaterialTier1;

    /** 旧素材重发 第51-100条（元/条） */
    @Column(name = "rate_old_material_tier2", precision = 10, scale = 2)
    private BigDecimal rateOldMaterialTier2;

    /** 旧素材重发 第101条及以上（元/条） */
    @Column(name = "rate_old_material_tier3", precision = 10, scale = 2)
    private BigDecimal rateOldMaterialTier3;

    /** 旧素材重发 第101条及以上部分，当月封顶金额（元/月） */
    @Column(name = "old_material_monthly_cap", precision = 10, scale = 2)
    private BigDecimal oldMaterialMonthlyCap;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}