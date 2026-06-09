package com.lusuoria.settlement.dto.response;

import lombok.Data;

import java.util.Date;

@Data
public class UserResponse {
    private Long id;
    private String username;
    private String displayName;   // 关联员工姓名，无关联则为用户名
    private String role;
    private String roleLabel;
    private Boolean enabled;
    private Long employeeId;
    private String employeeName;
    private Date createdAt;
    private Date updatedAt;
}
