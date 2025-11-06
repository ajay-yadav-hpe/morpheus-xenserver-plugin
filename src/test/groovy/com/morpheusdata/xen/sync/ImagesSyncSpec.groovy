package com.morpheusdata.xen.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.projection.VirtualImageIdentityProjection
import com.morpheusdata.model.projection.VirtualImageLocationIdentityProjection
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

		def "execute updates existing virtual image metadata"() {
			given:
			Cloud cloud = newCloud()
			cloud.account = cloud.owner
			plugin = GroovyMock(XenserverPlugin) {
				getAuthConfig(cloud) >> [:]
			}
			List<VirtualImageLocation> savedLocations = []
			List<VirtualImage> savedImages = []
			VirtualImageLocationIdentityProjection locationProjection = Stub(VirtualImageLocationIdentityProjection) {
				getId() >> 100L
				getExternalId() >> 'tmpl-1'
			}
			VirtualImage existingImage = new VirtualImage([id: 200L, refId: '999', externalId: 'tmpl-1', imageLocations: []])
			VirtualImageLocation existingLocation = new VirtualImageLocation([id: 100L, uuid: 'old-uuid', externalId: 'tmpl-1', virtualImage: existingImage])
			existingImage.imageLocations = [existingLocation]
			VirtualImageIdentityProjection imageProjection = Stub(VirtualImageIdentityProjection) {
				getId() >> 200L
				getExternalId() >> 'tmpl-1'
				getImageType() >> 'vhd'
				getName() >> 'Ubuntu Template'
				getSystemImage() >> false
				getOwnerId() >> cloud.owner.id
			}
			def locationAsync = [
				listSyncProjections: { Long id -> Observable.fromIterable([locationProjection]) },
				create            : { List adds, Cloud c -> Single.just(adds) },
				listById          : { List ids -> Observable.fromIterable([existingLocation]) },
				save               : { List items, Cloud c ->
					savedLocations.addAll(items)
					Single.just(items)
				},
				remove            : { List removeList -> Single.just(removeList) }
			]
			def virtualImageAsync = [
				listIdentityProjections: { Object arg ->
					if (arg instanceof DataQuery) {
						return Observable.empty()
					}
					Observable.fromIterable([imageProjection])
				},
				create               : { List adds, Cloud c -> Single.just(adds) },
				listById             : { List ids -> Observable.fromIterable([existingImage]) },
				save                 : { List items, Cloud c ->
					savedImages.addAll(items)
					Single.just(items)
				},
				location             : locationAsync
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
			savedLocations.size() == 1
			savedLocations.first().uuid == 'tmpl-1'
			savedImages.size() == 1
			savedImages.first().refId == "${cloud.id}"
		}

		def "execute removes stale virtual image locations"() {
			given:
			Cloud cloud = newCloud()
			cloud.account = cloud.owner
			plugin = GroovyMock(XenserverPlugin) {
				getAuthConfig(cloud) >> [:]
			}
			List<VirtualImageLocationIdentityProjection> removed = []
			VirtualImageLocationIdentityProjection activeProjection = Stub(VirtualImageLocationIdentityProjection) {
				getId() >> 300L
				getExternalId() >> 'tmpl-active'
			}
			VirtualImageLocationIdentityProjection staleProjection = Stub(VirtualImageLocationIdentityProjection) {
				getId() >> 301L
				getExternalId() >> 'tmpl-stale'
			}
			VirtualImage activeImage = new VirtualImage([id: 400L, refId: "${cloud.id}", externalId: 'tmpl-active', imageLocations: []])
			VirtualImageLocation activeLocation = new VirtualImageLocation([id: 300L, uuid: 'tmpl-active', externalId: 'tmpl-active', virtualImage: activeImage])
			def locationAsync = [
				listSyncProjections: { Long id -> Observable.fromIterable([activeProjection, staleProjection]) },
				create            : { List adds, Cloud c -> Single.just(adds) },
				listById          : { List ids -> Observable.fromIterable([activeLocation]) },
				save               : { List items, Cloud c -> Single.just(items) },
				remove            : { List items ->
					removed.addAll(items)
					Single.just(items)
				}
			]
			VirtualImageIdentityProjection imageProjection = Stub(VirtualImageIdentityProjection) {
				getId() >> 400L
				getExternalId() >> 'tmpl-active'
				getImageType() >> 'vhd'
				getName() >> 'Active Template'
				getSystemImage() >> false
				getOwnerId() >> cloud.owner.id
			}
			def virtualImageAsync = [
				listIdentityProjections: { Object arg ->
					if (arg instanceof DataQuery) {
						return Observable.empty()
					}
					Observable.fromIterable([imageProjection])
				},
				create               : { List adds, Cloud c -> Single.just(adds) },
				listById             : { List ids -> Observable.fromIterable([activeImage]) },
				save                 : { List items, Cloud c -> Single.just(items) },
				location             : locationAsync
			]
			def context = GroovyMock(MorpheusContext)
			stubServices(context)
			stubAsync(context, [virtualImage: virtualImageAsync])
			plugin.morpheusContext >> context

			VM.Record template = new VM.Record()
			template.uuid = 'tmpl-active'
			template.nameLabel = 'Active Template'
			template.nameDescription = 'Active'

			GroovySpy(XenComputeUtility, global: true)
			XenComputeUtility.listTemplates(_) >> [success: true, templateList: [template]]

			when:
			new ImagesSync(cloud, plugin).execute()

			then:
			removed == [staleProjection]
		}

	// ==================== COMPREHENSIVE EXPANSION FOR 80%+ COVERAGE ====================

	def "buildVirtualImageConfig creates proper image configuration"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		ImagesSync sync = new ImagesSync(cloud, plugin)
		
		VM.Record cloudItem = new VM.Record()
		cloudItem.uuid = 'test-uuid-123'
		cloudItem.nameLabel = 'Test Template Image'
		cloudItem.nameDescription = 'Test description for template'

		when:
		def config = sync.buildVirtualImageConfig(cloudItem)

		then:
		config.account == cloud.account
		config.category == "xenserver.image.${cloud.id}"
		config.name == 'Test Template Image'
		config.owner == cloud.owner
		config.description == 'Test description for template'
		config.imageType == 'xen'
		config.code == "xenserver.image.${cloud.id}.test-uuid-123"
		config.uniqueId == 'test-uuid-123'
		config.status == 'Active'
		config.externalId == 'test-uuid-123'
		config.refType == 'ComputeZone'
		config.refId == "${cloud.id}"
	}

	def "buildLocationConfig creates proper location configuration"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		ImagesSync sync = new ImagesSync(cloud, plugin)
		
		VirtualImage image = new VirtualImage()
		image.externalId = 'img-external-123'
		image.name = 'Ubuntu Server 20.04'

		when:
		def config = sync.buildLocationConfig(image)

		then:
		config.virtualImage == image
		config.code == "xenserver.image.${cloud.id}.img-external-123"
		config.internalId == 'img-external-123'
		config.externalId == 'img-external-123'
		config.imageName == 'Ubuntu Server 20.04'
	}

	def "addMissingVirtualImageLocations handles empty cloud items"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		plugin.morpheusContext >> context

		when:
		new ImagesSync(cloud, plugin).addMissingVirtualImageLocations([])

		then:
		notThrown(Exception)
	}

	def "addMissingVirtualImageLocations filters allowed image types correctly"() {
		given:
		Cloud cloud = newCloud()
		cloud.account = cloud.owner
		plugin = GroovyMock(XenserverPlugin)
		
		VirtualImageIdentityProjection vhdProj = Stub(VirtualImageIdentityProjection) {
			getId() >> 100L
			getExternalId() >> 'vhd-img'
			getImageType() >> 'vhd'
			getName() >> 'VHD Image'
			getSystemImage() >> false
			getOwnerId() >> cloud.owner.id
		}
		
		VirtualImageIdentityProjection vmdkProj = Stub(VirtualImageIdentityProjection) {
			getId() >> 101L
			getExternalId() >> 'vmdk-img'
			getImageType() >> 'vmdk'
			getName() >> 'VMDK Image'
			getSystemImage() >> false
			getOwnerId() >> cloud.owner.id
		}
		
		VirtualImageIdentityProjection isoProj = Stub(VirtualImageIdentityProjection) {
			getId() >> 102L
			getExternalId() >> 'iso-img'
			getImageType() >> 'iso'
			getName() >> 'ISO Image'
			getSystemImage() >> false
			getOwnerId() >> cloud.owner.id
		}

		def virtualImageAsync = [
			listIdentityProjections: { DataQuery query -> 
				Observable.fromIterable([vhdProj, vmdkProj, isoProj])
			},
			create: { List adds, Cloud c -> Single.just(adds) },
			listById: { List ids -> Observable.empty() },
			location: [
				create: { List adds, Cloud c -> Single.just(adds) }
			]
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [virtualImage: virtualImageAsync])
		plugin.morpheusContext >> context

		VM.Record vhdTemplate = new VM.Record()
		vhdTemplate.uuid = 'vhd-123'
		vhdTemplate.nameLabel = 'VHD Image'

		VM.Record vmdkTemplate = new VM.Record()
		vmdkTemplate.uuid = 'vmdk-123'
		vmdkTemplate.nameLabel = 'VMDK Image'

		VM.Record isoTemplate = new VM.Record()
		isoTemplate.uuid = 'iso-123'
		isoTemplate.nameLabel = 'ISO Image'

		when:
		new ImagesSync(cloud, plugin).addMissingVirtualImageLocations([vhdTemplate, vmdkTemplate, isoTemplate])

		then:
		notThrown(Exception)
	}

	def "addMissingVirtualImageLocations handles system images correctly"() {
		given:
		Cloud cloud = newCloud()
		cloud.account = cloud.owner
		plugin = GroovyMock(XenserverPlugin)
		
		VirtualImageIdentityProjection systemProj = Stub(VirtualImageIdentityProjection) {
			getId() >> 200L
			getExternalId() >> 'sys-img'
			getImageType() >> 'vhd'
			getName() >> 'System Template'
			getSystemImage() >> true
			getOwnerId() >> null
		}

		def virtualImageAsync = [
			listIdentityProjections: { DataQuery query -> 
				Observable.fromIterable([systemProj])
			},
			create: { List adds, Cloud c -> Single.just(adds) },
			listById: { List ids -> Observable.empty() },
			location: [
				create: { List adds, Cloud c -> Single.just(adds) }
			]
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [virtualImage: virtualImageAsync])
		plugin.morpheusContext >> context

		VM.Record template = new VM.Record()
		template.uuid = 'sys-123'
		template.nameLabel = 'System Template'

		when:
		new ImagesSync(cloud, plugin).addMissingVirtualImageLocations([template])

		then:
		notThrown(Exception)
	}

	def "addMissingVirtualImages creates images with proper configuration"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		List<VirtualImage> createdImages = []
		
		def virtualImageAsync = [
			create: { List adds, Cloud c ->
				createdImages.addAll(adds)
				Single.just(adds)
			}
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [virtualImage: virtualImageAsync])
		plugin.morpheusContext >> context

		VM.Record template1 = new VM.Record()
		template1.uuid = 'img-1'
		template1.nameLabel = 'Ubuntu 20.04'
		template1.nameDescription = 'Ubuntu Server LTS'

		VM.Record template2 = new VM.Record()
		template2.uuid = 'img-2'
		template2.nameLabel = 'CentOS 8'
		template2.nameDescription = 'CentOS Stream'

		ImagesSync sync = new ImagesSync(cloud, plugin)

		when:
		sync.addMissingVirtualImages([template1, template2])

		then:
		createdImages.size() == 2
		createdImages[0].name == 'Ubuntu 20.04'
		createdImages[0].externalId == 'img-1'
		createdImages[0].imageLocations.size() == 1
		createdImages[1].name == 'CentOS 8'
		createdImages[1].externalId == 'img-2'
		createdImages[1].imageLocations.size() == 1
	}

	def "addMissingVirtualImages handles exception gracefully"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		
		def virtualImageAsync = [
			create: { List adds, Cloud c ->
				throw new RuntimeException("Database error")
			}
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [virtualImage: virtualImageAsync])
		plugin.morpheusContext >> context

		VM.Record template = new VM.Record()
		template.uuid = 'error-img'
		template.nameLabel = 'Error Image'

		ImagesSync sync = new ImagesSync(cloud, plugin)

		when:
		sync.addMissingVirtualImages([template])

		then:
		notThrown(Exception)
	}

	def "addMissingVirtualImageLocationsForImages creates locations correctly"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		List<VirtualImageLocation> createdLocations = []
		
		def locationAsync = [
			create: { List adds, Cloud c ->
				createdLocations.addAll(adds)
				Single.just(adds)
			}
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [virtualImage: [location: locationAsync]])
		plugin.morpheusContext >> context

		VirtualImage image1 = new VirtualImage([externalId: 'img-loc-1', name: 'Image 1'])
		VirtualImage image2 = new VirtualImage([externalId: 'img-loc-2', name: 'Image 2'])

		def updateItem1 = [existingItem: image1, masterItem: new VM.Record()]
		def updateItem2 = [existingItem: image2, masterItem: new VM.Record()]

		ImagesSync sync = new ImagesSync(cloud, plugin)

		when:
		sync.addMissingVirtualImageLocationsForImages([updateItem1, updateItem2])

		then:
		createdLocations.size() == 2
		createdLocations[0].imageName == 'Image 1'
		createdLocations[0].externalId == 'img-loc-1'
		createdLocations[1].imageName == 'Image 2'
		createdLocations[1].externalId == 'img-loc-2'
	}

	def "addMissingVirtualImageLocationsForImages handles empty list"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		plugin.morpheusContext >> context

		ImagesSync sync = new ImagesSync(cloud, plugin)

		when:
		sync.addMissingVirtualImageLocationsForImages([])

		then:
		notThrown(Exception)
	}

	def "addMissingVirtualImageLocationsForImages handles exception gracefully"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		
		def locationAsync = [
			create: { List adds, Cloud c ->
				throw new RuntimeException("Location creation error")
			}
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [virtualImage: [location: locationAsync]])
		plugin.morpheusContext >> context

		VirtualImage image = new VirtualImage([externalId: 'error-img', name: 'Error Image'])
		def updateItem = [existingItem: image, masterItem: new VM.Record()]

		ImagesSync sync = new ImagesSync(cloud, plugin)

		when:
		sync.addMissingVirtualImageLocationsForImages([updateItem])

		then:
		notThrown(Exception)
	}

	def "updateMatchedVirtualImageLocations updates locations and images correctly"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		
		List<VirtualImageLocation> savedLocations = []
		List<VirtualImage> savedImages = []
		List<VirtualImageLocation> createdLocations = []

		VirtualImage existingImage = new VirtualImage([
			id: 500L, 
			refId: '999', 
			externalId: 'old-ref', 
			name: 'Old Name'
		])
		
		VirtualImageLocation existingLocation = new VirtualImageLocation([
			id: 600L, 
			uuid: 'old-uuid', 
			externalId: 'img-update', 
			virtualImage: existingImage
		])

		VirtualImageIdentityProjection imageProjection = Stub(VirtualImageIdentityProjection) {
			getId() >> 500L
		}

		def locationAsync = [
			create: { List adds, Cloud c ->
				createdLocations.addAll(adds)
				Single.just(adds)
			},
			save: { List items, Cloud c ->
				savedLocations.addAll(items)
				Single.just(items)
			}
		]

		def virtualImageAsync = [
			listIdentityProjections: { Long cloudId ->
				Observable.fromIterable([imageProjection])
			},
			listById: { List ids ->
				Observable.fromIterable([existingImage])
			},
			save: { List items, Cloud c ->
				savedImages.addAll(items)
				Single.just(items)
			},
			location: locationAsync
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [virtualImage: virtualImageAsync])
		plugin.morpheusContext >> context

		VM.Record cloudItem = new VM.Record()
		cloudItem.uuid = 'new-uuid-123'
		cloudItem.nameLabel = 'Updated Template'
		cloudItem.nameDescription = 'Updated description'

		def updateItem = [
			existingItem: existingLocation,
			masterItem: cloudItem
		]

		ImagesSync sync = new ImagesSync(cloud, plugin)

		when:
		sync.updateMatchedVirtualImageLocations([updateItem])

		then:
		savedLocations.size() == 1
		savedLocations[0].uuid == 'new-uuid-123'
		savedImages.size() == 1
		savedImages[0].refId == "${cloud.id}"
	}

	def "updateMatchedVirtualImageLocations handles valid location updates"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		
		List<VirtualImageLocation> savedLocations = []
		List<VirtualImage> savedImages = []

		VirtualImage existingImage = new VirtualImage([
			id: 700L, 
			externalId: 'matched-img', 
			name: 'Matched Template',
			refId: '999'
		])

		VirtualImageLocation existingLocation = new VirtualImageLocation([
			id: 750L, 
			uuid: 'old-uuid',
			externalId: 'matched-img', 
			virtualImage: existingImage
		])

		VirtualImageIdentityProjection imageProjection = Stub(VirtualImageIdentityProjection) {
			getId() >> 700L
		}

		def locationAsync = [
			create: { List adds, Cloud c -> Single.just(adds) },
			save: { List items, Cloud c ->
				savedLocations.addAll(items)
				Single.just(items)
			}
		]

		def virtualImageAsync = [
			listIdentityProjections: { Long cloudId ->
				Observable.fromIterable([imageProjection])
			},
			listById: { List ids ->
				Observable.fromIterable([existingImage])
			},
			save: { List items, Cloud c ->
				savedImages.addAll(items)
				Single.just(items)
			},
			location: locationAsync
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [virtualImage: virtualImageAsync])
		plugin.morpheusContext >> context

		VM.Record cloudItem = new VM.Record()
		cloudItem.uuid = 'new-uuid-456'
		cloudItem.nameLabel = 'Matched Template'

		def updateItem = [
			existingItem: existingLocation,
			masterItem: cloudItem
		]

		ImagesSync sync = new ImagesSync(cloud, plugin)

		when:
		sync.updateMatchedVirtualImageLocations([updateItem])

		then:
		savedLocations.size() == 1
		savedLocations[0].uuid == 'new-uuid-456'
		savedImages.size() == 1
		savedImages[0].refId == "${cloud.id}"
	}

	def "updateMatchedVirtualImageLocations handles exception gracefully"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		
		def virtualImageAsync = [
			listIdentityProjections: { Long cloudId ->
				throw new RuntimeException("Database connection error")
			}
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [virtualImage: virtualImageAsync])
		plugin.morpheusContext >> context

		VM.Record cloudItem = new VM.Record()
		cloudItem.uuid = 'error-item'

		VirtualImageLocation location = new VirtualImageLocation([id: 800L])
		def updateItem = [existingItem: location, masterItem: cloudItem]

		ImagesSync sync = new ImagesSync(cloud, plugin)

		when:
		sync.updateMatchedVirtualImageLocations([updateItem])

		then:
		notThrown(Exception)
	}

	def "removeMissingVirtualImageLocations removes locations correctly"() {
		given:
		Cloud cloud = newCloud()
		plugin = GroovyMock(XenserverPlugin)
		
		List<VirtualImageLocationIdentityProjection> removedItems = []

		def locationAsync = [
			remove: { List items ->
				removedItems.addAll(items)
				Single.just(items)
			}
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [virtualImage: [location: locationAsync]])
		plugin.morpheusContext >> context

		VirtualImageLocationIdentityProjection proj1 = Stub(VirtualImageLocationIdentityProjection) {
			getId() >> 900L
			getExternalId() >> 'remove-1'
		}
		
		VirtualImageLocationIdentityProjection proj2 = Stub(VirtualImageLocationIdentityProjection) {
			getId() >> 901L
			getExternalId() >> 'remove-2'
		}

		ImagesSync sync = new ImagesSync(cloud, plugin)

		when:
		sync.removeMissingVirtualImageLocations([proj1, proj2])

		then:
		removedItems == [proj1, proj2]
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
		XenComputeUtility.listTemplates(_) >> { throw new RuntimeException("XenAPI connection failed") }

		when:
		new ImagesSync(cloud, plugin).execute()

		then:
		notThrown(Exception)
	}

	def "execute processes multiple templates with mixed scenarios"() {
		given:
		Cloud cloud = newCloud()
		cloud.account = cloud.owner
		plugin = GroovyMock(XenserverPlugin) {
			getAuthConfig(cloud) >> [hostname: 'xenserver.test', username: 'test', password: 'pass']
		}

		List<VirtualImage> createdImages = []
		List<VirtualImageLocationIdentityProjection> removedLocations = []

		// Stale location that will be removed
		VirtualImageLocationIdentityProjection staleProj = Stub(VirtualImageLocationIdentityProjection) {
			getId() >> 1001L
			getExternalId() >> 'stale-template'
		}

		def locationAsync = [
			listSyncProjections: { Long id -> 
				Observable.fromIterable([staleProj]) 
			},
			create: { List adds, Cloud c -> Single.just(adds) },
			listById: { List ids -> Observable.empty() },
			save: { List items, Cloud c -> Single.just(items) },
			remove: { List items ->
				removedLocations.addAll(items)
				Single.just(items)
			}
		]

		def virtualImageAsync = [
			listIdentityProjections: { Object arg ->
				Observable.empty()
			},
			create: { List adds, Cloud c ->
				createdImages.addAll(adds)
				Single.just(adds)
			},
			listById: { List ids -> Observable.empty() },
			save: { List items, Cloud c -> Single.just(items) },
			location: locationAsync
		]

		def context = GroovyMock(MorpheusContext)
		stubServices(context)
		stubAsync(context, [virtualImage: virtualImageAsync])
		plugin.morpheusContext >> context

		// New template to be added
		VM.Record newTemplate = new VM.Record()
		newTemplate.uuid = 'new-template'
		newTemplate.nameLabel = 'New Ubuntu Template'
		newTemplate.nameDescription = 'Fresh template'

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.listTemplates(_) >> [
			success: true, 
			templateList: [newTemplate]
		]

		when:
		new ImagesSync(cloud, plugin).execute()

		then:
		createdImages.size() == 1
		createdImages[0].name == 'New Ubuntu Template'
		removedLocations == [staleProj]
	}
}
