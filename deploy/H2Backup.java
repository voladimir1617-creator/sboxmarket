/*
 * Hot backup of the live H2 money database, and the read-back that proves it.
 *
 * WHY THIS EXISTS. `deploy/backup-db.sh` is the Postgres path. This deployment
 * does not run Postgres -- it runs H2 at data/sboxmarket.mv.db, and until now
 * NOTHING backed that file up on a schedule. The three archives in
 * ~/skinbox-backups were all taken by hand on 2026-09-02.
 *
 * WHY A CLIENT AND NOT A FILE COPY. The running app holds an exclusive OS handle
 * on the .mv.db; a plain copy fails, and an embedded open from a second process
 * is refused with 90020 "Database may be already in use ... use the server mode".
 * AUTO_SERVER=TRUE in the datasource URL is that server mode. A client opening
 * the SAME path is handed the server key and transparently reconnects over the
 * loopback TCP server, and BACKUP TO then runs server-side. That is the route
 * deploy/RUNBOOK.md documents and the one tonight's three backups used; this
 * file automates it rather than inventing a second one.
 *
 * WHY TWO PHASES. `backup` and `verify` are separate commands run as separate
 * processes, communicating through a sidecar file beside the archive. That is
 * not ceremony: it is the only way to TEST the verifier honestly. A test can run
 * `backup`, swap the archive for one taken from a different database, and run
 * `verify` -- proving the read-back actually reads, with no test hook anywhere in
 * this file. A single-process design can only be tested by trusting itself.
 *
 * WHY THE COMPARISON IS A RANGE AND NOT AN EQUALITY. Row counts are read from
 * live immediately BEFORE and immediately AFTER BACKUP TO. The archive is a
 * snapshot from somewhere inside that window, so each restored count must land
 * within [min(before,after), max(before,after)]. On a quiet machine before==after
 * and this collapses to strict equality -- the strongest possible check. Under
 * concurrent writes it stays correct instead of failing a perfectly good backup,
 * which matters because a check that cries wolf gets switched off.
 *
 * NO CREDENTIAL EVER CROSSES A COMMAND LINE. The password is read from the
 * H2_PASSWORD environment variable. That is deliberate twice over: it keeps the
 * secret out of the process table, and it sidesteps the failure that cost an
 * hour tonight -- PowerShell 5.1 silently DROPS an empty-string argument to a
 * native executable, so `-password "" -sql "..."` shifts the SQL into the
 * password slot. The live SA password is empty today, so that trap is not
 * hypothetical here. Both phases print the password LENGTH they actually used,
 * because a length is the only way to see an argument that vanished.
 */

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class H2Backup {

    /** A backup smaller than this is a failure wearing a backup's name. */
    static final long MIN_ZIP_BYTES = 1024L;
    /** An H2 store file below this cannot hold 35 tables of anything. */
    static final long MIN_MVDB_BYTES = 4096L;

    public static void main(String[] args) {
        int rc;
        try {
            if (args.length < 1) throw new IllegalArgumentException(
                    "usage: H2Backup <backup|verify>  (configuration is read from the environment)");
            String mode = args[0];
            if ("backup".equals(mode))      rc = doBackup();
            else if ("verify".equals(mode)) rc = doVerify();
            else throw new IllegalArgumentException("unknown mode " + mode + " (expected backup|verify)");
        } catch (Throwable t) {
            // Every failure path lands here and prints a machine-readable line, so the
            // caller never has to infer a reason from an exit code alone.
            out("ERROR", oneLine(String.valueOf(t)));
            rc = 1;
        }
        System.out.flush();
        System.exit(rc);
    }

    // ------------------------------------------------------------------ phase 1

    static int doBackup() throws Exception {
        String url  = required("H2_URL");
        String user = env("H2_USER", "sa");
        String pw   = env("H2_PASSWORD", "");
        Path zip    = Paths.get(required("H2_BACKUP_ZIP")).toAbsolutePath();
        Path live   = Paths.get(required("H2_LIVE_MVDB")).toAbsolutePath();

        out("MODE", "backup");
        out("URL", url);
        // Print what we actually used, never the value. An argument that PowerShell
        // dropped shows up here as a length that is not the length you set.
        out("PASSWORD_LEN", String.valueOf(pw.length()));
        out("USER", user);
        out("ZIP", zip.toString());

        if (Files.exists(zip)) Files.delete(zip);           // our own .part from a prior failed run
        Files.createDirectories(zip.getParent());

        Map<String, Long> before, after;
        long t0 = System.currentTimeMillis();
        try (Connection c = DriverManager.getConnection(url, user, pw)) {
            out("CONNECTED", "true");
            before = countAll(c);
            out("TABLES", String.valueOf(before.size()));
            if (before.isEmpty())
                throw new IllegalStateException("the live database reports ZERO tables -- refusing to "
                        + "call an empty schema a backup (wrong database, or the wrong path opened)");

            String target = zip.toString().replace("\\", "/").replace("'", "''");
            try (Statement s = c.createStatement()) {
                s.execute("BACKUP TO '" + target + "'");
            }
            out("BACKUP_TO", "ok");
            after = countAll(c);
        }
        out("ELAPSED_MS", String.valueOf(System.currentTimeMillis() - t0));

        long zipBytes = Files.exists(zip) ? Files.size(zip) : -1L;
        out("ZIP_BYTES", String.valueOf(zipBytes));
        if (zipBytes < MIN_ZIP_BYTES)
            throw new IllegalStateException("archive is " + zipBytes + " bytes, under the "
                    + MIN_ZIP_BYTES + "-byte floor");

        long liveBytes = Files.exists(live) ? Files.size(live) : -1L;
        out("LIVE_MVDB_BYTES", String.valueOf(liveBytes));

        // The sidecar is the handoff to `verify`, and the reason the two phases can be
        // separate processes. Properties, not JSON: escaping is already solved and there
        // is no parser here to get wrong.
        Properties p = new Properties();
        p.setProperty("zip", zip.toString());
        p.setProperty("zip.bytes", String.valueOf(zipBytes));
        p.setProperty("live.mvdb.bytes", String.valueOf(liveBytes));
        p.setProperty("taken.at", java.time.Instant.now().toString());
        for (String t : union(before, after))
            p.setProperty("table." + t, n(before.get(t)) + "," + n(after.get(t)));
        try (Writer w = Files.newBufferedWriter(expectedFor(zip), StandardCharsets.UTF_8)) {
            p.store(w, "expected row counts: table=<before>,<after> around the BACKUP TO");
        }
        out("EXPECTED_FILE", expectedFor(zip).toString());

        // Surfaced by name because these four are the money and the audit trail; the
        // verifier still compares every table it finds.
        for (String t : new String[] { "STEAM_USERS", "WALLETS", "TRANSACTIONS", "AUDIT_LOG" })
            if (before.containsKey(t)) out("LIVE_" + t, String.valueOf(before.get(t)));

        out("BACKUP_OK", "true");
        return 0;
    }

    // ------------------------------------------------------------------ phase 2

    static int doVerify() throws Exception {
        Path zip  = Paths.get(required("H2_BACKUP_ZIP")).toAbsolutePath();
        Path work = Paths.get(required("H2_VERIFY_DIR")).toAbsolutePath();
        String user = env("H2_USER", "sa");
        String pw   = env("H2_PASSWORD", "");

        out("MODE", "verify");
        out("ZIP", zip.toString());
        out("PASSWORD_LEN", String.valueOf(pw.length()));

        if (!Files.exists(zip)) throw new IllegalStateException("no archive at " + zip);
        long zipBytes = Files.size(zip);
        out("ZIP_BYTES", String.valueOf(zipBytes));
        if (zipBytes < MIN_ZIP_BYTES)
            throw new IllegalStateException("archive is " + zipBytes + " bytes, under the "
                    + MIN_ZIP_BYTES + "-byte floor");

        Properties expected = new Properties();
        Path exp = expectedFor(zip);
        if (!Files.exists(exp))
            throw new IllegalStateException("no expected-counts sidecar at " + exp
                    + " -- cannot verify an archive against nothing");
        try (Reader r = Files.newBufferedReader(exp, StandardCharsets.UTF_8)) { expected.load(r); }

        // Fresh extraction directory every run. Created here, removed by the caller.
        if (Files.exists(work)) deleteTree(work);
        Files.createDirectories(work);

        String dbName = null;
        long mvdbBytes = -1L;
        List<String> entries = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                if (ze.isDirectory()) continue;
                // Basename only: never trust a path inside an archive (zip-slip).
                String base = new File(ze.getName().replace('\\', '/')).getName();
                entries.add(base);
                Path target = work.resolve(base);
                try (InputStream in = zf.getInputStream(ze)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                if (base.endsWith(".mv.db")) {
                    dbName = base.substring(0, base.length() - ".mv.db".length());
                    mvdbBytes = Files.size(target);
                }
            }
        }
        out("ZIP_ENTRIES", String.join(";", entries));
        if (dbName == null)
            throw new IllegalStateException("the archive contains no .mv.db store file (entries: "
                    + String.join(";", entries) + ")");
        out("RESTORED_DB", dbName);
        out("RESTORED_MVDB_BYTES", String.valueOf(mvdbBytes));
        if (mvdbBytes < MIN_MVDB_BYTES)
            throw new IllegalStateException("restored store file is " + mvdbBytes
                    + " bytes, under the " + MIN_MVDB_BYTES + "-byte floor");

        // Open the extracted copy for real. No AUTO_SERVER (a verification run must not
        // start a listener of its own) and IFEXISTS so a wrong path can never be answered
        // by silently creating an empty database and counting its zero rows as a match.
        String rurl = "jdbc:h2:file:" + work.resolve(dbName).toString().replace("\\", "/")
                    + ";MODE=PostgreSQL;IFEXISTS=TRUE";
        out("RESTORED_URL", rurl);
        Map<String, Long> restored;
        try (Connection c = DriverManager.getConnection(rurl, user, pw)) {
            restored = countAll(c);
        }
        out("RESTORED_TABLES", String.valueOf(restored.size()));

        // ---- the comparison
        TreeSet<String> wanted = new TreeSet<String>();
        for (String k : expected.stringPropertyNames())
            if (k.startsWith("table.")) wanted.add(k.substring("table.".length()));

        List<String> problems = new ArrayList<String>();
        if (wanted.isEmpty()) problems.add("the sidecar lists no tables to check");

        for (String t : wanted) {
            String[] ba = expected.getProperty("table." + t).split(",");
            long lo = Math.min(Long.parseLong(ba[0].trim()), Long.parseLong(ba[1].trim()));
            long hi = Math.max(Long.parseLong(ba[0].trim()), Long.parseLong(ba[1].trim()));
            Long got = restored.get(t);
            if (got == null) { problems.add(t + ": MISSING from the restored copy"); continue; }
            if (got < lo || got > hi)
                problems.add(t + ": restored " + got + " outside live [" + lo + "," + hi + "]");
        }
        for (String t : restored.keySet())
            if (!wanted.contains(t)) problems.add(t + ": present in the restored copy but not in live");

        for (String t : new String[] { "STEAM_USERS", "WALLETS", "TRANSACTIONS", "AUDIT_LOG" })
            if (restored.containsKey(t)) out("RESTORED_" + t, String.valueOf(restored.get(t)));

        out("CHECKED_TABLES", String.valueOf(wanted.size()));
        if (!problems.isEmpty()) {
            for (String p : problems) out("MISMATCH", p);
            out("VERIFY_OK", "false");
            throw new IllegalStateException("the restored copy does not match live in "
                    + problems.size() + " table(s) -- this archive is NOT a backup");
        }
        out("VERIFY_OK", "true");
        return 0;
    }

    // ------------------------------------------------------------------ helpers

    /** Row counts for every base table in PUBLIC. A backup is compared on all of them. */
    static Map<String, Long> countAll(Connection c) throws SQLException {
        List<String> names = new ArrayList<String>();
        String q = "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' "
                 + "AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME";
        Statement s = c.createStatement();
        try {
            ResultSet rs = s.executeQuery(q);
            while (rs.next()) names.add(rs.getString(1));
        } finally { s.close(); }

        Map<String, Long> counts = new LinkedHashMap<String, Long>();
        for (String n : names) {
            Statement s2 = c.createStatement();
            try {
                ResultSet rs = s2.executeQuery("SELECT COUNT(*) FROM \"" + n.replace("\"", "\"\"") + "\"");
                counts.put(n, rs.next() ? rs.getLong(1) : Long.valueOf(-1L));
            } finally { s2.close(); }
        }
        return counts;
    }

    static TreeSet<String> union(Map<String, Long> a, Map<String, Long> b) {
        TreeSet<String> u = new TreeSet<String>(a.keySet());
        u.addAll(b.keySet());
        return u;
    }

    static String n(Long v) { return v == null ? "-1" : String.valueOf(v); }

    static Path expectedFor(Path zip) { return Paths.get(zip.toString() + ".expected"); }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        java.util.stream.Stream<Path> w = Files.walk(root);
        try {
            Path[] all = w.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new);
            for (Path p : all) Files.deleteIfExists(p);
        } finally { w.close(); }
    }

    static String required(String k) {
        String v = System.getenv(k);
        if (v == null || v.isEmpty())
            throw new IllegalArgumentException("environment variable " + k + " is not set");
        return v;
    }

    static String env(String k, String dflt) {
        String v = System.getenv(k);
        return v == null ? dflt : v;
    }

    static String oneLine(String s) { return s.replace('\r', ' ').replace('\n', ' '); }

    /** KEY=VALUE on stdout: the caller parses these, so they must never wrap. */
    static void out(String k, String v) { System.out.println(k + "=" + oneLine(v)); }
}
