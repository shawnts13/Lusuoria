package com.lusuoria.settlement.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** "工资单"管理层列表页里的一行（一个员工），已按请求的 currency 换算好、可以直接展示 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayslipRowResponse {
    private Long employeeId;
    private String employeeName;
    private String employeeRole;
    private Boolean confirmed;

    /** 项目负责人/执行人员才有，其余角色为 null（前端据此不做"可点击"处理） */
    private Long videoCount;

    /** 提成金额 / 执行薪酬合计 / 固定月薪 / 法务工资 / (管理层)公司利润，已换算好 */
    private BigDecimal baseAmount;

    /** 仅项目负责人配置了阶梯bonus时非空 */
    private BigDecimal tierBonusAmount;

    private BigDecimal extraBonusAmount;
    /** 奖金的原始录入值/币种（未换算），供"设置奖金"弹窗回显真实原值用 */
    private BigDecimal extraBonusAmountNative;
    private String extraBonusCurrencyNative;
    private BigDecimal totalAmount;

    /** 法务专属：本月工资是否已录入，前端据此显示"输入本月工资"还是"编辑工资"按钮 */
    private Boolean legalSalarySet;

    /** 仅管理层自己那一行可能非空："请先确认其他员工的工资单后再确认管理层工资单" */
    private String blockedReason;
}
