package dev.erauner.jenkins

/**
 * Utility class for release management in Jenkins pipelines.
 *
 * Provides serialization-safe methods for:
 * - Calculating semantic versions
 * - Creating git tags
 * - Creating GitHub releases
 *
 * All regex operations use .findAll() to avoid NotSerializableException
 * with java.util.regex.Matcher objects.
 */
class ReleaseUtils implements Serializable {
    private static final long serialVersionUID = 1L

    /**
     * Calculate the next pre-release version based on current git tags.
     *
     * Version progression:
     * - v1.0.0 -> v1.1.0-rc.1 (stable -> next minor pre-release)
     * - v1.1.0-rc.1 -> v1.1.0-rc.2 (increment rc number)
     * - v1.1.0-rc.5 -> v1.1.0-rc.6 (continue incrementing)
     *
     * @param steps Pipeline script context (pass 'this' from Jenkinsfile)
     * @return Map with keys: currentTag, newVersion, baseVersion, rcNum
     */
    static Map<String, Object> calculateNextPreRelease(def steps) {
        // Use WORKSPACE env var to ensure we're in the git directory
        // Add safe.directory to work around git security check for different container users
        def currentTag = steps.sh(
            script: 'cd "${WORKSPACE}" && git config --global --add safe.directory "${WORKSPACE}" && git describe --tags --abbrev=0 2>/dev/null || echo "v0.0.0"',
            returnStdout: true
        ).trim()

        // Remove -rc.X suffix to get base version
        def baseVersion = currentTag.replaceAll(/-rc\..*/, '')

        // Use findAll() to avoid Matcher serialization issues
        def rcMatches = (currentTag =~ /-rc\.(\d+)/).findAll()
        def rcNum = rcMatches ? (rcMatches[0][1] as int) + 1 : 1

        // If current tag is a stable release (no -rc), bump minor version
        if (!currentTag.contains('-rc.')) {
            def parts = baseVersion.replace('v', '').tokenize('.')
            def major = parts[0] as int
            def minor = parts[1] as int
            baseVersion = "v${major}.${minor + 1}.0"
            rcNum = 1
        }

        def newVersion = "${baseVersion}-rc.${rcNum}"

        return [
            currentTag: currentTag,
            newVersion: newVersion,
            baseVersion: baseVersion,
            rcNum: rcNum
        ]
    }

    /**
     * Calculate the next stable release version.
     *
     * Version progression:
     * - v1.0.0-rc.5 -> v1.0.0 (promote rc to stable)
     * - v1.0.0 -> v1.1.0 (bump minor for next stable)
     *
     * @param steps Pipeline script context (pass 'this' from Jenkinsfile)
     * @param bumpType One of: 'major', 'minor', 'patch' (default: 'minor')
     * @return Map with keys: currentTag, newVersion
     */
    static Map<String, Object> calculateNextStable(def steps, String bumpType = 'minor') {
        // Use WORKSPACE env var to ensure we're in the git directory
        // Add safe.directory to work around git security check for different container users
        def currentTag = steps.sh(
            script: 'cd "${WORKSPACE}" && git config --global --add safe.directory "${WORKSPACE}" && git describe --tags --abbrev=0 2>/dev/null || echo "v0.0.0"',
            returnStdout: true
        ).trim()

        // Remove -rc.X suffix to get base version
        def baseVersion = currentTag.replaceAll(/-rc\..*/, '')

        // If already a stable release, bump according to bumpType
        if (!currentTag.contains('-rc.')) {
            def parts = baseVersion.replace('v', '').tokenize('.')
            def major = parts[0] as int
            def minor = parts[1] as int
            def patch = parts[2] as int

            switch (bumpType) {
                case 'major':
                    baseVersion = "v${major + 1}.0.0"
                    break
                case 'minor':
                    baseVersion = "v${major}.${minor + 1}.0"
                    break
                case 'patch':
                    baseVersion = "v${major}.${minor}.${patch + 1}"
                    break
            }
        }

        return [
            currentTag: currentTag,
            newVersion: baseVersion
        ]
    }

    /**
     * Create and push a git tag.
     *
     * Configures git with Jenkins CI user and pushes the tag to origin.
     * Automatically deletes any existing local/remote tag with the same name
     * to ensure idempotent pipeline runs.
     *
     * @param steps Pipeline script context (pass 'this' from Jenkinsfile)
     * @param args Map with:
     *   - version: Tag version (required, e.g., 'v1.0.0-rc.1')
     *   - message: Tag message (optional, defaults to "Release {version}")
     *   - repo: GitHub repo in format 'owner/name' (required for push URL)
     */
    static void createAndPushTag(def steps, Map args) {
        def version = args.version
        def message = args.message ?: "Release ${version}"
        def repo = args.repo

        if (!version) {
            steps.error("ReleaseUtils.createAndPushTag: 'version' is required")
        }
        if (!repo) {
            steps.error("ReleaseUtils.createAndPushTag: 'repo' is required")
        }

        // Use WORKSPACE env var to ensure we're in the git directory
        // Add safe.directory to work around git security check for different container users
        // Delete existing local/remote tag if it exists to support pipeline retries
        steps.sh """cd "\${WORKSPACE}" && \\
git config --global --add safe.directory "\${WORKSPACE}" && \\
git config user.email "jenkins@erauner.dev" && \\
git config user.name "Jenkins CI" && \\
git remote set-url origin https://\${GIT_USER}:\${GIT_TOKEN}@github.com/${repo}.git && \\
git tag -d ${version} 2>/dev/null || true && \\
git push origin :refs/tags/${version} 2>/dev/null || true && \\
git tag -a ${version} -m "${message}" && \\
git push origin ${version}"""
    }

    /**
     * Create a GitHub release via API.
     *
     * @param steps Pipeline script context (pass 'this' from Jenkinsfile)
     * @param args Map with:
     *   - version: Release version/tag (required)
     *   - repo: GitHub repo in format 'owner/name' (required)
     *   - body: Release body/description (optional)
     *   - prerelease: Whether this is a pre-release (default: true if version contains '-rc.')
     *   - draft: Whether this is a draft (default: false)
     */
    /**
     * Create a GitHub release and return the release ID.
     *
     * Automatically deletes any existing release with the same tag to support
     * pipeline retries.
     *
     * @return Map with keys: id (release ID), url (release URL)
     */
    static Map<String, Object> createGithubRelease(def steps, Map args) {
        def version = args.version
        def repo = args.repo
        def body = args.body ?: "Release ${version}"
        def prerelease = args.prerelease != null ? args.prerelease : version.contains('-rc.')
        def draft = args.draft ?: false

        if (!version) {
            steps.error("ReleaseUtils.createGithubRelease: 'version' is required")
        }
        if (!repo) {
            steps.error("ReleaseUtils.createGithubRelease: 'repo' is required")
        }

        // Delete existing release if it exists (for idempotent retries)
        steps.sh(
            script: """
EXISTING_RELEASE_ID=\$(curl -sf \\
    -H "Authorization: token \${GIT_TOKEN}" \\
    -H "Accept: application/vnd.github.v3+json" \\
    "https://api.github.com/repos/${repo}/releases/tags/${version}" 2>/dev/null | jq -r '.id' 2>/dev/null || echo "")

if [ -n "\$EXISTING_RELEASE_ID" ] && [ "\$EXISTING_RELEASE_ID" != "null" ]; then
    echo "Deleting existing release \$EXISTING_RELEASE_ID for tag ${version}"
    curl -sf -X DELETE \\
        -H "Authorization: token \${GIT_TOKEN}" \\
        "https://api.github.com/repos/${repo}/releases/\$EXISTING_RELEASE_ID" || true
fi
""",
            returnStatus: true
        )

        def releasePayload = """{
            "tag_name": "${version}",
            "name": "${version}",
            "body": "${body.replace('"', '\\"').replace('\n', '\\n')}",
            "draft": ${draft},
            "prerelease": ${prerelease}
        }"""

        steps.writeFile file: 'release-payload.json', text: releasePayload

        // Use WORKSPACE env var to ensure we're in the correct directory
        // Capture response to get release ID
        def response = steps.sh(
            script: """cd "\${WORKSPACE}" && \\
curl -sf -X POST \\
    -H "Authorization: token \${GIT_TOKEN}" \\
    -H "Accept: application/vnd.github.v3+json" \\
    -d @release-payload.json \\
    "https://api.github.com/repos/${repo}/releases\"""",
            returnStdout: true
        ).trim()

        // Parse release ID from response using jq
        def releaseId = steps.sh(
            script: "echo '${response.replace("'", "'\\''")}' | jq -r '.id'",
            returnStdout: true
        ).trim()

        def releaseUrl = steps.sh(
            script: "echo '${response.replace("'", "'\\''")}' | jq -r '.html_url'",
            returnStdout: true
        ).trim()

        return [id: releaseId, url: releaseUrl]
    }

    /**
     * Full release workflow: calculate version, create tag, create GitHub release.
     *
     * This is a convenience method that combines all release steps.
     * Must be called within a withCredentials block that sets GIT_USER and GIT_TOKEN.
     *
     * @param steps Pipeline script context (pass 'this' from Jenkinsfile)
     * @param args Map with:
     *   - repo: GitHub repo in format 'owner/name' (required)
     *   - imageTag: Docker image tag to include in release notes (optional)
     *   - imageName: Docker image name to include in release notes (optional)
     *   - stable: If true, create a stable release instead of pre-release (default: false)
     * @return Map with keys: version, releaseId, releaseUrl
     */
    static Map<String, Object> createPreRelease(def steps, Map args) {
        def repo = args.repo
        def imageTag = args.imageTag
        def imageName = args.imageName
        def stable = args.stable ?: false

        if (!repo) {
            steps.error("ReleaseUtils.createPreRelease: 'repo' is required")
        }

        // Calculate next version
        def versionInfo = stable ? calculateNextStable(steps) : calculateNextPreRelease(steps)
        def newVersion = versionInfo.newVersion

        steps.echo "Creating ${stable ? 'stable' : 'pre-'}release: ${newVersion}"

        // Create and push tag
        createAndPushTag(steps, [
            version: newVersion,
            message: "${stable ? 'Release' : 'Pre-release'} ${newVersion}",
            repo: repo
        ])

        // Build release body
        def body = "${stable ? 'Release' : 'Pre-release'} ${newVersion}"
        if (imageName && imageTag) {
            body += "\\n\\nImage: ${imageName}:${imageTag}"
        }

        // Create GitHub release and get release ID
        def releaseResult = createGithubRelease(steps, [
            version: newVersion,
            repo: repo,
            body: body,
            prerelease: !stable
        ])

        return [
            version: newVersion,
            releaseId: releaseResult.id,
            releaseUrl: releaseResult.url
        ]
    }
}
