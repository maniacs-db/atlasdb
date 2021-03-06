/**
 * Copyright 2016 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.palantir.atlasdb.performance.cli;

import java.io.File;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.reflections.Reflections;
import org.reflections.scanners.MethodAnnotationsScanner;

import com.google.common.collect.Sets;
import com.palantir.atlasdb.performance.BenchmarkParam;
import com.palantir.atlasdb.performance.PerformanceResults;
import com.palantir.atlasdb.performance.backend.DatabasesContainer;
import com.palantir.atlasdb.performance.backend.DockerizedDatabase;
import com.palantir.atlasdb.performance.backend.KeyValueServiceType;

import io.airlift.airline.Arguments;
import io.airlift.airline.Command;
import io.airlift.airline.HelpOption;
import io.airlift.airline.Option;
import io.airlift.airline.SingleCommand;

/**
 * The Atlas Perf(ormance) CLI is a tool for making and running AtlasDB performance tests.
 *
 * This requires you to have a docker-machine running and configured correctly.
 *
 * @author mwakerman, bullman
 */
@Command(name = "atlasdb-perf", description = "The AtlasDB performance benchmark CLI.")
public class AtlasDbPerfCli {
    private static final String LIST_DELIMITER = ",";

    @Inject
    private HelpOption helpOption;

    @Arguments(description = "The performance benchmarks to run. Leave blank to run all performance benchmarks.")
    private List<String> tests;

    @Option(name = {"-b", "--backends"}, description = "Space delimited list of backing KVS stores to use in "
            + "quotes. (e.g. \"POSTGRES CASSANDRA\")" + " Defaults to all backends if not specified.")
    private String backends =
            EnumSet.allOf(KeyValueServiceType.class)
                    .stream()
                    .map(Enum::toString)
                    .collect(Collectors.joining(LIST_DELIMITER));

    @Option(name = {"-l", "--list-tests"}, description = "Lists all available benchmarks.")
    private boolean listTests;

    @Option(name = {"-o", "--output"},
            description = "The file in which to store the test results. "
                    + "Leave blank to only write results to the console.")
    private String outputFile;

    @Option(name = {"--jmh-output"},
            description = "The file to write the JMH log. Leave blank to write the log to the console.")
    private String logFile;

    public static void main(String[] args) throws Exception {
        AtlasDbPerfCli cli = SingleCommand.singleCommand(AtlasDbPerfCli.class).parse(args);

        if (cli.helpOption.showHelpIfRequested()) {
            return;
        }

        if (cli.listTests) {
            listAllBenchmarks();
            return;
        }

        if (hasValidArgs(cli)) {
            run(cli);
        } else {
            System.exit(1);
        }
    }

    private static void run(AtlasDbPerfCli cli) throws Exception {
        List<String> backends = getBackends(cli.backends);

        try (DatabasesContainer container = startupDatabase(backends)) {
            List<DockerizedDatabase> dbs = container.getDockerizedDatabases();
            ChainedOptionsBuilder optBuilder = new OptionsBuilder()
                    .forks(1)
                    .threads(1)
                    .warmupIterations(1)
                    .measurementIterations(1)
                    .mode(Mode.SampleTime)
                    .timeUnit(TimeUnit.MICROSECONDS)
                    .param(BenchmarkParam.URI.getKey(),
                            dbs.stream()
                                    .map(db -> db.getUri().toString())
                                    .collect(Collectors.toList())
                                    .toArray(new String[backends.size()]));
            if (cli.logFile != null) {
                optBuilder.output(cli.logFile);
            }

            if (cli.tests == null) {
                getAllBenchmarks().forEach(b -> optBuilder.include(".*" + b));
            } else {
                cli.tests.forEach(b -> optBuilder.include(".*" + b));
            }

            Collection<RunResult> results = new Runner(optBuilder.build()).run();
            if (cli.outputFile != null) {
                new PerformanceResults(results).writeToFile(new File(cli.outputFile));
            }
        }
    }

    private static DatabasesContainer startupDatabase(List<String> backends) {
        return DatabasesContainer.startup(
                backends.stream()
                        .map(KeyValueServiceType::valueOf)
                        .collect(Collectors.toList()));
    }

    private static List<String> getBackends(String backendsStr) {
        Set<String> backends = Sets.newHashSet(backendsStr.split(LIST_DELIMITER));
        return backends.stream()
                .map(String::trim)
                .collect(Collectors.toList());
    }

    private static boolean hasValidArgs(AtlasDbPerfCli cli) {
        for (String backend : getBackends(cli.backends)) {
            try {
                KeyValueServiceType.valueOf(backend.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("Invalid backend specified. Valid options: "
                        + EnumSet.allOf(KeyValueServiceType.class) + " You provided: " + backend, e);
            }
        }
        return true;
    }

    private static void listAllBenchmarks() {
        getAllBenchmarks().forEach(System.out::println);
    }

    private static Set<String> getAllBenchmarks() {
        Reflections reflections = new Reflections(
                "com.palantir.atlasdb.performance.benchmarks",
                new MethodAnnotationsScanner());
        return reflections.getMethodsAnnotatedWith(Benchmark.class).stream()
                .map(method -> method.getDeclaringClass().getSimpleName() + "." + method.getName())
                .collect(Collectors.toSet());
    }

}
