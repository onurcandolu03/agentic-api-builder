package com.example.demoapi;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class CustomerService {

    private final AtomicLong idSequence = new AtomicLong();
    private final Map<Long, CustomerResponse> customers = new ConcurrentHashMap<>();

    public CustomerResponse create(CustomerCreateRequest request) {
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
}
