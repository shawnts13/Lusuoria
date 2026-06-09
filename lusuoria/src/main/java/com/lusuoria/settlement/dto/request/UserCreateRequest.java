package com.lusuoria.settlement.dto.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

@Data
public class UserCreateRequest {

    private Long id;  // 有id则更新，无则新建

    @NotBlank(message = "用户名不能为空")
    private String username;

    /** 新建时必填；更新时为空则不修改密码 */
    private String password;

    @NotBlank(message = "姓名不能为空")
    private String realName;

    /**
     * ADMIN / STAFF / AUDITOR
     */
    @NotBlank(message = "角色不能为空")
    @Pattern(regexp = "ADMIN|STAFF|AUDITOR", message = "角色必须是 ADMIN、STAFF 或 AUDITOR")
    private String role;

    private Boolean enabled;

    /** 可选：绑定到已有员工记录 */
    private Long employeeId;
}
