package com.lusuoria.settlement.repository;

import com.lusuoria.settlement.entity.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SysUserRepository extends JpaRepository<SysUser, Long> {

    /** 账号管理列表页默认排序：按用户名字母顺序 */
    List<SysUser> findByIsDeletedFalseOrderByUsernameAsc();

    Optional<SysUser> findByUsernameAndIsDeletedFalse(String username);

    Optional<SysUser> findByIdAndIsDeletedFalse(Long id);

    boolean existsByUsernameAndIsDeletedFalse(String username);

    /** 不限 isDeleted 精确匹配，供新建账号时复活同用户名的已软删除记录用（username 数据库
     * 层面有唯一约束，不认软删除，见 UserController.create()） */
    Optional<SysUser> findByUsername(String username);

    /** 一个员工只能绑定一个账号：新建/编辑账号关联员工时用来判断这个员工是不是已经被别的账号占用 */
    Optional<SysUser> findByEmployeeIdAndIsDeletedFalse(Long employeeId);
}
