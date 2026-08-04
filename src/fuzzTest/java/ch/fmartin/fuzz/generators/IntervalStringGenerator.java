package ch.fmartin.fuzz.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

/**
 * Generates numeric and non-numeric interval strings to exercise timestamp parsing.
 */
public class IntervalStringGenerator extends Generator<String> {

    public IntervalStringGenerator() {
        super(String.class);
    }

    @Override
    public String generate(SourceOfRandomness random, GenerationStatus status) {
        if (random.nextBoolean()) {
            long value = random.nextLong(-1_000_000L, 1_000_000L);
            String base = Long.toString(value);
            int leadingSpaces = random.nextInt(0, 3);
            int trailingSpaces = random.nextInt(0, 3);
            return " ".repeat(leadingSpaces) + base + " ".repeat(trailingSpaces);
        }

        int length = random.nextInt(1, 16);
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char ch = (char) random.nextChar('A', 'z');
            if (random.nextBoolean()) {
                ch = Character.toUpperCase(ch);
            }
            builder.append(ch);
        }
        if (random.nextBoolean()) {
            builder.append(random.nextInt(0, 9));
        }
        return builder.toString();
    }
}
