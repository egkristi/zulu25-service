package no.egk.demo;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Name, version and build timestamp, injected at build time by Maven resource
 * filtering ({@code src/main/resources/build-info.properties}).
 */
public record BuildInfo(String name, String version, String buildTime) {

    private static final BuildInfo CURRENT = load();

    public static BuildInfo current() {
        return CURRENT;
    }

    private static BuildInfo load() {
        Properties props = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream("/build-info.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            Log.warn("Could not read build-info.properties: " + e.getMessage());
        }
        return new BuildInfo(
                props.getProperty("app.name", "zulu25-service"),
                props.getProperty("app.version", "dev"),
                props.getProperty("app.buildTime", "unknown"));
    }
}
