package com.lusuoria.settlement.entity;

import com.lusuoria.settlement.enums.InfluencerContactStatus;
import com.lusuoria.settlement.enums.ProjectType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import javax.persistence.*;

/**
 * 红人管理
 *
 * 多值字段用换行符分隔存储（TEXT）：
 *   domains    - 所属领域，如 "家居\n科技"
 *   links      - 主页链接
 *   casesLinks - 合作案例链接
 *
 * contacts 字段存 JSON 数组：
 *   [{"type":"phone","value":"xxx"},{"type":"whatsapp","value":"xxx"},...]
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
    private ProjectType influencerType;

    /** 红人团队（单个，外部机构/MCN 名称） */
    @Column(name = "team_name")
    private String teamName;

    @Column(name = "account_name", nullable = false, unique = true)
    private String accountName;             // 红人社媒完整名字（全局唯一）

    @Column(name = "country_market")
    private String countryMarket;           // 服务国家/市场

    @Column(name = "platform")
    private String platform;

    /** 所属领域（多个，换行符分隔，如 "家居\n科技"） */
    @Column(name = "domains", columnDefinition = "TEXT")
    private String domains;

    @Column(name = "follower_count")
    private Long followerCount;

    @Column(name = "links", columnDefinition = "TEXT")
    private String links;

    @Column(name = "cases_links", columnDefinition = "TEXT")
    private String casesLinks;

    /** 已签署合同链接（Google Drive 链接） */
    @Column(name = "contract_link")
    private String contractLink;

    // ===== 联系方式 =====
    @Column(name = "email")
    private String email;

    /**
     * 联系方式 JSON 数组
     * 格式：[{"type":"phone","value":"xxx"},{"type":"whatsapp","value":"xxx"}]
     * type 枚举：phone / whatsapp / line / telegram
     */
    @Column(name = "contacts", columnDefinition = "TEXT")
    private String contacts;

    // ===== 合作信息 =====
    @Enumerated(EnumType.STRING)
    @Column(name = "contact_status", length = 30)
    private InfluencerContactStatus contactStatus;

    @Column(name = "follower_person")
    private String followerPerson;

    // ===== 敏感字段（仅 ADMIN / AUDITOR）=====
    /** 红人视频制作与发布成本（美金），原"红人成本" */
    @Column(name = "influencer_cost", columnDefinition = "TEXT")
    private String influencerCost;

    /** 视频投流成本（美金） */
    @Column(name = "ad_spend_cost", columnDefinition = "TEXT")
    private String adSpendCost;

    /** 视频版权成本（美金） */
    @Column(name = "copyright_cost", columnDefinition = "TEXT")
    private String copyrightCost;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // ===== 非持久化字段：关联的"品牌方-团队"对，由 Controller 查询中间表后手动填充 =====
    /** 关联的"品牌方-团队"对列表（不持久化，仅用于 API 响应；一个红人可以有多个对，
     * 同一品牌方下也可能有多个不同团队，团队也可能为空） */
    @Transient
    private java.util.List<InfluencerBrandTeamView> brandTeamPairs;
}
