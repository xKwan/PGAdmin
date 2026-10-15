package pgweb;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Architecture rules (plan §5, SEC-10), checked on the production classes. */
class ArchitectureTest {

    static JavaClasses classes;

    @BeforeAll
    static void load() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("pgweb");
    }

    @Test
    void noSpringNoVertxNoJavaxServlet() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "io.vertx..", "javax.servlet..")
                .because("ADR-0001 (no Spring) and ADR-0009 (pgJDBC only)").check(classes);
    }

    @Test
    void webLayerDoesNotTouchJdbc() {
        noClasses().that().resideInAPackage("pgweb.web..").should().dependOnClassesThat().resideInAPackage("java.sql..")
                .because("HTTP handlers go through services; SQL lives in dedicated packages").check(classes);
    }

    @Test
    void onlyEntryPointsWriteToTheConsole() {
        noClasses().that().doNotHaveSimpleName("Main").should().accessField(System.class, "out")
                .orShould().accessField(System.class, "err")
                .because("logs go through SLF4J (redaction)").check(classes);
    }

    @Test
    void noStatementWithDynamicSqlInAppCode() {
        // App-owned SQL uses PreparedStatement with parameters; plain Statement is allowed only for fixed text in
        // the audit infrastructure. User SQL (Phase 7) gets its own package and rule.
        noClasses().that().resideOutsideOfPackages("pgweb.audit..", "pgweb.execution..", "pgweb.dbsession..", "pgweb.metadata..")
                .should().callMethod(java.sql.Statement.class, "execute", String.class)
                .orShould().callMethod(java.sql.Statement.class, "executeQuery", String.class)
                .orShould().callMethod(java.sql.Statement.class, "executeUpdate", String.class)
                .check(classes);
    }
}
