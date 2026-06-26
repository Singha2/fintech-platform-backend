package com.arthvritt.platform.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit harness for bounded-context isolation (M9-A, DL-BE-039 — ARCH.1). Standing rule set that fails
 * the build when one BC reaches into another BC's internals instead of its published read port. Wired with
 * M9 (the first Milestone-2 module + the first cross-context consumer); it grows as later modules add ports.
 *
 * <p><b>What it can and cannot see:</b> ArchUnit enforces the <i>Java-level</i> boundary — a {@code listing}
 * class may reference {@code ..credit.port..} but not {@code credit} internals. The complementary rule "no
 * cross-BC <i>table</i> read" (raw SQL in strings) is not statically visible; it is held by routing every
 * foreign-table read through a {@code *QueryService} that lives in the owning BC's package (code-review +
 * this harness together close the gap).
 */
@AnalyzeClasses(packages = "com.arthvritt.platform", importOptions = ImportOption.DoNotIncludeTests.class)
class BoundedContextRulesTest {

    /**
     * ARCH.1 — the {@code listing} BC may read buyer/supplier/credit state ONLY through their {@code .port}
     * sub-packages (the read-only query ports + their DTOs), never their services, controllers, or any other
     * internal type. This is the rule that would have failed the WS-4 direct table reads had they been Java
     * dependencies; it now pins the M9-A refactor in place.
     */
    @ArchTest
    static final ArchRule listing_reads_other_bcs_only_via_query_ports =
            noClasses().that().resideInAPackage("..listing..")
                    .should().dependOnClassesThat(
                            resideInAnyPackage("..buyer..", "..supplier..", "..credit..")
                                    .and(resideOutsideOfPackages(
                                            "..buyer.port..", "..supplier.port..", "..credit.port..")))
                    .because("ARCH.1: the listing BC reaches buyer/supplier/credit only through their "
                            + "read-only *QueryPort interfaces (DL-BE-039)");

    /** Query ports are contracts, not implementations: every {@code *QueryPort} under a {@code .port} package is an interface. */
    @ArchTest
    static final ArchRule query_ports_are_interfaces =
            classes().that().resideInAnyPackage("..buyer.port..", "..supplier.port..", "..credit.port..")
                    .and().haveSimpleNameEndingWith("QueryPort")
                    .should().beInterfaces()
                    .because("a query port is a published read contract owned by its BC");
}
