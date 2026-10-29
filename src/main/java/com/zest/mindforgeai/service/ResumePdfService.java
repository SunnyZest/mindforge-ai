package com.zest.mindforgeai.service;

/**
 * 简历 PDF 服务端导出。
 * <p>
 * 为什么在服务端做而不是继续用前端 {@code window.print()}：
 * 打印结果依赖用户浏览器的纸张、边距、缩放设置，同一个人换个浏览器导出的页数都不一样；
 * 更要命的是部分浏览器「另存为 PDF」会把文字转成图形，ATS 一个字都读不出来 ——
 * 而导出简历的唯一目的就是投递，投递的第一关是机器读。
 *
 * @author crx
 * @since 2026-10-29
 */
public interface ResumePdfService {

    /**
     * 把一份简历渲染成 PDF。
     *
     * @param resumeId 简历 id，归属校验在 {@link ResumeService#getDetail(Long)} 里做
     * @return PDF 字节 + 建议的下载文件名
     * @throws com.zest.mindforgeai.common.exception.BizException
     *         简历不存在 / 不属于当前用户（40409）、还没解析过（40001）、并发打满（42901）
     */
    PdfFile render(Long resumeId);

    /**
     * 渲染结果。
     *
     * @param content  PDF 字节
     * @param fileName 下载文件名（已带 .pdf 后缀，中文原样，由 controller 做 RFC 5987 编码）
     */
    record PdfFile(byte[] content, String fileName) {
    }
}
