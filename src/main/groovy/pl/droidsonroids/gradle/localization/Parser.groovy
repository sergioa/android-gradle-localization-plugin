package pl.droidsonroids.gradle.localization

import groovy.xml.MarkupBuilder
import org.apache.commons.csv.CSVParser
import org.jsoup.Jsoup

import java.text.Normalizer
import java.util.regex.Pattern

/**
 * Class containing CSV parser logic
 */
class Parser {
    private static
    final Pattern JAVA_IDENTIFIER_REGEX = Pattern.compile("\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*");
    private static final String NAME = "name", TRANSLATABLE = "translatable", COMMENT = "comment"
    private static final int BUFFER_SIZE = 128 * 1024
    private final CSVParser mParser
    private final ConfigExtension mConfig
    private final File mResDir
    private final Reader mReader

    Parser(ConfigExtension config, File resDir) {
        Set<Object> csvSources = [config.csvFileURI, config.csvFile, config.csvGenerationCommand] as Set
        csvSources.remove(null)
        if (csvSources.size() != 1)
            throw new IllegalArgumentException("Exactly one source must be defined")
        Reader reader
        if (config.csvGenerationCommand != null) {
            def process = new ProcessBuilder(config.csvGenerationCommand.split('\\s+'))
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start()
            reader = new InputStreamReader(process.getInputStream())
        } else if (config.csvFile != null)
            reader = new FileReader(config.csvFile)
        else// if (config.csvFileURI!=null)
            reader = new InputStreamReader(new URL(config.csvFileURI).openStream())

        mReader = reader
        mResDir = resDir
        mParser = config.csvStrategy ? new CSVParser(reader, config.csvStrategy) : new CSVParser(reader)
        mConfig = config
    }

    static class SourceInfo {
        private final XMLBuilder[] mBuilders
        private final int mCommentIdx
        private final int mTranslatableIdx
        private final int mNameIdx
        private final int mColumnsCount

        SourceInfo(XMLBuilder[] builders, nameIdx, translatableIdx, commentIdx, columnsCount) {
            mBuilders = builders
            mNameIdx = nameIdx
            mTranslatableIdx = translatableIdx
            mCommentIdx = commentIdx
            mColumnsCount = columnsCount
        }
    }

    class XMLBuilder {
        final String mQualifier
        final MarkupBuilder mBuilder

        XMLBuilder(String qualifier) {
            String valuesDirName = qualifier == mConfig.defaultColumnName ? 'values' : 'values-' + qualifier
            File valuesDir = new File(mResDir, valuesDirName)
            if (!valuesDir.isDirectory())
                valuesDir.mkdirs()
            File valuesFile = new File(valuesDir, 'strings.xml')
            mBuilder = new MarkupBuilder(
                    new OutputStreamWriter(
                            new BufferedOutputStream(
                                    new FileOutputStream(valuesFile)
                                    , BUFFER_SIZE)
                            , 'UTF-8'))
            mBuilder.setDoubleQuotes(true)
            mBuilder.setOmitNullAttributes(true)
            mQualifier = qualifier
            mBuilder.getMkp().xmlDeclaration(version: '1.0', encoding: 'UTF-8')
        }

        def addResource(body) {//TODO add support for tools:locale
            mBuilder.resources(body/*, 'xmlns:tools':'http://schemas.android.com/tools', 'tools:locale':mQualifier*/)
        }
    }

    void parseCSV() throws IOException {
        mReader.withReader {
            parseCells(parseHeader(mParser))
        }
    }


    private parseCells(final SourceInfo sourceInfo) throws IOException {
        String[][] cells = mParser.getAllValues()
        def attrs = new LinkedHashMap<>(2)
        for (j in 0..sourceInfo.mBuilders.length - 1) {
            def builder = sourceInfo.mBuilders[j]
            if (builder == null)
                continue
            def keys = new HashSet(cells.length)
            builder.addResource({
                for (i in 0..cells.length - 1) {
                    def row = cells[i]
                    if (row.size() < sourceInfo.mColumnsCount) {
                        def extendedRow = new String[sourceInfo.mColumnsCount]
                        System.arraycopy(row, 0, extendedRow, 0, row.size())
                        for (k in row.size()..sourceInfo.mColumnsCount - 1)
                            extendedRow[k] = ''
                        row = extendedRow
                    }
                    def name = row[sourceInfo.mNameIdx]
                    if (!JAVA_IDENTIFIER_REGEX.matcher(name).matches())
                        throw new IOException(name + " is not valid name, row #" + (i + 2))
                    if (!keys.add(name))
                        throw new IOException(name + " is duplicated in row #" + (i + 2))
                    attrs.put('name', name)
                    def translatable = true
                    if (sourceInfo.mTranslatableIdx >= 0) {
                        translatable = !row[sourceInfo.mTranslatableIdx].equalsIgnoreCase('false')
                        attrs.put('translatable', translatable ? null : 'false')
                    }
                    def value = row[j]
                    if (value.isEmpty()) {
                        if (!translatable && builder.mQualifier != mConfig.defaultColumnName)
                            continue
                        if (!mConfig.allowEmptyTranslations)
                            throw new IOException(name + " is not translated to locale " + builder.mQualifier + ", row #" + (i + 2))
                    } else {
                        if (!translatable && !mConfig.allowNonTranslatableTranslation && builder.mQualifier != mConfig.defaultColumnName)
                            throw new IOException(name + " is translated but marked translatable='false', row #" + (i + 2))
                    }
                    if (mConfig.escapeSlashes)
                        value = value.replace("\\", "\\\\")
                    if (mConfig.escapeApostrophes)
                        value = value.replace("'", "\\'")
                    if (mConfig.escapeQuotes) //TODO don't escape tag attribute values
                        value = value.replace("\"", "\\\"")
                    if (mConfig.escapeNewLines)
                        value = value.replace("\n", "\\n")
                    if (mConfig.escapeBoundarySpaces && (value.indexOf(' ') == 0 || value.lastIndexOf(' ') == value.length() - 1))
                        value = '"' + value + '"'
                    if (mConfig.convertTripleDotsToHorizontalEllipsis)
                        value = value.replace("...", "…")
                    if (mConfig.normalizationForm != null)
                        value = Normalizer.normalize(value, mConfig.normalizationForm)
                    string(attrs) {
                        if (mConfig.tagEscapingStrategy == TagEscapingStrategy.ALWAYS ||
                                (mConfig.tagEscapingStrategy == TagEscapingStrategy.IF_TAGS_ABSENT &&
                                        Jsoup.parse(value).body().children().isEmpty()))
                            mkp.yield(value)
                        else
                            mkp.yieldUnescaped(value)
                    }
                    if (sourceInfo.mCommentIdx >= 0 && !row[sourceInfo.mCommentIdx].isEmpty())
                        mkp.comment(row[sourceInfo.mCommentIdx])
                }
            })
        }
    }

    private SourceInfo parseHeader(CSVParser mParser) throws IOException {
        def headerLine = mParser.getLine()
        if (headerLine == null || headerLine.size() < 2)
            throw new IOException("Invalid CSV header: " + headerLine)
        List<String> header = Arrays.asList(headerLine)
        def keyIdx = header.indexOf(NAME)
        if (keyIdx == -1)
            throw new IOException("'name' column not present")
        if (header.indexOf(mConfig.defaultColumnName) == -1)
            throw new IOException("Default locale column not present")
        def builders = new XMLBuilder[header.size()]

        def reservedColumns = [NAME, COMMENT, TRANSLATABLE]
        reservedColumns.addAll(mConfig.ignorableColumns)
        def i = 0
        for (columnName in header) {
            if (!(columnName in reservedColumns))
                builders[i] = new XMLBuilder(columnName)
            i++
        }
        new SourceInfo(builders, keyIdx, header.indexOf(TRANSLATABLE), header.indexOf(COMMENT), header.size())
    }
}