package com.company.dlms.infrastructure.security;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Strips WWW-Authenticate from all responses so the browser never shows
 * its native HTTP Basic auth dialog.
 */
@Component
@Order(Integer.MIN_VALUE)
public class SuppressWwwAuthenticateFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().remove(HttpHeaders.WWW_AUTHENTICATE);
            return Mono.empty();
        });
        return chain.filter(exchange);
    }
}
