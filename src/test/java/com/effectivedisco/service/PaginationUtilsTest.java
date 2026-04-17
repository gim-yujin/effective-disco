package com.effectivedisco.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaginationUtilsTest {

    @Test
    void clampPageSize_withinRange_returnsAsIs() {
        assertThat(PaginationUtils.clampPageSize(20, 50)).isEqualTo(20);
    }

    @Test
    void clampPageSize_exceedsMax_clampsToMax() {
        assertThat(PaginationUtils.clampPageSize(100, 50)).isEqualTo(50);
    }

    @Test
    void clampPageSize_zero_clampsToOne() {
        assertThat(PaginationUtils.clampPageSize(0, 50)).isEqualTo(1);
    }

    @Test
    void clampPageSize_negative_clampsToOne() {
        assertThat(PaginationUtils.clampPageSize(-5, 50)).isEqualTo(1);
    }

    @Test
    void clampPageSize_exactlyMax_returnsMax() {
        assertThat(PaginationUtils.clampPageSize(50, 50)).isEqualTo(50);
    }

    @Test
    void clampPageSize_one_returnsOne() {
        assertThat(PaginationUtils.clampPageSize(1, 50)).isEqualTo(1);
    }
}
