import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;

/**
 * GenWriter RAG 检索链路性能压测（独立 main 程序，直连本机 PostgreSQL + pgvector）。
 *
 * 覆盖两层真实链路：
 *   1) pgvector 向量检索：复刻 KnowledgeChunkMapper.similaritySearch 语义
 *      （WHERE kb_id=? ORDER BY embedding <=> ?::vector LIMIT 5），HNSW 索引在线。
 *   2) 批量写入：多行 INSERT（模拟知识库导入批量落库）。
 *
 * 数据量档位：5k / 20k / 50k chunks（1024 维，与生产 VECTOR(1024) 一致），按 kb_id 分档隔离。
 * 指标：P50 / P90 / P99 / P999 延迟、QPS、吞吐。
 *
 * 用法：java RagBenchDriver  （需环境变量或默认连接串）
 */
public class RagBenchDriver {

    static final String URL = System.getProperty("pg.url", "jdbc:postgresql://localhost:5432/genwriter_db");
    static final String USER = System.getProperty("pg.user", "postgres");
    static final String PASS = System.getProperty("pg.pass", "123456");
    static final int DIM = 1024;
    static final int TOP_K = 5;                      // 与 genwriter.embedding.default-search-limit 一致
    static final int[] DATA_TIERS = {5_000, 20_000, 50_000};
    static final int[] CONCURRENCIES = {1, 4, 16};
    static final int WARMUP_QUERIES = 50;
    static final int MEASURE_QUERIES = 500;          // 每个(档位,并发)组合的采样查询数

    static final String[] KB_IDS = new String[DATA_TIERS.length];

    public static void main(String[] args) throws Exception {
        Class.forName("org.postgresql.Driver");
        System.out.println("== GenWriter RAG 压测 ==");
        System.out.println("target: " + URL);

        try (Connection conn = DriverManager.getConnection(URL, USER, PASS)) {
            ensurePgVector(conn);
            for (int i = 0; i < DATA_TIERS.length; i++) {
                KB_IDS[i] = prepareTier(conn, DATA_TIERS[i]);
            }
        }

        StringBuilder report = new StringBuilder();
        report.append("# RAG 检索链路性能压测报告\n\n");
        report.append("- 时间: ").append(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
        report.append("- 目标: PostgreSQL ").append(connMeta()).append(" + pgvector (HNSW, cosine)\n");
        report.append("- 向量维度: ").append(DIM).append("，Top-").append(TOP_K)
                .append("（与生产 default-search-limit 一致）\n");
        report.append("- 数据: 合成写作语料 chunk，按 kb_id 分档隔离\n\n");

        // ---------- 检索压测 ----------
        report.append("## 一、向量检索（pgvector <=> 余弦距离，Top-5）\n\n");
        report.append("| 数据量 | 并发 | QPS | P50(ms) | P90(ms) | P99(ms) | P999(ms) | max(ms) |\n");
        report.append("|---|---|---|---|---|---|---|---|\n");
        System.out.println("\n-- 向量检索压测 --");
        for (int tier = 0; tier < DATA_TIERS.length; tier++) {
            for (int conc : CONCURRENCIES) {
                BenchResult r = benchSearch(KB_IDS[tier], conc);
                System.out.printf("rows=%d conc=%d qps=%.1f p50=%.2f p90=%.2f p99=%.2f%n",
                        DATA_TIERS[tier], conc, r.qps, r.p50, r.p90, r.p99);
                report.append(String.format("| %d | %d | %.1f | %.2f | %.2f | %.2f | %.2f | %.2f |%n",
                        DATA_TIERS[tier], conc, r.qps, r.p50, r.p90, r.p99, r.p999, r.max));
            }
        }

        // ---------- 写入压测 ----------
        System.out.println("\n-- 批量写入压测 --");
        report.append("\n## 二、批量写入（多行 INSERT，批大小 100）\n\n");
        report.append("| 目标表规模 | 行数 | 总耗时(s) | 行/秒 |\n");
        report.append("|---|---|---|---|\n");
        long insertTotal = 0;
        for (int tier = 0; tier < DATA_TIERS.length; tier++) {
            int rows = 2_000;
            double rps = benchBatchInsert(KB_IDS[tier], rows);
            insertTotal += rows;
            System.out.printf("tier=%d insert %d rows -> %.0f rows/s%n", DATA_TIERS[tier], rows, rps);
            report.append(String.format("| %d（追加） | %d | %.2f | %.0f |%n", DATA_TIERS[tier], rows, rows / rps, rps));
        }

        // ---------- 清理 ----------
        try (Connection conn = DriverManager.getConnection(URL, USER, PASS)) {
            for (String kb : KB_IDS) cleanup(conn, kb);
        }

        // ---------- 输出 ----------
        String out = System.getProperty("user.home") + "/Desktop/八股速记/项目改进/RAG性能压测报告-"
                + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md";
        java.nio.file.Files.writeString(java.nio.file.Path.of(out), report.toString(),
                java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("\n[DONE] 报告已写入: " + out);
    }

    // -------------------------------------------------------------------------

    static void ensurePgVector(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            ResultSet r = s.executeQuery("SELECT count(*) FROM pg_extension WHERE extname='vector'");
            r.next();
            if (r.getInt(1) == 0) {
                s.execute("CREATE EXTENSION IF NOT EXISTS vector");
            }
        }
    }

    /** 建档位表数据：一个独立 kb_id + N 行 chunk + HNSW 索引 */
    static String prepareTier(Connection conn, int rows) throws SQLException {
        String kbId = java.util.UUID.randomUUID().toString();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO knowledge_base (id, name, description, created_at, updated_at) " +
                "VALUES (CAST(? AS uuid), ?, ?, now(), now())")) {
            ps.setString(1, kbId);
            ps.setString(2, "bench-" + rows);
            ps.setString(3, "压测档位 " + rows);
            ps.executeUpdate();
        }
        long t0 = System.nanoTime();
        insertChunks(conn, kbId, rows);
        double sec = (System.nanoTime() - t0) / 1e9;
        System.out.printf("prepared tier rows=%d in %.1fs (%.0f rows/s)%n", rows, sec, rows / sec);
        // 与生产一致的全局 HNSW 索引（pgvector 0.8 部分索引不支持 vector_cosine_ops）
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE INDEX IF NOT EXISTS idx_bench_hnsw_" + rows +
                    " ON knowledge_chunk USING hnsw (embedding vector_cosine_ops)");
        }
        return kbId;
    }

    static void insertChunks(Connection conn, String kbId, int rows) throws SQLException {
        String sql = "INSERT INTO knowledge_chunk (id, kb_id, content, embedding, embedding_dimension, " +
                "embedding_model, metadata, created_at, updated_at) VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, " +
                "CAST(? AS vector), ?, 'bench', '{}', now(), now())";
        Random rnd = new Random(42);
        List<String> vocab = vocab();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            int batch = 0;
            for (int i = 0; i < rows; i++) {
                ps.setString(1, java.util.UUID.randomUUID().toString());
                ps.setString(2, kbId);
                ps.setString(3, randomText(vocab, rnd, 80 + rnd.nextInt(200)));
                ps.setString(4, randomVector(rnd));
                ps.setInt(5, DIM);
                ps.addBatch();
                if (++batch % 500 == 0) { ps.executeBatch(); batch = 0; }
            }
            ps.executeBatch();
            conn.setAutoCommit(true);
        }
    }

    static double benchBatchInsert(String kbId, int rows) throws SQLException {
        String sql = "INSERT INTO knowledge_chunk (id, kb_id, content, embedding, embedding_dimension, " +
                "embedding_model, metadata, created_at, updated_at) VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, " +
                "CAST(? AS vector), ?, 'bench-ins', '{}', now(), now())";
        Random rnd = new Random(7);
        List<String> vocab = vocab();
        long t0 = System.nanoTime();
        try (PreparedStatement ps = conn(kbId).prepareStatement(sql)) {
            Connection c = ps.getConnection();
            c.setAutoCommit(false);
            int batch = 0;
            for (int i = 0; i < rows; i++) {
                ps.setString(1, java.util.UUID.randomUUID().toString());
                ps.setString(2, kbId);
                ps.setString(3, randomText(vocab, rnd, 80 + rnd.nextInt(200)));
                ps.setString(4, randomVector(rnd));
                ps.setInt(5, DIM);
                ps.addBatch();
                if (++batch % 100 == 0) { ps.executeBatch(); batch = 0; }
            }
            ps.executeBatch();
            c.setAutoCommit(true);
        }
        return rows / ((System.nanoTime() - t0) / 1e9);
    }

    /** 并发检索压测：warmup 后采样 MEASURE_QUERIES 次查询，统计延迟分位 */
    static BenchResult benchSearch(String kbId, int concurrency) throws Exception {
        Random rnd = new Random(123 + concurrency);
        List<String> queries = new ArrayList<>();
        for (int i = 0; i < WARMUP_QUERIES + MEASURE_QUERIES; i++) {
            queries.add(randomVector(rnd));
        }
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);

        for (int w = 0; w < concurrency; w++) {
            final int worker = w;
            pool.submit(() -> {
                try (Connection c = DriverManager.getConnection(URL, USER, PASS)) {
                    PreparedStatement ps = c.prepareStatement(
                            "SELECT id, content FROM knowledge_chunk WHERE kb_id = CAST(? AS uuid) " +
                            "ORDER BY embedding <=> CAST(? AS vector) LIMIT " + TOP_K);
                    ready.countDown();
                    start.await();
                    for (int i = worker; i < WARMUP_QUERIES + MEASURE_QUERIES; i += concurrency) {
                        if (i >= WARMUP_QUERIES) {
                            long t0 = System.nanoTime();
                            ps.setString(1, kbId);
                            ps.setString(2, queries.get(i));
                            try (ResultSet rs = ps.executeQuery()) {
                                while (rs.next()) rs.getString(1);
                            }
                            latencies.add(System.nanoTime() - t0);
                        } else {
                            ps.setString(1, kbId);
                            ps.setString(2, queries.get(i));
                            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) rs.getString(1); }
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        done.await();
        pool.shutdown();

        List<Long> sorted = new ArrayList<>(latencies);
        java.util.Collections.sort(sorted);
        int n = sorted.size();
        double p50 = sorted.get((int) (n * 0.50)) / 1e6;
        double p90 = sorted.get((int) (n * 0.90)) / 1e6;
        double p99 = sorted.get((int) (n * 0.99)) / 1e6;
        double p999 = sorted.get((int) Math.min(n - 1, (int) (n * 0.999))) / 1e6;
        double max = sorted.get(n - 1) / 1e6;
        double qps = n / (sorted.stream().mapToLong(l -> l).sum() / 1e9);
        return new BenchResult(qps, p50, p90, p99, p999, max);
    }

    static Connection conn(String kbId) throws SQLException {
        return DriverManager.getConnection(URL, USER, PASS);
    }

    static void cleanup(Connection conn, String kbId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM knowledge_chunk WHERE kb_id=CAST(? AS uuid)")) {
            ps.setString(1, kbId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM knowledge_base WHERE id=CAST(? AS uuid)")) {
            ps.setString(1, kbId);
            ps.executeUpdate();
        }
    }

    record BenchResult(double qps, double p50, double p90, double p99, double p999, double max) {}

    /** 生成归一化随机向量字面量（1024 维，'[x,y,...]' 格式） */
    static String randomVector(Random rnd) {
        StringBuilder sb = new StringBuilder(DIM * 10);
        sb.append('[');
        for (int i = 0; i < DIM; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format("%.6f", rnd.nextGaussian()));
        }
        sb.append(']');
        return sb.toString();
    }

    static String randomText(List<String> vocab, Random rnd, int len) {
        StringBuilder sb = new StringBuilder(len + 16);
        while (sb.length() < len) {
            sb.append(vocab.get(rnd.nextInt(vocab.size())));
        }
        return sb.toString();
    }

    static List<String> vocab() {
        return List.of("写作", "情节", "人物", "冲突", "悬念", "对话", "描写", "视角", "节奏", "结构",
                "大纲", "初稿", "润色", "修订", "读者", "主角", "配角", "反派", "场景", "伏笔",
                "情感", "张力", "主题", "灵感", "素材", "修改", "阅读", "故事", "叙事", "语言");
    }

    static String connMeta() {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT version()")) {
            r.next();
            return r.getString(1).split(",")[0].replace("PostgreSQL ", "");
        } catch (SQLException e) {
            return "unknown";
        }
    }
}
