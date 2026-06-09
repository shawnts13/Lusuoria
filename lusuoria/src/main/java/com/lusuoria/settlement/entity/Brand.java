package com.lusuoria.settlement.entity;

import javax.persistence.*;
import lombok.*;

@Entity
@Table(name = "brands")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Brand extends BaseEntity {

    @Column(name = "name", nullable = false, unique = true)
    private String name;                 // 品牌方名称，如 TEMU、Atoms

    @Column(name = "country_market")
    private String countryMarket;        // 国家/市场

    @Column(name = "cooperation_type")
    private String cooperationType;      // 合作类型

    @Column(name = "contact_person")
    private String contactPerson;        // 联系人

    @Column(name = "settlement_currency", length = 10)
    private String settlementCurrency;   // 结算币种 USD/RMB

    @Column(name = "payment_cycle")
    private String paymentCycle;         // 付款周期

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;                // 备注
}