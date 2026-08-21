package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.enums.ProjectType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InfluencerRepository extends JpaRepository<Influencer, Long> {

    List<Influencer> findByIsDeletedFalseOrderByAccountNameAsc();

    /**
     * 精简投影：只查下拉框需要的3个字段，不加载 notes/contacts/links/成本等大字段。
     * 供 /api/influencers/simple 使用，供项目订单/合作跟踪/打款等模块的红人选择下拉框使用。
     */
    @Query("SELECT i.id, i.accountName, i.countryMarket FROM Influencer i " +
           "WHERE i.isDeleted = false ORDER BY i.accountName ASC")
    List<Object[]> findSimpleProjections();

    /**
     * 2026-08-13 性能修复：DomainSyncService.sync() 之前用 findByIsDeletedFalseOrderByAccountNameAsc()
     * 查全量实体（notes/联系方式/成本字段等全部一起加载）只是为了读 domains 这一列，
     * 是"红人管理"页面（GET /api/domains 依赖 sync()）和"编辑红人改了所属领域"这两个场景
     * 保存/切换模块变慢的根因之一——道理跟上面 findSimpleProjections/findIdsByFilters
     * 这两处精简投影完全一样，红人表越大、字段越多，全量实体加载就越吃亏。sync() 只需要
     * domains 这一列，改成只查这一列。
     */
    @Query("SELECT i.domains FROM Influencer i WHERE i.isDeleted = false AND i.domains IS NOT NULL")
    List<String> findActiveDomainsRaw();

    Optional<Influencer> findByIdAndIsDeletedFalse(Long id);

    /**
     * 精简投影：配置了"特殊回款周期"的红人 id + 天数（2026-08-21 新增），只查这两列，不加载
     * notes/联系方式/成本等大字段——供 ProgressReminderService.runCollabPaymentDue() 每天跑批
     * 时批量判断哪些记录要走这条最高优先级的回款周期规则，跟这个文件其它精简投影
     * （findSimpleProjections/findIdsByFilters）是同一个思路。
     */
    @Query("SELECT i.id, i.specialPaymentCycleDays FROM Influencer i " +
           "WHERE i.isDeleted = false AND i.specialPaymentCycleDays IS NOT NULL")
    List<Object[]> findSpecialPaymentCycleDaysProjections();

    Optional<Influencer> findByAccountNameAndIsDeletedFalse(String accountName);

    /** "提取需求内容"账号匹配用：忽略大小写精确匹配 */
    Optional<Influencer> findByAccountNameIgnoreCaseAndIsDeletedFalse(String accountName);

    /** 不限 isDeleted 的忽略大小写精确匹配，供新建红人时复活同名的已软删除记录用（account_name
     * 数据库层面有唯一约束，不认软删除，见 InfluencerController.save()） */
    Optional<Influencer> findByAccountNameIgnoreCase(String accountName);

    List<Influencer> findByInfluencerTypeAndIsDeletedFalse(ProjectType type);

    @Query("SELECT i FROM Influencer i " +
           "WHERE i.isDeleted = false " +
           "AND (:influencerType IS NULL OR i.influencerType = :influencerType) " +
           "AND (:platform IS NULL OR i.platform LIKE %:platform%) " +
           "AND (:countryMarket IS NULL OR i.countryMarket LIKE %:countryMarket%) " +
           "AND (:domain IS NULL OR i.domains LIKE %:domain%) " +
           "AND (:brandId IS NULL OR i.id IN (" +
           "    SELECT ibt.influencerId FROM InfluencerBrandTeam ibt " +
           "    WHERE ibt.brandId = :brandId AND ibt.isDeleted = false)) " +
           "AND (:teamId IS NULL OR i.id IN (" +
           "    SELECT ibt2.influencerId FROM InfluencerBrandTeam ibt2 " +
           "    WHERE ibt2.teamId = :teamId AND ibt2.isDeleted = false)) " +
           "AND (:followerMin IS NULL OR i.followerCount >= :followerMin) " +
           "AND (:followerMax IS NULL OR i.followerCount <= :followerMax) " +
           "AND (:keyword IS NULL OR i.accountName LIKE %:keyword%)")
    Page<Influencer> findByFilters(
            @Param("influencerType") ProjectType influencerType,
            @Param("platform") String platform,
            @Param("countryMarket") String countryMarket,
            @Param("domain") String domain,
            @Param("brandId") Long brandId,
            @Param("teamId") Long teamId,
            @Param("followerMin") Long followerMin,
            @Param("followerMax") Long followerMax,
            @Param("keyword") String keyword,
            Pageable pageable);

    /**
     * 与 findByFilters 完全相同的筛选条件，但只查 id——2026-07 起红人管理列表页的默认排序
     * （"合作中项目有值的排最前"）不再靠 findByFilters 整批拉全量实体再在内存里重排，那样
     * 每次翻页都会把整个筛选结果集的完整实体（含 notes/links 等大字段）都查出来，是列表页
     * 载入变慢的根因。id 是轻量投影，可以整批取出来做排序用，实体只在最终确定当前页之后
     * 才按需查（见 InfluencerController.list()）。
     */
    @Query("SELECT i.id FROM Influencer i " +
           "WHERE i.isDeleted = false " +
           "AND (:influencerType IS NULL OR i.influencerType = :influencerType) " +
           "AND (:platform IS NULL OR i.platform LIKE %:platform%) " +
           "AND (:countryMarket IS NULL OR i.countryMarket LIKE %:countryMarket%) " +
           "AND (:domain IS NULL OR i.domains LIKE %:domain%) " +
           "AND (:brandId IS NULL OR i.id IN (" +
           "    SELECT ibt.influencerId FROM InfluencerBrandTeam ibt " +
           "    WHERE ibt.brandId = :brandId AND ibt.isDeleted = false)) " +
           "AND (:teamId IS NULL OR i.id IN (" +
           "    SELECT ibt2.influencerId FROM InfluencerBrandTeam ibt2 " +
           "    WHERE ibt2.teamId = :teamId AND ibt2.isDeleted = false)) " +
           "AND (:followerMin IS NULL OR i.followerCount >= :followerMin) " +
           "AND (:followerMax IS NULL OR i.followerCount <= :followerMax) " +
           "AND (:keyword IS NULL OR i.accountName LIKE %:keyword%)")
    List<Long> findIdsByFilters(
            @Param("influencerType") ProjectType influencerType,
            @Param("platform") String platform,
            @Param("countryMarket") String countryMarket,
            @Param("domain") String domain,
            @Param("brandId") Long brandId,
            @Param("teamId") Long teamId,
            @Param("followerMin") Long followerMin,
            @Param("followerMax") Long followerMax,
            @Param("keyword") String keyword,
            Sort sort);
}
