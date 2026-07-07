package com.lusuoria.settlement.util;

import com.lusuoria.settlement.entity.ProjectOrder;
import com.lusuoria.settlement.enums.ProjectType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 核心利润计算工具
 *
 * 注意：其他外部成本、内部执行成本这两个字段填的是人民币金额，客户合作价格/
 * 红人成本/项目毛利这些都是美元计价，所以这两个成本参与美元计算之前，要先按
 * 这条订单的汇率换算成美元（见 toUsd）。
 *
 * 海外红人（差价模式）：
 *   红人成本 = 直填
 *   项目毛利 = 客户合作价格 - 红人成本 - 其他外部成本(已换算成美元)
 *   项目可分配利润 = 项目毛利 - 内部执行成本(已换算成美元)
 *
 * 中国红人（35/65分成）：
 *   红人成本 = 客户合作价格 × 65%
 *   项目毛利 = 客户合作价格 × 35%
 *   项目可分配利润 = 项目毛利 - 其他外部成本(已换算成美元) - 内部执行成本(已换算成美元)
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

    public void calculate(ProjectOrder order) {
        BigDecimal clientPrice   = safe(order.getClientPrice());
        BigDecimal commissionRate = safe(order.getCommissionRate());
        BigDecimal exchangeRate  = safe(order.getExchangeRate());

        // 其他外部成本、内部执行成本这两个字段是以人民币为单位填写的，
        // 客户合作价格/项目毛利这些都是美元计价，两者不能直接相减，
        // 要先按这条订单的汇率把人民币成本换算成美元，再参与后面的利润计算
        BigDecimal otherCost = toUsd(safe(order.getOtherExternalCost()), exchangeRate);
        BigDecimal execCost  = toUsd(safe(order.getInternalExecutionCost()), exchangeRate);

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

    /** 人民币金额按汇率换算成美元，汇率缺失或非法时按0处理（避免除以0） */
    private BigDecimal toUsd(BigDecimal rmbAmount, BigDecimal exchangeRate) {
        if (exchangeRate == null || exchangeRate.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        return rmbAmount.divide(exchangeRate, SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
