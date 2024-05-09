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

import com.github.kvr000.adaptivezip.io.AnyOfPathMatcher;
import com.github.kvr000.adaptivezip.io.Crc32CalculatingInputStream;
import com.github.kvr000.adaptivezip.io.FirstOfPathMatcher;
import com.github.kvr000.adaptivezip.io.PathMatcherUtil;
import com.google.common.collect.ImmutableMap;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Provides;
import lombok.Data;
import lombok.SneakyThrows;
import net.dryuf.base.concurrent.executor.CloseableExecutor;
import net.dryuf.base.concurrent.executor.CommonPoolExecutor;
import net.dryuf.base.concurrent.future.FutureUtil;
import net.dryuf.base.concurrent.executor.CapacityResultSequencingExecutor;
import net.dryuf.base.function.ThrowingConsumer;
import net.dryuf.base.io.FilenameComparators;
import net.dryuf.cmdline.app.AppContext;
import net.dryuf.cmdline.app.BeanFactory;
import net.dryuf.cmdline.app.CommonAppContext;
import net.dryuf.cmdline.app.guice.GuiceBeanFactory;
import net.dryuf.cmdline.command.AbstractCommand;
import net.dryuf.cmdline.command.CommandContext;
import net.dryuf.cmdline.command.RootCommandContext;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipMethod;
import org.apache.commons.compress.compressors.deflate.DeflateCompressorOutputStream;
import org.apache.commons.compress.compressors.deflate.DeflateParameters;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class AdaptiveZip extends AbstractCommand
{
	private Options options;

	public static void main(String[] args) throws Exception
	{
		runMain(args, (args0) -> {
			AppContext appContext = new CommonAppContext(Guice.createInjector(new GuiceModule()).getInstance(BeanFactory.class));
			return appContext.getBeanFactory().getBean(AdaptiveZip.class).run(
				new RootCommandContext(appContext).createChild(null, "AdaptiveZip", null),
				Arrays.asList(args0)
			);
		});
	}

	@Override
	protected boolean parseOption(CommandContext context, String arg, ListIterator<String> args) throws Exception
	{
		switch (arg) {
		case "-f" -> {
			options.archiveFilename = needArgsParam(options.archiveFilename, args);
			return true;
		}
		case "-t" -> {
			options.archiveType = needArgsParam(options.archiveType, args);
			return true;
		}
		case "--include" -> {
			ensureEmptySource(false).filter.add(Pair.of(PathMatcherUtil.createMatcher("glob:" + needArgsParam(null, args)), true));
			return true;
		}
		case "--exclude" -> {
			ensureEmptySource(false).filter.add(Pair.of(PathMatcherUtil.createMatcher("glob:" + needArgsParam(null, args)), false));
			return true;
		}
		case "--store-pattern" -> {
			options.storePatterns.add(PathMatcherUtil.createMatcher("glob:" + needArgsParam(null, args)));
			return true;
		}
		case "--store-ratio" -> {
			options.storeRatio = Integer.parseInt(needArgsParam(options.storeRatio, args));
			return true;
		}
		case "--compression-level", "-z" -> {
			options.compressionLevel = Integer.parseInt(needArgsParam(options.compressionLevel, args));
			return true;
		}
		case "--root" -> {
			ensureEmptySource(false).root = needArgsParam(null, args);
			return true;
		}
		default -> {
			return super.parseOption(context, arg, args);
		}
		}
	}

	@Override
	protected int parseNonOptions(CommandContext context, ListIterator<String> args) throws Exception
	{
		MutableInt counter = new MutableInt();
		args.forEachRemaining(arg -> {
			Source s = ensureEmptySource(counter.intValue() != 0);
			s.root = "";
			s.file = arg;
			counter.increment();
		});
		return EXIT_CONTINUE;
	}

	@Override
	protected int validateOptions(CommandContext context, ListIterator<String> args) throws Exception
	{
		if ((options.archiveFilename == null) == (options.archiveType == null)) {
			return usage(context, "One of -f archive-filename or -t archive-type must be specified");
		}
		if (options.sources.isEmpty()) {
			return usage(context, "source files are mandatory");
		}
		if (options.compressionLevel == null) {
			options.compressionLevel = 6;
		}
		if (options.storeRatio == null) {
			options.storeRatio = 10;
		}

		options.storePatternsMatcher = new AnyOfPathMatcher(options.storePatterns);
		options.deflateParameters = new DeflateParameters();
		options.deflateParameters.setCompressionLevel(options.compressionLevel);

		return EXIT_CONTINUE;
	}

	private Source ensureEmptySource(boolean copyFilters)
	{
		if (options.sources.isEmpty() || options.sources.getLast().root != null) {
			options.sources.add(new Source());
			if (copyFilters && options.sources.size() > 1) {
				options.sources.getLast().filter = options.sources.get(options.sources.size() - 2).filter;
			}
		}
		return options.sources.getLast();
	}

	@Override
	protected void createOptions(CommandContext context)
	{
		this.options = new Options();
	}

	@Override
	protected String configHelpTitle(CommandContext context)
	{
		return context.getCommandPath() + " - archiving tool";
	}

	@Override
	protected Map<String, String> configOptionsDescription(CommandContext context)
	{
		return ImmutableMap.<String, String>builder()
			.put("-f archive-filename", "output archive filename")
			.put("-t archive-type", "output archive type")
			.put("--include include-pattern", "file pattern to include")
			.put("--exclude exclude-pattern", "file pattern to exclude")
			.put("--store-pattern file-pattern", "file pattern to store")
			.put("--store-ratio percent", "compression ratio to avoid compression (default is 10)")
			.put("-z|--compression-level compression-level", "compression level (1-9, can be more for specific compressions)")
			.put("--root directory", "add files from the directory")
			.build();
	}

	@Override
	protected Map<String, String> configParametersDescription(CommandContext context)
	{
		return ImmutableMap.of(
			"file...", "files to directly add"
		);
	}

	@Data
	public static class Source
	{
		String root;

		String file;

		List<Pair<PathMatcher, Boolean>> filter = new ArrayList<>();
	}

	@Data
	public static class Options
	{
		String archiveFilename;

		String archiveType;

		List<Source> sources = new ArrayList<>();

		List<PathMatcher> storePatterns = new ArrayList<>();

		PathMatcher storePatternsMatcher;

		Integer storeRatio;

		Integer compressionLevel;

		DeflateParameters deflateParameters;
	}

	@Override
	public int execute() throws Exception
	{
		if (options.archiveType != null) {
			if (options.archiveType.equals("tar")) {
				return executeTar();
			}
			else {
				throw new IllegalArgumentException("Only tar is supported for piped archive-type");
			}
		}
		else {
			if (options.archiveFilename.endsWith(".zip")) {
				return executeZip();
			} else {
				throw new IllegalArgumentException("Only .zip extension is supported for direct archives");
			}
		}
	}

	int executeTar() throws Exception
	{
		Map<String, Path> seen = new LinkedHashMap<>();
		options.getSources().forEach(source -> {
			if (!source.root.equals(".") && !source.root.equals("")) {
				throw new IllegalArgumentException("--root is not supported by tar option");
			}
		});
		List<ImmutablePair<Path, Path>> files = collectFiles(options.getSources());
		AtomicReference<IOException> mainEx = new AtomicReference<>();
		Process tarBuilder = new ProcessBuilder("tar", "fcv", "-", "--no-recursion", "--null", "-T", "-")
			.redirectError(ProcessBuilder.Redirect.INHERIT)
			.redirectOutput(ProcessBuilder.Redirect.INHERIT)
			.redirectInput(ProcessBuilder.Redirect.PIPE)
			.start();
		try (OutputStream filesStream = tarBuilder.getOutputStream()) {
			files.forEach(ThrowingConsumer.sneaky((ImmutablePair<Path, Path> paths) -> {
				String name = slashify(paths.getLeft());
				Path old;
				if ((old = seen.put(name, paths.getRight())) != null) {
					System.err.println("Ignore duplicate entry: "+name+" old="+old+" new="+paths.getRight());
					return;
				}
				filesStream.write(paths.getRight().toString().getBytes(StandardCharsets.UTF_8));
				filesStream.write('\0');
			}));
			filesStream.close();
			int exit = tarBuilder.waitFor();
			if (exit != 0) {
				throw new IOException("tar exited with failure: exit=" + exit);
			}
		}
		finally {
			tarBuilder.waitFor();
		}
		return 0;
	}

	int executeZip() throws Exception
	{
		Map<String, Path> seen = new LinkedHashMap<>();
		List<ImmutablePair<Path, Path>> files = collectFiles(options.getSources());
		AtomicReference<IOException> mainEx = new AtomicReference<>();
		try (
			ZipArchiveOutputStream archive = new ZipArchiveOutputStream(new File(options.archiveFilename));
			CapacityResultSequencingExecutor executor = new CapacityResultSequencingExecutor(Runtime.getRuntime().maxMemory()*7/8, 128)
		) {
			files.forEach((ImmutablePair<Path, Path> paths) -> {
				if (!Files.isRegularFile(paths.getLeft())) {
					return;
				}
				FutureUtil.submitDirect(() -> Files.size(paths.getLeft()))
					.thenCompose((Long size) ->
						executor.submit(
							size,
							() -> buildRawEntry(paths),
							(entry) -> {
								try {
									Path old;
									if ((old = seen.put(entry.getLeft().getName(), paths.getRight())) != null) {
										System.err.println("Ignore duplicate entry: "+entry.getLeft().getName()+" old="+old+" new="+paths.getRight());
										return null;
									}
									System.err.println("\tadding: "+entry.getLeft().getName()+" ("+
										(entry.getLeft().getSize() != 0 ? (entry.getLeft().getSize()-entry.getLeft().getCompressedSize())*100L/entry.getLeft().getSize() : 0)+"%)");
										archive.addRawArchiveEntry(entry.getLeft(), entry.getRight());
										entry.getRight().close();
									return null;
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

	private List<ImmutablePair<Path, Path>> collectFiles(List<Source> sources)
	{
		List<ImmutablePair<Path, Path>> files = sources.parallelStream()
			.flatMap(source -> {
				PathMatcher matcher = new FirstOfPathMatcher(source.filter, true);
				try (CloseableExecutor executor = new CommonPoolExecutor()) {
					Path root = Paths.get(source.root);
					Path start = source.file == null ? root : Paths.get(source.file);
					List<ImmutablePair<Path, Path>> result = new ArrayList<>();
					if (Files.isDirectory(start)) {
						Files.walkFileTree(
							start,
							new FileVisitor<Path>()
							{
								@Override
								public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
								{
									executor.execute(() -> {
										Path relative = root.relativize(dir);
										boolean matches = matcher.matches(relative);
										if (matches) {
											result.add(ImmutablePair.of(dir, relative));
										}
									});
									return FileVisitResult.CONTINUE;
								}

								@Override
								public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
								{
									executor.execute(() -> {
										Path relative = root.relativize(file);
										boolean matches = matcher.matches(relative);
										if (matches) {
											result.add(ImmutablePair.of(file, relative));
										}
									});
									return FileVisitResult.CONTINUE;
								}

								@Override
								public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException
								{
									return FileVisitResult.CONTINUE;
								}

								@Override
								public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException
								{
									return FileVisitResult.CONTINUE;
								}
							}
						);
						executor.close();
						result.sort(Comparator.comparing(Pair::getRight, FilenameComparators.dirFirstPathComparator()));
						return result.stream();
					}
					else if (Files.isRegularFile(start)) {
						return Stream.of(new ImmutablePair<>(start, root.relativize(start)));
					}
					else if (Files.notExists(start, LinkOption.NOFOLLOW_LINKS)) {
						throw new IllegalArgumentException("File does not exist: " + start);
					}
					else {
						return Stream.of(new ImmutablePair<>(start, root.relativize(start)));
					}
				}
				catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			})
			.collect(Collectors.toList());
		return files;
	}

	private Pair<ZipArchiveEntry, InputStream> buildRawEntry(Pair<Path, Path> input) {
		Path full = input.getLeft();
		try (InputStream stream = Files.newInputStream(full)) {
			Crc32CalculatingInputStream crcStream = new Crc32CalculatingInputStream(stream);
			ZipArchiveEntry entry = new ZipArchiveEntry(slashify(input.getRight()));
			InputStream compressedInput = null;
			if (!options.storePatternsMatcher.matches(input.getRight())) {
				ByteArrayOutputStream deflatedBytes = new ByteArrayOutputStream();
				try (OutputStream deflated = new DeflateCompressorOutputStream(deflatedBytes, options.deflateParameters)) {
					IOUtils.copy(crcStream, deflated);
				}
				if (crcStream.getSize() > 0 && (crcStream.getSize()-deflatedBytes.size())*100L/crcStream.getSize() >= options.storeRatio) {
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

	public static class GuiceModule extends AbstractModule
	{
		@Override
		@SneakyThrows
		protected void configure()
		{
		}

		@Provides
		public BeanFactory beanFactory(Injector injector)
		{
			return new GuiceBeanFactory(injector);
		}
	}
}
