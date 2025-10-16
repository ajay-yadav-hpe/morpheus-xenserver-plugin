package com.morpheusdata.xen

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.Backup
import com.morpheusdata.model.BackupResult
import com.morpheusdata.model.BackupRestore
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Instance
import com.morpheusdata.model.Workload
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.xen.support.TestSpecBase
import com.morpheusdata.xen.util.XenComputeUtility
import io.reactivex.rxjava3.core.Maybe
import spock.lang.Subject

class XenserverBackupRestoreProviderSpec extends TestSpecBase {

	XenserverPlugin plugin = GroovyMock(XenserverPlugin)
	MorpheusContext context = GroovyMock(MorpheusContext)

	@Subject
	XenserverBackupRestoreProvider provider = new XenserverBackupRestoreProvider(plugin, context)

	def "configure and validate restore return success"() {
		expect:
		provider.configureRestoreBackup(new BackupResult(), [:], [:]).success
		provider.validateRestoreBackup(new BackupResult(), [:]).success
	}

	def "getRestoreOptions returns success"() {
		expect:
		provider.getRestoreOptions(new Backup(), [:]).success
	}

	def "getBackupRestoreInstanceConfig echoes config"() {
		given:
		BackupResult result = new BackupResult()
		Instance instance = new Instance()
		Map config = [name: 'restored']

		when:
		ServiceResponse response = provider.getBackupRestoreInstanceConfig(result, instance, config, [:])

		then:
		response.success
		response.data == config
	}

	def "restoreBackup success triggers start vm"() {
		given:
		BackupRestore restore = new BackupRestore(status: BackupResult.Status.START_REQUESTED)
		Backup backup = new Backup(id: 1)
		BackupResult result = new BackupResult(snapshotId: 'snap-1', backup: backup)
		result.setConfigProperty('vmId', 'vm-1')
		Workload workload = new Workload(server: new ComputeServer(cloud: newCloud(), externalId: 'vm-1'))

		and:
		def morpheusContext = GroovyMock(MorpheusContext)
		stubServices(morpheusContext, [
			backup      : [get: { Long id -> backup }],
			workload    : [get: { Long id -> workload }],
			computeServer: [save: { ComputeServer server -> server }],
			cloud       : [save: { Cloud cloud -> cloud }]
		])
		stubAsync(morpheusContext, [workload: [get: { Object... args -> Maybe.just(workload) }]])
		plugin.morpheus >> morpheusContext
		plugin.getAuthConfig(_) >> [hostname: 'host']

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.restoreServer(_, 'snap-1') >> [success: true]
		XenComputeUtility.startVm(_, 'vm-1') >> [success: true]

		when:
		ServiceResponse response = provider.restoreBackup(restore, result, backup, [containerId: 1L])

		then:
			response.success
			response.data.backupRestore.status.toString() == BackupResult.Status.SUCCEEDED.toString()
	}

	def "restoreBackup failure captures message"() {
		given:
		BackupRestore restore = new BackupRestore()
		Backup backup = new Backup(id: 1)
		BackupResult result = new BackupResult(snapshotId: 'snap-1', backup: backup)
		result.setConfigProperty('vmId', 'vm-1')
		Workload workload = new Workload(server: new ComputeServer(cloud: newCloud(), externalId: 'vm-1'))

		def morpheusContext = GroovyMock(MorpheusContext)
		stubServices(morpheusContext, [
			backup  : [get: { Long id -> backup }],
			workload: [get: { Long id -> workload }]
		])
		stubAsync(morpheusContext, [workload: [get: { Object... args -> Maybe.just(workload) }]])

		plugin.morpheus >> morpheusContext
		plugin.getAuthConfig(_) >> [hostname: 'host']

		GroovySpy(XenComputeUtility, global: true)
		XenComputeUtility.restoreServer(_, 'snap-1') >> [success: false]

		when:
		ServiceResponse response = provider.restoreBackup(restore, result, backup, [containerId: 1L])

		then:
		!response.success
	}
}
