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
     * 这个建议金额是不是按 ExecutorPayRate 里维护的费率梯度算出来的。
     * true：该项目负责人已经在"执行人员管理"/"员工管理"给这个执行人员配置过费率梯度，
     *       走梯度算出建议金额，前端展示"这是自动算出来的"这类说明文案
     * false：该项目负责人还没给这个执行人员配置费率梯度（见 noRateConfigured），
     *        默认给 null 纯手填
     */
    private boolean rateBasedSuggestion;

    /**
     * 2026-07 新增：该项目负责人尚未在"执行人员管理"给这个执行人员配置费率梯度。
     * true 时 breakdown 里带的是红字提示文案，前端应该标红展示。
     */
    private boolean noRateConfigured;

    /**
     * 2026-07-28 新增：这条记录本身已经设置过内部执行成本（重新打开"设置内部执行成本"弹窗
     * 编辑一条老记录）。true 时 suggestedAmount 是这条记录当前实际保存的金额（不是重新按梯度
     * 推算的建议值——梯度配置事后可能变过，重新推算的数字不一定等于当初实际保存的），
     * breakdown 文案也相应改成"目前已设置成 ¥X"而不是"建议金额"的措辞，rateBasedSuggestion
     * 这时候恒为 false，前端不应该再展示"这是自动算出的建议金额"那句提示。
     */
    private boolean alreadySet;
}
