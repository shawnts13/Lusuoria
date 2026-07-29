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
 * 数据库每日备份上传到 Google Drive 用的授权凭证（2026-07-29 新增）。
 *
 * 走的是"个人 Google 账号 OAuth 授权"方案，不是 Service Account——目标 Drive 文件夹属于普通
 * 个人 @gmail.com 账号（非 Google Workspace 企业账号），Service Account 上传到个人账号共享的
 * 文件夹会有存储配额归属问题（Service Account 自己基本没有可用配额），所以改成让管理层用自己的
 * 真实 Google 账号走一次网页授权（GoogleDriveAuthController.authorizeUrl/callback），上传时用的
 * 是这个真实账号自己的配额，不存在配额问题。
 *
 * 全表任何时刻最多只有一行未软删的记录（每次重新授权会把旧的一行软删掉，插入新的一行）——
 * 不是"多账号"设计，就是单一一份"当前生效的 Google 账号授权"。
 */
@Entity
@Table(name = "google_drive_auth")
@Getter
@Setter
public class GoogleDriveAuth extends BaseEntity {

    /** Google OAuth refresh_token，用于每次备份时换取新的 access_token（access_token 本身不存库，
     * 有效期只有1小时，没有持久化的必要） */
    @Column(name = "refresh_token", columnDefinition = "TEXT", nullable = false)
    private String refreshToken;

    /** 走授权流程时登录的系统账号用户名（审计留痕，不是 Google 账号本身的用户名——
     * Google 那边具体是哪个邮箱，回调时 Google 不会返回邮箱，除非额外请求 userinfo scope，
     * 这里没有必要，只需要知道"系统里谁做的这次授权"） */
    @Column(name = "connected_by_username")
    private String connectedByUsername;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "connected_at", nullable = false)
    private Date connectedAt;
}
