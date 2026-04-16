package com.sboxmarket.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

class CreateLoadoutRequest {
    @NotBlank
    @Size(max = 100)
    String name

    @Size(max = 500)
    String description

    @Pattern(regexp = 'PUBLIC|PRIVATE', message = "visibility must be PUBLIC or PRIVATE")
    String visibility = "PUBLIC"
}
