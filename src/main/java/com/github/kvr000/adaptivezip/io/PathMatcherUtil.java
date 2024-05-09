package com.github.kvr000.adaptivezip.io;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;


/**
 * Utility to work with {@link PathMatcher}.
 */
public class PathMatcherUtil
{
	/**
	 * Creates new {@link PathMatcher}, based on provided syntax.
	 *
	 * @param pattern
	 * 	pattern value
	 *
	 * @return
	 * 	new PathMatcher
	 *
	 * @throws IllegalArgumentException
	 * 	when pattern syntax is not recognized.
	 */
	public static PathMatcher createMatcher(String pattern)
	{
		if (pattern.startsWith("glob:") || pattern.startsWith("regex:")) {
			return FileSystems.getDefault().getPathMatcher(pattern);
		}
		else if (pattern.startsWith("ant:")) {
			return new AntPathMatcher(pattern);
		}
		else {
			throw new IllegalArgumentException("Unsupported path matcher pattern, only glob, regex, ant are supported: "+pattern);
		}
	}
}
