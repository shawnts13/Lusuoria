package com.lusuoria.settlement.entity;

import lombok.*;

import javax.persistence.*;
import java.math.BigDecimal;

/**
 * 项目负责人/管理层 - 提成 bonus 阶梯配置。
 *
 * 按"项目负责人某个时间范围（如当月）的提成总额"判档，命中区间后按 bonusRate 额外奖励
 * （bonus = 提成总额（美金） × bonusRate）。金额单位跟随 {@link Employee#getBonusTierCurrency()}
 * （该员工选定的币种），判档前会把提成总额换算成这个币种再比较，见
 * CommissionBonusService 里的 computeBonus()。
 *
 * maxAmount 为空表示不封顶（该档位覆盖 minAmount 及以上所有金额）。
 */
@Entity
@Table(name = "commission_bonus_tiers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommissionBonusTier extends BaseEntity {

    /** 所属项目负责人/管理层的员工 id */
    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "min_amount", precision = 15, scale = 2, nullable = false)
    private BigDecimal minAmount;

    /** 最高金额，留空表示不封顶 */
    @Column(name = "max_amount", precision = 15, scale = 2)
    private BigDecimal maxAmount;

    /** bonus 比例，如 0.05 表示 5% */
    @Column(name = "bonus_rate", precision = 5, scale = 4, nullable = false)
    private BigDecimal bonusRate;
}
