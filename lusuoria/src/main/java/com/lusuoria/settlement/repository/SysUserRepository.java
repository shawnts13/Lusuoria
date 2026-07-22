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

    /** 一个员工只能绑定一个账号：新建/编辑账号关联员工时用来判断这个员工是不是已经被别的账号占用 */
    Optional<SysUser> findByEmployeeIdAndIsDeletedFalse(Long employeeId);
}
