package com.lusuoria.settlement.util;

import com.lusuoria.settlement.entity.ProjectOrder;
import com.lusuoria.settlement.enums.ProjectType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 核心利润计算工具
 *
 * 海外红人（差价模式）：
 *   红人成本 = 直填
 *   项目毛利 = 客户合作价格 - 红人成本 - 其他外部成本
 *   项目可分配利润 = 项目毛利 - 内部执行成本
 *
 * 中国红人（35/65分成）：
 *   红人成本 = 客户合作价格 × 65%
 *   项目毛利 = 客户合作价格 × 35%
 *   项目可分配利润 = 项目毛利 - 其他外部成本 - 内部执行成本
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
        BigDecimal clientPrice = safe(order.getClientPrice());
        BigDecimal otherCost   = safe(order.getOtherExternalCost());
        BigDecimal execCost    = safe(order.getInternalExecutionCost());
        BigDecimal rate        = safe(order.getCommissionRate());

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
        BigDecimal commission = distributable.multiply(rate).setScale(SCALE, RoundingMode.HALF_UP);
        order.setCommissionAmount(commission);

        // 公司利润（美金）
        BigDecimal companyProfitUsd = distributable.subtract(commission).setScale(SCALE, RoundingMode.HALF_UP);
        order.setCompanyNetProfit(companyProfitUsd);

        // 公司利润（人民币）= 公司利润（美金） × 汇率
        if (order.getExchangeRate() != null && order.getExchangeRate().compareTo(BigDecimal.ZERO) > 0) {
            order.setRmbRevenue(companyProfitUsd.multiply(order.getExchangeRate())
                    .setScale(SCALE, RoundingMode.HALF_UP));
        } else {
            order.setRmbRevenue(BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP));
        }
    }

    private BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
