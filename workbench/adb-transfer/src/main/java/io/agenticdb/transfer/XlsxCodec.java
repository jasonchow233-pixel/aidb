package io.agenticdb.transfer;

import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.Styles;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ⭐ P6-3：xlsx 流式读取（SAX）。
 *
 * <p>用 POI 的 event-model 逐行回调，**不**把整本工作簿读成
 * {@code XSSFWorkbook} 对象树 —— 几十万行的导入不会因为解析就吃光内存。
 *
 * <p>单元格统一转成「显示文本」再交给导入层：数字、文本、布尔都一样，
 * 日期按单元格格式格式化。类型转换仍由 {@link TableImporter#coerce} 按目标列做。
 */
public final class XlsxCodec {

    private XlsxCodec() {
    }

    /** 逐行回调；返回 {@code false} 表示不再需要更多行。 */
    @FunctionalInterface
    public interface RowConsumer {
        boolean row(List<String> cells) throws IOException;
    }

    /** 读第一张工作表，逐行回调。 */
    public static void read(Path file, RowConsumer consumer) throws IOException {
        try (OPCPackage pkg = OPCPackage.open(file.toFile())) {
            XSSFReader reader = new XSSFReader(pkg);
            SharedStrings strings = reader.getSharedStringsTable();
            Styles styles = reader.getStylesTable();
            XSSFReader.SheetIterator sheets =
                    (XSSFReader.SheetIterator) reader.getSheetsData();
            if (!sheets.hasNext()) {
                return;
            }
            RowCollector collector = new RowCollector(consumer);
            try (InputStream sheet = sheets.next()) {
                XMLReader parser = XMLHelper.newXMLReader();
                parser.setContentHandler(new XSSFSheetXMLHandler(styles, strings, collector,
                        new DataFormatter(Locale.ROOT), false));
                try {
                    parser.parse(new InputSource(sheet));
                } catch (SAXException e) {
                    if (isStop(e)) {
                        return;
                    }
                    IOException failure = failureOf(e);
                    if (failure != null) {
                        throw failure;
                    }
                    throw e;
                } catch (StopReading ignored) {
                    // 调用方已经拿够行数 —— 正常停止，不是错误。
                } catch (RowReadFailure e) {
                    throw e.failure;
                }
            }
        } catch (OpenXML4JException e) {
            throw new IOException("不是有效的 xlsx 文件：" + e.getMessage(), e);
        } catch (SAXException | ParserConfigurationException e) {
            throw new IOException("xlsx 解析失败：" + e.getMessage(), e);
        }
    }

    /** 预览前 {@code maxRows} 行（含表头）。 */
    public static List<List<String>> preview(Path file, int maxRows) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        if (maxRows <= 0) {
            return rows;
        }
        read(file, cells -> {
            rows.add(cells);
            return rows.size() < maxRows;
        });
        return rows;
    }

    private static boolean isStop(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof StopReading) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /** SAX 解析器会把回调里的异常包一层；把真正的 IOException 找出来。 */
    private static IOException failureOf(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof RowReadFailure failure) {
                return failure.failure;
            }
            cause = cause.getCause();
        }
        return null;
    }

    private static int columnIndex(String reference) {
        int column = 0;
        for (int i = 0; i < reference.length(); i++) {
            char c = reference.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                column = column * 26 + (c - 'A' + 1);
            } else if (c >= 'a' && c <= 'z') {
                column = column * 26 + (c - 'a' + 1);
            } else {
                break;
            }
        }
        return column - 1;
    }

    /** 把一行里跳过的空格补齐，列号按单元格引用落位（不按出现顺序）。 */
    private static final class RowCollector implements XSSFSheetXMLHandler.SheetContentsHandler {

        private final RowConsumer consumer;
        private final List<String> cells = new ArrayList<>();

        RowCollector(RowConsumer consumer) {
            this.consumer = consumer;
        }

        @Override
        public void startRow(int rowNum) {
            cells.clear();
        }

        @Override
        public void endRow(int rowNum) {
            try {
                if (!consumer.row(List.copyOf(cells))) {
                    throw new StopReading();
                }
            } catch (IOException e) {
                throw new RowReadFailure(e);
            }
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            int index = cellReference == null ? cells.size() : columnIndex(cellReference);
            while (cells.size() <= index) {
                cells.add("");
            }
            cells.set(index, formattedValue == null ? "" : formattedValue);
        }

        @Override
        public void headerFooter(String text, boolean isHeader, String tagName) {
        }
    }

    /** 提前停止的内部控制流；不填栈，避免被当成真正的错误。 */
    private static final class StopReading extends RuntimeException {
        StopReading() {
            super(null, null, false, false);
        }
    }

    /** 回调里的 IO 错误穿过 SAX 回调边界时随身携带原始异常。 */
    private static final class RowReadFailure extends RuntimeException {
        private final IOException failure;

        RowReadFailure(IOException failure) {
            super(null, null, false, false);
            this.failure = failure;
        }
    }
}
