package com.morpheusdata.xen

import com.morpheusdata.PrepareHostResponse
import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.HostProvisionProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.core.providers.WorkloadProvisionProvider
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.NetworkUtility
import com.morpheusdata.model.*
import com.morpheusdata.model.provisioning.HostRequest
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.request.ImportWorkloadRequest
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.response.ImportWorkloadResponse
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ProvisionResponse
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.xen.util.DefaultPollingStrategy
import com.morpheusdata.xen.util.PollingStrategy
import com.morpheusdata.xen.util.XenComputeUtility
import com.xensource.xenapi.SR
import com.xensource.xenapi.VM
import groovy.util.logging.Slf4j

@Slf4j
class XenserverProvisionProvider extends AbstractProvisionProvider implements WorkloadProvisionProvider, HostProvisionProvider, ProvisionProvider.BlockDeviceNameFacet, WorkloadProvisionProvider.ResizeFacet, HostProvisionProvider.ResizeFacet, ProvisionProvider.HypervisorConsoleFacet, WorkloadProvisionProvider.ImportWorkloadFacet {

	public static final String PROVIDER_NAME = 'XCP-ng'
	public static final String PROVIDER_CODE = 'xen'
	public static final String PROVISION_TYPE_CODE = 'xen'

	protected MorpheusContext context
	protected XenserverPlugin plugin
	protected PollingStrategy pollingStrategy

	public XenserverProvisionProvider(XenserverPlugin plugin, MorpheusContext context) {
		super()
		this.@context = context
		this.@plugin = plugin
		this.pollingStrategy = new DefaultPollingStrategy()
	}

	/**
	 * Set the polling strategy (primarily for testing)
	 * @param strategy The polling strategy to use
	 */
	void setPollingStrategy(PollingStrategy strategy) {
		this.pollingStrategy = strategy
	}

	/**
	 * This method is called before runWorkload and provides an opportunity to perform action or obtain configuration
	 * that will be needed in runWorkload. At the end of this method, if deploying a ComputeServer with a VirtualImage,
	 * the sourceImage on ComputeServer should be determined and saved.
	 * @param workload the Workload object we intend to provision along with some of the associated data needed to determine
	 *                 how best to provision the workload
	 * @param workloadRequest the RunWorkloadRequest object containing the various configurations that may be needed
	 *                        in running the Workload. This will be passed along into runWorkload
	 * @param opts additional configuration options that may have been passed during provisioning
	 * @return Response from API
	 */
	@Override
	ServiceResponse<PrepareWorkloadResponse> prepareWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		ServiceResponse<PrepareWorkloadResponse> resp = new ServiceResponse<PrepareWorkloadResponse>(
			true, // successful
			'', // no message
			null, // no errors
			new PrepareWorkloadResponse(workload:workload) // adding the workload to the response for convenience
		)
		return resp
	}

	/**
	 * Some older clouds have a provision type code that is the exact same as the cloud code. This allows one to set it
	 * to match and in doing so the provider will be fetched via the cloud providers {CloudProvider#getDefaultProvisionTypeCode()} method.
	 * @return code for overriding the ProvisionType record code property
	 */
	@Override
	String getProvisionTypeCode() {
		return PROVISION_TYPE_CODE
	}

	/**
	 * Provide an icon to be displayed for ServicePlans, VM detail page, etc.
	 * where a circular icon is displayed
	 * @since 0.13.6
	 * @return Icon
	 */
	@Override
	Icon getCircularIcon() {
		return new Icon(path:'xcpng-circular-light.svg', darkPath:'xcpng-circular-dark.svg')
	}

	/**
	 * Provides a Collection of OptionType inputs that need to be made available to various provisioning Wizards
	 * @return Collection of OptionTypes
	 */
	@Override
	Collection<OptionType> getOptionTypes() {
		Collection<OptionType> options = []
		options << new OptionType(
				name: 'skip agent install',
				code: 'provisionType.xenserver.noAgent',
				category: 'provisionType.xenserver',
				inputType: OptionType.InputType.CHECKBOX,
				fieldName: 'noAgent',
				fieldContext: 'config',
				fieldCode: 'gomorpheus.optiontype.SkipAgentInstall',
				fieldLabel: 'Skip Agent Install',
				fieldGroup:'Advanced Options',
				displayOrder: 4,
				required: false,
				enabled: true,
				editable:false,
				global:false,
				placeHolder:null,
				helpBlock:'Skipping Agent installation will result in a lack of logging and guest operating system statistics. Automation scripts may also be adversely affected.',
				defaultValue:null,
				custom:false,
				fieldClass:null
		)
		// TODO: create some option types for provisioning and add them to collection
		return options
	}

	/**
	 * Provides a Collection of OptionType inputs for configuring node types
	 * @since 0.9.0
	 * @return Collection of OptionTypes
	 */
	@Override
	Collection<OptionType> getNodeOptionTypes() {
		Collection<OptionType> nodeOptions = []

		nodeOptions << new OptionType(
				name: 'virtual image',
				category:'provisionType.xen.custom',
				code: 'provisionType.xen.custom.containerType.virtualImageId',
				fieldContext: 'domain',
				fieldName: 'virtualImage.id',
				fieldCode: 'gomorpheus.label.vmImage',
				fieldLabel: 'VM Image',
				fieldGroup: null,
				inputType: OptionType.InputType.SELECT,
				displayOrder:10,
				fieldClass:null,
				required: false,
				editable: true,
				noSelection: 'Select',
				optionSourceType: "xcpng",
				optionSource: 'xcpVirtualImages'
		)
		nodeOptions << new OptionType(
				name: 'osType',
				category:'provisionType.xen.custom',
				code: 'provisionType.xen.custom.containerType.osTypeId',
				fieldContext: 'domain',
				fieldName: 'osType.id',
				fieldCode: 'gomorpheus.label.osType',
				fieldLabel: 'OsType',
				fieldGroup: null,
				inputType: OptionType.InputType.SELECT,
				displayOrder:15,
				fieldClass:null,
				required: false,
				editable: true,
				noSelection: 'Select',
				optionSource: 'osTypes'
		)
		nodeOptions << new OptionType(
				name: 'mount logs',
				category: "provisionType.xen.custom",
				code: 'provisionType.xen.custom.containerType.mountLogs',
				fieldContext: 'domain',
				fieldName: 'mountLogs',
				fieldCode: 'gomorpheus.optiontype.LogFolder',
				fieldLabel: 'Log Folder',
				fieldGroup: null,
				inputType: OptionType.InputType.TEXT,
				displayOrder: 20,
				required: false,
				enabled:true,
				editable: true,
				global:false,
				placeHolder:null,
				defaultValue:null,
				custom:false,
				fieldClass:null
		)
		nodeOptions << new OptionType(
				name: 'mount config',
				category: "provisionType.xen.custom",
				code: 'provisionType.xen.custom.containerType.mountConfig',
				fieldContext: 'domain',
				fieldName: 'mountConfig',
				fieldCode: 'gomorpheus.optiontype.ConfigFolder',
				fieldLabel: 'Config Folder',
				fieldGroup: null,
				inputType: OptionType.InputType.TEXT,
				displayOrder: 30,
				required: false,
				enabled:true,
				editable: true,
				global:false,
				placeHolder:null,
				defaultValue:null,
				custom:false,
				fieldClass:null,
		)
		nodeOptions << new OptionType(
				name: 'mount data',
				category: "provisionType.xen.custom",
				code: 'provisionType.xen.custom.containerType.mountData',
				fieldContext: 'domain',
				fieldName: 'mountData',
				fieldCode: 'gomorpheus.optiontype.DeployFolder',
				fieldLabel: 'Deploy Folder',
				fieldGroup: null,
				inputType: OptionType.InputType.TEXT,
				displayOrder: 40,
				required: false,
				enabled:true,
				editable: true,
				global:false,
				placeHolder:null,
				helpTextI18nCode: "gomorpheus.help.deployFolder",
				defaultValue:null,
				custom:false,
				fieldClass:null
		)
		nodeOptions << new OptionType(
				code:'provisionType.xen.custom.instanceType.backupType',
				inputType: OptionType.InputType.HIDDEN,
				name:'backup type',
				category:'provisionType.xen.custom',
				fieldName:'backupType',
				fieldCode: 'gomorpheus.optiontype.BackupType',
				fieldLabel:'Backup Type',
				fieldContext:'instanceType',
				fieldGroup: null,
				required:false,
				enabled:true,
				editable:false,
				global:false,
				placeHolder:null,
				helpBlock:'',
				defaultValue:'xenSnapshot',
				custom:false,
				displayOrder:100,
				fieldClass:null
		)
		nodeOptions << new OptionType(
				code:'provisionType.xen.custom.containerType.statTypeCode',
				inputType: OptionType.InputType.HIDDEN,
				name:'stat type code',
				category:'provisionType.xen.custom',
				fieldName:'statTypeCode',
				fieldCode: 'gomorpheus.optiontype.StatTypeCode',
				fieldLabel:'Stat Type Code',
				fieldContext:'domain',
				fieldGroup: null,
				required:false,
				enabled:true,
				editable:false,
				global:false,
				placeHolder:null,
				helpBlock:'',
				defaultValue:'vm',
				custom:false,
				displayOrder:101,
				fieldClass:null
		)
		nodeOptions << new OptionType(
				code:'provisionType.xen.custom.containerType.logTypeCode',
				inputType: OptionType.InputType.HIDDEN,
				name:'log type code',
				category:'provisionType.xen.custom',
				fieldName:'logTypeCode',
				fieldCode: 'gomorpheus.optiontype.LogTypeCode',
				fieldLabel:'Log Type Code',
				fieldContext:'domain',
				fieldGroup: null,
				required:false,
				enabled:true,
				editable:false,
				global:false,
				placeHolder:null,
				helpBlock:'',
				defaultValue:'xen',
				custom:false,
				displayOrder:102,
				fieldClass:null
		)
		nodeOptions << new OptionType(
				code:'provisionType.xen.custom.containerType.serverType',
				inputType: OptionType.InputType.HIDDEN,
				name:'server type',
				category:'provisionType.xen.custom',
				fieldName:'serverType',
				fieldCode: 'gomorpheus.optiontype.ServerType',
				fieldLabel:'Server Type',
				fieldContext:'domain',
				fieldGroup: null,
				required:false,
				enabled:true,
				editable:false,
				global:false,
				placeHolder:null,
				helpBlock:'',
				defaultValue:'vm',
				custom:false,
				displayOrder:103,
				fieldClass:null
		)
		nodeOptions << new OptionType(
				code:'provisionType.xen.custom.instanceTypeLayout.description',
				inputType: OptionType.InputType.HIDDEN,
				name:'layout description',
				category:'provisionType.xen.custom',
				fieldName:'description',
				fieldCode: 'gomorpheus.optiontype.LayoutDescription',
				fieldLabel:'Layout Description',
				fieldContext:'instanceTypeLayout',
				fieldGroup: null,
				required:false,
				enabled:true,
				editable:false,
				global:false,
				placeHolder:null,
				helpBlock:'',
				defaultValue:'This will provision a single vm container',
				custom:false,
				displayOrder:104,
				fieldClass:null
		)

		return nodeOptions
	}

	/**
	 * Provides a Collection of StorageVolumeTypes that are available for root StorageVolumes
	 * @return Collection of StorageVolumeTypes
	 */
	@Override
	Collection<StorageVolumeType> getRootVolumeStorageTypes() {
		context.async.storageVolume.storageVolumeType.list(
				new DataQuery().withFilter("code", "standard")).toList().blockingGet()
	}

	/**
	 * Provides a Collection of StorageVolumeTypes that are available for data StorageVolumes
	 * @return Collection of StorageVolumeTypes
	 */
	@Override
	Collection<StorageVolumeType> getDataVolumeStorageTypes() {
		context.async.storageVolume.storageVolumeType.list(
				new DataQuery().withFilter("code", "standard")).toList().blockingGet()
	}

	/**
	 * Provides a Collection of ${@link ServicePlan} related to this ProvisionProvider that can be seeded in.
	 * Some clouds do not use this as they may be synced in from the public cloud. This is more of a factor for
	 * On-Prem clouds that may wish to have some precanned plans provided for it.
	 * @return Collection of ServicePlan sizes that can be seeded in at plugin startup.
	 */
	@Override
	Collection<ServicePlan> getServicePlans() {
		def servicePlans = []
		servicePlans << new ServicePlan([code:'xen-vm-512', editable:true, name:'512MB Memory', description:'512MB Memory', sortOrder:0, maxCores:1,
										 maxStorage:10l * 1024l * 1024l * 1024l, maxMemory: 1l * 512l * 1024l * 1024l, maxCpu:1,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'xen-vm-1024', editable:true, name:'1GB Memory', description:'1GB Memory', sortOrder:1, maxCores:1,
										 maxStorage: 10l * 1024l * 1024l * 1024l, maxMemory: 1l * 1024l * 1024l * 1024l, maxCpu:1,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'xen-vm-2048', editable:true, name:'2GB Memory', description:'2GB Memory', sortOrder:2, maxCores:1,
										 maxStorage: 20l * 1024l * 1024l * 1024l, maxMemory: 2l * 1024l * 1024l * 1024l, maxCpu:1,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'xen-vm-4096', editable:true, name:'4GB Memory', description:'4GB Memory', sortOrder:3, maxCores:1,
										 maxStorage: 40l * 1024l * 1024l * 1024l, maxMemory: 4l * 1024l * 1024l * 1024l, maxCpu:1,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'xen-vm-8192', editable:true, name:'8GB Memory', description:'8GB Memory', sortOrder:4, maxCores:2,
										 maxStorage: 80l * 1024l * 1024l * 1024l, maxMemory: 8l * 1024l * 1024l * 1024l, maxCpu:2,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'xen-vm-16384', editable:true, name:'16GB Memory', description:'16GB Memory', sortOrder:5, maxCores:2,
										 maxStorage: 160l * 1024l * 1024l * 1024l, maxMemory: 16l * 1024l * 1024l * 1024l, maxCpu:2,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'xen-vm-24576', editable:true, name:'24GB Memory', description:'24GB Memory', sortOrder:6, maxCores:4,
										 maxStorage: 240l * 1024l * 1024l * 1024l, maxMemory: 24l * 1024l * 1024l * 1024l, maxCpu:4,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'xen-vm-32768', editable:true, name:'32GB Memory', description:'32GB Memory', sortOrder:7, maxCores:4,
										 maxStorage: 320l * 1024l * 1024l * 1024l, maxMemory: 32l * 1024l * 1024l * 1024l, maxCpu:4,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true])

		servicePlans << new ServicePlan([code:'internal-custom-xen', editable:false, name:'Xen Custom', description:'Xen Custom', sortOrder:0,
										 customMaxStorage:true, customMaxDataStorage:true, addVolumes:true, customCpu: true, customCores: true, customMaxMemory: true, deletable: false, provisionable: false,
										 maxStorage:0l, maxMemory: 0l,  maxCpu:0,])
		servicePlans
	}

	/**
	 * Validates the provided provisioning options of a workload. A return of success = false will halt the
	 * creation and display errors
	 * @param opts options
	 * @return Response from API. Errors should be returned in the errors Map with the key being the field name and the error
	 * message as the value.
	 */
	@Override
	ServiceResponse validateWorkload(Map opts) {
		log.debug("validateWorkload: ${opts}")
		ServiceResponse rtn = new ServiceResponse(true, null, [:], null)
		def validationOpts = [:]
		try{
			if (opts.containsKey('imageId') || opts?.config?.containsKey('imageId'))
				validationOpts += [imageId: opts?.config?.imageId ?: opts?.imageId]
			if(opts.networkInterfaces) {
				validationOpts.networkInterfaces = opts.networkInterfaces
			}
			def validationResults = XenComputeUtility.validateServerConfig(validationOpts)
			if(!validationResults.success) {
				validationResults.errors?.each { it ->
					rtn.addError(it.field, it.msg)
				}
			}
		} catch(e) {
			log.error("validate container error: ${e}", e)
		}
		return rtn
	}

	/**
	 * This method is a key entry point in provisioning a workload. This could be a vm, a container, or something else.
	 * Information associated with the passed Workload object is used to kick off the workload provision request
	 * @param workload the Workload object we intend to provision along with some of the associated data needed to determine
	 *                 how best to provision the workload
	 * @param workloadRequest the RunWorkloadRequest object containing the various configurations that may be needed
	 *                        in running the Workload
	 * @param opts additional configuration options that may have been passed during provisioning
	 * @return Response from API
	 */
	@Override
	ServiceResponse<ProvisionResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		log.debug "runWorkload: ${workload} ${workloadRequest} ${opts}"
		ProvisionResponse provisionResponse = new ProvisionResponse(success: true)

		ComputeServer server = workload.server
		Cloud cloud = server.cloud
		def imageId
		def sourceVmId
		def virtualImage
		def imageFormat = 'xva'
		try {
			def containerConfig = workload.getConfigMap()
			def rootVolume = server.volumes?.find { it.rootVolume == true }
			def networkId = containerConfig.networkId
			def network = context.async.network.get(networkId).blockingGet()
			def sourceWorkload = context.async.workload.get(opts.cloneContainerId).blockingGet()
			def cloneContainer = opts.cloneContainerId ? sourceWorkload : null
			def morphDataStores = context.async.cloud.datastore.listById([containerConfig.datastoreId?.toLong()])
					.toMap { it.id.toLong() }.blockingGet()
			def datastore = rootVolume?.datastore ?: morphDataStores[containerConfig.datastoreId?.toLong()]
			def authConfigMap = plugin.getAuthConfig(cloud)
			if (containerConfig.imageId || containerConfig.template || server.sourceImage?.id) {
				def virtualImageId = (containerConfig.imageId?.toLong() ?: containerConfig.template?.toLong() ?: server.sourceImage.id)
				virtualImage = context.async.virtualImage.get(virtualImageId).blockingGet()
				imageId = virtualImage.locations.find { it.refType == "ComputeZone" && it.refId == cloud.id }?.externalId
				if (!imageId) { //If its userUploaded and still needs uploaded
					//TODO: We need to upload ovg/vmdk stuff here
					def primaryNetwork = server.interfaces?.find { it.network }?.network
					def cloudFiles = context.async.virtualImage.getVirtualImageFiles(virtualImage).blockingGet()
					imageFormat = virtualImage.virtualImageType?.code ?: virtualImage.imageType
					def imageFile = cloudFiles?.find { cloudFile -> cloudFile.name.toLowerCase().indexOf('.' + imageFormat) > -1 }
					def containerImage =
							[
									name         : virtualImage.name,
									imageSrc     : imageFile?.getURL(),
									minDisk      : virtualImage.minDisk ?: 5,
									minRam       : virtualImage.minRam ?: (512 * ComputeUtility.ONE_MEGABYTE),
									tags         : 'morpheus, ubuntu',
									imageType    : 'disk_image',
									containerType: virtualImage.virtualImageType?.code ?: virtualImage.imageType, //'vhd',
									cloudFiles   : cloudFiles,
									imageFile    : imageFile,
									imageSize    : imageFile?.contentLength
							]
					def imageConfig =
							[
									zone      : cloud,
									image     : containerImage,
									name      : virtualImage.name,
									datastore : datastore,
									network   : primaryNetwork,
									osTypeCode: virtualImage?.osType?.code,
									containerType : containerImage?.containerType
							]
					imageConfig.authConfig = authConfigMap
					def imageResults = XenComputeUtility.insertTemplate(imageConfig)
					log.debug("insertTemplate: imageResults: ${imageResults}")
					if (imageResults.success == true) {
						imageId = imageResults.imageId
						// create a location for the newly created image
						def imageLocation = new VirtualImageLocation(
							virtualImage: virtualImage,
							code        : "xenserver.image.${cloud.id}.${imageId}",
							internalId  : imageId,
							externalId  : imageId,
							imageName   : virtualImage.name
						)
						morpheus.services.virtualImage.location.create(imageLocation, cloud)
					} else {
						def errorMessage = imageResults.msg ?: 'An error occurred while uploading the image. See logs for more details.'
						provisionResponse.setError(errorMessage)
						provisionResponse.success = false
						return new ServiceResponse(success: false, msg: errorMessage, data: provisionResponse)
					}
				}
			}
			if (opts.backupSetId) {
				//if this is a clone or restore, use the snapshot id as the image
				def snapshots = context.services.backup.backupResult.list(
						new DataQuery().withFilter("backupSetId", opts.backupSetId)
								.withFilter("containerId", opts.cloneContainerId))
				def snapshot = snapshots.find { it.backupSetId == opts.backupSetId }
				def snapshotId = snapshot?.snapshotId
				sourceVmId = snapshot?.configMap?.vmId
				if (snapshotId) {
					imageId = snapshotId
				}
				if (!network && (cloneContainer || snapshot.configMap)) {
					def cloneContainerConfig = cloneContainer?.configMap ?: snapshot?.configMap ?: [:]
					networkId = cloneContainerConfig.networkId
					if (networkId) {
						containerConfig.networkId = networkId
						containerConfig.each {
							it -> workload.setConfigProperty(it.key, it.value)
						}
						workload = context.async.workload.save(workload).blockingGet()
						network = context.async.network.get(networkId).blockingGet()
					}
				}
			}
			if (imageId) {
				def userGroups = workload.instance.userGroups?.toList() ?: []
				if (workload.instance.userGroup && userGroups.contains(workload.instance.userGroup) == false) {
					userGroups << workload.instance.userGroup
				}
				server.sourceImage = virtualImage
				server.serverOs = server.serverOs ?: virtualImage.osType
				server.osType = (virtualImage.osType?.platform == 'windows' ? 'windows' : 'linux') ?: virtualImage.platform
				def newType = this.findVmNodeServerTypeForCloud(cloud.id, server.osType, 'xenserver-provision-provider')
				if (newType && server.computeServerType != newType) {
					server.computeServerType = newType
				}
				server = saveAndGetMorpheusServer(server, true)
				def maxMemory = workload.maxMemory ?: workload.instance.plan.maxMemory
				def maxCores = workload.maxCores ?: workload.instance.plan.maxCores
				def maxStorage = this.getRootSize(workload)
				def dataDisks = server?.volumes?.findAll { it.rootVolume == false }?.sort { it.id }
				def createOpts =
						[
								account    : server.account,
								name       : server.name,
								maxMemory  : maxMemory,
								maxStorage : maxStorage,
								maxCpu     : maxCores,
								imageId    : imageId,
								server     : server,
								zone       : cloud,
								externalId : server.externalId,
								networkType: workload.getConfigProperty('networkType'),
								datastore  : datastore,
								network    : network,
								dataDisks  : dataDisks,
								platform   : server.osType,
								noAgent    : workload.getConfigProperty('noAgent')
						]
				createOpts.authConfig = authConfigMap
				createOpts.hostname = server.getExternalHostname()
				createOpts.domainName = server.getExternalDomain()
				createOpts.fqdn = createOpts.hostname
				if (createOpts.domainName) {
					createOpts.fqdn += '.' + createOpts.domainName
				}
				createOpts.networkConfig = opts.networkConfig
				//cloud init config
				createOpts.isoDatastore = findIsoDatastore(cloud.id)
				if (virtualImage?.isCloudInit) {
					createOpts.cloudConfigUser = workloadRequest?.cloudConfigUser ?: null
					createOpts.cloudConfigMeta = workloadRequest?.cloudConfigMeta ?: null
					createOpts.cloudConfigNetwork = workloadRequest?.cloudConfigNetwork ?: null
				} else if (virtualImage?.isSysprep) {
					createOpts.cloudConfigUser = workloadRequest?.cloudConfigUser ?: null
				}
				createOpts.cloudConfigFile = getCloudFileDiskName(server.id)
				createOpts.isSysprep = virtualImage?.isSysprep
				log.debug("Creating VM on Xen Server Additional Details: ${createOpts}")
				createOpts.server = server
				def createResults
				if (sourceVmId) {
					createOpts.sourceVmId = sourceVmId
					log.debug("runworkload: calling cloneServer")
					createResults = XenComputeUtility.cloneServer(createOpts, getCloudIsoOutputStream(createOpts))
				} else {
					log.debug("runworkload: calling createProvisionServer")
					createResults = createProvisionServer(createOpts)
				}
				log.debug("Create XenServer VM Results: ${createResults}")
				if (createResults.success == true && createResults.vmId) {
					server.externalId = createResults.vmId
					provisionResponse.externalId = server.externalId
					setVolumeInfo(server.volumes, createResults.volumes)
					server = saveAndGetMorpheusServer(server, true)
					def startResults = XenComputeUtility.startVm(authConfigMap, server.externalId)
					log.debug("start: ${startResults.success}")
					if (startResults.success == true) {
						if (startResults.error == true) {
							server.statusMessage = 'Failed to start server'
							//ouch - delet it?
						} else {
							//good to go
							def serverDetail = checkServerReady([authConfig: authConfigMap, externalId: server.externalId])
							// log.debug("serverDetail: ${serverDetail}")
							if (serverDetail.success == true) {
								Boolean doServerReload = false
								serverDetail.ipAddresses.each { interfaceName, data ->
									Long netInterfaceId = server.interfaces.find { it.name == interfaceName }?.id
									if(netInterfaceId) {
										ComputeServerInterface netInterface = context.async.computeServer.computeServerInterface.get(netInterfaceId).blockingGet()
										if (netInterface) {
											if (data.ipAddress) {
												def address = new NetAddress(address: data.ipAddress, type: NetAddress.AddressType.IPV4)
												netInterface.addresses << address
												netInterface.publicIpAddress = data.ipAddress
											}
											if (data.ipv6Address) {
												def address = new NetAddress(address: data.ipv6Address, type: NetAddress.AddressType.IPV6)
												netInterface.addresses << address
												netInterface.publicIpv6Address = data.ipv6Address
											}
											context.async.computeServer.computeServerInterface.save([netInterface]).blockingGet()
											doServerReload = true
										}
									}
								}

								if(doServerReload) {
									// reload the server to pickup interface changes
									server = getMorpheusServer(server.id)
								}

								def privateIp = serverDetail.ipAddress
								def publicIp = serverDetail.ipAddress
								if (privateIp) {
									def newInterface = false
									server.internalIp = privateIp
									server.externalIp = publicIp
									server.sshHost = privateIp
									server = saveAndGetMorpheusServer(server)
								}
								//update external info
								setNetworkInfo(server.interfaces, serverDetail.networks)
								// reload the server after setNetworkInfo made changes to interfaces
								server = getMorpheusServer(server.id)

								server.osDevice = '/dev/vda'
								server.dataDevice = '/dev/vda'
								server.lvmEnabled = false
								server.sshHost = privateIp ?: publicIp
								server.managed = true
								server.capacityInfo = new ComputeCapacityInfo(
										maxCores: 1,
										maxMemory: workload.getConfigProperty('maxMemory').toLong(),
										maxStorage: workload.getConfigProperty('maxStorage').toLong()
								)
								server.status = 'provisioned'
								server.powerState = ComputeServer.PowerState.on
								context.async.computeServer.save(server).blockingGet()
								provisionResponse.success = true
							} else {
								server.statusMessage = 'Failed to load server details'
								context.async.computeServer.save(server).blockingGet()
							}
						}
					} else {
						server.statusMessage = 'Failed to start server'
						saveAndGetMorpheusServer(server)
					}
				} else {
					provisionResponse.setError('An unknown error occurred while making an API request to Xen.')
					provisionResponse.success = false
				}
			} else {
				server.statusMessage = 'Image not found'
				saveAndGetMorpheusServer(server)
			}
			provisionResponse.noAgent = opts.noAgent ?: false
			if (provisionResponse.success != true) {
				return new ServiceResponse(success: false, msg: provisionResponse.message ?: 'vm config error', error: provisionResponse.message, data: provisionResponse)
			} else {
				return new ServiceResponse<ProvisionResponse>(success: true, data: provisionResponse)
			}
		} catch (e) {
			log.error("initializeServer error:${e}", e)
			provisionResponse.setError(e.message)
			return new ServiceResponse(success: false, msg: e.message, error: e.message, data: provisionResponse)
		}
	}

	/**
	 * This method is called after successful completion of runWorkload and provides an opportunity to perform some final
	 * actions during the provisioning process. For example, ejected CDs, cleanup actions, etc
	 * @param workload the Workload object that has been provisioned
	 * @return Response from the API
	 */
	@Override
	ServiceResponse finalizeWorkload(Workload workload) {
		return ServiceResponse.success()
	}

	/**
	 * Issues the remote calls necessary top stop a workload element from running.
	 * @param workload the Workload we want to shut down
	 * @return Response from API
	 */
	@Override
	ServiceResponse stopWorkload(Workload workload) {
		def rtn = ServiceResponse.prepare()
		try {
			if(workload.server?.externalId) {
				Cloud cloud = workload.server.cloud
				def stopResults = XenComputeUtility.stopVm(plugin.getAuthConfig(cloud), workload.server.externalId)
				if(stopResults.success == true) {
					rtn.success = true
				}
			} else {
				rtn.success = true
				rtn.msg = context.services.localization.get("gomorpheus.provision.xenServer.vmNotFound")
			}
		} catch (e) {
			log.error("stopContainer error: ${e}", e)
			rtn.msg = context.services.localization.get("gomorpheus.provision.xenServer.error.stopWorkload")
		}
		return rtn
	}

	/**
	 * Issues the remote calls necessary to start a workload element for running.
	 * @param workload the Workload we want to start up.
	 * @return Response from API
	 */
	@Override
	ServiceResponse startWorkload(Workload workload) {
		log.debug("startWorkload: ${workload.id}")
		def rtn = ServiceResponse.prepare()
		try {
			if(workload.server?.externalId) {
				def authConfigMap = plugin.getAuthConfig(workload.server?.cloud)
				def startResults = XenComputeUtility.startVm(authConfigMap, workload.server.externalId)
				log.debug("startWorkload: startResults: ${startResults}")
				if(startResults.success == true) {
					rtn.success = true
				} else {
					rtn.msg = "${startResults.msg}" ?: 'Failed to start vm'
				}
			} else {
				rtn.error = context.services.localization.get("gomorpheus.provision.xenServer.vmNotFound")
			}
		} catch(e) {
			log.error("startContainer error: ${e}", e)
			rtn.error = context.services.localization.get("gomorpheus.provision.xenServer.error.startWorkload")
		}

		return rtn
	}

	/**
	 * Issues the remote calls to restart a workload element. In some cases this is just a simple alias call to do a stop/start,
	 * however, in some cases cloud providers provide a direct restart call which may be preferred for speed.
	 * @param workload the Workload we want to restart.
	 * @return Response from API
	 */
	@Override
	ServiceResponse restartWorkload(Workload workload) {
		log.debug("restartWrokload: ${workload.id}")
		def rtn = ServiceResponse.prepare()
		try {
			if(workload.server?.externalId) {
				def authConfigMap = plugin.getAuthConfig(workload.server?.cloud)
				def restartResults = XenComputeUtility.restartVm(authConfigMap, workload.server.externalId)
				log.debug("restartWrokload: restartResults: ${restartResults}")
				if(restartResults.success == true) {
					rtn.success = true
				} else {
					rtn.msg = "${restartResults.msg}" ?: 'Failed to restart vm'
				}
			} else {
				rtn.error = context.services.localization.get("gomorpheus.provision.xenServer.vmNotFound")
			}
		} catch(e) {
			log.error("startContainer error: ${e}", e)
			rtn.error = context.services.localization.get("gomorpheus.provision.xenServer.error.restartWorkload")
		}

		return rtn
	}

	/**
	 * This is the key method called to destroy / remove a workload. This should make the remote calls necessary to remove any assets
	 * associated with the workload.
	 * @param workload to remove
	 * @param opts map of options
	 * @return Response from API
	 */
	@Override
	ServiceResponse removeWorkload(Workload workload, Map opts) {
		log.debug("removeWorkload: opts: ${opts}")
		try {
			if(workload.server?.externalId) {
				log.debug("removeWorkload: calling stopWorkload")
				def stopResults = stopWorkload(workload)
				log.debug("removeWorkload: stopResults: ${stopResults}")
				def authConfigMap = plugin.getAuthConfig(workload.server.cloud)
				def removeResults = XenComputeUtility.destroyVm(authConfigMap, workload.server.externalId)
				log.debug("removeWorkload: removeResults: ${removeResults}")
				if(removeResults.success == true) {
					return ServiceResponse.success()
				} else {
					def error = morpheus.services.localization.get("gomorpheus.provision.xenServer.failRemoveVm")
					log.warn("removeWorkload: ${error}")
					return ServiceResponse.error(error)
				}
			} else {
				def error = morpheus.services.localization.get("gomorpheus.provision.xenServer.vmNotFound")
				return ServiceResponse.error(error)
			}
		} catch(e) {
			log.error("removeWorkload error: ${e}", e)
			def error = morpheus.services.localization.get("gomorpheus.provision.xenServer.error.removeWorkload")
			return ServiceResponse.error(error)
		}
	}

	/**
	 * Method called after a successful call to runWorkload to obtain the details of the ComputeServer. Implementations
	 * should not return until the server is successfully created in the underlying cloud or the server fails to
	 * create.
	 * @param server to check status
	 * @return Response from API. The publicIp and privateIp set on the WorkloadResponse will be utilized to update the ComputeServer
	 */
	@Override
	ServiceResponse<ProvisionResponse> getServerDetails(ComputeServer server) {
		log.debug("getServerDetails: ${server.id}")
		def provisionResponse = new ProvisionResponse()
		ServiceResponse<ProvisionResponse> rtn = ServiceResponse.prepare(provisionResponse)
		try {
			Map authConfig = plugin.getAuthConfig(server.cloud)
			def serverDetail = checkServerReady([authConfig: authConfig, externalId: server.externalId])
			if (serverDetail.success == true) {
				provisionResponse.privateIp = serverDetail.ipAddress
				provisionResponse.publicIp = serverDetail.ipAddress
				provisionResponse.externalId = server.externalId
				def finalizeResults = finalizeHost(server)
				if(finalizeResults.success == true) {
					provisionResponse.success = true
					rtn.success = true
				}
			}
		} catch (e){
			log.error("Error getServerDetails: ${e}", e)
			rtn.success = false
			rtn.msg = "Error in getting server detail: ${e}"
		}

		return rtn
	}

	/**
	 * Method called before runWorkload to allow implementers to create resources required before runWorkload is called
	 * @param workload that will be provisioned
	 * @param opts additional options
	 * @return Response from API
	 */
	@Override
	ServiceResponse createWorkloadResources(Workload workload, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Stop the server
	 * @param computeServer to stop
	 * @return Response from API
	 */
	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		def rtn = [success: false, msg: null]
		try {
			if (computeServer?.externalId){
				Cloud cloud = computeServer.cloud
				def stopResults = XenComputeUtility.stopVm(plugin.getAuthConfig(cloud), computeServer.externalId)
				if(stopResults.success == true){
					rtn.success = true
				}
			} else {
				rtn.msg = morpheus.services.localization.get("gomorpheus.provision.xenServer.vmNotFound")
			}
		} catch(e) {
			log.error("stopServer error: ${e}", e)
			rtn.msg = e.message
		}
		return new ServiceResponse(rtn)
	}

	/**
	 * Start the server
	 * @param computeServer to start
	 * @return Response from API
	 */
	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		log.debug("startServer: computeServer.id: ${computeServer?.externalId}")
		def rtn = [success: false]
		try {
			if (computeServer?.externalId) {
				def authConfigMap = plugin.getAuthConfig(computeServer.cloud)
				def startResults = XenComputeUtility.startVm(authConfigMap, computeServer.externalId)
				log.debug("startServer: startResults: ${startResults}")
				if (startResults.success == true) {
					rtn.success = true
				}
			} else {
				def error = morpheus.services.localization.get("gomorpheus.provision.xenServer.vmNotFound")
				rtn.msg = error
			}
		} catch (e) {
			log.error("startServer error: ${e}", e)
			def error = morpheus.services.localization.get("gomorpheus.provision.xenServer.error.startServer")
			rtn.msg = error
		}
		return new ServiceResponse(rtn)
	}

	/**
	 * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
	 *
	 * @return an implementation of the MorpheusContext for running Future based rxJava queries
	 */
	@Override
	MorpheusContext getMorpheus() {
		return this.@context
	}

	/**
	 * Returns the instance of the Plugin class that this provider is loaded from
	 * @return Plugin class contains references to other providers
	 */
	@Override
	Plugin getPlugin() {
		return this.@plugin
	}

	/**
	 * A unique shortcode used for referencing the provided provider. Make sure this is going to be unique as any data
	 * that is seeded or generated related to this provider will reference it by this code.
	 * @return short code string that should be unique across all other plugin implementations.
	 */
	@Override
	String getCode() {
		return PROVIDER_CODE
	}

	/**
	 * Provides the provider name for reference when adding to the Morpheus Orchestrator
	 * NOTE: This may be useful to set as an i18n key for UI reference and localization support.
	 *
	 * @return either an English name of a Provider or an i18n based key that can be scanned for in a properties file.
	 */
	@Override
	String getName() {
		return PROVIDER_NAME
	}

	@Override
	ServiceResponse validateHost(ComputeServer server, Map opts) {
		log.debug("validateHost: ${server} ${opts}")
		def rtn =  ServiceResponse.success()
		try {
			def validationOpts = [
					networkId: opts?.config?.networkId,
					networkInterfaces: opts?.networkInterfaces
			]
			if (opts?.config?.templateTypeSelect == 'custom') {
				validationOpts += [imageId: opts?.config?.imageId]
			}
			if(opts?.config?.containsKey('nodeCount')){
				validationOpts += [nodeCount: opts.config.nodeCount]
			}
			def validationResults = XenComputeUtility.validateServerConfig(validationOpts)
			if(!validationResults.success) {
				rtn.success = false
				rtn.errors += validationResults.errors
			}
		} catch(e)  {
			log.error("error in validateHost: ${e}", e)
		}
		return rtn
	}

	protected ComputeServer saveAndGetMorpheusServer(ComputeServer server, Boolean fullReload=false) {
		def saveResult = context.async.computeServer.bulkSave([server]).blockingGet()
		def updatedServer
		if(saveResult.success == true) {
			if(fullReload) {
				updatedServer = getMorpheusServer(server.id)
			} else {
				updatedServer = saveResult.persistedItems.find { it.id == server.id }
			}
		} else {
			updatedServer = saveResult.failedItems.find { it.id == server.id }
			log.warn("Error saving server: ${server?.id}" )
		}
		return updatedServer ?: server
	}

	protected  ComputeServer getMorpheusServer(Long id) {
		return context.services.computeServer.find(
			new DataQuery().withFilter("id", id).withJoin("interfaces.network")
		)
	}

	@Override
	ServiceResponse<PrepareHostResponse> prepareHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug "prepareHost: ${server} ${hostRequest} ${opts}"

		def prepareResponse = new PrepareHostResponse(computeServer: server, disableCloudInit: false, options: [sendIp: true])
		ServiceResponse<PrepareHostResponse> rtn = ServiceResponse.prepare(prepareResponse)
		if(server.sourceImage){
			rtn.success = true
			return rtn
		}

		try {
			VirtualImage virtualImage
			Long computeTypeSetId = server.typeSet?.id
			if(computeTypeSetId) {
				ComputeTypeSet computeTypeSet = morpheus.async.computeTypeSet.get(computeTypeSetId).blockingGet()
				if(computeTypeSet.workloadType) {
					WorkloadType workloadType = morpheus.async.workloadType.get(computeTypeSet.workloadType.id).blockingGet()
					virtualImage = workloadType.virtualImage
				}
			}
			if(!virtualImage) {
				rtn.msg = "No virtual image selected"
			} else {
				server.sourceImage = virtualImage
				saveAndGetMorpheusServer(server)
				rtn.success = true
			}
		} catch(e) {
			rtn.msg = "Error in prepareHost: ${e}"
			log.error("${rtn.msg}, ${e}", e)

		}
		return rtn
	}

	@Override
	ServiceResponse<ProvisionResponse> runHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug("runHost: ${server} ${hostRequest} ${opts}")

		ProvisionResponse provisionResponse = new ProvisionResponse()
		try {
			def layout = server?.layout
			def typeSet = server.typeSet
			def serverGroupType = layout?.groupType
			Boolean isKubernetes = serverGroupType?.providerType == 'kubernetes'
			def config = server.getConfigMap()
			Cloud cloud = server.cloud
			Account account = server.account
			def imageFormat = 'vhd'
			def imageType = config.templateTypeSelect ?: 'default'
			def imageId
			def virtualImage
			Map authConfig = plugin.getAuthConfig(cloud)
			def rootVolume = server.volumes?.find{it.rootVolume == true}
			def datastoreId = rootVolume.datastore?.id
			def datastore = context.async.cloud.datastore.listById([datastoreId?.toLong()]).firstOrError().blockingGet()
			log.debug("runHost datastore: ${datastore}")

			if(layout && typeSet) {
				Long computeTypeSetId = server.typeSet?.id
				if(computeTypeSetId) {
					ComputeTypeSet computeTypeSet = morpheus.services.computeTypeSet.get(computeTypeSetId)
					WorkloadType workloadType = computeTypeSet.getWorkloadType()
					if(workloadType) {
						Long workloadTypeId = workloadType.id
						WorkloadType containerType = morpheus.services.containerType.get(workloadTypeId)
						Long virtualImageId = containerType.virtualImage.id
						virtualImage = morpheus.services.virtualImage.get(virtualImageId)
						def imageLocation = virtualImage?.imageLocations.find{it.refId == cloud.id && it.refType == "ComputeZone"}
						imageId = imageLocation?.externalId
					}
				}
			} else if(imageType == 'custom' && config.imageId) {
				virtualImage = server.sourceImage
				imageId = virtualImage.externalId
			} else {
				virtualImage  = new VirtualImage(code: 'xen.image.morpheus.ubuntu.20.04-v1.amd64')
			}
			if(!imageId) {
				def cloudFiles = context.async.virtualImage.getVirtualImageFiles(virtualImage).blockingGet()
				def imageFile = cloudFiles?.find{cloudFile -> cloudFile.name.toLowerCase().indexOf('.' + imageFormat) > -1}
				def primaryNetwork = server.interfaces?.find{it.network}?.network
				def containerImage = [
						name			: virtualImage.name,
						imageSrc		: imageFile?.getURL(),
						minDisk			: virtualImage.minDisk ?: 5,
						minRam			: virtualImage.minRam,
						tags			: 'morpheus, ubuntu',
						imageType		: 'disk_image',
						containerType	: 'vhd',
						cloudFiles		: cloudFiles,
						imageFile		: imageFile
				]
				def imageConfig = [
						zone		: cloud,
						image		: containerImage,
						name		: virtualImage.name,
						datastore	: datastore,
						network		: primaryNetwork,
						osTypeCode	: virtualImage?.osType?.code
				]
				imageConfig.authConfig = authConfig
				def imageResults = XenComputeUtility.insertTemplate(imageConfig)
				if(imageResults.success == true) {
					imageId = imageResults.imageId
				}
			}
			if(imageId) {
				server.sourceImage = virtualImage
				def maxMemory = server.maxMemory ?: server.plan.maxMemory
				def maxCores = server.maxCores ?: server.plan.maxCores
				def maxStorage = rootVolume.maxStorage
				def dataDisks = server?.volumes?.findAll{it.rootVolume == false}?.sort{it.id}
				server.osDevice = '/dev/xvda'
				server.dataDevice = dataDisks ? dataDisks.first().deviceName : '/dev/xvda'
				if(server.dataDevice == '/dev/xvda' || isKubernetes) {
					server.lvmEnabled = false
				}
				def createOpts = [
						account		: account,
						name		: server.name,
						maxMemory	: maxMemory,
						maxStorage	: maxStorage,
						maxCpu		: maxCores,
						imageId		: imageId,
						server		: server,
						zone		: cloud,
						dataDisks	: dataDisks,
						platform	: 'linux',
						externalId	: server.externalId,
						networkType	: config.networkType,
						datastore	: datastore
				]
				//cloud init config
				createOpts.hostname = server.getExternalHostname()
				createOpts.domainName = server.getExternalDomain()
				createOpts.fqdn = createOpts.hostname + '.' + createOpts.domainName
				createOpts.cloudConfigUser = hostRequest.cloudConfigUser
				createOpts.cloudConfigMeta = hostRequest.cloudConfigMeta
				createOpts.cloudConfigNetwork = hostRequest.cloudConfigNetwork
				createOpts.networkConfig = hostRequest.networkConfiguration
				createOpts.isSysprep = virtualImage?.isSysprep
				createOpts.isoDatastore = findIsoDatastore(cloud.id)
				createOpts.cloudConfigFile = getCloudFileDiskName(server.id)

				context.async.computeServer.save(server).blockingGet()
				//create it
				log.debug("create server: ${createOpts}")
				createOpts.authConfig = authConfig
				def createResults = findOrCreateServer(createOpts)
				if(createResults.success == true && createResults.vmId) {
					setVolumeInfo(server.volumes, createResults.volumes)
					def startResults = XenComputeUtility.startVm(authConfig, createResults.vmId)
					provisionResponse.externalId = createResults.vmId
					setVolumeInfo(server.volumes, createResults.volumes)
					setNetworkInfo(server.interfaces, createResults.networks)
					log.debug("start: ${startResults.success}")
					if(startResults.success == true) {
						if(startResults.error == true) {
							server.statusMessage = 'Failed to start server'
						} else {
							provisionResponse.success = true
						}
					} else {
						server.statusMessage = 'Failed to start server'
					}
				} else {
					server.statusMessage = 'Failed to create server'
				}
			} else {
				server.statusMessage = 'Image not found'
			}
			if (provisionResponse.success != true) {
				return new ServiceResponse(success: false, msg: provisionResponse.message ?: 'vm config error', error: provisionResponse.message, data: provisionResponse)
			} else {
				return new ServiceResponse<ProvisionResponse>(success: true, data: provisionResponse)
			}
		} catch(e) {
			log.error("Error in runHost method: ${e}", e)
			provisionResponse.setError(e.message)
			return new ServiceResponse(success: false, msg: e.message, error: e.message, data: provisionResponse)
		}
	}

	def findOrCreateServer(opts) {
		def rtn = [success:false]
		def found = false
		if(opts.server.externalId) {
			def serverDetail = getServerDetail(opts)
			if(serverDetail.success == true) {
				found = true
				rtn.success = true
				rtn.vm = serverDetail.vm
				rtn.vmId = serverDetail.vmId
				rtn.vmRecord = serverDetail.vmRecord
				rtn.volumes = serverDetail.volumes
				rtn.networks = serverDetail.networks
				if(serverDetail.ipAddress) {
					rtn.ipAddress = serverDetail.ipAddress
				} else {
					//try to start it?
				}
			}
		}
		if(found == true) {
			return rtn
		} else {
			return createServer(opts)
		}
	}

	def createServer(opts) {
		def rtn = [success:false]
		if(!opts.imageId) {
			rtn.error = 'Please specify a template'
		} else if(!opts.name) {
			rtn.error = 'Please specify a name'
		} else {
			rtn = createProvisionServer(opts)
		}
		return rtn
	}

	def createProvisionServer(opts) {
		def rtn = [success: false]
		log.debug "createServer: ${opts}"
		try {
			def config = XenComputeUtility.getXenConnectionSession(opts.authConfig)
			opts.connection = config.connection
			def srRecord = SR.getByUuid(config.connection, opts.datastore.externalId)
			def template = VM.getByUuid(config.connection, opts.imageId)
			def newVm = template.createClone(config.connection, opts.name)
			newVm.setIsATemplate(config.connection, false)
			//set ram
			def newMemory = (opts.maxMemory).toLong()
			def newStorage = (opts.maxStorage).toLong()
			newVm.setMemoryLimits(config.connection, newMemory, newMemory, newMemory, newMemory)
			//set cpu
			if (opts.maxCpu) {
				newVm.setVCPUsMax(config.connection, opts.maxCpu)
				newVm.setVCPUsAtStartup(config.connection, opts.maxCpu)
			}
			//disk
			def newConfig = newVm.getOtherConfig(config.connection)
			def newDisks = newConfig.get('disks')
			if (newDisks) {
				newDisks = newDisks.replaceAll('sr=\"\"', 'sr=\"' + srRecord.getUuid(config.connection) + '\"')
				newConfig.put('disks', newDisks)
				newVm.setOtherConfig(config.connection, newConfig)
			}
			//add cloud init iso
			def cdResults = opts.cloudConfigFile ? XenComputeUtility.insertCloudInitDisk(opts, getCloudIsoOutputStream(opts)) : [success: false]
			def rootVolume = opts.server.volumes?.find{it.rootVolume == true}
			if (rootVolume) {
				rootVolume.unitNumber = "0"
				context.async.storageVolume.save(rootVolume).blockingGet()
			}
			def lastDiskIndex = 0
			if (cdResults.success == true) {
				lastDiskIndex = XenComputeUtility.createCdromVbd(opts, newVm, cdResults.vdi, (lastDiskIndex + 1).toString()).deviceId.toInteger()
			}

			//add optional data disk
			if (opts.dataDisks?.size() > 0) {
				opts.dataDisks?.eachWithIndex { disk, diskIndex ->
					def tmpDisk = context.async.storageVolume.get(disk.id).blockingGet()
					def dataSrRecord = SR.getByUuid(config.connection, tmpDisk.datastore.externalId)
					def dataVdi = XenComputeUtility.createVdi(opts, dataSrRecord, disk.maxStorage)
					def dataVbd = XenComputeUtility.createVbd(opts, newVm, dataVdi, (lastDiskIndex + 1).toString())
					lastDiskIndex = dataVbd.deviceId?.toInteger() ?: lastDiskIndex + 1
					if (dataVbd.success == true) {
						dataVbd.vbd.setUnpluggable(opts.connection, true)
						def deviceId = dataVbd.vbd.getUserdevice(opts.connection)
						if (deviceId) {
							disk.unitNumber = "${deviceId}"
						} else {
							disk.unitNumber = lastDiskIndex
						}
						context.async.storageVolume.save(tmpDisk).blockingGet()
					}
				}
			}
			//No longer required, need to check later
//			else if (opts.diskSize) {
//
//				def dataVdi = XenComputeUtility.createVdi(opts, srRecord, opts.diskSize)
//				def dataVbd = XenComputeUtility.createVbd(opts, newVm, dataVdi, (lastDiskIndex + 1).toString())
//				lastDiskIndex = dataVbd.deviceId.toInteger()
//			}
			//set network
			XenComputeUtility.setVmNetwork(opts, newVm, opts.networkConfig)
			def rootVbd = XenComputeUtility.findRootDrive(opts, newVm)
			def rootVbdSize = rootVbd.getVirtualSize(config.connection)
			log.debug("resizing root drive: ${rootVbd} with size: ${rootVbdSize} to: ${newStorage}")
			if (rootVbd && newStorage > rootVbdSize)
				rootVbd.resize(config.connection, newStorage)
			rtn.success = true
			rtn.vm = newVm
			rtn.vmRecord = rtn.vm.getRecord(config.connection)
			rtn.vmId = rtn.vmRecord.uuid
			rtn.volumes = XenComputeUtility.getVmVolumes(config, newVm)
			rtn.networks = XenComputeUtility.getVmNetworks(config, newVm)
		} catch (e) {
			log.error("createServer error: ${e}", e)
		}
		return rtn
	}

	def getServerDetail(opts) {
		def getServerDetail = XenComputeUtility.getVirtualMachine(opts.authConfig, opts.externalId)
		return getServerDetail
	}

	def setNetworkInfo(serverInterfaces, externalNetworks) {
		log.debug("serverInterfaces: {}, externalNetworks: {}", serverInterfaces, externalNetworks)
		try {
			if(externalNetworks?.size() > 0) {
				serverInterfaces?.eachWithIndex { networkInterface, index ->
					if(networkInterface.externalId) {
						//check for changes?
					} else {
						def matchNetwork = externalNetworks.find{networkInterface.internalId == it.uuid}
						if(!matchNetwork) {
							String displayOrder = "${networkInterface.displayOrder}"
							matchNetwork = externalNetworks.find{ displayOrder == it.deviceIndex.toString() }
						}
						if(matchNetwork) {
							def tmpInterface = context.async.computeServer.computeServerInterface.get(networkInterface.id).blockingGet()
							tmpInterface.externalId = "${matchNetwork.deviceIndex}"
							tmpInterface.internalId = "${matchNetwork.uuid}"
							tmpInterface.macAddress = matchNetwork.macAddress
							if(tmpInterface.type == null) {
								tmpInterface.type = new ComputeServerInterfaceType(code: 'xenNetwork')
							}
							context.async.computeServer.computeServerInterface.save([tmpInterface]).blockingGet()
						}
					}
				}
			}
		} catch(e) {
			log.error("setNetworkInfo error: ${e}", e)
		}
	}

	@Override
	ServiceResponse<ProvisionResponse> waitForHost(ComputeServer server){
		log.debug("waitForHost: ${server.id}")
		def provisionResponse = new ProvisionResponse()
		ServiceResponse<ProvisionResponse> rtn = ServiceResponse.prepare(provisionResponse)
		try {
			Map authConfig = plugin.getAuthConfig(server.cloud)
			def serverDetail = checkServerReady([authConfig: authConfig, externalId: server.externalId])
			if (serverDetail.success == true) {
				provisionResponse.privateIp = serverDetail.ipAddress
				provisionResponse.publicIp = serverDetail.ipAddress
				provisionResponse.externalId = server.externalId
				def finalizeResults = finalizeHost(server)
				if(finalizeResults.success == true) {
					provisionResponse.success = true
					rtn.success = true
				}
			}
		} catch (e){
			log.error("Error waitForHost: ${e}", e)
			rtn.success = false
			rtn.msg = "Error in waiting for Host: ${e}"
		}

		return rtn
	}

	@Override
	ServiceResponse finalizeHost(ComputeServer server) {
		ServiceResponse rtn = ServiceResponse.prepare()
		log.debug("finalizeHost: ${server?.id}")
		try {
			Map authConfig = plugin.getAuthConfig(server.cloud)
			def serverDetail = checkServerReady([authConfig: authConfig, externalId: server.externalId])
			server = morpheus.services.computeServer.get(server.id)
			if (serverDetail.success == true){
				Boolean doServerReload = false
				serverDetail.ipAddresses.each { interfaceName, data ->
					Long netInterfaceId = server.interfaces?.find{it.name == interfaceName}?.id
					if(netInterfaceId) {
						ComputeServerInterface netInterface = context.async.computeServer.computeServerInterface.get(netInterfaceId).blockingGet()
						if(netInterfaceId) {
							if(data.ipAddress) {
								def address = new NetAddress(address: data.ipAddress, type: NetAddress.AddressType.IPV4)
								netInterface.addresses << address
								netInterface.publicIpAddress = data.ipAddress
							}
							if(data.ipv6Address) {
								def address = new NetAddress(address: data.ipv6Address, type: NetAddress.AddressType.IPV6)
								netInterface.addresses << address
								netInterface.publicIpv6Address = data.ipv6Address
							}
							context.async.computeServer.computeServerInterface.save([netInterface]).blockingGet()
							doServerReload = true
						}
					}
				}

				if(doServerReload) {
					// reload the server to pickup interface changes
					server = getMorpheusServer(server.id)
				}
				rtn.success = true
			}
		} catch (e){
			rtn.success = false
			rtn.msg = "Error in finalizing server: ${e.message}"
			log.error("Error in finalizeWorkload: {}", e, e)
		}
		return rtn
	}

	@Override
	Boolean hasNetworks() {
		return true
	}

	@Override
	Boolean canAddVolumes() {
		return true
	}

	@Override
	Boolean canCustomizeRootVolume() {
		return true
	}

	@Override
	HostType getHostType() {
		return HostType.vm
	}

	@Override
	String serverType() {
		return "vm"
	}

	@Override
	Boolean supportsCustomServicePlans() {
		return true;
	}

	@Override
	Boolean multiTenant() {
		return false
	}

	@Override
	Boolean aclEnabled() {
		return false
	}

	@Override
	Boolean customSupported() {
		return true;
	}

	@Override
	Boolean hasDatastores() {
		return true
	}

	@Override
	Boolean supportsAutoDatastore() {
		return false
	}

	@Override
	Boolean lvmSupported() {
		return true
	}

	@Override
	String getHostDiskMode() {
		return "lvm"
	}

	@Override
	String getDeployTargetService() {
		return "vmDeployTargetService"
	}

	@Override
	String getNodeFormat() {
		return "vm"
	}

	@Override
	Boolean hasSecurityGroups() {
		return false
	}

	@Override
	Boolean canCustomizeDataVolumes() {
		return true
	}

	@Override
	Boolean canResizeRootVolume() {
		return true
	}

	@Override
	Boolean canReconfigureNetwork() {
		return true
	}

	@Override
	Boolean hasNodeTypes() {
		return true;
	}

	@Override
	Boolean createDefaultInstanceType() {
		return false
	}

	@Override
	Collection<VirtualImageType> getVirtualImageTypes() {
		def virtualImageTypes = [
			new VirtualImageType(
				code:"xen", name:"XCP-ng", nameCode:"gomorpheus.virtualImage.types.xcpng",
				creatable: false, active:true, visible: true
			),
			new VirtualImageType(code: 'vhd', name: 'VHD'),
			new VirtualImageType(code: 'xva', name: 'XVA'),
		]

		return virtualImageTypes
	}

	def setVolumeInfo(serverVolumes, externalVolumes) {
		log.debug("volumes: ${externalVolumes}")
		try {
			def maxCount = externalVolumes?.size()
			serverVolumes.sort{it.displayOrder}.eachWithIndex { volume, index ->
				if(index < maxCount) {
					if(volume.externalId) {
						//check for changes?
						log.debug("volume already assigned: ${volume.externalId}")
					} else if(volume.internalId) {
						externalVolumes.each { externalVolume ->
							if(externalVolume.uuid == volume.internalId) {
								volume.externalId = "${externalVolume.deviceIndex}"
								volume.unitNumber = "${externalVolume.deviceIndex}"
								volume.displayOrder = externalVolume.deviceIndex?.toLong()
								/*volume.save(flush: true)*/ // Ask:
								context.async.storageVolume.save(volume).blockingGet()
							}
						}
					} else {
						def unitFound = false
						def volumeOrder = volume.displayOrder ? "${volume.displayOrder}" : null
						def volumeUnit = volume.unitNumber ? "${volume.unitNumber}" : null
						log.debug("finding volume: ${volume.id} - ${volumeOrder} - ${volumeUnit}")
						externalVolumes.each { externalVolume ->
							if(unitFound == false && volumeUnit == externalVolume.deviceIndex.toString()) {
								log.debug("found matching unit disk: ${volume.displayOrder}")
								unitFound = true
								volume.externalId = "${externalVolume.deviceIndex}"
								volume.internalId = externalVolume.uuid
								//volume.save() Ask:
								context.async.storageVolume.save(volume).blockingGet()
							}
						}
						if(unitFound != true) {
							externalVolumes.each { externalVolume ->
								if(unitFound == false && volumeOrder == externalVolume.deviceIndex) {
									log.debug("found matching order disk: ${volume.displayOrder}")
									unitFound = true
									volume.externalId = "${externalVolume.deviceIndex}"
									volume.internalId = externalVolume.uuid
									volume.unitNumber = "${externalVolume.deviceIndex}"
									//volume.save()
									context.async.storageVolume.save(volume).blockingGet()
								}
							}
						}
						if(unitFound != true) {
							def sizeRange = [min:(volume.maxStorage - ComputeUtility.ONE_GIGABYTE), max:(volume.maxStorage + ComputeUtility.ONE_GIGABYTE)]
							externalVolumes.each { externalVolume ->
								def sizeCheck = externalVolume.size
								def externalKey = externalVolume.deviceIndex
								log.debug("volume size check - ${externalKey}: ${sizeCheck} between ${sizeRange.min} and ${sizeRange.max}")
								if(unitFound != true && sizeCheck > sizeRange.min && sizeCheck < sizeRange.max) {
									def dupeCheck = serverVolumes.find{it.externalId == externalKey}
									if(!dupeCheck) {
										//assign a match to the volume
										unitFound = true
										volume.externalId = "${externalVolume.deviceIndex}"
										volume.internalId = externalVolume.uuid
										volume.unitNumber = "${externalVolume.deviceIndex}"
										//volume.save()
										context.async.storageVolume.save(volume).blockingGet()
									} else {
										log.debug("found dupe volume")
									}
								}
							}
						}
					}
				}
			}
		} catch(e) {
			log.error("setVolumeInfo error: ${e}", e)
		}
	}

	def checkServerShutdown(Map authConfig, ComputeServer server) {
		def rtn = [success: false]
		try {
			def timeoutMillis = 300 * 5 * 1000 // 25 minutes (300 attempts * 5 seconds)
			def intervalMillis = 5000 // 5 seconds

			// Define the shutdown states
			def shutdownStates = [
				com.xensource.xenapi.Types.VmPowerState.SUSPENDED,
				com.xensource.xenapi.Types.VmPowerState.HALTED,
				com.xensource.xenapi.Types.VmPowerState.PAUSED
			]

			// Use polling strategy to wait for server shutdown
			def isShutdown = pollingStrategy.pollUntil(
				// Condition: Check if VM is in shutdown state
				{
					try {
						def serverDetail = getServerDetail([authConfig: authConfig, externalId: server.externalId])
						return serverDetail?.success == true &&
							   serverDetail?.vmRecord &&
							   shutdownStates.contains(serverDetail?.vmRecord?.powerState)
					} catch(ex) {
						log.warn('An error occurred trying to get VM Details while waiting for server to be shutdown. This could be because the vm is not yet ready and can safely be ignored. ' +
							'We will automatically retry. Any detailed exceptions will be logged at debug level.')
						log.debug("Errors from get server detail: ${ex.message}", ex)
						return false
					}
				},
				timeoutMillis,
				intervalMillis
			)

			rtn.success = isShutdown
		} catch(e) {
			log.error("An Exception in checkServerShutdown: ${e.message}", e)
		}
		return rtn
	}

	def checkServerReady(opts) {
		def rtn = [success: false]
		try {
			def timeoutMillis = 300 * 5 * 1000 // 25 minutes (300 attempts * 5 seconds)
			def intervalMillis = 5000 // 5 seconds

			// Use polling strategy to wait for server to be ready
			def serverDetail = pollingStrategy.pollForResult(
				// Operation: Get server details
				{
					try {
						return getServerDetail(opts)
					} catch(ex) {
						log.warn('An error occurred trying to get VM Details while waiting for server to be ready. This could be because the vm is not yet ready and can safely be ignored. ' +
								'We will automatically retry. Any detailed exceptions will be logged at debug level.')
						log.debug("Errors from get server detail: ${ex.message}", ex)
						return null
					}
				},
				// Success condition: Has IP address and VM record
				{ detail ->
					detail?.success == true && detail?.vmRecord && detail?.ipAddress
				},
				timeoutMillis,
				intervalMillis
			)

			// Process successful result
			if (serverDetail?.success == true && serverDetail?.vmRecord && serverDetail?.ipAddress) {
				rtn.success = true
				rtn.ipAddress = serverDetail.ipAddress
				if (serverDetail.vmNetworks) {
					rtn.ipAddresses = [:]
					serverDetail.vmNetworks.each { key, value ->
						def keyInfo = key.tokenize('/')
						def interfaceName = "eth${keyInfo[0]}"
						rtn.ipAddresses[interfaceName] = rtn.ipAddresses[interfaceName] ?: [:]
						if (keyInfo[1] == 'ipv4') {
							rtn.ipAddresses[interfaceName].ipAddress = value
							if (interfaceName == 'eth0') {
								rtn.ipAddress = value
							}
						} else if (keyInfo[1] == 'ipv6') { //ipv6
							rtn.ipAddresses[interfaceName].ipv6Address = value
						}
					}
				}
				rtn.vmRecord = serverDetail.vmRecord
				rtn.vm = serverDetail.vm
				rtn.vmId = serverDetail.vmId
				rtn.vmDetails = serverDetail.vmDetails
				rtn.volumes = serverDetail.volumes
				rtn.networks = serverDetail.networks
			}
		} catch(e) {
			log.error("An Exception in checkServerReady: ${e.message}", e)
		}
		return rtn
	}

	def findIsoDatastore(Long cloudId) {
		def rtn
		try {
			def dsList = context.services.cloud.datastore.list(
					new DataQuery().withFilter("category", "xenserver.sr.${cloudId}")
							.withFilter("type", "iso")
							.withFilter("storageSize", ">", 1024l * 100l)
							.withFilter("active", true))
			if (dsList?.size() > 0) {
				rtn = dsList?.size() > 0 ? dsList.first() : null
			}
		} catch (e) {
			log.error("findIsoDatastore error: ${e}", e)
		}
		return rtn
	}

	def getCloudIsoOutputStream(Map opts = [:]) {
		def isoOutput = context.services.provision.buildIsoOutputStream(
				opts.isSysprep, PlatformType.valueOf(opts.platform), opts.cloudConfigMeta, opts.cloudConfigUser, opts.cloudConfigNetwork)
		return isoOutput
	}

	def getCloudFileDiskName (Long serverId) {
		return 'morpheus_server_' + serverId + '.iso'
	}

	@Override
	String[] getDiskNameList() {
		//xvdb is skipped to make way for the cdrom
		return ['xvda', 'xvdc', 'xvdd', 'xvde', 'xvdf', 'xvdg', 'xvdh', 'xvdi', 'xvdj', 'xvdk', 'xvdl'] as String[]
	}

	@Override
	ServiceResponse resizeWorkload(Instance instance, Workload workload, ResizeRequest resizeRequest, Map opts) {
		log.debug("resizeWorkload workload?.id: ${workload?.id} - opts: ${opts} - workload.id: ${workload.id}")
		return resizeWorkloadAndServer(instance?.id, workload, workload.server, resizeRequest, opts, true)
	}

	@Override
	ServiceResponse resizeServer(ComputeServer server, ResizeRequest resizeRequest, Map opts) {
		return resizeWorkloadAndServer(null, null, server, resizeRequest, opts, false)
	}

	private ServiceResponse resizeWorkloadAndServer (Long instanceId, Workload workload, ComputeServer server, ResizeRequest resizeRequest, Map opts, Boolean isWorkload) {
		log.debug("resizeWorkload ${workload ? "workload" : "server"}.id: ${workload?.id ?: server?.id} - opts: ${opts}")

		ServiceResponse rtn = ServiceResponse.success()
		ComputeServer computeServer = getMorpheusServer(server.id)
		def authConfigMap = plugin.getAuthConfig(computeServer.cloud)
		try {
			computeServer.status = 'resizing'
			computeServer = saveAndGetMorpheusServer(computeServer)


			def requestedMemory = resizeRequest.maxMemory
			def requestedCores = resizeRequest?.maxCores


			def currentMemory
			def currentCores
			if (isWorkload) {
				currentMemory = workload.maxMemory ?: workload.getConfigProperty('maxMemory')?.toLong()
				currentCores = workload.maxCores ?: 1
			} else {
				currentMemory = computeServer.maxMemory ?: computeServer.getConfigProperty('maxMemory')?.toLong()
				currentCores = server.maxCores ?: 1
			}
			def neededMemory = requestedMemory - currentMemory
			def neededCores = (requestedCores ?: 1) - (currentCores ?: 1)
			def allocationSpecs = [externalId: computeServer.externalId, maxMemory: requestedMemory, maxCpu: requestedCores]
			if (neededMemory > 100000000l || neededMemory < -100000000l || neededCores != 0) {
				log.debug("resizing vm: ${allocationSpecs}")
				def allocationResults = XenComputeUtility.adjustVmResources(authConfigMap, computeServer.externalId, allocationSpecs)
				log.debug("allocationResults ${allocationResults}")
				if (allocationResults.success == false) {
					rtn.success = false
					rtn.error = context.services.localization.get("gormorpheus.provision.xenServer.resize.adjustVm")
					log.warn("${rtn.error}")
					return rtn
				}
			}
			if (opts.volumes) {
				def newCounter = computeServer.volumes?.size()
				resizeRequest.volumesUpdate?.each { volumeUpdate ->
					StorageVolume existing = volumeUpdate.existingModel
					Map updateProps = volumeUpdate.updateProps
					if (updateProps.maxStorage > existing.maxStorage) {
						def resizeDiskConfig = [diskSize: updateProps.maxStorage, diskIndex: existing.externalId, uuid: existing.internalId]
						def resizeResults = XenComputeUtility.resizeVmDisk(authConfigMap, computeServer.externalId, resizeDiskConfig)
						log.debug("resizeResults ${resizeResults}")
						def existingVolume = context.async.storageVolume.get(existing.id).blockingGet()
						existingVolume.maxStorage = updateProps?.maxStorage
						context.async.storageVolume.save(existingVolume).blockingGet()
					}
				}
				def datastoreIds = []
				def storageVolumeTypes = [:]
				resizeRequest.volumesAdd?.each { Map volumeAdd ->
					log.debug("volumeAdd: ${volumeAdd}")
					def datastoreId = volumeAdd.datastoreId
					if (!(datastoreId == 'auto' || datastoreId == 'autoCluster')) {
						datastoreIds << datastoreId?.toLong()
					}
					def storageVolumeTypeId = volumeAdd.storageType.toLong()
					if(!storageVolumeTypes[storageVolumeTypeId]) {
						storageVolumeTypes[storageVolumeTypeId] = context.async.storageVolume.storageVolumeType.get(storageVolumeTypeId).blockingGet()
					}
				}
				datastoreIds = datastoreIds.unique()
				def datastores = context.async.cloud.datastore.listById(datastoreIds).toMap {it.id.toLong()}.blockingGet()
				resizeRequest.volumesAdd.each { volumeAdd ->
					//new disk add it
					def addDiskConfig = [diskSize: volumeAdd.maxStorage, diskName: "morpheus_data_${newCounter}", diskIndex: newCounter]
					def datastore = datastores[volumeAdd.datastoreId.toLong()]
					addDiskConfig.datastoreId = datastore?.externalId
					def addDiskResults = XenComputeUtility.addVmDisk(authConfigMap, computeServer.externalId, addDiskConfig)
					log.debug("addDiskResults ${addDiskResults}")
					if (addDiskResults.success == true) {
						def newVolume = buildStorageVolume(computeServer, volumeAdd, addDiskResults, newCounter)
						def volumeType = storageVolumeTypes[volumeAdd.storageType.toLong()]
						newVolume.type = volumeType
						newVolume.datastore = datastore
						def uniqueId = isWorkload ? "morpheus-vol-${instanceId}-${workload.id}-${newCounter}" : "morpheus-vol-${server.id}-${newCounter}"
						newVolume.uniqueId = uniqueId
						setVolumeInfo(computeServer.volumes, addDiskResults.volumes)
						context.async.storageVolume.create([newVolume], computeServer).blockingGet()
						computeServer = getMorpheusServer(computeServer.id)
						if (isWorkload) {
							workload.server = computeServer
						}
						newCounter++
					} else {
						log.warn("error adding disk: ${addDiskResults}")
					}
				}
				resizeRequest.volumesDelete.each { volume ->
					def deleteResults = XenComputeUtility.deleteVmDisk(authConfigMap, computeServer.externalId, volume.internalId)
					log.debug("deleteResults ${deleteResults}")
					if (deleteResults.success == true) {
						context.async.storageVolume.remove([volume], computeServer, true).blockingGet()
						computeServer = getMorpheusServer(computeServer.id)
					}
				}
			}
			//networks
			if (opts.networkInterfaces) {
				resizeRequest?.interfacesUpdate?.eachWithIndex { networkUpdate, index ->
					if (networkUpdate.existingModel) {
						log.debug("modifying network: ${networkUpdate}")
					}
				}
				resizeRequest.interfacesAdd.eachWithIndex { networkAdd, index ->
					def newIndex = computeServer.interfaces?.size()
					def newNetwork = context.async.network.listById([networkAdd.network.id.toLong()]).firstOrError().blockingGet()
					def networkConfig = [networkIndex: newIndex, networkUuid: newNetwork.externalId]
					def networkResults = XenComputeUtility.addVmNetwork(authConfigMap, computeServer.externalId, networkConfig)
					log.debug("networkResults ${networkResults}")
					if (networkResults.success == true) {
						def newInterface = buildNetworkInterface(computeServer, networkResults, newNetwork, newIndex)
						newInterface.uniqueId = isWorkload ? "morpheus-nic-${instanceId}-${workload.id}-${newIndex}" : "morpheus-nic-${server.id}-${newIndex}"
						newInterface.primaryInterface = false
						context.async.computeServer.computeServerInterface.create([newInterface], computeServer).blockingGet()
						computeServer = getMorpheusServer(computeServer.id)
					}
				}
				resizeRequest?.interfacesDelete?.eachWithIndex { networkDelete, index ->
					def interfaceId = networkDelete?.id

					if (interfaceId) {
					    try {
					    	computeServer = getMorpheusServer(computeServer.id)
					    	context.async.computeServer.computeServerInterface.remove([networkDelete], computeServer).blockingGet()
					        authConfigMap.stopped = opts.stopped
							def deleteResults = XenComputeUtility.deleteVmNetwork(authConfigMap, computeServer.externalId, networkDelete.internalId)
							log.debug("netdeleteResults: ${deleteResults}")
							if (deleteResults.success == true) {
								computeServer.interfaces = computeServer.interfaces.findAll { it.id != networkDelete.id }
								context.async.computeServer.save(computeServer).blockingGet()
								computeServer = getMorpheusServer(computeServer.id)
							}
					       
					    } catch (Exception e) {
					        log.error("Failed to delete NIC ${interfaceId}: ${e.message}", e)
					    }
					}
				}
			}
			computeServer.status = 'provisioned'
			computeServer = saveAndGetMorpheusServer(computeServer)
			rtn.success = true
		} catch (e) {
			log.error("Unable to resize workload: ${e.message}", e)
			computeServer.status = 'provisioned'
			if (!isWorkload)
				computeServer.statusMessage = "Unable to resize server: ${e.message}"
			computeServer = saveAndGetMorpheusServer(computeServer)
			rtn.success = false
			def error = morpheus.services.localization.get("gomorpheus.provision.xenServer.error.resizeWorkload")
			rtn.setError(error)
		}
		return rtn
	}

	def getInterfaceName(platform, index) {
		def nicName
		if (platform == 'windows') {
			nicName = (index == 0) ? 'Ethernet' : 'Ethernet ' + (index + 1)
		} else if (platform == 'linux') {
			nicName = "eth${index}"
		} else {
			nicName = "eth${index}"
		}
		return nicName
	}

	def buildStorageVolume(computeServer, volumeAdd, addDiskResults, newCounter) {
		def newVolume = new StorageVolume(
				refType		: 'ComputeZone',
				refId		: computeServer.cloud.id,
				regionCode	: computeServer.region?.regionCode,
				account		: computeServer.account,
				maxStorage	: volumeAdd.maxStorage?.toLong(),
				maxIOPS		: volumeAdd.maxIOPS?.toInteger(),
				externalId	: addDiskResults.volume?.uuid,
				internalId 	: addDiskResults.volume?.uuid, // This is used in embedded
				deviceName	: addDiskResults.volume?.deviceName,
				name		: volumeAdd.name,
				displayOrder: newCounter,
				status		: 'provisioned',
				unitNumber	: addDiskResults.volume?.deviceIndex?.toString(),
				deviceDisplayName : getDiskDisplayName(newCounter)
		)
		return newVolume
	}

	def buildNetworkInterface(server, networkResults, newNetwork, newIndex) {
		def newInterface = new ComputeServerInterface([
				name        : getInterfaceName(server.platform, newIndex),
				externalId  : "${networkResults.networkIndex}",
				internalId  : networkResults.uuid,
				network     : newNetwork,
				displayOrder: newIndex
		])
		return newInterface
	}

	@Override
	ServiceResponse getXvpVNCConsoleUrl(ComputeServer server) {
		def authConfigMap = plugin.getAuthConfig(server.cloud)
		def consoleInfo = XenComputeUtility.getConsoles(authConfigMap, server.externalId)
		if(consoleInfo.success) {
			return ServiceResponse.success([url: consoleInfo.consoles?.first(), headers: [[name: 'Cookie', value: "session_id=${consoleInfo.sessionId}".toString()]], httpVersion: 'HTTP/1.0'])
		}
		return ServiceResponse.error();

	}

	@Override
	ServiceResponse<ImportWorkloadResponse> importWorkload(ImportWorkloadRequest importWorkloadRequest) {
		log.debug("importWorkload started")
		ImportWorkloadResponse response = new ImportWorkloadResponse()
		ServiceResponse serviceResponse = ServiceResponse.prepare(response)
		try {
			Workload workload = importWorkloadRequest.workload
			ComputeServer server = workload?.server
			def authConfigMap = plugin.getAuthConfig(server.cloud)
			def snapshotName = "${workload?.instance?.name}-${workload?.id}-${System.currentTimeMillis()}"
			def vmName = workload?.instance?.containers?.size() > 0 ? "${workload?.instance?.name}-${workload?.id}" : workload?.instance?.name

			def doRestoreCloudInitCache = false // do we need to enable cloud init after export is complete?
			if(server.powerState.toString() != 'off') {
				if(server.serverOs?.platform?.toString() != 'windows') {
					if(server.sourceImage && server.sourceImage.isCloudInit()) {
						// disable cloud init cache and flush to disk
						log.debug("importWorkload: disable cloud-init cache")
						context.executeCommandOnServer(server, '''
							sudo rm -f /etc/cloud/cloud.cfg.d/99-manual-cache.cfg; 
							sudo cp /etc/machine-id /var/tmp/machine-id-old; 
							sudo > /etc/machine-id; 
							sudo mv /var/lib/cloud/instance /var/tmp/cloud-init-instance;
							sync; sync;
						''', false, server.sshUsername, server.sshPassword, null, null, null, null, true, true).blockingGet()
						doRestoreCloudInitCache = true
					} else {
						// just flush to disk
						log.debug("importWorkload: flush to disk")
						context.executeCommandOnServer(server, 'sync; sync;', false, server.sshUsername, server.sshPassword, null, null, null, null, true, true).blockingGet()
					}
				}
			}

			def snapshotOpts = [authConfig: authConfigMap, externalId: server.externalId, snapshotName: snapshotName, snapshotDescription: 'morpheus import']
			log.debug("importWorkload snapshotOpts: {}", snapshotOpts)
			def snapshotResults = XenComputeUtility.snapshotVm(snapshotOpts, server.externalId)
			log.debug("importWorkload snapshotResults: {}", snapshotResults)
			if(snapshotResults.success) {
				def storageProvider = importWorkloadRequest.storageBucket
				def providerMap = storageProvider?.id ? context.async.storageBucket.getBucketStorageProvider(storageProvider.id).blockingGet() : null
				if(providerMap) {

					def cloudBucket = providerMap[storageProvider.bucketName]
					def exportImage = importWorkloadRequest.targetImage
					exportImage.virtualImageType = new VirtualImageType(code: 'xva')
					exportImage.imageType = ImageType.xva
					exportImage.owner = server.account
					exportImage.cloudInit = server.sourceImage.cloudInit
					exportImage.installAgent = server.sourceImage.installAgent
					exportImage.osType = server.sourceImage.osType
					exportImage.platform = server.sourceImage.platform
					exportImage.vmToolsInstalled = server.sourceImage.vmToolsInstalled;
					exportImage = context.async.virtualImage.create(exportImage).blockingGet()
					def archiveFolder = "${importWorkloadRequest.imageBasePath}/${exportImage.id}".toString()
					exportImage.remotePath = archiveFolder
					context.async.virtualImage.save(exportImage).blockingGet()
					def archiveResults
					try {
						def archiveOpts = [authConfig: authConfigMap, snapshotId: snapshotResults.snapshotId, vmName: vmName, zone: server.cloud, snapshotName: snapshotResults.snapshotName]
						log.debug("importWorkload, archiveOpts: {}", archiveOpts)
						archiveResults = XenComputeUtility.archiveVm(archiveOpts, snapshotResults.snapshotId, cloudBucket, archiveFolder) { percent ->
							exportImage.statusPercent = percent.toDouble()
							context.services.virtualImage.save(exportImage)
						}
					} finally {
						if(doRestoreCloudInitCache) {
							Map serverReadyResponse = checkServerReady([authConfig: authConfigMap, externalId: server.externalId])
							if(serverReadyResponse.success) {
								//restore cloud init cache
								if(server.sourceImage && server.sourceImage.isCloudInit() && server.serverOs?.platform?.toString() != 'windows') {
									log.debug("importWorkload: restore cloud-init cache")
									context.executeCommandOnServer(server, '''
										sudo bash -c \"echo 'manual_cache_clean: True' >> /etc/cloud/cloud.cfg.d/99-manual-cache.cfg\"; 
										sudo cat /var/tmp/machine-id-old > /etc/machine-id; 
										sudo rm /var/tmp/machine-id-old; 
										sudo mv /var/tmp/cloud-init-instance /var/lib/cloud/instance;
										sync;
									''', false, server.sshUsername, server.sshPassword, null, null, null, null, true, true).blockingGet()
								}
							} else {
								log.warn("importWorkload: Timeout exceeded waiting for server to power on, cloud init settings will not be restored.")
							}
						}
					}
					if(archiveResults?.success == true) {
						response.virtualImage = exportImage
						response.imagePath = archiveFolder
						serviceResponse.data = response
						serviceResponse.success = true
					} else {
						serviceResponse.success = false
						serviceResponse.msg = "Failed to export image."
					}
				} else {
					serviceResponse.success = false
					serviceResponse.mgs = "Storage bucket not found."
				}
			}
		} catch (e) {
			log.error("importWorkload error: ${e}", e)
			serviceResponse.msg = e.message
		}
		return serviceResponse
	}
}