package com.lusuoria.settlement.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import javax.persistence.*;
import java.util.Date;

/**
 * 所有实体的公共基类：自增主键 + 创建/更新时间自动填充 + 软删除标记。
 *
 * 【Lombok 知识点】@Getter/@Setter 是编译期自动生成代码的注解（Lombok 是一个"注解处理器"，
 * 编译时在 .class 里插入 getId()/setId()/getCreatedAt() 这些方法，源码里看不到但编译产物里有，
 * IDE 装了 Lombok 插件才能识别、否则会显示"找不到符号"），省掉手写一堆 getter/setter 样板代码；
 * 跟 Spring 本身没关系，是独立的一个库。
 *
 * 【JPA/Spring Data 知识点】
 * @MappedSuperclass：告诉 JPA 这个类本身不对应数据库里的一张表，它的字段（id/createdAt/...）
 * 要"拼进"每一个继承它的子类（比如 Employee、Brand）各自的表里，成为那张表的列——不是像
 * 普通 Java 继承那样共用一张父表，每个子类实体最终各自是独立、完整的一张表。
 * @EntityListeners(AuditingEntityListener.class) + 字段上的 @CreatedDate/@LastModifiedDate：
 * 配合启动类上的 @EnableJpaAuditing（见 MyApplication），每次这个实体被 save() 的时候，
 * Hibernate 会自动帮你把 createdAt（仅第一次insert）/updatedAt（每次insert或update）填成
 * 当前时间，不需要业务代码自己 new Date() 手动赋值——也正因为是"保存时才由 Hibernate 填充"，
 * 这两个字段读取的是应用当时的 JVM 默认时区（MyApplication 里强制设成了 Asia/Shanghai）。
 *
 * isDeleted 是本项目"软删除"的核心字段：任何一条记录理论上都不会被真的 DELETE 掉，"删除"操作
 * 实际是把这个字段改成 true，所有查询也都要记得带上 isDeleted=false 的过滤条件（各 Repository
 * 里那些 findByIsDeletedFalseXxx 方法名就是这么来的）——保留历史数据可追溯、也避免误删后
 * 无法恢复；代价是几乎每条自定义查询都要自己记得加这个条件，忘加是这类系统里最容易踩的坑之一。
 */
@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreatedDate
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Date createdAt;

    @LastModifiedDate
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "updated_at", nullable = false)
    private Date updatedAt;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted = false;
}
