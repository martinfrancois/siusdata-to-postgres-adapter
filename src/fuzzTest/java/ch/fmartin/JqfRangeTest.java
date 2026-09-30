package ch.fmartin;

import edu.berkeley.cs.jqf.fuzz.guidance.StreamBackedRandom;
import edu.berkeley.cs.jqf.fuzz.junit.quickcheck.FastSourceOfRandomness;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Under a campaign, generators get JQF's {@link FastSourceOfRandomness}, whose {@code nextInt(min, max)}
 * never returns {@code max} although junit-quickcheck documents the bound as inclusive. The generators
 * therefore take every range from {@code nextInt(bound)}. Once this test fails, JQF includes {@code max},
 * and this test and the rule in AGENTS.md can go: https://github.com/martinfrancois/siusdata-to-postgres-adapter/issues/284
 */
class JqfRangeTest {

    @Test
    void nextIntWithMinAndMaxNeverReturnsMax() {
        Set<Integer> results = new TreeSet<>();
        // Fixed seeds keep the inputs the same on every run, and random bytes make the result
        // independent of which bytes an implementation reads.
        for (long seed = 0; seed < 1000; seed++) {
            byte[] input = new byte[64];
            new Random(seed).nextBytes(input);
            FastSourceOfRandomness random = new FastSourceOfRandomness(new StreamBackedRandom(new ByteArrayInputStream(input)));
            results.add(random.nextInt(0, 1));
        }
        assertEquals(Set.of(0), results);
    }
}
