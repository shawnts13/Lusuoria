package com.lusuoria.settlement.dto.request;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Data
public class EmployeeRequest {
    private Long id;
    @NotBlank(message = "员工姓名不能为空")
    private String name;
    private String role;
    private String email;
    private String contactPhone;
    private Date hireDate;
    /** 离职时间，留空表示在职 */
    private Date resignDate;

    // 项目负责人 / 管理层
    private BigDecimal defaultCommissionRate;

    /** 提成 bonus 阶梯判档币种："RMB"/"USD"，仅项目负责人/管理层维护 */
    private String bonusTierCurrency;

    /** 提成 bonus 阶梯列表，仅项目负责人/管理层维护 */
    private List<BonusTierItem> bonusTiers;

    // 财务 / IT后勤：固定月薪（人民币）
    private BigDecimal fixedMonthlySalary;

    private String notes;

    @Data
    public static class BonusTierItem {
        private BigDecimal minAmount;
        /** 最高金额，留空表示不封顶 */
        private BigDecimal maxAmount;
        /** bonus 比例，如 0.05 表示 5% */
        private BigDecimal bonusRate;
    }
}
