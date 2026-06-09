package com.lusuoria.settlement.dto.request;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import java.math.BigDecimal;

@Data
public class EmployeeRequest {
    private Long id;
    @NotBlank(message = "员工姓名不能为空")
    private String name;
    private String role;
    private String email;
    private BigDecimal defaultCommissionRate;
    private String notes;
}