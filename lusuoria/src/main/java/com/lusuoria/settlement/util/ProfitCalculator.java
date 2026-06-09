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
 *   红人成本 = 红人单价 × 数量
 *   项目毛利 = 品牌方收入 - 红人成本 - 其他外部成本
 *   项目可分配利润 = 项目毛利 - 内部执行成本
 *
 * 中国红人（35/65分成）：
 *   红人成本 = 客户收入 × 65%
 *   项目毛利 = 客户收入 × 35%
 *   项目可分配利润 = 项目毛利 - 其他外部成本 - 内部执行成本
 *
 * 通用：
 *   负责人提成 = 项目可分配利润 × 提成比例
 *   公司剩余利润 = 项目可分配利润 - 负责人提成
 */
@Component
public class ProfitCalculator {

    private static final BigDecimal CHINA_COST_RATIO    = new BigDecimal("0.65");
    private static final BigDecimal CHINA_COMPANY_RATIO = new BigDecimal("0.35");
    private static final int        SCALE               = 2;

    public void calculate(ProjectOrder order) {
        BigDecimal clientRevenue = safe(order.getClientRevenue());
        BigDecimal otherCost     = safe(order.getOtherExternalCost());
        BigDecimal execCost      = safe(order.getInternalExecutionCost());
        BigDecimal rate          = safe(order.getCommissionRate());

        // 人民币收入
        if (order.getExchangeRate() != null
                && order.getExchangeRate().compareTo(BigDecimal.ZERO) > 0
                && !"RMB".equalsIgnoreCase(order.getCurrency())
                && !"CNY".equalsIgnoreCase(order.getCurrency())) {
            order.setRmbRevenue(clientRevenue.multiply(order.getExchangeRate())
                    .setScale(SCALE, RoundingMode.HALF_UP));
        } else {
            order.setRmbRevenue(clientRevenue.setScale(SCALE, RoundingMode.HALF_UP));
        }

        BigDecimal influencerCost;
        BigDecimal grossProfit;

        if (ProjectType.CHINA_INFLUENCER == order.getProjectType()) {
            // 中国红人：固定 65/35
            influencerCost = clientRevenue.multiply(CHINA_COST_RATIO)
                    .setScale(SCALE, RoundingMode.HALF_UP);
            grossProfit = clientRevenue.multiply(CHINA_COMPANY_RATIO)
                    .setScale(SCALE, RoundingMode.HALF_UP);
            // 如果没有手动录入红人单价，自动按65%填充
            if (order.getInfluencerUnitPrice() == null && order.getClientUnitPrice() != null) {
                order.setInfluencerUnitPrice(order.getClientUnitPrice()
                        .multiply(CHINA_COST_RATIO).setScale(SCALE, RoundingMode.HALF_UP));
            }
        } else {
            // 海外红人：差价模式
            influencerCost = safe(order.getInfluencerCost());
            // 如果红人成本为0但有单价和数量，则自动计算
            if (influencerCost.compareTo(BigDecimal.ZERO) == 0
                    && order.getInfluencerUnitPrice() != null
                    && order.getCooperationQuantity() != null) {
                influencerCost = order.getInfluencerUnitPrice()
                        .multiply(BigDecimal.valueOf(order.getCooperationQuantity()))
                        .setScale(SCALE, RoundingMode.HALF_UP);
            }
            grossProfit = clientRevenue
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

        // 公司剩余利润
        order.setCompanyNetProfit(distributable.subtract(commission).setScale(SCALE, RoundingMode.HALF_UP));
    }

    private BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}