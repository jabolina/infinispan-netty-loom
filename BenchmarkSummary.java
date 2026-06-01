///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS info.picocli:picocli:4.7.6

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "benchmark-summary", mixinStandardHelpOptions = true,
        description = "Parse JFR recordings and Hyperfoil stats from benchmark runs.")
public class BenchmarkSummary implements Callable<Integer> {

    @Option(names = "--data",
            description = "Path to run folder, e.g. data/20260529/1216")
    Path dataDir;

    @Option(names = "--run",
            description = "Hyperfoil run ID, e.g. 0005. Resolves to <data-parent>/hyperfoil/run/<ID>/stats/total.csv")
    String runId;

    @Option(names = "--timeline",
            description = "Generate an HTML timeline chart at this path, e.g. timeline.html")
    Path timelinePath;

    @Option(names = "--compare", arity = "2",
            description = "Compare two runs: paths to two data directories, e.g. --compare data/20260529/1407 data/20260529/1504")
    Path[] comparePaths;

    @Override
    public Integer call() throws Exception {
        if (comparePaths != null) {
            return runComparison();
        }

        if (dataDir == null) {
            System.err.println("Either --data or --compare is required.");
            return 1;
        }

        if (!Files.isDirectory(dataDir)) {
            System.err.println("Directory not found: " + dataDir);
            return 1;
        }

        List<Path> nodeDirs = findNodeDirs(dataDir);

        List<HyperfoilPhase> phases = List.of();
        if (runId != null) {
            phases = printHyperfoilRun();
        }

        if (!nodeDirs.isEmpty()) {
            printMonitoringSummary(nodeDirs);
        }

        if (!nodeDirs.isEmpty()) {
            if (timelinePath == null) {
                timelinePath = dataDir.resolve("timeline.html");
            }
            generateTimeline(nodeDirs, phases);
        }

        return 0;
    }

    private List<Path> findNodeDirs(Path dir) throws IOException {
        List<Path> nodeDirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry) && hasMonitoringData(entry)) {
                    nodeDirs.add(entry);
                }
            }
        }
        nodeDirs.sort(null);
        return nodeDirs;
    }

    private boolean hasMonitoringData(Path dir) throws IOException {
        if (Files.exists(dir.resolve("pidstat.log")) || Files.exists(dir.resolve("perf-stat.txt"))
                || Files.exists(dir.resolve("mpstat.log")))
            return true;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jfr")) {
            return stream.iterator().hasNext();
        }
    }

    // ── Monitoring Summary (pidstat + perf stat) ──

    private void printMonitoringSummary(List<Path> nodeDirs) throws IOException {
        System.out.println();
        System.out.println("=".repeat(110));
        System.out.printf("MONITORING — %s (%d nodes)%n", dataDir, nodeDirs.size());
        System.out.println("=".repeat(110));

        for (Path nodeDir : nodeDirs) {
            System.out.println();
            System.out.println("-".repeat(110));
            System.out.printf("Node: %s%n", nodeDir.getFileName());
            System.out.println("-".repeat(110));

            List<PidstatSample> pidstat = parsePidstat(nodeDir);
            if (!pidstat.isEmpty()) {
                DoubleSummaryStatistics pUsr = pidstat.stream().mapToDouble(PidstatSample::cpuUsr).summaryStatistics();
                DoubleSummaryStatistics pSys = pidstat.stream().mapToDouble(PidstatSample::cpuSystem).summaryStatistics();
                DoubleSummaryStatistics pWait = pidstat.stream().mapToDouble(PidstatSample::cpuWait).summaryStatistics();
                DoubleSummaryStatistics pCpu = pidstat.stream().mapToDouble(PidstatSample::cpuTotal).summaryStatistics();
                LongSummaryStatistics pRss = pidstat.stream().mapToLong(PidstatSample::rssKB).summaryStatistics();
                DoubleSummaryStatistics pVol = pidstat.stream().mapToDouble(PidstatSample::cswchPerSec).summaryStatistics();
                DoubleSummaryStatistics pInvol = pidstat.stream().mapToDouble(PidstatSample::nvcswchPerSec).summaryStatistics();

                System.out.println();
                section("Pidstat");
                System.out.printf("    CPU %%usr:    avg=%.1f%%  max=%.1f%%  (%d samples)%n",
                        pUsr.getAverage(), pUsr.getMax(), pidstat.size());
                System.out.printf("    CPU %%system: avg=%.1f%%  max=%.1f%%%n",
                        pSys.getAverage(), pSys.getMax());
                System.out.printf("    CPU %%wait:   avg=%.1f%%  max=%.1f%%%n",
                        pWait.getAverage(), pWait.getMax());
                System.out.printf("    CPU %%total:  avg=%.1f%%  max=%.1f%%%n",
                        pCpu.getAverage(), pCpu.getMax());
                System.out.printf("    RSS:         avg=%s  max=%s%n",
                        fmtBytes(Math.round(pRss.getAverage()) * 1024), fmtBytes(pRss.getMax() * 1024));
                if (pVol.getCount() > 0 && pVol.getMax() > 0) {
                    System.out.printf("    Context switches/s (all threads):%n");
                    System.out.printf("      Voluntary:     avg=%.0f  max=%.0f%n", pVol.getAverage(), pVol.getMax());
                    System.out.printf("      Involuntary:   avg=%.0f  max=%.0f%n", pInvol.getAverage(), pInvol.getMax());
                }
            }

            List<ThreadCswch> topThreads = parseTopThreadCswch(nodeDir, 5);
            if (!topThreads.isEmpty()) {
                System.out.printf("    Top threads by voluntary cswch/s (avg):%n");
                for (ThreadCswch t : topThreads) {
                    System.out.printf("      %-22s %,.0f%n", t.name, t.avgCswch);
                }
            }

            List<CpuStat> cpuStats = parseMpstat(nodeDir);
            if (!cpuStats.isEmpty()) {
                System.out.println();
                section("Mpstat (per-CPU avg %usr+%sys)");
                for (int i = 0; i < cpuStats.size(); i++) {
                    CpuStat c = cpuStats.get(i);
                    System.out.printf("      CPU %d: %5.1f%%", c.cpu, c.avgBusy());
                    if ((i + 1) % 4 == 0 || i == cpuStats.size() - 1) System.out.println();
                }
                double mean = cpuStats.stream().mapToDouble(CpuStat::avgBusy).average().orElse(0);
                double stddev = Math.sqrt(cpuStats.stream()
                        .mapToDouble(c -> Math.pow(c.avgBusy() - mean, 2)).average().orElse(0));
                double min = cpuStats.stream().mapToDouble(CpuStat::avgBusy).min().orElse(0);
                double max = cpuStats.stream().mapToDouble(CpuStat::avgBusy).max().orElse(0);
                System.out.printf("    Spread: stddev=%.1f%%  min=%.1f%%  max=%.1f%%%n", stddev, min, max);
            }

            printPerfStat(nodeDir);
        }
    }

    private List<CpuStat> parseMpstat(Path nodeDir) throws IOException {
        Path mpstatFile = nodeDir.resolve("mpstat.log");
        if (!Files.exists(mpstatFile)) return List.of();

        List<String> lines = Files.readAllLines(mpstatFile);

        boolean hasAmPm = lines.stream().anyMatch(l -> l.contains(" AM ") || l.contains(" PM "));
        int cpuIdx = hasAmPm ? 2 : 1;
        int usrIdx = hasAmPm ? 3 : 2;
        int sysIdx = hasAmPm ? 5 : 4;

        Map<Integer, double[]> cpuAccum = new TreeMap<>();

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.contains("CPU") || line.startsWith("Linux") || line.startsWith("Average"))
                continue;

            String[] fields = line.split("\\s+");
            if (fields.length <= sysIdx) continue;

            String cpuField = fields[cpuIdx];
            if (cpuField.equals("all")) continue;

            try {
                int cpu = Integer.parseInt(cpuField);
                double usr = Double.parseDouble(fields[usrIdx]);
                double sys = Double.parseDouble(fields[sysIdx]);
                cpuAccum.merge(cpu, new double[]{usr, sys, 1},
                        (a, b) -> new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]});
            } catch (Exception ignored) {}
        }

        return cpuAccum.entrySet().stream()
                .map(e -> new CpuStat(e.getKey(), e.getValue()[0] / e.getValue()[2], e.getValue()[1] / e.getValue()[2]))
                .toList();
    }

    private List<ThreadCswch> parseTopThreadCswch(Path nodeDir, int topN) throws IOException {
        Path pidstatFile = nodeDir.resolve("pidstat.log");
        if (!Files.exists(pidstatFile)) return List.of();

        List<String> lines = Files.readAllLines(pidstatFile);
        if (lines.stream().noneMatch(l -> l.contains("TGID"))) return List.of();

        Map<String, double[]> threadAccum = new LinkedHashMap<>();
        boolean isCswchSection = false;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            if (line.contains("cswch/s")) { isCswchSection = true; continue; }
            if (line.contains("%usr") || line.contains("minflt/s")) { isCswchSection = false; continue; }
            if (!isCswchSection) continue;

            String[] fields = line.split("\\s+");
            if (fields.length < 7 || !fields[2].equals("-")) continue;

            try {
                double vol = Double.parseDouble(fields[4]);
                double invol = Double.parseDouble(fields[5]);
                String name = fields[6].replaceFirst("^\\|__", "");
                threadAccum.merge(name, new double[]{vol, invol, 1},
                        (a, b) -> new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]});
            } catch (Exception ignored) {}
        }

        return threadAccum.entrySet().stream()
                .map(e -> new ThreadCswch(e.getKey(), e.getValue()[0] / e.getValue()[2], e.getValue()[1] / e.getValue()[2]))
                .filter(t -> t.avgCswch > 0)
                .sorted((a, b) -> Double.compare(b.avgCswch, a.avgCswch))
                .limit(topN)
                .toList();
    }

    private void printPerfStat(Path nodeDir) throws IOException {
        Path perfFile = nodeDir.resolve("perf-stat.txt");
        if (!Files.exists(perfFile)) return;

        List<String> lines = Files.readAllLines(perfFile);

        String cpusUtilized = null;
        long contextSwitches = -1; String csRate = null;
        long cpuMigrations = -1; String migRate = null;
        long pageFaults = -1; String pfRate = null;
        long cycles = -1; String ghz = null;
        long instructions = -1; String ipc = null;
        double elapsedSeconds = -1;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.contains("<not supported>") || line.contains("<not counted>")) continue;

            try {
                if (line.contains("task-clock")) {
                    cpusUtilized = extractAfterHash(line);
                } else if (line.contains("context-switches")) {
                    contextSwitches = parseFirstLong(line);
                    csRate = extractAfterHash(line);
                } else if (line.contains("cpu-migrations")) {
                    cpuMigrations = parseFirstLong(line);
                    migRate = extractAfterHash(line);
                } else if (line.contains("page-faults")) {
                    pageFaults = parseFirstLong(line);
                    pfRate = extractAfterHash(line);
                } else if (line.contains("cycles") && !line.contains("instructions")) {
                    cycles = parseFirstLong(line);
                    ghz = extractAfterHash(line);
                } else if (line.contains("instructions")) {
                    instructions = parseFirstLong(line);
                    ipc = extractAfterHash(line);
                } else if (line.contains("seconds time elapsed")) {
                    elapsedSeconds = Double.parseDouble(line.split("\\s+")[0]);
                }
            } catch (Exception ignored) {}
        }

        System.out.println();
        String header = elapsedSeconds > 0 ? String.format("Perf stat (%.1fs)", elapsedSeconds) : "Perf stat";
        section(header);
        if (cpusUtilized != null)
            System.out.printf("    CPUs utilized:     %s%n", cpusUtilized);
        if (contextSwitches >= 0)
            System.out.printf("    Context switches:  %,d  (%s)%n", contextSwitches, csRate);
        if (cpuMigrations >= 0)
            System.out.printf("    CPU migrations:    %,d  (%s)%n", cpuMigrations, migRate);
        if (pageFaults >= 0)
            System.out.printf("    Page faults:       %,d  (%s)%n", pageFaults, pfRate);
        if (cycles >= 0) {
            String cyclesStr = cycles >= 1_000_000_000L
                    ? String.format("%.1fB", cycles / 1_000_000_000.0)
                    : String.format("%,d", cycles);
            System.out.printf("    Cycles:            %s  (%s)%n", cyclesStr, ghz);
        }
        if (instructions >= 0) {
            String instrStr = instructions >= 1_000_000_000L
                    ? String.format("%.1fB", instructions / 1_000_000_000.0)
                    : String.format("%,d", instructions);
            System.out.printf("    Instructions:      %s  (%s)%n", instrStr, ipc);
        }
    }

    // ── Hyperfoil ──

    record HyperfoilPhase(String name, long startMs, long endMs) {}

    private List<HyperfoilPhase> printHyperfoilRun() throws IOException {
        Path dateDir = dataDir.getParent();
        Path totalCsv = dateDir.resolve("hyperfoil").resolve("run").resolve(runId).resolve("stats").resolve("total.csv");

        if (!Files.exists(totalCsv)) {
            System.err.println("Not found: " + totalCsv);
            return List.of();
        }

        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(totalCsv)) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                rows.add(line.split(",", -1));
            }
        }

        System.out.println();
        System.out.println("=".repeat(110));
        System.out.printf("HYPERFOIL RUN %s%n", runId);
        System.out.println("=".repeat(110));
        System.out.println();
        System.out.printf("  %-18s %10s %10s %10s %10s %10s %10s %10s %10s %10s %10s%n",
                "Phase", "Requests", "Responses", "Min", "Mean", "p50", "p90", "p99", "p99.9", "p99.99", "Max");
        System.out.println("  " + "-".repeat(118));

        List<HyperfoilPhase> phases = new ArrayList<>();
        for (String[] r : rows) {
            String phase = r[0];
            long startMs = Long.parseLong(r[2]);
            long endMs = Long.parseLong(r[3]);
            long requests = Long.parseLong(r[4]);
            long responses = Long.parseLong(r[5]);
            long mean = Long.parseLong(r[6]);
            long min = Long.parseLong(r[8]);
            long p50 = Long.parseLong(r[9]);
            long p90 = Long.parseLong(r[10]);
            long p99 = Long.parseLong(r[11]);
            long p999 = Long.parseLong(r[12]);
            long p9999 = Long.parseLong(r[13]);
            long max = Long.parseLong(r[14]);
            long connErrors = Long.parseLong(r[15]);
            long timeouts = Long.parseLong(r[16]);
            long errors = connErrors + timeouts;

            System.out.printf("  %-18s %10s %10s %10s %10s %10s %10s %10s %10s %10s %10s%n",
                    phase, fmtCount(requests), fmtCount(responses),
                    fmtNs(min), fmtNs(mean), fmtNs(p50), fmtNs(p90),
                    fmtNs(p99), fmtNs(p999), fmtNs(p9999), fmtNs(max));
            if (errors > 0) {
                System.out.printf("  %-18s   errors: %d conn, %d timeouts%n", "", connErrors, timeouts);
            }

            phases.add(new HyperfoilPhase(phase, startMs, endMs));
        }

        System.out.println();
        long totalReq = rows.stream().mapToLong(r -> Long.parseLong(r[4])).sum();
        long totalResp = rows.stream().mapToLong(r -> Long.parseLong(r[5])).sum();
        long totalConnErr = rows.stream().mapToLong(r -> Long.parseLong(r[15])).sum();
        long totalTimeouts = rows.stream().mapToLong(r -> Long.parseLong(r[16])).sum();
        System.out.printf("  Total: %s requests, %s responses, %d connection errors, %d timeouts%n",
                fmtCount(totalReq), fmtCount(totalResp), totalConnErr, totalTimeouts);

        List<String> runErrors = parseRunErrors(dateDir.resolve("hyperfoil").resolve("run").resolve(runId).resolve("info.json"));
        if (!runErrors.isEmpty()) {
            System.out.println();
            System.out.printf("  ⚠ %d benchmark error(s):%n", runErrors.size());
            for (String err : runErrors) {
                System.out.printf("    - %s%n", err);
            }
        }

        return phases;
    }

    private List<String> parseRunErrors(Path infoJson) {
        List<String> errors = new ArrayList<>();
        if (!Files.exists(infoJson)) return errors;
        try {
            String json = Files.readString(infoJson);
            int errIdx = json.indexOf("\"errors\"");
            if (errIdx < 0) return errors;
            int arrStart = json.indexOf('[', errIdx);
            int arrEnd = json.indexOf(']', arrStart);
            if (arrStart < 0 || arrEnd < 0) return errors;
            String arrContent = json.substring(arrStart + 1, arrEnd);
            int msgIdx = 0;
            while ((msgIdx = arrContent.indexOf("\"msg\"", msgIdx)) >= 0) {
                int colon = arrContent.indexOf(':', msgIdx);
                int valStart = arrContent.indexOf('"', colon + 1);
                int valEnd = arrContent.indexOf('"', valStart + 1);
                if (valStart >= 0 && valEnd > valStart) {
                    errors.add(arrContent.substring(valStart + 1, valEnd));
                }
                msgIdx = valEnd + 1;
            }
        } catch (IOException e) {
            System.err.println("Failed to read " + infoJson + ": " + e.getMessage());
        }
        return errors;
    }

    // ── Timeline ──

    record TimeSample(long epochMs, double jvmCpu, double machineCpu, long heapUsedBytes) {}
    record PidstatSample(long epochMs, double cpuUsr, double cpuSystem, double cpuWait, double cpuTotal, long rssKB, double cswchPerSec, double nvcswchPerSec) {}
    record ThreadCswch(String name, double avgCswch, double avgNvcswch) {}
    record CpuStat(int cpu, double avgUsr, double avgSys) {
        double avgBusy() { return avgUsr + avgSys; }
    }

    private void generateTimeline(List<Path> nodeDirs, List<HyperfoilPhase> phases) throws IOException {
        Map<String, List<TimeSample>> nodeTimelines = new LinkedHashMap<>();
        Map<String, List<PidstatSample>> nodePidstat = new LinkedHashMap<>();

        for (Path nodeDir : nodeDirs) {
            Path jfrFile = findJfrFile(nodeDir);
            if (jfrFile == null) continue;

            TreeMap<Long, double[]> cpuByTime = new TreeMap<>();
            TreeMap<Long, Long> heapByTime = new TreeMap<>();

            try (RecordingFile rf = new RecordingFile(jfrFile)) {
                while (rf.hasMoreEvents()) {
                    RecordedEvent event = rf.readEvent();
                    long epochMs = event.getStartTime().toEpochMilli();
                    switch (event.getEventType().getName()) {
                        case "jdk.CPULoad" -> {
                            double jvm = event.getDouble("jvmUser") + event.getDouble("jvmSystem");
                            double machine = event.getDouble("machineTotal");
                            cpuByTime.put(epochMs, new double[]{jvm, machine});
                        }
                        case "jdk.GCHeapSummary" ->
                                heapByTime.put(epochMs, event.getLong("heapUsed"));
                    }
                }
            }

            List<TimeSample> samples = new ArrayList<>();
            for (var entry : cpuByTime.entrySet()) {
                long ms = entry.getKey();
                double[] cpu = entry.getValue();
                Long heap = heapByTime.floorEntry(ms) != null ? heapByTime.floorEntry(ms).getValue() : null;
                samples.add(new TimeSample(ms, cpu[0], cpu[1], heap != null ? heap : 0));
            }
            for (var entry : heapByTime.entrySet()) {
                if (!cpuByTime.containsKey(entry.getKey())) {
                    var cpuEntry = cpuByTime.floorEntry(entry.getKey());
                    double jvm = cpuEntry != null ? cpuEntry.getValue()[0] : 0;
                    double machine = cpuEntry != null ? cpuEntry.getValue()[1] : 0;
                    samples.add(new TimeSample(entry.getKey(), jvm, machine, entry.getValue()));
                }
            }
            samples.sort((a, b) -> Long.compare(a.epochMs, b.epochMs));

            String nodeName = nodeDir.getFileName().toString();
            nodeTimelines.put(nodeName, samples);

            List<PidstatSample> pidstat = parsePidstat(nodeDir);
            if (!pidstat.isEmpty()) {
                nodePidstat.put(nodeName, pidstat);
            }
        }

        writeTimelineHtml(nodeTimelines, nodePidstat, phases);
        System.out.println();
        System.out.println("Timeline written to: " + timelinePath.toAbsolutePath());
    }

    private List<PidstatSample> parsePidstat(Path nodeDir) throws IOException {
        Path pidstatFile = nodeDir.resolve("pidstat.log");
        if (!Files.exists(pidstatFile)) return List.of();

        List<String> lines = Files.readAllLines(pidstatFile);
        if (lines.isEmpty()) return List.of();

        LocalDate date = null;
        var datePattern = java.util.regex.Pattern.compile("(\\d{2}/\\d{2}/\\d{2})");
        var matcher = datePattern.matcher(lines.get(0));
        if (matcher.find()) {
            date = LocalDate.parse(matcher.group(1), DateTimeFormatter.ofPattern("MM/dd/yy"));
        }
        if (date == null) return List.of();

        boolean hasThreads = lines.stream().anyMatch(l -> l.contains("TGID"));

        TreeMap<Long, double[]> cpuByMs = new TreeMap<>();
        TreeMap<Long, Long> rssByMs = new TreeMap<>();
        TreeMap<Long, double[]> cswchByMs = new TreeMap<>();
        boolean isCpuSection = false;
        boolean isMemSection = false;
        boolean isCswchSection = false;

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;

            if (line.contains("%usr")) {
                isCpuSection = true; isMemSection = false; isCswchSection = false;
                continue;
            }
            if (line.contains("minflt/s")) {
                isCpuSection = false; isMemSection = true; isCswchSection = false;
                continue;
            }
            if (line.contains("cswch/s")) {
                isCpuSection = false; isMemSection = false; isCswchSection = true;
                continue;
            }

            String[] fields = line.split("\\s+");
            if (fields.length < 6) continue;

            try {
                LocalTime time = LocalTime.parse(fields[0]);
                long epochMs = date.atTime(time).toInstant(ZoneOffset.UTC).toEpochMilli();

                if (hasThreads) {
                    boolean isTgidRow = !fields[2].equals("-");

                    if (isCpuSection && isTgidRow && fields.length >= 10) {
                        double usr = Double.parseDouble(fields[4]);
                        double sys = Double.parseDouble(fields[5]);
                        double wait = Double.parseDouble(fields[7]);
                        double total = Double.parseDouble(fields[8]);
                        cpuByMs.put(epochMs, new double[]{usr, sys, wait, total});
                    } else if (isMemSection && isTgidRow && fields.length >= 9) {
                        long rss = Long.parseLong(fields[7]);
                        rssByMs.put(epochMs, rss);
                    } else if (isCswchSection && !isTgidRow && fields.length >= 6) {
                        double vol = Double.parseDouble(fields[4]);
                        double invol = Double.parseDouble(fields[5]);
                        cswchByMs.merge(epochMs, new double[]{vol, invol},
                                (a, b) -> new double[]{a[0] + b[0], a[1] + b[1]});
                    }
                } else {
                    if (isCpuSection && fields.length >= 9) {
                        double usr = Double.parseDouble(fields[3]);
                        double sys = Double.parseDouble(fields[4]);
                        double wait = Double.parseDouble(fields[6]);
                        double total = Double.parseDouble(fields[7]);
                        cpuByMs.put(epochMs, new double[]{usr, sys, wait, total});
                    } else if (isMemSection && fields.length >= 8) {
                        long rss = Long.parseLong(fields[6]);
                        rssByMs.put(epochMs, rss);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        List<PidstatSample> samples = new ArrayList<>();
        for (var entry : cpuByMs.entrySet()) {
            long ms = entry.getKey();
            double[] cpu = entry.getValue();
            Long rss = rssByMs.getOrDefault(ms, rssByMs.floorKey(ms) != null ? rssByMs.floorEntry(ms).getValue() : 0L);
            double[] cs = cswchByMs.getOrDefault(ms, new double[]{0, 0});
            samples.add(new PidstatSample(ms, cpu[0], cpu[1], cpu[2], cpu[3], rss, cs[0], cs[1]));
        }
        return samples;
    }

    private void writeTimelineHtml(Map<String, List<TimeSample>> nodeTimelines,
                                   Map<String, List<PidstatSample>> nodePidstat,
                                   List<HyperfoilPhase> phases) throws IOException {
        long globalMin = nodeTimelines.values().stream()
                .flatMap(List::stream).mapToLong(s -> s.epochMs).min().orElse(0);

        StringBuilder datasets = new StringBuilder();
        String[] colors = {"#e6194b", "#3cb44b", "#4363d8", "#f58231", "#911eb4",
                "#42d4f4", "#f032e6", "#bfef45", "#fabed4", "#469990"};
        int colorIdx = 0;

        for (var entry : nodeTimelines.entrySet()) {
            String node = shortName(entry.getKey());
            String color = colors[colorIdx % colors.length];
            String colorFaded = color + "80";

            StringBuilder cpuData = new StringBuilder("[");
            StringBuilder heapData = new StringBuilder("[");
            for (var s : entry.getValue()) {
                long t = s.epochMs - globalMin;
                cpuData.append("{x:").append(t).append(",y:").append(String.format("%.1f", s.jvmCpu * 100)).append("},");
                heapData.append("{x:").append(t).append(",y:").append(s.heapUsedBytes / (1024 * 1024)).append("},");
            }
            cpuData.append("]");
            heapData.append("]");

            datasets.append(String.format(
                    "{label:'%s',data:%s,borderColor:'%s',backgroundColor:'%s',borderWidth:1.5,pointRadius:0,fill:false,tension:0.2},\n",
                    node + " CPU%", cpuData, color, colorFaded));
            datasets.append(String.format(
                    "{label:'%s',data:%s,borderColor:'%s',backgroundColor:'%s',borderWidth:1.5,pointRadius:0,fill:false,tension:0.2,hidden:true},\n",
                    node + " Heap MB", heapData, color, colorFaded));

            List<PidstatSample> pidstat = nodePidstat.get(entry.getKey());
            if (pidstat != null && !pidstat.isEmpty()) {
                StringBuilder pCpuData = new StringBuilder("[");
                StringBuilder pRssData = new StringBuilder("[");
                for (var s : pidstat) {
                    long t = s.epochMs - globalMin;
                    pCpuData.append("{x:").append(t).append(",y:").append(String.format("%.1f", s.cpuTotal)).append("},");
                    pRssData.append("{x:").append(t).append(",y:").append(s.rssKB / 1024).append("},");
                }
                pCpuData.append("]");
                pRssData.append("]");

                datasets.append(String.format(
                        "{label:'%s',data:%s,borderColor:'%s',backgroundColor:'%s',borderWidth:1,pointRadius:0,fill:false,tension:0.2,borderDash:[4,2],hidden:true},\n",
                        node + " pidstat CPU%", pCpuData, color, colorFaded));
                datasets.append(String.format(
                        "{label:'%s',data:%s,borderColor:'%s',backgroundColor:'%s',borderWidth:1,pointRadius:0,fill:false,tension:0.2,borderDash:[4,2],hidden:true},\n",
                        node + " pidstat RSS MB", pRssData, color, colorFaded));
            }

            colorIdx++;
        }

        StringBuilder annotations = new StringBuilder();
        String[] phaseColors = {"#2196F3", "#4CAF50", "#FF9800", "#9C27B0", "#F44336", "#00BCD4", "#795548", "#607D8B"};
        int phaseIdx = 0;
        for (var phase : phases) {
            long start = phase.startMs - globalMin;
            long end = phase.endMs - globalMin;
            String pc = phaseColors[phaseIdx % phaseColors.length];
            annotations.append(String.format(
                    "ps%d:{type:'line',xMin:%d,xMax:%d,borderColor:'%s',borderWidth:2,borderDash:[6,3]," +
                    "label:{display:true,content:'%s',position:'start',backgroundColor:'%s',color:'white',font:{size:10}}},\n",
                    phaseIdx, start, start, pc, phase.name, pc));
            annotations.append(String.format(
                    "pe%d:{type:'line',xMin:%d,xMax:%d,borderColor:'%s',borderWidth:1,borderDash:[3,3]},\n",
                    phaseIdx, end, end, pc));
            phaseIdx++;
        }

        String html = String.format("""
                <!DOCTYPE html>
                <html><head>
                <title>Benchmark Timeline</title>
                <script src="https://cdn.jsdelivr.net/npm/chart.js@4"></script>
                <script src="https://cdn.jsdelivr.net/npm/chartjs-plugin-annotation@3"></script>
                <style>body{font-family:sans-serif;margin:20px;background:#fafafa} .chart-box{background:white;border-radius:8px;padding:16px;margin-bottom:24px;box-shadow:0 1px 3px rgba(0,0,0,0.1)}</style>
                </head><body>
                <h2>Benchmark Timeline — %s</h2>
                <p>Nodes: %d | Phases shown as shaded regions. Click legend items to toggle series.</p>
                <div class="chart-box"><canvas id="chart" height="100"></canvas></div>
                <script>
                new Chart(document.getElementById('chart'), {
                  type: 'line',
                  data: { datasets: [%s] },
                  options: {
                    responsive: true,
                    interaction: { mode: 'index', intersect: false },
                    scales: {
                      x: { type: 'linear', title: { display: true, text: 'Time (ms since start)' },
                           ticks: { callback: v => (v/1000).toFixed(0)+'s' } },
                      y: { title: { display: true, text: 'Value (CPU %% or Heap MB)' }, min: 0 }
                    },
                    plugins: {
                      annotation: { annotations: {%s} },
                      tooltip: { callbacks: {
                        title: items => (items[0].parsed.x/1000).toFixed(1)+'s',
                        label: item => item.dataset.label + ': ' + item.parsed.y.toFixed(1)
                      }}
                    }
                  }
                });
                </script></body></html>
                """, dataDir, nodeTimelines.size(), datasets, annotations);

        try (BufferedWriter writer = Files.newBufferedWriter(timelinePath)) {
            writer.write(html);
        }
    }

    // ── Comparison ──

    record RunData(String label, Map<String, List<TimeSample>> nodeTimelines, Map<String, List<PidstatSample>> nodePidstat) {}

    private int runComparison() throws Exception {
        for (Path p : comparePaths) {
            if (!Files.isDirectory(p)) {
                System.err.println("Directory not found: " + p);
                return 1;
            }
        }

        List<RunData> runs = new ArrayList<>();
        for (Path p : comparePaths) {
            List<Path> nodeDirs = findNodeDirs(p);
            if (nodeDirs.isEmpty()) {
                System.err.println("No node directories with .jfr files in " + p);
                return 1;
            }

            Map<String, List<TimeSample>> timelines = new LinkedHashMap<>();
            Map<String, List<PidstatSample>> pidstats = new LinkedHashMap<>();

            for (Path nodeDir : nodeDirs) {
                Path jfrFile = findJfrFile(nodeDir);
                if (jfrFile == null) continue;

                TreeMap<Long, double[]> cpuByTime = new TreeMap<>();
                TreeMap<Long, Long> heapByTime = new TreeMap<>();

                try (RecordingFile rf = new RecordingFile(jfrFile)) {
                    while (rf.hasMoreEvents()) {
                        RecordedEvent event = rf.readEvent();
                        long epochMs = event.getStartTime().toEpochMilli();
                        switch (event.getEventType().getName()) {
                            case "jdk.CPULoad" -> {
                                double jvm = event.getDouble("jvmUser") + event.getDouble("jvmSystem");
                                double machine = event.getDouble("machineTotal");
                                cpuByTime.put(epochMs, new double[]{jvm, machine});
                            }
                            case "jdk.GCHeapSummary" ->
                                    heapByTime.put(epochMs, event.getLong("heapUsed"));
                        }
                    }
                }

                List<TimeSample> samples = new ArrayList<>();
                for (var entry : cpuByTime.entrySet()) {
                    long ms = entry.getKey();
                    double[] cpu = entry.getValue();
                    Long heap = heapByTime.floorEntry(ms) != null ? heapByTime.floorEntry(ms).getValue() : null;
                    samples.add(new TimeSample(ms, cpu[0], cpu[1], heap != null ? heap : 0));
                }
                samples.sort((a, b) -> Long.compare(a.epochMs, b.epochMs));

                String nodeName = nodeDir.getFileName().toString();
                timelines.put(nodeName, samples);

                List<PidstatSample> pidstat = parsePidstat(nodeDir);
                if (!pidstat.isEmpty()) {
                    pidstats.put(nodeName, pidstat);
                }
            }

            runs.add(new RunData(p.getFileName().toString(), timelines, pidstats));
        }

        writeComparisonHtml(runs);
        return 0;
    }

    private record AvgSample(long relativeMs, double avgCpu, long avgHeapMB) {}
    private record AvgPidstatSample(long relativeMs, double avgCpu, long avgRssMB) {}

    private List<AvgSample> computeAverages(Map<String, List<TimeSample>> nodeTimelines, long globalMin) {
        TreeMap<Long, double[]> accum = new TreeMap<>();
        for (List<TimeSample> samples : nodeTimelines.values()) {
            for (TimeSample s : samples) {
                long t = s.epochMs - globalMin;
                accum.merge(t, new double[]{s.jvmCpu * 100, s.heapUsedBytes / (1024.0 * 1024), 1},
                        (a, b) -> new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]});
            }
        }
        List<AvgSample> result = new ArrayList<>();
        for (var entry : accum.entrySet()) {
            double[] v = entry.getValue();
            int count = (int) v[2];
            result.add(new AvgSample(entry.getKey(), v[0] / count, Math.round(v[1] / count)));
        }
        return result;
    }

    private List<AvgPidstatSample> computePidstatAverages(Map<String, List<PidstatSample>> nodePidstat, long globalMin) {
        TreeMap<Long, double[]> accum = new TreeMap<>();
        for (List<PidstatSample> samples : nodePidstat.values()) {
            for (PidstatSample s : samples) {
                long t = s.epochMs - globalMin;
                accum.merge(t, new double[]{s.cpuTotal, s.rssKB / 1024.0, 1},
                        (a, b) -> new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]});
            }
        }
        List<AvgPidstatSample> result = new ArrayList<>();
        for (var entry : accum.entrySet()) {
            double[] v = entry.getValue();
            int count = (int) v[2];
            result.add(new AvgPidstatSample(entry.getKey(), v[0] / count, Math.round(v[1] / count)));
        }
        return result;
    }

    private void writeComparisonHtml(List<RunData> runs) throws IOException {
        String[] runColors = {"#e6194b", "#4363d8"};

        StringBuilder datasets = new StringBuilder();
        for (int r = 0; r < runs.size(); r++) {
            RunData run = runs.get(r);
            String color = runColors[r % runColors.length];
            String colorFaded = color + "80";

            long runMin = run.nodeTimelines.values().stream()
                    .flatMap(List::stream).mapToLong(s -> s.epochMs).min().orElse(0);

            List<AvgSample> avgSamples = computeAverages(run.nodeTimelines, runMin);

            StringBuilder cpuData = new StringBuilder("[");
            StringBuilder heapData = new StringBuilder("[");
            for (AvgSample s : avgSamples) {
                cpuData.append("{x:").append(s.relativeMs).append(",y:").append(String.format("%.1f", s.avgCpu)).append("},");
                heapData.append("{x:").append(s.relativeMs).append(",y:").append(s.avgHeapMB).append("},");
            }
            cpuData.append("]");
            heapData.append("]");

            datasets.append(String.format(
                    "{label:'%s avg CPU%%',data:%s,borderColor:'%s',backgroundColor:'%s',borderWidth:2,pointRadius:0,fill:false,tension:0.2},\n",
                    run.label, cpuData, color, colorFaded));
            datasets.append(String.format(
                    "{label:'%s avg Heap MB',data:%s,borderColor:'%s',backgroundColor:'%s',borderWidth:2,pointRadius:0,fill:false,tension:0.2,borderDash:[6,3],hidden:true},\n",
                    run.label, heapData, color, colorFaded));

            if (!run.nodePidstat.isEmpty()) {
                List<AvgPidstatSample> pidstatAvg = computePidstatAverages(run.nodePidstat, runMin);
                StringBuilder pCpuData = new StringBuilder("[");
                StringBuilder pRssData = new StringBuilder("[");
                for (AvgPidstatSample s : pidstatAvg) {
                    pCpuData.append("{x:").append(s.relativeMs).append(",y:").append(String.format("%.1f", s.avgCpu)).append("},");
                    pRssData.append("{x:").append(s.relativeMs).append(",y:").append(s.avgRssMB).append("},");
                }
                pCpuData.append("]");
                pRssData.append("]");

                datasets.append(String.format(
                        "{label:'%s pidstat CPU%%',data:%s,borderColor:'%s',backgroundColor:'%s',borderWidth:1,pointRadius:0,fill:false,tension:0.2,borderDash:[4,2],hidden:true},\n",
                        run.label, pCpuData, color, colorFaded));
                datasets.append(String.format(
                        "{label:'%s pidstat RSS MB',data:%s,borderColor:'%s',backgroundColor:'%s',borderWidth:1,pointRadius:0,fill:false,tension:0.2,borderDash:[4,2],hidden:true},\n",
                        run.label, pRssData, color, colorFaded));
            }
        }

        if (timelinePath == null) {
            timelinePath = comparePaths[0].getParent().resolve(
                    "compare-" + comparePaths[0].getFileName() + "-vs-" + comparePaths[1].getFileName() + ".html");
        }

        String title = comparePaths[0].getFileName() + " vs " + comparePaths[1].getFileName();
        String html = String.format("""
                <!DOCTYPE html>
                <html><head>
                <title>Benchmark Comparison — %s</title>
                <script src="https://cdn.jsdelivr.net/npm/chart.js@4"></script>
                <style>body{font-family:sans-serif;margin:20px;background:#fafafa} .chart-box{background:white;border-radius:8px;padding:16px;margin-bottom:24px;box-shadow:0 1px 3px rgba(0,0,0,0.1)}</style>
                </head><body>
                <h2>Benchmark Comparison — %s</h2>
                <p>Averaged across all nodes per run. Click legend items to toggle series.</p>
                <div class="chart-box"><canvas id="chart" height="100"></canvas></div>
                <script>
                new Chart(document.getElementById('chart'), {
                  type: 'line',
                  data: { datasets: [%s] },
                  options: {
                    responsive: true,
                    interaction: { mode: 'index', intersect: false },
                    scales: {
                      x: { type: 'linear', title: { display: true, text: 'Time (s since start)' },
                           ticks: { callback: v => (v/1000).toFixed(0)+'s' } },
                      y: { title: { display: true, text: 'CPU %%%% / Heap MB' }, min: 0 }
                    },
                    plugins: {
                      tooltip: { callbacks: {
                        title: items => (items[0].parsed.x/1000).toFixed(1)+'s',
                        label: item => item.dataset.label + ': ' + item.parsed.y.toFixed(1)
                      }}
                    }
                  }
                });
                </script></body></html>
                """, title, title, datasets);

        try (BufferedWriter writer = Files.newBufferedWriter(timelinePath)) {
            writer.write(html);
        }
        System.out.println("Comparison written to: " + timelinePath.toAbsolutePath());
    }

    // ── Helpers ──

    private static Path findJfrFile(Path dir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jfr")) {
            for (Path p : stream) return p;
        }
        return null;
    }

    private static String shortName(String hostname) {
        if (hostname.startsWith("ec2-")) {
            String[] parts = hostname.split("\\.");
            return parts[0];
        }
        return hostname;
    }

    private static void section(String title) {
        System.out.println("  " + title + ":");
    }

    private static String extractAfterHash(String line) {
        int hash = line.indexOf('#');
        return hash >= 0 ? line.substring(hash + 1).trim() : "";
    }

    private static long parseFirstLong(String line) {
        String first = line.trim().split("\\s+")[0];
        try {
            return Long.parseLong(first);
        } catch (NumberFormatException e) {
            return (long) Double.parseDouble(first);
        }
    }

    private static String fmtBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String fmtNs(long nanos) {
        if (nanos < 1_000) return nanos + "ns";
        if (nanos < 1_000_000) return String.format("%.1fus", nanos / 1_000.0);
        if (nanos < 1_000_000_000) return String.format("%.2fms", nanos / 1_000_000.0);
        return String.format("%.2fs", nanos / 1_000_000_000.0);
    }

    private static String fmtCount(long count) {
        if (count < 1_000) return Long.toString(count);
        if (count < 1_000_000) return String.format("%.1fK", count / 1_000.0);
        return String.format("%.2fM", count / 1_000_000.0);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new BenchmarkSummary()).execute(args);
        System.exit(exitCode);
    }
}
