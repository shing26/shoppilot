import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

/**
 * 轻量发压器：只用来回答一个问题——Locust 的四进程同机发压是不是客户端先饱和了。
 *
 * <p>这不是替代 {@code loadtest/locustfile.py} 的口径。它刻意不做 Locust 的统计、事件与
 * 多进程编排，只用 JDK 自带的 HttpClient + 虚拟线程把请求打满，让服务端多拿几个核。
 * 结果必须和 Locust 同一档并发、同一流量模型、同一台机器、同一份网关配置对照着看。
 *
 * <p>用法：
 * <pre>
 *   java loadtest/AsyncLoadGen.java --base http://127.0.0.1:8082 --model mix80 \
 *        --concurrency 800 --duration 45 --warmup 5
 * </pre>
 * 输出一行 JSON：并发、模型、窗口内成功数、QPS、p50/p95/p99、错误数。
 */
public final class AsyncLoadGen {

    private static final String[] HOT = {
            "发什么快递", "七天无理由怎么算", "跨店满减怎么算", "生鲜坏了怎么赔",
            "多久发货", "可以指定顺丰吗", "优惠券能和满减一起用吗", "退款多久到账"
    };
    private static final String[] PARAPHRASE = {
            "你们家用的哪家物流", "无理由退换的七天从哪天开始算", "满300减50是几个店凑单的",
            "菜收到已经烂了要怎么理赔", "下单后几天能寄出", "我想发顺丰能不能备注",
            "店铺券和跨店优惠可以叠加不", "钱几天能退回我卡上", "快递一般选哪一家",
            "七天是从签收那天开始吗", "凑够300是不同店的一起算吗", "水果坏果能赔多少钱"
    };
    private static final String[] ACTIONS = {
            "90001 这单现在什么状态", "帮我查下订单 90002", "90002 的快递到哪了",
            "订单号 90003 麻烦看下进度", "90003 签收了吗", "查一下 90002 的物流轨迹"
    };
    private static final String[] LONGTAIL = {
            "定制商品支持无理由退货吗", "赠品需要一起退回吗", "价保申请后差价退到哪里",
            "定金膨胀和跨店满减能同时享受吗", "冷链断链导致化冻怎么界定责任",
            "大件商品上门取退怎么预约", "拆封后影响二次销售还能退吗", "预售尾款没付定金会退吗",
            "港澳台地区能下单发货吗", "验货后拒收运费谁承担"
    };

    /** 与 locustfile.py 的 WEIGHTS 同义：{hot, para, action, long} 的整数权重。 */
    private static int[] weights(String model) {
        return switch (model) {
            case "l1" -> new int[]{55, 15, 15, 15};
            case "l2" -> new int[]{15, 55, 15, 15};
            case "mix80" -> new int[]{80, 0, 10, 10};
            case "biz" -> new int[]{0, 0, 100, 0};
            default -> throw new IllegalArgumentException("未知流量模型: " + model);
        };
    }

    public static void main(String[] args) throws Exception {
        String base = "http://127.0.0.1:8082";
        String model = "mix80";
        int concurrency = 800;
        int duration = 45;
        int warmup = 5;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--base" -> base = args[++i];
                case "--model" -> model = args[++i];
                case "--concurrency" -> concurrency = Integer.parseInt(args[++i]);
                case "--duration" -> duration = Integer.parseInt(args[++i]);
                case "--warmup" -> warmup = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("未知参数: " + args[i]);
            }
        }

        final String endpoint = base;
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
                .build();

        String[][] tenants = {{"T001", "C001"}, {"T002", "C200"}, {"T003", "C300"}};
        List<String[]> accounts = new ArrayList<>();
        for (String[] tenant : tenants) {
            String token = mint(http, base, tenant[0], tenant[1]);
            if (token != null) {
                accounts.add(new String[]{tenant[0], tenant[1], token});
            }
        }
        if (accounts.isEmpty()) {
            throw new IllegalStateException("一个 token 都没领到，确认网关与 /auth/mock-token 在跑");
        }

        int[] weight = weights(model);
        int totalWeight = Arrays.stream(weight).sum();
        long startedAt = System.nanoTime();
        long measureAt = startedAt + Duration.ofSeconds(warmup).toNanos();
        long stopAt = measureAt + Duration.ofSeconds(duration).toNanos();
        LongAdder ok = new LongAdder();
        LongAdder failed = new LongAdder();
        List<List<Long>> samples = new ArrayList<>(concurrency);
        List<Thread> workers = new ArrayList<>(concurrency);

        for (int worker = 0; worker < concurrency; worker++) {
            List<Long> mine = new ArrayList<>(64);
            samples.add(mine);
            String[] account = accounts.get(worker % accounts.size());
            workers.add(Thread.ofVirtual().name("load-" + worker).start(() -> {
                long id = 0;
                while (System.nanoTime() < stopAt) {
                    String query = pick(weight, totalWeight);
                    long seq = id++;
                    long begin = System.nanoTime();
                    boolean success = false;
                    try {
                        String body = "{\"query\":\"" + query + "\",\"idempotencyToken\":\"async-"
                                + seq + "-" + ThreadLocalRandom.current().nextInt(1_000_000) + "\"}";
                        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/api/v1/support/chat"))
                                .timeout(Duration.ofSeconds(120))
                                .header("Content-Type", "application/json; charset=utf-8")
                                .header("Authorization", "Bearer " + account[2])
                                .header("X-Conversation-Id", "async-" + account[0] + "-" + seq)
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build();
                        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                        success = response.statusCode() == 200
                                && response.body().contains("\"answer\"")
                                && !response.body().contains("\"answer\":\"\"");
                    } catch (Exception ignored) {
                        success = false;
                    }
                    long took = System.nanoTime() - begin;
                    if (begin >= measureAt) {
                        if (success) {
                            ok.increment();
                        } else {
                            failed.increment();
                        }
                        mine.add(took / 1_000_000);
                    }
                }
            }));
        }
        for (Thread worker : workers) {
            worker.join();
        }

        List<Long> all = new ArrayList<>();
        for (List<Long> mine : samples) {
            all.addAll(mine);
        }
        all.sort(Long::compare);
        long successes = ok.sum();
        double seconds = Math.max(0.001, (stopAt - measureAt) / 1_000_000_000.0);
        System.out.println(String.format(Locale.ROOT,
                "{\"model\":\"%s\",\"concurrency\":%d,\"seconds\":%.1f,\"ok\":%d,\"failed\":%d,"
                        + "\"qps\":%.2f,\"p50_ms\":%d,\"p95_ms\":%d,\"p99_ms\":%d,\"samples\":%d}",
                model, concurrency, seconds, successes, failed.sum(), successes / seconds,
                percentile(all, 0.50), percentile(all, 0.95), percentile(all, 0.99), all.size()));
    }

    private static String pick(int[] weight, int total) {
        int ticket = ThreadLocalRandom.current().nextInt(1, total + 1);
        int kind = 0;
        for (int i = 0; i < weight.length; i++) {
            if (ticket <= weight[i]) {
                kind = i;
                break;
            }
            ticket -= weight[i];
        }
        return switch (kind) {
            case 0 -> HOT[ThreadLocalRandom.current().nextInt(HOT.length)];
            case 1 -> PARAPHRASE[ThreadLocalRandom.current().nextInt(PARAPHRASE.length)];
            case 2 -> ACTIONS[ThreadLocalRandom.current().nextInt(ACTIONS.length)];
            default -> LONGTAIL[ThreadLocalRandom.current().nextInt(LONGTAIL.length)];
        };
    }

    private static long percentile(List<Long> ordered, double share) {
        if (ordered.isEmpty()) {
            return 0;
        }
        int at = (int) Math.round(share * (ordered.size() - 1));
        return ordered.get(Math.max(0, Math.min(ordered.size() - 1, at)));
    }

    private static String mint(HttpClient http, String base, String tenant, String customer) {
        try {
            String body = "{\"tenantId\":\"" + tenant + "\",\"customerId\":\"" + customer + "\"}";
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/auth/mock-token"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(response.body());
            return matcher.find() ? matcher.group(1) : null;
        } catch (Exception failure) {
            return null;
        }
    }
}
