package com.lusuoria.settlement.dto.response;

import com.lusuoria.settlement.entity.CollaborationTracking;
import lombok.Data;

/**
 * "设置内部执行成本"接口的返回结果（2026-07 新增"二次修改需审核"后需要区分两种情况）。
 *
 * pendingApproval=false：改动已经直接生效（首次设置，或该记录的项目负责人本人操作），
 * tracking 是更新后的最新记录。
 * pendingApproval=true：本次是"非首次修改"且操作人不是该记录的项目负责人本人，改动没有
 * 立即生效，已提交一条待审核事项给项目负责人，tracking 是改动前的原始记录（前端应提示
 * "已提交项目负责人审核，同意后生效"，不能误以为金额已经改成功）。
 */
@Data
public class ExecutorCostUpdateResult {
    private CollaborationTracking tracking;
    private boolean pendingApproval;
}
