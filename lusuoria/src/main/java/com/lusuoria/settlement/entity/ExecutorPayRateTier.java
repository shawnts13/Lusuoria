package com.lusuoria.settlement.entity;

import com.lusuoria.settlement.enums.VideoType;
import lombok.*;

import javax.persistence.*;
import java.math.BigDecimal;

/**
 * 执行人员薪资梯度 - 按 (项目负责人, 执行人员, 项目视频类型) 独立维护，每个类型是一份
 * "按当月累计条数分档"的梯度价（2026-07 起四个视频类型——实拍新视频/实拍新图片/AI新素材/
 * 旧素材重发——统一走这一套梯度结构，不再有"哪些类型是固定单价、哪些类型是分档"的区分）。
 *
 * "每条固定价"就是只维护一档：minCount=1，maxCount=null（不封顶）。
 * 一个类型可以维护任意档数：minCount/maxCount 表示这一档覆盖"这个月第几条到第几条"
 * （从1开始，maxCount 为空表示这一档往上不封顶，一个类型最多只应该有一档 maxCount 为空，
 * 且必须是排在最后的那一档——由 Controller 保存时校验）。
 *
 * monthlyCap（当月封顶金额）只对 maxCount 为空的那一档（即"最后一档、不封顶数量"）有意义——
 * 跟旧素材重发原有的业务规则一致：这一档累计挣到的钱超过这个金额后，超出部分不再计入。
 * 有上限（maxCount 非空）的档位不应该设置这个字段，由 Controller 保存时校验。
 *
 * "第几条"的计算口径：这个执行人员在这个发布月份下，这个具体项目负责人名下、这个具体视频类型
 * 已经赋值过内部执行成本的记录数量 + 1（没赋值的记录不算数），见
 * CollaborationTrackingService.suggestExecutorCost()。
 */
@Entity
@Table(name = "executor_pay_rate_tiers", indexes = {
        // 覆盖 findByManagerId(In)AndIsDeletedFalseOrderByMinCountAsc
        @Index(name = "idx_ept_manager_min_count", columnList = "manager_id, min_count"),
        // 覆盖 findByManagerIdAndExecutorIdAndVideoTypeAndIsDeletedFalseOrderByMinCountAsc
        @Index(name = "idx_ept_manager_executor_type_min", columnList = "manager_id, executor_id, video_type, min_count")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutorPayRateTier extends BaseEntity {

    /** 项目负责人（或管理层）的员工 id */
    @Column(name = "manager_id", nullable = false)
    private Long managerId;

    /** 执行人员的员工 id */
    @Column(name = "executor_id", nullable = false)
    private Long executorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "video_type", nullable = false)
    private VideoType videoType;

    /** 这一档覆盖的最低条数（从1开始） */
    @Column(name = "min_count", nullable = false)
    private Integer minCount;

    /** 这一档覆盖的最高条数，留空表示不封顶（一个视频类型最多一档、且必须是排在最后的那档） */
    @Column(name = "max_count")
    private Integer maxCount;

    /** 这一档单价（元/条） */
    @Column(name = "rate", precision = 10, scale = 2, nullable = false)
    private BigDecimal rate;

    /** 当月封顶金额（元/月），只有 maxCount 为空（不封顶数量）的那一档才允许设置 */
    @Column(name = "monthly_cap", precision = 10, scale = 2)
    private BigDecimal monthlyCap;
}
