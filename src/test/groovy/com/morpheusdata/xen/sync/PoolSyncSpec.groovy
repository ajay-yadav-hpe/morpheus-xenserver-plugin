package com.morpheusdata.xen.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
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
}
