package demo.customer;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CustomerFilterService {
    private final CustomerFilterRepository repository;
    private final CustomerFilterMapper mapper;
    public CustomerFilterService(CustomerFilterRepository repository, CustomerFilterMapper mapper) {
        this.repository = repository; this.mapper = mapper;
    }
    public List<CustomerFilterResponse> list(String parentKey) {
        return repository.list(parentKey).stream().map(mapper::response).toList();
    }
}
