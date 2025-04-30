package one.wabbit.inspect

import one.wabbit.inspect.Inspect.sourceLocationOf
import org.objectweb.asm.tree.ClassNode
import kotlin.test.Test
import kotlin.test.assertTrue

class SourceLocationTests {

    @Test
    fun `sourceLocationOf for JDK class`() {
        val location = sourceLocationOf(String::class)
        println("Location of String: $location")
        assertTrue(location.startsWith("jrt:/java.base")) // Or similar for current JDK structure
    }

    @Test
    fun `sourceLocationOf for test class`() {
        val location = sourceLocationOf(SourceLocationTests::class)
        println("Location of Test Class: $location")
        // Should point to the build output directory (e.g., build/classes/kotlin/test)
        assertTrue(location.startsWith("file:"))
        assertTrue(location.contains("test")) // Common pattern in Gradle/Maven build dirs
    }

    @Test
    fun `sourceLocationOf for dependency class`() {
        // This test is more reliable if you add a real library dependency
        val location = sourceLocationOf(ClassNode::class) // Using Kotlin's Result class
        println("Location of ClassNode: $location")
        assertTrue(location.startsWith("file:"))
        assertTrue(location.contains("asm-tree"))
        assertTrue(location.endsWith(".jar"))
    }

    @Test
    fun `sourceLocationOf for stdlib class`() {
        // This test is more reliable if you add a real library dependency
        val location = sourceLocationOf(Result::class) // Using Kotlin's Result class
        println("Location of ClassNode: $location")
        assertTrue(location.startsWith("file:"))
        // Example check if using kotlin-stdlib jar
        assertTrue(location.contains("kotlin-stdlib"))
        assertTrue(location.endsWith(".jar"))
    }
//
//    // --- findLocalSourcesJar Tests ---
//    // These require the dummy JARs created in setupAll @BeforeAll
//
//    @Test
//    fun `findLocalSourcesJar finds sibling sources JAR`() {
//        // Need a KClass whose source location *is* the dummy JAR. This is hard.
//        // Instead, let's simulate the scenario by creating a mock KClass/ProtectionDomain
//        // Or, test the logic directly by providing the dummy JAR path.
//
//        // We'll test the *logic* by assuming a class came from dummyJarPath
//        val mockCls = MockClassForPath(dummyJarPath)
//        val foundSources = findLocalSourcesJar(mockCls::class) // Pass the KClass
//
//        assertThat(foundSources)
//            .isNotNull()
//            .isEqualTo(dummySourcesJarPath)
//    }
//
//    @Test
//    fun `findLocalSourcesJar returns null if no sibling sources JAR exists`() {
//        val nonExistentSourcesJar = tempDirForJars.resolve("dummy-lib-1.0-no-sources.jar")
//        Files.createFile(nonExistentSourcesJar)
//        val mockCls = MockClassForPath(nonExistentSourcesJar)
//
//        val foundSources = findLocalSourcesJar(mockCls::class)
//
//        assertThat(foundSources).isNull()
//        Files.deleteIfExists(nonExistentSourcesJar) // Clean up
//    }
//
//    // Mocking infrastructure to simulate a class loaded from a specific path
//    class MockClassForPath(val path: Path) {
//        // We need to override the protectionDomain to control the source location
//        // This requires deeper mocking or a custom class loader, which is complex.
//        // For simplicity, we'll rely on the direct logic test above.
//        // This test case demonstrates the difficulty.
//    }
//    // Due to complexity of mocking ProtectionDomain, skipping direct test via KClass here.
//    // The test `findLocalSourcesJar finds sibling sources JAR` covers the core logic.
//
//
//    // --- findAndDownloadSourcesJarFromMavenCentral Tests ---
//    // These tests are difficult as they involve network calls and parsing logic.
//    // Focus on parsing logic and maybe mock the HTTP download.
//
//    @Test
//    @Disabled("Requires network access and valid Maven coordinates")
//    fun `findAndDownloadSourcesJarFromMavenCentral integration test`() {
//        // Requires a class from a known Maven Central artifact
//        // Example: Use AssertJ's class itself if added as a dependency
//        val assertjClass = assertThat("").javaClass.kotlin
//        val groupId = "org.assertj" // Must know this
//        val downloadedPath = findAndDownloadSourcesJarFromMavenCentral(assertjClass, groupId)
//
//        assertThat(downloadedPath)
//            .isNotNull()
//            .exists()
//        assertThat(downloadedPath!!.name).contains("assertj-core")
//        assertThat(downloadedPath.name).contains("-sources.jar")
//
//        // Clean up downloaded file
//        Files.deleteIfExists(downloadedPath)
//    }
//
//    @Test
//    fun `findAndDownloadSourcesJarFromMavenCentral parsing logic`() {
//        // Test the internal parsing without actual download
//        // Need to mock the ProtectionDomain/CodeSource again, which is hard.
//        // Alternative: Test the URL generation logic separately if possible.
//        // Skipping direct test due to mocking complexity.
//        // Manual verification of the URL generated in the function's print statement is advised.
//        assertThat(true).isTrue() // Placeholder assertion
//    }
//
//    @Test
//    fun `httpDownload successful download`(@TempDir tempDir: Path) {
//        // Requires a reliable small file URL for testing
//        // Using a placeholder image service
//        val testUrl = "https://via.placeholder.com/10.png" // Small image
//        val destFile = tempDir.resolve("test_download.png")
//
//        val success = httpDownload(testUrl, destFile)
//
//        assertThat(success).isTrue()
//        assertThat(destFile).exists().hasSizeGreaterThan(0)
//    }
//
//    @Test
//    fun `httpDownload handles 404 Not Found`(@TempDir tempDir: Path) {
//        val testUrl = "https://repo1.maven.org/maven2/com/example/nonexistent/1.0/nonexistent-1.0.jar"
//        val destFile = tempDir.resolve("not_found.jar")
//
//        val success = httpDownload(testUrl, destFile)
//
//        assertThat(success).isFalse()
//        assertThat(destFile).doesNotExist()
//    }
}