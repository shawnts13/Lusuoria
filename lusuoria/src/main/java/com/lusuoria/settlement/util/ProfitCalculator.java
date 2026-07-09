package com.lusuoria.settlement.util;

import com.lusuoria.settlement.entity.CollaborationTracking;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 核心利润计算工具
 *
 * 2026-07：随着"项目订单"模块整体废弃，这些成本/利润字段（其他外部成本、内部执行成本、
 * 项目毛利、可分配利润、公司利润、提成等）连同这份计算逻辑一起搬到了"红人合作跟踪"上，
 * 计算对象从 ProjectOrder 换成了 CollaborationTracking，公式本身完全不变。
 *
 * 注意1：其他外部成本、内部执行成本这两个字段填的是人民币金额，客户合作价格/
 * 红人成本/项目毛利这些都是美元计价，所以这两个成本参与美元计算之前，要先按
 * 这条记录的汇率换算成美元（见 toUsd）。
 *
 * 注意2（重要业务规则）：内部执行成本是不是要影响公司利润，取决于这条记录的
 * "项目负责人"是不是"管理层"角色（目前系统里只有一个人是管理层）：
 *   - 项目负责人是管理层：这笔钱是管理层自己接单、自己找执行人员干活付的工资，
 *     按员工管理里维护的费率梯度算，这部分成本要从公司利润里扣掉
 *   - 项目负责人不是管理层：执行人员的工资是这个项目负责人自己掏腰包付的，
 *     不是公司出的钱，所以哪怕这个字段填了金额，也不能从公司利润里扣
 * 其他外部成本不受这条规则影响，不管谁是项目负责人都正常扣减。
 *
 * 注意3：红人成本/客户合作价格在 CollaborationTracking 上是文本字段（历史上允许填
 * "价格待定"这类非数字备注），参与计算前需要先解析成数字，解析不出来的按0处理，
 * 但不会反过来改写这两个文本字段本身的显示内容。
 *
 * 红人成本一律以"实际值"为准（红人合作跟踪 Excel 导入 / 前端录入进来的值直填），
 * 不分红人类型：
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

    public void calculate(CollaborationTracking t) {
        BigDecimal clientPrice   = safe(parseAmount(t.getClientPrice()));
        BigDecimal commissionRate = safe(t.getCommissionRate());
        BigDecimal exchangeRate  = safe(t.getExchangeRate());

        // 其他外部成本、内部执行成本这两个字段是以人民币为单位填写的，
        // 客户合作价格/项目毛利这些都是美元计价，两者不能直接相减，
        // 要先按这条记录的汇率把人民币成本换算成美元，再参与后面的利润计算
        BigDecimal otherCost = toUsd(safe(t.getOtherExternalCost()), exchangeRate);
        BigDecimal execCostRaw = toUsd(safe(t.getInternalExecutionCost()), exchangeRate);
        // 内部执行成本只有项目负责人是"管理层"的时候才真的从利润里扣，
        // 不是管理层的话，这个字段只是记录用，不影响公司利润
        BigDecimal execCost = isManagementOrder(t) ? execCostRaw : BigDecimal.ZERO;

        // 红人成本：不分红人类型，一律取录入的实际值（直填）
        BigDecimal influencerCost = safe(parseAmount(t.getInfluencerCost()));

        // 项目毛利 = 客户合作价格 - 红人成本 - 其他外部成本（不分红人类型，口径一致）
        BigDecimal grossProfit = clientPrice
                .subtract(influencerCost)
                .subtract(otherCost)
                .setScale(SCALE, RoundingMode.HALF_UP);
        t.setGrossProfit(grossProfit);

        // 可分配利润 = 项目毛利 - 内部执行成本（其他外部成本已经在毛利里扣过，这里不再重复扣）
        BigDecimal distributable = grossProfit.subtract(execCost)
                .setScale(SCALE, RoundingMode.HALF_UP);
        t.setDistributableProfit(distributable);

        // 负责人提成
        BigDecimal commission = distributable.multiply(commissionRate).setScale(SCALE, RoundingMode.HALF_UP);
        t.setCommissionAmount(commission);

        // 公司利润（美金）
        BigDecimal companyProfitUsd = distributable.subtract(commission).setScale(SCALE, RoundingMode.HALF_UP);
        t.setCompanyNetProfit(companyProfitUsd);

        // 公司利润（人民币）= 公司利润（美金） × 汇率
        if (exchangeRate.compareTo(BigDecimal.ZERO) > 0) {
            t.setRmbRevenue(companyProfitUsd.multiply(exchangeRate)
                    .setScale(SCALE, RoundingMode.HALF_UP));
        } else {
            t.setRmbRevenue(BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP));
        }
    }

    /** 这条记录的项目负责人是不是"管理层"角色（内部执行成本要不要影响利润，就看这个） */
    public boolean isManagementOrder(CollaborationTracking t) {
        return t.getProjectManager() != null
                && MANAGEMENT_ROLE.equals(t.getProjectManager().getRole());
    }

    /** 人民币金额按汇率换算成美元，汇率缺失或非法时按0处理（避免除以0） */
    private BigDecimal toUsd(BigDecimal rmbAmount, BigDecimal exchangeRate) {
        if (exchangeRate == null || exchangeRate.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        return rmbAmount.divide(exchangeRate, SCALE, RoundingMode.HALF_UP);
    }

    /** 把金额文本解析成 BigDecimal，非数字（如"价格待定"）按 null 处理（参与计算时当0） */
    private BigDecimal parseAmount(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            return new BigDecimal(s.trim().replaceAll(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
