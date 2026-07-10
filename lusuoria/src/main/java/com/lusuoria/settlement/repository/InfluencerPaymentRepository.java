package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.InfluencerPayment;
import com.lusuoria.settlement.enums.InfluencerPaymentStatus;
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
public interface InfluencerPaymentRepository extends JpaRepository<InfluencerPayment, Long> {

    Optional<InfluencerPayment> findByIdAndIsDeletedFalse(Long id);

    /**
     * ids 非空时按"涉及了这个团队"过滤（Controller 先查
     * InfluencerPaymentTeamRepository.findPaymentIdsByTeamId 拿到符合条件的结款记录 id 列表，
     * 再传进来），因为"涉及哪些团队"现在是关联表，不是这张表自己的列，没法直接 JOIN 简单表达。
     */
    @EntityGraph(attributePaths = {"brand"})
    @Query("SELECT ip FROM InfluencerPayment ip " +
           "WHERE ip.isDeleted = false " +
           "AND (:settlementMonth IS NULL OR ip.settlementMonth = :settlementMonth) " +
           "AND (:brandId IS NULL OR ip.brandId = :brandId) " +
           "AND (:filterByTeam = false OR ip.id IN :matchingIds) " +
           "AND (:paymentStatus IS NULL OR ip.paymentStatus = :paymentStatus) " +
           "ORDER BY ip.settlementMonth DESC, ip.paymentNo ASC")
    Page<InfluencerPayment> findByFilters(
            @Param("settlementMonth") String settlementMonth,
            @Param("brandId") Long brandId,
            @Param("filterByTeam") boolean filterByTeam,
            @Param("matchingIds") List<Long> matchingIds,
            @Param("paymentStatus") InfluencerPaymentStatus paymentStatus,
            Pageable pageable);

    @EntityGraph(attributePaths = {"brand"})
    List<InfluencerPayment> findBySettlementMonthAndIsDeletedFalse(String month);

    @EntityGraph(attributePaths = {"brand"})
    List<InfluencerPayment> findByIsDeletedFalse();

    /** 结款单号分配用：统计某"品牌-结算月份-"前缀已用了多少个（作为序号起点） */
    @Query("SELECT COUNT(ip) FROM InfluencerPayment ip WHERE ip.paymentNo LIKE :prefixPattern")
    long countByPaymentNoPrefix(@Param("prefixPattern") String prefixPattern);
}
