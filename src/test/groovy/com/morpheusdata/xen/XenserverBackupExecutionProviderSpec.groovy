package com.morpheusdata.xen

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.Backup
import com.morpheusdata.model.BackupResult
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Workload
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.xen.support.TestSpecBase
import spock.lang.Subject

class XenserverBackupExecutionProviderSpec extends TestSpecBase {

	XenserverPlugin plugin
	MorpheusContext context

	@Subject
	XenserverBackupExecutionProvider provider

	def setup() {
		plugin = GroovyMock(XenserverPlugin)
		context = Stub(MorpheusContext)
		provider = new XenserverBackupExecutionProvider(plugin, context)
		provider.@morpheusContext = context
	}

	def "morpheus accessor returns context"() {
		expect:
		provider.@morpheusContext.is(context)
	}

	def "configure backup returns success"() {
		given:
		Backup backup = new Backup()

		when:
		ServiceResponse response = provider.configureBackup(backup, [:], [:])

		then:
		response.success
		response.data.is(backup)
	}

	def "validate backup returns success"() {
		given:
		Backup backup = new Backup()

		when:
		ServiceResponse response = provider.validateBackup(backup, [:], [:])

		then:
		response.success
		response.data.is(backup)
	}

	def "create and delete backup return success"() {
		expect:
		provider.createBackup(new Backup(), [:]).success
		provider.deleteBackup(new Backup(), [:]).success
	}

	def "prepare hooks yield success"() {
		expect:
		provider.prepareExecuteBackup(new Backup(), [:]).success
		provider.prepareBackupResult(new BackupResult(), [:]).success
	}

	def "refresh backup result returns success response"() {
		given:
		BackupResult result = new BackupResult()

		when:
		ServiceResponse response = provider.refreshBackupResult(result)

		then:
		response.success
		response.data.backupResult.is(result)
	}

	def "cancel backup returns success"() {
		expect:
		provider.cancelBackup(new BackupResult(), [:]).success
	}

	def "delete backup result without cloud simply returns default"() {
		given:
		BackupResult backupResult = new BackupResult(backup: new Backup(id: 1), snapshotId: null)
		context.services >> [backup: [get: { Long id -> new Backup(id: id) }], workload: [get: { Long id -> null }]]

		when:
		ServiceResponse response = provider.deleteBackupResult(backupResult, [:])

		then:
		response != null
	}
}
