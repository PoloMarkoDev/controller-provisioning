//only runs on CJOC
import jenkins.model.*
import hudson.*
import hudson.model.*
import io.fabric8.kubernetes.client.utils.Serialization
import com.cloudbees.hudson.plugins.folder.*
import com.cloudbees.masterprovisioning.kubernetes.KubernetesImagePullSecret
import com.cloudbees.masterprovisioning.kubernetes.KubernetesMasterProvisioning
import com.cloudbees.opscenter.server.model.ManagedMaster
import com.cloudbees.opscenter.server.properties.ConnectedMasterLicenseServerProperty
import com.cloudbees.opscenter.server.properties.ConnectedMasterOwnerProperty
import com.cloudbees.opscenter.server.casc.config.ConnectedMasterCascProperty
import com.cloudbees.opscenter.server.casc.config.ConnectedMasterTokenProperty

import java.util.logging.Logger

Logger logger = Logger.getLogger("casc-workshop-provision-controller-with-casc.groovy")
String gitHubOrganization = this.args[0]
String gitHubUser = this.args[1]
String controllerName = this.args[2] 

String controllerFolderName = this.args[3]
def controllerFolder = Jenkins.instance.getItem(controllerFolderName)
if (controllerFolder == null) {
    logger.info("$controllerFolderName Folder does not exist so creating")
    controllerFolder = Jenkins.instance.createProject(Folder.class, controllerFolderName);
}
logger.info("controllerFolderName is ${controllerFolderName}")
String controllerDefinitionYaml = """
provisioning:
  cpus: 1
  disk: 20
  memory: 4000
  yaml: |
    kind: Service
    metadata:
      annotations:
        prometheus.io/scheme: 'http'
        prometheus.io/path: '/${controllerFolderName}-${controllerName}/prometheus'
        prometheus.io/port: '8080'
        prometheus.io/scrape: 'true'
    kind: "StatefulSet"
    spec:
      template:
        spec:
          containers:
          - name: "jenkins"
            env:
            - name: "SECRETS"
              value: "/var/jenkins_home/jcasc_secrets"
            - name: "GITHUB_ORGANIZATION"
              value: "/${gitHubOrganization}"
            - name: "GITHUB_USER"
              value: "/${gitHubUser}"
            volumeMounts:
            - name: "jcasc-secrets"
              mountPath: "/var/jenkins_home/jcasc_secrets"
          volumes:
          - name: "jcasc-secrets"
            csi:
              driver: secrets-store.csi.k8s.io
              readOnly: true
              volumeAttributes:
                secretProviderClass: "cbci-mc-secret-provider"
"""

def yamlMapper = Serialization.yamlMapper()
Map controllerDefinition = yamlMapper.readValue(controllerDefinitionYaml, Map.class);

logger.info("Create/update of controller '${controllerName}' beginning.")

//Either update or create the mm with this config
def existingController = controllerFolder.getItem(controllerName)
if (existingController != null) {
  return
} else {
    //item with controller name does not exist in target folder
    createController(controllerName, controllerFolderName, controllerDefinition)
}
sleep(2500)
logger.info("Finished with controller '${controllerName}'.\n")


//
//
// only function definitions below here
//
//
private void createController(String controllerName, String controllerFolderName, def controllerDefinition) {
  Logger logger = Logger.getLogger("oc-create-update-managed-controller")
  logger.info "controller '${controllerName}' does not exist yet. Creating it now."

  def configuration = new KubernetesMasterProvisioning()
  controllerDefinition.provisioning.each { k, v ->
      configuration["${k}"] = v
  }
  
  def controllerFolder = Jenkins.instance.getItem(controllerFolderName) 
  ManagedMaster controller = controllerFolder.createProject(ManagedMaster.class, controllerName)
  controller.setConfiguration(configuration)
  controller.properties.replace(new ConnectedMasterLicenseServerProperty(null))
  //needed for CasC RBAC
  controller.properties.replace(new com.cloudbees.opscenter.server.security.SecurityEnforcer.OptOutProperty(com.cloudbees.opscenter.server.sso.AuthorizationOptOutMode.INSTANCE, false, null))
  //set casc bundle, but not for CasC workshop
  controller.properties.replace(new ConnectedMasterTokenProperty(hudson.util.Secret.fromString(UUID.randomUUID().toString())))
  controller.properties.replace(new ConnectedMasterCascProperty("$controllerFolderName-$controllerName"))
  
  controller.save()
  controller.onModified()

  //ok, now we can actually boot this thing up
  logger.info "Ensuring controller '${controllerName}' starts..."
  def validActionSet = controller.getValidActionSet()
  if (validActionSet.contains(ManagedMaster.Action.ACKNOWLEDGE_ERROR)) {
      controller.acknowledgeErrorAction()
      sleep(50)
  }

  validActionSet = controller.getValidActionSet()
  if (validActionSet.contains(ManagedMaster.Action.START)) {
      controller.startAction();
      sleep(50)
  } else if (validActionSet.contains(ManagedMaster.Action.PROVISION_AND_START)) {
      controller.provisionAndStartAction();
      sleep(50)
  } else {
      throw "Cannot start the controller." as Throwable
  }
}
