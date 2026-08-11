package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.InfluencerRequirement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import javax.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

@Repository
public interface InfluencerRequirementRepository extends JpaRepository<InfluencerRequirement, Long> {

    Optional<InfluencerRequirement> findByIdAndIsDeletedFalse(Long id);

    Optional<InfluencerRequirement> findByInternalRequirementNoAndIsDeletedFalse(String internalRequirementNo);

    /**
     * 跟 findByInternalRequirementNoAndIsDeletedFalse 一样，但加悲观写锁（2026-08 新增）。
     * 专供 InfluencerRequirementService.validateTrackingLinkage() 校验"需求条目剩余名额"时使用——
     * 原来那里是"先查一遍已占用数，再跟 videoCount 比较"，中间没有锁，两个人几乎同时给同一个
     * 需求条目的最后一个名额各建一条红人合作跟踪记录时，可能都在对方提交之前读到"还有名额"、
     * 都通过校验，最终把这个需求条目超额占用，且没有任何数据库约束能拦下来。加锁后，第二个
     * 并发请求会阻塞到第一个请求所在事务提交/回滚为止，再重新读到"名额已经被占满"从而正确报错。
     * 只用在这一个"真正落库前"的校验点，不用在别处（比如 validateBatchLinkage 那个批量预检，
     * 那个只是给个更友好的提前报错，不是权威判断，不需要也不该在预检阶段就持锁）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM InfluencerRequirement r WHERE r.internalRequirementNo = :no AND r.isDeleted = false")
    Optional<InfluencerRequirement> findByInternalRequirementNoAndIsDeletedFalseForUpdate(@Param("no") String internalRequirementNo);

    /** "重新计算需求完成时间"善后用（2026-08 新增） */
    List<InfluencerRequirement> findByIsDeletedFalse();

    /** 红人结款"选择涉及的红人视频项目"按需求编号批量取需求信息用（避免逐条查库） */
    List<InfluencerRequirement> findByInternalRequirementNoInAndIsDeletedFalse(List<String> internalRequirementNos);

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
           "AND (:internalRequirementNo IS NULL OR r.internalRequirementNo LIKE %:internalRequirementNo%) " +
           "AND (:completedMonth IS NULL OR FUNCTION('to_char', r.completedAt, 'YYYYMM') = :completedMonth)")
    Page<InfluencerRequirement> findByFilters(
            @Param("brandId") Long brandId,
            @Param("teamId") Long teamId,
            @Param("accountName") String accountName,
            @Param("requirementMonth") String requirementMonth,
            @Param("internalRequirementNo") String internalRequirementNo,
            @Param("completedMonth") String completedMonth,
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
           "AND (:internalRequirementNo IS NULL OR r.internalRequirementNo LIKE %:internalRequirementNo%) " +
           "AND (:completedMonth IS NULL OR FUNCTION('to_char', r.completedAt, 'YYYYMM') = :completedMonth)")
    List<InfluencerRequirement> findByFiltersNoPaging(
            @Param("brandId") Long brandId,
            @Param("teamId") Long teamId,
            @Param("accountName") String accountName,
            @Param("requirementMonth") String requirementMonth,
            @Param("internalRequirementNo") String internalRequirementNo,
            @Param("completedMonth") String completedMonth,
            Sort sort);

    /**
     * 与 findByFiltersNoPaging 完全相同的筛选条件，但只查"判断是否未完成"需要的3个轻量字段
     * （id、internalRequirementNo、totalItemCount），不加载完整实体（notes 等大字段、
     * influencer 关联）。2026-07-28 起 pageIncomplete 改成先用这个轻量投影在内存里筛出
     * 未完成的 id 并分页，最后只对"当前页"这一小撮 id 才去查完整实体——避免每次翻页/筛选
     * 都把命中的需求全部实体查一遍。
     */
    @Query("SELECT r.id, r.internalRequirementNo, r.totalItemCount FROM InfluencerRequirement r " +
           "WHERE r.isDeleted = false " +
           "AND (:brandId IS NULL OR r.brandId = :brandId) " +
           "AND (:teamId IS NULL OR r.teamId = :teamId) " +
           "AND (:accountName IS NULL OR r.influencer.accountName LIKE %:accountName%) " +
           "AND (:requirementMonth IS NULL OR r.requirementMonth = :requirementMonth) " +
           "AND (:internalRequirementNo IS NULL OR r.internalRequirementNo LIKE %:internalRequirementNo%) " +
           "AND (:completedMonth IS NULL OR FUNCTION('to_char', r.completedAt, 'YYYYMM') = :completedMonth)")
    List<Object[]> findLiteProjectionByFilters(
            @Param("brandId") Long brandId,
            @Param("teamId") Long teamId,
            @Param("accountName") String accountName,
            @Param("requirementMonth") String requirementMonth,
            @Param("internalRequirementNo") String internalRequirementNo,
            @Param("completedMonth") String completedMonth,
            Sort sort);

    /** "关联红人需求"选择器第一步：某个红人名下的所有未删除需求（前端再按"需求完成进度"过滤掉已满的） */
    List<InfluencerRequirement> findByInfluencerIdAndIsDeletedFalse(Long influencerId);

    /** "Invoice逾期"提醒批次用：已完成（completedAt有值）但还没上传invoice的需求 */
    List<InfluencerRequirement> findByIsDeletedFalseAndCompletedAtIsNotNullAndInvoiceLinkIsNull();

    /** "合同上传逾期"提醒批次用：已完成（completedAt有值）但还没上传合同的需求 */
    List<InfluencerRequirement> findByIsDeletedFalseAndCompletedAtIsNotNullAndContractLinkIsNull();
}
