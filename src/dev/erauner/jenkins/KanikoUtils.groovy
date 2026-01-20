package dev.erauner.jenkins

/**
 * Utility class for Kaniko container image builds.
 *
 * Kaniko builds container images without Docker daemon, making it safe
 * for multi-tenant Kubernetes environments.
 */
class KanikoUtils implements Serializable {
    private static final long serialVersionUID = 1L

    /**
     * Build and push container image using Kaniko.
     *
     * @param steps Pipeline script context
     * @param args Map with:
     *   - dockerfile: Path to Dockerfile (required)
     *   - context: Build context directory (default: '.')
     *   - destinations: List of image destinations (required)
     *   - buildArgs: Map of build arguments (optional)
     *   - dockerConfigPath: Path to Docker config.json (default: '/kaniko/.docker/config.json')
     *   - pushRetry: Number of push retries (default: 3)
     *   - container: Kaniko container name (default: 'kaniko')
     */
    static void buildAndPush(def steps, Map args) {
        def dockerfile = args.dockerfile
        def context = args.context ?: '.'
        def destinations = args.destinations
        def buildArgs = args.buildArgs ?: [:]
        def dockerConfigPath = args.dockerConfigPath ?: '/kaniko/.docker/config.json'
        def pushRetry = args.pushRetry ?: 3
        def containerName = args.container ?: 'kaniko'

        if (!dockerfile) {
            steps.error "KanikoUtils.buildAndPush: 'dockerfile' is required"
        }
        if (!destinations || destinations.isEmpty()) {
            steps.error "KanikoUtils.buildAndPush: 'destinations' is required and must not be empty"
        }

        steps.container(containerName) {
            // Validate registry credentials are mounted
            steps.sh """
                if [ ! -f ${dockerConfigPath} ]; then
                    echo "ERROR: Registry credentials not found at ${dockerConfigPath}"
                    exit 1
                fi
                echo "✓ Registry credentials found"
            """

            // Build destination arguments
            def destArgs = destinations.collect { "--destination=${it}" }.join(' ')

            // Build build-arg arguments
            def buildArgArgs = buildArgs.collect { k, v -> "--build-arg=${k}=${v}" }.join(' ')

            // Execute Kaniko build
            steps.sh """
                echo "=== Building container image ==="
                echo "Dockerfile: ${dockerfile}"
                echo "Context: ${context}"
                echo "Destinations: ${destinations.join(', ')}"

                /kaniko/executor \\
                    --dockerfile=${dockerfile} \\
                    --context=${context} \\
                    ${destArgs} \\
                    ${buildArgArgs} \\
                    --push-retry=${pushRetry}

                echo "=== Image pushed successfully ==="
            """
        }
    }

    /**
     * Build and push with common defaults for homelab images.
     *
     * Automatically adds version, commit, and build date as build args.
     *
     * @param steps Pipeline script context
     * @param args Map with:
     *   - image: Base image name without tag (e.g., 'docker.nexus.erauner.dev/homelab/myapp')
     *   - version: Version tag (required)
     *   - commit: Git commit hash (optional, uses GIT_COMMIT env if not provided)
     *   - dockerfile: Path to Dockerfile (default: 'Dockerfile')
     *   - context: Build context (default: '.')
     *   - alsoTagLatest: Whether to also tag as :latest (default: true)
     *   - container: Kaniko container name (default: 'kaniko')
     */
    static void homelabBuild(def steps, Map args) {
        def image = args.image
        def version = args.version
        def commit = args.commit ?: steps.env.GIT_COMMIT?.take(7) ?: 'unknown'
        def dockerfile = args.dockerfile ?: 'Dockerfile'
        def context = args.context ?: '.'
        def alsoTagLatest = args.alsoTagLatest != false
        def containerName = args.container ?: 'kaniko'

        if (!image) {
            steps.error "KanikoUtils.homelabBuild: 'image' is required"
        }
        if (!version) {
            steps.error "KanikoUtils.homelabBuild: 'version' is required"
        }

        def destinations = ["${image}:${version}"]
        if (alsoTagLatest) {
            destinations.add("${image}:latest")
        }

        def buildDate = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))

        buildAndPush(steps, [
            dockerfile: dockerfile,
            context: context,
            destinations: destinations,
            buildArgs: [
                VERSION: version,
                COMMIT: commit,
                BUILD_DATE: buildDate
            ],
            container: containerName
        ])
    }
}
