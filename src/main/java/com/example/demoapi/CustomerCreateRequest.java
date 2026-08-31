package com.example.demoapi;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CustomerCreateRequest(
        @NotBlank @Size(min = 2) String firstName,
        @NotBlank @Size(min = 2) String lastName,
        @NotBlank @Email String email
) {
}
