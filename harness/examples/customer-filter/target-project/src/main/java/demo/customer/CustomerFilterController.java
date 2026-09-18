package demo.customer;

import java.util.List;
import org.springframework.web.bind.annotation.*;

/** API convention: GET lists a hierarchy; POST /customer-filters creates a filter.
 * POST uses the same response DTO and maps invalid input to 400, missing references to 404.
 */
@RestController
@RequestMapping("/customer-filters")
public class CustomerFilterController {
    private final CustomerFilterService service;
    public CustomerFilterController(CustomerFilterService service) { this.service = service; }
    @GetMapping
    public List<CustomerFilterResponse> list(@RequestParam String parentKey) { return service.list(parentKey); }
}
