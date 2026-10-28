package com.zest.mindforgeai.utils;

import com.zest.mindforgeai.pojo.entity.Document;

/**
 * 文档处理状态的取值与聚合。
 * <p>
 * 库里 {@code document} 表把处理过程拆成三个独立状态字段
 * （parse_status / chunk_status / vector_status），但对外只暴露一个「总状态」。
 * 这个聚合规则<b>只在这里实现一次</b> —— 用户端文档列表、管理端文档列表
 * 都必须用同一套口径，否则两边显示的状态会对不上，
 * 而这种不一致排查起来非常费劲（"为什么用户看到失败、管理员看到成功"）。
 *
 * @author crx
 * @since 2026-10-08
 */
public final class DocumentStatusUtils {

    /** 待处理（初始状态，也用于重试后的重置） */
    public static final String PENDING = "PENDING";

    /** 处理成功 */
    public static final String SUCCESS = "SUCCESS";

    /** 处理失败 */
    public static final String FAILED = "FAILED";

    /** 正在解析 */
    public static final String PARSING = "PARSING";

    /** 聚合后的最终态：三个阶段全部成功。 */
    public static final String COMPLETED = "COMPLETED";

    private DocumentStatusUtils() {
    }

    /**
     * 把三个阶段状态聚合成一个对外状态。
     * <p>
     * 判定顺序是有讲究的：
     * <ol>
     *   <li>任一阶段 FAILED → 整体 FAILED（失败优先级最高，不能被后面的 SUCCESS 盖掉）</li>
     *   <li>向量化完成 → COMPLETED</li>
     *   <li>分块完成 → CHUNKED</li>
     *   <li>解析完成 → PARSED</li>
     *   <li>正在解析 → PARSING</li>
     *   <li>否则 → UPLOADED</li>
     * </ol>
     */
    public static String aggregate(Document doc) {
        return aggregate(doc.getParseStatus(), doc.getChunkStatus(), doc.getVectorStatus());
    }

    /** 同上，直接传三个状态值（给不走实体的场景用） */
    public static String aggregate(String parseStatus, String chunkStatus, String vectorStatus) {
        if (FAILED.equals(parseStatus) || FAILED.equals(chunkStatus) || FAILED.equals(vectorStatus)) {
            return FAILED;
        }
        if (SUCCESS.equals(vectorStatus)) {
            return COMPLETED;
        }
        if (SUCCESS.equals(chunkStatus)) {
            return "CHUNKED";
        }
        if (SUCCESS.equals(parseStatus)) {
            return "PARSED";
        }
        if (PARSING.equals(parseStatus)) {
            return "PARSING";
        }
        return "UPLOADED";
    }
}
