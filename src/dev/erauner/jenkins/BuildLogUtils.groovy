package dev.erauner.jenkins

import com.cloudbees.groovy.cps.NonCPS

/**
 * Utility class for extracting and filtering Jenkins build logs.
 *
 * The @NonCPS annotation is critical - it prevents Jenkins from trying to
 * serialize the rawBuild object which causes NotSerializableException.
 */
class BuildLogUtils implements Serializable {
    private static final long serialVersionUID = 1L

    /**
     * Extract filtered build log focusing on error content.
     *
     * Filters out Pipeline metadata and prioritizes lines containing errors.
     * If no error patterns are found, returns the last N lines of actual content.
     *
     * @param build The RunWrapper from currentBuild
     * @param lines Number of log lines to fetch from the build
     * @param maxLines Maximum lines to return (default 60)
     * @return Filtered log content as a string
     */
    @NonCPS
    static String getFilteredBuildLog(def build, int lines, int maxLines = 60) {
        def logLines = build.rawBuild.getLog(lines)

        // Filter out Pipeline metadata, keep actual content
        def contentLines = logLines.findAll { line ->
            !line.startsWith('[Pipeline]') &&
            !line.startsWith('[declarative]') &&
            !(line =~ /^\[.+\] Running on/) &&
            !line.trim().isEmpty()
        }

        // Look for error-related lines (prioritize showing failures)
        def errorLines = contentLines.findAll { line ->
            line.toLowerCase().contains('error') ||
            line.toLowerCase().contains('failed') ||
            line.contains('FAILURE') ||
            line.contains('Exception') ||
            line.contains('✗') ||
            line.startsWith('  at ') ||  // Stack traces
            line =~ /^\s*\^/             // Syntax error indicators
        }

        // If we found errors, show them (up to maxLines)
        // Otherwise fall back to last maxLines of content
        if (errorLines.size() > 0) {
            return errorLines.take(maxLines).join('\n')
        }
        return contentLines.takeRight(maxLines).join('\n')
    }
}
