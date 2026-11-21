package com.bank.engine.model;

/** PENDING exists only inside an open database transaction and is never committed. */
public enum TransactionStatus { PENDING, SUCCESS, FAILED, REJECTED }
