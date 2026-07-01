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

    // 项目负责人 / 管理层
    private BigDecimal defaultCommissionRate;

    // 财务 / IT后勤：固定月薪（人民币）
    private BigDecimal fixedMonthlySalary;

    // 执行人员：按项目视频类型/件计算工资
    private BigDecimal rateRealShotNew;        // 实拍新视频（元/条）
    private BigDecimal rateAiNewMaterial;      // AI新素材（元/条）
    private BigDecimal rateOldMaterialTier1;   // 旧素材重发 第1-50条（元/条）
    private BigDecimal rateOldMaterialTier2;   // 旧素材重发 第51-100条（元/条）
    private BigDecimal rateOldMaterialTier3;   // 旧素材重发 第101条及以上（元/条）
    private BigDecimal oldMaterialMonthlyCap;  // 旧素材重发 第101条及以上部分，当月封顶金额（元/月）

    private String notes;
}