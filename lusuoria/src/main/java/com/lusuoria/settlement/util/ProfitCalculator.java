package com.lusuoria.settlement.util;

import com.lusuoria.settlement.entity.ProjectOrder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 核心利润计算工具
 *
 * 注意1：其他外部成本、内部执行成本这两个字段填的是人民币金额，客户合作价格/
 * 红人成本/项目毛利这些都是美元计价，所以这两个成本参与美元计算之前，要先按
 * 这条订单的汇率换算成美元（见 toUsd）。
 *
 * 注意2（重要业务规则）：内部执行成本是不是要影响公司利润，取决于这条订单的
 * "项目负责人"是不是"管理层"角色（目前系统里只有一个人是管理层）：
 *   - 项目负责人是管理层：这笔钱是管理层自己接单、自己找执行人员干活付的工资，
 *     按员工管理里维护的费率梯度算，这部分成本要从公司利润里扣掉
 *   - 项目负责人不是管理层：执行人员的工资是这个项目负责人自己掏腰包付的，
 *     不是公司出的钱，所以哪怕这个字段填了金额，也不能从公司利润里扣
 * 其他外部成本不受这条规则影响，不管谁是项目负责人都正常扣减。
 *
 * 红人成本一律以"实际值"为准（红人合作跟踪 Excel 导入 / 前端录入进来的值直填），
 * 不再按红人类型强制自动计算。之前中国红人固定按"客户合作价格 × 65%"覆盖，现已废弃：
 * 因为有些红人的类型可能被标记错了，强制换算会算出错误的成本，所以统一改成直填实际值。
 *
 * 现在中国红人和海外红人走完全一致的差价模式，红人类型不再影响任何金额计算：
 *   红人成本 = 直填（实际值）
 *   项目毛利 = 客户合作价格 - 红人成本 - 其他外部成本(已换算成美元，按实际录入值)
 *   项目可分配利润 = 项目毛利 - 内部执行成本(仅项目负责人是管理层时才扣，已换算成美元)
 *
 * 通用：
 *   负责人提成 = 项目可分配利润 × 提成比例
 *   公司利润（美金） = 项目可分配利润 - 负责人提成
 *   公司利润（人民币） = 公司利润（美金） × 汇率
 */
@Component
public class ProfitCalculator {

    private static final int SCALE = 2;

    /** 员工角色里"管理层"这个角色的固定叫法，跟 OptionConfigController 里维护的一致 */
    public static final String MANAGEMENT_ROLE = "管理层";

    public void calculate(ProjectOrder order) {
        BigDecimal clientPrice   = safe(order.getClientPrice());
        BigDecimal commissionRate = safe(order.getCommissionRate());
        BigDecimal exchangeRate  = safe(order.getExchangeRate());

        // 其他外部成本、内部执行成本这两个字段是以人民币为单位填写的，
        // 客户合作价格/项目毛利这些都是美元计价，两者不能直接相减，
        // 要先按这条订单的汇率把人民币成本换算成美元，再参与后面的利润计算
        // （不分红人类型，其他外部成本都按实际录入值参与差价计算）
        BigDecimal otherCost = toUsd(safe(order.getOtherExternalCost()), exchangeRate);
        BigDecimal execCostRaw = toUsd(safe(order.getInternalExecutionCost()), exchangeRate);
        // 内部执行成本只有项目负责人是"管理层"的时候才真的从利润里扣，
        // 不是管理层的话，这个字段只是记录用，不影响公司利润
        BigDecimal execCost = isManagementOrder(order) ? execCostRaw : BigDecimal.ZERO;

        // 红人成本：不分红人类型，一律取录入的实际值（直填），不再按类型强制 65% 自动计算
        BigDecimal influencerCost = safe(order.getInfluencerCost());

        // 项目毛利 = 客户合作价格 - 红人成本 - 其他外部成本（不分红人类型，口径一致）
        BigDecimal grossProfit = clientPrice
                .subtract(influencerCost)
                .subtract(otherCost)
                .setScale(SCALE, RoundingMode.HALF_UP);

        order.setInfluencerCost(influencerCost);
        order.setGrossProfit(grossProfit);

        // 可分配利润 = 项目毛利 - 内部执行成本（其他外部成本已经在毛利里扣过，这里不再重复扣）
        BigDecimal distributable = grossProfit.subtract(execCost)
                .setScale(SCALE, RoundingMode.HALF_UP);
        order.setDistributableProfit(distributable);

        // 负责人提成
        BigDecimal commission = distributable.multiply(commissionRate).setScale(SCALE, RoundingMode.HALF_UP);
        order.setCommissionAmount(commission);

        // 公司利润（美金）
        BigDecimal companyProfitUsd = distributable.subtract(commission).setScale(SCALE, RoundingMode.HALF_UP);
        order.setCompanyNetProfit(companyProfitUsd);

        // 公司利润（人民币）= 公司利润（美金） × 汇率
        if (exchangeRate.compareTo(BigDecimal.ZERO) > 0) {
            order.setRmbRevenue(companyProfitUsd.multiply(exchangeRate)
                    .setScale(SCALE, RoundingMode.HALF_UP));
        } else {
            order.setRmbRevenue(BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP));
        }
    }

    /** 这条订单的项目负责人是不是"管理层"角色（内部执行成本要不要影响利润，就看这个） */
    public boolean isManagementOrder(ProjectOrder order) {
        return order.getProjectManager() != null
                && MANAGEMENT_ROLE.equals(order.getProjectManager().getRole());
    }

    /** 人民币金额按汇率换算成美元，汇率缺失或非法时按0处理（避免除以0） */
    private BigDecimal toUsd(BigDecimal rmbAmount, BigDecimal exchangeRate) {
        if (exchangeRate == null || exchangeRate.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        return rmbAmount.divide(exchangeRate, SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
