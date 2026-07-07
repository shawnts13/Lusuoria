package com.lusuoria.settlement.dto.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * "内部执行成本"弹窗用：根据执行人员的费率档位，算出这笔订单默认应该填多少钱，
 * 以及算出来的依据说明（展示给操作的人看，方便核对是不是算对了）。
 * 这里给的只是"建议值"，前端还是允许手动修改后再保存。
 */
@Data
public class ExecutorCostSuggestionResponse {
    /** 建议金额，某些情况下（比如视频类型暂未配置费率）可能是 null，此时前端应留空让用户自己填 */
    private BigDecimal suggestedAmount;
    /** 算出这个金额的依据说明，比如"6月该执行人员处理AI新素材：¥70.00" */
    private String breakdown;
    /**
     * 这个建议金额是不是按员工管理里维护的费率梯度算出来的。
     * true：项目负责人是管理层，走费率梯度，前端要展示"这是自动算出来的"这类说明文案
     * false：项目负责人不是管理层，默认给0纯手填，前端不能提及费率梯度这件事
     *        （那是管理层跟执行人员之间的内部安排，不该让其他项目负责人知道）
     */
    private boolean rateBasedSuggestion;
}
