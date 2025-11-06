package com.morpheusdata.xen.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.projection.DatastoreIdentityProjection
import com.morpheusdata.xen.XenserverPlugin
import com.morpheusdata.xen.support.TestSpecBase
import com.morpheusdata.xen.util.XenComputeUtility
import com.xensource.xenapi.SR
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import java.util.EnumSet
import spock.lang.Ignore

class DatastoresSyncSpec extends TestSpecBase {

	XenserverPlugin plugin
	List createdDatastores

	def setup() {
		createdDatastores = []
	}

	def "execute creates missing datastores"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin) {
			getAuthConfig(cloud) >> [:]
		}

		def datastoreAsync = [
			listIdentityProjections: { DataQuery query -> Observable.empty() },
			create: { List adds ->
				createdDatastores.addAll(adds)
				Observable.just(adds)
			},
			save: { List items -> Single.just(items) },
			remove: { List items -> Single.just(items) },
			listById: { List ids -> Observable.fromIterable([]) }
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [cloud: [datastore: datastoreAsync]])
		plugin.morpheusContext >> context

		SR.Record srRecord = new SR.Record()
		srRecord.uuid = 'sr-1'
		srRecord.nameLabel = 'Primary SR'
		srRecord.type = 'lvm'
		srRecord.physicalSize = 100L
		srRecord.physicalUtilisation = 10L
		srRecord.allowedOperations = EnumSet.of(com.xensource.xenapi.Types.StorageOperations.VDI_CREATE)

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listStorageRepositories(_) >> [success: true, srList: [srRecord]]

		when:
		new DatastoresSync(cloud, plugin).execute()

		then:
		createdDatastores.size() == 1
		createdDatastores.first().name == 'Primary SR'
		createdDatastores.first().freeSpace == 90L
	}

	def "execute handles list failure"() {
		given:
		Cloud cloud = newCloud()
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [cloud: [datastore: [:]]])
		plugin = GroovyMock(XenserverPlugin) {
			getAuthConfig(cloud) >> [:]
			morpheusContext >> context
		}

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listStorageRepositories(_) >> [success: false]

		when:
		new DatastoresSync(cloud, plugin).execute()

		then:
		notThrown(Exception)
	}

	def "execute updates matched datastores and removes missing ones"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin) {
			getAuthConfig(cloud) >> [:]
		}

		DatastoreIdentityProjection existingProjection = Stub(DatastoreIdentityProjection) {
			getId() >> 1L
			getExternalId() >> 'sr-1'
		}
		DatastoreIdentityProjection staleProjection = Stub(DatastoreIdentityProjection) {
			getId() >> 2L
			getExternalId() >> 'sr-obsolete'
		}
		def datastoreToUpdate = new Datastore(id: 1L, name: 'Old SR', storageSize: 50L, freeSpace: 20L)
		List saved = []
		List removed = []
		def datastoreAsync = [
			listIdentityProjections: { DataQuery query -> Observable.fromIterable([existingProjection, staleProjection]) },
			listById: { List ids -> Observable.fromIterable(ids.collect { it == 1L ? datastoreToUpdate : new Datastore(id: it) }) },
			create: { List adds -> Observable.just(adds) },
			save: { List items -> saved += items; Single.just(items) },
			remove: { List items -> removed += items; Single.just(items) }
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [cloud: [datastore: datastoreAsync]])
		plugin.morpheusContext >> context

		SR.Record srRecord = new SR.Record()
		srRecord.uuid = 'sr-1'
		srRecord.nameLabel = 'Updated SR'
		srRecord.type = 'lvm'
		srRecord.physicalSize = 200L
		srRecord.physicalUtilisation = 40L

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listStorageRepositories(_) >> [success: true, srList: [srRecord]]

		when:
		new DatastoresSync(cloud, plugin).execute()

		then:
		saved*.name == ['Updated SR']
		saved*.storageSize == [200L]
		saved*.freeSpace == [160L]
		removed == [staleProjection]
	}

	// ===== COMPREHENSIVE TESTS FOR 80%+ COVERAGE =====

	def "execute handles exception gracefully"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin) {
			getAuthConfig(cloud) >> { throw new RuntimeException("Auth error") }
		}

		when:
		new DatastoresSync(cloud, plugin).execute()

		then:
		notThrown(Exception) // Should catch and log exceptions
	}

	def "addMissingDatastores creates datastores with correct properties"() {
		given:
		Cloud cloud = newCloud()
		cloud.owner = cloud.owner
		List createdItems = []
		
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [
			cloud: [
				datastore: [
					create: { List items -> 
						createdItems.addAll(items)
						Observable.just(items)
					}
				]
			]
		])
		
		plugin = GroovyMock(XenserverPlugin) {
			morpheusContext >> context
		}

		SR.Record srRecord1 = new SR.Record()
		srRecord1.uuid = 'sr-test-1'
		srRecord1.nameLabel = 'Test SR 1'
		srRecord1.type = 'ext'
		srRecord1.physicalSize = 1000L
		srRecord1.physicalUtilisation = 200L
		srRecord1.allowedOperations = EnumSet.of(com.xensource.xenapi.Types.StorageOperations.VDI_CREATE)

		SR.Record srRecord2 = new SR.Record()
		srRecord2.uuid = 'sr-test-2'
		srRecord2.nameLabel = 'Test SR 2'
		srRecord2.type = null // Test null type
		srRecord2.physicalSize = 2000L
		srRecord2.physicalUtilisation = 500L
		srRecord2.allowedOperations = EnumSet.noneOf(com.xensource.xenapi.Types.StorageOperations.class) // No create permission

		DatastoresSync sync = new DatastoresSync(cloud, plugin)

		when:
		sync.addMissingDatastores([srRecord1, srRecord2])

		then:
		createdItems.size() == 2
		createdItems[0].name == 'Test SR 1'
		createdItems[0].externalId == 'sr-test-1'
		createdItems[0].code == "xenserver.sr.${cloud.id}.sr-test-1"
		createdItems[0].type == 'ext'
		createdItems[0].storageSize == 1000L
		createdItems[0].freeSpace == 800L
		createdItems[0].allowWrite == true
		createdItems[1].name == 'Test SR 2'
		createdItems[1].type == 'general'  // Default type
		createdItems[1].allowWrite == false
	}

	def "addMissingDatastores handles empty list"() {
		given:
		Cloud cloud = newCloud()
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [
			cloud: [
				datastore: [
					create: { List items -> Observable.just(items) }
				]
			]
		])
		
		plugin = GroovyMock(XenserverPlugin) {
			morpheusContext >> context
		}

		DatastoresSync sync = new DatastoresSync(cloud, plugin)

		when:
		sync.addMissingDatastores([])

		then:
		notThrown(Exception)
	}

	def "addMissingDatastores handles exception"() {
		given:
		Cloud cloud = newCloud()
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [
			cloud: [
				datastore: [
					create: { List items -> throw new RuntimeException("Database error") }
				]
			]
		])
		
		plugin = GroovyMock(XenserverPlugin) {
			morpheusContext >> context
		}

		SR.Record srRecord = new SR.Record()
		srRecord.uuid = 'sr-error'
		srRecord.nameLabel = 'Error SR'

		DatastoresSync sync = new DatastoresSync(cloud, plugin)

		when:
		sync.addMissingDatastores([srRecord])

		then:
		notThrown(Exception) // Should catch and log exceptions
	}

	def "updateMatchedDatastores updates name when different"() {
		given:
		Cloud cloud = newCloud()
		List savedItems = []
		
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [
			cloud: [
				datastore: [
					save: { List items -> 
						savedItems.addAll(items)
						Single.just(items)
					}
				]
			]
		])
		
		plugin = GroovyMock(XenserverPlugin) {
			morpheusContext >> context
		}

		def existingDatastore = new Datastore(id: 1L, name: 'Old Name', storageSize: 1000L, freeSpace: 500L)
		
		SR.Record srRecord = new SR.Record()
		srRecord.nameLabel = 'Updated Name'
		srRecord.physicalSize = 1000L
		srRecord.physicalUtilisation = 500L

		def updateItem = new com.morpheusdata.core.util.SyncTask.UpdateItem<Datastore, SR.Record>(
			existingItem: existingDatastore, 
			masterItem: srRecord
		)

		DatastoresSync sync = new DatastoresSync(cloud, plugin)

		when:
		sync.updateMatchedDatastores([updateItem])

		then:
		savedItems.size() == 1
		existingDatastore.name == 'Updated Name'
	}

	def "updateMatchedDatastores updates storage size when different"() {
		given:
		Cloud cloud = newCloud()
		List savedItems = []
		
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [
			cloud: [
				datastore: [
					save: { List items -> 
						savedItems.addAll(items)
						Single.just(items)
					}
				]
			]
		])
		
		plugin = GroovyMock(XenserverPlugin) {
			morpheusContext >> context
		}

		def existingDatastore = new Datastore(id: 1L, name: 'Test DS', storageSize: 1000L, freeSpace: 500L)
		
		SR.Record srRecord = new SR.Record()
		srRecord.nameLabel = 'Test DS'
		srRecord.physicalSize = 2000L  // Different size
		srRecord.physicalUtilisation = 300L

		def updateItem = new com.morpheusdata.core.util.SyncTask.UpdateItem<Datastore, SR.Record>(
			existingItem: existingDatastore, 
			masterItem: srRecord
		)

		DatastoresSync sync = new DatastoresSync(cloud, plugin)

		when:
		sync.updateMatchedDatastores([updateItem])

		then:
		savedItems.size() == 1
		existingDatastore.storageSize == 2000L
		existingDatastore.freeSpace == 1700L
	}

	def "updateMatchedDatastores updates free space when different"() {
		given:
		Cloud cloud = newCloud()
		List savedItems = []
		
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [
			cloud: [
				datastore: [
					save: { List items -> 
						savedItems.addAll(items)
						Single.just(items)
					}
				]
			]
		])
		
		plugin = GroovyMock(XenserverPlugin) {
			morpheusContext >> context
		}

		def existingDatastore = new Datastore(id: 1L, name: 'Test DS', storageSize: 1000L, freeSpace: 500L)
		
		SR.Record srRecord = new SR.Record()
		srRecord.nameLabel = 'Test DS'
		srRecord.physicalSize = 1000L
		srRecord.physicalUtilisation = 600L  // Different utilization = different free space

		def updateItem = new com.morpheusdata.core.util.SyncTask.UpdateItem<Datastore, SR.Record>(
			existingItem: existingDatastore, 
			masterItem: srRecord
		)

		DatastoresSync sync = new DatastoresSync(cloud, plugin)

		when:
		sync.updateMatchedDatastores([updateItem])

		then:
		savedItems.size() == 1
		existingDatastore.freeSpace == 400L  // 1000 - 600
	}

	def "updateMatchedDatastores skips update when no changes"() {
		given:
		Cloud cloud = newCloud()
		List savedItems = []
		
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [
			cloud: [
				datastore: [
					save: { List items -> 
						savedItems.addAll(items)
						Single.just(items)
					}
				]
			]
		])
		
		plugin = GroovyMock(XenserverPlugin) {
			morpheusContext >> context
		}

		def existingDatastore = new Datastore(id: 1L, name: 'Test DS', storageSize: 1000L, freeSpace: 400L)
		
		SR.Record srRecord = new SR.Record()
		srRecord.nameLabel = 'Test DS'  // Same name
		srRecord.physicalSize = 1000L   // Same size
		srRecord.physicalUtilisation = 600L  // Same free space (1000-600=400)

		def updateItem = new com.morpheusdata.core.util.SyncTask.UpdateItem<Datastore, SR.Record>(
			existingItem: existingDatastore, 
			masterItem: srRecord
		)

		DatastoresSync sync = new DatastoresSync(cloud, plugin)

		when:
		sync.updateMatchedDatastores([updateItem])

		then:
		savedItems.size() == 0  // No items to save
	}

	def "updateMatchedDatastores handles empty list"() {
		given:
		Cloud cloud = newCloud()
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		
		plugin = GroovyMock(XenserverPlugin) {
			morpheusContext >> context
		}

		DatastoresSync sync = new DatastoresSync(cloud, plugin)

		when:
		sync.updateMatchedDatastores([])

		then:
		notThrown(Exception)
	}

	def "updateMatchedDatastores handles exception"() {
		given:
		Cloud cloud = newCloud()
		
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [
			cloud: [
				datastore: [
					save: { List items -> throw new RuntimeException("Save error") }
				]
			]
		])
		
		plugin = GroovyMock(XenserverPlugin) {
			morpheusContext >> context
		}

		def existingDatastore = new Datastore(id: 1L, name: 'Old Name', storageSize: 1000L, freeSpace: 500L)
		SR.Record srRecord = new SR.Record()
		srRecord.nameLabel = 'New Name'
		srRecord.physicalSize = 1000L
		srRecord.physicalUtilisation = 500L

		def updateItem = new com.morpheusdata.core.util.SyncTask.UpdateItem<Datastore, SR.Record>(
			existingItem: existingDatastore, 
			masterItem: srRecord
		)

		DatastoresSync sync = new DatastoresSync(cloud, plugin)

		when:
		sync.updateMatchedDatastores([updateItem])

		then:
		notThrown(Exception) // Should catch and log exceptions
	}

	def "removeMissingDatastores removes datastores"() {
		given:
		Cloud cloud = newCloud()
		List removedItems = []
		
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [
			cloud: [
				datastore: [
					remove: { List items -> 
						removedItems.addAll(items)
						Single.just(items)
					}
				]
			]
		])
		
		plugin = GroovyMock(XenserverPlugin) {
			morpheusContext >> context
		}

		def projection1 = Stub(DatastoreIdentityProjection) { getId() >> 1L; getExternalId() >> 'ds-1' }
		def projection2 = Stub(DatastoreIdentityProjection) { getId() >> 2L; getExternalId() >> 'ds-2' }

		DatastoresSync sync = new DatastoresSync(cloud, plugin)

		when:
		sync.removeMissingDatastores([projection1, projection2])

		then:
		removedItems.size() == 2
		removedItems.contains(projection1)
		removedItems.contains(projection2)
	}

	def "removeMissingDatastores handles empty list"() {
		given:
		Cloud cloud = newCloud()
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [
			cloud: [
				datastore: [
					remove: { List items -> Single.just(items) }
				]
			]
		])
		
		plugin = GroovyMock(XenserverPlugin) {
			morpheusContext >> context
		}

		DatastoresSync sync = new DatastoresSync(cloud, plugin)

		when:
		sync.removeMissingDatastores([])

		then:
		notThrown(Exception)
	}

	def "execute handles XenComputeUtility.listStorageRepositories null response"() {
		given:
		Cloud cloud = newCloud()
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [cloud: [datastore: [:]]])
		plugin = GroovyMock(XenserverPlugin) {
			getAuthConfig(cloud) >> [:]
			morpheusContext >> context
		}

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listStorageRepositories(_) >> null

		when:
		new DatastoresSync(cloud, plugin).execute()

		then:
		notThrown(Exception)
	}

	def "execute handles mixed SR record types"() {
		given:
		Cloud cloud = newCloud()
		List createdItems = []
		
		def datastoreAsync = [
			listIdentityProjections: { DataQuery query -> Observable.empty() },
			create: { List adds ->
				createdItems.addAll(adds)
				Observable.just(adds)
			}
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [cloud: [datastore: datastoreAsync]])
		plugin = GroovyMock(XenserverPlugin) {
			getAuthConfig(cloud) >> [:]
			morpheusContext >> context
		}

		// Create different SR record types
		SR.Record lvmSR = new SR.Record()
		lvmSR.uuid = 'lvm-sr'
		lvmSR.nameLabel = 'LVM SR'
		lvmSR.type = 'lvm'
		lvmSR.physicalSize = 5000L
		lvmSR.physicalUtilisation = 1000L
		lvmSR.allowedOperations = EnumSet.of(com.xensource.xenapi.Types.StorageOperations.VDI_CREATE)

		SR.Record nfsSR = new SR.Record()
		nfsSR.uuid = 'nfs-sr'
		nfsSR.nameLabel = 'NFS SR'
		nfsSR.type = 'nfs'
		nfsSR.physicalSize = 10000L
		nfsSR.physicalUtilisation = 3000L
		nfsSR.allowedOperations = EnumSet.noneOf(com.xensource.xenapi.Types.StorageOperations.class)

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listStorageRepositories(_) >> [success: true, srList: [lvmSR, nfsSR]]

		when:
		new DatastoresSync(cloud, plugin).execute()

	then:
	createdItems.size() == 2
	createdItems.find { it.type == 'lvm' }.allowWrite == true
	createdItems.find { it.type == 'nfs' }.allowWrite == false
	createdItems.find { it.type == 'lvm' }.freeSpace == 4000L
	createdItems.find { it.type == 'nfs' }.freeSpace == 7000L
}

def "execute handles listStorageRepositories failure"() {
	given:
	def cloud = new Cloud(id: 1L, owner: new Account(id: 1L))
	def authConfig = [apiUrl: 'http://test.com', username: 'user', password: 'pass']

	and:
	def mockPlugin = Mock(XenserverPlugin)
	mockPlugin.getAuthConfig(cloud) >> authConfig
	def datastoresSync = new DatastoresSync(cloud, mockPlugin)

	when:
	GroovySpy(XenComputeUtility, global: true)
	XenComputeUtility.listStorageRepositories(authConfig) >> [success: false]
	datastoresSync.execute()

	then:
	// Error should be logged but no exception thrown
	noExceptionThrown()
}

def "execute handles exception during sync"() {
	given:
	def cloud = new Cloud(id: 1L, owner: new Account(id: 1L))

	and:
	def mockPlugin = Mock(XenserverPlugin)
	mockPlugin.getAuthConfig(cloud) >> { throw new RuntimeException("Test exception") }
	def datastoresSync = new DatastoresSync(cloud, mockPlugin)

	when:
	datastoresSync.execute()

	then:
	// Exception should be caught and logged
	noExceptionThrown()
}
}