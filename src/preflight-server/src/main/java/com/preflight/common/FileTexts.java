package com.preflight.common;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 仓库里的文件文本解码唯一的口径：老工程（SVN 工作副本）以 GBK 为主、少数 UTF-8，
 * 先按 UTF-8 严格解，解不动再退 GBK。
 *
 * <p>为什么收成单点：这条规则此前在学习侧四处各写了一遍（{@code DbSourceIndex} / {@code MapperIndex}
 * / {@code CodeChainService} / {@code DispatchEnvelopeService}），改动流的"看文件全文"是第五个读盘的地方。
 * 再抄一遍就意味着四处与一处可能认不同的编码，同一份文件在两个页面显示成两种乱码。
 */
public final class FileTexts {

    public static final Charset GBK = Charset.forName("GBK");

    private FileTexts() {
    }

    /** 解出来的文本与实际用的编码名（界面要如实说"这份是 GBK"，别让人以为看到的乱码是内容本身） */
    public record Decoded(String text, String charset) {
    }

    public static Decoded decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return new Decoded("", "UTF-8");
        try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
            return new Decoded(new String(bytes, StandardCharsets.UTF_8), "UTF-8");
        } catch (Exception notUtf8) {
            return new Decoded(new String(bytes, GBK), "GBK");
        }
    }

    /** 按行切开（保留空行；CRLF 已由 split 吃掉 \r） */
    public static String[] lines(String text) {
        if (text == null || text.isEmpty()) return new String[0];
        return text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    }

    /**
     * 「这份内容能不能当文本看」唯一的判据（沿用 JGit 的口径：开头若干字节里有 NUL 就算二进制）。
     *
     * <p>为什么收成单点：Git 通道读历史版、SVN 通道读历史版、以及工作副本直接读盘，是三处各自取字节的
     * 入口，判据必须一致——否则同一个 .class 文件在【本提交那一版】里被正确拒绝，
     * 切到【本地工作副本】却按 GBK 解出一屏乱码冒充源码。
     */
    public static boolean binary(byte[] bytes) {
        return bytes != null && org.eclipse.jgit.diff.RawText.isBinary(bytes);
    }

    /** 二进制内容的统一说法（两个读版本都指这句，别在各处各写一遍措辞） */
    public static String binaryFile(String path) {
        return "二进制文件（jar/class/png 这类），没有文本内容可看：" + path;
    }
}
