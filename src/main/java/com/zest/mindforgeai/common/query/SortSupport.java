package com.zest.mindforgeai.common.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Map;
import java.util.TreeSet;

/**
 * 分页排序支持。
 *
 * <p><b>为什么要白名单</b>：MyBatis-Plus 的排序本质上是
 * <b>把字段名拼进 SQL</b> 的（{@code OrderItem} / {@code orderBy(String)} 都是字符串拼接）。
 * 直接把前端传来的字段名拼进去就是 SQL 注入。所以每个分页接口都必须声明
 * 「允许按哪些字段排」，白名单之外的一律 40001。
 *
 * <p><b>为什么自定义 XML 分页里不能自己写 ORDER BY</b>：
 * MP 的 {@code page.addOrder()} 是<b>追加</b>在已有 ORDER BY 后面的 ——
 * 排在后面的完全不生效（第一个字段已经决定了顺序）。
 * 所以那几处要把 XML 里自带的 ORDER BY 去掉，统一由这里控制。
 *
 * <p><b>为什么没有排序参数时也要给一个默认排序</b>：
 * 没有 ORDER BY 时 MySQL 不保证返回顺序，翻页会出现「同一条记录在第 1 页和第 2 页都出现」。
 * 所以每个接口都必须有一个稳定的兜底排序（一般是主键）。
 *
 * @author crx
 * @since 2026-10-10
 */
public final class SortSupport {

    /** 升序。不写 / 写别的都当降序 —— 宽松处理，不给前端添麻烦。 */
    public static final String ASC = "asc";

    private SortSupport() {
    }

    public static boolean isAsc(String sortOrder) {
        return sortOrder != null && ASC.equalsIgnoreCase(sortOrder.trim());
    }

    /**
     * LambdaWrapper 版：把排序加到 wrapper 上。
     *
     * @param sortable    允许排序的字段（API 字段名 → 实体属性）。白名单就是这个 Map
     * @param fallback    没传 sortBy 时的兜底排序字段
     * @param fallbackAsc 兜底排序的方向（一般是降序；分块那种天然有顺序的要升序）
     */
    public static <T> void applyTo(LambdaQueryWrapper<T> wrapper,
                                   String sortBy,
                                   String sortOrder,
                                   Map<String, SFunction<T, ?>> sortable,
                                   SFunction<T, ?> fallback,
                                   boolean fallbackAsc) {
        if (!StringUtils.hasText(sortBy)) {
            wrapper.orderBy(true, fallbackAsc, fallback);
            return;
        }
        SFunction<T, ?> column = sortable.get(sortBy.trim());
        if (column == null) {
            throw unsupported(sortBy, sortable.keySet());
        }
        wrapper.orderBy(true, isAsc(sortOrder), column);
    }

    /**
     * 自定义 XML 分页版：把排序加到 {@code Page} 上。
     *
     * <p>⚠️ 对应的 SQL 里<b>不能</b>再写 ORDER BY —— 会被追加在后面导致不生效。
     *
     * @param sortable API 字段名 → 数据库列名
     */
    public static <T> void applyTo(Page<T> page,
                                   String sortBy,
                                   String sortOrder,
                                   Map<String, String> sortable,
                                   String fallbackColumn,
                                   boolean fallbackAsc) {
        if (!StringUtils.hasText(sortBy)) {
            page.addOrder(fallbackAsc ? OrderItem.asc(fallbackColumn) : OrderItem.desc(fallbackColumn));
            return;
        }
        String column = sortable.get(sortBy.trim());
        if (column == null) {
            throw unsupported(sortBy, sortable.keySet());
        }
        boolean asc = isAsc(sortOrder);
        page.addOrder(asc ? OrderItem.asc(column) : OrderItem.desc(column));
    }

    /**
     * 报错时把可选字段列出来 —— 前端调错了能自己看出来，不用来问。
     *
     * <p>⚠️ 白名单里的字段名是我们自己写死的，不是用户输入，所以拼进提示信息是安全的。
     */
    private static BizException unsupported(String sortBy, Collection<String> allowed) {
        return new BizException(ErrorCode.PARAM_INVALID,
                "不支持按「" + sortBy + "」排序，可选：" + String.join("、", new TreeSet<>(allowed)));
    }

    /**
     * 转义 MySQL LIKE 查询中的通配符（\、%、_），防止用户输入 "%" 或 "_" 触发全表匹配。
     */
    public static String escapeLike(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return keyword;
        }
        return keyword.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
