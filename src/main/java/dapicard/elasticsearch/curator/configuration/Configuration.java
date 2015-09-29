package dapicard.elasticsearch.curator.configuration;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

public class Configuration {
	private static final Logger LOGGER = LogManager.getLogger(Configuration.class);

	private static ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
	private static Pattern extractVariable = Pattern.compile(".*%\\{(.+)\\}.*");
	private static PeriodFormatter periodFormatter = PeriodFormat.wordBased(Locale.ENGLISH);
	private static Configuration configuration;

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

	public static Configuration getConfiguration() throws JsonParseException, JsonMappingException, IOException {
		if (configuration != null) {
			return configuration;
		}
		URL confUrl = mapper.getClass().getClassLoader().getResource("curator.yml");
		LOGGER.info("Reading configuration from " + confUrl.toString());
		configuration = mapper.readValue(confUrl.openStream(), Configuration.class);
		LOGGER.debug("Configuration read successfully by Jackson");
		ArrayList<CuratorIndex> toRemove = new ArrayList<>();
		long now = new Date().getTime();
		for (CuratorIndex index : configuration.getCurator()) {
			LOGGER.info("Configuration for " + index.getName() + " : ");
			Period closePeriod = periodFormatter.parsePeriod(index.getClose());
			Period deletePeriod = periodFormatter.parsePeriod(index.getDelete());
			index.setClosePeriod(closePeriod);
			index.setDeletePeriod(deletePeriod);

			//To get the smallest period
			if (configuration.smallestPeriod == null) {
				configuration.smallestPeriod = closePeriod;
			}
			Duration compareDuration = configuration.smallestPeriod.toDurationFrom(new Instant(now));
			Duration closeDuration = closePeriod.toDurationFrom(new Instant(now));
			if (closeDuration.isShorterThan(compareDuration)) {
				compareDuration = closeDuration;
				configuration.smallestPeriod = closePeriod;
			}
			Duration deleteDuration = deletePeriod.toDurationFrom(new Instant(now));
			if (deleteDuration.isShorterThan(compareDuration)) {
				compareDuration = deleteDuration;
				configuration.smallestPeriod = deletePeriod;
			}

			LOGGER.info("[" + index.getName() + "] looks for indexes named " + index.getPattern() + ". Closes " + closePeriod.toString() + "'s old indexes. Deletes "
					+ deletePeriod.toString() + "'s old indexes.");
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
				LOGGER.info("[" + index.getName() + "] the extracted timestamp pattern is " + datePattern + " ; so the index name pattern would be " + index.getNamePattern());
			} else {
				LOGGER
						.error("[" + index.getName() + "] the pattern " + basePattern + " does not seem to have a timestamp variable. Add the timestamp with %{date-pattern} in the pattern.");
				LOGGER.error("[" + index.getName() + "] this index will be ignored.");
				toRemove.add(index);
			}
		}
		configuration.getCurator().removeAll(toRemove);

		return configuration;
	}
}
