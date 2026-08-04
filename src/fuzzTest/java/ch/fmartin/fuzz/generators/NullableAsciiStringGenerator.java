package ch.fmartin.fuzz.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

/**
 * Generates ASCII strings and occasionally {@code null} to exercise null-handling paths.
 */
public class NullableAsciiStringGenerator extends AsciiStringGenerator {

    public NullableAsciiStringGenerator() {
        super();
    }

    @Override
    public String generate(SourceOfRandomness random, GenerationStatus status) {
        if (random.nextInt(0, 9) == 0) {
            return null;
        }
        // Frequently produce values that trigger special branches
        int special = random.nextInt(0, 9);
        return switch (special) {
            case 0 -> "";
            case 1 -> "0";
            case 2 -> "1";
            case 3 -> " true ";
            case 4 -> " false ";
            default -> super.generate(random, status);
        };
    }
}
