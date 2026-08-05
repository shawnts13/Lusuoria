package com.lusuoria.settlement.dto.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.Date;

@Data
public class InfluencerTeamRequest {
    private Long id;

    @NotBlank(message = "团队名称不能为空")
    private String name;

    @NotNull(message = "团队必须归属一个品牌方")
    private Long brandId;

    /** 特殊：品牌方是"一年签一次合同"时，这个团队单独覆盖成"一次需求签一次合同" */
    private Boolean forcePerRequirementContract;

    /** 兜底默认合同到期日期（品牌方是"一年签一次合同"时才有意义） */
    private Date defaultContractEndDate;

    /**
     * 是否涉及"公对公发票"（2026-08 新增）。三态：不传/null=跟随品牌方默认，
     * 显式 true/false=覆盖品牌方默认（双向覆盖），见 InfluencerTeam.involvesCorporateInvoice()。
     */
    private Boolean involvesCorporateInvoice;
}
