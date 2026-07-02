package com.lusuoria.settlement.dto.request;

import com.lusuoria.settlement.enums.ClientStatus;
import com.lusuoria.settlement.enums.InternalSettlementStatus;
import lombok.Data;

/**
 * "状态流转"专用请求：只包含甲方状态 + 内部状态两个字段。
 * 配合前端的"状态流转"弹窗使用，弹窗里只展示这两个字段，从物理上避免误改其他字段。
 */
@Data
public class ProjectOrderStatusRequest {
    private ClientStatus clientStatus;
    private InternalSettlementStatus internalStatus;
}
