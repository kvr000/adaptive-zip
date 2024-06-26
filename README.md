# Adaptive Zip

Compressing zip utility which adapts the compression methods according to file type, achieved ratio, etc.


## Usage

```
Usage: AdaptiveZip options... file...
AdaptiveZip  - archiving tool

Options:
-f archive-filename                         output archive filename
-t archive-type                             output archive type
--include include-pattern                   file pattern to include
--exclude exclude-pattern                   file pattern to exclude
--store-pattern file-pattern                file pattern to store
--store-ratio percent                       compression ratio to avoid
                                            compression (default is 10)
-z|--compression-level compression-level    compression level (1-9, can be more
                                            for specific compressions)
--root directory                            add files from the directory

Parameters:
file...    files to directly add
```


## Examples

The following command will add everything from src/main/java/ and target/classes (in this order) and will ignore
compression for `*.class` and `*.gz` files or, for anything where the achieved compressed result is not below `90%` of
original size.

```
java -jar target/AdaptiveZip.jar -z 9 -f target/a.zip --ignore '**/target/**/*' --store-pattern '**/*.class' --store-pattern '**/*.gz' --store-ratio 10 src/main/java/ target/classes/
Adding com/github/kvr000/adaptivezip/AdaptiveZip.java (70%)
Adding com/github/kvr000/adaptivezip/concurrent/ResultSerializingExecutor.java (72%)
Adding com/github/kvr000/adaptivezip/io/AntPathMatcher.java (0%)
Adding com/github/kvr000/adaptivezip/io/AnyOfPathMatcher.java (50%)
Adding com/github/kvr000/adaptivezip/io/Crc32CalculatingInputStream.java (55%)
Adding com/github/kvr000/adaptivezip/AdaptiveZip$Arguments.class (69%)
Adding com/github/kvr000/adaptivezip/AdaptiveZip.class (63%)
Adding com/github/kvr000/adaptivezip/concurrent/ResultSerializingExecutor$1.class (0%)
Adding com/github/kvr000/adaptivezip/concurrent/ResultSerializingExecutor$ExecutionFuture$1.class (61%)
Adding com/github/kvr000/adaptivezip/concurrent/ResultSerializingExecutor$ExecutionFuture.class (70%)
Adding com/github/kvr000/adaptivezip/concurrent/ResultSerializingExecutor.class (64%)
Adding com/github/kvr000/adaptivezip/io/AntPathMatcher.class (0%)
Adding com/github/kvr000/adaptivezip/io/AnyOfPathMatcher.class (56%)
Adding com/github/kvr000/adaptivezip/io/Crc32CalculatingInputStream.class (0%)
```


## License

The code is released under version 2.0 of the [Apache License][].


## Stay in Touch

Feel free to use or suggest improvements: kvr000@gmail.com or http://github.com/kvr000/ or https://www.linkedin.com/in/zbynek-vyskovsky/

[Apache License]: http://www.apache.org/licenses/LICENSE-2.0

[//]: # vim: set tw=120:
