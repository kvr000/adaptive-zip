package com.github.kvr000.adaptivezip.io;

import org.codehaus.plexus.util.MatchPattern;

import java.nio.file.Path;
import java.nio.file.PathMatcher;


/**
 * {@link PathMatcher} implementation for Ant syntax.
 */
public class AntPathMatcher implements PathMatcher
{
	private final MatchPattern pattern;

	public AntPathMatcher(String pattern)
	{
		if (!pattern.startsWith("ant:")) {
			throw new IllegalArgumentException("Pattern expected to start with \"ant:\", got: "+pattern);
		}
		this.pattern = MatchPattern.fromString(pattern.substring(4));
	}

	@Override
	public boolean matches(Path path)
	{
		String pathStr = path.toString();
		return pattern.matchPath(pathStr, true);
	}
}
