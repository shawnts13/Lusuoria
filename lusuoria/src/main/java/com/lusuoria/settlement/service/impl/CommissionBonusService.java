package com.lusuoria.settlement.service.impl;

import com.lusuoria.settlement.entity.CommissionBonusTier;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.repository.CommissionBonusTierRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 项目负责人/管理层的提成 Bonus 阶梯计算。原本是 DashboardStatsService 的私有方法，
 * 2026-07 抽成独立服务，因为"工资单"模块（PayslipService）也需要同一套计算口径。
 *
 * 批量场景（工资单列表页一次要算所有项目负责人）请用 {@link #findTiersByEmployeeIds} 一次性
 * 查出所有人的阶梯配置，再配合 {@link #computeBonusFromTiers} 逐人计算——不要对每个负责人各自
 * 调用 {@link #computeBonus}/{@link #hasBonusTierConfigured}，那样员工一多就是明显的 N+1
 * （之前 PayslipService 这么写过，一个负责人最多导致两次查询）。
 */
@Service
public class CommissionBonusService {

    private static final int SCALE = 2;

    @Autowired private CommissionBonusTierRepository bonusTierRepo;

    /** 单个负责人场景（不在循环里调用时用）：查一次阶梯、算一次 bonus */
    public BigDecimal computeBonus(Employee manager, BigDecimal commissionTotalUsd, BigDecimal monthRate) {
        if (manager == null || manager.getBonusTierCurrency() == null) return BigDecimal.ZERO;
        List<CommissionBonusTier> tiers = bonusTierRepo
                .findByEmployeeIdAndIsDeletedFalseOrderByMinAmountAsc(manager.getId());
        return computeBonusFromTiers(manager, tiers, commissionTotalUsd, monthRate);
    }

    /** 单个负责人场景：该负责人是否配置了 bonus 阶梯（bonusTierCurrency 非空且至少有一档） */
    public boolean hasBonusTierConfigured(Employee manager) {
        if (manager == null || manager.getBonusTierCurrency() == null) return false;
        return !bonusTierRepo.findByEmployeeIdAndIsDeletedFalseOrderByMinAmountAsc(manager.getId()).isEmpty();
    }

    /** 批量场景：一次性查出这一批负责人的全部阶梯配置，按 employeeId 分组，供 computeBonusFromTiers 复用 */
    public Map<Long, List<CommissionBonusTier>> findTiersByEmployeeIds(List<Long> employeeIds) {
        if (employeeIds == null || employeeIds.isEmpty()) return Collections.emptyMap();
        return bonusTierRepo.findByEmployeeIdInAndIsDeletedFalseOrderByMinAmountAsc(employeeIds).stream()
                .collect(Collectors.groupingBy(CommissionBonusTier::getEmployeeId));
    }

    /**
     * 按项目负责人配置的 bonus 阶梯（已经查好、传进来，不在这里再查库），用这个负责人在当前
     * 下钻时间范围内的提成总额（美金）判档，命中区间后 bonus = 提成总额（美金） × 该档位
     * bonusRate。没配置阶梯（bonusTierCurrency 为空，或没有任何档位）的负责人返回 0。
     */
    public BigDecimal computeBonusFromTiers(Employee manager, List<CommissionBonusTier> tiers,
                                             BigDecimal commissionTotalUsd, BigDecimal monthRate) {
        if (manager == null || manager.getBonusTierCurrency() == null || tiers == null || tiers.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal amountInChosenCurrency = "RMB".equals(manager.getBonusTierCurrency()) && monthRate != null
                ? commissionTotalUsd.multiply(monthRate) : commissionTotalUsd;
        for (CommissionBonusTier tier : tiers) {
            boolean aboveMin = amountInChosenCurrency.compareTo(tier.getMinAmount()) >= 0;
            boolean withinMax = tier.getMaxAmount() == null
                    || amountInChosenCurrency.compareTo(tier.getMaxAmount()) <= 0;
            if (aboveMin && withinMax) {
                return commissionTotalUsd.multiply(tier.getBonusRate()).setScale(SCALE, RoundingMode.HALF_UP);
            }
        }
        return BigDecimal.ZERO;
    }
}
