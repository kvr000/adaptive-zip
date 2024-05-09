package com.github.kvr000.adaptivezip.io;

import org.apache.commons.lang3.tuple.Pair;

import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Objects;


/**
 * {@link PathMatcher} implementation for multiple {@link PathMatcher}.
 */
public class FirstOfPathMatcher implements PathMatcher
{
	private final List<Pair<PathMatcher, Boolean>> patterns;

	private final boolean defaultValue;

	public FirstOfPathMatcher(List<Pair<PathMatcher, Boolean>> patterns, boolean defaultValue)
	{
		this.patterns = patterns;
		this.defaultValue = defaultValue;
	}

	@Override
	public boolean matches(Path path)
	{
		return patterns.stream()
			.map(p -> p.getLeft().matches(path) ? p.getRight() : null)
			.filter(Objects::nonNull)
			.findFirst()
			.orElse(defaultValue);
	}
}
