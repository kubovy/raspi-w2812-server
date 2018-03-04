package com.poterion.raspi.w2812.server.serial

import jssc.SerialPort
import jssc.SerialPortEvent
import jssc.SerialPortEventListener
import jssc.SerialPortException
import org.slf4j.LoggerFactory
import java.util.zip.CRC32


/**
 * @author Jan Kubovy &lt;jan@kubovy.eu&gt;
 */
class SerialPortReader(portName: String,
					   private val onEvent: (SerialEvent, Boolean) -> Boolean = { _, _ -> false },
					   private val onData: (List<String>) -> Unit) :
		SerialPortEventListener, Runnable {
	companion object {
		private val LOGGER = LoggerFactory.getLogger(SerialPortReader::class.java)
	}

	private enum class State {
		IDLE, RECEIVING, WAITING, PROCESSING//, RESTARTING
	}

	private val logTag
		get() = "${serialPort.portName}:${Thread.currentThread().name}(${Thread.currentThread().id})"
	private val serialPort = SerialPort(portName)
	private var rest = ""
	private var checksum = ""
	private var state = State.IDLE
	private val buffer = mutableListOf<String>()
	private var listenerAdded = false
	private var interrupted = false
	private var lastCTS = 0 // The CTS (clear-to-send) signal changed state.
	private var lastDSR = 0 // The DSR (data-set-ready) signal changed state.
	private var lastRING = 0 // A ring indicator was detected.
	private var lastRLDS = 0 // The RLSD (receive-line-signal-detect) signal changed state.

	override fun run() {
		while (!interrupted) {
			if (!serialPort.isOpened) startPort()

			val (cts, dsr, ring, rlds) = serialPort.linesStatus
			LOGGER.debug("[${logTag}] Looping - open: ${serialPort.isOpened}, CTS: ${lastCTS}->${cts}, DSR: ${lastDSR}->${dsr}, RING: ${lastRING}->${ring}, RLDS: ${lastRLDS}->${rlds}")
			if (lastCTS == 1 && cts == 0 || lastDSR == 1 && dsr == 0 || lastRLDS == 1 && rlds == 0) restartPort()
			lastCTS = cts
			lastDSR = dsr
			lastRING = ring
			lastRLDS = rlds

			Thread.sleep(1_000L)
		}
	}

	override fun serialEvent(e: SerialPortEvent?) {
		if (e != null) when {
			e.isRXCHAR -> if (e.eventValue > 0) try { // Received Data - Carries data from DCE to DTE.
				val lines = serialPort.readBytes(e.eventValue)
						.toString(Charsets.UTF_8)
						.split("[\\n\\r]+".toRegex())
				LOGGER.debug("[${logTag}] Received ${e.eventValue} bytes, ${lines.size} lines:${lines.joinToString("\n\t- ", "\n\t- ")}")

				lines.forEachIndexed { index, line ->
					when (index) {
						0 -> "${rest}${line}".also { rest = "" }
						lines.lastIndex -> {
							rest = line
							""
						}
						else -> line
					}.also {
						LOGGER.debug("[${logTag}] Processing: \"${it}\"")
						try {
							processLine(it)
						} catch (e: SerialPortException) {
							LOGGER.error(e.message, e)
						}
					}
				}

			} catch (e: SerialPortException) {
				LOGGER.error(e.message, e)
			}
			e.isBREAK -> { // If BREAK line has changed state
				LOGGER.debug("[${logTag}] BREAK - ${if (e.eventValue == 1) "ON" else "OFF"}")
				onEvent.invoke(SerialEvent.BREAK, e.eventValue == 1)
			}
			e.isCTS -> { // If CTS line has changed state
				LOGGER.debug("[${logTag}] CTS - ${if (e.eventValue == 1) "ON" else "OFF"}")
				onEvent.invoke(SerialEvent.CLEAR_TO_SEND, e.eventValue == 1)
			}
			e.isDSR -> {// If DSR line has changed state
				LOGGER.debug("[${logTag}] DSR - ${if (e.eventValue == 1) "ON" else "OFF"}")
				onEvent.invoke(SerialEvent.DATA_SET_READY, e.eventValue == 1)
			}
			e.isERR -> { // If CTS line has changed state
				LOGGER.debug("[${logTag}] ERR - ${if (e.eventValue == 1) "ON" else "OFF"}")
				onEvent.invoke(SerialEvent.ERROR, e.eventValue == 1)
			}
			e.isRING -> { // If CTS line has changed state
				LOGGER.debug("[${logTag}] RING - ${if (e.eventValue == 1) "ON" else "OFF"}")
			}
			e.isRLSD -> {// If RLSD line has changed state
				LOGGER.debug("[${logTag}] RLSD - ${if (e.eventValue == 1) "ON" else "OFF"}")
				onEvent.invoke(SerialEvent.RLSD, e.eventValue == 1)
			}
		}
	}

	private fun processLine(line: String): Boolean = when (state) {
		State.IDLE -> {
			when (line) {
				ENQ -> {
					LOGGER.debug("[${logTag}] ${line}: ${state} -> ${state}")
					setModeWrite()
					serialPort.writeString("POTERION IOT:{\"name\": \"Raspi W2812 Server\", \"features\": [\"light-strip\", \"usb\", \"bluetooth\"], \"properties\": [\"24x2+2\"]}\n\r")
					setModeRead()
					true
				}
				STX -> {
					LOGGER.debug("[${logTag}] ${line}: ${state} -> RECEIVING")
					buffer.clear()
					state = State.RECEIVING
					true
				}
				else -> {
					LOGGER.debug("[${logTag}] ${line}: ${state} -> ${state} (Ignoring)")
					false
				}
			}
		}
		State.RECEIVING -> when (line) {
			ETX -> {
				LOGGER.debug("[${logTag}] ${line}: ${state} -> PROCESSING")
				state = State.PROCESSING
				val crc = CRC32()
				buffer.forEach { crc.update(it.toByteArray()) }
				checksum = crc.value.toString()

				LOGGER.debug("[${logTag}] ${line}: ${state} -> WAITING, CRC: ${checksum}")
				setModeWrite()
				serialPort.writeString("${ACK}:${checksum}\n\r")
				setModeRead()
				state = State.WAITING
				true
			}
			else -> {
				LOGGER.debug("[${logTag}] ${line}: ${state} -> ${state} - Added to buffer")
				buffer.add(line)
			}
		}
		State.WAITING -> when (line) {
			ACK -> {
				LOGGER.debug("[${logTag}] ${line}: ${state} -> IDLE")
				onData.invoke(buffer)
				state = State.IDLE
				true
			}
			ETX -> {
				LOGGER.debug("[${logTag}] ${line}: ${state} -> IDLE")
				state = State.IDLE
				true
			}
			else -> {
				LOGGER.debug("[${logTag}] Processing: ${line}: ${state} -> ${state} (Ignoring)")
				false
			}
		}
		else -> when (line) {
			ETX -> {
				LOGGER.debug("[${logTag}] ${line}: ${state} -> IDLE")
				state = State.IDLE
				true
			}
			else -> {
				LOGGER.debug("[${logTag}] Processing: ${line}: ${state} -> ${state} (Ignoring)")
				false
			}
		}
	}

	private fun startPort() {
		try {
			listenerAdded = false
			serialPort.openPort()
			serialPort.setParams(SerialPort.BAUDRATE_115200,
					SerialPort.DATABITS_8,
					SerialPort.STOPBITS_1,
					SerialPort.PARITY_NONE)
			serialPort.eventsMask = SerialPort.MASK_RXCHAR and SerialPort.MASK_CTS and SerialPort.MASK_DSR and SerialPort.MASK_RING and SerialPort.MASK_RLSD
			serialPort.purgePort(SerialPort.PURGE_TXCLEAR or SerialPort.PURGE_RXCLEAR or SerialPort.PURGE_RXABORT or SerialPort.PURGE_TXABORT)
			setModeRead()
		} catch (e: SerialPortException) {
			LOGGER.debug("[${logTag}] ${e.message} - Trying again...: ${state} -> IDLE")
			state = State.IDLE
			Thread.sleep(1_000L)
			if (e.exceptionType != SerialPortException.TYPE_PORT_ALREADY_OPENED) startPort()
		}
	}

	private fun restartPort() = try {
		LOGGER.debug("[${logTag}] >>PORT RESET<<: ${state} -> IDLE")
		if (serialPort.isOpened) {
			serialPort.purgePort(SerialPort.PURGE_TXCLEAR or SerialPort.PURGE_RXCLEAR or SerialPort.PURGE_RXABORT or SerialPort.PURGE_TXABORT)
			serialPort.closePort()
		}
		null
	} catch (e:SerialPortException) {
		LOGGER.debug("[${logTag}] ${e.message} - Trying again...: ${state} -> IDLE")
	} finally {
		state = State.IDLE
		Thread.sleep(500L)
		startPort()
	}

	private fun setModeWrite() {
		if (serialPort.isOpened) try {
			serialPort.purgePort(SerialPort.PURGE_TXCLEAR or SerialPort.PURGE_RXCLEAR)
		} catch (e:SerialPortException) {
			LOGGER.error(e.message, e)
		}
		if (listenerAdded) try {
			serialPort.removeEventListener()
		} catch (e:SerialPortException) {
			LOGGER.error(e.message, e)
		}
		listenerAdded = false
	}

	private fun setModeRead() {
		if (serialPort.isOpened) try {
			serialPort.purgePort(SerialPort.PURGE_TXCLEAR)
		} catch (e:SerialPortException) {
			LOGGER.error(e.message, e)
		}
		if (!listenerAdded) try {
			serialPort.addEventListener(this)
		} catch (e:SerialPortException) {
			LOGGER.error(e.message, e)
		}
		listenerAdded = true
	}
}