package ch.fmartin.fuzz.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

/**
 * Generates ASCII strings with a controllable length range.
 */
public class AsciiStringGenerator extends Generator<String> {

    private static final int DEFAULT_MIN_LENGTH = 0;
    private static final int DEFAULT_MAX_LENGTH = 64;

    private final int minLength;
    private final int maxLength;

    public AsciiStringGenerator() {
        this(DEFAULT_MIN_LENGTH, DEFAULT_MAX_LENGTH);
    }

    public AsciiStringGenerator(int minLength, int maxLength) {
        super(String.class);
        this.minLength = minLength;
        this.maxLength = maxLength;
    }

    @Override
    public String generate(SourceOfRandomness random, GenerationStatus status) {
        int length = random.nextInt(minLength, Math.max(minLength, maxLength));
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append((char) random.nextChar(' ', '~'));
        }
        return builder.toString();
    }
}
