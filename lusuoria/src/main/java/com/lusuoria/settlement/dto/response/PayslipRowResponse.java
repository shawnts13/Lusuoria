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

    /** 仅项目负责人角色有意义：是否已经确认了名下执行人员的工资，管理层不用点进明细就能看到 */
    private Boolean executorWageConfirmed;

    /**
     * 仅项目负责人角色有意义（2026-07 新增）：这个项目负责人当月名下是否真的有涉及执行人员的
     * 记录。项目负责人当月负责的视频如果压根没有一条设置了执行人员，管理层列表页就不应该再
     * 展示"执行人员工资预计/已确认"这个标签——那种情况下这个标签只会造成误解。
     */
    private Boolean hasExecutorWageWork;

    // ===== 管理层专属：公司利润计算公式展示用（其余角色为 null），均已按请求币种换算好 =====
    private BigDecimal grossProfit;
    private BigDecimal distributableProfit;
    /** 负责人提成合计（含所有项目负责人的阶梯bonus），跟 baseAmount 不是一回事——管理层这一行的
     * baseAmount 是 null，总工资走的是 companyProfit（即 totalAmount），这个字段单独给公式展示用 */
    private BigDecimal managerCommissionTotal;
    private BigDecimal executorPayTotal;
    private BigDecimal otherStaffCost;
    private BigDecimal extraBonusPayoutTotal;

    /** 仅管理层自己那一行可能非空："请先确认其他员工的工资单后再确认管理层工资单"；
     * 执行人员那一行非空时是"哪些项目负责人还没确认"的具体名单文案 */
    private String blockedReason;

    /**
     * 仅执行人员角色、且当前还没最终确认时有意义（2026-07-28 新增）：这个执行人员当月涉及的
     * 多个项目负责人（含以项目负责人身份行事的管理层）里，是不是已经有部分（但不是全部）
     * 确认过了——用于前端在"预计"和"已确认"之间展示一个中间状态"待其他项目负责人确认"。
     */
    private Boolean awaitingOtherManagers;
}
