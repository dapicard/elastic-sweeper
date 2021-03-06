package com.github.dapicard.elasticsearch.curator.configuration;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.joda.time.Period;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.PeriodFormat;
import org.joda.time.format.PeriodFormatter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.github.dapicard.elasticsearch.curator.configuration.exception.ConfigurationException;

public class Configuration {
	private static final Logger LOGGER = LogManager.getLogger(Configuration.class);
	private static final Map<URL, Configuration> CONFIGURATIONS = new HashMap<>();
	
	private static ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
	private static Pattern extractVariable = Pattern.compile(".*%\\{(.+)\\}.*");
	private static PeriodFormatter periodFormatter = PeriodFormat.wordBased(Locale.ENGLISH);
	

	private List<CuratorIndex> curator = new ArrayList<>();
	private String initialDelay;
	private String repeatDelay;
	private Period smallestPeriod;

	public Configuration() {
	}

	public List<CuratorIndex> getCurator() {
		return curator;
	}

	public void setCurator(List<CuratorIndex> curator) {
		this.curator = curator;
	}

	public String getInitialDelay() {
		return initialDelay;
	}

	public Duration getInitialDelayDuration() {
		return periodFormatter.parsePeriod(initialDelay).toStandardDuration();
	}

	public void setInitialDelay(String initialDelay) {
		this.initialDelay = initialDelay;
	}

	public String getRepeatDelay() {
		return repeatDelay;
	}

	public Duration getRepeatDelayDuration() {
		return periodFormatter.parsePeriod(repeatDelay).toStandardDuration();
	}

	public void setRepeatDelay(String repeatDelay) {
		this.repeatDelay = repeatDelay;
	}

	public Period getSmallestPeriod() {
		return smallestPeriod;
	}

	/**
	 * @return The configuration at default URL classpath:curator.yml
	 */
	public static Configuration getConfiguration() {
		return getConfiguration(mapper.getClass().getClassLoader().getResource("curator.yml"));
	}
	
	/**
	 * Retrieves and analyses the configuration given by confUrl. 
	 * If the URL has already been retrieved, this method only returns the matching Configuration singleton object
	 * @param confUrl The configuration file to retrieve
	 * @return The configuration singleton object for the given file
	 */
	public static Configuration getConfiguration(URL confUrl) {
		if (CONFIGURATIONS.get(confUrl) != null) {
			return CONFIGURATIONS.get(confUrl);
		}
		
		LOGGER.info("Reading configuration from {}", confUrl.toString());
		Configuration configuration;
		try {
			configuration = mapper.readValue(confUrl.openStream(), Configuration.class);
		} catch (IOException ioe) {
			throw new RuntimeException("An error occurs while reading the configuration", ioe);
		}
		LOGGER.debug("Configuration read successfully by Jackson");
		ArrayList<CuratorIndex> toRemove = new ArrayList<>();
		for (CuratorIndex index : configuration.getCurator()) {
			try {
				configureCuratorIndex(index, configuration);
			} catch (ConfigurationException ce) {
				LOGGER.error(ce.getMessage());
				LOGGER.error("[{}] this index will be ignored.", index.getName());
				toRemove.add(index);
			}
		}
		configuration.getCurator().removeAll(toRemove);
		CONFIGURATIONS.put(confUrl, configuration);
		return configuration;
	}
	
	/**
	 * Analyses the given configuration
	 * @param configuration The configuration to analyse
	 * @return The analysed configuration
	 * @throws ConfigurationException If an error occurs in the analysis
	 */
	public static Configuration configure(Configuration configuration) throws ConfigurationException {
		for (CuratorIndex index : configuration.getCurator()) {
			configureCuratorIndex(index, configuration);
		}
		return configuration;
	}
	
	protected static void configureCuratorIndex(CuratorIndex index, Configuration configuration) throws ConfigurationException {
		LOGGER.info("Configuration for {} : ", index.getName());
		Instant now = new Instant(new Date().getTime());
		Period closePeriod = periodFormatter.parsePeriod(index.getClose());
		Period deletePeriod = periodFormatter.parsePeriod(index.getDelete());
		index.setClosePeriod(closePeriod);
		index.setDeletePeriod(deletePeriod);

		//To get the smallest period
		if (configuration.smallestPeriod == null) {
			configuration.smallestPeriod = closePeriod;
		}
		Duration compareDuration = configuration.smallestPeriod.toDurationFrom(now);
		Duration closeDuration = closePeriod.toDurationFrom(now);
		if (closeDuration.isShorterThan(compareDuration)) {
			compareDuration = closeDuration;
			configuration.smallestPeriod = closePeriod;
		}
		Duration deleteDuration = deletePeriod.toDurationFrom(now);
		if (deleteDuration.isShorterThan(compareDuration)) {
			compareDuration = deleteDuration;
			configuration.smallestPeriod = deletePeriod;
		}

		LOGGER.info("[{}] looks for indexes named {}. Closes {}'s old indexes. Deletes {}'s old indexes.", index.getName(), index.getPattern(), closePeriod.toString(),
				deletePeriod.toString());
		String basePattern = index.getPattern();
		Matcher m = extractVariable.matcher(basePattern);
		if (m.matches()) {
			//remove the leading + that does not make sense
			String datePattern = m.group(1).replaceAll("\\+", "");
			index.setDatePattern(DateTimeFormat.forPattern(datePattern));

			StringBuilder indexPattern = new StringBuilder();
			indexPattern.append(basePattern.substring(0, m.start(1) - 2));
			indexPattern.append("(.+)");
			indexPattern.append(basePattern.substring(m.end(1) + 1));
			Pattern indexP = Pattern.compile(indexPattern.toString());
			index.setNamePattern(indexP);
			LOGGER.info("[{}] the extracted timestamp pattern is {} ; so the index name pattern would be {}", index.getName(), datePattern, index.getNamePattern());
		} else {
			throw new ConfigurationException("[" + index.getName() +"] the pattern " + basePattern + " does not seem to have a timestamp variable. Add the timestamp with %{date-pattern} in the pattern.");
		}
	}
}
