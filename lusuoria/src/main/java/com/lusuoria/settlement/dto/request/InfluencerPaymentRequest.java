package com.lusuoria.settlement.dto.request;

import com.lusuoria.settlement.enums.InfluencerPaymentStatus;
import lombok.Data;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 红人结款 - 新建/编辑请求。
 * brandId/teamId/settlementMonth 创建后不可再改（编辑时会被忽略）。
 * cooperationQuantity/payableAmount/currency/rmbAmount 不接受前端传入，
 * 由服务端根据 collaborationTrackingIds 算出来，防止绕过校验直接篡改金额。
 */
@Data
public class InfluencerPaymentRequest {
    private Long id;

    @NotNull(message = "品牌方不能为空")
    private Long brandId;

    private Long teamId;

    @NotEmpty(message = "结算月份不能为空")
    private String settlementMonth;

    private Date reconcileDate;

    @NotEmpty(message = "请至少选择一条涉及的红人视频项目")
    private List<Long> collaborationTrackingIds;

    private BigDecimal exchangeRate;

    private Date expectedPaymentDate;

    /** 仅新建时有意义（初始状态），编辑时会被忽略——状态只能通过"状态流转"接口修改 */
    private InfluencerPaymentStatus paymentStatus;

    /** 仅新建时有意义，且仅当 paymentStatus=PAID 时才会被采用 */
    private Date actualPaymentDate;

    private String notes;
}
