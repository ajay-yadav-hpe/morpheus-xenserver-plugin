package com.morpheusdata.xen.util

import com.bertramlabs.plugins.karman.CloudFile
import com.bertramlabs.plugins.karman.StorageProvider
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.NetworkUtility
import com.morpheusdata.core.util.ProgressInputStream
import com.morpheusdata.model.Cloud
import com.morpheusdata.response.ServiceResponse
import com.xensource.xenapi.*
import com.xensource.xenapi.Types.VmPowerState
import groovy.util.logging.Slf4j
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZUtils
import org.apache.commons.compress.utils.IOUtils
import org.apache.http.client.methods.CloseableHttpResponse
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.InputSource

import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * @author rahul.ray, Dustin DeYoung
 */

@Slf4j
class XenComputeUtility {

	static minDiskImageSize = (2l * 1024l * 1024l * 1024l)
	static minDynamicMemory = (512l * 1024l * 1024l)

	static testConnection(Map config) {
		getXenConnectionSession(config)
	}

	static createServer(opts, cloudIsoOutputStream) {
		def rtn = [success: false]
		log.debug "createServer: ${opts}"
		try {
			def config = getXenConnectionSession(opts.authConfig)
			opts.connection = config.connection
			def srRecord = SR.getByUuid(config.connection, opts.datastore.externalId)
			def template = VM.getByUuid(config.connection, opts.imageId)
			def newVm = template.createClone(config.connection, opts.name)
			newVm.setIsATemplate(config.connection, false)
			//set ram
			def newMemory = (opts.maxMemory).toLong()
			def newStorage = (opts.maxStorage).toLong()
			newVm.setMemoryLimits(config.connection, newMemory, newMemory, newMemory, newMemory)
			//set cpu
			if(opts.maxCpu) {
				newVm.setVCPUsMax(config.connection, opts.maxCpu)

				newVm.setVCPUsAtStartup(config.connection, opts.maxCpu)
			}
			//disk
			def newConfig = newVm.getOtherConfig(config.connection)
			def newDisks = newConfig.get('disks')
			if(newDisks) {
				newDisks = newDisks.replaceAll('sr=\"\"', 'sr=\"' + srRecord.getUuid(config.connection) + '\"')
				newConfig.put('disks', newDisks)
				newVm.setOtherConfig(config.connection, newConfig)
			}
			//add cloud init iso
			def cdResults = opts.cloudConfigFile ? insertCloudInitDisk(opts, cloudIsoOutputStream) : [success: false]
			def rootVolume = opts.server.volumes.find { it.rootVolume }
			if(rootVolume) {
				rootVolume.unitNumber = "0"
				rootVolume.save()
			}
			def lastDiskIndex = 0
			if(cdResults.success == true) {
				lastDiskIndex = createCdromVbd(opts, newVm, cdResults.vdi, (lastDiskIndex + 1).toString()).deviceId.toInteger()
			}
			//add optional data disk
			if(opts.dataDisks?.size() > 0) {
				opts.dataDisks?.eachWithIndex { disk, diskIndex ->
					def dataSrRecord = SR.getByUuid(config.connection, disk.datastore.externalId)
					def dataVdi = createVdi(opts, dataSrRecord, disk.maxStorage)
					def dataVbd = createVbd(opts, newVm, dataVdi, (lastDiskIndex + 1).toString())
					lastDiskIndex = dataVbd.deviceId?.toInteger() ?: lastDiskIndex + 1
					if(dataVbd.success == true) {
						dataVbd.vbd.setUnpluggable(opts.connection, true)
						def deviceId = dataVbd.vbd.getUserdevice(opts.connection)
						if(deviceId) {
							disk.unitNumber = "${deviceId}"
						} else {
							disk.unitNumber = lastDiskIndex
						}
						disk.save()
					}
				}
			} else if(opts.diskSize) {
				def dataVdi = createVdi(opts, srRecord, opts.diskSize)
				def dataVbd = createVbd(opts, newVm, dataVdi, (lastDiskIndex + 1).toString())
				lastDiskIndex = dataVbd.deviceId.toInteger()
			}
			//set network
			setVmNetwork(opts, newVm, opts.networkConfig)
			def rootVbd = findRootDrive(opts, newVm)
			def rootVbdSize = rootVbd.getVirtualSize(config.connection)
			log.info("resizing root drive: ${rootVbd} with size: ${rootVbdSize} to: ${newStorage}")
			if(rootVbd && newStorage > rootVbdSize) rootVbd.resize(config.connection, newStorage)
			rtn.success = true
			rtn.vm = newVm
			rtn.vmRecord = rtn.vm.getRecord(opts.connection)
			rtn.vmId = rtn.vmRecord.uuid
			rtn.volumes = getVmVolumes(opts, newVm)
			rtn.networks = getVmNetworks(opts, newVm)
			//find vif - change
			//def networkRecord = com.xensource.xenapi.Network.getByUuid(config.connection, opts.network.externalId)
			//def newVif = createVif(opts, newVm, networkRecord)
			//create vbd
			//def newVbd = createVbd(opts, newVm, opts.vdi)
			/*def newConfig = newVm.getOtherConfig(config.connection)
			def newDisks = newConfig.get('disks')
			if(newDisks) {
				newDisks = newDisks.replaceAll('sr=\"\"', 'sr=\"' + opts.srRecord.getUuid(config.connection) + '\"')
			  newConfig.put('disks', newDisks)
			  newVm.setOtherConfig(config.connection, newConfig)
			}
			//pvargs
			def pvArgs = newVm.getPVArgs(config.connection)
			pvArgs += '-- quiet console=hvc0'
			newVm.setPVArgs(opts.connection, pvArgs)*/
		} catch(e) {
			log.error("createServer error: ${e}", e)
		}
		return rtn
	}

	static cloneServer(opts, cloudIsoOutputStream) {
		def rtn = [success: false]
		log.info "cloneServer: ${opts}"
		try {
			def config = getXenConnectionSession(opts.authConfig)
			opts.connection = config.connection
			def srRecord = SR.getByUuid(config.connection, opts.datastore.externalId)
			def template = VM.getByUuid(config.connection, opts.imageId)
			def sourceVmId = opts.sourceVmId
			def sourceVm
			try {
				sourceVm = VM.getByUuid(config.connection, opts.sourceVmId)
			} catch(vme) {
				//source vm no longer exists
			}
			//need to shut down source vm
			if(sourceVm) {
				stopVm(opts.authConfig, sourceVmId)
			}
			def newVm = template.createClone(config.connection, opts.name)
			newVm.setIsATemplate(config.connection, false)
			//disk
			def newConfig = newVm.getOtherConfig(config.connection)
			def newDisks = newConfig.get('disks')
			if(newDisks) {
				newDisks = newDisks.replaceAll('sr=\"\"', 'sr=\"' + srRecord.getUuid(config.connection) + '\"')
				newConfig.put('disks', newDisks)
				newVm.setOtherConfig(config.connection, newConfig)
			}
			def cdrom = newVm.getVBDs(config.connection).find { vbd -> vbd.getType(config.connection) == Types.VbdType.CD }
			if(cdrom) {
				def cdResults = opts.cloudConfigFile ? insertCloudInitDisk(opts, cloudIsoOutputStream) : [success: false]
				if(cdResults.success == true) {
					cdrom.eject(config.connection)
					cdrom.insert(config.connection, cdResults.vdi)
				} else {
					rtn.success = false
					rtn.msg = cdResults.msg
				}
				// we have a cdrom and need to attach a new iso
			}
			if(sourceVm) {
				//restart the source vm
				log.info "startng source VM ${sourceVmId}"
				startVm(opts.authConfig, sourceVmId)
			}
			//results
			rtn.success = true
			rtn.vm = newVm
			rtn.vmRecord = rtn.vm.getRecord(opts.connection)
			rtn.vmId = rtn.vmRecord.uuid
			rtn.volumes = getVmVolumes(opts, newVm)
			rtn.networks = getVmNetworks(opts, newVm)
		} catch(e) {
			log.error("cloneServer error: ${e}", e)
		}
		return rtn
	}

	static validateServerConfig(Map opts = [:]) {
		log.debug "validateServerConfig: $opts"
		def rtn = [success: false, errors: []]
		try {
			// def zone = ComputeZone.read(opts.zoneId)
			if(opts.networkInterfaces?.size() > 0) {
				def hasNetwork = true
				opts.networkInterfaces?.each {
					if(!it.network.group && (it.network.id == null || it.network.id == '')) {
						hasNetwork = false
					}
				}
				if(hasNetwork != true) {
					rtn.errors += [field: 'networkInterface', msg: 'You must choose a network for each interface']
				}
			} else {
				rtn.errors += [field: 'networkInterface', msg: 'You must choose a network']
			}
			if(opts.containsKey('imageId') && !opts.imageId) {
				rtn.errors += [field: 'imageId', msg: 'You must choose an image']
			}
			if(opts.containsKey('nodeCount') && !opts.nodeCount) {
				rtn.errors += [field: 'nodeCount', msg: 'Cannot be blank']
				rtn.errors += [field: 'config.nodeCount', msg: 'Cannot be blank']
			}
			rtn.success = (rtn.errors.size() == 0)
			log.debug "validateServer results: ${rtn}"
		} catch(e) {
			log.error "error in validateServerConfig: ${e}", e
		}
		return rtn
	}

	static createTemplate(opts) {
		def rtn = [success: false]
		try {
			def templateName = osTypeTemplates[opts.osTypeCode]
			templateName = 'Other install media' //templateName ?: 'Other install media'
			def vmTemplateList = VM.getByNameLabel(opts.connection, templateName)
			def vmTemplate = vmTemplateList ? vmTemplateList.first() : null
			if(!vmTemplate) {
				VM.Record vm = new VM.Record()
				vm.nameLabel = opts.name ?: 'Morpheus Template'
				vm.memoryTarget = 512L * 1024L * 1024L
				vm.isATemplate = true
				VM newVm = VM.create(opts.connection, vm)
				def networkRecord = com.xensource.xenapi.Network.getByUuid(opts.connection, opts.network.externalId)
				createVif(opts, newVm, networkRecord)
				//create vbd
				createVbd(opts, newVm, opts.vdi)
				def newConfig = newVm.getOtherConfig(opts.connection)
				def newDisks = newConfig.get("disks")
				if(newDisks) {
					newDisks = newDisks.replaceAll('sr=\"\"', 'sr=\"' + opts.srRecord.getUuid(opts.connection) + '\"')
					newConfig.put('disks', newDisks)
					newVm.setOtherConfig(opts.connection, newConfig)
				}
				//pvargs
				def pvArgs = newVm.getPVArgs(opts.connection)
				pvArgs += '-- quiet console=hvc0'
				newVm.setPVArgs(opts.connection, pvArgs)
				//-- quiet console=hvc0
				rtn.success = true
				rtn.vm = newVm
				rtn.vmRecord = rtn.vm.getRecord(opts.connection)
				rtn.vmId = rtn.vmRecord.uuid
			} else {
				VM newVm = vmTemplate.createClone(opts.connection, opts.name)
				//create vif
				def networkRecord = com.xensource.xenapi.Network.getByUuid(opts.connection, opts.network.externalId)
				createVif(opts, newVm, networkRecord)
				//create vbd
				createVbd(opts, newVm, opts.vdi)
				def newConfig = newVm.getOtherConfig(opts.connection)
				def newDisks = newConfig.get("disks")
				if(newDisks) {
					newDisks = newDisks.replaceAll('sr=\"\"', 'sr=\"' + opts.srRecord.getUuid(opts.connection) + '\"')
					newConfig.put('disks', newDisks)
					newVm.setOtherConfig(opts.connection, newConfig)
				}
				//pvargs
				def pvArgs = newVm.getPVArgs(opts.connection)
				pvArgs += '-- quiet console=hvc0'
				newVm.setPVArgs(opts.connection, pvArgs)
				//-- quiet console=hvc0
				rtn.success = true
				rtn.vm = newVm
				rtn.vmRecord = rtn.vm.getRecord(opts.connection)
				rtn.vmId = rtn.vmRecord.uuid
			}


		} catch(e) {
			log.error("createTemplate error: ${e}", e)
		}
		return rtn
	}

	static restoreServer(opts, snapshotId) {
		def rtn = [success: false]
		log.debug "restoreServer: ${opts}"
		try {
			def config = getXenConnectionSession(opts)
			opts.connection = config.connection
			def snapshot = VM.getByUuid(config.connection, snapshotId)

			snapshot.revert(config.connection)
			rtn.success = true

		} catch(e) {
			log.error("restoreServer error: ${e}", e)
		}
		return rtn
	}

	static startVm(Map authConfig, vmId) {
		def rtn = [success: false]
		try {
			def config = getXenConnectionSession(authConfig)
			def vm = VM.getByUuid(config.connection, vmId)
			if(vm.getPowerState(config.connection) != com.xensource.xenapi.Types.VmPowerState.RUNNING) {
				vm.start(config.connection, false, true)
				rtn.success = true
			} else {
				rtn.success = true // already powered on, our job here is done.
				rtn.msg = 'VM is already powered on'
			}
		} catch(e) {
			log.error("startVm error: ${e}", e)
			rtn.msg = 'error powering on vm'
		}
		return rtn
	}

	static stopVm(Map authConfig, String vmId) {
		def rtn = [success: false]
		try {
			def config = getXenConnectionSession(authConfig)
			def vm = VM.getByUuid(config.connection, vmId)
			if(vm.getPowerState(config.connection) == com.xensource.xenapi.Types.VmPowerState.RUNNING) {
				vm.shutdown(config.connection)
				rtn.success = true
			} else {
				rtn.success = true // already powered off, our job here is done.
				rtn.msg = 'VM is already powered off'
			}
		} catch(e) {
			log.error("stopVm error: ${e}", e)
			rtn.msg = 'error powering off vm'
		}
		return rtn
	}

	static stopVmClean(Map authConfig, String vmId) {
		def rtn = [success: false]
		try {
			def config = getXenConnectionSession(authConfig)
			def vm = VM.getByUuid(config.connection, vmId)
			if(vm.getPowerState(config.connection) == com.xensource.xenapi.Types.VmPowerState.RUNNING) {
				vm.cleanShutdown(config.connection)
				rtn.success = true
			} else {
				rtn.success = true // already powered off, our job here is done.
				rtn.msg = 'VM is already powered off'
			}
		} catch(e) {
			log.error("stopVm error: ${e}", e)
			rtn.msg = 'error powering off vm'
		}
		return rtn
	}

	static restartVm(Map authConfig, String vmId) {
		def rtn = [success: false]
		try {
			def config = getXenConnectionSession(authConfig)
			def vm = VM.getByUuid(config.connection, vmId)
			if(vm.getPowerState(config.connection) == com.xensource.xenapi.Types.VmPowerState.RUNNING) {
				// this only attempts a clean reboot. To replicate the stop behavior, attempt a clean stop and then a hard stop if the clean fails, we could call stopVm() then startVm();
				vm.cleanReboot(config.connection)
				rtn.success = true
			} else {
				return startVm(authConfig, vmId)
			}
		} catch(e) {
			log.error("restartVm error: ${e}", e)
			rtn.msg = 'error restarting vm'
		}
		return rtn
	}

	static destroyVm(Map authConfig, vmId) {
		def rtn = [success: false]
		try {

			def config = getXenConnectionSession(authConfig)
			def vm = VM.getByUuid(config.connection, vmId)
			def vbdList = vm.getVBDs(config.connection)
			def vdiList = []
			vbdList?.each { vbd ->
				def vdi = vbd.getVDI(config.connection)
				if(vbd.getType(config.connection) == com.xensource.xenapi.Types.VbdType.DISK && vdi) {
					vdiList << vdi
				}
			}
			vm.destroy(config.connection)
			vdiList?.each { vdi -> vdi.destroy(config.connection)
			}
			rtn.success = true
		} catch(com.xensource.xenapi.Types.UuidInvalid ignored) {
			rtn.success = true
		} catch(e) {
			log.error("destroyVm error: ${e}", e)
			rtn.msg = 'error on destroy vm'
		}
		return rtn
	}

	static adjustVmResources(opts, vmId, allocationSpecs) {
		def rtn = [success: false]
		try {
			def config = getXenConnectionSession(opts)
			def vm = VM.getByUuid(config.connection, vmId)
			def newMemory = allocationSpecs.maxMemory
			def newCores = allocationSpecs.maxCpu
			if(newMemory) vm.setMemoryLimits(config.connection, newMemory, newMemory, newMemory, newMemory)
			//vm.setVCPUsNumberLive(config.connection, newCores)
			if(newCores) {
				if(newCores > vm.getVCPUsMax(config.connection)) {
					vm.setVCPUsMax(config.connection, newCores)
					vm.setVCPUsAtStartup(config.connection, newCores)
				} else {
					vm.setVCPUsAtStartup(config.connection, newCores)
					vm.setVCPUsMax(config.connection, newCores)
				}
			}
			rtn.success = true
		} catch(e) {
			log.error("adjustVmResources error: ${e}", e)
			println("error: ${e.dump()}")
			rtn.msg = 'error on adjust vm resources'
		}
		return rtn
	}

	static setVmNetwork(opts, vm, networkConfig) {
		def vifId = 0
		def vifList = vm.getVIFs(opts.connection)
		def primaryInterface = networkConfig.primaryInterface
		def primaryNetworkRecord = com.xensource.xenapi.Network.getByUuid(opts.connection, primaryInterface.network?.externalId)
		if(vifList?.size() > 0) {
			def vif = vifList[0]
			def vifNetwork = vif.getNetwork(opts.connection)
			if(vifNetwork != primaryNetworkRecord) {
				vif.destroy(opts.connection)
				createVif(opts, vm, primaryNetworkRecord, vifId.toString())
			}
			vifId++
		}
		networkConfig.extraInterfaces.eachWithIndex { networkRow, index ->
			def networkRecord = com.xensource.xenapi.Network.getByUuid(opts.connection, networkRow.network?.externalId)
			if(vifList.size() >= index + 2) {
				//we have a network interface we can adjust
				def vif = vifList[index + 1]
				def vifNetwork = vif.getNetwork(opts.connection)
				if(vifNetwork != networkRecord) {
					vif.destroy(opts.connection)
					createVif(opts, vm, network, vifId.toString())
				}
			} else {
				createVif(opts, vm, networkRecord, vifId.toString())
			}
			vifId++
		}
		if(vifList.size() > networkConfig.extraInterfaces.size() + 1) {
			vifList.eachWithIndex { vif, index ->
				if(index > networkConfig.extraInterfaces.size()) {
					vif.destroy(opts.connection)
				}
			}
		}
	}

	static addVmNetwork(opts, vmId, networkConfig) {
		def rtn = [success: false]
		try {
			def config = getXenConnectionSession(opts)
			opts.connection = config.connection
			def vm = VM.getByUuid(config.connection, vmId)
			def networkRecord = com.xensource.xenapi.Network.getByUuid(opts.connection, networkConfig.networkUuid)
			def newVif = createVif(opts, vm, networkRecord, networkConfig.networkIndex.toString())
			rtn.success = true
			rtn.networkIndex = newVif.getDevice(opts.connection)
			rtn.uuid = newVif.getUuid(opts.connection)
			rtn.networks = getVmNetworks(opts, vm)
		} catch(e) {
			log.error("addVmNetwork error: ${e}", e)
			rtn.msg = 'error on adding vm disk'
		}
		return rtn
	}

	static deleteVmNetwork(opts, vmId, networkUuid) {
		def rtn = [success: false]
		try {
			def config = getXenConnectionSession(opts)
			opts.connection = config.connection
			def vm = VM.getByUuid(opts.connection, vmId)
			def vmVif = findVif(opts, vm, networkUuid)
			if(vmVif) {
				try {
					if(opts.stopped != true) vmVif.unplug(opts.connection)
				} catch(e2) {
					log.warn("failed to unplug the nic")
				}
				vmVif.destroy(opts.connection)
				rtn.success = true
			} else {
				// vmVif not found, consider it a success
				rtn.success = true
			}
		} catch(e) {
			log.error("deleteVmNetwork error: ${e}", e)
			println("error: ${e.dump()}")
			rtn.msg = 'error on destroy vm vif'
		}
		return rtn
	}

	static addVmDisk(opts, vmId, diskConfig) {
		def rtn = [success: false]
		try {
			def config = getXenConnectionSession(opts)
			opts.connection = config.connection
			def vm = VM.getByUuid(config.connection, vmId)
			def dataSrRecord = SR.getByUuid(config.connection, diskConfig.datastoreId)
			def dataVdi = createVdi(opts, dataSrRecord, diskConfig.diskSize)
			def dataVbd = createVbd(opts, vm, dataVdi, (diskConfig.diskIndex).toString())
			rtn.success = true
			rtn.volume = getVmVolumeInfo(opts, dataVbd.vbd)
			rtn.volumes = getVmVolumes(opts, vm)
		} catch(e) {
			log.error("addVmDisk error: ${e}", e)
			rtn.msg = 'error on adding vm disk'
		}
		return rtn
	}

	static resizeVmDisk(opts, vmId, diskConfig) {
		def rtn = [success: false]
		try {
			def config = getXenConnectionSession(opts)
			opts.connection = config.connection
			def vm = VM.getByUuid(opts.connection, vmId)
			def vmDrive = findDriveVdi(opts, vm, diskConfig.uuid)
			def currentSize = vmDrive.getVirtualSize(opts.connection)
			def newSize = diskConfig.diskSize
			if(newSize > currentSize) {
				vmDrive.resize(opts.connection, newSize)
				//def taskResults = waitForTask(opts, opts.connection, resizeTask)
				rtn.success = true //taskResults.success
			}
			rtn.volumes = getVmVolumes(opts, vm)
		} catch(e) {
			log.error("resizeVmDisk error: ${e}", e)
			println("error: ${e.dump()}")
			rtn.msg = 'error resizing vm disk'
		}
		return rtn
	}

	static deleteVmDisk(opts, vmId, diskUuid) {
		def rtn = [success: false]
		try {
			def config = getXenConnectionSession(opts)
			opts.connection = config.connection
			def vm = VM.getByUuid(opts.connection, vmId)
			def vmDrive = findDriveVbd(opts, vm, diskUuid)
			def driveVdi = vmDrive.getVDI(opts.connection)
			try {
				if(opts.stopped != true) vmDrive.unplug(opts.connection)
			} catch(e2) {
				log.warn("failed to unplug the disk")
			}
			vmDrive.destroy(opts.connection)
			if(opts.keepDisk != true) driveVdi.destroy(opts.connection)
			rtn.success = true
		} catch(e) {
			log.error("deleteVmDisk error: ${e}", e)
			println("error: ${e.dump()}")
			rtn.msg = 'error on destroy vm'
		}
		return rtn
	}

	static getVmVolumes(Map config, vm) {
		def rtn = []
		try {
			def vbdList = vm.getVBDs(config.connection)
			vbdList?.each { vbd ->
				if(vbd.getType(config.connection) == com.xensource.xenapi.Types.VbdType.DISK) {
					rtn << getVmVolumeInfo(config, vbd)
				}
			}
			rtn = rtn.sort { it.displayOrder }
		} catch(e) {
			log.error("getVmVolumes error: ${e}", e)
		}
		return rtn
	}

	static getVmVolumeInfo(Map config, vbd) {
		def diskVdi = vbd.getVDI(config.connection)
		def diskSR = diskVdi ? diskVdi.getSR(config.connection) : null
		def newDisk = [bootable  : vbd.getBootable(config.connection), deviceIndex: vbd.getUserdevice(config.connection),
					   uuid      : vbd.getUuid(config.connection), size: (diskVdi ? diskVdi.getVirtualSize(config.connection) : 0),
					   deviceName: vbd.getDevice(config.connection), dataStore: [externalId: diskSR?.getUuid(config.connection)]]
		newDisk.displayOrder = newDisk.deviceIndex ? newDisk.deviceIndex.toLong() : 0
		return newDisk
	}

	static getVmNetworks(Map config, vm) {
		def rtn = []
		try {
			def vifList = vm.getVIFs(config.connection)
			vifList?.each { vif ->
				def vifNetwork = vif.getNetwork(config.connection)
				def networkUuid = vifNetwork.getUuid(config.connection)
				def newNic = [ipv4Allowed: vif.getIpv4Allowed(config.connection), ipv6Allowed: vif.getIpv6Allowed(config.connection),
							  deviceIndex: vif.getDevice(config.connection), uuid: vif.getUuid(config.connection),
							  macAddress : vif.getMAC(config.connection), networkUuid: networkUuid]
				// newNic.ipv4Addresses = vif.getIpv4Addresses(opts.connection)
				//ipv6Addresses:vif.getIpv6Addresses(opts.connection)
				rtn << newNic
			}
		} catch(e) {
			log.error("getVmNetworks error: ${e}")
		}
		return rtn
	}

	static findRootDrive(opts, vm) {
		def rtn
		def vbdList = vm.getVBDs(opts.connection)
		def rootVbd = vbdList.find { it.getUserdevice(opts.connection) == '0' }
		if(rootVbd) rtn = rootVbd.getVDI(opts.connection)
		return rtn
	}

	static findDriveVdi(opts, vm, driveUuid) {
		def rtn
		def vbdList = vm.getVBDs(opts.connection)
		def rootVbd = vbdList.find { it.getUuid(opts.connection) == driveUuid }
		if(rootVbd) rtn = rootVbd.getVDI(opts.connection)
		return rtn
	}

	static findDriveVbd(opts, vm, driveUuid) {
		def rtn
		def vbdList = vm.getVBDs(opts.connection)
		vbdList?.each { vbd ->
			def vbdUuid = vbd.getUuid(opts.connection)
			def vbdType = vbd.getType(opts.connection)
			println("vbd type: ${vbdType} uuid: ${vbdUuid}")
			if(vbdUuid == driveUuid) rtn = vbd
		}
		//rtn = vbdList.find { it.getUuid(opts.connection) == driveUuid }
		if(rtn) {
			def record = rtn.getRecord(opts.connection)
			println("looking for: ${driveUuid} - found vbd: ${record.uuid} ${record.userdevice} - type: ${record.type} unplug: ${record.unpluggable}")
		}
		return rtn
	}

	static findVif(opts, vm, vifUuid) {
		def rtn
		def vifList = vm.getVIFs(opts.connection)
		rtn = vifList.find { it.getUuid(opts.connection) == vifUuid }
		return rtn
	}

	static createVbd(opts, vm, vdi, deviceId = '0') {
		def newVbd = new VBD.Record()
		newVbd.VM = vm
		newVbd.VDI = vdi
		newVbd.userdevice = deviceId
		newVbd.mode = Types.VbdMode.RW
		newVbd.type = Types.VbdType.DISK
		newVbd.bootable = true
		newVbd.unpluggable = true
		//newVbd.otherConfig = ['owner':'true'] as Map
		//newVbd.empty = false
		while(deviceId?.toInteger() < 30) {
			try {
				def results = VBD.create(opts.connection, newVbd)
				return [deviceId: deviceId, vbd: results, success: true]
			} catch(com.xensource.xenapi.Types.DeviceAlreadyExists ex) {
				deviceId = (deviceId?.toInteger() + 1).toString()
				newVbd.userdevice = deviceId
			}
		}
		return [success: false]
	}

	static createVdi(opts, sr, diskSize) {
		def newVdi = new VDI.Record()
		newVdi.SR = sr
		newVdi.type = Types.VdiType.USER
		newVdi.nameLabel = opts.name
		newVdi.readOnly = false
		newVdi.virtualSize = diskSize
		return VDI.create(opts.connection, newVdi)
	}

	static createCdromVbd(opts, vm, vdi, deviceId = '3') {
		def newVbd = new VBD.Record()
		newVbd.VM = vm
		newVbd.VDI = vdi
		newVbd.userdevice = deviceId
		newVbd.mode = Types.VbdMode.RO
		newVbd.type = Types.VbdType.CD
		newVbd.bootable = false
		while(deviceId?.toInteger() < 30) {
			try {
				def results = VBD.create(opts.connection, newVbd)
				return [deviceId: deviceId, vbd: results, success: true]
			} catch(com.xensource.xenapi.Types.DeviceAlreadyExists ex) {
				deviceId = (deviceId?.toInteger() + 1).toString()
				newVbd.userdevice = deviceId
			}
		}
		return [success: false]
	}

	static createVif(opts, vm, network, deviceId = '0') {
		def newVif = new VIF.Record()
		newVif.VM = vm
		newVif.network = network
		newVif.device = deviceId
		newVif.MTU = 1500l
		newVif.lockingMode = Types.VifLockingMode.NETWORK_DEFAULT
		return VIF.create(opts.connection, newVif)
	}

	static listHosts(Map authConfig) {
		def rtn = [success: false, hostList: []]
		def config = getXenConnectionSession(authConfig)
		def hostList = com.xensource.xenapi.Host.getAllRecords(config.connection)
		hostList?.each { hostKey, hostValue ->
			def hostRow = [host: hostValue, uuid: hostValue.uuid]
			hostRow.metrics = hostValue.metrics.getRecord(config.connection)
			rtn.hostList << hostRow
		}
		rtn.success = true
		return rtn
	}

	static listPools(Map authConfig) {
		def rtn = [success: false, poolList: []]
		def config = getXenConnectionSession(authConfig)
		def poolList = com.xensource.xenapi.Pool.getAllRecords(config.connection)
		poolList?.each { poolKey, poolValue ->
			def poolRow = [pool: poolValue, uuid: poolValue.uuid]
			poolRow.master = poolValue.master.getRecord(config.connection)
			rtn.poolList << poolRow
		}
		rtn.success = true
		return rtn
	}

	/**
	 * Retrieves a list of storage repositories (SRs) from the XenServer using the provided authentication configuration.
	 *
	 * @param authConfig A map containing authentication configuration for accessing the XenServer.
	 * @return A map indicating the success status and the list of storage repositories.
	 */
	static listStorageRepositories(Map authConfig) {
		def rtn = [success: false, srList: []]
		def config = getXenConnectionSession(authConfig)
		def srList = SR.getAllRecords(config.connection)
		srList?.each { srKey, srValue -> rtn.srList << srValue
		}
		rtn.success = true
		return rtn
	}

	static listNetworks(Map authConfig) {
		def rtn = [success: false, networkList: []]
		def config = getXenConnectionSession(authConfig)
		def networkList = com.xensource.xenapi.Network.getAllRecords(config.connection)
		networkList?.each { networkKey, networkValue -> rtn.networkList << networkValue
		}
		rtn.success = true
		return rtn
	}

	/**
	 * Retrieves a list of templates from the XenServer using the provided authentication configuration.
	 *
	 * @param authConfig A map containing authentication configuration for accessing the XenServer.
	 *                   This configuration may include details such as host, username, password, etc.
	 * @return A map with the success status and the list of templates.
	 */
	static listTemplates(Map authConfig) {
		def rtn = [success: false, templateList: []]
		def config = getXenConnectionSession(authConfig)
		def vmList = VM.getAllRecords(config.connection)
		vmList?.each { vmKey, vmValue ->
			if(vmValue.isATemplate == true && vmValue.isASnapshot == false && vmValue.VBDs?.size() > 0) {
				rtn.templateList << vmValue
			}
		}
		rtn.success = true
		return rtn
	}

	static listSnapshots(opts) {
		def rtn = [success: false, snapshotList: []]
		def config = getXenConnectionSession(opts.zone)
		def vmList = VM.getAllRecords(config.connection)
		vmList?.each { vmKey, vmValue ->
			if(vmValue.isASnapshot == true) {
				rtn.snapshotList << vmValue
			}
		}
		rtn.success = true
		return rtn
	}

	static listVirtualMachines(Map authConfig) {
		def rtn = [success: false, vmList: []]
		def config = getXenConnectionSession(authConfig)
		def vmList = VM.getAllRecords(config.connection)
		vmList?.each { vmKey, vmValue ->
			if(vmValue.isATemplate == false && vmValue.isControlDomain == false && vmValue.isASnapshot == false) {
				def vmRow = [vm: vmValue]
				try {
					if(vmValue.powerState == com.xensource.xenapi.Types.VmPowerState.RUNNING) {
						try {
							vmRow.guestMetrics = vmValue.guestMetrics.getRecord(config.connection)
						} catch(me) {
							log.warn("Guest Metrics unavailable for ${vmValue.nameLabel} - {}", me.message)
						}
					}

					vmRow.volumes = getVmVolumes(config, vmKey)
					vmRow.virtualInterfaces = getVmNetworks(config, vmKey)
					vmRow.totalDiskSize = vmRow.volumes?.sum { it.size }

					try {
						if(vmValue.powerState != VmPowerState.HALTED) {
							vmRow.hostId = vmValue.residentOn.getUuid(config.connection)
						}
					} catch(e2) {
						log.warn("listVirtualMachines: an error occurred fetching VM host. VM may be in an invalid state: {}", vmValue.powerState)
					}
				} catch(e) {
					log.error("listVirtualMachines error: ${e}", e)
				}
				rtn.vmList << vmRow
			}
		}
		rtn.success = true
		return rtn
	}

	static getVirtualMachine(Map authConfig, externalId) {
		def rtn = [success: false]
		try {
			def config = getXenConnectionSession(authConfig)
			//            opts.connection = config.connection
			def vmRecord = VM.getByUuid(config.connection, externalId)
			rtn.vmId = externalId
			rtn.vm = vmRecord
			rtn.vmRecord = vmRecord.getRecord(config.connection)
			def vmGuestInfo = null
			if(rtn.vmRecord.powerState == com.xensource.xenapi.Types.VmPowerState.RUNNING) {
				try {
					vmGuestInfo = vmRecord.getGuestMetrics(config.connection)
				} catch(ignore) {
					log.warn("Warning getting guest metrics ${ignore.message}")
					//metrics ignored when powered off please
				}
			}


			if(vmGuestInfo) {
				def vmNetworks = vmGuestInfo.getNetworks(config.connection)
				rtn.vmNetworks = vmNetworks
				vmNetworks.each { key, value ->
					if(!rtn.ipAddress) {
						if(value?.length() <= 15) rtn.ipAddress = value
					}
				}
			}
			rtn.volumes = getVmVolumes(config, vmRecord)
			rtn.networks = getVmNetworks(config, vmRecord)
			rtn.success = true
		} catch(e) {
			log.error("getVirtualMachine error: ${e}")
		}
		return rtn
	}

	static getConsoles(opts, externalId) {
		def rtn = [success: false]
		try {
			def config = getXenConnectionSession(opts)
			opts.connection = config.connection
			def vmRecord = VM.getByUuid(config.connection, externalId)
			rtn.vmId = externalId
			rtn.vm = vmRecord
			rtn.consoles = rtn.vm.getConsoles(config.connection)?.collect { it.getLocation(config.connection) }
			rtn.sessionId = config.connection.sessionReference //.getUuid(config.connection)
			rtn.success = true
		} catch(e) {
			log.error("getVirtualMachine error: ${e}", e)
		}
		return rtn
	}

	static createTask(opts, connection, label) {
		def rtn = [success: false]
		try {
			rtn.task = com.xensource.xenapi.Task.create(connection, label, 'morpheus created task')
			rtn.taskId = rtn.task.getUuid(connection)
		} catch(e) {
			log.error("createTask error: ${e}", e)
		}
		return rtn
	}

	static destroyTask(task, connection) {
		def rtn = [success: false]
		try {
			try {
				def errorInfo = task.getErrorInfo(connection)
				println("task errors: ${errorInfo}")
			} catch(e2) {
			}
			task.destroy(connection)
			rtn.success = true
		} catch(e) {
			log.error("destroyTask error: ${e}", e)
		}
		return rtn
	}

	static waitForTask(opts, connection, com.xensource.xenapi.Task task) {
		def rtn = [success: false]
		try {
			def pending = true
			def attempts = 0
			def maxAttempts = opts.maxAttempts ?: 50
			while(pending) {
				def tmpState = task.getStatus(connection)
				if(tmpState != Types.TaskStatusType.PENDING) {
					if(tmpState == Types.TaskStatusType.SUCCESS) {
						rtn.success = true
						pending = false
					} else if(tmpState == Types.TaskStatusType.FAILURE) {
						rtn.error = true
						rtn.success = true
						rtn.msg = "Task Failure: ${task.getResult()}"
						pending = false
					}
				}
				attempts++
				if(attempts > maxAttempts) pending = false
				sleep(opts.depay ?: (1000l * 5l))
			}
		} catch(e) {
			log.error("Error waiting for Xen Task to Complete: ${e.message}", e)
		}
	}

	static snapshotVm(opts, vmId) {
		def rtn = [success: false, externalId: vmId]
		try {
			def config = getXenConnectionSession(opts.authConfig)
			def vm = VM.getByUuid(config.connection, vmId)
			if(vm) {
				def snapshotName = opts.snapshotName ?: "${vm.getNameLabel(config.connection)}.${System.currentTimeMillis()}"
				rtn.newVm = vm.snapshot(config.connection, snapshotName)
				rtn.snapshotId = rtn.newVm.getUuid(config.connection)
				rtn.snapshotName = rtn.newVm.getNameLabel(config.connection)
				rtn.success = true
			} else {
				rtn.msg = 'VM is not available'
			}
		} catch(e) {
			log.error("snapshotVm error: ${e}", e)
			rtn.msg = 'error snapshoting vm'
		}
		return rtn
	}

	static exportVm(opts, vmId) {
		def rtn = [success: false]
		ZipOutputStream targetZipStream
		try {
			def config = getXenConnectionSession(opts.authConfig)
			def vm = VM.getByUuid(config.connection, vmId)
			def vmName = vm.getNameLabel(config.connection)
			def srcUrl = getXenApiUrl(opts.zone, true) + '/export?uuid=' + vmId
			def targetFileName = (opts.vmName ?: "${vmName}.${System.currentTimeMillis()}") + '.xva'
			def targetFolder = opts.targetDir
			targetZipStream = (ZipOutputStream) opts.targetZipStream
			def downloadResults
			if(targetZipStream) {
				ZipEntry zipEntry = new ZipEntry(targetFileName)
				targetZipStream.putNextEntry(zipEntry)
				downloadResults = downloadImage(opts, srcUrl, targetZipStream)
			} else {
				def targetFile = new File(targetFolder, targetFileName)
				OutputStream outStream = targetFile.newOutputStream()
				downloadResults = downloadImage(opts, srcUrl, outStream)
			}

			if(downloadResults.success == true) {
				rtn.success = true
			} else {
				println("failed")
			}
		} catch(e) {
			log.error("exportVm error: ${e}", e)
			rtn.msg = 'error exporting vm'
		} finally {
			if(targetZipStream) {
				targetZipStream.flush(); targetZipStream.close()
			}
		}
		return rtn
	}

	static archiveVm(Map opts, vmId, cloudBucket, archiveFolder, Closure progressCallback = null) {
		def rtn = [success: false, vmFiles: []]
		try {
			def config = getXenConnectionSession(opts.authConfig)
			opts.connection = config.connection
			def vm = VM.getByUuid(config.connection, vmId)
			def vmName = vm.getNameLabel(config.connection)
			def srcUrl = getXenApiUrl(opts.zone, true) + '/export?uuid=' + vmId
			def targetFileName = (opts.vmName ?: "${vmName}.${System.currentTimeMillis()}") + '.xva'
			def targetFile = cloudBucket["${archiveFolder}/${targetFileName}"]
			def vmDiskSize = geTotalVmDiskSize(opts, vm)
			def downloadResults = archiveImage(opts, srcUrl, targetFile, vmDiskSize, progressCallback)
			if(downloadResults.success == true) {
				rtn.success = true
			} else {
				println("failed")
			}
		} catch(e) {
			log.error("downloadVm error: ${e}", e)
		}
		return rtn
	}

	static geTotalVmDiskSize(opts, vm) {
		def rtn = 0
		try {
			def blockDevices = vm.getVBDs(opts.connection)
			blockDevices?.each { blockDevice ->
				def vdi = blockDevice.getVDI(opts.connection)
				def vdiSize = vdi?.getVirtualSize(opts.connection)
				if(vdiSize) rtn += vdiSize
			}
		} catch(e) {
			log.error("getVmTotalDiskSize error: ${e}", e)
		}
		return rtn
	}

	static insertTemplate(Map opts = [:]) {
		def rtn = [success: false]
		def config = getXenConnectionSession(opts.authConfig)
		opts.connection = config.connection
		def imageResults
		imageResults = insertContainerImage(opts)
		if(imageResults.success == true) {
			if(imageResults.vdi) {
				opts.vdi = imageResults.vdi
				opts.srRecord = imageResults.srRecord
				def templateResults = createTemplate(opts)
				rtn.success = templateResults.success
				if(rtn.success == true) rtn.imageId = templateResults.vmId
			} else if(imageResults.found == true || imageResults.imageId) {
				rtn.success = true
				rtn.imageId = imageResults.imageId
			} else {
				rtn.success = false
			}

		} else {
			log.warn("Image Upload Failed! ${imageResults}")
		}
		return rtn
	}

	static insertContainerImage(opts) {
		def rtn = [success: false, found: false]
		try {
			def currentList = listTemplates(opts.authConfig)?.templateList
			def image = opts.image
			def match = currentList.find { it.uuid == image.externalId || it.nameLabel == image.name }
			if(!match) {
				def insertOpts = [zone         : opts.zone,
								  name         : image.name,
								  imageSrc     : image.imageSrc,
								  minDisk      : image.minDisk,
								  minRam       : image.minRam,
								  imageType    : image.imageType,
								  containerType: image.containerType,
								  imageFile    : image.imageFile,
								  diskSize     : image.imageSize,
								  cloudFiles   : image.cloudFiles,
								  //cachePath       :opts.cachePath,
								  datastore    : opts.datastore,
								  network      : opts.network,
								  connection   : opts.connection,
								  authConfig   : opts.authConfig]


				//estimated disk size is wrong. we have to recalculate it
				if(image.imageFile?.name?.endsWith('.tar.gz')) {
					log.info("tar gz stream detected. recalculating size...")
					InputStream sourceStream = image.imageFile.inputStream
					TarArchiveInputStream tarStream = new TarArchiveInputStream(new GZIPInputStream(sourceStream))
					TarArchiveEntry tarEntry = tarStream.getNextTarEntry()
					insertOpts.diskSize = tarEntry.getSize()
				}
				if(opts.containerType == 'xva') {
					log.debug "insertContainerImage image: ${image}"
					def tgtUrl = getXenApiUrl(opts.zone, true) + '/import?restore=false&force=true'
					CloudFile cloudFile = image.imageFile
					def cloudFileName = cloudFile.name
					if(cloudFileName.indexOf(".") > 0) {
						cloudFileName = cloudFileName.substring(0, cloudFileName.lastIndexOf("."))
					}
					int index = cloudFileName.lastIndexOf('/')
					cloudFileName = cloudFileName.substring(index + 1)

					// template name to match on after upload, extract from the disk name in the XVA file
					def templateName = getNameFromFile(cloudFile.inputStream, cloudFileName)
					// upload it
					def uploadResults = uploadImage(image.imageFile, tgtUrl, insertOpts.cachePath, insertOpts)
					sleep(5l * 1000l) // wait for the image to be created, can we do this with a wait loop instead of an arbitrary sleep?
					// find the resulting template by name extracted from the XVA
					if(uploadResults.success && templateName) {
						def templateList = listTemplates(opts.authConfig)?.templateList
						def matchFile = templateList.find { it.nameLabel == templateName }
						log.debug("matching template: ${templateName} ${matchFile?.uuid} ${matchFile}")
						rtn.imageId = matchFile?.uuid
						rtn.found = true
						rtn.success = true
					} else {
						rtn.success = false
						rtn.found = templateName != null
					}
				} else {
					def createResults = createVdi(insertOpts)
					if(createResults.success == true) {
						//upload it -
						def srRecord = SR.getByUuid(opts.connection, opts.datastore.externalId)
						def tgtUrl = getXenApiUrl(opts.zone, true) + '/import_raw_vdi?vdi=' + createResults.vdiId
						rtn.vdiId = createResults.vdiId
						rtn.vdi = createResults.vdi
						rtn.srRecord = srRecord
						insertOpts.vdi = rtn.vdi
						//sleep(10l*60l*1000l)
						log.debug "insertContainerImage image: ${image}"
						def uploadResults = uploadImage(image.imageFile, tgtUrl, insertOpts.cachePath, insertOpts)
						rtn.success = uploadResults.success

					} else {
						rtn.msg = createResults.msg ?: createResults.error
					}
				}
			} else {
				println("using image: ${match.uuid}")
				rtn.imageId = match.uuid
				rtn.found = true
				rtn.success = true
			}
		} catch(e) {
			log.error("insertContainerImage error: ${e}", e)
		}

		return rtn
	}

	static insertCloudInitDisk(opts, cloudIsoOutputStream) {
		def rtn = [success: false]
		try {
			def isoDatastore = opts.isoDatastore
			def srRecord = SR.getByUuid(opts.connection, isoDatastore.externalId)
			def pbdList = srRecord.getPBDs(opts.connection)
			def isoPbd = pbdList.first()
			def pbdRecord = isoPbd.getRecord(opts.connection)
			def deviceConfig = pbdRecord.deviceConfig

			log.info("Preparing to Upload ISO Disk to Datastore: ${isoDatastore.name} - with deviceConfig: ${deviceConfig.dump()} ${deviceConfig['type']}")
			if(deviceConfig['type'] == 'cifs') {
				def deviceLocations = deviceConfig['location'].tokenize('\\/')
				def share = deviceLocations[1..-1].join('/')
				def devicePassword = deviceConfig['cifspassword']
				def deviceSecret = deviceConfig['cifspassword_secret']
				if(deviceSecret) {
					def secret = com.xensource.xenapi.Secret.getByUuid(opts.connection, deviceSecret)
					devicePassword = secret.getValue(opts.connection)
				}

				log.debug("Looking for cifs: ${deviceLocations[0]}")
				def provider = StorageProvider.create(provider: 'cifs', host: deviceLocations[0], username: deviceConfig['username'], password: devicePassword)
				def iso = provider[share][opts.cloudConfigFile]
				iso.setBytes(cloudIsoOutputStream)
				iso.save()
			} else {
				def locationArgs = deviceConfig['location'].tokenize(':')
				log.debug("Looking for nfs: ${locationArgs[0]}")
				def provider = StorageProvider.create(provider: 'nfs', host: locationArgs[0], exportFolder: locationArgs[1])
				def iso = provider['/'][opts.cloudConfigFile]
				iso.setBytes(cloudIsoOutputStream)
				iso.save()
			}
			srRecord.scan(opts.connection)
			//find it
			def vdiList = srRecord.getVDIs(opts.connection)
			vdiList?.each {
				if(it.getNameLabel(opts.connection) == opts.cloudConfigFile) {
					rtn.vdi = it
					rtn.success = true
				}
			}
		} catch(e) {
			rtn.success = false
			rtn.msg = "Failed to upload Cloud Init UserData to shared ISO Datastore"
			log.error("insertCloudInitDisk error: ${e}", e)
		}
		return rtn
	}

	static createVdi(opts) {
		log.debug "createVdi opts: ${opts}"
		def rtn = [success: false]
		try {
			//HTTP PUT /import_raw_vdi?vdi=<VDI ref>[&session_id=<session ref>][&task_id=<task ref>][&format=<format>]
			def srRecord = SR.getByUuid(opts.connection, opts.datastore.externalId)
			def newRecord = new VDI.Record()
			newRecord.nameLabel = opts.name
			newRecord.SR = srRecord
			newRecord.type = Types.VdiType.USER
			newRecord.readOnly = false
			newRecord.virtualSize = opts.diskSize ?: minDiskImageSize
			rtn.vdi = VDI.create(opts.connection, newRecord)
			log.info "createVdi got: ${rtn.vdi}"
			rtn.vdiRecord = rtn.vdi.getRecord(opts.connection)
			rtn.vdiId = rtn.vdiRecord.uuid
			rtn.success = true
		} catch(e) {
			log.error("create vdi error: ${e}", e)
		}
		log.debug("createVdi rtn: [success: ${rtn.success}, vdiId: ${rtn.vidId}")
		return rtn
	}

	static uploadImage(CloudFile cloudFile, String tgtUrl, String cachePath = null, Map opts = [:]) {
		log.debug("uploadImage cloudFile: ${cloudFile?.name} tgt: ${tgtUrl} cachePath: ${cachePath}")
		def rtn = [success: false]
		def usingCache = false
		def sourceStream
		Long totalCount
		try {

			sourceStream = cloudFile.inputStream
			totalCount = cloudFile.getContentLength()

			opts.isTarGz = cloudFile.getName().endsWith('.tar.gz')
			opts.isXz = XZUtils.isCompressedFilename(cloudFile.getName())
			if(opts.isXz) {
				def xzStream = new XZCompressorInputStream(sourceStream)
				totalCount = 0
				int len
				byte[] buffer = new byte[102400]
				while((len = xzStream.read(buffer)) != -1) {
					if(len >= 0) {
						totalCount += len
					}
				}
				xzStream.close()
				def cacheFile = new File(cachePath, cloudFile.name)
				sourceStream = cacheFile.newInputStream()
			} else if(opts.isTarGz) {
				// .tar.gz file, get the real size of the disk image from the tar metadata. Requires a new input stream
				// to read the headers of the VHD.
				TarArchiveInputStream tarStream = new TarArchiveInputStream(new GZIPInputStream(cloudFile.inputStream))
				totalCount = VhdUtility.extractVhdDiskSize(tarStream)
				log.debug("uploadImage, TarGz contentLength: ${totalCount}")
			}
			rtn = uploadImage(cloudFile.getName(), sourceStream, totalCount, tgtUrl, opts)
		} catch(ex) {
			log.error("uploadImage cloudFile error: ${ex}", ex)
		} finally {
			try {
				sourceStream?.close()
			} catch(e) {
			}
		}
		return rtn
	}

	static uploadImage(String fileName, InputStream sourceStream, Long contentLength, String tgtUrl, Map opts = [:]) {
		log.debug("uploadImage: stream: ${contentLength} :: ${tgtUrl} :: ${opts}")
		HttpApiClient apiClient = new HttpApiClient()
		InputStream uploadStream
		def rtn = [success: false]
		try {
			log.debug("uploadImage tgtUrl: ${tgtUrl}, contentLength: ${contentLength}, opts.isTarGz: ${opts.isTarGz}, opts.isXz: ${opts.isXz}")
			int streamBufferSize = 16384
			BufferedInputStream bufferedStream
			if(opts.isTarGz == true) {
				TarArchiveInputStream tarStream = new TarArchiveInputStream(new GZIPInputStream(sourceStream))
				tarStream.getNextTarEntry()
				bufferedStream = new BufferedInputStream(tarStream, streamBufferSize)
			} else if(opts.isXz) {
				bufferedStream = new BufferedInputStream(new XZCompressorInputStream(sourceStream), streamBufferSize)
			} else {
				bufferedStream = new BufferedInputStream(sourceStream, streamBufferSize)
			}
			try {
				uploadStream = new ProgressInputStream(bufferedStream, contentLength, 1, 0, "uploadImage ${fileName} progressStream")
			} catch (IllegalAccessError ignored) {
				// ProgressInputStream is a private class in plugin core 1.1.6 and older
				uploadStream = bufferedStream
			}

			// if the content length is adjusted, we'll need to resize the disk
			log.info("Resize attempt: contentLength: ${contentLength} -- diskSize: ${opts.diskSize} -- ${contentLength > opts.diskSize} ${contentLength?.toLong() > opts.diskSize?.toLong()}")
			if(opts.vdi && contentLength > minDiskImageSize && contentLength?.toLong() > opts.diskSize?.toLong()) {
				opts.vdi.resize(opts.connection, contentLength)
			}

			HttpApiClient.RequestOptions requestOptions = new HttpApiClient.RequestOptions(
				body: uploadStream,
				contentLength: contentLength,
				connectionTimeout: -1,
				headers: ['Content-Type': 'application/octet-stream']
			)
			ServiceResponse<CloseableHttpResponse> uploadResponse = apiClient.callStreamApi(tgtUrl, null, opts.authConfig?.username, opts.authConfig?.password, requestOptions, "PUT")

			log.info("uploadImage: stream api call success: ${uploadResponse.success}")
			if(uploadResponse.success == true) {
				rtn.success = true
			} else {
				rtn.success = false
				log.warn("Upload Image Error HTTP: ${uploadResponse.errorCode}")
				rtn.msg = "Upload Image Error HTTP: ${uploadResponse.errorCode}"
			}
		} catch(e) {
			log.error("uploadImage From Stream error: ${e} - Offset ${uploadStream?.getOffset()}", e)
		} finally {
			apiClient?.shutdownClient()
			uploadStream?.close()
		}
		return rtn
	}

	static downloadImage(opts, srcUrl, targetStream) {
		log.info("downloadImage")
		CloseableHttpResponse httpResponse
		HttpApiClient httpClient
		def rtn = [success: false, ovfFiles: []]
		try {
			httpClient = new HttpApiClient()
			def requetOptions = new HttpApiClient.RequestOptions()
			def response = httpClient.callStreamApi(srcUrl, null, opts.authConfig.username, opts.authConfig.password, requetOptions, "GET")
			httpResponse = response.data
			def responseBody = httpResponse.getEntity()
			rtn.contentLength = responseBody.getContentLength()
			if(rtn.contentLength < 0) rtn.contentLength = 0
			def vmInputStream = new ProgressInputStream(new BufferedInputStream(responseBody.getContent(), 64 * 1024), rtn.contentLength, null, null)
			writeStreamToOut(vmInputStream, targetStream)
			targetStream.flush()

			rtn.success = true
		} catch(e) {
			log.error("downloadImage From Stream error: ${e}", e)
		} finally {
			try {
				httpResponse?.close()
				httpClient?.shutdownClient()
			} catch(Exception ex3) {
				log.error("downloadImage target Stream close error, {}", ex3)
			}
		}
		return rtn
	}

	static void writeStreamToOut(InputStream inputStream, OutputStream out) {
		byte[] buffer = new byte[102400]
		int len
		while((len = inputStream.read(buffer)) != -1) {
			out.write(buffer, 0, len)
		}
	}

	static archiveImage(opts, srcUrl, targetFile, fileSize = 0, progressCallback = null) {
		log.info("downloadImage: src: ${srcUrl}")
		CloseableHttpResponse httpResponse
		HttpApiClient httpClient
		def rtn = [success: false, ovfFiles: []]
		try {
			httpClient = new HttpApiClient()
			def requetOptions = new HttpApiClient.RequestOptions()
			def response = httpClient.callStreamApi(srcUrl, null, opts.authConfig.username, opts.authConfig.password, requetOptions, "GET")
			httpResponse = response.data
			def responseBody = httpResponse.getEntity()
			rtn.contentLength = responseBody.getContentLength()
			if(rtn.contentLength < 0 && fileSize > 0) rtn.contentLength = fileSize
			def vmInputStream = new ProgressInputStream(new BufferedInputStream(responseBody.getContent(), 16384), rtn.contentLength, 1, 0)
			vmInputStream.progressCallback = progressCallback
			targetFile.setInputStream(vmInputStream)
			targetFile.save()
			rtn.success = true
		} catch(e) {
			log.error("downloadImage From Stream error: ${e}", e)
		} finally {
			httpResponse?.close()
			httpClient?.shutdownClient()
		}
		return rtn
	}

	static getXenConnectionSession(Map config) {
		def rtn = [success: false, invalidLogin: false]
		try {
			def urlPrefix = config.isSecure == true ? 'https://' : 'http://'
			rtn.connection = new Connection(new URL(urlPrefix + config.hostname))
			rtn.session = Session.loginWithPassword(rtn.connection, config.username, config.password, config.apiVersion)
			rtn.connectionName = config.hostname
			rtn.success = true
		} catch(e) {
			log.error("getXenConnectionSession error: ${e}", e)
			try {
				rtn.invalidLogin = e.shortDescription?.contains('credentials')
			} catch(ignore) {
				//ignore
			}

		}
		return rtn
	}

	static getXenApiUrl(cloud, forceSecure = false) {
		def apiHost = getXenApiHost(cloud)
		def urlPrefix = (forceSecure == true || apiHost.isSecure == true) ? 'https://' : 'http://'
		return urlPrefix + apiHost.address
	}

	static getXenApiHost(Cloud cloud) {
		def rtn = [address: null, isSecure: false]
		def config = cloud.configMap

		def initialApiUrl = config.apiUrl
		if(config.masterAddress) rtn.address = config.masterAddress else if(config.apiUrl) rtn.address = config.apiUrl
		//strip out stuff
		if(rtn.address) {
			if(rtn.address.startsWith('http')) {
				rtn.isSecure = rtn.address.indexOf('https') == 0
				def apiUrlObj = new URL(rtn.address)
				rtn.address = apiUrlObj.getHost()
			} else if(initialApiUrl.startsWith('http')) {
				rtn.isSecure = initialApiUrl.indexOf('https') == 0
			}
			//port
			if(config.apiPort?.length() > 0 && config.apiPort != '80' && config.apiPort != '443') rtn.address = rtn.address + ':' + config.apiPort
			return rtn
		}
		throw new Exception('no xen apiUrl specified')
	}

	static getXenUsername(zone) {
		def rtn = zone.credentialData?.username ?: zone.getConfigProperty('username')
		if(!rtn) {
			throw new Exception('no xen username specified')
		}
		return rtn
	}

	static getXenPassword(zone) {
		def rtn = zone.credentialData?.password ?: zone.getConfigProperty('password')
		if(!rtn) {
			throw new Exception('no xen password specified')
		}
		return rtn
	}

	static getXenApiVersion(obj) {
		return obj.getConfigProperty('apiVersion') ?: APIVersion.latest().toString()
	}

	static osTypeTemplates = ['ubuntu.14.04.64': 'Ubuntu Trusty Tahr 14.04']

	static isValidIpv6Address(String address) {
		// validate the ipv6 address is an ipv6 address. There is no separate validation for ipv6 addresses, so validate that its not an ipv4 address and it is a valid ip address
		return address && NetworkUtility.validateIpAddr(address, false) == false && NetworkUtility.validateIpAddr(address, true) == true
	}

	static getVmSyncVolumes(Map authConfig, vm) {
		def rtn = []
		try {
			def config = getXenConnectionSession(authConfig)
			def vbdList = vm.getVBDs(config.connection)
			vbdList?.each { vbd ->
				if(vbd.getType(config.connection) == com.xensource.xenapi.Types.VbdType.DISK) {
					rtn << getVmVolumeInfo(config, vbd)
				}
			}
			rtn = rtn.sort { it.displayOrder }
		} catch(e) {
			log.error("getVmVolumes error: ${e}", e)
		}
		return rtn
	}

	static buildSyncLists(existingItems, masterItems, matchExistingToMasterFunc) {
		// log.debug "buildSyncLists: ${existingItems}, ${masterItems}"
		def rtn = [addList: [], updateList: [], removeList: []]
		try {
			existingItems?.each { existing ->
				def matches = masterItems?.findAll { matchExistingToMasterFunc(existing, it) }
				if(matches?.size() > 0) {
					matches?.each { match -> rtn.updateList << [existingItem: existing, masterItem: match]
					}
				} else {
					rtn.removeList << existing
				}
			}
			masterItems?.each { masterItem ->
				def match = rtn?.updateList?.find {
					it.masterItem == masterItem
				}
				if(!match) {
					rtn.addList << masterItem
				}
			}
		} catch(e) {
			log.error "buildSyncLists error: ${e}", e
		}
		return rtn
	}

	static getNameFromFile(inputStream, cloudFileName) {
		def fileVal = null
		def tarStream = new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(inputStream)
		String matchedFileName = null
		TarArchiveEntry entry
		while((entry = tarStream.getNextTarEntry()) != null) {
			if(!entry.isDirectory() && entry.name == "ova.xml") {
				matchedFileName = entry.name
				break
			}
		}
		byte[] buf = new byte[(int) entry.getSize()]
		int readed = IOUtils.readFully(tarStream, buf)
		if(readed != buf.length) {
			throw new RuntimeException("Read bytes count and entry size differ")
		}
		String string = new String(buf, StandardCharsets.UTF_8)
		if(matchedFileName) {
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance()
			DocumentBuilder db = dbf.newDocumentBuilder()
			InputSource is = new InputSource(new StringReader(string))
			Document domObject = db.parse(is)
			domObject.getDocumentElement().normalize()
			NodeList list = domObject.getDocumentElement().getElementsByTagName("name")
			for(int i = 0; i < list.getLength(); i++) {
				Node node = list.item(i)
				if(node.getNodeType() == Node.ELEMENT_NODE && node.getTextContent() == "name_label") {
					def nodeVal = node.getNextSibling().getTextContent()
					if(nodeVal && nodeVal.contains(cloudFileName)) {
						fileVal = nodeVal
						break
					}
				}
			}
		}
		return fileVal
	}
}
