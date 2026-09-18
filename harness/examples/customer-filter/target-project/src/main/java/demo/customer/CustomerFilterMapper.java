package demo.customer;

import org.springframework.stereotype.Component;

@Component
public class CustomerFilterMapper {
    public CustomerFilterResponse response(CustomerFilter row) {
        return new CustomerFilterResponse(row.id(), row.label(), row.parentKey(), row.filterOrder());
    }
}
