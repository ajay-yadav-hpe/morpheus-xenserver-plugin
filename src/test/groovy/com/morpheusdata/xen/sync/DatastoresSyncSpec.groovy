package com.morpheusdata.xen.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.projection.DatastoreIdentityProjection
import com.morpheusdata.xen.XenserverPlugin
import com.morpheusdata.xen.support.TestSpecBase
import com.morpheusdata.xen.util.XenComputeUtility
import com.xensource.xenapi.SR
import io.reactivex.rxjava3.core.Observable
import java.util.EnumSet

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
			save: { List items -> Observable.just(items) },
			remove: { List items -> Observable.just(items) },
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
}
