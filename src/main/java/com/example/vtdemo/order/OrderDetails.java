package com.example.vtdemo.order;

import com.example.vtdemo.external.ExternalClients;

/** 주문/결제/배송 정보를 합친 응답. */
public record OrderDetails(ExternalClients.OrderInfo order,
                           ExternalClients.PaymentInfo payment,
                           ExternalClients.ShippingInfo shipping) {
}
