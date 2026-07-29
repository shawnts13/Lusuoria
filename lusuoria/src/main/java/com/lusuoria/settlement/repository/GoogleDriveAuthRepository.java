package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.GoogleDriveAuth;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GoogleDriveAuthRepository extends JpaRepository<GoogleDriveAuth, Long> {
    Optional<GoogleDriveAuth> findFirstByIsDeletedFalseOrderByIdDesc();
}
