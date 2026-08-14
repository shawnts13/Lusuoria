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
 * 注意3：红人成本/客户合作价格 2026-07 起是严格数字字段（numeric(15,2)），不再像
 * 以前那样允许"价格待定"这类文本备注，所以这里不需要再做任何文本解析。
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
/**
 * 【Java 知识点】全文件的金额计算都用 BigDecimal 而不是 double/float，是因为 double 是二进制浮点数，
 * 没法精确表示十进制小数（比如 0.1 + 0.2 在 double 里算出来是 0.30000000000000004，不是 0.3），
 * 金额计算这种"差一分钱都不行"的场景绝对不能用；BigDecimal 内部是按十进制精确存储的，才能保证
 * 加减乘除结果分毫不差。用法上有两个新手容易踩的坑，全文件的写法已经在规避：
 * 1）BigDecimal 是不可变对象（immutable），subtract()/multiply()/divide() 这些方法都是"返回一个
 *    新的 BigDecimal 实例，原对象不变"，不是在原对象上原地修改——所以下面全是
 *    "xxx.subtract(yyy).setScale(...)" 这种链式写法，每一步都要接住返回值，写成
 *    "xxx.subtract(yyy);" 不接返回值等于这行代码白写了。
 * 2）判断两个 BigDecimal 是否"数值相等"要用 compareTo(...) == 0，不能用 equals()——
 *    BigDecimal.equals() 连小数位数（scale）都要求一致，new BigDecimal("80.00") 和
 *    new BigDecimal("80.0") 数值相等但 equals() 会返回 false（这也是本项目
 *    "设置执行成本二次修改需审核"那个 amountUnchanged 判断专门强调用 compareTo 而不是 equals
 *    的原因）。
 * setScale(SCALE, RoundingMode.HALF_UP) 是"保留几位小数 + 怎么四舍五入"：SCALE=2 表示保留两位
 * 小数（分），HALF_UP 是最常见的"四舍五入"（严格地说是"离得较远的那一侧为 0.5 时往上进"，
 * 跟日常理解的四舍五入基本一致，区别于 HALF_EVEN"银行家舍入"这种更少见的策略）。
 */
@Component
public class ProfitCalculator {

    private static final int SCALE = 2;

    /** 员工角色里"管理层"这个角色的固定叫法，跟 OptionConfigController 里维护的一致 */
    public static final String MANAGEMENT_ROLE = "管理层";

    public void calculate(CollaborationTracking t) {
        BigDecimal clientPrice   = safe(t.getClientPrice());
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
        BigDecimal influencerCost = safe(t.getInfluencerCost());

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

    private BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
