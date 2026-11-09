package ch.fmartin;

import org.testcontainers.utility.DockerImageName;

import java.util.Optional;

/**
 * Container images the integration tests start. Renovate reads the literal in DockerImageName.parse
 * (see the regex manager in renovate.json), so every test that needs PostgreSQL refers to this one place.
 */
final class TestImages {

    private static final DockerImageName DEFAULT_POSTGRES = DockerImageName.parse("postgres:18.6");

    /** CI sets this property to run the integration tests once per PostgreSQL major version. */
    static final DockerImageName POSTGRES = Optional.ofNullable(System.getProperty("siusdata.postgresImage"))
            .map(DockerImageName::parse)
            .orElse(DEFAULT_POSTGRES);

    private TestImages() {
    }
}
