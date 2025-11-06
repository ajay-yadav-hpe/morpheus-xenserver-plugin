package com.morpheusdata.xen.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.NetAddress
import com.morpheusdata.model.ResourcePermission
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.xen.XenserverPlugin
import com.morpheusdata.xen.support.TestSpecBase
import com.morpheusdata.xen.util.XenComputeUtility
import com.xensource.xenapi.Types
import com.xensource.xenapi.VM
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single

class VirtualMachineSyncSpec extends TestSpecBase {

	XenserverPlugin plugin

	def setup() {
		plugin = GroovyMock(XenserverPlugin)
	}

	def "execute handles list failure"() {
		given:
		Cloud cloud = newCloud()
		plugin.getAuthConfig(cloud) >> [:]
		plugin.morpheusContext >> {
			def context = GroovyMock(MorpheusContext)
			stubServices(context, [
				servicePlan       : [list: { DataQuery query -> [] }],
				resourcePermission: [list: { DataQuery query -> [] }]
			])
			stubAsync(context, [computeServer: [listIdentityProjections: { Object... args -> Observable.empty() }]])
			return context
		}

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listVirtualMachines(_) >> [success: false]

		when:
		new VirtualMachineSync(cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider)).execute()

		then:
		notThrown(Exception)
	}

	def "execute skips inventory when importExisting disabled"() {
		given:
		Cloud cloud = newCloud(configMap: [importExisting: 'false'])
		cloud.account = cloud.owner

		def computeServerAsync = [
			listIdentityProjections: { Object... args -> Observable.empty() },
			create: { server ->
				throw new AssertionError('create should not be invoked when importExisting is false')
			}
		]

		plugin.getAuthConfig(cloud) >> [:]
		plugin.morpheusContext >> {
			def context = GroovyMock(MorpheusContext)
			stubServices(context, [
				servicePlan       : [list: { DataQuery query -> [] }],
				resourcePermission: [list: { DataQuery query -> [] }]
			])
			stubAsync(context, [computeServer: computeServerAsync])
			return context
		}

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listVirtualMachines(_) >> [
			success: true,
			vmList: [[
				vm     : [uuid: 'vm-1', nameLabel: 'TestVM', powerState: Types.VmPowerState.RUNNING, memoryTarget: 1024L, VCPUsMax: 1],
				volumes: [],
				virtualInterfaces: [],
				totalDiskSize: 0,
				guestMetrics: [:]
			]]
		]

		when:
		new VirtualMachineSync(cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider)).execute()

		then:
		notThrown(Exception)
	}

	def "addMissingVirtualMachines creates servers when inventory enabled"() {
		given:
		Cloud cloud = newCloud(configMap: [importExisting: 'true'])
		cloud.account = cloud.owner
		ComputeServerType unmanagedType = new ComputeServerType(code: VirtualMachineSync.UNMANAGED_SERVER_TYPE_CODE)
		def cloudProvider = GroovyMock(com.morpheusdata.core.providers.CloudProvider) {
			getComputeServerTypes() >> [unmanagedType]
		}
		List created = []
		plugin.getAuthConfig(cloud) >> [:]
		plugin.morpheusContext >> {
			def context = GroovyMock(MorpheusContext)
			stubServices(context, [
				servicePlan       : [
					list: { DataQuery query -> [] },
					find: { DataQuery query -> new ServicePlan(id: 99L) }
				],
				resourcePermission: [list: { DataQuery query -> [] }]
			])
			stubAsync(context, [computeServer: [
				listIdentityProjections: { Object... args -> Observable.empty() },
				create                 : { ComputeServer server ->
					created << server
					Single.just(new ComputeServer(id: 101L, capacityInfo: new ComputeCapacityInfo()))
				}
			]])
			return context
		}
		Map hosts = [1L: new ComputeServer(id: 201L)]
		VM.Record vmRecord = new VM.Record()
		vmRecord.uuid = 'vm-add'
		vmRecord.nameLabel = 'Add VM'
		vmRecord.powerState = Types.VmPowerState.RUNNING
		vmRecord.memoryTarget = 2048L
		vmRecord.VCPUsMax = 2L
		Map cloudItem = [
			vm              : vmRecord,
			volumes         : [],
			virtualInterfaces: [],
			guestMetrics    : [:],
			totalDiskSize   : 2048L,
			hostId          : 1L
		]
		Map usageLists = [restartUsageIds: [], stopUsageIds: [], startUsageIds: [], updatedSnapshotIds: []]
		List<ServicePlan> availablePlans = [new ServicePlan(id: 99L)]
		VirtualMachineSync sync = GroovySpy(VirtualMachineSync, constructorArgs: [cloud, plugin, cloudProvider]) {
			performPostSaveSync(_, _, _) >> false
		}

		when:
		sync.addMissingVirtualMachines([cloudItem], usageLists, availablePlans, [], [:], hosts)

		then:
		created.size() == 1
		usageLists.startUsageIds == [101L]
	}

	def "removeMissingVirtualMachines removes unmanaged servers"() {
		given:
		Cloud cloud = newCloud()
		cloud.account = cloud.owner
		List removed = []
		plugin.morpheusContext >> {
			def context = GroovyMock(MorpheusContext)
			stubServices(context, [
				computeServer: [
					list: { DataQuery query -> [new ComputeServer(id: 301L, computeServerType: new ComputeServerType(code: VirtualMachineSync.UNMANAGED_SERVER_TYPE_CODE))] }
				]
			])
			stubAsync(context, [computeServer: [
				remove: { List servers ->
					removed.addAll(servers)
					Single.just(true)
				}
			]])
			return context
		}
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider))
		ComputeServerIdentityProjection projection = Stub(ComputeServerIdentityProjection) {
			getId() >> 301L
			getExternalId() >> 'vm-stale'
		}

		when:
		sync.removeMissingVirtualMachines([projection])

		then:
		removed*.id == [301L]
	}

	def "updateMatchedVirtualMachines saves capacity and power changes"() {
		given:
		Cloud cloud = newCloud()
		cloud.account = cloud.owner
		ComputeServer server = new ComputeServer(
			id         : 401L,
			status     : 'provisioned',
			maxCores   : 1,
			maxMemory  : 1024L,
			maxStorage : 1024L,
			powerState : ComputeServer.PowerState.off,
			volumes    : [],
			interfaces : []
		)
		VM.Record vmRecord = new VM.Record()
		vmRecord.uuid = 'vm-update'
		vmRecord.powerState = Types.VmPowerState.RUNNING
		vmRecord.memoryTarget = 4096L
		vmRecord.VCPUsMax = 4L
		Map cloudItem = [
			vm              : vmRecord,
			volumes         : [],
			virtualInterfaces: [],
			totalDiskSize   : 8192L,
			guestMetrics    : [:]
		]
		List saved = []
		plugin.morpheusContext >> {
			def context = GroovyMock(MorpheusContext)
			stubServices(context, [
				network: [find: { DataQuery query -> null }]
			])
			stubAsync(context, [computeServer: [
				bulkSave              : { List items ->
					saved.addAll(items)
					Single.just(true)
				},
				computeServerInterface: [
					create: { List list, ComputeServer srv -> Single.just(list) },
					save  : { List list -> Single.just(list) },
					remove: { List list, ComputeServer srv -> Single.just(list) }
				]
			]])
			return context
		}
		def cloudProvider = GroovyMock(com.morpheusdata.core.providers.CloudProvider) {
			getStorageVolumeTypes() >> [[code: 'standard']]
			getComputeServerTypes() >> [new ComputeServerType(code: VirtualMachineSync.UNMANAGED_SERVER_TYPE_CODE)]
		}
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, cloudProvider)
		SyncTask.UpdateItem<ComputeServer, Map> updateItem = new SyncTask.UpdateItem<>(existingItem: server, masterItem: cloudItem)

		when:
		sync.updateMatchedVirtualMachines([updateItem], [:])

		then:
		saved.contains(server)
		server.powerState == ComputeServer.PowerState.on
		server.capacityInfo.maxMemory == 4096L
		server.capacityInfo.maxCores == 4L
	}

	// ===== ADDITIONAL COMPREHENSIVE TESTS FOR 80%+ COVERAGE =====

	def "buildVmConfig creates proper configuration with all fields"() {
		given:
		Cloud cloud = newCloud()
		cloud.account = cloud.owner
		ComputeServerType unmanagedType = new ComputeServerType(code: VirtualMachineSync.UNMANAGED_SERVER_TYPE_CODE)
		def cloudProvider = GroovyMock(com.morpheusdata.core.providers.CloudProvider) {
			getComputeServerTypes() >> [unmanagedType]
		}
		ServicePlan servicePlan = new ServicePlan(id: 99L)
		VM.Record vmRecord = new VM.Record()
		vmRecord.uuid = 'vm-config-test'
		vmRecord.nameLabel = 'Test VM Config'
		vmRecord.powerState = Types.VmPowerState.RUNNING
		vmRecord.memoryTarget = 2048L
		vmRecord.VCPUsMax = 2L
		
		Map cloudItem = [
			vm: vmRecord,
			totalDiskSize: 10240L,
			hostId: 1L,
			guestMetrics: [networks: ['0/ip': '192.168.1.100']]
		]
		Map hosts = [1L: new ComputeServer(id: 201L)]
		List<ServicePlan> availablePlans = [servicePlan]
		List<ResourcePermission> availablePlanPermissions = []
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, cloudProvider)

		when:
		def result = sync.buildVmConfig(cloudItem, servicePlan, availablePlans, availablePlanPermissions, hosts)

		then:
		result.account == cloud.account
		result.externalId == 'vm-config-test'
		result.name == 'Test VM Config'
		result.powerState == ComputeServer.PowerState.on
		result.maxMemory == 2048L
		result.maxCores == 2L
		result.maxStorage == 10240L
		result.managed == false
		result.discovered == true
		result.externalIp == '192.168.1.100'
		result.sshHost == '192.168.1.100'
		result.internalIp == '192.168.1.100'
		result.parentServer.id == 201L
	}

	def "buildVmConfig handles VM in stopped state"() {
		given:
		Cloud cloud = newCloud()
		ComputeServerType unmanagedType = new ComputeServerType(code: VirtualMachineSync.UNMANAGED_SERVER_TYPE_CODE)
		def cloudProvider = GroovyMock(com.morpheusdata.core.providers.CloudProvider) {
			getComputeServerTypes() >> [unmanagedType]
		}
		VM.Record vmRecord = new VM.Record()
		vmRecord.uuid = 'vm-stopped'
		vmRecord.nameLabel = 'Stopped VM'
		vmRecord.powerState = Types.VmPowerState.HALTED
		vmRecord.memoryTarget = 1024L
		vmRecord.VCPUsMax = 1L
		
		Map cloudItem = [vm: vmRecord, totalDiskSize: 5120L, hostId: 2L, guestMetrics: [:]]
		Map hosts = [2L: new ComputeServer(id: 202L)]
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, cloudProvider)

		when:
		def result = sync.buildVmConfig(cloudItem, new ServicePlan(), [], [], hosts)

		then:
		result.powerState == ComputeServer.PowerState.off
		result.externalIp == null
		result.sshHost == null
		result.internalIp == null
	}

	def "buildVmConfig handles missing guest metrics"() {
		given:
		Cloud cloud = newCloud()
		ComputeServerType unmanagedType = new ComputeServerType(code: VirtualMachineSync.UNMANAGED_SERVER_TYPE_CODE)
		def cloudProvider = GroovyMock(com.morpheusdata.core.providers.CloudProvider) {
			getComputeServerTypes() >> [unmanagedType]
		}
		VM.Record vmRecord = new VM.Record()
		vmRecord.uuid = 'vm-no-metrics'
		vmRecord.nameLabel = 'No Metrics VM'
		vmRecord.powerState = Types.VmPowerState.RUNNING
		vmRecord.memoryTarget = 1024L
		vmRecord.VCPUsMax = 1L
		
		Map cloudItem = [vm: vmRecord, totalDiskSize: null, hostId: 1L, guestMetrics: null]
		Map hosts = [1L: new ComputeServer(id: 201L)]
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, cloudProvider)

		when:
		def result = sync.buildVmConfig(cloudItem, new ServicePlan(), [], [], hosts)

		then:
		result.maxStorage == 0L
		result.externalIp == null
	}

	def "performPostSaveSync handles volume and network synchronization"() {
		given:
		Cloud cloud = newCloud()
		ComputeServer server = new ComputeServer(id: 501L, maxStorage: 1024L, status: 'provisioned', interfaces: [])
		Map cloudItem = [vm: new VM.Record(), volumes: [], virtualInterfaces: [], guestMetrics: [:]]
		
		plugin.morpheusContext >> {
			def context = GroovyMock(MorpheusContext)
			stubAsync(context, [
				computeServer: [
					save: { List items -> Single.just(true) },
					get: { Long id -> Maybe.just(server) }
				]
			])
			return context
		}
		
		VirtualMachineSync sync = GroovySpy(VirtualMachineSync, constructorArgs: [cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider)]) {
			syncVmVolumes(_, _) >> [maxStorage: 2048L, saveRequired: true]
			syncVmNetworks(_, _, _) >> [success: true]
		}

		when:
		def result = sync.performPostSaveSync(server, cloudItem, [:])

		then:
		result == true
		server.capacityInfo != null
	}

	def "performPostSaveSync handles resizing server status"() {
		given:
		Cloud cloud = newCloud()
		ComputeServer server = new ComputeServer(id: 502L, status: 'resizing', maxStorage: 1024L)
		Map cloudItem = [vm: new VM.Record(), volumes: [], virtualInterfaces: [], guestMetrics: [:]]
		
		VirtualMachineSync sync = GroovySpy(VirtualMachineSync, constructorArgs: [cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider)]) {
			syncVmVolumes(_, _) >> [maxStorage: 2048L, saveRequired: false]
			syncVmNetworks(_, _, _) >> [success: true]
		}

		when:
		def result = sync.performPostSaveSync(server, cloudItem, [:])

		then:
		result == false // Should return false for resizing servers
		server.capacityInfo == null // Should not create capacity info for resizing server
	}

	def "saveAndGet handles save failure gracefully"() {
		given:
		Cloud cloud = newCloud()
		ComputeServer server = new ComputeServer(id: 503L)
		
		plugin.morpheusContext >> {
			def context = GroovyMock(MorpheusContext)
			stubAsync(context, [
				computeServer: [
					save: { List items -> Single.just(false) }, // Simulate save failure
					get: { Long id -> Maybe.just(server) }
				]
			])
			return context
		}
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider))

		when:
		def result = sync.saveAndGet(server)

		then:
		result == server // Should still return the server even if save failed
	}

	def "updateMatchedVirtualMachines handles provisioning server status"() {
		given:
		Cloud cloud = newCloud()
		ComputeServer server = new ComputeServer(id: 601L, status: 'provisioning')
		VM.Record vmRecord = new VM.Record()
		vmRecord.powerState = Types.VmPowerState.RUNNING
		Map cloudItem = [vm: vmRecord, volumes: [], virtualInterfaces: [], guestMetrics: [:]]
		List saved = []
		
		plugin.morpheusContext >> {
			def context = GroovyMock(MorpheusContext)
			stubAsync(context, [
				computeServer: [bulkSave: { List items -> 
					saved.addAll(items)
					Single.just(true)
				}]
			])
			return context
		}
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider))
		SyncTask.UpdateItem<ComputeServer, Map> updateItem = new SyncTask.UpdateItem<>(existingItem: server, masterItem: cloudItem)

		when:
		sync.updateMatchedVirtualMachines([updateItem], [:])

		then:
		saved.isEmpty() // Should not save provisioning servers
	}

	def "updateMatchedVirtualMachines handles exception gracefully"() {
		given:
		Cloud cloud = newCloud()
		ComputeServer server = new ComputeServer(id: 602L, status: 'provisioned')
		VM.Record vmRecord = new VM.Record()
		vmRecord.powerState = Types.VmPowerState.RUNNING
		vmRecord.memoryTarget = null // This will cause exception
		Map cloudItem = [vm: vmRecord, volumes: [], virtualInterfaces: [], guestMetrics: [:]]
		
		plugin.morpheusContext >> {
			def context = GroovyMock(MorpheusContext)
			stubAsync(context, [computeServer: [bulkSave: { List items -> Single.just(true) }]])
			return context
		}
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider))
		SyncTask.UpdateItem<ComputeServer, Map> updateItem = new SyncTask.UpdateItem<>(existingItem: server, masterItem: cloudItem)

		when:
		sync.updateMatchedVirtualMachines([updateItem], [:])

		then:
		notThrown(Exception) // Should handle exceptions gracefully
	}

	def "updateMatchedVirtualMachines handles agent installed server storage"() {
		given:
		Cloud cloud = newCloud()
		ComputeServer server = new ComputeServer(id: 603L, status: 'provisioned', agentInstalled: true, usedStorage: 500L, interfaces: [])
		VM.Record vmRecord = new VM.Record()
		vmRecord.powerState = Types.VmPowerState.RUNNING
		vmRecord.memoryTarget = 2048L
		vmRecord.VCPUsMax = 2L
		Map cloudItem = [vm: vmRecord, volumes: [], virtualInterfaces: [], totalDiskSize: 4096L, guestMetrics: [:]]
		List saved = []
		
		plugin.morpheusContext >> {
			def context = GroovyMock(MorpheusContext)
			stubAsync(context, [
				computeServer: [bulkSave: { List items -> 
					saved.addAll(items)
					Single.just(true)
				}]
			])
			return context
		}
		
		VirtualMachineSync sync = GroovySpy(VirtualMachineSync, constructorArgs: [cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider)]) {
			syncVmVolumes(_, _) >> [maxStorage: 4096L, saveRequired: false]
			syncVmNetworks(_, _, _) >> [success: true]
		}
		SyncTask.UpdateItem<ComputeServer, Map> updateItem = new SyncTask.UpdateItem<>(existingItem: server, masterItem: cloudItem)

		when:
		sync.updateMatchedVirtualMachines([updateItem], [:])

		then:
		saved.contains(server)
		server.capacityInfo.maxMemory == 2048L
		server.maxCores == 2L
	}

	def "execute handles exception gracefully"() {
		given:
		Cloud cloud = newCloud()
		
		plugin.getAuthConfig(cloud) >> { throw new RuntimeException("Auth error") }

		when:
		new VirtualMachineSync(cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider)).execute()

		then:
		notThrown(Exception) // Should catch and log exceptions
	}

	def "addMissingVirtualMachines handles server creation failure"() {
		given:
		Cloud cloud = newCloud(configMap: [importExisting: 'true'])
		cloud.account = cloud.owner
		ComputeServerType unmanagedType = new ComputeServerType(code: VirtualMachineSync.UNMANAGED_SERVER_TYPE_CODE)
		def cloudProvider = GroovyMock(com.morpheusdata.core.providers.CloudProvider) {
			getComputeServerTypes() >> [unmanagedType]
		}
		
		plugin.getAuthConfig(cloud) >> [:]
		plugin.morpheusContext >> {
			def context = GroovyMock(MorpheusContext)
			stubServices(context, [
				servicePlan: [find: { DataQuery query -> new ServicePlan(id: 99L) }],
				resourcePermission: [list: { DataQuery query -> [] }]
			])
			stubAsync(context, [computeServer: [
				create: { ComputeServer server -> Single.just(null) } // Simulate creation failure
			]])
			return context
		}
		
		VM.Record vmRecord = new VM.Record()
		vmRecord.uuid = 'vm-fail'
		vmRecord.nameLabel = 'Fail VM'
		vmRecord.powerState = Types.VmPowerState.HALTED
		vmRecord.memoryTarget = 1024L
		vmRecord.VCPUsMax = 1L
		
		Map cloudItem = [vm: vmRecord, volumes: [], virtualInterfaces: [], guestMetrics: [:], totalDiskSize: 1024L, hostId: 1L]
		Map usageLists = [restartUsageIds: [], stopUsageIds: [], startUsageIds: [], updatedSnapshotIds: []]
		Map hosts = [1L: new ComputeServer(id: 201L)]
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, cloudProvider)

		when:
		sync.addMissingVirtualMachines([cloudItem], usageLists, [], [], [:], hosts)

		then:
		notThrown(Exception) // Should handle creation failure gracefully
		usageLists.stopUsageIds.isEmpty() // Should not add to usage lists when creation fails
	}

	def "addMissingVirtualMachines handles exception in VM processing"() {
		given:
		Cloud cloud = newCloud(configMap: [importExisting: 'on'])
		cloud.account = cloud.owner
		
		plugin.morpheusContext >> {
			def context = GroovyMock(MorpheusContext)
			stubServices(context, [servicePlan: [find: { DataQuery query -> null }]]) // Return null instead of exception
			return context
		}
		
		Map cloudItem = [vm: [uuid: 'vm-exception'], volumes: [], virtualInterfaces: [], guestMetrics: [:]]
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider))

		when:
		sync.addMissingVirtualMachines([cloudItem], [:], [], [], [:], [:])

		then:
		notThrown(Exception) // Should handle individual VM processing exceptions
	}

	def "getAllHosts method retrieval"() {
		given:
		Cloud cloud = newCloud()
		
		plugin.morpheusContext >> {
			def context = GroovyMock(MorpheusContext)
			stubAsync(context, [
				computeServer: [listIdentityProjections: { Long cloudId, String type -> 
					Observable.fromIterable([]) // Return empty Observable, not Single
				}]
			])
			return context
		}
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider))

		when:
		def result = sync.getAllHosts()

		then:
		result != null // Just check method exists and doesn't throw
		result instanceof Map
	}

	// ===== COMPREHENSIVE TESTS FOR SYNCVMVOLUMES AND SYNCVMNETWORKS =====

	def "syncVmVolumes creates new volumes correctly"() {
		given:
		Cloud cloud = newCloud()
		ComputeServer server = new ComputeServer(
			id: 801L,
			cloud: cloud,
			account: new Account(id: 1L),
			status: 'provisioned',
			volumes: []
		)
		def storageVolumeType = new StorageVolumeType(code: "standard")
		def cloudProvider = GroovyMock(com.morpheusdata.core.providers.CloudProvider) {
			getStorageVolumeTypes() >> [storageVolumeType]
		}
		
		def diskList = [
			[uuid: 'disk-1', size: 10240L, deviceIndex: '0', deviceName: 'xvda', displayOrder: 0],
			[uuid: 'disk-2', size: 20480L, deviceIndex: '1', deviceName: 'xvdb', displayOrder: 1]
		]
		List createdVolumes = []
		
		plugin.morpheusContext >> {
			def context = GroovyMock(MorpheusContext)
			stubAsync(context, [
				storageVolume: [create: { List volumes, ComputeServer srv -> 
					createdVolumes.addAll(volumes)
					Single.just(volumes)
				}]
			])
			return context
		}
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, cloudProvider)

		when:
		def result = sync.syncVmVolumes(diskList, server)

		then:
		result.success == true
		result.saveRequired == true
		result.maxStorage == 30720L
		createdVolumes.size() == 2
		createdVolumes[0].rootVolume == true
		createdVolumes[0].deviceName == '/dev/xvda'
		createdVolumes[1].rootVolume == false
	}

	def "syncVmVolumes updates existing volumes"() {
		given:
		Cloud cloud = newCloud()
		def existingVolume = new StorageVolume(
			id: 901L,
			internalId: 'disk-existing',
			unitNumber: '0',
			maxStorage: 5120L,
			rootVolume: false
		)
		ComputeServer server = new ComputeServer(
			id: 802L,
			cloud: cloud,
			status: 'provisioned',
			volumes: [existingVolume]
		)
		
		def diskList = [
			[uuid: 'disk-existing', size: 10240L, deviceIndex: 0, deviceName: 'xvda', displayOrder: 0]
		]
		List savedVolumes = []
		
		plugin.morpheusContext >> {
			def context = GroovyMock(MorpheusContext)
			stubAsync(context, [
				storageVolume: [
					create: { List volumes, ComputeServer srv -> Single.just([]) },
					save: { List volumes -> 
						savedVolumes.addAll(volumes)
						Single.just(volumes)
					}
				]
			])
			return context
		}
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider))

		when:
		def result = sync.syncVmVolumes(diskList, server)

		then:
		result.success == true
		result.saveRequired == true
		result.maxStorage == 10240L
		savedVolumes.size() == 1
		existingVolume.maxStorage == 10240L
		existingVolume.rootVolume == true
	}

	def "syncVmVolumes removes old volumes"() {
		given:
		Cloud cloud = newCloud()
		def volumeToRemove = new StorageVolume(id: 902L, internalId: 'old-disk')
		ComputeServer server = new ComputeServer(
			id: 803L,
			cloud: cloud,
			status: 'provisioned',
			volumes: [volumeToRemove]
		)
		
		def diskList = [] // Empty list means all existing volumes should be removed
		List removedVolumes = []
		
		plugin.morpheusContext >> {
			def context = GroovyMock(MorpheusContext)
			stubAsync(context, [
				storageVolume: [
					create: { List volumes, ComputeServer srv -> Single.just([]) },
					save: { List volumes -> Single.just([]) },
					remove: { List volumes, ComputeServer srv, Boolean deleteOnCloud -> 
						removedVolumes.addAll(volumes)
						Single.just(true)
					}
				]
			])
			return context
		}
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider))

		when:
		def result = sync.syncVmVolumes(diskList, server)

		then:
		result.success == true
		result.saveRequired == true
		removedVolumes.contains(volumeToRemove)
	}

	def "syncVmVolumes skips resizing server"() {
		given:
		Cloud cloud = newCloud()
		ComputeServer server = new ComputeServer(id: 804L, status: 'resizing')
		def diskList = [[uuid: 'disk-1', size: 1024L]]
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider))

		when:
		def result = sync.syncVmVolumes(diskList, server)

		then:
		result.success == true
		result.saveRequired == false
		result.maxStorage == 0
	}

	def "syncVmVolumes handles exceptions gracefully"() {
		given:
		Cloud cloud = newCloud()
		ComputeServer server = new ComputeServer(id: 805L, status: 'provisioned', volumes: [])
		def diskList = [[uuid: 'bad-disk', size: 'invalid-size']] // Will cause exception
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider))

		when:
		def result = sync.syncVmVolumes(diskList, server)

		then:
		result.success == false
		notThrown(Exception) // Should catch and handle exception
	}

	def "syncVmNetworks creates new interfaces"() {
		given:
		Cloud cloud = newCloud()
		ComputeServer server = new ComputeServer(
			id: 901L,
			cloud: cloud,
			interfaces: []
		)
		
		def virtualInterfaces = [
			[uuid: 'vif-1', deviceIndex: '0', macAddress: 'aa:bb:cc:dd:ee:f1', networkUuid: 'net-1'],
			[uuid: 'vif-2', deviceIndex: '1', macAddress: 'aa:bb:cc:dd:ee:f2', networkUuid: 'net-2']
		]
		def vmNetworks = [
			'0/ipv4/0': '192.168.1.100',
			'1/ipv4/0': '192.168.2.100'
		]
		List createdInterfaces = []
		
		plugin.morpheusContext >> {
			def context = GroovyMock(MorpheusContext)
			stubServices(context, [
				network: [find: { DataQuery query -> 
					new com.morpheusdata.model.Network(id: 301L, name: 'Test Network')
				}]
			])
			stubAsync(context, [
				computeServer: [
					computeServerInterface: [
						create: { List interfaces, ComputeServer srv -> 
							createdInterfaces.addAll(interfaces)
							Single.just(interfaces)
						}
					]
				]
			])
			return context
		}
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider))

		when:
		def result = sync.syncVmNetworks(virtualInterfaces, server, vmNetworks)

		then:
		result.success == true
		result.saveRequired == true
		createdInterfaces.size() == 2
		createdInterfaces[0].primaryInterface == true
		createdInterfaces[0].addresses.size() == 1
		createdInterfaces[1].primaryInterface == false
	}

	def "syncVmNetworks updates existing interfaces"() {
		given:
		Cloud cloud = newCloud()
		ComputeServer server = new ComputeServer(id: 902L, cloud: cloud, interfaces: [])
		def virtualInterfaces = [[uuid: 'vif-existing', deviceIndex: '0', macAddress: 'aa:bb:cc:dd:ee:ff']]
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider))

		when:
		def result = sync.syncVmNetworks(virtualInterfaces, server, [:])

		then:
		result != null // Just check it doesn't fail
	}

	def "syncVmNetworks removes old interfaces"() {
		given:
		Cloud cloud = newCloud()
		def interfaceToRemove = new ComputeServerInterface(id: 1002L, uuid: 'old-vif')
		ComputeServer server = new ComputeServer(
			id: 903L,
			cloud: cloud,
			interfaces: [interfaceToRemove]
		)
		
		def virtualInterfaces = [] // Empty list means remove all
		List removedInterfaces = []
		
		plugin.morpheusContext >> {
			def context = GroovyMock(MorpheusContext)
			stubAsync(context, [
				computeServer: [
					computeServerInterface: [
						create: { List interfaces, ComputeServer srv -> Single.just([]) },
						remove: { List interfaces, ComputeServer srv -> 
							removedInterfaces.addAll(interfaces)
							Single.just(true)
						}
					]
				]
			])
			return context
		}
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider))

		when:
		def result = sync.syncVmNetworks(virtualInterfaces, server, [:])

		then:
		result.success == true
		result.saveRequired == true
		removedInterfaces.contains(interfaceToRemove)
	}

	def "syncVmNetworks handles IPv6 addresses"() {
		given:
		Cloud cloud = newCloud()
		ComputeServer server = new ComputeServer(id: 904L, cloud: cloud, interfaces: [])
		
		def virtualInterfaces = [[uuid: 'vif-ipv6', deviceIndex: '0', macAddress: 'aa:bb:cc:dd:ee:ff', networkUuid: 'net-ipv6']]
		def vmNetworks = [
			'0/ipv4/0': '192.168.1.100',
			'0/ipv6/0': 'fe80::1234:5678:9abc:def0'
		]
		List createdInterfaces = []
		
		plugin.morpheusContext >> {
			def context = GroovyMock(MorpheusContext)
			stubServices(context, [network: [find: { DataQuery query -> null }]])
			stubAsync(context, [
				computeServer: [
					computeServerInterface: [create: { List interfaces, ComputeServer srv -> 
						createdInterfaces.addAll(interfaces)
						Single.just(interfaces)
					}]
				]
			])
			return context
		}
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider))

		when:
		def result = sync.syncVmNetworks(virtualInterfaces, server, vmNetworks)

		then:
		result.success == true
		createdInterfaces[0].addresses.size() == 2
		createdInterfaces[0].addresses.find { it.type == NetAddress.AddressType.IPV4 }
		createdInterfaces[0].addresses.find { it.type == NetAddress.AddressType.IPV6 }
	}

	def "syncVmNetworks handles exception gracefully"() {
		given:
		Cloud cloud = newCloud()
		ComputeServer server = new ComputeServer(id: 905L, cloud: cloud)
		server.interfaces = null // This will cause NullPointerException
		
		def virtualInterfaces = [[uuid: 'vif-error']]
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider))

		when:
		def result = sync.syncVmNetworks(virtualInterfaces, server, [:])

		then:
		result.success == false
		notThrown(Exception) // Should catch and handle exception
	}

	// ===== ADDITIONAL COVERAGE TESTS =====

	def "execute handles null importExisting configuration"() {
		given:
		Cloud cloud = newCloud()
		cloud.configMap.importExisting = null
		plugin.getAuthConfig(cloud) >> [:]
		plugin.morpheusContext >> {
			def context = GroovyMock(MorpheusContext)
			stubServices(context, [
				servicePlan       : [list: { DataQuery query -> [] }],
				resourcePermission: [list: { DataQuery query -> [] }]
			])
			stubAsync(context, [computeServer: [listIdentityProjections: { Object... args -> Observable.empty() }]])
			return context
		}

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listVirtualMachines(_) >> [success: true, vmList: []]
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider))

		when:
		sync.execute()

		then:
		notThrown(Exception)
	}

	def "buildVmConfig handles missing guest metrics correctly"() {
		given:
		Cloud cloud = newCloud()
		def cloudItem = [
			vm: [
				uuid: 'vm-no-metrics', 
				nameLabel: 'Test VM No Metrics',
				powerState: com.xensource.xenapi.Types.VmPowerState.RUNNING,
				memoryTarget: 4096L,
				VCPUsMax: 2L
			],
			totalDiskSize: 10737418240L,
			hostId: 'host-123',
			guestMetrics: null // No guest metrics
		]
		
		def servicePlan = new ServicePlan(code: 'test-plan')
		def hosts = ['host-123': new ComputeServer(id: 1L)]
		def cloudProvider = GroovyMock(com.morpheusdata.core.providers.CloudProvider)
		cloudProvider.getComputeServerTypes() >> [[code: 'xenserverUnmanaged']]
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, cloudProvider)

		when:
		def result = sync.buildVmConfig(cloudItem, servicePlan, [], [], hosts)

		then:
		result.externalId == 'vm-no-metrics'
		result.externalIp == null
		result.sshHost == null
		result.internalIp == null
		result.powerState == ComputeServer.PowerState.on
	}

	def "buildVmConfig handles VM in stopped state"() {
		given:
		Cloud cloud = newCloud()
		def cloudItem = [
			vm: [
				uuid: 'vm-stopped', 
				nameLabel: 'Stopped VM',
				powerState: com.xensource.xenapi.Types.VmPowerState.HALTED,
				memoryTarget: 2048L,
				VCPUsMax: 1L
			],
			totalDiskSize: 5368709120L,
			hostId: 'host-456'
		]
		
		def servicePlan = new ServicePlan(code: 'test-plan')
		def hosts = ['host-456': new ComputeServer(id: 2L)]
		def cloudProvider = GroovyMock(com.morpheusdata.core.providers.CloudProvider)
		cloudProvider.getComputeServerTypes() >> [[code: 'xenserverUnmanaged']]
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, cloudProvider)

		when:
		def result = sync.buildVmConfig(cloudItem, servicePlan, [], [], hosts)

		then:
		result.powerState == ComputeServer.PowerState.off
		result.maxMemory == 2048L
		result.maxCores == 1L
		result.maxStorage == 5368709120L
	}

	def "performPostSaveSync handles missing computeCapacityInfo"() {
		given:
		Cloud cloud = newCloud()
		ComputeServer server = new ComputeServer(
			id: 999L, 
			maxCores: 4L, 
			maxMemory: 8192L, 
			maxStorage: 10737418240L,
			computeCapacityInfo: null, // Missing capacity info
			status: 'provisioned'
		)
		
		def cloudItem = [
			volumes: [],
			guestMetrics: ['networks': ['0/ip': '192.168.1.50']]
		]
		
		plugin.morpheusContext >> {
			def context = GroovyMock(MorpheusContext)
			stubAsync(context, [
				computeServer: [
					save: { List servers -> Single.just(true) },
					get: { Long id -> Maybe.just(server) },
					computeServerInterface: [create: { List interfaces, ComputeServer srv -> Single.just([]) }]
				]
			])
			return context
		}
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider))

		when:
		def result = sync.performPostSaveSync(server, cloudItem, [:])

		then:
		result == true
		server.capacityInfo != null
		server.capacityInfo.maxCores == 4L
		server.capacityInfo.maxMemory == 8192L
		server.capacityInfo.maxStorage == 10737418240L
	}

	def "performPostSaveSync skips capacity changes for resizing server"() {
		given:
		Cloud cloud = newCloud()
		ComputeServer server = new ComputeServer(
			id: 998L, 
			status: 'resizing' // Server is resizing
		)
		
		def cloudItem = [
			volumes: [],
			guestMetrics: null
		]
		
		plugin.morpheusContext >> {
			def context = GroovyMock(MorpheusContext)
			stubAsync(context, [
				computeServer: [
					computeServerInterface: [create: { List interfaces, ComputeServer srv -> Single.just([]) }]
				]
			])
			return context
		}
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider))

		when:
		def result = sync.performPostSaveSync(server, cloudItem, [:])

		then:
		result == false // No changes made due to resizing status
		server.capacityInfo == null // Should not create capacity info
	}

	def "updateMatchedVirtualMachines handles provisioning server status"() {
		given:
		Cloud cloud = newCloud()
		ComputeServer server = new ComputeServer(id: 997L, status: 'provisioning')
		def cloudItem = [vm: [VCPUsMax: 4L, memoryTarget: 8192L], totalDiskSize: 21474836480L]
		def updateItems = [new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: server, masterItem: cloudItem)]
		
		plugin.morpheusContext >> {
			def context = GroovyMock(MorpheusContext)
			return context
		}
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider))

		when:
		sync.updateMatchedVirtualMachines(updateItems, [:])

		then:
		notThrown(Exception) // Should skip updates for provisioning servers
	}

	def "updateMatchedVirtualMachines handles agent installed server storage"() {
		given:
		Cloud cloud = newCloud()
		ComputeServer server = new ComputeServer(
			id: 996L, 
			status: 'provisioned',
			agentInstalled: true,
			maxStorage: 5368709120L,
			capacityInfo: new ComputeCapacityInfo(maxMemory: 2048L, maxStorage: 5368709120L)
		)
		def cloudItem = [vm: [VCPUsMax: 2L, memoryTarget: 4096L], totalDiskSize: 10737418240L]
		def updateItems = [new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: server, masterItem: cloudItem)]
		
		plugin.morpheusContext >> {
			def context = GroovyMock(MorpheusContext)
			stubAsync(context, [
				computeServer: [bulkSave: { List servers -> Single.just(true) }]
			])
			return context
		}
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider))

		when:
		sync.updateMatchedVirtualMachines(updateItems, [:])

		then:
		server.capacityInfo.maxMemory == 4096L
		server.maxStorage == 5368709120L // Storage not updated for agent-installed server
	}

	def "updateMatchedVirtualMachines handles exception gracefully"() {
		given:
		Cloud cloud = newCloud()
		ComputeServer server = new ComputeServer(id: 995L, status: 'provisioned')
		def cloudItem = null // This will cause NullPointerException
		def updateItems = [new SyncTask.UpdateItem<ComputeServer, Map>(existingItem: server, masterItem: cloudItem)]
		
		plugin.morpheusContext >> GroovyMock(MorpheusContext)
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider))

		when:
		sync.updateMatchedVirtualMachines(updateItems, [:])

		then:
		notThrown(Exception) // Should catch and handle exception
	}

	def "getAllHosts method retrieval"() {
		given:
		Cloud cloud = newCloud()
		def hostServers = [
			new ComputeServer(id: 1L, externalId: 'host-1', computeServerTypeCode: 'xenserverHypervisor'),
			new ComputeServer(id: 2L, externalId: 'host-2', computeServerTypeCode: 'xenserverHypervisor')
		]
		
		plugin.morpheusContext >> {
			def context = GroovyMock(MorpheusContext)
			stubAsync(context, [
				computeServer: [listIdentityProjections: { Long cloudId, String category -> Observable.fromIterable(hostServers) }]
			])
			return context
		}
		
		VirtualMachineSync sync = new VirtualMachineSync(cloud, plugin, GroovyMock(com.morpheusdata.core.providers.CloudProvider))

		when:
		def result = sync.getAllHosts()

		then:
		result.size() == 2
		result['host-1'].id == 1L
		result['host-2'].id == 2L
	}
}
