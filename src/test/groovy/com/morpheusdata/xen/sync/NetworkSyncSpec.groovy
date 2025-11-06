package com.morpheusdata.xen.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.projection.NetworkIdentityProjection
import com.morpheusdata.xen.XenserverPlugin
import com.morpheusdata.xen.support.TestSpecBase
import com.morpheusdata.xen.util.XenComputeUtility
import com.xensource.xenapi.Network as XenNetwork
import io.reactivex.rxjava3.core.Observable

class NetworkSyncSpec extends TestSpecBase {

	XenserverPlugin plugin
	List createdNetworks

	def setup() {
		createdNetworks = []
	}

	def "execute creates missing networks"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin) {
			getAuthConfig(cloud) >> [:]
		}

		def networkAsync = [
			listIdentityProjections: { Long id -> Observable.empty() },
			create: { List adds ->
				createdNetworks.addAll(adds)
				Observable.just(adds)
			},
			save: { List items -> Observable.just(items) },
			remove: { List items -> Observable.just(items) },
			listById: { List ids -> Observable.fromIterable([]) }
		]

		def servicesNetwork = [find: { DataQuery query -> null }]

		def context = GroovyMock(MorpheusContext)
		stubServices(context, [network: servicesNetwork])
		stubAsync(context, [cloud: [network: networkAsync]])
		plugin.morpheusContext >> context

		XenNetwork.Record record = new XenNetwork.Record()
		record.uuid = 'net-1'
		record.nameLabel = 'Management'
		record.nameDescription = 'Mgmt network'

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listNetworks(_) >> [success: true, networkList: [record]]

		when:
		new NetworkSync(cloud, plugin).execute()

		then:
		createdNetworks.size() == 1
		createdNetworks.first() instanceof Network
		createdNetworks.first().name == 'Management'
		createdNetworks.first().owner instanceof Account
	}

	def "execute handles api failure"() {
		given:
		Cloud cloud = newCloud()
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [cloud: [network: [:]]])
		plugin = GroovyMock(XenserverPlugin) {
			getAuthConfig(cloud) >> [:]
			morpheusContext >> context
		}

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listNetworks(_) >> [success: false]

		when:
		new NetworkSync(cloud, plugin).execute()

		then:
		notThrown(Exception)
	}

	def "execute updates existing networks missing metadata"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin) {
			getAuthConfig(cloud) >> [:]
		}
		NetworkIdentityProjection existingProjection = Stub(NetworkIdentityProjection) {
			getId() >> 10L
			getExternalId() >> 'net-1'
		}
		List savedNetworks = []
		def networkAsync = [
			listIdentityProjections: { Long id -> Observable.fromIterable([existingProjection]) },
			create               : { List adds -> Observable.just(adds) },
			save                 : { List items ->
				savedNetworks.addAll(items)
				Observable.just(items)
			},
			remove               : { List items -> Observable.just(items) },
			listById             : { List ids ->
				Observable.fromIterable(ids.collect {
					new Network(id: it, name: 'Legacy', description: 'Old desc', externalId: 'net-1')
				})
			}
		]
		def context = GroovyMock(MorpheusContext)
		stubServices(context, [network: [find: { DataQuery query -> null }]])
		stubAsync(context, [cloud: [network: networkAsync]])
		plugin.morpheusContext >> context

		XenNetwork.Record record = new XenNetwork.Record()
		record.uuid = 'net-1'
		record.nameLabel = 'Updated Network'
		record.nameDescription = 'Updated description'

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listNetworks(_) >> [success: true, networkList: [record]]

		when:
		new NetworkSync(cloud, plugin).execute()

		then:
		savedNetworks.size() == 1
		savedNetworks.first().name == 'Updated Network'
		savedNetworks.first().description == 'Updated description'
		savedNetworks.first().type instanceof NetworkType
		savedNetworks.first().dhcpServer
	}

	def "execute removes stale networks"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin) {
			getAuthConfig(cloud) >> [:]
		}
		NetworkIdentityProjection activeProjection = Stub(NetworkIdentityProjection) {
			getId() >> 21L
			getExternalId() >> 'net-active'
		}
		NetworkIdentityProjection staleProjection = Stub(NetworkIdentityProjection) {
			getId() >> 22L
			getExternalId() >> 'net-stale'
		}
		List removed = []
		def networkAsync = [
			listIdentityProjections: { Long id -> Observable.fromIterable([activeProjection, staleProjection]) },
			create               : { List adds -> Observable.just(adds) },
			save                 : { List items -> Observable.just(items) },
			remove               : { List items ->
				removed.addAll(items)
				Observable.just(items)
			},
			listById             : { List ids -> Observable.fromIterable(ids.collect { new Network(id: it, externalId: 'net-active') }) }
		]
		def context = GroovyMock(MorpheusContext)
		stubServices(context, [network: [find: { DataQuery query -> null }]])
		stubAsync(context, [cloud: [network: networkAsync]])
		plugin.morpheusContext >> context

		XenNetwork.Record record = new XenNetwork.Record()
		record.uuid = 'net-active'
		record.nameLabel = 'Active Network'
		record.nameDescription = 'Still present'

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listNetworks(_) >> [success: true, networkList: [record]]

		when:
		new NetworkSync(cloud, plugin).execute()

		then:
		removed == [staleProjection]
	}

	// ==================== COMPREHENSIVE EXPANSION FOR 80%+ COVERAGE ====================

	def "addMissingNetworks creates networks with proper configuration"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		List<Network> createdNetworks = []
		
		def networkAsync = [
			create: { List adds ->
				createdNetworks.addAll(adds)
				Observable.just(adds)
			}
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [cloud: [network: networkAsync]])
		plugin.morpheusContext >> context

		NetworkType networkType = new NetworkType(code: 'xenNetwork')

		XenNetwork.Record net1 = new XenNetwork.Record()
		net1.uuid = 'net-uuid-1'
		net1.nameLabel = 'Public Network'
		net1.nameDescription = 'External network access'

		XenNetwork.Record net2 = new XenNetwork.Record()
		net2.uuid = 'net-uuid-2'
		net2.nameLabel = 'Private Network'
		net2.nameDescription = 'Internal network'

		NetworkSync sync = new NetworkSync(cloud, plugin)

		when:
		sync.addMissingNetworks([net1, net2], networkType)

		then:
		createdNetworks.size() == 2
		createdNetworks[0].name == 'Public Network'
		createdNetworks[0].externalId == 'net-uuid-1'
		createdNetworks[0].category == "xenserver.network.${cloud.id}"
		createdNetworks[0].code == "xenserver.network.${cloud.id}.net-uuid-1"
		createdNetworks[0].uniqueId == 'net-uuid-1'
		createdNetworks[0].refType == 'ComputeZone'
		createdNetworks[0].refId == cloud.id
		createdNetworks[0].type == networkType
		createdNetworks[0].description == 'External network access'
		createdNetworks[0].dhcpServer == true
		createdNetworks[1].name == 'Private Network'
	}

	def "addMissingNetworks handles networks with missing nameLabel"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		List<Network> createdNetworks = []
		
		def networkAsync = [
			create: { List adds ->
				createdNetworks.addAll(adds)
				Observable.just(adds)
			}
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [cloud: [network: networkAsync]])
		plugin.morpheusContext >> context

		NetworkType networkType = new NetworkType(code: 'xenNetwork')

		XenNetwork.Record netRecord = new XenNetwork.Record()
		netRecord.uuid = 'net-no-label'
		netRecord.nameLabel = null  // Missing label
		netRecord.nameDescription = 'Network without label'

		NetworkSync sync = new NetworkSync(cloud, plugin)

		when:
		sync.addMissingNetworks([netRecord], networkType)

		then:
		createdNetworks.size() == 1
		createdNetworks[0].name == 'net-no-label'  // Falls back to UUID
		createdNetworks[0].externalId == 'net-no-label'
	}

	def "addMissingNetworks handles empty network list"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		plugin.morpheusContext >> context

		NetworkType networkType = new NetworkType(code: 'xenNetwork')
		NetworkSync sync = new NetworkSync(cloud, plugin)

		when:
		sync.addMissingNetworks([], networkType)

		then:
		notThrown(Exception)
	}

	def "addMissingNetworks handles exception gracefully"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		
		def networkAsync = [
			create: { List adds ->
				throw new RuntimeException("Database error during network creation")
			}
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [cloud: [network: networkAsync]])
		plugin.morpheusContext >> context

		NetworkType networkType = new NetworkType(code: 'xenNetwork')

		XenNetwork.Record netRecord = new XenNetwork.Record()
		netRecord.uuid = 'error-net'
		netRecord.nameLabel = 'Error Network'

		NetworkSync sync = new NetworkSync(cloud, plugin)

		when:
		sync.addMissingNetworks([netRecord], networkType)

		then:
		notThrown(Exception)
	}

	def "updateMatchedNetworks updates all network properties correctly"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		List<Network> savedNetworks = []
		
		def networkAsync = [
			save: { List items ->
				savedNetworks.addAll(items)
				Observable.just(items)
			}
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [cloud: [network: networkAsync]])
		plugin.morpheusContext >> context

		NetworkType networkType = new NetworkType(code: 'xenNetwork')

		Network existingNet = new Network([
			id: 100L,
			name: 'Old Network Name',
			description: 'Old description',
			type: null,  // Missing type
			dhcpServer: false
		])

		XenNetwork.Record cloudRecord = new XenNetwork.Record()
		cloudRecord.uuid = 'net-update'
		cloudRecord.nameLabel = 'Updated Network Name'
		cloudRecord.nameDescription = 'Updated network description'

		def updateItem = [
			existingItem: existingNet,
			masterItem: cloudRecord
		]

		NetworkSync sync = new NetworkSync(cloud, plugin)

		when:
		sync.updateMatchedNetworks([updateItem], networkType)

		then:
		savedNetworks.size() == 1
		savedNetworks[0].name == 'Updated Network Name'
		savedNetworks[0].description == 'Updated network description'
		savedNetworks[0].type == networkType
		savedNetworks[0].dhcpServer == true
	}

	def "updateMatchedNetworks handles networks with existing type"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		List<Network> savedNetworks = []
		
		def networkAsync = [
			save: { List items ->
				savedNetworks.addAll(items)
				Observable.just(items)
			}
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [cloud: [network: networkAsync]])
		plugin.morpheusContext >> context

		NetworkType existingType = new NetworkType(code: 'existingType')
		NetworkType newType = new NetworkType(code: 'xenNetwork')

		Network existingNet = new Network([
			id: 200L,
			name: 'Current Name',
			description: 'Current desc',
			type: existingType,  // Already has type
			dhcpServer: true
		])

		XenNetwork.Record cloudRecord = new XenNetwork.Record()
		cloudRecord.uuid = 'net-existing-type'
		cloudRecord.nameLabel = 'Updated Name Only'
		cloudRecord.nameDescription = 'Updated desc only'

		def updateItem = [
			existingItem: existingNet,
			masterItem: cloudRecord
		]

		NetworkSync sync = new NetworkSync(cloud, plugin)

		when:
		sync.updateMatchedNetworks([updateItem], newType)

		then:
		savedNetworks.size() == 1
		savedNetworks[0].name == 'Updated Name Only'
		savedNetworks[0].description == 'Updated desc only'
		savedNetworks[0].type == existingType  // Type should remain unchanged
		savedNetworks[0].dhcpServer == true   // Should remain unchanged
	}

	def "updateMatchedNetworks skips when no changes needed"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		List<Network> savedNetworks = []
		
		def networkAsync = [
			save: { List items ->
				savedNetworks.addAll(items)
				Observable.just(items)
			}
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [cloud: [network: networkAsync]])
		plugin.morpheusContext >> context

		NetworkType networkType = new NetworkType(code: 'xenNetwork')

		Network unchangedNet = new Network([
			id: 300L,
			name: 'Same Name',
			description: 'Same description',
			type: networkType,
			dhcpServer: true
		])

		XenNetwork.Record cloudRecord = new XenNetwork.Record()
		cloudRecord.uuid = 'net-unchanged'
		cloudRecord.nameLabel = 'Same Name'        // Same name
		cloudRecord.nameDescription = 'Same description'  // Same desc

		def updateItem = [
			existingItem: unchangedNet,
			masterItem: cloudRecord
		]

		NetworkSync sync = new NetworkSync(cloud, plugin)

		when:
		sync.updateMatchedNetworks([updateItem], networkType)

		then:
		savedNetworks.size() == 0  // No updates needed
	}

	def "updateMatchedNetworks handles empty update list"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		plugin.morpheusContext >> context

		NetworkType networkType = new NetworkType(code: 'xenNetwork')
		NetworkSync sync = new NetworkSync(cloud, plugin)

		when:
		sync.updateMatchedNetworks([], networkType)

		then:
		notThrown(Exception)
	}

	def "updateMatchedNetworks handles null existing item gracefully"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		List<Network> savedNetworks = []
		
		def networkAsync = [
			save: { List items ->
				savedNetworks.addAll(items)
				Observable.just(items)
			}
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [cloud: [network: networkAsync]])
		plugin.morpheusContext >> context

		NetworkType networkType = new NetworkType(code: 'xenNetwork')

		XenNetwork.Record cloudRecord = new XenNetwork.Record()
		cloudRecord.uuid = 'net-null-existing'
		cloudRecord.nameLabel = 'Network Name'

		def updateItem = [
			existingItem: null,  // Null existing item
			masterItem: cloudRecord
		]

		NetworkSync sync = new NetworkSync(cloud, plugin)

		when:
		sync.updateMatchedNetworks([updateItem], networkType)

		then:
		savedNetworks.size() == 0
		notThrown(Exception)
	}

	def "updateMatchedNetworks handles exception gracefully"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		
		def networkAsync = [
			save: { List items ->
				throw new RuntimeException("Database save error")
			}
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [cloud: [network: networkAsync]])
		plugin.morpheusContext >> context

		NetworkType networkType = new NetworkType(code: 'xenNetwork')

		Network existingNet = new Network([
			id: 400L,
			name: 'Error Network',
			type: null
		])

		XenNetwork.Record cloudRecord = new XenNetwork.Record()
		cloudRecord.uuid = 'net-error'
		cloudRecord.nameLabel = 'Updated Error Network'

		def updateItem = [
			existingItem: existingNet,
			masterItem: cloudRecord
		]

		NetworkSync sync = new NetworkSync(cloud, plugin)

		when:
		sync.updateMatchedNetworks([updateItem], networkType)

		then:
		notThrown(Exception)
	}

	def "execute handles XenComputeUtility exception gracefully"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin) {
			getAuthConfig(cloud) >> [:]
		}
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		plugin.morpheusContext >> context

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listNetworks(_) >> { throw new RuntimeException("XenAPI connection failed") }

		when:
		new NetworkSync(cloud, plugin).execute()

		then:
		notThrown(Exception)
	}

	def "execute handles comprehensive network synchronization workflow"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin) {
			getAuthConfig(cloud) >> [hostname: 'xenserver.test', username: 'test', password: 'pass']
		}

		List<Network> createdNetworks = []

		def networkAsync = [
			listIdentityProjections: { Long id -> Observable.empty() },
			create: { List adds ->
				createdNetworks.addAll(adds)
				Observable.just(adds)
			},
			listById: { List ids -> Observable.empty() },
			save: { List items -> Observable.just(items) },
			remove: { List items -> Observable.just(items) }
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context, [network: [find: { DataQuery query -> null }]])
		stubAsync(context, [cloud: [network: networkAsync]])
		plugin.morpheusContext >> context

		XenNetwork.Record network = new XenNetwork.Record()
		network.uuid = 'net-workflow'
		network.nameLabel = 'Workflow Network'
		network.nameDescription = 'Complete workflow test'

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listNetworks(_) >> [
			success: true, 
			networkList: [network]
		]

		when:
		new NetworkSync(cloud, plugin).execute()

		then:
		createdNetworks.size() == 1
		createdNetworks[0].name == 'Workflow Network'
	}

	def "execute processes network with empty nameDescription"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin) {
			getAuthConfig(cloud) >> [:]
		}

		List<Network> createdNetworks = []

		def networkAsync = [
			listIdentityProjections: { Long id -> Observable.empty() },
			create: { List adds ->
				createdNetworks.addAll(adds)
				Observable.just(adds)
			},
			save: { List items -> Observable.just(items) },
			remove: { List items -> Observable.just(items) },
			listById: { List ids -> Observable.fromIterable([]) }
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context, [network: [find: { DataQuery query -> null }]])
		stubAsync(context, [cloud: [network: networkAsync]])
		plugin.morpheusContext >> context

		XenNetwork.Record record = new XenNetwork.Record()
		record.uuid = 'net-no-desc'
		record.nameLabel = 'Network Without Description'
		record.nameDescription = null  // No description

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listNetworks(_) >> [success: true, networkList: [record]]

		when:
		new NetworkSync(cloud, plugin).execute()

		then:
		createdNetworks.size() == 1
		createdNetworks[0].name == 'Network Without Description'
		createdNetworks[0].description == null
	}
}
