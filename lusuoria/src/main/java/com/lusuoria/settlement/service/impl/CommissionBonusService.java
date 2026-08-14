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
        CommissionBonusTier matched = findMatchedTier(manager, tiers, commissionTotalUsd, monthRate);
        if (matched == null) return BigDecimal.ZERO;
        return commissionTotalUsd.multiply(matched.getBonusRate()).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 判档逻辑跟 {@link #computeBonusFromTiers} 完全一样，但只返回命中的那一档（不算金额），
     * 供工资单详情页展示"bonus比例"用（2026-07-28 新增，跟"提成比例"并排展示）——判档逻辑
     * 必须共用同一份，不要各写一份，否则以后改判档规则容易漏改一处。
     */
    public CommissionBonusTier findMatchedTier(Employee manager, List<CommissionBonusTier> tiers,
                                                BigDecimal commissionTotalUsd, BigDecimal monthRate) {
        if (manager == null || manager.getBonusTierCurrency() == null || tiers == null || tiers.isEmpty()) {
            return null;
        }
        // 【业务逻辑说明 + 已知风险，未修复】阶梯档位按 minAmount 升序排列，逐档判断，命中第一个
        // "amountInChosenCurrency 落在 [minAmount, maxAmount] 区间"的档位就返回——这要求管理层
        // 在"员工管理"里配置的各档位区间本身互不重叠、首尾相接，代码这里不会校验这个前提，
        // 配错了（比如两档区间有重叠）就静默按数值较小的那一档命中，不会报错提醒。
        //
        // "RMB".equals(...) && monthRate != null 这个条件还有一个更隐蔽的坑：manager 配置的是
        // RMB 档位、但 monthRate 传了 null（调用方 rateForRange()/汇率维护对应月份还没录入汇率时
        // 就会发生，见 ExchangeRateService.getRateForMonth() 缺失时 usdToCny 返回 null 的分支），
        // 这个三元表达式会静默落到 else 分支——直接拿"美金"提成总额 commissionTotalUsd 去跟
        // "人民币"档位的 minAmount/maxAmount 比较，相当于把一个大约小7倍的数字拿去匹配 RMB 档位，
        // 大概率会匹配到远低于实际应得的档位（甚至因为数值太小落不进任何档位、直接返回 null 显示
        // "-"）。目前没有对这种"currency=RMB 但汇率缺失"的组合做任何特殊处理/报错/日志，是一个
        // 已知但尚未修复的静默风险点，命中概率取决于"当月汇率是否已经在汇率维护页面录入"——正常
        // 使用流程下汇率通常会提前录好，所以实际触发概率不高，但没有兜底。
        BigDecimal amountInChosenCurrency = "RMB".equals(manager.getBonusTierCurrency()) && monthRate != null
                ? commissionTotalUsd.multiply(monthRate) : commissionTotalUsd;
        for (CommissionBonusTier tier : tiers) {
            boolean aboveMin = amountInChosenCurrency.compareTo(tier.getMinAmount()) >= 0;
            boolean withinMax = tier.getMaxAmount() == null
                    || amountInChosenCurrency.compareTo(tier.getMaxAmount()) <= 0;
            if (aboveMin && withinMax) return tier;
        }
        return null;
    }
}
