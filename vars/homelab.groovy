import dev.erauner.jenkins.BuildLogUtils
import dev.erauner.jenkins.GitUtils
import dev.erauner.jenkins.NotifyUtils
import dev.erauner.jenkins.KanikoUtils
import dev.erauner.jenkins.ReleaseUtils

/**
 * Homelab Jenkins Shared Library - Public API
 *
 * This is the main entry point for the homelab shared library.
 * All methods are available as `homelab.methodName()` in Jenkinsfiles.
 *
 * Example usage:
 *   @Library('homelab') _
 *
 *   pipeline {
 *       agent { kubernetes { yaml homelab.podTemplate('golang') } }
 *       post {
 *           failure {
 *               script {
 *                   homelab.postFailurePrComment()
 *                   homelab.notifyDiscordFailure()
 *               }
 *           }
 *       }
 *   }
 */

// ============================================================================
// Build Log Utilities
// ============================================================================

/**
 * Get filtered build log focusing on error content.
 *
 * @param build The build object (currentBuild)
 * @param lines Number of log lines to fetch (default: 500)
 * @param maxLines Maximum lines to return (default: 60)
 * @return Filtered log content as a string
 */
def filteredBuildLog(def build = currentBuild, int lines = 500, int maxLines = 60) {
    return BuildLogUtils.getFilteredBuildLog(build, lines, maxLines)
}

// ============================================================================
// Git Utilities
// ============================================================================

/**
 * Get git describe output (tag or commit hash).
 */
def gitDescribe() {
    return GitUtils.describe(this)
}

/**
 * Get short git commit hash.
 */
def gitShortCommit() {
    return GitUtils.shortCommit(this)
}

/**
 * Get information about the last commit.
 * @return Map with keys: short, message, author
 */
def lastCommitInfo() {
    return GitUtils.lastCommitInfo(this)
}

/**
 * Check if files matching patterns changed since a base reference.
 *
 * This avoids using currentBuild.changeSets which causes NotSerializableException.
 *
 * @param baseRef Git reference to compare against (default: HEAD~1)
 * @param containsAny List of path prefixes to check for changes
 * @return true if any of the patterns match changed files
 */
def changedSince(String baseRef = 'HEAD~1', List<String> containsAny = []) {
    return GitUtils.changedSince(this, baseRef, containsAny)
}

// ============================================================================
// Notification Utilities
// ============================================================================

/**
 * Post GitHub commit status with Pipeline Graph View URL.
 *
 * @param status 'SUCCESS', 'FAILURE', 'PENDING', or 'ERROR'
 * @param description Status description
 * @param targetUrl URL to link to (defaults to pipeline-overview)
 */
def githubStatus(String status, String description, String targetUrl = null) {
    NotifyUtils.githubStatus(this, status, description, targetUrl)
}

/**
 * Post failure comment to GitHub PR with error context.
 *
 * Automatically uses environment variables for most parameters.
 *
 * @param args Optional overrides:
 *   - repo: GitHub repo (default: 'erauner/homelab-k8s')
 *   - credentialsId: GitHub token credential ID (default: 'github-token')
 *   - container: Container to run gh CLI in (default: 'tools')
 *   - errorContextLines: Lines to fetch from build log (default: 500)
 *   - maxChars: Max characters for error context (default: 3000)
 */
def postFailurePrComment(Map args = [:]) {
    def errorContext = filteredBuildLog(currentBuild, args.errorContextLines ?: 500)

    NotifyUtils.prFailureComment(this, [
        changeId: env.CHANGE_ID,
        repo: args.repo ?: 'erauner/homelab-k8s',
        errorContext: errorContext,
        jobName: env.JOB_NAME,
        buildNumber: env.BUILD_NUMBER,
        buildUrl: env.BUILD_URL,
        gitBranch: env.GIT_BRANCH,
        gitCommit: env.GIT_COMMIT,
        credentialsId: args.credentialsId ?: 'github-token',
        container: args.container ?: 'tools',
        maxChars: args.maxChars ?: 3000
    ])
}

/**
 * Send Discord webhook notification for build failure.
 *
 * Automatically uses environment variables for most parameters.
 *
 * @param args Optional overrides:
 *   - credentialsId: Discord webhook credential ID (default: 'discord-webhook')
 *   - container: Container to run curl in (default: 'tools')
 */
def notifyDiscordFailure(Map args = [:]) {
    def commitInfo = lastCommitInfo()

    NotifyUtils.discordFailure(this, [
        credentialsId: args.credentialsId ?: 'discord-webhook',
        container: args.container ?: 'tools',
        jobName: env.JOB_NAME,
        buildNumber: env.BUILD_NUMBER,
        buildUrl: env.BUILD_URL,
        gitBranch: env.GIT_BRANCH,
        gitCommit: env.GIT_COMMIT,
        changeId: env.CHANGE_ID,
        changeUrl: env.CHANGE_URL,
        changeTitle: env.CHANGE_TITLE,
        changeAuthor: env.CHANGE_AUTHOR,
        commitInfo: commitInfo
    ])
}

/**
 * Post shadow manifest diff comment to GitHub PR.
 *
 * Posts a comment with a link to the GitHub compare URL showing
 * the rendered manifest differences for this PR.
 *
 * @param args Map with:
 *   - compareUrl: GitHub compare URL (required)
 *   - repo: GitHub repo (default: 'erauner12/homelab-k8s')
 *   - renderedDirs: Number of rendered directories (optional)
 *   - failedDirs: Number of failed directories (optional)
 *   - credentialsId: GitHub token credential ID (default: 'github-token')
 *   - container: Container to run gh CLI in (default: 'tools')
 */
def postShadowDiffPrComment(Map args = [:]) {
    NotifyUtils.shadowDiffComment(this, [
        changeId: env.CHANGE_ID,
        repo: args.repo ?: 'erauner12/homelab-k8s',
        compareUrl: args.compareUrl,
        renderedDirs: args.renderedDirs,
        failedDirs: args.failedDirs,
        commitShort: env.GIT_COMMIT?.take(7),
        gitCommit: env.GIT_COMMIT,
        credentialsId: args.credentialsId ?: 'github-token',
        container: args.container ?: 'tools'
    ])
}

/**
 * Post GitHub commit status with custom context.
 *
 * Allows posting multiple statuses without overwriting each other.
 *
 * @param status 'SUCCESS', 'FAILURE', 'PENDING', or 'ERROR'
 * @param description Status description
 * @param targetUrl URL to link to
 * @param context Status context name (e.g., 'shadow/diff')
 */
def githubStatusWithContext(String status, String description, String targetUrl, String context) {
    NotifyUtils.githubStatusWithContext(this, status, description, targetUrl, context)
}

// ============================================================================
// Kaniko Build Utilities
// ============================================================================

/**
 * Build and push container image using Kaniko.
 *
 * @param args Map with:
 *   - dockerfile: Path to Dockerfile (required)
 *   - context: Build context directory (default: '.')
 *   - destinations: List of image destinations (required)
 *   - buildArgs: Map of build arguments (optional)
 *   - container: Kaniko container name (default: 'kaniko')
 */
def kanikoBuildAndPush(Map args) {
    KanikoUtils.buildAndPush(this, args)
}

/**
 * Build and push with homelab defaults.
 *
 * Automatically adds version, commit, and build date as build args.
 *
 * @param args Map with:
 *   - image: Base image name without tag (required)
 *   - version: Version tag (required)
 *   - dockerfile: Path to Dockerfile (default: 'Dockerfile')
 *   - context: Build context (default: '.')
 *   - alsoTagLatest: Whether to also tag as :latest (default: true)
 */
def homelabBuild(Map args) {
    KanikoUtils.homelabBuild(this, args)
}

// ============================================================================
// Release Utilities
// ============================================================================

/**
 * Calculate the next pre-release version based on current git tags.
 *
 * Version progression:
 * - v1.0.0 -> v1.1.0-rc.1 (stable -> next minor pre-release)
 * - v1.1.0-rc.1 -> v1.1.0-rc.2 (increment rc number)
 *
 * Uses .findAll() internally to avoid Matcher serialization issues.
 *
 * @return Map with keys: currentTag, newVersion, baseVersion, rcNum
 */
def calculateNextPreRelease() {
    return ReleaseUtils.calculateNextPreRelease(this)
}

/**
 * Calculate the next stable release version.
 *
 * @param bumpType One of: 'major', 'minor', 'patch' (default: 'minor')
 * @return Map with keys: currentTag, newVersion
 */
def calculateNextStable(String bumpType = 'minor') {
    return ReleaseUtils.calculateNextStable(this, bumpType)
}

/**
 * Create and push a git tag.
 *
 * Must be called within a withCredentials block that sets GIT_USER and GIT_TOKEN.
 *
 * @param args Map with:
 *   - version: Tag version (required, e.g., 'v1.0.0-rc.1')
 *   - message: Tag message (optional)
 *   - repo: GitHub repo in format 'owner/name' (required)
 */
def createAndPushTag(Map args) {
    ReleaseUtils.createAndPushTag(this, args)
}

/**
 * Create a GitHub release via API.
 *
 * Must be called within a withCredentials block that sets GIT_TOKEN.
 *
 * @param args Map with:
 *   - version: Release version/tag (required)
 *   - repo: GitHub repo in format 'owner/name' (required)
 *   - body: Release body/description (optional)
 *   - prerelease: Whether this is a pre-release (default: true if version contains '-rc.')
 *   - draft: Whether this is a draft (default: false)
 */
def createGithubRelease(Map args) {
    ReleaseUtils.createGithubRelease(this, args)
}

/**
 * Full release workflow: calculate version, create tag, create GitHub release.
 *
 * This is a convenience method that combines all release steps.
 * Must be called within a withCredentials block that sets GIT_USER and GIT_TOKEN.
 *
 * Example:
 *   withCredentials([usernamePassword(
 *       credentialsId: 'github-app',
 *       usernameVariable: 'GIT_USER',
 *       passwordVariable: 'GIT_TOKEN'
 *   )]) {
 *       def result = homelab.createPreRelease([
 *           repo: 'erauner/my-repo',
 *           imageName: 'docker.nexus.erauner.dev/homelab/my-image',
 *           imageTag: env.VERSION
 *       ])
 *       env.NEW_VERSION = result.version
 *   }
 *
 * @param args Map with:
 *   - repo: GitHub repo in format 'owner/name' (required)
 *   - imageTag: Docker image tag to include in release notes (optional)
 *   - imageName: Docker image name to include in release notes (optional)
 *   - stable: If true, create a stable release instead of pre-release (default: false)
 * @return Map with keys: version (the new version string)
 */
def createPreRelease(Map args) {
    return ReleaseUtils.createPreRelease(this, args)
}

// ============================================================================
// Pod Templates
// ============================================================================

/**
 * Get a pod template YAML for Kubernetes agent.
 *
 * Available templates:
 *   - 'gitops': Full GitOps validation pod with golang, tools, kaniko containers
 *   - 'golang': Lightweight Go development pod
 *   - 'python': Python development pod
 *   - 'node': Node.js development pod
 *   - 'flutter': Flutter/Dart development pod
 *   - 'tools': Alpine with kubectl, git, and other CLI tools
 *   - 'kaniko': Image build pod with alpine + kaniko (for non-Go projects)
 *   - 'kaniko-go': Image build pod with golang + kaniko (for Go projects)
 *
 * @param template Template name (default: 'gitops')
 * @return Pod YAML string
 */
def podTemplate(String template = 'gitops') {
    switch (template) {
        case 'gitops':
            return libraryResource('podTemplates/homelab-gitops-validation.yaml')
        case 'golang':
            return golangPodTemplate()
        case 'python':
            return pythonPodTemplate()
        case 'node':
            return nodePodTemplate()
        case 'flutter':
            return flutterPodTemplate()
        case 'tools':
            return toolsPodTemplate()
        case 'kaniko':
            return kanikoPodTemplate()
        case 'kaniko-go':
            return kanikoGoPodTemplate()
        default:
            error "Unknown pod template: ${template}. Available: gitops, golang, python, node, flutter, tools, kaniko, kaniko-go"
    }
}

/**
 * Generate Go development pod template.
 */
def golangPodTemplate() {
    return '''
apiVersion: v1
kind: Pod
metadata:
  labels:
    workload-type: ci-builds
spec:
  containers:
  - name: jnlp
    image: jenkins/inbound-agent:3355.v388858a_47b_33-3-jdk21
    resources:
      requests:
        cpu: 100m
        memory: 256Mi
      limits:
        cpu: 500m
        memory: 512Mi
  - name: golang
    image: golang:1.25-alpine
    command: ['cat']
    tty: true
    workingDir: /home/jenkins/agent
    env:
    - name: GOCACHE
      value: /go-cache/cache
    - name: GOMODCACHE
      value: /go-cache/mod
    - name: GOPROXY
      value: "https://athens.erauner.dev,direct"
    - name: GONOSUMDB
      value: "github.com/erauner/*"
    - name: GOFLAGS
      value: "-buildvcs=false"
    volumeMounts:
    - name: go-cache
      mountPath: /go-cache
    resources:
      requests:
        cpu: 200m
        memory: 512Mi
      limits:
        cpu: 2000m
        memory: 2Gi
  volumes:
  - name: go-cache
    persistentVolumeClaim:
      claimName: jenkins-go-cache
'''
}

/**
 * Generate Python development pod template.
 */
def pythonPodTemplate() {
    return '''
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: jnlp
    image: jenkins/inbound-agent:3355.v388858a_47b_33-3-jdk21
    resources:
      requests:
        cpu: 100m
        memory: 256Mi
      limits:
        cpu: 500m
        memory: 512Mi
  - name: python
    image: python:3.12-slim
    command: ['sleep', '3600']
    resources:
      requests:
        cpu: 200m
        memory: 512Mi
      limits:
        cpu: 1000m
        memory: 1Gi
'''
}

/**
 * Generate Node.js development pod template.
 */
def nodePodTemplate() {
    return '''
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: jnlp
    image: jenkins/inbound-agent:3355.v388858a_47b_33-3-jdk21
    resources:
      requests:
        cpu: 100m
        memory: 256Mi
      limits:
        cpu: 500m
        memory: 512Mi
  - name: node
    image: node:22-alpine
    command: ['sleep', '3600']
    resources:
      requests:
        cpu: 200m
        memory: 512Mi
      limits:
        cpu: 1000m
        memory: 1Gi
'''
}

/**
 * Generate Flutter/Dart development pod template.
 *
 * Uses the Cirrus Labs Flutter image which includes:
 * - Flutter SDK
 * - Dart SDK
 * - Android SDK (for mobile builds)
 */
def flutterPodTemplate() {
    return '''
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: jnlp
    image: jenkins/inbound-agent:3355.v388858a_47b_33-3-jdk21
    resources:
      requests:
        cpu: 100m
        memory: 256Mi
      limits:
        cpu: 500m
        memory: 512Mi
  - name: flutter
    image: ghcr.io/cirruslabs/flutter:3.32.5
    command: ['sleep', '3600']
    resources:
      requests:
        cpu: 500m
        memory: 2Gi
      limits:
        cpu: 2000m
        memory: 4Gi
  - name: tools
    image: alpine/k8s:1.31.3
    command: ['sleep', '3600']
    resources:
      requests:
        cpu: 50m
        memory: 64Mi
      limits:
        cpu: 200m
        memory: 256Mi
'''
}

/**
 * Generate tools-only pod template.
 */
def toolsPodTemplate() {
    return '''
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: jnlp
    image: jenkins/inbound-agent:3355.v388858a_47b_33-3-jdk21
    resources:
      requests:
        cpu: 100m
        memory: 256Mi
      limits:
        cpu: 500m
        memory: 512Mi
  - name: tools
    image: alpine/k8s:1.31.3
    command: ['sleep', '3600']
    resources:
      requests:
        cpu: 100m
        memory: 256Mi
'''
}

/**
 * Generate Kaniko image build pod template.
 *
 * Includes:
 * - jnlp: Jenkins agent
 * - alpine: For git/curl/release operations
 * - kaniko: For building and pushing container images
 *
 * Requires 'nexus-registry-credentials' secret in the namespace.
 */
def kanikoPodTemplate() {
    return '''
apiVersion: v1
kind: Pod
metadata:
  labels:
    workload-type: ci-builds
spec:
  imagePullSecrets:
  - name: nexus-registry-credentials
  containers:
  - name: jnlp
    image: jenkins/inbound-agent:3355.v388858a_47b_33-3-jdk21
    resources:
      requests:
        cpu: 100m
        memory: 256Mi
      limits:
        cpu: 500m
        memory: 512Mi
  - name: alpine
    image: alpine:3.20
    command: ['cat']
    tty: true
    workingDir: /home/jenkins/agent
    resources:
      requests:
        cpu: 50m
        memory: 64Mi
      limits:
        cpu: 200m
        memory: 128Mi
  - name: kaniko
    image: gcr.io/kaniko-project/executor:debug
    command: ['sleep', '3600']
    volumeMounts:
    - name: nexus-creds
      mountPath: /kaniko/.docker
    resources:
      requests:
        cpu: 500m
        memory: 1Gi
      limits:
        cpu: 1000m
        memory: 2Gi
  volumes:
  - name: nexus-creds
    secret:
      secretName: nexus-registry-credentials
'''
}

/**
 * Generate Kaniko + Go pod template for Go projects.
 *
 * Includes:
 * - jnlp: Jenkins agent
 * - golang: For testing and building Go code
 * - kaniko: For building and pushing container images
 *
 * Note: workingDir is intentionally NOT set - Jenkins Kubernetes plugin
 * automatically sets it to the job workspace for multibranch pipelines.
 * Requires 'nexus-registry-credentials' secret in the namespace.
 */
def kanikoGoPodTemplate() {
    return '''
apiVersion: v1
kind: Pod
metadata:
  labels:
    workload-type: ci-builds
spec:
  imagePullSecrets:
  - name: nexus-registry-credentials
  containers:
  - name: jnlp
    image: jenkins/inbound-agent:3355.v388858a_47b_33-3-jdk21
    resources:
      requests:
        cpu: 100m
        memory: 256Mi
      limits:
        cpu: 500m
        memory: 512Mi
  - name: golang
    image: golang:1.25-alpine
    command: ['cat']
    tty: true
    env:
    - name: GOPROXY
      value: https://athens.erauner.dev,direct
    - name: GONOSUMDB
      value: github.com/erauner/*
    resources:
      requests:
        cpu: 500m
        memory: 512Mi
      limits:
        cpu: 1000m
        memory: 1Gi
  - name: kaniko
    image: gcr.io/kaniko-project/executor:debug
    command: ['sleep', '3600']
    volumeMounts:
    - name: nexus-creds
      mountPath: /kaniko/.docker
    resources:
      requests:
        cpu: 500m
        memory: 1Gi
      limits:
        cpu: 1000m
        memory: 2Gi
  volumes:
  - name: nexus-creds
    secret:
      secretName: nexus-registry-credentials
'''
}

// ============================================================================
// Resource Loading
// ============================================================================

/**
 * Load a library resource file.
 *
 * @param path Path relative to resources/ directory
 * @return File contents as string
 */
def resource(String path) {
    return libraryResource(path)
}
