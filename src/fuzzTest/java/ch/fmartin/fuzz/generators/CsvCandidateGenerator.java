package ch.fmartin.fuzz.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

/**
 * Generates file name candidates to stress {@link ch.fmartin.SiusDataToPostgresAdapter#isValidCsvFile(String)}.
 */
public class CsvCandidateGenerator extends Generator<String> {

    private static final String[] EXTENSIONS = {".csv", ".CSV", ".txt", ".dat", ""};
    private static final String[] SIDE_CAR_SUFFIXES = {"", "_stl", "_STL", "_mod", "_MOD"};

    public CsvCandidateGenerator() {
        super(String.class);
    }

    @Override
    public String generate(SourceOfRandomness random, GenerationStatus status) {
        StringBuilder builder = new StringBuilder();
        if (random.nextBoolean()) {
            int digits = random.nextInt(4, 10);
            for (int i = 0; i < digits; i++) {
                builder.append(random.nextInt(0, 9));
            }
        } else {
            int letters = random.nextInt(0, 6);
            for (int i = 0; i < letters; i++) {
                char ch = (char) random.nextChar('A', 'z');
                builder.append(ch);
            }
        }

        int bodyLength = random.nextInt(0, 12);
        for (int i = 0; i < bodyLength; i++) {
            char ch = (char) random.nextChar('A', 'z');
            if (random.nextBoolean()) {
                ch = Character.toLowerCase(ch);
            }
            builder.append(ch);
        }

        builder.append(SIDE_CAR_SUFFIXES[random.nextInt(0, SIDE_CAR_SUFFIXES.length - 1)]);
        builder.append(EXTENSIONS[random.nextInt(0, EXTENSIONS.length - 1)]);

        if (builder.length() > 0 && random.nextBoolean()) {
            int newLength = random.nextInt(0, builder.length());
            builder.setLength(newLength);
        }

        return builder.toString();
    }
}
