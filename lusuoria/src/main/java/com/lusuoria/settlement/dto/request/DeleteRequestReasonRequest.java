package com.lusuoria.settlement.dto.request;

import lombok.Data;
import javax.validation.constraints.NotBlank;

@Data
public class DeleteRequestReasonRequest {
    @NotBlank(message = "请填写删除原因")
    private String reason;
}
