package com.preflight.repo;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 改动快照（patch 文件）唯一的落盘口径：路径规则与截断上限只写这一处。
 * 同步时生成（{@link SyncService}）和点开时按需补（{@link PatchFetchService}）都走这里，
 * 两条通道写出来的路径必须一致，否则缓存认不出自己写过的文件。
 */
@Component
public class PatchStore {

    /** 单个快照的字符上限：再长前端也读不动，截断并留话（与原先 Git 通道同值） */
    public static final int MAX_PATCH_CHARS = 200_000;

    /**
     * 快照的落盘路径规则（唯一的文件名口径）。
     *
     * <p>上下文行数必须进文件名：同一份改动按 3 行、30 行、整文件渲染是三个不同的派生视图，
     * 共用一个文件会互相覆盖。默认档（3 行）沿用历史文件名——{@code file_changes.patch_file}
     * 有 5 个消费方（归因、摘要、出参、导出、界面），列语义一点都不能变。
     */
    public Path pathFor(long projectId, long commitId, String revision, String path, int context) {
        String base = commitId + "_" + shortRev(revision) + "_" + sanitize(path);
        String suffix = context == UnifiedDiff.DEFAULT_CONTEXT ? ""
                : context == UnifiedDiff.FULL_CONTEXT ? "_cfull" : "_c" + context;
        return Path.of("data", "artifacts", String.valueOf(projectId), "patches", base + suffix);
    }

    /** 写默认档（3 行上下文）的快照：同步通道与回写 {@code patch_file} 的唯一入口 */
    public String write(long projectId, long commitId, String revision, String path, String patch) throws IOException {
        return write(projectId, commitId, revision, path, patch, UnifiedDiff.DEFAULT_CONTEXT);
    }

    /** @return 落盘路径（只有默认档才允许回写 file_changes.patch_file） */
    public String write(long projectId, long commitId, String revision, String path, String patch, int context) throws IOException {
        String content = truncate(patch);
        Path target = pathFor(projectId, commitId, revision, path, context);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
        return target.toString();
    }

    /** 按规则读缓存：认不出文件返回 null（缓存是派生物，没有就重算，不拿旧列值搪塞） */
    public String read(long projectId, long commitId, String revision, String path, int context) {
        try {
            Path p = pathFor(projectId, commitId, revision, path, context);
            return Files.exists(p) ? Files.readString(p) : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String truncate(String patch) {
        if (patch == null) return null;
        return patch.length() <= MAX_PATCH_CHARS ? patch
                : patch.substring(0, MAX_PATCH_CHARS)
                + "\n...[截断：快照超过 " + (MAX_PATCH_CHARS / 1000) + "k 字符上限。" + TRUNCATION_SUFFIX;
    }

    /** 截断话术由 truncate 单点产出，判据也只认这一处（全文件视图要据此说明"标记可能不全"）。
     *  尾巴这句话本身就是常量——两处各写一遍时，判据迟早对不上产出的原文（曾经就一直对不上）。 */
    public static boolean isTruncated(String patch) {
        return patch != null && patch.endsWith(TRUNCATION_SUFFIX);
    }

    private static final String TRUNCATION_SUFFIX = "把【上下文】换小一档（3 行 / 10 行）即可看全改动点前后的内容，或直接点【看全文】]";

    /** 与历史文件名保持一致：只留字母数字下划线连字符，最多 12 位 */
    private static String shortRev(String revision) {
        String cleaned = String.valueOf(revision).replaceAll("[^a-zA-Z0-9_-]", "");
        return cleaned.substring(0, Math.min(12, cleaned.length()));
    }

    private static String sanitize(String path) {
        return path.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
