package io.agenticdb.transfer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文本数据文件的打开方式。
 *
 * <p>⭐ <b>吞掉 BOM</b>：Windows 上另存为 UTF-8 的 CSV 往往带 {@code EF BB BF}，
 * 不处理的话第一个表头名会变成 {@code \uFEFFid}，于是「表头与目标列无法匹配」——
 * 用户看到的是一份「明明一模一样」的文件导入失败。这是真实世界最常见的导入坑。
 */
public final class TransferFiles {

    private TransferFiles() {
    }

    /** 按指定编码打开文本文件并吃掉可能的 BOM。 */
    public static Reader openReader(Path file, Charset charset) throws IOException {
        Charset effective = charset == null ? java.nio.charset.StandardCharsets.UTF_8 : charset;
        InputStream in = Files.newInputStream(file);
        Reader reader;
        try {
            reader = new InputStreamReader(in, effective);
        } catch (RuntimeException e) {
            in.close();
            throw e;
        }
        PushbackReader pushback = new PushbackReader(reader, 1);
        int first = pushback.read();
        if (first >= 0 && first != '\uFEFF') {
            pushback.unread(first);
        }
        return pushback;
    }
}
