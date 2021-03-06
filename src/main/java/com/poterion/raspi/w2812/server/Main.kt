package com.poterion.raspi.w2812.server

import com.poterion.raspi.w2812.server.serial.SerialPortReader
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.io.FileWriter

/**
 * @author Jan Kubovy &lt;jan@kubovy.eu&gt;
 */
class Main {
	companion object {
		private val LOGGER: Logger = LoggerFactory.getLogger(Main::class.java)

		private val logTag
			get() = "${Thread.currentThread().name}(${Thread.currentThread().id})"

		@JvmStatic
		fun main(args: Array<String>) {
			var outFile: String? = null
			args.forEachIndexed { index, param ->
				if (index == 0) {
					outFile = param
					LOGGER.info("[${logTag}] Messages will be written to: ${outFile}")
				} else {
					LOGGER.info("[${logTag}] Listening on: ${param}")
					val serialPortReader = SerialPortReader(param) { message ->
						outFile?.let { FileWriter(it) }
								?.let { BufferedWriter(it) }
								?.use { it.write(message.joinToString("\n").trim()) }
					}
					Thread(serialPortReader).start()
				}
			}

			while (true) {
				LOGGER.debug("[${logTag}] Looping")
				Thread.sleep(1_000L)
			}
		}
	}
}