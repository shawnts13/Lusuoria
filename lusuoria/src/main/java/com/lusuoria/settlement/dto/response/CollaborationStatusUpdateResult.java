package com.lusuoria.settlement.dto.response;

import com.lusuoria.settlement.entity.CollaborationTracking;
import lombok.Data;

/**
 * "状态流转"接口的返回结果。
 *
 * pendingApproval=false：状态已经直接生效，tracking 是更新后的最新记录。
 * pendingApproval=true：本次改动是"视频项目进度倒退"且需要管理员审核，
 * 没有立即生效，tracking 是改动前的原始记录（前端应提示"已提交审核，等待管理员同意"，
 * 不能误以为状态已经改成功）。
 */
@Data
public class CollaborationStatusUpdateResult {
    private CollaborationTracking tracking;
    private boolean pendingApproval;

    /**
     * true 表示这次状态流转触发了"设置内部执行成本"的条件——视频项目进度这次流转的目标
     * 明确是"已发布（未结算）"、且是从别的状态第一次流转进来，且这条记录还没确认过
     * "不涉及内部执行人员"，前端收到这个标记后应该紧接着弹出"设置内部执行成本"弹窗。
     */
    private boolean needExecutorCost;
}
