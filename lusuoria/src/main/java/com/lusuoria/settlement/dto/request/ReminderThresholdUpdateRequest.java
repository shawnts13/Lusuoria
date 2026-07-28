package com.lusuoria.settlement.dto.request;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Data
public class ReminderThresholdUpdateRequest {
    @NotNull(message = "数值不能为空")
    @Min(value = 0, message = "数值不能为负数")
    private Integer paramValue;
}
