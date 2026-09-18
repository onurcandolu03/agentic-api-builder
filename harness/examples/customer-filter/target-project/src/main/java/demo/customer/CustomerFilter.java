package demo.customer;

/** Immutable logical row: CUSTOMER_FILTER(FILTER_ID, DISPLAY_LABEL, PARENT_KEY, FILTER_ORDER). */
public record CustomerFilter(String id, String label, String parentKey, int filterOrder) {
    public CustomerFilter withOrder(int order) { return new CustomerFilter(id, label, parentKey, order); }
}
