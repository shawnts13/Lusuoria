package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.CollaborationTracking;
import com.lusuoria.settlement.enums.CollaborationProgress;
import com.lusuoria.settlement.enums.InfluencerPaymentProgress;
import com.lusuoria.settlement.enums.VideoType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
     * 红人管理删除红人前的拦截校验专用（2026-08 新增）：只要这个红人名下还有一条未删除的
     * 跟踪记录（不分进度/状态，进行中还是已完结都算），就不允许删除该红人——否则这条跟踪
     * 记录的 influencerId 会变成指向一个已软删红人的悬空引用，后续查询按 isDeleted=false
     * 过滤时该红人名字就会从这条记录上"消失"。
     */
    long countByInfluencerIdAndIsDeletedFalse(Long influencerId);

    /**
     * Excel 批量导入优化专用：一次性查出这批红人名下所有未删除的跟踪记录，
     * 在内存里做查重匹配，避免导入循环里每一行都单独查一次数据库。
     */
    List<CollaborationTracking> findByInfluencerIdInAndIsDeletedFalse(List<Long> influencerIds);

    /**
     * Excel 批量导入优化专用：一次性取出所有内部项目编号，在内存里做唯一性判断和序号分配，
     * 避免导入循环里每一行都单独查一次数据库。
     *
     * 2026-08 修复：这里之前是 WHERE c.isDeleted = false，只取未删除记录的编号——但
     * internal_project_no 在数据库层面是永久唯一约束，不认软删除，也不会像
     * old_material_source_link_normalized 那样在删除时被清空腾出来复用（内部项目编号设计上
     * 就是永久占用、不重新分配的，见字段注释）。单条保存/批量新建走的 ProjectNoAllocator
     * 本来就查全表不限 isDeleted，唯独这条 Excel 批量导入专用的内存索引漏了这个条件，导致
     * "软删除一条跟踪记录后，重新导入同一份Excel（红人/品牌方/团队/月份都一样，会算出同一个
     * 候选编号）"会在内存查重这一步误判"没被占用"，真正 insert 时才在数据库层面撞唯一键
     * 报错（uk_..._internal_project_no）。去掉 isDeleted 过滤，取全表已用过的编号即可。
     */
    @Query("SELECT c.internalProjectNo FROM CollaborationTracking c")
    List<String> findAllInternalProjectNos();

    /** 故意不过滤 isDeleted：内部项目编号在数据库层面是全表唯一约束、不认软删除，判重必须连软删记录一起查 */
    boolean existsByInternalProjectNo(String internalProjectNo);

    /** 采买旧视频原链接查重：归一化后的链接是否已被其他记录使用（编辑时排除自身） */
    @Query("SELECT c FROM CollaborationTracking c " +
           "WHERE c.isDeleted = false AND c.oldMaterialSourceLinkNormalized = :normalized " +
           "AND (:excludeId IS NULL OR c.id <> :excludeId)")
    List<CollaborationTracking> findByOldMaterialSourceLinkNormalized(
            @Param("normalized") String normalized, @Param("excludeId") Long excludeId);

    /**
     * Excel 批量导入优化专用（2026-08 新增）：一次性取出全表所有已填写"采买旧视频的原链接"的
     * 记录，用于预建查重索引。这个字段全表唯一、不分红人，不能像 dedupIndex 那样只按这批
     * 文件涉及的红人查——那样会漏掉"占用链接的是文件外其他红人的记录"这种情况，导致
     * 应用层的友好报错形同虚设，最终插到数据库时才撞上唯一约束，报出一坨用户看不懂的
     * Hibernate 异常（历史 bug，2026-08 修复）。
     */
    List<CollaborationTracking> findByOldMaterialSourceLinkNormalizedIsNotNullAndIsDeletedFalse();

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
     *
     * 2026-07 新增按当前登录人角色"优先展示"：project负责人/执行人员登录时，自己是负责人/
     * 执行人员的记录排最前；财务登录时，视频项目进度是"已发布（未结算）"/"已加入客户未结算
     * 列表"（财务需要处理的两个阶段）的记录排最前。priorityEmployeeId/prioritizeFinance
     * 由 Controller 按当前登录账号的员工角色决定，两者互斥（一次只会有一个生效，另一个传
     * null/false）；都不适用的角色（管理层/ADMIN/AUDITOR/GUEST等）两个参数都传
     * null/false，这条 ORDER BY 对所有行算出来的优先级都一样，不影响原有排序。
     * "优先展示"这两级 CASE 排序不写死在这条 ORDER BY 里，改由 Controller 通过
     * JpaSort.unsafe(...) 拼进 Pageable 的 Sort（见 CollaborationTrackingController.list）。
     * 原因：Spring Data 2.7.x 的 QueryUtils.hasOrderByClause() 用一个基于括号计数的正则来判断
     * 查询里是否已经有一条"顶层" ORDER BY（用来排除窗口函数/子查询里的 order by）；这条 WHERE
     * 子句本身有大量 (...) 分组，加上 ORDER BY 里的 CASE WHEN (...) ... END 后面又跟着括号，
     * 会让这个正则误判成"没有顶层 order by"，于是 Spring 会在这条 ORDER BY 后面再盲目拼一个
     * "order by c.id desc"（Pageable 的排序），导致同一条 JPQL 里出现两个 ORDER BY 关键字，
     * Hibernate 解析直接报 QuerySyntaxException: unexpected token: order（所有角色都会触发，
     * 因为问题在查询文本结构本身，跟参数值/角色无关）。
     * 这条 @Query 现在不写 ORDER BY，避免这个检测逻辑被触发；排序完全交给 Pageable 的 Sort。
     *
     * onlyMyResponsibility：前端"查看由我负责的记录"按钮用，把"软优先排序"变成硬筛选——
     * 项目负责人/执行人员只看自己是负责人/执行人员的记录，财务只看需要处理的两个阶段。
     * 为 false/null 时完全不影响原有筛选结果（向后兼容，老的调用方不用管这个参数）。
     * 命中筛选后，项目负责人/执行人员视角额外按"是否还没到客户已结算/折损这两个不需要
     * 他们再跟进的终态"做二级排序，还需要跟进的排前面（这条二级 CASE 同样由 Controller 拼进 Sort）。
     *
     * onlyIncomplete：前端"查看未完成的记录"按钮用，硬筛选出视频项目进度不是"客户已结算"
     * 也不是"折损"的记录（这两个是终态，不需要再跟进）。为 false/null 时完全不影响原有筛选结果。
     *
     * onlyUnpublished（2026-08 新增）：前端"查看视频未发布的记录"按钮用，硬筛选出"视频发布
     * 链接"还是空的记录，但排除"折损"——折损是终态，代表这条记录已经作废，不算"还没发布"，
     * 不需要再跟进。为 false/null 时完全不影响原有筛选结果，可以跟 onlyIncomplete/
     * onlyMyResponsibility 同时生效（互不影响，纯 AND 叠加）。
     *
     * influencerId（2026-07 新增）：红人管理模块"合作中项目/已完结项目"下钻的"查看全部"
     * 精确跳转用——accountName 是模糊匹配（LIKE），账号名互为子串的两个红人会串号，
     * influencerId 是精确匹配，不受文本子串影响。两者可以同时传（一般只会传一个）。
     *
     * onlyMissingRequirementNo（2026-08 新增）：前端"查看未绑定需求编号的记录"按钮用，
     * 硬筛选出"内部需求编号"（internalRequirementNo）为空的记录——这类记录没有关联到
     * "红人需求管理"的任何需求，方便排查/补关联。为 false/null 时完全不影响原有筛选结果，
     * 可以跟 onlyIncomplete/onlyUnpublished/onlyMyResponsibility 同时生效（互不影响，
     * 纯 AND 叠加）。
     *
     * progress/influencerPaymentProgress/videoType/projectManagerId 这4个筛选（2026-08-21
     * 起支持多选，类型从单值改成 List）：JPQL 里不是写成常见的 "(:progress IS NULL OR
     * c.progress IN :progress)"，而是额外带一个 progressActive 布尔参数、写成
     * "(:progressActive = false OR c.progress IN :progress)"——这不是随手换的写法，是刻意
     * 规避 Hibernate 5.x 经典 HQL 解析器的一个已知 bug：同一个具名参数既做 IS NULL 判断、
     * 又做集合类型的 IN 判断，会被解析成非法的 "vector" AST 节点，线上直接报
     * QuerySyntaxException（跟这个类下面 findLitePriorityProjectionByFilters 之前在
     * JpaSort.unsafe 链式排序上踩的是同一类"本地没有数据库环境验证不了、只能线上报错后
     * 回退"的 Hibernate 解析器脆弱问题）。调用方必须用 CollaborationFilterUtil.isActive()/
     * orPlaceholder() 计算这4组 (xxxActive, xxx) 参数，不能直接把可能为 null 的 List 传进来，
     * 见 CollaborationTrackingController.list()/exportExcel()、
     * CollaborationTrackingService.findAllMatchingFilters() 的用法。
     */
    @Query("SELECT c FROM CollaborationTracking c " +
           "WHERE c.isDeleted = false " +
           "AND (:brandId IS NULL OR c.brandId = :brandId) " +
           "AND (:teamId IS NULL OR c.teamId = :teamId) " +
           "AND (:countryMarket IS NULL OR c.countryMarket = :countryMarket) " +
           "AND (:accountName IS NULL OR c.influencer.accountName LIKE %:accountName%) " +
           "AND (:influencerId IS NULL OR c.influencerId = :influencerId) " +
           "AND (:platform IS NULL OR c.platform LIKE %:platform%) " +
           "AND (:progressActive = false OR c.progress IN :progress) " +
           "AND (:influencerPaymentProgressActive = false OR c.influencerPaymentProgress IN :influencerPaymentProgress) " +
           "AND (:videoTypeActive = false OR c.videoType IN :videoType) " +
           "AND (:videoMonth IS NULL OR FUNCTION('to_char', c.publishDate, 'YYYYMM') = :videoMonth) " +
           "AND (:videoDateStart IS NULL OR FUNCTION('to_char', c.publishDate, 'YYYY-MM-DD') >= :videoDateStart) " +
           "AND (:videoDateEnd IS NULL OR FUNCTION('to_char', c.publishDate, 'YYYY-MM-DD') <= :videoDateEnd) " +
           "AND (:internalProjectNo IS NULL OR c.internalProjectNo LIKE %:internalProjectNo%) " +
           "AND (:internalRequirementNo IS NULL OR c.internalRequirementNo LIKE %:internalRequirementNo%) " +
           "AND (:clientOrderId IS NULL OR c.clientOrderId LIKE %:clientOrderId%) " +
           "AND (:clientPaymentBatch IS NULL OR c.clientPaymentBatch LIKE %:clientPaymentBatch%) " +
           "AND (:projectManagerIdActive = false OR c.projectManagerId IN :projectManagerId) " +
           "AND (:onlyMyResponsibility = false " +
           "     OR (:priorityEmployeeId IS NOT NULL AND (c.projectManagerId = :priorityEmployeeId OR c.executorId = :priorityEmployeeId)) " +
           "     OR (:prioritizeFinance = true AND c.progress IN (" +
           "           com.lusuoria.settlement.enums.CollaborationProgress.PUBLISHED_UNSETTLED, " +
           "           com.lusuoria.settlement.enums.CollaborationProgress.JOINED_CLIENT_UNSETTLED_LIST, " +
           "           com.lusuoria.settlement.enums.CollaborationProgress.SETTLED))) " +
           "AND (:onlyIncomplete = false OR c.progress IS NULL OR c.progress NOT IN (" +
           "     com.lusuoria.settlement.enums.CollaborationProgress.PAYMENT_RECEIVED, " +
           "     com.lusuoria.settlement.enums.CollaborationProgress.DELAYED)) " +
           "AND (:onlyUnpublished = false OR ((c.publishLink IS NULL OR c.publishLink = '') " +
           "     AND (c.progress IS NULL OR c.progress <> com.lusuoria.settlement.enums.CollaborationProgress.DELAYED))) " +
           "AND (:onlyMissingRequirementNo = false OR c.internalRequirementNo IS NULL)")
    Page<CollaborationTracking> findByFilters(
            @Param("brandId") Long brandId,
            @Param("teamId") Long teamId,
            @Param("countryMarket") String countryMarket,
            @Param("accountName") String accountName,
            @Param("influencerId") Long influencerId,
            @Param("platform") String platform,
            @Param("progressActive") boolean progressActive,
            @Param("progress") List<CollaborationProgress> progress,
            @Param("influencerPaymentProgressActive") boolean influencerPaymentProgressActive,
            @Param("influencerPaymentProgress") List<InfluencerPaymentProgress> influencerPaymentProgress,
            @Param("videoTypeActive") boolean videoTypeActive,
            @Param("videoType") List<VideoType> videoType,
            @Param("videoMonth") String videoMonth,
            @Param("videoDateStart") String videoDateStart,
            @Param("videoDateEnd") String videoDateEnd,
            @Param("internalProjectNo") String internalProjectNo,
            @Param("internalRequirementNo") String internalRequirementNo,
            @Param("clientOrderId") String clientOrderId,
            @Param("clientPaymentBatch") String clientPaymentBatch,
            @Param("projectManagerIdActive") boolean projectManagerIdActive,
            @Param("projectManagerId") List<Long> projectManagerId,
            @Param("priorityEmployeeId") Long priorityEmployeeId,
            @Param("prioritizeFinance") Boolean prioritizeFinance,
            @Param("onlyMyResponsibility") Boolean onlyMyResponsibility,
            @Param("onlyIncomplete") Boolean onlyIncomplete,
            @Param("onlyUnpublished") Boolean onlyUnpublished,
            @Param("onlyMissingRequirementNo") Boolean onlyMissingRequirementNo,
            Pageable pageable);

    /**
     * 跟 findByFilters 完全同一套筛选条件（WHERE 子句逐字保持一致，改了记得两边一起改），
     * 只取"默认排序（未完成优先/未加入结款批次优先）"需要的3个轻量字段（id/progress/
     * influencerPaymentId），不加载完整实体。2026-07 新增，配合 CollaborationTrackingController
     * 里的 Java 内存分桶重排——原本想跟 priorityEmployeeId 那两级一样直接拼两个
     * JpaSort.unsafe CASE WHEN 塞进 ORDER BY，上线后在这个 Hibernate 版本下触发了
     * QuerySyntaxException（把其中一个 CASE WHEN 表达式误当成安全属性路径，拼出
     * "c.CASE WHEN ..." 这种非法 HQL——本地没有数据库环境，没法在改动前先验证这类多级
     * unsafe 链式调用的实际行为，只能线上出问题后再回退），改成在 Java 里做，规避这个坑，
     * 也避免继续往那条已经很脆弱的 ORDER BY 链上叠加更多 unsafe CASE WHEN。
     * sort 参数传跟 findByFilters 同一份 Pageable 的 Sort（保留 priorityEmployeeId 那两级
     * 已经在正常工作的排序 + 用户选的列排序），这里只按这个顺序取 id 列表，再在 Java 里
     * 按"是否未完成"/"是否未加入结款批次"做稳定分桶（组内相对顺序不变）。
     */
    @Query("SELECT c.id, c.progress, c.influencerPaymentId FROM CollaborationTracking c " +
           "WHERE c.isDeleted = false " +
           "AND (:brandId IS NULL OR c.brandId = :brandId) " +
           "AND (:teamId IS NULL OR c.teamId = :teamId) " +
           "AND (:countryMarket IS NULL OR c.countryMarket = :countryMarket) " +
           "AND (:accountName IS NULL OR c.influencer.accountName LIKE %:accountName%) " +
           "AND (:influencerId IS NULL OR c.influencerId = :influencerId) " +
           "AND (:platform IS NULL OR c.platform LIKE %:platform%) " +
           "AND (:progressActive = false OR c.progress IN :progress) " +
           "AND (:influencerPaymentProgressActive = false OR c.influencerPaymentProgress IN :influencerPaymentProgress) " +
           "AND (:videoTypeActive = false OR c.videoType IN :videoType) " +
           "AND (:videoMonth IS NULL OR FUNCTION('to_char', c.publishDate, 'YYYYMM') = :videoMonth) " +
           "AND (:videoDateStart IS NULL OR FUNCTION('to_char', c.publishDate, 'YYYY-MM-DD') >= :videoDateStart) " +
           "AND (:videoDateEnd IS NULL OR FUNCTION('to_char', c.publishDate, 'YYYY-MM-DD') <= :videoDateEnd) " +
           "AND (:internalProjectNo IS NULL OR c.internalProjectNo LIKE %:internalProjectNo%) " +
           "AND (:internalRequirementNo IS NULL OR c.internalRequirementNo LIKE %:internalRequirementNo%) " +
           "AND (:clientOrderId IS NULL OR c.clientOrderId LIKE %:clientOrderId%) " +
           "AND (:clientPaymentBatch IS NULL OR c.clientPaymentBatch LIKE %:clientPaymentBatch%) " +
           "AND (:projectManagerIdActive = false OR c.projectManagerId IN :projectManagerId) " +
           "AND (:onlyMyResponsibility = false " +
           "     OR (:priorityEmployeeId IS NOT NULL AND (c.projectManagerId = :priorityEmployeeId OR c.executorId = :priorityEmployeeId)) " +
           "     OR (:prioritizeFinance = true AND c.progress IN (" +
           "           com.lusuoria.settlement.enums.CollaborationProgress.PUBLISHED_UNSETTLED, " +
           "           com.lusuoria.settlement.enums.CollaborationProgress.JOINED_CLIENT_UNSETTLED_LIST, " +
           "           com.lusuoria.settlement.enums.CollaborationProgress.SETTLED))) " +
           "AND (:onlyIncomplete = false OR c.progress IS NULL OR c.progress NOT IN (" +
           "     com.lusuoria.settlement.enums.CollaborationProgress.PAYMENT_RECEIVED, " +
           "     com.lusuoria.settlement.enums.CollaborationProgress.DELAYED)) " +
           "AND (:onlyUnpublished = false OR ((c.publishLink IS NULL OR c.publishLink = '') " +
           "     AND (c.progress IS NULL OR c.progress <> com.lusuoria.settlement.enums.CollaborationProgress.DELAYED))) " +
           "AND (:onlyMissingRequirementNo = false OR c.internalRequirementNo IS NULL)")
    List<Object[]> findLitePriorityProjectionByFilters(
            @Param("brandId") Long brandId,
            @Param("teamId") Long teamId,
            @Param("countryMarket") String countryMarket,
            @Param("accountName") String accountName,
            @Param("influencerId") Long influencerId,
            @Param("platform") String platform,
            @Param("progressActive") boolean progressActive,
            @Param("progress") List<CollaborationProgress> progress,
            @Param("influencerPaymentProgressActive") boolean influencerPaymentProgressActive,
            @Param("influencerPaymentProgress") List<InfluencerPaymentProgress> influencerPaymentProgress,
            @Param("videoTypeActive") boolean videoTypeActive,
            @Param("videoType") List<VideoType> videoType,
            @Param("videoMonth") String videoMonth,
            @Param("videoDateStart") String videoDateStart,
            @Param("videoDateEnd") String videoDateEnd,
            @Param("internalProjectNo") String internalProjectNo,
            @Param("internalRequirementNo") String internalRequirementNo,
            @Param("clientOrderId") String clientOrderId,
            @Param("clientPaymentBatch") String clientPaymentBatch,
            @Param("projectManagerIdActive") boolean projectManagerIdActive,
            @Param("projectManagerId") List<Long> projectManagerId,
            @Param("priorityEmployeeId") Long priorityEmployeeId,
            @Param("prioritizeFinance") Boolean prioritizeFinance,
            @Param("onlyMyResponsibility") Boolean onlyMyResponsibility,
            @Param("onlyIncomplete") Boolean onlyIncomplete,
            @Param("onlyUnpublished") Boolean onlyUnpublished,
            @Param("onlyMissingRequirementNo") Boolean onlyMissingRequirementNo,
            Sort sort);


    /**
     * 数据看板用：取出全部未删除记录，月份归属（发布月份，若无则归创建月份）
     * 的精确判断在 Service 层用 Java 完成，避免不同数据库方言下日期函数写法
     * 不一致、以及"跨月范围+按月归属"组合逻辑在 SQL 里难以正确表达的问题。
     */
    List<CollaborationTracking> findByIsDeletedFalse();

    /**
     * 红人管理"合作中项目"（进度不是"已收到客户回款"也不是"折损"）+ "已完结项目"（进度="已收到
     * 客户回款"）各自数量，按红人 id 分组。2026-07 起合并成一条 SQL（之前是两条独立的 COUNT
     * 查询，各自扫一遍 CollaborationTracking 表），用 SUM(CASE WHEN ... THEN 1 ELSE 0 END)
     * 一次算出两个口径的计数，减少一半的数据库往返——这个查询会被红人管理列表页的批量计数、
     * 以及默认排序（"合作中项目有值的排最前"）复用，后者是每次翻页/筛选都会跑一次的高频路径，
     * 值得省这一趟。返回：[influencerId, activeCount, completedCount]，每个数字类型统一按
     * Number 处理再转 long——不同 Hibernate 版本/方言对 SUM(CASE WHEN...) 这种聚合表达式
     * 返回的具体包装类型（Long/BigInteger）不完全一致，直接强转 Long 有把握不住的风险。
     *
     * 2026-08-21："已完结"的判定标准从"客户已结算"（SETTLED）改成"已收到客户回款"
     * （PAYMENT_RECEIVED）——SETTLED 不再是终态，改成代表"客户结算完成、钱还没真正到账"，
     * 处于 SETTLED 的记录现在算进"合作中项目"（Shawn 明确要求：口径统一改认 PAYMENT_RECEIVED
     * 才是"已完成"，SETTLED 不再算，其他把 SETTLED 当终态判断的地方同理，见
     * CollaborationProgress 类注释）。
     */
    @Query("SELECT c.influencerId, " +
           "SUM(CASE WHEN c.progress IS NULL OR c.progress NOT IN (" +
           "  com.lusuoria.settlement.enums.CollaborationProgress.PAYMENT_RECEIVED, " +
           "  com.lusuoria.settlement.enums.CollaborationProgress.DELAYED) THEN 1L ELSE 0L END), " +
           "SUM(CASE WHEN c.progress = com.lusuoria.settlement.enums.CollaborationProgress.PAYMENT_RECEIVED THEN 1L ELSE 0L END) " +
           "FROM CollaborationTracking c " +
           "WHERE c.isDeleted = false AND c.influencerId IN :influencerIds " +
           "GROUP BY c.influencerId")
    List<Object[]> countActiveAndCompletedByInfluencerIds(@Param("influencerIds") List<Long> influencerIds);

    /**
     * 红人管理"合作中项目/已完结项目"下钻弹窗用：某个红人 + 类别（completed=true 只看"已收到
     * 客户回款"，completed=false 看"不是已收到客户回款也不是折损"的进行中记录）分页查询。
     * EntityGraph 预先带上展示需要的关联，避免弹窗表格逐行触发懒加载 N+1。
     *
     * 2026-07 新增几个可选筛选（都是"传了就筛，不传不影响"，可以同时生效）：brandId/teamId/
     * platform/videoType/projectManagerId 两个类别通用；progress 主要给"合作中项目"用
     * （"已完结项目"本身已经锁定 progress=已收到客户回款，这个筛选传了也不会报错，只是没什么
     * 实际意义，前端只在"合作中项目"弹窗展示这一项）；videoMonth（发布月份）主要给"已完结
     * 项目"用。videoMonth 用 to_char 转字符串比较，原因跟 CollaborationTrackingRepository.
     * findByFilters 的 videoMonth 注释一致（Date 类型参数在这类动态筛选查询里在 Supabase 连接池
     * 下会报"could not determine data type of parameter"）。influencerPaymentProgress 这个筛选
     * 2026-07 加上后很快又被 Shawn 要求去掉（"合作中项目"弹窗不需要），已整个删除，不要再加回来。
     * 2026-08-21："已完结"判定口径同上，从 SETTLED 改成 PAYMENT_RECEIVED。
     */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"brand", "team", "influencer", "projectManager", "executor"})
    @Query("SELECT c FROM CollaborationTracking c WHERE c.isDeleted = false AND c.influencerId = :influencerId AND (" +
           "  (:completed = true AND c.progress = com.lusuoria.settlement.enums.CollaborationProgress.PAYMENT_RECEIVED) " +
           "  OR (:completed = false AND (c.progress IS NULL OR c.progress NOT IN (" +
           "        com.lusuoria.settlement.enums.CollaborationProgress.PAYMENT_RECEIVED, " +
           "        com.lusuoria.settlement.enums.CollaborationProgress.DELAYED)))) " +
           "AND (:brandId IS NULL OR c.brandId = :brandId) " +
           "AND (:teamId IS NULL OR c.teamId = :teamId) " +
           "AND (:platform IS NULL OR c.platform LIKE %:platform%) " +
           "AND (:videoType IS NULL OR c.videoType = :videoType) " +
           "AND (:progress IS NULL OR c.progress = :progress) " +
           "AND (:projectManagerId IS NULL OR c.projectManagerId = :projectManagerId) " +
           "AND (:videoMonth IS NULL OR FUNCTION('to_char', c.publishDate, 'YYYYMM') = :videoMonth)")
    Page<CollaborationTracking> findByInfluencerAndCompletionStatus(
            @Param("influencerId") Long influencerId, @Param("completed") boolean completed,
            @Param("brandId") Long brandId, @Param("teamId") Long teamId,
            @Param("platform") String platform, @Param("videoType") VideoType videoType,
            @Param("progress") CollaborationProgress progress,
            @Param("projectManagerId") Long projectManagerId, @Param("videoMonth") String videoMonth,
            Pageable pageable);

    /**
     * 同上筛选条件（含2026-07新增的这几个可选筛选），取"红人视频制作与发布成本"+"客户合作
     * 价格"合计（供弹窗汇总行用，汇总要覆盖全部命中记录，不能只按当前这一页现算）。
     *
     * 返回类型必须是 List<Object[]>，不能直接声明成 Object[]：这是之前踩过的坑——
     * Spring Data JPA 对着一条"多列 SELECT、无 GROUP BY"的聚合查询，返回值实际上是
     * "一行结果的 List"，里面每个元素才是那一整行的 Object[] 元组；如果方法签名直接写
     * Object[]，实际拿到的对象在运行时是 List（或被再包一层 Object[]），
     * 强转 (Long) 到内层字段时会报 "[Ljava.lang.Object; cannot be cast to java.lang.Long"
     * ——跟本仓库其它同类聚合查询（countActiveAndCompletedByInfluencerIds 等）保持一致写法。
     */
    @Query("SELECT COUNT(c), COALESCE(SUM(c.influencerCost), 0), COALESCE(SUM(c.clientPrice), 0) " +
           "FROM CollaborationTracking c WHERE c.isDeleted = false AND c.influencerId = :influencerId AND (" +
           "  (:completed = true AND c.progress = com.lusuoria.settlement.enums.CollaborationProgress.PAYMENT_RECEIVED) " +
           "  OR (:completed = false AND (c.progress IS NULL OR c.progress NOT IN (" +
           "        com.lusuoria.settlement.enums.CollaborationProgress.PAYMENT_RECEIVED, " +
           "        com.lusuoria.settlement.enums.CollaborationProgress.DELAYED)))) " +
           "AND (:brandId IS NULL OR c.brandId = :brandId) " +
           "AND (:teamId IS NULL OR c.teamId = :teamId) " +
           "AND (:platform IS NULL OR c.platform LIKE %:platform%) " +
           "AND (:videoType IS NULL OR c.videoType = :videoType) " +
           "AND (:progress IS NULL OR c.progress = :progress) " +
           "AND (:projectManagerId IS NULL OR c.projectManagerId = :projectManagerId) " +
           "AND (:videoMonth IS NULL OR FUNCTION('to_char', c.publishDate, 'YYYYMM') = :videoMonth)")
    List<Object[]> sumByInfluencerAndCompletionStatus(
            @Param("influencerId") Long influencerId, @Param("completed") boolean completed,
            @Param("brandId") Long brandId, @Param("teamId") Long teamId,
            @Param("platform") String platform, @Param("videoType") VideoType videoType,
            @Param("progress") CollaborationProgress progress,
            @Param("projectManagerId") Long projectManagerId, @Param("videoMonth") String videoMonth);

    // ===== 以下方法 2026-07 从 ProjectOrderRepository 迁移过来（"项目订单"模块已废弃），
    // 月份口径统一改成按"发布时间"（原来 ProjectOrder 还有个"项目建立月份"，已废弃不再使用）=====

    /**
     * 数据看板/工资单用：按"发布时间"所在月份查询。entity graph 里的 team 是 2026-07 补上的——
     * 工资单模块（PayslipService）按品牌方/团队分组是每次都会走的主路径（不像看板的"按团队"
     * 下钻只是偶尔点开的一个维度），漏了 team 的话每条记录访问 o.getTeam() 都会各自触发一次
     * 懒加载查询，记录一多就是隐蔽的 N+1（这一种是 JPA 层面的，跟按员工循环查库那种不一样，
     * 但同样会明显拖慢整页）。
     */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"influencer", "brand", "projectManager", "team"})
    @Query("SELECT c FROM CollaborationTracking c WHERE c.isDeleted = false " +
           "AND FUNCTION('to_char', c.publishDate, 'YYYYMM') = :month")
    List<CollaborationTracking> findByPublishMonth(@Param("month") String month);

    /** 数据看板用：按"发布时间"所在月份范围（闭区间）查询 */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"influencer", "brand", "projectManager", "team"})
    @Query("SELECT c FROM CollaborationTracking c WHERE c.isDeleted = false " +
           "AND FUNCTION('to_char', c.publishDate, 'YYYYMM') BETWEEN :startMonth AND :endMonth")
    List<CollaborationTracking> findByPublishMonthBetween(
            @Param("startMonth") String startMonth, @Param("endMonth") String endMonth);

    /**
     * 数据看板"视频发布日期"筛选用（2026-08 新增）：按"发布时间"精确到天的区间（闭区间）查询，
     * 跟 findByPublishMonthBetween 是同一个思路，只是 to_char 格式换成 'YYYY-MM-DD'，
     * 用字符串比较而不是 Date 类型参数（原因见 findByFilters 上关于 videoMonth 的注释——
     * Supabase 连接池下 Date 类型参数在这类动态筛选查询里会报"could not determine data type
     * of parameter"）。startDate/endDate 格式 'YYYY-MM-DD'。
     */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"influencer", "brand", "projectManager", "team"})
    @Query("SELECT c FROM CollaborationTracking c WHERE c.isDeleted = false " +
           "AND FUNCTION('to_char', c.publishDate, 'YYYY-MM-DD') BETWEEN :startDate AND :endDate")
    List<CollaborationTracking> findByPublishDateBetween(
            @Param("startDate") String startDate, @Param("endDate") String endDate);

    /**
     * 内部执行成本梯度分档计算专用：某执行人员在某"发布时间"月份下、某个具体项目负责人名下，
     * 已经赋值过内部执行成本的全部记录（不分视频类型，按 publishDate 升序排列，同一天的用 id
     * 兜底排序保证结果确定）。
     *
     * 2026-08 修复：排序字段之前用的是 id（创建/导入进系统的先后），注释里当时也承认这只是
     * "近似代表实际处理顺序"——批量 Excel 导入的历史数据尤其容易失真：一批记录几乎同一时刻
     * 导入、id 连号，但它们真实的视频发布时间可能横跨好几周，而项目负责人设置内部执行成本的
     * 先后顺序往往是跟着发布时间走的，不是跟着导入顺序走的。更麻烦的是"第几条"是每次现算的，
     * 不是设置成本那一刻就固定下来的——后面只要又有别的、id 更小的记录被设置了成本，
     * 前面已经设置过的记录重新计算出来的"第几条"就会跟着往后挪，掉进下一档，导致明明当初
     * 填对了价格的记录，事后被 CollaborationTrackingService.computeSpecialPayNote() 误判成
     * "特殊薪酬"（用户反馈：这类误判笔数在批量导入过的月份尤其多）。
     * 改成按 publishDate 排序后，排位依据是每条记录自己就带着、发布之后不会再变的客观事实，
     * 不会被后续别的记录是否设置了成本影响——不需要新增字段/不需要补历史数据，
     * 存量记录和以后新记录用的是同一套排序逻辑，一次性都修好了。
     *
     * 2026-07 起四个视频类型统一走"按当月累计条数分档"的梯度结构（见 ExecutorPayRateTier），
     * CollaborationTrackingService 在内存里按 videoType 再分桶，分别判断第几笔、分档、以及
     * "不封顶那档"的当月累计封顶金额，不需要针对某个视频类型单独开一条查询。
     */
    @Query("SELECT c FROM CollaborationTracking c WHERE c.isDeleted = false " +
           "AND c.executorId = :executorId AND c.projectManagerId = :managerId " +
           "AND c.internalExecutionCost IS NOT NULL " +
           "AND FUNCTION('to_char', c.publishDate, 'YYYYMM') = :month " +
           "ORDER BY c.publishDate ASC, c.id ASC")
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

    /** 上面那条的批量版本：进度提醒批次一次性算多条结款记录时用，避免循环里逐条查库 */
    List<CollaborationTracking> findByInfluencerPaymentIdInAndIsDeletedFalse(List<Long> influencerPaymentIds);

    /** 红人结款 - 创建/编辑时校验勾选的 id 是否都合法可用（属于该品牌+团队、且未被其他批次占用） */
    List<CollaborationTracking> findByIdInAndIsDeletedFalse(List<Long> ids);

    // ===== 2026-07 红人需求管理对接新增 =====

    /**
     * 需求列表页"需求完成进度"批量计算：按 internalRequirementNo 分组统计视频项目进度属于
     * 已发布(未结算)/已加入客户未结算列表/客户已结算/已收到客户回款/折损 这五个状态的记录数
     * （2026-08-21 新增"已收到客户回款"），一次查出当前页所有需求的计数，避免逐条查库。
     * 这里判断的是"视频生产/发布环节是否已经走到位"（不是"客户回款是否到账"），
     * "已收到客户回款"是"客户已结算"之后才能到达的更靠后状态，自然也满足这个条件，
     * 不加的话反而会漏计。
     */
    @Query("SELECT c.internalRequirementNo, COUNT(c) FROM CollaborationTracking c " +
           "WHERE c.isDeleted = false AND c.internalRequirementNo IN :requirementNos " +
           "AND c.progress IN (" +
           "  com.lusuoria.settlement.enums.CollaborationProgress.PUBLISHED_UNSETTLED, " +
           "  com.lusuoria.settlement.enums.CollaborationProgress.JOINED_CLIENT_UNSETTLED_LIST, " +
           "  com.lusuoria.settlement.enums.CollaborationProgress.SETTLED, " +
           "  com.lusuoria.settlement.enums.CollaborationProgress.PAYMENT_RECEIVED, " +
           "  com.lusuoria.settlement.enums.CollaborationProgress.DELAYED) " +
           "GROUP BY c.internalRequirementNo")
    List<Object[]> countCompletedByRequirementNos(@Param("requirementNos") List<String> requirementNos);

    /**
     * "重新计算需求完成时间"善后用（2026-08 新增）：按 internalRequirementNo 分组算出该需求下
     * 所有关联记录（含折损，只要填过视频发布时间）里最晚的视频发布时间，供
     * InfluencerRequirementService.recomputeAllCompletedAt() 把"需求完成时间"订正成
     * "该需求里最晚的视频发布时间"，而不是"系统检测到100%完成的那一刻"——存量/批量导入数据
     * 场景下，后者跟真实完成时间可能相差很远。
     */
    @Query("SELECT c.internalRequirementNo, MAX(c.publishDate) FROM CollaborationTracking c " +
           "WHERE c.isDeleted = false AND c.internalRequirementNo IN :requirementNos " +
           "AND c.publishDate IS NOT NULL " +
           "GROUP BY c.internalRequirementNo")
    List<Object[]> maxPublishDateByRequirementNos(@Param("requirementNos") List<String> requirementNos);

    /**
     * 红人结款用：按 internalRequirementNo 分组统计"实际可结款成本"——只看 已发布(未结算)/
     * 已加入客户未结算列表/客户已结算/已收到客户回款 这四个"会真正付钱"的终态（2026-08-21
     * 新增"已收到客户回款"；不含折损），红人视频制作与发布成本(influencerCost)之和。2026-08
     * 修复：之前直接用 InfluencerRequirement.totalInfluencerCost（需求创建时按单价×数量算好
     * 的计划总成本）做阈值分档，没有排除后来被判"折损"、事实上不会付款的条目，导致阈值/预计
     * 付款日算多了——现在改成从实际记录聚合，天然排除折损。
     */
    @Query("SELECT c.internalRequirementNo, SUM(c.influencerCost) FROM CollaborationTracking c " +
           "WHERE c.isDeleted = false AND c.internalRequirementNo IN :requirementNos " +
           "AND c.progress IN (" +
           "  com.lusuoria.settlement.enums.CollaborationProgress.PUBLISHED_UNSETTLED, " +
           "  com.lusuoria.settlement.enums.CollaborationProgress.JOINED_CLIENT_UNSETTLED_LIST, " +
           "  com.lusuoria.settlement.enums.CollaborationProgress.SETTLED, " +
           "  com.lusuoria.settlement.enums.CollaborationProgress.PAYMENT_RECEIVED) " +
           "GROUP BY c.internalRequirementNo")
    List<Object[]> sumPayableCostByRequirementNos(@Param("requirementNos") List<String> requirementNos);

    /**
     * 红人结款用：按 internalRequirementNo 分组统计"折损"条目的数量+成本之和——"选择涉及的红人
     * 视频项目"弹窗要提示管理层"这个需求里有几笔折损，涉及金额多少"，避免管理层看着"需求完成
     * 进度100%"却发现实际能勾选的条数比总数少，误以为哪里出了问题。
     */
    @Query("SELECT c.internalRequirementNo, COUNT(c), SUM(c.influencerCost) FROM CollaborationTracking c " +
           "WHERE c.isDeleted = false AND c.internalRequirementNo IN :requirementNos " +
           "AND c.progress = com.lusuoria.settlement.enums.CollaborationProgress.DELAYED " +
           "GROUP BY c.internalRequirementNo")
    List<Object[]> sumDelayedByRequirementNos(@Param("requirementNos") List<String> requirementNos);

    /**
     * "查看未结款的需求"专用（2026-08 修复）：从传入的需求编号里，找出"存在至少一条已经进入
     * 可结款阶段（红人结款进度非空）、但还没被纳入任何结款批次"的记录的需求编号集合。
     *
     * 背景：这个筛选原来只判断"结款状态字段是否为 null"，但月结品牌方允许一个需求分多批结款——
     * 需求一旦被结过一次款（哪怕只结了其中一部分），结款状态就不再是 null，会永久性地从
     * "查看未结款的需求"列表消失，即使后续又有新的可结款记录被漏掉忘记结。这里改成直接判断
     * "这个需求下还有没有漏网的可结款记录"，不管这个需求之前有没有结过款——只要还有没结的，
     * 就应该继续出现在这个列表里；调用方（InfluencerRequirementService.pageUnsettled）把这个
     * 结果跟"结款状态为 null"（覆盖还没任何视频进入可结款阶段的全新需求）取并集。
     *
     * "已经进入可结款阶段"= influencerPaymentProgress 非空（跟 InfluencerPaymentService.
     * validateNoPartialRequirement() 判断"遗漏"用的是同一个口径）；"还没被纳入任何结款批次"
     * 排除 INCLUDED_IN_PAYMENT_BATCH / INCLUDED_IN_PAYMENT_BATCH_MISSING_INVOICE 这两个值。
     */
    @Query("SELECT DISTINCT c.internalRequirementNo FROM CollaborationTracking c " +
           "WHERE c.isDeleted = false AND c.internalRequirementNo IN :requirementNos " +
           "AND c.influencerPaymentProgress IS NOT NULL " +
           "AND c.influencerPaymentProgress NOT IN (" +
           "  com.lusuoria.settlement.enums.InfluencerPaymentProgress.INCLUDED_IN_PAYMENT_BATCH, " +
           "  com.lusuoria.settlement.enums.InfluencerPaymentProgress.INCLUDED_IN_PAYMENT_BATCH_MISSING_INVOICE)")
    List<String> findRequirementNosWithUnbatchedPayableItems(@Param("requirementNos") List<String> requirementNos);

    /**
     * 需求列表页"新建合作跟踪"按钮判断用：按 internalRequirementNo 分组统计"已建立跟踪记录数"
     * ——不看 progress 状态，只要关联了就算（含折损），口径跟 findByInternalRequirementNoAndIsDeletedFalse
     * 一致。达到 totalItemCount 时说明每个条目的名额都已经有跟踪记录占上了，不该再允许新建，
     * 即使"需求完成进度"（只看已发布/已结算/折损这几个终态）还没到100%。
     */
    @Query("SELECT c.internalRequirementNo, COUNT(c) FROM CollaborationTracking c " +
           "WHERE c.isDeleted = false AND c.internalRequirementNo IN :requirementNos " +
           "GROUP BY c.internalRequirementNo")
    List<Object[]> countEstablishedByRequirementNos(@Param("requirementNos") List<String> requirementNos);

    /**
     * "关联红人需求"选择器第二步 / 需求完成进度点击详情：某个需求下所有已关联的红人合作跟踪记录
     * （不看 progress 状态，只要关联了就算，含折损）。
     */
    List<CollaborationTracking> findByInternalRequirementNoAndIsDeletedFalse(String internalRequirementNo);

    /**
     * 上面那个单需求版本的批量版（2026-08-16 新增）：ProgressReminderService.
     * runRequirementInvoiceOverdue()/runRequirementContractOverdue() 之前是在"遍历候选需求"
     * 的循环里逐条调用单需求版本，是个会随"完成后长时间未上传Invoice/合同的需求数"线性增长的
     * N+1——这两类候选本来就是"越攒越多、直到有人补上Invoice/合同才会减少"的存量数据，不会
     * 自然清零，这两个方法又是"项目流转后更新提示内容"手动重算按钮会触发的同步调用，管理层点
     * 一次按钮就要背等这些请求全部跑完，很容易拖到网关/浏览器超时（Render 免费层 DB 连接池只有
     * 3 个，这个方法本身还是 @Transactional，慢查询期间一直占着一个连接，可能连带拖慢其它请求）。
     * 改成一次性按 internalRequirementNo 批量查回来，在内存里 groupingBy 分组，用法见上面两个
     * 方法——不看 progress 状态，只要关联了就算，含折损，跟单需求版本口径一致。
     */
    List<CollaborationTracking> findByInternalRequirementNoInAndIsDeletedFalse(List<String> internalRequirementNos);

    /** "存量记录关联需求"候选查询：某个红人名下还没关联任何需求的记录 */
    List<CollaborationTracking> findByInfluencerIdAndInternalRequirementNoIsNullAndIsDeletedFalse(Long influencerId);

    /** 红人结款列表按"内部需求编号"筛选用：涉及了这个需求编号、且已经纳入某个结款批次的记录，对应的结款批次id */
    @Query("SELECT DISTINCT c.influencerPaymentId FROM CollaborationTracking c " +
           "WHERE c.internalRequirementNo = :requirementNo AND c.isDeleted = false " +
           "AND c.influencerPaymentId IS NOT NULL")
    List<Long> findPaymentIdsByRequirementNo(@Param("requirementNo") String requirementNo);
}
