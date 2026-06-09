package com.lusuoria.settlement.dto.response;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class MonthlySummaryResponse {

    private String month;
    private int totalProjects;

    private BigDecimal totalClientRevenue;
    private BigDecimal totalRmbRevenue;
    private BigDecimal totalInfluencerCost;
    private BigDecimal totalOtherCost;
    private BigDecimal totalExecCost;
    private BigDecimal totalGrossProfit;
    private BigDecimal totalDistributableProfit;
    private BigDecimal totalCommissionAmount;
    private BigDecimal totalCompanyNetProfit;

    private List<ManagerCommissionItem> managerCommissions;

    @Data
    public static class ManagerCommissionItem {
        private Long managerId;
        private String managerName;
        private int projectCount;
        private BigDecimal totalCommission;
    }
}