package com.lusuoria.settlement.entity;

import com.lusuoria.settlement.enums.InfluencerContactStatus;
import com.lusuoria.settlement.enums.ProjectType;
import lombok.*;

import javax.persistence.*;

/**
 * 红人管理
 *
 * 多值字段用逗号分隔字符串存储（TEXT）：
 *   teamNames  - 红人团队，如 "游琳团队,田震团队"
 *   links      - 主页链接，如 "https://tiktok.com/xxx,https://ins.com/xxx"
 *   casesLinks - 合作案例链接，如 "https://..."
 */
@Entity
@Table(name = "influencers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Influencer extends BaseEntity {

    // ===== 基本信息 =====
    @Enumerated(EnumType.STRING)
    @Column(name = "influencer_type", nullable = false)
    private ProjectType influencerType;     // 海外红人 / 中国红人 / 境外红人（在华）

    /** 红人团队（多个，逗号分隔），外部机构/MCN 团队名称 */
    @Column(name = "team_names", columnDefinition = "TEXT")
    private String teamNames;

    @Column(name = "account_name", nullable = false)
    private String accountName;             // 红人ID（前端展示为"红人ID"）

    @Column(name = "country_market")
    private String countryMarket;           // 国家/市场（中文名，如"美国"、"中国"）

    @Column(name = "platform")
    private String platform;               // 平台 TikTok / Instagram / YouTube 等

    @Column(name = "domain")
    private String domain;                 // 领域：家居 / 科技

    @Column(name = "follower_count")
    private Long followerCount;            // 粉丝量

    /** 主页链接（多条，逗号分隔） */
    @Column(name = "links", columnDefinition = "TEXT")
    private String links;

    /** 合作案例链接（多条，逗号分隔） */
    @Column(name = "cases_links", columnDefinition = "TEXT")
    private String casesLinks;

    // ===== 合作信息 =====
    @Column(name = "email")
    private String email;                  // 红人邮箱

    @Enumerated(EnumType.STRING)
    @Column(name = "contact_status", length = 30)
    private InfluencerContactStatus contactStatus; // 建联情况，null 表示未设置

    @Column(name = "payment_cycle", length = 20)
    private String paymentCycle;           // 付款周期：7天 / 14天 / 30天

    @Column(name = "follower_person")
    private String followerPerson;         // 跟进人（员工真实姓名）

    // ===== 敏感字段（仅 ADMIN / AUDITOR）=====
    @Column(name = "influencer_cost", columnDefinition = "TEXT")
    private String influencerCost;         // 红人成本（美金），可能是金额或备注文字

    @Column(name = "client_price", columnDefinition = "TEXT")
    private String clientPrice;            // 客户合作价格（美金），可能是金额或备注文字

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
