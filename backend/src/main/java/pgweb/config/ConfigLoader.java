package pgweb.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Loads and validates {@code pgweb.properties}. Every problem is collected and reported together; any problem makes
 * startup fail (plan Phase 3: invalid config gives a non-zero exit and a clear message). Unknown keys are errors, so
 * a misspelt setting can't silently fall back to a default.
 */
public final class ConfigLoader {

    static final String INTERNAL = "internal";
    static final String EXTERNAL = "external";
    private static final String NEGOTIATE = "Negotiate";
    static final Set<String> PROFILE_NAMES = Set.of(INTERNAL, EXTERNAL);
    private static final Set<String> SSL_MODES = Set.of("disable", "require", "verify-ca", "verify-full");
    private static final Pattern SID = Pattern.compile("^S-1-\\d+(-\\d+){1,14}$");
    private static final Pattern DNS_LABEL = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$");
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern DOMAIN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$");
    private static final String BACKSLASH = "\\";
    /** {@code DOMAIN\account}: a domain name, one backslash, an account without backslashes or spaces. */
    private static final Pattern OVERRIDE_ACCOUNT = Pattern.compile("^[A-Za-z0-9._-]+" + Pattern.quote(BACKSLASH) + "[^\\\\\\s]+$");
    private static final Pattern DB_NAME =Pattern.compile("^[A-Za-z_][A-Za-z0-9_$]{0,62}$");

    private static final Set<String> KEYS = Set.of(
            "pgweb.mode",
            "server.bind", "server.port", "server.publicHost", "server.workDir", "server.tls.keystore", "server.tls.keystorePassword",
            "auth.protocols", "auth.kerberosOnly", "auth.dataAdminSid", "auth.allowedDomains",
            "auth.session.idleMinutes", "auth.session.absoluteHours",
            "audit.jdbcUrl", "audit.user", "audit.password", "audit.hmacKey", "audit.spoolDir",
            "audit.migrator.user", "audit.migrator.password", "audit.failMode",
            "execution.enabled",
            "limits.maxRows", "limits.maxResponseBytes", "limits.maxCellBytes", "limits.maxSqlBytes",
            "limits.cancelGraceSeconds", "limits.idleSessionMinutes", "limits.idleInTransactionMinutes",
            "limits.idleInTransactionWarningMinutes", "limits.maxTransactionAgeMinutes", "limits.heartbeatAbandonSeconds",
            "limits.sessionsPerUser", "limits.sessionsGlobal", "limits.failedOpensPerWindow", "limits.failedOpenWindowMinutes",
            "limits.maxScriptStatements", "limits.maxFetchedRows",
            "logging.dir", "identity.roleCase", "identity.roleOverrides", "parser.library",
            "auditViewer.enabled", "auditViewer.host", "auditViewer.port", "auditViewer.database", "auditViewer.sslMode",
            "auditViewer.sslRootCert");
    private static final Set<String> PROFILE_KEYS = Set.of(
            "displayName", "host", "port", "sslMode", "sslRootCert", "maintenanceDb", "requiresDataAdmin", "statementTimeoutSeconds",
            "confirmDestructive", "bannerText", "bannerTone");
    private static final Set<String> BANNER_TONES = Set.of("info", "warn", "danger");

    private ConfigLoader() {
    }

    /** A DNS host name: 1-253 characters of dot-separated labels (checked label by label, no unbounded regex). */
    static boolean isDnsName(String s) {
        if (s.isEmpty() || s.length() > 253 || s.startsWith(".") || s.endsWith(".")) {
            return false;
        }
        for (String label : s.split("[.]", -1)) {
            if (!DNS_LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }

    public static Config load(Path file) throws ConfigException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            throw new ConfigException("cannot read configuration file " + file + ": " + e.getMessage());
        }
        return load(p, null);
    }

    /** Loads from properties; {@code secrets} overrides the resolver (tests), {@code null} uses the real one. */
    public static Config load(Properties p, SecretResolver secrets) throws ConfigException {
        return new Parser(p, secrets).parse();
    }

    private static final class Parser {
        private final Properties p;
        private final List<String> problems = new ArrayList<>();
        private SecretResolver secrets;
        private Mode mode = Mode.PROD;

        Parser(Properties p, SecretResolver secrets) {
            this.p = p;
            this.secrets = secrets;
        }

        Config parse() throws ConfigException {
            for (String k : new TreeSet<>(p.stringPropertyNames())) {
                if (!KEYS.contains(k) && !isProfileKey(k)) {
                    problems.add("unknown setting " + k);
                }
            }
            String m = str("pgweb.mode", null);
            if (m == null) {
                problems.add("pgweb.mode is required (prod or test)");
            } else {
                switch (m.toLowerCase(Locale.ROOT)) {
                    case "prod" -> mode = Mode.PROD;
                    case "test" -> mode = Mode.TEST;
                    default -> problems.add("pgweb.mode must be prod or test, not " + m);
                }
            }
            if (secrets == null) {
                secrets = new SecretResolver(mode);
            }
            boolean prod = mode == Mode.PROD;

            Config.Server server = server(prod);
            Config.Auth auth = auth(prod);
            Config.Audit audit = audit();
            Map<String, Config.DbProfile> profiles = profiles(prod);
            Config.Limits limits = limits();
            boolean execution = bool("execution.enabled", false);
            Path logDir = Path.of(str("logging.dir", "logs"));
            Path parserLibrary = Path.of(str("parser.library", "native/pg_query.dll"));
            Optional<Config.DbProfile> auditViewer = auditViewer(prod);

            if (!problems.isEmpty()) {
                throw new ConfigException(problems);
            }
            Config.Identity identity = identity();
            if (!problems.isEmpty()) {
                throw new ConfigException(problems);
            }
            return new Config(mode, server, auth, audit, profiles, limits, execution, logDir, identity, parserLibrary, auditViewer);
        }

        private Config.Server server(boolean prod) {
            String bind = str("server.bind", prod ? "0.0.0.0" : "127.0.0.1");
            int port = intRange("server.port", prod ? 8443 : 8080, 0, 65535);
            String publicHost = str("server.publicHost", null);
            if (publicHost == null) {
                problems.add("server.publicHost is required (the exact host name users open; used for the Origin check and the SPN HTTP/<host>)");
            } else if (!isDnsName(publicHost) || IPV4.matcher(publicHost).matches()) {
                problems.add("server.publicHost must be a DNS host name, not " + publicHost);
            }
            Optional<Config.Tls> tls = Optional.empty();
            String ks = str("server.tls.keystore", null);
            if (ks != null) {
                Path ksPath = Path.of(ks);
                if (!Files.isRegularFile(ksPath)) {
                    problems.add("server.tls.keystore does not exist: " + ks);
                }
                Secret pw = secret("server.tls.keystorePassword");
                if (pw != null) {
                    tls = Optional.of(new Config.Tls(ksPath, pw));
                }
            } else if (prod) {
                problems.add("server.tls.keystore is required with pgweb.mode=prod (HTTPS only)");
            }
            return new Config.Server(bind, port, publicHost, tls, Path.of(str("server.workDir", "work")));
        }

        private Config.Auth auth(boolean prod) {
            Set<String> protocols = protocols(prod);
            boolean kerberosOnly = bool("auth.kerberosOnly", true);
            if (prod && !kerberosOnly) {
                problems.add("auth.kerberosOnly=false is not allowed with pgweb.mode=prod");
            }
            String sid = str("auth.dataAdminSid", null);
            if (sid == null) {
                problems.add("auth.dataAdminSid is required (SID of the DataAdmin AD group, e.g. S-1-5-21-...-1234)");
            } else if (!SID.matcher(sid).matches()) {
                problems.add("auth.dataAdminSid is not a SID: " + sid);
            }
            Set<String> domains = domains();
            Duration idle = Duration.ofMinutes(intRange("auth.session.idleMinutes", 30, 1, 24 * 60));
            Duration absolute = Duration.ofHours(intRange("auth.session.absoluteHours", 8, 1, 24));
            if (idle.compareTo(absolute) > 0) {
                problems.add("auth.session.idleMinutes must not exceed auth.session.absoluteHours");
            }
            return new Config.Auth(Set.copyOf(protocols), kerberosOnly, sid, Set.copyOf(domains), idle, absolute);
        }

        private Set<String> protocols(boolean prod) {
            Set<String> protocols = new LinkedHashSet<>();
            for (String s : list("auth.protocols", NEGOTIATE)) {
                switch (s.toUpperCase(Locale.ROOT)) {
                    case "NEGOTIATE" -> protocols.add(NEGOTIATE);
                    case "NTLM" -> {
                        if (prod) {
                            problems.add("auth.protocols: NTLM is not allowed with pgweb.mode=prod (Kerberos delegation needs Negotiate/Kerberos, ADR-0002)");
                        }
                        protocols.add("NTLM");
                    }
                    default -> problems.add("auth.protocols: unknown protocol " + s + " (Negotiate, NTLM)");
                }
            }
            if (!protocols.contains(NEGOTIATE) && !protocols.isEmpty()) {
                problems.add("auth.protocols must include Negotiate");
            }
            return protocols;
        }

        private Set<String> domains() {
            Set<String> domains = new LinkedHashSet<>();
            for (String d : list("auth.allowedDomains", "")) {
                if (!DOMAIN.matcher(d).matches()) {
                    problems.add("auth.allowedDomains: invalid domain name " + d);
                } else {
                    domains.add(d.toUpperCase(Locale.ROOT));
                }
            }
            return domains;
        }

        private Config.Audit audit() {
            String url = str("audit.jdbcUrl", null);
            if (url == null) {
                problems.add("audit.jdbcUrl is required (jdbc:postgresql://<host>:<port>/pgweb_audit)");
            } else if (!url.startsWith("jdbc:postgresql://")) {
                problems.add("audit.jdbcUrl must start with jdbc:postgresql://");
            }
            String user = str("audit.user", "pgweb_app");
            Secret password = secret("audit.password");
            Secret hmac = secret("audit.hmacKey");
            if (hmac != null && hmac.length() < 32) {
                problems.add("audit.hmacKey must be at least 32 bytes");
            }
            String spool = str("audit.spoolDir", null);
            if (spool == null) {
                problems.add("audit.spoolDir is required (local folder for audit outcomes that could not be written)");
            }
            Optional<String> migUser = Optional.ofNullable(str("audit.migrator.user", null));
            Optional<Secret> migPw = Optional.empty();
            if (migUser.isPresent()) {
                migPw = Optional.ofNullable(secret("audit.migrator.password"));
            }
            String failMode = str("audit.failMode", "closed").toLowerCase(Locale.ROOT);
            if (!failMode.equals("closed") && !failMode.equals("open")) {
                problems.add("audit.failMode must be closed or open, not " + failMode);
            }
            return new Config.Audit(url, user, password, hmac, spool == null ? null : Path.of(spool), migUser, migPw,
                    !failMode.equals("open"));
        }

        private Map<String, Config.DbProfile> profiles(boolean prod) {
            Map<String, Config.DbProfile> out = new LinkedHashMap<>();
            for (String name : List.of(INTERNAL, EXTERNAL)) {
                String prefix = "profile." + name + ".";
                if (p.stringPropertyNames().stream().anyMatch(k -> k.startsWith(prefix))) {
                    out.put(name, profile(name, prefix, prod));
                }
            }
            if (out.isEmpty()) {
                problems.add("at least one PostgreSQL profile is required (profile.internal.* and/or profile.external.*)");
            }
            return Map.copyOf(out);
        }

        private Config.DbProfile profile(String name, String prefix, boolean prod) {
            boolean internal = INTERNAL.equals(name);
            String host = str(prefix + "host", null);
            if (host == null) {
                problems.add(prefix + "host is required");
            } else if (IPV4.matcher(host).matches() || host.contains(":") || !isDnsName(host)) {
                problems.add(prefix + "host must be a DNS host name (Kerberos SPN POSTGRES/<host>; finding S7-3), not " + host);
            }
            int port = intRange(prefix + "port", internal ? 5433 : 5432, 1, 65535);
            String ssl = sslMode(prefix, prod);
            Optional<Path> root = sslRootCert(prefix, ssl);
            String maint = str(prefix + "maintenanceDb", "postgres");
            if (!DB_NAME.matcher(maint).matches()) {
                problems.add(prefix + "maintenanceDb is not a valid database name: " + maint);
            }
            boolean dataAdmin = bool(prefix + "requiresDataAdmin", internal);
            if (internal && !dataAdmin) {
                problems.add(prefix + "requiresDataAdmin must be true for the internal profile (F3)");
            }
            String display = str(prefix + "displayName", internal ? "Internal PostgreSQL" : "External PostgreSQL");
            Duration st = Duration.ofSeconds(intRange(prefix + "statementTimeoutSeconds", internal ? 300 : 60, 1, 24 * 3600));
            boolean confirm = bool(prefix + "confirmDestructive", true);
            Optional<String> banner = Optional.ofNullable(str(prefix + "bannerText", null));
            if (banner.isPresent() && banner.get().length() > 80) {
                problems.add(prefix + "bannerText is longer than 80 characters");
            }
            String tone = str(prefix + "bannerTone", "warn").toLowerCase(Locale.ROOT);
            if (!BANNER_TONES.contains(tone)) {
                problems.add(prefix + "bannerTone must be one of " + new TreeSet<>(BANNER_TONES));
            }
            return new Config.DbProfile(name, display, host, port, ssl, root, maint, dataAdmin, st, confirm, banner, tone);
        }

        /**
         * The audit viewer's target (admin Phase 9): the audit database, reached as the signed-in user. Off unless
         * {@code auditViewer.enabled=true}; then a DNS host name is required, as for the profiles.
         */
        private Optional<Config.DbProfile> auditViewer(boolean prod) {
            if (!bool("auditViewer.enabled", false)) {
                return Optional.empty();
            }
            String prefix = "auditViewer.";
            String host = str(prefix + "host", null);
            if (host == null) {
                problems.add(prefix + "host is required when auditViewer.enabled=true");
            } else if (IPV4.matcher(host).matches() || host.contains(":") || !isDnsName(host)) {
                problems.add(prefix + "host must be a DNS host name (Kerberos SPN POSTGRES/<host>; finding S7-3), not " + host);
            }
            int port = intRange(prefix + "port", 5432, 1, 65535);
            String ssl = sslMode(prefix, prod);
            Optional<Path> root = sslRootCert(prefix, ssl);
            String database = str(prefix + "database", "pgweb_audit");
            if (!DB_NAME.matcher(database).matches()) {
                problems.add(prefix + "database is not a valid database name: " + database);
            }
            return Optional.of(new Config.DbProfile("audit", "Audit trail", host, port, ssl, root, database, false,
                    Duration.ofSeconds(15), true, Optional.empty(), "info"));
        }

        private String sslMode(String prefix, boolean prod) {
            String ssl = str(prefix + "sslMode", prod ? "verify-full" : "disable");
            if (!SSL_MODES.contains(ssl)) {
                problems.add(prefix + "sslMode must be one of " + new TreeSet<>(SSL_MODES));
            } else if (prod && (ssl.equals("require") || ssl.equals("verify-ca"))) {
                problems.add(prefix + "sslMode=" + ssl + " does not verify the host name; use verify-full (or disable, accepting risk T12)");
            }
            return ssl;
        }

        private Optional<Path> sslRootCert(String prefix, String ssl) {
            Optional<Path> root = Optional.ofNullable(str(prefix + "sslRootCert", null)).map(Path::of);
            if (ssl.startsWith("verify") && root.isEmpty()) {
                problems.add(prefix + "sslRootCert is required with sslMode=" + ssl);
            }
            root.ifPresent(r -> {
                if (!Files.isRegularFile(r)) {
                    problems.add(prefix + "sslRootCert does not exist: " + r);
                }
            });
            return root;
        }

        private Config.Identity identity() {
            String roleCase = str("identity.roleCase", "lower").toLowerCase(Locale.ROOT);
            if (!roleCase.equals("lower") && !roleCase.equals("asis")) {
                problems.add("identity.roleCase must be lower or asis, not " + roleCase);
            }
            Map<String, String> overrides = new LinkedHashMap<>();
            String raw = str("identity.roleOverrides", "");
            for (String entry : raw.split(";")) {
                if (entry.isBlank()) {
                    continue;
                }
                int eq = entry.indexOf('=');
                String account = eq < 0 ? "" : entry.substring(0, eq).strip();
                String role = eq < 0 ? "" : entry.substring(eq + 1).strip();
                if (!OVERRIDE_ACCOUNT.matcher(account).matches() || !pgweb.identity.RoleMapper.ROLE.matcher(role).matches()) {
                    problems.add("identity.roleOverrides: expected DOMAIN" + BACKSLASH + "account=role entries separated by ';', not " + entry.strip());
                } else {
                    overrides.put(account.toUpperCase(Locale.ROOT), role);
                }
            }
            return new Config.Identity(!roleCase.equals("asis"), Map.copyOf(overrides));
        }

        private Config.Limits limits() {
            int maxRows = intRange("limits.maxRows", 1000, 1, 100_000);
            long maxResponse = longRange("limits.maxResponseBytes", 10L * 1024 * 1024, 64L * 1024, 256L * 1024 * 1024);
            int maxCell = intRange("limits.maxCellBytes", 64 * 1024, 256, 16_777_216);
            long maxSql = longRange("limits.maxSqlBytes", 1024L * 1024, 1024, 16L * 1024 * 1024);
            Duration grace = Duration.ofSeconds(intRange("limits.cancelGraceSeconds", 5, 1, 120));
            Duration idle = Duration.ofMinutes(intRange("limits.idleSessionMinutes", 15, 1, 24 * 60));
            Duration idleTx = Duration.ofMinutes(intRange("limits.idleInTransactionMinutes", 10, 1, 24 * 60));
            Duration idleTxWarn = Duration.ofMinutes(intRange("limits.idleInTransactionWarningMinutes", 8, 1, 24 * 60));
            Duration maxTx = Duration.ofMinutes(intRange("limits.maxTransactionAgeMinutes", 60, 1, 24 * 60));
            Duration hb = Duration.ofSeconds(intRange("limits.heartbeatAbandonSeconds", 120, 10, 3600));
            int perUser = intRange("limits.sessionsPerUser", 3, 1, 50);
            int global = intRange("limits.sessionsGlobal", 50, 1, 1000);
            int failed = intRange("limits.failedOpensPerWindow", 5, 1, 1000);
            Duration window = Duration.ofMinutes(intRange("limits.failedOpenWindowMinutes", 5, 1, 24 * 60));
            int scriptStatements = intRange("limits.maxScriptStatements", 1000, 1, 10_000);
            int fetchedRows = intRange("limits.maxFetchedRows", 10_000, 1, 1_000_000);
            if (idleTxWarn.compareTo(idleTx) >= 0) {
                problems.add("limits.idleInTransactionWarningMinutes must be less than limits.idleInTransactionMinutes");
            }
            if (perUser > global) {
                problems.add("limits.sessionsPerUser must not exceed limits.sessionsGlobal");
            }
            if (fetchedRows < maxRows) {
                problems.add("limits.maxFetchedRows must not be less than limits.maxRows");
            }
            if (maxCell > maxResponse) {
                problems.add("limits.maxCellBytes must not exceed limits.maxResponseBytes");
            }
            return new Config.Limits(maxRows, maxResponse, maxCell, maxSql, grace, idle, idleTx, idleTxWarn, maxTx, hb,
                    perUser, global, failed, window, scriptStatements, fetchedRows);
        }

        // ------------------------------------------------------------------

        private static boolean isProfileKey(String k) {
            String[] parts = k.split("\\.", 3);
            return parts.length == 3 && parts[0].equals("profile") && PROFILE_NAMES.contains(parts[1]) && PROFILE_KEYS.contains(parts[2]);
        }

        private String str(String key, String def) {
            String v = p.getProperty(key);
            if (v == null || v.isBlank()) {
                return def;
            }
            return v.strip();
        }

        private List<String> list(String key, String def) {
            String v = str(key, def);
            List<String> out = new ArrayList<>();
            for (String s : v.split("[,\\s]+")) {
                if (!s.isBlank()) {
                    out.add(s);
                }
            }
            return out;
        }

        private boolean bool(String key, boolean def) {
            String v = str(key, null);
            if (v == null) {
                return def;
            }
            return switch (v.toLowerCase(Locale.ROOT)) {
                case "true" -> true;
                case "false" -> false;
                default -> {
                    problems.add(key + " must be true or false, not " + v);
                    yield def;
                }
            };
        }

        private int intRange(String key, int def, int min, int max) {
            return (int) longRange(key, def, min, max);
        }

        private long longRange(String key, long def, long min, long max) {
            String v = str(key, null);
            if (v == null) {
                return def;
            }
            try {
                long n = Long.parseLong(v);
                if (n < min || n > max) {
                    problems.add(key + " must be between " + min + " and " + max + ", not " + n);
                    return def;
                }
                return n;
            } catch (NumberFormatException e) {
                problems.add(key + " must be a whole number, not " + v);
                return def;
            }
        }

        private Secret secret(String key) {
            try {
                return secrets.resolve(key, str(key, null));
            } catch (ConfigException e) {
                problems.addAll(e.problems());
                return null;
            }
        }
    }
}
