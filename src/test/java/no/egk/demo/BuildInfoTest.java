package no.egk.demo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class BuildInfoTest {

    @Test
    @DisplayName("exposes its fields through the generated accessors")
    void recordAccessorsReturnConstructorValues() {
        BuildInfo info = new BuildInfo("svc", "1.2.3", "2024-01-01T00:00:00Z");
        assertEquals("svc", info.name());
        assertEquals("1.2.3", info.version());
        assertEquals("2024-01-01T00:00:00Z", info.buildTime());
    }

    @Test
    @DisplayName("records with equal fields are equal")
    void recordEquality() {
        BuildInfo a = new BuildInfo("svc", "1.0", "t");
        BuildInfo b = new BuildInfo("svc", "1.0", "t");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("current() is loaded once from the filtered build-info.properties resource")
    void currentReflectsFilteredResource() {
        BuildInfo current = BuildInfo.current();
        assertNotNull(current);
        assertFalse(current.name().isBlank(), "name should have been filtered in by Maven");
        assertFalse(current.version().isBlank(), "version should have been filtered in by Maven");
        assertFalse(current.buildTime().isBlank(), "buildTime should have been filtered in by Maven");
        assertSame(current, BuildInfo.current(), "current() should always return the same cached instance");
    }
}
