package com.lusuoria.settlement.util;

import java.util.Collections;
import java.util.List;

/**
 * "红人合作跟踪"列表筛选支持多选（2026-08-21，视频项目进度/红人结款进度/项目视频类型/
 * 项目负责人这4个字段从单选改多选）之后，CollaborationTrackingRepository.findByFilters()/
 * findLitePriorityProjectionByFilters() 原本 "(:param IS NULL OR field IN :param)" 这种
 * 写法，在这个项目实际跑的 Hibernate 版本下会触发一个 HQL classic 解析器的已知 bug
 * （org.hibernate.hql.internal.ast.QuerySyntaxException: unexpected AST node: {vector}）——
 * 同一个具名参数既拿来做 IS NULL 判断、又拿来做集合类型的 IN 判断，解析器会把它误判成
 * "vector"（元组）节点解析出错。这不是本项目业务代码的 bug，是这个仓库之前在
 * JpaSort.unsafe 链式排序上就踩过的同一类 Hibernate 解析器脆弱问题（本地没有数据库环境，
 * 没法在改动前验证这类写法在这个 Hibernate 版本下到底安不安全，只能线上报错后再回退/改写）。
 *
 * 规避方式：JPQL 里不再对集合参数本身做 IS NULL 判断，改成额外传一个独立的 "xxxActive"
 * 布尔标量参数专门控制"这个筛选到底生不生效"（"(:xxxActive = false OR field IN :param)"），
 * 集合参数本身任何时候都保证非空——调用方筛选条件为空（不筛）时，用这里的 orPlaceholder()
 * 塞一个占位值进去。占位值内容不影响结果：xxxActive=false 时这段 IN 判断在逻辑上根本不会
 * 被"命中"，但 SQL 语法层面 PostgreSQL 对 "IN ()" 空列表是直接报语法错误的，不能真的传空
 * 集合，所以必须塞至少一个占位元素撑住语法。
 */
public class CollaborationFilterUtil {

    private CollaborationFilterUtil() {}

    /** 这个多选筛选是不是真的选了值（null 或空列表都算"没筛"） */
    public static boolean isActive(List<?> list) {
        return list != null && !list.isEmpty();
    }

    /** 没筛时返回只含一个占位值的列表（撑住 IN 子句的 SQL 语法），选了值就原样返回 */
    public static <E> List<E> orPlaceholder(List<E> list, E placeholder) {
        return isActive(list) ? list : Collections.singletonList(placeholder);
    }
}
