package com.bank.engine.model;

import java.math.BigDecimal;

public record Account(long accountId, long customerId, BigDecimal balance, String status) {
    public boolean isActive() { return "ACTIVE".equals(status); }
}
