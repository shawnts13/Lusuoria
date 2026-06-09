package com.lusuoria.settlement.dto.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

@Data
public class UserCreateRequest {

    private Long id;

    @NotBlank(message = "用户名不能为空")
    private String username;

    /** 新建时必填；更新时为空则不修改密码 */
    private String password;

    /**
     * ADMIN / STAFF / AUDITOR / GUEST
     */
    @NotBlank(message = "角色不能为空")
    @Pattern(regexp = "ADMIN|STAFF|AUDITOR|GUEST", message = "角色必须是 ADMIN、STAFF、AUDITOR 或 GUEST")
    private String role;

    private Boolean enabled;

    private Long employeeId;
}
