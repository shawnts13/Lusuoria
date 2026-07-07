package com.lusuoria.settlement.util;

import com.lusuoria.settlement.entity.ProjectOrder;
import com.lusuoria.settlement.enums.ProjectType;
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
 * 海外红人（差价模式）：
 *   红人成本 = 直填
 *   项目毛利 = 客户合作价格 - 红人成本 - 其他外部成本(已换算成美元)
 *   项目可分配利润 = 项目毛利 - 内部执行成本(仅项目负责人是管理层时才扣，已换算成美元)
 *
 * 中国红人（35/65分成）：
 *   红人成本 = 客户合作价格 × 65%
 *   项目毛利 = 客户合作价格 × 35%
 *   项目可分配利润 = 项目毛利 - 其他外部成本(已换算成美元) - 内部执行成本(仅项目负责人是管理层时才扣，已换算成美元)
 *
 * 通用：
 *   负责人提成 = 项目可分配利润 × 提成比例
 *   公司利润（美金） = 项目可分配利润 - 负责人提成
 *   公司利润（人民币） = 公司利润（美金） × 汇率
 */
@Component
public class ProfitCalculator {

    private static final BigDecimal CHINA_COST_RATIO    = new BigDecimal("0.65");
    private static final BigDecimal CHINA_COMPANY_RATIO = new BigDecimal("0.35");
    private static final int        SCALE               = 2;

    /** 员工角色里"管理层"这个角色的固定叫法，跟 OptionConfigController 里维护的一致 */
    public static final String MANAGEMENT_ROLE = "管理层";

    public void calculate(ProjectOrder order) {
        BigDecimal clientPrice   = safe(order.getClientPrice());
        BigDecimal commissionRate = safe(order.getCommissionRate());
        BigDecimal exchangeRate  = safe(order.getExchangeRate());

        // 其他外部成本、内部执行成本这两个字段是以人民币为单位填写的，
        // 客户合作价格/项目毛利这些都是美元计价，两者不能直接相减，
        // 要先按这条订单的汇率把人民币成本换算成美元，再参与后面的利润计算
        BigDecimal otherCost = toUsd(safe(order.getOtherExternalCost()), exchangeRate);
        BigDecimal execCostRaw = toUsd(safe(order.getInternalExecutionCost()), exchangeRate);
        // 内部执行成本只有项目负责人是"管理层"的时候才真的从利润里扣，
        // 不是管理层的话，这个字段只是记录用，不影响公司利润
        BigDecimal execCost = isManagementOrder(order) ? execCostRaw : BigDecimal.ZERO;

        BigDecimal influencerCost;
        BigDecimal grossProfit;

        if (ProjectType.CHINA_INFLUENCER == order.getProjectType()) {
            // 中国红人：固定 65/35，红人成本由客户合作价格自动算出
            influencerCost = clientPrice.multiply(CHINA_COST_RATIO)
                    .setScale(SCALE, RoundingMode.HALF_UP);
            grossProfit = clientPrice.multiply(CHINA_COMPANY_RATIO)
                    .setScale(SCALE, RoundingMode.HALF_UP);
        } else {
            // 海外红人：差价模式，红人成本直填
            influencerCost = safe(order.getInfluencerCost());
            grossProfit = clientPrice
                    .subtract(influencerCost)
                    .subtract(otherCost)
                    .setScale(SCALE, RoundingMode.HALF_UP);
        }

        order.setInfluencerCost(influencerCost);
        order.setGrossProfit(grossProfit);

        // 可分配利润
        BigDecimal distributable;
        if (ProjectType.CHINA_INFLUENCER == order.getProjectType()) {
            distributable = grossProfit.subtract(otherCost).subtract(execCost)
                    .setScale(SCALE, RoundingMode.HALF_UP);
        } else {
            distributable = grossProfit.subtract(execCost)
                    .setScale(SCALE, RoundingMode.HALF_UP);
        }
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
