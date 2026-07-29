package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.DbBackupAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DbBackupAlertRepository extends JpaRepository<DbBackupAlert, Long> {
    Optional<DbBackupAlert> findFirstByIsDeletedFalseOrderByIdDesc();
}
