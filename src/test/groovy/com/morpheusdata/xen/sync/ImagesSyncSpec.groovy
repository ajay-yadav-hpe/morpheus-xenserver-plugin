package com.morpheusdata.xen.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.xen.XenserverPlugin
import com.morpheusdata.xen.support.TestSpecBase
import com.morpheusdata.xen.util.XenComputeUtility
import com.xensource.xenapi.VM
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single

class ImagesSyncSpec extends TestSpecBase {

	XenserverPlugin plugin
	List<VirtualImage> createdImages
	List<VirtualImageLocation> createdLocations

	def setup() {
		createdImages = []
		createdLocations = []
	}

	def "execute creates virtual images and locations"() {
		given:
		Cloud cloud = newCloud()
		cloud.account = cloud.owner
		plugin = GroovyMock(XenserverPlugin)

		def locationAsync = [
			listSyncProjections: { Long id -> Observable.empty() },
			create: { List adds, Cloud c ->
				createdLocations.addAll(adds)
				Single.just(adds)
			},
			listById: { List ids -> Observable.fromIterable([]) },
			save: { List items, Cloud c -> Single.just(items) },
			remove: { List items -> Single.just(items) }
		]

		def virtualImageAsync = [
			listIdentityProjections: { DataQuery query -> Observable.empty() },
			create: { List adds, Cloud c ->
				createdImages.addAll(adds)
				Single.just(adds)
			},
			listById: { List ids -> Observable.fromIterable([]) },
			location: locationAsync
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [virtualImage: virtualImageAsync])
		plugin.morpheusContext >> context

		VM.Record template = new VM.Record()
		template.uuid = 'tmpl-1'
		template.nameLabel = 'Ubuntu Template'
		template.nameDescription = 'Ubuntu LTS'

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listTemplates(_) >> [success: true, templateList: [template]]

		when:
		new ImagesSync(cloud, plugin).execute()

		then:
		createdImages.size() == 1
		createdImages.first().name == 'Ubuntu Template'
		def imageLocations = createdImages.first().imageLocations
		imageLocations.size() == 1
		imageLocations.first().imageName == 'Ubuntu Template'
	}

	def "execute handles template list failure"() {
		given:
		Cloud cloud = newCloud()
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [virtualImage: [location: [:]]])
		plugin = GroovyMock(XenserverPlugin) {
			morpheusContext >> context
		}

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listTemplates(_) >> [success: false]

		when:
		new ImagesSync(cloud, plugin).execute()

		then:
		notThrown(Exception)
	}
}
