package com.example.otel.aws;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * {@link ConfigProperties} for {@code ResourceProvider}s, read from the Spring
 * {@link Environment}. Keys use OpenTelemetry's names ({@code otel.*}); Spring's relaxed
 * binding also finds them as environment variables ({@code OTEL_*}), and
 * {@code application.yml} works too. Lists and maps use OpenTelemetry's comma-separated
 * format.
 */
class EnvironmentConfigProperties implements ConfigProperties {

	private final Environment environment;

	EnvironmentConfigProperties(Environment environment) {
		this.environment = environment;
	}

	@Override
	public String getString(String name) {
		return this.environment.getProperty(name);
	}

	@Override
	public Boolean getBoolean(String name) {
		return this.environment.getProperty(name, Boolean.class);
	}

	@Override
	public Integer getInt(String name) {
		return this.environment.getProperty(name, Integer.class);
	}

	@Override
	public Long getLong(String name) {
		return this.environment.getProperty(name, Long.class);
	}

	@Override
	public Double getDouble(String name) {
		return this.environment.getProperty(name, Double.class);
	}

	// OpenTelemetry durations are milliseconds unless they carry a unit, as Spring's are
	@Override
	public Duration getDuration(String name) {
		String value = getString(name);
		return StringUtils.hasText(value) ? DurationStyle.detectAndParse(value.trim()) : null;
	}

	@Override
	public List<String> getList(String name) {
		String value = getString(name);
		if (!StringUtils.hasText(value)) {
			return List.of();
		}
		return Arrays.stream(value.split(",")).map(String::trim).filter(StringUtils::hasLength).toList();
	}

	@Override
	public Map<String, String> getMap(String name) {
		Map<String, String> map = new LinkedHashMap<>();
		for (String entry : getList(name)) {
			int equals = entry.indexOf('=');
			if (equals > 0) {
				map.put(entry.substring(0, equals).trim(), entry.substring(equals + 1).trim());
			}
		}
		return map;
	}

}
