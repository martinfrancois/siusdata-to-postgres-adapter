package ch.fmartin;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import de.siegmar.fastcsv.writer.LineDelimiter;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates main files whose lines all have the right number of fields but any content in them:
 * numbers past the column's range, blanks, and text with separators, quotes and line breaks.
 *
 * <p>Every range comes from {@code nextInt(bound)}. Under Zest, {@code nextInt(min, max)} never
 * returns {@code max}, although junit-quickcheck documents it as inclusive.
 */
public class ShotFileGenerator extends Generator<ShotFile> {

    private static final int MAX_LINES = 4;
    private static final int MAX_TEXT_LENGTH = 20;
    private static final String CSV_SPECIAL_CHARACTERS = ";\"\r\n \t";
    private static final int SURROGATE_COUNT = Character.MAX_SURROGATE - Character.MIN_SURROGATE + 1;

    public ShotFileGenerator() {
        super(ShotFile.class);
    }

    @Override
    public ShotFile generate(SourceOfRandomness random, GenerationStatus status) {
        String name = digits(random, 8) + ".csv";
        List<List<String>> lines = new ArrayList<>();
        int lineCount = 1 + random.nextInt(MAX_LINES);
        for (int line = 0; line < lineCount; line++) {
            List<String> fields = new ArrayList<>(ShotFile.FIELD_COUNT);
            for (int field = 0; field < ShotFile.FIELD_COUNT; field++) {
                fields.add(field(random));
            }
            lines.add(fields);
        }
        return new ShotFile(name, lines, random.choose(LineDelimiter.values()));
    }

    private static String field(SourceOfRandomness random) {
        return switch (random.nextInt(6)) {
            case 0 -> "";
            case 1 -> padded(random, Integer.toString(random.nextInt()));
            case 2 -> padded(random, Long.toString(random.nextLong()));
            case 3 -> padded(random, random.nextBoolean() ? "1" : "0");
            case 4 -> random.nextInt(1000) + "." + digits(random, 1 + random.nextInt(5));
            default -> text(random);
        };
    }

    private static String padded(SourceOfRandomness random, String value) {
        return " ".repeat(random.nextInt(3)) + value + " ".repeat(random.nextInt(3));
    }

    private static String digits(SourceOfRandomness random, int count) {
        StringBuilder digits = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            digits.append(random.nextInt(10));
        }
        return digits.toString();
    }

    private static String text(SourceOfRandomness random) {
        int length = random.nextInt(MAX_TEXT_LENGTH + 1);
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            if (random.nextBoolean()) {
                text.append(CSV_SPECIAL_CHARACTERS.charAt(random.nextInt(CSV_SPECIAL_CHARACTERS.length())));
            } else {
                text.appendCodePoint(codePoint(random));
            }
        }
        return text.toString();
    }

    // A lone surrogate cannot be encoded as UTF-8, so the file could not be written at all.
    private static int codePoint(SourceOfRandomness random) {
        int codePoint = random.nextInt(Character.MAX_CODE_POINT + 1 - SURROGATE_COUNT);
        return codePoint < Character.MIN_SURROGATE ? codePoint : codePoint + SURROGATE_COUNT;
    }
}
