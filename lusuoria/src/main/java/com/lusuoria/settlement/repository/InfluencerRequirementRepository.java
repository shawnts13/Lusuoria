package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.InfluencerRequirement;
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
public interface InfluencerRequirementRepository extends JpaRepository<InfluencerRequirement, Long> {

    Optional<InfluencerRequirement> findByIdAndIsDeletedFalse(Long id);

    Optional<InfluencerRequirement> findByInternalRequirementNoAndIsDeletedFalse(String internalRequirementNo);

    boolean existsByInternalRequirementNo(String internalRequirementNo);

    /** 内部需求编号分配用：统计某"品牌-团队-月份-账号-"前缀已用了多少个（作为序号起点估算） */
    @Query("SELECT COUNT(r) FROM InfluencerRequirement r " +
           "WHERE r.isDeleted = false AND r.internalRequirementNo LIKE :prefixPattern")
    long countByInternalRequirementNoPrefix(@Param("prefixPattern") String prefixPattern);

    @Query("SELECT r FROM InfluencerRequirement r " +
           "WHERE r.isDeleted = false " +
           "AND (:brandId IS NULL OR r.brandId = :brandId) " +
           "AND (:teamId IS NULL OR r.teamId = :teamId) " +
           "AND (:accountName IS NULL OR r.influencer.accountName LIKE %:accountName%) " +
           "AND (:requirementMonth IS NULL OR r.requirementMonth = :requirementMonth) " +
           "AND (:internalRequirementNo IS NULL OR r.internalRequirementNo LIKE %:internalRequirementNo%)")
    Page<InfluencerRequirement> findByFilters(
            @Param("brandId") Long brandId,
            @Param("teamId") Long teamId,
            @Param("accountName") String accountName,
            @Param("requirementMonth") String requirementMonth,
            @Param("internalRequirementNo") String internalRequirementNo,
            Pageable pageable);

    /**
     * "查看未完成的需求"专用：条件跟 findByFilters 完全一致，但不分页，一次性全部查出来。
     * "需求完成进度"分子（completedCount）是查完之后批量算出来的，不是数据库列，没法直接下推
     * 到 SQL WHERE 里筛，只能先把符合其他筛选条件的需求全部查出来，在内存里筛掉已经100%的，
     * 再手动分页（见 InfluencerRequirementService.pageIncomplete）——这个模块数据量不大，
     * 全量查询+内存筛选足够用，不需要为了这一个开关额外写关联子查询。
     */
    @Query("SELECT r FROM InfluencerRequirement r " +
           "WHERE r.isDeleted = false " +
           "AND (:brandId IS NULL OR r.brandId = :brandId) " +
           "AND (:teamId IS NULL OR r.teamId = :teamId) " +
           "AND (:accountName IS NULL OR r.influencer.accountName LIKE %:accountName%) " +
           "AND (:requirementMonth IS NULL OR r.requirementMonth = :requirementMonth) " +
           "AND (:internalRequirementNo IS NULL OR r.internalRequirementNo LIKE %:internalRequirementNo%)")
    List<InfluencerRequirement> findByFiltersNoPaging(
            @Param("brandId") Long brandId,
            @Param("teamId") Long teamId,
            @Param("accountName") String accountName,
            @Param("requirementMonth") String requirementMonth,
            @Param("internalRequirementNo") String internalRequirementNo,
            Sort sort);

    /** "关联红人需求"选择器第一步：某个红人名下的所有未删除需求（前端再按"需求完成进度"过滤掉已满的） */
    List<InfluencerRequirement> findByInfluencerIdAndIsDeletedFalse(Long influencerId);

    /** "Invoice逾期"提醒批次用：已完成（completedAt有值）但还没上传invoice的需求 */
    List<InfluencerRequirement> findByIsDeletedFalseAndCompletedAtIsNotNullAndInvoiceLinkIsNull();

    /** "合同上传逾期"提醒批次用：已完成（completedAt有值）但还没上传合同的需求 */
    List<InfluencerRequirement> findByIsDeletedFalseAndCompletedAtIsNotNullAndContractLinkIsNull();
}
