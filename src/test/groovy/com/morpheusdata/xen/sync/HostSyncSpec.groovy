package com.morpheusdata.xen.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.xen.XenserverPlugin
import com.morpheusdata.xen.support.TestSpecBase
import com.morpheusdata.xen.util.XenComputeUtility
import io.reactivex.rxjava3.core.Observable

class HostSyncSpec extends TestSpecBase {

	XenserverPlugin plugin
	List createdHosts

	def setup() {
		createdHosts = []
	}

	def "execute creates missing hosts"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin) {
			getAuthConfig(cloud) >> [endpoint: 'test']
		}

		def asyncComputeServer = [
			listIdentityProjections: { DataQuery query -> Observable.empty() },
			create: { host ->
				createdHosts << host
				Observable.just(host)
			},
			remove: { List removeList -> Observable.just(true) }
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [computeServer: asyncComputeServer])
		plugin.morpheusContext >> context

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listHosts(_) >> [success: true, hostList: [[
			uuid : 'host-1',
			host : [nameLabel: 'Host One', address: '10.0.0.1', hostname: 'host-one'],
			metrics: [memoryTotal: 1024L]
		]]]

		when:
		new HostSync(cloud, plugin).execute()

		then:
		createdHosts.size() == 1
		createdHosts.first().name == 'Host One'
	}

	def "execute handles api failure gracefully"() {
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
		XenComputeUtility.listHosts(_) >> [success: false]

		when:
		new HostSync(cloud, plugin).execute()

		then:
		notThrown(Exception)
	}
}
