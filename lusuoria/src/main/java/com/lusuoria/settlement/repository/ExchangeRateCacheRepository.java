package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.ExchangeRateCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ExchangeRateCacheRepository extends JpaRepository<ExchangeRateCache, Long> {

    Optional<ExchangeRateCache> findByYearMonth(String yearMonth);
}
