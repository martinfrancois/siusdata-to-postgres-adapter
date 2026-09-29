package ch.fmartin;

import org.testcontainers.utility.DockerImageName;

/**
 * Container images the integration tests start. Renovate reads the literal in DockerImageName.parse
 * (see the regex manager in renovate.json), so every test that needs PostgreSQL refers to this one place.
 */
final class TestImages {

    static final DockerImageName POSTGRES = DockerImageName.parse("postgres:18.6");

    private TestImages() {
    }
}
