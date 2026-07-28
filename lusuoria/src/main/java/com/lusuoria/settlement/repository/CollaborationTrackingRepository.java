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
     * influencerId（2026-07 新增）：红人管理模块"合作中项目/已完结项目"下钻的"查看全部"
     * 精确跳转用——accountName 是模糊匹配（LIKE），账号名互为子串的两个红人会串号，
     * influencerId 是精确匹配，不受文本子串影响。两者可以同时传（一般只会传一个）。
     */
    @Query("SELECT c FROM CollaborationTracking c " +
           "WHERE c.isDeleted = false " +
           "AND (:brandId IS NULL OR c.brandId = :brandId) " +
           "AND (:teamId IS NULL OR c.teamId = :teamId) " +
           "AND (:countryMarket IS NULL OR c.countryMarket = :countryMarket) " +
           "AND (:accountName IS NULL OR c.influencer.accountName LIKE %:accountName%) " +
           "AND (:influencerId IS NULL OR c.influencerId = :influencerId) " +
           "AND (:platform IS NULL OR c.platform LIKE %:platform%) " +
           "AND (:progress IS NULL OR c.progress = :progress) " +
           "AND (:influencerPaymentProgress IS NULL OR c.influencerPaymentProgress = :influencerPaymentProgress) " +
           "AND (:videoType IS NULL OR c.videoType = :videoType) " +
           "AND (:videoMonth IS NULL OR FUNCTION('to_char', c.publishDate, 'YYYYMM') = :videoMonth) " +
           "AND (:internalProjectNo IS NULL OR c.internalProjectNo LIKE %:internalProjectNo%) " +
           "AND (:internalRequirementNo IS NULL OR c.internalRequirementNo LIKE %:internalRequirementNo%) " +
           "AND (:clientOrderId IS NULL OR c.clientOrderId LIKE %:clientOrderId%) " +
           "AND (:clientPaymentBatch IS NULL OR c.clientPaymentBatch LIKE %:clientPaymentBatch%) " +
           "AND (:projectManagerId IS NULL OR c.projectManagerId = :projectManagerId) " +
           "AND (:onlyMyResponsibility = false " +
           "     OR (:priorityEmployeeId IS NOT NULL AND (c.projectManagerId = :priorityEmployeeId OR c.executorId = :priorityEmployeeId)) " +
           "     OR (:prioritizeFinance = true AND c.progress IN (" +
           "           com.lusuoria.settlement.enums.CollaborationProgress.PUBLISHED_UNSETTLED, " +
           "           com.lusuoria.settlement.enums.CollaborationProgress.JOINED_CLIENT_UNSETTLED_LIST))) " +
           "AND (:onlyIncomplete = false OR c.progress IS NULL OR c.progress NOT IN (" +
           "     com.lusuoria.settlement.enums.CollaborationProgress.SETTLED, " +
           "     com.lusuoria.settlement.enums.CollaborationProgress.DELAYED))")
    Page<CollaborationTracking> findByFilters(
            @Param("brandId") Long brandId,
            @Param("teamId") Long teamId,
            @Param("countryMarket") String countryMarket,
            @Param("accountName") String accountName,
            @Param("influencerId") Long influencerId,
            @Param("platform") String platform,
            @Param("progress") CollaborationProgress progress,
            @Param("influencerPaymentProgress") InfluencerPaymentProgress influencerPaymentProgress,
            @Param("videoType") VideoType videoType,
            @Param("videoMonth") String videoMonth,
            @Param("internalProjectNo") String internalProjectNo,
            @Param("internalRequirementNo") String internalRequirementNo,
            @Param("clientOrderId") String clientOrderId,
            @Param("clientPaymentBatch") String clientPaymentBatch,
            @Param("projectManagerId") Long projectManagerId,
            @Param("priorityEmployeeId") Long priorityEmployeeId,
            @Param("prioritizeFinance") Boolean prioritizeFinance,
            @Param("onlyMyResponsibility") Boolean onlyMyResponsibility,
            @Param("onlyIncomplete") Boolean onlyIncomplete,
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
           "AND (:progress IS NULL OR c.progress = :progress) " +
           "AND (:influencerPaymentProgress IS NULL OR c.influencerPaymentProgress = :influencerPaymentProgress) " +
           "AND (:videoType IS NULL OR c.videoType = :videoType) " +
           "AND (:videoMonth IS NULL OR FUNCTION('to_char', c.publishDate, 'YYYYMM') = :videoMonth) " +
           "AND (:internalProjectNo IS NULL OR c.internalProjectNo LIKE %:internalProjectNo%) " +
           "AND (:internalRequirementNo IS NULL OR c.internalRequirementNo LIKE %:internalRequirementNo%) " +
           "AND (:clientOrderId IS NULL OR c.clientOrderId LIKE %:clientOrderId%) " +
           "AND (:clientPaymentBatch IS NULL OR c.clientPaymentBatch LIKE %:clientPaymentBatch%) " +
           "AND (:projectManagerId IS NULL OR c.projectManagerId = :projectManagerId) " +
           "AND (:onlyMyResponsibility = false " +
           "     OR (:priorityEmployeeId IS NOT NULL AND (c.projectManagerId = :priorityEmployeeId OR c.executorId = :priorityEmployeeId)) " +
           "     OR (:prioritizeFinance = true AND c.progress IN (" +
           "           com.lusuoria.settlement.enums.CollaborationProgress.PUBLISHED_UNSETTLED, " +
           "           com.lusuoria.settlement.enums.CollaborationProgress.JOINED_CLIENT_UNSETTLED_LIST))) " +
           "AND (:onlyIncomplete = false OR c.progress IS NULL OR c.progress NOT IN (" +
           "     com.lusuoria.settlement.enums.CollaborationProgress.SETTLED, " +
           "     com.lusuoria.settlement.enums.CollaborationProgress.DELAYED))")
    List<Object[]> findLitePriorityProjectionByFilters(
            @Param("brandId") Long brandId,
            @Param("teamId") Long teamId,
            @Param("countryMarket") String countryMarket,
            @Param("accountName") String accountName,
            @Param("influencerId") Long influencerId,
            @Param("platform") String platform,
            @Param("progress") CollaborationProgress progress,
            @Param("influencerPaymentProgress") InfluencerPaymentProgress influencerPaymentProgress,
            @Param("videoType") VideoType videoType,
            @Param("videoMonth") String videoMonth,
            @Param("internalProjectNo") String internalProjectNo,
            @Param("internalRequirementNo") String internalRequirementNo,
            @Param("clientOrderId") String clientOrderId,
            @Param("clientPaymentBatch") String clientPaymentBatch,
            @Param("projectManagerId") Long projectManagerId,
            @Param("priorityEmployeeId") Long priorityEmployeeId,
            @Param("prioritizeFinance") Boolean prioritizeFinance,
            @Param("onlyMyResponsibility") Boolean onlyMyResponsibility,
            @Param("onlyIncomplete") Boolean onlyIncomplete,
            Sort sort);


    /**
     * 数据看板用：取出全部未删除记录，月份归属（发布月份，若无则归创建月份）
     * 的精确判断在 Service 层用 Java 完成，避免不同数据库方言下日期函数写法
     * 不一致、以及"跨月范围+按月归属"组合逻辑在 SQL 里难以正确表达的问题。
     */
    List<CollaborationTracking> findByIsDeletedFalse();

    /**
     * 红人管理"合作中项目"（进度不是"客户已结算"也不是"折损"）+ "已完结项目"（进度="客户已结算"）
     * 各自数量，按红人 id 分组。2026-07 起合并成一条 SQL（之前是两条独立的 COUNT 查询，各自
     * 扫一遍 CollaborationTracking 表），用 SUM(CASE WHEN ... THEN 1 ELSE 0 END) 一次算出两个
     * 口径的计数，减少一半的数据库往返——这个查询会被红人管理列表页的批量计数、以及默认排序
     * （"合作中项目有值的排最前"）复用，后者是每次翻页/筛选都会跑一次的高频路径，值得省这一趟。
     * 返回：[influencerId, activeCount, completedCount]，每个数字类型统一按 Number 处理再转
     * long——不同 Hibernate 版本/方言对 SUM(CASE WHEN...) 这种聚合表达式返回的具体包装类型
     * （Long/BigInteger）不完全一致，直接强转 Long 有把握不住的风险。
     */
    @Query("SELECT c.influencerId, " +
           "SUM(CASE WHEN c.progress IS NULL OR c.progress NOT IN (" +
           "  com.lusuoria.settlement.enums.CollaborationProgress.SETTLED, " +
           "  com.lusuoria.settlement.enums.CollaborationProgress.DELAYED) THEN 1L ELSE 0L END), " +
           "SUM(CASE WHEN c.progress = com.lusuoria.settlement.enums.CollaborationProgress.SETTLED THEN 1L ELSE 0L END) " +
           "FROM CollaborationTracking c " +
           "WHERE c.isDeleted = false AND c.influencerId IN :influencerIds " +
           "GROUP BY c.influencerId")
    List<Object[]> countActiveAndCompletedByInfluencerIds(@Param("influencerIds") List<Long> influencerIds);

    /**
     * 红人管理"合作中项目/已完结项目"下钻弹窗用：某个红人 + 类别（completed=true 只看"客户
     * 已结算"，completed=false 看"不是客户已结算也不是折损"的进行中记录）分页查询。
     * EntityGraph 预先带上展示需要的关联，避免弹窗表格逐行触发懒加载 N+1。
     *
     * 2026-07 新增几个可选筛选（都是"传了就筛，不传不影响"，可以同时生效）：platform/videoType/
     * projectManagerId 两个类别通用；progress/influencerPaymentProgress 主要给"合作中项目"用
     * （"已完结项目"本身已经锁定 progress=已结算，这两个筛选传了也不会报错，只是没什么实际
     * 意义，前端只在"合作中项目"弹窗展示这两个筛选项）；videoMonth（发布月份）主要给"已完结
     * 项目"用。videoMonth 用 to_char 转字符串比较，原因跟 CollaborationTrackingRepository.
     * findByFilters 的 videoMonth 注释一致（Date 类型参数在这类动态筛选查询里在 Supabase 连接池
     * 下会报"could not determine data type of parameter"）。
     */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"brand", "team", "influencer", "projectManager", "executor"})
    @Query("SELECT c FROM CollaborationTracking c WHERE c.isDeleted = false AND c.influencerId = :influencerId AND (" +
           "  (:completed = true AND c.progress = com.lusuoria.settlement.enums.CollaborationProgress.SETTLED) " +
           "  OR (:completed = false AND (c.progress IS NULL OR c.progress NOT IN (" +
           "        com.lusuoria.settlement.enums.CollaborationProgress.SETTLED, " +
           "        com.lusuoria.settlement.enums.CollaborationProgress.DELAYED)))) " +
           "AND (:platform IS NULL OR c.platform LIKE %:platform%) " +
           "AND (:videoType IS NULL OR c.videoType = :videoType) " +
           "AND (:progress IS NULL OR c.progress = :progress) " +
           "AND (:influencerPaymentProgress IS NULL OR c.influencerPaymentProgress = :influencerPaymentProgress) " +
           "AND (:projectManagerId IS NULL OR c.projectManagerId = :projectManagerId) " +
           "AND (:videoMonth IS NULL OR FUNCTION('to_char', c.publishDate, 'YYYYMM') = :videoMonth)")
    Page<CollaborationTracking> findByInfluencerAndCompletionStatus(
            @Param("influencerId") Long influencerId, @Param("completed") boolean completed,
            @Param("platform") String platform, @Param("videoType") VideoType videoType,
            @Param("progress") CollaborationProgress progress,
            @Param("influencerPaymentProgress") InfluencerPaymentProgress influencerPaymentProgress,
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
           "  (:completed = true AND c.progress = com.lusuoria.settlement.enums.CollaborationProgress.SETTLED) " +
           "  OR (:completed = false AND (c.progress IS NULL OR c.progress NOT IN (" +
           "        com.lusuoria.settlement.enums.CollaborationProgress.SETTLED, " +
           "        com.lusuoria.settlement.enums.CollaborationProgress.DELAYED)))) " +
           "AND (:platform IS NULL OR c.platform LIKE %:platform%) " +
           "AND (:videoType IS NULL OR c.videoType = :videoType) " +
           "AND (:progress IS NULL OR c.progress = :progress) " +
           "AND (:influencerPaymentProgress IS NULL OR c.influencerPaymentProgress = :influencerPaymentProgress) " +
           "AND (:projectManagerId IS NULL OR c.projectManagerId = :projectManagerId) " +
           "AND (:videoMonth IS NULL OR FUNCTION('to_char', c.publishDate, 'YYYYMM') = :videoMonth)")
    List<Object[]> sumByInfluencerAndCompletionStatus(
            @Param("influencerId") Long influencerId, @Param("completed") boolean completed,
            @Param("platform") String platform, @Param("videoType") VideoType videoType,
            @Param("progress") CollaborationProgress progress,
            @Param("influencerPaymentProgress") InfluencerPaymentProgress influencerPaymentProgress,
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
     * 内部执行成本梯度分档计算专用：某执行人员在某"发布时间"月份下、某个具体项目负责人名下，
     * 已经赋值过内部执行成本的全部记录（不分视频类型，按 id 升序排列，用 id 顺序近似代表实际
     * 处理顺序）。2026-07 起四个视频类型统一走"按当月累计条数分档"的梯度结构
     * （见 ExecutorPayRateTier），CollaborationTrackingService 在内存里按 videoType 再分桶，
     * 分别判断第几笔、分档、以及"不封顶那档"的当月累计封顶金额，不需要针对某个视频类型
     * 单独开一条查询。
     */
    @Query("SELECT c FROM CollaborationTracking c WHERE c.isDeleted = false " +
           "AND c.executorId = :executorId AND c.projectManagerId = :managerId " +
           "AND c.internalExecutionCost IS NOT NULL " +
           "AND FUNCTION('to_char', c.publishDate, 'YYYYMM') = :month " +
           "ORDER BY c.id ASC")
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

    /** "存量记录关联需求"候选查询：某个红人名下还没关联任何需求的记录 */
    List<CollaborationTracking> findByInfluencerIdAndInternalRequirementNoIsNullAndIsDeletedFalse(Long influencerId);

    /** 红人结款列表按"内部需求编号"筛选用：涉及了这个需求编号、且已经纳入某个结款批次的记录，对应的结款批次id */
    @Query("SELECT DISTINCT c.influencerPaymentId FROM CollaborationTracking c " +
           "WHERE c.internalRequirementNo = :requirementNo AND c.isDeleted = false " +
           "AND c.influencerPaymentId IS NOT NULL")
    List<Long> findPaymentIdsByRequirementNo(@Param("requirementNo") String requirementNo);
}
