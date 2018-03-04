# Raspi W2812 Server

Implements communication protocol between the [Raspi W2812 light](https://blog.kubovy.eu/2018/02/11/status-light-with-raspberry-pi-zero-and-w2812-led-strip/)
and other devices, e.g, the [Monitor](https://blog.kubovy.eu/2018/02/18/service-monitor-application/) or the 
[Raspi W2812 App](http://blog.kubovy.eu/2018/03/04/mobile-app-for-raspi-w2812-light-strip/). 
 
It reads the input from serial device, e.g., ``/dev/ttyGS0`` or ``/dev/rfcomm0``, which is the USB or Bluetooth 
respectively, line by line. If a line containing "``STX``" is seen it starts listening. In the listening mode 
all new lines are stored until a line containing "``ETX``" is seen. Then it calculates a ``CRC32`` checksum and
sends it back in the following format "``ACK:[CRC32]``". 

If the sender receives this message and the ``CRC32`` matches the one it calculated itself it sends back an 
"``ACK``" message. If the receiver receives this acknowledgment it saves all what was received in the last 
payload in a configured file.

Starting the application: ``java -jar [JAR] [FILE] [DEVICE]``