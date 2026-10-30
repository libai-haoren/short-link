import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 跳转链路压测（单文件，无第三方依赖）。
 *
 * <pre>
 *   java scripts/LoadTest.java --admin http://127.0.0.1:8082 --redirect http://127.0.0.1:8081 \
 *        --codes 2000 --threads 32 --seconds 10
 * </pre>
 *
 * <p>流程：先用 admin 造 N 条短链 → 预热 1s（让 L1 有数据）→ 固定时长压测 → 输出 QPS 与分位延迟。</p>
 *
 * <p>为什么自己写而不是用 wrk/JMeter：只需要一个能跑在任意 JDK 上的最小工具，
 * 不引入额外安装成本；固定 URL 池 + 随机命中，足够对比"开/关 L1"的差异。</p>
 */
public class LoadTest {

    private static final Pattern SHORT_CODE = Pattern.compile("\"shortCode\":\"([^\"]+)\"");

    public static void main(String[] args) throws Exception {
        String admin = arg(args, "--admin", "http://127.0.0.1:8082");
        String redirect = arg(args, "--redirect", "http://127.0.0.1:8081");
        int codeCount = Integer.parseInt(arg(args, "--codes", "2000"));
        int threads = Integer.parseInt(arg(args, "--threads", "32"));
        int seconds = Integer.parseInt(arg(args, "--seconds", "10"));

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        System.out.printf("== 造数：%d 条短链 ==%n", codeCount);
        List<String> codes = seed(client, admin, codeCount);
        if (codes.isEmpty()) {
            System.err.println("造数失败：一条短链都没创建成功");
            System.exit(1);
        }
        System.out.printf("   实际可用 %d 条%n", codes.size());

        System.out.println("== 预热 1s ==");
        warmUp(client, redirect, codes, threads);

        System.out.printf("== 压测：%d 线程 × %ds ==%n", threads, seconds);
        Result result = hammer(client, redirect, codes, threads, seconds);

        long elapsedNanos = TimeUnit.MILLISECONDS.toNanos(result.elapsedMillis);
        double qps = result.total * 1_000_000_000.0 / elapsedNanos;

        System.out.println();
        System.out.println("=== 结果 ===");
        System.out.printf("总请求 : %d%n", result.total);
        System.out.printf("耗时   : %d ms%n", result.elapsedMillis);
        System.out.printf("QPS    : %.0f%n", qps);
        System.out.printf("状态   : 302=%d  403=%d  404=%d  410=%d  5xx=%d  其他=%d%n",
                result.status302, result.status403, result.status404,
                result.status410, result.status5xx, result.other);
        if (result.total > 0) {
            double[] p = percentiles(result.latencies);
            System.out.printf("延迟   : P50=%.2fms  P95=%.2fms  P99=%.2fms  max=%.2fms%n",
                    p[0], p[1], p[2], p[3]);
        }
    }

    private static List<String> seed(HttpClient client, String admin, int count) throws Exception {
        List<String> codes = new CopyOnWriteArrayList<>();
        int threads = Math.min(16, Math.max(1, count / 100));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(count);
        AtomicLong seq = new AtomicLong();

        for (int i = 0; i < count; i++) {
            pool.submit(() -> {
                try {
                    long id = seq.incrementAndGet();
                    String body = "{\"longUrl\":\"https://example.com/perf/" + System.nanoTime() + "/" + id
                            + "\",\"title\":\"perf\"}";
                    HttpRequest request = HttpRequest.newBuilder(URI.create(admin + "/api/v1/links"))
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(10))
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        Matcher matcher = SHORT_CODE.matcher(response.body());
                        if (matcher.find()) {
                            codes.add(matcher.group(1));
                        }
                    }
                } catch (Exception ignored) {
                    // 单条失败不影响整体
                } finally {
                    done.countDown();
                }
            });
        }
        done.await(120, TimeUnit.SECONDS);
        pool.shutdownNow();
        return new ArrayList<>(codes);
    }

    private static void warmUp(HttpClient client, String redirect, List<String> codes, int threads)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long deadline = System.currentTimeMillis() + 1000;
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                while (System.currentTimeMillis() < deadline) {
                    try {
                        get(client, redirect, pick(codes));
                    } catch (Exception ignored) {
                    }
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }

    private static Result hammer(HttpClient client, String redirect, List<String> codes, int threads, int seconds)
            throws InterruptedException {
        Result result = new Result();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        long begin = System.currentTimeMillis();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                List<Long> localLatencies = new ArrayList<>(4096);
                long c302 = 0, c403 = 0, c404 = 0, c410 = 0, c5xx = 0, other = 0;
                try {
                    start.await();
                    while (System.currentTimeMillis() < deadline) {
                        String code = pick(codes);
                        long t0 = System.nanoTime();
                        int status;
                        try {
                            status = get(client, redirect, code);
                        } catch (Exception ex) {
                            status = -1;
                        }
                        localLatencies.add(System.nanoTime() - t0);
                        switch (status) {
                            case 302 -> c302++;
                            case 403 -> c403++;
                            case 404 -> c404++;
                            case 410 -> c410++;
                            case -1 -> other++;
                            default -> {
                                if (status >= 500) {
                                    c5xx++;
                                } else {
                                    other++;
                                }
                            }
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    result.merge(localLatencies, c302, c403, c404, c410, c5xx, other);
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await(seconds + 30, TimeUnit.SECONDS);
        pool.shutdownNow();
        result.elapsedMillis = System.currentTimeMillis() - begin;
        return result;
    }

    private static int get(HttpClient client, String redirect, String code) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(redirect + "/" + code))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static String pick(List<String> codes) {
        return codes.get(ThreadLocalRandom.current().nextInt(codes.size()));
    }

    private static String arg(String[] args, String name, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }

    private static double[] percentiles(List<Long> latencies) {
        long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        return new double[]{
                ms(sorted, 0.50),
                ms(sorted, 0.95),
                ms(sorted, 0.99),
                sorted[sorted.length - 1] / 1_000_000.0
        };
    }

    private static double ms(long[] sorted, double p) {
        int index = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))] / 1_000_000.0;
    }

    /** 汇总容器：每个线程把自己的样本并进来，避免压测期间加锁。 */
    private static final class Result {
        private final List<Long> latencies = new ArrayList<>();
        private long total;
        private long status302;
        private long status403;
        private long status404;
        private long status410;
        private long status5xx;
        private long other;
        private long elapsedMillis;

        synchronized void merge(List<Long> local, long c302, long c403, long c404,
                                long c410, long c5xx, long cOther) {
            latencies.addAll(local);
            total += local.size();
            status302 += c302;
            status403 += c403;
            status404 += c404;
            status410 += c410;
            status5xx += c5xx;
            other += cOther;
        }
    }
}
