package com.morpheusdata.xen.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ReferenceData
import com.morpheusdata.model.projection.ReferenceDataSyncProjection
import com.morpheusdata.xen.XenserverPlugin
import com.morpheusdata.xen.support.TestSpecBase
import com.morpheusdata.xen.util.XenComputeUtility
import com.xensource.xenapi.Host
import io.reactivex.rxjava3.core.Observable

class PoolSyncSpec extends TestSpecBase {

	XenserverPlugin plugin
	List createdPools
	List savedClouds

	def setup() {
		createdPools = []
		savedClouds = []
	}

	def "execute saves pools and sets master address"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)

		def referenceDataAsync = [
			listIdentityProjections: { DataQuery query -> Observable.fromIterable([new ReferenceDataSyncProjection(id: 1L, externalId: 'pool-1')]) },
			listById: { List ids -> Observable.fromIterable(ids.collect { Long id -> new ReferenceData(id: id, externalId: 'pool-1') }) }
		]

		def services = [
			referenceData: [
				bulkCreate: { List items ->
					createdPools.addAll(items)
					items
				},
				bulkRemove: { List items -> items }
			],
			cloud: [save: { Cloud c ->
				savedClouds << c
				c
			}]
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context, [
			referenceData: services.referenceData,
			cloud        : services.cloud
		])
		stubAsync(context, [referenceData: referenceDataAsync])
		plugin.morpheusContext >> context

		Host.Record hostRecord = new Host.Record()
		hostRecord.address = '10.0.0.5'

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listPools(_) >> [success: true, poolList: [
			[uuid: 'pool-1', pool: [nameLabel: 'PoolA', nameDescription: 'Primary pool'], master: hostRecord],
			[uuid: 'pool-2', pool: [nameLabel: 'PoolB', nameDescription: 'Secondary pool'], master: hostRecord]
		]]

		when:
		new PoolSync(cloud, plugin).execute()

		then:
		createdPools.size() == 1
		createdPools.first().value == 'PoolB'
		savedClouds.last().getConfigProperty('masterAddress') == '10.0.0.5'
	}

	def "execute handles failure"() {
		given:
		Cloud cloud = newCloud()
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [referenceData: [:]])
		plugin = GroovyMock(XenserverPlugin) {
			morpheusContext >> context
		}

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listPools(_) >> [success: false]

		when:
		new PoolSync(cloud, plugin).execute()

		then:
		notThrown(Exception)
	}

	def "execute removes stale pools"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin) {
			getAuthConfig(cloud) >> [:]
		}
		ReferenceDataSyncProjection activeProjection = new ReferenceDataSyncProjection(id: 1L, externalId: 'pool-active')
		ReferenceDataSyncProjection staleProjection = new ReferenceDataSyncProjection(id: 2L, externalId: 'pool-stale')
		List removed = []
		def referenceDataAsync = [
			listIdentityProjections: { DataQuery query -> Observable.fromIterable([activeProjection, staleProjection]) },
			listById: { List ids -> Observable.fromIterable(ids.collect { new ReferenceData(id: it, externalId: it == 1L ? 'pool-active' : 'pool-stale') }) }
		]
		def services = [
			referenceData: [
				bulkCreate: { List items -> items },
				bulkRemove: { List items ->
					removed.addAll(items)
					items
				}
			],
			cloud: [save: { Cloud c -> c }]
		]
		def context = GroovyMock(MorpheusContext)
		stubServices(context, services)
		stubAsync(context, [referenceData: referenceDataAsync])
		plugin.morpheusContext >> context

		Host.Record hostRecord = new Host.Record()
		hostRecord.address = '10.0.0.6'

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listPools(_) >> [success: true, poolList: [[uuid: 'pool-active', pool: [nameLabel: 'Pool Active', nameDescription: 'Active'], master: hostRecord]]]

		when:
		new PoolSync(cloud, plugin).execute()

		then:
		removed == [staleProjection]
	}

	def "setCloudMaster skips save when address missing"() {
		given:
		Cloud cloud = newCloud()
		List saved = []
		def services = [
			cloud: [save: { Cloud c ->
				saved << c
				c
			}]
		]
		def context = GroovyMock(MorpheusContext)
		stubServices(context, services)
		stubAsync(context, [referenceData: [:]])
		plugin = GroovyMock(XenserverPlugin) {
			morpheusContext >> context
			getAuthConfig(cloud) >> [:]
		}
		PoolSync sync = new PoolSync(cloud, plugin)
		ReferenceData referenceData = new ReferenceData([id: 1L])
		Map poolData = [master: new Host.Record(address: null)]

		when:
		sync.setCloudMaster([new SyncTask.UpdateItem<ReferenceData, Map>(existingItem: referenceData, masterItem: poolData)])

		then:
		saved.isEmpty()
	}

	// ==================== COMPREHENSIVE EXPANSION FOR 80%+ COVERAGE ====================

	def "addMissingPools creates reference data with proper configuration"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		List<ReferenceData> createdPools = []
		
		def services = [
			referenceData: [
				bulkCreate: { List items ->
					createdPools.addAll(items)
					items
				}
			]
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context, services)
		plugin.morpheusContext >> context

		def pool1 = [
			uuid: 'pool-uuid-1',
			pool: [
				nameLabel: 'Production Pool',
				nameDescription: 'Main production pool'
			]
		]

		def pool2 = [
			uuid: 'pool-uuid-2', 
			pool: [
				nameLabel: 'Development Pool',
				nameDescription: 'Dev environment pool'
			]
		]

		PoolSync sync = new PoolSync(cloud, plugin)

		when:
		sync.addMissingPools([pool1, pool2])

		then:
		createdPools.size() == 2
		createdPools[0].name == 'Production Pool'
		createdPools[0].value == 'Production Pool'
		createdPools[0].category == "xenserver.pool.${cloud.id}"
		createdPools[0].code == "xenserver.pool.${cloud.id}.pool-uuid-1"
		createdPools[0].keyValue == 'pool-uuid-1'
		createdPools[0].externalId == 'pool-uuid-1'
		createdPools[0].refType == 'ComputeZone'
		createdPools[0].refId == "${cloud.id}"
		createdPools[0].description == 'Main production pool'
		createdPools[0].type == 'string'
		createdPools[1].name == 'Development Pool'
	}

	def "addMissingPools handles pool with missing nameLabel"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		List<ReferenceData> createdPools = []
		
		def services = [
			referenceData: [
				bulkCreate: { List items ->
					createdPools.addAll(items)
					items
				}
			]
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context, services)
		plugin.morpheusContext >> context

		def poolData = [
			uuid: 'pool-no-name',
			pool: [
				nameLabel: null,  // Missing name label
				nameDescription: 'Pool without name'
			]
		]

		PoolSync sync = new PoolSync(cloud, plugin)

		when:
		sync.addMissingPools([poolData])

		then:
		createdPools.size() == 1
		createdPools[0].name == 'default'  // Falls back to default
		createdPools[0].value == 'default'
	}

	def "addMissingPools handles empty pool list"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		plugin.morpheusContext >> context

		PoolSync sync = new PoolSync(cloud, plugin)

		when:
		sync.addMissingPools([])

		then:
		notThrown(Exception)
	}

	def "addMissingPools handles exception gracefully"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		
		def services = [
			referenceData: [
				bulkCreate: { List items ->
					throw new RuntimeException("Database error during bulk create")
				}
			]
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context, services)
		plugin.morpheusContext >> context

		def poolData = [
			uuid: 'error-pool',
			pool: [nameLabel: 'Error Pool']
		]

		PoolSync sync = new PoolSync(cloud, plugin)

		when:
		sync.addMissingPools([poolData])

		then:
		notThrown(Exception)
	}

	def "setCloudMaster successfully sets master address"() {
		given:
		Cloud cloud = newCloud()
		List<Cloud> savedClouds = []
		
		def services = [
			cloud: [
				save: { Cloud c ->
					savedClouds << c
					c
				}
			]
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context, services)
		plugin = GroovyMock(XenserverPlugin) {
			morpheusContext >> context
		}

		PoolSync sync = new PoolSync(cloud, plugin)

		ReferenceData poolRef = new ReferenceData([id: 100L])
		Host.Record hostRecord = new Host.Record()
		hostRecord.address = '192.168.1.100'
		
		Map poolData = [master: hostRecord]

		SyncTask.UpdateItem updateItem = new SyncTask.UpdateItem<ReferenceData, Map>(
			existingItem: poolRef,
			masterItem: poolData
		)

		when:
		sync.setCloudMaster([updateItem])

		then:
		savedClouds.size() == 1
		savedClouds[0].getConfigProperty('masterAddress') == '192.168.1.100'
	}

	def "setCloudMaster handles multiple pools with same master"() {
		given:
		Cloud cloud = newCloud()
		List<Cloud> savedClouds = []
		
		def services = [
			cloud: [
				save: { Cloud c ->
					savedClouds << c
					c
				}
			]
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context, services)
		plugin = GroovyMock(XenserverPlugin) {
			morpheusContext >> context
		}

		PoolSync sync = new PoolSync(cloud, plugin)

		Host.Record masterHost = new Host.Record()
		masterHost.address = '10.0.0.50'

		ReferenceData pool1 = new ReferenceData([id: 200L])
		ReferenceData pool2 = new ReferenceData([id: 201L])

		Map poolData1 = [master: masterHost]
		Map poolData2 = [master: masterHost]

		SyncTask.UpdateItem updateItem1 = new SyncTask.UpdateItem<ReferenceData, Map>(
			existingItem: pool1,
			masterItem: poolData1
		)
		
		SyncTask.UpdateItem updateItem2 = new SyncTask.UpdateItem<ReferenceData, Map>(
			existingItem: pool2,
			masterItem: poolData2
		)

		when:
		sync.setCloudMaster([updateItem1, updateItem2])

		then:
		savedClouds.size() == 2
		savedClouds[0].getConfigProperty('masterAddress') == '10.0.0.50'
		savedClouds[1].getConfigProperty('masterAddress') == '10.0.0.50'
	}

	def "setCloudMaster handles null master host gracefully"() {
		given:
		Cloud cloud = newCloud()
		List<Cloud> savedClouds = []
		
		def services = [
			cloud: [
				save: { Cloud c ->
					savedClouds << c
					c
				}
			]
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context, services)
		plugin = GroovyMock(XenserverPlugin) {
			morpheusContext >> context
		}

		PoolSync sync = new PoolSync(cloud, plugin)

		ReferenceData poolRef = new ReferenceData([id: 300L])
		Map poolData = [master: null]  // Null master

		SyncTask.UpdateItem updateItem = new SyncTask.UpdateItem<ReferenceData, Map>(
			existingItem: poolRef,
			masterItem: poolData
		)

		when:
		sync.setCloudMaster([updateItem])

		then:
		savedClouds.isEmpty()
		notThrown(Exception)
	}

	def "setCloudMaster handles empty update list"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		plugin.morpheusContext >> context

		PoolSync sync = new PoolSync(cloud, plugin)

		when:
		sync.setCloudMaster([])

		then:
		notThrown(Exception)
	}

	def "setCloudMaster handles exception gracefully"() {
		given:
		Cloud cloud = newCloud()
		
		def services = [
			cloud: [
				save: { Cloud c ->
					throw new RuntimeException("Cloud save error")
				}
			]
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context, services)
		plugin = GroovyMock(XenserverPlugin) {
			morpheusContext >> context
		}

		PoolSync sync = new PoolSync(cloud, plugin)

		ReferenceData poolRef = new ReferenceData([id: 400L])
		Host.Record hostRecord = new Host.Record()
		hostRecord.address = '172.16.0.1'
		
		Map poolData = [master: hostRecord]

		SyncTask.UpdateItem updateItem = new SyncTask.UpdateItem<ReferenceData, Map>(
			existingItem: poolRef,
			masterItem: poolData
		)

		when:
		sync.setCloudMaster([updateItem])

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
		XenComputeUtility.listPools(_) >> { throw new RuntimeException("XenAPI connection failed") }

		when:
		new PoolSync(cloud, plugin).execute()

		then:
		notThrown(Exception)
	}

	def "execute processes pools with comprehensive workflow"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin) {
			getAuthConfig(cloud) >> [hostname: 'xenserver.test', username: 'admin', password: 'secret']
		}

		List<ReferenceData> createdPools = []
		List<Cloud> savedClouds = []

		def referenceDataAsync = [
			listIdentityProjections: { DataQuery query -> Observable.empty() },
			listById: { List ids -> Observable.empty() }
		]

		def services = [
			referenceData: [
				bulkCreate: { List items ->
					createdPools.addAll(items)
					items
				},
				bulkRemove: { List items -> items }
			],
			cloud: [
				save: { Cloud c ->
					savedClouds << c
					c
				}
			]
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context, services)
		stubAsync(context, [referenceData: referenceDataAsync])
		plugin.morpheusContext >> context

		Host.Record masterHost = new Host.Record()
		masterHost.address = '10.20.30.40'

		def poolData = [
			uuid: 'workflow-pool',
			pool: [
				nameLabel: 'Workflow Pool',
				nameDescription: 'Complete workflow test pool'
			],
			master: masterHost
		]

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listPools(_) >> [
			success: true, 
			poolList: [poolData]
		]

		when:
		new PoolSync(cloud, plugin).execute()

		then:
		createdPools.size() == 1
		createdPools[0].name == 'Workflow Pool'
		createdPools[0].externalId == 'workflow-pool'
		savedClouds.size() == 0  // No update items since all pools are new
	}
}
