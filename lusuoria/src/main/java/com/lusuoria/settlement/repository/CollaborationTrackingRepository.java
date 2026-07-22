package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.enums.CollaborationProgress;
import com.lusuoria.settlement.enums.VideoType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Repository
public interface CollaborationTrackingRepository extends JpaRepository<CollaborationTracking, Long> {

    Optional<CollaborationTracking> findByIdAndIsDeletedFalse(Long id);

    /**
     * Excel 批量导入优化专用：一次性查出这批红人名下所有未删除的跟踪记录，
     * 在内存里做查重匹配，避免导入循环里每一行都单独查一次数据库。
     */
    List<CollaborationTracking> findByInfluencerIdInAndIsDeletedFalse(List<Long> influencerIds);

    /**
     * Excel 批量导入优化专用：一次性取出所有未删除记录的内部项目编号，
     * 在内存里做唯一性判断和序号分配，避免导入循环里每一行都单独查一次数据库。
     */
    @Query("SELECT c.internalProjectNo FROM CollaborationTracking c WHERE c.isDeleted = false")
    List<String> findAllInternalProjectNos();

    boolean existsByInternalProjectNo(String internalProjectNo);

    /** 采买旧视频原链接查重：归一化后的链接是否已被其他记录使用（编辑时排除自身） */
    @Query("SELECT c FROM CollaborationTracking c " +
           "WHERE c.isDeleted = false AND c.oldMaterialSourceLinkNormalized = :normalized " +
           "AND (:excludeId IS NULL OR c.id <> :excludeId)")
    List<CollaborationTracking> findByOldMaterialSourceLinkNormalized(
            @Param("normalized") String normalized, @Param("excludeId") Long excludeId);

    /** 内部项目编号分配用：统计某"品牌-月份-账号-"前缀已用了多少个（作为序号起点估算） */
    @Query("SELECT COUNT(c) FROM CollaborationTracking c " +
           "WHERE c.isDeleted = false AND c.internalProjectNo LIKE :prefixPattern")
    long countByInternalProjectNoPrefix(@Param("prefixPattern") String prefixPattern);

    /**
     * 去重判断：同一红人（按 id，不再按名字文本比较——红人改名不影响判重）
     * + 同一发布链接 + 同一发布日期 视为重复。
     * 仅当 publishLink 与 publishDate 均非空时才有意义（调用方负责判断）。
     * 排除自身 id（编辑时不和自己比）。
     */
    @Query("SELECT c FROM CollaborationTracking c " +
           "WHERE c.isDeleted = false " +
           "AND c.influencerId = :influencerId " +
           "AND c.publishLink = :publishLink " +
           "AND c.publishDate = :publishDate " +
           "AND (:excludeId IS NULL OR c.id <> :excludeId)")
    List<CollaborationTracking> findDuplicates(
            @Param("influencerId") Long influencerId,
            @Param("publishLink") String publishLink,
            @Param("publishDate") Date publishDate,
            @Param("excludeId") Long excludeId);

    /**
     * "项目视频月份"筛选：用 to_char 把 publishDate 转成 'YYYYMM' 字符串再比较，
     * 不用 Date 类型参数做区间比较。
     * 原因：Date 类型参数在这条动态筛选查询里（配合 Supabase 连接池）会触发
     * "could not determine data type of parameter"（SQLState 42P18），
     * 无论传不传值都会报错。改成字符串比较后，videoMonth 参数跟这条查询里
     * 其他正常工作的字符串筛选字段（accountName等）是完全一样的类型，
     * 不会再有参数类型歧义问题（跟 ProjectOrder.projectMonth 直接存字符串是同一个思路）。
     *
     * accountName 筛选走 c.influencer.accountName（通过关联的红人记录做模糊匹配，
     * 不再是本表自己的字段，改名后筛选结果始终反映红人当前的最新名字）。
     */
    @Query("SELECT c FROM CollaborationTracking c " +
           "WHERE c.isDeleted = false " +
           "AND (:brandId IS NULL OR c.brandId = :brandId) " +
           "AND (:teamId IS NULL OR c.teamId = :teamId) " +
           "AND (:countryMarket IS NULL OR c.countryMarket = :countryMarket) " +
           "AND (:accountName IS NULL OR c.influencer.accountName LIKE %:accountName%) " +
           "AND (:platform IS NULL OR c.platform LIKE %:platform%) " +
           "AND (:progress IS NULL OR c.progress = :progress) " +
           "AND (:videoType IS NULL OR c.videoType = :videoType) " +
           "AND (:videoMonth IS NULL OR FUNCTION('to_char', c.publishDate, 'YYYYMM') = :videoMonth) " +
           "AND (:internalProjectNo IS NULL OR c.internalProjectNo LIKE %:internalProjectNo%) " +
           "AND (:clientOrderId IS NULL OR c.clientOrderId LIKE %:clientOrderId%) " +
           "AND (:clientPaymentBatch IS NULL OR c.clientPaymentBatch LIKE %:clientPaymentBatch%) " +
           "AND (:projectManagerId IS NULL OR c.projectManagerId = :projectManagerId)")
    Page<CollaborationTracking> findByFilters(
            @Param("brandId") Long brandId,
            @Param("teamId") Long teamId,
            @Param("countryMarket") String countryMarket,
            @Param("accountName") String accountName,
            @Param("platform") String platform,
            @Param("progress") CollaborationProgress progress,
            @Param("videoType") VideoType videoType,
            @Param("videoMonth") String videoMonth,
            @Param("internalProjectNo") String internalProjectNo,
            @Param("clientOrderId") String clientOrderId,
            @Param("clientPaymentBatch") String clientPaymentBatch,
            @Param("projectManagerId") Long projectManagerId,
            Pageable pageable);

    /**
     * 数据看板用：取出全部未删除记录，月份归属（发布月份，若无则归创建月份）
     * 的精确判断在 Service 层用 Java 完成，避免不同数据库方言下日期函数写法
     * 不一致、以及"跨月范围+按月归属"组合逻辑在 SQL 里难以正确表达的问题。
     */
    List<CollaborationTracking> findByIsDeletedFalse();

    /** 红人合作次数统计用：按红人 id 分组统计未删除的跟踪记录数量 */
    @Query("SELECT c.influencerId, COUNT(c) FROM CollaborationTracking c " +
           "WHERE c.isDeleted = false " +
           "AND c.influencerId IN :influencerIds " +
           "GROUP BY c.influencerId")
    List<Object[]> countByInfluencerIds(@Param("influencerIds") List<Long> influencerIds);

    // ===== 以下方法 2026-07 从 ProjectOrderRepository 迁移过来（"项目订单"模块已废弃），
    // 月份口径统一改成按"发布时间"（原来 ProjectOrder 还有个"项目建立月份"，已废弃不再使用）=====

    /** 数据看板用：按"发布时间"所在月份查询 */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"influencer", "brand", "projectManager"})
    @Query("SELECT c FROM CollaborationTracking c WHERE c.isDeleted = false " +
           "AND FUNCTION('to_char', c.publishDate, 'YYYYMM') = :month")
    List<CollaborationTracking> findByPublishMonth(@Param("month") String month);

    /** 数据看板用：按"发布时间"所在月份范围（闭区间）查询 */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"influencer", "brand", "projectManager"})
    @Query("SELECT c FROM CollaborationTracking c WHERE c.isDeleted = false " +
           "AND FUNCTION('to_char', c.publishDate, 'YYYYMM') BETWEEN :startMonth AND :endMonth")
    List<CollaborationTracking> findByPublishMonthBetween(
            @Param("startMonth") String startMonth, @Param("endMonth") String endMonth);

    /**
     * 内部执行成本梯度分档计算专用：某执行人员在某"发布时间"月份下，
     * 已经赋值过内部执行成本的旧素材重发记录，且项目负责人必须是指定的这个人
     * （目前是"管理层"那唯一一个人，因为费率梯度是管理层跟执行人员之间的约定，
     * 不应该把这个执行人员给其他项目负责人干的活也算进这个梯度里）。
     * 按 id 升序排列（用 id 顺序近似代表实际处理顺序，用来判断第几笔、分档、
     * 以及当月101条以上部分的累计封顶金额）。
     */
    @Query("SELECT c FROM CollaborationTracking c WHERE c.isDeleted = false " +
           "AND c.executorId = :executorId AND c.projectManagerId = :managerId " +
           "AND c.videoType = com.lusuoria.settlement.enums.VideoType.OLD_MATERIAL_REPOST " +
           "AND c.internalExecutionCost IS NOT NULL " +
           "AND FUNCTION('to_char', c.publishDate, 'YYYYMM') = :month " +
           "ORDER BY c.id ASC")
    List<CollaborationTracking> findCostedOldMaterialOrdersForExecutor(
            @Param("executorId") Long executorId, @Param("managerId") Long managerId, @Param("month") String month);

    /**
     * 非管理层项目负责人场景下，弹窗里的提示信息用：这个执行人员在某"发布时间"月份下，
     * 已经为这个具体的项目负责人结算过的记录（已赋值内部执行成本），用来告诉这个项目负责人
     * "这个执行人员这个月已经帮你干了多少活"，本身不参与任何金额计算（这种情况下金额是
     * 项目负责人自己填的，系统不提供默认值），只是给个参考。
     */
    @Query("SELECT c FROM CollaborationTracking c WHERE c.isDeleted = false " +
           "AND c.executorId = :executorId AND c.projectManagerId = :managerId " +
           "AND c.internalExecutionCost IS NOT NULL " +
           "AND FUNCTION('to_char', c.publishDate, 'YYYYMM') = :month")
    List<CollaborationTracking> findCostedOrdersForExecutorAndManager(
            @Param("executorId") Long executorId, @Param("managerId") Long managerId, @Param("month") String month);

    // ===== 2026-07 红人结款模块重构新增 =====

    /**
     * 红人结款 - "选择涉及的红人视频项目"候选列表：某品牌下、属于给定团队集合（可能还包括
     * "不选团队"）、还没被纳入任何结款批次的记录（支持跨团队合并结款，2026-07 起）。
     * teamIds 为空列表时只按 includeNoTeam 决定要不要匹配"没有团队"的记录。
     */
    @Query("SELECT c FROM CollaborationTracking c WHERE c.isDeleted = false " +
           "AND c.brandId = :brandId " +
           "AND ((:includeNoTeam = true AND c.teamId IS NULL) OR c.teamId IN :teamIds) " +
           "AND c.influencerPaymentProgress IS NOT NULL " +
           "AND c.influencerPaymentProgress NOT IN (" +
           "  com.lusuoria.settlement.enums.InfluencerPaymentProgress.INCLUDED_IN_PAYMENT_BATCH, " +
           "  com.lusuoria.settlement.enums.InfluencerPaymentProgress.INCLUDED_IN_PAYMENT_BATCH_MISSING_INVOICE)")
    List<CollaborationTracking> findPaymentCandidatesByTeams(
            @Param("brandId") Long brandId,
            @Param("teamIds") List<Long> teamIds,
            @Param("includeNoTeam") boolean includeNoTeam);

    /** 红人结款 - 某条结款记录已纳入的红人合作跟踪明细 */
    List<CollaborationTracking> findByInfluencerPaymentIdAndIsDeletedFalse(Long influencerPaymentId);

    /** 红人结款 - 创建/编辑时校验勾选的 id 是否都合法可用（属于该品牌+团队、且未被其他批次占用） */
    List<CollaborationTracking> findByIdInAndIsDeletedFalse(List<Long> ids);

    // ===== 2026-07 红人需求管理对接新增 =====

    /**
     * 需求列表页"需求完成进度"批量计算：按 internalRequirementNo 分组统计视频项目进度属于
     * 已发布(未结算)/已加入客户未结算列表/客户已结算/折损 这四个状态的记录数，一次查出当前页
     * 所有需求的计数，避免逐条查库。
     */
    @Query("SELECT c.internalRequirementNo, COUNT(c) FROM CollaborationTracking c " +
           "WHERE c.isDeleted = false AND c.internalRequirementNo IN :requirementNos " +
           "AND c.progress IN (" +
           "  com.lusuoria.settlement.enums.CollaborationProgress.PUBLISHED_UNSETTLED, " +
           "  com.lusuoria.settlement.enums.CollaborationProgress.JOINED_CLIENT_UNSETTLED_LIST, " +
           "  com.lusuoria.settlement.enums.CollaborationProgress.SETTLED, " +
           "  com.lusuoria.settlement.enums.CollaborationProgress.DELAYED) " +
           "GROUP BY c.internalRequirementNo")
    List<Object[]> countCompletedByRequirementNos(@Param("requirementNos") List<String> requirementNos);

    /**
     * "关联红人需求"选择器第二步 / 需求完成进度点击详情：某个需求下所有已关联的红人合作跟踪记录
     * （不看 progress 状态，只要关联了就算，含折损）。
     */
    List<CollaborationTracking> findByInternalRequirementNoAndIsDeletedFalse(String internalRequirementNo);
}
