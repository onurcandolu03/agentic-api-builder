package demo.customer;

import java.util.*;
import org.springframework.stereotype.Repository;

/** In-memory repository adapter for CUSTOMER_FILTER; no database connection or SQL execution.
 * Existing ordering rule: insert immediately after the identified sibling; shift siblings at
 * or above the insertion position by one. Reject missing parent/reference and duplicate IDs.
 * Consistency convention: validate and build a copy under the repository monitor, then publish
 * once; synchronized readers never observe a partial shift. This is not a database transaction.
 */
@Repository
public class CustomerFilterRepository {
    private List<CustomerFilter> rows = new ArrayList<>(List.of(
            new CustomerFilter("CUSTOMER_SEGMENT", "Customer Segment", "CUSTOMER_INFORMATION", 1),
            new CustomerFilter("CUSTOMER_TITLE", "Customer Title", "CUSTOMER_INFORMATION", 2),
            new CustomerFilter("CUSTOMER_CREDIT_SCORE", "Customer Credit Score", "CUSTOMER_INFORMATION", 3),
            new CustomerFilter("CUSTOMER_AGE", "Customer Age", "CUSTOMER_INFORMATION", 4),
            new CustomerFilter("OTHER", "Other hierarchy", "OTHER_PARENT", 1)));

    public synchronized List<CustomerFilter> list(String parentKey) {
        return rows.stream().filter(r -> r.parentKey().equals(parentKey))
                .sorted(Comparator.comparingInt(CustomerFilter::filterOrder)).toList();
    }

    public synchronized int positionAfter(String parentKey, String reference) {
        return list(parentKey).stream().filter(r -> r.id().equals(reference)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("Parent or reference not found")).filterOrder() + 1;
    }
}
