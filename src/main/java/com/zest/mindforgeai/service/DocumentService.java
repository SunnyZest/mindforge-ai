package com.zest.mindforgeai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zest.mindforgeai.pojo.entity.Document;
import com.zest.mindforgeai.pojo.vo.DocumentStatusVO;
import com.zest.mindforgeai.pojo.vo.DocumentVO;
import com.zest.mindforgeai.pojo.vo.PageResultVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档服务（用户端）。
 * <p>
 * 本轮只实现<b>上传与下载</b> —— 解析/分块/向量化那条流水线要等 Python AI 服务，
 * 上传后文档停在 {@code PENDING} 状态。
 *
 * @author crx
 * @since 2026-10-05
 */
public interface DocumentService extends IService<Document> {

    /**
     * 上传文档到指定知识库。
     * <p>
     * 内部流程：校验知识库归属 → 校验文件类型 → 上传对象存储 → 落 {@code sys_file} → 落 {@code document}。
     *
     * @param description 文档描述，当前未持久化（document 表无对应字段），仅作审计明细
     */
    DocumentVO upload(Long knowledgeBaseId, MultipartFile file, String description);

    /** 某个知识库下的文档分页列表（校验知识库归属） */
    PageResultVO<DocumentVO> pageDocuments(Long knowledgeBaseId, long page, long pageSize, String keyword,
                                           String sortBy, String sortOrder);

    /** 文档详情（校验归属） */
    DocumentVO getDetail(Long documentId);

    /** 文档处理状态（校验归属），供前端轮询 */
    DocumentStatusVO getStatus(Long documentId);

    /**
     * 重试处理失败的文档。
     * <p>
     * 把三个状态重置为 PENDING 并清空错误信息，由 Python AI 服务的轮询循环
     * 下一轮认领处理（claim 只挑 PENDING 的文档，所以不需要主动通知）。
     */
    void retry(Long documentId);

    /** 按 id 取文档并校验归属 */
    Document getOwned(Long documentId);

    /** 生成预签名下载 URL */
    String downloadUrl(Long documentId);

    /**
     * 删除文档。
     * <p>
     * <b>只做逻辑删除</b>（document + sys_file），<b>不动 OSS 对象</b> ——
     * 留出恢复窗口，到期由 {@code FilePurgeTask} 判断引用计数后清理。
     * <p>
     * 处理中的文档（PARSING / CHUNKING / EMBEDDING）<b>也允许删</b> ——
     * 由 AI 服务那边判断「文档已删除就跳过」，比卡住用户好。
     */
    void delete(Long documentId);
}
