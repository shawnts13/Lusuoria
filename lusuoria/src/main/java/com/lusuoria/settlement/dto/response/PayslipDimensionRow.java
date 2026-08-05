package com.lusuoria.settlement.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 工资单明细弹窗里的一行维度数据。三种角色复用同一个结构，具体哪些字段有值取决于
 * PayslipDetailResponse.type：
 *  - PROJECT_MANAGER：brandName/teamName/videoCount/amount（客户合作价格）/amount2（红人成本，
 *    2026-08 新增）/profit（利润=客户合作价格-红人成本-其他外部成本，2026-08 新增，含汇总行+
 *    明细行；执行人员自己的工资单、以及本类型下面的 executorWageRows 都不涉及，恒为 null）
 *  - EXECUTOR：brandName/teamName/videoType/videoTypeLabel/videoCount/amount（薪酬金额），
 *    2026-07 新增 projectManagerName（这一行属于哪个项目负责人，每行都会标注）
 *  - MANAGEMENT：brandName/teamName/videoCount/amount（客户合作价格）/amount2（红人成本）
 *  - PROJECT_MANAGER.executorWageRows（2026-07 新增）：executorId/executorName/brandName/
 *    teamName/videoType/videoTypeLabel/videoCount/amount（薪酬金额，人民币）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayslipDimensionRow {
    private String brandName;
    private String teamName;
    private String videoType;
    private String videoTypeLabel;
    private Long videoCount;
    private BigDecimal amount;
    private BigDecimal amount2;
    /**
     * 利润（2026-08 新增，仅 PROJECT_MANAGER 类型的 rows 使用）= 客户合作价格 − 红人成本 −
     * 其他外部成本，即 DashboardStatsService.Computed.grossProfit——不进一步扣内部执行成本
     * （那部分是项目负责人自己发给执行人员的，不影响这里展示给项目负责人看的利润口径，
     * 也不影响提成计算：project负责人自己的订单 isManagementOrder() 恒为 false，可分配利润
     * 和这里的项目毛利本来就相等）。
     */
    private BigDecimal profit;
    /** 是否是汇总行（前端展示上加粗/置底） */
    private Boolean isSummaryRow;

    // ===== 2026-07 新增：执行人员/项目负责人之间的薪酬关系链 =====

    /** 执行人员本人视角新增的"所属项目负责人"维度，每一行明细都会标注 */
    private String projectManagerName;

    /** 项目负责人视角"执行人员薪酬明细"用，标注这一行属于哪个执行人员（executorId 供可靠匹配） */
    private Long executorId;
    private String executorName;

    /**
     * 是否是"分组小计"行（比如某个项目负责人/某个执行人员这一组的小计），区别于 isSummaryRow
     * 那个整体汇总行。展示顺序是：[分组A明细行...] [分组A小计] [分组B明细行...] [分组B小计] ...
     * [整体汇总行]。
     */
    private Boolean isGroupSubtotal;

    /**
     * 仅执行人员本人视角、按项目负责人分组的小计行（isGroupSubtotal=true）使用：这个项目负责人
     * 是否已经确认了执行人员工资（true=这一组金额来自冻结快照，false=还是实时预估）。
     * 执行人员自己不再有整体的确认状态，改成每个项目负责人分组各自标注。项目负责人视角的
     * "执行人员薪酬明细"不需要这个字段——同一个项目负责人下所有执行人员分组的确认状态永远
     * 一致，直接看 PayslipDetailResponse.executorWageConfirmed 这一个字段就够了。
     */
    private Boolean groupConfirmed;

    /**
     * 执行人员薪酬明细专属（2026-07 新增）：这一行对应的单价（元/条）。同一个
     * (品牌方,团队,视频类型) 组合如果当月实际单价不一致（说明这批视频横跨了梯度分档的边界），
     * 会按单价拆成多行分别展示，每行都是同一个单价，这样项目负责人才看得出"为什么这几条钱
     * 不一样多"，而不是一个笼统的合计数字。
     */
    private BigDecimal unitPrice;

    /**
     * "梯度小结"行（2026-07 新增，isTierSummaryRow=true）：某个视频类型如果配置了 2 档以上
     * 真正意义上的梯度（不是"每条固定价"那种单档），在这个视频类型的明细行之后插入一行，
     * 把配置的梯度区间 + 当月实际落在每一档的条数拼成一句话（说明文案存在 brandName 字段里，
     * 复用 buildSummaryRow 那种"整行只放一段说明"的展示方式，不需要另开字段）。
     */
    private Boolean isTierSummaryRow;
}
