package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SysUserRepository extends JpaRepository<SysUser, Long> {

    Optional<SysUser> findByUsernameAndIsDeletedFalse(String username);

    Optional<SysUser> findByIdAndIsDeletedFalse(Long id);

    boolean existsByUsernameAndIsDeletedFalse(String username);
}
