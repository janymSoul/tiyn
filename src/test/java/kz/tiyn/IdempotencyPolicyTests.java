package kz.tiyn;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import kz.tiyn.platform.idempotency.IdempotencyContext;
import kz.tiyn.platform.idempotency.NaturallyIdempotent;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

class IdempotencyPolicyTests {

    private static final String LEGACY_HEADER = "Idempotency-Key";

    private static final Set<String> CREATIONS_AWAITING_A_KEY =
            Set.of("kz.tiyn.ledger.wallet.GoalController.create", "kz.tiyn.task.series.SeriesController.create");

    @Test
    void everyPostHandlerTakesTheIdempotencyContextOrIsMarkedNaturallyIdempotent() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("kz.tiyn");

        methods()
                .that()
                .areAnnotatedWith(PostMapping.class)
                .should(handleRetries())
                .allowEmptyShould(false)
                .check(classes);
    }

    private static ArchCondition<JavaMethod> handleRetries() {
        return new ArchCondition<>(
                "take an IdempotencyContext, be @NaturallyIdempotent, or be a listed creation awaiting a key") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (!isCovered(method)) {
                    events.add(SimpleConditionEvent.violated(
                            method, method.getFullName() + " is a POST handler without idempotency handling"));
                }
            }
        };
    }

    private static boolean isCovered(JavaMethod method) {
        return takesContext(method)
                || takesLegacyKeyHeader(method)
                || method.isAnnotatedWith(NaturallyIdempotent.class)
                || CREATIONS_AWAITING_A_KEY.contains(method.getOwner().getFullName() + "." + method.getName());
    }

    private static boolean takesContext(JavaMethod method) {
        return method.getRawParameterTypes().stream()
                .map(JavaClass::getFullName)
                .anyMatch(IdempotencyContext.class.getName()::equals);
    }

    private static boolean takesLegacyKeyHeader(JavaMethod method) {
        return method.getParameterAnnotations().stream()
                .flatMap(Set::stream)
                .filter(annotation -> annotation.getRawType().isEquivalentTo(RequestHeader.class))
                .map(JavaAnnotation::getProperties)
                .anyMatch(properties -> LEGACY_HEADER.equals(properties.get("value")));
    }
}
