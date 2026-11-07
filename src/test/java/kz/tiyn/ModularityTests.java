package kz.tiyn;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    private static final List<String> TABLE_OWNERS = List.of("identity", "task", "ledger", "notification", "platform");

    @Test
    void verifiesModuleBoundaries() {
        ApplicationModules.of(TiynApplication.class).verify();
    }

    @Test
    void eachModuleTouchesOnlyItsOwnTables() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("kz.tiyn");

        for (String owner : TABLE_OWNERS) {
            noClasses()
                    .that()
                    .resideOutsideOfPackages("kz.tiyn." + owner + "..", "kz.tiyn.platform.jooq..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("kz.tiyn.platform.jooq." + owner + "..")
                    .allowEmptyShould(true)
                    .check(classes);
        }
    }
}
