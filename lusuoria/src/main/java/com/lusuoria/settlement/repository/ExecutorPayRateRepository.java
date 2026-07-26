package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.ExecutorPayRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExecutorPayRateRepository extends JpaRepository<ExecutorPayRate, Long> {

    Optional<ExecutorPayRate> findByManagerIdAndExecutorIdAndIsDeletedFalse(Long managerId, Long executorId);

    List<ExecutorPayRate> findByManagerIdAndIsDeletedFalse(Long managerId);
}
