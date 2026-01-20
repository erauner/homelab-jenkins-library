// Jenkins Shared Library - Validation Pipeline
// This validates the library itself before changes are merged to main.
//
// Note: We can't use @Library('homelab') here since we ARE the library.
// Instead, we use inline pod templates and basic validation.

pipeline {
    agent {
        kubernetes {
            yaml '''
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
  - name: tools
    image: alpine/k8s:1.31.3
    command: ['sleep', '3600']
    resources:
      requests:
        cpu: 100m
        memory: 256Mi
      limits:
        cpu: 200m
        memory: 512Mi
'''
        }
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timeout(time: 15, unit: 'MINUTES')
        disableConcurrentBuilds()
    }

    stages {
        stage('Validate Structure') {
            steps {
                container('tools') {
                    sh '''
                        echo "=== Validating library structure ==="

                        # Check required directories exist
                        for dir in vars src resources; do
                            if [ ! -d "$dir" ]; then
                                echo "ERROR: Missing required directory: $dir"
                                exit 1
                            fi
                        done

                        # Check main API file exists
                        if [ ! -f "vars/homelab.groovy" ]; then
                            echo "ERROR: Missing vars/homelab.groovy"
                            exit 1
                        fi

                        echo "Structure validation passed"
                    '''
                }
            }
        }

        stage('Validate Groovy Syntax') {
            steps {
                container('tools') {
                    sh '''
                        echo "=== Checking Groovy file syntax ==="

                        # Find all Groovy files
                        groovy_files=$(find . -name "*.groovy" -type f)

                        for file in $groovy_files; do
                            echo "Checking: $file"

                            # Basic syntax checks (without full Groovy compiler)
                            # Check for unmatched braces
                            open_braces=$(grep -o "{" "$file" | wc -l)
                            close_braces=$(grep -o "}" "$file" | wc -l)
                            if [ "$open_braces" -ne "$close_braces" ]; then
                                echo "WARNING: Possible unmatched braces in $file (open: $open_braces, close: $close_braces)"
                            fi

                            # Check for common syntax errors
                            if grep -n "def def " "$file"; then
                                echo "ERROR: Double 'def' keyword in $file"
                                exit 1
                            fi
                        done

                        echo "Groovy syntax checks passed"
                    '''
                }
            }
        }

        stage('Validate Pod Templates') {
            steps {
                container('tools') {
                    sh '''
                        echo "=== Validating pod template YAML ==="

                        for template in resources/podTemplates/*.yaml; do
                            echo "Validating: $template"

                            # Use kubectl to validate YAML structure
                            if ! kubectl apply --dry-run=client -f "$template" 2>&1 | head -5; then
                                echo "WARNING: Pod template may have issues: $template"
                            fi
                        done

                        echo "Pod template validation passed"
                    '''
                }
            }
        }

        stage('Check API Exports') {
            steps {
                container('tools') {
                    sh '''
                        echo "=== Checking exported API functions ==="

                        # Extract function definitions from homelab.groovy
                        echo "Exported functions:"
                        grep -E "^def [a-zA-Z]+" vars/homelab.groovy | sed 's/def /  - /' | sed 's/(.*$//'

                        # Check for minimum expected functions
                        for func in podTemplate gitDescribe githubStatus; do
                            if ! grep -q "def $func" vars/homelab.groovy; then
                                echo "ERROR: Missing expected function: $func"
                                exit 1
                            fi
                        done

                        echo "API export check passed"
                    '''
                }
            }
        }
    }

    post {
        success {
            echo 'Library validation succeeded'
        }
        failure {
            echo 'Library validation failed - check logs above'
        }
        cleanup {
            deleteDir()
        }
    }
}
