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

    /** 视频项目数量（来自红人合作跟踪表） */
    private Long videoProjectCount;

    /** 客户合作价格合计 */
    private BigDecimal totalClientPrice;

    /** 红人成本合计 */
    private BigDecimal totalInfluencerCost;

    /** 项目毛利合计 = 客户合作价格 - 红人成本 - 其他外部成本 */
    private BigDecimal totalGrossProfit;

    /** 可分配利润合计 = 项目毛利 - 内部执行成本 */
    private BigDecimal totalDistributableProfit;

    /** 负责人提成合计 */
    private BigDecimal totalCommissionAmount;

    /** 公司利润 = 客户合作价格 - 红人成本 - 其他外部成本 - 内部执行成本 - 负责人提成 */
    private BigDecimal totalCompanyProfit;

    /** 当前展示币种：USD 或 RMB */
    private String currency;

    /** 本次汇总用到的汇率信息（用于前端展示和换算说明） */
    private ExchangeRateInfo exchangeRateInfo;
}
