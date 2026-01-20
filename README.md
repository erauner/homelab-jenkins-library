# Homelab Jenkins Shared Library

Reusable Jenkins pipeline components for homelab CI/CD.

## Quick Start

```groovy
@Library('homelab') _

pipeline {
    agent { kubernetes { yaml homelab.podTemplate('golang') } }

    stages {
        stage('Build') {
            steps {
                container('golang') {
                    sh 'go build ./...'
                }
            }
        }
    }

    post {
        failure {
            script {
                homelab.postFailurePrComment()
                homelab.notifyDiscordFailure()
            }
        }
        success {
            script {
                homelab.githubStatus('SUCCESS', 'Build succeeded')
            }
        }
    }
}
```

## Available Functions

### Git Utilities

| Function | Description |
|----------|-------------|
| `homelab.gitDescribe()` | Get git describe output (tag or commit hash) |
| `homelab.gitShortCommit()` | Get short commit hash |
| `homelab.lastCommitInfo()` | Get map with `short`, `message`, `author` |
| `homelab.changedSince(baseRef, patterns)` | Check if files matching patterns changed |

### Notifications

| Function | Description |
|----------|-------------|
| `homelab.githubStatus(status, description)` | Post GitHub commit status with Pipeline Graph View URL |
| `homelab.postFailurePrComment([args])` | Post formatted failure comment to PR with error context |
| `homelab.notifyDiscordFailure([args])` | Send Discord webhook notification for build failure |

### Build Utilities

| Function | Description |
|----------|-------------|
| `homelab.filteredBuildLog(build, lines, maxLines)` | Get filtered build log focusing on errors |
| `homelab.kanikoBuildAndPush(args)` | Build and push container image using Kaniko |
| `homelab.homelabBuild(args)` | Build with homelab defaults (version, commit, date) |

### Pod Templates

| Function | Description |
|----------|-------------|
| `homelab.podTemplate('gitops')` | Full GitOps validation pod (golang, tools, kaniko) |
| `homelab.podTemplate('golang')` | Lightweight Go development pod |
| `homelab.podTemplate('python')` | Python development pod |
| `homelab.podTemplate('node')` | Node.js development pod |
| `homelab.podTemplate('tools')` | Alpine with kubectl and CLI tools |

## Detailed Usage

### Checking for Changed Files

```groovy
stage('Build Image') {
    when {
        expression {
            // Check if Dockerfile or source changed
            return homelab.changedSince('HEAD~1', ['Dockerfile', 'src/'])
        }
    }
    steps {
        // ...
    }
}
```

### Kaniko Image Builds

```groovy
stage('Build Image') {
    steps {
        script {
            homelab.homelabBuild([
                image: 'docker.nexus.erauner.dev/homelab/myapp',
                version: homelab.gitDescribe(),
                dockerfile: 'Dockerfile',
                context: '.'
            ])
        }
    }
}
```

Or with full control:

```groovy
homelab.kanikoBuildAndPush([
    dockerfile: 'Dockerfile',
    context: '.',
    destinations: [
        'docker.nexus.erauner.dev/homelab/myapp:v1.0.0',
        'docker.nexus.erauner.dev/homelab/myapp:latest'
    ],
    buildArgs: [
        VERSION: 'v1.0.0',
        COMMIT: env.GIT_COMMIT
    ]
])
```

### PR Failure Comments

Automatically posts a formatted comment to the PR with error context:

```groovy
post {
    failure {
        script {
            homelab.postFailurePrComment([
                repo: 'erauner12/my-repo'  // Optional, defaults to homelab-k8s
            ])
        }
    }
}
```

### Discord Notifications

```groovy
post {
    failure {
        script {
            homelab.notifyDiscordFailure([
                credentialsId: 'discord-webhook',  // Optional, this is the default
                container: 'tools'                  // Optional, this is the default
            ])
        }
    }
}
```

## Directory Structure

```
homelab-jenkins-library/
├── vars/
│   └── homelab.groovy          # Public API (homelab.* functions)
├── src/dev/erauner/jenkins/
│   ├── BuildLogUtils.groovy    # @NonCPS log filtering
│   ├── GitUtils.groovy         # Git operations
│   ├── NotifyUtils.groovy      # GitHub/Discord/PR comments
│   ├── KanikoUtils.groovy      # Container image builds
│   └── ReleaseUtils.groovy     # Go release operations
├── resources/
│   └── podTemplates/
│       └── homelab-gitops-validation.yaml
├── Jenkinsfile                 # Library validation pipeline
└── README.md
```

## Configuration

The library is configured in Jenkins via JCasC (in homelab-k8s: `apps/jenkins/base/values.yaml`):

```yaml
controller:
  JCasC:
    configScripts:
      global-shared-library: |
        unclassified:
          globalLibraries:
            libraries:
              - name: "homelab"
                defaultVersion: "main"
                allowVersionOverride: true
                implicit: false
                retriever:
                  modernSCM:
                    scm:
                      git:
                        remote: "https://github.com/erauner/homelab-jenkins-library.git"
                        credentialsId: "github-app"
```

## Required Credentials

| Credential ID | Description | Used By |
|---------------|-------------|---------|
| `github-app` | GitHub App for repo access | Library loading |
| `github-token` | GitHub PAT for gh CLI | `postFailurePrComment()` |
| `discord-webhook` | Discord webhook URL | `notifyDiscordFailure()` |

## Adding to Other Repos

1. Ensure the repo is in the org folder's `sourceRegexFilter` (in homelab-k8s: `apps/jenkins/base/values.yaml`)
2. Add a `Jenkinsfile` that loads the library:

```groovy
@Library('homelab') _

pipeline {
    agent { kubernetes { yaml homelab.podTemplate('python') } }

    stages {
        stage('Test') {
            steps {
                container('python') {
                    sh 'pytest'
                }
            }
        }
    }

    post {
        failure {
            script {
                homelab.postFailurePrComment([repo: 'erauner12/my-repo'])
                homelab.notifyDiscordFailure()
            }
        }
        success {
            script {
                homelab.githubStatus('SUCCESS', 'Tests passed')
            }
        }
    }
}
```

## Versioning

The library uses `defaultVersion: "main"` for simplicity. For stricter version control:

```groovy
@Library('homelab@v1.0.0') _
```

Tag the repo and update `allowVersionOverride: true` in JCasC to enable this.

## Development

### Testing Library Changes

To test library changes before merging to main:

1. Push your branch to the library repo
2. In a test pipeline, reference the branch:
   ```groovy
   @Library('homelab@my-feature-branch') _
   ```
3. The branch-specific library will be loaded

### CI Pipeline

This repo has its own Jenkinsfile for validation:
- Groovy syntax checking
- Pod template YAML validation
- Integration tests (dry-run mode)

Changes to this library are validated before merge.

## Troubleshooting

### Library not loading

1. Check Jenkins logs for library fetch errors
2. Verify `github-app` credential has access to the repo
3. Ensure `workflow-cps-global-lib` plugin is installed

### @NonCPS errors

The `BuildLogUtils.getFilteredBuildLog()` function uses `@NonCPS` to avoid serialization issues. Required script approvals are already configured in JCasC:

```yaml
script-approval: |
  security:
    scriptApproval:
      approvedSignatures:
        - "method hudson.model.Run getLog int"
        - "method org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper getRawBuild"
```

### Container not found

Pod templates define specific container names. Make sure you're using the right container:

- `gitops` template: `golang`, `tools`, `kaniko`
- `golang` template: `golang`
- `python` template: `python`
- `node` template: `node`
- `tools` template: `tools`
# Trigger CI
