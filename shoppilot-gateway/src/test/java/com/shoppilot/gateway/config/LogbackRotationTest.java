package com.shoppilot.gateway.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.status.Status;
import com.shoppilot.gateway.identity.RequestTrace;
import com.shoppilot.gateway.identity.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日志落盘与轮转（ADR 0027，票 24）。
 *
 * <p>这一格防的不是"没配轮转"，而是配了纯按时间的 {@code TimeBasedRollingPolicy} 就以为按大小也转了：
 * 那种配置演示里永远不炸，只在某一次把整段 trace 打进一行日志时把盘写满。所以这里既读配置文本，
 * 也真把文件写过阈值，要求它当场产出归档。
 *
 * <p>配置用裸 {@link JoranConfigurator} 解析，不起 Spring 容器。前提是 {@code logback-spring.xml}
 * 只用原生 logback 属性——掺进 {@code <springProperty>} 这类标签，这格就没人守得住了。
 */
class LogbackRotationTest {

    private static final String CONFIG = "/logback-spring.xml";
    /** 一行约 400 字节，20 行足够越过下面设的 1KB 阈值。 */
    private static final String BULKY_LINE = "政策问答落盘轮转验证".repeat(24);

    @TempDir
    Path dir;

    private LoggerContext context;

    @AfterEach
    void releaseContext() {
        // 不停掉的话 Windows 上文件句柄还开着，@TempDir 清不干净，下一格被"访问被拒绝"绊倒
        if (context != null) {
            context.stop();
            context = null;
        }
        System.clearProperty("LOG_DIR");
        System.clearProperty("LOG_APP");
        System.clearProperty("LOG_MAX_FILE_SIZE");
        RequestTrace.clear();
        // 归档是 .gz：logback 把压缩丢在自己的执行器线程上，context.stop() 只不等它释放句柄。
        // 实测 172 格里这一格偶发判红（Failed to delete temp directory ... ingest-*.log.gz），
        // 所以这里带截止时间自己删一遍，JUnit 再看到这个目录时已经是空的，无句柄可争。
        deleteWithRetries(dir);
    }

    /** 尽力删临时目录：删不动就退避重试，总预算 5 秒；删不干净也不掩盖，留给 JUnit 自己报错。 */
    private static void deleteWithRetries(Path root) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            AtomicBoolean remaining = new AtomicBoolean(false);
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        deleteIfPresent(file, remaining);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException failure) {
                        if (!(failure instanceof NoSuchFileException)) {
                            remaining.set(true);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path directory, IOException failure) {
                        if (failure != null && !(failure instanceof NoSuchFileException)) {
                            remaining.set(true);
                        } else {
                            deleteIfPresent(directory, remaining);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (NoSuchFileException alreadyGone) {
                return;
            } catch (IOException stillHeld) {
                remaining.set(true);
            }
            if (!remaining.get() && Files.notExists(root)) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void deleteIfPresent(Path path, AtomicBoolean remaining) {
        try {
            Files.deleteIfExists(path);
        } catch (NoSuchFileException alreadyGone) {
            // 压缩线程可能刚把临时文件移走；不存在就是清理已经达成。
        } catch (IOException stillHeld) {
            remaining.set(true);
        }
    }

    @Test
    @DisplayName("配置文本齐备：按大小与时间两条策略、四个坐标键、控制台 appender 一个都不能少")
    void configTextDeclaresBothPoliciesAndTheFourKeys() throws Exception {
        String text = configText();

        // 纯按时间也能配出 %d，所以两条都要：既有日期又有序号，才说明大小这一维真的在管
        assertThat(text).contains("SizeAndTimeBasedRollingPolicy");
        assertThat(text).contains("<maxFileSize>").contains("%i").contains("%d{");
        assertThat(text).contains("%X{traceId").contains("%X{tenantId")
                .contains("%X{customerId").contains("%X{conversationId");
        // 摘掉控制台 appender 会让实验面"grep stdout 判 profile 有没有换上"那一步失明，当场钉住
        assertThat(text).contains("ch.qos.logback.core.ConsoleAppender");
    }

    @Test
    @DisplayName("超过 maxFileSize 当场产出归档，文件名跟着 LOG_APP 走")
    void rollsOnSizeAndHonoursAppName() throws Exception {
        System.setProperty("LOG_DIR", dir.toString());
        // 入库进程与常驻网关跑的是同一份 jar：靠 LOG_APP 分名，两个进程不去轮转同一个文件
        System.setProperty("LOG_APP", "ingest");
        System.setProperty("LOG_MAX_FILE_SIZE", "1KB");
        context = configuredContext();

        RollingFileAppender<ILoggingEvent> file = fileAppender();
        // logback 会把分隔符归一成正斜杠，直接比字符串在 Windows 上永远不相等
        assertThat(Path.of(file.getFile())).isEqualTo(dir.resolve("ingest.log"));
        assertThat(file.getRollingPolicy()).isInstanceOf(SizeAndTimeBasedRollingPolicy.class);

        Logger probe = context.getLogger("rotation-probe");
        for (int i = 0; i < 20; i++) {
            probe.info(BULKY_LINE);
        }

        assertThat(rolledFiles("ingest-")).as("写过 1KB 阈值之后必须留下归档文件").isNotEmpty();
    }

    @Test
    @DisplayName("四个坐标由落盘 appender 自己的编码器渲染出来，不是只写在模板字符串里")
    void renderedLineCarriesTheFourCoordinates() throws Exception {
        System.setProperty("LOG_DIR", dir.toString());
        context = configuredContext();

        String traceId = RequestTrace.start();
        RequestTrace.bind(new TenantContext.Identity("T001", "C155", "conv-1"));
        Logger logger = context.getLogger("render-probe");
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        logger.addAppender(capture);
        logger.info("这一行必须认得回自己是谁");

        assertThat(capture.list).as("坐标没进 MDC 的话，落盘行只能是一串空键").hasSize(1);
        String rendered = new String(fileAppender().getEncoder().encode(capture.list.get(0)),
                StandardCharsets.UTF_8);
        // 链路号是 UUID 形状：装填点没跑、或者被上一单残留顶掉，这里就会露馅
        assertThat(traceId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(rendered).contains("traceId=" + traceId)
                .contains("tenantId=T001").contains("customerId=C155")
                .contains("conversationId=conv-1")
                .contains("这一行必须认得回自己是谁");
    }

    @Test
    @DisplayName("root 上控制台与文件两个 appender 并存：门禁与实验面靠 grep stdout 判 profile")
    void consoleAndFileAppendersBothAttachedToRoot() throws Exception {
        System.setProperty("LOG_DIR", dir.toString());
        context = configuredContext();

        List<Appender<ILoggingEvent>> appenders = appendersOnRoot();

        assertThat(appenders).anyMatch(ConsoleAppender.class::isInstance);
        assertThat(appenders).anyMatch(RollingFileAppender.class::isInstance);
        assertThat(appenders).allMatch(Appender::isStarted);
    }

    @Test
    @DisplayName("不引 context-propagation：跨线程靠手工 wrap，这条取舍由类路径本身守住")
    void contextPropagationLibraryStaysOffTheClasspath() {
        for (String className : List.of("io.micrometer.context.ThreadLocalAccessor",
                "io.micrometer.context.ContextSnapshotFactory")) {
            boolean present;
            try {
                Class.forName(className);
                present = true;
            } catch (ClassNotFoundException absent) {
                present = false;
            }
            assertThat(present).as("类路径上出现了 %s：票 24 明写不引 context-propagation", className).isFalse();
        }
    }

    /** 解析配置并确认它自己没报 ERROR：写坏一个标签名要当场红，而不是静默退回 logback 默认配置。 */
    private LoggerContext configuredContext() throws Exception {
        LoggerContext fresh = new LoggerContext();
        // 裸 new 出来的上下文不带 MDC 适配器，%X 转换器渲染时会当场 NPE；接上全局那一个，
        // 它看到的坐标才与 MDC.put 的那一份同源
        fresh.setMDCAdapter(org.slf4j.MDC.getMDCAdapter());
        fresh.setName("logback-rotation-probe");
        fresh.start();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(fresh);
        try (InputStream in = LogbackRotationTest.class.getResourceAsStream(CONFIG)) {
            assertThat(in).as("配置文件不在 classpath：" + CONFIG).isNotNull();
            configurator.doConfigure(in);
        }
        List<String> errors = new ArrayList<>();
        for (Status status : fresh.getStatusManager().getCopyOfStatusList()) {
            if (status.getLevel() >= Status.ERROR) {
                errors.add(status.getThrowable() == null
                        ? status.getMessage() : status.getMessage() + " / " + status.getThrowable());
            }
        }
        assertThat(errors).as("logback-spring.xml 自身解析报错").isEmpty();
        return fresh;
    }

    private String configText() throws Exception {
        try (InputStream in = LogbackRotationTest.class.getResourceAsStream(CONFIG)) {
            return new String(Objects.requireNonNull(in, CONFIG).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<Appender<ILoggingEvent>> appendersOnRoot() {
        List<Appender<ILoggingEvent>> appenders = new ArrayList<>();
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).iteratorForAppenders()
                .forEachRemaining(appenders::add);
        return appenders;
    }

    private RollingFileAppender<ILoggingEvent> fileAppender() {
        List<Appender<ILoggingEvent>> files = new ArrayList<>();
        for (Appender<ILoggingEvent> appender : appendersOnRoot()) {
            if (appender instanceof RollingFileAppender<?>) {
                files.add(appender);
            }
        }
        assertThat(files).as("root 上必须恰好挂着一个 RollingFileAppender").hasSize(1);
        @SuppressWarnings("unchecked")
        RollingFileAppender<ILoggingEvent> file = (RollingFileAppender<ILoggingEvent>) files.get(0);
        return file;
    }

    /** 归档会先落成 .tmp 再压成 .gz，两种都算数。 */
    private List<Path> rolledFiles(String prefix) throws Exception {
        for (int attempt = 0; attempt < 60; attempt++) {
            try (Stream<Path> files = Files.list(dir)) {
                List<Path> rolled = files.filter(path -> path.getFileName().toString().startsWith(prefix)).toList();
                if (!rolled.isEmpty()) {
                    return rolled;
                }
            }
            Thread.sleep(50);
        }
        return List.of();
    }
}
