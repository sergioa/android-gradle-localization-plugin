package pl.droidsonroids.gradle.localization

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test
//TODO add more tests
class LocalizationPluginTest extends GroovyTestCase {

    @Test
    void testCsvFileConfig() {
        def config = new ConfigExtension()
        config.csvFile = new File(getClass().getResource('valid.csv').getPath())
        parseTestCSV(config)
    }

    @Test
    void testValidFile() {
        println 'testing valid file'
        parseTestFile('valid.csv', new ConfigExtension())
    }

    @Test
    void testMissingTranslation() {
        println 'testing invalid file'
        try {
            parseTestFile('missing_translation.csv', new ConfigExtension())
            fail(IOException.class.getName() + ' expected')
        }
        catch (IOException ignored) {
            println 'expected exception thrown'
        }
    }

    @Test
    void testTranslations() {
        def config = new ConfigExtension()
        config.allowEmptyTranslations = true
        parseTestFile('android-new.csv', config)
    }

    private void parseTestFile(String fileName, ConfigExtension config) {
        config.csvFileURI = getClass().getResource(fileName).toURI()
        parseTestCSV(config)
    }

    private static void parseTestCSV(ConfigExtension config) throws IOException {
        def project = ProjectBuilder.builder().build()
        def resDir = project.file('src/main/res')
        try {
            new Parser(config, resDir).parseCSV()
        }
        finally {
            resDir.deleteDir()
        }
    }
}
