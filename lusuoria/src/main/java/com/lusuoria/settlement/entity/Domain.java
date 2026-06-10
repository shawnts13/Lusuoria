package com.lusuoria.settlement.entity;

import lombok.*;

import javax.persistence.*;

/**
 * 红人领域表
 * 只维护领域名称，不与红人直接关联
 * 红人表里用逗号分隔字符串存储所属领域
 */
@Entity
@Table(name = "domains")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Domain extends BaseEntity {

    @Column(name = "name", nullable = false, unique = true, length = 50)
    private String name;
}
