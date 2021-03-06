package com.github.dapicard.elasticsearch.curator;

import static org.elasticsearch.node.NodeBuilder.nodeBuilder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;

import com.github.dapicard.elasticsearch.curator.configuration.Configuration;
import com.github.dapicard.elasticsearch.curator.service.CuratorService;
import com.github.dapicard.elasticsearch.curator.service.MatchingService;
import com.github.dapicard.elasticsearch.curator.transport.CuratorClient;
import com.github.dapicard.elasticsearch.curator.transport.impl.CuratorNodeClient;

public class Curator {
	private static final Logger LOGGER = LogManager.getLogger(Curator.class);
	// Configuration system properties
	public static final String CONFIGURATION_SYS = System.getProperty("curator.configurationFile");
	public static final String ES_CONFIGURATION_SYS = System.getProperty("elasticsearch.configurationFile");
	
	// Elasticsearch Transport
	public static final String TRANSPORT_SETTING = "transport.client.initial_nodes";
	public static final int TRANSPORT_DEFAULT_PORT = 9300;

	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
	
	public Curator() {
		this(Configuration.getConfiguration(), Settings.settingsBuilder().loadFromStream("elasticsearch.yml", Curator.class.getClassLoader().getResourceAsStream("elasticsearch.yml")).build());
	}
	
	public Curator(URL configuration, URL elasticsearchConfiguration) throws IOException {
		this(Configuration.getConfiguration(configuration), Settings.settingsBuilder().loadFromStream(elasticsearchConfiguration.getFile(), elasticsearchConfiguration.openStream()).build());
	}
	
	public Curator(Configuration config, Settings settings) {
		LOGGER.debug("Using given settings : {}", settings.toDelimitedString('|'));
		String[] transportInitialNodes = settings.getAsArray(TRANSPORT_SETTING);
		LOGGER.debug("Transport client initial nodes : {}", Strings.arrayToCommaDelimitedString(transportInitialNodes).toString());
		Client client;
		if(transportInitialNodes != null && transportInitialNodes.length > 0) {
			//Transport client
			Settings s = Settings.settingsBuilder().put("cluster.name", settings.get("cluster.name")).build();
			LOGGER.info("Instantiating Transport Client using settings {}", s.toString());
			client = TransportClient.builder().settings(settings).build();
			//Specific configuration
			for(String initialNode : transportInitialNodes) {
				int port = TRANSPORT_DEFAULT_PORT;
				LOGGER.info("Adding remote node [{}] to Transport client", initialNode);
				String[] splitHost = initialNode.split(":", 2);
				if (splitHost.length == 2) {
					initialNode = splitHost[0];
					try {
						port = Integer.parseInt(splitHost[1]);
					} catch (NumberFormatException nfe) {
						LOGGER.warn("The port number [{}] is not a valid port number. Using port number {}", splitHost[1], TRANSPORT_DEFAULT_PORT);
					}
				}
				((TransportClient) client).addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(initialNode, port)));
			}
		} else {
			//Node client
			client = nodeBuilder().client(true).settings(settings).node().client();
		}
		
		MatchingService matchingService = new MatchingService(config);
		CuratorClient transport = new CuratorNodeClient(client);
		final CuratorService curatorService = new CuratorService(matchingService, transport);
		
		Runnable cleanup = new Runnable() {
			@Override
			public void run() {
				try {
					curatorService.doCleanup();
				} catch (Exception e) {
					LOGGER.error("Error while trying to cleanup", e);
				}
			}
		};
		scheduler.scheduleWithFixedDelay(cleanup, config.getInitialDelayDuration().getStandardSeconds(), config.getRepeatDelayDuration().getStandardSeconds(), TimeUnit.SECONDS);
	}

	public static void main(String[] args) {
		//Curator configuration
		Configuration conf = null;
		if(CONFIGURATION_SYS != null && !CONFIGURATION_SYS.trim().isEmpty()) {
			try {
				URL confUrl = new URL(CONFIGURATION_SYS);
				conf = Configuration.getConfiguration(confUrl);
			} catch (MalformedURLException mue) {
				LOGGER.error("Unable to find the configuration file. Please provide an URL to the configuration file.", mue);
				System.exit(1);
			} catch (RuntimeException re) {
				LOGGER.error(re.getMessage(), re);
				System.exit(2);
			}
		} else {
			try {
				conf = Configuration.getConfiguration();
			} catch (RuntimeException re) {
				LOGGER.error(re.getMessage(), re);
				System.exit(2);
			}
		}
		
		//ElasticSearch Configuration
		Settings settings = null;
		if(ES_CONFIGURATION_SYS != null && !ES_CONFIGURATION_SYS.trim().isEmpty()) {
			try {
				URL esUrl = new URL(ES_CONFIGURATION_SYS);
				settings = Settings.settingsBuilder().loadFromStream(esUrl.getFile(), esUrl.openStream()).build();
			} catch (MalformedURLException mue) {
				LOGGER.error("Unable to find the ElasticSearch configuration file. Please provide an URL to this file.", mue);
				System.exit(1);
			} catch (IOException ioe) {
				LOGGER.error("An error occurs while reading the configuration URL.", ioe);
				System.exit(1);
			} catch (RuntimeException re) {
				LOGGER.error(re.getMessage(), re);
				System.exit(2);
			}
		} else {
			try {
				settings = Settings.settingsBuilder().loadFromStream("elasticsearch.yml", Curator.class.getClassLoader().getResourceAsStream("elasticsearch.yml")).build();
			} catch (RuntimeException re) {
				LOGGER.error(re.getMessage(), re);
				System.exit(2);
			}
		}
		new Curator(conf, settings);
	}

}
