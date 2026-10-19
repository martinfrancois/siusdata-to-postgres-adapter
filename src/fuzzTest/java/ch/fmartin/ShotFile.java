package ch.fmartin;

import de.siegmar.fastcsv.writer.CsvWriter;
import de.siegmar.fastcsv.writer.LineDelimiter;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

/**
 * A main file as SIUS writes it: one line per shot, 28 fields separated by {@code ;}.
 *
 * @param name          the file name, which starts with the date the shots belong to
 * @param lines         the fields of each line
 * @param lineDelimiter the line ending between the lines
 */
public record ShotFile(String name, List<List<String>> lines, LineDelimiter lineDelimiter) {

    public static final int FIELD_COUNT = 28;

    /**
     * Returns the file content, with fields quoted wherever the CSV format requires it.
     */
    public String content() throws IOException {
        StringWriter content = new StringWriter();
        try (CsvWriter csv = CsvWriter.builder().fieldSeparator(';').lineDelimiter(lineDelimiter).build(content)) {
            lines.forEach(csv::writeRecord);
        }
        return content.toString();
    }
}
