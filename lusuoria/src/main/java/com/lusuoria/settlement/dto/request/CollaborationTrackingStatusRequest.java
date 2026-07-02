package com.lusuoria.settlement.dto.request;

import com.lusuoria.settlement.enums.CollaborationProgress;
import lombok.Data;

/**
 * "状态流转"专用请求：只包含状态字段。
 * 配合前端的"状态流转"弹窗使用，弹窗里只展示这一个字段，从物理上避免误改其他字段。
 */
@Data
public class CollaborationTrackingStatusRequest {
    private CollaborationProgress progress;
}
