package com.zest.mindforgeai.common.constant;

import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * 上传文件的类型白名单与扩展名工具。
 * <p>
 * 「允许哪些扩展名」和「怎么从文件名取扩展名」这两件事，
 * 文档上传和简历上传都要做 —— 各写一遍的话，两处迟早不一致
 * （比如某天给文档加了 .rtf 却忘了给简历加）。
 * <p>
 * 两个白名单<b>刻意分开命名</b>而不是共用一份：它们现在的取值相同，
 * 但语义不同（一个是知识库资料，一个是简历），将来要分化时改一处就行，
 * 不用先做一次「拆开」的重构。
 *
 * @author crx
 * @since 2026-10-08
 */
public final class UploadConstants {

    /** 知识库文档允许的类型，对齐文档 §十六 */
    public static final Set<String> DOCUMENT_EXT =
            Set.of("pdf", "docx", "txt", "md", "markdown", "html", "htm");

    /** 简历文件允许的类型 —— 简历常见就是 PDF / Word，纯文本和 Markdown 也放进来 */
    public static final Set<String> RESUME_EXT =
            Set.of("pdf", "docx", "txt", "md", "markdown", "html", "htm");

    private UploadConstants() {
    }

    /**
     * 从文件名取小写扩展名（不含点）。
     * <p>
     * 没有扩展名、或者文件名以点开头（隐藏文件）都返回空串 ——
     * 调用方拿到空串会被白名单挡掉，这是想要的行为。
     */
    public static String extensionOf(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && dot < fileName.length() - 1
                ? fileName.substring(dot + 1).toLowerCase()
                : "";
    }

    /** 扩展名是否在白名单内 */
    public static boolean isAllowed(String fileName, Set<String> allowed) {
        return allowed.contains(extensionOf(fileName));
    }
}
