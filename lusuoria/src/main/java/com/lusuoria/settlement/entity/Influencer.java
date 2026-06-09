package com.lusuoria.settlement.entity;

import com.lusuoria.settlement.enums.ProjectType;
import javax.persistence.*;
import lombok.*;

@Entity
@Table(name = "influencers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Influencer extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "influencer_type", nullable = false)
    private ProjectType influencerType;  // 海外红人 / 中国红人

    @Column(name = "team_name")
    private String teamName;             // 红人团队，如 游琳团队、田震团队

    @Column(name = "account_name", nullable = false)
    private String accountName;          // 红人账号

    @Column(name = "country_market")
    private String countryMarket;        // 国家/市场

    @Column(name = "platform")
    private String platform;             // 平台 (TikTok/Instagram/YouTube等)

    @Column(name = "cooperation_mode")
    private String cooperationMode;      // 合作模式

    @Column(name = "payment_info", columnDefinition = "TEXT")
    private String paymentInfo;          // 收款信息

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}