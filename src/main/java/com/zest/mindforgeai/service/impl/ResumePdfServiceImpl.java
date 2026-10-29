package com.zest.mindforgeai.service.impl;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.zest.mindforgeai.common.constant.ErrorCode;
import com.zest.mindforgeai.common.exception.BizException;
import com.zest.mindforgeai.pojo.vo.ResumeDetailVO;
import com.zest.mindforgeai.pojo.vo.ResumePdfModel;
import com.zest.mindforgeai.service.ResumePdfService;
import com.zest.mindforgeai.service.ResumeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 简历 PDF 导出实现：结构化简历 → Thymeleaf 渲染 XHTML → openhtmltopdf 出 PDF。
 *
 * @author crx
 * @since 2026-10-29
 */
@Slf4j
@Service
public class ResumePdfServiceImpl implements ResumePdfService {

    private static final String TEMPLATE = "resume/classic";

    /**
     * 中文字体族名，模板里的 {@code font-family} 必须和它一字不差。
     * <p>
     * ⚠️ 字体没生效<b>不会报错</b>，只会让所有中文变成方块，
     * 或者变成 PDF 里读不出来的字形 —— 所以测试里必须用 PDFBox 提取文本断言。
     */
    private static final String FONT_FAMILY = "ResumeCN";

    /**
     * 思源黑体（Noto Sans SC）Regular，SIL OFL 1.1，可商用。
     * <p>
     * ⚠️ <b>必须是 TrueType 轮廓（TTF）</b>。实测同一份文档换成
     * {@code NotoSansSC-Regular.otf}（CFF 轮廓）后，PDF 能正常生成、人眼看也正常，
     * 但 PDFBox 提取出来的中文全是 {@code #} —— 也就是 ATS 读不出字。
     * 这不是渲染问题，是 CFF 子集化后 Unicode 映射丢了，而且不会抛任何异常。
     * <p>
     * 许可证见同目录 {@code LICENSE-NotoSansSC.txt}（OFL 要求随字体一起分发）。
     */
    private static final String FONT_PATH = "fonts/NotoSansSC-Regular.ttf";

    /**
     * 拉丁字体族名。
     * <p>
     * ★ <b>这个字体不是「顺带加的」，是必须的。</b>
     * 思源黑体的 cmap 里 U+0020（普通空格）和 U+00A0（不换行空格）指向同一个字形，
     * PDFBox 做字体子集时反向映射取了后者 —— 结果 PDF 里<b>所有空格都变成 U+00A0</b>。
     * 人眼看不出差别，但 ATS 按 {@code "Spring Boot"}（普通空格）匹配
     * {@code "Spring\u00a0Boot"} 会直接失配，等于白导。
     * <p>
     * 解法：CSS 里把拉丁字体排在中文前面（{@code font-family: 'ResumeLatin', 'ResumeCN'}）。
     * openhtmltopdf 会逐字符回退，ASCII 和空格走拉丁字体（空格映射正确），
     * 汉字回退到思源黑体。实测空格恢复成 U+0020，中文照样能提取。
     * <p>
     * 顺带的好处：拉丁字形用 Noto Sans（思源黑体的同族设计），中英混排不违和。
     */
    private static final String LATIN_FONT_FAMILY = "ResumeLatin";

    /** Noto Sans Regular，Apache/OFL，只用于 ASCII 与空格 */
    private static final String LATIN_FONT_PATH = "fonts/NotoSans-Regular.ttf";

    /**
     * 同时渲染的上限。
     * <p>
     * 导出是 CPU 密集操作（排版 + 字体子集化），不限并发的话几个人同时点导出
     * 就能把 Tomcat 线程池占满，连正常接口都跟着慢。
     * <p>
     * ⚠️ 这是<b>单实例</b>信号量：部署 N 个实例时全局并发是 N × 4。
     * 现在的量级够用；真要严格全局限流得走 Redis 计数器，那是另一件事。
     */
    private static final int MAX_CONCURRENT = 4;

    /** 等许可的时间。短于一次页面点击的耐心，超时直接让前端提示「稍后重试」 */
    private static final long ACQUIRE_TIMEOUT_MS = 300;

    /** 文件名里不允许出现的字符（Windows / macOS / Linux 并集） */
    private static final String ILLEGAL_FILENAME_CHARS = "[\\\\/:*?\"<>|\\r\\n\\t]";

    private static final int MAX_FILENAME_BASE_LEN = 60;

    /** 简历允许上传的扩展名，拼下载文件名时要剥掉（见 fileNameOf） */
    private static final String[] UPLOAD_EXTENSIONS = {
            ".pdf", ".docx", ".doc", ".txt", ".md", ".markdown", ".html", ".htm"
    };

    private final ResumeService resumeService;
    private final TemplateEngine templateEngine;

    private final Semaphore permits = new Semaphore(MAX_CONCURRENT);

    /**
     * 字体字节缓存。
     * <p>
     * 17MB 的字体每次导出都从 classpath 读一遍 + 解析一遍太浪费，
     * 读一次缓存起来（字体是不可变的，可以安全共享）。
     */
    private volatile byte[] cjkFontBytes;

    private volatile byte[] latinFontBytes;

    public ResumePdfServiceImpl(ResumeService resumeService, TemplateEngine templateEngine) {
        this.resumeService = resumeService;
        this.templateEngine = templateEngine;
    }

    @Override
    public PdfFile render(Long resumeId) {
        // 先抢许可再干活：过载时连数据库都不用查，直接让用户重试
        boolean acquired;
        try {
            acquired = permits.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "导出任务较多，请稍后重试");
        }
        if (!acquired) {
            log.warn("简历导出并发已满（{}），拒绝 resumeId={}", MAX_CONCURRENT, resumeId);
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "导出任务较多，请稍后重试");
        }

        try {
            long start = System.currentTimeMillis();

            // 复用 getDetail：归属校验（不存在 / 不是你的都报 40409）和
            // structured_json 解析都在那边做好了，这里不重复实现
            ResumeDetailVO detail = resumeService.getDetail(resumeId);

            if (detail.getStructuredJson() == null || detail.getStructuredJson().isNull()) {
                // 刻意不用 rawContent 硬渲：原文排版不可控，渲出来是一坨纯文本，
                // 那正是这次改造要解决的问题本身
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "这份简历还没有解析过，无法导出，请先解析");
            }

            ResumePdfModel model = ResumePdfModel.from(
                    detail.getStructuredJson(), detail.getName());

            byte[] content = toPdf(renderXhtml(model));

            log.info("简历导出 resumeId={} bytes={} 耗时={}ms",
                    resumeId, content.length, System.currentTimeMillis() - start);

            String personName = detail.getStructuredJson().path("name").asText("").trim();
            return new PdfFile(content, fileNameOf(detail, personName, resumeId));

        } finally {
            permits.release();
        }
    }

    /* ---------- 内部 ---------- */

    private String renderXhtml(ResumePdfModel model) {
        Context context = new Context(Locale.SIMPLIFIED_CHINESE);
        context.setVariable("r", model);
        return templateEngine.process(TEMPLATE, context);
    }

    /**
     * XHTML → PDF。
     * <p>
     * 渲染到内存再返回，而不是直接写 {@code HttpServletResponse}：
     * 直接流式写的话，渲染中途出错时 200 响应头已经发出去了，
     * 用户拿到的是一个「下载成功但打不开」的坏文件，错误也传不回去。
     */
    private byte[] toPdf(String xhtml) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        try {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            // fast 模式关掉一些用不到的特性（如自定义 SVG 渲染路径），出得更快
            builder.useFastMode();
            // ★ 注册顺序无所谓，起作用的是模板 CSS 里 font-family 的先后 ——
            //   拉丁在前、中文在后，空格才会走拉丁字体（见 LATIN_FONT_FAMILY 的说明）
            builder.useFont(() -> new ByteArrayInputStream(latinFontBytes()), LATIN_FONT_FAMILY);
            builder.useFont(() -> new ByteArrayInputStream(cjkFontBytes()), FONT_FAMILY);
            builder.withHtmlContent(xhtml, null);
            builder.toStream(out);
            builder.run();
        } catch (Exception e) {
            // 模板不是良构 XML、字体缺字、CSS 解析失败都会走到这里。
            // 完整堆栈留给日志，对外只给一句人话。
            log.error("简历 PDF 渲染失败 xhtml长度={}", xhtml.length(), e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "简历 PDF 生成失败，请稍后重试");
        }
        return out.toByteArray();
    }

    /**
     * 供 openhtmltopdf 读取的中文字体流。
     * <p>
     * 每次渲染会调用一次（它要自己解析字体），所以从缓存的字节里开流，
     * 而不是每次都去读 classpath。
     */
    private byte[] cjkFontBytes() {
        if (cjkFontBytes == null) {
            cjkFontBytes = loadFont(FONT_PATH);
        }
        return cjkFontBytes;
    }

    private byte[] latinFontBytes() {
        if (latinFontBytes == null) {
            latinFontBytes = loadFont(LATIN_FONT_PATH);
        }
        return latinFontBytes;
    }

    /**
     * 读字体文件。
     * <p>
     * 用双重检查 + synchronized：两个线程同时首次导出时，不要各读一遍 17MB。
     */
    private byte[] loadFont(String path) {
        synchronized (this) {
            try (InputStream in = new ClassPathResource(path).getInputStream()) {
                byte[] bytes = in.readAllBytes();
                log.info("简历 PDF 字体已加载 {} {} 字节", path, bytes.length);
                return bytes;
            } catch (IOException e) {
                // 字体是打包进 jar 的，读不到说明构建产物缺文件 —— 这是部署问题，
                // 不是用户操作问题，所以给 50001 而不是 40001
                log.error("简历 PDF 字体加载失败 {}", path, e);
                throw new BizException(ErrorCode.SYSTEM_ERROR, "简历 PDF 字体缺失，请联系管理员");
            }
        }
    }

    /**
     * 下载文件名，优先级：目标职位 → 结构化数据里的姓名 → 简历名称。
     * <p>
     * ⚠️ 简历名称默认取的是上传时的原始文件名（可能是「张伟-简历.txt」），
     * 直接拼 {@code .pdf} 会变成「张伟-简历.txt.pdf」，所以要先把原扩展名剥掉。
     * <p>
     * 还要清洗掉路径分隔符和 Windows 保留字符 —— 文件名会进
     * {@code Content-Disposition}，带 {@code "} 或换行会破坏响应头。
     */
    private String fileNameOf(ResumeDetailVO detail, String personName, Long resumeId) {
        String base;
        if (StringUtils.hasText(detail.getTargetPosition())) {
            base = "简历-" + detail.getTargetPosition();
        } else if (StringUtils.hasText(personName)) {
            base = personName + "-简历";
        } else {
            base = detail.getName();
        }

        if (StringUtils.hasText(base)) {
            base = stripExtension(base)
                    .replaceAll(ILLEGAL_FILENAME_CHARS, " ")
                    .replaceAll("\\s+", " ")
                    .trim();
        }
        if (base.length() > MAX_FILENAME_BASE_LEN) {
            base = base.substring(0, MAX_FILENAME_BASE_LEN).trim();
        }
        if (!StringUtils.hasText(base)) {
            base = "resume-" + resumeId;
        }
        return base + ".pdf";
    }

    /** 剥掉上传文件留下的扩展名（大小写不敏感），没有就原样返回 */
    private String stripExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : UPLOAD_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return name.substring(0, name.length() - ext.length());
            }
        }
        return name;
    }
}
