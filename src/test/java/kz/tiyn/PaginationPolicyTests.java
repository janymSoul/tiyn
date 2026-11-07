package kz.tiyn;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Collection;
import kz.tiyn.platform.paging.BoundedList;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;

class PaginationPolicyTests {

    private static com.tngtech.archunit.core.domain.JavaClasses production() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("kz.tiyn");
    }

    @Test
    void everyGetHandlerThatReturnsAListIsPaginatedOrMarkedAsBoundedWithAReason() {
        methods()
                .that()
                .areAnnotatedWith(GetMapping.class)
                .should(returnNoUnboundedCollection())
                .allowEmptyShould(false)
                .check(production());
    }

    @Test
    void theMarkedHandlersAreTheOnesTheDomainKeepsSmall() {
        var marked = production().stream()
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> m.isAnnotatedWith(BoundedList.class))
                .map(JavaMethod::getFullName)
                .toList();

        assertThat(marked)
                .containsExactlyInAnyOrder(
                        "kz.tiyn.identity.family.FamilyController.family(kz.tiyn.platform.security.Principal)",
                        "kz.tiyn.task.tasks.TaskController.proofs(kz.tiyn.platform.security.Principal, java.util.UUID)");
    }

    private static ArchCondition<JavaMethod> returnNoUnboundedCollection() {
        return new ArchCondition<>("return no List, array or Collection unless marked @BoundedList with a reason") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (!returnsCollection(method)) {
                    return;
                }
                var bounded = method.tryGetAnnotationOfType(BoundedList.class);
                if (bounded.isEmpty() || bounded.get().value().isBlank()) {
                    events.add(SimpleConditionEvent.violated(
                            method,
                            method.getFullName()
                                    + " is a GET handler returning a collection: return a Page"
                                    + " or annotate it @BoundedList(\"<why it stays small>\")"));
                }
            }
        };
    }

    private static boolean returnsCollection(JavaMethod method) {
        return isCollection(method.getRawReturnType()) || wrapsCollection(method.getReturnType());
    }

    private static boolean wrapsCollection(JavaType type) {
        if (!(type instanceof JavaParameterizedType parameterized)
                || !parameterized.toErasure().isEquivalentTo(ResponseEntity.class)) {
            return false;
        }
        return parameterized.getActualTypeArguments().stream()
                .map(JavaType::toErasure)
                .anyMatch(PaginationPolicyTests::isCollection);
    }

    private static boolean isCollection(JavaClass type) {
        return type.isArray() || type.isAssignableTo(Collection.class);
    }
}
