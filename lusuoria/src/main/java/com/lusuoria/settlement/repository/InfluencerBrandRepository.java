package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.InfluencerBrand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InfluencerBrandRepository extends JpaRepository<InfluencerBrand, Long> {

    List<InfluencerBrand> findByInfluencerId(Long influencerId);

    List<InfluencerBrand> findByBrandId(Long brandId);

    @Modifying
    @Query("DELETE FROM InfluencerBrand ib WHERE ib.influencerId = :influencerId")
    void deleteByInfluencerId(@Param("influencerId") Long influencerId);

    /** 批量查询多个红人关联的品牌方关系（红人列表展示用，避免逐条 N+1 查询） */
    @Query("SELECT ib FROM InfluencerBrand ib WHERE ib.influencerId IN :influencerIds AND ib.isDeleted = false")
    List<InfluencerBrand> findByInfluencerIdIn(@Param("influencerIds") List<Long> influencerIds);

    boolean existsByInfluencerIdAndBrandId(Long influencerId, Long brandId);
}
