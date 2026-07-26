package com.lusuoria.settlement.service.impl;

import com.lusuoria.settlement.entity.CommissionBonusTier;
import com.lusuoria.settlement.entity.Employee;
import com.lusuoria.settlement.repository.CommissionBonusTierRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 项目负责人/管理层的提成 Bonus 阶梯计算。原本是 DashboardStatsService 的私有方法，
 * 2026-07 抽成独立服务，因为"工资单"模块（PayslipService）也需要同一套计算口径。
 */
@Service
public class CommissionBonusService {

    private static final int SCALE = 2;

    @Autowired private CommissionBonusTierRepository bonusTierRepo;

    /**
     * 按项目负责人配置的 bonus 阶梯，用这个负责人在当前下钻时间范围内的提成总额（美金）判档，
     * 命中区间后 bonus = 提成总额（美金） × 该档位 bonusRate。没配置阶梯（bonusTierCurrency
     * 为空，或没有任何档位）的负责人返回 0。
     */
    public BigDecimal computeBonus(Employee manager, BigDecimal commissionTotalUsd, BigDecimal monthRate) {
        if (manager == null || manager.getBonusTierCurrency() == null) return BigDecimal.ZERO;
        List<CommissionBonusTier> tiers = bonusTierRepo
                .findByEmployeeIdAndIsDeletedFalseOrderByMinAmountAsc(manager.getId());
        if (tiers.isEmpty()) return BigDecimal.ZERO;

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

    /** 该负责人是否配置了 bonus 阶梯（bonusTierCurrency 非空且至少有一档），供调用方判断要不要显示 bonus 行 */
    public boolean hasBonusTierConfigured(Employee manager) {
        if (manager == null || manager.getBonusTierCurrency() == null) return false;
        return !bonusTierRepo.findByEmployeeIdAndIsDeletedFalseOrderByMinAmountAsc(manager.getId()).isEmpty();
    }
}
