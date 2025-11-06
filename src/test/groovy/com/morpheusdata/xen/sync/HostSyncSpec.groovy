package com.morpheusdata.xen.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.xen.XenserverPlugin
import com.morpheusdata.xen.support.TestSpecBase
import com.morpheusdata.xen.util.XenComputeUtility
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

class HostSyncSpec extends TestSpecBase {

	XenserverPlugin plugin
	List createdHosts
	List updatedHosts
	List removedHosts

	def setup() {
		createdHosts = []
		updatedHosts = []
		removedHosts = []
	}

	def "execute should sync hosts successfully"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin) {
			getAuthConfig(cloud) >> [endpoint: 'test']
		}

		def hostList = [
			[uuid: 'host-1', host: [nameLabel: 'Host One', address: '10.0.0.1', hostname: 'host-one'], metrics: [memoryTotal: 1024L]],
			[uuid: 'host-2', host: [nameLabel: 'Host Two', address: '10.0.0.2', hostname: 'host-two'], metrics: [memoryTotal: 2048L]]
		]

		def asyncComputeServer = [
			listIdentityProjections: { DataQuery query -> Observable.empty() },
			create: { host -> createdHosts << host; Single.just(host) },
			remove: { List removeList -> Single.just(true) },
			bulkSave: { List items -> Single.just(items) },
			listById: { List ids -> Observable.empty() }
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [computeServer: asyncComputeServer])
		plugin.morpheusContext >> context

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listHosts(_) >> [success: true, hostList: hostList]

		when:
		new HostSync(cloud, plugin).execute()

		then:
		createdHosts.size() == 2
		createdHosts[0].name == 'Host One'
		createdHosts[0].externalId == 'host-1'
		createdHosts[1].name == 'Host Two'
		createdHosts[1].externalId == 'host-2'
	}

	def "execute should handle API failure gracefully"() {
		given:
		Cloud cloud = newCloud()
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [computeServer: [:]])
		plugin = GroovyMock(XenserverPlugin) {
			getAuthConfig(cloud) >> [endpoint: 'test']
			morpheusContext >> context
		}

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listHosts(_) >> [success: false, error: 'Connection failed']

		when:
		new HostSync(cloud, plugin).execute()

		then:
		notThrown(Exception)
	}

	def "execute should handle exception gracefully"() {
		given:
		Cloud cloud = newCloud()
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [computeServer: [:]])
		plugin = GroovyMock(XenserverPlugin) {
			getAuthConfig(cloud) >> [endpoint: 'test']
			morpheusContext >> context
		}

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listHosts(_) >> { throw new RuntimeException("Network error") }

		when:
		new HostSync(cloud, plugin).execute()

		then:
		notThrown(Exception)
	}

	def "addMissingHosts should create new compute servers"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		
		def hostList = [
			[uuid: 'host-1', host: [nameLabel: 'Host One', address: '10.0.0.1', hostname: 'host-one'], metrics: [memoryTotal: 1024L]]
		]

		def asyncComputeServer = [
			create: { host -> createdHosts << host; Single.just(host) }
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [computeServer: asyncComputeServer])
		plugin.morpheusContext >> context

		def hostSync = new HostSync(cloud, plugin)

		when:
		hostSync.addMissingHosts(hostList)

		then:
		createdHosts.size() == 1
		createdHosts[0].name == 'Host One'
		createdHosts[0].externalId == 'host-1'
	}

	def "addMissingHosts should handle hosts without address"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		
		def hostList = [
			[uuid: 'host-1', host: [nameLabel: 'Host One', hostname: 'host-one'], metrics: [memoryTotal: 1024L]]
		]

		def asyncComputeServer = [
			create: { host -> createdHosts << host; Single.just(host) }
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [computeServer: asyncComputeServer])
		plugin.morpheusContext >> context

		def hostSync = new HostSync(cloud, plugin)

		when:
		hostSync.addMissingHosts(hostList)

		then:
		createdHosts.size() == 1
		def host = createdHosts[0]
		host.name == 'Host One'
		host.internalIp == null
		host.externalIp == null
		host.sshHost == null
	}

	def "addMissingHosts should handle empty list"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [computeServer: [:]])
		plugin.morpheusContext >> context
		def hostSync = new HostSync(cloud, plugin)

		when:
		hostSync.addMissingHosts([])

		then:
		notThrown(Exception)
		createdHosts.size() == 0
	}

	def "updateMatchedHosts should handle empty list"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [computeServer: [:]])
		plugin.morpheusContext >> context
		def hostSync = new HostSync(cloud, plugin)

		when:
		hostSync.updateMatchedHosts([])

		then:
		notThrown(Exception)
	}

	def "removeMissingHosts should remove hosts"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		
		def removeList = [Mock(ComputeServerIdentityProjection)]

		def asyncComputeServer = [
			remove: { list -> removedHosts.addAll(list); Single.just(list) }
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [computeServer: asyncComputeServer])
		plugin.morpheusContext >> context

		def hostSync = new HostSync(cloud, plugin)

		when:
		hostSync.removeMissingHosts(removeList)

		then:
		removedHosts == removeList
	}

	def "addMissingHosts should handle hosts with all properties set"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		
		def hostList = [
			[uuid: 'host-1', host: [nameLabel: 'Host One', address: '10.0.0.1', hostname: 'host-one'], metrics: [memoryTotal: 8192L]]
		]

		def asyncComputeServer = [
			create: { host -> 
				createdHosts << host
				// Verify host properties
				assert host.name == 'Host One'
				assert host.externalId == 'host-1'
				assert host.category == "xen.host.${cloud.id}"
				assert host.hostname == 'host-one'
				assert host.internalIp == '10.0.0.1'
				assert host.externalIp == '10.0.0.1'
				assert host.sshHost == '10.0.0.1'
				assert host.maxMemory == 8192L
				assert host.maxStorage == 0L
				assert host.status == 'provisioned'
				assert host.serverType == 'hypervisor'
				assert host.sshUsername == 'root'
				assert host.provision == false
				assert host.singleTenant == false
				assert host.osType == 'linux'
				assert host.computeServerType.code == 'xenserverHypervisor'
				assert host.serverOs.code == 'linux.64'
				assert host.capacityInfo.maxMemory == 8192L
				assert host.capacityInfo.maxStorage == 0L
				Single.just(host) 
			}
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [computeServer: asyncComputeServer])
		plugin.morpheusContext >> context

		def hostSync = new HostSync(cloud, plugin)

		when:
		hostSync.addMissingHosts(hostList)

		then:
		createdHosts.size() == 1
	}

	def "addMissingHosts should handle hosts with missing metrics gracefully"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		
		def hostList = [
			[uuid: 'host-1', host: [nameLabel: 'Host One', address: '10.0.0.1', hostname: 'host-one']]
		]

		def asyncComputeServer = [
			create: { host -> 
				createdHosts << host
				assert host.maxMemory == 0L
				assert host.capacityInfo.maxMemory == 0L
				Single.just(host) 
			}
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [computeServer: asyncComputeServer])
		plugin.morpheusContext >> context

		def hostSync = new HostSync(cloud, plugin)

		when:
		hostSync.addMissingHosts(hostList)

		then:
		createdHosts.size() == 1
	}

	def "execute should handle sync with updates and removals"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin) {
			getAuthConfig(cloud) >> [endpoint: 'test']
		}

		def hostList = [
			[uuid: 'host-1', host: [nameLabel: 'Host One', address: '10.0.0.1', hostname: 'host-one'], metrics: [memoryTotal: 1024L]]
		]
		
		ComputeServerIdentityProjection existingProjection = Stub(ComputeServerIdentityProjection) {
			getId() >> 1L
			getExternalId() >> 'host-1'
		}
		ComputeServerIdentityProjection staleProjection = Stub(ComputeServerIdentityProjection) {
			getId() >> 2L
			getExternalId() >> 'host-old'
		}

		def asyncComputeServer = [
			listIdentityProjections: { DataQuery query -> Observable.fromIterable([existingProjection, staleProjection]) },
			listById: { List ids -> Observable.fromIterable(ids.collect { new ComputeServer(id: it) }) },
			create: { host -> createdHosts << host; Single.just(host) },
			bulkSave: { List items -> updatedHosts.addAll(items); Single.just(items) },
			remove: { List removeList -> removedHosts.addAll(removeList); Single.just(removeList) }
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [computeServer: asyncComputeServer])
		plugin.morpheusContext >> context

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listHosts(_) >> [success: true, hostList: hostList]

		when:
		new HostSync(cloud, plugin).execute()

		then:
		removedHosts == [staleProjection]
	}

	def "constructor should initialize properly"() {
		given:
		def testCloud = newCloud()
		def testPlugin = GroovyMock(XenserverPlugin)
		def testContext = GroovyMock(MorpheusContext)
		testPlugin.morpheusContext >> testContext

		when:
		def sync = new HostSync(testCloud, testPlugin)

		then:
		sync.cloud == testCloud
		sync.plugin == testPlugin
		sync.morpheusContext == testContext
	}
}
