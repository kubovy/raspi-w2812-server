package com.poterion.raspi.w2812.serial

/**
 * @author Jan Kubovy &lt;jan@kubovy.eu&gt;
 */
enum class SerialEvent {
	BREAK,
	/** Clear To Send (CTS) - DCE is ready to accept data from the DTE. */
	CLEAR_TO_SEND,
	/** Data Set Ready (DSR) - DCE is ready to receive and send data. */
	DATA_SET_READY,
	ERROR,
	RING,
	/** Receive Line Signal Detect (RLSD) */
	RLSD
}