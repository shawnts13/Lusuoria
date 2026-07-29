package com.lusuoria.settlement.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import java.util.Date;

/**
 * 数据库每日全量备份失败提醒（2026-07-29 新增），展示在"待处理"模块，供 ADMIN/管理层查看+重试。
 *
 * 全表任何时刻最多只有一行未软删的记录——不是"每次失败都新增一行"，而是"当前是否处于失败状态"
 * 这一件事的快照：连续失败只更新同一行的 lastFailedAt/failureCount/errorMessage；只要有一次
 * 备份成功（不管是当天定时任务自己重试成功、还是有人在"待处理"点了"重试"按钮成功），就把这一行
 * 软删掉。见 DbBackupService.recordFailure/clearAlert。
 */
@Entity
@Table(name = "db_backup_alerts")
@Getter
@Setter
public class DbBackupAlert extends BaseEntity {

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * true = 这次失败是因为 Google Drive 授权失效/尚未授权（refresh_token 无效或不存在），
     * 前端据此展示"去账号管理重新连接 Google Drive"而不是单纯的"重试"按钮——单纯重试在
     * 授权没修好之前永远还是会失败，必须先引导去重新走一次授权。
     */
    @Column(name = "auth_expired", nullable = false)
    private Boolean authExpired = false;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "first_failed_at", nullable = false)
    private Date firstFailedAt;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "last_failed_at", nullable = false)
    private Date lastFailedAt;

    /** 连续失败的次数（每天定时任务失败+1，成功后这一行整体被软删，不存在"清零"这个操作） */
    @Column(name = "failure_count", nullable = false)
    private Integer failureCount = 1;
}
