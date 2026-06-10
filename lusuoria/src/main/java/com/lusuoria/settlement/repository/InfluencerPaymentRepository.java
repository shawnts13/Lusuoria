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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface InfluencerPaymentRepository extends JpaRepository<InfluencerPayment, Long> {

    Optional<InfluencerPayment> findByIdAndIsDeletedFalse(Long id);

    @EntityGraph(attributePaths = {"influencer", "projectOrder"})
    @Query("SELECT ip FROM InfluencerPayment ip " +
           "WHERE ip.isDeleted = false " +
           "AND (:settlementMonth IS NULL OR ip.settlementMonth = :settlementMonth) " +
           "AND (:influencerId IS NULL OR ip.influencer.id = :influencerId) " +
           "AND (:paymentStatus IS NULL OR ip.paymentStatus = :paymentStatus) " +
           "ORDER BY ip.settlementMonth DESC, ip.influencer.accountName ASC")
    Page<InfluencerPayment> findByFilters(
            @Param("settlementMonth") String settlementMonth,
            @Param("influencerId") Long influencerId,
            @Param("paymentStatus") InfluencerPaymentStatus paymentStatus,
            Pageable pageable);

    List<InfluencerPayment> findBySettlementMonthAndIsDeletedFalse(String month);

    @Query("SELECT SUM(ip.payableAmount) FROM InfluencerPayment ip " +
           "WHERE ip.isDeleted = false " +
           "AND ip.settlementMonth = :month " +
           "AND ip.influencer.id = :influencerId")
    BigDecimal sumPayableByMonthAndInfluencer(@Param("month") String month,
                                              @Param("influencerId") Long influencerId);

    /**
     * 导入去重：结算月份 + 红人 + 合作内容
     */
    @Query("SELECT COUNT(ip) > 0 FROM InfluencerPayment ip " +
           "WHERE ip.isDeleted = false " +
           "AND ip.settlementMonth = :month " +
           "AND ip.influencer.id = :influencerId " +
           "AND (:cooperationContent IS NULL OR ip.cooperationContent = :cooperationContent)")
    boolean existsByMonthInfluencerContent(
            @Param("month") String month,
            @Param("influencerId") Long influencerId,
            @Param("cooperationContent") String cooperationContent);
}
