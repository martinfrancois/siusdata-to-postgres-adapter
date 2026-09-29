package ch.fmartin;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenovateConfigurationTest {

    @Test
    void unavailableOpenRewritePluginVersionsAreExcludedForTheGradleDependency() throws IOException {
        String build = Files.readString(Path.of("build.gradle.kts"));
        Matcher pluginDeclaration = Pattern.compile(
                "id\\(\"(org\\.openrewrite\\.rewrite)\"\\) version \"[^\"]+\"").matcher(build);
        assertTrue(pluginDeclaration.find(), "the build should declare the OpenRewrite Gradle plugin");
        String dependencyName = pluginDeclaration.group(1);

        JSONObject config = new JSONObject(Files.readString(Path.of("renovate.json")));
        JSONObject matchingRule = null;
        JSONArray rules = config.getJSONArray("packageRules");
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            if (contains(rule.optJSONArray("matchManagers"), "gradle")
                    && contains(rule.optJSONArray("matchDepNames"), dependencyName)) {
                matchingRule = rule;
                break;
            }
        }

        assertNotNull(matchingRule,
                "the exclusion must match Renovate's Gradle depName, not the plugin marker packageName");
        String allowedVersions = matchingRule.getString("allowedVersions");
        assertTrue(allowedVersions.startsWith("!/") && allowedVersions.endsWith("/"),
                "the rule should exclude specific versions with a negated regular expression");
        Pattern excludedVersions = Pattern.compile(allowedVersions.substring(2, allowedVersions.length() - 1));
        assertTrue(excludedVersions.matcher("7.40.0").matches());
        assertTrue(excludedVersions.matcher("7.41.0").matches());
        assertFalse(excludedVersions.matcher("7.39.0").matches());
        assertFalse(excludedVersions.matcher("7.42.0").matches());
    }

    private static boolean contains(JSONArray values, String expected) {
        if (values == null) {
            return false;
        }
        for (int i = 0; i < values.length(); i++) {
            if (expected.equals(values.getString(i))) {
                return true;
            }
        }
        return false;
    }
}
