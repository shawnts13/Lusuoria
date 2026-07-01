package com.lusuoria.settlement.dto.request;

import com.lusuoria.settlement.enums.ClientStatus;
import com.lusuoria.settlement.enums.InternalSettlementStatus;
import com.lusuoria.settlement.enums.ProjectType;
import com.lusuoria.settlement.enums.VideoType;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class ProjectOrderRequest {

    // 如有 id 则为更新，否则为新增
    private Long id;

    @NotBlank(message = "项目月份不能为空")
    private String projectMonth;         // 格式 202604

    @NotNull(message = "项目类型不能为空")
    private ProjectType projectType;

    @NotNull(message = "品牌方不能为空")
    private Long brandId;

    private Long influencerId;
    private Long projectManagerId;

    private String clientOrderNo;
    private String cooperationContent;
    private Boolean isOwnResource;

    /** 项目视频类型：实拍新视频 / AI新素材 / 旧素材重发（联动生成时取自红人合作跟踪） */
    private VideoType videoType;

    // 收入
    /** 客户合作价格（美金），参与利润计算 */
    private BigDecimal clientPrice;
    private BigDecimal exchangeRate;

    // 成本
    private BigDecimal influencerCost;
    private BigDecimal otherExternalCost;
    private BigDecimal internalExecutionCost;

    // 提成比例
    private BigDecimal commissionRate;

    // 甲方状态
    private ClientStatus clientStatus;
    private Boolean contractSigned;
    private Date expectedReceiptDate;
    private Date actualReceiptDate;
    private BigDecimal receivedAmount;

    // 内部状态
    private InternalSettlementStatus internalStatus;

    private String notes;
}