package io.agenticdb.transfer;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * ⭐ P6-3：SXSSF 流式写出 xlsx。
 *
 * <p>只把最近若干行留在内存里，其余落临时文件 —— 导大表时不会因为
 * 「整本工作簿都在内存里」而 OOM。{@link #close()} 时一次性写入目标流，
 * 并删除临时文件。
 */
final class XlsxStreamWriter implements AutoCloseable {

    /** 内存里保留多少行，其余滚到临时文件。 */
    private static final int WINDOW = 100;

    private final OutputStream out;
    private final SXSSFWorkbook workbook;
    private final Sheet sheet;
    private int nextRow;
    private boolean closed;

    XlsxStreamWriter(OutputStream out) {
        this.out = out;
        this.workbook = new SXSSFWorkbook(WINDOW);
        this.workbook.setCompressTempFiles(true);
        this.sheet = workbook.createSheet("Sheet1");
    }

    void header(List<String> names) {
        row(names);
    }

    void row(List<?> values) {
        Row row = sheet.createRow(nextRow++);
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (value == null) {
                continue;
            }
            Cell cell = row.createCell(i);
            if (value instanceof Number number) {
                cell.setCellValue(number.doubleValue());
            } else if (value instanceof Boolean flag) {
                cell.setCellValue(flag);
            } else {
                cell.setCellValue(String.valueOf(value));
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            workbook.write(out);
        } finally {
            try {
                workbook.close();
            } finally {
                workbook.dispose();
            }
        }
    }
}
