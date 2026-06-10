package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.ProjectOrder;
import com.lusuoria.settlement.enums.ClientStatus;
import com.lusuoria.settlement.enums.InternalSettlementStatus;
import com.lusuoria.settlement.enums.ProjectType;
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

    Optional<ProjectOrder> findByInternalProjectNoAndIsDeletedFalse(String internalProjectNo);

    Optional<ProjectOrder> findByIdAndIsDeletedFalse(Long id);

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
           "AND (:projectType IS NULL OR p.projectType = :projectType) " +
           "AND (:clientStatus IS NULL OR p.clientStatus = :clientStatus) " +
           "AND (:internalStatus IS NULL OR p.internalStatus = :internalStatus) " +
           "AND (:influencerId IS NULL OR p.influencer.id = :influencerId) " +
           "AND (:keyword IS NULL OR p.internalProjectNo LIKE %:keyword% OR p.clientOrderNo LIKE %:keyword%) " +
           "ORDER BY p.createdAt DESC")
    Page<ProjectOrder> findByFilters(
            @Param("brandId") Long brandId,
            @Param("projectMonth") String projectMonth,
            @Param("projectType") ProjectType projectType,
            @Param("clientStatus") ClientStatus clientStatus,
            @Param("internalStatus") InternalSettlementStatus internalStatus,
            @Param("influencerId") Long influencerId,
            @Param("keyword") String keyword,
            Pageable pageable);

    @EntityGraph(attributePaths = {"influencer"})
    @Query("SELECT p FROM ProjectOrder p " +
           "WHERE p.isDeleted = false " +
           "AND p.projectMonth = :month " +
           "ORDER BY p.brand.name ASC, p.internalProjectNo ASC")
    List<ProjectOrder> findByProjectMonth(@Param("month") String month);

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
