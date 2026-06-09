package com.lusuoria.settlement.dto.response;

import lombok.Data;

import java.util.Date;

@Data
public class UserResponse {
    private Long id;
    private String username;
    private String realName;
    private String role;
    private String roleLabel;
    private Boolean enabled;
    private Long employeeId;
    private String employeeName;
    private Date createdAt;
    private Date updatedAt;
}
