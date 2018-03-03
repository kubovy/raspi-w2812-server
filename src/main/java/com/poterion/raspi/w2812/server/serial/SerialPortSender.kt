package com.poterion.raspi.w2812.server.serial

import jssc.SerialPort
import jssc.SerialPortEvent
import jssc.SerialPortEventListener
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.zip.CRC32

/**
 * @author Jan Kubovy &lt;jan@kubovy.eu&gt;
 */
class SerialPortSender(portName: String) : SerialPortEventListener {
	companion object {
		val LOGGER: Logger = LoggerFactory.getLogger(SerialPortSender::class.java)
	}

	private val serialPort: SerialPort = SerialPort(portName)
	private var rest = ""
	private var checksum: Long? = null
	private var listenerAdded = false

	fun sendMessage(message: String): Boolean {
		serialPort.openPort()
		serialPort.setParams(SerialPort.BAUDRATE_115200,
				SerialPort.DATABITS_8,
				SerialPort.STOPBITS_1,
				SerialPort.PARITY_NONE)//, true, false)
		//serialPort.flowControlMode = SerialPort.FLOWCONTROL_NONE
		var iterations = 0
		var success: Boolean
		do {
			LOGGER.debug("[${serialPort.portName}] Sending tryout ${iterations + 1}")
			val checksum = CRC32().apply {
				update(message.replace("[\\n\\r]".toRegex(), "").toByteArray())
			}.value
			setModeWrite()
			//serialPort.purgePort(SerialPort.PURGE_RXCLEAR or SerialPort.PURGE_TXCLEAR)
			serialPort.writeString("${ETX}\n\r")
			serialPort.writeString("${ETX}\n\r")
			serialPort.writeString("${ETX}\n\r")
			serialPort.writeString("${ETX}\n\r")
			serialPort.writeString("${ETX}\n\r")
			serialPort.writeString("${ETX}\n\r")
			serialPort.writeString("${ETX}\n\r")
			serialPort.writeString("${ETX}\n\r")
			serialPort.writeString("${STX}\n\r")
			serialPort.writeString("${message}\n\r")
			serialPort.writeString("${ETX}\n\r")
			//serialPort.purgePort(SerialPort.PURGE_RXCLEAR)

			setModeRead()
			//serialPort.addEventListener(SerialPortConfirmator(serialPort, { this.checksum = it }))
			val sent = System.currentTimeMillis()
			while (this.checksum == null && System.currentTimeMillis() - sent < 5_000L) {
				Thread.sleep(500L)
			}
			//serialPort.removeEventListener()
			setModeWrite()

			LOGGER.info("[${serialPort.portName}] Iteration ${iterations}: Received: ${this.checksum}, Calculated: ${checksum}")
			success = checksum == this.checksum
			this.checksum = null
			iterations++
		} while (!success && iterations < 5)
		//serialPort.purgePort(SerialPort.PURGE_RXCLEAR or SerialPort.PURGE_TXCLEAR)
		setModeWrite()
		serialPort.writeString("${ACK}\n\r${ACK}\n\r${ACK}\n\r")
		LOGGER.info("[${serialPort.portName}] Sending ${if (success) "SUCCESSFUL" else "FAILED"} after ${iterations} iterations")
		//serialPort.purgePort(SerialPort.PURGE_RXCLEAR)
		serialPort.closePort()
		return success
	}

	override fun serialEvent(e: SerialPortEvent?) {
		if (e != null && e.isRXCHAR && e.eventValue > 0) {

			val lines = serialPort.readBytes(e.eventValue)
					.toString(Charsets.UTF_8)
					.split("[\\n\\r]+".toRegex())
			LOGGER.debug("[${serialPort.portName}] Received ${e.eventValue} bytes, ${lines.size} lines:${lines.joinToString("\n\t- ", "\n\t- ")}")

			lines.mapIndexed { index, line ->
				when (index) {
					0 -> "${rest}${line}".also { rest = "" }
					lines.lastIndex -> {
						rest = line
						""
					}
					else -> line
				}
			}.filter { it.startsWith("${ACK}:") }
					.map { it.substring(ACK.length + 1) }
					.mapNotNull { it.toLongOrNull() }
					.forEach {
						LOGGER.debug("[${serialPort.portName}] Checksum: \"${it}\"")
						this.checksum = it
					}
		}
	}

	private fun setModeWrite() {
		serialPort.purgePort(SerialPort.PURGE_TXCLEAR)
		//serialPort.purgePort(SerialPort.PURGE_TXCLEAR or SerialPort.PURGE_RXCLEAR)
		if (listenerAdded) serialPort.removeEventListener()
		listenerAdded = false
		//serialPort.setRTS(true) // Ready to send
		//serialPort.setDTR(false) // Data terminal ready
	}

	private fun setModeRead() {
		//serialPort.purgePort(SerialPort.PURGE_TXCLEAR or SerialPort.PURGE_RXCLEAR)
		if (!listenerAdded) serialPort.addEventListener(this)
		listenerAdded = true
		//serialPort.setRTS(false) // Ready to send
		//serialPort.setDTR(true) // Data terminal ready
	}
}