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

    /**
     * 是否"最终版"（2026-07-28 起语义变化）：不再等同于"管理层是否点过主表格确认按钮"，而是
     * Payslip.finalConfirmed 的直接映射——项目负责人/执行人员这两个角色，还需要相关的
     * "执行人员工资"确认（ExecutorWageConfirmation）全部到位才会变 true；其余角色跟
     * {@link #ownActionConfirmed} 恒等（点确认即最终版，没有下游依赖）。驱动"已确认"（绿色）
     * 标签、以及是否展示冻结快照 vs 实时数据。
     */
    private Boolean confirmed;

    /**
     * 管理层自己是否已经点过这一行的主表格"确认"按钮（即 Payslip.confirmed 原始值，2026-07-28
     * 新增，用于跟上面 {@link #confirmed} 区分开）。驱动前端"确认/取消确认"按钮切换——按钮
     * 反映的是管理层自己有没有点过，不是有没有到达最终版，哪怕还没到最终版，管理层点过之后
     * 也要能"取消确认"。
     */
    private Boolean ownActionConfirmed;

    /** 项目负责人/执行人员才有，其余角色为 null（前端据此不做"可点击"处理） */
    private Long videoCount;

    /** 提成金额 / 执行薪酬合计 / 固定月薪 / 法务工资 / (管理层)公司利润，已换算好 */
    private BigDecimal baseAmount;

    /** 仅项目负责人配置了阶梯bonus时非空 */
    private BigDecimal tierBonusAmount;

    /** "项目管理员"固定月薪（2026-08-21 新增，已换算好）：仅项目负责人同时是项目管理员且这个
     *  月份已生效时非空。列表页"薪酬"列展示时会加到 baseAmount 上——不然"薪酬+阶梯Bonus+奖金"
     *  跟"总工资"这几列会对不上（Shawn 反馈），跟"查看详情"弹窗把这块单独展示成一行是两种
     *  展示策略：详情弹窗有位置拆开展示构成，列表这几列窄、没有位置再加一列，直接并进"薪酬"里 */
    private BigDecimal projectAdminSalaryRmb;

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
     * 执行人员那一行非空时表示：管理层这个月确实欠这个执行人员一份薪酬（是相关项目负责人
     * 之一），但还没有先在"管理层手下执行人员工资"确认过，主表格"确认"按钮暂不可点。 */
    private String blockedReason;

}
