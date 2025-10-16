package com.morpheusdata.xen.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.xen.XenserverPlugin
import com.morpheusdata.xen.support.TestSpecBase
import com.morpheusdata.xen.util.XenComputeUtility
import com.xensource.xenapi.Types
import io.reactivex.rxjava3.core.Observable

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
}
