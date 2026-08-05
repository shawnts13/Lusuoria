package com.lusuoria.settlement.dto.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * "选择内部执行人员"弹窗用：根据执行人员的费率档位，算出这笔订单应该付多少钱，
 * 以及算出来的依据说明（展示给操作的人看，方便核对是不是算对了）。
 *
 * 2026-08 起内部执行成本完全由系统按费率梯度计算，这里给的不再是可以手动改的"建议值"——
 * noRateConfigured=true 或 outOfRange=true 时前端应该禁用保存按钮，不允许在没有算出金额的
 * 情况下把这条记录保存下来（唯一剩下的人工出口是 ADMIN 在"编辑"表单里手动改
 * internalExecutionCost，见 CollaborationTrackingService.doSave()）。
 */
@Data
public class ExecutorCostSuggestionResponse {
    /**
     * 系统算出的金额。noRateConfigured 或 outOfRange 为 true 时是 null，代表算不出来，
     * 不允许保存；cappedAtZero 为 true 时是 0（不是"算不出来"，是"配置齐全、只是月度预算
     * 花完了"，允许保存）。
     */
    private BigDecimal suggestedAmount;
    /** 算出这个金额的依据说明，比如"6月该执行人员处理AI新素材：¥70.00" */
    private String breakdown;
    /**
     * 这个金额是不是按 ExecutorPayRate 里维护的费率梯度算出来的。
     * true：该项目负责人已经在"执行人员管理"/"员工管理"给这个执行人员配置过费率梯度，
     *       走梯度算出金额，前端展示"这是系统自动算出的"这类说明文案
     * false：该项目负责人还没给这个执行人员配置费率梯度（见 noRateConfigured），算不出来
     */
    private boolean rateBasedSuggestion;

    /**
     * 该项目负责人尚未在"执行人员管理"给这个执行人员配置这个视频类型的费率梯度。
     * true 时 breakdown 里带的是红字提示文案，suggestedAmount 恒为 null，前端应该标红展示、
     * 禁用保存按钮——这条记录现在没法保存（见 CollaborationTrackingService.doSave() 里对应
     * 的硬性校验，Excel 导入同理）。
     */
    private boolean noRateConfigured;

    /**
     * 2026-08 新增：配置了费率梯度，但当前档位没有覆盖到"这个月第几条"这个数字（比如只配了
     * 1-50 条，这已经是第 51 条，又没有配一档"51 及以上"兜底）。suggestedAmount 恒为 null，
     * 前端应该禁用保存按钮，提示先去补充梯度配置。
     */
    private boolean outOfRange;

    /**
     * 2026-08 新增：命中的档位配置了"当月封顶金额"，且这个视频类型当月累计已经花完了这笔预算，
     * 这一笔算出来是 ¥0——这不是"算不出来"，是正常的业务结果，suggestedAmount=0，允许保存。
     * true 时 breakdown 里会带"已达到当月封顶金额 ¥XX"这句说明。
     */
    private boolean cappedAtZero;

    /**
     * 2026-07-28 新增：这条记录本身已经设置过内部执行成本（重新打开这个弹窗查看一条老记录）。
     * true 时 suggestedAmount 是这条记录当前实际保存的金额（不是重新按梯度推算的值——梯度
     * 配置事后可能变过，重新推算的数字不一定等于当初实际保存的），breakdown 文案也相应改成
     * "目前已设置成 ¥X"而不是"建议金额"的措辞，rateBasedSuggestion 这时候恒为 false。
     */
    private boolean alreadySet;
}
