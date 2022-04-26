def event = currentBuild.getBuildCauses()[0].event
library 'pipeline-library'
pipeline {
  agent none
  environment {
    OPS_PROVISION_SECRET = credentials('casc-workshop-controller-provision-secret')
    CONTROLLER_PROVISION_SECRET = event.secret.toString()
  }
  options { timeout(time: 10, unit: 'MINUTES') }
  triggers {
    eventTrigger jmespathQuery("controller.action=='provision'")
  }
  stages {
    stage('Provision Managed Controller') {
      agent {
        kubernetes {
          yaml libraryResource ('podtemplates/kubectl.yml')
        }
      }
      environment {
        ADMIN_CLI_TOKEN = credentials('admin-cli-token')
        GITHUB_ORGANIZATION = event.github.organization.toString().replaceAll(" ", "-")
        GITHUB_REPOSITORY = event.github.repository.toString().toLowerCase()
        GITHUB_USER = event.github.user.toString().toLowerCase()
        CONTROLLER_FOLDER = GITHUB_ORGANIZATION.toLowerCase()
        BUNDLE_ID = "${CONTROLLER_FOLDER}-${GITHUB_REPOSITORY}"
        AVAILABILITY_PATTERN = "cloudbees-ci-casc-workshop/${GITHUB_ORGANIZATION}/${GITHUB_REPOSITORY}"
      }
      when {
        triggeredBy 'EventTriggerCause'
        environment name: 'CONTROLLER_PROVISION_SECRET', value: OPS_PROVISION_SECRET
      }
      steps {
        sh """
          rm -rf ./checkout || true
          mkdir -p checkout
          rm -rf ./${BUNDLE_ID} || true
          mkdir -p ${BUNDLE_ID}
          git clone https://github.com/${GITHUB_ORGANIZATION}/${GITHUB_REPOSITORY}.git checkout
          dir('checkout') {
            sh "mv ./controller.yaml .. || true"
            sh "cp --parents `find -name \\*.yaml*` ../${BUNDLE_ID}/"
          }
        """
      
        container('kubectl') {
          sh "ls -la ${BUNDLE_ID}"
          sh "kubectl cp --namespace cbci ${BUNDLE_ID} cjoc-0:/var/jenkins_home/jcasc-bundles-store/ -c jenkins"
        }
        waitUntil {
            script {
              def status = sh script: '''curl -s -o /dev/null -w '%{http_code}' --user "$ADMIN_CLI_TOKEN_USR:$ADMIN_CLI_TOKEN_PSW" -XPOST http://cjoc/cjoc/casc-bundle/validate-uploaded-bundle?bundleId=${BUNDLE_ID}''', returnStdout: true
              echo "returned status: ${status}"
              return (status=="200")
            }
          }
          sh '''
          curl --user "$ADMIN_CLI_TOKEN_USR:$ADMIN_CLI_TOKEN_PSW" -XPOST \
            http://cjoc/cjoc/casc-items/create-items?path=/cloudbees-ci-casc-workshop \
            --data-binary @./$BUNDLE_ID/controller.yaml -H 'Content-Type:text/yaml'
        '''
      }
    }
  }
}
