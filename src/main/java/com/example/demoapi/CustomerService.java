package com.example.demoapi;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class CustomerService {

    private final AtomicLong idSequence = new AtomicLong();
    private final Map<Long, CustomerResponse> customers = new ConcurrentHashMap<>();

    public synchronized CustomerResponse create(CustomerCreateRequest request) {
        boolean emailExists = customers.values().stream()
                .anyMatch(customer -> customer.email().equalsIgnoreCase(request.email()));
        if (emailExists) {
            throw new DuplicateCustomerEmailException(request.email());
        }

        long id = idSequence.incrementAndGet();
        CustomerResponse customer = new CustomerResponse(
                id,
                request.firstName(),
                request.lastName(),
                request.email()
        );
        customers.put(id, customer);
        return customer;
    }

    public Optional<CustomerResponse> findById(long id) {
        return Optional.ofNullable(customers.get(id));
    }

    public boolean deleteById(long id) {
        return customers.remove(id) != null;
    }
}
