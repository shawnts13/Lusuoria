package com.lusuoria.settlement.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 工资单明细弹窗数据 + 确认时冻结进 Payslip.detailJson 的快照结构（两用）。
 *
 * 存快照时，rows[].amount/amount2、baseAmount、tierBonusAmount、grossProfit 等这些金额字段
 * 一律是"原始计价币种"（项目负责人/管理层：美金；执行人员/财务/IT后勤/法务：人民币，
 * 具体哪个字段是哪个币种见字段注释），不做汇率换算；PayslipService 在返回给前端之前
 * （无论是活的实时计算还是读快照）统一按请求的 currency 换算一遍。currency/confirmed/
 * exchangeRateInfo 三个字段是"读取时"才补上的展示上下文，不参与快照序列化的语义。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayslipDetailResponse {

    /** PROJECT_MANAGER / EXECUTOR / FIXED_SALARY / LEGAL / MANAGEMENT */
    private String type;

    private List<PayslipDimensionRow> rows;

    /** 项目负责人专属：提成比例（取 Employee.defaultCommissionRate，仅作展示用） */
    private BigDecimal commissionRate;

    /** 提成金额(项目负责人，美金) / 执行薪酬合计(执行人员，人民币) / 固定月薪(财务IT后勤，人民币) / 法务工资(人民币) */
    private BigDecimal baseAmount;

    /** 阶梯Bonus（仅项目负责人配置了阶梯时非空，美金；未配置=null=不展示该行；已配置未达标=0） */
    private BigDecimal tierBonusAmount;

    /** "奖金"：管理层手动设置的月度额外奖励，任何角色通用，未设置=null=不展示该行（已按请求币种换算） */
    private BigDecimal extraBonusAmount;

    /** 奖金的原始录入值/币种（未换算），供"设置奖金"弹窗回显真实原值用，避免拿换算后的近似值去编辑 */
    private BigDecimal extraBonusAmountNative;
    private String extraBonusCurrencyNative;

    /**
     * 总工资（管理层这一行含义是扣减当月各项发放后的"公司利润"；项目负责人这一行是
     * "管理层所发工资"——提成+阶梯Bonus+奖金，还没扣执行人员那部分，即下面的 finalNetWage）
     */
    private BigDecimal totalAmount;

    // ===== 项目负责人专属：应发给自己名下执行人员的工资（三层薪酬关系里"项目负责人→执行
    // 人员"这一层，由项目负责人自己确认，见 ExecutorWageConfirmation） =====

    /** 按执行人员分组的薪酬明细（含每个执行人员的小计行+整体汇总行），人民币，未换算 */
    private List<PayslipDimensionRow> executorWageRows;
    /** 人民币原值，未换算（走 toDisplayResponse 统一换算） */
    private BigDecimal executorWageTotal;
    /** 这个项目负责人是否已经确认了执行人员工资（这一层的确认状态，独立于 confirmed 字段） */
    private Boolean executorWageConfirmed;
    /** = 总工资("管理层所发工资") − executorWageTotal，两边都换算成请求币种后现算，不单独存快照 */
    private BigDecimal finalNetWage;

    // ===== 管理层专属汇总数字，均为美金 =====
    private BigDecimal grossProfit;
    private BigDecimal distributableProfit;
    /** 负责人提成合计（含所有项目负责人的阶梯bonus） */
    private BigDecimal managerCommissionTotal;
    private BigDecimal executorPayTotal;
    private BigDecimal otherStaffCost;
    /** 当月所有员工已确认的"奖金"（管理层手动发放的月度额外奖励）合计，供公司利润计算公式展示用 */
    private BigDecimal extraBonusPayoutTotal;
    private BigDecimal companyProfit;

    // ===== 读取时补上的展示上下文，不参与快照存储 =====
    private String currency;
    private Boolean confirmed;
    private ExchangeRateInfo exchangeRateInfo;
}
