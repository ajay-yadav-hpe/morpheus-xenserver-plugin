package com.morpheusdata.xen.util

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import spock.lang.Specification
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Arrays

class VhdUtilitySpec extends Specification {

	def "extractVhdDiskSize reads header value when present"() {
		given:
		byte[] vhdBytes = new byte[512]
		putLong(vhdBytes, 40, 2048L)
		putInt(vhdBytes, 60, 3) // dynamic disk
		TarArchiveInputStream tarStream = buildTarStream('disk.vhd', vhdBytes)

		when:
		long size = VhdUtility.extractVhdDiskSize(tarStream)

		then:
		size == 2048L
	}

	def "extractVhdDiskSize falls back to tar entry size"() {
		given:
		byte[] vhdBytes = new byte[512]
		TarArchiveInputStream tarStream = buildTarStream('disk.vhd', vhdBytes, 4096L)

		when:
		long size = VhdUtility.extractVhdDiskSize(tarStream)

		then:
		size == 4096L
	}

	def "extractVhdDiskSize throws when archive empty"() {
		when:
		VhdUtility.extractVhdDiskSize(new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(new ByteArrayInputStream(new byte[0])))

		then:
		thrown(IOException)
	}

	def "extractVhdDiskSize reads header value for fixed disk type"() {
		given:
		byte[] vhdBytes = new byte[512]
		putLong(vhdBytes, 40, 1024L)
		putInt(vhdBytes, 60, 2) // fixed disk type
		TarArchiveInputStream tarStream = buildTarStream('disk.vhd', vhdBytes)

		when:
		long size = VhdUtility.extractVhdDiskSize(tarStream)

		then:
		size == 1024L
	}

	def "extractVhdDiskSize handles invalid disk type gracefully"() {
		given:
		byte[] vhdBytes = new byte[512]
		putLong(vhdBytes, 40, 2048L)
		putInt(vhdBytes, 60, 99) // invalid disk type
		TarArchiveInputStream tarStream = buildTarStream('disk.vhd', vhdBytes, 4096L)

		when:
		long size = VhdUtility.extractVhdDiskSize(tarStream)

		then:
		size == 4096L // falls back to tar entry size
	}

	private static TarArchiveInputStream buildTarStream(String name, byte[] data, long entrySize = -1L) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream()
		TarArchiveOutputStream tarOutput = new TarArchiveOutputStream(baos)
		byte[] payload = data
		if (entrySize > data.length) {
			payload = Arrays.copyOf(data, entrySize.intValue())
		}
		TarArchiveEntry entry = new TarArchiveEntry(name)
		entry.setSize(payload.length)
		tarOutput.putArchiveEntry(entry)
		tarOutput.write(payload)
		tarOutput.closeArchiveEntry()
		tarOutput.finish()
		tarOutput.close()
		return new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(new ByteArrayInputStream(baos.toByteArray()))
	}

	private static void putLong(byte[] target, int offset, long value) {
		byte[] bytes = ByteBuffer.allocate(8).putLong(value).array()
		System.arraycopy(bytes, 0, target, offset, 8)
	}

	private static void putInt(byte[] target, int offset, int value) {
		byte[] bytes = ByteBuffer.allocate(4).putInt(value).array()
		System.arraycopy(bytes, 0, target, offset, 4)
	}
}
