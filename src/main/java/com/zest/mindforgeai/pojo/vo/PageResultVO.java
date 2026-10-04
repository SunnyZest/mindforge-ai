package com.zest.mindforgeai.pojo.vo;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 通用分页结果，对齐文档 §8.5。
 *
 * @param <T> 列表项类型
 * @author crx
 * @since 2026-10-04
 */
@Schema(description = "分页结果")
@Data
public class PageResultVO<T> {

    @Schema(description = "当前页码，从 1 开始")
    private long page;

    @Schema(description = "每页条数")
    private long pageSize;

    @Schema(description = "总条数")
    private long total;

    @Schema(description = "当前页数据")
    private List<T> records;

    /** 用 MyBatis-Plus 的分页对象 + 转换后的 records 组装 */
    public static <T> PageResultVO<T> of(IPage<?> page, List<T> records) {
        PageResultVO<T> vo = new PageResultVO<>();
        vo.setPage(page.getCurrent());
        vo.setPageSize(page.getSize());
        vo.setTotal(page.getTotal());
        vo.setRecords(records);
        return vo;
    }

    /** 空结果（例如按角色筛选时没有命中任何用户） */
    public static <T> PageResultVO<T> empty(long page, long pageSize) {
        PageResultVO<T> vo = new PageResultVO<>();
        vo.setPage(page);
        vo.setPageSize(pageSize);
        vo.setTotal(0);
        vo.setRecords(List.of());
        return vo;
    }
}
