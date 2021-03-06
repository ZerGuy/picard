package picard.analysis;

import htsjdk.samtools.AlignmentBlock;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.filter.SamRecordFilter;
import htsjdk.samtools.filter.SecondaryAlignmentFilter;
import htsjdk.samtools.metrics.MetricBase;
import htsjdk.samtools.metrics.MetricsFile;
import htsjdk.samtools.reference.ReferenceSequence;
import htsjdk.samtools.reference.ReferenceSequenceFileWalker;
import htsjdk.samtools.util.Histogram;
import htsjdk.samtools.util.IOUtil;
import htsjdk.samtools.util.Log;
import htsjdk.samtools.util.ProgressLogger;
import htsjdk.samtools.util.SamLocusIterator;
import picard.cmdline.CommandLineProgram;
import picard.cmdline.CommandLineProgramProperties;
import picard.cmdline.Option;
import picard.cmdline.StandardOptionDefinitions;
import picard.cmdline.programgroups.Metrics;
import picard.util.MathUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Computes a number of metrics that are useful for evaluating coverage and performance of whole genome sequencing experiments.
 *
 * @author tfennell
 */
@CommandLineProgramProperties(
        usage = "Computes a number of metrics that are useful for evaluating coverage and performance of " +
                "whole genome sequencing experiments.",
        usageShort = "Writes whole genome sequencing-related metrics for a SAM or BAM file",
        programGroup = Metrics.class
)
public class CollectWgsMetrics extends CommandLineProgram {

    @Option(shortName = StandardOptionDefinitions.INPUT_SHORT_NAME, doc = "Input SAM or BAM file.")
    public File INPUT;

    @Option(shortName = StandardOptionDefinitions.OUTPUT_SHORT_NAME, doc = "Output metrics file.")
    public File OUTPUT;

    @Option(shortName = StandardOptionDefinitions.REFERENCE_SHORT_NAME, doc = "The reference sequence fasta aligned to.")
    public File REFERENCE_SEQUENCE;

    @Option(shortName = "MQ", doc = "Minimum mapping quality for a read to contribute coverage.", overridable = true)
    public int MINIMUM_MAPPING_QUALITY = 20;

    @Option(shortName = "Q", doc = "Minimum base quality for a base to contribute coverage.", overridable = true)
    public int MINIMUM_BASE_QUALITY = 20;

    @Option(shortName = "CAP", doc = "Treat bases with coverage exceeding this value as if they had coverage at this value.", overridable = true)
    public int COVERAGE_CAP = 250;

    @Option(doc = "For debugging purposes, stop after processing this many genomic bases.")
    public long STOP_AFTER = -1;

    @Option(doc = "Determines whether to include the base quality histogram in the metrics file.")
    public boolean INCLUDE_BQ_HISTOGRAM = false;

    private final Log log = Log.getInstance(CollectWgsMetrics.class);

    /** Metrics for evaluating the performance of whole genome sequencing experiments. */
    public static class WgsMetrics extends MetricBase {
        /** The number of non-N bases in the genome reference over which coverage will be evaluated. */
        public long GENOME_TERRITORY;
        /** The mean coverage in bases of the genome territory, after all filters are applied. */
        public double MEAN_COVERAGE;
        /** The standard deviation of coverage of the genome after all filters are applied. */
        public double SD_COVERAGE;
        /** The median coverage in bases of the genome territory, after all filters are applied. */
        public double MEDIAN_COVERAGE;
        /** The median absolute deviation of coverage of the genome after all filters are applied. */
        public double MAD_COVERAGE;

        /** The fraction of aligned bases that were filtered out because they were in reads with low mapping quality (default is < 20). */
        public double PCT_EXC_MAPQ;
        /** The fraction of aligned bases that were filtered out because they were in reads marked as duplicates. */
        public double PCT_EXC_DUPE;
        /** The fraction of aligned bases that were filtered out because they were in reads without a mapped mate pair. */
        public double PCT_EXC_UNPAIRED;
        /** The fraction of aligned bases that were filtered out because they were of low base quality (default is < 20). */
        public double PCT_EXC_BASEQ;
        /** The fraction of aligned bases that were filtered out because they were the second observation from an insert with overlapping reads. */
        public double PCT_EXC_OVERLAP;
        /** The fraction of aligned bases that were filtered out because they would have raised coverage above the capped value (default cap = 250x). */
        public double PCT_EXC_CAPPED;
        /** The total fraction of aligned bases excluded due to all filters. */
        public double PCT_EXC_TOTAL;

        /** The fraction of bases that attained at least 5X sequence coverage in post-filtering bases. */
        public double PCT_5X;
        /** The fraction of bases that attained at least 10X sequence coverage in post-filtering bases. */
        public double PCT_10X;
        /** The fraction of bases that attained at least 15X sequence coverage in post-filtering bases. */
        public double PCT_15X;
        /** The fraction of bases that attained at least 20X sequence coverage in post-filtering bases. */
        public double PCT_20X;
        /** The fraction of bases that attained at least 25X sequence coverage in post-filtering bases. */
        public double PCT_25X;
        /** The fraction of bases that attained at least 30X sequence coverage in post-filtering bases. */
        public double PCT_30X;
        /** The fraction of bases that attained at least 40X sequence coverage in post-filtering bases. */
        public double PCT_40X;
        /** The fraction of bases that attained at least 50X sequence coverage in post-filtering bases. */
        public double PCT_50X;
        /** The fraction of bases that attained at least 60X sequence coverage in post-filtering bases. */
        public double PCT_60X;
        /** The fraction of bases that attained at least 70X sequence coverage in post-filtering bases. */
        public double PCT_70X;
        /** The fraction of bases that attained at least 80X sequence coverage in post-filtering bases. */
        public double PCT_80X;
        /** The fraction of bases that attained at least 90X sequence coverage in post-filtering bases. */
        public double PCT_90X;
        /** The fraction of bases that attained at least 100X sequence coverage in post-filtering bases. */
        public double PCT_100X;
    }

    public static final int NUM_OF_THREADS = 4;
    public static final int PACK_MAX_SIZE = 500;
    public static final int PACK_MAX_ITERATIONS_SUM = 3000;
    private static final int QUEUE_CAPACITY = 100;
    public static final int SEMAPHORE_PERMITS = 10;

    public static void main(final String[] args) {
        new CollectWgsMetrics().instanceMainWithExit(args);
    }

    @Override
    protected int doWork() {

        //*** Time
        long time1 = System.currentTimeMillis();
        long time2, elapsed;

        IOUtil.assertFileIsReadable(INPUT);
        IOUtil.assertFileIsWritable(OUTPUT);
        IOUtil.assertFileIsReadable(REFERENCE_SEQUENCE);

        // Setup all the inputs
        final ProgressLogger progress = new ProgressLogger(log, 10000000, "Processed", "loci");
        final ReferenceSequenceFileWalker refWalker = new ReferenceSequenceFileWalker(REFERENCE_SEQUENCE);
        final SamReader in = SamReaderFactory.makeDefault().referenceSequence(REFERENCE_SEQUENCE).open(INPUT);

        final SamLocusIterator iterator = new SamLocusIterator(in);
        final List<SamRecordFilter> filters = new ArrayList<SamRecordFilter>();
        final CountingFilter dupeFilter = new CountingDuplicateFilter();
        final CountingFilter mapqFilter = new CountingMapQFilter(MINIMUM_MAPPING_QUALITY);
        final CountingPairedFilter pairFilter = new CountingPairedFilter();
        filters.add(mapqFilter);
        filters.add(dupeFilter);
        filters.add(pairFilter);
        filters.add(new SecondaryAlignmentFilter()); // Not a counting filter because we never want to count reads twice
        iterator.setSamFilters(filters);
        iterator.setEmitUncoveredLoci(true);
        iterator.setMappingQualityScoreCutoff(0); // Handled separately because we want to count bases
        iterator.setQualityScoreCutoff(0);        // Handled separately because we want to count bases
        iterator.setIncludeNonPfReads(false);

        final int max = COVERAGE_CAP;
        final AtomicLong[] HistogramArray = new AtomicLong[max + 1];
        final AtomicLong[] baseQHistogramArray = new AtomicLong[Byte.MAX_VALUE];
        final boolean usingStopAfter = STOP_AFTER > 0;
        final long stopAfter = STOP_AFTER - 1;
        final AtomicLong counter = new AtomicLong(0);

        final AtomicLong basesExcludedByBaseq = new AtomicLong(0);
        final AtomicLong basesExcludedByOverlap = new AtomicLong(0);
        final AtomicLong basesExcludedByCapping = new AtomicLong(0);

        for (int i = 0; i < HistogramArray.length; i++) {
            HistogramArray[i] = new AtomicLong(0);
        }
        for (int i = 0; i < baseQHistogramArray.length; i++) {
            baseQHistogramArray[i] = new AtomicLong(0);
        }

        //*** Time
        time2 = System.currentTimeMillis();
        elapsed = time2 - time1;
        System.out.println("Step 1 (start - before while): " + elapsed);
        time1 = System.currentTimeMillis();

        final ExecutorService executorService = Executors.newFixedThreadPool(NUM_OF_THREADS);

        final Semaphore sem = new Semaphore(SEMAPHORE_PERMITS);

        final BlockingQueue<List<SamLocusIterator.LocusInfo>> packsQueue =
                new LinkedBlockingDeque<List<SamLocusIterator.LocusInfo>>(QUEUE_CAPACITY);
        List<SamLocusIterator.LocusInfo> pack = new ArrayList<SamLocusIterator.LocusInfo>(PACK_MAX_SIZE);
        int iterationsCounter = 0;

        executorService.execute(new Runnable() {
            @Override
            public void run() {
                while (!((usingStopAfter && counter.get() > stopAfter) && packsQueue.isEmpty())) {
//                    System.out.println(packsQueue.remainingCapacity());
                    try {
                        final List<SamLocusIterator.LocusInfo> tempPack = packsQueue.take();

                        sem.acquire();

                        executorService.execute(new Runnable() {
                            @Override
                            public void run() {
                                for (final SamLocusIterator.LocusInfo info : tempPack) {
                                    if (info.getRecordAndPositions().size() > 0) {
                                        // Figure out the coverage while not counting overlapping reads twice, and excluding various things
                                        final HashSet<String> readNames = new HashSet<String>(info.getRecordAndPositions().size());
                                        int pileupSize = 0;
                                        for (final SamLocusIterator.RecordAndOffset recs : info.getRecordAndPositions()) {
                                            if (recs.getBaseQuality() < MINIMUM_BASE_QUALITY) {
                                                basesExcludedByBaseq.incrementAndGet();
                                                continue;
                                            }
                                            if (!readNames.add(recs.getRecord().getReadName())) {
                                                basesExcludedByOverlap.incrementAndGet();
                                                continue;
                                            }
                                            pileupSize++;
                                            if (pileupSize <= max) {
                                                baseQHistogramArray[recs.getRecord().getBaseQualities()[recs.getOffset()]].incrementAndGet();
                                            }
                                        }

                                        final int depth = Math.min(readNames.size(), max);
                                        if (depth < readNames.size())
                                            basesExcludedByCapping.addAndGet(readNames.size() - max);
                                        HistogramArray[depth].incrementAndGet();
                                    } else {
                                        HistogramArray[0].incrementAndGet();
                                    }
                                }

                                sem.release();
                            }
                        });
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                executorService.shutdown();
            }
        });

        // Loop through all the loci
        while (iterator.hasNext()) {
            final SamLocusIterator.LocusInfo info = iterator.next();

            // Check that the reference is not N
            final ReferenceSequence ref = refWalker.get(info.getSequenceIndex());
            final byte base = ref.getBases()[info.getPosition() - 1];
            if (base == 'N') {
                continue;
            }

            //Adding info to pack
            final int recLength = info.getRecordAndPositions().size();
            pack.add(info);
            iterationsCounter += recLength;
            counter.incrementAndGet();

            //Check if pack is ready
            if ((iterationsCounter < PACK_MAX_ITERATIONS_SUM) && (pack.size() < PACK_MAX_SIZE) &&
                    (iterator.hasNext()) && !(usingStopAfter && counter.get() > stopAfter)) {
                continue;
            }

//            System.out.println("putting pack in queue");
            try {
                packsQueue.put(pack);
//                System.out.println("Put: " + packsQueue.remainingCapacity());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            pack = new ArrayList<SamLocusIterator.LocusInfo>(PACK_MAX_SIZE);
            iterationsCounter = 0;

            // Record progress and perhaps stop
            progress.record(info.getSequenceName(), info.getPosition());

            if (usingStopAfter && counter.get() > stopAfter) {
                break;
            }
        }

        try {
            executorService.awaitTermination(1, TimeUnit.DAYS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        //*** Time
        time2 = System.currentTimeMillis();
        elapsed = time2 - time1;
        System.out.println("Step 2 (while): " + elapsed);
        time1 = System.currentTimeMillis();

        // Construct and write the outputs
        final Histogram<Integer> histo = new Histogram<Integer>("coverage", "count");
        for (int i = 0; i < HistogramArray.length; ++i) {
            histo.increment(i, HistogramArray[i].get());
        }

        //*** Time
        time2 = System.currentTimeMillis();
        elapsed = time2 - time1;
        System.out.println("Step 3 (histo): " + elapsed);
        time1 = System.currentTimeMillis();

        // Construct and write the outputs
        final Histogram<Integer> baseQHisto = new Histogram<Integer>("value", "baseq_count");
        for (int i = 0; i < baseQHistogramArray.length; ++i) {
            baseQHisto.increment(i, baseQHistogramArray[i].get());
        }

        //*** Time
        time2 = System.currentTimeMillis();
        elapsed = time2 - time1;
        System.out.println("Step 4 (baseQHisto): " + elapsed);
        time1 = System.currentTimeMillis();

        final WgsMetrics metrics = generateWgsMetrics();
        metrics.GENOME_TERRITORY = (long) histo.getSumOfValues();
        metrics.MEAN_COVERAGE = histo.getMean();
        metrics.SD_COVERAGE = histo.getStandardDeviation();
        metrics.MEDIAN_COVERAGE = histo.getMedian();
        metrics.MAD_COVERAGE = histo.getMedianAbsoluteDeviation();

        //*** Time
        time2 = System.currentTimeMillis();
        elapsed = time2 - time1;
        System.out.println("Step 5 (generateWgsMetrics): " + elapsed);
        time1 = System.currentTimeMillis();

        final long basesExcludedByDupes = dupeFilter.getFilteredBases();
        final long basesExcludedByMapq = mapqFilter.getFilteredBases();
        final long basesExcludedByPairing = pairFilter.getFilteredBases();
        final double total = histo.getSum();
        final double totalWithExcludes = total + basesExcludedByDupes + basesExcludedByMapq + basesExcludedByPairing +
                basesExcludedByBaseq.get() + basesExcludedByOverlap.get() + basesExcludedByCapping.get();
        metrics.PCT_EXC_DUPE = basesExcludedByDupes / totalWithExcludes;
        metrics.PCT_EXC_MAPQ = basesExcludedByMapq / totalWithExcludes;
        metrics.PCT_EXC_UNPAIRED = basesExcludedByPairing / totalWithExcludes;
        metrics.PCT_EXC_BASEQ = basesExcludedByBaseq.get() / totalWithExcludes;
        metrics.PCT_EXC_OVERLAP = basesExcludedByOverlap.get() / totalWithExcludes;
        metrics.PCT_EXC_CAPPED = basesExcludedByCapping.get() / totalWithExcludes;
        metrics.PCT_EXC_TOTAL = (totalWithExcludes - total) / totalWithExcludes;

        //*** Time
        time2 = System.currentTimeMillis();
        elapsed = time2 - time1;
        System.out.println("Step 6: " + elapsed);
        time1 = System.currentTimeMillis();

        final long[] HistogramArrayL = new long[max + 1];
        for (int i = 0; i < HistogramArrayL.length; i++) {
            HistogramArrayL[i] = HistogramArray[i].get();
        }

        metrics.PCT_5X = MathUtil.sum(HistogramArrayL, 5, HistogramArrayL.length) / (double) metrics.GENOME_TERRITORY;
        metrics.PCT_10X = MathUtil.sum(HistogramArrayL, 10, HistogramArrayL.length) / (double) metrics.GENOME_TERRITORY;
        metrics.PCT_15X = MathUtil.sum(HistogramArrayL, 15, HistogramArrayL.length) / (double) metrics.GENOME_TERRITORY;
        metrics.PCT_20X = MathUtil.sum(HistogramArrayL, 20, HistogramArrayL.length) / (double) metrics.GENOME_TERRITORY;
        metrics.PCT_25X = MathUtil.sum(HistogramArrayL, 25, HistogramArrayL.length) / (double) metrics.GENOME_TERRITORY;
        metrics.PCT_30X = MathUtil.sum(HistogramArrayL, 30, HistogramArrayL.length) / (double) metrics.GENOME_TERRITORY;
        metrics.PCT_40X = MathUtil.sum(HistogramArrayL, 40, HistogramArrayL.length) / (double) metrics.GENOME_TERRITORY;
        metrics.PCT_50X = MathUtil.sum(HistogramArrayL, 50, HistogramArrayL.length) / (double) metrics.GENOME_TERRITORY;
        metrics.PCT_60X = MathUtil.sum(HistogramArrayL, 60, HistogramArrayL.length) / (double) metrics.GENOME_TERRITORY;
        metrics.PCT_70X = MathUtil.sum(HistogramArrayL, 70, HistogramArrayL.length) / (double) metrics.GENOME_TERRITORY;
        metrics.PCT_80X = MathUtil.sum(HistogramArrayL, 80, HistogramArrayL.length) / (double) metrics.GENOME_TERRITORY;
        metrics.PCT_90X = MathUtil.sum(HistogramArrayL, 90, HistogramArrayL.length) / (double) metrics.GENOME_TERRITORY;
        metrics.PCT_100X = MathUtil.sum(HistogramArrayL, 100, HistogramArrayL.length) / (double) metrics.GENOME_TERRITORY;

        //*** Time
        time2 = System.currentTimeMillis();
        elapsed = time2 - time1;
        System.out.println("Step 7: " + elapsed);
        time1 = System.currentTimeMillis();

        final MetricsFile<WgsMetrics, Integer> out = getMetricsFile();
        out.addMetric(metrics);
        out.addHistogram(histo);
        if (INCLUDE_BQ_HISTOGRAM) {
            out.addHistogram(baseQHisto);
        }
        out.write(OUTPUT);

        //*** Time
        time2 = System.currentTimeMillis();
        elapsed = time2 - time1;
        System.out.println("Step 8 (end): " + elapsed);

        return 0;
    }

    protected WgsMetrics generateWgsMetrics() {
        return new WgsMetrics();
    }
}

/**
 * A SamRecordFilter that counts the number of aligned bases in the reads which it filters out. Abstract and designed
 * to be subclassed to implement the desired filter.
 */
abstract class CountingFilter implements SamRecordFilter {
    private long filteredRecords = 0;
    private long filteredBases = 0;

    /** Gets the number of records that have been filtered out thus far. */
    public long getFilteredRecords() { return this.filteredRecords; }

    /** Gets the number of bases that have been filtered out thus far. */
    public long getFilteredBases() { return this.filteredBases; }

    @Override
    public final boolean filterOut(final SAMRecord record) {
        final boolean filteredOut = reallyFilterOut(record);
        if (filteredOut) {
            ++filteredRecords;
            for (final AlignmentBlock block : record.getAlignmentBlocks()) {
                this.filteredBases += block.getLength();
            }
        }
        return filteredOut;
    }

    abstract public boolean reallyFilterOut(final SAMRecord record);

    @Override
    public boolean filterOut(final SAMRecord first, final SAMRecord second) {
        throw new UnsupportedOperationException();
    }
}

/** Counting filter that discards reads that have been marked as duplicates. */
class CountingDuplicateFilter extends CountingFilter {
    @Override
    public boolean reallyFilterOut(final SAMRecord record) { return record.getDuplicateReadFlag(); }
}

/** Counting filter that discards reads below a configurable mapping quality threshold. */
class CountingMapQFilter extends CountingFilter {
    private final int minMapq;

    CountingMapQFilter(final int minMapq) { this.minMapq = minMapq; }

    @Override
    public boolean reallyFilterOut(final SAMRecord record) { return record.getMappingQuality() < minMapq; }
}

/** Counting filter that discards reads that are unpaired in sequencing and paired reads who's mates are not mapped. */
class CountingPairedFilter extends CountingFilter {
    @Override
    public boolean reallyFilterOut(final SAMRecord record) { return !record.getReadPairedFlag() || record.getMateUnmappedFlag(); }
}

