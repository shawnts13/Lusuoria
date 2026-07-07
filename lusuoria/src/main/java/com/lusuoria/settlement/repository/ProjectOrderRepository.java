package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.ProjectOrder;
import com.lusuoria.settlement.enums.ClientStatus;
import com.lusuoria.settlement.enums.InternalSettlementStatus;
import com.lusuoria.settlement.enums.ProjectType;
import com.lusuoria.settlement.enums.VideoType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectOrderRepository extends JpaRepository<ProjectOrder, Long> {

    /** 批量重算用：取出所有未删除的订单 */
    List<ProjectOrder> findByIsDeletedFalse();

    Optional<ProjectOrder> findByInternalProjectNoAndIsDeletedFalse(String internalProjectNo);

    /** 检查内部项目编号是否已存在（含软删除，因唯一约束覆盖全表） */
    boolean existsByInternalProjectNo(String internalProjectNo);

    Optional<ProjectOrder> findByIdAndIsDeletedFalse(Long id);

    /** 按甲方订单号查询未删除的项目订单（合作跟踪联动用） */
    List<ProjectOrder> findByClientOrderNoAndIsDeletedFalse(String clientOrderNo);

    /** 是否存在指定甲方订单号的未删除项目订单（防止重复生成） */
    boolean existsByClientOrderNoAndIsDeletedFalse(String clientOrderNo);

    /**
     * 导入去重：根据「甲方订单号 + 品牌方 + 项目月份」判断是否已存在
     * 甲方订单号不为空时用此方法
     */
    @Query("SELECT COUNT(p) > 0 FROM ProjectOrder p " +
           "WHERE p.isDeleted = false " +
           "AND p.clientOrderNo = :clientOrderNo " +
           "AND p.brand.id = :brandId " +
           "AND p.projectMonth = :projectMonth")
    boolean existsByClientOrderNoAndBrandAndMonth(
            @Param("clientOrderNo") String clientOrderNo,
            @Param("brandId") Long brandId,
            @Param("projectMonth") String projectMonth);

    /**
     * 导入去重：甲方订单号为空时，根据「红人账号 + 品牌方 + 项目月份 + 合作内容」判断
     */
    @Query("SELECT COUNT(p) > 0 FROM ProjectOrder p " +
           "WHERE p.isDeleted = false " +
           "AND p.brand.id = :brandId " +
           "AND p.projectMonth = :projectMonth " +
           "AND (:influencerId IS NULL OR p.influencer.id = :influencerId) " +
           "AND (:cooperationContent IS NULL OR p.cooperationContent = :cooperationContent)")
    boolean existsByBrandMonthInfluencerContent(
            @Param("brandId") Long brandId,
            @Param("projectMonth") String projectMonth,
            @Param("influencerId") Long influencerId,
            @Param("cooperationContent") String cooperationContent);

    @EntityGraph(attributePaths = {"influencer"})
    @Query("SELECT p FROM ProjectOrder p " +
           "WHERE p.isDeleted = false " +
           "AND (:brandId IS NULL OR p.brand.id = :brandId) " +
           "AND (:projectMonth IS NULL OR p.projectMonth = :projectMonth) " +
           "AND (:videoPublishMonth IS NULL OR FUNCTION('to_char', p.videoPublishDate, 'YYYYMM') = :videoPublishMonth) " +
           "AND (:projectType IS NULL OR p.projectType = :projectType) " +
           "AND (:clientStatus IS NULL OR p.clientStatus = :clientStatus) " +
           "AND (:internalStatus IS NULL OR p.internalStatus = :internalStatus) " +
           "AND (:videoType IS NULL OR p.videoType = :videoType) " +
           "AND (:internalProjectNo IS NULL OR p.internalProjectNo LIKE %:internalProjectNo%) " +
           "AND (:influencerId IS NULL OR p.influencer.id = :influencerId) " +
           "AND (:accountName IS NULL OR p.influencer.accountName LIKE %:accountName%) " +
           "AND (:projectManagerId IS NULL OR p.projectManager.id = :projectManagerId) " +
           "AND (:keyword IS NULL OR p.internalProjectNo LIKE %:keyword% OR p.clientOrderNo LIKE %:keyword%) " +
           "ORDER BY p.createdAt DESC")
    Page<ProjectOrder> findByFilters(
            @Param("brandId") Long brandId,
            @Param("projectMonth") String projectMonth,
            @Param("videoPublishMonth") String videoPublishMonth,
            @Param("projectType") ProjectType projectType,
            @Param("clientStatus") ClientStatus clientStatus,
            @Param("internalStatus") InternalSettlementStatus internalStatus,
            @Param("videoType") VideoType videoType,
            @Param("internalProjectNo") String internalProjectNo,
            @Param("influencerId") Long influencerId,
            @Param("accountName") String accountName,
            @Param("projectManagerId") Long projectManagerId,
            @Param("keyword") String keyword,
            Pageable pageable);

    @EntityGraph(attributePaths = {"influencer", "brand", "projectManager"})
    @Query("SELECT p FROM ProjectOrder p " +
           "WHERE p.isDeleted = false " +
           "AND p.projectMonth = :month " +
           "ORDER BY p.brand.name ASC, p.internalProjectNo ASC")
    List<ProjectOrder> findByProjectMonth(@Param("month") String month);

    /**
     * 按"项目视频发布时间"所在月份查询（数据看板"视频项目数量"专用）。
     * 用 to_char 转字符串比较，不用 Date 类型参数做区间比较——
     * 之前踩过 Date 类型可空参数在这个连接池下报
     * "could not determine data type of parameter" 的坑，这里直接绕开。
     */
    @Query("SELECT p FROM ProjectOrder p WHERE p.isDeleted = false " +
           "AND FUNCTION('to_char', p.videoPublishDate, 'YYYYMM') = :month")
    List<ProjectOrder> findByVideoPublishMonth(@Param("month") String month);

    @Query("SELECT p FROM ProjectOrder p WHERE p.isDeleted = false " +
           "AND FUNCTION('to_char', p.videoPublishDate, 'YYYYMM') BETWEEN :startMonth AND :endMonth")
    List<ProjectOrder> findByVideoPublishMonthBetween(
            @Param("startMonth") String startMonth, @Param("endMonth") String endMonth);

    /**
     * 内部执行成本计算专用：某执行人员在某"项目视频发布"月份下，已经赋值过内部执行成本的
     * 旧素材重发订单，按 id 升序排列（用 id 顺序近似代表实际处理顺序，用来判断第几笔、
     * 分档、以及当月101条以上部分的累计封顶金额）。只统计"已经赋值"的，没赋值的不算，
     * 这个规则很重要——没赋值的可能是还没走到这一步、或者压根不需要付费的订单。
     */
    @Query("SELECT p FROM ProjectOrder p WHERE p.isDeleted = false " +
           "AND p.executorId = :executorId AND p.videoType = com.lusuoria.settlement.enums.VideoType.OLD_MATERIAL_REPOST " +
           "AND p.internalExecutionCost IS NOT NULL " +
           "AND FUNCTION('to_char', p.videoPublishDate, 'YYYYMM') = :month " +
           "ORDER BY p.id ASC")
    List<ProjectOrder> findCostedOldMaterialOrdersForExecutor(
            @Param("executorId") Long executorId, @Param("month") String month);

    /** 数据看板用：按月份范围（闭区间，字符串比较，格式 yyyyMM 可直接比较）查询 */
    @EntityGraph(attributePaths = {"influencer", "brand", "projectManager"})
    @Query("SELECT p FROM ProjectOrder p " +
           "WHERE p.isDeleted = false " +
           "AND p.projectMonth >= :startMonth AND p.projectMonth <= :endMonth")
    List<ProjectOrder> findByProjectMonthBetween(
            @Param("startMonth") String startMonth,
            @Param("endMonth") String endMonth);

    @Query("SELECT COUNT(p) FROM ProjectOrder p " +
           "WHERE p.isDeleted = false " +
           "AND p.brand.id = :brandId " +
           "AND p.projectMonth = :month")
    long countByBrandAndMonth(@Param("brandId") Long brandId, @Param("month") String month);

    @Query("SELECT COUNT(p) FROM ProjectOrder p " +
           "WHERE p.isDeleted = false " +
           "AND p.influencer.id = :influencerId")
    long countByInfluencer(@Param("influencerId") Long influencerId);

    /**
     * 批量查询多个红人的项目数量，一次 SQL 搞定
     * 返回格式：[[influencerId, count], ...]
     */
    @Query("SELECT p.influencer.id, COUNT(p) FROM ProjectOrder p " +
           "WHERE p.isDeleted = false " +
           "AND p.influencer.id IN :influencerIds " +
           "GROUP BY p.influencer.id")
    List<Object[]> countByInfluencerIds(@Param("influencerIds") List<Long> influencerIds);

    @Query("SELECT p FROM ProjectOrder p " +
           "WHERE p.isDeleted = false " +
           "AND p.projectManager.id = :managerId " +
           "AND p.projectMonth = :month " +
           "ORDER BY p.internalProjectNo ASC")
    List<ProjectOrder> findByManagerAndMonth(@Param("managerId") Long managerId, @Param("month") String month);
}
