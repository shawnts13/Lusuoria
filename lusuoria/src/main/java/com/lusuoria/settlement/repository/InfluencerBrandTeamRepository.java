package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.InfluencerBrandTeam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InfluencerBrandTeamRepository extends JpaRepository<InfluencerBrandTeam, Long> {

    List<InfluencerBrandTeam> findByInfluencerId(Long influencerId);

    /** 批量查询多个红人的关联关系（红人列表展示用，避免逐条 N+1 查询） */
    @Query("SELECT ibt FROM InfluencerBrandTeam ibt WHERE ibt.influencerId IN :influencerIds AND ibt.isDeleted = false")
    List<InfluencerBrandTeam> findByInfluencerIdIn(@Param("influencerIds") List<Long> influencerIds);

    /** 某红人在某品牌方下的所有关联（可能 0/1/多条，用于判断团队怎么选） */
    @Query("SELECT ibt FROM InfluencerBrandTeam ibt " +
           "WHERE ibt.influencerId = :influencerId AND ibt.brandId = :brandId AND ibt.isDeleted = false")
    List<InfluencerBrandTeam> findByInfluencerIdAndBrandId(
            @Param("influencerId") Long influencerId, @Param("brandId") Long brandId);

    boolean existsByInfluencerIdAndBrandId(Long influencerId, Long brandId);
}
