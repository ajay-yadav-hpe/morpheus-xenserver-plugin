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
}
