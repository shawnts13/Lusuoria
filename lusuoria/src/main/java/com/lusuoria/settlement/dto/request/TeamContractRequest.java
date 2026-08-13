package com.lusuoria.settlement.dto.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.Date;

@Data
public class TeamContractRequest {
    @NotNull(message = "请选择品牌方")
    private Long brandId;

    /** 团队可为空（该品牌方下没有团队层的情况） */
    private Long teamId;

    @NotNull(message = "请选择合同生效日期")
    private Date startDate;

    @NotNull(message = "请选择合同失效日期")
    private Date endDate;

    @NotBlank(message = "请填写合同链接")
    private String contractLink;
}
