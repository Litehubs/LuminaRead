package com.luminaread;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

/** Runs the existing offline regression checks as part of Maven's test phase. */
final class OfflineRegressionSuiteTest {
    private static final String[] NO_ARGS = new String[0];

    @Test void downloadSafety() throws Exception { DownloadSafetyTest.main(NO_ARGS); }
    @Test void nestedFb2Sections() throws Exception { Fb2NestedSectionsTest.main(NO_ARGS); }
    @Test void latestRequest() { LatestRequestTest.main(NO_ARGS); }
    @Test void readerConfiguration() throws Exception { ReaderConfigTest.main(NO_ARGS); }
    @Test void catalog() throws Exception { OnlineCatalogArchitectureTest.main(NO_ARGS); }
    @Test void parserAndService() throws Exception { ParserAndServiceRefactorTest.main(NO_ARGS); }
    @Test void ranobeArchitecture(@TempDir Path home) throws Exception {
        String previous = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            RanobeArchitectureTest.main(NO_ARGS);
        } finally {
            if (previous == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previous);
        }
    }
    @Test void regression() throws Exception { RegressionSmokeTest.main(NO_ARGS); }
}
