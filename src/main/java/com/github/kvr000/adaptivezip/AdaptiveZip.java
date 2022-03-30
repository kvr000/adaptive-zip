/*
 * Copyright 2016 Zbynek Vyskovsky mailto:kvr000@gmail.com http://github.com/kvr000/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.kvr000.adaptivezip;

import com.github.kvr000.adaptivezip.io.AntPathMatcher;
import com.github.kvr000.adaptivezip.io.AnyOfPathMatcher;
import com.github.kvr000.adaptivezip.io.Crc32CalculatingInputStream;
import net.dryuf.concurrent.FutureUtil;
import net.dryuf.concurrent.executor.CapacityResultSerializingExecutor;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipMethod;
import org.apache.commons.compress.compressors.deflate.DeflateCompressorOutputStream;
import org.apache.commons.compress.compressors.deflate.DeflateParameters;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class AdaptiveZip {

	public static void main(String[] args) throws Exception
	{
		AdaptiveZip self = new AdaptiveZip();
		System.exit(self.run(args));
	}

	public int run(String[] args) throws Exception
	{
		Pair<Integer, Arguments> status = setup(args);
		if (status.getLeft() != null) {
			return status.getLeft();
		}
		return execute(status.getRight());
	}

	public Pair<Integer, Arguments> setup(String[] args) throws Exception
	{
		Options options = new Options()
				.addOption("h", "Help")
				.addOption(Option.builder()
					.longOpt("deflate-level")
					.desc("Compression level for deflate method")
					.hasArg(true)
					.numberOfArgs(1)
					.type(Number.class)
					.build()
				)
				.addOption(Option.builder()
					.longOpt("ignore-pattern")
					.desc("Filename pattern to ignore")
					.hasArg(true)
					.numberOfArgs(1)
					.hasArgs()
					.numberOfArgs(1)
					.type(String.class)
					.build()
				)
				.addOption(Option.builder()
					.longOpt("store-pattern")
					.desc("Filename pattern to avoid compression (can be multiple)")
					.hasArg(true)
					.numberOfArgs(1)
					.hasArgs()
					.type(String.class)
					.build()
				)
				.addOption(Option.builder()
					.longOpt("store-ratio")
					.desc("Ratio (percentage of compressed to original) to avoid compression, default is 10")
					.hasArg(true)
					.numberOfArgs(1)
					.type(Number.class)
					.build()
				);

		Arguments arguments = new Arguments();

		CommandLine cmdline = new DefaultParser().parse(options, args, true);

		if (cmdline.hasOption("h")) {
			return new ImmutablePair<>(printHelp(options, null), null);
		}

		Optional.ofNullable((Number) cmdline.getParsedOptionValue("deflate-level"))
				.ifPresent(value -> arguments.deflateParameters.setCompressionLevel(value.intValue()));
		arguments.ignorePatterns = new AnyOfPathMatcher(Optional.ofNullable(cmdline.getOptionValues("ignore-pattern"))
				.map(Stream::of)
				.orElseGet(Stream::of)
				.map(pattern -> new AntPathMatcher("ant:"+pattern))
				.collect(Collectors.toList())
			);
		arguments.storePatterns = new AnyOfPathMatcher(Optional.ofNullable(cmdline.getOptionValues("store-pattern"))
				.map(Stream::of)
				.orElseGet(Stream::of)
				.map(pattern -> new AntPathMatcher("ant:"+pattern))
				.collect(Collectors.toList())
		);
		Optional.ofNullable((Number) cmdline.getParsedOptionValue("store-ratio"))
				.ifPresent(value -> arguments.storeRatio = value.intValue());
		List<String> remaining = cmdline.getArgList();
		if (remaining.size() < 2) {
			return new ImmutablePair<>(printHelp(options, "Need archive filename and source directories as additional arguments"), null);
		}
		arguments.archiveFilename = remaining.get(0);
		arguments.sourceDirectories = remaining.subList(1, remaining.size());
		return new ImmutablePair<>(null, arguments);
	}

	public int printHelp(Options options, String message) {
		if (message != null) {
			System.err.println(message);
			System.err.println("Type -h for help");
			return 127;
		}
		new HelpFormatter().printHelp(
				"Usage: AdaptiveZip [options] zip-filename source-directories-roots...",
				options
		);

		return 0;
	}

	public int execute(Arguments arguments) throws Exception
	{
		Map<String, Path> seen = new LinkedHashMap<>();
		List<ImmutablePair<Path, Path>> files = collectFiles(arguments);
		AtomicReference<IOException> mainEx = new AtomicReference<>();
		try (
			ZipArchiveOutputStream archive = new ZipArchiveOutputStream(new File(arguments.archiveFilename));
			CapacityResultSerializingExecutor executor = new CapacityResultSerializingExecutor(Runtime.getRuntime().maxMemory()*7/8, 128)
		) {
			files.forEach((ImmutablePair<Path, Path> paths) -> {
				FutureUtil.submitDirect(() -> Files.size(paths.getLeft()))
					.thenCompose((Long size) ->
						executor.submit(size, () -> buildRawEntry(arguments, paths))
							.thenAccept((Pair<ZipArchiveEntry, InputStream> entry) -> {
								try {
									Path old;
									if ((old = seen.put(entry.getLeft().getName(), paths.getRight())) != null) {
										System.err.println("Ignore duplicate entry: "+entry.getLeft().getName()+" old="+old+" new="+paths.getRight());
										return;
									}
									System.err.println("\tadding: "+entry.getLeft().getName()+" ("+
										(entry.getLeft().getSize() != 0 ? (entry.getLeft().getSize()-entry.getLeft().getCompressedSize())*100L/entry.getLeft().getSize() : 0)+"%)");
										archive.addRawArchiveEntry(entry.getLeft(), entry.getRight());
										entry.getRight().close();
								}
								catch (IOException e) {
									throw new UncheckedIOException(e);
								}
								finally {
									IOUtils.closeQuietly(entry.getRight());
								}
							})
					)
					.exceptionally((Throwable ex) -> {
						if (mainEx.get() == null && mainEx.compareAndSet(null, new IOException("Failed to process file: " + paths.getLeft(), ex)))
							return null;
						mainEx.get().addSuppressed(ex);
						return null;
					});
			});
		}
		if (mainEx.get() != null) {
			throw mainEx.get();
		}
		return 0;
	}

	private List<ImmutablePair<Path, Path>> collectFiles(Arguments arguments)
	{
		List<ImmutablePair<Path, Path>> files = arguments.sourceDirectories.parallelStream()
			.flatMap(dirname -> {
				try {
					Path root = Paths.get(dirname);
					if (Files.isDirectory(root)) {
						return Files.find(root, Integer.MAX_VALUE,
								(path, attrs) -> attrs.isRegularFile())
							.parallel()
							.map(path -> new ImmutablePair<>(path, root.relativize(path)))
							.filter((Pair<Path, Path> fileEntry) ->
								!arguments.ignorePatterns.matches(fileEntry.getRight()))
							.sorted();
					}
					else if (Files.isRegularFile(root)) {
						return Stream.of(new ImmutablePair<>(root, root));
					}
					else {
						return Stream.empty();
					}
				}
				catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			})
			// this line ensures file parallelism which is effectively disabled by low number of
			// very original input
			.collect(Collectors.toList());
		return files;
	}

	private Pair<ZipArchiveEntry, InputStream> buildRawEntry(Arguments arguments, Pair<Path, Path> input) {
		Path full = input.getLeft();
		try (InputStream stream = Files.newInputStream(full)) {
			Crc32CalculatingInputStream crcStream = new Crc32CalculatingInputStream(stream);
			ZipArchiveEntry entry = new ZipArchiveEntry(slashify(input.getRight()));
			InputStream compressedInput = null;
			if (!arguments.storePatterns.matches(input.getRight())) {
				ByteArrayOutputStream deflatedBytes = new ByteArrayOutputStream();
				try (OutputStream deflated = new DeflateCompressorOutputStream(deflatedBytes, arguments.deflateParameters)) {
					IOUtils.copy(crcStream, deflated);
				}
				if (crcStream.getSize() > 0 && (crcStream.getSize()-deflatedBytes.size())*100L/crcStream.getSize() >= arguments.storeRatio) {
					entry.setMethod(ZipMethod.DEFLATED.getCode());
					entry.setCompressedSize(deflatedBytes.size());
					compressedInput = new ByteArrayInputStream(deflatedBytes.toByteArray());
				}
			}
			else {
				IOUtils.copy(crcStream, NullOutputStream.NULL_OUTPUT_STREAM);
			}
			if (compressedInput == null) {
				entry.setMethod(ZipMethod.STORED.getCode());
				entry.setCompressedSize(crcStream.getSize());
				compressedInput = Files.newInputStream(full);
			}
			entry.setCrc(crcStream.getCrc32()&0xffffffffL);
			entry.setSize(crcStream.getSize());
			return new ImmutablePair<>(entry, compressedInput);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String slashify(Path path)
	{
		return path.toString()
				.replace(path.getFileSystem().getSeparator(), "/");
	}

	private static class Arguments
	{
		public Arguments()
		{
			deflateParameters.setWithZlibHeader(false);
		}

		private String archiveFilename;

		private List<String> sourceDirectories;

		private PathMatcher ignorePatterns;

		private PathMatcher storePatterns;

		private int storeRatio = 10;

		private DeflateParameters deflateParameters = new DeflateParameters();
	}
}
