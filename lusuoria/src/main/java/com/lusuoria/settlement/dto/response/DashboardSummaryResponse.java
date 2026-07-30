package com.lusuoria.settlement.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 数据看板顶部汇总卡片
 *
 * 所有金额字段均按请求里的 currency 参数（USD/RMB）统一换算后返回，
 * 前端不需要再自己做汇率换算。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardSummaryResponse {

    /**
     * 所属月份（yyyyMM，2026-07 新增）。单月查询（/summary）不需要用这个字段，留空即可；
     * 区间汇总（/range-summary）的 monthly 数组里每一项会填上对应月份，供前端月度趋势图用。
     */
    private String yearMonth;

    /** 视频项目数量（来自红人合作跟踪表，含"折损"记录——这是真实存在过的项目总数） */
    private Long videoProjectCount;

    /**
     * 其中视频项目进度="折损"的笔数（2026-07 新增，供前端标注"XX笔（其中X笔为折损）"）。
     * 折损记录不参与下面任何金额统计（客户合作价格/红人成本/项目毛利/公司利润等），
     * 只在这个计数维度里保留可见性。
     */
    private Long damagedVideoProjectCount;

    /** 客户合作价格合计 */
    private BigDecimal totalClientPrice;

    /** 红人成本合计 */
    private BigDecimal totalInfluencerCost;

    /** 其他外部成本合计 */
    private BigDecimal totalOtherExternalCost;

    /** 内部执行人力成本合计（展示用总数，不区分是不是影响公司利润——所有已填的执行成本原始值） */
    private BigDecimal totalInternalExecutionCost;

    /**
     * 2026-07 新增：真正参与"公司利润"公式计算的那部分内部执行人力成本（只有项目负责人是
     * "管理层"的记录才计入，口径跟工资单模块一致）。公式展示要用这个字段，不要用上面
     * totalInternalExecutionCost 那个未筛选的总数——之前公式展示用错了字段，导致公式里几项
     * 加减对不上（虽然 totalCompanyProfit 本身算的是对的）。
     */
    private BigDecimal totalInternalExecutionCostForProfit;

    /** 内部其他员工成本合计（财务、IT后勤这些角色的固定月薪，法务角色薪资方案还没设计，暂不计入） */
    private BigDecimal totalOtherStaffCost;

    /**
     * 奖金合计（Payslip.extraBonusAmount，2026-07 新增，任何角色都可能被设置）。当月没有任何
     * 人设置奖金时为 0，前端据此决定要不要显示这张卡片（不为 0 才显示）。
     */
    private BigDecimal totalExtraBonus;

    /** 项目毛利合计 = 客户合作价格 - 红人成本 - 其他外部成本 */
    private BigDecimal totalGrossProfit;

    /** 可分配利润合计 = 项目毛利 - 内部执行成本 */
    private BigDecimal totalDistributableProfit;

    /** 负责人提成合计 */
    private BigDecimal totalCommissionAmount;

    /**
     * 公司利润 = 项目毛利 - 内部执行人力成本（仅计入公司利润的那部分，见
     * totalInternalExecutionCostForProfit） - 负责人提成合计 - 内部其他员工成本 - 奖金
     */
    private BigDecimal totalCompanyProfit;

    /** 当前展示币种：USD 或 RMB */
    private String currency;

    /** 本次汇总用到的汇率信息（用于前端展示和换算说明） */
    private ExchangeRateInfo exchangeRateInfo;
}
