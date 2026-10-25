package io.github.viacheslavmezentsev.hwlivetest

import org.junit.Assert.*
import org.junit.Test

class ReleaseVersionTest {
    @Test fun comparesNumericComponentsAndTagPrefix() {
        assertEquals(1, ReleaseVersion.compare("v0.3.16", "0.3.9"))
        assertEquals(0, ReleaseVersion.compare("v0.3.16", "0.3.16"))
        assertEquals(-1, ReleaseVersion.compare("v0.3.13", "0.3.16"))
        assertEquals(1, ReleaseVersion.compare("1.0.0", "0.99.99"))
    }
    @Test fun unknownOrPrereleaseVersionIsNotReportedAsUpdate() {
        for (tag in listOf("latest", "v0.3.17-rc1", "0.3", "0.3.999999999999999999")) {
            assertNull(ReleaseVersion.compare(tag, "0.3.16"))
        }
        assertNull(ReleaseVersion.compare("0.3.17", "development"))
    }
}
