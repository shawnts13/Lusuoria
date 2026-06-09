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

    /** 按红人团队名称模糊搜索（teamNames 字段逗号分隔存储） */
    @Query("SELECT i FROM Influencer i WHERE i.isDeleted = false AND i.teamNames LIKE %:teamName%")
    List<Influencer> findByTeamNameContaining(@Param("teamName") String teamName);

    /** 筛选查询（支持红人团队、平台过滤） */
    @Query("SELECT i FROM Influencer i " +
           "WHERE i.isDeleted = false " +
           "AND (:influencerType IS NULL OR i.influencerType = :influencerType) " +
           "AND (:platform IS NULL OR i.platform = :platform) " +
           "AND (:countryMarket IS NULL OR i.countryMarket = :countryMarket) " +
           "AND (:teamName IS NULL OR i.teamNames LIKE %:teamName%) " +
           "AND (:keyword IS NULL OR i.accountName LIKE %:keyword% OR i.teamNames LIKE %:keyword%) " +
           "ORDER BY i.accountName ASC")
    Page<Influencer> findByFilters(
            @Param("influencerType") ProjectType influencerType,
            @Param("platform") String platform,
            @Param("countryMarket") String countryMarket,
            @Param("teamName") String teamName,
            @Param("keyword") String keyword,
            Pageable pageable);
}
