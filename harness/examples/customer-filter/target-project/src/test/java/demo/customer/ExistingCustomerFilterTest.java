package demo.customer;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExistingCustomerFilterTest {
    @Test void existingHierarchyIsOrdered() {
        var rows = new CustomerFilterRepository().list("CUSTOMER_INFORMATION");
        assertEquals(java.util.List.of(1, 2, 3, 4), rows.stream().map(CustomerFilter::filterOrder).toList());
    }
}
