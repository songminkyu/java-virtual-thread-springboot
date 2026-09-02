package com.example.vtdemo.order;

/** 주문 정보 (샘플이므로 JPA 엔티티 대신 record 로 단순화). */
public record Order(long id, String customer, int amount, String status) {
}
