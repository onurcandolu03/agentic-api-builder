package com.example.demoapi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CustomerControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createsCustomer() throws Exception {
        mockMvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Onur",
                                  "lastName": "Can",
                                  "email": "onur@example.com"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/customers/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.firstName").value("Onur"))
                .andExpect(jsonPath("$.lastName").value("Can"))
                .andExpect(jsonPath("$.email").value("onur@example.com"));

        mockMvc.perform(get("/customers/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.firstName").value("Onur"))
                .andExpect(jsonPath("$.lastName").value("Can"))
                .andExpect(jsonPath("$.email").value("onur@example.com"));
    }

    @Test
    void returnsNotFoundForUnknownCustomer() throws Exception {
        mockMvc.perform(get("/customers/999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsInvalidCustomerId() throws Exception {
        mockMvc.perform(get("/customers/0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsBlankFirstName() throws Exception {
        assertBadRequest("""
                {
                  "firstName": "",
                  "lastName": "Can",
                  "email": "onur@example.com"
                }
                """);
    }

    @Test
    void rejectsShortLastName() throws Exception {
        assertBadRequest("""
                {
                  "firstName": "Onur",
                  "lastName": "C",
                  "email": "onur@example.com"
                }
                """);
    }

    @Test
    void rejectsInvalidEmail() throws Exception {
        assertBadRequest("""
                {
                  "firstName": "Onur",
                  "lastName": "Can",
                  "email": "invalid-email"
                }
                """);
    }

    @Test
    void preservesHelloEndpoint() throws Exception {
        mockMvc.perform(get("/hello"))
                .andExpect(status().isOk())
                .andExpect(content().string("Hello Onur!"));
    }

    private void assertBadRequest(String requestBody) throws Exception {
        mockMvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }
}
