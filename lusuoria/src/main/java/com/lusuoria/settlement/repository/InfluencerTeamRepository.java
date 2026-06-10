package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.InfluencerTeam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InfluencerTeamRepository extends JpaRepository<InfluencerTeam, Long> {

    List<InfluencerTeam> findByIsDeletedFalseOrderByNameAsc();

    Optional<InfluencerTeam> findByNameAndIsDeletedFalse(String name);

    boolean existsByNameAndIsDeletedFalse(String name);
}
