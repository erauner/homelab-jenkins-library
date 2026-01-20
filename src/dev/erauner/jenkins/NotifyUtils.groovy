package dev.erauner.jenkins

import groovy.json.JsonOutput

/**
 * Utility class for notifications (GitHub status, Discord, PR comments).
 *
 * Centralizes notification logic to ensure consistent behavior across repos.
 */
class NotifyUtils implements Serializable {
    private static final long serialVersionUID = 1L

    /**
     * Post GitHub commit status with custom target URL.
     *
     * Uses the pipeline-githubnotify-step plugin to override the default
     * build URL with Pipeline Graph View for better UX.
     *
     * @param steps Pipeline script context
     * @param status 'SUCCESS', 'FAILURE', 'PENDING', or 'ERROR'
     * @param description Status description
     * @param targetUrl URL to link to (defaults to pipeline-overview)
     */
    static void githubStatus(def steps, String status, String description, String targetUrl = null) {
        def url = targetUrl ?: "${steps.env.BUILD_URL}pipeline-overview/"
        steps.githubNotify(
            status: status,
            description: description,
            targetUrl: url
        )
    }

    /**
     * Post failure comment to GitHub PR with error context.
     *
     * @param steps Pipeline script context
     * @param args Map with:
     *   - changeId: PR number (env.CHANGE_ID)
     *   - repo: GitHub repo (e.g., 'erauner/homelab-k8s')
     *   - errorContext: Filtered error log content
     *   - jobName: Job name (env.JOB_NAME)
     *   - buildNumber: Build number (env.BUILD_NUMBER)
     *   - buildUrl: Build URL (env.BUILD_URL)
     *   - gitBranch: Git branch (env.GIT_BRANCH)
     *   - gitCommit: Git commit (env.GIT_COMMIT)
     *   - credentialsId: GitHub token credential ID (default: 'github-token')
     *   - container: Container to run gh CLI in (default: 'tools')
     *   - maxChars: Max characters for error context (default: 3000)
     */
    static void prFailureComment(def steps, Map args) {
        if (!args.changeId) {
            steps.echo "No CHANGE_ID set, skipping PR comment"
            return
        }

        def repo = args.repo ?: 'erauner/homelab-k8s'
        def credentialsId = args.credentialsId ?: 'github-token'
        def containerName = args.container ?: 'tools'
        def maxChars = args.maxChars ?: 3000

        def commitShort = args.gitCommit?.take(7) ?: 'unknown'
        def errorContext = args.errorContext ?: ''

        // Truncate if too long for GitHub comment
        if (errorContext.length() > maxChars) {
            errorContext = errorContext.take(maxChars) + "\n... (truncated)"
        }

        // Build comment with error context
        def prComment = """<!-- jenkins-build-failure -->
### \u274C Build Failed

**Job:** ${args.jobName}
**Build:** [#${args.buildNumber}](${args.buildUrl})
**Branch:** ${args.gitBranch}
**Commit:** [${commitShort}](https://github.com/${repo}/commit/${args.gitCommit})

<details>
<summary>Error Context (click to expand)</summary>

```
${errorContext}
```

</details>

[\uD83D\uDCCB View Pipeline](${args.buildUrl}pipeline-overview/) | [\uD83D\uDCC4 Raw Console](${args.buildUrl}consoleText)

---
_Posted by Jenkins CI_"""

        steps.container(containerName) {
            steps.withCredentials([steps.string(credentialsId: credentialsId, variable: 'GH_TOKEN')]) {
                steps.writeFile file: "${steps.env.WORKSPACE}/pr-comment.md", text: prComment
                steps.sh """
                    apk add --no-cache github-cli >/dev/null 2>&1 || true
                    echo "Posting failure comment to PR #${args.changeId}..."
                    gh pr comment "${args.changeId}" --repo "${repo}" --body-file "${steps.env.WORKSPACE}/pr-comment.md"
                """
            }
        }
    }

    /**
     * Send Discord webhook notification for build failure.
     *
     * @param steps Pipeline script context
     * @param args Map with:
     *   - credentialsId: Discord webhook credential ID (default: 'discord-webhook')
     *   - container: Container to run curl in (default: 'tools')
     *   - jobName: Job name (env.JOB_NAME)
     *   - buildNumber: Build number (env.BUILD_NUMBER)
     *   - buildUrl: Build URL (env.BUILD_URL)
     *   - gitBranch: Git branch (env.GIT_BRANCH)
     *   - gitCommit: Git commit (env.GIT_COMMIT)
     *   - changeId: PR number (env.CHANGE_ID) - optional
     *   - changeUrl: PR URL (env.CHANGE_URL) - optional
     *   - changeTitle: PR title (env.CHANGE_TITLE) - optional
     *   - changeAuthor: PR author (env.CHANGE_AUTHOR) - optional
     *   - commitInfo: Map with short, message, author (from GitUtils.lastCommitInfo)
     */
    static void discordFailure(def steps, Map args) {
        def credentialsId = args.credentialsId ?: 'discord-webhook'
        def containerName = args.container ?: 'tools'

        // Build description with commit context
        def desc = "**Job:** ${args.jobName}\\n"
        desc += "**Build:** #${args.buildNumber}\\n"
        desc += "**Branch:** ${args.gitBranch}\\n\\n"

        if (args.commitInfo) {
            desc += "**Commit:** [${args.commitInfo.short}](https://github.com/erauner/homelab-k8s/commit/${args.gitCommit})\\n"
            desc += "**Author:** ${args.commitInfo.author}\\n"
            desc += "**Message:** ${args.commitInfo.message?.take(100)}"
        } else {
            def commitShort = args.gitCommit?.take(7) ?: 'unknown'
            desc += "**Commit:** [${commitShort}](https://github.com/erauner/homelab-k8s/commit/${args.gitCommit})"
        }

        // Add PR context if this is a PR build
        if (args.changeId) {
            desc += "\\n\\n**PR:** [#${args.changeId}](${args.changeUrl}) - ${args.changeTitle ?: 'No title'}"
            desc += "\\n**PR Author:** ${args.changeAuthor ?: 'unknown'}"
        }

        def buildUrl = args.buildUrl ?: ''
        def timestamp = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))

        // Build JSON payload using JsonOutput to avoid escaping issues
        def payload = JsonOutput.toJson([
            embeds: [[
                title: "\u274C Jenkins Build Failed",
                description: desc,
                color: 15158332,  // Red
                url: "${buildUrl}pipeline-overview/",
                footer: [text: "Jenkins CI"],
                timestamp: timestamp
            ]]
        ])

        steps.container(containerName) {
            steps.withCredentials([steps.string(credentialsId: credentialsId, variable: 'DISCORD_WEBHOOK')]) {
                steps.sh """
                    curl -s -H "Content-Type: application/json" -X POST "\$DISCORD_WEBHOOK" -d '${payload}'
                """
            }
        }
    }

    /**
     * Post or update shadow manifest diff comment on GitHub PR.
     *
     * Uses HTML comment markers to find and update existing comments,
     * preventing duplicate comments on subsequent pushes.
     *
     * @param steps Pipeline script context
     * @param args Map with:
     *   - changeId: PR number (env.CHANGE_ID)
     *   - repo: GitHub repo (e.g., 'erauner12/homelab-k8s')
     *   - compareUrl: GitHub compare URL for the manifest diff
     *   - renderedDirs: Number of directories rendered (optional)
     *   - failedDirs: Number of directories that failed (optional)
     *   - commitShort: Short commit SHA (optional)
     *   - credentialsId: GitHub token credential ID (default: 'github-token')
     *   - container: Container to run gh CLI in (default: 'tools')
     */
    static void shadowDiffComment(def steps, Map args) {
        if (!args.changeId) {
            steps.echo "No CHANGE_ID set, skipping shadow diff PR comment"
            return
        }

        if (!args.compareUrl) {
            steps.echo "No compareUrl provided, skipping shadow diff PR comment"
            return
        }

        def repo = args.repo ?: 'erauner12/homelab-k8s'
        def credentialsId = args.credentialsId ?: 'github-token'
        def containerName = args.container ?: 'tools'
        def commitShort = args.commitShort ?: args.gitCommit?.take(7) ?: ''

        // Build stats line if available
        def statsLine = ""
        if (args.renderedDirs != null) {
            statsLine = "**Rendered:** ${args.renderedDirs} directories"
            if (args.failedDirs && args.failedDirs > 0) {
                statsLine += " | **Failed:** ${args.failedDirs} directories"
            }
            statsLine = "\n${statsLine}\n"
        }

        // Build commit line if available
        def commitLine = ""
        if (commitShort) {
            commitLine = "\n_Rendered from commit ${commitShort}_"
        }

        // HTML comment marker for finding/updating this comment
        def marker = "<!-- shadow-manifest-diff -->"

        def prComment = """${marker}
## \uD83D\uDCCB Manifest Preview

This PR changes the following rendered Kubernetes manifests.
${statsLine}
[\uD83D\uDD0D **View rendered manifest diff \u2192**](${args.compareUrl})
${commitLine}
---
_Shadow sync by Jenkins CI_"""

        steps.container(containerName) {
            steps.withCredentials([steps.string(credentialsId: credentialsId, variable: 'GH_TOKEN')]) {
                steps.writeFile file: "${steps.env.WORKSPACE}/shadow-diff-comment.md", text: prComment

                // Try to find and update existing comment, or create new one
                steps.sh """
                    apk add --no-cache github-cli jq >/dev/null 2>&1 || true

                    # Look for existing comment with our marker
                    COMMENT_ID=\$(gh api repos/${repo}/issues/${args.changeId}/comments --jq '.[] | select(.body | contains("${marker}")) | .id' | head -1)

                    if [ -n "\$COMMENT_ID" ]; then
                        echo "Updating existing shadow diff comment (ID: \$COMMENT_ID)..."
                        gh api repos/${repo}/issues/comments/\$COMMENT_ID -X PATCH -F body=@"${steps.env.WORKSPACE}/shadow-diff-comment.md"
                    else
                        echo "Creating new shadow diff comment on PR #${args.changeId}..."
                        gh pr comment "${args.changeId}" --repo "${repo}" --body-file "${steps.env.WORKSPACE}/shadow-diff-comment.md"
                    fi
                """
            }
        }
    }

    /**
     * Post GitHub commit status with custom context.
     *
     * Allows posting multiple statuses (e.g., build + shadow diff) without
     * overwriting each other.
     *
     * @param steps Pipeline script context
     * @param status 'SUCCESS', 'FAILURE', 'PENDING', or 'ERROR'
     * @param description Status description
     * @param targetUrl URL to link to
     * @param context Status context name (e.g., 'shadow/diff', 'ci/build')
     */
    static void githubStatusWithContext(def steps, String status, String description, String targetUrl, String context) {
        steps.githubNotify(
            status: status,
            description: description,
            targetUrl: targetUrl,
            context: context
        )
    }
}
