package com.example.demoapi;

public record CustomerResponse(
        long id,
        String firstName,
        String lastName,
        String email
) {
}
