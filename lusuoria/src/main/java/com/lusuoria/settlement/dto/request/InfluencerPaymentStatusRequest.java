package com.lusuoria.settlement.dto.request;

import com.lusuoria.settlement.enums.InfluencerPaymentStatus;
import lombok.Data;

import java.util.Date;

/**
 * "状态流转"专用请求：只包含打款状态 + 实际付款日。
 * 配合前端的"状态流转"弹窗使用，弹窗里只展示这两个字段，从物理上避免误改其他字段。
 * PENDING -> PAID 需要带 actualPaymentDate；PAID -> PENDING（倒退）由 Service 自动清空
 * actualPaymentDate，忽略这里传的值。
 */
@Data
public class InfluencerPaymentStatusRequest {
    private InfluencerPaymentStatus paymentStatus;
    private Date actualPaymentDate;
}
