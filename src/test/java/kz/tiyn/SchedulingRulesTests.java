package kz.tiyn;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import kz.tiyn.platform.tick.Ticker;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

class SchedulingRulesTests {

    private static final String TICK_PACKAGE = "kz.tiyn.platform.tick..";
    private static final String TICK_REGISTRAR = "kz.tiyn.platform.tick.TickRegistrar";

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("kz.tiyn");

    @Test
    void schedulingAndShedLockAreUsedOnlyInsideThePlatformTickPackage() {
        noClasses()
                .that()
                .resideOutsideOfPackage(TICK_PACKAGE)
                .should()
                .dependOnClassesThat()
                .resideInAPackage("net.javacrumbs.shedlock..")
                .orShould()
                .dependOnClassesThat()
                .areAssignableTo(Scheduled.class)
                .orShould()
                .dependOnClassesThat()
                .areAssignableTo(EnableScheduling.class)
                .check(classes);
    }

    @Test
    void noTickerDependsOnAnotherTicker() {
        classes()
                .that()
                .implement(Ticker.class)
                .should(notDependOnAnotherTicker())
                .allowEmptyShould(false)
                .check(classes);
    }

    @Test
    void onlyTheTickRegistrarCallsTick() {
        DescribedPredicate<JavaCall<?>> callsTick = new DescribedPredicate<>("call Ticker.tick") {
            @Override
            public boolean test(JavaCall<?> call) {
                return call.getTarget().getName().equals("tick")
                        && call.getTargetOwner().isAssignableTo(Ticker.class);
            }
        };

        noClasses()
                .that()
                .doNotHaveFullyQualifiedName(TICK_REGISTRAR)
                .should()
                .callMethodWhere(callsTick)
                .check(classes);
    }

    private static ArchCondition<JavaClass> notDependOnAnotherTicker() {
        return new ArchCondition<>("not depend on another Ticker") {
            @Override
            public void check(JavaClass ticker, ConditionEvents events) {
                for (Dependency dependency : ticker.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    if (!target.equals(ticker)
                            && !target.isEquivalentTo(Ticker.class)
                            && target.isAssignableTo(Ticker.class)) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }
}
