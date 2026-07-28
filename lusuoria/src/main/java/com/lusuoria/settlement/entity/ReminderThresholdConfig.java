package com.lusuoria.settlement.entity;

import com.lusuoria.settlement.enums.ReminderCategory;
import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Table;

/**
 * 进度提醒阈值维护（2026-07-28 新增）：把 ProgressReminderService 里原本写死的各种"多少个
 * 工作日算滞留"/"提醒分几档、每档边界是多少天"这些参数抽出来变成可维护的配置行，管理层可以
 * 在"进度提醒阈值维护"模块里改，不用再改代码重新部署。
 *
 * 每一行是某个提醒类型（category）下的一个具名参数（paramKey），不是一张大而全的"提醒类型
 * 配置表"——不同类型需要的参数个数、含义都不一样（比如 PM_EXECUTOR_PROGRESS_STALL 有两个
 * 不同的滞留阈值，CONTRACT_EXPIRING_SOON 只有一个窗口天数），这种"每个类型的参数集合形状
 * 都不同"的场景用"类型+参数名"的行式设计比每个类型单独开字段更容易维护和扩展。
 *
 * 这批行是启动时按 {@link com.lusuoria.settlement.config.ReminderThresholdCache} 里的默认值
 * 表预置好的固定集合（管理层只能改 paramValue，不能新增/删除行），所以不需要 isDeleted 参与
 * 任何查询过滤逻辑（沿用 BaseEntity 只是保持跟其它实体一致的约定）。
 */
@Entity
@Table(name = "reminder_threshold_configs")
@Getter
@Setter
public class ReminderThresholdConfig extends BaseEntity {

    /** 这个参数属于哪个提醒类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private ReminderCategory category;

    /** 参数标识（程序内部用来读取这个值，比如 STALL_THRESHOLD_ORDERED），同一 category 下唯一 */
    @Column(name = "param_key", nullable = false)
    private String paramKey;

    /** 中文说明，前端表单的字段标签 */
    @Column(name = "param_label", nullable = false)
    private String paramLabel;

    /** 当前配置的值 */
    @Column(name = "param_value", nullable = false)
    private Integer paramValue;

    /** 单位（"工作日"/"天"），纯展示用 */
    @Column(name = "unit", nullable = false)
    private String unit;

    /** 同一 category 内的展示顺序 */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
