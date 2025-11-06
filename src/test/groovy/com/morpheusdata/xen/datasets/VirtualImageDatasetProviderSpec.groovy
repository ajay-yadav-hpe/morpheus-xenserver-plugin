package com.morpheusdata.xen.datasets

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DatasetQuery
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.projection.VirtualImageIdentityProjection
import com.morpheusdata.xen.support.TestSpecBase
import io.reactivex.rxjava3.core.Observable

class VirtualImageDatasetProviderSpec extends TestSpecBase {

	VirtualImageDatasetProvider provider
	List capturedOptions

	def setup() {
		capturedOptions = []
		ProvisionProvider provisionProvider = GroovyMock(ProvisionProvider) {
			getVirtualImageTypes() >> [[code: 'xen']]
		}
		def plugin = GroovyMock(com.morpheusdata.core.Plugin) {
			getProvidersByType(ProvisionProvider) >> [provisionProvider]
		}

		def asyncVirtualImage = [
			list: { query -> Observable.fromIterable([new VirtualImage(name: 'ImageA')]) },
			listIdentityProjections: { query ->
				def projection = Stub(VirtualImageIdentityProjection) {
					getId() >> 1L
					getName() >> 'ImageA'
				}
				Observable.fromIterable([projection])
			}
		]

		def servicesVirtualImage = [get: { Long id -> new VirtualImage(id: id, name: 'ImageA') }]

		def context = GroovyMock(MorpheusContext)
		stubServices(context, [virtualImage: servicesVirtualImage])
		stubAsync(context, [virtualImage: asyncVirtualImage])

		provider = new VirtualImageDatasetProvider(plugin, context)
	}

	def "provider metadata is defined"() {
		expect:
		provider.info.name == VirtualImageDatasetProvider.providerName
		provider.itemType == VirtualImage
	}

	def "list returns observable of virtual images"() {
		when:
		def results = provider.list(new DatasetQuery()).toList().blockingGet()

		then:
		results.size() == 1
		results.first() instanceof VirtualImage
	}

	def "listOptions maps projections to name value pairs"() {
		when:
		def options = provider.listOptions(new DatasetQuery()).toList().blockingGet()

		then:
		options == [[name: 'ImageA', value: 1L]]
	}

	def "item lookup supports numeric ids"() {
		expect:
		provider.item(1L).name == 'ImageA'
		provider.fetchItem('1').name == 'ImageA'
	}

	def "fetch item returns null when parsing fails"() {
		expect:
		provider.fetchItem('not-a-number') == null
		provider.fetchItem(null) == null
	}

	def "item metadata helpers expose name and id"() {
		given:
		VirtualImage image = new VirtualImage(id: 9L, name: 'Xen Template')

		expect:
		provider.itemName(image) == 'Xen Template'
		provider.itemValue(image) == 9L
	}

	def "image types collected from provision providers"() {
		expect:
		provider.imageTypes == ['xen']
	}
}
