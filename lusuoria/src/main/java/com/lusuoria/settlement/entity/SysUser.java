package com.lusuoria.settlement.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import javax.persistence.*;
import java.util.Date;

@Entity
@Table(name = "sys_users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SysUser extends BaseEntity {

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password", nullable = false)
    private String password;

    /**
     * 角色：
     *   ADMIN   - 老板/管理员：全部权限 + 老板审核 + 账号管理
     *   STAFF   - 普通员工：可写操作，看不到收入/利润/提成
     *   AUDITOR - 审计/会计：只读 + 导出，可看全部财务数据
     *   GUEST   - 访客：只读，看不到收入/利润/提成
     */
    @Column(name = "role", nullable = false, length = 20)
    private String role;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /**
     * 关联员工 ID（直接读列，不触发懒加载）
     * toResponse() 用这个字段查 EmployeeCache，零额外 SQL
     */
    @Column(name = "employee_id", insertable = false, updatable = false)
    private Long employeeId;

    /**
     * 关联员工记录（可选）
     * 关联后右上角显示名称将使用员工姓名，否则显示用户名
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    /**
     * 最后一次看到"进度提醒"登录弹窗的时间（2026-07 新增）。
     * 每天北京时间 12点/18点/22点这三个节点，只要这个时间戳早于"最近一个已过去的节点时刻"，
     * 下次登录/进入系统时就会再弹一次（见 ProgressReminderService.shouldShowPopup）。
     * 用户点弹窗上的按钮（跳转待处理/我知道了）后更新为当前时间。
     */
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "last_seen_reminder_popup_at")
    private Date lastSeenReminderPopupAt;
}
