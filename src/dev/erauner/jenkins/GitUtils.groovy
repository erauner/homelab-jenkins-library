package dev.erauner.jenkins

/**
 * Utility class for git operations in Jenkins pipelines.
 *
 * Avoids using currentBuild.changeSets which causes serialization issues
 * when Jenkins checkpoints pipeline state after agent disconnect/reconnect.
 */
class GitUtils implements Serializable {
    private static final long serialVersionUID = 1L

    /**
     * Get git describe output (tag or commit hash).
     *
     * @param steps Pipeline script context (pass 'this' from Jenkinsfile)
     * @return Git describe output or 'dev' if not available
     */
    static String describe(def steps) {
        return steps.sh(
            script: 'git describe --tags --always --dirty 2>/dev/null || echo "dev"',
            returnStdout: true
        ).trim()
    }

    /**
     * Get short commit hash.
     *
     * @param steps Pipeline script context (pass 'this' from Jenkinsfile)
     * @return Short commit hash
     */
    static String shortCommit(def steps) {
        return steps.sh(
            script: 'git rev-parse --short HEAD',
            returnStdout: true
        ).trim()
    }

    /**
     * Get information about the last commit.
     *
     * @param steps Pipeline script context (pass 'this' from Jenkinsfile)
     * @return Map with keys: short, message, author
     */
    static Map<String, String> lastCommitInfo(def steps) {
        def shortHash = steps.sh(
            script: 'git log -1 --pretty=%h 2>/dev/null || echo "unknown"',
            returnStdout: true
        ).trim()

        def message = steps.sh(
            script: 'git log -1 --pretty=%s 2>/dev/null || echo "unknown"',
            returnStdout: true
        ).trim()

        def author = steps.sh(
            script: 'git log -1 --pretty=%an 2>/dev/null || echo "unknown"',
            returnStdout: true
        ).trim()

        return [
            short: shortHash,
            message: message,
            author: author
        ]
    }

    /**
     * Check if files matching patterns changed since a base reference.
     *
     * This avoids using currentBuild.changeSets which causes NotSerializableException
     * when Jenkins checkpoints pipeline state (especially after agent reconnects).
     *
     * @param steps Pipeline script context (pass 'this' from Jenkinsfile)
     * @param baseRef Git reference to compare against (default: HEAD~1)
     * @param containsAny List of path prefixes to check for changes
     * @return true if any of the patterns match changed files
     */
    static boolean changedSince(def steps, String baseRef = 'HEAD~1', List<String> containsAny = []) {
        def changedFiles = steps.sh(
            script: "git diff --name-only ${baseRef} 2>/dev/null || echo ''",
            returnStdout: true
        ).trim()

        if (containsAny.isEmpty()) {
            return !changedFiles.isEmpty()
        }

        return containsAny.any { pattern -> changedFiles.contains(pattern) }
    }
}
