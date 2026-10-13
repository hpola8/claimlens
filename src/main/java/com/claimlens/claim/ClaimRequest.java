package com.claimlens.claim;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** What an adjuster submits for review. */
public record ClaimRequest(
        @NotBlank @Pattern(regexp = "HOME|AUTO|DEVICE") String policyType,
        @NotBlank @Size(max = 120) String claimantName,
        @NotNull LocalDate incidentDate,
        @NotNull LocalDate reportedDate,
        @NotNull @DecimalMin("0.0") BigDecimal amount,
        @NotBlank @Size(min = 20, max = 4000) String description
) {
}
