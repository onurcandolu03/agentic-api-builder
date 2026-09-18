package demo.customer;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class AddCustomerFilterTest {
    private final CustomerFilterRepository repository = new CustomerFilterRepository();
    private final CustomerFilterController api = new CustomerFilterController(new CustomerFilterService(repository, new CustomerFilterMapper()));
    private static AddCustomerFilterRequest request(String id, String label, String after) {
        return new AddCustomerFilterRequest(id, label, "CUSTOMER_INFORMATION", after);
    }

    @Test void insertsAfterTitleAndShiftsSiblingsAtomically() throws Exception {
        var response = api.addCustomerFilter(request("NEW_FILTER", "New Filter", "CUSTOMER_TITLE"));
        assertEquals(new CustomerFilterResponse("NEW_FILTER", "New Filter", "CUSTOMER_INFORMATION", 3), response);
        var actual = repository.list("CUSTOMER_INFORMATION");
        assertEquals(List.of("Customer Segment", "Customer Title", "New Filter", "Customer Credit Score", "Customer Age"),
                actual.stream().map(CustomerFilter::label).toList());
        assertEquals(List.of(1, 2, 3, 4, 5), actual.stream().map(CustomerFilter::filterOrder).toList());
        assertEquals(List.of(new CustomerFilter("OTHER", "Other hierarchy", "OTHER_PARENT", 1)), repository.list("OTHER_PARENT"));
        // Recorded by the executed test; the E2E host checks this against the accepted placement plan.
        Files.writeString(Path.of("target/customer-filter-order.txt"), String.join("\n",
                actual.stream().map(r -> r.filterOrder() + ". " + r.label()).toList()) + "\n");
        var method = CustomerFilterRepository.class.getMethod("insertAfter", String.class, String.class, String.class, String.class);
        assertTrue(java.lang.reflect.Modifier.isSynchronized(method.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isSynchronized(CustomerFilterRepository.class.getMethod("list", String.class).getModifiers()));
    }

    @Test void invalidAndDuplicateRequestsLeaveStateUnchanged() {
        var before = repository.list("CUSTOMER_INFORMATION");
        for (var request : List.of(request("NEW_FILTER", "", "CUSTOMER_TITLE"), request("CUSTOMER_TITLE", "Duplicate", "CUSTOMER_TITLE"))) {
            var error = assertThrows(ResponseStatusException.class, () -> api.addCustomerFilter(request));
            assertEquals(400, error.getStatusCode().value());
            assertEquals(before, repository.list("CUSTOMER_INFORMATION"));
        }
    }

    @Test void missingReferenceLeavesStateUnchanged() {
        var before = repository.list("CUSTOMER_INFORMATION");
        var error = assertThrows(ResponseStatusException.class, () -> api.addCustomerFilter(request("NEW_FILTER", "New Filter", "ABSENT")));
        assertEquals(404, error.getStatusCode().value());
        assertEquals(before, repository.list("CUSTOMER_INFORMATION"));
    }

    @Test void preservesSpringApiContract() throws Exception {
        assertEquals("/customer-filters", CustomerFilterController.class.getAnnotation(RequestMapping.class).value()[0]);
        var method = CustomerFilterController.class.getMethod("addCustomerFilter", AddCustomerFilterRequest.class);
        assertNotNull(method.getAnnotation(PostMapping.class));
        assertNotNull(method.getParameters()[0].getAnnotation(RequestBody.class));
        assertEquals(CustomerFilterResponse.class, method.getReturnType());
    }
}
