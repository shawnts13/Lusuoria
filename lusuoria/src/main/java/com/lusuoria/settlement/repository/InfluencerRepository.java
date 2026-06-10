package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.Influencer;
import com.lusuoria.settlement.enums.ProjectType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InfluencerRepository extends JpaRepository<Influencer, Long> {

    List<Influencer> findByIsDeletedFalseOrderByAccountNameAsc();

    Optional<Influencer> findByIdAndIsDeletedFalse(Long id);

    Optional<Influencer> findByAccountNameAndIsDeletedFalse(String accountName);

    List<Influencer> findByInfluencerTypeAndIsDeletedFalse(ProjectType type);

    @Query("SELECT i FROM Influencer i " +
           "WHERE i.isDeleted = false " +
           "AND (:influencerType IS NULL OR i.influencerType = :influencerType) " +
           "AND (:platform IS NULL OR i.platform LIKE %:platform%) " +
           "AND (:countryMarket IS NULL OR i.countryMarket = :countryMarket) " +
           "AND (:brandId IS NULL OR i.brandId = :brandId) " +
           "AND (:teamName IS NULL OR i.teamName LIKE %:teamName%) " +
           "AND (:followerMin IS NULL OR i.followerCount >= :followerMin) " +
           "AND (:followerMax IS NULL OR i.followerCount <= :followerMax) " +
           "AND (:keyword IS NULL OR i.accountName LIKE %:keyword% OR i.teamName LIKE %:keyword%)")
    Page<Influencer> findByFilters(
            @Param("influencerType") ProjectType influencerType,
            @Param("platform") String platform,
            @Param("countryMarket") String countryMarket,
            @Param("brandId") Long brandId,
            @Param("teamName") String teamName,
            @Param("followerMin") Long followerMin,
            @Param("followerMax") Long followerMax,
            @Param("keyword") String keyword,
            Pageable pageable);
}
