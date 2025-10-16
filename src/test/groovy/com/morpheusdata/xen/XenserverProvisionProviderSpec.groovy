package com.morpheusdata.xen

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.model.Icon
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.Workload
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.xen.support.TestSpecBase
import io.reactivex.rxjava3.core.Observable
import spock.lang.Subject

class XenserverProvisionProviderSpec extends TestSpecBase {

	MorpheusContext context
	XenserverPlugin plugin
	Map storageVolumeAsync

	def setup() {
		plugin = GroovyMock(XenserverPlugin)
		storageVolumeAsync = [
			storageVolumeType: [
				list: { query -> Observable.fromIterable([new StorageVolumeType(code: 'standard')]) }
			]
		]
		context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [storageVolume: storageVolumeAsync])
		provider = new XenserverProvisionProvider(plugin, context)
	}

	@Subject
	XenserverProvisionProvider provider

	def "provision type details are exposed"() {
		expect:
		provider.provisionTypeCode == XenserverProvisionProvider.PROVISION_TYPE_CODE
		provider.circularIcon instanceof Icon
		provider.circularIcon.path == 'xcpng-circular-light.svg'
	}

	def "option types include skip agent flag"() {
		expect:
		provider.optionTypes*.code.contains('provisionType.xenserver.noAgent')
	}

	def "node option types include virtual image select"() {
		expect:
		provider.nodeOptionTypes*.code.contains('provisionType.xen.custom.containerType.virtualImageId')
	}

	def "root volume storage types resolve standard type"() {
		expect:
		provider.rootVolumeStorageTypes*.code == ['standard']
	}

	def "data volume storage types resolve standard type"() {
		expect:
		provider.dataVolumeStorageTypes*.code == ['standard']
	}

	def "prepareWorkload returns successful response"() {
		given:
		Workload workload = new Workload()
		WorkloadRequest request = new WorkloadRequest()

		when:
		ServiceResponse response = provider.prepareWorkload(workload, request, [:])

		then:
		response.success
		response.data.workload == workload
	}

	def "service plans include expected defaults"() {
		when:
		Collection<ServicePlan> plans = provider.servicePlans

		then:
		plans.size() > 0
		plans*.code.containsAll(['xen-vm-512', 'xen-vm-16384', 'internal-custom-xen'])
		plans.find { it.code == 'xen-vm-512' }.maxMemory > 0
	}
}
