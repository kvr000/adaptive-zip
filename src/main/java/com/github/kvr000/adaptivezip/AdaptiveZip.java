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

import com.github.kvr000.adaptivezip.io.Crc32CalculatingInputStream;
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
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
						.hasArgs()
						.type(String.class)
						.build()
				)
				.addOption(Option.builder()
						.longOpt("store-pattern")
						.desc("Filename pattern to avoid compression (can be multiple)")
						.hasArg(true)
						.hasArgs()
						.type(String.class)
						.build()
				)
				.addOption(Option.builder()
						.longOpt("store-ratio")
						.desc("Ratio (percentage of compressed to original) to avoid compression, default is 90")
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
		arguments.ignorePatterns = new WildcardFileFilter(Optional.ofNullable(cmdline.getOptionValues("ignore-pattern"))
				.map(Stream::of)
				.orElseGet(Stream::of)
				.collect(Collectors.toList())
		);
		arguments.storePatterns = new WildcardFileFilter(Optional.ofNullable(cmdline.getOptionValues("store-pattern"))
				.map(Stream::of)
				.orElseGet(Stream::of)
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
		Set<String> seen = new HashSet<>();
		try (ZipArchiveOutputStream archive = new ZipArchiveOutputStream(new File(arguments.archiveFilename))) {
			arguments.sourceDirectories.parallelStream()
					.flatMap(dirname -> {
						try {
							Path root = Paths.get(dirname);
							return Files.find(Paths.get(dirname), Integer.MAX_VALUE, (path, attrs) -> attrs.isRegularFile())
									.parallel()
									.map(path -> new ImmutablePair<>(root, root.relativize(path)))
									.filter((Pair<Path, Path> fileEntry) ->
											!arguments.ignorePatterns.accept(fileEntry.getLeft().toFile(), slashify(fileEntry.getRight())))
									.sorted();
						}
						catch (IOException e) {
							throw new UncheckedIOException(e);
						}
					})
					// this line ensures file parallelism which is effectively disabled by low number of very original input
					.collect(Collectors.toList()).parallelStream()
					.map(input -> buildRawEntry(arguments, input))
					.forEachOrdered((Pair<ZipArchiveEntry, InputStream> entry) -> {
						if (!seen.add(entry.getLeft().getName())) {
							System.err.println("Ignore duplicate entry: "+entry.getLeft().getName());
						}
						System.err.println("Adding "+entry.getLeft().getName()+" ("+
								(entry.getLeft().getSize() != 0 ? entry.getLeft().getCompressedSize()*100/entry.getLeft().getSize() : 100)+"%)");
						try {
							archive.addRawArchiveEntry(entry.getLeft(), entry.getRight());
						}
						catch (IOException e) {
							throw new UncheckedIOException(e);
						}
					});
		}
		return 0;
	}

	public Pair<ZipArchiveEntry, InputStream> buildRawEntry(Arguments arguments, Pair<Path, Path> input) {
		Path full = input.getLeft().resolve(input.getRight());
		try (InputStream stream = Files.newInputStream(full)) {
			Crc32CalculatingInputStream crcStream = new Crc32CalculatingInputStream(stream);
			ZipArchiveEntry entry = new ZipArchiveEntry(slashify(input.getRight()));
			ByteArrayInputStream content = new ByteArrayInputStream(IOUtils.toByteArray(crcStream));
			boolean noCompress = crcStream.getSize() == 0;
			InputStream compressedInput = null;
			if (!noCompress && arguments.storePatterns.accept(input.getLeft().toFile(), slashify(input.getRight()))) {
				noCompress = true;
			}
			if (!noCompress) {
				ByteArrayOutputStream deflatedBytes = new ByteArrayOutputStream();
				try (OutputStream deflated = new DeflateCompressorOutputStream(deflatedBytes, arguments.deflateParameters)) {
					IOUtils.copy(content, deflated);
				}
				if (deflatedBytes.size()*100/crcStream.getSize() < arguments.storeRatio) {
					entry.setMethod(ZipMethod.DEFLATED.getCode());
					entry.setCompressedSize(deflatedBytes.size());
					compressedInput = new ByteArrayInputStream(deflatedBytes.toByteArray());
				}
			}
			if (compressedInput == null) {
				entry.setMethod(ZipMethod.STORED.getCode());
				entry.setCompressedSize(crcStream.getSize());
				content.reset();
				compressedInput = content;
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

		private FilenameFilter ignorePatterns;

		private FilenameFilter storePatterns;

		private int storeRatio = 90;

		private DeflateParameters deflateParameters = new DeflateParameters();
	}
}
