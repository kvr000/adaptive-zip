package com.github.kvr000.adaptivezip.io;

import org.codehaus.plexus.util.MatchPattern;

import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;


/**
 * {@link PathMatcher} implementation for multiple {@link PathMatcher}.
 */
public class AnyOfPathMatcher implements PathMatcher
{
	private final List<PathMatcher> patterns;

	public AnyOfPathMatcher(List<PathMatcher> patterns)
	{
		this.patterns = patterns;
	}

	@Override
	public boolean matches(Path path)
	{
		return patterns.stream().anyMatch(p -> p.matches(path));
	}
}
