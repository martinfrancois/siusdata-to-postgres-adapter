package ch.fmartin.fuzz.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

/**
 * Generates filenames that begin with a year to test timestamp reconstruction logic.
 */
public class YearPrefixedCsvFileGenerator extends Generator<String> {

    private static final String[] EXTENSIONS = {".csv", ".CSV", ""};

    public YearPrefixedCsvFileGenerator() {
        super(String.class);
    }

    @Override
    public String generate(SourceOfRandomness random, GenerationStatus status) {
        int year = random.nextInt(1900, 2099);
        StringBuilder builder = new StringBuilder(String.format("%04d", year));

        int extraDigits = random.nextInt(0, 4);
        for (int i = 0; i < extraDigits; i++) {
            builder.append(random.nextInt(0, 9));
        }

        int bodyLength = random.nextInt(0, 12);
        for (int i = 0; i < bodyLength; i++) {
            char ch = (char) random.nextChar('A', 'z');
            builder.append(ch);
        }

        if (random.nextBoolean()) {
            builder.append('_');
            builder.append(random.nextInt(0, 9));
        }

        builder.append(EXTENSIONS[random.nextInt(0, EXTENSIONS.length - 1)]);

        if (builder.length() > 0 && random.nextBoolean()) {
            int newLength = random.nextInt(1, builder.length());
            builder.setLength(newLength);
        }

        return builder.toString();
    }
}
