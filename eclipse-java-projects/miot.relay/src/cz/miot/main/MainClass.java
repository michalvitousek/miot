package cz.miot.main;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence;

import com.pi4j.io.gpio.GpioController;
import com.pi4j.io.gpio.GpioFactory;
import com.pi4j.io.gpio.GpioPinDigitalOutput;
import com.pi4j.io.gpio.Pin;
import com.pi4j.io.gpio.PinState;
import com.pi4j.io.gpio.RaspiPin;

public class MainClass implements MqttCallback {

	/**
	 * MAIN ENTRY
	 * @param args
	 */
	public static void main(String[] args) {

		// Default settings:
		boolean useGPIO 	= false;
		boolean quietMode 	= false;		
		String topic 		= "";		
		int qos 			= 0;
		String broker 		= "m2m.eclipse.org";
		int port 			= 1883;
		String clientId 	= null;		
		boolean cleanSession = true;			// Non durable subscriptions
		boolean ssl = false;
		String password = null;
		String userName = null;
		int wiringPiPin = -1;

		// Parse the arguments -
		for (int i=0; i<args.length; i++) {
			// Check this is a valid argument
			if (args[i].length() == 2 && args[i].startsWith("-")) {
				char arg = args[i].charAt(1);
				// Handle arguments that take no-value
				switch(arg) {
				case 'h': case '?':	printHelp(); return;
				case 'q': quietMode = true;	continue;
				case 'x': useGPIO = true; continue;
				}

				// Now handle the arguments that take a value and
				// ensure one is specified
				if (i == args.length -1 || args[i+1].charAt(0) == '-') {
					System.out.println("Missing value for argument: "+args[i]);
					printHelp();
					return;
				}
				switch(arg) {
				case 'j': wiringPiPin = Integer.parseInt(args[++i]);break;
				case 't': topic = args[++i];                  		break;				
				case 's': qos = Integer.parseInt(args[++i]);  		break;
				case 'b': broker = args[++i];                 		break;
				case 'p': port = Integer.parseInt(args[++i]); 		break;
				case 'i': clientId = args[++i];				  		break;
				case 'c': cleanSession = Boolean.valueOf(args[++i]).booleanValue();  break;
				case 'k': System.getProperties().put("javax.net.ssl.keyStore", args[++i]); break;
				case 'w': System.getProperties().put("javax.net.ssl.keyStorePassword", args[++i]); break;
				case 'r': System.getProperties().put("javax.net.ssl.trustStore", args[++i]); break;
				case 'v': ssl = Boolean.valueOf(args[++i]).booleanValue(); break;
				case 'u': userName = args[++i];               break;
				case 'z': password = args[++i];               break;
				default:
					System.out.println("Unrecognised argument: " + args[i]);
					printHelp();
					return;
				}
			} else {
				System.out.println("Unrecognised argument: " + args[i]);
				printHelp();
				return;
			}
		}

		// Validate the provided arguments		
		if (qos < 0 || qos > 2) {
			System.out.println("Invalid QoS: "+qos);
			printHelp();
			return;
		}
		if (topic.equals("")) {
			System.out.println("Empty topic");
			printHelp();
			return;
		}
		if (wiringPiPin < 0) {
			System.out.println("Empty GPIO WiringPi pin number");
			printHelp();
			return;
		}

		String protocol = "tcp://";

		if (ssl) {
			protocol = "ssl://";
		}

		String url = protocol + broker + ":" + port;

		if (clientId == null || clientId.equals("")) {
			clientId = "miot.relay_" + UUID.randomUUID().toString();
		}

		// With a valid set of arguments, the real work of
		// driving the client API can begin
		MainClass sampleClient = null;
		try {
			// Create an instance of this class
			sampleClient = new MainClass(url, clientId, cleanSession, quietMode,userName,password,wiringPiPin,useGPIO);
			sampleClient.subscribe(topic,qos);
		} catch(MqttException me) {
			// Display full details of any exception that occurs
			StringBuilder sb = new StringBuilder();
			sb.append("reason " + me.getReasonCode());
			sb.append("msg "+me.getMessage());
			sb.append("loc "+me.getLocalizedMessage());
			sb.append("cause "+me.getCause());
			sb.append("excep "+me);
			log.error(sb.toString(), me);
			System.exit(1);
		}
		
		// Create HOLD file and control it
		File f = new File("./controlFile.cf");
		try {
			f.createNewFile();
		} catch (IOException e) {
			log.error("Create control file error", e);
			System.exit(1);
		}
		while(f.exists()) {
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {				
			}
		}
		try {
			sampleClient.close();
		} catch (MqttException e) {
			log.error("MQTT close error", e);
		}
		log.info("Application stoped!");
		System.exit(0);
	}

	// Private instance variables
	private MqttClient client;
	private String brokerUrl;
	private MqttConnectOptions conOpt;
	private boolean clean;
	private String password;
	private String userName;
	private static final Logger log = LogManager.getLogger();
	private GpioController gpioController;
	private GpioPinDigitalOutput relay;
	private boolean useGPIO;

	/**
	 * Constructor
	 * @param brokerUrl
	 * @param clientId
	 * @param cleanSession
	 * @param quietMode
	 * @param userName
	 * @param password
	 * @throws MqttException
	 */
	public MainClass(String brokerUrl, String clientId, boolean cleanSession, boolean quietMode, String userName, String password, int pinNumber, boolean useGPIO) throws MqttException {
		this.brokerUrl = brokerUrl;
		this.clean 	   = cleanSession;
		this.password = password;
		this.userName = userName;
		this.useGPIO = useGPIO;
		//This sample stores in a temporary directory... where messages temporarily
		// stored until the message has been delivered to the server.
		//..a real application ought to store them somewhere
		// where they are not likely to get deleted or tampered with
		String tmpDir = System.getProperty("java.io.tmpdir");
		MqttDefaultFilePersistence dataStore = new MqttDefaultFilePersistence(tmpDir);

		try {
			// Construct the connection options object that contains connection parameters
			// such as cleanSession and LWT
			conOpt = new MqttConnectOptions();
			conOpt.setCleanSession(clean);
			if(password != null ) {
				conOpt.setPassword(this.password.toCharArray());
			}
			if(userName != null) {
				conOpt.setUserName(this.userName);
			}

			// Construct an MQTT blocking mode client
			client = new MqttClient(this.brokerUrl, clientId, dataStore);

			// Set this wrapper as the callback handler
			client.setCallback(this);

			// Set relay GPIO output pin
			if (useGPIO) {
				this.gpioController = GpioFactory.getInstance();
				Pin pin = RaspiPin.getPinByAddress(pinNumber);
				this.relay = gpioController.provisionDigitalOutputPin(pin);
			} else {
				log.info("Simulated GPIO Setting for pin: " + pinNumber);
			}

		} catch (MqttException e) {
			e.printStackTrace();
			log.error("Unable to set up MQTT client", e);
			System.exit(1);
		}
	}

	/**
	 * Subscribe to a topic on an MQTT server
	 * Once subscribed this method waits for the messages to arrive from the server
	 * that match the subscription. It continues listening for messages     
	 * @param topicName to subscribe to (can be wild carded)
	 * @param qos the maximum quality of service to receive messages at for this subscription
	 * @throws MqttException
	 */
	public void subscribe(String topicName, int qos) throws MqttException {

		// Connect to the MQTT server
		client.connect(conOpt);
		log.info("Connected to " + brokerUrl + " with client ID " + client.getClientId());

		// Subscribe to the requested topic
		// The QoS specified is the maximum level that messages will be sent to the client at.
		// For instance if QoS 1 is specified, any messages originally published at QoS 2 will
		// be downgraded to 1 when delivering to the client but messages published at 1 and 0
		// will be received at the same level they were published at.
		log.info("Subscribing to topic \"" + topicName + "\" qos " + qos);
		client.subscribe(topicName, qos);
	}

	/**
	 * Close connection to MQTT broker and close GPIO handling
	 * @throws MqttException
	 */
	public void close() throws MqttException {
		// Disconnect the client from the server
		this.client.disconnect();
		log.info("Disconnected");
		if (this.useGPIO) {
			this.gpioController.shutdown();
		} else {
			log.info("Simulated GPIO shutdown");
		}
	}

	@Override
	public void connectionLost(Throwable cause) {
		// Called when the connection to the server has been lost.
		// An application may choose to implement reconnection
		// logic at this point. This sample simply exits.
		log.error("Connection to " + brokerUrl + " lost!" + cause);
		this.gpioController.shutdown();
		System.exit(1);
	}

	@Override
	public void deliveryComplete(IMqttDeliveryToken arg0) {
		// TODO Auto-generated method stub

	}

	@Override
	public void messageArrived(String topic, MqttMessage message) throws Exception {
		String payload = new String(message.getPayload());
		switch (payload.toUpperCase()) {
		case "ON":
		case "1": 
			if (this.useGPIO) {
				this.relay.setState(PinState.HIGH);
			} else {
				log.info("Simulated GPIO state: " + payload);
			}
			break;
		case "OFF":
		case "0":
			if (this.useGPIO) {
				this.relay.setState(PinState.LOW);
			} else {
				log.info("Simulated GPIO state: " + payload);
			}
			break;
		}
	}

	/****************************************************************/
	/* End of MqttCallback methods                                  */
	/****************************************************************/

	static void printHelp() {
		System.out.println(
				"Syntax:\n\n" +
						"    miot.relay [-h] [-j <Wiring Pi pin number] [-t <topic>]\n" +
						"               [-s 0|1|2] -b <hostname|IP address>] [-p <brokerport>] [-i <clientID>]\n\n" +
						"    -h  Print this help text and quit\n" +
						"    -q  Quiet mode (default is false)\n" +
						"    -j  Perform the relevant pin which connected to relay (default is WiringPi 0 = BCM pin 17 = HW pin 11)\n" +
						"    -t  Subscribe to <topic>\n" +              
						"    -s  Use this QoS instead of the default (0)\n" +
						"    -b  Use this name/IP address instead of the default (m2m.eclipse.org)\n" +
						"    -p  Use this port instead of the default (1883)\n\n" +
						"    -i  Use this client ID instead of SampleJavaV3_<action>\n" +
						"    -c  Connect to the server with a clean session (default is false)\n" +
						"     \n\n Security Options \n" +
						"     -u Username \n" +
						"     -z Password \n" +
						"     \n\n SSL Options \n" +
						"    -v  SSL enabled; true - (default is false) " +
						"    -k  Use this JKS format key store to verify the client\n" +
						"    -w  Passpharse to verify certificates in the keys store\n" +
						"    -r  Use this JKS format keystore to verify the server\n" +
						"    -x  eXecute GPIO using, default is simulated GPIO\n" +
						" If javax.net.ssl properties have been set only the -v flag needs to be set\n" +
						"Delimit strings containing spaces with \"\"\n\n"	              
				);
	}

}
