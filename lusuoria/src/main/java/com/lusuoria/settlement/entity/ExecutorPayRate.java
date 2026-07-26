package com.lusuoria.settlement.entity;

import lombok.*;

import javax.persistence.*;
import java.math.BigDecimal;

/**
 * 执行人员薪资梯度 - 按 (项目负责人, 执行人员) 独立维护。
 *
 * 2026-07 之前，执行人员的费率梯度存在 {@code Employee} 自己身上、全公司共用一份；
 * 现在改成每个项目负责人可以跟同一个执行人员各自谈价格，一个 (managerId, executorId)
 * 组合对应一条记录。字段跟原来 Employee 上那 6 个字段一一对应，含义不变。
 *
 * (managerId, executorId) 唯一性由 service 层"先查后插/更新"保证，不建数据库唯一约束。
 */
@Entity
@Table(name = "executor_pay_rates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutorPayRate extends BaseEntity {

    /** 项目负责人（或管理层）的员工 id */
    @Column(name = "manager_id", nullable = false)
    private Long managerId;

    /** 执行人员的员工 id */
    @Column(name = "executor_id", nullable = false)
    private Long executorId;

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
}
